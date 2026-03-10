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
import java.util.LinkedHashMap;
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
 * matter_contacts
 *
 * Tenant-scoped links between matters and contacts:
 * data/tenants/{tenantUuid}/matter_contacts.xml
 */
public final class matter_contacts {

    private static final ConcurrentHashMap<String, ReentrantReadWriteLock> LOCKS = new ConcurrentHashMap<String, ReentrantReadWriteLock>();
    private static final activity_log ACTIVITY_LOGS = activity_log.defaultStore();

    public static matter_contacts defaultStore() {
        return new matter_contacts();
    }

    public static final class LinkRec {
        public final String matterUuid;
        public final String contactUuid;
        public final String source;
        public final String sourceMatterId;
        public final String sourceContactId;
        public final String updatedAt;

        public LinkRec(String matterUuid,
                       String contactUuid,
                       String source,
                       String sourceMatterId,
                       String sourceContactId,
                       String updatedAt) {
            this.matterUuid = safe(matterUuid).trim();
            this.contactUuid = safe(contactUuid).trim();
            this.source = safe(source).trim().toLowerCase();
            this.sourceMatterId = safe(sourceMatterId).trim();
            this.sourceContactId = safe(sourceContactId).trim();
            this.updatedAt = safe(updatedAt).trim();
        }
    }

