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
 * custom_object_attributes
 *
 * Tenant/object-scoped custom object attribute definitions:
 *   data/tenants/{tenantUuid}/settings/custom_object_attributes/{objectUuid}.xml
 */
public final class custom_object_attributes {

    private static final ConcurrentHashMap<String, ReentrantReadWriteLock> LOCKS = new ConcurrentHashMap<String, ReentrantReadWriteLock>();

    private static final String TYPE_TEXT = "text";
    private static final String TYPE_TEXTAREA = "textarea";
    private static final String TYPE_NUMBER = "number";
    private static final String TYPE_DATE = "date";
    private static final String TYPE_SELECT = "select";
    private static final String TYPE_BOOLEAN = "boolean";
    private static final String TYPE_DATETIME = "datetime";
    private static final String TYPE_TIME = "time";
    private static final String TYPE_EMAIL = "email";
    private static final String TYPE_PHONE = "phone";
    private static final String TYPE_URL = "url";

    public static custom_object_attributes defaultStore() {
        return new custom_object_attributes();
    }

    public static final class AttributeRec {
        public final String uuid;
        public final String key;
        public final String label;
        public final String dataType;
        public final String options;
        public final boolean required;
        public final boolean enabled;
        public final int sortOrder;
        public final String updatedAt;

        public AttributeRec(String uuid,
                            String key,
                            String label,
                            String dataType,
                            String options,
                            boolean required,
                            boolean enabled,
                            int sortOrder,
                            String updatedAt) {
            this.uuid = safe(uuid);
            this.key = normalizeKeyStatic(key);
            this.label = normalizeLabel(label, this.key);
            this.dataType = normalizeTypeStatic(dataType);
            this.options = TYPE_SELECT.equals(this.dataType) ? normalizeOptionsStatic(options) : "";
            this.required = required;
            this.enabled = enabled;
            this.sortOrder = Math.max(0, sortOrder);
            this.updatedAt = safe(updatedAt);
        }
    }

    public void ensure(String tenantUuid, String objectUuid) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String ou = safeFileToken(objectUuid);
        if (tu.isBlank() || ou.isBlank()) throw new IllegalArgumentException("tenantUuid/objectUuid required");

