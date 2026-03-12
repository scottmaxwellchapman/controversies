// tenants.java
package net.familylawandprobate.controversies;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.*;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * tenants.java
 * -----------
 * Manages:
 * - data/tenants.xml
 * - data/sec/random_pepper.bin
 *
 * tenants.xml structure:
 * <tenants created="...">
 *   <tenant>
 *     <uuid>...</uuid>
 *     <enabled>true</enabled>
 *     <label>Default Tenant</label>
 *     <tenant_algo2id_password_hash>...</tenant_algo2id_password_hash>
 *   </tenant>
 * </tenants>
 *
 * Notes:
 * - uuid is auto-generated
 * - enabled is boolean
 * - label is human-friendly and must be unique (case-insensitive)
 * - "tenant_algo2id_password_hash" stores a versioned hash string using PBKDF2-HMAC-SHA256 + secret pepper.
 *   (Element name kept as requested even though algorithm string is PBKDF2 for JDK-only compatibility.)
 *
 * Bootstrap:
 * - On initialization (ensure()), guarantees a tenant labeled "Default Tenant" exists and is enabled.
 * - Bootstrap password source:
 *   1) System property/env CONTROVERSIES_BOOTSTRAP_PASSWORD
 *   2) Generated one-time random password (logged once at WARN level)
 */
public final class tenants {

    private static final Logger LOG = Logger.getLogger(tenants.class.getName());
    private static final SecureRandom RNG = new SecureRandom();

    private static final String ROOT = "tenants";
    private static final String TENANT = "tenant";

    private static final String E_UUID = "uuid";
    private static final String E_ENABLED = "enabled";
    private static final String E_LABEL = "label";
    private static final String E_HASH = "tenant_algo2id_password_hash";

    // Bootstrap default tenant
    private static final String DEFAULT_TENANT_LABEL = "Default Tenant";
    private static final String BOOTSTRAP_PASSWORD_ENV = "CONTROVERSIES_BOOTSTRAP_PASSWORD";
    private static final int GENERATED_BOOTSTRAP_PASSWORD_LEN = 24;
    private static final String BOOTSTRAP_PASSWORD_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@$%*-_";
    private static volatile String GENERATED_BOOTSTRAP_PASSWORD = null;

    // Label rules:
    // - letters, numbers, spaces
    // - must start with alphanumeric
    // - max 64 chars (after trim)
    private static final String LABEL_RE = "^[A-Za-z0-9][A-Za-z0-9 ]*$";

    // Pepper bytes length
    private static final int PEPPER_LEN = 32;

    // Hash params (tuned for server-side use; adjust as needed)
    private static final int PBKDF2_ITERS = 210_000;
    private static final int DK_LEN_BYTES = 32; // 256-bit derived key
    private static final int SALT_LEN = 16;

    // Stored hash format (single string in E_HASH):
    // v1$pbkdf2_sha256$<iters>$<saltB64>$<dkB64>
    private static final String HASH_VERSION = "v1";
    private static final String HASH_ALGO = "pbkdf2_sha256";

    private final Path tenantsPath;
    private final Path pepperPath;

    private final Object lock = new Object();

    public tenants(Path tenantsXmlPath, Path randomPepperPath) {
        this.tenantsPath = Objects.requireNonNull(tenantsXmlPath, "tenantsXmlPath").toAbsolutePath();
        this.pepperPath = Objects.requireNonNull(randomPepperPath, "randomPepperPath").toAbsolutePath();
    }

    /** Default store: data/tenants.xml + data/sec/random_pepper.bin */
    public static tenants defaultStore() {
        Path base = Paths.get("data").toAbsolutePath();
        return new tenants(
                base.resolve("tenants.xml"),
                base.resolve("sec").resolve("random_pepper.bin")
        );
    }

    /**
     * Ensures tenants.xml and random_pepper.bin exist. Safe to call repeatedly.
     * Also ensures a bootstrap "Default Tenant" exists.
     */
    public void ensure() throws Exception {
        synchronized (lock) {
            Files.createDirectories(tenantsPath.getParent());
            Files.createDirectories(pepperPath.getParent());
            ensurePepperExists();
            ensureTenantsXmlExists();
            ensureDefaultTenantExists(); // <-- bootstrap
        }
    }

    // -----------------------------
    // Public API
    // -----------------------------

