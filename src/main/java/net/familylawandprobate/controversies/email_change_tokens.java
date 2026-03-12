package net.familylawandprobate.controversies;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * email_change_tokens
 *
 * Tenant-scoped email verification tokens stored in:
 *   data/tenants/{tenantUuid}/sync/email_change_tokens.xml
 *
 * Verification tokens are random URL-safe values; only SHA-256 hashes are
 * stored at rest.
 */
public final class email_change_tokens {

    private static final ConcurrentHashMap<String, ReentrantReadWriteLock> LOCKS =
            new ConcurrentHashMap<String, ReentrantReadWriteLock>();
    private static final SecureRandom RNG = new SecureRandom();
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(20);
    private static final Duration HISTORY_RETENTION = Duration.ofDays(7);

    public static email_change_tokens defaultStore() {
        return new email_change_tokens();
    }

    public static final class IssueResult {
        public final String tokenUuid;
        public final String token;
        public final String userUuid;
        public final String newEmailAddress;
        public final String expiresAt;

        IssueResult(String tokenUuid, String token, String userUuid, String newEmailAddress, String expiresAt) {
            this.tokenUuid = safe(tokenUuid).trim();
            this.token = safe(token).trim();
            this.userUuid = safe(userUuid).trim();
            this.newEmailAddress = normalizeEmail(newEmailAddress);
            this.expiresAt = safe(expiresAt).trim();
        }
    }

    public static final class ConsumeResult {
        public final boolean ok;
        public final String userUuid;
        public final String newEmailAddress;
        public final String reason;

        ConsumeResult(boolean ok, String userUuid, String newEmailAddress, String reason) {
            this.ok = ok;
            this.userUuid = safe(userUuid).trim();
            this.newEmailAddress = normalizeEmail(newEmailAddress);
            this.reason = safe(reason).trim();
        }
    }

    private static final class TokenRec {
        final String uuid;
        final String userUuid;
        final String newEmailAddress;
        final String tokenHash;
        final String createdAt;
        final String expiresAt;
        final String consumedAt;
        final String requestIp;
        final String consumeIp;

        TokenRec(String uuid,
                 String userUuid,
                 String newEmailAddress,
                 String tokenHash,
                 String createdAt,
                 String expiresAt,
                 String consumedAt,
                 String requestIp,
                 String consumeIp) {
            this.uuid = safeFileToken(uuid).isBlank() ? UUID.randomUUID().toString() : safeFileToken(uuid);
            this.userUuid = safe(userUuid).trim();
            this.newEmailAddress = normalizeEmail(newEmailAddress);
            this.tokenHash = safe(tokenHash).trim().toLowerCase(Locale.ROOT);
            this.createdAt = safe(createdAt).trim();
            this.expiresAt = safe(expiresAt).trim();
            this.consumedAt = safe(consumedAt).trim();
            this.requestIp = safe(requestIp).trim();
            this.consumeIp = safe(consumeIp).trim();
        }

        TokenRec withConsumed(String whenIso, String fromIp) {
            return new TokenRec(
                    this.uuid,
                    this.userUuid,
                    this.newEmailAddress,
                    this.tokenHash,
                    this.createdAt,
                    this.expiresAt,
                    whenIso,
                    this.requestIp,
                    fromIp
            );
        }
    }

    public IssueResult issue(String tenantUuid,
                             String userUuid,
                             String newEmailAddress,
                             String requestIp,
                             Duration ttl) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String uu = safe(userUuid).trim();
        String email = normalizeEmail(newEmailAddress);
        if (tu.isBlank()) throw new IllegalArgumentException("tenantUuid required");
        if (uu.isBlank()) throw new IllegalArgumentException("userUuid required");
        if (email.isBlank()) throw new IllegalArgumentException("newEmailAddress required");

        Duration effectiveTtl = (ttl == null || ttl.isZero() || ttl.isNegative()) ? DEFAULT_TTL : ttl;
        Instant now = app_clock.now();
        String nowIso = now.toString();
        String expiresAt = now.plus(effectiveTtl).toString();

