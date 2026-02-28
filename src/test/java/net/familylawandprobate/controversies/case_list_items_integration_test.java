package net.familylawandprobate.controversies;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

public class case_list_items_integration_test {

    @Test
    void xml_row_dataset_round_trip_supports_each_item_fields() throws Exception {
        String tenant = "test_tenant_" + token();
        String matter = "test_matter_" + token();
        Path p = caseListPath(tenant, matter);

        try {
            case_list_items store = case_list_items.defaultStore();
            document_assembler assembler = new document_assembler();

            LinkedHashMap<String, String> in = new LinkedHashMap<String, String>();
            in.put("service_rows",
                    "<list>"
                            + "<row><name>John Doe</name><address>123 Main St</address><method>Mail</method></row>"
                            + "<row><name>Jane Roe</name><address>456 Oak Ave</address><method>Email</method></row>"
                            + "</list>");

            store.write(tenant, matter, in);
            Map<String, String> read = store.read(tenant, matter);

            assertTrue(read.containsKey("service_rows"));
            assertTrue(String.valueOf(read.get("service_rows")).contains("John Doe"));

            LinkedHashMap<String, String> values = new LinkedHashMap<String, String>();
            values.put("tenant.advanced_assembly_enabled", "true");
            for (Map.Entry<String, String> e : read.entrySet()) {
                if (e == null) continue;
                String key = store.normalizeKey(e.getKey());
                String value = String.valueOf(e.getValue());
                values.put("case." + key, value);
                values.put("kv." + key, value);
                values.put(key, value);
            }

            String template = "Service List:\n{{#each case.service_rows}}- {{item.name}} | {{item.address}} | {{item.method}}\n{{/each}}Done.";
            document_assembler.PreviewResult preview =
                    assembler.preview(template.getBytes(StandardCharsets.UTF_8), "txt", values);

            String expected = "Service List:\n"
                    + "- John Doe | 123 Main St | Mail\n"
                    + "- Jane Roe | 456 Oak Ave | Email\n"
                    + "Done.";
            assertEquals(expected, preview.assembledText);
        } finally {
            deleteIfExistsQuiet(p);
            deleteIfExistsQuiet(p.getParent());
        }
    }

    @Test
    void plain_line_dataset_round_trip_supports_each_this() throws Exception {
        String tenant = "test_tenant_" + token();
        String matter = "test_matter_" + token();
        Path p = caseListPath(tenant, matter);

        try {
            case_list_items store = case_list_items.defaultStore();
            document_assembler assembler = new document_assembler();

            LinkedHashMap<String, String> in = new LinkedHashMap<String, String>();
            in.put("party_names", "Alice\nBob");

            store.write(tenant, matter, in);
            Map<String, String> read = store.read(tenant, matter);

            assertTrue(read.containsKey("party_names"));

            LinkedHashMap<String, String> values = new LinkedHashMap<String, String>();
            values.put("tenant.advanced_assembly_enabled", "true");
            for (Map.Entry<String, String> e : read.entrySet()) {
                if (e == null) continue;
                String key = store.normalizeKey(e.getKey());
                String value = String.valueOf(e.getValue());
                values.put("case." + key, value);
                values.put("kv." + key, value);
                values.put(key, value);
            }

            String template = "Parties:\n{{#each case.party_names}}- {{this}}\n{{/each}}Done.";
            document_assembler.PreviewResult preview =
                    assembler.preview(template.getBytes(StandardCharsets.UTF_8), "txt", values);

            String expected = "Parties:\n- Alice\n- Bob\nDone.";
            assertEquals(expected, preview.assembledText);
        } finally {
            deleteIfExistsQuiet(p);
            deleteIfExistsQuiet(p.getParent());
        }
    }

    private static String token() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static Path caseListPath(String tenantUuid, String matterUuid) {
        return Paths.get("data", "tenants", tenantUuid, "matters", matterUuid, "list-items.xml").toAbsolutePath();
    }

    private static void deleteIfExistsQuiet(Path p) {
        try {
            if (p != null) Files.deleteIfExists(p);
        } catch (Exception ignored) {
        }
    }
}
