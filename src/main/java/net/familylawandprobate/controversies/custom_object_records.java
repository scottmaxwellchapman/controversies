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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * custom_object_records
 *
 * Tenant/object-scoped custom object records in:
 *   data/tenants/{tenantUuid}/custom_objects/{objectUuid}/records.xml
 */
public final class custom_object_records {

    private static final ConcurrentHashMap<String, ReentrantReadWriteLock> LOCKS = new ConcurrentHashMap<String, ReentrantReadWriteLock>();

    public static custom_object_records defaultStore() {
        return new custom_object_records();
    }

    public static final class RecordRec {
        public final String uuid;
        public final String label;
        public final boolean enabled;
        public final boolean trashed;
        public final String createdAt;
        public final String updatedAt;
        public final LinkedHashMap<String, String> values;

        public RecordRec(String uuid,
                         String label,
                         boolean enabled,
                         boolean trashed,
                         String createdAt,
                         String updatedAt,
                         Map<String, String> values) {
            this.uuid = safe(uuid);
            this.label = normalizeLabel(label);
            this.enabled = enabled;
            this.trashed = trashed;
            this.createdAt = safe(createdAt);
            this.updatedAt = safe(updatedAt);
            this.values = sanitizeFieldValues(values);
        }
    }

    public void ensure(String tenantUuid, String objectUuid) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String ou = safeFileToken(objectUuid);
        if (tu.isBlank() || ou.isBlank()) throw new IllegalArgumentException("tenantUuid/objectUuid required");

