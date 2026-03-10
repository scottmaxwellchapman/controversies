package net.familylawandprobate.controversies;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

public class forms_assembly_preview_matrix_test {
    private static final String FIXTURE_ROOT = "net/familylawandprobate/controversies/document_assembler/";

    @Test
    void txt_supports_advanced_directives_markup_and_preview_hits() throws Exception {
        document_assembler assembler = new document_assembler();
        document_image_preview previewer = new document_image_preview();

        String template = ""
                + "Case: {{case.label}}\n"
                + "Client: [Client Name]\n"
                + "Switch: {missing/value_from_switch}\n"
                + "Smart Switch: “missing/value_from_switch”\n"
                + "Smart Bracket: ‘Client Name’\n"
                + "FullWidth Bracket: ［Client Name］\n"
                + "{{#if case.has_children}}Children: Yes{{/if}}\n"
                + "Rows:\n"
                + "{{#each case.service_rows}}- {{this}}\n{{/each}}"
                + "Hearing: {{format.date case.next_hearing \"MM/dd/yyyy\"}}\n"
                + "Comma Hearing: {{format.date case.next_hearing, \"MM/dd/yyyy\"}}\n"
                + "County Fallback: {{default case.county \"County Pending\"}}\n"
                + "Nested If: {{#if case.has_children}}A{{#if case.nope}}B{{else}}C{{/if}}{{else}}Z{{/if}}\n"
                + "Missing: {{case.missing_field}}\n";

        Map<String, String> values = baseValues();
        byte[] templateBytes = template.getBytes(StandardCharsets.UTF_8);

        document_assembler.PreviewResult preview = assembler.preview(templateBytes, "txt", values);
        assertTrue(preview.assembledText.contains("Case: Matter Alpha"));
        assertTrue(preview.assembledText.contains("Client: Alice Client"));
        assertTrue(preview.assembledText.contains("Switch: Resolved Switch"));
        assertTrue(preview.assembledText.contains("Smart Switch: Resolved Switch"));
        assertTrue(preview.assembledText.contains("Smart Bracket: Alice Client"));
        assertTrue(preview.assembledText.contains("FullWidth Bracket: Alice Client"));
        assertTrue(preview.assembledText.contains("Children: Yes"));
        assertTrue(preview.assembledText.contains("- Row One"));
        assertTrue(preview.assembledText.contains("- Row Two"));
        assertTrue(preview.assembledText.contains("Hearing: 03/21/2026"));
        assertTrue(preview.assembledText.contains("Comma Hearing: 03/21/2026"));
        assertTrue(preview.assembledText.contains("County Fallback: County Pending"));
        assertTrue(preview.assembledText.contains("Nested If: AC"));
        assertTrue(preview.missingTokens.contains("case.missing_field"));

        document_assembler.AssembledFile assembled = assembler.assemble(templateBytes, "txt", values);
        String assembledText = new String(assembled.bytes, StandardCharsets.UTF_8);
        assertEquals(preview.assembledText, assembledText);

        LinkedHashMap<String, String> defaults = assembler.workspaceTokenDefaults(preview.sourceText, values);
        assertTrue(defaults.containsKey("{{case.label}}"));
        assertTrue(defaults.containsKey("[Client Name]"));
        assertTrue(defaults.containsKey("{missing/value_from_switch}"));

        ArrayList<String> needles = new ArrayList<String>(defaults.keySet());
        document_image_preview.PreviewResult imagePreview = previewer.render(
                templateBytes,
                "txt",
                new LinkedHashMap<String, String>(),
                needles,
                3
        );
        assertFalse(imagePreview.pages.isEmpty());
        assertTrue(imagePreview.hitRects.containsKey("{{case.label}}"));
        assertFalse(imagePreview.hitRects.get("{{case.label}}").isEmpty());
    }

