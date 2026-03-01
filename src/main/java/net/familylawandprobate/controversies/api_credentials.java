package net.familylawandprobate.controversies;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * api_credentials
 *
 * Tenant-scoped API credentials for automation clients (n8n/OpenClaw).
 * Stored in:
 *   data/tenants/{tenantUuid}/settings/api_credentials.json
 */
public final class api_credentials {

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final SecureRandom RNG = new SecureRandom();
    private static final ConcurrentHashMap<String, ReentrantReadWriteLock> LOCKS = new ConcurrentHashMap<String, ReentrantReadWriteLock>();
    private static final Path PEPPER_PATH = Paths.get("data", "sec", "random_pepper.bin").toAbsolutePath();

    public static api_credentials defaultStore() {
        return new api_credentials();
    }

    public static final class CredentialRec {
        public final String credentialId;
        public final String label;
        public final String scope;
        public final String createdAt;
        public final String createdByUserUuid;
        public final String lastUsedAt;
        public final String lastUsedFromIp;
        public final boolean revoked;

        public CredentialRec(String credentialId,
                             String label,
                             String scope,
                             String createdAt,
                             String createdByUserUuid,
                             String lastUsedAt,
                             String lastUsedFromIp,
                             boolean revoked) {
            this.credentialId = safe(credentialId);
            this.label = safe(label);
            this.scope = safe(scope);
            this.createdAt = safe(createdAt);
            this.createdByUserUuid = safe(createdByUserUuid);
            this.lastUsedAt = safe(lastUsedAt);
            this.lastUsedFromIp = safe(lastUsedFromIp);
            this.revoked = revoked;
        }
    }

    public static final class GeneratedCredential {
        public final CredentialRec credential;
        public final String apiKey;
        public final String apiSecret;

        public GeneratedCredential(CredentialRec credential, String apiKey, String apiSecret) {
            this.credential = credential;
            this.apiKey = safe(apiKey);
            this.apiSecret = safe(apiSecret);
        }
    }

    public static final class VerificationResult {
        public final boolean ok;
        public final String tenantUuid;
        public final String credentialId;
        public final String credentialLabel;
        public final String scope;

        public VerificationResult(boolean ok,
                                  String tenantUuid,
                                  String credentialId,
                                  String credentialLabel,
                                  String scope) {
            this.ok = ok;
            this.tenantUuid = safe(tenantUuid);
            this.credentialId = safe(credentialId);
            this.credentialLabel = safe(credentialLabel);
            this.scope = safe(scope);
        }
    }

    private static final class FileRec {
        public String updated_at = "";
        public ArrayList<StoredRec> credentials = new ArrayList<StoredRec>();
    }

    private static final class StoredRec {
        public String credential_id = "";
        public String label = "";
        public String api_key = "";
        public String secret_hash = "";
        public String scope = "full_access";
        public String created_at = "";
        public String created_by_user_uuid = "";
        public String last_used_at = "";
        public String last_used_from_ip = "";
        public boolean revoked = false;
    }

    public List<CredentialRec> list(String tenantUuid) throws Exception {
        String tu = safeFileToken(tenantUuid);
        if (tu.isBlank()) return List.of();

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.readLock().lock();
        try {
            ensureLocked(tu);
            FileRec rec = readLocked(tu);
            ArrayList<CredentialRec> out = new ArrayList<CredentialRec>();
            for (StoredRec s : rec.credentials) {
                if (s == null) continue;
                out.add(toPublic(s));
            }
            out.sort(Comparator.comparing((CredentialRec r) -> safe(r.createdAt)).reversed());
            return out;
        } finally {
            lock.readLock().unlock();
        }
    }

