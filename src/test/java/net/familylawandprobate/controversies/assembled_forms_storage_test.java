package net.familylawandprobate.controversies;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.familylawandprobate.controversies.storage.AssembledFormsStoragePromoter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

public class assembled_forms_storage_test {

    @Test
    void completed_records_persist_storage_metadata_and_can_be_promoted() throws Exception {
        String tenantUuid = "tenant-" + UUID.randomUUID();
        String matterUuid = "matter-" + UUID.randomUUID();
        String assemblyUuid = "asm-" + UUID.randomUUID();

        assembled_forms store = assembled_forms.defaultStore();
        store.ensure(tenantUuid, matterUuid);

        assembled_forms.AssemblyRec completed = store.markCompleted(
                tenantUuid,
                matterUuid,
                assemblyUuid,
                "tmpl-1",
                "Template",
                "txt",
                "user-1",
                "user@example.com",
                Map.of("a", "b"),
                "file.txt",
                "txt",
                "hello world".getBytes()
        );

        assertEquals("local", completed.storageBackendType);
        assertFalse(completed.storageObjectKey.isBlank());
        assertFalse(completed.storageChecksumSha256.isBlank());
        assertArrayEquals("hello world".getBytes(), store.readOutputBytes(tenantUuid, matterUuid, completed.uuid));

        Path cfg = Paths.get("data", "tenants", tenantUuid, "assembled", "storage.properties").toAbsolutePath();
        Files.createDirectories(cfg.getParent());
        Files.writeString(cfg, "backend=s3_compatible\n");

        AssembledFormsStoragePromoter promoter = new AssembledFormsStoragePromoter();
        int moved = promoter.promoteMatter(tenantUuid, matterUuid, "s3_compatible", 10);
        assertEquals(1, moved);

        assembled_forms.AssemblyRec after = store.getByUuid(tenantUuid, matterUuid, completed.uuid);
        assertEquals("s3_compatible", after.storageBackendType);
        assertFalse(after.storageObjectKey.isBlank());
        assertArrayEquals("hello world".getBytes(), store.readOutputBytes(tenantUuid, matterUuid, completed.uuid));

        store.rebindStorageMetadata(tenantUuid, matterUuid, completed.uuid, "local", completed.storageObjectKey, completed.storageChecksumSha256);
    }


    @Test
    void completed_records_queue_remote_sync_when_backend_not_local() throws Exception {
        String tenantUuid = "tenant-sync-" + UUID.randomUUID();
        String matterUuid = "matter-sync-" + UUID.randomUUID();
        String assemblyUuid = "asm-sync-" + UUID.randomUUID();

        Path cfg = Paths.get("data", "tenants", tenantUuid, "assembled", "storage.properties").toAbsolutePath();
        Files.createDirectories(cfg.getParent());
        Files.writeString(cfg, "backend=s3_compatible\n");

        assembled_forms store = assembled_forms.defaultStore();
        store.ensure(tenantUuid, matterUuid);

        assembled_forms.AssemblyRec completed = store.markCompleted(
                tenantUuid,
                matterUuid,
                assemblyUuid,
                "tmpl-1",
                "Template",
                "txt",
                "user-1",
                "user@example.com",
                Map.of(),
                "queued.txt",
                "txt",
                "queued".getBytes()
        );

        assertEquals("local", completed.storageBackendType);
        assertEquals("pending", store.syncState(tenantUuid, matterUuid, completed.uuid));

        Path queue = Paths.get("data", "tenants", tenantUuid, "sync", "storage_queue.xml").toAbsolutePath();
        assertTrue(Files.exists(queue));
        String xml = Files.readString(queue);
        assertTrue(xml.contains("<assembly_uuid>" + completed.uuid + "</assembly_uuid>"));

        assertTrue(store.retrySyncNow(tenantUuid, matterUuid, completed.uuid));
    }

}
