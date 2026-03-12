package net.familylawandprobate.controversies;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Builds companion OCR XML records for part versions.
 * The implementation is intentionally conservative with resources:
 * - single-version lock to avoid duplicate OCR work
 * - one-page-at-a-time PDF rendering
 * - bounded PDF page cap
 * - Tesseract subprocess thread limits and timeout
 */
public final class version_ocr_companion_service {

    private static final long TESSERACT_CACHE_MS = 30_000L;
    private static final int OCR_PDF_DPI = 144;
    private static final int OCR_PDF_PAGE_CAP = 220;
    private static final int STRUCTURED_IMAGE_HASH_PAGE_CAP = 20;
    private static final int OCR_TIMEOUT_SECONDS = 90;
    private static final int EMBEDDED_TEXT_MIN_CHARS = 40;
    private static final int MAX_CAPTURED_STDOUT_BYTES = 4 * 1024 * 1024;

    private static final class DocumentTextExtraction {
        String engine = "document_assembler";
        String text = "";
        final ArrayList<ImageHashRec> imageHashes = new ArrayList<ImageHashRec>();
    }

    private static final class Holder {
        private static final version_ocr_companion_service INSTANCE = new version_ocr_companion_service();
    }

    public static final class CompanionRec {
        public Path xmlPath;
        public String versionUuid;
        public String generatedAt;
        public String engine;
        public boolean usedTesseract;
        public int pageCount;
        public String fullText;
        public String sourceStoragePath;
        public String sourceChecksum;
        public long sourceBytes;
        public long sourceMtimeMs;
        public final ArrayList<ImageHashRec> imageHashes = new ArrayList<ImageHashRec>();
    }

    public static final class ImageHashRec {
        public int pageIndex;
        public int width;
        public int height;
        public String source;
        public String sha256Rgb;
        public String averageHash64;
        public String differenceHash64;

        public ImageHashRec() {
        }

        public ImageHashRec(int pageIndex,
                            int width,
                            int height,
                            String source,
                            String sha256Rgb,
                            String averageHash64,
                            String differenceHash64) {
            this.pageIndex = Math.max(0, pageIndex);
            this.width = Math.max(0, width);
            this.height = Math.max(0, height);
            this.source = safe(source).trim();
            this.sha256Rgb = safe(sha256Rgb).trim().toLowerCase(Locale.ROOT);
            this.averageHash64 = image_hash_tools.normalizeHex64(averageHash64);
            this.differenceHash64 = image_hash_tools.normalizeHex64(differenceHash64);
        }
    }

    private static final class PageTextRec {
        final int pageIndex;
        final String source;
        final String text;

        PageTextRec(int pageIndex, String source, String text) {
            this.pageIndex = Math.max(0, pageIndex);
            this.source = safe(source).trim();
            this.text = safe(text);
        }
    }

    private final Object tesseractAvailabilityLock = new Object();
    private final Object ocrRuntimeLock = new Object();
    private final ConcurrentHashMap<String, Object> perVersionLocks = new ConcurrentHashMap<String, Object>();
    private volatile long tesseractCheckedAtMs = 0L;
    private volatile boolean tesseractAvailable = false;

    private version_ocr_companion_service() {
    }

    public static version_ocr_companion_service defaultService() {
        return Holder.INSTANCE;
    }

    public boolean isTesseractAvailable() {
        long now = app_clock.currentTimeMillis();
        if (now - tesseractCheckedAtMs <= TESSERACT_CACHE_MS) {
            return tesseractAvailable;
        }
        synchronized (tesseractAvailabilityLock) {
            now = app_clock.currentTimeMillis();
            if (now - tesseractCheckedAtMs <= TESSERACT_CACHE_MS) {
                return tesseractAvailable;
            }
            boolean ok = probeTesseract();
            tesseractAvailable = ok;
            tesseractCheckedAtMs = now;
            return ok;
        }
    }

