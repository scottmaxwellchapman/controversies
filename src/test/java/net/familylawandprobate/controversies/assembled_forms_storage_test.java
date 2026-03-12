package net.familylawandprobate.controversies;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.familylawandprobate.controversies.storage.AssembledFormsStoragePromoter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

public class assembled_forms_storage_test {

    @Test
    void completed_assemblies_emit_bpm_events_for_follow_on_document_workflows() throws Exception {
        String tenantUuid = "tenant-bpm-assembly-event-" + UUID.randomUUID();
        matters.MatterRec matter = matters.defaultStore().create(
                tenantUuid,
                "Assembly Event Matter",
                "",
                "",
                "",
                "",
                "",
                "",
                ""
        );

        business_process_manager bpm = business_process_manager.defaultService();
        business_process_manager.ProcessDefinition process = new business_process_manager.ProcessDefinition();
        process.name = "Assembly Completed Listener";
        business_process_manager.ProcessTrigger trigger = new business_process_manager.ProcessTrigger();
        trigger.type = "assembly.completed";
        process.triggers.add(trigger);
        business_process_manager.ProcessStep step = new business_process_manager.ProcessStep();
        step.stepId = "capture_assembly";
        step.order = 10;
        step.action = "set_case_field";
        step.settings.put("matter_uuid", "{{event.matter_uuid}}");
        step.settings.put("field_key", "last_assembly_uuid");
        step.settings.put("field_value", "{{event.assembly_uuid}}");
        process.steps.add(step);
        bpm.saveProcess(tenantUuid, process);

        assembled_forms store = assembled_forms.defaultStore();
        assembled_forms.AssemblyRec completed = store.markCompleted(
                tenantUuid,
                matter.uuid,
                "",
                "tmpl-bpm",
                "BPM Template",
                "txt",
                "user-1",
                "user@example.com",
                Map.of(),
                "assembly-event.txt",
                "txt",
                "assembly event payload".getBytes()
        );

        List<business_process_manager.RunResult> runs = bpm.listRuns(tenantUuid, 10);
        assertEquals(1, runs.size());
        assertEquals("completed", runs.get(0).status);
        assertEquals(completed.uuid, case_fields.defaultStore().read(tenantUuid, matter.uuid).get("last_assembly_uuid"));
    }

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

    @Test
    void assembled_form_records_can_be_soft_trashed_and_restored() throws Exception {
        String tenantUuid = "tenant-trash-" + UUID.randomUUID();
        String matterUuid = "matter-trash-" + UUID.randomUUID();

        assembled_forms store = assembled_forms.defaultStore();
        store.ensure(tenantUuid, matterUuid);

        assembled_forms.AssemblyRec completed = store.markCompleted(
                tenantUuid,
                matterUuid,
                "",
                "tmpl-trash",
                "Trash Template",
                "txt",
                "user-trash",
                "trash@example.com",
                Map.of(),
                "trash-check.txt",
                "txt",
                "trash-check".getBytes()
        );
        assertFalse(completed.trashed);

        assertTrue(store.setTrashed(tenantUuid, matterUuid, completed.uuid, true));
        assembled_forms.AssemblyRec afterTrash = store.getByUuid(tenantUuid, matterUuid, completed.uuid);
        assertTrue(afterTrash != null && afterTrash.trashed);
        assertFalse(store.setTrashed(tenantUuid, matterUuid, completed.uuid, true));

        assertTrue(store.setTrashed(tenantUuid, matterUuid, completed.uuid, false));
        assembled_forms.AssemblyRec restored = store.getByUuid(tenantUuid, matterUuid, completed.uuid);
        assertTrue(restored != null && !restored.trashed);
    }

    @Test
    void app_level_encryption_encrypts_local_and_remote_payloads() throws Exception {
        String tenantUuid = "tenant-enc-" + UUID.randomUUID();
        String matterUuid = "matter-enc-" + UUID.randomUUID();

        Path pepper = Paths.get("data", "sec", "random_pepper.bin").toAbsolutePath();
        Files.createDirectories(pepper.getParent());
        if (!Files.exists(pepper)) Files.writeString(pepper, "test-pepper-material");

        tenant_settings settings = tenant_settings.defaultStore();
        settings.write(tenantUuid, Map.of(
                "storage_backend", "s3_compatible",
                "storage_endpoint", "https://s3.example.test",
                "storage_access_key", "key-1",
                "storage_secret", "secret-1",
                "storage_encryption_mode", "tenant_managed",
                "storage_encryption_key", "my-app-key",
                "storage_s3_sse_mode", "aes256"
        ));

        assembled_forms store = assembled_forms.defaultStore();
        assembled_forms.AssemblyRec completed = store.markCompleted(
                tenantUuid,
                matterUuid,
                "",
                "tmpl-1",
                "Template",
                "txt",
                "user-1",
                "user@example.com",
                Map.of(),
                "enc.txt",
                "txt",
                "hello encrypted world".getBytes()
        );

        assertArrayEquals("hello encrypted world".getBytes(), store.readOutputBytes(tenantUuid, matterUuid, completed.uuid));

        Path rawPath = Paths.get("data", "tenants", tenantUuid, "matters", matterUuid, "assembled", "files")
                .toAbsolutePath()
                .resolve(completed.storageObjectKey)
                .normalize();
        assertTrue(Files.exists(rawPath));
        byte[] raw = Files.readAllBytes(rawPath);
        assertFalse(new String(raw).contains("hello encrypted world"));

        assertTrue(store.retrySyncNow(tenantUuid, matterUuid, completed.uuid));
        assembled_forms.AssemblyRec synced = store.getByUuid(tenantUuid, matterUuid, completed.uuid);
        Path remotePath = Paths.get("data", "tenants", tenantUuid, "assembled_remote", "s3_compatible")
                .toAbsolutePath()
                .resolve(synced.storageObjectKey)
                .normalize();
        assertTrue(Files.exists(remotePath));
        byte[] remoteRaw = Files.readAllBytes(remotePath);
        assertFalse(new String(remoteRaw).contains("hello encrypted world"));
    }

}
