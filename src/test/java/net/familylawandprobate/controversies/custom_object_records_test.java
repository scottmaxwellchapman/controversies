package net.familylawandprobate.controversies;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

public class custom_object_records_test {

    @Test
    void create_update_and_archive_records() throws Exception {
        String tenantUuid = "custom-object-records-" + UUID.randomUUID();
        String objectUuid = "obj-" + UUID.randomUUID();
        Path tenantDir = Paths.get("data", "tenants", tenantUuid).toAbsolutePath();
        deleteQuietly(tenantDir);

        try {
            custom_object_records store = custom_object_records.defaultStore();
            store.ensure(tenantUuid, objectUuid);

            LinkedHashMap<String, String> firstValues = new LinkedHashMap<String, String>();
            firstValues.put("entry_date", "2026-03-01");
            firstValues.put("rate", "250");
            custom_object_records.RecordRec first = store.create(tenantUuid, objectUuid, "March billing", firstValues);
            assertNotNull(first);
            assertFalse(first.uuid.isBlank());

            LinkedHashMap<String, String> secondValues = new LinkedHashMap<String, String>();
            secondValues.put("entry_date", "2026-03-02");
            secondValues.put("rate", "300");
            store.create(tenantUuid, objectUuid, "April billing", secondValues);

            List<custom_object_records.RecordRec> all = store.listAll(tenantUuid, objectUuid);
            assertEquals(2, all.size());

            LinkedHashMap<String, String> updateValues = new LinkedHashMap<String, String>();
            updateValues.put("entry_date", "2026-03-05");
            updateValues.put("rate", "275");
            boolean changed = store.update(tenantUuid, objectUuid, first.uuid, "March billing updated", updateValues);
            assertTrue(changed);

            custom_object_records.RecordRec updated = store.getByUuid(tenantUuid, objectUuid, first.uuid);
            assertNotNull(updated);
            assertEquals("March billing updated", updated.label);
            assertEquals("275", updated.values.get("rate"));

            boolean archived = store.setTrashed(tenantUuid, objectUuid, first.uuid, true);
            assertTrue(archived);
            custom_object_records.RecordRec archivedRec = store.getByUuid(tenantUuid, objectUuid, first.uuid);
            assertTrue(archivedRec.trashed);
            assertFalse(archivedRec.enabled);

            boolean restored = store.setTrashed(tenantUuid, objectUuid, first.uuid, false);
            assertTrue(restored);
            custom_object_records.RecordRec restoredRec = store.getByUuid(tenantUuid, objectUuid, first.uuid);
            assertFalse(restoredRec.trashed);
            assertTrue(restoredRec.enabled);
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
