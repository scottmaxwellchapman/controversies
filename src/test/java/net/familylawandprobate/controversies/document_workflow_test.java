package net.familylawandprobate.controversies;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

public class document_workflow_test {

    @AfterEach
    void cleanup() throws Exception {
        documents.clearActorContext();
        Path root = Paths.get("data", "tenants", "tenant-doc-test");
        if (!Files.exists(root)) return;
        Files.walk(root).sorted(Comparator.reverseOrder()).forEach(p -> {
            try { Files.deleteIfExists(p); } catch (Exception ignored) {}
        });
    }

    @Test
    void stores_document_part_version_and_taxonomy() throws Exception {
        String tenant = "tenant-doc-test";
        String matter = "matter-001";

        document_taxonomy tx = document_taxonomy.defaultStore();
        tx.addValues(tenant, java.util.List.of("Pleading"), java.util.List.of("Motion"), java.util.List.of("Draft"));
        assertTrue(tx.read(tenant).categories.contains("pleading"));

        documents docs = documents.defaultStore();
        documents.DocumentRec doc = docs.create(tenant, matter, "Motion to Compel", "pleading", "motion", "draft", "Attorney", "work_product", "", "", "");
        assertFalse(doc.uuid.isBlank());
        assertEquals(1, docs.listAll(tenant, matter).size());

        document_parts parts = document_parts.defaultStore();
        document_parts.PartRec part = parts.create(tenant, matter, doc.uuid, "Argument", "attachment", "1", "confidential", "Atty", "");
        assertEquals(1, parts.listAll(tenant, matter, doc.uuid).size());

        part_versions versions = part_versions.defaultStore();
        versions.create(tenant, matter, doc.uuid, part.uuid, "v1", "generated", "application/pdf", "abc", "100", "vault://x", "Atty", "", true);
        assertEquals(1, versions.listAll(tenant, matter, doc.uuid, part.uuid).size());
    }

    @Test
    void document_part_and_version_events_can_trigger_bpm_workflows() throws Exception {
        String tenant = "tenant-doc-test";
        String matter = "matter-events";

        business_process_manager bpm = business_process_manager.defaultService();
        business_process_manager.ProcessDefinition partProcess = new business_process_manager.ProcessDefinition();
        partProcess.name = "Part Event Watch";
        business_process_manager.ProcessTrigger partTrigger = new business_process_manager.ProcessTrigger();
        partTrigger.type = "document.part.created";
        partProcess.triggers.add(partTrigger);
        business_process_manager.ProcessStep partStep = new business_process_manager.ProcessStep();
        partStep.stepId = "part_log";
        partStep.order = 10;
        partStep.action = "log_note";
        partStep.settings.put("message", "Part created: {{event.part_uuid}}");
        partProcess.steps.add(partStep);
        bpm.saveProcess(tenant, partProcess);

        business_process_manager.ProcessDefinition versionProcess = new business_process_manager.ProcessDefinition();
        versionProcess.name = "Version Event Watch";
        business_process_manager.ProcessTrigger versionTrigger = new business_process_manager.ProcessTrigger();
        versionTrigger.type = "document.version.created";
        versionProcess.triggers.add(versionTrigger);
        business_process_manager.ProcessStep versionStep = new business_process_manager.ProcessStep();
        versionStep.stepId = "version_log";
        versionStep.order = 10;
        versionStep.action = "log_note";
        versionStep.settings.put("message", "Version created: {{event.version_uuid}}");
        versionProcess.steps.add(versionStep);
        bpm.saveProcess(tenant, versionProcess);

        documents docs = documents.defaultStore();
        documents.DocumentRec doc = docs.create(tenant, matter, "Event Doc", "pleading", "motion", "draft", "Atty", "work_product", "", "", "");

        document_parts parts = document_parts.defaultStore();
        document_parts.PartRec part = parts.create(tenant, matter, doc.uuid, "Argument", "attachment", "1", "confidential", "Atty", "");

        part_versions versions = part_versions.defaultStore();
        versions.create(tenant, matter, doc.uuid, part.uuid, "v1", "generated", "application/pdf", "abc", "100", "vault://event", "Atty", "", true);

        List<business_process_manager.RunResult> runs = bpm.listRuns(tenant, 20);
        assertEquals(2, runs.size());
        assertEquals("completed", runs.get(0).status);
        assertEquals("completed", runs.get(1).status);
    }

    @Test
    void rejects_local_version_storage_paths_outside_tenant_root() throws Exception {
        String tenant = "tenant-doc-test";
        String matter = "matter-002";

        documents docs = documents.defaultStore();
        documents.DocumentRec doc = docs.create(tenant, matter, "Guardrail Doc", "pleading", "motion", "draft", "Attorney", "work_product", "", "", "");
        document_parts parts = document_parts.defaultStore();
        document_parts.PartRec part = parts.create(tenant, matter, doc.uuid, "Main", "lead", "1", "", "Atty", "");

        Path outside = Paths.get("data", "sec", "outside-tenant.pdf").toAbsolutePath();
        part_versions versions = part_versions.defaultStore();
        assertThrows(IllegalArgumentException.class, () ->
                versions.create(
                        tenant,
                        matter,
                        doc.uuid,
                        part.uuid,
                        "v1",
                        "uploaded",
                        "application/pdf",
                        "abc",
                        "100",
                        outside.toString(),
                        "Atty",
                        "",
                        true
                ));
    }

