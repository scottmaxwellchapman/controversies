package net.familylawandprobate.controversies;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class version_upload_service_test {

    private final ArrayList<Path> cleanupRoots = new ArrayList<Path>();

    @AfterEach
    void cleanup() throws Exception {
        for (Path root : cleanupRoots) {
            deleteRecursively(root);
        }
    }

    @Test
    void stores_chunks_and_commits_version_file() throws Exception {
        String tenant = "tenant-upload-" + UUID.randomUUID();
        String matter = "matter-upload-" + UUID.randomUUID();
        cleanupRoots.add(Paths.get("data", "tenants", tenant).toAbsolutePath());

        documents.DocumentRec doc = documents.defaultStore().create(
                tenant,
                matter,
                "Upload Source",
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

        version_upload_service svc = version_upload_service.defaultService();
        byte[] allBytes = sampleBytes(550_000);
        int chunkSize = 64 * 1024;
        int totalChunks = (int) Math.ceil(allBytes.length / (double) chunkSize);
        String uploadId = "u" + UUID.randomUUID().toString().replace("-", "");

        for (int i = 0; i < totalChunks; i++) {
            int start = i * chunkSize;
            int end = Math.min(allBytes.length, start + chunkSize);
            byte[] chunk = Arrays.copyOfRange(allBytes, start, end);
            version_upload_service.ChunkResult stored = svc.storeChunk(
                    tenant,
                    matter,
                    doc.uuid,
                    part.uuid,
                    uploadId,
                    i,
                    totalChunks,
                    sha256(chunk),
                    chunk
            );
            assertEquals(i, stored.chunkIndex);
            assertEquals(chunk.length, stored.bytesWritten);
        }

        version_upload_service.CommitRequest req = new version_upload_service.CommitRequest();
        req.uploadId = uploadId;
        req.totalChunks = totalChunks;
        req.expectedFileSha256 = sha256(allBytes);
        req.expectedBytes = allBytes.length;
        req.uploadFileName = "test upload-(v1).pdf";
        req.versionLabel = "Uploaded v1";
        req.source = "uploaded";
        req.mimeType = "application/pdf";
        req.createdBy = "Tester";
        req.notes = "chunked";
        req.makeCurrent = true;

        version_upload_service.CommitResult committed = svc.commitUpload(tenant, matter, doc.uuid, part.uuid, req);
        assertNotNull(committed);
        assertEquals(allBytes.length, committed.assembledBytes);
        assertEquals(sha256(allBytes), committed.checksum);
        assertNotNull(committed.storagePath);
        assertTrue(Files.exists(committed.storagePath));
        assertArrayEquals(allBytes, Files.readAllBytes(committed.storagePath));
        String storedName = committed.storagePath.getFileName() == null ? "" : committed.storagePath.getFileName().toString();
        assertTrue(storedName.matches("[A-Za-z0-9._]+"));
        assertFalse(storedName.contains("-"));
        assertFalse(storedName.contains(" "));
        assertFalse(storedName.contains("("));
        assertFalse(storedName.contains(")"));

        List<part_versions.VersionRec> rows = part_versions.defaultStore().listAll(tenant, matter, doc.uuid, part.uuid);
        assertEquals(1, rows.size());
        assertEquals("Uploaded v1", rows.get(0).versionLabel);
        assertEquals("application/pdf", rows.get(0).mimeType);
        assertEquals(sha256(allBytes), rows.get(0).checksum);
        Path staging = document_parts.defaultStore().partFolder(tenant, matter, doc.uuid, part.uuid).resolve(".upload_staging").resolve(uploadId);
        assertTrue(!Files.exists(staging));
    }

    @Test
    void rejects_chunk_when_checksum_is_wrong() throws Exception {
        String tenant = "tenant-upload-bad-chunk-" + UUID.randomUUID();
        String matter = "matter-upload-bad-chunk-" + UUID.randomUUID();
        cleanupRoots.add(Paths.get("data", "tenants", tenant).toAbsolutePath());

        documents.DocumentRec doc = documents.defaultStore().create(
                tenant, matter, "Doc", "pleading", "motion", "draft", "Tester", "work_product", "", "", ""
        );
        document_parts.PartRec part = document_parts.defaultStore().create(
                tenant, matter, doc.uuid, "Main", "lead", "1", "", "Tester", ""
        );
        version_upload_service svc = version_upload_service.defaultService();
        byte[] chunk = "hello".getBytes(StandardCharsets.UTF_8);

        assertThrows(IllegalArgumentException.class, () -> svc.storeChunk(
                tenant,
                matter,
                doc.uuid,
                part.uuid,
                "badsha",
                0,
                1,
                "not-a-real-sha",
                chunk
        ));
    }

    @Test
    void rejects_commit_when_chunk_missing() throws Exception {
        String tenant = "tenant-upload-missing-" + UUID.randomUUID();
        String matter = "matter-upload-missing-" + UUID.randomUUID();
        cleanupRoots.add(Paths.get("data", "tenants", tenant).toAbsolutePath());

        documents.DocumentRec doc = documents.defaultStore().create(
                tenant, matter, "Doc", "pleading", "motion", "draft", "Tester", "work_product", "", "", ""
        );
        document_parts.PartRec part = document_parts.defaultStore().create(
                tenant, matter, doc.uuid, "Main", "lead", "1", "", "Tester", ""
        );

        version_upload_service svc = version_upload_service.defaultService();
        byte[] first = "first".getBytes(StandardCharsets.UTF_8);
        svc.storeChunk(tenant, matter, doc.uuid, part.uuid, "upmissing", 0, 2, sha256(first), first);

        version_upload_service.CommitRequest req = new version_upload_service.CommitRequest();
        req.uploadId = "upmissing";
        req.totalChunks = 2;
        req.versionLabel = "Broken Upload";
        req.source = "uploaded";
        req.mimeType = "application/octet-stream";
        req.expectedBytes = 10L;
        req.makeCurrent = true;

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> svc.commitUpload(tenant, matter, doc.uuid, part.uuid, req));
        assertTrue(ex.getMessage().toLowerCase(Locale.ROOT).contains("missing chunk"));
    }

    private static byte[] sampleBytes(int n) {
        byte[] out = new byte[n];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) ((i * 31 + 7) & 0xFF);
        }
        return out;
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
}
