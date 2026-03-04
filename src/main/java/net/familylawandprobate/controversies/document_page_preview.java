package net.familylawandprobate.controversies;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.action.PDAction;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;

import org.apache.poi.xwpf.usermodel.IBody;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFHyperlink;
import org.apache.poi.xwpf.usermodel.XWPFHyperlinkRun;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFStyle;
import org.apache.poi.xwpf.usermodel.XWPFStyles;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBookmark;

import javax.imageio.ImageIO;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * Shared page-by-page preview renderer for PDF and word processor files.
 */
public final class document_page_preview {

    private static final int PDF_RENDER_DPI = 144;
    private static final int WORD_PREVIEW_PAGE_CAP = 220;
    private static final int MAX_PAGE_TEXT_CHARS = 120_000;
    private static final int MAX_NAV_ENTRIES = 240;
    private static final int MAX_NAV_LABEL_CHARS = 180;

    private document_page_preview() {
    }

    public static final class NavigationEntry {
        public final String type; // heading | bookmark | link
        public final String label;
        public final int pageIndex; // -1 when unknown
        public final String target;
        public final boolean external;

        public NavigationEntry(String type,
                               String label,
                               int pageIndex,
                               String target,
                               boolean external) {
            this.type = safe(type);
            this.label = safe(label);
            this.pageIndex = pageIndex;
            this.target = safe(target);
            this.external = external;
        }
    }

    public static final class RenderedPage {
        public final int pageIndex;
        public final int totalPages;
        public final boolean totalKnown;
        public final boolean hasPrev;
        public final boolean hasNext;
        public final int imageWidthPx;
        public final int imageHeightPx;
        public final String base64Png;
        public final String warning;
        public final String engine;
        public final String pageText;
        public final ArrayList<NavigationEntry> navigation;

        public RenderedPage(int pageIndex,
                            int totalPages,
                            boolean totalKnown,
                            boolean hasPrev,
                            boolean hasNext,
                            int imageWidthPx,
                            int imageHeightPx,
                            String base64Png,
                            String warning,
                            String engine,
                            String pageText,
                            List<NavigationEntry> navigation) {
            this.pageIndex = Math.max(0, pageIndex);
            this.totalPages = Math.max(0, totalPages);
            this.totalKnown = totalKnown;
            this.hasPrev = hasPrev;
            this.hasNext = hasNext;
            this.imageWidthPx = Math.max(0, imageWidthPx);
            this.imageHeightPx = Math.max(0, imageHeightPx);
            this.base64Png = safe(base64Png);
            this.warning = safe(warning);
            this.engine = safe(engine);
            this.pageText = truncate(safe(pageText), MAX_PAGE_TEXT_CHARS);
            this.navigation = navigation == null
                    ? new ArrayList<NavigationEntry>()
                    : new ArrayList<NavigationEntry>(navigation);
        }
    }

    private static final class DocxNavCandidate {
        final String type;
        final String label;
        final String target;
        final boolean external;
        final String probeText;

        DocxNavCandidate(String type,
                         String label,
                         String target,
                         boolean external,
                         String probeText) {
            this.type = safe(type);
            this.label = safe(label);
            this.target = safe(target);
            this.external = external;
            this.probeText = safe(probeText);
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
        return "pdf".equals(ext)
                || "docx".equals(ext)
                || "doc".equals(ext)
                || "rtf".equals(ext)
                || "txt".equals(ext)
                || "odt".equals(ext);
    }

    public static RenderedPage renderPage(Path file, int requestedPageIndex) throws Exception {
        if (file == null || !Files.isRegularFile(file)) {
            throw new IllegalArgumentException("File not found.");
        }
        String ext = extension(file);
        if ("pdf".equals(ext)) return renderPdfPage(file, requestedPageIndex);
        if ("docx".equals(ext) || "doc".equals(ext) || "rtf".equals(ext)
                || "txt".equals(ext) || "odt".equals(ext)) {
            return renderWordProcessorPage(file, ext, requestedPageIndex);
        }
        throw new IllegalArgumentException("Viewer supports PDF, DOCX, DOC, RTF, TXT, and ODT.");
    }