    public GeneratedCredential create(String tenantUuid, String label, String createdByUserUuid) throws Exception {
        String tu = safeFileToken(tenantUuid);
        if (tu.isBlank()) throw new IllegalArgumentException("tenantUuid required");

        String normalizedLabel = normalizeLabel(label);
        if (normalizedLabel.isBlank()) normalizedLabel = "Automation Key";

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensureLocked(tu);
            FileRec rec = readLocked(tu);
            if (rec.credentials == null) rec.credentials = new ArrayList<StoredRec>();

            String now = Instant.now().toString();
            String credentialId = "cred_" + randomToken(9);
            String apiKey = "trs_" + randomToken(12);
            String apiSecret = "sec_" + randomToken(24);
            String secretHash = hashSecret(tu, apiKey, apiSecret);

            while (containsApiKey(rec, apiKey)) {
                apiKey = "trs_" + randomToken(12);
                secretHash = hashSecret(tu, apiKey, apiSecret);
            }

            StoredRec stored = new StoredRec();
            stored.credential_id = credentialId;
            stored.label = normalizedLabel;
            stored.api_key = apiKey;
            stored.secret_hash = secretHash;
            stored.scope = "full_access";
            stored.created_at = now;
            stored.created_by_user_uuid = safe(createdByUserUuid).trim();
            stored.last_used_at = "";
            stored.last_used_from_ip = "";
            stored.revoked = false;
            rec.credentials.add(stored);
            rec.updated_at = now;

            writeLocked(tu, rec);
            return new GeneratedCredential(toPublic(stored), apiKey, apiSecret);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean revoke(String tenantUuid, String credentialId) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String cid = safe(credentialId).trim();
        if (tu.isBlank() || cid.isBlank()) return false;

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensureLocked(tu);
            FileRec rec = readLocked(tu);
            if (rec.credentials == null || rec.credentials.isEmpty()) return false;

            boolean changed = false;
            for (StoredRec s : rec.credentials) {
                if (s == null) continue;
                if (!cid.equals(safe(s.credential_id).trim())) continue;
                if (!s.revoked) {
                    s.revoked = true;
                    changed = true;
                }
            }
            if (changed) {
                rec.updated_at = Instant.now().toString();
                writeLocked(tu, rec);
            }
            return changed;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public VerificationResult verify(String tenantUuid, String apiKey, String apiSecret, String clientIp) {
        String tu = safeFileToken(tenantUuid);
        String key = safe(apiKey).trim();
        String secret = safe(apiSecret).trim();
        if (tu.isBlank() || key.isBlank() || secret.isBlank()) {
            return new VerificationResult(false, tu, "", "", "");
        }

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensureLocked(tu);
            FileRec rec = readLocked(tu);
            if (rec.credentials == null || rec.credentials.isEmpty()) {
                return new VerificationResult(false, tu, "", "", "");
            }

            String now = Instant.now().toString();
            String providedHash = hashSecret(tu, key, secret);

            StoredRec matched = null;
            for (StoredRec s : rec.credentials) {
                if (s == null) continue;
                if (!safe(key).equals(safe(s.api_key))) continue;
                if (s.revoked) return new VerificationResult(false, tu, "", "", "");
                String expectedHash = safe(s.secret_hash);
                if (expectedHash.isBlank()) return new VerificationResult(false, tu, "", "", "");
                if (!constantTimeEquals(expectedHash, providedHash)) return new VerificationResult(false, tu, "", "", "");

                s.last_used_at = now;
                s.last_used_from_ip = safe(clientIp).trim();
                matched = s;
                break;
            }

            if (matched == null) return new VerificationResult(false, tu, "", "", "");

            rec.updated_at = now;
            writeLocked(tu, rec);
            return new VerificationResult(true, tu, safe(matched.credential_id), safe(matched.label), safe(matched.scope));
        } catch (Exception ignored) {
            return new VerificationResult(false, tu, "", "", "");
        } finally {
            lock.writeLock().unlock();
        }
    }

    private static ReentrantReadWriteLock lockFor(String tenantUuid) {
        return LOCKS.computeIfAbsent(tenantUuid, k -> new ReentrantReadWriteLock());
    }

    private static Path credentialsPath(String tenantUuid) {
        return Paths.get("data", "tenants", tenantUuid, "settings", "api_credentials.json").toAbsolutePath();
    }

