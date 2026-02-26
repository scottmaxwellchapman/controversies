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
import java.util.List;
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
 * matters
 *
 * data/tenants/{tenantUuid}/matters.xml
 * data/tenants/{tenantUuid}/matters/{matterUuid}/
 *
 * Soft-delete via <trashed>true</trashed>. No hard deletes.
 */
public final class matters {

    private static final ConcurrentHashMap<String, ReentrantReadWriteLock> LOCKS = new ConcurrentHashMap<>();

    public static matters defaultStore() { return new matters(); }

    public static final class MatterRec {
        public final String uuid;
        public final boolean enabled;
        public final boolean trashed;
        public final String label;

        public final String jurisdictionUuid;
        public final String matterCategoryUuid;
        public final String matterSubcategoryUuid;
        public final String matterStatusUuid;
        public final String matterSubstatusUuid;

        
        // Optional fields (v1)
        public String causeDocketNumber;
        public String county;
public MatterRec(String uuid,
                         boolean enabled,
                         boolean trashed,
                         String label,
                         String jurisdictionUuid,
                         String matterCategoryUuid,
                         String matterSubcategoryUuid,
                         String matterStatusUuid,
                         String matterSubstatusUuid) {
            this.uuid = safe(uuid);
            this.enabled = enabled;
            this.trashed = trashed;
            this.label = safe(label);

            this.jurisdictionUuid = safe(jurisdictionUuid);
            this.matterCategoryUuid = safe(matterCategoryUuid);
            this.matterSubcategoryUuid = safe(matterSubcategoryUuid);
            this.matterStatusUuid = safe(matterStatusUuid);
            this.matterSubstatusUuid = safe(matterSubstatusUuid);
        }
    }

