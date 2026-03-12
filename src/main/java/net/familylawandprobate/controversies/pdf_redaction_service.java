package net.familylawandprobate.controversies;

import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.multipdf.PDFCloneUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * PDF redaction helper for the document version workflow.
 *
 * Uses pdfredactor (if available on classpath) and falls back to a
 * PDFBox rasterized burn-in workflow (page -> image -> redaction -> PDF).
 */
public final class pdf_redaction_service {

    private static final int DEFAULT_RENDER_DPI = 144;
    private static final int RASTER_REDACTION_DPI = 200;
    private static final int MAX_REDACTION_RECTS = 5000;
    private static final double MIN_PREVIEW_RENDER_QUALITY = 0.25d;
    private static final double MAX_PREVIEW_RENDER_QUALITY = 1.0d;
    private static final float PDFREDACTOR_HEADLESS_QUALITY = 1.0f;

    private pdf_redaction_service() {
    }

    public static final class PdfInfo {
        public final int totalPages;

        public PdfInfo(int totalPages) {
            this.totalPages = Math.max(0, totalPages);
        }
    }

    public static final class RenderedPage {
        public final int pageIndex;
        public final int totalPages;
        public final int imageWidthPx;
        public final int imageHeightPx;
        public final byte[] pngBytes;

        public RenderedPage(int pageIndex, int totalPages, int imageWidthPx, int imageHeightPx, byte[] pngBytes) {
            this.pageIndex = Math.max(0, pageIndex);
            this.totalPages = Math.max(0, totalPages);
            this.imageWidthPx = Math.max(0, imageWidthPx);
            this.imageHeightPx = Math.max(0, imageHeightPx);
            this.pngBytes = pngBytes == null ? new byte[0] : pngBytes;
        }
    }

    public static final class RedactionRectNorm {
        public final int pageIndex;
        public final double xNorm;
        public final double yNorm;
        public final double widthNorm;
        public final double heightNorm;

        public RedactionRectNorm(int pageIndex, double xNorm, double yNorm, double widthNorm, double heightNorm) {
            this.pageIndex = Math.max(0, pageIndex);
            this.xNorm = xNorm;
            this.yNorm = yNorm;
            this.widthNorm = widthNorm;
            this.heightNorm = heightNorm;
        }
    }

    public static final class RedactionRectPt {
        public final int pageIndex;
        public final float xPt;
        public final float yPt;
        public final float widthPt;
        public final float heightPt;

        public RedactionRectPt(int pageIndex, float xPt, float yPt, float widthPt, float heightPt) {
            this.pageIndex = Math.max(0, pageIndex);
            this.xPt = xPt;
            this.yPt = yPt;
            this.widthPt = widthPt;
            this.heightPt = heightPt;
        }
    }

    public static final class RedactionRun {
        public final boolean usedPdfRedactor;
        public final int appliedRectCount;

        public RedactionRun(boolean usedPdfRedactor, int appliedRectCount) {
            this.usedPdfRedactor = usedPdfRedactor;
            this.appliedRectCount = Math.max(0, appliedRectCount);
        }
    }

    public static Path resolveStoragePath(String storagePath) {
        String raw = safe(storagePath).trim();
        if (raw.isBlank()) return null;

        try {
            if (raw.regionMatches(true, 0, "file:", 0, 5)) {
                URI uri = URI.create(raw);
                if (!"file".equalsIgnoreCase(safe(uri.getScheme()))) return null;
                return Paths.get(uri).toAbsolutePath().normalize();
            }
        } catch (Exception ignored) {
            return null;
        }

        String lower = raw.toLowerCase(Locale.ROOT);
        int colon = lower.indexOf(':');
        if (colon > 1) {
            String scheme = lower.substring(0, colon).trim();
            if (scheme.matches("[a-z][a-z0-9+.-]*")) return null;
        }

        try {
            Path p = Paths.get(raw);
            if (!p.isAbsolute()) p = p.toAbsolutePath();
            return p.normalize();
        } catch (Exception ignored) {
            return null;
        }
    }

    public static Path tenantRootPath(String tenantUuid) {
        String tu = safeFileToken(tenantUuid);
        if (tu.isBlank()) return null;
        return Paths.get("data", "tenants", tu).toAbsolutePath().normalize();
    }

