package net.familylawandprobate.controversies;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.swing.text.Document;
import javax.swing.text.rtf.RTFEditorKit;

import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.hwpf.usermodel.Range;
import org.apache.poi.xwpf.model.XWPFHeaderFooterPolicy;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.IBody;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFFooter;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTText;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STFldCharType;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * document_assembler
 *
 * Performs token replacement for .docx/.doc/.rtf/.txt templates.
 * Supported placeholder formats:
 *   {{token_name}}
 *   [Name of defendant]  -> lookup key "name_of_defendant"
 *   {a/b/c} and [a/b/c] switch-style placeholders
 *   nested bracket placeholders such as [a[b]c] and [a/b/c[d]e]
 */
public final class document_assembler {
    private static final int MAX_TOKEN_BODY_LEN = 600;
    private static final Pattern FORMAT_DATE_PATTERN = Pattern.compile("\\{\\{\\s*format\\.date\\s+([^\\s}]+)\\s+\"([^\"]+)\"\\s*}}", Pattern.CASE_INSENSITIVE);

    public static final class PreviewResult {
        public final String sourceText;
        public final String assembledText;
        public final LinkedHashSet<String> usedTokens;
        public final LinkedHashSet<String> missingTokens;
        public final LinkedHashMap<String, Integer> tokenCounts;

        public PreviewResult(String sourceText,
                             String assembledText,
                             LinkedHashSet<String> usedTokens,
                             LinkedHashSet<String> missingTokens,
                             LinkedHashMap<String, Integer> tokenCounts) {
            this.sourceText = safe(sourceText);
            this.assembledText = safe(assembledText);
            this.usedTokens = usedTokens == null ? new LinkedHashSet<String>() : usedTokens;
            this.missingTokens = missingTokens == null ? new LinkedHashSet<String>() : missingTokens;
            this.tokenCounts = tokenCounts == null ? new LinkedHashMap<String, Integer>() : tokenCounts;
        }
    }

    public static final class AssembledFile {
        public final byte[] bytes;
        public final String extension;
        public final String contentType;

        public AssembledFile(byte[] bytes, String extension, String contentType) {
            this.bytes = bytes == null ? new byte[0] : bytes;
            this.extension = safe(extension);
            this.contentType = safe(contentType);
        }
    }

    public static final class StyledSegment {
        public final String text;
        public final String css;

        public StyledSegment(String text, String css) {
            this.text = safe(text);
            this.css = safe(css);
        }
    }

    public static final class StyledPreview {
        public final ArrayList<StyledSegment> segments;
        public final boolean styled;

        public StyledPreview(ArrayList<StyledSegment> segments, boolean styled) {
            this.segments = segments == null ? new ArrayList<StyledSegment>() : segments;
            this.styled = styled;
        }
    }

    public PreviewResult preview(byte[] templateBytes, String templateExtOrName, Map<String, String> values) {
        String ext = effectiveExtension(templateExtOrName, templateBytes);
        String source;

        if ("doc".equals(ext) && isLikelyRtfContent(templateBytes, null)) ext = "rtf";

        try {
            if ("docx".equals(ext)) {
                try {
                    source = extractDocxText(templateBytes);
                } catch (Exception ex) {
                    source = extractDocxTextLenient(templateBytes);
                    if (source.isBlank()) throw ex;
                }
            } else if ("doc".equals(ext)) {
                source = extractDocText(templateBytes);
            } else if ("rtf".equals(ext)) {
                source = extractRtfText(templateBytes);
            } else {
                source = extractTxtText(templateBytes);
            }
        } catch (Exception ex) {
            if ("docx".equals(ext)) source = "";
            else source = extractTxtText(templateBytes);
        }

        TokenScan scan = scanTokens(source, values);
        return new PreviewResult(
                source,
                scan.assembledText,
                scan.usedTokens,
                scan.missingTokens,
                scan.tokenCounts
        );
    }

    public AssembledFile assemble(byte[] templateBytes, String templateExtOrName, Map<String, String> values) throws Exception {
        String ext = effectiveExtension(templateExtOrName, templateBytes);
        if ("doc".equals(ext) && isLikelyRtfContent(templateBytes, null)) ext = "rtf";

        if ("docx".equals(ext)) {
            byte[] out = assembleDocx(templateBytes, values);
            return new AssembledFile(out, "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        }
        if ("doc".equals(ext)) {
            try {
                byte[] out = assembleDoc(templateBytes, values);
                return new AssembledFile(out, "doc", "application/msword");
            } catch (Exception ex) {
                if (isLikelyRtfContent(templateBytes, ex)) {
                    byte[] out = assembleRtf(templateBytes, values);
                    return new AssembledFile(out, "rtf", "application/rtf");
                }
                throw ex;
            }
        }
        if ("rtf".equals(ext)) {
            byte[] out = assembleRtf(templateBytes, values);
            return new AssembledFile(out, "rtf", "application/rtf");
        }

        byte[] out = assembleTxt(templateBytes, values);
        return new AssembledFile(out, "txt", "text/plain; charset=UTF-8");
    }

    public byte[] deleteTextAndAbove(byte[] templateBytes, String templateExtOrName, String anchorText) throws Exception {
        String ext = normalizeExtension(templateExtOrName);
        ensureDocxTemplateTools(ext);
        String anchor = safe(anchorText).trim();
        if (anchor.isBlank()) throw new IllegalArgumentException("Anchor text is required.");
        if (templateBytes == null || templateBytes.length == 0) throw new IllegalArgumentException("Template file is empty.");

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(templateBytes))) {
            boolean changed = trimDocxByAnchor(doc, anchor, true);
            if (!changed) throw new IllegalArgumentException("Anchor text not found in template.");
            return writeDocx(doc, templateBytes.length);
        }
    }

