package net.familylawandprobate.controversies;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class matters_clio_lock_test {

    @Test
    void clio_managed_matter_blocks_local_edits_and_archive() throws Exception {
        String tenantUuid = "tenant-clio-lock-" + UUID.randomUUID();
        try {
            matters store = matters.defaultStore();
            store.ensure(tenantUuid);

            matters.MatterRec rec = store.create(
                    tenantUuid,
                    "Clio Local",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    ""
            );
            store.updateSourceMetadata(tenantUuid, rec.uuid, "clio", "clio-1001", "1001 - Example", "2026-03-04T00:00:00Z");

            IllegalArgumentException updateEx = assertThrows(
                    IllegalArgumentException.class,
                    () -> store.update(tenantUuid, rec.uuid, "Edited", "", "", "", "", "", "", "")
            );
            assertTrue(updateEx.getMessage().contains("Edit it in Clio"));

            IllegalArgumentException trashEx = assertThrows(
                    IllegalArgumentException.class,
                    () -> store.trash(tenantUuid, rec.uuid)
            );
            assertTrue(trashEx.getMessage().contains("Edit it in Clio"));
        } finally {
            deleteTenantDirQuiet(tenantUuid);
        }
    }

    @Test
    void clio_managed_matter_allows_external_sync_update_path() throws Exception {
        String tenantUuid = "tenant-clio-sync-path-" + UUID.randomUUID();
        try {
            matters store = matters.defaultStore();
            store.ensure(tenantUuid);

            matters.MatterRec rec = store.create(
                    tenantUuid,
                    "Old Label",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    ""
            );
            store.updateSourceMetadata(tenantUuid, rec.uuid, "clio", "clio-2002", "2002 - Initial", "2026-03-04T00:00:00Z");

            boolean changed = store.updateForExternalSync(
                    tenantUuid,
                    rec.uuid,
                    "2002 - Updated From Clio",
                    rec.jurisdictionUuid,
                    rec.matterCategoryUuid,
                    rec.matterSubcategoryUuid,
                    rec.matterStatusUuid,
                    rec.matterSubstatusUuid,
                    rec.causeDocketNumber,
                    rec.county
            );
            assertTrue(changed);

            matters.MatterRec refreshed = store.getByUuid(tenantUuid, rec.uuid);
            assertEquals("2002 - Updated From Clio", refreshed.label);
        } finally {
            deleteTenantDirQuiet(tenantUuid);
        }
    }

    private static void deleteTenantDirQuiet(String tenantUuid) {
        if (tenantUuid == null || tenantUuid.isBlank()) return;
        Path root = Paths.get("data", "tenants", tenantUuid).toAbsolutePath();
        if (!Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {
        }
    }
}