    public static boolean isPathWithinTenant(Path path, String tenantUuid) {
        Path root = tenantRootPath(tenantUuid);
        if (path == null || root == null) return false;
        Path normalized = path.toAbsolutePath().normalize();
        return normalized.startsWith(root);
    }

    public static void requirePathWithinTenant(Path path, String tenantUuid, String label) {
        if (isPathWithinTenant(path, tenantUuid)) return;
        String what = safe(label).trim();
        if (what.isBlank()) what = "File path";
        throw new IllegalArgumentException(what + " is outside the tenant boundary.");
    }

    public static boolean isPdfVersion(part_versions.VersionRec rec) {
        if (rec == null) return false;
        String mime = safe(rec.mimeType).trim().toLowerCase(Locale.ROOT);
        if (mime.contains("pdf")) return true;
        Path p = resolveStoragePath(rec.storagePath);
        if (p == null || p.getFileName() == null) return false;
        String fileName = safe(p.getFileName().toString()).toLowerCase(Locale.ROOT);
        return fileName.endsWith(".pdf");
    }

    public static PdfInfo inspect(Path pdfPath) throws Exception {
        Path p = requirePdf(pdfPath);
        try (PDDocument doc = PDDocument.load(p.toFile())) {
            return new PdfInfo(doc.getNumberOfPages());
        }
    }

    public static RenderedPage renderPage(Path pdfPath, int requestedPageIndex) throws Exception {
        return renderPage(pdfPath, requestedPageIndex, 1d);
    }

    public static RenderedPage renderPage(Path pdfPath, int requestedPageIndex, double requestedQuality) throws Exception {
        Path p = requirePdf(pdfPath);
        int dpi = renderDpiForQuality(requestedQuality);
        try (PDDocument doc = PDDocument.load(p.toFile())) {
            int total = Math.max(0, doc.getNumberOfPages());
            if (total <= 0) throw new IllegalArgumentException("PDF has no pages.");
            int pageIndex = clamp(requestedPageIndex, 0, total - 1);
            PDFRenderer renderer = new PDFRenderer(doc);
            BufferedImage image = renderer.renderImageWithDPI(pageIndex, dpi, ImageType.RGB);
            return new RenderedPage(
                    pageIndex,
                    total,
                    image == null ? 0 : image.getWidth(),
                    image == null ? 0 : image.getHeight(),
                    image == null ? new byte[0] : toPng(image)
            );
        }
    }

    public static List<RedactionRectNorm> parseNormalizedPayload(String rawPayload) {
        ArrayList<RedactionRectNorm> out = new ArrayList<RedactionRectNorm>();
        String raw = safe(rawPayload).trim();
        if (raw.isBlank()) return out;

        String[] rows = raw.split(";");
        for (String row : rows) {
            String r = safe(row).trim();
            if (r.isBlank()) continue;
            String[] parts = r.split(",");
            if (parts.length != 5) continue;

            int page = parseInt(parts[0], -1);
            double x = parseDouble(parts[1], -1d);
            double y = parseDouble(parts[2], -1d);
            double w = parseDouble(parts[3], -1d);
            double h = parseDouble(parts[4], -1d);
            RedactionRectNorm normalized = normalizeRect(page, x, y, w, h);
            if (normalized == null) continue;
            out.add(normalized);
            if (out.size() > MAX_REDACTION_RECTS) {
                throw new IllegalArgumentException("Too many redaction rectangles.");
            }
        }

        return out;
    }

