package net.familylawandprobate.controversies;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

public class custom_objects_test {

    @Test
    void save_and_list_published_custom_objects() throws Exception {
        String tenantUuid = "custom-objects-" + UUID.randomUUID();
        Path tenantDir = Paths.get("data", "tenants", tenantUuid).toAbsolutePath();
        deleteQuietly(tenantDir);

        try {
            custom_objects store = custom_objects.defaultStore();
            store.ensure(tenantUuid);

            List<custom_objects.ObjectRec> rows = new ArrayList<custom_objects.ObjectRec>();
            rows.add(new custom_objects.ObjectRec("", "billing_entry", "Billing Entry", "Billing Entries", true, true, 10, ""));
            rows.add(new custom_objects.ObjectRec("", "intake_checklist", "Intake Checklist", "Intake Checklists", true, false, 20, ""));
            store.saveAll(tenantUuid, rows);

            List<custom_objects.ObjectRec> all = store.listAll(tenantUuid);
            assertEquals(2, all.size());

            custom_objects.ObjectRec billing = store.getByKey(tenantUuid, "billing_entry");
            assertNotNull(billing);
            assertTrue(billing.published);

            List<custom_objects.ObjectRec> published = store.listPublished(tenantUuid);
            assertEquals(1, published.size());
            assertEquals("billing_entry", published.get(0).key);
        } finally {
            deleteQuietly(tenantDir);
        }
    }

    @Test
    void set_published_updates_visibility() throws Exception {
        String tenantUuid = "custom-objects-publish-" + UUID.randomUUID();
        Path tenantDir = Paths.get("data", "tenants", tenantUuid).toAbsolutePath();
        deleteQuietly(tenantDir);

        try {
            custom_objects store = custom_objects.defaultStore();
            custom_objects.ObjectRec created = new custom_objects.ObjectRec("", "court_event", "Court Event", "Court Events", true, false, 10, "");
            store.saveAll(tenantUuid, java.util.List.of(created));

            custom_objects.ObjectRec fetched = store.getByKey(tenantUuid, "court_event");
            assertNotNull(fetched);
            assertFalse(fetched.published);

            boolean changed = store.setPublished(tenantUuid, fetched.uuid, true);
            assertTrue(changed);
            assertEquals(1, store.listPublished(tenantUuid).size());
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
