package net.familylawandprobate.controversies;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.familylawandprobate.controversies.storage.CachedDocumentStorageBackend;
import net.familylawandprobate.controversies.storage.DocumentStorageBackend;
import net.familylawandprobate.controversies.storage.EncryptedDocumentStorageBackend;
import net.familylawandprobate.controversies.storage.FilesystemRemoteStorageBackend;
import net.familylawandprobate.controversies.storage.LocalFsStorageBackend;
import net.familylawandprobate.controversies.storage.StorageBackendResolver;
import net.familylawandprobate.controversies.storage.StorageCrypto;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashMap;
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
    void dedup_links_reuse_single_object_across_local_and_external_backends() throws Exception {
        String tenantUuid = "tenant-dedup-shared-" + UUID.randomUUID();
        String matterUuid = "matter-dedup-shared-" + UUID.randomUUID();
        byte[] bytes = "same-content-across-backends".getBytes(StandardCharsets.UTF_8);

        LocalFsStorageBackend local = new LocalFsStorageBackend(tenantUuid, matterUuid, true);
        FilesystemRemoteStorageBackend remote = new FilesystemRemoteStorageBackend("webdav", tenantUuid, 0, 0, true);

        String localKey = local.put("checks/local-copy.txt", bytes);
        String remoteKey = remote.put("checks/remote-copy.txt", bytes);

        Path localPath = Paths.get("data", "tenants", tenantUuid, "matters", matterUuid, "assembled", "files", localKey).toAbsolutePath();
        Path remotePath = Paths.get("data", "tenants", tenantUuid, "assembled_remote", "webdav", remoteKey).toAbsolutePath();
        assertTrue(Files.isSameFile(localPath, remotePath));

        String sha = StorageCrypto.checksumSha256Hex(bytes);
        Path objectPath = Paths.get(
                "data",
                "tenants",
                tenantUuid,
                "dedup_objects",
                "sha256",
                sha.substring(0, 2),
                sha.substring(2, 4),
                sha + ".bin"
        ).toAbsolutePath();
        assertTrue(Files.exists(objectPath));
    }

    @Test
    void dedup_write_fails_closed_when_object_integrity_is_tampered() throws Exception {
        String tenantUuid = "tenant-dedup-tamper-" + UUID.randomUUID();
        String matterUuid = "matter-dedup-tamper-" + UUID.randomUUID();
        byte[] bytes = "tamper-check".getBytes(StandardCharsets.UTF_8);

        LocalFsStorageBackend local = new LocalFsStorageBackend(tenantUuid, matterUuid, true);
        local.put("checks/first.txt", bytes);

        String sha = StorageCrypto.checksumSha256Hex(bytes);
        Path objectPath = Paths.get(
                "data",
                "tenants",
                tenantUuid,
                "dedup_objects",
                "sha256",
                sha.substring(0, 2),
                sha.substring(2, 4),
                sha + ".bin"
        ).toAbsolutePath();
        Files.writeString(objectPath, "corrupted", StandardCharsets.UTF_8);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                local.put("checks/second.txt", bytes)
        );
        assertTrue(ex.getMessage().contains("dedup object integrity check failed"));
    }

    @Test
    void webdav_backend_is_supported_for_remote_storage() throws Exception {
        byte[] bytes = "hello webdav".getBytes(StandardCharsets.UTF_8);
        DocumentStorageBackend backend = new FilesystemRemoteStorageBackend("webdav", "tenant-" + UUID.randomUUID());
        String key = backend.put("checks/webdav.txt", bytes);

        Map<String, String> metadata = backend.metadata(key);
        assertEquals("webdav", metadata.get("backend"));
        assertEquals(StorageCrypto.checksumMd5Hex(bytes), metadata.get("checksum_md5"));
        assertEquals(StorageCrypto.checksumSha256Hex(bytes), metadata.get("checksum_sha256"));
    }

    @Test
    void onedrive_business_backend_is_supported_for_remote_storage() throws Exception {
        byte[] bytes = "hello onedrive".getBytes(StandardCharsets.UTF_8);
        DocumentStorageBackend backend = new FilesystemRemoteStorageBackend("onedrive_business", "tenant-" + UUID.randomUUID());
        String key = backend.put("checks/onedrive.txt", bytes);

        Map<String, String> metadata = backend.metadata(key);
        assertEquals("onedrive_business", metadata.get("backend"));
        assertEquals(StorageCrypto.checksumMd5Hex(bytes), metadata.get("checksum_md5"));
        assertEquals(StorageCrypto.checksumSha256Hex(bytes), metadata.get("checksum_sha256"));
    }

    @Test
    void remote_storage_respects_configured_path_and_filename_limits() throws Exception {
        DocumentStorageBackend backend = new FilesystemRemoteStorageBackend(
                "s3_compatible",
                "tenant-" + UUID.randomUUID(),
                64,
                20
        );
        byte[] bytes = "limited-key".getBytes(StandardCharsets.UTF_8);
        String key = backend.put(
                "matters/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/assemblies/this-is-a-very-very-long-filename-for-external-storage.pdf",
                bytes
        );

        assertTrue(key.length() <= 64);
        String[] segments = key.split("/");
        for (int i = 0; i < segments.length; i++) {
            assertTrue(segments[i].length() <= 20);
        }
        assertEquals("limited-key", new String(backend.get(key), StandardCharsets.UTF_8));
    }

    @Test
    void remote_storage_handles_limit_collisions_non_destructively() throws Exception {
        DocumentStorageBackend backend = new FilesystemRemoteStorageBackend(
                "webdav",
                "tenant-" + UUID.randomUUID(),
                44,
                16
        );

        byte[] first = "payload-one".getBytes(StandardCharsets.UTF_8);
        byte[] second = "payload-two".getBytes(StandardCharsets.UTF_8);
        String firstStoredKey = backend.put("docs/same-prefix-with-very-long-file-name-A.txt", first);
        String secondStoredKey = backend.put("docs/same-prefix-with-very-long-file-name-B.txt", second);

        assertNotEquals(firstStoredKey, secondStoredKey);
        assertTrue(backend.exists(firstStoredKey));
        assertTrue(backend.exists(secondStoredKey));
        assertEquals("payload-one", new String(backend.get(firstStoredKey), StandardCharsets.UTF_8));
        assertEquals("payload-two", new String(backend.get(secondStoredKey), StandardCharsets.UTF_8));
        assertTrue(firstStoredKey.length() <= 44);
        assertTrue(secondStoredKey.length() <= 44);
    }

    @Test
    void remote_storage_reuses_existing_key_for_same_content_under_limits() throws Exception {
        DocumentStorageBackend backend = new FilesystemRemoteStorageBackend(
                "ftp",
                "tenant-" + UUID.randomUUID(),
                40,
                14
        );

        byte[] bytes = "same-content".getBytes(StandardCharsets.UTF_8);
        String firstKey = backend.put("docs/collision-prone-file-name.txt", bytes);
        String secondKey = backend.put("docs/collision-prone-file-name.txt", bytes);

        assertEquals(firstKey, secondKey);
        assertEquals("same-content", new String(backend.get(secondKey), StandardCharsets.UTF_8));
    }

    @Test
    void onedrive_defaults_and_reserved_character_rules_are_applied() throws Exception {
        DocumentStorageBackend backend = new FilesystemRemoteStorageBackend(
                "onedrive_for_business",
                "tenant-" + UUID.randomUUID(),
                0,
                0
        );

        String longSegment = "segment-" + "x".repeat(320);
        String key = backend.put(
                "matters/" + longSegment + "/report#bad%name?:<>|.txt. ",
                "onedrive-rules".getBytes(StandardCharsets.UTF_8)
        );

        assertTrue(key.length() <= 400);
        String[] segments = key.split("/");
        for (int i = 0; i < segments.length; i++) {
            assertTrue(segments[i].length() <= 255);
            assertFalse(segments[i].contains("#"));
            assertFalse(segments[i].contains("%"));
            assertFalse(segments[i].contains("?"));
            assertFalse(segments[i].contains(":"));
            assertFalse(segments[i].contains("<"));
            assertFalse(segments[i].contains(">"));
            assertFalse(segments[i].contains("|"));
            assertFalse(segments[i].endsWith("."));
            assertFalse(segments[i].endsWith(" "));
        }
        assertEquals("onedrive-rules", new String(backend.get(key), StandardCharsets.UTF_8));
    }

    @Test
    void storage_backends_replace_non_alnum_and_non_dot_filename_characters() throws Exception {
        byte[] bytes = "sanitize-filename".getBytes(StandardCharsets.UTF_8);

        DocumentStorageBackend local = new LocalFsStorageBackend("tenant-" + UUID.randomUUID(), "matter-" + UUID.randomUUID());
        String localKey = local.put("checks/File Name-(v1).pdf", bytes);
        assertTrue(localKey.matches("[A-Za-z0-9._/]+"));
        assertFalse(localKey.contains(" "));
        assertFalse(localKey.contains("-"));
        assertFalse(localKey.contains("("));
        assertFalse(localKey.contains(")"));
        assertEquals("sanitize-filename", new String(local.get(localKey), StandardCharsets.UTF_8));

        DocumentStorageBackend remote = new FilesystemRemoteStorageBackend("s3_compatible", "tenant-" + UUID.randomUUID());
        String remoteKey = remote.put("checks/File Name-(v1).pdf", bytes);
        assertTrue(remoteKey.matches("[A-Za-z0-9._/]+"));
        assertFalse(remoteKey.contains(" "));
        assertFalse(remoteKey.contains("-"));
        assertFalse(remoteKey.contains("("));
        assertFalse(remoteKey.contains(")"));
        assertEquals("sanitize-filename", new String(remote.get(remoteKey), StandardCharsets.UTF_8));
    }

    @Test
    void storage_backends_can_read_and_delete_legacy_hyphenated_keys() throws Exception {
        String tenantUuid = "tenant-" + UUID.randomUUID();
        String matterUuid = "matter-" + UUID.randomUUID();

        Path legacyLocalPath = Paths.get("data", "tenants", tenantUuid, "matters", matterUuid, "assembled", "files", "checks", "legacy-file.txt").toAbsolutePath();
        Files.createDirectories(legacyLocalPath.getParent());
        Files.writeString(legacyLocalPath, "legacy-local", StandardCharsets.UTF_8);

        LocalFsStorageBackend local = new LocalFsStorageBackend(tenantUuid, matterUuid);
        assertTrue(local.exists("checks/legacy-file.txt"));
        assertEquals("legacy-local", new String(local.get("checks/legacy-file.txt"), StandardCharsets.UTF_8));
        local.delete("checks/legacy-file.txt");
        assertFalse(Files.exists(legacyLocalPath));

        Path legacyRemotePath = Paths.get("data", "tenants", tenantUuid, "assembled_remote", "webdav", "checks", "legacy-file.txt").toAbsolutePath();
        Files.createDirectories(legacyRemotePath.getParent());
        Files.writeString(legacyRemotePath, "legacy-remote", StandardCharsets.UTF_8);

        FilesystemRemoteStorageBackend remote = new FilesystemRemoteStorageBackend("webdav", tenantUuid);
        assertTrue(remote.exists("checks/legacy-file.txt"));
        assertEquals("legacy-remote", new String(remote.get("checks/legacy-file.txt"), StandardCharsets.UTF_8));
        remote.delete("checks/legacy-file.txt");
        assertFalse(Files.exists(legacyRemotePath));
    }

    @Test
    void resolver_applies_external_path_limits_from_tenant_settings() throws Exception {
        String tenantUuid = "tenant-" + UUID.randomUUID();
        LinkedHashMap<String, String> settings = tenant_settings.defaultStore().read(tenantUuid);
        settings.put("storage_backend", "webdav");
        settings.put("storage_endpoint", "webdav://example.test/storage");
        settings.put("storage_access_key", "ak");
        settings.put("storage_secret", "secret");
        settings.put("storage_max_path_length", "36");
        settings.put("storage_max_filename_length", "12");
        tenant_settings.defaultStore().write(tenantUuid, settings);

        StorageBackendResolver resolver = new StorageBackendResolver();
        DocumentStorageBackend backend = resolver.resolve(tenantUuid, "matter-" + UUID.randomUUID(), "webdav");
        String key = backend.put("matters/long-segment/another-segment/super-long-filename-value.txt", "x".getBytes(StandardCharsets.UTF_8));

        assertTrue(key.length() <= 36);
        String[] segments = key.split("/");
        for (int i = 0; i < segments.length; i++) {
            assertTrue(segments[i].length() <= 12);
        }
    }

    @Test
    void resolver_uses_onedrive_cache_size_setting_for_cached_reads() throws Exception {
        String tenantUuid = "tenant-" + UUID.randomUUID();
        LinkedHashMap<String, String> settings = tenant_settings.defaultStore().read(tenantUuid);
        settings.put("storage_backend", "onedrive_business");
        settings.put("storage_endpoint", "https://graph.microsoft.com/v1.0/drives/test/root:/controversies");
        settings.put("storage_access_key", "client-id");
        settings.put("storage_secret", "client-secret");
        settings.put("storage_cache_size_onedrive_business_mb", "8");
        tenant_settings.defaultStore().write(tenantUuid, settings);

        StorageBackendResolver resolver = new StorageBackendResolver();
        DocumentStorageBackend backend = resolver.resolve(tenantUuid, "matter-" + UUID.randomUUID(), "onedrive_business");
        DocumentStorageBackend remote = new FilesystemRemoteStorageBackend("onedrive_business", tenantUuid);

        String key = backend.put("checks/cache-onedrive.txt", "first".getBytes(StandardCharsets.UTF_8));
        assertEquals("first", new String(backend.get(key), StandardCharsets.UTF_8));

        remote.put(key, "second".getBytes(StandardCharsets.UTF_8));
        assertEquals("first", new String(backend.get(key), StandardCharsets.UTF_8));
    }

    @Test
    void resolver_cache_layer_works_for_all_external_backends() throws Exception {
        String[] backends = new String[] { "ftp", "ftps", "sftp", "webdav", "s3_compatible", "onedrive_business" };
        for (int i = 0; i < backends.length; i++) {
            String backendType = backends[i];
            String tenantUuid = "tenant-cache-" + backendType + "-" + UUID.randomUUID();
            LinkedHashMap<String, String> settings = tenant_settings.defaultStore().read(tenantUuid);
            settings.put("storage_backend", backendType);
            settings.put("storage_endpoint", endpointForBackend(backendType));
            settings.put("storage_access_key", "ak-" + backendType);
            settings.put("storage_secret", "sk-" + backendType);
            settings.put("storage_encryption_mode", "none");
            settings.put("storage_encryption_key", "");
            settings.put("storage_cache_size_ftp_mb", "0");
            settings.put("storage_cache_size_ftps_mb", "0");
            settings.put("storage_cache_size_sftp_mb", "0");
            settings.put("storage_cache_size_webdav_mb", "0");
            settings.put("storage_cache_size_s3_compatible_mb", "0");
            settings.put("storage_cache_size_onedrive_business_mb", "0");
            settings.put(cacheSettingForBackend(backendType), "8");
            tenant_settings.defaultStore().write(tenantUuid, settings);

            StorageBackendResolver resolver = new StorageBackendResolver();
            DocumentStorageBackend resolved = resolver.resolve(tenantUuid, "matter-" + UUID.randomUUID(), backendType);
            DocumentStorageBackend remote = new FilesystemRemoteStorageBackend(backendType, tenantUuid);

            String key = resolved.put("checks/cache-" + backendType + ".txt", "first".getBytes(StandardCharsets.UTF_8));
            assertEquals("first", new String(resolved.get(key), StandardCharsets.UTF_8));

            remote.put(key, "second".getBytes(StandardCharsets.UTF_8));
            assertEquals("first", new String(resolved.get(key), StandardCharsets.UTF_8));
        }
    }

    @Test
    void resolver_cache_source_token_changes_when_root_folder_changes() throws Exception {
        String tenantUuid = "tenant-cache-root-" + UUID.randomUUID();
        String matterUuid = "matter-cache-root-" + UUID.randomUUID();

        LinkedHashMap<String, String> settings = tenant_settings.defaultStore().read(tenantUuid);
        settings.put("storage_backend", "s3_compatible");
        settings.put("storage_endpoint", "https://s3.example.test/bucket");
        settings.put("storage_root_folder", "root-a");
        settings.put("storage_access_key", "ak");
        settings.put("storage_secret", "sk");
        settings.put("storage_cache_size_s3_compatible_mb", "8");
        tenant_settings.defaultStore().write(tenantUuid, settings);

        StorageBackendResolver resolver = new StorageBackendResolver();
        DocumentStorageBackend first = resolver.resolve(tenantUuid, matterUuid, "s3_compatible");
        String key = first.put("checks/root-token.txt", "first".getBytes(StandardCharsets.UTF_8));
        assertEquals("first", new String(first.get(key), StandardCharsets.UTF_8));

        Path cacheRoot = Paths.get("data", "tenants", tenantUuid, "storage_cache").toAbsolutePath();
        long cacheDirsAfterFirst;
        try (Stream<Path> dirs = Files.list(cacheRoot)) {
            cacheDirsAfterFirst = dirs.filter(Files::isDirectory).count();
        }
        assertTrue(cacheDirsAfterFirst >= 1L);

        LinkedHashMap<String, String> updated = tenant_settings.defaultStore().read(tenantUuid);
        updated.put("storage_root_folder", "root-b");
        tenant_settings.defaultStore().write(tenantUuid, updated);

        DocumentStorageBackend second = resolver.resolve(tenantUuid, matterUuid, "s3_compatible");
        assertEquals("first", new String(second.get(key), StandardCharsets.UTF_8));

        long cacheDirsAfterSecond;
        try (Stream<Path> dirs = Files.list(cacheRoot)) {
            cacheDirsAfterSecond = dirs.filter(Files::isDirectory).count();
        }
        assertTrue(cacheDirsAfterSecond > cacheDirsAfterFirst);
    }

    @Test
    void resolver_local_backend_does_not_write_external_cache_files() throws Exception {
        String tenantUuid = "tenant-local-cache-" + UUID.randomUUID();
        String matterUuid = "matter-" + UUID.randomUUID();
        LinkedHashMap<String, String> settings = tenant_settings.defaultStore().read(tenantUuid);
        settings.put("storage_backend", "local");
        settings.put("storage_encryption_mode", "none");
        settings.put("storage_encryption_key", "");
        settings.put("storage_cache_size_ftp_mb", "8");
        settings.put("storage_cache_size_ftps_mb", "8");
        settings.put("storage_cache_size_sftp_mb", "8");
        settings.put("storage_cache_size_webdav_mb", "8");
        settings.put("storage_cache_size_s3_compatible_mb", "8");
        settings.put("storage_cache_size_onedrive_business_mb", "8");
        tenant_settings.defaultStore().write(tenantUuid, settings);

        StorageBackendResolver resolver = new StorageBackendResolver();
        DocumentStorageBackend local = resolver.resolve(tenantUuid, matterUuid, "local");
        String key = local.put("checks/local-cache-none.txt", "local".getBytes(StandardCharsets.UTF_8));
        assertEquals("local", new String(local.get(key), StandardCharsets.UTF_8));

        Path cacheRoot = Paths.get("data", "tenants", tenantUuid, "storage_cache").toAbsolutePath();
        assertFalse(Files.exists(cacheRoot));
    }

    @Test
    void resolver_can_disable_dedup_links_and_keep_physical_copies_separate() throws Exception {
        String tenantUuid = "tenant-dedup-disabled-" + UUID.randomUUID();
        String matterUuid = "matter-dedup-disabled-" + UUID.randomUUID();
        LinkedHashMap<String, String> settings = tenant_settings.defaultStore().read(tenantUuid);
        settings.put("storage_backend", "local");
        settings.put("storage_dedup_links_enabled", "false");
        tenant_settings.defaultStore().write(tenantUuid, settings);

        StorageBackendResolver resolver = new StorageBackendResolver();
        DocumentStorageBackend local = resolver.resolve(tenantUuid, matterUuid, "local");
        String first = local.put("checks/a.txt", "same".getBytes(StandardCharsets.UTF_8));
        String second = local.put("checks/b.txt", "same".getBytes(StandardCharsets.UTF_8));

        Path firstPath = Paths.get("data", "tenants", tenantUuid, "matters", matterUuid, "assembled", "files", first).toAbsolutePath();
        Path secondPath = Paths.get("data", "tenants", tenantUuid, "matters", matterUuid, "assembled", "files", second).toAbsolutePath();
        assertFalse(Files.isSameFile(firstPath, secondPath));
    }

    @Test
    void onedrive_business_backend_works_with_tenant_managed_encryption() throws Exception {
        String tenantUuid = "tenant-" + UUID.randomUUID();
        String matterUuid = "matter-" + UUID.randomUUID();
        LinkedHashMap<String, String> settings = tenant_settings.defaultStore().read(tenantUuid);
        settings.put("storage_backend", "onedrive_business");
        settings.put("storage_endpoint", "https://graph.microsoft.com/v1.0/drives/test/root:/controversies");
        settings.put("storage_access_key", "client-id");
        settings.put("storage_secret", "client-secret");
        settings.put("storage_encryption_mode", "tenant_managed");
        settings.put("storage_encryption_key", "tenant-key-123");
        tenant_settings.defaultStore().write(tenantUuid, settings);

        StorageBackendResolver resolver = new StorageBackendResolver();
        DocumentStorageBackend backend = resolver.resolve(tenantUuid, matterUuid, "onedrive_business");
        DocumentStorageBackend remote = new FilesystemRemoteStorageBackend("onedrive_business", tenantUuid);

        byte[] plaintext = "onedrive encrypted payload".getBytes(StandardCharsets.UTF_8);
        String key = backend.put("checks/encrypted-onedrive.txt", plaintext);
        byte[] stored = remote.get(key);
        assertFalse(Arrays.equals(plaintext, stored));
        assertEquals("onedrive encrypted payload", new String(backend.get(key), StandardCharsets.UTF_8));
    }

    @Test
    void local_dedup_with_tenant_managed_encryption_stores_ciphertext_and_reads_plaintext() throws Exception {
        String tenantUuid = "tenant-encrypted-local-" + UUID.randomUUID();
        String matterUuid = "matter-encrypted-local-" + UUID.randomUUID();
        LinkedHashMap<String, String> settings = tenant_settings.defaultStore().read(tenantUuid);
        settings.put("storage_backend", "local");
        settings.put("storage_encryption_mode", "tenant_managed");
        settings.put("storage_encryption_key", "tenant-key-local-123");
        settings.put("storage_dedup_links_enabled", "true");
        tenant_settings.defaultStore().write(tenantUuid, settings);

        StorageBackendResolver resolver = new StorageBackendResolver();
        DocumentStorageBackend encrypted = resolver.resolve(tenantUuid, matterUuid, "local");
        LocalFsStorageBackend rawLocal = new LocalFsStorageBackend(tenantUuid, matterUuid, true);

        byte[] plaintext = "local encrypted payload".getBytes(StandardCharsets.UTF_8);
        String key = encrypted.put("checks/encrypted-local.txt", plaintext);
        byte[] stored = rawLocal.get(key);

        assertFalse(Arrays.equals(plaintext, stored));
        assertTrue(stored.length >= 4);
        assertEquals("CVE1", new String(Arrays.copyOfRange(stored, 0, 4), StandardCharsets.UTF_8));
        assertEquals("local encrypted payload", new String(encrypted.get(key), StandardCharsets.UTF_8));

        String sha = StorageCrypto.checksumSha256Hex(stored);
        Path objectPath = Paths.get(
                "data",
                "tenants",
                tenantUuid,
                "dedup_objects",
                "sha256",
                sha.substring(0, 2),
                sha.substring(2, 4),
                sha + ".bin"
        ).toAbsolutePath();
        assertTrue(Files.exists(objectPath));
    }

    @Test
    void local_dedup_encrypted_blob_tamper_fails_on_read() throws Exception {
        String tenantUuid = "tenant-encrypted-tamper-" + UUID.randomUUID();
        String matterUuid = "matter-encrypted-tamper-" + UUID.randomUUID();
        LinkedHashMap<String, String> settings = tenant_settings.defaultStore().read(tenantUuid);
        settings.put("storage_backend", "local");
        settings.put("storage_encryption_mode", "tenant_managed");
        settings.put("storage_encryption_key", "tenant-key-tamper-123");
        settings.put("storage_dedup_links_enabled", "true");
        tenant_settings.defaultStore().write(tenantUuid, settings);

        StorageBackendResolver resolver = new StorageBackendResolver();
        DocumentStorageBackend encrypted = resolver.resolve(tenantUuid, matterUuid, "local");
        String key = encrypted.put("checks/encrypted-tamper.txt", "sensitive".getBytes(StandardCharsets.UTF_8));

        Path refPath = Paths.get(
                "data",
                "tenants",
                tenantUuid,
                "matters",
                matterUuid,
                "assembled",
                "files",
                key
        ).toAbsolutePath();
        Files.write(refPath, "tampered".getBytes(StandardCharsets.UTF_8));

        assertThrows(Exception.class, () -> encrypted.get(key));
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

    private static String endpointForBackend(String backendType) {
        String b = backendType == null ? "" : backendType.trim().toLowerCase();
        if ("onedrive_business".equals(b)) {
            return "https://graph.microsoft.com/v1.0/drives/test/root:/controversies";
        }
        if ("webdav".equals(b)) {
            return "https://webdav.example.test/storage";
        }
        if ("s3_compatible".equals(b)) {
            return "https://s3.example.test/bucket";
        }
        return b + "://example.test/storage";
    }

    private static String cacheSettingForBackend(String backendType) {
        String b = backendType == null ? "" : backendType.trim().toLowerCase();
        return switch (b) {
            case "ftp" -> "storage_cache_size_ftp_mb";
            case "ftps" -> "storage_cache_size_ftps_mb";
            case "sftp" -> "storage_cache_size_sftp_mb";
            case "webdav" -> "storage_cache_size_webdav_mb";
            case "s3_compatible" -> "storage_cache_size_s3_compatible_mb";
            case "onedrive_business" -> "storage_cache_size_onedrive_business_mb";
            default -> "";
        };
    }
}