    public static List<RedactionRectNorm> parseNormalizedObjects(Object raw) {
        ArrayList<RedactionRectNorm> out = new ArrayList<RedactionRectNorm>();
        if (!(raw instanceof List<?> rows)) return out;

        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> m)) continue;

            int page = parseInt(bestOf(m, "page", "page_index"), -1);
            double x = parseDouble(bestOf(m, "x_norm", "x"), -1d);
            double y = parseDouble(bestOf(m, "y_norm", "y"), -1d);
            double w = parseDouble(bestOf(m, "width_norm", "width", "w"), -1d);
            double h = parseDouble(bestOf(m, "height_norm", "height", "h"), -1d);

            RedactionRectNorm normalized = normalizeRect(page, x, y, w, h);
            if (normalized == null) continue;
            out.add(normalized);
            if (out.size() > MAX_REDACTION_RECTS) {
                throw new IllegalArgumentException("Too many redaction rectangles.");
            }
        }
        return out;
    }

    public static List<RedactionRectPt> toPageCoordinates(Path pdfPath, List<RedactionRectNorm> rects) throws Exception {
        ArrayList<RedactionRectPt> out = new ArrayList<RedactionRectPt>();
        if (rects == null || rects.isEmpty()) return out;

        Path p = requirePdf(pdfPath);
        try (PDDocument doc = PDDocument.load(p.toFile())) {
            int totalPages = Math.max(0, doc.getNumberOfPages());
            for (RedactionRectNorm r : rects) {
                if (r == null) continue;
                int pageIndex = r.pageIndex;
                if (pageIndex < 0 || pageIndex >= totalPages) continue;

                PDPage page = doc.getPage(pageIndex);
                if (page == null) continue;
                PDRectangle crop = page.getCropBox();
                if (crop == null) crop = page.getMediaBox();
                if (crop == null) continue;

                float pw = Math.max(1f, crop.getWidth());
                float ph = Math.max(1f, crop.getHeight());
                float x = (float) (r.xNorm * pw);
                float yTop = (float) (r.yNorm * ph);
                float w = (float) (r.widthNorm * pw);
                float h = (float) (r.heightNorm * ph);
                float y = ph - yTop - h;

                if (x < 0f) x = 0f;
                if (y < 0f) y = 0f;
                if (x + w > pw) w = pw - x;
                if (y + h > ph) h = ph - y;
                if (w <= 0f || h <= 0f) continue;

                out.add(new RedactionRectPt(pageIndex, x, y, w, h));
            }
        }
        return out;
    }

    public static RedactionRun redact(Path inputPdf, Path outputPdf, List<RedactionRectPt> rects) throws Exception {
        Path input = requirePdf(inputPdf);
        if (outputPdf == null) throw new IllegalArgumentException("Output path required.");
        if (rects == null || rects.isEmpty()) throw new IllegalArgumentException("At least one redaction rectangle is required.");
        Files.createDirectories(outputPdf.toAbsolutePath().normalize().getParent());

        boolean usedPdfRedactor = tryPdfRedactor(input, outputPdf, rects);
        if (!usedPdfRedactor) {
            applyPdfBoxRasterizedRedaction(input, outputPdf, rects);
        }
        if (!Files.exists(outputPdf) || Files.size(outputPdf) <= 0L) {
            throw new IllegalStateException("Redacted PDF output was not created.");
        }
        preservePageAnnotations(input, outputPdf);
        return new RedactionRun(usedPdfRedactor, rects.size());
    }

    public static void flattenToImagePdf(Path inputPdf, Path outputPdf) throws Exception {
        Path input = requirePdf(inputPdf);
        if (outputPdf == null) throw new IllegalArgumentException("Output path required.");
        Path output = outputPdf.toAbsolutePath().normalize();
        Files.createDirectories(output.getParent());

        try (PDDocument source = PDDocument.load(input.toFile());
             PDDocument flattened = new PDDocument()) {
            int pages = Math.max(0, source.getNumberOfPages());
            if (pages <= 0) throw new IllegalArgumentException("PDF has no pages.");
            PDFRenderer renderer = new PDFRenderer(source);

            for (int pageIndex = 0; pageIndex < pages; pageIndex++) {
                BufferedImage rendered = renderer.renderImageWithDPI(pageIndex, RASTER_REDACTION_DPI, ImageType.RGB);
                float pageWidthPt = (float) (rendered.getWidth() * 72d / RASTER_REDACTION_DPI);
                float pageHeightPt = (float) (rendered.getHeight() * 72d / RASTER_REDACTION_DPI);
                if (pageWidthPt <= 0f || pageHeightPt <= 0f) {
                    rendered.flush();
                    continue;
                }

                PDPage outPage = new PDPage(new PDRectangle(pageWidthPt, pageHeightPt));
                flattened.addPage(outPage);

                PDImageXObject pageImage = LosslessFactory.createFromImage(flattened, rendered);
                try (PDPageContentStream cs = new PDPageContentStream(
                        flattened,
                        outPage,
                        PDPageContentStream.AppendMode.OVERWRITE,
                        false,
                        false
                )) {
                    cs.drawImage(pageImage, 0f, 0f, pageWidthPt, pageHeightPt);
                }
                rendered.flush();
            }

            preservePageAnnotations(source, flattened);
            flattened.save(output.toFile());
        }

        if (!Files.exists(output) || Files.size(output) <= 0L) {
            throw new IllegalStateException("Flattened PDF output was not created.");
        }
    }

    public static void preservePageAnnotations(Path sourcePdf, Path outputPdf) throws Exception {
        Path sourcePath = requirePdf(sourcePdf);
        Path outputPath = requirePdf(outputPdf);
        try (PDDocument source = PDDocument.load(sourcePath.toFile());
             PDDocument output = PDDocument.load(outputPath.toFile())) {
            preservePageAnnotations(source, output);
            output.save(outputPath.toFile());
        }
    }

    public static String sha256(Path p) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (java.io.InputStream in = Files.newInputStream(p)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        return hex(md.digest());
    }

    public static String suggestRedactedFileName(String sourceFileName) {
        String name = safe(sourceFileName).trim();
        if (name.isBlank()) name = "document.pdf";
        name = name.replaceAll("[^A-Za-z0-9.]", "_");
        if (name.length() > 140) name = name.substring(name.length() - 140);

        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf")) {
            String base = name.substring(0, name.length() - 4);
            if (base.isBlank()) base = "document";
            return base + "_redacted.pdf";
        }
        return name + "_redacted.pdf";
    }

    private static Path requirePdf(Path p) {
        if (p == null) throw new IllegalArgumentException("PDF path required.");
        Path n = p.toAbsolutePath().normalize();
        if (!Files.exists(n) || !Files.isRegularFile(n)) {
            throw new IllegalArgumentException("PDF file not found.");
        }
        String fileName = n.getFileName() == null ? "" : safe(n.getFileName().toString()).toLowerCase(Locale.ROOT);
        if (!fileName.endsWith(".pdf")) {
            throw new IllegalArgumentException("Only PDF files are supported.");
        }
        return n;
    }

    private static void applyPdfBoxRasterizedRedaction(Path inputPdf, Path outputPdf, List<RedactionRectPt> rects) throws Exception {
        LinkedHashMap<Integer, ArrayList<RedactionRectPt>> byPage = new LinkedHashMap<Integer, ArrayList<RedactionRectPt>>();
        for (RedactionRectPt rect : rects) {
            if (rect == null) continue;
            byPage.computeIfAbsent(rect.pageIndex, k -> new ArrayList<RedactionRectPt>()).add(rect);
        }

        try (PDDocument input = PDDocument.load(inputPdf.toFile());
             PDDocument output = new PDDocument()) {
            int pages = Math.max(0, input.getNumberOfPages());
            PDFRenderer renderer = new PDFRenderer(input);

            for (int pageIndex = 0; pageIndex < pages; pageIndex++) {
                PDPage sourcePage = input.getPage(pageIndex);
                if (sourcePage == null) continue;
                ArrayList<RedactionRectPt> pageRects = byPage.get(pageIndex);
                if (pageRects == null || pageRects.isEmpty()) {
                    output.importPage(sourcePage);
                    continue;
                }

                BufferedImage rendered = renderer.renderImageWithDPI(pageIndex, RASTER_REDACTION_DPI, ImageType.RGB);
                applyRasterRedactions(rendered, sourcePage, pageRects);

                float pageWidthPt = (float) (rendered.getWidth() * 72d / RASTER_REDACTION_DPI);
                float pageHeightPt = (float) (rendered.getHeight() * 72d / RASTER_REDACTION_DPI);
                if (pageWidthPt <= 0f || pageHeightPt <= 0f) {
                    rendered.flush();
                    continue;
                }

                PDPage outPage = new PDPage(new PDRectangle(pageWidthPt, pageHeightPt));
                output.addPage(outPage);

                PDImageXObject pageImage = LosslessFactory.createFromImage(output, rendered);
                try (PDPageContentStream cs = new PDPageContentStream(
                        output,
                        outPage,
                        PDPageContentStream.AppendMode.OVERWRITE,
                        false,
                        false
                )) {
                    cs.drawImage(pageImage, 0f, 0f, pageWidthPt, pageHeightPt);
                }
                rendered.flush();
            }
            preservePageAnnotations(input, output);
            output.save(outputPdf.toFile());
        }
    }

    private static void applyRasterRedactions(BufferedImage image, PDPage sourcePage, List<RedactionRectPt> rects) {
        if (image == null || sourcePage == null || rects == null || rects.isEmpty()) return;

        PDRectangle crop = sourcePage.getCropBox();
        if (crop == null) crop = sourcePage.getMediaBox();
        if (crop == null) return;

        float pageWidthPt = Math.max(1f, crop.getWidth());
        float pageHeightPt = Math.max(1f, crop.getHeight());
        double scaleX = image.getWidth() / pageWidthPt;
        double scaleY = image.getHeight() / pageHeightPt;

        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.BLACK);
            int imageWidth = Math.max(1, image.getWidth());
            int imageHeight = Math.max(1, image.getHeight());

            for (RedactionRectPt r : rects) {
                if (r == null) continue;
                if (r.widthPt <= 0f || r.heightPt <= 0f) continue;

                int x1 = clamp((int) Math.floor(r.xPt * scaleX), 0, imageWidth);
                int y1 = clamp((int) Math.floor((pageHeightPt - (r.yPt + r.heightPt)) * scaleY), 0, imageHeight);
                int x2 = clamp((int) Math.ceil((r.xPt + r.widthPt) * scaleX), 0, imageWidth);
                int y2 = clamp((int) Math.ceil((pageHeightPt - r.yPt) * scaleY), 0, imageHeight);

                if (x2 <= x1 || y2 <= y1) continue;
                g.fillRect(x1, y1, x2 - x1, y2 - y1);
            }
        } finally {
            g.dispose();
        }
    }

    private static boolean tryPdfRedactor(Path inputPdf, Path outputPdf, List<RedactionRectPt> rects) {
        if (tryPdfRedactorSpecApi(inputPdf, outputPdf, rects)) return true;
        return tryPdfRedactorHeadlessApi(inputPdf, outputPdf, rects);
    }

    private static boolean tryPdfRedactorSpecApi(Path inputPdf, Path outputPdf, List<RedactionRectPt> rects) {
        try {
            Class<?> redactorClass = Class.forName("group.chapmanlaw.pdfredactor.headless.PdfRedactor");
            Class<?> specClass = Class.forName("group.chapmanlaw.pdfredactor.headless.RedactionSpec");

            Constructor<?> specCtor = null;
            for (Constructor<?> c : specClass.getConstructors()) {
                if (c != null && c.getParameterCount() == 5) {
                    specCtor = c;
                    break;
                }
            }
            if (specCtor == null) return false;

            ArrayList<Object> specs = new ArrayList<Object>();
            for (RedactionRectPt r : rects) {
                Object spec = instantiateSpec(specCtor, r);
                if (spec != null) specs.add(spec);
            }
            if (specs.isEmpty()) return false;

            Method redactFile = redactorClass.getMethod("redactFile", Path.class, Path.class, List.class);
            redactFile.invoke(null, inputPdf, outputPdf, specs);
            return Files.exists(outputPdf) && Files.size(outputPdf) > 0L;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean tryPdfRedactorHeadlessApi(Path inputPdf, Path outputPdf, List<RedactionRectPt> rects) {
        try {
            Class<?> headlessClass = Class.forName("group.chapmanlaw.pdfredactor.headless");
            Method openPdf = headlessClass.getMethod("openPdf", String.class, float.class);
            Method dimensions = headlessClass.getMethod("getPageDimensionsInPixels");
            Method apply = headlessClass.getMethod("applyPixelRedactions", Map.class, String.class);

            openPdf.invoke(null, inputPdf.toAbsolutePath().normalize().toString(), PDFREDACTOR_HEADLESS_QUALITY);
            Object rawDims = dimensions.invoke(null);
            if (!(rawDims instanceof List<?> dimRows) || dimRows.isEmpty()) return false;

            LinkedHashMap<Integer, ArrayList<Rectangle>> redactionsByPage = new LinkedHashMap<Integer, ArrayList<Rectangle>>();
            try (PDDocument source = PDDocument.load(inputPdf.toFile())) {
                int totalPages = Math.max(0, source.getNumberOfPages());
                for (RedactionRectPt rect : rects) {
                    if (rect == null) continue;
                    int pageIndex = rect.pageIndex;
                    if (pageIndex < 0 || pageIndex >= totalPages || pageIndex >= dimRows.size()) continue;
                    Object dimRow = dimRows.get(pageIndex);
                    if (!(dimRow instanceof Dimension dim)) continue;

                    PDPage page = source.getPage(pageIndex);
                    if (page == null) continue;
                    PDRectangle crop = page.getCropBox();
                    if (crop == null) crop = page.getMediaBox();
                    if (crop == null) continue;

                    float pageWidthPt = Math.max(1f, crop.getWidth());
                    float pageHeightPt = Math.max(1f, crop.getHeight());
                    int pageWidthPx = Math.max(1, dim.width);
                    int pageHeightPx = Math.max(1, dim.height);

                    int x1 = clamp((int) Math.floor((rect.xPt / pageWidthPt) * pageWidthPx), 0, pageWidthPx);
                    int y1 = clamp((int) Math.floor(((pageHeightPt - (rect.yPt + rect.heightPt)) / pageHeightPt) * pageHeightPx), 0, pageHeightPx);
                    int x2 = clamp((int) Math.ceil(((rect.xPt + rect.widthPt) / pageWidthPt) * pageWidthPx), 0, pageWidthPx);
                    int y2 = clamp((int) Math.ceil(((pageHeightPt - rect.yPt) / pageHeightPt) * pageHeightPx), 0, pageHeightPx);
                    int width = x2 - x1;
                    int height = y2 - y1;
                    if (width <= 0 || height <= 0) continue;

                    redactionsByPage.computeIfAbsent(pageIndex, k -> new ArrayList<Rectangle>())
                            .add(new Rectangle(x1, y1, width, height));
                }
            }

            if (redactionsByPage.isEmpty()) return false;
            apply.invoke(null, redactionsByPage, outputPdf.toAbsolutePath().normalize().toString());
            return Files.exists(outputPdf) && Files.size(outputPdf) > 0L;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Object instantiateSpec(Constructor<?> ctor, RedactionRectPt rect) throws Exception {
        if (ctor == null || rect == null) return null;
        Class<?>[] types = ctor.getParameterTypes();
        if (types == null || types.length != 5) return null;

        double[] vals = new double[] { rect.pageIndex, rect.xPt, rect.yPt, rect.widthPt, rect.heightPt };
        Object[] args = new Object[5];
        for (int i = 0; i < types.length; i++) {
            args[i] = convertNumber(vals[i], types[i]);
            if (args[i] == null) return null;
        }
        return ctor.newInstance(args);
    }

    private static Object convertNumber(double value, Class<?> targetType) {
        if (targetType == null) return null;
        if (targetType == Integer.TYPE || targetType == Integer.class) return (int) Math.round(value);
        if (targetType == Long.TYPE || targetType == Long.class) return (long) Math.round(value);
        if (targetType == Float.TYPE || targetType == Float.class) return (float) value;
        if (targetType == Double.TYPE || targetType == Double.class) return value;
        if (Number.class.isAssignableFrom(targetType)) return Double.valueOf(value);
        return null;
    }

    private static void preservePageAnnotations(PDDocument source, PDDocument output) throws Exception {
        if (source == null || output == null) return;
        int pages = Math.min(Math.max(0, source.getNumberOfPages()), Math.max(0, output.getNumberOfPages()));
        if (pages <= 0) return;

        PDFCloneUtility cloner = new PDFCloneUtility(output);
        for (int pageIndex = 0; pageIndex < pages; pageIndex++) {
            PDPage sourcePage = source.getPage(pageIndex);
            PDPage outputPage = output.getPage(pageIndex);
            if (sourcePage == null || outputPage == null) continue;
            preservePageAnnotations(cloner, sourcePage, outputPage);
        }
    }

    private static void preservePageAnnotations(PDFCloneUtility cloner, PDPage sourcePage, PDPage outputPage) throws Exception {
        if (cloner == null || sourcePage == null || outputPage == null) return;

        ArrayList<PDAnnotation> merged = new ArrayList<PDAnnotation>();
        HashSet<String> seen = new HashSet<String>();

        List<PDAnnotation> outputAnnotations = safeAnnotations(outputPage);
        for (PDAnnotation existing : outputAnnotations) {
            if (existing == null) continue;
            existing.setPage(outputPage);
            String fp = annotationFingerprint(existing);
            if (!fp.isBlank() && seen.contains(fp)) continue;
            if (!fp.isBlank()) seen.add(fp);
            merged.add(existing);
        }

        List<PDAnnotation> sourceAnnotations = safeAnnotations(sourcePage);
        for (PDAnnotation sourceAnnotation : sourceAnnotations) {
            if (sourceAnnotation == null) continue;
            COSBase clonedBase = cloner.cloneForNewDocument(sourceAnnotation.getCOSObject());
            if (!(clonedBase instanceof COSDictionary clonedDictionary)) continue;
            PDAnnotation cloned = PDAnnotation.createAnnotation(clonedDictionary);
            if (cloned == null) continue;
            cloned.setPage(outputPage);
            String fp = annotationFingerprint(cloned);
            if (!fp.isBlank() && seen.contains(fp)) continue;
            if (!fp.isBlank()) seen.add(fp);
            merged.add(cloned);
        }

        outputPage.setAnnotations(merged);
    }

    private static List<PDAnnotation> safeAnnotations(PDPage page) {
        if (page == null) return new ArrayList<PDAnnotation>();
        try {
            List<PDAnnotation> annotations = page.getAnnotations();
            if (annotations == null || annotations.isEmpty()) return new ArrayList<PDAnnotation>();
            return new ArrayList<PDAnnotation>(annotations);
        } catch (Exception ignored) {
            return new ArrayList<PDAnnotation>();
        }
    }

    private static String annotationFingerprint(PDAnnotation annotation) {
        if (annotation == null) return "";
        String subtype = safe(annotation.getSubtype()).trim().toLowerCase(Locale.ROOT);
        String contents = safe(annotation.getContents()).trim();
        String name = "";
        try {
            name = safe(annotation.getAnnotationName()).trim();
        } catch (Exception ignored) {
        }
        PDRectangle rect = null;
        try {
            rect = annotation.getRectangle();
        } catch (Exception ignored) {
        }
        if (rect == null) {
            return subtype + "|" + contents + "|" + name;
        }
        return subtype + "|"
                + Math.round(rect.getLowerLeftX() * 100f) + ","
                + Math.round(rect.getLowerLeftY() * 100f) + ","
                + Math.round(rect.getWidth() * 100f) + ","
                + Math.round(rect.getHeight() * 100f)
                + "|" + contents + "|" + name;
    }

    private static byte[] toPng(BufferedImage image) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(8192, image.getWidth() * image.getHeight() / 2));
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static RedactionRectNorm normalizeRect(int page, double x, double y, double w, double h) {
        if (page < 0) return null;
        if (!(x >= 0d) || !(y >= 0d) || !(w > 0d) || !(h > 0d)) return null;
        if (x >= 1d || y >= 1d) return null;

        double nx = clamp01(x);
        double ny = clamp01(y);
        double nw = clamp01(w);
        double nh = clamp01(h);
        if (nx + nw > 1d) nw = 1d - nx;
        if (ny + nh > 1d) nh = 1d - ny;
        if (nw <= 0d || nh <= 0d) return null;
        return new RedactionRectNorm(page, nx, ny, nw, nh);
    }

    private static Object bestOf(Map<?, ?> m, String... keys) {
        if (m == null || keys == null) return null;
        for (String key : keys) {
            if (key == null) continue;
            if (m.containsKey(key)) return m.get(key);
        }
        return null;
    }

    private static int parseInt(Object raw, int fallback) {
        if (raw == null) return fallback;
        if (raw instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(safe(String.valueOf(raw)).trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static double parseDouble(Object raw, double fallback) {
        if (raw == null) return fallback;
        if (raw instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(safe(String.valueOf(raw)).trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    private static double clamp01(double value) {
        if (value < 0d) return 0d;
        if (value > 1d) return 1d;
        return value;
    }

    public static double normalizePreviewRenderQuality(double value) {
        if (!Double.isFinite(value)) return 1d;
        if (value < MIN_PREVIEW_RENDER_QUALITY) return MIN_PREVIEW_RENDER_QUALITY;
        if (value > MAX_PREVIEW_RENDER_QUALITY) return MAX_PREVIEW_RENDER_QUALITY;
        return value;
    }

    private static int renderDpiForQuality(double requestedQuality) {
        double quality = normalizePreviewRenderQuality(requestedQuality);
        return Math.max(36, (int) Math.round(DEFAULT_RENDER_DPI * quality));
    }

    private static String hex(byte[] raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder(raw.length * 2);
        for (byte b : raw) {
            sb.append(String.format(Locale.ROOT, "%02x", b));
        }
        return sb.toString();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String safeFileToken(String s) {
        return safe(s).trim().replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