    public byte[] deleteTextAndBelow(byte[] templateBytes, String templateExtOrName, String anchorText) throws Exception {
        String ext = normalizeExtension(templateExtOrName);
        ensureDocxTemplateTools(ext);
        String anchor = safe(anchorText).trim();
        if (anchor.isBlank()) throw new IllegalArgumentException("Anchor text is required.");
        if (templateBytes == null || templateBytes.length == 0) throw new IllegalArgumentException("Template file is empty.");

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(templateBytes))) {
            boolean changed = trimDocxByAnchor(doc, anchor, false);
            if (!changed) throw new IllegalArgumentException("Anchor text not found in template.");
            return writeDocx(doc, templateBytes.length);
        }
    }

    public byte[] normalizeFontFamily(byte[] templateBytes, String templateExtOrName, String fontFamily) throws Exception {
        String ext = normalizeExtension(templateExtOrName);
        ensureDocxTemplateTools(ext);
        String family = safe(fontFamily).trim();
        if (family.isBlank()) throw new IllegalArgumentException("Font family is required.");
        if (templateBytes == null || templateBytes.length == 0) throw new IllegalArgumentException("Template file is empty.");

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(templateBytes))) {
            ArrayList<XWPFRun> runs = new ArrayList<XWPFRun>();
            collectAllRuns(doc, runs);
            if (runs.isEmpty()) return templateBytes;
            for (XWPFRun run : runs) {
                if (run == null) continue;
                run.setFontFamily(family);
            }
            return writeDocx(doc, templateBytes.length);
        }
    }

    public byte[] normalizeFontSize(byte[] templateBytes, String templateExtOrName, int sizePt) throws Exception {
        String ext = normalizeExtension(templateExtOrName);
        ensureDocxTemplateTools(ext);
        if (sizePt < 6 || sizePt > 72) throw new IllegalArgumentException("Font size must be between 6 and 72.");
        if (templateBytes == null || templateBytes.length == 0) throw new IllegalArgumentException("Template file is empty.");

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(templateBytes))) {
            ArrayList<XWPFRun> runs = new ArrayList<XWPFRun>();
            collectAllRuns(doc, runs);
            if (runs.isEmpty()) return templateBytes;
            for (XWPFRun run : runs) {
                if (run == null) continue;
                run.setFontSize(sizePt);
            }
            return writeDocx(doc, templateBytes.length);
        }
    }

    public byte[] deleteHeader(byte[] templateBytes, String templateExtOrName) throws Exception {
        String ext = normalizeExtension(templateExtOrName);
        ensureDocxTemplateTools(ext);
        if (templateBytes == null || templateBytes.length == 0) throw new IllegalArgumentException("Template file is empty.");

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(templateBytes))) {
            XWPFHeaderFooterPolicy policy = new XWPFHeaderFooterPolicy(doc);
            clearHeader(policy.getDefaultHeader());
            clearHeader(policy.getEvenPageHeader());
            clearHeader(policy.getFirstPageHeader());
            return writeDocx(doc, templateBytes.length);
        }
    }

    public byte[] addHeader(byte[] templateBytes, String templateExtOrName, String headerText) throws Exception {
        String ext = normalizeExtension(templateExtOrName);
        ensureDocxTemplateTools(ext);
        String text = safe(headerText).trim();
        if (text.isBlank()) throw new IllegalArgumentException("Header text is required.");
        if (templateBytes == null || templateBytes.length == 0) throw new IllegalArgumentException("Template file is empty.");

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(templateBytes))) {
            XWPFHeaderFooterPolicy policy = new XWPFHeaderFooterPolicy(doc);
            XWPFHeader header = policy.getDefaultHeader();
            if (header == null) header = policy.createHeader(XWPFHeaderFooterPolicy.DEFAULT);
            if (header == null) throw new IllegalStateException("Unable to create header.");

            header.clearHeaderFooter();
            XWPFParagraph p = header.createParagraph();
            p.setAlignment(ParagraphAlignment.CENTER);
            writeMultilineText(p, text);
            return writeDocx(doc, templateBytes.length);
        }
    }

    public byte[] deleteFooter(byte[] templateBytes, String templateExtOrName) throws Exception {
        String ext = normalizeExtension(templateExtOrName);
        ensureDocxTemplateTools(ext);
        if (templateBytes == null || templateBytes.length == 0) throw new IllegalArgumentException("Template file is empty.");

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(templateBytes))) {
            XWPFHeaderFooterPolicy policy = new XWPFHeaderFooterPolicy(doc);
            clearFooter(policy.getDefaultFooter());
            clearFooter(policy.getEvenPageFooter());
            clearFooter(policy.getFirstPageFooter());
            return writeDocx(doc, templateBytes.length);
        }
    }

    public byte[] addFooterWithPagination(byte[] templateBytes, String templateExtOrName, String footerPrefix) throws Exception {
        String ext = normalizeExtension(templateExtOrName);
        ensureDocxTemplateTools(ext);
        if (templateBytes == null || templateBytes.length == 0) throw new IllegalArgumentException("Template file is empty.");

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(templateBytes))) {
            XWPFHeaderFooterPolicy policy = new XWPFHeaderFooterPolicy(doc);
            XWPFFooter footer = policy.getDefaultFooter();
            if (footer == null) footer = policy.createFooter(XWPFHeaderFooterPolicy.DEFAULT);
            if (footer == null) throw new IllegalStateException("Unable to create footer.");

            footer.clearHeaderFooter();
            XWPFParagraph p = footer.createParagraph();
            p.setAlignment(ParagraphAlignment.CENTER);

            String prefix = safe(footerPrefix).trim();
            if (!prefix.isBlank()) {
                XWPFRun pre = p.createRun();
                pre.setText(prefix + " ");
            }

            XWPFRun label = p.createRun();
            label.setText("Page ");
            appendField(p, "PAGE", "1");

            XWPFRun mid = p.createRun();
            mid.setText(" of ");
            appendField(p, "NUMPAGES", "1");

            return writeDocx(doc, templateBytes.length);
        }
    }

    public StyledPreview previewStyled(byte[] templateBytes, String templateExtOrName) {
        String ext = effectiveExtension(templateExtOrName, templateBytes);
        if (!"docx".equals(ext)) {
            try {
                String source = preview(templateBytes, ext, new LinkedHashMap<String, String>()).sourceText;
                ArrayList<StyledSegment> out = new ArrayList<StyledSegment>();
                out.add(new StyledSegment(source, ""));
                return new StyledPreview(out, false);
            } catch (Exception ignored) {
                return new StyledPreview(new ArrayList<StyledSegment>(), false);
            }
        }

        if (templateBytes == null || templateBytes.length == 0) {
            return new StyledPreview(new ArrayList<StyledSegment>(), true);
        }

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(templateBytes))) {
            ArrayList<StyledSegment> out = new ArrayList<StyledSegment>();
            appendStyledBody(doc, out);
            if (out.isEmpty()) out.add(new StyledSegment("", ""));
            return new StyledPreview(out, true);
        } catch (Exception ex) {
            try {
                String source = extractDocxTextLenient(templateBytes);
                ArrayList<StyledSegment> out = new ArrayList<StyledSegment>();
                out.add(new StyledSegment(source, ""));
                return new StyledPreview(out, false);
            } catch (Exception ignored) {
                return new StyledPreview(new ArrayList<StyledSegment>(), false);
            }
        }
    }

    public LinkedHashMap<String, String> workspaceTokenDefaults(String sourceText, Map<String, String> values) {
        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
        ArrayList<TokenMatch> matches = findTokenMatches(safe(sourceText));
        for (TokenMatch tm : matches) {
            if (tm == null) continue;
            TokenRef token = tm.token;
            if (token == null) continue;
            String literal = safe(token.fullToken);
            if (literal.isBlank()) continue;

            String replacement = resolveReplacement(token, values);
            if (literal.equals(replacement)) replacement = "";
            out.putIfAbsent(literal, replacement);
        }
        return out;
    }

    /**
     * Applies token replacement logic directly to plain text using the same resolver
     * and token grammar as template assembly.
     */
    public String applyReplacementsToText(String sourceText, Map<String, String> values) {
        TokenScan scan = scanTokens(safe(sourceText), values);
        return safe(scan == null ? "" : scan.assembledText);
    }

    public String normalizeExtension(String extOrName) {
        String v = safe(extOrName).trim().toLowerCase(Locale.ROOT);
        if (v.isBlank()) return "txt";

        int slash = Math.max(v.lastIndexOf('/'), v.lastIndexOf('\\'));
        if (slash >= 0 && slash + 1 < v.length()) v = v.substring(slash + 1);

        int dot = v.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < v.length()) v = v.substring(dot + 1);

        v = v.replaceAll("[^a-z0-9]", "");
        if (v.isBlank()) return "txt";
        return v;
    }

    private String effectiveExtension(String extOrName, byte[] bytes) {
        String ext = normalizeExtension(extOrName);
        if (!"txt".equals(ext)) return ext;
        if (looksLikeDocxZip(bytes)) return "docx";
        if (looksLikeOleDoc(bytes)) return "doc";
        if (looksLikeRtf(bytes)) return "rtf";
        return ext;
    }

    private static boolean looksLikeDocxZip(byte[] bytes) {
        if (bytes == null || bytes.length < 4) return false;
        if ((bytes[0] & 0xFF) != 0x50 || (bytes[1] & 0xFF) != 0x4B) return false;
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                String name = safe(entry.getName()).replace('\\', '/').toLowerCase(Locale.ROOT);
                if ("word/document.xml".equals(name)) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static boolean looksLikeOleDoc(byte[] bytes) {
        if (bytes == null || bytes.length < 8) return false;
        return (bytes[0] & 0xFF) == 0xD0
                && (bytes[1] & 0xFF) == 0xCF
                && (bytes[2] & 0xFF) == 0x11
                && (bytes[3] & 0xFF) == 0xE0
                && (bytes[4] & 0xFF) == 0xA1
                && (bytes[5] & 0xFF) == 0xB1
                && (bytes[6] & 0xFF) == 0x1A
                && (bytes[7] & 0xFF) == 0xE1;
    }

    private static boolean looksLikeRtf(byte[] bytes) {
        if (bytes == null || bytes.length < 5) return false;
        String prefix = new String(bytes, 0, Math.min(bytes.length, 24), StandardCharsets.US_ASCII).toLowerCase(Locale.ROOT);
        return prefix.contains("{\\rtf");
    }

    private static void ensureDocxTemplateTools(String ext) {
        if (!"docx".equals(safe(ext).trim().toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Template tools currently support DOCX templates only.");
        }
    }

    private static byte[] writeDocx(XWPFDocument doc, int sizeHint) throws Exception {
        int hint = Math.max(4096, sizeHint + 8192);
        ByteArrayOutputStream out = new ByteArrayOutputStream(hint);
        doc.write(out);
        return out.toByteArray();
    }

    private static void clearHeader(XWPFHeader header) {
        if (header != null) header.clearHeaderFooter();
    }

    private static void clearFooter(XWPFFooter footer) {
        if (footer != null) footer.clearHeaderFooter();
    }

    private static void writeMultilineText(XWPFParagraph p, String text) {
        if (p == null) return;
        String[] lines = safe(text).split("\\r?\\n", -1);
        XWPFRun run = p.createRun();
        if (lines.length == 0) {
            run.setText("");
            return;
        }
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) run.addBreak();
            run.setText(safe(lines[i]));
        }
    }

    private static void appendField(XWPFParagraph paragraph, String instruction, String fallbackText) {
        if (paragraph == null) return;

        XWPFRun run = paragraph.createRun();
        run.getCTR().addNewFldChar().setFldCharType(STFldCharType.BEGIN);

        run = paragraph.createRun();
        CTText instructionText = run.getCTR().addNewInstrText();
        instructionText.setStringValue(safe(instruction).trim());

        run = paragraph.createRun();
        run.getCTR().addNewFldChar().setFldCharType(STFldCharType.SEPARATE);

        if (!safe(fallbackText).isBlank()) {
            run = paragraph.createRun();
            run.setText(safe(fallbackText));
        }

        run = paragraph.createRun();
        run.getCTR().addNewFldChar().setFldCharType(STFldCharType.END);
    }

    private static boolean trimDocxByAnchor(XWPFDocument doc, String anchorText, boolean deleteAbove) {
        if (doc == null) return false;
        String anchor = safe(anchorText);
        if (anchor.isBlank()) return false;

        ArrayList<XWPFRun> runs = new ArrayList<XWPFRun>();
        collectRunsBody(doc, runs);
        if (runs.isEmpty()) return false;

        ArrayList<String> runTexts = new ArrayList<String>(runs.size());
        StringBuilder flat = new StringBuilder(512);
        ArrayList<Integer> runByChar = new ArrayList<Integer>();
        ArrayList<Integer> offByChar = new ArrayList<Integer>();

        for (int i = 0; i < runs.size(); i++) {
            XWPFRun run = runs.get(i);
            String txt = runText(run);
            runTexts.add(txt);

            for (int j = 0; j < txt.length(); j++) {
                flat.append(txt.charAt(j));
                runByChar.add(i);
                offByChar.add(j);
            }
        }

        if (flat.length() == 0) return false;

        int matchStart = indexOfIgnoreCase(flat.toString(), anchor);
        if (matchStart < 0) return false;
        int matchEnd = matchStart + anchor.length();
        if (matchEnd > flat.length()) return false;

        int cutStart = deleteAbove ? 0 : matchStart;
        int cutEnd = deleteAbove ? matchEnd : flat.length();
        if (cutStart >= cutEnd) return false;

        int startChar = cutStart;
        int endChar = cutEnd - 1;
        if (startChar < 0 || endChar < startChar || endChar >= runByChar.size()) return false;

        int startRun = runByChar.get(startChar);
        int startOffset = offByChar.get(startChar);
        int endRun = runByChar.get(endChar);
        int endOffset = offByChar.get(endChar);

        if (startRun < 0 || startRun >= runTexts.size()) return false;
        if (endRun < 0 || endRun >= runTexts.size()) return false;

        String startText = safe(runTexts.get(startRun));
        String endText = safe(runTexts.get(endRun));
        String prefix = left(startText, startOffset);
        String suffix = rightFrom(endText, endOffset + 1);

        if (startRun == endRun) {
            runTexts.set(startRun, prefix + suffix);
        } else {
            runTexts.set(startRun, prefix);
            runTexts.set(endRun, suffix);
            for (int r = startRun + 1; r < endRun; r++) runTexts.set(r, "");
        }

        for (int i = 0; i < runs.size(); i++) {
            setRunText(runs.get(i), runTexts.get(i));
        }
        return true;
    }

    private static int indexOfIgnoreCase(String haystack, String needle) {
        String h = safe(haystack);
        String n = safe(needle);
        if (n.isBlank()) return -1;
        return h.toLowerCase(Locale.ROOT).indexOf(n.toLowerCase(Locale.ROOT));
    }

    private static void collectAllRuns(XWPFDocument doc, ArrayList<XWPFRun> out) {
        if (doc == null || out == null) return;
        collectRunsBody(doc, out);
        for (XWPFHeader h : doc.getHeaderList()) {
            if (h != null) collectRunsBody(h, out);
        }
        for (XWPFFooter f : doc.getFooterList()) {
            if (f != null) collectRunsBody(f, out);
        }
    }

    private static void collectRunsBody(IBody body, ArrayList<XWPFRun> out) {
        if (body == null || out == null) return;
        for (XWPFParagraph p : body.getParagraphs()) {
            if (p == null) continue;
            out.addAll(p.getRuns());
        }
        for (XWPFTable table : body.getTables()) {
            collectRunsTable(table, out);
        }
    }

    private static void collectRunsTable(XWPFTable table, ArrayList<XWPFRun> out) {
        if (table == null || out == null) return;
        for (XWPFTableRow row : table.getRows()) {
            if (row == null) continue;
            for (XWPFTableCell cell : row.getTableCells()) {
                if (cell != null) collectRunsBody(cell, out);
            }
        }
    }

    // -----------------------------
    // Preview helpers
    // -----------------------------
    private static String extractDocxText(byte[] bytes) throws Exception {
        if (bytes == null || bytes.length == 0) return "";
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes));
             XWPFWordExtractor ex = new XWPFWordExtractor(doc)) {
            return safe(ex.getText());
        }
    }

    private static String extractDocxTextLenient(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "";

        String xml = "";
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            byte[] buf = new byte[8192];
            while ((entry = zin.getNextEntry()) != null) {
                String name = safe(entry.getName()).replace('\\', '/');
                if (!"word/document.xml".equalsIgnoreCase(name)) continue;

                int sizeHint = 4096;
                long declaredSize = entry.getSize();
                if (declaredSize > 0L && declaredSize < Integer.MAX_VALUE) {
                    sizeHint = (int) Math.max(2048L, declaredSize);
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream(sizeHint);
                int n;
                while ((n = zin.read(buf)) >= 0) {
                    if (n == 0) continue;
                    out.write(buf, 0, n);
                }
                xml = out.toString(StandardCharsets.UTF_8);
                break;
            }
        } catch (Exception ignored) {
            return "";
        }

        if (xml.isBlank()) return "";
        return extractWordDocumentXmlText(xml);
    }

    private static String extractWordDocumentXmlText(String xml) {
        String src = safe(xml);
        if (src.isBlank()) return "";

        String out = src
                .replaceAll("(?is)<w:tab\\b[^>]*/>", "\t")
                .replaceAll("(?is)<w:(br|cr)\\b[^>]*/>", "\n")
                .replaceAll("(?is)</w:p\\s*>", "\n")
                .replaceAll("(?is)<[^>]+>", "");

        out = out
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&apos;", "'");
        return safe(out);
    }

    private static String extractDocText(byte[] bytes) throws Exception {
        if (bytes == null || bytes.length == 0) return "";
        try (HWPFDocument doc = new HWPFDocument(new ByteArrayInputStream(bytes));
             WordExtractor ex = new WordExtractor(doc)) {
            return safe(ex.getText());
        }
    }

    private static String extractRtfText(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "";

        try {
            RTFEditorKit kit = new RTFEditorKit();
            Document doc = kit.createDefaultDocument();
            kit.read(new ByteArrayInputStream(bytes), doc, 0);
            return safe(doc.getText(0, doc.getLength()));
        } catch (Exception ignored) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static String extractTxtText(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "";
        return new String(bytes, StandardCharsets.UTF_8);
    }

    // -----------------------------
    // Assembly by format
    // -----------------------------
    private static byte[] assembleTxt(byte[] templateBytes, Map<String, String> values) {
        String src = extractTxtText(templateBytes);
        TokenScan scan = scanTokens(src, values);
        return scan.assembledText.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] assembleRtf(byte[] templateBytes, Map<String, String> values) {
        String raw = extractTxtText(templateBytes);
        TokenScan scan = scanTokens(raw, values);

        String out = raw;
        for (Map.Entry<String, String> e : scan.fullTokenToReplacement.entrySet()) {
            if (e == null) continue;
            String token = safe(e.getKey());
            if (token.isBlank()) continue;
            String replacement = escapeRtf(safe(e.getValue()));
            out = out.replace(token, replacement);
        }
        return out.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] assembleDoc(byte[] templateBytes, Map<String, String> values) throws Exception {
        if (templateBytes == null || templateBytes.length == 0) return new byte[0];

        try (HWPFDocument doc = new HWPFDocument(new ByteArrayInputStream(templateBytes))) {
            Range range = doc.getRange();
            String source = safe(range.text());
            TokenScan scan = scanTokens(source, values);

            for (Map.Entry<String, String> e : scan.fullTokenToReplacement.entrySet()) {
                if (e == null) continue;
                String token = safe(e.getKey());
                if (token.isBlank()) continue;
                range.replaceText(token, safe(e.getValue()));
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(2048, templateBytes.length + 4096));
            doc.write(out);
            return out.toByteArray();
        }
    }

    private static boolean isLikelyRtfContent(byte[] bytes, Exception ex) {
        if (bytes != null && bytes.length >= 5) {
            String prefix = new String(bytes, 0, Math.min(bytes.length, 24), StandardCharsets.US_ASCII)
                    .toLowerCase(Locale.ROOT);
            if (prefix.contains("{\\rtf")) return true;
        }
        String msg = ex == null ? "" : safe(ex.getMessage()).toLowerCase(Locale.ROOT);
        return msg.contains("really a rtf file") || msg.contains("rtf");
    }

    private static byte[] assembleDocx(byte[] templateBytes, Map<String, String> values) throws Exception {
        if (templateBytes == null || templateBytes.length == 0) return new byte[0];

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(templateBytes))) {
            replaceInBody(doc, values);

            for (XWPFHeader h : doc.getHeaderList()) {
                if (h != null) replaceInBody(h, values);
            }
            for (XWPFFooter f : doc.getFooterList()) {
                if (f != null) replaceInBody(f, values);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(4096, templateBytes.length + 8192));
            doc.write(out);
            return out.toByteArray();
        }
    }

    private static void replaceInBody(IBody body, Map<String, String> values) {
        if (body == null) return;

        for (XWPFParagraph p : body.getParagraphs()) {
            replaceInParagraph(p, values);
        }

        for (XWPFTable t : body.getTables()) {
            replaceInTable(t, values);
        }
    }

    private static void appendStyledBody(IBody body, ArrayList<StyledSegment> out) {
        if (body == null || out == null) return;

        for (IBodyElement el : body.getBodyElements()) {
            if (el instanceof XWPFParagraph) {
                appendStyledParagraph((XWPFParagraph) el, out);
            } else if (el instanceof XWPFTable) {
                appendStyledTable((XWPFTable) el, out);
            }
        }
    }

    private static void appendStyledTable(XWPFTable table, ArrayList<StyledSegment> out) {
        if (table == null || out == null) return;

        for (XWPFTableRow row : table.getRows()) {
            if (row == null) continue;
            ArrayList<XWPFTableCell> cells = new ArrayList<XWPFTableCell>(row.getTableCells());
            for (int i = 0; i < cells.size(); i++) {
                XWPFTableCell cell = cells.get(i);
                if (cell != null) appendStyledBody(cell, out);
                if (i + 1 < cells.size()) addStyledSegment(out, "\t", "");
            }
            addStyledSegment(out, "\n", "");
        }
        addStyledSegment(out, "\n", "");
    }

    private static void appendStyledParagraph(XWPFParagraph paragraph, ArrayList<StyledSegment> out) {
        if (paragraph == null || out == null) return;

        ArrayList<XWPFRun> runs = new ArrayList<XWPFRun>(paragraph.getRuns());
        if (runs.isEmpty()) {
            addStyledSegment(out, "\n", "");
            return;
        }

        for (XWPFRun run : runs) {
            String text = runText(run);
            if (text.isEmpty()) continue;
            addStyledSegment(out, text, runCss(run));
        }
        addStyledSegment(out, "\n", "");
    }

    private static void addStyledSegment(ArrayList<StyledSegment> out, String text, String css) {
        if (out == null) return;
        String t = safe(text);
        String c = safe(css);
        if (t.isEmpty()) return;

        if (!out.isEmpty()) {
            StyledSegment last = out.get(out.size() - 1);
            if (last != null && safe(last.css).equals(c)) {
                out.set(out.size() - 1, new StyledSegment(safe(last.text) + t, c));
                return;
            }
        }
        out.add(new StyledSegment(t, c));
    }

    private static String runCss(XWPFRun run) {
        if (run == null) return "";

        StringBuilder css = new StringBuilder(128);

        if (run.isBold()) css.append("font-weight:700;");
        if (run.isItalic()) css.append("font-style:italic;");

        boolean underline = run.getUnderline() != null && !"NONE".equalsIgnoreCase(String.valueOf(run.getUnderline()));
        boolean strike = run.isStrikeThrough();
        if (underline && strike) css.append("text-decoration:underline line-through;");
        else if (underline) css.append("text-decoration:underline;");
        else if (strike) css.append("text-decoration:line-through;");

        String color = normalizeColor(run.getColor());
        if (!color.isBlank()) css.append("color:#").append(color).append(';');

        String family = safe(run.getFontFamily()).trim();
        if (!family.isBlank()) css.append("font-family:'").append(cssLiteral(family)).append("';");

        int size = run.getFontSize();
        if (size > 0) css.append("font-size:").append(size).append("pt;");

        String hl = normalizeHighlight(String.valueOf(run.getTextHighlightColor()));
        if (!hl.isBlank()) css.append("background:").append(hl).append(';');

        String vertical = safe(String.valueOf(run.getVerticalAlignment())).toLowerCase(Locale.ROOT);
        if (vertical.contains("superscript")) css.append("vertical-align:super;font-size:smaller;");
        if (vertical.contains("subscript")) css.append("vertical-align:sub;font-size:smaller;");

        return css.toString();
    }

    private static String normalizeColor(String color) {
        String c = safe(color).trim();
        if (c.isBlank()) return "";
        c = c.replace("#", "").replaceAll("[^0-9A-Fa-f]", "");
        if (c.length() == 6) return c.toUpperCase(Locale.ROOT);
        if (c.length() == 3) {
            char a = c.charAt(0), b = c.charAt(1), d = c.charAt(2);
            return ("" + a + a + b + b + d + d).toUpperCase(Locale.ROOT);
        }
        return "";
    }

    private static String normalizeHighlight(String colorName) {
        String c = safe(colorName).trim().toLowerCase(Locale.ROOT);
        if (c.isBlank() || "none".equals(c) || "auto".equals(c)) return "";

        if ("yellow".equals(c)) return "#fff59a";
        if ("green".equals(c) || "darkgreen".equals(c)) return "#c8f7c5";
        if ("cyan".equals(c)) return "#b8f6ff";
        if ("magenta".equals(c)) return "#ffd6ff";
        if ("blue".equals(c) || "darkblue".equals(c)) return "#dbe7ff";
        if ("red".equals(c) || "darkred".equals(c)) return "#ffd7d7";
        if ("darkyellow".equals(c)) return "#ffe7b2";
        if ("gray25".equals(c) || "gray50".equals(c)) return "#ececec";
        if ("black".equals(c)) return "#f0f0f0";
        if ("lightgray".equals(c)) return "#f2f2f2";
        return "";
    }

    private static String cssLiteral(String in) {
        String v = safe(in);
        StringBuilder out = new StringBuilder(v.length() + 8);
        for (int i = 0; i < v.length(); i++) {
            char ch = v.charAt(i);
            if (ch == '\'' || ch == '\\') out.append('\\');
            out.append(ch);
        }
        return out.toString();
    }

    private static void replaceInTable(XWPFTable table, Map<String, String> values) {
        if (table == null) return;

        for (XWPFTableRow row : table.getRows()) {
            if (row == null) continue;
            for (XWPFTableCell cell : row.getTableCells()) {
                if (cell != null) replaceInBody(cell, values);
            }
        }
    }

    private static final class ParagraphOp {
        final int startRun;
        final int startOffset;
        final int endRun;
        final int endOffset;
        final String replacement;

        ParagraphOp(int startRun, int startOffset, int endRun, int endOffset, String replacement) {
            this.startRun = startRun;
            this.startOffset = startOffset;
            this.endRun = endRun;
            this.endOffset = endOffset;
            this.replacement = safe(replacement);
        }
    }

    private static final class TokenRef {
        final String fullToken;
        final String key;
        final String rawLabel;
        final boolean curly;

        TokenRef(String fullToken, String key, String rawLabel, boolean curly) {
            this.fullToken = safe(fullToken);
            this.key = safe(key).trim();
            this.rawLabel = safe(rawLabel).trim();
            this.curly = curly;
        }
    }

    private static void replaceInParagraph(XWPFParagraph paragraph, Map<String, String> values) {
        if (paragraph == null) return;

        ArrayList<XWPFRun> runs = new ArrayList<XWPFRun>(paragraph.getRuns());
        if (runs.isEmpty()) return;

        ArrayList<String> runTexts = new ArrayList<String>(runs.size());
        StringBuilder flat = new StringBuilder(256);
        ArrayList<Integer> runByChar = new ArrayList<Integer>();
        ArrayList<Integer> offByChar = new ArrayList<Integer>();

        for (int i = 0; i < runs.size(); i++) {
            XWPFRun run = runs.get(i);
            String txt = runText(run);
            runTexts.add(txt);

            for (int j = 0; j < txt.length(); j++) {
                flat.append(txt.charAt(j));
                runByChar.add(i);
                offByChar.add(j);
            }
        }

        if (flat.length() == 0) return;

        String source = flat.toString();
        ArrayList<TokenMatch> matches = findTokenMatches(source);
        ArrayList<ParagraphOp> ops = new ArrayList<ParagraphOp>();

        for (TokenMatch tm : matches) {
            if (tm == null) continue;
            TokenRef token = tm.token;
            if (token == null) continue;

            String replacement = resolveReplacement(token, values);
            if (replacement.equals(token.fullToken)) continue;

            int startChar = tm.start;
            int endChar = tm.end - 1;
            if (startChar < 0 || endChar < startChar || endChar >= runByChar.size()) continue;

            int startRun = runByChar.get(startChar);
            int startOffset = offByChar.get(startChar);
            int endRun = runByChar.get(endChar);
            int endOffset = offByChar.get(endChar);

            ops.add(new ParagraphOp(startRun, startOffset, endRun, endOffset, replacement));
        }

        if (ops.isEmpty()) return;

        for (int i = ops.size() - 1; i >= 0; i--) {
            ParagraphOp op = ops.get(i);

            if (op.startRun < 0 || op.startRun >= runTexts.size()) continue;
            if (op.endRun < 0 || op.endRun >= runTexts.size()) continue;

            String startText = safe(runTexts.get(op.startRun));
            String endText = safe(runTexts.get(op.endRun));

            String prefix = left(startText, op.startOffset);
            String suffix = rightFrom(endText, op.endOffset + 1);

            if (op.startRun == op.endRun) {
                runTexts.set(op.startRun, prefix + op.replacement + suffix);
            } else {
                runTexts.set(op.startRun, prefix + op.replacement);
                runTexts.set(op.endRun, suffix);
                for (int r = op.startRun + 1; r < op.endRun; r++) {
                    runTexts.set(r, "");
                }
            }
        }

        for (int i = 0; i < runs.size(); i++) {
            setRunText(runs.get(i), runTexts.get(i));
        }
    }

    private static String runText(XWPFRun run) {
        if (run == null) return "";

        StringBuilder sb = new StringBuilder();
        int idx = 0;
        while (true) {
            String t;
            try {
                t = run.getText(idx);
            } catch (IndexOutOfBoundsException ex) {
                break;
            }
            if (t == null) break;
            sb.append(t);
            idx++;
        }

        if (sb.length() > 0) return sb.toString();

        String fallback = run.toString();
        return fallback == null ? "" : fallback;
    }

    private static void setRunText(XWPFRun run, String text) {
        if (run == null) return;

        CTR ctr = run.getCTR();
        if (ctr != null) {
            for (int i = ctr.sizeOfTArray() - 1; i >= 0; i--) {
                ctr.removeT(i);
            }
        }

        String v = safe(text);
        if (!v.isEmpty()) run.setText(v, 0);
    }

    private static String left(String s, int endExclusive) {
        String v = safe(s);
        if (endExclusive <= 0) return "";
        if (endExclusive >= v.length()) return v;
        return v.substring(0, endExclusive);
    }

    private static String rightFrom(String s, int beginInclusive) {
        String v = safe(s);
        if (beginInclusive <= 0) return v;
        if (beginInclusive >= v.length()) return "";
        return v.substring(beginInclusive);
    }

    // -----------------------------
    // Token scanning
    // -----------------------------
    private static final class TokenScan {
        final String assembledText;
        final LinkedHashSet<String> usedTokens;
        final LinkedHashSet<String> missingTokens;
        final LinkedHashMap<String, Integer> tokenCounts;
        final LinkedHashMap<String, String> fullTokenToReplacement;

        TokenScan(String assembledText,
                  LinkedHashSet<String> usedTokens,
                  LinkedHashSet<String> missingTokens,
                  LinkedHashMap<String, Integer> tokenCounts,
                  LinkedHashMap<String, String> fullTokenToReplacement) {
            this.assembledText = safe(assembledText);
            this.usedTokens = usedTokens == null ? new LinkedHashSet<String>() : usedTokens;
            this.missingTokens = missingTokens == null ? new LinkedHashSet<String>() : missingTokens;
            this.tokenCounts = tokenCounts == null ? new LinkedHashMap<String, Integer>() : tokenCounts;
            this.fullTokenToReplacement = fullTokenToReplacement == null ? new LinkedHashMap<String, String>() : fullTokenToReplacement;
        }
    }

    private static final class AssemblyOptions {
        final boolean advancedEnabled;
        final boolean strictMode;
        final String tenantUuid;
        final String userUuid;
        final String caseUuid;
        final String documentUuid;
        final activity_log logger;

        AssemblyOptions(boolean advancedEnabled,
                        boolean strictMode,
                        String tenantUuid,
                        String userUuid,
                        String caseUuid,
                        String documentUuid,
                        activity_log logger) {
            this.advancedEnabled = advancedEnabled;
            this.strictMode = strictMode;
            this.tenantUuid = safe(tenantUuid);
            this.userUuid = safe(userUuid);
            this.caseUuid = safe(caseUuid);
            this.documentUuid = safe(documentUuid);
            this.logger = logger;
        }
    }

    private static final class DirectiveScan {
        final String text;
        final LinkedHashSet<String> unresolved;

        DirectiveScan(String text, LinkedHashSet<String> unresolved) {
            this.text = safe(text);
            this.unresolved = unresolved == null ? new LinkedHashSet<String>() : unresolved;
        }
    }

    private static final class TokenMatch {
        final int start;
        final int end; // exclusive
        final TokenRef token;

        TokenMatch(int start, int end, TokenRef token) {
            this.start = Math.max(0, start);
            this.end = Math.max(this.start, end);
            this.token = token;
        }
    }


    private static final class EachItem {
        final String text;
        final LinkedHashMap<String, String> fields;

        EachItem(String text, LinkedHashMap<String, String> fields) {
            this.text = safe(text);
            this.fields = fields == null ? new LinkedHashMap<String, String>() : fields;
        }
    }

    private static TokenScan scanTokens(String source, Map<String, String> values) {
        return scanTokens(source, values, optionsFromValues(values));
    }

    private static TokenScan scanTokens(String source, Map<String, String> values, AssemblyOptions options) {
        String src = safe(source);
        DirectiveScan directives = applyAdvancedDirectives(src, values, options);
        src = directives.text;
        LinkedHashSet<String> used = new LinkedHashSet<String>();
        LinkedHashSet<String> missing = new LinkedHashSet<String>();
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<String, Integer>();
        LinkedHashMap<String, String> forms = new LinkedHashMap<String, String>();
        ArrayList<TokenMatch> matches = findTokenMatches(src);
        StringBuilder assembled = new StringBuilder(Math.max(64, src.length() + 32));
        int cursor = 0;

        for (TokenMatch tm : matches) {
            if (tm == null || tm.token == null) continue;
            if (tm.start < cursor) continue;
            if (tm.end <= tm.start || tm.end > src.length()) continue;

            assembled.append(src, cursor, tm.start);

            TokenRef token = tm.token;
            if (token.curly) {
                used.add(token.key);
                counts.put(token.key, counts.getOrDefault(token.key, 0) + 1);
            }

            String replacement = resolveReplacement(token, values);
            if (token.curly && replacement.equals(token.fullToken)) {
                missing.add(token.key);
            }
            forms.putIfAbsent(token.fullToken, replacement);
            assembled.append(safe(replacement));
            cursor = tm.end;
        }

        if (cursor < src.length()) assembled.append(src.substring(cursor));
        if (options != null && options.strictMode) {
            missing.addAll(directives.unresolved);
            logStrictUnresolved(options, missing);
        }
        return new TokenScan(assembled.toString(), used, missing, counts, forms);
    }

    private static DirectiveScan applyAdvancedDirectives(String source, Map<String, String> values, AssemblyOptions options) {
        String text = safe(source);
        LinkedHashSet<String> unresolved = new LinkedHashSet<String>();
        if (options == null || !options.advancedEnabled) return new DirectiveScan(text, unresolved);
        if (!containsDirectiveSyntax(text)) return new DirectiveScan(text, unresolved);

        text = applyIfBlocks(text, values, unresolved);
        text = applyEachBlocks(text, values, unresolved);
        text = applyFormatDate(text, values, unresolved);
        return new DirectiveScan(text, unresolved);
    }

    private static String applyIfBlocks(String text, Map<String, String> values, LinkedHashSet<String> unresolved) {
        String out = safe(text);
        int guard = 0;
        while (guard < 1000) {
            guard++;
            int start = out.indexOf("{{#if ");
            if (start < 0) break;
            int closeOpen = out.indexOf("}}", start);
            if (closeOpen < 0) break;
            int end = out.indexOf("{{/if}}", closeOpen + 2);
            if (end < 0) {
                unresolved.add("unclosed_if_directive");
                break;
            }

            String expr = safe(out.substring(start + 6, closeOpen)).trim();
            String body = out.substring(closeOpen + 2, end);
            String value = lookupValue(values, expr, normalizeLooseKey(expr));
            if (value == null) unresolved.add("if:" + expr);
            boolean truthy = isTruthy(value);

            String replacement = truthy ? body : "";
            out = out.substring(0, start) + replacement + out.substring(end + 7);
        }
        return out;
    }

    private static String applyEachBlocks(String text, Map<String, String> values, LinkedHashSet<String> unresolved) {
        String out = safe(text);
        int guard = 0;
        while (guard < 1000) {
            guard++;
            int start = out.indexOf("{{#each ");
            if (start < 0) break;
            int closeOpen = out.indexOf("}}", start);
            if (closeOpen < 0) break;
            int end = out.indexOf("{{/each}}", closeOpen + 2);
            if (end < 0) {
                unresolved.add("unclosed_each_directive");
                break;
            }

            String expr = safe(out.substring(start + 8, closeOpen)).trim();
            String body = out.substring(closeOpen + 2, end);
            String rawList = lookupValue(values, expr, normalizeLooseKey(expr));
            if (rawList == null) unresolved.add("each:" + expr);
            ArrayList<EachItem> items = splitEachItems(rawList);

            StringBuilder repeated = new StringBuilder();
            for (EachItem item : items) {
                String chunk = body.replace("{{this}}", item == null ? "" : safe(item.text));
                chunk = replaceEachFields(chunk, item);
                repeated.append(chunk);
            }
            int suffixStart = end + 9;
            if (endsWithLineBreak(repeated)) suffixStart = consumeLeadingLineBreak(out, suffixStart);
            out = out.substring(0, start) + repeated + out.substring(suffixStart);
        }
        return out;
    }

    private static String applyFormatDate(String text, Map<String, String> values, LinkedHashSet<String> unresolved) {
        String src = safe(text);
        Matcher m = FORMAT_DATE_PATTERN.matcher(src);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String key = safe(m.group(1)).trim();
            String pattern = safe(m.group(2)).trim();
            String raw = lookupValue(values, key, normalizeLooseKey(key));
            String replacement = "";
            if (raw == null || raw.isBlank()) {
                unresolved.add("format.date:" + key);
                replacement = m.group(0);
            } else {
                replacement = formatDate(raw, pattern);
                if (replacement.equals(raw) && !pattern.equalsIgnoreCase("yyyy-MM-dd")) {
                    unresolved.add("format.date:" + key + ":" + pattern);
                }
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(safe(replacement)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String formatDate(String raw, String pattern) {
        String value = safe(raw).trim();
        String fmt = safe(pattern).trim();
        if (value.isBlank() || fmt.isBlank()) return value;
        DateTimeFormatter out;
        try {
            out = DateTimeFormatter.ofPattern(fmt);
        } catch (Exception ex) {
            return value;
        }

        try { return LocalDate.parse(value).format(out); } catch (DateTimeParseException ignored) {}
        try { return LocalDateTime.parse(value).format(out); } catch (DateTimeParseException ignored) {}
        try { return OffsetDateTime.parse(value).format(out); } catch (DateTimeParseException ignored) {}
        try { return ZonedDateTime.parse(value).format(out); } catch (DateTimeParseException ignored) {}

        try {
            DateTimeFormatter in = DateTimeFormatter.ofPattern("M/d/yyyy");
            return LocalDate.parse(value, in).format(out);
        } catch (DateTimeParseException ignored) {}
        try {
            DateTimeFormatter in = DateTimeFormatter.ofPattern("MM/dd/yyyy");
            return LocalDate.parse(value, in).format(out);
        } catch (DateTimeParseException ignored) {}
        return value;
    }

    private static String replaceEachFields(String body, EachItem item) {
        String out = safe(body);
        if (item == null || item.fields.isEmpty()) return out;
        for (Map.Entry<String, String> e : item.fields.entrySet()) {
            if (e == null) continue;
            String key = normalizeLooseKey(e.getKey());
            if (key.isBlank()) continue;
            out = out.replace("{{item." + key + "}}", safe(e.getValue()));
        }
        return out;
    }

    private static ArrayList<EachItem> splitEachItems(String rawList) {
        ArrayList<EachItem> out = new ArrayList<EachItem>();
        String raw = safe(rawList);
        if (raw.isBlank()) return out;

        if (looksLikeXml(raw)) {
            ArrayList<EachItem> xmlItems = parseEachItemsFromXml(raw);
            if (!xmlItems.isEmpty()) return xmlItems;
        }

        String[] parts = raw.split("\\r?\\n|\\|");
        if (parts.length <= 1) parts = raw.split(",");
        for (String p : parts) {
            String v = safe(p).trim();
            if (!v.isBlank()) out.add(new EachItem(v, new LinkedHashMap<String, String>()));
        }
        return out;
    }

    private static boolean looksLikeXml(String raw) {
        String s = safe(raw).trim();
        return s.startsWith("<") && s.endsWith(">") && s.contains("</");
    }

    private static ArrayList<EachItem> parseEachItemsFromXml(String raw) {
        ArrayList<EachItem> out = new ArrayList<EachItem>();
        org.w3c.dom.Document doc = parseXmlSafe(raw);
        if (doc == null || doc.getDocumentElement() == null) return out;

        Element root = doc.getDocumentElement();
        if ("list".equalsIgnoreCase(root.getTagName())) {
            NodeList children = root.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);
                if (!(child instanceof Element)) continue;
                out.addAll(parseEachItemsFromContainer((Element) child));
            }
            return out;
        }
        out.addAll(parseEachItemsFromContainer(root));
        return out;
    }

    private static ArrayList<EachItem> parseEachItemsFromContainer(Element container) {
        ArrayList<EachItem> out = new ArrayList<EachItem>();
        if (container == null) return out;
        String tag = safe(container.getTagName()).toLowerCase(Locale.ROOT);

        if ("item".equals(tag) || "row".equals(tag)) {
            EachItem one = parseXmlEachItem(container);
            if (one != null) out.add(one);
            return out;
        }

        NodeList children = container.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (!(n instanceof Element)) continue;
            Element el = (Element) n;
            String ctag = safe(el.getTagName()).toLowerCase(Locale.ROOT);
            if ("item".equals(ctag) || "row".equals(ctag)) {
                EachItem one = parseXmlEachItem(el);
                if (one != null) out.add(one);
            }
        }
        return out;
    }

    private static EachItem parseXmlEachItem(Element itemEl) {
        if (itemEl == null) return null;
        LinkedHashMap<String, String> fields = new LinkedHashMap<String, String>();
        NodeList children = itemEl.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (!(node instanceof Element)) continue;
            Element fieldEl = (Element) node;
            String key = normalizeLooseKey(fieldEl.getAttribute("key"));
            if (key.isBlank()) key = normalizeLooseKey(fieldEl.getAttribute("name"));
            if (key.isBlank()) key = normalizeLooseKey(fieldEl.getTagName());
            if (key.isBlank()) continue;
            fields.put(key, safe(fieldEl.getTextContent()).trim());
        }
        String text = safe(itemEl.getTextContent()).trim();
        return new EachItem(text, fields);
    }

    private static org.w3c.dom.Document parseXmlSafe(String raw) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            DocumentBuilder b = f.newDocumentBuilder();
            return b.parse(new InputSource(new StringReader(safe(raw))));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean endsWithLineBreak(CharSequence text) {
        if (text == null || text.length() == 0) return false;
        int last = text.length() - 1;
        char c = text.charAt(last);
        if (c == '\n' || c == '\r') return true;
        return false;
    }

    private static int consumeLeadingLineBreak(String text, int index) {
        String src = safe(text);
        int i = Math.max(0, index);
        if (i >= src.length()) return i;
        char c = src.charAt(i);
        if (c == '\r') {
            if (i + 1 < src.length() && src.charAt(i + 1) == '\n') return i + 2;
            return i + 1;
        }
        if (c == '\n') return i + 1;
        return i;
    }

    private static boolean containsDirectiveSyntax(String text) {
        String src = safe(text);
        return src.contains("{{#if") || src.contains("{{#each") || src.contains("{{format.date");
    }

    private static boolean isTruthy(String value) {
        String v = safe(value).trim().toLowerCase(Locale.ROOT);
        if (v.isBlank()) return false;
        return !("false".equals(v) || "0".equals(v) || "no".equals(v) || "off".equals(v));
    }

    private static AssemblyOptions optionsFromValues(Map<String, String> values) {
        boolean advanced = parseBoolean(lookupValue(values,
                "tenant.advanced_assembly_enabled",
                "advanced_assembly_enabled",
                "kv.advanced_assembly_enabled"));
        boolean strict = parseBoolean(lookupValue(values,
                "tenant.advanced_assembly_strict_mode",
                "advanced_assembly_strict_mode",
                "advanced_assembly_strict",
                "kv.advanced_assembly_strict_mode"));

        return new AssemblyOptions(
                advanced,
                strict,
                lookupValue(values, "tenant.uuid", "tenant_uuid"),
                lookupValue(values, "user.uuid", "user_uuid"),
                lookupValue(values, "case.uuid", "case_uuid"),
                lookupValue(values, "document.uuid", "document_uuid"),
                activity_log.defaultStore()
        );
    }

    private static boolean parseBoolean(String value) {
        String v = safe(value).trim().toLowerCase(Locale.ROOT);
        return "1".equals(v) || "true".equals(v) || "yes".equals(v) || "on".equals(v) || "enabled".equals(v);
    }

    private static void logStrictUnresolved(AssemblyOptions options, LinkedHashSet<String> unresolved) {
        if (options == null || !options.strictMode || unresolved == null || unresolved.isEmpty()) return;
        if (safe(options.tenantUuid).isBlank()) return;
        try {
            LinkedHashMap<String, String> details = new LinkedHashMap<String, String>();
            details.put("count", String.valueOf(unresolved.size()));
            details.put("entries", String.join(", ", unresolved));
            details.put("advanced_enabled", String.valueOf(options.advancedEnabled));
            options.logger.logVerbose(
                    "document_assembly_strict_unresolved",
                    options.tenantUuid,
                    options.userUuid,
                    options.caseUuid,
                    options.documentUuid,
                    details
            );
        } catch (Exception ignored) {}
    }

    private static ArrayList<TokenMatch> findTokenMatches(String source) {
        String src = normalizeTokenDelimiters(safe(source));
        ArrayList<TokenMatch> out = new ArrayList<TokenMatch>();
        if (src.isEmpty()) return out;

        for (int i = 0; i < src.length(); i++) {
            char ch = src.charAt(i);
            TokenMatch tm = null;

            if (ch == '{') {
                tm = scanDoubleCurlyToken(src, i);
                if (tm == null) tm = scanSwitchBraceToken(src, i);
            } else if (ch == '[') {
                tm = scanBracketToken(src, i);
            }

            if (tm == null || tm.token == null) continue;
            if (tm.end <= tm.start || tm.end > src.length()) continue;
            out.add(tm);
            i = tm.end - 1;
        }
        return out;
    }

    private static TokenMatch scanDoubleCurlyToken(String src, int start) {
        if (src == null || start < 0 || start + 3 >= src.length()) return null;
        if (src.charAt(start) != '{' || src.charAt(start + 1) != '{') return null;

        int closeStart = -1;
        for (int i = start + 2; i + 1 < src.length(); i++) {
            if (i - start > MAX_TOKEN_BODY_LEN + 6) return null;
            if (src.charAt(i) == '\r' || src.charAt(i) == '\n') continue;
            if (src.charAt(i) == '}' && src.charAt(i + 1) == '}') {
                closeStart = i;
                break;
            }
        }
        if (closeStart < 0) return null;

        String key = safe(src.substring(start + 2, closeStart)).trim();
        if (!isValidCurlyKey(key)) return null;

        String full = src.substring(start, closeStart + 2);
        return new TokenMatch(start, closeStart + 2, new TokenRef(full, key, key, true));
    }

    private static TokenMatch scanSwitchBraceToken(String src, int start) {
        if (src == null || start < 0 || start >= src.length()) return null;
        if (src.charAt(start) != '{') return null;
        if (start + 1 < src.length() && src.charAt(start + 1) == '{') return null;

        int close = -1;
        for (int i = start + 1; i < src.length(); i++) {
            char ch = src.charAt(i);
            if (ch == '\r' || ch == '\n') return null;
            if (ch == '{') return null;
            if (ch == '}') {
                close = i;
                break;
            }
            if (i - start > MAX_TOKEN_BODY_LEN + 2) return null;
        }
        if (close < 0) return null;

        String raw = safe(src.substring(start + 1, close)).trim();
        if (!isSwitchLabel(raw)) return null;

        String full = src.substring(start, close + 1);
        String key = normalizeLooseKey(raw);
        if (key.isBlank()) key = raw;
        return new TokenMatch(start, close + 1, new TokenRef(full, key, raw, false));
    }

    private static TokenMatch scanBracketToken(String src, int start) {
        if (src == null || start < 0 || start >= src.length()) return null;
        if (src.charAt(start) != '[') return null;

        int depth = 1;
        int close = -1;
        for (int i = start + 1; i < src.length(); i++) {
            char ch = src.charAt(i);
            if (ch == '\r' || ch == '\n') return null;
            if (ch == '[') depth++;
            else if (ch == ']') {
                depth--;
                if (depth == 0) {
                    close = i;
                    break;
                }
            }
            if (i - start > MAX_TOKEN_BODY_LEN + 8) return null;
        }
        if (close < 0) return null;

        String raw = safe(src.substring(start + 1, close)).trim();
        if (raw.isBlank() || raw.length() > MAX_TOKEN_BODY_LEN) return null;

        String full = src.substring(start, close + 1);
        String key = normalizeLooseKey(raw);
        if (key.isBlank()) key = raw;
        return new TokenMatch(start, close + 1, new TokenRef(full, key, raw, false));
    }


    private static String normalizeTokenDelimiters(String in) {
        String src = safe(in);
        if (src.isEmpty()) return src;
        return src
                .replace('“', '{').replace('”', '}')
                .replace('‘', '[').replace('’', ']')
                .replace('｛', '{').replace('｝', '}')
                .replace('［', '[').replace('］', ']');
    }

    private static boolean isValidCurlyKey(String key) {
        String v = safe(key).trim();
        if (v.isBlank() || v.length() > 180) return false;

        for (int i = 0; i < v.length(); i++) {
            char ch = v.charAt(i);
            boolean ok = (ch >= 'A' && ch <= 'Z')
                    || (ch >= 'a' && ch <= 'z')
                    || (ch >= '0' && ch <= '9')
                    || ch == '_' || ch == '-' || ch == '.';
            if (!ok) return false;
        }
        return true;
    }

    private static boolean isSwitchLabel(String label) {
        String v = safe(label).trim();
        if (v.isBlank() || v.length() > MAX_TOKEN_BODY_LEN) return false;
        if (v.indexOf('/') < 0) return false;
        if (v.indexOf('{') >= 0 || v.indexOf('}') >= 0) return false;

        String compact = v.replaceAll("\\s+", "");
        if (compact.isBlank()) return false;
        if (compact.startsWith("/") || compact.endsWith("/")) return false;
        return !compact.contains("//");
    }

    private static String resolveReplacement(TokenRef token, Map<String, String> values) {
        if (token == null) return "";
        String full = safe(token.fullToken);
        if (values == null || values.isEmpty()) return full;

        String literalOverride = lookupValue(values, full);
        if (literalOverride != null) return literalOverride;

        String replacement;
        if (token.curly) {
            replacement = lookupValue(values, token.key);
        } else {
            ArrayList<String> options = splitTopLevelSwitchOptions(token.rawLabel);
            replacement = lookupSwitchOption(values, options);
            if (replacement == null) replacement = lookupBracketLabel(values, token.rawLabel);
        }
        if (replacement == null) return full;
        return replacement;
    }

    private static String lookupBracketLabel(Map<String, String> values, String rawLabel) {
        String nk = normalizeLooseKey(rawLabel);
        return lookupValue(
                values,
                safe(rawLabel),
                nk,
                "kv." + nk,
                "case." + nk,
                "tenant." + nk
        );
    }

    private static String lookupSwitchOption(Map<String, String> values, ArrayList<String> options) {
        if (values == null || values.isEmpty() || options == null || options.isEmpty()) return null;

        String firstFound = null;
        for (String option : options) {
            String opt = safe(option).trim();
            if (opt.isBlank()) continue;
            String candidate = lookupBracketLabel(values, opt);
            if (candidate == null) continue;
            if (!candidate.isBlank()) return candidate;
            if (firstFound == null) firstFound = candidate;
        }
        return firstFound;
    }

    private static ArrayList<String> splitTopLevelSwitchOptions(String rawLabel) {
        String raw = safe(rawLabel).trim();
        ArrayList<String> out = new ArrayList<String>();
        if (raw.isBlank() || raw.indexOf('/') < 0) return out;

        int squareDepth = 0;
        int curlyDepth = 0;
        int start = 0;
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch == '[') squareDepth++;
            else if (ch == ']') {
                if (squareDepth > 0) squareDepth--;
            } else if (ch == '{') curlyDepth++;
            else if (ch == '}') {
                if (curlyDepth > 0) curlyDepth--;
            } else if (ch == '/' && squareDepth == 0 && curlyDepth == 0) {
                String part = safe(raw.substring(start, i)).trim();
                if (!part.isBlank()) out.add(part);
                start = i + 1;
            }
        }

        String tail = safe(raw.substring(Math.min(start, raw.length()))).trim();
        if (!tail.isBlank()) out.add(tail);
        if (out.size() < 2) out.clear();
        return out;
    }

    private static String lookupValue(Map<String, String> values, String... keys) {
        if (values == null || values.isEmpty() || keys == null || keys.length == 0) return null;

        for (int i = 0; i < keys.length; i++) {
            String k = safe(keys[i]).trim();
            if (k.isBlank()) continue;
            if (values.containsKey(k)) return values.get(k);
        }

        for (int i = 0; i < keys.length; i++) {
            String wanted = safe(keys[i]).trim();
            if (wanted.isBlank()) continue;

            for (Map.Entry<String, String> e : values.entrySet()) {
                if (e == null) continue;
                String have = safe(e.getKey()).trim();
                if (wanted.equalsIgnoreCase(have)) return e.getValue();
            }
        }
        return null;
    }

    private static String normalizeLooseKey(String key) {
        String k = safe(key).trim().toLowerCase(Locale.ROOT);
        if (k.isBlank()) return "";

        StringBuilder sb = new StringBuilder(k.length());
        boolean lastUnderscore = false;
        for (int i = 0; i < k.length(); i++) {
            char ch = k.charAt(i);
            boolean ok = (ch >= 'a' && ch <= 'z')
                    || (ch >= '0' && ch <= '9')
                    || ch == '_' || ch == '-' || ch == '.';

            if (ok) {
                sb.append(ch);
                lastUnderscore = false;
            } else if (!lastUnderscore && sb.length() > 0) {
                sb.append('_');
                lastUnderscore = true;
            }
        }

        String out = sb.toString();
        while (out.startsWith("_")) out = out.substring(1);
        while (out.endsWith("_")) out = out.substring(0, out.length() - 1);
        if (out.length() > 80) out = out.substring(0, 80);
        return out;
    }

    private static String escapeRtf(String value) {
        String v = safe(value);
        StringBuilder out = new StringBuilder(v.length() + 16);

        for (int i = 0; i < v.length(); i++) {
            char ch = v.charAt(i);
            if (ch == '\\') out.append("\\\\");
            else if (ch == '{') out.append("\\{");
            else if (ch == '}') out.append("\\}");
            else if (ch == '\n') out.append("\\line ");
            else if (ch == '\r') {
                // ignore
            } else if (ch > 0x7f) out.append("\\u").append((int) ch).append('?');
            else out.append(ch);
        }

        return out.toString();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
