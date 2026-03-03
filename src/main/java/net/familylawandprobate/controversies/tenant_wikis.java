package net.familylawandprobate.controversies;

import org.jsoup.Jsoup;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class tenant_wikis {

    public static final long MAX_ATTACHMENT_BYTES = 50L * 1024L * 1024L;

    private static final ConcurrentHashMap<String, ReentrantReadWriteLock> LOCKS = new ConcurrentHashMap<String, ReentrantReadWriteLock>();
    private static final Cleaner HTML_CLEANER = new Cleaner(
            Safelist.relaxed()
                    .addTags("table", "thead", "tbody", "tfoot", "tr", "th", "td", "hr", "span", "div")
                    .addAttributes(":all", "class")
                    .addAttributes("a", "target", "rel")
                    .addProtocols("a", "href", "http", "https", "mailto")
                    .addProtocols("img", "src", "http", "https", "data")
    );

    public static final class PageRec {
        public String uuid;
        public String title;
        public String slug;
        public String parentUuid;
        public String permissionKey;
        public int navOrder;
        public String createdBy;
        public String createdAt;
        public String updatedAt;
        public String currentRevisionUuid;
        public boolean archived;
    }

    public static final class RevisionRec {
        public String uuid;
        public String label;
        public String summary;
        public String editorUuid;
        public String editorEmail;
        public String createdAt;
        public String baseRevisionUuid;
        public String checksumSha256;
        public String fileSizeBytes;
        public String storageFile;
        public boolean current;
    }

    public static final class AttachmentRec {
        public String uuid;
        public String fileName;
        public String mimeType;
        public String checksumSha256;
        public String fileSizeBytes;
        public String storageFile;
        public String uploadedBy;
        public String uploadedAt;
    }

    public static final class DiffLine {
        public char type; // ' ', '+', '-'
        public int leftLine;
        public int rightLine;
        public String text;
    }

    public static final class DiffResult {
        public final List<DiffLine> lines = new ArrayList<DiffLine>();
        public int added;
        public int removed;
    }

    public static tenant_wikis defaultStore() {
        return new tenant_wikis();
    }

    public void ensure(String tenantUuid) throws Exception {
        String tu = safeFileToken(tenantUuid);
        if (tu.isBlank()) throw new IllegalArgumentException("tenantUuid required");

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            Path wikiRoot = wikiRoot(tu);
            Files.createDirectories(wikiRoot);
            Files.createDirectories(pagesDir(tu));
            Path pagesIndex = pagesPath(tu);
            if (!Files.exists(pagesIndex)) {
                document_workflow_support.writeAtomic(
                        pagesIndex,
                        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<wiki_pages updated=\"" + document_workflow_support.xmlText(document_workflow_support.nowIso()) + "\"></wiki_pages>\n"
                );
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<PageRec> listPages(String tenantUuid, boolean includeArchived) throws Exception {
        String tu = safeFileToken(tenantUuid);
        if (tu.isBlank()) return List.of();

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.readLock().lock();
        try {
            List<PageRec> all = readPagesLocked(tu);
            if (includeArchived) {
                all.sort(Comparator.comparingInt((PageRec p) -> p.navOrder).thenComparing(p -> safe(p.title).toLowerCase(Locale.ROOT)));
                return all;
            }
            List<PageRec> out = new ArrayList<PageRec>();
            for (PageRec p : all) {
                if (p == null || p.archived) continue;
                out.add(p);
            }
            out.sort(Comparator.comparingInt((PageRec p) -> p.navOrder).thenComparing(p -> safe(p.title).toLowerCase(Locale.ROOT)));
            return out;
        } finally {
            lock.readLock().unlock();
        }
    }

    public PageRec getPage(String tenantUuid, String pageUuid) throws Exception {
        String pu = safe(pageUuid).trim();
        if (pu.isBlank()) return null;
        for (PageRec rec : listPages(tenantUuid, true)) {
            if (rec == null) continue;
            if (pu.equals(safe(rec.uuid).trim())) return rec;
        }
        return null;
    }

    public PageRec createPage(String tenantUuid,
                              String title,
                              String slug,
                              String parentUuid,
                              String permissionKey,
                              String createdBy,
                              String initialSummary,
                              String initialHtml) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String pageTitle = safe(title).trim();
        if (tu.isBlank()) throw new IllegalArgumentException("tenantUuid required");
        if (pageTitle.isBlank()) throw new IllegalArgumentException("Page title is required.");

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensure(tu);
            List<PageRec> pages = readPagesLocked(tu);

            String parent = safe(parentUuid).trim();
            if (!parent.isBlank() && findPage(pages, parent) == null) parent = "";

            String now = document_workflow_support.nowIso();
            PageRec rec = new PageRec();
            rec.uuid = UUID.randomUUID().toString();
            rec.title = pageTitle;
            rec.slug = ensureUniqueSlug(slugifyOrDefault(slug, pageTitle), pages, "");
            rec.parentUuid = parent;
            rec.permissionKey = normalizePermissionKey(permissionKey);
            rec.navOrder = nextNavOrder(pages);
            rec.createdBy = safe(createdBy).trim();
            rec.createdAt = now;
            rec.updatedAt = now;
            rec.currentRevisionUuid = "";
            rec.archived = false;

            pages.add(rec);
            writePagesLocked(tu, pages);

            Files.createDirectories(pageDir(tu, rec.uuid));
            Files.createDirectories(revisionsDir(tu, rec.uuid));
            Files.createDirectories(attachmentsDir(tu, rec.uuid));
            ensureRevisionsIndexExists(tu, rec.uuid);
            ensureAttachmentsIndexExists(tu, rec.uuid);

            RevisionRec r1 = saveRevisionLocked(
                    tu,
                    rec.uuid,
                    initialHtml,
                    safe(initialSummary).trim().isBlank() ? "Initial page revision." : initialSummary,
                    safe(createdBy).trim(),
                    "",
                    "",
                    true
            );
            rec.currentRevisionUuid = safe(r1 == null ? "" : r1.uuid);
            rec.updatedAt = document_workflow_support.nowIso();
            writePagesLocked(tu, pages);
            return rec;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean updatePageMeta(String tenantUuid,
                                  String pageUuid,
                                  String title,
                                  String slug,
                                  String parentUuid,
                                  String permissionKey,
                                  int navOrder,
                                  boolean archived) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String pu = safe(pageUuid).trim();
        if (tu.isBlank() || pu.isBlank()) return false;

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensure(tu);
            List<PageRec> pages = readPagesLocked(tu);
            PageRec target = findPage(pages, pu);
            if (target == null) return false;

            String newTitle = safe(title).trim();
            if (newTitle.isBlank()) throw new IllegalArgumentException("Page title is required.");

            String desiredSlug = slugifyOrDefault(slug, newTitle);
            desiredSlug = ensureUniqueSlug(desiredSlug, pages, pu);

            String parent = safe(parentUuid).trim();
            if (parent.equals(pu)) parent = "";
            if (!parent.isBlank() && findPage(pages, parent) == null) parent = "";

            target.title = newTitle;
            target.slug = desiredSlug;
            target.parentUuid = parent;
            target.permissionKey = normalizePermissionKey(permissionKey);
            target.navOrder = Math.max(0, navOrder);
            target.archived = archived;
            target.updatedAt = document_workflow_support.nowIso();

            writePagesLocked(tu, pages);
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public RevisionRec saveRevision(String tenantUuid,
                                    String pageUuid,
                                    String html,
                                    String summary,
                                    String editorEmail,
                                    String editorUuid) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String pu = safe(pageUuid).trim();
        if (tu.isBlank() || pu.isBlank()) throw new IllegalArgumentException("Page required.");

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            return saveRevisionLocked(tu, pu, html, summary, editorEmail, editorUuid, "", false);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public RevisionRec restoreRevision(String tenantUuid,
                                       String pageUuid,
                                       String revisionUuid,
                                       String summary,
                                       String editorEmail,
                                       String editorUuid) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String pu = safe(pageUuid).trim();
        String ru = safe(revisionUuid).trim();
        if (tu.isBlank() || pu.isBlank() || ru.isBlank()) throw new IllegalArgumentException("Page and revision are required.");

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            RevisionRec source = getRevisionLocked(tu, pu, ru);
            if (source == null) throw new IllegalArgumentException("Revision not found.");
            String html = readRevisionHtmlLocked(tu, pu, ru);
            String note = safe(summary).trim();
            if (note.isBlank()) note = "Restored from " + safe(source.label).trim() + ".";
            return saveRevisionLocked(tu, pu, html, note, editorEmail, editorUuid, ru, true);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<RevisionRec> listRevisions(String tenantUuid, String pageUuid) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String pu = safe(pageUuid).trim();
        if (tu.isBlank() || pu.isBlank()) return List.of();

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.readLock().lock();
        try {
            List<RevisionRec> rows = readRevisionsLocked(tu, pu);
            rows.sort(Comparator.comparing((RevisionRec r) -> safe(r.createdAt)).reversed());
            return rows;
        } finally {
            lock.readLock().unlock();
        }
    }

    public RevisionRec getRevision(String tenantUuid, String pageUuid, String revisionUuid) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String pu = safe(pageUuid).trim();
        String ru = safe(revisionUuid).trim();
        if (tu.isBlank() || pu.isBlank() || ru.isBlank()) return null;

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.readLock().lock();
        try {
            return getRevisionLocked(tu, pu, ru);
        } finally {
            lock.readLock().unlock();
        }
    }

    public RevisionRec getCurrentRevision(String tenantUuid, String pageUuid) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String pu = safe(pageUuid).trim();
        if (tu.isBlank() || pu.isBlank()) return null;

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.readLock().lock();
        try {
            List<RevisionRec> rows = readRevisionsLocked(tu, pu);
            for (RevisionRec r : rows) {
                if (r != null && r.current) return r;
            }
            return rows.isEmpty() ? null : rows.get(rows.size() - 1);
        } finally {
            lock.readLock().unlock();
        }
    }

    public String readRevisionHtml(String tenantUuid, String pageUuid, String revisionUuid) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String pu = safe(pageUuid).trim();
        String ru = safe(revisionUuid).trim();
        if (tu.isBlank() || pu.isBlank() || ru.isBlank()) return "";

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.readLock().lock();
        try {
            return readRevisionHtmlLocked(tu, pu, ru);
        } finally {
            lock.readLock().unlock();
        }
    }

    public String readCurrentHtml(String tenantUuid, String pageUuid) throws Exception {
        RevisionRec cur = getCurrentRevision(tenantUuid, pageUuid);
        if (cur == null) return "";
        return readRevisionHtml(tenantUuid, pageUuid, cur.uuid);
    }

    public DiffResult diffRevisions(String tenantUuid, String pageUuid, String fromRevisionUuid, String toRevisionUuid) throws Exception {
        String left = readRevisionHtml(tenantUuid, pageUuid, fromRevisionUuid);
        String right = readRevisionHtml(tenantUuid, pageUuid, toRevisionUuid);
        return diffHtml(left, right);
    }

    public DiffResult diffHtml(String leftHtml, String rightHtml) {
        List<String> left = toDiffLines(leftHtml);
        List<String> right = toDiffLines(rightHtml);
        return buildDiff(left, right);
    }

    public List<AttachmentRec> listAttachments(String tenantUuid, String pageUuid) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String pu = safe(pageUuid).trim();
        if (tu.isBlank() || pu.isBlank()) return List.of();

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.readLock().lock();
        try {
            List<AttachmentRec> rows = readAttachmentsLocked(tu, pu);
            rows.sort(Comparator.comparing((AttachmentRec a) -> safe(a.uploadedAt)).reversed());
            return rows;
        } finally {
            lock.readLock().unlock();
        }
    }

    public AttachmentRec saveAttachment(String tenantUuid,
                                        String pageUuid,
                                        String fileName,
                                        String mimeType,
                                        byte[] bytes,
                                        String uploadedBy) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String pu = safe(pageUuid).trim();
        if (tu.isBlank() || pu.isBlank()) throw new IllegalArgumentException("Page required.");
        if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("Attachment file is empty.");
        if (bytes.length > MAX_ATTACHMENT_BYTES) throw new IllegalArgumentException("Attachment exceeds max size.");

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensure(tu);
            if (findPage(readPagesLocked(tu), pu) == null) throw new IllegalArgumentException("Page not found.");
            ensureAttachmentsIndexExists(tu, pu);
            Files.createDirectories(attachmentsDir(tu, pu));

            String now = document_workflow_support.nowIso();
            String safeName = safeFileName(fileName);
            String uuid = UUID.randomUUID().toString();
            String storageFile = uuid + "__" + safeName;
            Path outPath = attachmentsDir(tu, pu).resolve(storageFile).normalize();
            if (!outPath.startsWith(attachmentsDir(tu, pu).normalize())) throw new IllegalArgumentException("Invalid attachment path.");

            Files.write(outPath, bytes);

            AttachmentRec rec = new AttachmentRec();
            rec.uuid = uuid;
            rec.fileName = safeName;
            rec.mimeType = safe(mimeType).trim().isBlank() ? "application/octet-stream" : safe(mimeType).trim();
            rec.checksumSha256 = sha256Hex(bytes);
            rec.fileSizeBytes = String.valueOf(bytes.length);
            rec.storageFile = storageFile;
            rec.uploadedBy = safe(uploadedBy).trim();
            rec.uploadedAt = now;

            List<AttachmentRec> rows = readAttachmentsLocked(tu, pu);
            rows.add(rec);
            writeAttachmentsLocked(tu, pu, rows);
            return rec;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public AttachmentRec getAttachment(String tenantUuid, String pageUuid, String attachmentUuid) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String pu = safe(pageUuid).trim();
        String au = safe(attachmentUuid).trim();
        if (tu.isBlank() || pu.isBlank() || au.isBlank()) return null;

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.readLock().lock();
        try {
            List<AttachmentRec> rows = readAttachmentsLocked(tu, pu);
            for (AttachmentRec rec : rows) {
                if (rec == null) continue;
                if (au.equals(safe(rec.uuid).trim())) return rec;
            }
            return null;
        } finally {
            lock.readLock().unlock();
        }
    }

    public Path resolveAttachmentPath(String tenantUuid, String pageUuid, String attachmentUuid) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String pu = safe(pageUuid).trim();
        String au = safe(attachmentUuid).trim();
        if (tu.isBlank() || pu.isBlank() || au.isBlank()) return null;

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.readLock().lock();
        try {
            AttachmentRec rec = getAttachmentLocked(tu, pu, au);
            if (rec == null) return null;
            Path root = attachmentsDir(tu, pu).normalize();
            Path p = root.resolve(safe(rec.storageFile).trim()).normalize();
            if (!p.startsWith(root)) return null;
            return p;
        } finally {
            lock.readLock().unlock();
        }
    }

    public Path resolveRevisionPath(String tenantUuid, String pageUuid, String revisionUuid) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String pu = safe(pageUuid).trim();
        String ru = safe(revisionUuid).trim();
        if (tu.isBlank() || pu.isBlank() || ru.isBlank()) return null;

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.readLock().lock();
        try {
            RevisionRec rec = getRevisionLocked(tu, pu, ru);
            if (rec == null) return null;
            Path root = revisionsDir(tu, pu).normalize();
            Path p = root.resolve(safe(rec.storageFile).trim()).normalize();
            if (!p.startsWith(root)) return null;
            return p;
        } finally {
            lock.readLock().unlock();
        }
    }

    private RevisionRec saveRevisionLocked(String tenantUuid,
                                           String pageUuid,
                                           String html,
                                           String summary,
                                           String editorEmail,
                                           String editorUuid,
                                           String baseRevisionUuid,
                                           boolean force) throws Exception {
        ensure(tenantUuid);
        List<PageRec> pages = readPagesLocked(tenantUuid);
        PageRec page = findPage(pages, pageUuid);
        if (page == null) throw new IllegalArgumentException("Page not found.");

        ensureRevisionsIndexExists(tenantUuid, pageUuid);
        Files.createDirectories(revisionsDir(tenantUuid, pageUuid));

        String sanitized = sanitizeHtml(html);
        byte[] bytes = sanitized.getBytes(StandardCharsets.UTF_8);
        String sha = sha256Hex(bytes);
        List<RevisionRec> rows = readRevisionsLocked(tenantUuid, pageUuid);

        RevisionRec current = null;
        for (RevisionRec r : rows) {
            if (r != null && r.current) {
                current = r;
                break;
            }
        }

        if (!force && current != null && sha.equals(safe(current.checksumSha256).trim())) {
            return current;
        }

        for (RevisionRec r : rows) {
            if (r == null) continue;
            r.current = false;
        }

        String now = document_workflow_support.nowIso();
        String uuid = UUID.randomUUID().toString();
        String storageFile = uuid + ".html";
        Path outPath = revisionsDir(tenantUuid, pageUuid).resolve(storageFile).normalize();
        if (!outPath.startsWith(revisionsDir(tenantUuid, pageUuid).normalize())) {
            throw new IllegalArgumentException("Invalid revision path.");
        }
        document_workflow_support.writeAtomic(outPath, sanitized);

        RevisionRec rec = new RevisionRec();
        rec.uuid = uuid;
        rec.label = "r" + (rows.size() + 1);
        rec.summary = safe(summary).trim();
        rec.editorUuid = safe(editorUuid).trim();
        rec.editorEmail = safe(editorEmail).trim();
        rec.createdAt = now;
        rec.baseRevisionUuid = safe(baseRevisionUuid).trim().isBlank() ? (current == null ? "" : safe(current.uuid).trim()) : safe(baseRevisionUuid).trim();
        rec.checksumSha256 = sha;
        rec.fileSizeBytes = String.valueOf(bytes.length);
        rec.storageFile = storageFile;
        rec.current = true;
        rows.add(rec);
        writeRevisionsLocked(tenantUuid, pageUuid, rows);

        page.currentRevisionUuid = rec.uuid;
        page.updatedAt = now;
        writePagesLocked(tenantUuid, pages);
        return rec;
    }

    private static DiffResult buildDiff(List<String> leftLines, List<String> rightLines) {
        int m = leftLines == null ? 0 : leftLines.size();
        int n = rightLines == null ? 0 : rightLines.size();
        int[][] lcs = new int[m + 1][n + 1];

        for (int i = m - 1; i >= 0; i--) {
            for (int j = n - 1; j >= 0; j--) {
                if (safe(leftLines.get(i)).equals(safe(rightLines.get(j)))) {
                    lcs[i][j] = lcs[i + 1][j + 1] + 1;
                } else {
                    lcs[i][j] = Math.max(lcs[i + 1][j], lcs[i][j + 1]);
                }
            }
        }

        DiffResult out = new DiffResult();
        int i = 0;
        int j = 0;
        int leftNo = 1;
        int rightNo = 1;
        while (i < m && j < n) {
            String left = safe(leftLines.get(i));
            String right = safe(rightLines.get(j));
            if (left.equals(right)) {
                out.lines.add(makeDiffLine(' ', leftNo, rightNo, left));
                i++;
                j++;
                leftNo++;
                rightNo++;
                continue;
            }
            if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                out.lines.add(makeDiffLine('-', leftNo, 0, left));
                out.removed++;
                i++;
                leftNo++;
            } else {
                out.lines.add(makeDiffLine('+', 0, rightNo, right));
                out.added++;
                j++;
                rightNo++;
            }
        }
        while (i < m) {
            String left = safe(leftLines.get(i));
            out.lines.add(makeDiffLine('-', leftNo, 0, left));
            out.removed++;
            i++;
            leftNo++;
        }
        while (j < n) {
            String right = safe(rightLines.get(j));
            out.lines.add(makeDiffLine('+', 0, rightNo, right));
            out.added++;
            j++;
            rightNo++;
        }
        return out;
    }

    private static DiffLine makeDiffLine(char type, int left, int right, String text) {
        DiffLine out = new DiffLine();
        out.type = type;
        out.leftLine = left;
        out.rightLine = right;
        out.text = safe(text);
        return out;
    }

    private static List<String> toDiffLines(String html) {
        String cleaned = sanitizeHtml(html);
        org.jsoup.nodes.Document doc = Jsoup.parseBodyFragment(cleaned);
        doc.select("br").append("\\n");
        doc.select("p,div,li,h1,h2,h3,h4,h5,h6,blockquote,tr,pre").append("\\n");
        String text = safe(doc.text()).replace("\\n", "\n").replace('\r', '\n');
        text = text.replace('\u00A0', ' ').replaceAll("[ \\t]+", " ");

        List<String> out = new ArrayList<String>();
        for (String raw : text.split("\\n")) {
            String line = safe(raw).trim();
            if (line.isBlank()) continue;
            out.add(line);
        }
        if (out.isEmpty()) out.add("");
        return out;
    }

    public static String sanitizeHtml(String rawHtml) {
        String in = safe(rawHtml);
        if (in.isBlank()) return "<p></p>";
        String cleaned = HTML_CLEANER.clean(Jsoup.parseBodyFragment(in)).body().html();
        if (safe(cleaned).trim().isBlank()) return "<p></p>";
        return cleaned;
    }

    private static ReentrantReadWriteLock lockFor(String tenantUuid) {
        return LOCKS.computeIfAbsent(safeFileToken(tenantUuid), k -> new ReentrantReadWriteLock());
    }

    private static Path wikiRoot(String tenantUuid) {
        return Paths.get("data", "tenants", safeFileToken(tenantUuid), "wiki").toAbsolutePath();
    }

    private static Path pagesPath(String tenantUuid) {
        return wikiRoot(tenantUuid).resolve("pages.xml");
    }

    private static Path pagesDir(String tenantUuid) {
        return wikiRoot(tenantUuid).resolve("pages");
    }

    private static Path pageDir(String tenantUuid, String pageUuid) {
        return pagesDir(tenantUuid).resolve(safeFileToken(pageUuid));
    }

    private static Path revisionsPath(String tenantUuid, String pageUuid) {
        return pageDir(tenantUuid, pageUuid).resolve("revisions.xml");
    }

    private static Path revisionsDir(String tenantUuid, String pageUuid) {
        return pageDir(tenantUuid, pageUuid).resolve("revisions");
    }

    private static Path attachmentsPath(String tenantUuid, String pageUuid) {
        return pageDir(tenantUuid, pageUuid).resolve("attachments.xml");
    }

    private static Path attachmentsDir(String tenantUuid, String pageUuid) {
        return pageDir(tenantUuid, pageUuid).resolve("attachments");
    }

    private static void ensureRevisionsIndexExists(String tenantUuid, String pageUuid) throws Exception {
        Path p = revisionsPath(tenantUuid, pageUuid);
        Files.createDirectories(p.getParent());
        if (!Files.exists(p)) {
            document_workflow_support.writeAtomic(
                    p,
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<wiki_revisions updated=\"" + document_workflow_support.xmlText(document_workflow_support.nowIso()) + "\"></wiki_revisions>\n"
            );
        }
    }

    private static void ensureAttachmentsIndexExists(String tenantUuid, String pageUuid) throws Exception {
        Path p = attachmentsPath(tenantUuid, pageUuid);
        Files.createDirectories(p.getParent());
        if (!Files.exists(p)) {
            document_workflow_support.writeAtomic(
                    p,
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<wiki_attachments updated=\"" + document_workflow_support.xmlText(document_workflow_support.nowIso()) + "\"></wiki_attachments>\n"
            );
        }
    }

    private static List<PageRec> readPagesLocked(String tenantUuid) throws Exception {
        Path p = pagesPath(tenantUuid);
        if (p == null || !Files.exists(p)) return new ArrayList<PageRec>();
        Document d = document_workflow_support.parseXml(p);
        Element root = d == null ? null : d.getDocumentElement();
        if (root == null) return new ArrayList<PageRec>();
        List<PageRec> out = new ArrayList<PageRec>();
        NodeList nl = root.getElementsByTagName("page");
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (!(n instanceof Element e)) continue;
            out.add(readPage(e));
        }
        return out;
    }

    private static PageRec readPage(Element e) {
        PageRec rec = new PageRec();
        rec.uuid = document_workflow_support.text(e, "uuid");
        rec.title = document_workflow_support.text(e, "title");
        rec.slug = document_workflow_support.text(e, "slug");
        rec.parentUuid = document_workflow_support.text(e, "parent_uuid");
        rec.permissionKey = document_workflow_support.text(e, "permission_key");
        rec.navOrder = intOr(document_workflow_support.text(e, "nav_order"), 0);
        rec.createdBy = document_workflow_support.text(e, "created_by");
        rec.createdAt = document_workflow_support.text(e, "created_at");
        rec.updatedAt = document_workflow_support.text(e, "updated_at");
        rec.currentRevisionUuid = document_workflow_support.text(e, "current_revision_uuid");
        rec.archived = "true".equalsIgnoreCase(document_workflow_support.text(e, "archived"));
        return rec;
    }

    private static void writePagesLocked(String tenantUuid, List<PageRec> rows) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<wiki_pages updated=\"")
                .append(document_workflow_support.xmlText(document_workflow_support.nowIso()))
                .append("\">\n");
        List<PageRec> safeRows = rows == null ? List.of() : rows;
        safeRows.sort(Comparator.comparingInt((PageRec p) -> p == null ? Integer.MAX_VALUE : p.navOrder)
                .thenComparing(p -> safe(p == null ? "" : p.title).toLowerCase(Locale.ROOT)));
        for (PageRec rec : safeRows) {
            if (rec == null || safe(rec.uuid).trim().isBlank()) continue;
            sb.append("  <page>\n");
            writeTag(sb, "uuid", rec.uuid);
            writeTag(sb, "title", rec.title);
            writeTag(sb, "slug", rec.slug);
            writeTag(sb, "parent_uuid", rec.parentUuid);
            writeTag(sb, "permission_key", rec.permissionKey);
            writeTag(sb, "nav_order", String.valueOf(rec.navOrder));
            writeTag(sb, "created_by", rec.createdBy);
            writeTag(sb, "created_at", rec.createdAt);
            writeTag(sb, "updated_at", rec.updatedAt);
            writeTag(sb, "current_revision_uuid", rec.currentRevisionUuid);
            writeTag(sb, "archived", rec.archived ? "true" : "false");
            sb.append("  </page>\n");
        }
        sb.append("</wiki_pages>\n");
        document_workflow_support.writeAtomic(pagesPath(tenantUuid), sb.toString());
    }

    private static List<RevisionRec> readRevisionsLocked(String tenantUuid, String pageUuid) throws Exception {
        Path p = revisionsPath(tenantUuid, pageUuid);
        if (p == null || !Files.exists(p)) return new ArrayList<RevisionRec>();
        Document d = document_workflow_support.parseXml(p);
        Element root = d == null ? null : d.getDocumentElement();
        if (root == null) return new ArrayList<RevisionRec>();
        List<RevisionRec> out = new ArrayList<RevisionRec>();
        NodeList nl = root.getElementsByTagName("revision");
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (!(n instanceof Element e)) continue;
            out.add(readRevision(e));
        }
        return out;
    }

    private static RevisionRec readRevision(Element e) {
        RevisionRec rec = new RevisionRec();
        rec.uuid = document_workflow_support.text(e, "uuid");
        rec.label = document_workflow_support.text(e, "label");
        rec.summary = document_workflow_support.text(e, "summary");
        rec.editorUuid = document_workflow_support.text(e, "editor_uuid");
        rec.editorEmail = document_workflow_support.text(e, "editor_email");
        rec.createdAt = document_workflow_support.text(e, "created_at");
        rec.baseRevisionUuid = document_workflow_support.text(e, "base_revision_uuid");
        rec.checksumSha256 = document_workflow_support.text(e, "checksum_sha256");
        rec.fileSizeBytes = document_workflow_support.text(e, "file_size_bytes");
        rec.storageFile = document_workflow_support.text(e, "storage_file");
        rec.current = "true".equalsIgnoreCase(document_workflow_support.text(e, "current"));
        return rec;
    }

    private static void writeRevisionsLocked(String tenantUuid, String pageUuid, List<RevisionRec> rows) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<wiki_revisions updated=\"")
                .append(document_workflow_support.xmlText(document_workflow_support.nowIso()))
                .append("\">\n");
        List<RevisionRec> safeRows = rows == null ? List.of() : rows;
        for (RevisionRec rec : safeRows) {
            if (rec == null || safe(rec.uuid).trim().isBlank()) continue;
            sb.append("  <revision>\n");
            writeTag(sb, "uuid", rec.uuid);
            writeTag(sb, "label", rec.label);
            writeTag(sb, "summary", rec.summary);
            writeTag(sb, "editor_uuid", rec.editorUuid);
            writeTag(sb, "editor_email", rec.editorEmail);
            writeTag(sb, "created_at", rec.createdAt);
            writeTag(sb, "base_revision_uuid", rec.baseRevisionUuid);
            writeTag(sb, "checksum_sha256", rec.checksumSha256);
            writeTag(sb, "file_size_bytes", rec.fileSizeBytes);
            writeTag(sb, "storage_file", rec.storageFile);
            writeTag(sb, "current", rec.current ? "true" : "false");
            sb.append("  </revision>\n");
        }
        sb.append("</wiki_revisions>\n");
        document_workflow_support.writeAtomic(revisionsPath(tenantUuid, pageUuid), sb.toString());
    }

    private static List<AttachmentRec> readAttachmentsLocked(String tenantUuid, String pageUuid) throws Exception {
        Path p = attachmentsPath(tenantUuid, pageUuid);
        if (p == null || !Files.exists(p)) return new ArrayList<AttachmentRec>();
        Document d = document_workflow_support.parseXml(p);
        Element root = d == null ? null : d.getDocumentElement();
        if (root == null) return new ArrayList<AttachmentRec>();
        List<AttachmentRec> out = new ArrayList<AttachmentRec>();
        NodeList nl = root.getElementsByTagName("attachment");
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (!(n instanceof Element e)) continue;
            out.add(readAttachment(e));
        }
        return out;
    }

    private static AttachmentRec readAttachment(Element e) {
        AttachmentRec rec = new AttachmentRec();
        rec.uuid = document_workflow_support.text(e, "uuid");
        rec.fileName = document_workflow_support.text(e, "file_name");
        rec.mimeType = document_workflow_support.text(e, "mime_type");
        rec.checksumSha256 = document_workflow_support.text(e, "checksum_sha256");
        rec.fileSizeBytes = document_workflow_support.text(e, "file_size_bytes");
        rec.storageFile = document_workflow_support.text(e, "storage_file");
        rec.uploadedBy = document_workflow_support.text(e, "uploaded_by");
        rec.uploadedAt = document_workflow_support.text(e, "uploaded_at");
        return rec;
    }

    private static AttachmentRec getAttachmentLocked(String tenantUuid, String pageUuid, String attachmentUuid) throws Exception {
        for (AttachmentRec rec : readAttachmentsLocked(tenantUuid, pageUuid)) {
            if (rec == null) continue;
            if (safe(attachmentUuid).trim().equals(safe(rec.uuid).trim())) return rec;
        }
        return null;
    }

    private static void writeAttachmentsLocked(String tenantUuid, String pageUuid, List<AttachmentRec> rows) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<wiki_attachments updated=\"")
                .append(document_workflow_support.xmlText(document_workflow_support.nowIso()))
                .append("\">\n");
        List<AttachmentRec> safeRows = rows == null ? List.of() : rows;
        for (AttachmentRec rec : safeRows) {
            if (rec == null || safe(rec.uuid).trim().isBlank()) continue;
            sb.append("  <attachment>\n");
            writeTag(sb, "uuid", rec.uuid);
            writeTag(sb, "file_name", rec.fileName);
            writeTag(sb, "mime_type", rec.mimeType);
            writeTag(sb, "checksum_sha256", rec.checksumSha256);
            writeTag(sb, "file_size_bytes", rec.fileSizeBytes);
            writeTag(sb, "storage_file", rec.storageFile);
            writeTag(sb, "uploaded_by", rec.uploadedBy);
            writeTag(sb, "uploaded_at", rec.uploadedAt);
            sb.append("  </attachment>\n");
        }
        sb.append("</wiki_attachments>\n");
        document_workflow_support.writeAtomic(attachmentsPath(tenantUuid, pageUuid), sb.toString());
    }

    private static String readRevisionHtmlLocked(String tenantUuid, String pageUuid, String revisionUuid) throws Exception {
        RevisionRec rec = getRevisionLocked(tenantUuid, pageUuid, revisionUuid);
        if (rec == null) return "";
        Path file = revisionsDir(tenantUuid, pageUuid).resolve(safe(rec.storageFile).trim()).normalize();
        if (!file.startsWith(revisionsDir(tenantUuid, pageUuid).normalize())) return "";
        if (!Files.exists(file)) return "";
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private static RevisionRec getRevisionLocked(String tenantUuid, String pageUuid, String revisionUuid) throws Exception {
        String ru = safe(revisionUuid).trim();
        if (ru.isBlank()) return null;
        List<RevisionRec> rows = readRevisionsLocked(tenantUuid, pageUuid);
        for (RevisionRec rec : rows) {
            if (rec == null) continue;
            if (ru.equals(safe(rec.uuid).trim())) return rec;
        }
        return null;
    }

    private static PageRec findPage(List<PageRec> pages, String pageUuid) {
        String pu = safe(pageUuid).trim();
        if (pu.isBlank() || pages == null) return null;
        for (PageRec rec : pages) {
            if (rec == null) continue;
            if (pu.equals(safe(rec.uuid).trim())) return rec;
        }
        return null;
    }

    private static int nextNavOrder(List<PageRec> pages) {
        int max = 0;
        if (pages != null) {
            for (PageRec rec : pages) {
                if (rec == null) continue;
                if (rec.navOrder > max) max = rec.navOrder;
            }
        }
        return max + 10;
    }

    private static String ensureUniqueSlug(String baseSlug, List<PageRec> pages, String exceptPageUuid) {
        String base = safe(baseSlug).trim().toLowerCase(Locale.ROOT);
        if (base.isBlank()) base = "page";
        String except = safe(exceptPageUuid).trim();
        String candidate = base;
        int suffix = 2;
        while (slugExists(candidate, pages, except)) {
            candidate = base + "-" + suffix;
            suffix++;
        }
        return candidate;
    }

    private static boolean slugExists(String slug, List<PageRec> pages, String exceptPageUuid) {
        String s = safe(slug).trim().toLowerCase(Locale.ROOT);
        String except = safe(exceptPageUuid).trim();
        if (s.isBlank() || pages == null) return false;
        for (PageRec rec : pages) {
            if (rec == null) continue;
            String id = safe(rec.uuid).trim();
            if (!except.isBlank() && except.equals(id)) continue;
            if (s.equals(safe(rec.slug).trim().toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private static String slugifyOrDefault(String raw, String fallbackTitle) {
        String candidate = safe(raw).trim();
        if (candidate.isBlank()) candidate = safe(fallbackTitle).trim();
        candidate = candidate.toLowerCase(Locale.ROOT);
        candidate = candidate.replaceAll("[^a-z0-9]+", "-");
        candidate = candidate.replaceAll("^-+", "");
        candidate = candidate.replaceAll("-+$", "");
        if (candidate.isBlank()) candidate = "page";
        if (candidate.length() > 96) candidate = candidate.substring(0, 96);
        return candidate;
    }

    private static String normalizePermissionKey(String raw) {
        String key = safe(raw).trim();
        if (key.isBlank()) return "";
        if ("tenant_admin".equalsIgnoreCase(key)) return "tenant_admin";
        key = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "");
        if (key.length() > 96) key = key.substring(0, 96);
        return key;
    }

    private static String safeFileName(String raw) {
        String cleaned = safe(raw).trim();
        if (cleaned.isBlank()) cleaned = "attachment.bin";
        cleaned = cleaned.replaceAll("[^A-Za-z0-9._-]", "_");
        if (cleaned.length() > 140) cleaned = cleaned.substring(cleaned.length() - 140);
        if (cleaned.isBlank()) cleaned = "attachment.bin";
        return cleaned;
    }

    private static int intOr(String raw, int fallback) {
        try {
            return Integer.parseInt(safe(raw).trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static void writeTag(StringBuilder sb, String tag, String value) {
        sb.append("    <").append(tag).append(">")
                .append(document_workflow_support.xmlText(value))
                .append("</").append(tag).append(">\n");
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes == null ? new byte[0] : bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.toString();
        } catch (Exception ex) {
            return "";
        }
    }

    private static String safeFileToken(String s) {
        return document_workflow_support.safe(s).trim().replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
