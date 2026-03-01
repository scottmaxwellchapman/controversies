package net.familylawandprobate.controversies;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Shared Texas law browsing helpers (rendering + search).
 */
public final class texas_law_library {

    private static final int PDF_RENDER_DPI = 144;
    private static final int DOCX_PREVIEW_PAGE_CAP = 220;
    private static final int SEARCH_SCAN_FILE_CAP = 5000;
    private static final int SEARCH_TEXT_SNIPPET_SIZE = 220;
    private static final int MAX_CONTENT_EXTRACT_FILE_BYTES = 25 * 1024 * 1024;
    private static final int MAX_EXTRACT_TEXT_CHARS = 250_000;

    private texas_law_library() {
    }

    public static final class RenderedPage {
        public final int pageIndex;
        public final int totalPages;
        public final boolean totalKnown;
        public final boolean hasPrev;
        public final boolean hasNext;
        public final String base64Png;
        public final String warning;
        public final String engine;

        public RenderedPage(int pageIndex,
                            int totalPages,
                            boolean totalKnown,
                            boolean hasPrev,
                            boolean hasNext,
                            String base64Png,
                            String warning,
                            String engine) {
            this.pageIndex = Math.max(0, pageIndex);
            this.totalPages = Math.max(0, totalPages);
            this.totalKnown = totalKnown;
            this.hasPrev = hasPrev;
            this.hasNext = hasNext;
            this.base64Png = safe(base64Png);
            this.warning = safe(warning);
            this.engine = safe(engine);
        }
    }

    public static final class SearchResult {
        public final String relativePath;
        public final String fileName;
        public final String ext;
        public final long sizeBytes;
        public final String matchType; // filename | content | both
        public final String snippet;

        public SearchResult(String relativePath,
                            String fileName,
                            String ext,
                            long sizeBytes,
                            String matchType,
                            String snippet) {
            this.relativePath = safe(relativePath);
            this.fileName = safe(fileName);
            this.ext = safe(ext).toLowerCase(Locale.ROOT);
            this.sizeBytes = Math.max(0L, sizeBytes);
            this.matchType = safe(matchType);
            this.snippet = safe(snippet);
        }
    }

    public static String extension(Path p) {
        if (p == null || p.getFileName() == null) return "";
        String n = safe(p.getFileName().toString()).trim().toLowerCase(Locale.ROOT);
        int dot = n.lastIndexOf('.');
        if (dot < 0 || dot + 1 >= n.length()) return "";
        return n.substring(dot + 1).replaceAll("[^a-z0-9]", "");
    }

    public static boolean isRenderable(Path p) {
        String ext = extension(p);
        return "pdf".equals(ext) || "docx".equals(ext);
    }

    public static RenderedPage renderPage(Path file, int requestedPageIndex) throws Exception {
        if (file == null || !Files.isRegularFile(file)) {
            throw new IllegalArgumentException("File not found.");
        }
        String ext = extension(file);
        if ("pdf".equals(ext)) return renderPdfPage(file, requestedPageIndex);
        if ("docx".equals(ext)) return renderDocxPage(file, requestedPageIndex);
        throw new IllegalArgumentException("Viewer supports PDF and DOCX only.");
    }