    public static final class Tenant {
        public final String uuid;
        public final boolean enabled;
        public final String label;
        public final String tenantAlgo2idPasswordHash;

        Tenant(String uuid, boolean enabled, String label, String hash) {
            this.uuid = nz(uuid);
            this.enabled = enabled;
            this.label = nz(label);
            this.tenantAlgo2idPasswordHash = nz(hash);
        }
    }

    /** Returns a snapshot list of tenants. */
    public List<Tenant> list() throws Exception {
        synchronized (lock) {
            ensure();
            Document d = loadDoc();
            List<Tenant> out = new ArrayList<>();
            NodeList nodes = d.getDocumentElement().getElementsByTagName(TENANT);
            for (int i = 0; i < nodes.getLength(); i++) {
                if (!(nodes.item(i) instanceof Element el)) continue;
                out.add(readTenant(el));
            }
            return out;
        }
    }

    public Tenant getByUuid(String uuid) throws Exception {
        String u = nz(uuid).trim();
        if (u.isBlank()) return null;

        synchronized (lock) {
            ensure();
            Document d = loadDoc();
            Element root = d.getDocumentElement();
            NodeList nodes = root.getElementsByTagName(TENANT);
            for (int i = 0; i < nodes.getLength(); i++) {
                if (!(nodes.item(i) instanceof Element el)) continue;
                String id = text(el, E_UUID);
                if (u.equals(id)) return readTenant(el);
            }
            return null;
        }
    }

    /** Creates a tenant (enabled=true) and sets password hash. Returns new uuid. */
    public String create(String label, char[] password) throws Exception {
        String lbl = validateLabel(label);
        if (password == null || password.length == 0) throw new IllegalArgumentException("password required");

        synchronized (lock) {
            ensure();
            Document d = loadDoc();
            Element root = d.getDocumentElement();

            ensureLabelUnique(d, lbl, null);

            String uuid = UUID.randomUUID().toString();
            String hash = hashPassword(password);

            Element tenant = d.createElement(TENANT);

            appendChildText(d, tenant, E_UUID, uuid);
            appendChildText(d, tenant, E_ENABLED, "true");
            appendChildText(d, tenant, E_LABEL, lbl);
            appendChildText(d, tenant, E_HASH, hash);

            root.appendChild(tenant);
            saveDocAtomic(d);

            return uuid;
        }
    }

    public void setEnabled(String uuid, boolean enabled) throws Exception {
        String u = nz(uuid).trim();
        if (u.isBlank()) throw new IllegalArgumentException("uuid required");

        synchronized (lock) {
            ensure();
            Document d = loadDoc();
            Element el = findTenantElement(d, u);
            if (el == null) throw new IllegalArgumentException("tenant not found: " + u);

            setOrCreateChildText(d, el, E_ENABLED, Boolean.toString(enabled));
            saveDocAtomic(d);
        }
    }

    public void setLabel(String uuid, String newLabel) throws Exception {
        String u = nz(uuid).trim();
        String lbl = validateLabel(newLabel);

        synchronized (lock) {
            ensure();
            Document d = loadDoc();
            Element el = findTenantElement(d, u);
            if (el == null) throw new IllegalArgumentException("tenant not found: " + u);

            ensureLabelUnique(d, lbl, u);
            setOrCreateChildText(d, el, E_LABEL, lbl);
            saveDocAtomic(d);
        }
    }

    public void setPassword(String uuid, char[] newPassword) throws Exception {
        String u = nz(uuid).trim();
        if (u.isBlank()) throw new IllegalArgumentException("uuid required");
        if (newPassword == null || newPassword.length == 0) throw new IllegalArgumentException("password required");

        synchronized (lock) {
            ensure();
            Document d = loadDoc();
            Element el = findTenantElement(d, u);
            if (el == null) throw new IllegalArgumentException("tenant not found: " + u);

            String hash = hashPassword(newPassword);
            setOrCreateChildText(d, el, E_HASH, hash);
            saveDocAtomic(d);
        }
    }