    public CompanionRec ensureCompanion(String tenantUuid,
                                        String matterUuid,
                                        String docUuid,
                                        String partUuid,
                                        part_versions.VersionRec version) throws Exception {
        String tu = safe(tenantUuid).trim();
        String mu = safe(matterUuid).trim();
        String du = safe(docUuid).trim();
        String pu = safe(partUuid).trim();
        String vu = safe(version == null ? "" : version.uuid).trim();
        if (tu.isBlank() || mu.isBlank() || du.isBlank() || pu.isBlank()) {
            throw new IllegalArgumentException("Missing tenant/matter/document/part identifiers.");
        }
        if (vu.isBlank()) throw new IllegalArgumentException("Version identifier is required.");

        Path sourcePath = pdf_redaction_service.resolveStoragePath(version.storagePath);
        pdf_redaction_service.requirePathWithinTenant(sourcePath, tu, "Version source path");
        if (sourcePath == null || !Files.isRegularFile(sourcePath)) {
            throw new IllegalArgumentException("Version source file not found.");
        }
        long sourceBytes = Files.size(sourcePath);
        long sourceMtimeMs = Files.getLastModifiedTime(sourcePath).toMillis();

        Path companionPath = companionPath(tu, mu, du, pu, vu);
        CompanionRec existing = readCompanion(companionPath);
        if (isFresh(existing, version, sourcePath, sourceBytes, sourceMtimeMs)) {
            return existing;
        }

        String lockKey = tu + "|" + mu + "|" + du + "|" + pu + "|" + vu;
        Object lock = perVersionLocks.computeIfAbsent(lockKey, k -> new Object());
        synchronized (lock) {
            CompanionRec again = readCompanion(companionPath);
            if (isFresh(again, version, sourcePath, sourceBytes, sourceMtimeMs)) {
                return again;
            }
            CompanionRec generated = generateCompanion(companionPath, sourcePath, version, sourceBytes, sourceMtimeMs);
            return generated == null ? new CompanionRec() : generated;
        }
    }

    public Path companionPath(String tenantUuid,
                              String matterUuid,
                              String docUuid,
                              String partUuid,
                              String versionUuid) throws Exception {
        String tu = safe(tenantUuid).trim();
        String mu = safe(matterUuid).trim();
        String du = safe(docUuid).trim();
        String pu = safe(partUuid).trim();
        String vu = safe(versionUuid).trim();
        if (tu.isBlank() || mu.isBlank() || du.isBlank() || pu.isBlank() || vu.isBlank()) {
            throw new IllegalArgumentException("Missing identifiers for OCR companion path.");
        }
        Path partFolder = document_parts.defaultStore().partFolder(tu, mu, du, pu);
        if (partFolder == null) throw new IllegalArgumentException("Part folder unavailable.");
        pdf_redaction_service.requirePathWithinTenant(partFolder, tu, "Part folder path");

        Path dir = partFolder.resolve("ocr_companions");
        Files.createDirectories(dir);
        pdf_redaction_service.requirePathWithinTenant(dir, tu, "OCR companion folder path");

        String fileName = safeFileToken(vu) + ".ocr.xml";
        Path out = dir.resolve(fileName).toAbsolutePath().normalize();
        pdf_redaction_service.requirePathWithinTenant(out, tu, "OCR companion file path");
        return out;
    }