    // -----------------------------
    // Public API
    // -----------------------------
    public void ensure(String tenantUuid) throws Exception {
        String tu = safe(tenantUuid).trim();
        if (tu.isBlank()) throw new IllegalArgumentException("tenantUuid required");

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            Path file = mattersPath(tu);
            Files.createDirectories(file.getParent());
            Files.createDirectories(mattersDir(tu));
            if (!Files.exists(file)) writeAtomic(file, emptyMattersXml());
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Returns all matters (including trashed). Filter in caller. */
    public List<MatterRec> listAll(String tenantUuid) throws Exception {
        String tu = safe(tenantUuid).trim();
        if (tu.isBlank()) return List.of();

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.readLock().lock();
        try {
            return readAllLocked(tu);
        } finally {
            lock.readLock().unlock();
        }
    }

    public MatterRec getByUuid(String tenantUuid, String matterUuid) throws Exception {
        String tu = safe(tenantUuid).trim();
        String mu = safe(matterUuid).trim();
        if (tu.isBlank() || mu.isBlank()) return null;

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.readLock().lock();
        try {
            List<MatterRec> all = readAllLocked(tu);
            for (int i = 0; i < all.size(); i++) {
                MatterRec m = all.get(i);
                if (m != null && mu.equals(safe(m.uuid).trim())) return m;
            }
            return null;
        } finally {
            lock.readLock().unlock();
        }
    }

    public MatterRec create(String tenantUuid,
                            String label,
                            String jurisdictionUuid,
                            String matterCategoryUuid,
                            String matterSubcategoryUuid,
                            String matterStatusUuid,
                            String matterSubstatusUuid) throws Exception {

        String tu = safe(tenantUuid).trim();
        String lbl = safe(label).trim();

        if (tu.isBlank()) throw new IllegalArgumentException("tenantUuid required");
        if (lbl.isBlank()) throw new IllegalArgumentException("label required");

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensure(tu);

            String uuid = UUID.randomUUID().toString();

            // Create matter folder
            Files.createDirectories(matterFolder(tu, uuid));

            List<MatterRec> all = readAllLocked(tu);
            MatterRec rec = new MatterRec(
                    uuid,
                    true,   // enabled
                    false,  // trashed
                    lbl,
                    safe(jurisdictionUuid).trim(),
                    safe(matterCategoryUuid).trim(),
                    safe(matterSubcategoryUuid).trim(),
                    safe(matterStatusUuid).trim(),
                    safe(matterSubstatusUuid).trim()
            );
            rec.causeDocketNumber = "";
            rec.county = "";

            all.add(rec);
            sortByLabel(all);
            writeAllLocked(tu, all);
            return rec;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean update(String tenantUuid,
                          String uuid,
                          String label,
                          String jurisdictionUuid,
                          String matterCategoryUuid,
                          String matterSubcategoryUuid,
                          String matterStatusUuid,
                          String matterSubstatusUuid) throws Exception {

        String tu = safe(tenantUuid).trim();
        String id = safe(uuid).trim();
        String lbl = safe(label).trim();

        if (tu.isBlank()) throw new IllegalArgumentException("tenantUuid required");
        if (id.isBlank()) throw new IllegalArgumentException("uuid required");
        if (lbl.isBlank()) throw new IllegalArgumentException("label required");

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensure(tu);
            Files.createDirectories(matterFolder(tu, id));

            List<MatterRec> all = readAllLocked(tu);
            boolean changed = false;
            List<MatterRec> out = new ArrayList<MatterRec>(all.size());

            for (int i = 0; i < all.size(); i++) {
                MatterRec m = all.get(i);
                if (m == null) continue;

                if (id.equals(safe(m.uuid).trim())) {
                    MatterRec next = new MatterRec(
                            id,
                            m.enabled,
                            m.trashed,
                            lbl,
                            safe(jurisdictionUuid).trim(),
                            safe(matterCategoryUuid).trim(),
                            safe(matterSubcategoryUuid).trim(),
                            safe(matterStatusUuid).trim(),
                            safe(matterSubstatusUuid).trim()
                    );
                    // Preserve optional case metadata on updates that don't provide it.
                    next.causeDocketNumber = safe(m.causeDocketNumber);
                    next.county = safe(m.county);
                    out.add(next);
                    changed = true;
                } else {
                    out.add(m);
                }
            }

            if (changed) {
                sortByLabel(out);
                writeAllLocked(tu, out);
            }
            return changed;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Soft-delete: sets trashed=true and enabled=false. */
    public boolean trash(String tenantUuid, String uuid) throws Exception {
        return setTrashFlag(tenantUuid, uuid, true);
    }

    /** Restore: sets trashed=false and enabled=true. */
    public boolean restore(String tenantUuid, String uuid) throws Exception {
        return setTrashFlag(tenantUuid, uuid, false);
    }

    public Path matterFolderPath(String tenantUuid, String matterUuid) {
        String tu = safe(tenantUuid).trim();
        String mu = safe(matterUuid).trim();
        if (tu.isBlank() || mu.isBlank()) return null;
        return matterFolder(tu, mu);
    }

    // -----------------------------
    // Internals
    // -----------------------------
    private boolean setTrashFlag(String tenantUuid, String uuid, boolean trashed) throws Exception {
        String tu = safe(tenantUuid).trim();
        String id = safe(uuid).trim();
        if (tu.isBlank() || id.isBlank()) return false;

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensure(tu);
            Files.createDirectories(matterFolder(tu, id));

            List<MatterRec> all = readAllLocked(tu);
            boolean changed = false;
            List<MatterRec> out = new ArrayList<MatterRec>(all.size());

            for (int i = 0; i < all.size(); i++) {
                MatterRec m = all.get(i);
                if (m == null) continue;

                if (id.equals(safe(m.uuid).trim())) {
                    boolean en = trashed ? false : true;
                    if (m.trashed != trashed || m.enabled != en) changed = true;

                    MatterRec next = new MatterRec(
                            m.uuid,
                            en,
                            trashed,
                            m.label,
                            m.jurisdictionUuid,
                            m.matterCategoryUuid,
                            m.matterSubcategoryUuid,
                            m.matterStatusUuid,
                            m.matterSubstatusUuid
                    );
                    // Preserve optional metadata when archiving/restoring.
                    next.causeDocketNumber = safe(m.causeDocketNumber);
                    next.county = safe(m.county);
                    out.add(next);
                } else {
                    out.add(m);
                }
            }

            if (changed) {
                sortByLabel(out);
                writeAllLocked(tu, out);
            }
            return changed;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private static void sortByLabel(List<MatterRec> xs) {
        if (xs == null) return;
        xs.sort(new Comparator<MatterRec>() {
            public int compare(MatterRec a, MatterRec b) {
                return safe(a.label).compareToIgnoreCase(safe(b.label));
            }
        });
    }

    private static ReentrantReadWriteLock lockFor(String tenantUuid) {
        return LOCKS.computeIfAbsent(tenantUuid, k -> new ReentrantReadWriteLock());
    }

    private static Path tenantDir(String tenantUuid) {
        return Paths.get("data", "tenants", tenantUuid).toAbsolutePath();
    }

    private static Path mattersPath(String tenantUuid) {
        return tenantDir(tenantUuid).resolve("matters.xml");
    }

    private static Path mattersDir(String tenantUuid) {
        return tenantDir(tenantUuid).resolve("matters");
    }

    private static Path matterFolder(String tenantUuid, String matterUuid) {
        return mattersDir(tenantUuid).resolve(matterUuid);
    }

    private static List<MatterRec> readAllLocked(String tenantUuid) throws Exception {
        Path p = mattersPath(tenantUuid);
        if (!Files.exists(p)) return new ArrayList<MatterRec>();

        Document d = parseXml(p);
        Element root = (d == null) ? null : d.getDocumentElement();
        if (root == null) return new ArrayList<MatterRec>();

        List<MatterRec> out = new ArrayList<MatterRec>();
        NodeList nl = root.getElementsByTagName("matter");
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (!(n instanceof Element)) continue;
            Element e = (Element) n;

            String uuid = text(e, "uuid");
            String label = text(e, "label");

            // default older files: enabled=true, trashed=false
            boolean enabled = parseBool(text(e, "enabled"), true);
            boolean trashed = parseBool(text(e, "trashed"), false);

            String jur = text(e, "jurisdiction_uuid");
            String cat = text(e, "matter_category_uuid");
            String sub = text(e, "matter_subcategory_uuid");
            String st  = text(e, "matter_status_uuid");
            String sst = text(e, "matter_substatus_uuid");

            if (safe(uuid).trim().isBlank() || safe(label).trim().isBlank()) continue;

            {
            MatterRec _r = new MatterRec(uuid, enabled, trashed, label, jur, cat, sub, st, sst);
            String _cause = text(e, "causeDocketNumber");
            String _county = text(e, "county");
            _r.causeDocketNumber = _cause;
            _r.county = _county;
            out.add(_r);
        }
        }
        return out;
    }

    private static void writeAllLocked(String tenantUuid, List<MatterRec> list) throws Exception {
        Path p = mattersPath(tenantUuid);
        Files.createDirectories(p.getParent());

        String now = Instant.now().toString();

        StringBuilder sb = new StringBuilder(16384);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<matters updated=\"").append(xmlAttr(now)).append("\">\n");

        List<MatterRec> xs = (list == null) ? List.of() : list;
        for (int i = 0; i < xs.size(); i++) {
            MatterRec m = xs.get(i);
            if (m == null) continue;

            sb.append("  <matter>\n");
            sb.append("    <uuid>").append(xmlText(m.uuid)).append("</uuid>\n");
            sb.append("    <enabled>").append(m.enabled ? "true" : "false").append("</enabled>\n");
            sb.append("    <trashed>").append(m.trashed ? "true" : "false").append("</trashed>\n");
            sb.append("    <label>").append(xmlText(m.label)).append("</label>\n");

            
            if (m.causeDocketNumber != null && !m.causeDocketNumber.isBlank()) {
                sb.append("    <causeDocketNumber>").append(xmlText(m.causeDocketNumber)).append("</causeDocketNumber>\n");
            }
            if (m.county != null && !m.county.isBlank()) {
                sb.append("    <county>").append(xmlText(m.county)).append("</county>\n");
            }
            sb.append("    <jurisdiction_uuid>").append(xmlText(m.jurisdictionUuid)).append("</jurisdiction_uuid>\n");
            sb.append("    <matter_category_uuid>").append(xmlText(m.matterCategoryUuid)).append("</matter_category_uuid>\n");
            sb.append("    <matter_subcategory_uuid>").append(xmlText(m.matterSubcategoryUuid)).append("</matter_subcategory_uuid>\n");
            sb.append("    <matter_status_uuid>").append(xmlText(m.matterStatusUuid)).append("</matter_status_uuid>\n");
            sb.append("    <matter_substatus_uuid>").append(xmlText(m.matterSubstatusUuid)).append("</matter_substatus_uuid>\n");
            sb.append("  </matter>\n");
        }

        sb.append("</matters>\n");
        writeAtomic(p, sb.toString());
    }
    public MatterRec create(String tenantUuid,
                            String label,
                            String jurisdictionUuid,
                            String matterCategoryUuid,
                            String matterSubcategoryUuid,
                            String matterStatusUuid,
                            String matterSubstatusUuid,
                            String causeDocketNumber,
                            String county) throws Exception {

        String tu = safe(tenantUuid).trim();
        String lbl = safe(label).trim();

        if (tu.isBlank()) throw new IllegalArgumentException("tenantUuid required");
        if (lbl.isBlank()) throw new IllegalArgumentException("label required");

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensure(tu);

            String uuid = UUID.randomUUID().toString();
            Files.createDirectories(matterFolder(tu, uuid));

            List<MatterRec> all = readAllLocked(tu);
            MatterRec rec = new MatterRec(
                    uuid,
                    true,
                    false,
                    lbl,
                    safe(jurisdictionUuid).trim(),
                    safe(matterCategoryUuid).trim(),
                    safe(matterSubcategoryUuid).trim(),
                    safe(matterStatusUuid).trim(),
                    safe(matterSubstatusUuid).trim()
            );
            rec.causeDocketNumber = safe(causeDocketNumber);
            rec.county = safe(county);

            all.add(rec);
            sortByLabel(all);
            writeAllLocked(tu, all);
            return rec;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean update(String tenantUuid,
                          String uuid,
                          String label,
                          String jurisdictionUuid,
                          String matterCategoryUuid,
                          String matterSubcategoryUuid,
                          String matterStatusUuid,
                          String matterSubstatusUuid,
                          String causeDocketNumber,
                          String county) throws Exception {

        String tu = safe(tenantUuid).trim();
        String id = safe(uuid).trim();
        String lbl = safe(label).trim();

        if (tu.isBlank()) throw new IllegalArgumentException("tenantUuid required");
        if (id.isBlank()) throw new IllegalArgumentException("uuid required");
        if (lbl.isBlank()) throw new IllegalArgumentException("label required");

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensure(tu);
            Files.createDirectories(matterFolder(tu, id));

            List<MatterRec> all = readAllLocked(tu);
            boolean changed = false;
            List<MatterRec> out = new ArrayList<MatterRec>(all.size());

            for (int i = 0; i < all.size(); i++) {
                MatterRec m = all.get(i);
                if (m == null) continue;

                if (id.equals(safe(m.uuid).trim())) {
                    MatterRec next = new MatterRec(
                            id,
                            m.enabled,
                            m.trashed,
                            lbl,
                            safe(jurisdictionUuid).trim(),
                            safe(matterCategoryUuid).trim(),
                            safe(matterSubcategoryUuid).trim(),
                            safe(matterStatusUuid).trim(),
                            safe(matterSubstatusUuid).trim()
                    );
                    next.causeDocketNumber = safe(causeDocketNumber);
                    next.county = safe(county);
                    out.add(next);
                    changed = true;
                } else {
                    out.add(m);
                }
            }

            if (changed) {
                sortByLabel(out);
                writeAllLocked(tu, out);
            }
            return changed;
        } finally {
            lock.writeLock().unlock();
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

    private static String text(Element parent, String childTag) {
        if (parent == null || childTag == null) return "";
        NodeList nl = parent.getElementsByTagName(childTag);
        if (nl == null || nl.getLength() == 0) return "";
        Node n = nl.item(0);
        return (n == null) ? "" : safe(n.getTextContent()).trim();
    }

    private static void writeAtomic(Path p, String content) throws Exception {
        if (p == null) return;
        Files.createDirectories(p.getParent());

        Path tmp = p.resolveSibling(p.getFileName().toString() + ".tmp." + UUID.randomUUID());
        Files.writeString(tmp, content == null ? "" : content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        try {
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception atomicNotSupported) {
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private static boolean parseBool(String s, boolean def) {
        String v = safe(s).trim().toLowerCase();
        if (v.isBlank()) return def;
        return "true".equals(v) || "1".equals(v) || "yes".equals(v) || "y".equals(v) || "on".equals(v);
    }

    private static String xmlAttr(String s) {
        if (s == null) return "";
        return s.replace("&","&amp;")
                .replace("\"","&quot;")
                .replace("<","&lt;")
                .replace(">","&gt;")
                .replace("'","&apos;");
    }

    private static String xmlText(String s) {
        if (s == null) return "";
        return s.replace("&","&amp;")
                .replace("<","&lt;")
                .replace(">","&gt;")
                .replace("\"","&quot;")
                .replace("'","&apos;");
    }

    private static String emptyMattersXml() {
        String now = Instant.now().toString();
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
             + "<matters created=\"" + xmlAttr(now) + "\" updated=\"" + xmlAttr(now) + "\"></matters>\n";
    }
}