    /** Verifies password against stored hash (and requires tenant enabled). */
    public boolean verifyPassword(String uuid, char[] password) throws Exception {
        String u = nz(uuid).trim();
        if (u.isBlank()) return false;
        if (password == null || password.length == 0) return false;

        synchronized (lock) {
            ensure();
            Document d = loadDoc();
            Element el = findTenantElement(d, u);
            if (el == null) return false;

            boolean enabled = Boolean.parseBoolean(nz(text(el, E_ENABLED)).trim());
            if (!enabled) return false;

            String stored = text(el, E_HASH);
            if (stored == null || stored.isBlank()) return false;

            return verifyHash(password, stored);
        }
    }

    // -----------------------------
    // Bootstrap: ensure Default Tenant exists
    // -----------------------------

    private void ensureDefaultTenantExists() throws Exception {
        // tenantsPath and pepper already ensured in ensure()
        Document d = loadDoc();
        Element root = d.getDocumentElement();

        Element foundEl = null;
        NodeList nodes = root.getElementsByTagName(TENANT);
        for (int i = 0; i < nodes.getLength(); i++) {
            if (!(nodes.item(i) instanceof Element el)) continue;
            String lbl = nz(text(el, E_LABEL)).trim();
            if (!lbl.isBlank() && lbl.equalsIgnoreCase(DEFAULT_TENANT_LABEL)) {
                foundEl = el;
                break;
            }
        }

        boolean changed = false;

        if (foundEl == null) {
            // Create it
            String uuid = UUID.randomUUID().toString();
            char[] bootstrapPassword = resolveBootstrapPassword("default_tenant.create");
            String hash;
            try {
                hash = hashPassword(bootstrapPassword);
            } finally {
                wipe(bootstrapPassword);
            }

            Element tenant = d.createElement(TENANT);
            appendChildText(d, tenant, E_UUID, uuid);
            appendChildText(d, tenant, E_ENABLED, "true");
            appendChildText(d, tenant, E_LABEL, DEFAULT_TENANT_LABEL);
            appendChildText(d, tenant, E_HASH, hash);

            root.appendChild(tenant);
            changed = true;

            // Optional: create tenant directory skeleton (harmless if unused)
            try {
                Path tdir = Paths.get("data", "tenants", uuid).toAbsolutePath();
                Files.createDirectories(tdir.resolve("bindings"));
            } catch (Exception ignored) {}

            LOG.info("Bootstrap: created Default Tenant (label=\"" + DEFAULT_TENANT_LABEL + "\")");
        } else {
            // Ensure enabled=true (do not overwrite password)
            String en = nz(text(foundEl, E_ENABLED)).trim();
            if (!"true".equalsIgnoreCase(en)) {
                setOrCreateChildText(d, foundEl, E_ENABLED, "true");
                changed = true;
                LOG.info("Bootstrap: re-enabled Default Tenant (label=\"" + DEFAULT_TENANT_LABEL + "\")");
            }

            // Ensure hash exists (only if missing/blank)
            String h = nz(text(foundEl, E_HASH)).trim();
            if (h.isBlank()) {
                char[] bootstrapPassword = resolveBootstrapPassword("default_tenant.restore_hash");
                String hash;
                try {
                    hash = hashPassword(bootstrapPassword);
                } finally {
                    wipe(bootstrapPassword);
                }
                setOrCreateChildText(d, foundEl, E_HASH, hash);
                changed = true;
                LOG.info("Bootstrap: set Default Tenant password hash (label=\"" + DEFAULT_TENANT_LABEL + "\")");
            }

            // Optional: create directory skeleton for existing Default Tenant
            try {
                String uuid = nz(text(foundEl, E_UUID)).trim();
                if (!uuid.isBlank()) {
                    Path tdir = Paths.get("data", "tenants", uuid).toAbsolutePath();
                    Files.createDirectories(tdir.resolve("bindings"));
                }
            } catch (Exception ignored) {}
        }

        if (changed) saveDocAtomic(d);
    }

    // -----------------------------
    // Pepper + hashing
    // -----------------------------