    public CompanionRec readCompanion(Path xmlPath) {
        try {
            if (xmlPath == null || !Files.isRegularFile(xmlPath)) return null;
            Document doc = document_workflow_support.parseXml(xmlPath);
            Element root = doc == null ? null : doc.getDocumentElement();
            if (root == null) return null;
            if (!"ocr_companion".equalsIgnoreCase(safe(root.getTagName()).trim())) return null;

            CompanionRec rec = new CompanionRec();
            rec.xmlPath = xmlPath.toAbsolutePath().normalize();
            rec.versionUuid = safe(root.getAttribute("version_uuid")).trim();
            rec.generatedAt = safe(root.getAttribute("generated_at")).trim();
            rec.engine = safe(root.getAttribute("engine")).trim();
            rec.usedTesseract = "true".equalsIgnoreCase(safe(root.getAttribute("used_tesseract")).trim());
            rec.sourceStoragePath = safe(root.getAttribute("source_storage_path")).trim();
            rec.sourceChecksum = safe(root.getAttribute("source_checksum")).trim();
            rec.sourceBytes = longOrDefault(root.getAttribute("source_bytes"), -1L);
            rec.sourceMtimeMs = longOrDefault(root.getAttribute("source_mtime_ms"), -1L);
            rec.fullText = document_workflow_support.text(root, "full_text");

            Element pagesEl = firstChild(root, "pages");
            if (pagesEl != null) {
                rec.pageCount = intOrDefault(pagesEl.getAttribute("count"), 0);
                if (rec.pageCount <= 0) {
                    rec.pageCount = pagesEl.getElementsByTagName("page").getLength();
                }
            } else {
                rec.pageCount = 0;
            }
            Element imageHashesEl = firstChild(root, "image_hashes");
            if (imageHashesEl != null) {
                var nodes = imageHashesEl.getElementsByTagName("image");
                int count = nodes == null ? 0 : nodes.getLength();
                for (int i = 0; i < count; i++) {
                    if (!(nodes.item(i) instanceof Element e)) continue;
                    ImageHashRec hash = new ImageHashRec();
                    hash.pageIndex = intOrDefault(e.getAttribute("page_index"), 0);
                    hash.width = intOrDefault(e.getAttribute("width"), 0);
                    hash.height = intOrDefault(e.getAttribute("height"), 0);
                    hash.source = safe(e.getAttribute("source")).trim();
                    hash.sha256Rgb = safe(e.getAttribute("sha256_rgb")).trim().toLowerCase(Locale.ROOT);
                    hash.averageHash64 = image_hash_tools.normalizeHex64(e.getAttribute("ahash64"));
                    hash.differenceHash64 = image_hash_tools.normalizeHex64(e.getAttribute("dhash64"));
                    if (!hash.sha256Rgb.isBlank() || !hash.averageHash64.isBlank() || !hash.differenceHash64.isBlank()) {
                        rec.imageHashes.add(hash);
                    }
                }
            }
            return rec;
        } catch (Exception ignored) {
            return null;
        }
    }

    private CompanionRec generateCompanion(Path companionPath,
                                           Path sourcePath,
                                           part_versions.VersionRec version,
                                           long sourceBytes,
                                           long sourceMtimeMs) throws Exception {
        String ext = extension(sourcePath);
        String mime = safe(version == null ? "" : version.mimeType).trim().toLowerCase(Locale.ROOT);
        String sourceChecksum = safe(version == null ? "" : version.checksum).trim();
        String sourceStoragePath = safe(version == null ? "" : version.storagePath).trim();
        String versionUuid = safe(version == null ? "" : version.uuid).trim();

        ArrayList<PageTextRec> pages = new ArrayList<PageTextRec>();
        ArrayList<ImageHashRec> imageHashes = new ArrayList<ImageHashRec>();
        String engine = "";
        boolean usedTesseract = false;
        String fullText = "";

        if ("pdf".equals(ext) || mime.contains("pdf")) {
            PdfExtraction rec = extractPdfPageTextAndOcr(sourcePath);
            pages.addAll(rec.pages);
            imageHashes.addAll(rec.imageHashes);
            engine = rec.engine;
            usedTesseract = rec.usedTesseract;
            fullText = rec.fullText;
        } else if (isImageExtension(ext) || mime.startsWith("image/")) {
            if (!isTesseractAvailable()) {
                throw new IllegalStateException("Tesseract is required for OCR and is not available on this server.");
            }
            String text = runTesseractSerialized(sourcePath);
            pages.add(new PageTextRec(0, "tesseract", text));
            ImageHashRec hash = buildImageHashRec(0, "source_image", ImageIO.read(sourcePath.toFile()));
            if (hash != null) imageHashes.add(hash);
            fullText = text;
            engine = "tesseract";
            usedTesseract = true;
        } else if (isStructuredDocumentExtension(ext) || isStructuredDocumentMime(mime)) {
            DocumentTextExtraction rec = extractStructuredDocumentText(sourcePath);
            fullText = safe(rec == null ? "" : rec.text);
            pages.add(new PageTextRec(0, safe(rec == null ? "document_assembler" : rec.engine), fullText));
            if (rec != null) imageHashes.addAll(rec.imageHashes);
            engine = safe(rec == null ? "document_assembler" : rec.engine);
            usedTesseract = false;
        } else if (isLikelyTextExtension(ext) || mime.startsWith("text/")) {
            fullText = safe(Files.readString(sourcePath, StandardCharsets.UTF_8));
            pages.add(new PageTextRec(0, "plain_text", fullText));
            engine = "plain_text";
            usedTesseract = false;
        } else {
            fullText = "";
            engine = "unsupported";
            usedTesseract = false;
        }

        String generatedAt = document_workflow_support.nowIso();
        writeCompanionXml(
                companionPath,
                versionUuid,
                generatedAt,
                engine,
                usedTesseract,
                sourceStoragePath,
                sourceChecksum,
                sourceBytes,
                sourceMtimeMs,
                pages,
                imageHashes,
                fullText
        );

        CompanionRec out = new CompanionRec();
        out.xmlPath = companionPath;
        out.versionUuid = versionUuid;
        out.generatedAt = generatedAt;
        out.engine = engine;
        out.usedTesseract = usedTesseract;
        out.pageCount = pages.size();
        out.fullText = fullText;
        out.sourceStoragePath = sourceStoragePath;
        out.sourceChecksum = sourceChecksum;
        out.sourceBytes = sourceBytes;
        out.sourceMtimeMs = sourceMtimeMs;
        out.imageHashes.addAll(imageHashes);
        return out;
    }

