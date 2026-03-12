package net.familylawandprobate.controversies;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class document_editor_service_test {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ArrayList<Path> cleanupRoots = new ArrayList<Path>();
    private final ArrayList<String> touchedProps = new ArrayList<String>();

    @AfterEach
    void cleanup() throws Exception {
        for (Path root : cleanupRoots) {
            deleteRecursively(root);
        }
        cleanupRoots.clear();
        for (String key : touchedProps) {
            System.clearProperty(key);
        }
        touchedProps.clear();
    }

    @Test
    void prepares_onlyoffice_launch_for_docx_version() throws Exception {
        setProperty("controversies.onlyoffice.docserver.url", "https://docs.example.test");
        Fixture fixture = createFixture("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "Original docx bytes".getBytes(StandardCharsets.UTF_8));
        document_editor_service svc = document_editor_service.defaultService();

        document_editor_service.LaunchResult launch = svc.prepareOnlyOfficeLaunch(
                fixture.tenantUuid,
                fixture.matterUuid,
                fixture.docUuid,
                fixture.partUuid,
                fixture.versionUuid,
                "editor@example.test",
                "Editor User",
                "https://app.example.test"
        );

        assertNotNull(launch);
        assertTrue(launch.ok);
        assertEquals("onlyoffice", launch.provider);
        assertEquals("docx", launch.fileType);
        assertEquals("https://docs.example.test/web-apps/apps/api/documents/api.js", launch.scriptUrl);
        assertTrue(!safe(launch.token).isBlank());
        assertTrue(!safe(launch.configJson).isBlank());

        LinkedHashMap<String, Object> cfg = JSON.readValue(
                launch.configJson,
                new TypeReference<LinkedHashMap<String, Object>>() {}
        );
        LinkedHashMap<String, Object> doc = map(cfg.get("document"));
        LinkedHashMap<String, Object> editorConfig = map(cfg.get("editorConfig"));
        assertEquals("docx", safe(doc.get("fileType")));
        assertTrue(safe(doc.get("url")).contains("/version_editor/file?token="));
        assertTrue(safe(editorConfig.get("callbackUrl")).startsWith("https://app.example.test/version_editor/callback?token="));

        document_editor_service.FileAccessResult file = svc.resolveEditorFile(launch.token);
        assertNotNull(file);
        assertEquals(fixture.sourcePath.toAbsolutePath().normalize(), file.filePath.toAbsolutePath().normalize());
    }

    @Test
    void normalizes_rtx_to_rtf_for_editor_serving() throws Exception {
        setProperty("controversies.onlyoffice.docserver.url", "https://docs.example.test");
        byte[] bytes = "{\\rtf1\\ansi Test}".getBytes(StandardCharsets.UTF_8);
        Fixture fixture = createFixture("rtx", "text/richtext", bytes);
        document_editor_service svc = document_editor_service.defaultService();

        document_editor_service.LaunchResult launch = svc.prepareOnlyOfficeLaunch(
                fixture.tenantUuid,
                fixture.matterUuid,
                fixture.docUuid,
                fixture.partUuid,
                fixture.versionUuid,
                "editor@example.test",
                "Editor User",
                "https://app.example.test"
        );

        assertTrue(launch.ok);
        assertEquals("rtf", launch.fileType);
        document_editor_service.FileAccessResult file = svc.resolveEditorFile(launch.token);
        assertNotNull(file.filePath);
        assertEquals("rtf", extension(file.filePath));
        assertTrue(file.filePath.toAbsolutePath().normalize().toString().contains("data/sec/editor_sessions/files"));
        assertArrayEquals(bytes, Files.readAllBytes(file.filePath));
    }

    @Test
    void callback_saves_new_version_and_skips_duplicate_payload() throws Exception {
        setProperty("controversies.onlyoffice.docserver.url", "https://docs.example.test");
        Fixture fixture = createFixture("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "Original v1".getBytes(StandardCharsets.UTF_8));
        document_editor_service svc = document_editor_service.defaultService();
        document_editor_service.LaunchResult launch = svc.prepareOnlyOfficeLaunch(
                fixture.tenantUuid,
                fixture.matterUuid,
                fixture.docUuid,
                fixture.partUuid,
                fixture.versionUuid,
                "editor@example.test",
                "Editor User",
                "https://app.example.test"
        );
        assertTrue(launch.ok);

        byte[] updated = "Edited bytes from callback".getBytes(StandardCharsets.UTF_8);
        String dataUrl = "data:application/vnd.openxmlformats-officedocument.wordprocessingml.document;base64,"
                + Base64.getEncoder().encodeToString(updated);
        LinkedHashMap<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("status", 2);
        payload.put("url", dataUrl);
        payload.put("filetype", "docx");
        payload.put("key", "k1");

        document_editor_service.CallbackResult first = svc.handleOnlyOfficeCallback(launch.token, payload);
        assertNotNull(first);
        assertTrue(first.saved);
        assertTrue(!safe(first.versionUuid).isBlank());

        List<part_versions.VersionRec> rows = part_versions.defaultStore().listAll(
                fixture.tenantUuid, fixture.matterUuid, fixture.docUuid, fixture.partUuid
        );
        assertEquals(2, rows.size());
        assertEquals("wysiwyg.onlyoffice", safe(rows.get(0).source));
        assertEquals(sha256(updated), safe(rows.get(0).checksum));

        document_editor_service.CallbackResult second = svc.handleOnlyOfficeCallback(launch.token, payload);
        assertNotNull(second);
        assertFalse(second.saved);
        List<part_versions.VersionRec> after = part_versions.defaultStore().listAll(
                fixture.tenantUuid, fixture.matterUuid, fixture.docUuid, fixture.partUuid
        );
        assertEquals(2, after.size());
    }

    private Fixture createFixture(String extension, String mimeType, byte[] sourceBytes) throws Exception {
        String tenant = "tenant-editor-" + UUID.randomUUID();
        String matter = "matter-editor-" + UUID.randomUUID();
        cleanupRoots.add(Paths.get("data", "tenants", tenant).toAbsolutePath());
        cleanupRoots.add(Paths.get("data", "sec", "editor_sessions").toAbsolutePath());

        documents.DocumentRec doc = documents.defaultStore().create(
                tenant,
                matter,
                "Editor Source",
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
        Path versionDir = partFolder.resolve("version_files");
        Files.createDirectories(versionDir);
        Path source = versionDir.resolve(UUID.randomUUID().toString().replace("-", "_") + "__source." + extension);
        Files.write(source, sourceBytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);

        part_versions.VersionRec created = part_versions.defaultStore().create(
                tenant,
                matter,
                doc.uuid,
                part.uuid,
                "Original " + extension.toUpperCase(Locale.ROOT),
                "uploaded",
                mimeType,
                sha256(sourceBytes),
                String.valueOf(sourceBytes.length),
                source.toUri().toString(),
                "Tester",
                "",
                true
        );

        Fixture f = new Fixture();
        f.tenantUuid = tenant;
        f.matterUuid = matter;
        f.docUuid = doc.uuid;
        f.partUuid = part.uuid;
        f.versionUuid = created.uuid;
        f.sourcePath = source;
        return f;
    }

    private void setProperty(String key, String value) {
        System.setProperty(key, value);
        touchedProps.add(key);
    }

    private static LinkedHashMap<String, Object> map(Object value) {
        if (value instanceof LinkedHashMap<?, ?> m) {
            LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), e.getValue());
            }
            return out;
        }
        return new LinkedHashMap<String, Object>();
    }

    private static String extension(Path p) {
        if (p == null || p.getFileName() == null) return "";
        String name = safe(p.getFileName().toString());
        int idx = name.lastIndexOf('.');
        if (idx < 0 || idx + 1 >= name.length()) return "";
        return name.substring(idx + 1).toLowerCase(Locale.ROOT);
    }

    private static String sha256(byte[] raw) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(raw == null ? new byte[0] : raw);
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) sb.append(String.format(Locale.ROOT, "%02x", b));
        return sb.toString();
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

    private static String safe(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static final class Fixture {
        String tenantUuid;
        String matterUuid;
        String docUuid;
        String partUuid;
        String versionUuid;
        Path sourcePath;
    }
}