    private void ensurePepperExists() throws Exception {
        if (Files.exists(pepperPath) && Files.size(pepperPath) == PEPPER_LEN) return;

        byte[] p = new byte[PEPPER_LEN];
        RNG.nextBytes(p);

        // Create atomically
        Path tmp = pepperPath.resolveSibling(pepperPath.getFileName() + ".tmp");
        Files.write(tmp, p, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        try {
            Files.move(tmp, pepperPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, pepperPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private byte[] readPepper() throws Exception {
        byte[] p = Files.readAllBytes(pepperPath);
        if (p.length != PEPPER_LEN) throw new IllegalStateException("pepper length invalid: " + p.length);
        return p;
    }

    private String hashPassword(char[] password) throws Exception {
        byte[] salt = new byte[SALT_LEN];
        RNG.nextBytes(salt);

        byte[] pepper = readPepper();

        // Salt' = SHA-256(salt || pepper) so pepper is required but not stored in hash string.
        byte[] salted = sha256(concat(salt, pepper));

        byte[] dk = pbkdf2(password, salted, PBKDF2_ITERS, DK_LEN_BYTES);

        return HASH_VERSION + "$" + HASH_ALGO + "$" + PBKDF2_ITERS + "$"
                + b64(salt) + "$" + b64(dk);
    }

    private boolean verifyHash(char[] password, String stored) throws Exception {
        String[] parts = nz(stored).split("\\$");
        if (parts.length != 5) return false;

        if (!HASH_VERSION.equals(parts[0])) return false;
        if (!HASH_ALGO.equals(parts[1])) return false;

        int iters;
        try { iters = Integer.parseInt(parts[2]); } catch (Exception e) { return false; }
        if (iters < 50_000) return false; // sanity floor

        byte[] salt;
        byte[] expected;
        try {
            salt = b64d(parts[3]);
            expected = b64d(parts[4]);
        } catch (Exception e) {
            return false;
        }

        byte[] pepper = readPepper();
        byte[] salted = sha256(concat(salt, pepper));

        byte[] got = pbkdf2(password, salted, iters, expected.length);
        return constantTimeEquals(expected, got);
    }

    private static byte[] pbkdf2(char[] password, byte[] salt, int iters, int dkLenBytes) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iters, dkLenBytes * 8);
        SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        return f.generateSecret(spec).getEncoded();
    }

    private char[] resolveBootstrapPassword(String purpose) {
        String configured = nz(System.getProperty(BOOTSTRAP_PASSWORD_ENV)).trim();
        if (configured.isBlank()) configured = nz(System.getenv(BOOTSTRAP_PASSWORD_ENV)).trim();
        if (!configured.isBlank()) return configured.toCharArray();

        synchronized (lock) {
            if (GENERATED_BOOTSTRAP_PASSWORD == null || GENERATED_BOOTSTRAP_PASSWORD.isBlank()) {
                GENERATED_BOOTSTRAP_PASSWORD = generateBootstrapPassword();
                LOG.warning("Security bootstrap generated one-time password for " + purpose
                        + ". Set " + BOOTSTRAP_PASSWORD_ENV + " to control this value. Password="
                        + GENERATED_BOOTSTRAP_PASSWORD);
            }
            return GENERATED_BOOTSTRAP_PASSWORD.toCharArray();
        }
    }

    private static String generateBootstrapPassword() {
        String alphabet = BOOTSTRAP_PASSWORD_ALPHABET;
        int len = Math.max(16, GENERATED_BOOTSTRAP_PASSWORD_LEN);
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            int idx = RNG.nextInt(alphabet.length());
            sb.append(alphabet.charAt(idx));
        }
        return sb.toString();
    }

    private static void wipe(char[] chars) {
        if (chars == null) return;
        Arrays.fill(chars, '\0');
    }

    private static byte[] sha256(byte[] in) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return md.digest(in);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static String b64(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static byte[] b64d(String s) {
        return Base64.getUrlDecoder().decode(s);
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a == null || b == null) return false;
        if (a.length != b.length) return false;
        int r = 0;
        for (int i = 0; i < a.length; i++) r |= (a[i] ^ b[i]);
        return r == 0;
    }

    // -----------------------------
    // XML storage
    // -----------------------------

    private void ensureTenantsXmlExists() throws Exception {
        if (Files.exists(tenantsPath) && Files.size(tenantsPath) > 0) return;

        Files.createDirectories(tenantsPath.getParent());
        String xml =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<" + ROOT + " created=\"" + escAttr(app_clock.now().toString()) + "\">\n" +
                "</" + ROOT + ">\n";
        Files.writeString(tenantsPath, xml, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private Document loadDoc() throws Exception {
        try (InputStream in = Files.newInputStream(tenantsPath, StandardOpenOption.READ)) {
            DocumentBuilder b = secureBuilder();
            Document d = b.parse(in);
            d.getDocumentElement().normalize();

            if (d.getDocumentElement() == null || !ROOT.equals(d.getDocumentElement().getTagName())) {
                throw new IllegalStateException("Invalid root element in " + tenantsPath);
            }
            return d;
        }
    }

    private void saveDocAtomic(Document d) throws Exception {
        byte[] bytes = toPrettyXml(d);

        Path dir = tenantsPath.getParent();
        Files.createDirectories(dir);

        Path tmp = dir.resolve(tenantsPath.getFileName().toString() + ".tmp");
        Files.write(tmp, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        try {
            Files.move(tmp, tenantsPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, tenantsPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static byte[] toPrettyXml(Document d) throws Exception {
        TransformerFactory tf = TransformerFactory.newInstance();
        try {
            tf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        } catch (Exception ignored) {}

        Transformer t = tf.newTransformer();
        t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        t.setOutputProperty(OutputKeys.INDENT, "yes");
        t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");

        ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
        t.transform(new DOMSource(d), new StreamResult(out));
        return out.toByteArray();
    }

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

    // -----------------------------
    // Tenant element helpers
    // -----------------------------

    private static Tenant readTenant(Element el) {
        String uuid = text(el, E_UUID);
        boolean enabled = Boolean.parseBoolean(nz(text(el, E_ENABLED)).trim());
        String label = text(el, E_LABEL);
        String hash = text(el, E_HASH);
        return new Tenant(uuid, enabled, label, hash);
    }

    private static String text(Element parent, String childTag) {
        NodeList nl = parent.getElementsByTagName(childTag);
        if (nl == null || nl.getLength() == 0) return "";
        Node n = nl.item(0);
        if (n == null) return "";
        String s = n.getTextContent();
        return (s == null) ? "" : s.trim();
    }

    private static void appendChildText(Document d, Element parent, String tag, String value) {
        Element c = d.createElement(tag);
        c.appendChild(d.createTextNode(nz(value)));
        parent.appendChild(c);
    }

    private static void setOrCreateChildText(Document d, Element parent, String tag, String value) {
        NodeList nl = parent.getElementsByTagName(tag);
        Element c = null;
        if (nl != null && nl.getLength() > 0 && nl.item(0) instanceof Element e) c = e;
        if (c == null) {
            appendChildText(d, parent, tag, value);
        } else {
            c.setTextContent(nz(value));
        }
    }

    private static Element findTenantElement(Document d, String uuid) {
        Element root = d.getDocumentElement();
        NodeList nodes = root.getElementsByTagName(TENANT);
        for (int i = 0; i < nodes.getLength(); i++) {
            if (!(nodes.item(i) instanceof Element el)) continue;
            String id = text(el, E_UUID);
            if (uuid.equals(id)) return el;
        }
        return null;
    }

    private static String validateLabel(String label) {
        String lbl = nz(label).trim();
        if (lbl.isBlank()) throw new IllegalArgumentException("label required");
        if (lbl.length() > 64) throw new IllegalArgumentException("label too long (max 64)");
        if (!lbl.matches(LABEL_RE)) throw new IllegalArgumentException("label must be letters/numbers/spaces only (and start with a letter/number)");
        return lbl;
    }

    private static void ensureLabelUnique(Document d, String label, String ignoreUuid) {
        String want = label.toLowerCase(Locale.ROOT);

        NodeList nodes = d.getDocumentElement().getElementsByTagName(TENANT);
        for (int i = 0; i < nodes.getLength(); i++) {
            if (!(nodes.item(i) instanceof Element el)) continue;
            String uuid = text(el, E_UUID);
            if (ignoreUuid != null && ignoreUuid.equals(uuid)) continue;

            String lbl = text(el, E_LABEL);
            if (lbl != null && !lbl.isBlank() && want.equals(lbl.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("label already in use: " + label);
            }
        }
    }

    // -----------------------------
    // Small helpers
    // -----------------------------

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String escAttr(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("'", "&apos;");
    }

    @SuppressWarnings("unused")
    public void logSummary() {
        try {
            List<Tenant> ts = list();
            LOG.info(() -> "tenants: " + ts.size() + " (file=" + tenantsPath + ")");
        } catch (Exception e) {
            LOG.log(Level.WARNING, "tenants summary failed: " + e.getMessage(), e);
        }
    }
}