    private static DocumentTextExtraction extractStructuredDocumentText(Path sourcePath) throws Exception {
        DocumentTextExtraction out = new DocumentTextExtraction();
        if (sourcePath == null || !Files.isRegularFile(sourcePath)) return out;
        byte[] bytes = Files.readAllBytes(sourcePath);
        String extOrName = sourcePath.getFileName() == null ? "" : sourcePath.getFileName().toString();
        document_assembler.PreviewResult preview = new document_assembler().preview(bytes, extOrName, Map.of());
        out.engine = "document_assembler+image_preview";
        out.text = safe(preview == null ? "" : preview.sourceText);
        try {
            document_image_preview.PreviewResult rendered = new document_image_preview().render(
                    bytes,
                    extOrName,
                    null,
                    STRUCTURED_IMAGE_HASH_PAGE_CAP
            );
            if (rendered != null && rendered.pages != null) {
                for (document_image_preview.PageImage page : rendered.pages) {
                    if (page == null) continue;
                    BufferedImage img = image_hash_tools.decodePngBase64(page.base64Png);
                    ImageHashRec hash = buildImageHashRec(page.pageIndex, "preview_renderer", img);
                    if (hash != null) out.imageHashes.add(hash);
                }
            }
        } catch (Exception ignored) {
            // Keep OCR companion resilient when preview image rendering fails.
        }
        return out;
    }

    private static final class PdfExtraction {
        final ArrayList<PageTextRec> pages = new ArrayList<PageTextRec>();
        final ArrayList<ImageHashRec> imageHashes = new ArrayList<ImageHashRec>();
        String engine = "pdfbox";
        boolean usedTesseract = false;
        String fullText = "";
    }

    private PdfExtraction extractPdfPageTextAndOcr(Path sourcePdf) throws Exception {
        PdfExtraction out = new PdfExtraction();
        if (sourcePdf == null) return out;

        StringBuilder full = new StringBuilder(8_192);
        boolean anyPdfText = false;
        boolean anyOcr = false;

        try (PDDocument doc = PDDocument.load(sourcePdf.toFile())) {
            int total = Math.max(0, doc.getNumberOfPages());
            int limit = Math.min(total, OCR_PDF_PAGE_CAP);
            PDFRenderer renderer = new PDFRenderer(doc);

            for (int pageIndex = 0; pageIndex < limit; pageIndex++) {
                BufferedImage renderedPage = null;
                try {
                    String pageText = extractPdfPageText(doc, pageIndex);
                    String source = "pdfbox";
                    renderedPage = renderer.renderImageWithDPI(pageIndex, OCR_PDF_DPI, ImageType.GRAY);
                    ImageHashRec hash = buildImageHashRec(pageIndex, "pdf_renderer", renderedPage);
                    if (hash != null) out.imageHashes.add(hash);

                    if (safe(pageText).trim().length() < EMBEDDED_TEXT_MIN_CHARS) {
                        if (!isTesseractAvailable()) {
                            throw new IllegalStateException("Tesseract is required for OCR and is not available on this server.");
                        }
                        Path tempPng = null;
                        try {
                            BufferedImage img = renderedPage;
                            if (img == null) {
                                img = renderer.renderImageWithDPI(pageIndex, OCR_PDF_DPI, ImageType.GRAY);
                            }
                            tempPng = Files.createTempFile("ocr-page-" + pageIndex + "-", ".png");
                            ImageIO.write(img, "png", tempPng.toFile());
                            pageText = runTesseractSerialized(tempPng);
                            source = "tesseract";
                            anyOcr = true;
                        } finally {
                            if (tempPng != null) {
                                try {
                                    Files.deleteIfExists(tempPng);
                                } catch (Exception ignored) {
                                }
                            }
                        }
                    } else {
                        anyPdfText = true;
                    }

                    out.pages.add(new PageTextRec(pageIndex, source, pageText));
                    if (!safe(pageText).isBlank()) {
                        if (full.length() > 0) full.append('\n');
                        full.append(pageText);
                    }
                } catch (Exception ex) {
                    if (ex instanceof IllegalStateException) throw ex;
                    out.pages.add(new PageTextRec(pageIndex, "pdfbox", ""));
                } finally {
                    if (renderedPage != null) {
                        renderedPage.flush();
                    }
                }
            }
        }

        out.fullText = full.toString();
        out.usedTesseract = anyOcr;
        if (anyOcr && anyPdfText) out.engine = "pdfbox+tesseract";
        else if (anyOcr) out.engine = "tesseract";
        else out.engine = "pdfbox";
        return out;
    }