    @Test
    void docx_supports_markup_and_image_preview() throws Exception {
        document_assembler assembler = new document_assembler();
        document_image_preview previewer = new document_image_preview();

        byte[] templateBytes = minimalDocx("Case {{case.label}} | Client [Client Name] | Switch {missing/value_from_switch}");
        Map<String, String> values = baseValues();

        document_assembler.PreviewResult preview = assembler.preview(templateBytes, "docx", values);
        assertTrue(preview.sourceText.contains("{{case.label}}"));
        assertTrue(preview.assembledText.contains("Matter Alpha"));
        assertTrue(preview.assembledText.contains("Alice Client"));
        assertTrue(preview.assembledText.contains("Resolved Switch"));

        document_assembler.AssembledFile assembled = assembler.assemble(templateBytes, "docx", values);
        try (XWPFDocument outDoc = new XWPFDocument(new java.io.ByteArrayInputStream(assembled.bytes));
             XWPFWordExtractor extractor = new XWPFWordExtractor(outDoc)) {
            String text = extractor.getText();
            assertTrue(text.contains("Matter Alpha"));
            assertTrue(text.contains("Alice Client"));
            assertTrue(text.contains("Resolved Switch"));
        }

        LinkedHashMap<String, String> defaults = assembler.workspaceTokenDefaults(preview.sourceText, values);
        document_image_preview.PreviewResult imagePreview = previewer.render(templateBytes, "docx", values, new ArrayList<String>(defaults.keySet()), 2);
        assertFalse(imagePreview.pages.isEmpty());
        assertTrue(imagePreview.hitRects.containsKey("{{case.label}}"));
    }

    @Test
    void rtf_supports_markup_and_image_preview() throws Exception {
        document_assembler assembler = new document_assembler();
        document_image_preview previewer = new document_image_preview();

        String plain = "Case {{case.label}}\\nClient [Client Name]\\nSwitch {missing/value_from_switch}";
        byte[] templateBytes = minimalRtf(plain);
        Map<String, String> values = baseValues();

        document_assembler.PreviewResult preview = assembler.preview(templateBytes, "rtf", values);
        assertTrue(preview.assembledText.contains("Matter Alpha"));
        assertTrue(preview.assembledText.contains("Alice Client"));
        assertTrue(preview.assembledText.contains("Resolved Switch"));

        document_assembler.AssembledFile assembled = assembler.assemble(templateBytes, "rtf", values);
        String outRaw = new String(assembled.bytes, StandardCharsets.UTF_8);
        assertTrue(outRaw.contains("Matter Alpha"));
        assertTrue(outRaw.contains("Alice Client"));
        assertTrue(outRaw.contains("Resolved Switch"));

        LinkedHashMap<String, String> defaults = assembler.workspaceTokenDefaults(preview.sourceText, values);
        document_image_preview.PreviewResult imagePreview = previewer.render(templateBytes, "rtf", values, new ArrayList<String>(defaults.keySet()), 2);
        assertFalse(imagePreview.pages.isEmpty());
    }

    @Test
    void odt_supports_markup_and_image_preview() throws Exception {
        document_assembler assembler = new document_assembler();
        document_image_preview previewer = new document_image_preview();

        byte[] templateBytes = minimalOdt("Case {{case.label}}\\nClient [Client Name]\\nSwitch {missing/value_from_switch}");
        Map<String, String> values = baseValues();

        document_assembler.PreviewResult preview = assembler.preview(templateBytes, "odt", values);
        assertTrue(preview.assembledText.contains("Matter Alpha"));
        assertTrue(preview.assembledText.contains("Alice Client"));
        assertTrue(preview.assembledText.contains("Resolved Switch"));

        document_assembler.AssembledFile assembled = assembler.assemble(templateBytes, "odt", values);
        String contentXml = extractOdtContentXml(assembled.bytes);
        assertTrue(contentXml.contains("Matter Alpha"));
        assertTrue(contentXml.contains("Alice Client"));
        assertTrue(contentXml.contains("Resolved Switch"));

        LinkedHashMap<String, String> defaults = assembler.workspaceTokenDefaults(preview.sourceText, values);
        document_image_preview.PreviewResult imagePreview = previewer.render(templateBytes, "odt", values, new ArrayList<String>(defaults.keySet()), 2);
        assertFalse(imagePreview.pages.isEmpty());
    }

    @Test
    void doc_supports_markup_and_image_preview() throws Exception {
        document_assembler assembler = new document_assembler();
        document_image_preview previewer = new document_image_preview();

        byte[] templateBytes = fixtureBytes("legacy-template.doc");
        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put("case.label", "Matter Alpha");
        values.put("tenant.name", "Matter Firm");

        document_assembler.PreviewResult preview = assembler.preview(templateBytes, "doc", values);
        assertTrue(preview.sourceText.contains("{{case.label}}"));

        document_assembler.AssembledFile assembled = assembler.assemble(templateBytes, "doc", values);
        String text = extractDocText(assembled.bytes);
        assertTrue(text.contains("Matter Alpha"));
        assertTrue(text.contains("Matter Firm"));

        LinkedHashMap<String, String> defaults = assembler.workspaceTokenDefaults(preview.sourceText, values);
        document_image_preview.PreviewResult imagePreview = previewer.render(templateBytes, "doc", values, new ArrayList<String>(defaults.keySet()), 2);
        assertFalse(imagePreview.pages.isEmpty());
    }