        ReentrantReadWriteLock lock = lockFor(tu, ou);
        lock.writeLock().lock();
        try {
            Path p = recordsPath(tu, ou);
            Files.createDirectories(p.getParent());
            if (!Files.exists(p)) writeAllLocked(tu, ou, List.of());
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<RecordRec> listAll(String tenantUuid, String objectUuid) throws Exception {
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

    public RecordRec getByUuid(String tenantUuid, String objectUuid, String recordUuid) throws Exception {
        String id = safe(recordUuid).trim();
        if (id.isBlank()) return null;
        List<RecordRec> all = listAll(tenantUuid, objectUuid);
        for (int i = 0; i < all.size(); i++) {
            RecordRec r = all.get(i);
            if (r != null && id.equals(safe(r.uuid).trim())) return r;
        }
        return null;
    }

    public RecordRec create(String tenantUuid, String objectUuid, String label, Map<String, String> values) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String ou = safeFileToken(objectUuid);
        String lbl = normalizeLabel(label);
        if (tu.isBlank() || ou.isBlank()) throw new IllegalArgumentException("tenantUuid/objectUuid required");
        if (lbl.isBlank()) throw new IllegalArgumentException("label required");

        ReentrantReadWriteLock lock = lockFor(tu, ou);
        lock.writeLock().lock();
        try {
            ensure(tu, ou);
            List<RecordRec> all = readAllLocked(tu, ou);

            String now = Instant.now().toString();
            RecordRec rec = new RecordRec(
                    UUID.randomUUID().toString(),
                    lbl,
                    true,
                    false,
                    now,
                    now,
                    values
            );

            all.add(rec);
            sortRows(all);
            writeAllLocked(tu, ou, all);
            return rec;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean update(String tenantUuid,
                          String objectUuid,
                          String recordUuid,
                          String label,
                          Map<String, String> values) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String ou = safeFileToken(objectUuid);
        String id = safe(recordUuid).trim();
        String lbl = normalizeLabel(label);
        if (tu.isBlank() || ou.isBlank() || id.isBlank()) throw new IllegalArgumentException("tenantUuid/objectUuid/recordUuid required");
        if (lbl.isBlank()) throw new IllegalArgumentException("label required");

        ReentrantReadWriteLock lock = lockFor(tu, ou);
        lock.writeLock().lock();
        try {
            ensure(tu, ou);
            List<RecordRec> all = readAllLocked(tu, ou);
            ArrayList<RecordRec> out = new ArrayList<RecordRec>(all.size());
            boolean changed = false;
            String now = Instant.now().toString();

            for (int i = 0; i < all.size(); i++) {
                RecordRec r = all.get(i);
                if (r == null) continue;

                if (id.equals(safe(r.uuid).trim())) {
                    out.add(new RecordRec(
                            r.uuid,
                            lbl,
                            r.enabled,
                            r.trashed,
                            safe(r.createdAt).isBlank() ? now : r.createdAt,
                            now,
                            values
                    ));
                    changed = true;
                } else {
                    out.add(r);
                }
            }

            if (changed) {
                sortRows(out);
                writeAllLocked(tu, ou, out);
            }
            return changed;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean setTrashed(String tenantUuid, String objectUuid, String recordUuid, boolean trashed) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String ou = safeFileToken(objectUuid);
        String id = safe(recordUuid).trim();
        if (tu.isBlank() || ou.isBlank() || id.isBlank()) return false;

        ReentrantReadWriteLock lock = lockFor(tu, ou);
        lock.writeLock().lock();
        try {
            ensure(tu, ou);
            List<RecordRec> all = readAllLocked(tu, ou);
            ArrayList<RecordRec> out = new ArrayList<RecordRec>(all.size());
            boolean changed = false;
            String now = Instant.now().toString();

            for (int i = 0; i < all.size(); i++) {
                RecordRec r = all.get(i);
                if (r == null) continue;

                if (id.equals(safe(r.uuid).trim())) {
                    boolean enabled = trashed ? false : true;
                    if (r.trashed != trashed || r.enabled != enabled) changed = true;
                    out.add(new RecordRec(
                            r.uuid,
                            r.label,
                            enabled,
                            trashed,
                            safe(r.createdAt).isBlank() ? now : r.createdAt,
                            now,
                            r.values
                    ));
                } else {
                    out.add(r);
                }
            }

            if (changed) {
                sortRows(out);
                writeAllLocked(tu, ou, out);
            }
            return changed;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public String normalizeFieldKey(String key) {
        return normalizeFieldKeyStatic(key);
    }

    public LinkedHashMap<String, String> sanitizeValues(Map<String, String> values) {
        return sanitizeFieldValues(values);
    }

    private static ReentrantReadWriteLock lockFor(String tenantUuid, String objectUuid) {
        String k = tenantUuid + "|" + objectUuid;
        return LOCKS.computeIfAbsent(k, x -> new ReentrantReadWriteLock());
    }

    private static Path recordsPath(String tenantUuid, String objectUuid) {
        return Paths.get("data", "tenants", tenantUuid, "custom_objects", objectUuid, "records.xml").toAbsolutePath();
    }

    private static List<RecordRec> readAllLocked(String tenantUuid, String objectUuid) throws Exception {
        ArrayList<RecordRec> out = new ArrayList<RecordRec>();
        Path p = recordsPath(tenantUuid, objectUuid);
        if (!Files.exists(p)) return out;

        Document d = parseXml(p);
        Element root = d == null ? null : d.getDocumentElement();
        if (root == null) return out;

        NodeList nl = root.getElementsByTagName("record");
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (!(n instanceof Element)) continue;
            Element e = (Element) n;

            String uuid = text(e, "uuid");
            if (uuid.isBlank()) uuid = UUID.randomUUID().toString();

            LinkedHashMap<String, String> values = new LinkedHashMap<String, String>();
            NodeList fieldNodes = e.getElementsByTagName("field");
            for (int fi = 0; fi < fieldNodes.getLength(); fi++) {
                Node fn = fieldNodes.item(fi);
                if (!(fn instanceof Element)) continue;
                Element fe = (Element) fn;
                String key = normalizeFieldKeyStatic(fe.getAttribute("key"));
                if (key.isBlank()) continue;
                String value = safe(fe.getTextContent());
                values.put(key, value);
            }

            out.add(new RecordRec(
                    uuid,
                    text(e, "label"),
                    parseBool(text(e, "enabled"), true),
                    parseBool(text(e, "trashed"), false),
                    text(e, "created_at"),
                    text(e, "updated_at"),
                    values
            ));
        }

        sortRows(out);
        return out;
    }

    private static void writeAllLocked(String tenantUuid, String objectUuid, List<RecordRec> rows) throws Exception {
        Path p = recordsPath(tenantUuid, objectUuid);
        Files.createDirectories(p.getParent());

        String now = Instant.now().toString();
        StringBuilder sb = new StringBuilder(4096);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<customObjectRecords object_uuid=\"").append(xmlAttr(objectUuid)).append("\" updated=\"").append(xmlAttr(now)).append("\">\n");

        List<RecordRec> src = rows == null ? List.of() : rows;
        for (int i = 0; i < src.size(); i++) {
            RecordRec r = src.get(i);
            if (r == null) continue;
            String uuid = safe(r.uuid).trim();
            if (uuid.isBlank()) uuid = UUID.randomUUID().toString();

            String createdAt = safe(r.createdAt).isBlank() ? now : r.createdAt;
            String updatedAt = safe(r.updatedAt).isBlank() ? now : r.updatedAt;

            sb.append("  <record>\n");
            sb.append("    <uuid>").append(xmlText(uuid)).append("</uuid>\n");
            sb.append("    <label>").append(xmlText(normalizeLabel(r.label))).append("</label>\n");
            sb.append("    <enabled>").append(r.enabled ? "true" : "false").append("</enabled>\n");
            sb.append("    <trashed>").append(r.trashed ? "true" : "false").append("</trashed>\n");
            sb.append("    <created_at>").append(xmlText(createdAt)).append("</created_at>\n");
            sb.append("    <updated_at>").append(xmlText(updatedAt)).append("</updated_at>\n");

            sb.append("    <fields>\n");
            LinkedHashMap<String, String> values = sanitizeFieldValues(r.values);
            for (Map.Entry<String, String> e : values.entrySet()) {
                if (e == null) continue;
                String key = normalizeFieldKeyStatic(e.getKey());
                if (key.isBlank()) continue;
                sb.append("      <field key=\"").append(xmlAttr(key)).append("\">")
                  .append(xmlText(safe(e.getValue())))
                  .append("</field>\n");
            }
            sb.append("    </fields>\n");

            sb.append("  </record>\n");
        }

        sb.append("</customObjectRecords>\n");
        writeAtomic(p, sb.toString());
    }

    private static LinkedHashMap<String, String> sanitizeFieldValues(Map<String, String> values) {
        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
        if (values == null || values.isEmpty()) return out;

        ArrayList<Map.Entry<String, String>> rows = new ArrayList<Map.Entry<String, String>>(values.entrySet());
        rows.sort(new Comparator<Map.Entry<String, String>>() {
            public int compare(Map.Entry<String, String> a, Map.Entry<String, String> b) {
                String ak = normalizeFieldKeyStatic(a == null ? "" : a.getKey());
                String bk = normalizeFieldKeyStatic(b == null ? "" : b.getKey());
                return ak.compareToIgnoreCase(bk);
            }
        });

        for (int i = 0; i < rows.size(); i++) {
            Map.Entry<String, String> e = rows.get(i);
            if (e == null) continue;
            String key = normalizeFieldKeyStatic(e.getKey());
            if (key.isBlank()) continue;
            String value = safe(e.getValue());
            if (value.length() > 4000) value = value.substring(0, 4000);
            out.put(key, value);
        }
        return out;
    }

    private static String normalizeFieldKeyStatic(String key) {
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

    private static String normalizeLabel(String raw) {
        String v = safe(raw).trim();
        if (v.length() > 200) v = v.substring(0, 200).trim();
        return v;
    }

    private static boolean parseBool(String raw, boolean fallback) {
        String v = safe(raw).trim().toLowerCase(Locale.ROOT);
        if (v.isBlank()) return fallback;
        return "true".equals(v) || "1".equals(v) || "yes".equals(v) || "on".equals(v) || "y".equals(v);
    }

    private static void sortRows(List<RecordRec> rows) {
        if (rows == null) return;
        rows.sort(new Comparator<RecordRec>() {
            public int compare(RecordRec a, RecordRec b) {
                if (a == null && b == null) return 0;
                if (a == null) return 1;
                if (b == null) return -1;

                if (a.trashed != b.trashed) return a.trashed ? 1 : -1;

                int byLabel = safe(a.label).compareToIgnoreCase(safe(b.label));
                if (byLabel != 0) return byLabel;
                return safe(a.uuid).compareToIgnoreCase(safe(b.uuid));
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