    private String runTesseractSerialized(Path inputPath) throws Exception {
        synchronized (ocrRuntimeLock) {
            return runTesseract(inputPath);
        }
    }

    private static String extractPdfPageText(PDDocument doc, int pageIndex) {
        if (doc == null || pageIndex < 0) return "";
        try {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(pageIndex + 1);
            stripper.setEndPage(pageIndex + 1);
            String text = stripper.getText(doc);
            return safe(text);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static void writeCompanionXml(Path targetPath,
                                          String versionUuid,
                                          String generatedAt,
                                          String engine,
                                          boolean usedTesseract,
                                          String sourceStoragePath,
                                          String sourceChecksum,
                                          long sourceBytes,
                                          long sourceMtimeMs,
                                          List<PageTextRec> pages,
                                          List<ImageHashRec> imageHashes,
                                          String fullText) throws Exception {
        if (targetPath == null) throw new IllegalArgumentException("OCR companion path is required.");
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<ocr_companion")
                .append(" version_uuid=\"").append(document_workflow_support.xmlText(versionUuid)).append("\"")
                .append(" generated_at=\"").append(document_workflow_support.xmlText(generatedAt)).append("\"")
                .append(" engine=\"").append(document_workflow_support.xmlText(engine)).append("\"")
                .append(" used_tesseract=\"").append(usedTesseract ? "true" : "false").append("\"")
                .append(" source_storage_path=\"").append(document_workflow_support.xmlText(sourceStoragePath)).append("\"")
                .append(" source_checksum=\"").append(document_workflow_support.xmlText(sourceChecksum)).append("\"")
                .append(" source_bytes=\"").append(sourceBytes).append("\"")
                .append(" source_mtime_ms=\"").append(sourceMtimeMs).append("\"")
                .append(">\n");

        sb.append("  <full_text>").append(document_workflow_support.xmlText(fullText)).append("</full_text>\n");
        sb.append("  <pages count=\"").append(pages == null ? 0 : pages.size()).append("\">\n");
        List<PageTextRec> rows = pages == null ? List.of() : pages;
        for (PageTextRec row : rows) {
            if (row == null) continue;
            sb.append("    <page index=\"").append(row.pageIndex)
                    .append("\" source=\"").append(document_workflow_support.xmlText(row.source)).append("\">")
                    .append(document_workflow_support.xmlText(row.text))
                    .append("</page>\n");
        }
        sb.append("  </pages>\n");
        sb.append("  <image_hashes count=\"").append(imageHashes == null ? 0 : imageHashes.size()).append("\">\n");
        List<ImageHashRec> hashes = imageHashes == null ? List.of() : imageHashes;
        for (ImageHashRec hash : hashes) {
            if (hash == null) continue;
            sb.append("    <image")
                    .append(" page_index=\"").append(Math.max(0, hash.pageIndex)).append("\"")
                    .append(" width=\"").append(Math.max(0, hash.width)).append("\"")
                    .append(" height=\"").append(Math.max(0, hash.height)).append("\"")
                    .append(" source=\"").append(document_workflow_support.xmlText(hash.source)).append("\"")
                    .append(" sha256_rgb=\"").append(document_workflow_support.xmlText(hash.sha256Rgb)).append("\"")
                    .append(" ahash64=\"").append(document_workflow_support.xmlText(hash.averageHash64)).append("\"")
                    .append(" dhash64=\"").append(document_workflow_support.xmlText(hash.differenceHash64)).append("\"")
                    .append("/>\n");
        }
        sb.append("  </image_hashes>\n");
        sb.append("</ocr_companion>\n");

        document_workflow_support.writeAtomic(targetPath, sb.toString());
    }

    private boolean isFresh(CompanionRec rec,
                            part_versions.VersionRec version,
                            Path sourcePath,
                            long sourceBytes,
                            long sourceMtimeMs) {
        if (rec == null) return false;
        if (safe(rec.versionUuid).isBlank()) return false;

        String currentVersionUuid = safe(version == null ? "" : version.uuid).trim();
        String currentStoragePath = safe(version == null ? "" : version.storagePath).trim();
        String currentChecksum = safe(version == null ? "" : version.checksum).trim();

        if (!currentVersionUuid.equals(safe(rec.versionUuid).trim())) return false;
        if (!currentStoragePath.equals(safe(rec.sourceStoragePath).trim())) return false;
        if (sourceBytes >= 0L && rec.sourceBytes >= 0L && sourceBytes != rec.sourceBytes) return false;
        if (sourceMtimeMs > 0L && rec.sourceMtimeMs > 0L && sourceMtimeMs != rec.sourceMtimeMs) return false;
        if (!currentChecksum.isBlank() && !safe(rec.sourceChecksum).isBlank()
                && !currentChecksum.equalsIgnoreCase(safe(rec.sourceChecksum).trim())) {
            return false;
        }
        String ext = extension(sourcePath);
        String mime = safe(version == null ? "" : version.mimeType).trim().toLowerCase(Locale.ROOT);
        if (shouldRequireImageHashes(sourcePath, ext, mime) && (rec.imageHashes == null || rec.imageHashes.isEmpty())) {
            return false;
        }
        return true;
    }

    private boolean probeTesseract() {
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("tesseract", "--version");
            pb.redirectErrorStream(true);
            applyProcessThreadLimits(pb);
            process = pb.start();
            try (var in = process.getInputStream()) {
                in.transferTo(OutputStream.nullOutputStream());
            }
            int exit = process.waitFor();
            return exit == 0;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (process != null) process.destroyForcibly();
        }
    }

    private static String runTesseract(Path inputPath) throws Exception {
        if (inputPath == null || !Files.isRegularFile(inputPath)) {
            throw new IllegalArgumentException("OCR input file not found.");
        }
        ProcessBuilder pb = new ProcessBuilder(
                "tesseract",
                inputPath.toAbsolutePath().normalize().toString(),
                "stdout",
                "--oem", "1",
                "--psm", "3",
                "-l", "eng"
        );
        pb.redirectErrorStream(true);
        applyProcessThreadLimits(pb);
        Process process = pb.start();
        ByteArrayOutputStream out = new ByteArrayOutputStream(16_384);
        try (var in = process.getInputStream()) {
            byte[] buf = new byte[8192];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0) {
                int allowed = Math.min(n, Math.max(0, MAX_CAPTURED_STDOUT_BYTES - total));
                if (allowed > 0) out.write(buf, 0, allowed);
                total += n;
            }
        }

        boolean done = process.waitFor(OCR_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!done) {
            process.destroyForcibly();
            throw new IllegalStateException("Tesseract OCR timed out after " + OCR_TIMEOUT_SECONDS + " seconds.");
        }
        int exit = process.exitValue();
        String text = safe(out.toString(StandardCharsets.UTF_8)).trim();
        if (exit != 0) {
            String detail = text;
            if (detail.length() > 600) detail = detail.substring(0, 600);
            throw new IllegalStateException("Tesseract exited with code " + exit + (detail.isBlank() ? "" : (": " + detail)));
        }
        return text;
    }

