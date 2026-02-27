package net.familylawandprobate.controversies;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.familylawandprobate.controversies.storage.DocumentStorageBackend;
import net.familylawandprobate.controversies.storage.EncryptedDocumentStorageBackend;
import net.familylawandprobate.controversies.storage.FilesystemRemoteStorageBackend;
import net.familylawandprobate.controversies.storage.LocalFsStorageBackend;
import net.familylawandprobate.controversies.storage.StorageCrypto;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

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
}
