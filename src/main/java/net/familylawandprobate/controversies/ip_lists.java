package net.familylawandprobate.controversies;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.*;

import java.io.BufferedWriter;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * ip_lists
 * -------
 * Ensures and manages:
 *   data/ip_lists/whitelist.xml
 *   data/ip_lists/blacklist.xml
 *   data/ip_lists/temporary_bans.xml
 *
 * Matching supports:
 * - IPv4 and IPv6
 * - single IP or CIDR (e.g. 1.2.3.4, 1.2.3.0/24, 2001:db8::/32)
 *
 * Precedence:
 * - Never block localhost/loopback (always ALLOW)
 * - whitelist overrides blacklist and temp bans (ALLOW)
 * - temp ban denies (DENY_TEMP_BAN)
 * - blacklist denies (DENY_BLACKLIST)
 * - otherwise allow
 */
public final class ip_lists {

    private static final Logger LOG = Logger.getLogger(ip_lists.class.getName());

    public enum Decision {
        ALLOW,
        DENY_BLACKLIST,
        DENY_TEMP_BAN,
        DENY_INVALID_IP
    }

    private final Path dir;
    private final Path whitelistFile;
    private final Path blacklistFile;
    private final Path tempBansFile;

    // cached compiled nets
    private volatile long wlMtime = -1L;
    private volatile long blMtime = -1L;
    private volatile long tbMtime = -1L;

    private volatile List<IpNet> whitelist = List.of();
    private volatile List<IpNet> blacklist = List.of();
    private volatile List<TempBan> tempBans = List.of();

    // prevent excessive disk writes when pruning
    private volatile long lastPruneWriteEpochSec = 0L;

    // -------------------------
    // Construction / defaults
    // -------------------------

    public ip_lists(Path dir) {
        this.dir = Objects.requireNonNull(dir, "dir");
        this.whitelistFile = dir.resolve("whitelist.xml");
        this.blacklistFile = dir.resolve("blacklist.xml");
        this.tempBansFile = dir.resolve("temporary_bans.xml");
    }

    /** Default location: {user.dir}/data/ip_lists */
    public static ip_lists defaultStore() {
        Path base = Paths.get(System.getProperty("user.dir"), "data", "sec", "ip_lists");
        ip_lists store = new ip_lists(base);
        store.ensure();
        return store;
    }

    // -------------------------
    // Ensure files exist
    // -------------------------

