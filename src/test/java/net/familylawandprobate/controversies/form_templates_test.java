package net.familylawandprobate.controversies;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;

public class form_templates_test {

    @Test
    void create_and_list_supports_folder_paths_and_subfolders() throws Exception {
        String tenantUuid = "tmpl-folder-" + UUID.randomUUID();
        Path tenantDir = Paths.get("data", "tenants", tenantUuid).toAbsolutePath();
        deleteQuietly(tenantDir);

        try {
            form_templates store = form_templates.defaultStore();
            store.ensure(tenantUuid);

            byte[] rtf = "{\\rtf1\\ansi Folder Test}".getBytes(StandardCharsets.UTF_8);

            form_templates.TemplateRec root = store.create(tenantUuid, "Cover Sheet", "", "cover-sheet.rtf", rtf);
            form_templates.TemplateRec nested = store.create(tenantUuid, "Original Petition", "Pleadings/Initial Filings", "petition.rtf", rtf);
            form_templates.TemplateRec normalized = store.create(tenantUuid, "Response", " /Pleadings\\Responses//Drafts/ ", "response.rtf", rtf);

            assertEquals("", root.folderPath);
            assertEquals("Pleadings/Initial Filings", nested.folderPath);
            assertEquals("Pleadings/Responses/Drafts", normalized.folderPath);

            List<form_templates.TemplateRec> all = store.list(tenantUuid);
            assertEquals(3, all.size());

            assertEquals("", all.get(0).folderPath);
            assertEquals("Cover Sheet", all.get(0).label);

            assertEquals("Pleadings/Initial Filings", all.get(1).folderPath);
            assertEquals("Original Petition", all.get(1).label);

            assertEquals("Pleadings/Responses/Drafts", all.get(2).folderPath);
            assertEquals("Response", all.get(2).label);
        } finally {
            deleteQuietly(tenantDir);
        }
    }

    @Test
    void update_meta_can_change_folder_path_and_legacy_update_keeps_existing_folder() throws Exception {
        String tenantUuid = "tmpl-folder-meta-" + UUID.randomUUID();
        Path tenantDir = Paths.get("data", "tenants", tenantUuid).toAbsolutePath();
        deleteQuietly(tenantDir);

        try {
            form_templates store = form_templates.defaultStore();
            store.ensure(tenantUuid);

            byte[] rtf = "{\\rtf1\\ansi Meta Update Test}".getBytes(StandardCharsets.UTF_8);
            form_templates.TemplateRec rec = store.create(tenantUuid, "Template A", "Folder One", "template-a.rtf", rtf);

            boolean moved = store.updateMeta(tenantUuid, rec.uuid, "Template B", "Folder Two/Sub");
            assertTrue(moved);

            form_templates.TemplateRec updated = store.get(tenantUuid, rec.uuid);
            assertEquals("Template B", updated.label);
            assertEquals("Folder Two/Sub", updated.folderPath);

            boolean renamed = store.updateMeta(tenantUuid, rec.uuid, "Template C");
            assertTrue(renamed);

            form_templates.TemplateRec updatedAgain = store.get(tenantUuid, rec.uuid);
            assertEquals("Template C", updatedAgain.label);
            assertEquals("Folder Two/Sub", updatedAgain.folderPath);

            boolean missing = store.updateMeta(tenantUuid, "missing-template", "Nope", "Folder X");
            assertFalse(missing);
        } finally {
            deleteQuietly(tenantDir);
        }
    }

    @Test
    void create_and_replace_support_odt_and_txt_templates() throws Exception {
        String tenantUuid = "tmpl-odt-txt-" + UUID.randomUUID();
        Path tenantDir = Paths.get("data", "tenants", tenantUuid).toAbsolutePath();
        deleteQuietly(tenantDir);

        try {
            form_templates store = form_templates.defaultStore();
            store.ensure(tenantUuid);

            byte[] odt = minimalOdt("Case Label: {{case.label}}");
            byte[] txt = "Case Label: {{case.label}}".getBytes(StandardCharsets.UTF_8);

            form_templates.TemplateRec odtRec = store.create(tenantUuid, "ODT Template", "", "template.odt", odt);
            form_templates.TemplateRec txtRec = store.create(tenantUuid, "TXT Template", "", "template.txt", txt);

            assertEquals("odt", odtRec.fileExt);
            assertEquals("txt", txtRec.fileExt);
            assertTrue(odtRec.sizeBytes > 0);
            assertTrue(txtRec.sizeBytes > 0);

            boolean replacedOdtWithTxt = store.replaceFile(
                    tenantUuid,
                    odtRec.uuid,
                    "template-updated.txt",
                    "Updated {{case.label}}".getBytes(StandardCharsets.UTF_8)
            );
            assertTrue(replacedOdtWithTxt);
            assertEquals("txt", store.get(tenantUuid, odtRec.uuid).fileExt);

            boolean replacedTxtWithOdt = store.replaceFile(tenantUuid, txtRec.uuid, "template-updated.odt", odt);
            assertTrue(replacedTxtWithOdt);
            assertEquals("odt", store.get(tenantUuid, txtRec.uuid).fileExt);
        } finally {
            deleteQuietly(tenantDir);
        }
    }

    private static byte[] minimalOdt(String text) throws Exception {
        String contentXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<office:document-content "
                + "xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\" "
                + "xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\" "
                + "office:version=\"1.2\">"
                + "<office:body><office:text>"
                + "<text:p>" + escapeXml(text) + "</text:p>"
                + "</office:text></office:body></office:document-content>";

        byte[] mimeBytes = "application/vnd.oasis.opendocument.text".getBytes(StandardCharsets.UTF_8);
        byte[] contentBytes = contentXml.getBytes(StandardCharsets.UTF_8);

        ByteArrayOutputStream out = new ByteArrayOutputStream(2048);
        try (out; ZipOutputStream zout = new ZipOutputStream(out)) {
            ZipEntry mime = new ZipEntry("mimetype");
            mime.setMethod(ZipEntry.STORED);
            mime.setSize(mimeBytes.length);
            mime.setCompressedSize(mimeBytes.length);
            CRC32 crc = new CRC32();
            crc.update(mimeBytes);
            mime.setCrc(crc.getValue());
            zout.putNextEntry(mime);
            zout.write(mimeBytes);
            zout.closeEntry();

            ZipEntry content = new ZipEntry("content.xml");
            zout.putNextEntry(content);
            zout.write(contentBytes);
            zout.closeEntry();
        }
        return out.toByteArray();
    }

    private static String escapeXml(String value) {
        String v = value == null ? "" : value;
        return v.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private static void deleteQuietly(Path p) {
        try {
            if (p == null || !Files.exists(p)) return;
            try (var walk = Files.walk(p)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {}
                });
            }
        } catch (Exception ignored) {}
    }
}
