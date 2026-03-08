package net.familylawandprobate.controversies;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class texas_pdf_compatibility_checker_test {

    @Test
    void searchableLetterPdfPassesCoreChecks() throws Exception {
        Path dir = Files.createTempDirectory("tx-pdf-check-ok");
        Path pdf = dir.resolve("Compliant2026.pdf");
        createTextPdf(pdf, PDRectangle.LETTER, "Texas compatibility searchable content line.");

        texas_pdf_compatibility_checker.Report report = texas_pdf_compatibility_checker.evaluate(pdf);

        assertNotNull(report);
        assertTrue(report.compatible);
        assertEquals(texas_pdf_compatibility_checker.Status.PASS, statusFor(report, "3.1.A"));
        assertEquals(texas_pdf_compatibility_checker.Status.PASS, statusFor(report, "3.1.D"));
        assertEquals(texas_pdf_compatibility_checker.Status.PASS, statusFor(report, "3.1.E"));
        assertEquals(texas_pdf_compatibility_checker.Status.PASS, statusFor(report, "3.1.F"));
    }

    @Test
    void imageOnlyLowDpiPdfFailsSearchAndDpiChecks() throws Exception {
        Path dir = Files.createTempDirectory("tx-pdf-check-textless");
        Path pdf = dir.resolve("ScanLowDpi2026.pdf");
        createBlankPdf(pdf);

        texas_pdf_compatibility_checker.Report report = texas_pdf_compatibility_checker.evaluate(pdf);

        assertNotNull(report);
        assertTrue(!report.compatible);
        assertEquals(texas_pdf_compatibility_checker.Status.FAIL, statusFor(report, "3.1.A"));
        assertEquals(texas_pdf_compatibility_checker.Status.WARN, statusFor(report, "3.1.C"));
    }

    @Test
    void nonAlphanumericFilenameFailsRuleF() throws Exception {
        Path dir = Files.createTempDirectory("tx-pdf-check-name");
        Path pdf = dir.resolve("bad_name-2026!.pdf");
        createTextPdf(pdf, PDRectangle.LETTER, "Filename rule test text.");

        texas_pdf_compatibility_checker.Report report = texas_pdf_compatibility_checker.evaluate(pdf);

        assertNotNull(report);
        assertEquals(texas_pdf_compatibility_checker.Status.FAIL, statusFor(report, "3.1.F"));
    }

    private static texas_pdf_compatibility_checker.Status statusFor(texas_pdf_compatibility_checker.Report report, String code) {
        if (report == null || report.checks == null) return null;
        for (texas_pdf_compatibility_checker.CheckResult row : report.checks) {
            if (row == null) continue;
            if (code.equals(row.code)) return row.status;
        }
        return null;
    }

    private static void createTextPdf(Path output, PDRectangle pageSize, String line) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(pageSize == null ? PDRectangle.LETTER : pageSize);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(PDType1Font.HELVETICA, 12f);
                cs.newLineAtOffset(72f, 720f);
                cs.showText(line == null ? "" : line);
                cs.endText();
            }
            doc.save(output.toFile());
        }
    }

    private static void createBlankPdf(Path output) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            doc.save(output.toFile());
        }
    }
}