        ReentrantReadWriteLock lock = lockFor(tu, ou);
        lock.writeLock().lock();
        try {
            Path p = attrsPath(tu, ou);
            Files.createDirectories(p.getParent());
            if (!Files.exists(p)) writeAllLocked(tu, ou, List.of());
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<AttributeRec> listAll(String tenantUuid, String objectUuid) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String ou = safeFileToken(objectUuid);
        if (tu.isBlank() || ou.isBlank()) return List.of();
        ensure(tu, ou);

        ReentrantReadWriteLock lock = lockFor(tu, ou);
        lock.readLock().lock();
        try {
            return readAllLocked(tu, ou);
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<AttributeRec> listEnabled(String tenantUuid, String objectUuid) throws Exception {
        List<AttributeRec> all = listAll(tenantUuid, objectUuid);
        ArrayList<AttributeRec> out = new ArrayList<AttributeRec>();
        for (int i = 0; i < all.size(); i++) {
            AttributeRec r = all.get(i);
            if (r == null || !r.enabled) continue;
            out.add(r);
        }
        sortRows(out);
        return out;
    }

    public void saveAll(String tenantUuid, String objectUuid, List<AttributeRec> rows) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String ou = safeFileToken(objectUuid);
        if (tu.isBlank() || ou.isBlank()) throw new IllegalArgumentException("tenantUuid/objectUuid required");

        ReentrantReadWriteLock lock = lockFor(tu, ou);
        lock.writeLock().lock();
        try {
            ensure(tu, ou);
            List<AttributeRec> clean = sanitizeRows(rows);
            sortRows(clean);
            writeAllLocked(tu, ou, clean);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public String normalizeKey(String key) {
        return normalizeKeyStatic(key);
    }

    public String normalizeType(String type) {
        return normalizeTypeStatic(type);
    }

    public String normalizeOptions(String raw) {
        return normalizeOptionsStatic(raw);
    }

    public List<String> optionList(String optionsRaw) {
        ArrayList<String> out = new ArrayList<String>();
        String normalized = normalizeOptionsStatic(optionsRaw);
        if (normalized.isBlank()) return out;
        String[] rows = normalized.split("\\n");
        LinkedHashSet<String> seen = new LinkedHashSet<String>();
        for (int i = 0; i < rows.length; i++) {
            String v = safe(rows[i]).trim();
            if (v.isBlank()) continue;
            String key = v.toLowerCase(Locale.ROOT);
            if (seen.contains(key)) continue;
            seen.add(key);
            out.add(v);
        }
        return out;
    }

    private static ReentrantReadWriteLock lockFor(String tenantUuid, String objectUuid) {
        String key = tenantUuid + "|" + objectUuid;
        return LOCKS.computeIfAbsent(key, k -> new ReentrantReadWriteLock());
    }

    private static Path attrsPath(String tenantUuid, String objectUuid) {
        return Paths.get("data", "tenants", tenantUuid, "settings", "custom_object_attributes", objectUuid + ".xml").toAbsolutePath();
    }

    private static List<AttributeRec> sanitizeRows(List<AttributeRec> rows) {
        ArrayList<AttributeRec> out = new ArrayList<AttributeRec>();
        if (rows == null) return out;
        LinkedHashSet<String> seen = new LinkedHashSet<String>();
        String now = Instant.now().toString();
        int ord = 10;
        for (int i = 0; i < rows.size(); i++) {
            AttributeRec r = rows.get(i);
            if (r == null) continue;

            String key = normalizeKeyStatic(r.key);
            if (key.isBlank()) continue;
            String lk = key.toLowerCase(Locale.ROOT);
            if (seen.contains(lk)) continue;
            seen.add(lk);

            String uuid = safe(r.uuid).trim();
            if (uuid.isBlank()) uuid = UUID.randomUUID().toString();

            int sortOrder = r.sortOrder > 0 ? r.sortOrder : ord;
            ord += 10;
            out.add(new AttributeRec(
                    uuid,
                    key,
                    r.label,
                    r.dataType,
                    r.options,
                    r.required,
                    r.enabled,
                    sortOrder,
                    safe(r.updatedAt).isBlank() ? now : r.updatedAt
            ));
        }
        return out;
    }

    private static List<AttributeRec> readAllLocked(String tenantUuid, String objectUuid) throws Exception {
        ArrayList<AttributeRec> out = new ArrayList<AttributeRec>();
        Path p = attrsPath(tenantUuid, objectUuid);
        if (!Files.exists(p)) return out;

        Document d = parseXml(p);
        Element root = d == null ? null : d.getDocumentElement();
        if (root == null) return out;

        NodeList nl = root.getElementsByTagName("attribute");
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (!(n instanceof Element)) continue;
            Element e = (Element) n;

            String key = normalizeKeyStatic(text(e, "key"));
            if (key.isBlank()) continue;
            out.add(new AttributeRec(
                    text(e, "uuid"),
                    key,
                    text(e, "label"),
                    text(e, "data_type"),
                    text(e, "options"),
                    parseBool(text(e, "required"), false),
                    parseBool(text(e, "enabled"), true),
                    parseInt(text(e, "sort_order"), (i + 1) * 10),
                    text(e, "updated_at")
            ));
        }
        sortRows(out);
        return out;
    }

    private static void writeAllLocked(String tenantUuid, String objectUuid, List<AttributeRec> rows) throws Exception {
        Path p = attrsPath(tenantUuid, objectUuid);
        Files.createDirectories(p.getParent());

        String now = Instant.now().toString();
        StringBuilder sb = new StringBuilder(2048);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<customObjectAttributes object_uuid=\"").append(xmlAttr(objectUuid)).append("\" updated=\"").append(xmlAttr(now)).append("\">\n");

        List<AttributeRec> src = rows == null ? List.of() : rows;
        for (int i = 0; i < src.size(); i++) {
            AttributeRec r = src.get(i);
            if (r == null || safe(r.key).isBlank()) continue;
            sb.append("  <attribute>\n");
            sb.append("    <uuid>").append(xmlText(safe(r.uuid).isBlank() ? UUID.randomUUID().toString() : r.uuid)).append("</uuid>\n");
            sb.append("    <key>").append(xmlText(normalizeKeyStatic(r.key))).append("</key>\n");
            sb.append("    <label>").append(xmlText(normalizeLabel(r.label, r.key))).append("</label>\n");
            sb.append("    <data_type>").append(xmlText(normalizeTypeStatic(r.dataType))).append("</data_type>\n");
            sb.append("    <options>").append(xmlText(normalizeOptionsStatic(r.options))).append("</options>\n");
            sb.append("    <required>").append(r.required ? "true" : "false").append("</required>\n");
            sb.append("    <enabled>").append(r.enabled ? "true" : "false").append("</enabled>\n");
            sb.append("    <sort_order>").append(Math.max(0, r.sortOrder)).append("</sort_order>\n");
            sb.append("    <updated_at>").append(xmlText(safe(r.updatedAt).isBlank() ? now : r.updatedAt)).append("</updated_at>\n");
            sb.append("  </attribute>\n");
        }
        sb.append("</customObjectAttributes>\n");
        writeAtomic(p, sb.toString());
    }

    private static String normalizeLabel(String label, String key) {
        String v = safe(label).trim();
        if (v.isBlank()) v = labelFromKey(key);
        if (v.length() > 80) v = v.substring(0, 80).trim();
        return v.isBlank() ? "Field" : v;
    }

    private static String labelFromKey(String key) {
        String k = normalizeKeyStatic(key);
        if (k.isBlank()) return "Field";
        String[] pieces = k.replace('-', '_').replace('.', '_').split("_");
        ArrayList<String> out = new ArrayList<String>();
        for (int i = 0; i < pieces.length; i++) {
            String p = safe(pieces[i]).trim();
            if (p.isBlank()) continue;
            String head = p.substring(0, 1).toUpperCase(Locale.ROOT);
            String tail = p.length() > 1 ? p.substring(1).toLowerCase(Locale.ROOT) : "";
            out.add(head + tail);
        }
        return out.isEmpty() ? "Field" : String.join(" ", out);
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

    private static String normalizeTypeStatic(String raw) {
        String t = safe(raw).trim().toLowerCase(Locale.ROOT);
        if (TYPE_TEXT.equals(t)) return TYPE_TEXT;
        if (TYPE_TEXTAREA.equals(t)) return TYPE_TEXTAREA;
        if (TYPE_NUMBER.equals(t)) return TYPE_NUMBER;
        if (TYPE_DATE.equals(t)) return TYPE_DATE;
        if (TYPE_SELECT.equals(t)) return TYPE_SELECT;
        if (TYPE_BOOLEAN.equals(t) || "bool".equals(t) || "checkbox".equals(t)) return TYPE_BOOLEAN;
        if (TYPE_DATETIME.equals(t) || "datetime-local".equals(t) || "timestamp".equals(t)) return TYPE_DATETIME;
        if (TYPE_TIME.equals(t)) return TYPE_TIME;
        if (TYPE_EMAIL.equals(t)) return TYPE_EMAIL;
        if (TYPE_PHONE.equals(t) || "tel".equals(t) || "telephone".equals(t) || "phone_number".equals(t)) return TYPE_PHONE;
        if (TYPE_URL.equals(t) || "uri".equals(t)) return TYPE_URL;
        return TYPE_TEXT;
    }

    private static String normalizeOptionsStatic(String raw) {
        String in = safe(raw).replace("\r\n", "\n").replace('\r', '\n');
        if (in.isBlank()) return "";
        String[] rows = in.split("\\n");
        ArrayList<String> out = new ArrayList<String>();
        LinkedHashSet<String> seen = new LinkedHashSet<String>();
        for (int i = 0; i < rows.length; i++) {
            String v = safe(rows[i]).trim();
            if (v.isBlank()) continue;
            if (v.length() > 120) v = v.substring(0, 120).trim();
            String key = v.toLowerCase(Locale.ROOT);
            if (seen.contains(key)) continue;
            seen.add(key);
            out.add(v);
        }
        if (out.isEmpty()) return "";
        return String.join("\n", out);
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

    private static void sortRows(List<AttributeRec> rows) {
        if (rows == null) return;
        rows.sort(new Comparator<AttributeRec>() {
            public int compare(AttributeRec a, AttributeRec b) {
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
