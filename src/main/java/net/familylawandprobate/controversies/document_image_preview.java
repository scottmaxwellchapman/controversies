package net.familylawandprobate.controversies;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.BasicStroke;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.imageio.ImageIO;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

/**
 * Cross-platform, pure-Java preview renderer.
 *
 * It avoids external office processes and renders a paged, Word-like preview image
 * directly from extracted styled/text content.
 */
public final class document_image_preview {

    static {
        String headless = safe(System.getProperty("java.awt.headless")).trim();
        if (headless.isBlank()) {
            System.setProperty("java.awt.headless", "true");
        }
    }

    private static final int DEFAULT_MAX_PAGES = 6;
    private static final int MAX_PAGE_TEXT_CHARS = 120_000;
    private static final int PAGE_WIDTH = 1224;   // 8.5in @ 144dpi
    private static final int PAGE_HEIGHT = 1584;  // 11in @ 144dpi
    private static final int MARGIN_LEFT = 110;
    private static final int MARGIN_RIGHT = 110;
    private static final int MARGIN_TOP = 110;
    private static final int MARGIN_BOTTOM = 110;

    private static final Color PAGE_BG = new Color(255, 255, 255);
    private static final Color DEFAULT_TEXT = new Color(17, 24, 39);
    private static final String DEFAULT_FONT_FAMILY = "Times New Roman";
    private static final int DEFAULT_FONT_PX = 24;
    private static final int TAB_SPACES = 4;
    private static final float PDF_PREVIEW_DPI = 144f;

    public static final class PageImage {
        public final int pageIndex;
        public final int width;
        public final int height;
        public final String base64Png;
        public final String pageText;

        public PageImage(int pageIndex, int width, int height, String base64Png, String pageText) {
            this.pageIndex = Math.max(0, pageIndex);
            this.width = Math.max(1, width);
            this.height = Math.max(1, height);
            this.base64Png = safe(base64Png);
            this.pageText = safe(pageText);
        }
    }

    public static final class HitRect {
        public final int pageIndex;
        public final int x;
        public final int y;
        public final int width;
        public final int height;

        public HitRect(int pageIndex, int x, int y, int width, int height) {
            this.pageIndex = Math.max(0, pageIndex);
            this.x = Math.max(0, x);
            this.y = Math.max(0, y);
            this.width = Math.max(1, width);
            this.height = Math.max(1, height);
        }
    }

    public static final class PreviewResult {
        public final ArrayList<PageImage> pages;
        public final LinkedHashMap<String, ArrayList<HitRect>> hitRects;
        public final String warning;
        public final String engine;

        public PreviewResult(ArrayList<PageImage> pages,
                             LinkedHashMap<String, ArrayList<HitRect>> hitRects,
                             String warning,
                             String engine) {
            this.pages = pages == null ? new ArrayList<PageImage>() : pages;
            this.hitRects = hitRects == null ? new LinkedHashMap<String, ArrayList<HitRect>>() : hitRects;
            this.warning = safe(warning);
            this.engine = safe(engine);
        }

        public static PreviewResult empty() {
            return new PreviewResult(
                    new ArrayList<PageImage>(),
                    new LinkedHashMap<String, ArrayList<HitRect>>(),
                    "",
                    ""
            );
        }
    }

    public static final class FocusPreview {
        public final String token;
        public final int pageIndex;
        public final int hitIndex;
        public final int hitCount;
        public final int width;
        public final int height;
        public final String base64Png;
        public final String mode;
        public final String message;

        public FocusPreview(String token,
                            int pageIndex,
                            int hitIndex,
                            int hitCount,
                            int width,
                            int height,
                            String base64Png,
                            String mode,
                            String message) {
            this.token = safe(token);
            this.pageIndex = Math.max(0, pageIndex);
            this.hitIndex = hitIndex;
            this.hitCount = Math.max(0, hitCount);
            this.width = Math.max(0, width);
            this.height = Math.max(0, height);
            this.base64Png = safe(base64Png);
            this.mode = safe(mode);
            this.message = safe(message);
        }
    }

    private static final class GlyphPos {
        final int x;
        final int y;
        final int w;
        final int h;

        GlyphPos(int x, int y, int w, int h) {
            this.x = Math.max(0, x);
            this.y = Math.max(0, y);
            this.w = Math.max(1, w);
            this.h = Math.max(1, h);
        }
    }