    private static void applyProcessThreadLimits(ProcessBuilder pb) {
        if (pb == null) return;
        pb.environment().put("OMP_THREAD_LIMIT", "1");
        pb.environment().put("OMP_NUM_THREADS", "1");
        pb.environment().put("OPENBLAS_NUM_THREADS", "1");
        pb.environment().put("MKL_NUM_THREADS", "1");
    }

    private static Element firstChild(Element parent, String tag) {
        if (parent == null || safe(tag).trim().isBlank()) return null;
        var nodes = parent.getElementsByTagName(tag);
        if (nodes == null || nodes.getLength() <= 0) return null;
        if (nodes.item(0) instanceof Element e) return e;
        return null;
    }

    private static ImageHashRec buildImageHashRec(int pageIndex, String source, BufferedImage image) {
        if (image == null) return null;
        try {
            image_hash_tools.HashRec hash = image_hash_tools.compute(image);
            if (hash == null) return null;
            if (safe(hash.sha256Rgb).isBlank()
                    && safe(hash.averageHash64).isBlank()
                    && safe(hash.differenceHash64).isBlank()) {
                return null;
            }
            return new ImageHashRec(
                    pageIndex,
                    hash.width,
                    hash.height,
                    source,
                    hash.sha256Rgb,
                    hash.averageHash64,
                    hash.differenceHash64
            );
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean shouldRequireImageHashes(Path sourcePath, String ext, String mime) {
        String e = safe(ext).trim().toLowerCase(Locale.ROOT);
        String m = safe(mime).trim().toLowerCase(Locale.ROOT);
        if ("pdf".equals(e) || m.contains("pdf")) return true;
        if (isImageExtension(e) || m.startsWith("image/")) {
            BufferedImage img = null;
            try {
                if (sourcePath != null && Files.isRegularFile(sourcePath)) {
                    img = ImageIO.read(sourcePath.toFile());
                }
            } catch (Exception ignored) {
                img = null;
            } finally {
                if (img != null) img.flush();
            }
            return img != null;
        }
        return false;
    }

    private static boolean isImageExtension(String ext) {
        String e = safe(ext).trim().toLowerCase(Locale.ROOT);
        return "png".equals(e)
                || "jpg".equals(e)
                || "jpeg".equals(e)
                || "tif".equals(e)
                || "tiff".equals(e)
                || "bmp".equals(e)
                || "gif".equals(e)
                || "webp".equals(e);
    }

    private static boolean isLikelyTextExtension(String ext) {
        String e = safe(ext).trim().toLowerCase(Locale.ROOT);
        return "txt".equals(e)
                || "md".equals(e)
                || "csv".equals(e)
                || "json".equals(e)
                || "xml".equals(e)
                || "html".equals(e)
                || "htm".equals(e)
                || "log".equals(e)
                || "yaml".equals(e)
                || "yml".equals(e);
    }

    private static boolean isStructuredDocumentExtension(String ext) {
        String e = safe(ext).trim().toLowerCase(Locale.ROOT);
        return "docx".equals(e)
                || "doc".equals(e)
                || "rtf".equals(e)
                || "odf".equals(e)
                || "odt".equals(e);
    }

    private static boolean isStructuredDocumentMime(String mime) {
        String m = safe(mime).trim().toLowerCase(Locale.ROOT);
        return m.contains("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                || m.contains("application/msword")
                || m.contains("application/rtf")
                || m.contains("text/rtf")
                || m.contains("application/vnd.oasis.opendocument")
                || m.contains("application/vnd.oasis.opendocument.text");
    }

    private static String extension(Path p) {
        if (p == null || p.getFileName() == null) return "";
        String n = safe(p.getFileName().toString()).trim().toLowerCase(Locale.ROOT);
        int dot = n.lastIndexOf('.');
        if (dot < 0 || dot + 1 >= n.length()) return "";
        return n.substring(dot + 1).replaceAll("[^a-z0-9]", "");
    }

    private static String safeFileToken(String s) {
        String v = safe(s).trim();
        if (v.isBlank()) return UUID.randomUUID().toString().replace("-", "_");
        return v.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static int intOrDefault(String raw, int d) {
        try {
            return Integer.parseInt(safe(raw).trim());
        } catch (Exception ignored) {
            return d;
        }
    }

    private static long longOrDefault(String raw, long d) {
        try {
            return Long.parseLong(safe(raw).trim());
        } catch (Exception ignored) {
            return d;
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