    private static Map<String, String> baseValues() {
        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put("tenant.advanced_assembly_enabled", "true");
        values.put("case.label", "Matter Alpha");
        values.put("tenant.name", "Matter Firm");
        values.put("Client Name", "Alice Client");
        values.put("[Client Name]", "Alice Client");
        values.put("value_from_switch", "Resolved Switch");
        values.put("case.has_children", "true");
        values.put("case.service_rows", "Row One|Row Two");
        values.put("case.next_hearing", "2026-03-21");
        return values;
    }

    private static byte[] fixtureBytes(String name) throws Exception {
        try (InputStream stream = fixtureStream(name)) {
            return stream.readAllBytes();
        }
    }

    private static InputStream fixtureStream(String name) {
        String path = FIXTURE_ROOT + name;
        InputStream stream = Thread.currentThread().getContextClassLoader().getResourceAsStream(path);
        if (stream == null) throw new IllegalArgumentException("Missing fixture: " + path);
        return stream;
    }

    private static byte[] minimalDocx(String text) throws Exception {
        try (XWPFDocument doc = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream(2048)) {
            doc.createParagraph().createRun().setText(String.valueOf(text == null ? "" : text));
            doc.write(out);
            return out.toByteArray();
        }
    }

    private static byte[] minimalRtf(String plainTextWithNewlineMarker) {
        String text = String.valueOf(plainTextWithNewlineMarker == null ? "" : plainTextWithNewlineMarker);
        String[] lines = text.split("\\\\n", -1);
        StringBuilder rtf = new StringBuilder(256);
        rtf.append("{\\rtf1\\ansi\\deff0 {\\fonttbl{\\f0 Times New Roman;}}\\f0\\fs24 ");
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) rtf.append("\\par ");
            rtf.append(escapeRtf(lines[i]));
        }
        rtf.append('}');
        return rtf.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String escapeRtf(String value) {
        String v = String.valueOf(value == null ? "" : value);
        StringBuilder out = new StringBuilder(v.length() + 16);
        for (int i = 0; i < v.length(); i++) {
            char ch = v.charAt(i);
            if (ch == '\\') out.append("\\\\");
            else if (ch == '{') out.append("\\{");
            else if (ch == '}') out.append("\\}");
            else if (ch > 0x7f) out.append("\\u").append((int) ch).append('?');
            else out.append(ch);
        }
        return out.toString();
    }

    private static byte[] minimalOdt(String text) throws Exception {
        String contentXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<office:document-content "
                + "xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\" "
                + "xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\" "
                + "office:version=\"1.2\">"
                + "<office:body><office:text>"
                + "<text:p>" + escapeXml(text) + "</text:p>"
                + "</office:text></office:body></office:document-content>";

        byte[] mimeBytes = "application/vnd.oasis.opendocument.text".getBytes(StandardCharsets.UTF_8);
        byte[] contentBytes = contentXml.getBytes(StandardCharsets.UTF_8);

        ByteArrayOutputStream out = new ByteArrayOutputStream(2048);
        try (out; ZipOutputStream zout = new ZipOutputStream(out)) {
            ZipEntry mime = new ZipEntry("mimetype");
            mime.setMethod(ZipEntry.STORED);
            mime.setSize(mimeBytes.length);
            mime.setCompressedSize(mimeBytes.length);
            CRC32 crc = new CRC32();
            crc.update(mimeBytes);
            mime.setCrc(crc.getValue());
            zout.putNextEntry(mime);
            zout.write(mimeBytes);
            zout.closeEntry();

            ZipEntry content = new ZipEntry("content.xml");
            zout.putNextEntry(content);
            zout.write(contentBytes);
            zout.closeEntry();
        }
        return out.toByteArray();
    }

    private static String extractOdtContentXml(byte[] bytes) throws Exception {
        try (java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bytes))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                String name = String.valueOf(entry.getName());
                if (!"content.xml".equalsIgnoreCase(name.replace('\\', '/'))) continue;
                return new String(zin.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    private static String extractDocText(byte[] bytes) throws Exception {
        try (HWPFDocument doc = new HWPFDocument(new java.io.ByteArrayInputStream(bytes));
             WordExtractor extractor = new WordExtractor(doc)) {
            return extractor.getText();
        }
    }

    private static String escapeXml(String value) {
        String v = String.valueOf(value == null ? "" : value);
        return v.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
