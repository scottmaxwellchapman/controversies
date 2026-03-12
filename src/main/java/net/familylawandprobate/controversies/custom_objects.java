package net.familylawandprobate.controversies;

import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * custom_objects
 *
 * Tenant-scoped custom object definitions:
 *   data/tenants/{tenantUuid}/settings/custom_objects.xml
 */
public final class custom_objects {

    private static final ConcurrentHashMap<String, ReentrantReadWriteLock> LOCKS = new ConcurrentHashMap<String, ReentrantReadWriteLock>();

    public static custom_objects defaultStore() {
        return new custom_objects();
    }

    public static final class ObjectRec {
        public final String uuid;
        public final String key;
        public final String label;
        public final String pluralLabel;
        public final boolean enabled;
        public final boolean published;
        public final int sortOrder;
        public final String updatedAt;

        public ObjectRec(String uuid,
                         String key,
                         String label,
                         String pluralLabel,
                         boolean enabled,
                         boolean published,
                         int sortOrder,
                         String updatedAt) {
            this.uuid = safe(uuid);
            this.key = normalizeKeyStatic(key);
            this.label = normalizeLabel(label, this.key);
            this.pluralLabel = normalizePlural(pluralLabel, this.label);
            this.enabled = enabled;
            this.published = published;
            this.sortOrder = Math.max(0, sortOrder);
            this.updatedAt = safe(updatedAt);
        }
    }