    private static void ensureLocked(String tenantUuid) throws Exception {
        Path p = credentialsPath(tenantUuid);
        Files.createDirectories(p.getParent());
        if (Files.exists(p)) return;

        FileRec seed = new FileRec();
        seed.updated_at = Instant.now().toString();
        writeJsonAtomic(p, seed);
    }

    private static FileRec readLocked(String tenantUuid) throws Exception {
        Path p = credentialsPath(tenantUuid);
        if (!Files.exists(p)) return new FileRec();
        String raw = Files.readString(p, StandardCharsets.UTF_8);
        if (safe(raw).trim().isBlank()) return new FileRec();
        FileRec rec = JSON.readValue(raw, FileRec.class);
        if (rec == null) rec = new FileRec();
        if (rec.credentials == null) rec.credentials = new ArrayList<StoredRec>();
        return rec;
    }

    private static void writeLocked(String tenantUuid, FileRec rec) throws Exception {
        Path p = credentialsPath(tenantUuid);
        writeJsonAtomic(p, rec == null ? new FileRec() : rec);
    }

    private static void writeJsonAtomic(Path p, Object payload) throws Exception {
        Files.createDirectories(p.getParent());
        Path tmp = p.resolveSibling(p.getFileName().toString() + ".tmp");
        String json = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
        Files.writeString(
                tmp,
                safe(json),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        );
        try {
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception ignored) {
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static boolean containsApiKey(FileRec rec, String apiKey) {
        if (rec == null || rec.credentials == null || rec.credentials.isEmpty()) return false;
        for (StoredRec s : rec.credentials) {
            if (s == null) continue;
            if (safe(apiKey).equals(safe(s.api_key))) return true;
        }
        return false;
    }

    private static CredentialRec toPublic(StoredRec s) {
        if (s == null) return new CredentialRec("", "", "", "", "", "", "", false);
        return new CredentialRec(
                safe(s.credential_id),
                safe(s.label),
                safe(s.scope),
                safe(s.created_at),
                safe(s.created_by_user_uuid),
                safe(s.last_used_at),
                safe(s.last_used_from_ip),
                s.revoked
        );
    }

    private static String normalizeLabel(String label) {
        String v = safe(label).trim();
        if (v.length() > 120) v = v.substring(0, 120).trim();
        return v;
    }

    private static String hashSecret(String tenantUuid, String apiKey, String apiSecret) throws Exception {
        MessageDigest d = MessageDigest.getInstance("SHA-256");
        d.update(readPepper());
        d.update((byte) ':');
        d.update(safe(tenantUuid).getBytes(StandardCharsets.UTF_8));
        d.update((byte) ':');
        d.update(safe(apiKey).getBytes(StandardCharsets.UTF_8));
        d.update((byte) ':');
        d.update(safe(apiSecret).getBytes(StandardCharsets.UTF_8));
        byte[] out = d.digest();
        return Base64.getEncoder().encodeToString(out);
    }

    private static byte[] readPepper() throws Exception {
        if (!Files.exists(PEPPER_PATH)) {
            throw new IllegalStateException("Missing data/sec/random_pepper.bin");
        }
        return Files.readAllBytes(PEPPER_PATH);
    }

    private static String randomToken(int bytes) {
        int size = Math.max(8, bytes);
        byte[] out = new byte[size];
        RNG.nextBytes(out);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(out).toLowerCase(Locale.ROOT);
    }

    private static boolean constantTimeEquals(String a, String b) {
        byte[] x = safe(a).getBytes(StandardCharsets.UTF_8);
        byte[] y = safe(b).getBytes(StandardCharsets.UTF_8);
        if (x.length != y.length) return false;
        int r = 0;
        for (int i = 0; i < x.length; i++) r |= (x[i] ^ y[i]);
        return r == 0;
    }

    private static String safeFileToken(String s) {
        String t = safe(s).trim();
        if (t.isBlank()) return "";
        return t.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