    private static RenderedPage renderPdfPage(Path file, int requestedPageIndex) throws Exception {
        try (PDDocument doc = PDDocument.load(file.toFile())) {
            int total = Math.max(0, doc.getNumberOfPages());
            if (total <= 0) {
                return new RenderedPage(
                        0,
                        0,
                        true,
                        false,
                        false,
                        0,
                        0,
                        "",
                        "PDF has no pages.",
                        "PDFBox",
                        "",
                        new ArrayList<NavigationEntry>()
                );
            }
            int idx = clamp(requestedPageIndex, 0, total - 1);
            PDFRenderer renderer = new PDFRenderer(doc);
            BufferedImage img = renderer.renderImageWithDPI(idx, PDF_RENDER_DPI);
            String b64 = encodePngBase64(img);
            String pageText = extractPdfPageText(doc, idx);
            ArrayList<NavigationEntry> navigation = collectPdfNavigation(doc);
            return new RenderedPage(
                    idx,
                    total,
                    true,
                    idx > 0,
                    idx + 1 < total,
                    img == null ? 0 : img.getWidth(),
                    img == null ? 0 : img.getHeight(),
                    b64,
                    "",
                    "PDFBox",
                    pageText,
                    navigation
            );
        }
    }

    private static RenderedPage renderWordProcessorPage(Path file, String ext, int requestedPageIndex) throws Exception {
        byte[] bytes = Files.readAllBytes(file);
        int requested = Math.max(0, requestedPageIndex);
        int window = Math.max(2, requested + 2);
        int maxPages = Math.min(WORD_PREVIEW_PAGE_CAP, window);

        document_image_preview previewer = new document_image_preview();
        document_image_preview.PreviewResult preview = previewer.render(bytes, ext, null, maxPages);
        ArrayList<document_image_preview.PageImage> pages = preview == null ? new ArrayList<>() : preview.pages;
        if (pages == null || pages.isEmpty()) {
            return new RenderedPage(
                    0,
                    0,
                    true,
                    false,
                    false,
                    0,
                    0,
                    "",
                    safe(preview == null ? "" : preview.warning),
                    safe(preview == null ? "" : preview.engine),
                    "",
                    new ArrayList<NavigationEntry>()
            );
        }

        int idx = clamp(requested, 0, pages.size() - 1);
        boolean hasNext = idx + 1 < pages.size();

        if (!hasNext && pages.size() == maxPages && maxPages < WORD_PREVIEW_PAGE_CAP) {
            int probePages = Math.min(WORD_PREVIEW_PAGE_CAP, maxPages + 1);
            if (probePages > maxPages) {
                document_image_preview.PreviewResult probe = previewer.render(bytes, ext, null, probePages);
                ArrayList<document_image_preview.PageImage> probePagesList = probe == null ? null : probe.pages;
                if (probePagesList != null && probePagesList.size() > pages.size()) {
                    pages = probePagesList;
                    hasNext = idx + 1 < pages.size();
                    preview = probe;
                }
            }
        }

        int total = pages.size();
        boolean totalKnown = total < WORD_PREVIEW_PAGE_CAP || !hasNext;
        document_image_preview.PageImage page = pages.get(idx);
        String b64 = safe(page == null ? "" : page.base64Png);
        String pageText = safe(page == null ? "" : page.pageText);

        ArrayList<NavigationEntry> navigation = new ArrayList<NavigationEntry>();
        if ("docx".equals(ext)) {
            ArrayList<String> pageTexts = collectPreviewPageTexts(pages);
            navigation = collectDocxNavigation(bytes, pageTexts);
        }

        return new RenderedPage(
                idx,
                total,
                totalKnown,
                idx > 0,
                hasNext,
                page == null ? 0 : page.width,
                page == null ? 0 : page.height,
                b64,
                safe(preview == null ? "" : preview.warning),
                safe(preview == null ? "" : preview.engine),
                pageText,
                navigation
        );
    }