    private static final class TextStyle {
        final Font font;
        final Color fg;
        final Color bg;

        TextStyle(Font font, Color fg, Color bg) {
            this.font = font;
            this.fg = fg;
            this.bg = bg;
        }
    }

    private static final class PageState {
        final int index;
        final BufferedImage image;
        final Graphics2D g;
        final StringBuilder text;
        final ArrayList<GlyphPos> glyphs;

        PageState(int index) {
            this.index = index;
            this.image = new BufferedImage(PAGE_WIDTH, PAGE_HEIGHT, BufferedImage.TYPE_INT_RGB);
            this.g = image.createGraphics();
            this.g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            this.g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            this.g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            this.g.setColor(PAGE_BG);
            this.g.fillRect(0, 0, PAGE_WIDTH, PAGE_HEIGHT);
            this.text = new StringBuilder(2048);
            this.glyphs = new ArrayList<GlyphPos>(2048);
        }

        void close() {
            g.dispose();
        }
    }

    public PreviewResult render(byte[] templateBytes,
                                String templateExtOrName,
                                List<String> highlightNeedles,
                                int maxPages) {
        return render(templateBytes, templateExtOrName, null, highlightNeedles, maxPages);
    }

    public PreviewResult render(byte[] templateBytes,
                                String templateExtOrName,
                                Map<String, String> replacementValues,
                                List<String> highlightNeedles,
                                int maxPages) {
        String ext = normalizeExtension(templateExtOrName);
        if (templateBytes == null || templateBytes.length == 0) return PreviewResult.empty();

        int pageLimit = maxPages <= 0 ? DEFAULT_MAX_PAGES : Math.min(Math.max(1, maxPages), 20);
        ArrayList<String> needles = normalizeNeedles(highlightNeedles);

        try {
            document_assembler assembler = new document_assembler();
            byte[] previewBytes = templateBytes;
            String previewExt = ext;

            if (replacementValues != null && !replacementValues.isEmpty()) {
                try {
                    document_assembler.AssembledFile assembled = assembler.assemble(templateBytes, ext, replacementValues);
                    if (assembled != null && assembled.bytes != null && assembled.bytes.length > 0) {
                        previewBytes = assembled.bytes;
                        previewExt = safe(assembled.extension).isBlank() ? ext : safe(assembled.extension);
                    }
                } catch (Exception ignored) {
                    // Keep preview resilient: fallback to original bytes if assembly fails.
                }
            }

            ArrayList<document_assembler.StyledSegment> segments = new ArrayList<document_assembler.StyledSegment>();
            String warning = "";
            String engine = "Pure Java Styled Renderer";

            if ("docx".equals(previewExt)) {
                document_assembler.StyledPreview styled = assembler.previewStyled(previewBytes, previewExt);
                if (styled != null && styled.segments != null) segments.addAll(styled.segments);
                if (segments.isEmpty()) {
                    document_assembler.PreviewResult plain = assembler.preview(previewBytes, previewExt, new LinkedHashMap<String, String>());
                    segments.add(new document_assembler.StyledSegment(safe(plain == null ? "" : plain.sourceText), ""));
                    warning = "Styled DOCX rendering fallback used plain text extraction.";
                    engine = "Pure Java Text Renderer";
                }
            } else if ("doc".equals(previewExt) || "rtf".equals(previewExt) || "odt".equals(previewExt)
                    || "txt".equals(previewExt)) {
                document_assembler.PreviewResult plain = assembler.preview(previewBytes, previewExt, new LinkedHashMap<String, String>());
                segments.add(new document_assembler.StyledSegment(safe(plain == null ? "" : plain.sourceText), ""));
                warning = "Full fidelity image rendering for " + previewExt.toUpperCase(Locale.ROOT) + " is not available in pure Java mode; using text layout preview.";
                engine = "Pure Java Text Renderer";
            } else if ("pdf".equals(previewExt)) {
                return renderPdf(previewBytes, needles, pageLimit);
            } else {
                return new PreviewResult(
                        new ArrayList<PageImage>(),
                        new LinkedHashMap<String, ArrayList<HitRect>>(),
                        "Image preview is currently supported for DOCX/DOC/RTF/ODT/TXT/PDF templates.",
                        ""
                );
            }

            return rasterize(segments, needles, pageLimit, warning, engine);
        } catch (Exception ex) {
            return new PreviewResult(
                    new ArrayList<PageImage>(),
                    new LinkedHashMap<String, ArrayList<HitRect>>(),
                    "Image preview unavailable: " + safe(ex.getMessage()),
                    ""
            );
        }
    }

