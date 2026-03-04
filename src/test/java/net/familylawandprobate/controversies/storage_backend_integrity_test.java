package net.familylawandprobate.controversies;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import net.familylawandprobate.controversies.storage.CachedDocumentStorageBackend;
import net.familylawandprobate.controversies.storage.DocumentStorageBackend;
import net.familylawandprobate.controversies.storage.EncryptedDocumentStorageBackend;
import net.familylawandprobate.controversies.storage.FilesystemRemoteStorageBackend;
import net.familylawandprobate.controversies.storage.LocalFsStorageBackend;
import net.familylawandprobate.controversies.storage.StorageCrypto;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

public class storage_backend_integrity_test {

    @Test
    void local_metadata_includes_md5_and_sha256_checksums() throws Exception {
        byte[] bytes = "hello local".getBytes(StandardCharsets.UTF_8);
        DocumentStorageBackend backend = new LocalFsStorageBackend("tenant-" + UUID.randomUUID(), "matter-" + UUID.randomUUID());
        String key = backend.put("checks/local.txt", bytes);

        Map<String, String> metadata = backend.metadata(key);
        assertEquals(StorageCrypto.checksumMd5Hex(bytes), metadata.get("checksum_md5"));
        assertEquals(StorageCrypto.checksumSha256Hex(bytes), metadata.get("checksum_sha256"));
    }

    @Test
    void remote_metadata_includes_md5_and_sha256_checksums() throws Exception {
        byte[] bytes = "hello remote".getBytes(StandardCharsets.UTF_8);
        DocumentStorageBackend backend = new FilesystemRemoteStorageBackend("s3_compatible", "tenant-" + UUID.randomUUID());
        String key = backend.put("checks/remote.txt", bytes);

        Map<String, String> metadata = backend.metadata(key);
        assertEquals(StorageCrypto.checksumMd5Hex(bytes), metadata.get("checksum_md5"));
        assertEquals(StorageCrypto.checksumSha256Hex(bytes), metadata.get("checksum_sha256"));
    }

    @Test
    void encrypted_metadata_reports_plaintext_md5() throws Exception {
        byte[] bytes = "hello encrypted".getBytes(StandardCharsets.UTF_8);
        DocumentStorageBackend delegate = new LocalFsStorageBackend("tenant-" + UUID.randomUUID(), "matter-" + UUID.randomUUID());
        DocumentStorageBackend backend = new EncryptedDocumentStorageBackend(delegate, "tenant_managed", "k1", "none", "");
        String key = backend.put("checks/encrypted.txt", bytes);

        Map<String, String> metadata = backend.metadata(key);
        assertEquals(StorageCrypto.checksumMd5Hex(bytes), metadata.get("checksum_md5"));
        assertEquals(StorageCrypto.checksumSha256Hex(bytes), metadata.get("checksum_sha256"));
    }

    @Test
    void cache_layer_serves_reads_after_remote_object_changes() throws Exception {
        String tenantUuid = "tenant-" + UUID.randomUUID();
        String sourceToken = "s3_compatible_default";
        DocumentStorageBackend remote = new FilesystemRemoteStorageBackend("s3_compatible", tenantUuid);
        DocumentStorageBackend cached = new CachedDocumentStorageBackend(remote, tenantUuid, sourceToken, 1024L * 1024L);

        String key = remote.put("checks/cache-hit.txt", "first".getBytes(StandardCharsets.UTF_8));
        assertEquals("first", new String(cached.get(key), StandardCharsets.UTF_8));

        remote.put(key, "second".getBytes(StandardCharsets.UTF_8));
        assertEquals("first", new String(cached.get(key), StandardCharsets.UTF_8));
    }

    @Test
    void cache_layer_enforces_max_size_eviction() throws Exception {
        String tenantUuid = "tenant-" + UUID.randomUUID();
        String sourceToken = "sftp_default";
        long maxBytes = 32L;
        DocumentStorageBackend remote = new FilesystemRemoteStorageBackend("sftp", tenantUuid);
        DocumentStorageBackend cached = new CachedDocumentStorageBackend(remote, tenantUuid, sourceToken, maxBytes);

        String firstKey = cached.put("checks/first.bin", "12345678901234567890".getBytes(StandardCharsets.UTF_8));
        String secondKey = cached.put("checks/second.bin", "abcdefghijabcdefghij".getBytes(StandardCharsets.UTF_8));
        assertEquals("abcdefghijabcdefghij", new String(cached.get(secondKey), StandardCharsets.UTF_8));

        Path cacheRoot = Paths.get("data", "tenants", tenantUuid, "storage_cache", sourceToken).toAbsolutePath();
        long totalBytes = 0L;
        if (Files.exists(cacheRoot)) {
            try (Stream<Path> walk = Files.walk(cacheRoot)) {
                totalBytes = walk.filter(Files::isRegularFile).mapToLong(p -> {
                    try { return Files.size(p); } catch (Exception ignored) { return 0L; }
                }).sum();
            }
        }
        assertFalse(totalBytes > maxBytes);
        assertEquals("12345678901234567890", new String(remote.get(firstKey), StandardCharsets.UTF_8));
    }
}
