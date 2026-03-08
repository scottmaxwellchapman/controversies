package net.familylawandprobate.controversies;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.junit.jupiter.api.Test;

public class pdf_form_assembly_test {

    @Test
    void preview_defaults_and_assembly_use_pdf_field_name_keys() throws Exception {
        byte[] pdfForm = minimalPdfForm();
        document_assembler assembler = new document_assembler();

        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put("petitioner_name", "Jordan Petitioner");
        values.put("[Client Name]", "Acme Client");

        document_assembler.PreviewResult preview = assembler.preview(pdfForm, "pdf", values);
        assertTrue(preview.usedTokens.contains("{{petitioner_name}}"));
        assertTrue(preview.usedTokens.contains("[Client Name]"));
        assertFalse(preview.missingTokens.contains("{{petitioner_name}}"));
        assertFalse(preview.missingTokens.contains("[Client Name]"));

        LinkedHashMap<String, String> defaults = assembler.workspacePdfFieldDefaults(pdfForm, values);
        assertEquals("Jordan Petitioner", defaults.get("{{petitioner_name}}"));
        assertEquals("Acme Client", defaults.get("[Client Name]"));

        document_assembler.AssembledFile assembled = assembler.assemble(pdfForm, "pdf", values);
        assertEquals("pdf", assembled.extension);
        assertEquals("application/pdf", assembled.contentType);

        try (PDDocument doc = PDDocument.load(assembled.bytes)) {
            PDAcroForm form = doc.getDocumentCatalog().getAcroForm();
            assertEquals("Jordan Petitioner", valueOf(form.getField("petitioner_name")));
            assertEquals("Acme Client", valueOf(form.getField("Client Name")));
        }
    }

    @Test
    void image_preview_renders_pdf_and_returns_field_hit_rects() throws Exception {
        byte[] pdfForm = minimalPdfForm();
        document_image_preview previewer = new document_image_preview();

        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put("petitioner_name", "Jordan Petitioner");
        values.put("[Client Name]", "Acme Client");

        ArrayList<String> needles = new ArrayList<String>(List.of("{{petitioner_name}}", "[Client Name]"));
        document_image_preview.PreviewResult preview = previewer.render(pdfForm, "pdf", values, needles, 2);

        assertFalse(preview.pages.isEmpty());
        assertEquals("PDF Renderer", preview.engine);
        assertTrue(preview.hitRects.containsKey("{{petitioner_name}}"));
        assertFalse(preview.hitRects.get("{{petitioner_name}}").isEmpty());
        assertTrue(preview.hitRects.containsKey("[Client Name]"));
        assertFalse(preview.hitRects.get("[Client Name]").isEmpty());

        document_image_preview.FocusPreview focus = previewer.renderFocusPreview(preview, "{{petitioner_name}}", 0, false);
        assertTrue(focus.width > 0);
        assertTrue(focus.height > 0);
        assertFalse(focus.base64Png.isBlank());
    }

    private static String valueOf(PDField field) throws Exception {
        if (field == null) return "";
        return String.valueOf(field.getValueAsString());
    }

    private static byte[] minimalPdfForm() throws Exception {
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream(4096)) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);

            PDAcroForm form = new PDAcroForm(doc);
            doc.getDocumentCatalog().setAcroForm(form);
            form.setNeedAppearances(true);

            PDResources resources = new PDResources();
            resources.put(COSName.getPDFName("Helv"), PDType1Font.HELVETICA);
            form.setDefaultResources(resources);
            form.setDefaultAppearance("/Helv 10 Tf 0 g");

            addTextField(form, page, "petitioner_name", 72f, 700f, 240f, 20f);
            addTextField(form, page, "Client Name", 72f, 660f, 240f, 20f);

            doc.save(out);
            return out.toByteArray();
        }
    }

    private static void addTextField(PDAcroForm form,
                                     PDPage page,
                                     String name,
                                     float x,
                                     float y,
                                     float w,
                                     float h) throws Exception {
        PDTextField field = new PDTextField(form);
        field.setPartialName(name);
        field.setDefaultAppearance("/Helv 10 Tf 0 g");
        form.getFields().add(field);

        PDAnnotationWidget widget = field.getWidgets().get(0);
        widget.setRectangle(new PDRectangle(x, y, w, h));
        widget.setPage(page);
        page.getAnnotations().add(widget);
    }
}