    public void ensure(String tenantUuid) throws Exception {
        String tu = safe(tenantUuid).trim();
        if (tu.isBlank()) throw new IllegalArgumentException("tenantUuid required");

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            Path p = path(tu);
            Files.createDirectories(p.getParent());
            if (!Files.exists(p)) writeAtomic(p, emptyXml());
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<LinkRec> listAll(String tenantUuid) throws Exception {
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

    public List<LinkRec> listByMatter(String tenantUuid, String matterUuid) throws Exception {
        String mu = safe(matterUuid).trim();
        if (mu.isBlank()) return List.of();
        List<LinkRec> all = listAll(tenantUuid);
        ArrayList<LinkRec> out = new ArrayList<LinkRec>();
        for (LinkRec r : all) {
            if (r == null) continue;
            if (mu.equals(safe(r.matterUuid))) out.add(r);
        }
        return out;
    }

    public List<LinkRec> listByContact(String tenantUuid, String contactUuid) throws Exception {
        String cu = safe(contactUuid).trim();
        if (cu.isBlank()) return List.of();
        List<LinkRec> all = listAll(tenantUuid);
        ArrayList<LinkRec> out = new ArrayList<LinkRec>();
        for (LinkRec r : all) {
            if (r == null) continue;
            if (cu.equals(safe(r.contactUuid))) out.add(r);
        }
        return out;
    }

    public void replaceClioLinksForMatter(String tenantUuid,
                                          String matterUuid,
                                          String clioMatterId,
                                          List<LinkRec> nextLinks) throws Exception {
        replaceSourceLinksForMatter(tenantUuid, matterUuid, "clio", clioMatterId, nextLinks);
    }

    public void replaceNativeLinksForContact(String tenantUuid,
                                             String contactUuid,
                                             List<String> matterUuids) throws Exception {
        String tu = safe(tenantUuid).trim();
        String cu = safe(contactUuid).trim();
        if (tu.isBlank() || cu.isBlank()) throw new IllegalArgumentException("tenantUuid/contactUuid required");

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensure(tu);
            List<LinkRec> all = readAllLocked(tu);
            List<LinkRec> out = new ArrayList<LinkRec>(all.size() + Math.max(0, matterUuids == null ? 0 : matterUuids.size()));
            for (LinkRec row : all) {
                if (row == null) continue;
                if ("native".equalsIgnoreCase(safe(row.source)) && cu.equals(safe(row.contactUuid))) {
                    continue;
                }
                out.add(row);
            }
            String now = Instant.now().toString();
            int requestedCount = 0;
            if (matterUuids != null) {
                for (String mu : matterUuids) {
                    String matterId = safe(mu).trim();
                    if (matterId.isBlank()) continue;
                    out.add(new LinkRec(matterId, cu, "native", "", "", now));
                    requestedCount++;
                }
            }
            List<LinkRec> deduped = dedupe(out);
            writeAllLocked(tu, deduped);
            LinkedHashMap<String, String> details = new LinkedHashMap<String, String>();
            details.put("contact_uuid", cu);
            details.put("source", "native");
            details.put("requested_matter_count", String.valueOf(requestedCount));
            int linked = 0;
            for (LinkRec row : deduped) {
                if (row == null) continue;
                if ("native".equalsIgnoreCase(safe(row.source)) && cu.equals(safe(row.contactUuid))) linked++;
            }
            details.put("linked_matter_count", String.valueOf(linked));
            ACTIVITY_LOGS.logVerbose("contacts.links.replaced_for_contact", tu, "", "", "", details);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void replaceSourceLinksForMatter(String tenantUuid,
                                             String matterUuid,
                                             String source,
                                             String sourceMatterId,
                                             List<LinkRec> nextLinks) throws Exception {
        String tu = safe(tenantUuid).trim();
        String mu = safe(matterUuid).trim();
        String src = safe(source).trim().toLowerCase();
        if (tu.isBlank() || mu.isBlank() || src.isBlank()) {
            throw new IllegalArgumentException("tenantUuid/matterUuid/source required");
        }

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensure(tu);
            List<LinkRec> all = readAllLocked(tu);
            List<LinkRec> out = new ArrayList<LinkRec>(all.size() + Math.max(0, nextLinks == null ? 0 : nextLinks.size()));
            for (LinkRec row : all) {
                if (row == null) continue;
                if (mu.equals(safe(row.matterUuid)) && src.equalsIgnoreCase(safe(row.source))) {
                    continue;
                }
                out.add(row);
            }
            String now = Instant.now().toString();
            int requestedCount = 0;
            if (nextLinks != null) {
                for (LinkRec row : nextLinks) {
                    if (row == null) continue;
                    String cu = safe(row.contactUuid).trim();
                    if (cu.isBlank()) continue;
                    out.add(new LinkRec(
                            mu,
                            cu,
                            src,
                            safe(sourceMatterId).trim(),
                            safe(row.sourceContactId).trim(),
                            now
                    ));
                    requestedCount++;
                }
            }
            List<LinkRec> deduped = dedupe(out);
            writeAllLocked(tu, deduped);
            LinkedHashMap<String, String> details = new LinkedHashMap<String, String>();
            details.put("matter_uuid", mu);
            details.put("source", src);
            details.put("source_matter_id", safe(sourceMatterId).trim());
            details.put("requested_contact_count", String.valueOf(requestedCount));
            int linked = 0;
            for (LinkRec row : deduped) {
                if (row == null) continue;
                if (mu.equals(safe(row.matterUuid)) && src.equalsIgnoreCase(safe(row.source))) linked++;
            }
            details.put("linked_contact_count", String.valueOf(linked));
            ACTIVITY_LOGS.logVerbose("contacts.links.replaced_for_matter", tu, "", mu, "", details);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private static List<LinkRec> dedupe(List<LinkRec> rows) {
        LinkedHashMap<String, LinkRec> map = new LinkedHashMap<String, LinkRec>();
        if (rows == null) return List.of();
        for (LinkRec r : rows) {
            if (r == null) continue;
            String matter = safe(r.matterUuid).trim();
            String contact = safe(r.contactUuid).trim();
            if (matter.isBlank() || contact.isBlank()) continue;
            String key = matter + "|" + contact + "|" + safe(r.source).trim().toLowerCase();
            map.put(key, r);
        }
        return new ArrayList<LinkRec>(map.values());
    }

    private static List<LinkRec> readAllLocked(String tenantUuid) throws Exception {
        Path p = path(tenantUuid);
        if (!Files.exists(p)) return new ArrayList<LinkRec>();

        Document d = parseXml(p);
        Element root = d == null ? null : d.getDocumentElement();
        if (root == null) return new ArrayList<LinkRec>();

        List<LinkRec> out = new ArrayList<LinkRec>();
        NodeList nl = root.getElementsByTagName("matter_contact");
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (!(n instanceof Element)) continue;
            Element e = (Element) n;
            LinkRec rec = new LinkRec(
                    text(e, "matter_uuid"),
                    text(e, "contact_uuid"),
                    text(e, "source"),
                    text(e, "source_matter_id"),
                    text(e, "source_contact_id"),
                    text(e, "updated_at")
            );
            if (safe(rec.matterUuid).isBlank() || safe(rec.contactUuid).isBlank()) continue;
            out.add(rec);
        }
        return out;
    }

    private static void writeAllLocked(String tenantUuid, List<LinkRec> rows) throws Exception {
        Path p = path(tenantUuid);
        Files.createDirectories(p.getParent());
        String now = Instant.now().toString();

        StringBuilder sb = new StringBuilder(8192);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<matter_contacts updated=\"").append(xmlAttr(now)).append("\">\n");
        List<LinkRec> xs = rows == null ? List.of() : rows;
        for (LinkRec r : xs) {
            if (r == null) continue;
            if (safe(r.matterUuid).isBlank() || safe(r.contactUuid).isBlank()) continue;
            sb.append("  <matter_contact>\n");
            sb.append("    <matter_uuid>").append(xmlText(r.matterUuid)).append("</matter_uuid>\n");
            sb.append("    <contact_uuid>").append(xmlText(r.contactUuid)).append("</contact_uuid>\n");
            if (!safe(r.source).isBlank()) sb.append("    <source>").append(xmlText(r.source)).append("</source>\n");
            if (!safe(r.sourceMatterId).isBlank()) sb.append("    <source_matter_id>").append(xmlText(r.sourceMatterId)).append("</source_matter_id>\n");
            if (!safe(r.sourceContactId).isBlank()) sb.append("    <source_contact_id>").append(xmlText(r.sourceContactId)).append("</source_contact_id>\n");
            if (!safe(r.updatedAt).isBlank()) sb.append("    <updated_at>").append(xmlText(r.updatedAt)).append("</updated_at>\n");
            sb.append("  </matter_contact>\n");
        }
        sb.append("</matter_contacts>\n");
        writeAtomic(p, sb.toString());
    }

    private static ReentrantReadWriteLock lockFor(String tenantUuid) {
        return LOCKS.computeIfAbsent(safe(tenantUuid), k -> new ReentrantReadWriteLock());
    }

    private static Path path(String tenantUuid) {
        return Paths.get("data", "tenants", safe(tenantUuid).trim(), "matter_contacts.xml").toAbsolutePath();
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
        return n == null ? "" : safe(n.getTextContent()).trim();
    }

    private static String emptyXml() {
        String now = Instant.now().toString();
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<matter_contacts created=\"" + xmlAttr(now) + "\" updated=\"" + xmlAttr(now) + "\"></matter_contacts>\n";
    }

    private static void writeAtomic(Path p, String content) throws Exception {
        if (p == null) return;
        Files.createDirectories(p.getParent());
        Path tmp = p.resolveSibling(p.getFileName().toString() + ".tmp." + UUID.randomUUID());
        Files.writeString(
                tmp,
                content == null ? "" : content,
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

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