    public FocusPreview renderFocusPreview(PreviewResult preview,
                                           String tokenLiteral,
                                           int requestedIndex,
                                           boolean fullPage) {
        String mode = fullPage ? "full" : "context";
        String token = safe(tokenLiteral).trim();
        if (preview == null || preview.pages == null || preview.pages.isEmpty()) {
            return new FocusPreview(token, 0, -1, 0, 0, 0, "", mode, "Rendered preview is unavailable.");
        }

        PageImage firstPage = preview.pages.get(0);
        int fallbackPageIndex = firstPage == null ? 0 : Math.max(0, firstPage.pageIndex);
        ArrayList<HitRect> hits = findHitsForToken(preview.hitRects, token);
        int hitCount = hits.size();
        int idx = -1;
        HitRect hit = null;
        if (hitCount > 0) {
            idx = requestedIndex;
            if (idx < 0) idx = 0;
            idx = idx % hitCount;
            hit = hits.get(idx);
        }

        int pageIndex = hit == null ? fallbackPageIndex : Math.max(0, hit.pageIndex);
        PageImage page = pageByIndex(preview.pages, pageIndex);
        if (page == null) page = firstPage;
        if (page == null) {
            return new FocusPreview(token, pageIndex, idx, hitCount, 0, 0, "", mode, "Rendered preview is unavailable.");
        }

        BufferedImage pageImage = decodePngBase64(page.base64Png);
        if (pageImage == null) {
            return new FocusPreview(token, pageIndex, idx, hitCount, 0, 0, "", mode, "Unable to decode rendered preview image.");
        }

        int iw = Math.max(1, pageImage.getWidth());
        int ih = Math.max(1, pageImage.getHeight());

        int cropY = 0;
        int cropH = ih;
        if (!fullPage && hit != null) {
            int hy = Math.max(0, hit.y);
            int hh = Math.max(1, hit.height);
            int contextPadY = Math.max(72, (int) Math.round((ih / 11.0d) * 1.5d));
            int y1 = Math.max(0, (int) Math.floor(hy - contextPadY));
            int y2 = Math.min(ih, (int) Math.ceil(hy + hh + contextPadY));
            if (y2 <= y1) {
                y1 = 0;
                y2 = ih;
            }
            cropY = y1;
            cropH = Math.max(1, y2 - y1);
        }

        BufferedImage out = new BufferedImage(iw, cropH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(pageImage, 0, 0, iw, cropH, 0, cropY, iw, cropY + cropH, null);

        if (hit != null) {
            int hx = Math.max(0, hit.x);
            int hy = Math.max(0, hit.y - cropY);
            int hw = Math.max(1, hit.width);
            int hh = Math.max(1, hit.height);
            boolean multi = hitCount > 1;
            if (multi) {
                g.setColor(new Color(244, 114, 182, 84));
                g.fillRect(hx, hy, hw, hh);
                g.setColor(new Color(190, 24, 93, 242));
            } else {
                g.setColor(new Color(255, 222, 89, 89));
                g.fillRect(hx, hy, hw, hh);
                g.setColor(new Color(199, 133, 0, 255));
            }
            g.setStroke(new BasicStroke(2f));
            g.drawRect(hx, hy, hw, hh);
        }
        g.dispose();

        String msg;
        if (hit == null) {
            msg = "No rendered highlight was found for the selected token. Showing page " + (Math.max(0, page.pageIndex) + 1) + ".";
        } else {
            msg = "Match " + (idx + 1) + " of " + hitCount
                + " • page " + (Math.max(0, page.pageIndex) + 1)
                + (hitCount > 1 ? " • multi-instance token" : "")
                + (fullPage ? " • full page preview" : " • context: 1.5in above and below highlight");
        }

        try {
            String b64 = encodePngBase64(out);
            return new FocusPreview(token, Math.max(0, page.pageIndex), idx, hitCount, iw, cropH, b64, mode, msg);
        } catch (Exception ex) {
            return new FocusPreview(token, Math.max(0, page.pageIndex), idx, hitCount, iw, cropH, "", mode, "Unable to encode rendered preview image.");
        }
    }

    public PreviewResult renderPlainText(String text,
                                         List<String> highlightNeedles,
                                         int maxPages,
                                         String warning,
                                         String engineLabel) {
        int pageLimit = maxPages <= 0 ? DEFAULT_MAX_PAGES : Math.min(Math.max(1, maxPages), 20);
        ArrayList<String> needles = normalizeNeedles(highlightNeedles);
        ArrayList<document_assembler.StyledSegment> segments = new ArrayList<document_assembler.StyledSegment>();
        segments.add(new document_assembler.StyledSegment(safe(text), ""));
        try {
            return rasterize(segments, needles, pageLimit, safe(warning), safe(engineLabel));
        } catch (Exception ex) {
            return new PreviewResult(
                    new ArrayList<PageImage>(),
                    new LinkedHashMap<String, ArrayList<HitRect>>(),
                    "Image preview unavailable: " + safe(ex.getMessage()),
                    safe(engineLabel)
            );
        }
    }

    private static PreviewResult renderPdf(byte[] pdfBytes,
                                           ArrayList<String> needles,
                                           int maxPages) {
        LinkedHashMap<String, ArrayList<HitRect>> hitRects = new LinkedHashMap<String, ArrayList<HitRect>>();
        ArrayList<String> tokenNeedles = needles == null ? new ArrayList<String>() : needles;
        for (String needle : tokenNeedles) {
            String key = safe(needle).trim();
            if (key.isBlank()) continue;
            hitRects.put(key, new ArrayList<HitRect>());
        }

        try (PDDocument doc = PDDocument.load(pdfBytes)) {
            int totalPages = Math.max(0, doc.getNumberOfPages());
            int pageCount = Math.min(Math.max(0, maxPages), totalPages);
            ArrayList<PageImage> pages = new ArrayList<PageImage>(pageCount);

            if (pageCount > 0) {
                PDFRenderer renderer = new PDFRenderer(doc);
                for (int pageIndex = 0; pageIndex < pageCount; pageIndex++) {
                    BufferedImage image = renderer.renderImageWithDPI(pageIndex, PDF_PREVIEW_DPI, ImageType.RGB);
                    pages.add(new PageImage(
                            pageIndex,
                            image.getWidth(),
                            image.getHeight(),
                            encodePngBase64(image),
                            ""
                    ));
                }
            }

            if (!tokenNeedles.isEmpty() && pageCount > 0 && doc.getDocumentCatalog() != null) {
                PDAcroForm form = doc.getDocumentCatalog().getAcroForm();
                if (form != null) {
                    for (PDField field : form.getFieldTree()) {
                        if (field == null) continue;
                        String fieldName = safe(field.getFullyQualifiedName()).trim();
                        if (fieldName.isBlank()) continue;
                        String tokenLiteral = document_assembler.pdfFieldTokenLiteral(fieldName);

                        for (PDAnnotationWidget widget : field.getWidgets()) {
                            if (widget == null) continue;
                            int pageIndex = widgetPageIndex(doc, widget);
                            if (pageIndex < 0 || pageIndex >= pageCount) continue;

                            PageImage pageImage = pageByIndex(pages, pageIndex);
                            if (pageImage == null) continue;

                            HitRect hit = widgetToHitRect(doc.getPage(pageIndex), widget.getRectangle(), pageImage);
                            if (hit == null) continue;

                            for (String needle : tokenNeedles) {
                                if (!pdfNeedleMatches(needle, tokenLiteral, fieldName)) continue;
                                ArrayList<HitRect> out = hitRects.get(needle);
                                if (out == null) {
                                    out = new ArrayList<HitRect>();
                                    hitRects.put(needle, out);
                                }
                                out.add(hit);
                            }
                        }
                    }
                }
            }

            String warning = "";
            if (totalPages > pageCount) warning = "Preview truncated to " + pageCount + " page(s).";
            return new PreviewResult(pages, hitRects, warning, "PDF Renderer");
        } catch (Exception ex) {
            return new PreviewResult(
                    new ArrayList<PageImage>(),
                    hitRects,
                    "Image preview unavailable: " + safe(ex.getMessage()),
                    ""
            );
        }
    }

    private static boolean pdfNeedleMatches(String needle, String tokenLiteral, String fieldName) {
        String n = safe(needle).trim();
        if (n.isBlank()) return false;

        String literal = safe(tokenLiteral).trim();
        String field = safe(fieldName).trim();

        if (!literal.isBlank() && (n.equals(literal) || n.equalsIgnoreCase(literal))) return true;
        if (!field.isBlank() && (n.equals(field) || n.equalsIgnoreCase(field))) return true;

        String normalizedNeedle = normalizeTokenLookupKey(n);
        if (normalizedNeedle.isBlank()) return false;

        if (!literal.isBlank() && normalizedNeedle.equals(normalizeTokenLookupKey(literal))) return true;
        return !field.isBlank() && normalizedNeedle.equals(normalizeTokenLookupKey(field));
    }

    private static int widgetPageIndex(PDDocument doc, PDAnnotationWidget widget) {
        if (doc == null || widget == null) return -1;

        PDPage directPage = widget.getPage();
        if (directPage != null) {
            int total = Math.max(0, doc.getNumberOfPages());
            for (int i = 0; i < total; i++) {
                if (doc.getPage(i) == directPage) return i;
            }
        }

        int total = Math.max(0, doc.getNumberOfPages());
        for (int i = 0; i < total; i++) {
            PDPage page = doc.getPage(i);
            if (page == null) continue;
            try {
                List<PDAnnotation> annotations = page.getAnnotations();
                if (annotations == null) continue;
                for (PDAnnotation ann : annotations) {
                    if (ann == null) continue;
                    if (ann == widget) return i;
                    try {
                        if (ann.getCOSObject() == widget.getCOSObject()) return i;
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
        }
        return -1;
    }

    private static HitRect widgetToHitRect(PDPage page, PDRectangle widgetRect, PageImage pageImage) {
        if (page == null || widgetRect == null || pageImage == null) return null;
        int width = Math.max(1, pageImage.width);
        int height = Math.max(1, pageImage.height);

        PDRectangle pageBox = page.getCropBox();
        if (pageBox == null) pageBox = page.getMediaBox();
        if (pageBox == null) return null;

        double pageWidth = Math.max(1.0d, pageBox.getWidth());
        double pageHeight = Math.max(1.0d, pageBox.getHeight());
        double scaleX = width / pageWidth;
        double scaleY = height / pageHeight;

        double xPt = widgetRect.getLowerLeftX() - pageBox.getLowerLeftX();
        double yPt = widgetRect.getLowerLeftY() - pageBox.getLowerLeftY();
        double wPt = Math.max(1.0d, widgetRect.getWidth());
        double hPt = Math.max(1.0d, widgetRect.getHeight());

        int w = Math.max(1, (int) Math.round(wPt * scaleX));
        int h = Math.max(1, (int) Math.round(hPt * scaleY));

        int x = (int) Math.round(xPt * scaleX);
        int yFromBottom = (int) Math.round(yPt * scaleY);
        int y = height - (yFromBottom + h);

        x = clamp(x, 0, Math.max(0, width - 1));
        y = clamp(y, 0, Math.max(0, height - 1));
        if (x + w > width) w = Math.max(1, width - x);
        if (y + h > height) h = Math.max(1, height - y);

        return new HitRect(pageImage.pageIndex, x, y, w, h);
    }

    private static PreviewResult rasterize(ArrayList<document_assembler.StyledSegment> segments,
                                           ArrayList<String> needles,
                                           int maxPages,
                                           String warning,
                                           String engine) throws Exception {
        ArrayList<PageState> states = new ArrayList<PageState>();
        LinkedHashMap<String, ArrayList<HitRect>> hitRects = new LinkedHashMap<String, ArrayList<HitRect>>();
        for (String n : needles) hitRects.put(n, new ArrayList<HitRect>());

        Map<String, TextStyle> styleCache = new LinkedHashMap<String, TextStyle>();

        PageState page = new PageState(0);
        states.add(page);

        int x = MARGIN_LEFT;
        int yTop = MARGIN_TOP;
        int lineHeight = Math.max(26, DEFAULT_FONT_PX + 8);

        boolean pageLimitReached = false;

        for (document_assembler.StyledSegment seg : segments) {
            if (seg == null) continue;
            String text = safe(seg.text);
            if (text.isEmpty()) continue;

            TextStyle style = toStyle(safe(seg.css), styleCache);
            page.g.setFont(style.font);
            var fm = page.g.getFontMetrics(style.font);
            int styleLineHeight = Math.max(16, fm.getHeight() + 2);
            int ascent = fm.getAscent();

            for (int i = 0; i < text.length(); i++) {
                char ch = text.charAt(i);
                if (ch == '\r') continue;

                if (ch == '\n') {
                    yTop += lineHeight;
                    x = MARGIN_LEFT;
                    lineHeight = Math.max(26, styleLineHeight);
                    if (yTop + lineHeight > PAGE_HEIGHT - MARGIN_BOTTOM) {
                        if (states.size() >= maxPages) {
                            pageLimitReached = true;
                            break;
                        }
                        page = new PageState(states.size());
                        states.add(page);
                        x = MARGIN_LEFT;
                        yTop = MARGIN_TOP;
                        lineHeight = Math.max(26, styleLineHeight);
                        page.g.setFont(style.font);
                    }
                    continue;
                }

                int repeats = (ch == '\t') ? TAB_SPACES : 1;
                char drawable = (ch == '\t') ? ' ' : ch;

                for (int r = 0; r < repeats; r++) {
                    page.g.setFont(style.font);
                    fm = page.g.getFontMetrics(style.font);
                    ascent = fm.getAscent();
                    styleLineHeight = Math.max(16, fm.getHeight() + 2);
                    if (styleLineHeight > lineHeight) lineHeight = styleLineHeight;

                    int w = Math.max(1, fm.charWidth(drawable));
                    if (x + w > PAGE_WIDTH - MARGIN_RIGHT) {
                        yTop += lineHeight;
                        x = MARGIN_LEFT;
                        lineHeight = Math.max(26, styleLineHeight);
                    }

                    if (yTop + lineHeight > PAGE_HEIGHT - MARGIN_BOTTOM) {
                        if (states.size() >= maxPages) {
                            pageLimitReached = true;
                            break;
                        }
                        page = new PageState(states.size());
                        states.add(page);
                        x = MARGIN_LEFT;
                        yTop = MARGIN_TOP;
                        lineHeight = Math.max(26, styleLineHeight);
                        page.g.setFont(style.font);
                        fm = page.g.getFontMetrics(style.font);
                        ascent = fm.getAscent();
                    }

                    if (style.bg != null) {
                        page.g.setColor(style.bg);
                        page.g.fillRect(x, yTop, w, lineHeight);
                    }

                    page.g.setColor(style.fg == null ? DEFAULT_TEXT : style.fg);
                    page.g.drawString(String.valueOf(drawable), x, yTop + ascent);

                    page.text.append(drawable);
                    page.glyphs.add(new GlyphPos(x, yTop, w, lineHeight));
                    x += w;
                }

                if (pageLimitReached) break;
            }

            if (pageLimitReached) break;
        }

        ArrayList<PageImage> pageImages = new ArrayList<PageImage>(states.size());
        for (PageState st : states) {
            for (String needle : needles) {
                ArrayList<HitRect> out = hitRects.get(needle);
                if (out != null) locateNeedleRects(st.text.toString(), st.glyphs, needle, st.index, out);
            }

            pageImages.add(new PageImage(
                    st.index,
                    st.image.getWidth(),
                    st.image.getHeight(),
                    encodePngBase64(st.image),
                    truncate(st.text.toString(), MAX_PAGE_TEXT_CHARS)
            ));
            st.close();
        }

        String warn = safe(warning);
        if (pageLimitReached) {
            if (!warn.isBlank()) warn += " ";
            warn += "Preview truncated to " + maxPages + " page(s).";
        }

        return new PreviewResult(pageImages, hitRects, warn, safe(engine));
    }

    private static TextStyle toStyle(String css, Map<String, TextStyle> cache) {
        String key = safe(css);
        if (cache != null && cache.containsKey(key)) return cache.get(key);

        String family = DEFAULT_FONT_FAMILY;
        int sizePx = DEFAULT_FONT_PX;
        boolean bold = false;
        boolean italic = false;
        Color fg = DEFAULT_TEXT;
        Color bg = null;

        String[] rules = key.split(";");
        for (String rawRule : rules) {
            String rule = safe(rawRule).trim();
            if (rule.isBlank()) continue;
            int idx = rule.indexOf(':');
            if (idx <= 0 || idx + 1 >= rule.length()) continue;

            String k = rule.substring(0, idx).trim().toLowerCase(Locale.ROOT);
            String v = rule.substring(idx + 1).trim();

            if ("font-family".equals(k)) {
                String cleaned = v.replace("\"", "").replace("'", "").trim();
                if (!cleaned.isBlank()) family = cleaned;
            } else if ("font-size".equals(k)) {
                String vl = v.toLowerCase(Locale.ROOT);
                if (vl.endsWith("pt")) {
                    Double pt = parseDouble(vl.substring(0, vl.length() - 2));
                    if (pt != null) sizePx = clamp((int) Math.round(pt * 2.0d), 10, 96);
                } else if (vl.endsWith("px")) {
                    Double px = parseDouble(vl.substring(0, vl.length() - 2));
                    if (px != null) sizePx = clamp((int) Math.round(px), 10, 96);
                } else if ("smaller".equals(vl)) {
                    sizePx = clamp((int) Math.round(sizePx * 0.85d), 10, 96);
                }
            } else if ("font-weight".equals(k)) {
                String vl = v.toLowerCase(Locale.ROOT);
                if (vl.contains("bold") || vl.contains("700") || vl.contains("800") || vl.contains("900")) bold = true;
            } else if ("font-style".equals(k)) {
                if (v.toLowerCase(Locale.ROOT).contains("italic")) italic = true;
            } else if ("color".equals(k)) {
                Color c = parseColor(v);
                if (c != null) fg = c;
            } else if ("background".equals(k) || "background-color".equals(k)) {
                Color c = parseColor(v);
                if (c != null) bg = c;
            }
        }

        int style = Font.PLAIN;
        if (bold) style |= Font.BOLD;
        if (italic) style |= Font.ITALIC;

        Font font = new Font(family, style, Math.max(10, sizePx));
        TextStyle out = new TextStyle(font, fg, bg);
        if (cache != null) cache.put(key, out);
        return out;
    }

    private static Color parseColor(String raw) {
        String v = safe(raw).trim().toLowerCase(Locale.ROOT);
        if (v.isBlank()) return null;
        if (v.startsWith("#")) {
            String hex = v.substring(1).replaceAll("[^0-9a-f]", "");
            if (hex.length() == 3) {
                hex = "" + hex.charAt(0) + hex.charAt(0)
                        + hex.charAt(1) + hex.charAt(1)
                        + hex.charAt(2) + hex.charAt(2);
            }
            if (hex.length() == 6) {
                try {
                    return new Color(Integer.parseInt(hex, 16));
                } catch (Exception ignored) {
                    return null;
                }
            }
            return null;
        }

        if ("black".equals(v)) return Color.BLACK;
        if ("white".equals(v)) return Color.WHITE;
        if ("red".equals(v)) return Color.RED;
        if ("blue".equals(v)) return Color.BLUE;
        if ("green".equals(v)) return new Color(0, 128, 0);
        if ("yellow".equals(v)) return Color.YELLOW;
        if ("gray".equals(v) || "grey".equals(v)) return Color.GRAY;
        return null;
    }

    private static void locateNeedleRects(String pageText,
                                          ArrayList<GlyphPos> glyphs,
                                          String needle,
                                          int pageIndex,
                                          ArrayList<HitRect> out) {
        if (pageText == null || glyphs == null || needle == null || out == null) return;
        if (needle.isBlank()) return;

        int from = 0;
        while (from < pageText.length()) {
            int idx = pageText.indexOf(needle, from);
            if (idx < 0) break;

            int end = idx + needle.length();
            if (end > glyphs.size()) break;

            int x1 = Integer.MAX_VALUE;
            int y1 = Integer.MAX_VALUE;
            int x2 = -1;
            int y2 = -1;

            for (int i = idx; i < end; i++) {
                GlyphPos g = glyphs.get(i);
                if (g == null) continue;
                x1 = Math.min(x1, g.x);
                y1 = Math.min(y1, g.y);
                x2 = Math.max(x2, g.x + g.w);
                y2 = Math.max(y2, g.y + g.h);
            }

            if (x2 > x1 && y2 > y1) {
                out.add(new HitRect(pageIndex, x1, y1, x2 - x1, y2 - y1));
            }

            from = idx + 1;
        }
    }

    private static PageImage pageByIndex(List<PageImage> pages, int pageIndex) {
        if (pages == null || pages.isEmpty()) return null;
        for (PageImage p : pages) {
            if (p == null) continue;
            if (Math.max(0, p.pageIndex) == Math.max(0, pageIndex)) return p;
        }
        return pages.get(0);
    }

    private static ArrayList<HitRect> findHitsForToken(Map<String, ArrayList<HitRect>> hitMap, String tokenLiteral) {
        ArrayList<HitRect> empty = new ArrayList<HitRect>();
        if (hitMap == null || hitMap.isEmpty()) return empty;
        String token = safe(tokenLiteral).trim();
        if (token.isBlank()) return empty;

        ArrayList<HitRect> exact = hitMap.get(token);
        if (exact != null) return exact;

        String normalized = normalizeTokenLookupKey(token);
        if (normalized.isBlank()) return empty;

        for (Map.Entry<String, ArrayList<HitRect>> e : hitMap.entrySet()) {
            if (e == null) continue;
            String k = safe(e.getKey()).trim();
            if (k.isBlank()) continue;
            if (normalized.equals(normalizeTokenLookupKey(k))) {
                ArrayList<HitRect> v = e.getValue();
                return v == null ? empty : v;
            }
        }
        return empty;
    }

    private static String normalizeTokenLookupKey(String tokenLiteral) {
        String t = safe(tokenLiteral).trim();
        if (t.isBlank()) return "";

        if (t.startsWith("{{") && t.endsWith("}}") && t.length() > 4) {
            String body = safe(t.substring(2, t.length() - 2)).trim().toLowerCase(Locale.ROOT);
            if (body.matches("[a-z0-9_.-]+")) return "curly:" + body;
        }
        if (t.startsWith("[") && t.endsWith("]") && t.length() > 2) {
            String body = safe(t.substring(1, t.length() - 1)).trim().toLowerCase(Locale.ROOT);
            if (!body.isBlank()) return "bracket:" + body;
        }
        if (t.startsWith("{") && t.endsWith("}") && t.length() > 2 && t.indexOf('/') >= 0) {
            String body = safe(t.substring(1, t.length() - 1)).trim().toLowerCase(Locale.ROOT);
            if (!body.isBlank()) return "switch:" + body;
        }
        if (t.matches("[A-Za-z0-9_.-]+")) return "curly:" + t.toLowerCase(Locale.ROOT);
        return "raw:" + t.toLowerCase(Locale.ROOT);
    }

    private static String encodePngBase64(BufferedImage image) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(4096, image.getWidth() * image.getHeight() / 2));
        ImageIO.write(image, "png", out);
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    private static BufferedImage decodePngBase64(String base64Png) {
        String b64 = safe(base64Png).trim();
        if (b64.isBlank()) return null;
        try {
            byte[] raw = Base64.getDecoder().decode(b64);
            if (raw == null || raw.length == 0) return null;
            return ImageIO.read(new ByteArrayInputStream(raw));
        } catch (Exception ex) {
            return null;
        }
    }

    private static Double parseDouble(String s) {
        try {
            return Double.valueOf(safe(s).trim());
        } catch (Exception ex) {
            return null;
        }
    }

    private static ArrayList<String> normalizeNeedles(List<String> highlightNeedles) {
        LinkedHashSet<String> out = new LinkedHashSet<String>();
        if (highlightNeedles == null) return new ArrayList<String>();
        for (String raw : highlightNeedles) {
            String n = safe(raw).trim();
            if (n.isBlank()) continue;
            if (n.length() > 240) n = n.substring(0, 240);
            out.add(n);
            if (out.size() >= 240) break;
        }
        return new ArrayList<String>(out);
    }

    private static String normalizeExtension(String extOrName) {
        String v = safe(extOrName).trim().toLowerCase(Locale.ROOT);
        if (v.isBlank()) return "";
        int slash = Math.max(v.lastIndexOf('/'), v.lastIndexOf('\\'));
        if (slash >= 0 && slash + 1 < v.length()) v = v.substring(slash + 1);
        int dot = v.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < v.length()) v = v.substring(dot + 1);
        return v.replaceAll("[^a-z0-9]", "");
    }

    private static int clamp(int v, int min, int max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String truncate(String s, int maxChars) {
        String v = safe(s);
        if (maxChars <= 0 || v.length() <= maxChars) return v;
        return v.substring(0, maxChars);
    }
}