    public static List<SearchResult> search(Path root,
                                            String query,
                                            String extFilter,
                                            String pathFilter,
                                            boolean includeFileName,
                                            boolean includeContent,
                                            int maxResults) {
        ArrayList<SearchResult> out = new ArrayList<SearchResult>();
        Path normalizedRoot = root == null ? null : root.toAbsolutePath().normalize();
        if (normalizedRoot == null || !Files.isDirectory(normalizedRoot)) return out;

        String q = safe(query).trim().toLowerCase(Locale.ROOT);
        if (q.isBlank()) return out;
        if (!includeFileName && !includeContent) includeFileName = true;

        String extNeedle = safe(extFilter).trim().toLowerCase(Locale.ROOT);
        String pathNeedle = safe(pathFilter).trim().toLowerCase(Locale.ROOT);
        int wanted = Math.min(Math.max(1, maxResults), 500);
        int scanned = 0;

        try (Stream<Path> stream = Files.walk(normalizedRoot)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                if (p == null || !Files.isRegularFile(p)) continue;
                scanned++;
                if (scanned > SEARCH_SCAN_FILE_CAP) break;

                String rel = relativize(normalizedRoot, p);
                String relLower = rel.toLowerCase(Locale.ROOT);
                String ext = extension(p);
                String fileName = fileName(p);

                if (!extNeedle.isBlank() && !ext.equals(extNeedle)) continue;
                if (!pathNeedle.isBlank() && !relLower.contains(pathNeedle)) continue;

                boolean nameMatch = includeFileName && fileName.toLowerCase(Locale.ROOT).contains(q);
                boolean contentMatch = false;
                String snippet = "";
                if (includeContent && supportsContentSearch(ext)) {
                    String text = extractSearchText(p, ext);
                    if (!text.isBlank()) {
                        String textLower = text.toLowerCase(Locale.ROOT);
                        int idx = textLower.indexOf(q);
                        if (idx >= 0) {
                            contentMatch = true;
                            snippet = makeSnippet(text, idx, SEARCH_TEXT_SNIPPET_SIZE);
                        }
                    }
                }

                if (!nameMatch && !contentMatch) continue;

                String matchType = nameMatch && contentMatch
                        ? "both"
                        : (nameMatch ? "filename" : "content");
                long size = 0L;
                try { size = Files.size(p); } catch (Exception ignored) {}
                out.add(new SearchResult(rel, fileName, ext, size, matchType, snippet));
                if (out.size() >= wanted) break;
            }
        } catch (Exception ignored) {
            return out;
        }
        return out;
    }

    private static RenderedPage renderPdfPage(Path file, int requestedPageIndex) throws Exception {
        try (PDDocument doc = PDDocument.load(file.toFile())) {
            int total = Math.max(0, doc.getNumberOfPages());
            if (total <= 0) {
                return new RenderedPage(0, 0, true, false, false, "", "PDF has no pages.", "PDFBox");
            }
            int idx = clamp(requestedPageIndex, 0, total - 1);
            PDFRenderer renderer = new PDFRenderer(doc);
            BufferedImage img = renderer.renderImageWithDPI(idx, PDF_RENDER_DPI);
            String b64 = encodePngBase64(img);
            return new RenderedPage(
                    idx,
                    total,
                    true,
                    idx > 0,
                    idx + 1 < total,
                    b64,
                    "",
                    "PDFBox"
            );
        }
    }

    private static RenderedPage renderDocxPage(Path file, int requestedPageIndex) throws Exception {
        byte[] bytes = Files.readAllBytes(file);
        int requested = Math.max(0, requestedPageIndex);
        int window = Math.max(2, requested + 2);
        int maxPages = Math.min(DOCX_PREVIEW_PAGE_CAP, window);

        document_image_preview previewer = new document_image_preview();
        document_image_preview.PreviewResult preview = previewer.render(bytes, "docx", null, maxPages);
        ArrayList<document_image_preview.PageImage> pages = preview == null ? new ArrayList<>() : preview.pages;
        if (pages == null || pages.isEmpty()) {
            return new RenderedPage(0, 0, true, false, false, "", safe(preview == null ? "" : preview.warning), "Pure Java Styled Renderer");
        }

        int idx = clamp(requested, 0, pages.size() - 1);
        boolean hasNext = idx + 1 < pages.size();

        if (!hasNext && pages.size() == maxPages && maxPages < DOCX_PREVIEW_PAGE_CAP) {
            int probePages = Math.min(DOCX_PREVIEW_PAGE_CAP, maxPages + 1);
            if (probePages > maxPages) {
                document_image_preview.PreviewResult probe = previewer.render(bytes, "docx", null, probePages);
                ArrayList<document_image_preview.PageImage> probePagesList = probe == null ? null : probe.pages;
                if (probePagesList != null && probePagesList.size() > pages.size()) {
                    pages = probePagesList;
                    hasNext = idx + 1 < pages.size();
                    preview = probe;
                }
            }
        }

        int total = pages.size();
        boolean totalKnown = total < DOCX_PREVIEW_PAGE_CAP || !hasNext;
        String b64 = safe(pages.get(idx) == null ? "" : pages.get(idx).base64Png);
        return new RenderedPage(
                idx,
                total,
                totalKnown,
                idx > 0,
                hasNext,
                b64,
                safe(preview == null ? "" : preview.warning),
                safe(preview == null ? "" : preview.engine)
        );
    }

    private static boolean supportsContentSearch(String ext) {
        String e = safe(ext).toLowerCase(Locale.ROOT);
        return "pdf".equals(e)
                || "docx".equals(e)
                || "doc".equals(e)
                || "rtf".equals(e)
                || "odt".equals(e)
                || "txt".equals(e)
                || "md".equals(e)
                || "json".equals(e)
                || "xml".equals(e)
                || "csv".equals(e)
                || "html".equals(e)
                || "htm".equals(e);
    }

    private static String extractSearchText(Path file, String ext) {
        if (file == null || !Files.isRegularFile(file)) return "";
        long size = 0L;
        try { size = Files.size(file); } catch (Exception ignored) {}
        if (size <= 0L || size > MAX_CONTENT_EXTRACT_FILE_BYTES) return "";

        String e = safe(ext).toLowerCase(Locale.ROOT);
        try {
            if ("pdf".equals(e)) {
                try (PDDocument doc = PDDocument.load(file.toFile())) {
                    PDFTextStripper stripper = new PDFTextStripper();
                    stripper.setStartPage(1);
                    stripper.setEndPage(Math.min(250, Math.max(1, doc.getNumberOfPages())));
                    String txt = safe(stripper.getText(doc));
                    return truncate(txt, MAX_EXTRACT_TEXT_CHARS);
                }
            }

            byte[] bytes = Files.readAllBytes(file);
            if ("txt".equals(e) || "md".equals(e) || "json".equals(e) || "xml".equals(e)
                    || "csv".equals(e) || "html".equals(e) || "htm".equals(e)) {
                return truncate(new String(bytes), MAX_EXTRACT_TEXT_CHARS);
            }

            document_assembler assembler = new document_assembler();
            document_assembler.PreviewResult preview = assembler.preview(bytes, e, new LinkedHashMap<String, String>());
            return truncate(safe(preview == null ? "" : preview.sourceText), MAX_EXTRACT_TEXT_CHARS);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String makeSnippet(String text, int hitIndex, int size) {
        String t = safe(text).replaceAll("\\s+", " ").trim();
        if (t.isBlank()) return "";
        int n = Math.max(40, size);
        int idx = Math.max(0, Math.min(hitIndex, t.length() - 1));
        int from = Math.max(0, idx - (n / 2));
        int to = Math.min(t.length(), from + n);
        if (to <= from) return "";
        String out = t.substring(from, to).trim();
        if (from > 0) out = "... " + out;
        if (to < t.length()) out = out + " ...";
        return out;
    }

    private static String encodePngBase64(BufferedImage image) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(4096, image.getWidth() * image.getHeight() / 2));
        ImageIO.write(image, "png", out);
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    private static String relativize(Path root, Path child) {
        try {
            return root.toAbsolutePath().normalize()
                    .relativize(child.toAbsolutePath().normalize())
                    .toString()
                    .replace('\\', '/');
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String fileName(Path p) {
        if (p == null || p.getFileName() == null) return "";
        return safe(p.getFileName().toString());
    }

    private static int clamp(int v, int min, int max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }

    private static String truncate(String s, int maxChars) {
        String v = safe(s);
        if (maxChars <= 0 || v.length() <= maxChars) return v;
        return v.substring(0, maxChars);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
