package net.familylawandprobate.controversies;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.imageio.ImageIO;

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

    public static final class PageImage {
        public final int pageIndex;
        public final int width;
        public final int height;
        public final String base64Png;

        public PageImage(int pageIndex, int width, int height, String base64Png) {
            this.pageIndex = Math.max(0, pageIndex);
            this.width = Math.max(1, width);
            this.height = Math.max(1, height);
            this.base64Png = safe(base64Png);
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
            } else if ("doc".equals(previewExt) || "rtf".equals(previewExt) || "txt".equals(previewExt)) {
                document_assembler.PreviewResult plain = assembler.preview(previewBytes, previewExt, new LinkedHashMap<String, String>());
                segments.add(new document_assembler.StyledSegment(safe(plain == null ? "" : plain.sourceText), ""));
                warning = "Full fidelity image rendering for " + previewExt.toUpperCase(Locale.ROOT) + " is not available in pure Java mode; using text layout preview.";
                engine = "Pure Java Text Renderer";
            } else {
                return new PreviewResult(
                        new ArrayList<PageImage>(),
                        new LinkedHashMap<String, ArrayList<HitRect>>(),
                        "Image preview is currently supported for DOCX/DOC/RTF templates.",
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
                    encodePngBase64(st.image)
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
        String normalizedPageText = normalizeTokenDelimiters(pageText);
        String normalizedNeedle = normalizeTokenDelimiters(needle);
        if (normalizedNeedle.isBlank()) return;

        int from = 0;
        while (from < normalizedPageText.length()) {
            int idx = normalizedPageText.indexOf(normalizedNeedle, from);
            if (idx < 0) break;

            int end = idx + normalizedNeedle.length();
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

    private static String encodePngBase64(BufferedImage image) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(4096, image.getWidth() * image.getHeight() / 2));
        ImageIO.write(image, "png", out);
        return Base64.getEncoder().encodeToString(out.toByteArray());
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

    private static String normalizeTokenDelimiters(String input) {
        String src = safe(input);
        if (src.isEmpty()) return src;
        return src
                .replace('“', '{').replace('”', '}')
                .replace('‘', '[').replace('’', ']')
                .replace('｛', '{').replace('｝', '}')
                .replace('［', '[').replace('］', ']');
    }

    private static int clamp(int v, int min, int max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