    public void ensure(String tenantUuid) throws Exception {
        String tu = safeFileToken(tenantUuid);
        if (tu.isBlank()) throw new IllegalArgumentException("tenantUuid required");

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            Path p = objectsPath(tu);
            Files.createDirectories(p.getParent());
            if (!Files.exists(p)) writeAllLocked(tu, List.of());
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<ObjectRec> listAll(String tenantUuid) throws Exception {
        String tu = safeFileToken(tenantUuid);
        if (tu.isBlank()) return List.of();
        ensure(tu);

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.readLock().lock();
        try {
            return readAllLocked(tu);
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<ObjectRec> listPublished(String tenantUuid) throws Exception {
        List<ObjectRec> all = listAll(tenantUuid);
        ArrayList<ObjectRec> out = new ArrayList<ObjectRec>();
        for (int i = 0; i < all.size(); i++) {
            ObjectRec r = all.get(i);
            if (r == null || !r.enabled || !r.published) continue;
            out.add(r);
        }
        sortRows(out);
        return out;
    }

    public ObjectRec getByUuid(String tenantUuid, String objectUuid) throws Exception {
        String id = safe(objectUuid).trim();
        if (id.isBlank()) return null;
        List<ObjectRec> all = listAll(tenantUuid);
        for (int i = 0; i < all.size(); i++) {
            ObjectRec r = all.get(i);
            if (r != null && id.equals(safe(r.uuid).trim())) return r;
        }
        return null;
    }

    public ObjectRec getByKey(String tenantUuid, String objectKey) throws Exception {
        String key = normalizeKeyStatic(objectKey);
        if (key.isBlank()) return null;
        List<ObjectRec> all = listAll(tenantUuid);
        for (int i = 0; i < all.size(); i++) {
            ObjectRec r = all.get(i);
            if (r != null && key.equals(normalizeKeyStatic(r.key))) return r;
        }
        return null;
    }

    public void saveAll(String tenantUuid, List<ObjectRec> rows) throws Exception {
        String tu = safeFileToken(tenantUuid);
        if (tu.isBlank()) throw new IllegalArgumentException("tenantUuid required");

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensure(tu);
            List<ObjectRec> clean = sanitizeRows(rows);
            sortRows(clean);
            writeAllLocked(tu, clean);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean setPublished(String tenantUuid, String objectUuid, boolean published) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String id = safe(objectUuid).trim();
        if (tu.isBlank() || id.isBlank()) return false;

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensure(tu);
            List<ObjectRec> all = readAllLocked(tu);
            ArrayList<ObjectRec> out = new ArrayList<ObjectRec>(all.size());
            boolean changed = false;
            String now = app_clock.now().toString();

            for (int i = 0; i < all.size(); i++) {
                ObjectRec r = all.get(i);
                if (r == null) continue;
                if (id.equals(safe(r.uuid).trim())) {
                    out.add(new ObjectRec(
                            r.uuid,
                            r.key,
                            r.label,
                            r.pluralLabel,
                            r.enabled,
                            published,
                            r.sortOrder,
                            now
                    ));
                    changed = true;
                } else {
                    out.add(r);
                }
            }

            if (changed) {
                sortRows(out);
                writeAllLocked(tu, out);
            }
            return changed;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public String normalizeKey(String key) {
        return normalizeKeyStatic(key);
    }

    private static ReentrantReadWriteLock lockFor(String tenantUuid) {
        return LOCKS.computeIfAbsent(tenantUuid, k -> new ReentrantReadWriteLock());
    }

    private static Path objectsPath(String tenantUuid) {
        return Paths.get("data", "tenants", tenantUuid, "settings", "custom_objects.xml").toAbsolutePath();
    }

    private static List<ObjectRec> sanitizeRows(List<ObjectRec> rows) {
        ArrayList<ObjectRec> out = new ArrayList<ObjectRec>();
        if (rows == null) return out;

        LinkedHashSet<String> seenKey = new LinkedHashSet<String>();
        String now = app_clock.now().toString();
        int ord = 10;
        for (int i = 0; i < rows.size(); i++) {
            ObjectRec r = rows.get(i);
            if (r == null) continue;

            String key = normalizeKeyStatic(r.key);
            if (key.isBlank()) continue;
            String keyLc = key.toLowerCase(Locale.ROOT);
            if (seenKey.contains(keyLc)) continue;
            seenKey.add(keyLc);

            String uuid = safe(r.uuid).trim();
            if (uuid.isBlank()) uuid = UUID.randomUUID().toString();

            int sortOrder = r.sortOrder > 0 ? r.sortOrder : ord;
            ord += 10;

            out.add(new ObjectRec(
                    uuid,
                    key,
                    r.label,
                    r.pluralLabel,
                    r.enabled,
                    r.published,
                    sortOrder,
                    safe(r.updatedAt).isBlank() ? now : r.updatedAt
            ));
        }
        return out;
    }

    private static List<ObjectRec> readAllLocked(String tenantUuid) throws Exception {
        ArrayList<ObjectRec> out = new ArrayList<ObjectRec>();
        Path p = objectsPath(tenantUuid);
        if (!Files.exists(p)) return out;

        Document d = parseXml(p);
        Element root = d == null ? null : d.getDocumentElement();
        if (root == null) return out;

        NodeList nl = root.getElementsByTagName("object");
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (!(n instanceof Element)) continue;
            Element e = (Element) n;

            String uuid = text(e, "uuid");
            String key = normalizeKeyStatic(text(e, "key"));
            if (key.isBlank()) continue;
            if (uuid.isBlank()) uuid = UUID.randomUUID().toString();

            out.add(new ObjectRec(
                    uuid,
                    key,
                    text(e, "label"),
                    text(e, "plural_label"),
                    parseBool(text(e, "enabled"), true),
                    parseBool(text(e, "published"), false),
                    parseInt(text(e, "sort_order"), (i + 1) * 10),
                    text(e, "updated_at")
            ));
        }

        sortRows(out);
        return out;
    }

    private static void writeAllLocked(String tenantUuid, List<ObjectRec> rows) throws Exception {
        Path p = objectsPath(tenantUuid);
        Files.createDirectories(p.getParent());

        String now = app_clock.now().toString();
        StringBuilder sb = new StringBuilder(2048);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<customObjects updated=\"").append(xmlAttr(now)).append("\">\n");

        List<ObjectRec> src = rows == null ? List.of() : rows;
        for (int i = 0; i < src.size(); i++) {
            ObjectRec r = src.get(i);
            if (r == null) continue;
            if (safe(r.key).isBlank()) continue;
            sb.append("  <object>\n");
            sb.append("    <uuid>").append(xmlText(safe(r.uuid).isBlank() ? UUID.randomUUID().toString() : r.uuid)).append("</uuid>\n");
            sb.append("    <key>").append(xmlText(normalizeKeyStatic(r.key))).append("</key>\n");
            sb.append("    <label>").append(xmlText(normalizeLabel(r.label, r.key))).append("</label>\n");
            sb.append("    <plural_label>").append(xmlText(normalizePlural(r.pluralLabel, normalizeLabel(r.label, r.key)))).append("</plural_label>\n");
            sb.append("    <enabled>").append(r.enabled ? "true" : "false").append("</enabled>\n");
            sb.append("    <published>").append(r.published ? "true" : "false").append("</published>\n");
            sb.append("    <sort_order>").append(Math.max(0, r.sortOrder)).append("</sort_order>\n");
            sb.append("    <updated_at>").append(xmlText(safe(r.updatedAt).isBlank() ? now : r.updatedAt)).append("</updated_at>\n");
            sb.append("  </object>\n");
        }
        sb.append("</customObjects>\n");

        writeAtomic(p, sb.toString());
    }

    private static String normalizeLabel(String label, String key) {
        String v = safe(label).trim();
        if (v.isBlank()) v = labelFromKey(key);
        if (v.length() > 80) v = v.substring(0, 80).trim();
        return v.isBlank() ? "Object" : v;
    }

    private static String normalizePlural(String pluralLabel, String singularLabel) {
        String v = safe(pluralLabel).trim();
        if (v.isBlank()) v = safe(singularLabel).trim() + "s";
        if (v.length() > 80) v = v.substring(0, 80).trim();
        return v.isBlank() ? "Objects" : v;
    }

    private static String labelFromKey(String key) {
        String k = normalizeKeyStatic(key);
        if (k.isBlank()) return "Object";
        String[] pieces = k.replace('-', '_').replace('.', '_').split("_");
        ArrayList<String> out = new ArrayList<String>();
        for (int i = 0; i < pieces.length; i++) {
            String p = safe(pieces[i]).trim();
            if (p.isBlank()) continue;
            String head = p.substring(0, 1).toUpperCase(Locale.ROOT);
            String tail = p.length() > 1 ? p.substring(1).toLowerCase(Locale.ROOT) : "";
            out.add(head + tail);
        }
        return out.isEmpty() ? "Object" : String.join(" ", out);
    }

    private static String normalizeKeyStatic(String key) {
        String k = safe(key).trim().toLowerCase(Locale.ROOT);
        if (k.isBlank()) return "";

        StringBuilder sb = new StringBuilder(k.length());
        boolean lastUnderscore = false;
        for (int i = 0; i < k.length(); i++) {
            char ch = k.charAt(i);
            boolean ok = (ch >= 'a' && ch <= 'z')
                    || (ch >= '0' && ch <= '9')
                    || ch == '_' || ch == '-' || ch == '.';
            if (ok) {
                sb.append(ch);
                lastUnderscore = false;
            } else {
                if (!lastUnderscore && sb.length() > 0) {
                    sb.append('_');
                    lastUnderscore = true;
                }
            }
        }

        String out = sb.toString();
        while (out.startsWith("_")) out = out.substring(1);
        while (out.endsWith("_")) out = out.substring(0, out.length() - 1);
        if (out.length() > 80) out = out.substring(0, 80);
        return out;
    }

    private static boolean parseBool(String raw, boolean fallback) {
        String v = safe(raw).trim().toLowerCase(Locale.ROOT);
        if (v.isBlank()) return fallback;
        return "true".equals(v) || "1".equals(v) || "yes".equals(v) || "y".equals(v) || "on".equals(v);
    }

    private static int parseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(safe(raw).trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static void sortRows(List<ObjectRec> rows) {
        if (rows == null) return;
        rows.sort(new Comparator<ObjectRec>() {
            public int compare(ObjectRec a, ObjectRec b) {
                int bySort = Integer.compare(a == null ? 0 : a.sortOrder, b == null ? 0 : b.sortOrder);
                if (bySort != 0) return bySort;

                String al = safe(a == null ? "" : a.label);
                String bl = safe(b == null ? "" : b.label);
                int byLabel = al.compareToIgnoreCase(bl);
                if (byLabel != 0) return byLabel;

                String ak = safe(a == null ? "" : a.key);
                String bk = safe(b == null ? "" : b.key);
                return ak.compareToIgnoreCase(bk);
            }
        });
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

    private static void writeAtomic(Path p, String content) throws Exception {
        Files.createDirectories(p.getParent());
        Path tmp = p.resolveSibling(p.getFileName().toString() + ".tmp." + UUID.randomUUID());
        Files.writeString(
                tmp,
                safe(content),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        );
        try {
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception ex) {
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String text(Element parent, String childTag) {
        if (parent == null || childTag == null) return "";
        NodeList nl = parent.getElementsByTagName(childTag);
        if (nl == null || nl.getLength() == 0) return "";
        Node n = nl.item(0);
        return n == null ? "" : safe(n.getTextContent()).trim();
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
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("'", "&apos;");
    }

    private static String xmlText(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}