    public synchronized void ensure() {
        try {
            Files.createDirectories(dir);

            if (!Files.exists(whitelistFile)) writeWhitelist(List.of());
            if (!Files.exists(blacklistFile)) writeBlacklist(List.of());
            if (!Files.exists(tempBansFile)) writeTempBans(List.of());

            // warm load
            reloadIfStale();
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "ip_lists ensure() failed: " + e.getMessage(), e);
        }
    }

    public Path getDir() { return dir; }

    // -------------------------
    // Public API (read)
    // -------------------------

    /** Returns ALLOW / DENY_* based on current lists. Never blocks loopback. */
    public Decision check(String ipText) {
        if (ipText == null) return Decision.DENY_INVALID_IP;

        // "Origin: null" etc sometimes get piped incorrectly—this is for IP checking only.
        // Treat anything non-IP as invalid (except localhost tokens).
        if (isLocalhostToken(ipText)) return Decision.ALLOW;

        InetAddress addr = parseAddress(ipText);
        if (addr == null) return Decision.DENY_INVALID_IP;

        // Never block localhost/loopback
        if (addr.isLoopbackAddress()) return Decision.ALLOW;

        reloadIfStale();
        pruneExpiredTempBansIfNeeded(false);

        // whitelist override
        if (matchesAny(addr, whitelist)) return Decision.ALLOW;

        // temp bans
        TempBan hitBan = matchTempBan(addr);
        if (hitBan != null) return Decision.DENY_TEMP_BAN;

        // blacklist
        if (matchesAny(addr, blacklist)) return Decision.DENY_BLACKLIST;

        return Decision.ALLOW;
    }

    /** Convenience: whitelist override already included. */
    public boolean isAllowed(String ipText) {
        return check(ipText) == Decision.ALLOW;
    }

    /** For logging/debugging */
    public Optional<Instant> getTempBanExpiryIfAny(String ipText) {
        InetAddress addr = parseAddress(ipText);
        if (addr == null) return Optional.empty();
        if (addr.isLoopbackAddress()) return Optional.empty();
        reloadIfStale();
        TempBan b = matchTempBan(addr);
        return (b == null) ? Optional.empty() : Optional.of(Instant.ofEpochSecond(b.untilEpochSec));
    }

    /** Snapshot of normalized entries (as stored/canonicalized). */
    public synchronized List<String> listWhitelist() {
        reloadIfStale();
        return netsToStrings(whitelist);
    }

    public synchronized List<String> listBlacklist() {
        reloadIfStale();
        return netsToStrings(blacklist);
    }

    public synchronized List<String> listTempBans() {
        reloadIfStale();
        List<String> out = new ArrayList<>();
        for (TempBan b : tempBans) {
            out.add(b.net.original + " until=" + b.untilEpochSec + (b.reason == null ? "" : " reason=" + b.reason));
        }
        return out;
    }

    // -------------------------
    // Public API (mutations)
    // -------------------------

    /** Adds to whitelist (single IP or CIDR). Whitelist overrides everything except localhost (which is always allowed anyway). */
    public synchronized void addWhitelist(String ipOrCidr) {
        ensure();
        IpNet net = parseNet(ipOrCidr);
        if (net == null) return;
        if (isLoopbackNet(net)) return; // no need; but safe

        List<IpNet> wl = new ArrayList<>(loadWhitelistRaw());
        if (!containsNet(wl, net)) wl.add(net);

        wl.sort(IpNet::compareStable);
        writeWhitelist(wl);
        reloadIfStale(true);
    }

    public synchronized void removeWhitelist(String ipOrCidr) {
        ensure();
        IpNet net = parseNet(ipOrCidr);
        if (net == null) return;

        List<IpNet> wl = new ArrayList<>(loadWhitelistRaw());
        wl.removeIf(n -> n.equalsNet(net));
        writeWhitelist(wl);
        reloadIfStale(true);
    }

    /** Adds to blacklist (single IP or CIDR). Loopback is never blocked even if listed. */
    public synchronized void addBlacklist(String ipOrCidr) {
        ensure();
        IpNet net = parseNet(ipOrCidr);
        if (net == null) return;

        List<IpNet> bl = new ArrayList<>(loadBlacklistRaw());
        if (!containsNet(bl, net)) bl.add(net);

        bl.sort(IpNet::compareStable);
        writeBlacklist(bl);
        reloadIfStale(true);
    }

    public synchronized void removeBlacklist(String ipOrCidr) {
        ensure();
        IpNet net = parseNet(ipOrCidr);
        if (net == null) return;

        List<IpNet> bl = new ArrayList<>(loadBlacklistRaw());
        bl.removeIf(n -> n.equalsNet(net));
        writeBlacklist(bl);
        reloadIfStale(true);
    }

    /**
     * Temporarily ban an IP or CIDR.
     * @param durationSeconds duration from now
     * @param reason optional, stored in XML
     */
    public synchronized void banTemporary(String ipOrCidr, long durationSeconds, String reason) {
        ensure();
        IpNet net = parseNet(ipOrCidr);
        if (net == null) return;
        if (isLoopbackNet(net)) return; // never block localhost

        long until = app_clock.now().getEpochSecond() + Math.max(1, durationSeconds);

        List<TempBan> bans = new ArrayList<>(loadTempBansRaw());
        // replace if exists
        boolean replaced = false;
        for (int i = 0; i < bans.size(); i++) {
            if (bans.get(i).net.equalsNet(net)) {
                bans.set(i, new TempBan(net, until, reason));
                replaced = true;
                break;
            }
        }
        if (!replaced) bans.add(new TempBan(net, until, reason));

        bans.sort(TempBan::compareStable);
        writeTempBans(bans);
        reloadIfStale(true);
    }

    public synchronized void unbanTemporary(String ipOrCidr) {
        ensure();
        IpNet net = parseNet(ipOrCidr);
        if (net == null) return;

        List<TempBan> bans = new ArrayList<>(loadTempBansRaw());
        bans.removeIf(b -> b.net.equalsNet(net));
        writeTempBans(bans);
        reloadIfStale(true);
    }

    /** Prune expired bans and persist. Safe to call occasionally (e.g., on admin page). */
    public synchronized void pruneExpiredTempBansNow() {
        ensure();
        reloadIfStale();
        pruneExpiredTempBansIfNeeded(true);
    }

    // -------------------------
    // Internal: reload + parse
    // -------------------------

    private void reloadIfStale() { reloadIfStale(false); }

    private synchronized void reloadIfStale(boolean force) {
        try {
            long wl = Files.getLastModifiedTime(whitelistFile).toMillis();
            long bl = Files.getLastModifiedTime(blacklistFile).toMillis();
            long tb = Files.getLastModifiedTime(tempBansFile).toMillis();

            if (force || wl != wlMtime) {
                whitelist = compileNets(readSimpleListXml(whitelistFile, "whitelist"));
                wlMtime = wl;
            }
            if (force || bl != blMtime) {
                blacklist = compileNets(readSimpleListXml(blacklistFile, "blacklist"));
                blMtime = bl;
            }
            if (force || tb != tbMtime) {
                tempBans = compileTempBans(readTempBansXml(tempBansFile));
                tbMtime = tb;
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "ip_lists reload failed: " + e.getMessage(), e);
        }
    }

    private List<IpNet> loadWhitelistRaw() { return compileNets(readSimpleListXml(whitelistFile, "whitelist")); }
    private List<IpNet> loadBlacklistRaw() { return compileNets(readSimpleListXml(blacklistFile, "blacklist")); }
    private List<TempBan> loadTempBansRaw() { return compileTempBans(readTempBansXml(tempBansFile)); }

    // -------------------------
    // Matching
    // -------------------------

    private static boolean matchesAny(InetAddress addr, List<IpNet> nets) {
        for (IpNet n : nets) {
            if (n.matches(addr)) return true;
        }
        return false;
    }

    private TempBan matchTempBan(InetAddress addr) {
        long now = app_clock.now().getEpochSecond();
        for (TempBan b : tempBans) {
            if (b.untilEpochSec <= now) continue;
            if (b.net.matches(addr)) return b;
        }
        return null;
    }

    private static boolean isLocalhostToken(String s) {
        String t = s.trim().toLowerCase(Locale.ROOT);
        return t.equals("localhost") || t.equals("127.0.0.1") || t.equals("::1") || t.equals("0:0:0:0:0:0:0:1");
    }

    private static InetAddress parseAddress(String ipText) {
        try {
            return InetAddress.getByName(ipText.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isLoopbackNet(IpNet net) {
        // If the base address is loopback, treat as loopback net.
        // Additionally, if net is IPv4 /8 and base is 127.x.x.x, we consider loopback.
        try {
            InetAddress a = InetAddress.getByAddress(net.addrBytes);
            return a.isLoopbackAddress();
        } catch (Exception ignored) {
            return false;
        }
    }

    // -------------------------
    // Prune temp bans
    // -------------------------

    private synchronized void pruneExpiredTempBansIfNeeded(boolean forceWrite) {
        long now = app_clock.now().getEpochSecond();
        List<TempBan> cur = new ArrayList<>(tempBans);

        boolean changed = cur.removeIf(b -> b.untilEpochSec <= now);

        // Only write if changed and either forced or we haven't written recently
        if (changed) {
            if (forceWrite || (now - lastPruneWriteEpochSec) > 30) {
                writeTempBans(cur);
                lastPruneWriteEpochSec = now;
                reloadIfStale(true);
            } else {
                // update memory only
                tempBans = List.copyOf(cur);
            }
        }
    }

    // -------------------------
    // XML parsing / writing
    // -------------------------

    private static DocumentBuilder secureBuilder() throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false);
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);

        // XXE hardening
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

        DocumentBuilder b = f.newDocumentBuilder();
        b.setEntityResolver((publicId, systemId) -> new org.xml.sax.InputSource(new java.io.StringReader("")));
        return b;
    }

    private static List<String> readSimpleListXml(Path file, String expectedRoot) {
        try (InputStream in = Files.newInputStream(file)) {
            DocumentBuilder b = secureBuilder();
            Document d = b.parse(in);
            d.getDocumentElement().normalize();

            Element root = d.getDocumentElement();
            if (root == null || !expectedRoot.equals(root.getTagName())) return List.of();

            List<String> out = new ArrayList<>();
            NodeList entries = root.getElementsByTagName("entry");
            for (int i = 0; i < entries.getLength(); i++) {
                Node n = entries.item(i);
                if (!(n instanceof Element)) continue;
                Element el = (Element) n;

                String v = el.getAttribute("value");
                if (v != null && !v.isBlank()) out.add(v.trim());
            }
            return out;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed reading " + file + ": " + e.getMessage(), e);
            return List.of();
        }
    }

    private static List<TempBanRaw> readTempBansXml(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            DocumentBuilder b = secureBuilder();
            Document d = b.parse(in);
            d.getDocumentElement().normalize();

            Element root = d.getDocumentElement();
            if (root == null || !"temporaryBans".equals(root.getTagName())) return List.of();

            List<TempBanRaw> out = new ArrayList<>();
            NodeList bans = root.getElementsByTagName("ban");
            for (int i = 0; i < bans.getLength(); i++) {
                Node n = bans.item(i);
                if (!(n instanceof Element)) continue;
                Element el = (Element) n;

                String v = el.getAttribute("value");
                String until = el.getAttribute("untilEpochSec");
                String reason = el.getAttribute("reason");
                if (v == null || v.isBlank()) continue;

                long untilSec = 0L;
                try { untilSec = Long.parseLong(until.trim()); } catch (Exception ignored) {}

                out.add(new TempBanRaw(v.trim(), untilSec, (reason == null || reason.isBlank()) ? null : reason));
            }
            return out;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed reading " + file + ": " + e.getMessage(), e);
            return List.of();
        }
    }

    private synchronized void writeWhitelist(List<IpNet> nets) {
        writeSimpleListXml(whitelistFile, "whitelist", netsToStrings(nets));
    }

    private synchronized void writeBlacklist(List<IpNet> nets) {
        writeSimpleListXml(blacklistFile, "blacklist", netsToStrings(nets));
    }

    private synchronized void writeTempBans(List<TempBan> bans) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<temporaryBans version=\"1\" updatedEpochSec=\"").append(app_clock.now().getEpochSecond()).append("\">\n");

        // never persist loopback bans
        long now = app_clock.now().getEpochSecond();
        for (TempBan b : bans) {
            if (b.untilEpochSec <= now) continue;
            if (isLoopbackNet(b.net)) continue;

            sb.append("  <ban value=\"").append(xa(b.net.original)).append("\"");
            sb.append(" untilEpochSec=\"").append(b.untilEpochSec).append("\"");
            if (b.reason != null && !b.reason.isBlank()) sb.append(" reason=\"").append(xa(b.reason)).append("\"");
            sb.append(" />\n");
        }
        sb.append("</temporaryBans>\n");

        atomicWrite(tempBansFile, sb.toString());
    }

    private synchronized void writeSimpleListXml(Path file, String root, List<String> entries) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<").append(root).append(" version=\"1\" updatedEpochSec=\"").append(app_clock.now().getEpochSecond()).append("\">\n");

        for (String e : entries) {
            if (e == null || e.isBlank()) continue;

            // never persist loopback blocks (we also ignore at runtime)
            IpNet net = parseNet(e);
            if (net != null && isLoopbackNet(net)) continue;

            sb.append("  <entry value=\"").append(xa(e.trim())).append("\" />\n");
        }
        sb.append("</").append(root).append(">\n");

        atomicWrite(file, sb.toString());
    }

    private static void atomicWrite(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");

            try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                w.write(content);
            }

            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "atomicWrite failed for " + file + ": " + e.getMessage(), e);
        }
    }

    private static String xa(String s) {
        if (s == null) return "";
        return s.replace("&","&amp;")
                .replace("<","&lt;")
                .replace(">","&gt;")
                .replace("\"","&quot;")
                .replace("'","&#39;");
    }

    // -------------------------
    // Net compilation
    // -------------------------

    private static List<IpNet> compileNets(List<String> raw) {
        List<IpNet> out = new ArrayList<>();
        for (String s : raw) {
            IpNet n = parseNet(s);
            if (n != null && !containsNet(out, n)) out.add(n);
        }
        out.sort(IpNet::compareStable);
        return List.copyOf(out);
    }

    private static List<TempBan> compileTempBans(List<TempBanRaw> raw) {
        long now = app_clock.now().getEpochSecond();
        List<TempBan> out = new ArrayList<>();
        for (TempBanRaw r : raw) {
            IpNet n = parseNet(r.value);
            if (n == null) continue;
            if (isLoopbackNet(n)) continue;          // never block localhost
            if (r.untilEpochSec <= now) continue;    // expired
            out.add(new TempBan(n, r.untilEpochSec, r.reason));
        }
        out.sort(TempBan::compareStable);
        return List.copyOf(out);
    }

    private static boolean containsNet(List<IpNet> list, IpNet n) {
        for (IpNet x : list) if (x.equalsNet(n)) return true;
        return false;
    }

    private static List<String> netsToStrings(List<IpNet> list) {
        List<String> out = new ArrayList<>();
        for (IpNet n : list) out.add(n.original);
        return out;
    }

    /**
     * Parse "ip" or "ip/prefix".
     * Returns null if invalid.
     */
    private static IpNet parseNet(String ipOrCidr) {
        if (ipOrCidr == null) return null;
        String t = ipOrCidr.trim();
        if (t.isEmpty()) return null;

        try {
            String ipPart = t;
            int prefixBits = -1;

            int slash = t.indexOf('/');
            if (slash >= 0) {
                ipPart = t.substring(0, slash).trim();
                String p = t.substring(slash + 1).trim();
                prefixBits = Integer.parseInt(p);
            }

            InetAddress addr = InetAddress.getByName(ipPart);
            byte[] bytes = addr.getAddress();
            int bits = bytes.length * 8;

            if (prefixBits < 0) prefixBits = bits;
            if (prefixBits < 0 || prefixBits > bits) return null;

            // Canonicalize original: canonicalIP or canonicalIP/prefix if not full mask
            String canonicalIp = InetAddress.getByAddress(bytes).getHostAddress();
            String original = (prefixBits == bits) ? canonicalIp : (canonicalIp + "/" + prefixBits);

            return new IpNet(bytes, prefixBits, original);

        } catch (Exception e) {
            return null;
        }
    }

    // -------------------------
    // Helper types
    // -------------------------

    private static final class IpNet {
        final byte[] addrBytes; // network base as given (not masked; we mask in compare)
        final int prefixBits;
        final String original;

        IpNet(byte[] addrBytes, int prefixBits, String original) {
            this.addrBytes = addrBytes;
            this.prefixBits = prefixBits;
            this.original = original;
        }

        boolean equalsNet(IpNet other) {
            if (other == null) return false;
            if (this.prefixBits != other.prefixBits) return false;
            if (this.addrBytes.length != other.addrBytes.length) return false;

            // compare masked bytes only
            return matchesBytes(other.addrBytes, this.addrBytes, this.prefixBits);
        }

        boolean matches(InetAddress candidate) {
            byte[] c = candidate.getAddress();
            if (c.length != addrBytes.length) return false;
            return matchesBytes(c, addrBytes, prefixBits);
        }

        static boolean matchesBytes(byte[] candidate, byte[] net, int prefixBits) {
            int fullBytes = prefixBits / 8;
            int remBits = prefixBits % 8;

            for (int i = 0; i < fullBytes; i++) {
                if (candidate[i] != net[i]) return false;
            }

            if (remBits == 0) return true;
            int mask = 0xFF << (8 - remBits);
            return (candidate[fullBytes] & mask) == (net[fullBytes] & mask);
        }

        static int compareStable(IpNet a, IpNet b) {
            // IPv4 first
            int al = a.addrBytes.length;
            int bl = b.addrBytes.length;
            if (al != bl) return Integer.compare(al, bl);

            // then prefix (more specific first)
            int pc = Integer.compare(b.prefixBits, a.prefixBits);
            if (pc != 0) return pc;

            // then lexicographic bytes
            for (int i = 0; i < al; i++) {
                int ai = a.addrBytes[i] & 0xFF;
                int bi = b.addrBytes[i] & 0xFF;
                if (ai != bi) return Integer.compare(ai, bi);
            }
            return a.original.compareToIgnoreCase(b.original);
        }
    }

    private static final class TempBanRaw {
        final String value;
        final long untilEpochSec;
        final String reason;

        TempBanRaw(String value, long untilEpochSec, String reason) {
            this.value = value;
            this.untilEpochSec = untilEpochSec;
            this.reason = reason;
        }
    }

    private static final class TempBan {
        final IpNet net;
        final long untilEpochSec;
        final String reason;

        TempBan(IpNet net, long untilEpochSec, String reason) {
            this.net = net;
            this.untilEpochSec = untilEpochSec;
            this.reason = reason;
        }

        static int compareStable(TempBan a, TempBan b) {
            int c = IpNet.compareStable(a.net, b.net);
            if (c != 0) return c;
            return Long.compare(a.untilEpochSec, b.untilEpochSec);
        }
    }
}
