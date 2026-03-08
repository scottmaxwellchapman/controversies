package net.familylawandprobate.controversies;

import net.familylawandprobate.controversies.storage.StorageCrypto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class external_storage_data_sync_test {

    private static final String DATA_ROOT_PROP = "controversies.data.root";

    @TempDir
    Path tempDir;

    @Test
    void backup_if_due_skips_when_last_snapshot_is_recent() throws Exception {
        Path dataRoot = tempDir.resolve("data");
        Path sourceRoot = tempDir.resolve("source");
        Files.createDirectories(dataRoot);
        Files.createDirectories(sourceRoot);
        Files.writeString(dataRoot.resolve("a.txt"), "alpha", StandardCharsets.UTF_8);

        String oldDataRoot = System.getProperty(DATA_ROOT_PROP);
        try {
            System.setProperty(DATA_ROOT_PROP, dataRoot.toString());
            external_storage_data_sync service = external_storage_data_sync.defaultService();
            String tenantUuid = "tenant-" + UUID.randomUUID();

            external_storage_data_sync.BackupResult first = service.backupIfDueForConfig(
                    tenantUuid,
                    "s3_compatible",
                    sourceRoot.toUri().toString(),
                    "key",
                    "secret",
                    ""
            );
            assertTrue(first.ok);
            assertFalse(first.skipped);
            assertFalse(first.snapshotId.isBlank());

            external_storage_data_sync.BackupResult second = service.backupIfDueForConfig(
                    tenantUuid,
                    "s3_compatible",
                    sourceRoot.toUri().toString(),
                    "key",
                    "secret",
                    ""
            );
            assertTrue(second.ok);
            assertTrue(second.skipped);
            assertEquals(first.snapshotId, second.snapshotId);
        } finally {
            if (oldDataRoot == null) System.clearProperty(DATA_ROOT_PROP);
            else System.setProperty(DATA_ROOT_PROP, oldDataRoot);
        }
    }

    @Test
    void restore_fails_on_corrupt_snapshot_and_keeps_local_data_unchanged() throws Exception {
        Path dataRoot = tempDir.resolve("data-corrupt");
        Path sourceRoot = tempDir.resolve("source-corrupt");
        Files.createDirectories(dataRoot);
        Files.createDirectories(sourceRoot);
        Path marker = dataRoot.resolve("marker.txt");
        Files.writeString(marker, "before-backup", StandardCharsets.UTF_8);

        String oldDataRoot = System.getProperty(DATA_ROOT_PROP);
        try {
            System.setProperty(DATA_ROOT_PROP, dataRoot.toString());
            external_storage_data_sync service = external_storage_data_sync.defaultService();
            String tenantUuid = "tenant-" + UUID.randomUUID();

            external_storage_data_sync.BackupResult backup = service.backupNowForConfig(
                    tenantUuid,
                    "sftp",
                    sourceRoot.toUri().toString(),
                    "key",
                    "secret",
                    ""
            );
            assertTrue(backup.ok);

            external_storage_data_sync.SnapshotInfo snapshot = service.latestSnapshotForConfig(
                    tenantUuid,
                    "sftp",
                    sourceRoot.toUri().toString(),
                    "key",
                    "secret",
                    ""
            );
            assertTrue(snapshot.available);
            assertTrue(snapshot.zipPath != null && Files.exists(snapshot.zipPath));

            Files.writeString(marker, "after-backup-local-change", StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
            Files.writeString(snapshot.zipPath, "corrupt", StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);

            IllegalStateException ex = assertThrows(
                    IllegalStateException.class,
                    () -> service.restoreLatestForConfig(
                            tenantUuid,
                            "sftp",
                            sourceRoot.toUri().toString(),
                            "key",
                            "secret",
                            ""
                    )
            );
            assertTrue(ex.getMessage().toLowerCase().contains("checksum"));
            assertEquals("after-backup-local-change", Files.readString(marker, StandardCharsets.UTF_8));
        } finally {
            if (oldDataRoot == null) System.clearProperty(DATA_ROOT_PROP);
            else System.setProperty(DATA_ROOT_PROP, oldDataRoot);
            deleteTree(dataRoot);
            deleteTree(sourceRoot);
        }
    }

    @Test
    void backup_restore_preserves_tenant_managed_encrypted_payloads() throws Exception {
        String tenantUuid = "tenant-enc-sync-" + UUID.randomUUID();
        String matterUuid = "matter-enc-sync-" + UUID.randomUUID();
        Path dataRoot = tempDir.resolve("data-encryption");
        Path sourceRoot = tempDir.resolve("source-encryption");
        Files.createDirectories(dataRoot);
        Files.createDirectories(sourceRoot);

        String payload = "enc-payload-" + UUID.randomUUID();
        String tenantManagedKey = "tenant-managed-key-" + tenantUuid;
        StorageCrypto crypto = new StorageCrypto();
        external_storage_data_sync service = external_storage_data_sync.defaultService();
        String oldDataRoot = System.getProperty(DATA_ROOT_PROP);

        try {
            System.setProperty(DATA_ROOT_PROP, dataRoot.toString());

            Path localCipherPath = dataRoot.resolve(Paths.get(
                    "tenants",
                    tenantUuid,
                    "matters",
                    matterUuid,
                    "assembled",
                    "files",
                    "case-output....txt"
            )).toAbsolutePath();
            Path remoteCipherPath = dataRoot.resolve(Paths.get(
                    "tenants",
                    tenantUuid,
                    "assembled_remote",
                    "s3_compatible",
                    "matters",
                    matterUuid,
                    "assemblies",
                    "case-output..remote.txt"
            )).toAbsolutePath();

            Files.createDirectories(localCipherPath.getParent());
            Files.createDirectories(remoteCipherPath.getParent());
            byte[] localCipherBefore = crypto.encrypt(payload.getBytes(StandardCharsets.UTF_8), tenantManagedKey);
            byte[] remoteCipherBefore = crypto.encrypt(payload.getBytes(StandardCharsets.UTF_8), tenantManagedKey);
            Files.write(localCipherPath, localCipherBefore, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            Files.write(remoteCipherPath, remoteCipherBefore, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);

            assertFalse(new String(localCipherBefore, StandardCharsets.UTF_8).contains(payload));
            assertFalse(new String(remoteCipherBefore, StandardCharsets.UTF_8).contains(payload));

            external_storage_data_sync.BackupResult backup = service.backupNowForConfig(
                    tenantUuid,
                    "sftp",
                    sourceRoot.toUri().toString(),
                    "access",
                    "secret",
                    ""
            );
            assertTrue(backup.ok);
            assertFalse(backup.snapshotId.isBlank());

            deleteTree(dataRoot);
            assertFalse(Files.exists(localCipherPath));

            external_storage_data_sync.RestoreResult restored = service.restoreLatestForConfig(
                    tenantUuid,
                    "sftp",
                    sourceRoot.toUri().toString(),
                    "access",
                    "secret",
                    ""
            );
            assertTrue(restored.ok);

            byte[] localCipherAfter = Files.readAllBytes(localCipherPath);
            byte[] remoteCipherAfter = Files.readAllBytes(remoteCipherPath);
            assertFalse(new String(localCipherAfter, StandardCharsets.UTF_8).contains(payload));
            assertFalse(new String(remoteCipherAfter, StandardCharsets.UTF_8).contains(payload));
            assertEquals(bytesHex(localCipherBefore), bytesHex(localCipherAfter));
            assertEquals(bytesHex(remoteCipherBefore), bytesHex(remoteCipherAfter));
            assertEquals(payload, new String(crypto.decrypt(localCipherAfter, tenantManagedKey), StandardCharsets.UTF_8));
            assertEquals(payload, new String(crypto.decrypt(remoteCipherAfter, tenantManagedKey), StandardCharsets.UTF_8));
        } finally {
            if (oldDataRoot == null) System.clearProperty(DATA_ROOT_PROP);
            else System.setProperty(DATA_ROOT_PROP, oldDataRoot);
            deleteTree(dataRoot);
            deleteTree(sourceRoot);
        }
    }

    @Test
    void backup_restore_preserves_last_modified_metadata_with_webdav_backend() throws Exception {
        String tenantUuid = "tenant-meta-sync-" + UUID.randomUUID();
        Path dataRoot = tempDir.resolve("data-metadata");
        Path sourceRoot = tempDir.resolve("source-webdav-metadata");
        Files.createDirectories(dataRoot);
        Files.createDirectories(sourceRoot);

        Path tracked = dataRoot.resolve(Paths.get("tenants", tenantUuid, "meta", "tracked.txt"));
        Files.createDirectories(tracked.getParent());
        Files.writeString(tracked, "metadata-content", StandardCharsets.UTF_8);
        long targetEpochMs = 1_704_067_200_123L;
        Files.setLastModifiedTime(tracked, FileTime.fromMillis(targetEpochMs));
        long expectedEpochMs = Files.getLastModifiedTime(tracked).toMillis();

        String oldDataRoot = System.getProperty(DATA_ROOT_PROP);
        external_storage_data_sync service = external_storage_data_sync.defaultService();
        try {
            System.setProperty(DATA_ROOT_PROP, dataRoot.toString());
            assertTrue(external_storage_data_sync.isExternalBackend("webdav"));

            external_storage_data_sync.BackupResult backup = service.backupNowForConfig(
                    tenantUuid,
                    "webdav",
                    sourceRoot.toUri().toString(),
                    "access",
                    "secret",
                    ""
            );
            assertTrue(backup.ok);

            external_storage_data_sync.SnapshotInfo snapshot = service.latestSnapshotForConfig(
                    tenantUuid,
                    "webdav",
                    sourceRoot.toUri().toString(),
                    "access",
                    "secret",
                    ""
            );
            assertTrue(snapshot.available);
            String manifest = Files.readString(snapshot.manifestPath, StandardCharsets.UTF_8);
            String row = "";
            String[] lines = manifest.split("\\R");
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                if (line == null) continue;
                String trimmed = line.trim();
                if (trimmed.isBlank() || trimmed.startsWith("#")) continue;
                row = line;
                break;
            }
            assertFalse(row.isBlank());
            String[] cols = row.split("\t");
            assertEquals(4, cols.length);
            long manifestEpochMs = Long.parseLong(cols[3].trim());
            assertTrue(Math.abs(manifestEpochMs - expectedEpochMs) <= 2_000L);

            Files.writeString(tracked, "metadata-mutated", StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
            Files.setLastModifiedTime(tracked, FileTime.fromMillis(expectedEpochMs + 86_400_000L));

            external_storage_data_sync.RestoreResult restored = service.restoreLatestForConfig(
                    tenantUuid,
                    "webdav",
                    sourceRoot.toUri().toString(),
                    "access",
                    "secret",
                    ""
            );
            assertTrue(restored.ok);
            assertEquals("metadata-content", Files.readString(tracked, StandardCharsets.UTF_8));

            long restoredEpochMs = Files.getLastModifiedTime(tracked).toMillis();
            assertTrue(Math.abs(restoredEpochMs - expectedEpochMs) <= 2_000L);
        } finally {
            if (oldDataRoot == null) System.clearProperty(DATA_ROOT_PROP);
            else System.setProperty(DATA_ROOT_PROP, oldDataRoot);
            deleteTree(dataRoot);
            deleteTree(sourceRoot);
        }
    }

    @Test
    void backup_restore_round_trip_works_with_onedrive_business_backend() throws Exception {
        String tenantUuid = "tenant-onedrive-sync-" + UUID.randomUUID();
        Path dataRoot = tempDir.resolve("data-onedrive");
        Path sourceRoot = tempDir.resolve("source-onedrive");
        Files.createDirectories(dataRoot);
        Files.createDirectories(sourceRoot);

        Path tracked = dataRoot.resolve(Paths.get("tenants", tenantUuid, "meta", "report#draft%.txt"));
        Files.createDirectories(tracked.getParent());
        Files.writeString(tracked, "onedrive-original", StandardCharsets.UTF_8);

        String oldDataRoot = System.getProperty(DATA_ROOT_PROP);
        external_storage_data_sync service = external_storage_data_sync.defaultService();
        try {
            System.setProperty(DATA_ROOT_PROP, dataRoot.toString());
            assertTrue(external_storage_data_sync.isExternalBackend("onedrive_business"));

            external_storage_data_sync.BackupResult backup = service.backupNowForConfig(
                    tenantUuid,
                    "onedrive_business",
                    sourceRoot.toUri().toString(),
                    "client-id",
                    "client-secret",
                    ""
            );
            assertTrue(backup.ok);

            Files.writeString(tracked, "onedrive-mutated", StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
            external_storage_data_sync.RestoreResult restored = service.restoreLatestForConfig(
                    tenantUuid,
                    "onedrive_business",
                    sourceRoot.toUri().toString(),
                    "client-id",
                    "client-secret",
                    ""
            );
            assertTrue(restored.ok);
            assertEquals("onedrive-original", Files.readString(tracked, StandardCharsets.UTF_8));
        } finally {
            if (oldDataRoot == null) System.clearProperty(DATA_ROOT_PROP);
            else System.setProperty(DATA_ROOT_PROP, oldDataRoot);
            deleteTree(dataRoot);
            deleteTree(sourceRoot);
        }
    }

    @Test
    void changing_external_root_folder_keeps_snapshots_isolated_and_restorable() throws Exception {
        String tenantUuid = "tenant-root-switch-" + UUID.randomUUID();
        Path dataRoot = tempDir.resolve("data-root-switch");
        Path sourceRoot = tempDir.resolve("source-root-switch");
        Files.createDirectories(dataRoot);
        Files.createDirectories(sourceRoot);

        Path tracked = dataRoot.resolve("tenant-data.txt");
        Files.writeString(tracked, "root-a-version", StandardCharsets.UTF_8);

        String oldDataRoot = System.getProperty(DATA_ROOT_PROP);
        external_storage_data_sync service = external_storage_data_sync.defaultService();
        try {
            System.setProperty(DATA_ROOT_PROP, dataRoot.toString());

            external_storage_data_sync.BackupResult first = service.backupNowForConfig(
                    tenantUuid,
                    "s3_compatible",
                    sourceRoot.toUri().toString(),
                    "access",
                    "secret",
                    "root-a"
            );
            assertTrue(first.ok);

            external_storage_data_sync.SnapshotInfo firstSnapshot = service.latestSnapshotForConfig(
                    tenantUuid,
                    "s3_compatible",
                    sourceRoot.toUri().toString(),
                    "access",
                    "secret",
                    "root-a"
            );
            assertTrue(firstSnapshot.available);

            Files.writeString(tracked, "root-b-version", StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
            external_storage_data_sync.BackupResult second = service.backupNowForConfig(
                    tenantUuid,
                    "s3_compatible",
                    sourceRoot.toUri().toString(),
                    "access",
                    "secret",
                    "root-b"
            );
            assertTrue(second.ok);

            external_storage_data_sync.SnapshotInfo secondSnapshot = service.latestSnapshotForConfig(
                    tenantUuid,
                    "s3_compatible",
                    sourceRoot.toUri().toString(),
                    "access",
                    "secret",
                    "root-b"
            );
            assertTrue(secondSnapshot.available);
            assertFalse(firstSnapshot.snapshotId.equals(secondSnapshot.snapshotId));
            assertFalse(firstSnapshot.sourceRoot.equals(secondSnapshot.sourceRoot));

            Files.writeString(tracked, "mutated-local", StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);

            external_storage_data_sync.RestoreResult restoreA = service.restoreLatestForConfig(
                    tenantUuid,
                    "s3_compatible",
                    sourceRoot.toUri().toString(),
                    "access",
                    "secret",
                    "root-a"
            );
            assertTrue(restoreA.ok);
            assertEquals("root-a-version", Files.readString(tracked, StandardCharsets.UTF_8));

            external_storage_data_sync.RestoreResult restoreB = service.restoreLatestForConfig(
                    tenantUuid,
                    "s3_compatible",
                    sourceRoot.toUri().toString(),
                    "access",
                    "secret",
                    "root-b"
            );
            assertTrue(restoreB.ok);
            assertEquals("root-b-version", Files.readString(tracked, StandardCharsets.UTF_8));
        } finally {
            if (oldDataRoot == null) System.clearProperty(DATA_ROOT_PROP);
            else System.setProperty(DATA_ROOT_PROP, oldDataRoot);
            deleteTree(dataRoot);
            deleteTree(sourceRoot);
        }
    }

    private static String bytesHex(byte[] bytes) {
        byte[] src = bytes == null ? new byte[0] : bytes;
        StringBuilder sb = new StringBuilder(src.length * 2);
        for (int i = 0; i < src.length; i++) sb.append(String.format("%02x", src[i]));
        return sb.toString();
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {
        }
    }
}
