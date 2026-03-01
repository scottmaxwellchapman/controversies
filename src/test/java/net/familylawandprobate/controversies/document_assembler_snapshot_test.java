package net.familylawandprobate.controversies;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

public class document_assembler_snapshot_test {
    private static final String FIXTURE_ROOT = "net/familylawandprobate/controversies/document_assembler/";

    @Test
    void legacy_tokens_match_golden_fixture_without_directives_enabled() throws Exception {
        document_assembler assembler = new document_assembler();
        Map<String, String> values = baseValues();

        byte[] templateBytes = fixtureBytes("legacy-template.txt");
        String expected = fixtureText("legacy-expected.txt");

        document_assembler.PreviewResult preview = assembler.preview(templateBytes, "txt", values);
        document_assembler.AssembledFile assembled = assembler.assemble(templateBytes, "txt", values);

        assertExactText("legacy preview output", expected, preview.assembledText);
        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), assembled.bytes, "legacy assembled bytes must match golden fixture exactly");

        assertTrue(preview.usedTokens.contains("tenant.name"));
        assertTrue(preview.usedTokens.contains("case.caption"));
        assertTrue(preview.usedTokens.contains("case.number"));
        assertTrue(preview.usedTokens.contains("kv.firm_name"));
        assertTrue(preview.usedTokens.contains("petitioner_name"));
        assertTrue(preview.missingTokens.contains("case.unknown_field"));
        assertEquals(3, preview.tokenCounts.get("tenant.name"));
    }

    @Test
    void disabled_directive_parser_matches_pre_parser_text_and_bytes() throws Exception {
        document_assembler assembler = new document_assembler();

        byte[] templateBytes = fixtureBytes("directive-disabled-template.txt");
        String expected = fixtureText("directive-disabled-expected.txt");

        Map<String, String> implicitDisabled = baseValues();
        Map<String, String> explicitDisabled = baseValues();
        explicitDisabled.put("tenant.advanced_assembly_enabled", "false");

        document_assembler.PreviewResult before = assembler.preview(templateBytes, "txt", implicitDisabled);
        document_assembler.PreviewResult after = assembler.preview(templateBytes, "txt", explicitDisabled);
        document_assembler.AssembledFile assembledBefore = assembler.assemble(templateBytes, "txt", implicitDisabled);
        document_assembler.AssembledFile assembledAfter = assembler.assemble(templateBytes, "txt", explicitDisabled);

        assertExactText("directive disabled preview(before)", expected, before.assembledText);
        assertExactText("directive disabled preview(after)", expected, after.assembledText);
        assertExactText("directive disabled pre/post parity", before.assembledText, after.assembledText);
        assertArrayEquals(assembledBefore.bytes, assembledAfter.bytes, "disabled-mode output bytes changed unexpectedly");
        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), assembledAfter.bytes, "disabled-mode bytes must match golden fixture exactly");
    }

    @Test
    void directives_are_applied_only_when_feature_flag_is_enabled() throws Exception {
        document_assembler assembler = new document_assembler();

        byte[] templateBytes = fixtureBytes("directive-enabled-template.txt");
        String expected = fixtureText("directive-enabled-expected.txt");

        Map<String, String> enabled = baseValues();
        enabled.put("tenant.advanced_assembly_enabled", "true");

        document_assembler.PreviewResult preview = assembler.preview(templateBytes, "txt", enabled);
        document_assembler.AssembledFile assembled = assembler.assemble(templateBytes, "txt", enabled);

        assertExactText("directive enabled preview", expected, preview.assembledText);
        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), assembled.bytes, "directive enabled bytes must match golden fixture exactly");
    }


    @Test
    void each_directive_supports_xml_rows_with_item_fields_and_graceful_fallbacks() throws Exception {
        document_assembler assembler = new document_assembler();
        String template = "Service List:\n{{#each case.service_rows}}- {{item.name}} | {{item.address}} | {{item.method}}\n{{/each}}Done.";

        Map<String, String> values = baseValues();
        values.put("tenant.advanced_assembly_enabled", "true");
        values.put("case.service_rows",
                "<list><items>"
                        + "<item><name>John Doe</name><address>123 Main St</address><method>Mail</method></item>"
                        + "<item><name>Jane Roe</name><address>456 Oak Ave</address></item>"
                        + "</items></list>");

        document_assembler.PreviewResult preview = assembler.preview(template.getBytes(StandardCharsets.UTF_8), "txt", values);

        String expected = "Service List:\n"
                + "- John Doe | 123 Main St | Mail\n"
                + "- Jane Roe | 456 Oak Ave | {{item.method}}\n"
                + "Done.";

        assertEquals(expected, preview.assembledText);

        values.put("case.service_rows", "");
        document_assembler.PreviewResult emptyPreview = assembler.preview(template.getBytes(StandardCharsets.UTF_8), "txt", values);
        assertEquals("Service List:\nDone.", emptyPreview.assembledText);
    }

    @Test
    void preview_detects_docx_payload_when_template_extension_is_txt() throws Exception {
        document_assembler assembler = new document_assembler();
        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put("case.label", "Sample Matter");

        byte[] docx = minimalDocx("Case Label: {{case.label}}");
        document_assembler.PreviewResult preview = assembler.preview(docx, "txt", values);

        assertTrue(preview.sourceText.contains("{{case.label}}"));
        assertEquals("Case Label: Sample Matter", preview.assembledText.trim());
        assertTrue(preview.usedTokens.contains("case.label"));
        assertFalse(preview.sourceText.contains("[Content_Types].xml"));
    }

    @Test
    void assemble_detects_docx_payload_when_template_extension_is_txt() throws Exception {
        document_assembler assembler = new document_assembler();
        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put("case.label", "Assembled Matter");

        byte[] docx = minimalDocx("Case Label: {{case.label}}");
        document_assembler.AssembledFile assembled = assembler.assemble(docx, "txt", values);

        assertEquals("docx", assembled.extension);
        try (XWPFDocument outDoc = new XWPFDocument(new java.io.ByteArrayInputStream(assembled.bytes));
             XWPFWordExtractor extractor = new XWPFWordExtractor(outDoc)) {
            String text = extractor.getText();
            assertTrue(text.contains("Case Label: Assembled Matter"));
        }
    }

    @Test
    void assemble_and_preview_support_doc_templates() throws Exception {
        document_assembler assembler = new document_assembler();
        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put("case.label", "Legacy Matter");
        values.put("tenant.name", "Acme Tenancy LLC");

        byte[] doc = fixtureBytes("legacy-template.doc");
        document_assembler.PreviewResult preview = assembler.preview(doc, "doc", values);

        assertTrue(preview.sourceText.contains("{{case.label}}"));
        assertTrue(preview.sourceText.contains("{{tenant.name}}"));
        assertTrue(preview.assembledText.contains("Case Label: Legacy Matter"));
        assertTrue(preview.assembledText.contains("Tenant: Acme Tenancy LLC"));

        document_assembler.AssembledFile assembled = assembler.assemble(doc, "doc", values);
        assertEquals("doc", assembled.extension);
        assertEquals("application/msword", assembled.contentType);

        String outText = extractDocText(assembled.bytes);
        assertTrue(outText.contains("Case Label: Legacy Matter"));
        assertTrue(outText.contains("Tenant: Acme Tenancy LLC"));
        assertFalse(outText.contains("{{case.label}}"));
        assertFalse(outText.contains("{{tenant.name}}"));
    }

    @Test
    void assemble_and_preview_support_rtf_templates_with_escaped_tokens() throws Exception {
        document_assembler assembler = new document_assembler();
        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put("case.label", "RTF Matter");
        values.put("tenant.name", "Acme Tenancy LLC");

        byte[] rtf = minimalRtf("Case Label: {{case.label}}\\nTenant: {{tenant.name}}");
        document_assembler.AssembledFile assembled = assembler.assemble(rtf, "rtf", values);
        assertEquals("rtf", assembled.extension);
        assertEquals("application/rtf", assembled.contentType);

        String outRaw = new String(assembled.bytes, StandardCharsets.UTF_8);
        assertTrue(outRaw.contains("RTF Matter"));
        assertTrue(outRaw.contains("Acme Tenancy LLC"));
        assertFalse(outRaw.contains("\\{\\{case.label\\}\\}"));
        assertFalse(outRaw.contains("\\{\\{tenant.name\\}\\}"));
    }

    @Test
    void assemble_detects_rtf_payload_when_template_extension_is_doc() throws Exception {
        document_assembler assembler = new document_assembler();
        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put("case.label", "Misnamed RTF");

        byte[] rtf = minimalRtf("Case Label: {{case.label}}");
        document_assembler.AssembledFile assembled = assembler.assemble(rtf, "doc", values);

        assertEquals("rtf", assembled.extension);
        assertEquals("application/rtf", assembled.contentType);
        assertTrue(new String(assembled.bytes, StandardCharsets.UTF_8).contains("Misnamed RTF"));
    }

    @Test
    void assemble_and_preview_support_odt_templates() throws Exception {
        document_assembler assembler = new document_assembler();
        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put("case.label", "ODT Matter");
        values.put("tenant.name", "Acme Tenancy LLC");

        byte[] odt = minimalOdt("Case Label: {{case.label}}\\nTenant: {{tenant.name}}");
        document_assembler.PreviewResult preview = assembler.preview(odt, "odt", values);
        assertTrue(preview.sourceText.contains("{{case.label}}"));
        assertTrue(preview.assembledText.contains("Case Label: ODT Matter"));
        assertTrue(preview.assembledText.contains("Tenant: Acme Tenancy LLC"));

        document_assembler.AssembledFile assembled = assembler.assemble(odt, "odt", values);
        assertEquals("odt", assembled.extension);
        assertEquals("application/vnd.oasis.opendocument.text", assembled.contentType);

        String contentXml = extractOdtContentXml(assembled.bytes);
        assertTrue(contentXml.contains("ODT Matter"));
        assertTrue(contentXml.contains("Acme Tenancy LLC"));
        assertFalse(contentXml.contains("{{case.label}}"));
        assertFalse(contentXml.contains("{{tenant.name}}"));
    }

    @Test
    void assemble_detects_odt_payload_when_template_extension_is_txt() throws Exception {
        document_assembler assembler = new document_assembler();
        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put("case.label", "Misnamed ODT");

        byte[] odt = minimalOdt("Case Label: {{case.label}}");
        document_assembler.AssembledFile assembled = assembler.assemble(odt, "txt", values);

        assertEquals("odt", assembled.extension);
        assertEquals("application/vnd.oasis.opendocument.text", assembled.contentType);
        assertTrue(extractOdtContentXml(assembled.bytes).contains("Misnamed ODT"));
    }

    private static Map<String, String> baseValues() {
        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put("tenant.name", "Acme Tenancy LLC");
        values.put("case.caption", "In re Example Household");
        values.put("case.number", "24-PB-1001");
        values.put("kv.firm_name", "Family Law & Probate Group");
        values.put("petitioner_name", "John Q. Petitioner");
        values.put("tenant_city", "Phoenix");
        values.put("case.has_children", "true");
        values.put("kv.hearing_dates", "2025-03-01|2025-03-15");
        values.put("case.next_hearing", "2025-03-20");
        return values;
    }

    private static void assertExactText(String label, String expected, String actual) {
        if (!expected.equals(actual) && normalizeWhitespace(expected).equals(normalizeWhitespace(actual))) {
            throw new AssertionError(label + " changed only by whitespace normalization; this is not allowed for legal filing text.");
        }
        assertEquals(expected, actual, label + " differs from golden fixture");
    }

    private static String normalizeWhitespace(String value) {
        return value.replace("\r\n", "\n").replaceAll("[ \t]+", " ").trim();
    }

    private static byte[] fixtureBytes(String name) throws IOException {
        try (InputStream stream = fixtureStream(name)) {
            return stream.readAllBytes();
        }
    }

    private static String fixtureText(String name) throws IOException {
        return new String(fixtureBytes(name), StandardCharsets.UTF_8);
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

    private static String extractDocText(byte[] bytes) throws Exception {
        try (HWPFDocument doc = new HWPFDocument(new ByteArrayInputStream(bytes));
             WordExtractor extractor = new WordExtractor(doc)) {
            return extractor.getText();
        }
    }

    private static byte[] minimalRtf(String plainTextWithNewlineMarker) {
        String text = String.valueOf(plainTextWithNewlineMarker == null ? "" : plainTextWithNewlineMarker);
        String[] lines = text.split("\\\\n", -1);
        StringBuilder rtf = new StringBuilder(256);
        rtf.append("{\\rtf1\\ansi\\deff0 {\\fonttbl{\\f0 Times New Roman;}}\\f0\\fs24 ");
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) rtf.append("\\par ");
            rtf.append(escapeRtfLiteral(lines[i]));
        }
        rtf.append("}");
        return rtf.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String escapeRtfLiteral(String text) {
        String v = String.valueOf(text == null ? "" : text);
        StringBuilder out = new StringBuilder(v.length() + 32);
        for (int i = 0; i < v.length(); i++) {
            char ch = v.charAt(i);
            if (ch == '\\') out.append("\\\\");
            else if (ch == '{') out.append("\\{");
            else if (ch == '}') out.append("\\}");
            else if (ch == '\n') out.append("\\par ");
            else if (ch == '\r') continue;
            else out.append(ch);
        }
        return out.toString();
    }

    private static byte[] minimalOdt(String plainTextWithNewlineMarker) throws Exception {
        String text = String.valueOf(plainTextWithNewlineMarker == null ? "" : plainTextWithNewlineMarker);
        String[] lines = text.split("\\\\n", -1);

        StringBuilder body = new StringBuilder(512);
        for (int i = 0; i < lines.length; i++) {
            body.append("<text:p>").append(escapeXml(lines[i])).append("</text:p>");
        }
        String contentXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<office:document-content "
                + "xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\" "
                + "xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\" "
                + "office:version=\"1.2\">"
                + "<office:body><office:text>"
                + body
                + "</office:text></office:body></office:document-content>";

        byte[] mimeBytes = "application/vnd.oasis.opendocument.text".getBytes(StandardCharsets.UTF_8);
        byte[] contentBytes = contentXml.getBytes(StandardCharsets.UTF_8);

        ByteArrayOutputStream out = new ByteArrayOutputStream(2048);
        try (out;
             ZipOutputStream zout = new ZipOutputStream(out)) {
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
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if ("content.xml".equalsIgnoreCase(String.valueOf(entry.getName()))) {
                    return new String(zin.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        return "";
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
