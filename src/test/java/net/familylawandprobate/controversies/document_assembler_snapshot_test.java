package net.familylawandprobate.controversies;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

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
}