    @Test
    void clio_synced_document_is_read_only_but_external_sync_can_append_versions() throws Exception {
        String tenant = "tenant-doc-test";
        String matter = "matter-003";

        documents docs = documents.defaultStore();
        documents.DocumentRec doc = docs.create(
                tenant, matter, "Clio Doc", "clio", "", "synced", "Clio", "", "", "clio-123", ""
        );
        docs.updateSourceMetadata(tenant, matter, doc.uuid, "clio", "clio-123", "2026-03-01T00:00:00Z", true);

        documents.DocumentRec updateIn = new documents.DocumentRec();
        updateIn.uuid = doc.uuid;
        updateIn.title = "Edited";
        updateIn.category = "x";
        updateIn.subcategory = "";
        updateIn.status = "draft";
        updateIn.owner = "User";
        updateIn.privilegeLevel = "";
        updateIn.filedOn = "";
        updateIn.externalReference = "";
        updateIn.notes = "";

        assertThrows(IllegalStateException.class, () -> docs.update(tenant, matter, updateIn));
        assertThrows(IllegalStateException.class, () -> docs.setTrashed(tenant, matter, doc.uuid, true));
        assertThrows(IllegalStateException.class, () ->
                document_fields.defaultStore().write(tenant, matter, doc.uuid, java.util.Map.of("k", "v")));
        assertThrows(IllegalStateException.class, () ->
                document_parts.defaultStore().create(tenant, matter, doc.uuid, "Part", "lead", "1", "", "User", ""));

        document_parts.PartRec externalPart = document_parts.defaultStore().createForExternalSync(
                tenant, matter, doc.uuid, "Clio Document", "lead", "1", "", "Clio", ""
        );
        assertFalse(externalPart.uuid.isBlank());

        part_versions.VersionRec externalVersion = part_versions.defaultStore().createForExternalSync(
                tenant,
                matter,
                doc.uuid,
                externalPart.uuid,
                "v1",
                "clio_version:900",
                "application/pdf",
                "abc",
                "100",
                "vault://clio",
                "clio-sync",
                "synced",
                true
        );
        assertFalse(externalVersion.uuid.isBlank());

        assertThrows(IllegalStateException.class, () ->
                part_versions.defaultStore().create(
                        tenant,
                        matter,
                        doc.uuid,
                        externalPart.uuid,
                        "manual-v2",
                        "uploaded",
                        "application/pdf",
                        "xyz",
                        "200",
                        "vault://manual",
                        "user",
                        "",
                        true
                ));
    }

    @Test
    void checkout_blocks_other_users_but_allows_owner_edits() throws Exception {
        String tenant = "tenant-doc-test";
        String matter = "matter-004";

        documents docs = documents.defaultStore();
        documents.DocumentRec doc = docs.create(tenant, matter, "Shared Doc", "pleading", "", "draft", "A", "", "", "", "");

        assertTrue(docs.checkOut(tenant, matter, doc.uuid, "alice@example.com"));
        assertThrows(IllegalStateException.class, () -> docs.checkOut(tenant, matter, doc.uuid, "bob@example.com"));

        documents.DocumentRec in = new documents.DocumentRec();
        in.uuid = doc.uuid;
        in.title = "Shared Doc Updated";
        in.category = "pleading";
        in.subcategory = "";
        in.status = "draft";
        in.owner = "A";
        in.privilegeLevel = "";
        in.filedOn = "";
        in.externalReference = "";
        in.notes = "";

        documents.bindActorContext("alice@example.com");
        assertTrue(docs.update(tenant, matter, in));
        documents.clearActorContext();

        documents.bindActorContext("bob@example.com");
        assertThrows(IllegalStateException.class, () -> docs.update(tenant, matter, in));
        documents.clearActorContext();

        assertThrows(IllegalStateException.class, () -> docs.checkIn(tenant, matter, doc.uuid, "bob@example.com"));
        assertTrue(docs.checkIn(tenant, matter, doc.uuid, "alice@example.com"));
        assertTrue(docs.checkOut(tenant, matter, doc.uuid, "bob@example.com"));
    }

    @Test
    void checkout_auto_expires_after_72_hours() throws Exception {
        String tenant = "tenant-doc-test";
        String matter = "matter-005";

        documents docs = documents.defaultStore();
        documents.DocumentRec doc = docs.create(tenant, matter, "Expiring Lock", "pleading", "", "draft", "A", "", "", "", "");

        assertTrue(docs.checkOut(tenant, matter, doc.uuid, "alice@example.com"));

        documents.DocumentRec locked = docs.get(tenant, matter, doc.uuid);
        String priorCheckedOutAt = locked == null ? "" : locked.checkedOutAt;
        Path docsPath = Paths.get("data", "tenants", tenant, "matters", matter, "documents.xml").toAbsolutePath();
        String xml = Files.readString(docsPath);
        String expiredAt = Instant.now().minus(Duration.ofHours(73)).toString();
        xml = xml.replace(
                "<checked_out_at>" + document_workflow_support.xmlText(priorCheckedOutAt) + "</checked_out_at>",
                "<checked_out_at>" + document_workflow_support.xmlText(expiredAt) + "</checked_out_at>"
        );
        Files.writeString(docsPath, xml);

        documents.DocumentRec expired = docs.get(tenant, matter, doc.uuid);
        assertFalse(documents.isCheckoutActive(expired));
        assertTrue(docs.checkOut(tenant, matter, doc.uuid, "bob@example.com"));
    }
}
