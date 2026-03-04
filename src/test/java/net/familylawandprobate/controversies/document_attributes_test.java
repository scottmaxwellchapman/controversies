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
        Path tenantDir = Paths.get("data", "tenants", tenantUuid).toAbsolutePath();
        deleteQuietly(tenantDir);

        try {
            document_fields store = document_fields.defaultStore();
            matters.MatterRec matter = matters.defaultStore().create(
                    tenantUuid,
                    "Document Fields Matter",
                    "",
                    "",
                    "",
                    "",
                    ""
            );
            documents.DocumentRec doc = documents.defaultStore().create(
                    tenantUuid,
                    matter.uuid,
                    "Document Fields Doc",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    ""
            );
            java.util.LinkedHashMap<String, String> in = new java.util.LinkedHashMap<String, String>();
            in.put("document_source", "Client");
            in.put("received_date", "2026-03-01");
            in.put("  ", "ignored");

            store.write(tenantUuid, matter.uuid, doc.uuid, in);
            java.util.Map<String, String> out = store.read(tenantUuid, matter.uuid, doc.uuid);

            assertEquals("Client", out.get("document_source"));
            assertEquals("2026-03-01", out.get("received_date"));
            assertFalse(out.containsKey("  "));
        } finally {
            deleteQuietly(tenantDir);
        }
    }

    @Test
    void save_supports_additional_document_attribute_data_types() throws Exception {
        String tenantUuid = "doc-attrs-extra-types-" + UUID.randomUUID();
        Path tenantDir = Paths.get("data", "tenants", tenantUuid).toAbsolutePath();
        deleteQuietly(tenantDir);

        try {
            document_attributes store = document_attributes.defaultStore();
            store.ensure(tenantUuid);

            List<document_attributes.AttributeRec> rows = new ArrayList<document_attributes.AttributeRec>();
            rows.add(new document_attributes.AttributeRec("", "signed_at", "Signed At", "datetime-local", "", false, true, 10, ""));
            rows.add(new document_attributes.AttributeRec("", "review_time", "Review Time", "time", "", false, true, 20, ""));
            rows.add(new document_attributes.AttributeRec("", "is_confidential", "Is Confidential", "bool", "ignored", false, true, 30, ""));
            rows.add(new document_attributes.AttributeRec("", "contact_email", "Contact Email", "email", "", false, true, 40, ""));
            rows.add(new document_attributes.AttributeRec("", "contact_phone", "Contact Phone", "telephone", "", false, true, 50, ""));
            rows.add(new document_attributes.AttributeRec("", "source_url", "Source Url", "uri", "", false, true, 60, ""));
            store.saveAll(tenantUuid, rows);

            List<document_attributes.AttributeRec> all = store.listAll(tenantUuid);
            assertEquals(6, all.size());

            assertEquals("datetime", findByKey(all, "signed_at").dataType);
            assertEquals("time", findByKey(all, "review_time").dataType);
            assertEquals("boolean", findByKey(all, "is_confidential").dataType);
            assertEquals("", findByKey(all, "is_confidential").options);
            assertEquals("email", findByKey(all, "contact_email").dataType);
            assertEquals("phone", findByKey(all, "contact_phone").dataType);
            assertEquals("url", findByKey(all, "source_url").dataType);
        } finally {
            deleteQuietly(tenantDir);
        }
    }

    private static document_attributes.AttributeRec findByKey(List<document_attributes.AttributeRec> rows, String key) {
        for (int i = 0; i < rows.size(); i++) {
            document_attributes.AttributeRec r = rows.get(i);
            if (r != null && key.equals(r.key)) return r;
        }
        throw new IllegalStateException("Missing key: " + key);
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
