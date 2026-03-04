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

public class custom_object_attributes_test {

    @Test
    void save_and_list_enabled_attributes_for_object() throws Exception {
        String tenantUuid = "custom-object-attrs-" + UUID.randomUUID();
        String objectUuid = "obj-" + UUID.randomUUID();
        Path tenantDir = Paths.get("data", "tenants", tenantUuid).toAbsolutePath();
        deleteQuietly(tenantDir);

        try {
            custom_object_attributes store = custom_object_attributes.defaultStore();
            store.ensure(tenantUuid, objectUuid);

            List<custom_object_attributes.AttributeRec> rows = new ArrayList<custom_object_attributes.AttributeRec>();
            rows.add(new custom_object_attributes.AttributeRec("", "entry_date", "Entry Date", "date", "", true, true, 10, ""));
            rows.add(new custom_object_attributes.AttributeRec("", "rate", "Rate", "number", "", false, true, 20, ""));
            rows.add(new custom_object_attributes.AttributeRec("", "billing_type", "Billing Type", "select", "Hourly\nFlat Fee\nContingency", true, true, 30, ""));
            rows.add(new custom_object_attributes.AttributeRec("", "internal_note", "Internal Note", "textarea", "", false, false, 40, ""));
            store.saveAll(tenantUuid, objectUuid, rows);

            List<custom_object_attributes.AttributeRec> all = store.listAll(tenantUuid, objectUuid);
            assertEquals(4, all.size());

            List<custom_object_attributes.AttributeRec> enabled = store.listEnabled(tenantUuid, objectUuid);
            assertEquals(3, enabled.size());

            custom_object_attributes.AttributeRec billingType = null;
            for (int i = 0; i < all.size(); i++) {
                custom_object_attributes.AttributeRec r = all.get(i);
                if (r != null && "billing_type".equals(r.key)) {
                    billingType = r;
                    break;
                }
            }

            assertTrue(billingType != null);
            assertEquals("select", billingType.dataType);
            assertEquals("Hourly\nFlat Fee\nContingency", billingType.options);
            assertTrue(billingType.required);

            List<String> options = store.optionList(billingType.options);
            assertEquals(3, options.size());
            assertFalse(options.contains(""));
        } finally {
            deleteQuietly(tenantDir);
        }
    }

    @Test
    void save_supports_additional_custom_object_attribute_data_types() throws Exception {
        String tenantUuid = "custom-object-attrs-extra-types-" + UUID.randomUUID();
        String objectUuid = "obj-extra-" + UUID.randomUUID();
        Path tenantDir = Paths.get("data", "tenants", tenantUuid).toAbsolutePath();
        deleteQuietly(tenantDir);

        try {
            custom_object_attributes store = custom_object_attributes.defaultStore();
            store.ensure(tenantUuid, objectUuid);

            List<custom_object_attributes.AttributeRec> rows = new ArrayList<custom_object_attributes.AttributeRec>();
            rows.add(new custom_object_attributes.AttributeRec("", "opened_at", "Opened At", "datetime-local", "", false, true, 10, ""));
            rows.add(new custom_object_attributes.AttributeRec("", "service_time", "Service Time", "time", "", false, true, 20, ""));
            rows.add(new custom_object_attributes.AttributeRec("", "active_flag", "Active Flag", "bool", "ignored", false, true, 30, ""));
            rows.add(new custom_object_attributes.AttributeRec("", "owner_email", "Owner Email", "email", "", false, true, 40, ""));
            rows.add(new custom_object_attributes.AttributeRec("", "owner_phone", "Owner Phone", "phone_number", "", false, true, 50, ""));
            rows.add(new custom_object_attributes.AttributeRec("", "record_url", "Record Url", "uri", "", false, true, 60, ""));
            store.saveAll(tenantUuid, objectUuid, rows);

            List<custom_object_attributes.AttributeRec> all = store.listAll(tenantUuid, objectUuid);
            assertEquals(6, all.size());

            assertEquals("datetime", findByKey(all, "opened_at").dataType);
            assertEquals("time", findByKey(all, "service_time").dataType);
            assertEquals("boolean", findByKey(all, "active_flag").dataType);
            assertEquals("", findByKey(all, "active_flag").options);
            assertEquals("email", findByKey(all, "owner_email").dataType);
            assertEquals("phone", findByKey(all, "owner_phone").dataType);
            assertEquals("url", findByKey(all, "record_url").dataType);
        } finally {
            deleteQuietly(tenantDir);
        }
    }

    private static custom_object_attributes.AttributeRec findByKey(List<custom_object_attributes.AttributeRec> rows, String key) {
        for (int i = 0; i < rows.size(); i++) {
            custom_object_attributes.AttributeRec r = rows.get(i);
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