    private static ArrayList<NavigationEntry> collectPdfNavigation(PDDocument doc) {
        ArrayList<NavigationEntry> out = new ArrayList<NavigationEntry>();
        if (doc == null) return out;
        HashSet<String> seen = new HashSet<String>();

        try {
            PDDocumentOutline outline = doc.getDocumentCatalog() == null
                    ? null
                    : doc.getDocumentCatalog().getDocumentOutline();
            if (outline != null) {
                collectPdfBookmarkItems(outline.getFirstChild(), doc, out, seen);
            }
        } catch (Exception ignored) {
        }

        try {
            int pages = Math.max(0, doc.getNumberOfPages());
            for (int i = 0; i < pages && out.size() < MAX_NAV_ENTRIES; i++) {
                PDPage page = doc.getPage(i);
                if (page == null) continue;
                List<PDAnnotation> annotations;
                try {
                    annotations = page.getAnnotations();
                } catch (Exception ex) {
                    continue;
                }
                if (annotations == null || annotations.isEmpty()) continue;

                int perPageLinks = 0;
                for (PDAnnotation ann : annotations) {
                    if (!(ann instanceof PDAnnotationLink)) continue;
                    PDAnnotationLink link = (PDAnnotationLink) ann;
                    String label = compactLabel(link.getContents());
                    String target = "";
                    int targetPage = -1;
                    boolean external = false;

                    try {
                        PDAction action = link.getAction();
                        if (action instanceof PDActionURI) {
                            target = safe(((PDActionURI) action).getURI()).trim();
                            external = !target.isBlank();
                        } else if (action instanceof PDActionGoTo) {
                            targetPage = pageIndexFromDestination(((PDActionGoTo) action).getDestination(), doc);
                        }
                    } catch (Exception ignored) {
                    }

                    if (targetPage < 0) {
                        try {
                            targetPage = pageIndexFromDestination(link.getDestination(), doc);
                        } catch (Exception ignored) {
                        }
                    }

                    if (label.isBlank()) {
                        if (external && !target.isBlank()) {
                            label = compactLabel(target);
                        } else if (targetPage >= 0) {
                            label = "Page " + (targetPage + 1);
                        } else {
                            label = "Link";
                        }
                    }

                    int navPage = targetPage >= 0 ? targetPage : i;
                    addNavigationEntry(out, seen, "link", label, navPage, target, external);
                    perPageLinks++;
                    if (perPageLinks >= 20 || out.size() >= MAX_NAV_ENTRIES) break;
                }
            }
        } catch (Exception ignored) {
        }

        return out;
    }

    private static void collectPdfBookmarkItems(PDOutlineItem start,
                                                PDDocument doc,
                                                ArrayList<NavigationEntry> out,
                                                HashSet<String> seen) {
        if (start == null || doc == null || out == null || seen == null) return;
        PDOutlineItem current = start;

        while (current != null && out.size() < MAX_NAV_ENTRIES) {
            String label = compactLabel(current.getTitle());
            int pageIndex = -1;
            try {
                PDPage destinationPage = current.findDestinationPage(doc);
                if (destinationPage != null) {
                    pageIndex = doc.getPages().indexOf(destinationPage);
                }
            } catch (Exception ignored) {
            }
            if (pageIndex < 0) {
                try {
                    pageIndex = pageIndexFromDestination(current.getDestination(), doc);
                } catch (Exception ignored) {
                }
            }

            if (!label.isBlank()) {
                addNavigationEntry(out, seen, "bookmark", label, pageIndex, "", false);
            }

            try {
                PDOutlineItem child = current.getFirstChild();
                if (child != null) collectPdfBookmarkItems(child, doc, out, seen);
            } catch (Exception ignored) {
            }
            current = current.getNextSibling();
        }
    }

    private static int pageIndexFromDestination(PDDestination destination, PDDocument doc) {
        if (destination == null || doc == null) return -1;
        if (destination instanceof PDPageDestination) {
            PDPageDestination pd = (PDPageDestination) destination;
            try {
                int n = pd.retrievePageNumber();
                if (n >= 0 && n < doc.getNumberOfPages()) return n;
            } catch (Exception ignored) {
            }
            try {
                PDPage p = pd.getPage();
                if (p != null) {
                    int idx = doc.getPages().indexOf(p);
                    if (idx >= 0) return idx;
                }
            } catch (Exception ignored) {
            }
        }
        return -1;
    }

