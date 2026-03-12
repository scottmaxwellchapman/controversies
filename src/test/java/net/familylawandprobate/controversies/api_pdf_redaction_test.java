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
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class api_pdf_redaction_test {

    private final ArrayList<Path> cleanupRoots = new ArrayList<Path>();

    @AfterEach
    void cleanup() throws Exception {
        for (Path root : cleanupRoots) {
            deleteRecursively(root);
        }
    }

    @Test
    void api_renders_and_redacts_part_versions() throws Exception {
        String tenant = "tenant-api-redact-" + UUID.randomUUID();
        String matter = "matter-api-redact-" + UUID.randomUUID();
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
        createPdf(sourcePdf, 2);
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

        LinkedHashMap<String, Object> renderParams = new LinkedHashMap<String, Object>();
        renderParams.put("matter_uuid", matter);
        renderParams.put("doc_uuid", doc.uuid);
        renderParams.put("part_uuid", part.uuid);
        renderParams.put("source_version_uuid", sourceVersion.uuid);
        renderParams.put("page", 0);

        LinkedHashMap<String, Object> renderResult = invokeApi("document.versions.render_page", renderParams, tenant);
        assertEquals(1, asInt(renderResult.get("page_number")));
        assertEquals(2, asInt(renderResult.get("total_pages")));
        assertFalse(String.valueOf(renderResult.get("image_png_base64")).isBlank());
        assertFalse(String.valueOf(renderResult.get("image_hash_sha256_rgb")).isBlank());
        assertFalse(String.valueOf(renderResult.get("image_hash_ahash64")).isBlank());
        assertFalse(String.valueOf(renderResult.get("image_hash_dhash64")).isBlank());

        LinkedHashMap<String, Object> redactParams = new LinkedHashMap<String, Object>();
        redactParams.put("matter_uuid", matter);
        redactParams.put("doc_uuid", doc.uuid);
        redactParams.put("part_uuid", part.uuid);
        redactParams.put("source_version_uuid", sourceVersion.uuid);
        redactParams.put("version_label", "v2-redacted");
        redactParams.put("created_by", "Tester");
        redactParams.put("make_current", true);
        redactParams.put("redactions", List.of(
                Map.of("page", 0, "x_norm", 0.10d, "y_norm", 0.10d, "width_norm", 0.30d, "height_norm", 0.10d),
                Map.of("page", 1, "x_norm", 0.20d, "y_norm", 0.20d, "width_norm", 0.25d, "height_norm", 0.12d)
        ));

        LinkedHashMap<String, Object> redactResult = invokeApi("document.versions.redact", redactParams, tenant);
        assertEquals(2, asInt(redactResult.get("redaction_count")));
        boolean usedPdfRedactor = Boolean.parseBoolean(String.valueOf(redactResult.get("used_pdfredactor")));
        Object createdObj = redactResult.get("version");
        assertTrue(createdObj instanceof Map<?, ?>);

        @SuppressWarnings("unchecked")
        Map<String, Object> created = (Map<String, Object>) createdObj;
        assertEquals("v2-redacted", String.valueOf(created.get("version_label")));
        assertEquals("application/pdf", String.valueOf(created.get("mime_type")));
        assertFalse(String.valueOf(created.get("storage_path")).isBlank());

        List<part_versions.VersionRec> all = part_versions.defaultStore().listAll(tenant, matter, doc.uuid, part.uuid);
        assertEquals(2, all.size());
        boolean hasRedacted = false;
        for (part_versions.VersionRec row : all) {
            if (row == null) continue;
            if (!"redacted".equals(row.source)) continue;
            hasRedacted = true;
            Path redactedPath = pdf_redaction_service.resolveStoragePath(row.storagePath);
            assertNotNull(redactedPath);
            assertTrue(Files.isRegularFile(redactedPath));
            assertTrue(Files.size(redactedPath) > 0L);
            assertTrue(hasAnnotationSubtype(redactedPath, 0, "Text"));
            assertTrue(hasAnnotationSubtype(redactedPath, 0, "Stamp"));
            assertTrue(hasAnnotationSubtype(redactedPath, 0, "Widget"));
            assertTrue(hasAnnotationSubtype(redactedPath, 1, PDAnnotationSquareCircle.SUB_TYPE_SQUARE));
            assertTrue(hasAnnotationContent(redactedPath, "API source comment bubble"));
            assertTrue(hasAnnotationContent(redactedPath, "API source comment box"));
            assertTrue(hasAnnotationContent(redactedPath, "API source sticker object"));
            assertTrue(hasAnnotationContent(redactedPath, "API source signature widget"));
            if (!usedPdfRedactor) {
                String extracted = extractText(redactedPath);
                assertTrue(extracted.trim().isEmpty(), "Rasterized fallback should not retain extractable source text.");
            }
        }
        assertTrue(hasRedacted);
    }

    @SuppressWarnings("unchecked")
    private static LinkedHashMap<String, Object> invokeApi(String operation, Map<String, Object> params, String tenantUuid) throws Exception {
        Method m = api_servlet.class.getDeclaredMethod(
                "executeOperation",
                String.class,
                Map.class,
                String.class,
                String.class,
                String.class,
                String.class,
                String.class
        );
        m.setAccessible(true);
        try {
            Object out = m.invoke(null, operation, params, tenantUuid, "cred-1", "Credential", "127.0.0.1", "POST");
            if (out instanceof LinkedHashMap<?, ?> map) return (LinkedHashMap<String, Object>) map;
            return new LinkedHashMap<String, Object>();
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof Exception e) throw e;
            throw ex;
        }
    }

    private static int asInt(Object raw) {
        if (raw instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(raw));
        } catch (Exception ignored) {
            return 0;
        }
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
                    cs.showText("API Redaction Test Page " + (i + 1));
                    cs.endText();
                }
            }
            doc.save(target.toFile());
        }
    }

    private static String extractText(Path target) throws Exception {
        try (PDDocument doc = PDDocument.load(target.toFile())) {
            return new PDFTextStripper().getText(doc);
        }
    }

    private static void addCommentAndStickerAnnotations(Path target) throws Exception {
        try (PDDocument doc = PDDocument.load(target.toFile())) {
            if (doc.getNumberOfPages() <= 0) {
                doc.save(target.toFile());
                return;
            }

            PDPage page0 = doc.getPage(0);
            PDAnnotationText comment = new PDAnnotationText();
            comment.setRectangle(new PDRectangle(72f, 640f, 22f, 22f));
            comment.setContents("API source comment bubble");
            comment.setTitlePopup("Reviewer");
            page0.getAnnotations().add(comment);

            PDAnnotationRubberStamp sticker = new PDAnnotationRubberStamp();
            sticker.setRectangle(new PDRectangle(120f, 620f, 130f, 48f));
            sticker.setContents("API source sticker object");
            page0.getAnnotations().add(sticker);

            PDAnnotationWidget signatureWidget = new PDAnnotationWidget();
            signatureWidget.setRectangle(new PDRectangle(280f, 620f, 180f, 38f));
            signatureWidget.setContents("API source signature widget");
            page0.getAnnotations().add(signatureWidget);

            PDPage page1 = doc.getNumberOfPages() > 1 ? doc.getPage(1) : page0;
            PDAnnotationSquareCircle commentBox = new PDAnnotationSquareCircle(PDAnnotationSquareCircle.SUB_TYPE_SQUARE);
            commentBox.setRectangle(new PDRectangle(72f, 520f, 220f, 64f));
            commentBox.setContents("API source comment box");
            page1.getAnnotations().add(commentBox);

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
