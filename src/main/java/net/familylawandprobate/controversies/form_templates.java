package net.familylawandprobate.controversies;

import java.io.ByteArrayInputStream;
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
import org.apache.poi.xwpf.usermodel.XWPFDocument;

/**
 * form_templates
 *
 * Tenant-scoped template metadata and binary files:
 *   data/tenants/{tenantUuid}/forms/templates.xml
 *   data/tenants/{tenantUuid}/forms/templates/{templateUuid}.{ext}
 *
 * Supported template file extensions:
 *   docx, doc, rtf, txt
 */
public final class form_templates {

    private static final ConcurrentHashMap<String, ReentrantReadWriteLock> LOCKS = new ConcurrentHashMap<String, ReentrantReadWriteLock>();

    public static form_templates defaultStore() {
        return new form_templates();
    }

    public static final class TemplateRec {
        public final String uuid;
        public final String label;
        public final String folderPath;
        public final boolean enabled;
        public final String updatedAt;
        public final String fileName;
        public final String fileExt;
        public final long sizeBytes;

        public TemplateRec(String uuid,
                           String label,
                           String folderPath,
                           boolean enabled,
                           String updatedAt,
                           String fileName,
                           String fileExt,
                           long sizeBytes) {
            this.uuid = safe(uuid);
            this.label = safe(label);
            this.folderPath = normalizeFolderPathStatic(folderPath);
            this.enabled = enabled;
            this.updatedAt = safe(updatedAt);
            this.fileName = safe(fileName);
            this.fileExt = normalizeExtensionStatic(fileExt);
            this.sizeBytes = Math.max(0L, sizeBytes);
        }
    }

    public void ensure(String tenantUuid) throws Exception {
        String tu = safeFileToken(tenantUuid);
        if (tu.isBlank()) throw new IllegalArgumentException("tenantUuid required");

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            Files.createDirectories(templatesDir(tu));
            Path idx = templatesIndexPath(tu);
            if (!Files.exists(idx)) writeAtomic(idx, emptyXml());
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<TemplateRec> list(String tenantUuid) throws Exception {
        String tu = safeFileToken(tenantUuid);
        if (tu.isBlank()) return List.of();

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.readLock().lock();
        try {
            return readAllLocked(tu);
        } finally {
            lock.readLock().unlock();
        }
    }

    public TemplateRec get(String tenantUuid, String templateUuid) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String id = safe(templateUuid).trim();
        if (tu.isBlank() || id.isBlank()) return null;

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.readLock().lock();
        try {
            List<TemplateRec> all = readAllLocked(tu);
            for (int i = 0; i < all.size(); i++) {
                TemplateRec r = all.get(i);
                if (r != null && id.equals(safe(r.uuid).trim())) return r;
            }
            return null;
        } finally {
            lock.readLock().unlock();
        }
    }

    public TemplateRec create(String tenantUuid, String label, String sourceFileName, byte[] bytes) throws Exception {
        return create(tenantUuid, label, "", sourceFileName, bytes);
    }

    public TemplateRec create(String tenantUuid, String label, String folderPath, String sourceFileName, byte[] bytes) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String lbl = safe(label).trim();
        String fp = normalizeFolderPathStatic(folderPath);
        if (tu.isBlank()) throw new IllegalArgumentException("tenantUuid required");
        if (lbl.isBlank()) throw new IllegalArgumentException("Template name is required");

