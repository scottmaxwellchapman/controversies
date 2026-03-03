package net.familylawandprobate.controversies;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class matter_facts_test {

    private final ArrayList<Path> cleanupRoots = new ArrayList<Path>();

    @AfterEach
    void cleanup() throws Exception {
        for (Path root : cleanupRoots) {
            deleteRecursively(root);
        }
    }

    @Test
    void facts_hierarchy_generates_landscape_report_and_versions() throws Exception {
        String tenant = "tenant-facts-" + UUID.randomUUID();
        cleanupRoots.add(Paths.get("data", "tenants", tenant).toAbsolutePath());

        matters matterStore = matters.defaultStore();
        matterStore.ensure(tenant);
        matters.MatterRec matter = matterStore.create(
                tenant,
                "Facts Matter",
                "",
                "",
                "",
                "",
                ""
        );
        assertNotNull(matter);

        documents docs = documents.defaultStore();
        document_parts parts = document_parts.defaultStore();
        part_versions versions = part_versions.defaultStore();

        documents.DocumentRec evidenceDoc = docs.create(
                tenant,
                matter.uuid,
                "Signed Contract",
                "evidence",
                "contract",
                "draft",
                "owner",
                "work_product",
                "",
                "",
                ""
        );
        document_parts.PartRec evidencePart = parts.create(
                tenant,
                matter.uuid,
                evidenceDoc.uuid,
                "Main Exhibit",
                "lead",
                "1",
                "internal",
                "owner",
                ""
        );
        Path sourcePath = parts.partFolder(tenant, matter.uuid, evidenceDoc.uuid, evidencePart.uuid)
                .resolve("version_files")
                .resolve("source_contract.pdf")
                .toAbsolutePath()
                .normalize();
        Files.createDirectories(sourcePath.getParent());
        Files.write(sourcePath, "source".getBytes(StandardCharsets.UTF_8));
        part_versions.VersionRec sourceVersion = versions.create(
                tenant,
                matter.uuid,
                evidenceDoc.uuid,
                evidencePart.uuid,
                "v1 source",
                "uploaded",
                "application/pdf",
                "abc123",
                String.valueOf(Files.size(sourcePath)),
                sourcePath.toString(),
                "owner",
                "",
                true
        );

        matter_facts store = matter_facts.defaultStore();
        store.ensure(tenant, matter.uuid);

        matter_facts.ClaimRec claim = store.createClaim(
                tenant,
                matter.uuid,
                "Breach of Contract",
                "Defendant failed to perform under signed contract terms.",
                10,
                "tester"
        );
        assertNotNull(claim);

        matter_facts.ElementRec element = store.createElement(
                tenant,
                matter.uuid,
                claim.uuid,
                "Existence of valid agreement",
                "Need proof of signatures and mutual assent.",
                10,
                "tester"
        );
        assertNotNull(element);

        matter_facts.FactRec fact = store.createFact(
                tenant,
                matter.uuid,
                claim.uuid,
                element.uuid,
                "Signed engagement agreement by both parties",
                "Agreement executed on 2024-02-15 with all required terms.",
                "Use this fact for summary judgment brief framing.",
                "corroborated",
                "high",
                evidenceDoc.uuid,
                evidencePart.uuid,
                sourceVersion.uuid,
                1,
                10,
                "tester"
        );
        assertNotNull(fact);
        assertFalse(safe(fact.uuid).isBlank());

        matter_facts.ReportRec report = store.reportRefs(tenant, matter.uuid);
        assertFalse(safe(report.reportDocumentUuid).isBlank());
        assertFalse(safe(report.reportPartUuid).isBlank());
        assertFalse(safe(report.lastReportVersionUuid).isBlank());

        List<part_versions.VersionRec> reportVersionsBefore = versions.listAll(
                tenant,
                matter.uuid,
                report.reportDocumentUuid,
                report.reportPartUuid
        );
        assertFalse(reportVersionsBefore.isEmpty());

        Path reportPdf = Paths.get(safe(reportVersionsBefore.get(0).storagePath)).toAbsolutePath().normalize();
        assertTrue(Files.exists(reportPdf));
        assertTrue(Files.size(reportPdf) > 0L);

        try (PDDocument pdf = PDDocument.load(reportPdf.toFile())) {
            assertTrue(pdf.getPage(0).getMediaBox().getWidth() > pdf.getPage(0).getMediaBox().getHeight());
            String text = safe(new PDFTextStripper().getText(pdf));
            assertTrue(text.contains("Facts Case Plan Report"));
            assertTrue(text.contains("Breach of Contract"));
            assertTrue(text.contains("Signed engagement agreement"));
        }

        matter_facts.FactRec update = store.getFact(tenant, matter.uuid, fact.uuid);
        assertNotNull(update);
        update.summary = "Contract signed and notarized by both parties";
        update.detail = "Notarized signature page confirms execution authenticity.";
        boolean changed = store.updateFact(tenant, matter.uuid, update, "tester");
        assertTrue(changed);

        List<part_versions.VersionRec> reportVersionsAfter = versions.listAll(
                tenant,
                matter.uuid,
                report.reportDocumentUuid,
                report.reportPartUuid
        );
        assertTrue(reportVersionsAfter.size() > reportVersionsBefore.size());
    }

    @Test
    void archiving_claim_cascades_to_elements_and_facts() throws Exception {
        String tenant = "tenant-facts-cascade-" + UUID.randomUUID();
        cleanupRoots.add(Paths.get("data", "tenants", tenant).toAbsolutePath());

        matters matterStore = matters.defaultStore();
        matterStore.ensure(tenant);
        matters.MatterRec matter = matterStore.create(
                tenant,
                "Cascade Matter",
                "",
                "",
                "",
                "",
                ""
        );

        matter_facts store = matter_facts.defaultStore();
        store.ensure(tenant, matter.uuid);

        matter_facts.ClaimRec claim = store.createClaim(tenant, matter.uuid, "Fraud", "", 10, "tester");
        matter_facts.ElementRec element = store.createElement(tenant, matter.uuid, claim.uuid, "Intent", "", 10, "tester");
        matter_facts.FactRec fact = store.createFact(
                tenant,
                matter.uuid,
                claim.uuid,
                element.uuid,
                "Email indicates intentional misrepresentation",
                "",
                "",
                "unverified",
                "medium",
                "",
                "",
                "",
                0,
                10,
                "tester"
        );

        assertTrue(store.setClaimTrashed(tenant, matter.uuid, claim.uuid, true, "tester"));
        assertTrue(store.getClaim(tenant, matter.uuid, claim.uuid).trashed);
        assertTrue(store.getElement(tenant, matter.uuid, element.uuid).trashed);
        assertTrue(store.getFact(tenant, matter.uuid, fact.uuid).trashed);

        assertTrue(store.setClaimTrashed(tenant, matter.uuid, claim.uuid, false, "tester"));
        assertFalse(store.getClaim(tenant, matter.uuid, claim.uuid).trashed);
        assertFalse(store.getElement(tenant, matter.uuid, element.uuid).trashed);
        assertFalse(store.getFact(tenant, matter.uuid, fact.uuid).trashed);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (root == null || !Files.exists(root)) return;
        Files.walk(root).sorted(Comparator.reverseOrder()).forEach(p -> {
            try {
                Files.deleteIfExists(p);
            } catch (Exception ignored) {
            }
        });
    }
}
