package net.familylawandprobate.controversies;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
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
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDComboBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDListBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDRadioButton;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;

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

    @Test
    void assembly_supports_checkbox_radio_choice_list_and_signature_fields() throws Exception {
        byte[] pdfForm = richPdfForm();
        document_assembler assembler = new document_assembler();

        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put("client_name", "Jordan Petitioner");
        values.put("confirm_terms", "true");
        values.put("delivery_method", "Email");
        values.put("county_select", "Travis");
        values.put("filing_types", "Original\nMotion");
        values.put("sig_client", signatureDataUrl());

        document_assembler.AssembledFile assembled = assembler.assemble(pdfForm, "pdf", values);
        assertEquals("pdf", assembled.extension);
        assertTrue(assembled.bytes.length >= pdfForm.length);

        try (PDDocument doc = PDDocument.load(assembled.bytes)) {
            PDAcroForm form = doc.getDocumentCatalog().getAcroForm();
            assertNotNull(form);
            assertEquals("Jordan Petitioner", valueOf(form.getField("client_name")));

            PDCheckBox checkBox = (PDCheckBox) form.getField("confirm_terms");
            assertTrue(checkBox.isChecked());

            PDRadioButton radio = (PDRadioButton) form.getField("delivery_method");
            assertEquals("Email", radio.getValue());

            PDComboBox combo = (PDComboBox) form.getField("county_select");
            assertTrue(combo.getValueAsString().contains("Travis"));

            PDListBox list = (PDListBox) form.getField("filing_types");
            List<String> selected = list.getValue();
            assertTrue(selected.contains("Original"));
            assertTrue(selected.contains("Motion"));

            PDResources pageResources = doc.getPage(0).getResources();
            int xObjectCount = 0;
            for (COSName name : pageResources.getXObjectNames()) {
                if (name == null) continue;
                xObjectCount++;
            }
            assertTrue(xObjectCount > 0);
        }
    }

    @Test
    void workspace_pdf_field_descriptors_expose_prompt_types_and_options() throws Exception {
        byte[] pdfForm = richPdfForm();
        document_assembler assembler = new document_assembler();

        LinkedHashMap<String, document_assembler.PdfFieldDescriptor> meta =
                assembler.workspacePdfFieldDescriptors(pdfForm, new LinkedHashMap<String, String>());

        document_assembler.PdfFieldDescriptor text = meta.get("{{client_name}}");
        assertNotNull(text);
        assertEquals("text", text.fieldType);

        document_assembler.PdfFieldDescriptor checkbox = meta.get("{{confirm_terms}}");
        assertNotNull(checkbox);
        assertEquals("checkbox", checkbox.fieldType);

        document_assembler.PdfFieldDescriptor radio = meta.get("{{delivery_method}}");
        assertNotNull(radio);
        assertEquals("radio", radio.fieldType);
        assertTrue(hasOptionValue(radio.options, "Email"));
        assertTrue(hasOptionValue(radio.options, "Mail"));

        document_assembler.PdfFieldDescriptor select = meta.get("{{county_select}}");
        assertNotNull(select);
        assertEquals("select", select.fieldType);
        assertTrue(hasOptionValue(select.options, "Travis"));

        document_assembler.PdfFieldDescriptor list = meta.get("{{filing_types}}");
        assertNotNull(list);
        assertEquals("list", list.fieldType);
        assertTrue(list.multiSelect);

        document_assembler.PdfFieldDescriptor signature = meta.get("{{sig_client}}");
        assertNotNull(signature);
        assertEquals("signature", signature.fieldType);
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

    private static byte[] richPdfForm() throws Exception {
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream(8192)) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);

            PDAcroForm form = new PDAcroForm(doc);
            doc.getDocumentCatalog().setAcroForm(form);
            form.setNeedAppearances(true);

            PDResources resources = new PDResources();
            resources.put(COSName.getPDFName("Helv"), PDType1Font.HELVETICA);
            form.setDefaultResources(resources);
            form.setDefaultAppearance("/Helv 10 Tf 0 g");

            addTextField(form, page, "client_name", 72f, 700f, 240f, 20f);
            addCheckBoxField(form, page, "confirm_terms", 72f, 670f, 16f, 16f);
            addRadioField(form, page, "delivery_method", List.of("Email", "Mail"), 72f, 640f, 18f, 18f, 32f);
            addComboField(form, page, "county_select", List.of("Travis", "Harris", "Dallas"), 72f, 605f, 180f, 20f);
            addListField(form, page, "filing_types", List.of("Original", "Answer", "Motion"), 72f, 545f, 200f, 50f);
            addSignatureField(form, page, "sig_client", 72f, 465f, 220f, 36f);

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

    private static void addCheckBoxField(PDAcroForm form,
                                         PDPage page,
                                         String name,
                                         float x,
                                         float y,
                                         float w,
                                         float h) throws Exception {
        PDCheckBox field = new PDCheckBox(form);
        field.setPartialName(name);
        form.getFields().add(field);

        PDAnnotationWidget widget = field.getWidgets().get(0);
        widget.setRectangle(new PDRectangle(x, y, w, h));
        widget.setPage(page);
        page.getAnnotations().add(widget);
    }

    private static void addRadioField(PDAcroForm form,
                                      PDPage page,
                                      String name,
                                      List<String> exportValues,
                                      float x,
                                      float y,
                                      float w,
                                      float h,
                                      float gap) throws Exception {
        PDRadioButton field = new PDRadioButton(form);
        field.setPartialName(name);
        field.setExportValues(exportValues);
        form.getFields().add(field);

        ArrayList<PDAnnotationWidget> widgets = new ArrayList<PDAnnotationWidget>();
        for (int i = 0; i < exportValues.size(); i++) {
            PDAnnotationWidget widget = new PDAnnotationWidget();
            widget.setRectangle(new PDRectangle(x + (gap * i), y, w, h));
            widget.setPage(page);
            page.getAnnotations().add(widget);
            widgets.add(widget);
        }
        field.setWidgets(widgets);
    }

    private static void addComboField(PDAcroForm form,
                                      PDPage page,
                                      String name,
                                      List<String> options,
                                      float x,
                                      float y,
                                      float w,
                                      float h) throws Exception {
        PDComboBox field = new PDComboBox(form);
        field.setPartialName(name);
        field.setOptions(options);
        field.setDefaultAppearance("/Helv 10 Tf 0 g");
        form.getFields().add(field);

        PDAnnotationWidget widget = field.getWidgets().get(0);
        widget.setRectangle(new PDRectangle(x, y, w, h));
        widget.setPage(page);
        page.getAnnotations().add(widget);
    }

    private static void addListField(PDAcroForm form,
                                     PDPage page,
                                     String name,
                                     List<String> options,
                                     float x,
                                     float y,
                                     float w,
                                     float h) throws Exception {
        PDListBox field = new PDListBox(form);
        field.setPartialName(name);
        field.setOptions(options);
        field.setMultiSelect(true);
        field.setDefaultAppearance("/Helv 10 Tf 0 g");
        form.getFields().add(field);

        PDAnnotationWidget widget = field.getWidgets().get(0);
        widget.setRectangle(new PDRectangle(x, y, w, h));
        widget.setPage(page);
        page.getAnnotations().add(widget);
    }

    private static void addSignatureField(PDAcroForm form,
                                          PDPage page,
                                          String name,
                                          float x,
                                          float y,
                                          float w,
                                          float h) throws Exception {
        PDSignatureField field = new PDSignatureField(form);
        field.setPartialName(name);
        form.getFields().add(field);

        PDAnnotationWidget widget = field.getWidgets().get(0);
        widget.setRectangle(new PDRectangle(x, y, w, h));
        widget.setPage(page);
        page.getAnnotations().add(widget);
    }

    private static boolean hasOptionValue(List<document_assembler.PdfFieldOption> options, String expected) {
        if (options == null || options.isEmpty()) return false;
        String wanted = expected == null ? "" : expected.trim();
        for (document_assembler.PdfFieldOption option : options) {
            if (option == null) continue;
            if (wanted.equalsIgnoreCase(String.valueOf(option.value).trim())) return true;
        }
        return false;
    }

    private static String signatureDataUrl() throws Exception {
        BufferedImage image = new BufferedImage(240, 80, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, image.getWidth(), image.getHeight());
            g.setColor(new Color(17, 24, 39));
            g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine(18, 52, 90, 40);
            g.drawLine(90, 40, 140, 56);
            g.drawLine(140, 56, 224, 30);
        } finally {
            g.dispose();
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(2048);
        ImageIO.write(image, "png", out);
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
    }
}