    private static String extractPdfPageText(PDDocument doc, int pageIndex) {
        if (doc == null) return "";
        int total = Math.max(0, doc.getNumberOfPages());
        if (total <= 0) return "";
        int idx = clamp(pageIndex, 0, total - 1);
        try {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(idx + 1);
            stripper.setEndPage(idx + 1);
            return truncate(safe(stripper.getText(doc)), MAX_PAGE_TEXT_CHARS);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static ArrayList<NavigationEntry> collectDocxNavigation(byte[] bytes, ArrayList<String> pageTexts) {
        ArrayList<NavigationEntry> out = new ArrayList<NavigationEntry>();
        if (bytes == null || bytes.length == 0) return out;

        ArrayList<String> normalizedPages = normalizePageTexts(pageTexts);
        HashSet<String> seenCandidates = new HashSet<String>();
        ArrayList<DocxNavCandidate> candidates = new ArrayList<DocxNavCandidate>();

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            XWPFStyles styles = doc.getStyles();
            ArrayList<XWPFParagraph> paragraphs = new ArrayList<XWPFParagraph>();
            collectDocxParagraphs(doc, paragraphs);

            for (XWPFParagraph p : paragraphs) {
                if (p == null) continue;

                String paragraphText = compactLabel(p.getText());
                String styleId = safe(p.getStyle()).trim();
                String styleName = styleNameFor(styles, styleId);

                if (isHeadingStyle(styleId, styleName) && !paragraphText.isBlank()) {
                    addDocxCandidate(candidates, seenCandidates, "heading", paragraphText, styleName, false, paragraphText);
                }

                try {
                    List<CTBookmark> bookmarks = p.getCTP() == null ? null : p.getCTP().getBookmarkStartList();
                    if (bookmarks != null) {
                        for (CTBookmark bm : bookmarks) {
                            if (bm == null) continue;
                            String name = safe(bm.getName()).trim();
                            if (name.isBlank() || name.startsWith("_")) continue;
                            String label = paragraphText.isBlank() ? name : paragraphText;
                            addDocxCandidate(candidates, seenCandidates, "bookmark", label, name, false, label);
                        }
                    }
                } catch (Exception ignored) {
                }

                List<XWPFRun> runs = p.getRuns();
                if (runs == null || runs.isEmpty()) continue;
                for (XWPFRun run : runs) {
                    if (!(run instanceof XWPFHyperlinkRun)) continue;
                    XWPFHyperlinkRun hr = (XWPFHyperlinkRun) run;

                    String label = compactLabel(runText(hr));
                    String anchor = safe(hr.getAnchor()).trim();
                    String url = "";
                    try {
                        XWPFHyperlink link = hr.getHyperlink(doc);
                        if (link != null) url = safe(link.getURL()).trim();
                    } catch (Exception ignored) {
                    }

                    boolean external = !url.isBlank();
                    String target = external ? url : anchor;
                    if (label.isBlank()) {
                        if (!target.isBlank()) label = compactLabel(target);
                        else label = paragraphText;
                    }
                    if (label.isBlank()) continue;

                    String probe = paragraphText.isBlank() ? label : paragraphText;
                    addDocxCandidate(candidates, seenCandidates, "link", label, target, external, probe);
                }

                if (candidates.size() >= MAX_NAV_ENTRIES) break;
            }
        } catch (Exception ignored) {
            return out;
        }

        HashSet<String> seenOut = new HashSet<String>();
        int lastPageHint = 0;
        for (DocxNavCandidate c : candidates) {
            if (c == null || out.size() >= MAX_NAV_ENTRIES) break;
            int pageIndex = findBestPageIndex(normalizedPages, c.probeText, lastPageHint);
            if (pageIndex < 0) pageIndex = findBestPageIndex(normalizedPages, c.label, lastPageHint);
            if (pageIndex >= 0) lastPageHint = pageIndex;
            addNavigationEntry(out, seenOut, c.type, c.label, pageIndex, c.target, c.external);
        }

        return out;
    }

    private static void collectDocxParagraphs(IBody body, ArrayList<XWPFParagraph> out) {
        if (body == null || out == null) return;
        List<IBodyElement> elements = body.getBodyElements();
        if (elements == null || elements.isEmpty()) return;

        for (IBodyElement el : elements) {
            if (el instanceof XWPFParagraph) {
                out.add((XWPFParagraph) el);
            } else if (el instanceof XWPFTable) {
                XWPFTable table = (XWPFTable) el;
                List<XWPFTableRow> rows = table.getRows();
                if (rows == null) continue;
                for (XWPFTableRow row : rows) {
                    if (row == null) continue;
                    List<XWPFTableCell> cells = row.getTableCells();
                    if (cells == null) continue;
                    for (XWPFTableCell cell : cells) {
                        collectDocxParagraphs(cell, out);
                    }
                }
            }
            if (out.size() >= MAX_NAV_ENTRIES * 8) return;
        }
    }

    private static void addDocxCandidate(ArrayList<DocxNavCandidate> out,
                                         HashSet<String> seen,
                                         String type,
                                         String label,
                                         String target,
                                         boolean external,
                                         String probeText) {
        if (out == null || seen == null) return;
        if (out.size() >= MAX_NAV_ENTRIES) return;

        String t = safe(type).trim().toLowerCase(Locale.ROOT);
        String l = compactLabel(label);
        String tr = safe(target).trim();
        String probe = safe(probeText).trim();
        if (l.isBlank()) return;

        String key = t + "|" + l.toLowerCase(Locale.ROOT) + "|" + tr.toLowerCase(Locale.ROOT);
        if (!seen.add(key)) return;
        out.add(new DocxNavCandidate(t, l, tr, external, probe));
    }

    private static void addNavigationEntry(ArrayList<NavigationEntry> out,
                                           HashSet<String> seen,
                                           String type,
                                           String label,
                                           int pageIndex,
                                           String target,
                                           boolean external) {
        if (out == null || seen == null) return;
        if (out.size() >= MAX_NAV_ENTRIES) return;

        String t = safe(type).trim().toLowerCase(Locale.ROOT);
        String l = compactLabel(label);
        String tr = safe(target).trim();
        int page = pageIndex < 0 ? -1 : pageIndex;
        if (l.isBlank()) return;

        String key = t + "|" + page + "|" + l.toLowerCase(Locale.ROOT) + "|" + tr.toLowerCase(Locale.ROOT);
        if (!seen.add(key)) return;

        out.add(new NavigationEntry(t, l, page, tr, external));
    }

    private static int findBestPageIndex(List<String> normalizedPages, String probe, int startHint) {
        if (normalizedPages == null || normalizedPages.isEmpty()) return -1;
        String p = normalizeLookupText(probe);
        if (p.isBlank() || p.length() < 3) return -1;

        ArrayList<String> probes = new ArrayList<String>();
        probes.add(p);
        String firstSix = firstWords(p, 6);
        if (!firstSix.isBlank() && !firstSix.equals(p)) probes.add(firstSix);
        String firstThree = firstWords(p, 3);
        if (!firstThree.isBlank() && !firstThree.equals(firstSix) && !firstThree.equals(p)) probes.add(firstThree);

        int total = normalizedPages.size();
        int start = clamp(startHint, 0, Math.max(0, total - 1));

        for (String needle : probes) {
            if (needle.isBlank()) continue;

            for (int i = start; i < total; i++) {
                String hay = normalizedPages.get(i);
                if (!hay.isBlank() && hay.contains(needle)) return i;
            }
            for (int i = 0; i < start; i++) {
                String hay = normalizedPages.get(i);
                if (!hay.isBlank() && hay.contains(needle)) return i;
            }
        }

        return -1;
    }

    private static String firstWords(String text, int wordCount) {
        String t = normalizeLookupText(text);
        if (t.isBlank() || wordCount <= 0) return "";
        String[] words = t.split(" ");
        if (words.length <= wordCount) return t;
        StringBuilder out = new StringBuilder(64);
        for (int i = 0; i < words.length && i < wordCount; i++) {
            if (i > 0) out.append(' ');
            out.append(words[i]);
        }
        return out.toString();
    }

    private static ArrayList<String> collectPreviewPageTexts(ArrayList<document_image_preview.PageImage> pages) {
        ArrayList<String> out = new ArrayList<String>();
        if (pages == null) return out;
        for (document_image_preview.PageImage page : pages) {
            out.add(safe(page == null ? "" : page.pageText));
        }
        return out;
    }

    private static ArrayList<String> normalizePageTexts(List<String> pageTexts) {
        ArrayList<String> out = new ArrayList<String>();
        if (pageTexts == null) return out;
        for (String t : pageTexts) {
            out.add(normalizeLookupText(t));
        }
        return out;
    }

    private static String normalizeLookupText(String s) {
        return safe(s)
                .replace('\u00A0', ' ')
                .replaceAll("[\\t\\r\\n]+", " ")
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private static boolean isHeadingStyle(String styleId, String styleName) {
        String id = safe(styleId).trim().toLowerCase(Locale.ROOT);
        String name = safe(styleName).trim().toLowerCase(Locale.ROOT);
        if (id.startsWith("heading")) return true;
        if (name.startsWith("heading")) return true;
        return name.matches("h[1-9]") || id.matches("h[1-9]");
    }

    private static String styleNameFor(XWPFStyles styles, String styleId) {
        if (styles == null) return "";
        String id = safe(styleId).trim();
        if (id.isBlank()) return "";
        try {
            XWPFStyle style = styles.getStyle(id);
            if (style == null) return "";
            return safe(style.getName());
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String runText(XWPFRun run) {
        if (run == null) return "";
        String txt = safe(run.text());
        if (!txt.isBlank()) return txt;
        try {
            String fallback = safe(run.getText(0));
            return fallback;
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String compactLabel(String s) {
        String v = safe(s)
                .replace('\u00A0', ' ')
                .replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (v.length() > MAX_NAV_LABEL_CHARS) v = v.substring(0, MAX_NAV_LABEL_CHARS);
        return v;
    }

    private static String encodePngBase64(BufferedImage image) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(4096, image.getWidth() * image.getHeight() / 2));
        ImageIO.write(image, "png", out);
        return Base64.getEncoder().encodeToString(out.toByteArray());
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
