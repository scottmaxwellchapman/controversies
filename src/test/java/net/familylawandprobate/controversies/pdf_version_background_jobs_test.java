package net.familylawandprobate.controversies;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationRubberStamp;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationSquareCircle;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationText;
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
        addCommentAndStickerAnnotations(sourcePdf);

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
        Path flattenedPath = pdf_redaction_service.resolveStoragePath(flattened.storagePath);
        assertNotNull(flattenedPath);
        assertTrue(Files.isRegularFile(flattenedPath));
        assertTrue(hasAnnotationSubtype(flattenedPath, 0, "Text"));
        assertTrue(hasAnnotationSubtype(flattenedPath, 0, "Stamp"));
        assertTrue(hasAnnotationSubtype(flattenedPath, 0, PDAnnotationSquareCircle.SUB_TYPE_SQUARE));
        assertTrue(hasAnnotationContent(flattenedPath, "Background job comment bubble"));
        assertTrue(hasAnnotationContent(flattenedPath, "Background job sticker object"));
        assertTrue(hasAnnotationContent(flattenedPath, "Background job comment box"));
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

    private static void addCommentAndStickerAnnotations(Path target) throws Exception {
        try (PDDocument doc = PDDocument.load(target.toFile())) {
            if (doc.getNumberOfPages() <= 0) {
                doc.save(target.toFile());
                return;
            }
            PDPage page = doc.getPage(0);

            PDAnnotationText comment = new PDAnnotationText();
            comment.setRectangle(new PDRectangle(72f, 640f, 22f, 22f));
            comment.setContents("Background job comment bubble");
            page.getAnnotations().add(comment);

            PDAnnotationRubberStamp sticker = new PDAnnotationRubberStamp();
            sticker.setRectangle(new PDRectangle(120f, 620f, 130f, 48f));
            sticker.setContents("Background job sticker object");
            page.getAnnotations().add(sticker);

            PDAnnotationSquareCircle commentBox = new PDAnnotationSquareCircle(PDAnnotationSquareCircle.SUB_TYPE_SQUARE);
            commentBox.setRectangle(new PDRectangle(72f, 520f, 220f, 64f));
            commentBox.setContents("Background job comment box");
            page.getAnnotations().add(commentBox);

            doc.save(target.toFile());
        }
    }

    private static boolean hasAnnotationSubtype(Path target, int pageIndex, String expectedSubtype) throws Exception {
        try (PDDocument doc = PDDocument.load(target.toFile())) {
            if (pageIndex < 0 || pageIndex >= doc.getNumberOfPages()) return false;
            for (PDAnnotation annotation : doc.getPage(pageIndex).getAnnotations()) {
                if (annotation == null) continue;
                String subtype = String.valueOf(annotation.getSubtype());
                if (expectedSubtype.equalsIgnoreCase(subtype)) return true;
            }
            return false;
        }
    }

    private static boolean hasAnnotationContent(Path target, String expectedContent) throws Exception {
        String needle = expectedContent == null ? "" : expectedContent;
        try (PDDocument doc = PDDocument.load(target.toFile())) {
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                for (PDAnnotation annotation : doc.getPage(i).getAnnotations()) {
                    if (annotation == null) continue;
                    if (needle.equals(String.valueOf(annotation.getContents()))) return true;
                }
            }
            return false;
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
