package net.familylawandprobate.controversies;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

public class document_workflow_test {

    @AfterEach
    void cleanup() throws Exception {
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
}
