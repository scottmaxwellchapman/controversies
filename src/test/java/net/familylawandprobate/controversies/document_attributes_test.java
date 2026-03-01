package net.familylawandprobate.controversies;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

public class document_attributes_test {

    @Test
    void save_and_list_enabled_document_attributes() throws Exception {
        String tenantUuid = "doc-attrs-" + UUID.randomUUID();
        Path tenantDir = Paths.get("data", "tenants", tenantUuid).toAbsolutePath();
        deleteQuietly(tenantDir);

        try {
            document_attributes store = document_attributes.defaultStore();
            store.ensure(tenantUuid);

            List<document_attributes.AttributeRec> rows = new ArrayList<document_attributes.AttributeRec>();
            rows.add(new document_attributes.AttributeRec("", "received_date", "Received Date", "date", "", false, true, 10, ""));
            rows.add(new document_attributes.AttributeRec("", "document_source", "Document Source", "select", "Client\nCourt\nOpposing Counsel", true, true, 20, ""));
            rows.add(new document_attributes.AttributeRec("", "internal_notes", "Internal Notes", "textarea", "", false, false, 30, ""));
            store.saveAll(tenantUuid, rows);

            List<document_attributes.AttributeRec> all = store.listAll(tenantUuid);
            assertEquals(3, all.size());

            List<document_attributes.AttributeRec> enabled = store.listEnabled(tenantUuid);
            assertEquals(2, enabled.size());

            document_attributes.AttributeRec source = null;
            for (int i = 0; i < all.size(); i++) {
                document_attributes.AttributeRec r = all.get(i);
                if (r != null && "document_source".equals(r.key)) {
                    source = r;
                    break;
                }
            }
            assertTrue(source != null);
            assertEquals("select", source.dataType);
            assertEquals("Client\nCourt\nOpposing Counsel", source.options);
            assertTrue(source.required);
        } finally {
            deleteQuietly(tenantDir);
        }
    }

    @Test
    void document_fields_round_trip_values() throws Exception {
        String tenantUuid = "doc-fields-" + UUID.randomUUID();
        String matterUuid = "matter-" + UUID.randomUUID();
        String docUuid = "doc-" + UUID.randomUUID();
        Path tenantDir = Paths.get("data", "tenants", tenantUuid).toAbsolutePath();
        deleteQuietly(tenantDir);

        try {
            document_fields store = document_fields.defaultStore();
            java.util.LinkedHashMap<String, String> in = new java.util.LinkedHashMap<String, String>();
            in.put("document_source", "Client");
            in.put("received_date", "2026-03-01");
            in.put("  ", "ignored");

            store.write(tenantUuid, matterUuid, docUuid, in);
            java.util.Map<String, String> out = store.read(tenantUuid, matterUuid, docUuid);

            assertEquals("Client", out.get("document_source"));
            assertEquals("2026-03-01", out.get("received_date"));
            assertFalse(out.containsKey("  "));
        } finally {
            deleteQuietly(tenantDir);
        }
    }

    private static void deleteQuietly(Path p) {
        try {
            if (p == null || !Files.exists(p)) return;
            try (var walk = Files.walk(p)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) {}
                });
            }
        } catch (Exception ignored) {}
    }
}