        String ext = normalizeExtensionStatic(sourceFileName);
        if (!isSupportedExtension(ext)) {
            throw new IllegalArgumentException("Unsupported template type. Use .docx, .doc, or .rtf.");
        }
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("Template file is required");
        }
        validateTemplateBytes(ext, bytes);

        String safeFileName = sanitizeFileName(sourceFileName, ext);

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensure(tu);

            String id = UUID.randomUUID().toString();
            String now = Instant.now().toString();

            Path filePath = templateBodyPath(tu, id, ext);
            Files.createDirectories(filePath.getParent());
            Files.write(filePath, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            List<TemplateRec> all = readAllLocked(tu);
            TemplateRec rec = new TemplateRec(id, lbl, fp, true, now, safeFileName, ext, bytes.length);
            all.add(rec);
            sortByFolderThenLabel(all);
            writeAllLocked(tu, all);
            return rec;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean updateMeta(String tenantUuid, String templateUuid, String label) throws Exception {
        return updateMetaInternal(tenantUuid, templateUuid, label, "", true);
    }

    public boolean updateMeta(String tenantUuid, String templateUuid, String label, String folderPath) throws Exception {
        return updateMetaInternal(tenantUuid, templateUuid, label, folderPath, false);
    }

    private boolean updateMetaInternal(String tenantUuid, String templateUuid, String label, String folderPath, boolean preserveFolderPath) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String id = safe(templateUuid).trim();
        String lbl = safe(label).trim();
        String fp = normalizeFolderPathStatic(folderPath);
        if (tu.isBlank() || id.isBlank()) throw new IllegalArgumentException("tenantUuid/templateUuid required");
        if (lbl.isBlank()) throw new IllegalArgumentException("Template name is required");

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensure(tu);

            List<TemplateRec> all = readAllLocked(tu);
            List<TemplateRec> out = new ArrayList<TemplateRec>(all.size());
            boolean found = false;
            String now = Instant.now().toString();

            for (int i = 0; i < all.size(); i++) {
                TemplateRec r = all.get(i);
                if (r == null) continue;
                if (id.equals(safe(r.uuid).trim())) {
                    out.add(new TemplateRec(
                            id,
                            lbl,
                            preserveFolderPath ? r.folderPath : fp,
                            true,
                            now,
                            r.fileName,
                            r.fileExt,
                            r.sizeBytes
                    ));
                    found = true;
                } else {
                    out.add(r);
                }
            }
            if (!found) return false;

            sortByFolderThenLabel(out);
            writeAllLocked(tu, out);
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean replaceFile(String tenantUuid, String templateUuid, String sourceFileName, byte[] bytes) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String id = safe(templateUuid).trim();
        if (tu.isBlank() || id.isBlank()) throw new IllegalArgumentException("tenantUuid/templateUuid required");

        String ext = normalizeExtensionStatic(sourceFileName);
        if (!isSupportedExtension(ext)) {
            throw new IllegalArgumentException("Unsupported template type. Use .docx, .doc, or .rtf.");
        }
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("Template file is required");
        }
        validateTemplateBytes(ext, bytes);

        String safeFileName = sanitizeFileName(sourceFileName, ext);

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensure(tu);

            List<TemplateRec> all = readAllLocked(tu);
            List<TemplateRec> out = new ArrayList<TemplateRec>(all.size());
            boolean found = false;
            String now = Instant.now().toString();

            for (int i = 0; i < all.size(); i++) {
                TemplateRec r = all.get(i);
                if (r == null) continue;

                if (id.equals(safe(r.uuid).trim())) {
                    found = true;

                    Path oldFile = templateBodyPath(tu, id, normalizeExtensionStatic(r.fileExt));
                    Path newFile = templateBodyPath(tu, id, ext);

                    Files.createDirectories(newFile.getParent());
                    Files.write(newFile, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

                    if (!oldFile.equals(newFile)) deleteQuiet(oldFile);

                    out.add(new TemplateRec(
                            r.uuid,
                            r.label,
                            r.folderPath,
                            true,
                            now,
                            safeFileName,
                            ext,
                            bytes.length
                    ));
                } else {
                    out.add(r);
                }
            }
            if (!found) return false;

            sortByFolderThenLabel(out);
            writeAllLocked(tu, out);
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean delete(String tenantUuid, String templateUuid) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String id = safe(templateUuid).trim();
        if (tu.isBlank() || id.isBlank()) return false;

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensure(tu);
            List<TemplateRec> all = readAllLocked(tu);
            List<TemplateRec> out = new ArrayList<TemplateRec>(all.size());
            boolean removed = false;

            for (int i = 0; i < all.size(); i++) {
                TemplateRec r = all.get(i);
                if (r == null) continue;
                if (id.equals(safe(r.uuid).trim())) {
                    removed = true;
                    deleteQuiet(templateBodyPath(tu, id, normalizeExtensionStatic(r.fileExt)));
                    continue;
                }
                out.add(r);
            }
            if (!removed) return false;

            writeAllLocked(tu, out);
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public byte[] readBytes(String tenantUuid, String templateUuid) {
        String tu = safeFileToken(tenantUuid);
        String id = safe(templateUuid).trim();
        if (tu.isBlank() || id.isBlank()) return new byte[0];

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.readLock().lock();
        try {
            TemplateRec rec = get(tu, id);
            if (rec == null) return new byte[0];

            Path p = templateBodyPath(tu, id, normalizeExtensionStatic(rec.fileExt));
            if (Files.exists(p)) return Files.readAllBytes(p);

            Path legacy = templateBodyPath(tu, id, "txt");
            if (Files.exists(legacy)) return Files.readAllBytes(legacy);

            return new byte[0];
        } catch (Exception ignored) {
            return new byte[0];
        } finally {
            lock.readLock().unlock();
        }
    }

    public String normalizeExtension(String extOrFileName) {
        return normalizeExtensionStatic(extOrFileName);
    }

    public boolean isSupportedExtension(String extOrFileName) {
        String ext = normalizeExtensionStatic(extOrFileName);
        return "docx".equals(ext) || "doc".equals(ext) || "rtf".equals(ext) || "txt".equals(ext);
    }

    private static ReentrantReadWriteLock lockFor(String tenantUuid) {
        return LOCKS.computeIfAbsent(tenantUuid, k -> new ReentrantReadWriteLock());
    }

    private static Path tenantDir(String tenantUuid) {
        return Paths.get("data", "tenants", tenantUuid).toAbsolutePath();
    }

    private static Path formsDir(String tenantUuid) {
        return tenantDir(tenantUuid).resolve("forms");
    }

    private static Path templatesDir(String tenantUuid) {
        return formsDir(tenantUuid).resolve("templates");
    }

    private static Path templatesIndexPath(String tenantUuid) {
        return formsDir(tenantUuid).resolve("templates.xml");
    }

    private static Path templateBodyPath(String tenantUuid, String templateUuid, String ext) {
        String cleanExt = normalizeExtensionStatic(ext);
        if (cleanExt.isBlank()) cleanExt = "txt";
        return templatesDir(tenantUuid).resolve(safeFileToken(templateUuid) + "." + cleanExt);
    }

    private static List<TemplateRec> readAllLocked(String tenantUuid) throws Exception {
        ArrayList<TemplateRec> out = new ArrayList<TemplateRec>();
        Path p = templatesIndexPath(tenantUuid);
        if (!Files.exists(p)) return out;

        Document d = parseXml(p);
        Element root = (d == null) ? null : d.getDocumentElement();
        if (root == null) return out;

        NodeList nl = root.getElementsByTagName("template");
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (!(n instanceof Element)) continue;
            Element e = (Element) n;

            String uuid = text(e, "uuid");
            String label = text(e, "label");
            String folderPath = normalizeFolderPathStatic(text(e, "folder_path"));
            boolean enabled = parseBool(text(e, "enabled"), true);
            String updated = text(e, "updated_at");
            String fileName = text(e, "file_name");
            String fileExt = normalizeExtensionStatic(text(e, "file_ext"));
            long size = parseLong(text(e, "size_bytes"), 0L);

            if (safe(uuid).trim().isBlank()) continue;
            if (safe(label).trim().isBlank()) continue;
            if (!enabled) continue;

            if (fileExt.isBlank()) fileExt = normalizeExtensionStatic(fileName);
            if (fileExt.isBlank()) fileExt = "txt";
            if (safe(fileName).trim().isBlank()) fileName = safeFileToken(uuid) + "." + fileExt;

            out.add(new TemplateRec(uuid.trim(), label.trim(), folderPath, true, updated, fileName, fileExt, size));
        }

        sortByFolderThenLabel(out);
        return out;
    }

    private static void writeAllLocked(String tenantUuid, List<TemplateRec> all) throws Exception {
        Path p = templatesIndexPath(tenantUuid);
        Files.createDirectories(p.getParent());

        String now = Instant.now().toString();
        StringBuilder sb = new StringBuilder(4096);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<formTemplates updated=\"").append(xmlAttr(now)).append("\">\n");

        List<TemplateRec> rows = all == null ? List.of() : all;
        for (int i = 0; i < rows.size(); i++) {
            TemplateRec r = rows.get(i);
            if (r == null) continue;
            sb.append("  <template>\n");
            sb.append("    <uuid>").append(xmlText(r.uuid)).append("</uuid>\n");
            sb.append("    <enabled>true</enabled>\n");
            sb.append("    <label>").append(xmlText(r.label)).append("</label>\n");
            sb.append("    <folder_path>").append(xmlText(normalizeFolderPathStatic(r.folderPath))).append("</folder_path>\n");
            sb.append("    <file_name>").append(xmlText(safe(r.fileName))).append("</file_name>\n");
            sb.append("    <file_ext>").append(xmlText(normalizeExtensionStatic(r.fileExt))).append("</file_ext>\n");
            sb.append("    <size_bytes>").append(r.sizeBytes).append("</size_bytes>\n");
            sb.append("    <updated_at>").append(xmlText(safe(r.updatedAt).isBlank() ? now : r.updatedAt)).append("</updated_at>\n");
            sb.append("  </template>\n");
        }

        sb.append("</formTemplates>\n");
        writeAtomic(p, sb.toString());
    }

    private static String sanitizeFileName(String sourceFileName, String fallbackExt) {
        String in = safe(sourceFileName).trim();
        if (in.isBlank()) {
            return "template." + normalizeExtensionStatic(fallbackExt);
        }

        String out = in.replace("\\", "/");
        int slash = out.lastIndexOf('/');
        if (slash >= 0) out = out.substring(slash + 1);

        out = out.replaceAll("[^A-Za-z0-9._ -]", "_").trim();
        if (out.isBlank()) out = "template";

        String ext = normalizeExtensionStatic(out);
        if (ext.isBlank()) {
            ext = normalizeExtensionStatic(fallbackExt);
            out = out + "." + ext;
        }

        if (out.length() > 180) out = out.substring(0, 180);
        return out;
    }

    private static String normalizeExtensionStatic(String extOrFileName) {
        String v = safe(extOrFileName).trim().toLowerCase(Locale.ROOT);
        if (v.isBlank()) return "";

        int slash = Math.max(v.lastIndexOf('/'), v.lastIndexOf('\\'));
        if (slash >= 0 && slash + 1 < v.length()) v = v.substring(slash + 1);

        int dot = v.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < v.length()) {
            v = v.substring(dot + 1);
        }

        v = v.replaceAll("[^a-z0-9]", "");
        return v;
    }

    public String normalizeFolderPath(String raw) {
        return normalizeFolderPathStatic(raw);
    }

    private static String normalizeFolderPathStatic(String raw) {
        String in = safe(raw).trim();
        if (in.isBlank()) return "";

        String[] pieces = in.replace('\\', '/').split("/");
        ArrayList<String> cleanPieces = new ArrayList<String>();
        for (int i = 0; i < pieces.length; i++) {
            String part = safe(pieces[i]).trim();
            if (part.isBlank()) continue;
            part = part.replaceAll("[^A-Za-z0-9._ -]", "_").trim();
            if (part.isBlank()) continue;
            if (part.length() > 64) part = part.substring(0, 64).trim();
            if (!part.isBlank()) cleanPieces.add(part);
        }
        if (cleanPieces.isEmpty()) return "";

        String joined = String.join("/", cleanPieces);
        if (joined.length() > 240) joined = joined.substring(0, 240);
        while (joined.startsWith("/")) joined = joined.substring(1);
        while (joined.endsWith("/")) joined = joined.substring(0, joined.length() - 1);
        return joined;
    }

    private static void sortByFolderThenLabel(List<TemplateRec> out) {
        if (out == null) return;
        out.sort(new Comparator<TemplateRec>() {
            public int compare(TemplateRec a, TemplateRec b) {
                String af = safe(a == null ? "" : a.folderPath);
                String bf = safe(b == null ? "" : b.folderPath);
                int byFolder = af.compareToIgnoreCase(bf);
                if (byFolder != 0) return byFolder;

                String al = safe(a == null ? "" : a.label);
                String bl = safe(b == null ? "" : b.label);
                int byLabel = al.compareToIgnoreCase(bl);
                if (byLabel != 0) return byLabel;

                return safe(a == null ? "" : a.uuid).compareToIgnoreCase(safe(b == null ? "" : b.uuid));
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

    private static void deleteQuiet(Path p) {
        try {
            if (p != null) Files.deleteIfExists(p);
        } catch (Exception ignored) {}
    }

    private static long parseLong(String s, long def) {
        try {
            return Long.parseLong(safe(s).trim());
        } catch (Exception ignored) {
            return def;
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static void validateTemplateBytes(String ext, byte[] bytes) {
        String normalized = normalizeExtensionStatic(ext);
        if (!"docx".equals(normalized)) return;
        if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("Template file is required");

        try (XWPFDocument ignored = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            // Valid DOCX package.
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid DOCX template package.");
        }
    }

    private static String safeFileToken(String s) {
        String t = safe(s).trim();
        if (t.isBlank()) return "";
        return t.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static boolean parseBool(String s, boolean def) {
        String v = safe(s).trim().toLowerCase(Locale.ROOT);
        if (v.isBlank()) return def;
        return "true".equals(v) || "1".equals(v) || "yes".equals(v) || "y".equals(v) || "on".equals(v);
    }

    private static String text(Element parent, String childTag) {
        if (parent == null || childTag == null) return "";
        NodeList nl = parent.getElementsByTagName(childTag);
        if (nl == null || nl.getLength() == 0) return "";
        Node n = nl.item(0);
        return n == null ? "" : safe(n.getTextContent()).trim();
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

    private static String emptyXml() {
        String now = Instant.now().toString();
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<formTemplates created=\"" + xmlAttr(now) + "\" updated=\"" + xmlAttr(now) + "\"></formTemplates>\n";
    }
}
