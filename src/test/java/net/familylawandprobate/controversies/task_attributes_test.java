package net.familylawandprobate.controversies;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

public class task_attributes_test {

    @Test
    void save_and_list_enabled_task_attributes() throws Exception {
        String tenantUuid = "task-attrs-" + UUID.randomUUID();
        Path tenantDir = Paths.get("data", "tenants", tenantUuid).toAbsolutePath();
        deleteQuietly(tenantDir);

        try {
            task_attributes store = task_attributes.defaultStore();
            store.ensure(tenantUuid);

            List<task_attributes.AttributeRec> rows = new ArrayList<task_attributes.AttributeRec>();
            rows.add(new task_attributes.AttributeRec("", "phase", "Phase", "select", "Intake\nDiscovery\nTrial", true, true, 10, ""));
            rows.add(new task_attributes.AttributeRec("", "complexity", "Complexity", "number", "", false, true, 20, ""));
            rows.add(new task_attributes.AttributeRec("", "internal_flag", "Internal Flag", "text", "", false, false, 30, ""));
            store.saveAll(tenantUuid, rows);

            List<task_attributes.AttributeRec> all = store.listAll(tenantUuid);
            assertEquals(3, all.size());

            List<task_attributes.AttributeRec> enabled = store.listEnabled(tenantUuid);
            assertEquals(2, enabled.size());

            task_attributes.AttributeRec phase = null;
            for (int i = 0; i < all.size(); i++) {
                task_attributes.AttributeRec r = all.get(i);
                if (r != null && "phase".equals(r.key)) {
                    phase = r;
                    break;
                }
            }
            assertTrue(phase != null);
            assertEquals("select", phase.dataType);
            assertEquals("Intake\nDiscovery\nTrial", phase.options);
            assertTrue(phase.required);
        } finally {
            deleteQuietly(tenantDir);
        }
    }

    @Test
    void task_fields_round_trip_values() throws Exception {
        String tenantUuid = "task-fields-" + UUID.randomUUID();
        String taskUuid = "task-" + UUID.randomUUID();
        Path tenantDir = Paths.get("data", "tenants", tenantUuid).toAbsolutePath();
        deleteQuietly(tenantDir);

        try {
            task_fields store = task_fields.defaultStore();
            LinkedHashMap<String, String> in = new LinkedHashMap<String, String>();
            in.put("phase", "Discovery");
            in.put("complexity", "3");
            in.put("  ", "ignored");

            store.write(tenantUuid, taskUuid, in);
            Map<String, String> out = store.read(tenantUuid, taskUuid);

            assertEquals("Discovery", out.get("phase"));
            assertEquals("3", out.get("complexity"));
            assertFalse(out.containsKey("  "));
        } finally {
            deleteQuietly(tenantDir);
        }
    }

    @Test
    void save_supports_additional_task_attribute_data_types() throws Exception {
        String tenantUuid = "task-attrs-extra-types-" + UUID.randomUUID();
        Path tenantDir = Paths.get("data", "tenants", tenantUuid).toAbsolutePath();
        deleteQuietly(tenantDir);

        try {
            task_attributes store = task_attributes.defaultStore();
            store.ensure(tenantUuid);

            List<task_attributes.AttributeRec> rows = new ArrayList<task_attributes.AttributeRec>();
            rows.add(new task_attributes.AttributeRec("", "next_call_at", "Next Call At", "timestamp", "", false, true, 10, ""));
            rows.add(new task_attributes.AttributeRec("", "call_window", "Call Window", "time", "", false, true, 20, ""));
            rows.add(new task_attributes.AttributeRec("", "requires_review", "Requires Review", "checkbox", "ignored", false, true, 30, ""));
            rows.add(new task_attributes.AttributeRec("", "requestor_email", "Requestor Email", "email", "", false, true, 40, ""));
            rows.add(new task_attributes.AttributeRec("", "requestor_phone", "Requestor Phone", "tel", "", false, true, 50, ""));
            rows.add(new task_attributes.AttributeRec("", "reference_link", "Reference Link", "url", "", false, true, 60, ""));
            store.saveAll(tenantUuid, rows);

            List<task_attributes.AttributeRec> all = store.listAll(tenantUuid);
            assertEquals(6, all.size());

            assertEquals("datetime", findByKey(all, "next_call_at").dataType);
            assertEquals("time", findByKey(all, "call_window").dataType);
            assertEquals("boolean", findByKey(all, "requires_review").dataType);
            assertEquals("", findByKey(all, "requires_review").options);
            assertEquals("email", findByKey(all, "requestor_email").dataType);
            assertEquals("phone", findByKey(all, "requestor_phone").dataType);
            assertEquals("url", findByKey(all, "reference_link").dataType);
        } finally {
            deleteQuietly(tenantDir);
        }
    }

    private static task_attributes.AttributeRec findByKey(List<task_attributes.AttributeRec> rows, String key) {
        for (int i = 0; i < rows.size(); i++) {
            task_attributes.AttributeRec r = rows.get(i);
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
