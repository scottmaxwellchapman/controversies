package net.familylawandprobate.controversies;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class pdf_redaction_service_test {

    @Test
    void renders_and_redacts_pdf_pages() throws Exception {
        Path work = Files.createTempDirectory("pdf-redaction-service-");
        try {
            Path source = work.resolve("source.pdf");
            createPdf(source, 2);

            assertEquals(source.toAbsolutePath().normalize(),
                    pdf_redaction_service.resolveStoragePath(source.toUri().toString()));

            pdf_redaction_service.PdfInfo info = pdf_redaction_service.inspect(source);
            assertEquals(2, info.totalPages);

            pdf_redaction_service.RenderedPage page = pdf_redaction_service.renderPage(source, 0);
            assertEquals(0, page.pageIndex);
            assertEquals(2, page.totalPages);
            assertTrue(page.imageWidthPx > 0);
            assertTrue(page.imageHeightPx > 0);
            assertTrue(page.pngBytes.length > 1000);

            List<pdf_redaction_service.RedactionRectNorm> normalized =
                    pdf_redaction_service.parseNormalizedPayload("0,0.08,0.12,0.24,0.08;1,0.20,0.45,0.25,0.12");
            assertEquals(2, normalized.size());

            List<pdf_redaction_service.RedactionRectPt> rects =
                    pdf_redaction_service.toPageCoordinates(source, normalized);
            assertEquals(2, rects.size());

            Path output = work.resolve("source_redacted.pdf");
            pdf_redaction_service.RedactionRun run = pdf_redaction_service.redact(source, output, rects);
            assertNotNull(run);
            assertEquals(2, run.appliedRectCount);
            assertTrue(Files.exists(output));
            assertTrue(Files.size(output) > 0L);

            String sha = pdf_redaction_service.sha256(output);
            assertEquals(64, sha.length());

            String suggested = pdf_redaction_service.suggestRedactedFileName("Motion to Compel.pdf");
            assertTrue(suggested.toLowerCase().endsWith("_redacted.pdf"));
        } finally {
            deleteRecursively(work);
        }
    }

    @Test
    void parses_object_redaction_rows() {
        List<pdf_redaction_service.RedactionRectNorm> rows = pdf_redaction_service.parseNormalizedObjects(
                List.of(
                        new LinkedHashMap<String, Object>() {{
                            put("page", 0);
                            put("x_norm", 0.10d);
                            put("y_norm", 0.20d);
                            put("width_norm", 0.30d);
                            put("height_norm", 0.15d);
                        }},
                        new LinkedHashMap<String, Object>() {{
                            put("page_index", 1);
                            put("x", 0.50d);
                            put("y", 0.50d);
                            put("w", 0.25d);
                            put("h", 0.25d);
                        }}
                )
        );

        assertEquals(2, rows.size());
        assertEquals(0, rows.get(0).pageIndex);
        assertEquals(1, rows.get(1).pageIndex);
        assertTrue(pdf_redaction_service.parseNormalizedPayload("bad,data").isEmpty());
    }

    private static void createPdf(Path target, int pages) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(PDType1Font.HELVETICA, 14f);
                    cs.newLineAtOffset(72f, 720f);
                    cs.showText("Page " + (i + 1) + " Sample Content");
                    cs.endText();
                }
            }
            doc.save(target.toFile());
        }
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (root == null || !Files.exists(root)) return;
        Files.walk(root)
                .sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                    }
                });
    }
}
