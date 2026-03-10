package net.familylawandprobate.controversies;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
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

public class api_image_hash_similarity_test {

    private final ArrayList<Path> cleanupRoots = new ArrayList<Path>();

    @AfterEach
    void cleanup() throws Exception {
        for (Path root : cleanupRoots) {
            deleteRecursively(root);
        }
    }

    @Test
    void find_similar_uses_hashes_for_duplicate_detection_and_scope() throws Exception {
        String tenant = "tenant-hash-sim-" + UUID.randomUUID();
        cleanupRoots.add(Paths.get("data", "tenants", tenant).toAbsolutePath());

        matters.MatterRec matterARec = matters.defaultStore().create(tenant, "Hash Matter A", "", "", "", "", "");
        matters.MatterRec matterBRec = matters.defaultStore().create(tenant, "Hash Matter B", "", "", "", "", "");
        String matterA = matterARec.uuid;
        String matterB = matterBRec.uuid;

        byte[] pdfBytes = buildPdf(
                "Image hash similarity baseline text with enough embedded glyph content to avoid OCR fallback."
        );

        VersionFixture source = createPdfVersion(tenant, matterA, "Source Doc", "Source Part", "v1", pdfBytes);
        VersionFixture duplicateSameMatter = createPdfVersion(tenant, matterA, "Duplicate A", "Duplicate Part A", "v1-dup-a", pdfBytes);
        VersionFixture duplicateOtherMatter = createPdfVersion(tenant, matterB, "Duplicate B", "Duplicate Part B", "v1-dup-b", pdfBytes);

        LinkedHashMap<String, Object> matterScope = new LinkedHashMap<String, Object>();
        matterScope.put("matter_uuid", source.matterUuid);
        matterScope.put("doc_uuid", source.docUuid);
        matterScope.put("part_uuid", source.partUuid);
        matterScope.put("source_version_uuid", source.versionUuid);
        matterScope.put("scope", "matter");
        matterScope.put("duplicates_only", true);
        matterScope.put("max_hamming_distance", 0);
        matterScope.put("max_results", 20);

        LinkedHashMap<String, Object> matterScopedOut = invokeApi("document.versions.find_similar", matterScope, tenant);
        assertEquals(1, asInt(matterScopedOut.get("count")));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> matterItems = (List<Map<String, Object>>) matterScopedOut.get("items");
        assertNotNull(matterItems);
        assertFalse(matterItems.isEmpty());
        assertEquals(duplicateSameMatter.versionUuid, String.valueOf(matterItems.get(0).get("version_uuid")));
        assertTrue(Boolean.parseBoolean(String.valueOf(matterItems.get(0).get("duplicate_candidate"))));

        LinkedHashMap<String, Object> tenantScope = new LinkedHashMap<String, Object>();
        tenantScope.put("matter_uuid", source.matterUuid);
        tenantScope.put("doc_uuid", source.docUuid);
        tenantScope.put("part_uuid", source.partUuid);
        tenantScope.put("source_version_uuid", source.versionUuid);
        tenantScope.put("scope", "tenant");
        tenantScope.put("duplicates_only", true);
        tenantScope.put("max_hamming_distance", 0);
        tenantScope.put("max_results", 20);

        LinkedHashMap<String, Object> tenantScopedOut = invokeApi("document.versions.find_similar", tenantScope, tenant);
        assertTrue(asInt(tenantScopedOut.get("count")) >= 2);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tenantItems = (List<Map<String, Object>>) tenantScopedOut.get("items");
        assertNotNull(tenantItems);
        boolean sawMatterB = false;
        for (Map<String, Object> row : tenantItems) {
            if (row == null) continue;
            if (duplicateOtherMatter.versionUuid.equals(String.valueOf(row.get("version_uuid")))) {
                sawMatterB = true;
                break;
            }
        }
        assertTrue(sawMatterB);
    }

    private static final class VersionFixture {
        final String matterUuid;
        final String docUuid;
        final String partUuid;
        final String versionUuid;

        VersionFixture(String matterUuid, String docUuid, String partUuid, String versionUuid) {
            this.matterUuid = matterUuid;
            this.docUuid = docUuid;
            this.partUuid = partUuid;
            this.versionUuid = versionUuid;
        }
    }

    private static VersionFixture createPdfVersion(String tenant,
                                                   String matterUuid,
                                                   String title,
                                                   String partLabel,
                                                   String versionLabel,
                                                   byte[] pdfBytes) throws Exception {
        documents.DocumentRec doc = documents.defaultStore().create(
                tenant,
                matterUuid,
                title,
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
                matterUuid,
                doc.uuid,
                partLabel,
                "lead",
                "1",
                "",
                "Tester",
                ""
        );
        Path partFolder = document_parts.defaultStore().partFolder(tenant, matterUuid, doc.uuid, part.uuid);
        assertNotNull(partFolder);
        Path versionDir = partFolder.resolve("version_files");
        Files.createDirectories(versionDir);
        Path path = versionDir.resolve(UUID.randomUUID().toString().replace("-", "_") + "__source.pdf");
        Files.write(path, pdfBytes);

        part_versions.VersionRec version = part_versions.defaultStore().create(
                tenant,
                matterUuid,
                doc.uuid,
                part.uuid,
                versionLabel,
                "uploaded",
                "application/pdf",
                pdf_redaction_service.sha256(path),
                String.valueOf(Files.size(path)),
                path.toUri().toString(),
                "Tester",
                "",
                true
        );
        assertNotNull(version);
        return new VersionFixture(matterUuid, doc.uuid, part.uuid, version.uuid);
    }

    private static byte[] buildPdf(String line) throws Exception {
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(PDType1Font.HELVETICA, 12f);
                cs.newLineAtOffset(72f, 720f);
                cs.showText(line == null ? "" : line);
                cs.endText();
            }
            doc.save(out);
            return out.toByteArray();
        }
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

    private static void deleteRecursively(Path root) throws Exception {
        if (root == null || !Files.exists(root)) return;
        Files.walk(root)
                .sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                    }
                });
    }
}
