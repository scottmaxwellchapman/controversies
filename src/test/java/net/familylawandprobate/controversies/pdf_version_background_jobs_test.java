package net.familylawandprobate.controversies;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class pdf_version_background_jobs_test {

    private final ArrayList<Path> cleanupRoots = new ArrayList<Path>();

    @AfterEach
    void cleanup() throws Exception {
        for (Path root : cleanupRoots) {
            deleteRecursively(root);
        }
    }

    @Test
    void enqueue_flatten_creates_new_version_in_background() throws Exception {
        String tenant = "tenant-pdf-jobs-" + UUID.randomUUID();
        String matter = "matter-pdf-jobs-" + UUID.randomUUID();
        cleanupRoots.add(Paths.get("data", "tenants", tenant).toAbsolutePath());

        documents.DocumentRec doc = documents.defaultStore().create(
                tenant,
                matter,
                "Source PDF",
                "pleading",
                "motion",
                "draft",
                "Tester",
                "work_product",
                "",
                "",
                ""
        );
        document_parts.PartRec part = document_parts.defaultStore().create(
                tenant,
                matter,
                doc.uuid,
                "Main",
                "lead",
                "1",
                "",
                "Tester",
                ""
        );
        Path partFolder = document_parts.defaultStore().partFolder(tenant, matter, doc.uuid, part.uuid);
        assertNotNull(partFolder);
        Path versionFiles = partFolder.resolve("version_files");
        Files.createDirectories(versionFiles);

        Path sourcePdf = versionFiles.resolve("source.pdf");
        createPdf(sourcePdf, 1);

        part_versions.VersionRec sourceVersion = part_versions.defaultStore().create(
                tenant,
                matter,
                doc.uuid,
                part.uuid,
                "v1",
                "uploaded",
                "application/pdf",
                pdf_redaction_service.sha256(sourcePdf),
                String.valueOf(Files.size(sourcePdf)),
                sourcePdf.toUri().toString(),
                "Tester",
                "",
                true
        );

        String jobId = pdf_version_background_jobs.defaultService().enqueueFlatten(
                tenant,
                matter,
                doc.uuid,
                part.uuid,
                sourceVersion.uuid,
                "Tester"
        );
        assertTrue(jobId != null && !jobId.isBlank());

        part_versions.VersionRec flattened = waitForDerivedVersion(tenant, matter, doc.uuid, part.uuid, "flattened", 20_000L);
        assertNotNull(flattened);
        assertTrue(flattened.versionLabel.contains("Flattened"));
    }

    private static part_versions.VersionRec waitForDerivedVersion(String tenant,
                                                                  String matter,
                                                                  String docUuid,
                                                                  String partUuid,
                                                                  String source,
                                                                  long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + Math.max(1000L, timeoutMs);
        while (System.currentTimeMillis() < deadline) {
            List<part_versions.VersionRec> rows = part_versions.defaultStore().listAll(tenant, matter, docUuid, partUuid);
            for (part_versions.VersionRec rec : rows) {
                if (rec == null) continue;
                if (source.equals(rec.source)) return rec;
            }
            Thread.sleep(150L);
        }
        List<part_versions.VersionRec> rows = part_versions.defaultStore().listAll(tenant, matter, docUuid, partUuid);
        assertEquals(2, rows.size(), "Timed out waiting for derived version creation.");
        return null;
    }

    private static void createPdf(Path target, int pages) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(PDType1Font.HELVETICA, 12f);
                    cs.newLineAtOffset(72f, 720f);
                    cs.showText("Background Jobs Test Page " + (i + 1));
                    cs.endText();
                }
            }
            doc.save(target.toFile());
        }
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