        String rawToken = randomTokenUrlSafe(32);
        String tokenHash = sha256Hex(rawToken);
        String tokenUuid = UUID.randomUUID().toString();

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensureLocked(tu);
            List<TokenRec> all = readLocked(tu);
            pruneOldRecords(all, now);
            consumeActiveTokensForUser(all, uu, nowIso, "superseded");
            all.add(new TokenRec(
                    tokenUuid,
                    uu,
                    email,
                    tokenHash,
                    nowIso,
                    expiresAt,
                    "",
                    safe(requestIp).trim(),
                    ""
            ));
            writeLocked(tu, all);
        } finally {
            lock.writeLock().unlock();
        }

        return new IssueResult(tokenUuid, rawToken, uu, email, expiresAt);
    }

    public ConsumeResult consume(String tenantUuid,
                                 String userUuid,
                                 String newEmailAddress,
                                 String rawToken,
                                 String consumeIp) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String uu = safe(userUuid).trim();
        String email = normalizeEmail(newEmailAddress);
        String token = safe(rawToken).trim();
        if (tu.isBlank() || uu.isBlank() || email.isBlank() || token.isBlank()) {
            return new ConsumeResult(false, "", "", "invalid");
        }

        String tokenHash = sha256Hex(token);
        Instant now = app_clock.now();
        String nowIso = now.toString();

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensureLocked(tu);
            List<TokenRec> all = readLocked(tu);

            boolean changed = pruneOldRecords(all, now);
            int matchIdx = -1;
            for (int i = all.size() - 1; i >= 0; i--) {
                TokenRec r = all.get(i);
                if (r == null) continue;
                if (!uu.equals(r.userUuid)) continue;
                if (!email.equals(r.newEmailAddress)) continue;
                if (constantTimeEquals(r.tokenHash, tokenHash)) {
                    matchIdx = i;
                    break;
                }
            }

            if (matchIdx < 0) {
                if (changed) writeLocked(tu, all);
                return new ConsumeResult(false, "", "", "invalid");
            }

            TokenRec m = all.get(matchIdx);
            if (!m.consumedAt.isBlank()) {
                if (changed) writeLocked(tu, all);
                return new ConsumeResult(false, "", "", "consumed");
            }
            if (isExpired(m.expiresAt, now)) {
                all.set(matchIdx, m.withConsumed(nowIso, safe(consumeIp).trim()));
                writeLocked(tu, all);
                return new ConsumeResult(false, "", "", "expired");
            }

            all.set(matchIdx, m.withConsumed(nowIso, safe(consumeIp).trim()));
            consumeActiveTokensForUser(all, m.userUuid, nowIso, "superseded");
            writeLocked(tu, all);
            return new ConsumeResult(true, m.userUuid, m.newEmailAddress, "ok");
        } finally {
            lock.writeLock().unlock();
        }
    }

    private static ReentrantReadWriteLock lockFor(String tenantUuid) {
        return LOCKS.computeIfAbsent(tenantUuid, k -> new ReentrantReadWriteLock());
    }

    private static Path tokensPath(String tenantUuid) {
        return Paths.get("data", "tenants", safeFileToken(tenantUuid), "sync", "email_change_tokens.xml").toAbsolutePath();
    }

    private static void ensureLocked(String tenantUuid) throws Exception {
        Path p = tokensPath(tenantUuid);
        Files.createDirectories(p.getParent());
        if (!Files.exists(p)) writeLocked(tenantUuid, List.of());
    }

    private static List<TokenRec> readLocked(String tenantUuid) throws Exception {
        Path p = tokensPath(tenantUuid);
        if (!Files.exists(p)) return new ArrayList<TokenRec>();

        Document d = parseXml(p);
        Element root = d == null ? null : d.getDocumentElement();
        if (root == null) return new ArrayList<TokenRec>();

        ArrayList<TokenRec> out = new ArrayList<TokenRec>();
        NodeList list = root.getElementsByTagName("token");
        for (int i = 0; i < list.getLength(); i++) {
            Node n = list.item(i);
            if (!(n instanceof Element e)) continue;

            TokenRec r = new TokenRec(
                    text(e, "uuid"),
                    text(e, "user_uuid"),
                    text(e, "new_email_address"),
                    text(e, "token_hash"),
                    text(e, "created_at"),
                    text(e, "expires_at"),
                    text(e, "consumed_at"),
                    text(e, "request_ip"),
                    text(e, "consume_ip")
            );
            if (r.userUuid.isBlank() || r.newEmailAddress.isBlank() || r.tokenHash.isBlank()) continue;
            out.add(r);
        }
        return out;
    }

    private static void writeLocked(String tenantUuid, List<TokenRec> all) throws Exception {
        Path p = tokensPath(tenantUuid);
        Files.createDirectories(p.getParent());

        String now = app_clock.now().toString();
        StringBuilder sb = new StringBuilder(4096);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<email_change_tokens updated=\"").append(xmlAttr(now)).append("\">\n");
        List<TokenRec> records = all == null ? List.of() : all;
        for (int i = 0; i < records.size(); i++) {
            TokenRec r = records.get(i);
            if (r == null) continue;
            sb.append("  <token>\n");
            sb.append("    <uuid>").append(xmlText(r.uuid)).append("</uuid>\n");
            sb.append("    <user_uuid>").append(xmlText(r.userUuid)).append("</user_uuid>\n");
            sb.append("    <new_email_address>").append(xmlText(r.newEmailAddress)).append("</new_email_address>\n");
            sb.append("    <token_hash>").append(xmlText(r.tokenHash)).append("</token_hash>\n");
            sb.append("    <created_at>").append(xmlText(r.createdAt)).append("</created_at>\n");
            sb.append("    <expires_at>").append(xmlText(r.expiresAt)).append("</expires_at>\n");
            sb.append("    <consumed_at>").append(xmlText(r.consumedAt)).append("</consumed_at>\n");
            sb.append("    <request_ip>").append(xmlText(r.requestIp)).append("</request_ip>\n");
            sb.append("    <consume_ip>").append(xmlText(r.consumeIp)).append("</consume_ip>\n");
            sb.append("  </token>\n");
        }
        sb.append("</email_change_tokens>\n");

        writeAtomic(p, sb.toString());
    }

    private static boolean pruneOldRecords(List<TokenRec> all, Instant now) {
        if (all == null || all.isEmpty()) return false;
        Instant cutoff = now.minus(HISTORY_RETENTION);
        boolean changed = false;
        for (int i = all.size() - 1; i >= 0; i--) {
            TokenRec r = all.get(i);
            if (r == null) {
                all.remove(i);
                changed = true;
                continue;
            }

            Instant consumedAt = parseInstant(r.consumedAt);
            if (consumedAt != null && consumedAt.isBefore(cutoff)) {
                all.remove(i);
                changed = true;
                continue;
            }

            Instant expiresAt = parseInstant(r.expiresAt);
            if (consumedAt == null && expiresAt != null && expiresAt.isBefore(cutoff)) {
                all.remove(i);
                changed = true;
            }
        }
        return changed;
    }

    private static void consumeActiveTokensForUser(List<TokenRec> all, String userUuid, String whenIso, String consumeIp) {
        if (all == null || all.isEmpty()) return;
        String uu = safe(userUuid).trim();
        if (uu.isBlank()) return;
        for (int i = 0; i < all.size(); i++) {
            TokenRec r = all.get(i);
            if (r == null) continue;
            if (!uu.equals(r.userUuid)) continue;
            if (!r.consumedAt.isBlank()) continue;
            all.set(i, r.withConsumed(whenIso, consumeIp));
        }
    }

    private static boolean isExpired(String expiresAtIso, Instant now) {
        Instant exp = parseInstant(expiresAtIso);
        if (exp == null) return true;
        return !exp.isAfter(now);
    }

    private static String randomTokenUrlSafe(int bytes) {
        int size = Math.max(16, bytes);
        byte[] raw = new byte[size];
        RNG.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(safe(value).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (int i = 0; i < digest.length; i++) {
                sb.append(String.format(Locale.ROOT, "%02x", digest[i]));
            }
            return sb.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("sha256 unavailable", ex);
        }
    }

    private static Document parseXml(Path p) throws Exception {
        if (p == null || !Files.exists(p)) return null;
        DocumentBuilder b = secureBuilder();
        try (InputStream in = Files.newInputStream(p)) {
            Document d = b.parse(in);
            d.getDocumentElement().normalize();
            return d;
        }
    }

    private static DocumentBuilder secureBuilder() throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false);
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);

        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

        DocumentBuilder b = f.newDocumentBuilder();
        b.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
        return b;
    }

    private static void writeAtomic(Path target, String content) throws Exception {
        Path dir = target.getParent();
        if (dir != null) Files.createDirectories(dir);

        Path tmp = target.resolveSibling(target.getFileName().toString() + ".tmp");
        Files.writeString(tmp, safe(content), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static String text(Element parent, String tag) {
        if (parent == null || tag == null || tag.isBlank()) return "";
        NodeList list = parent.getElementsByTagName(tag);
        if (list == null || list.getLength() == 0) return "";
        Node n = list.item(0);
        return n == null ? "" : safe(n.getTextContent()).trim();
    }

    private static Instant parseInstant(String s) {
        try {
            String v = safe(s).trim();
            if (v.isBlank()) return null;
            return Instant.parse(v);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        byte[] x = safe(a).getBytes(StandardCharsets.UTF_8);
        byte[] y = safe(b).getBytes(StandardCharsets.UTF_8);
        if (x.length != y.length) return false;
        int r = 0;
        for (int i = 0; i < x.length; i++) r |= (x[i] ^ y[i]);
        return r == 0;
    }

    private static String normalizeEmail(String email) {
        return safe(email).trim().toLowerCase(Locale.ROOT);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String safeFileToken(String s) {
        String t = safe(s).trim();
        if (t.isBlank()) return "";
        return t.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String xmlAttr(String s) {
        return safe(s)
                .replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("'", "&apos;");
    }

    private static String xmlText(String s) {
        return safe(s)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
