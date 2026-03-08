package net.familylawandprobate.controversies;

import net.familylawandprobate.controversies.storage.FilesystemRemoteStorageBackend;
import net.familylawandprobate.controversies.storage.StorageCrypto;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Full data-directory backup/restore service for external storage backends.
 * Backup format is file-based and keeps remote pointer updates atomic.
 */
public final class external_storage_data_sync {

    private static final Logger LOG = Logger.getLogger(external_storage_data_sync.class.getName());

    private static final String BACKUP_FORMAT_ROOT = "controversies_data_backup_v1";
    private static final String LATEST_FILE_NAME = "latest.properties";
    private static final String META_FILE_NAME = "meta.properties";
    private static final String DATA_ZIP_NAME = "data.zip";
    private static final String MANIFEST_FILE_NAME = "manifest.tsv";
    private static final String SYSTEM_PROP_DATA_ROOT = "controversies.data.root";
    private static final String SYSTEM_PROP_EXTERNAL_STORAGE_ROOT = "controversies.external.storage.root";
    private static final Duration DEFAULT_MIN_SYNC_INTERVAL = Duration.ofHours(24);
    private static final int DEFAULT_SNAPSHOT_RETENTION = 14;
    private static final int DEFAULT_LOCAL_RESTORE_BACKUP_RETENTION = 3;
    private static final int COPY_BUFFER_BYTES = 64 * 1024;
    private static final Pattern WINDOWS_ABSOLUTE_PATTERN = Pattern.compile("^[A-Za-z]:[\\\\/].*");

    private final Object lock = new Object();

    private static final class Holder {
        private static final external_storage_data_sync INSTANCE = new external_storage_data_sync();
    }

    public static external_storage_data_sync defaultService() {
        return Holder.INSTANCE;
    }

    public static boolean isExternalBackend(String backendType) {
        String normalized = normalizeBackend(backendType);
        return "ftp".equals(normalized)
                || "ftps".equals(normalized)
                || "sftp".equals(normalized)
                || "webdav".equals(normalized)
                || "s3_compatible".equals(normalized)
                || "onedrive_business".equals(normalized);
    }

    public BackupResult backupIfDue(String tenantUuid) throws Exception {
        SourceConfig cfg = resolveTenantSource(tenantUuid, false);
        if (cfg == null) {
            return BackupResult.skipped(safe(tenantUuid), "", "", "tenant storage backend is local or incomplete");
        }
        return backupInternal(cfg, false);
    }

    public BackupResult backupNow(String tenantUuid) throws Exception {
        SourceConfig cfg = resolveTenantSource(tenantUuid, true);
        return backupInternal(cfg, true);
    }

    public BackupResult backupIfDueForConfig(String tenantUuid,
                                             String backendType,
                                             String endpoint,
                                             String accessKey,
                                             String secret,
                                             String rootFolder) throws Exception {
        SourceConfig cfg = buildSourceConfig(tenantUuid, backendType, endpoint, accessKey, secret, rootFolder);
        return backupInternal(cfg, false);
    }

    public BackupResult backupNowForConfig(String tenantUuid,
                                           String backendType,
                                           String endpoint,
                                           String accessKey,
                                           String secret,
                                           String rootFolder) throws Exception {
        SourceConfig cfg = buildSourceConfig(tenantUuid, backendType, endpoint, accessKey, secret, rootFolder);
        return backupInternal(cfg, true);
    }

    public RestoreResult restoreLatest(String tenantUuid) throws Exception {
        SourceConfig cfg = resolveTenantSource(tenantUuid, true);
        return restoreInternal(cfg);
    }

    public RestoreResult restoreLatestForConfig(String tenantUuid,
                                                String backendType,
                                                String endpoint,
                                                String accessKey,
                                                String secret,
                                                String rootFolder) throws Exception {
        SourceConfig cfg = buildSourceConfig(tenantUuid, backendType, endpoint, accessKey, secret, rootFolder);
        return restoreInternal(cfg);
    }

    public SnapshotInfo latestSnapshotForTenant(String tenantUuid) throws Exception {
        SourceConfig cfg = resolveTenantSource(tenantUuid, false);
        if (cfg == null) return SnapshotInfo.unavailable("", "", null, null, null);
        return readLatestSnapshot(cfg);
    }

    public SnapshotInfo latestSnapshotForConfig(String tenantUuid,
                                                String backendType,
                                                String endpoint,
                                                String accessKey,
                                                String secret,
                                                String rootFolder) throws Exception {
        SourceConfig cfg = buildSourceConfig(tenantUuid, backendType, endpoint, accessKey, secret, rootFolder);
        return readLatestSnapshot(cfg);
    }

    public List<BackupResult> syncAllTenantsIfDue() {
        ArrayList<BackupResult> out = new ArrayList<BackupResult>();
        LinkedHashSet<String> processedSources = new LinkedHashSet<String>();
        try {
            tenants tenantStore = tenants.defaultStore();
            tenantStore.ensure();
            List<tenants.Tenant> all = tenantStore.list();
            for (int i = 0; i < (all == null ? 0 : all.size()); i++) {
                tenants.Tenant t = all.get(i);
                if (t == null || !t.enabled) continue;
                String tenantUuid = safe(t.uuid).trim();
                if (tenantUuid.isBlank()) continue;
                SourceConfig cfg;
                try {
                    cfg = resolveTenantSource(tenantUuid, false);
                } catch (Exception ex) {
                    LOG.log(Level.WARNING, "Unable to resolve external source for tenant " + tenantUuid + ": " + safe(ex.getMessage()), ex);
                    continue;
                }
                if (cfg == null) continue;
                if (!processedSources.add(cfg.sourceId)) continue;
                try {
                    out.add(backupInternal(cfg, false));
                } catch (Exception ex) {
                    String err = safe(ex.getMessage());
                    if (err.isBlank()) err = "backup failed";
                    out.add(BackupResult.failed(cfg.tenantUuid, cfg.sourceId, "", err));
                    LOG.log(Level.WARNING, "External backup sync failed for source " + cfg.sourceId + ": " + err, ex);
                }
            }
        } catch (Exception ex) {
            LOG.log(Level.WARNING, "Unable to run external storage daily sync tick: " + safe(ex.getMessage()), ex);
        }
        return out;
    }

    private BackupResult backupInternal(SourceConfig cfg, boolean force) throws Exception {
        synchronized (lock) {
            SnapshotInfo latest = readLatestSnapshot(cfg);
            Instant now = Instant.now();
            Instant last = parseInstantSafe(latest.completedAt);
            if (!force && last != null && now.isBefore(last.plus(DEFAULT_MIN_SYNC_INTERVAL))) {
                return BackupResult.skipped(
                        cfg.tenantUuid,
                        cfg.sourceId,
                        latest.snapshotId,
                        "last successful backup is within the daily sync interval"
                );
            }

            Path stageDir = Files.createTempDirectory("controversies-backup-stage-");
            try {
                SnapshotBuild build = buildSnapshot(stageDir, cfg);
                Path incomingDir = incomingRoot(cfg).resolve(build.snapshotId + ".tmp");
                Path snapshotDir = snapshotsRoot(cfg).resolve(build.snapshotId);
                Files.createDirectories(incomingDir.getParent());
                Files.createDirectories(snapshotDir.getParent());
                deleteTree(incomingDir);
                deleteTree(snapshotDir);
                Files.createDirectories(incomingDir);

                Path incomingZip = incomingDir.resolve(DATA_ZIP_NAME);
                Path incomingManifest = incomingDir.resolve(MANIFEST_FILE_NAME);
                Path incomingMeta = incomingDir.resolve(META_FILE_NAME);

                Files.copy(build.zipPath, incomingZip, StandardCopyOption.REPLACE_EXISTING);
                Files.copy(build.manifestPath, incomingManifest, StandardCopyOption.REPLACE_EXISTING);
                writePropertiesAtomic(incomingMeta, build.meta, "external_data_snapshot_meta");

                String copiedZipSha = checksumFileSha256(incomingZip);
                String copiedManifestSha = checksumFileSha256(incomingManifest);
                if (!build.zipSha256.equalsIgnoreCase(copiedZipSha)) {
                    throw new IllegalStateException("backup upload integrity check failed for snapshot zip");
                }
                if (!build.manifestSha256.equalsIgnoreCase(copiedManifestSha)) {
                    throw new IllegalStateException("backup upload integrity check failed for snapshot manifest");
                }

                movePath(incomingDir, snapshotDir);

                Properties latestProps = new Properties();
                latestProps.setProperty("snapshot_id", build.snapshotId);
                latestProps.setProperty("completed_at", build.createdAt);
                latestProps.setProperty("file_count", String.valueOf(build.fileCount));
                latestProps.setProperty("zip_sha256", build.zipSha256);
                latestProps.setProperty("manifest_sha256", build.manifestSha256);
                latestProps.setProperty("source_id", cfg.sourceId);
                latestProps.setProperty("tenant_uuid", cfg.tenantUuid);
                writePropertiesAtomic(latestFile(cfg), latestProps, "external_data_backup_latest");

                pruneOldSnapshots(snapshotsRoot(cfg), build.snapshotId);

                return BackupResult.completed(cfg.tenantUuid, cfg.sourceId, build.snapshotId, build.fileCount, build.createdAt);
            } finally {
                deleteTree(stageDir);
            }
        }
    }

    private RestoreResult restoreInternal(SourceConfig cfg) throws Exception {
        synchronized (lock) {
            SnapshotInfo latest = readLatestSnapshot(cfg);
            if (!latest.available || latest.snapshotDir == null) {
                throw new IllegalStateException("No backup snapshot is available for this external storage source.");
            }
            if (latest.zipPath == null || !Files.isRegularFile(latest.zipPath)) {
                throw new IllegalStateException("Latest backup snapshot is missing data.zip");
            }
            if (latest.manifestPath == null || !Files.isRegularFile(latest.manifestPath)) {
                throw new IllegalStateException("Latest backup snapshot is missing manifest.tsv");
            }

            Path metaPath = latest.snapshotDir.resolve(META_FILE_NAME);
            Properties meta = loadProperties(metaPath);
            String expectedZipSha = safe(meta.getProperty("zip_sha256")).trim();
            if (expectedZipSha.isBlank()) expectedZipSha = safe(latest.zipSha256).trim();
            String expectedManifestSha = safe(meta.getProperty("manifest_sha256")).trim();

            String actualZipSha = checksumFileSha256(latest.zipPath);
            if (!expectedZipSha.isBlank() && !expectedZipSha.equalsIgnoreCase(actualZipSha)) {
                throw new IllegalStateException("Restore aborted: snapshot zip checksum validation failed.");
            }

            String actualManifestSha = checksumFileSha256(latest.manifestPath);
            if (!expectedManifestSha.isBlank() && !expectedManifestSha.equalsIgnoreCase(actualManifestSha)) {
                throw new IllegalStateException("Restore aborted: snapshot manifest checksum validation failed.");
            }

            List<ManifestEntry> manifestEntries = readManifest(latest.manifestPath);

            Path restoreStage = Files.createTempDirectory("controversies-restore-stage-");
            Path extractedDataRoot = restoreStage.resolve("data");
            Path dataRoot = dataRoot();
            Path dataParent = dataRoot.getParent();
            if (dataParent == null) throw new IllegalStateException("Unable to determine data directory parent.");
            Files.createDirectories(dataParent);
            boolean dataExisted = Files.exists(dataRoot);
            String stamp = String.valueOf(System.currentTimeMillis());
            Path localRollback = dataParent.resolve(dataRoot.getFileName().toString() + ".pre_restore." + stamp);

            try {
                extractZip(latest.zipPath, extractedDataRoot);
                verifyExtractedDataAgainstManifest(extractedDataRoot, manifestEntries);

                if (dataExisted) {
                    movePath(dataRoot, localRollback);
                }

                try {
                    movePath(extractedDataRoot, dataRoot);
                } catch (Exception swapEx) {
                    if (Files.exists(dataRoot)) deleteTree(dataRoot);
                    if (dataExisted && Files.exists(localRollback)) {
                        movePath(localRollback, dataRoot);
                    }
                    throw swapEx;
                }

                pruneLocalRestoreBackups(dataParent, dataRoot.getFileName().toString() + ".pre_restore.");
                return RestoreResult.completed(cfg.tenantUuid, cfg.sourceId, latest.snapshotId, localRollback.toAbsolutePath());
            } finally {
                deleteTree(restoreStage);
            }
        }
    }

    private SnapshotBuild buildSnapshot(Path stageDir, SourceConfig cfg) throws Exception {
        Files.createDirectories(stageDir);
        Path zipPath = stageDir.resolve(DATA_ZIP_NAME);
        Path manifestPath = stageDir.resolve(MANIFEST_FILE_NAME);
        Path dataRoot = dataRoot();
        if (!Files.exists(dataRoot)) Files.createDirectories(dataRoot);

        List<Path> files;
        try (Stream<Path> walk = Files.walk(dataRoot)) {
            files = walk.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(p -> normalizeRelativePath(dataRoot.relativize(p).toString())))
                    .toList();
        }

        ArrayList<ManifestEntry> manifestEntries = new ArrayList<ManifestEntry>();
        byte[] buffer = new byte[COPY_BUFFER_BYTES];
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(
                zipPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        ));
             ZipOutputStream zipOut = new ZipOutputStream(out, StandardCharsets.UTF_8)) {

            for (int i = 0; i < files.size(); i++) {
                Path file = files.get(i);
                String rel = normalizeRelativePath(dataRoot.relativize(file).toString());
                ZipEntry entry = new ZipEntry(rel);
                long modifiedAtEpochMs = -1L;
                try {
                    modifiedAtEpochMs = Files.getLastModifiedTime(file).toMillis();
                    entry.setTime(modifiedAtEpochMs);
                } catch (Exception ignored) {
                }
                zipOut.putNextEntry(entry);
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long size = 0L;
                try (InputStream in = new BufferedInputStream(Files.newInputStream(file, StandardOpenOption.READ))) {
                    int read;
                    while ((read = in.read(buffer)) >= 0) {
                        if (read == 0) continue;
                        zipOut.write(buffer, 0, read);
                        digest.update(buffer, 0, read);
                        size += read;
                    }
                }
                zipOut.closeEntry();
                manifestEntries.add(new ManifestEntry(rel, size, toHex(digest.digest()), modifiedAtEpochMs));
            }
        }

        StringBuilder manifest = new StringBuilder();
        manifest.append("# controversies backup manifest v2\n");
        for (int i = 0; i < manifestEntries.size(); i++) {
            ManifestEntry e = manifestEntries.get(i);
            manifest.append(e.sha256)
                    .append('\t')
                    .append(e.sizeBytes)
                    .append('\t')
                    .append(encodeManifestPath(e.relativePath))
                    .append('\t')
                    .append(e.lastModifiedEpochMs)
                    .append('\n');
        }
        Files.writeString(
                manifestPath,
                manifest.toString(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        );

        String zipSha256 = checksumFileSha256(zipPath);
        String manifestSha256 = checksumFileSha256(manifestPath);
        String snapshotId = snapshotIdNow();
        String createdAt = Instant.now().toString();

        Properties meta = new Properties();
        meta.setProperty("snapshot_id", snapshotId);
        meta.setProperty("created_at", createdAt);
        meta.setProperty("file_count", String.valueOf(manifestEntries.size()));
        meta.setProperty("zip_sha256", zipSha256);
        meta.setProperty("manifest_sha256", manifestSha256);
        meta.setProperty("source_id", cfg.sourceId);
        meta.setProperty("tenant_uuid", cfg.tenantUuid);

        return new SnapshotBuild(
                snapshotId,
                createdAt,
                zipPath,
                manifestPath,
                zipSha256,
                manifestSha256,
                manifestEntries.size(),
                meta
        );
    }

    private SnapshotInfo readLatestSnapshot(SourceConfig cfg) throws Exception {
        Path latestPath = latestFile(cfg);
        if (!Files.isRegularFile(latestPath)) {
            return SnapshotInfo.unavailable(cfg.tenantUuid, cfg.sourceId, sourceRoot(cfg), snapshotsRoot(cfg), latestPath);
        }
        Properties p = loadProperties(latestPath);
        String snapshotId = safe(p.getProperty("snapshot_id")).trim();
        String completedAt = safe(p.getProperty("completed_at")).trim();
        long fileCount = parseLongSafe(p.getProperty("file_count"), 0L);
        String zipSha = safe(p.getProperty("zip_sha256")).trim();
        Path snapshotsRoot = snapshotsRoot(cfg);
        Path snapshotDir = snapshotId.isBlank() ? null : snapshotsRoot.resolve(snapshotId);
        Path zipPath = snapshotDir == null ? null : snapshotDir.resolve(DATA_ZIP_NAME);
        Path manifestPath = snapshotDir == null ? null : snapshotDir.resolve(MANIFEST_FILE_NAME);
        boolean available = snapshotDir != null
                && Files.isDirectory(snapshotDir)
                && Files.isRegularFile(zipPath)
                && Files.isRegularFile(manifestPath);
        return new SnapshotInfo(
                available,
                cfg.tenantUuid,
                cfg.sourceId,
                snapshotId,
                completedAt,
                fileCount,
                zipSha,
                sourceRoot(cfg),
                snapshotsRoot,
                latestPath,
                snapshotDir,
                zipPath,
                manifestPath
        );
    }

    private void verifyExtractedDataAgainstManifest(Path extractedDataRoot, List<ManifestEntry> entries) throws Exception {
        LinkedHashMap<String, ManifestEntry> expected = new LinkedHashMap<String, ManifestEntry>();
        for (int i = 0; i < entries.size(); i++) {
            ManifestEntry e = entries.get(i);
            if (expected.containsKey(e.relativePath)) {
                throw new IllegalStateException("Restore aborted: duplicate manifest path " + e.relativePath);
            }
            expected.put(e.relativePath, e);
        }

        LinkedHashMap<String, Path> actual = new LinkedHashMap<String, Path>();
        if (Files.exists(extractedDataRoot)) {
            try (Stream<Path> walk = Files.walk(extractedDataRoot)) {
                walk.filter(Files::isRegularFile).forEach(path -> {
                    String rel = normalizeRelativePath(extractedDataRoot.relativize(path).toString());
                    actual.put(rel, path);
                });
            }
        }

        if (actual.size() != expected.size()) {
            throw new IllegalStateException("Restore aborted: extracted file count does not match manifest.");
        }

        for (Map.Entry<String, ManifestEntry> entry : expected.entrySet()) {
            String rel = entry.getKey();
            ManifestEntry m = entry.getValue();
            Path file = actual.get(rel);
            if (file == null || !Files.isRegularFile(file)) {
                throw new IllegalStateException("Restore aborted: missing file in extracted snapshot: " + rel);
            }
            long size = Files.size(file);
            if (size != m.sizeBytes) {
                throw new IllegalStateException("Restore aborted: size mismatch for " + rel);
            }
            String sha = checksumFileSha256(file);
            if (!m.sha256.equalsIgnoreCase(sha)) {
                throw new IllegalStateException("Restore aborted: checksum mismatch for " + rel);
            }
            if (m.lastModifiedEpochMs >= 0L) {
                try {
                    Files.setLastModifiedTime(file, FileTime.fromMillis(m.lastModifiedEpochMs));
                } catch (Exception ex) {
                    throw new IllegalStateException("Restore aborted: unable to restore file metadata for " + rel, ex);
                }
            }
        }
    }

    private void extractZip(Path zipPath, Path targetRoot) throws Exception {
        Files.createDirectories(targetRoot);
        byte[] buffer = new byte[COPY_BUFFER_BYTES];
        try (InputStream in = new BufferedInputStream(Files.newInputStream(zipPath, StandardOpenOption.READ));
             ZipInputStream zipIn = new ZipInputStream(in, StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = zipIn.getNextEntry()) != null) {
                if (e.isDirectory()) {
                    zipIn.closeEntry();
                    continue;
                }
                String rel = normalizeRelativePath(e.getName());
                Path out = targetRoot.resolve(rel).normalize();
                if (!out.startsWith(targetRoot)) {
                    throw new IllegalStateException("Restore aborted: invalid zip entry path");
                }
                Files.createDirectories(out.getParent());
                try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(
                        out,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE
                ))) {
                    int read;
                    while ((read = zipIn.read(buffer)) >= 0) {
                        if (read == 0) continue;
                        os.write(buffer, 0, read);
                    }
                }
                long zipEntryTime = e.getTime();
                if (zipEntryTime > 0L) {
                    try {
                        Files.setLastModifiedTime(out, FileTime.fromMillis(zipEntryTime));
                    } catch (Exception ignored) {
                    }
                }
                zipIn.closeEntry();
            }
        }
    }

    private List<ManifestEntry> readManifest(Path manifestPath) throws Exception {
        ArrayList<ManifestEntry> out = new ArrayList<ManifestEntry>();
        try (BufferedReader reader = Files.newBufferedReader(manifestPath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = safe(line).trim();
                if (trimmed.isBlank() || trimmed.startsWith("#")) continue;
                String[] parts = line.split("\t");
                if (parts.length != 3 && parts.length != 4) {
                    throw new IllegalStateException("Restore aborted: malformed manifest row");
                }
                String sha = safe(parts[0]).trim().toLowerCase(Locale.ROOT);
                long size = parseLongSafe(parts[1], -1L);
                String rel = decodeManifestPath(parts[2]);
                long lastModifiedEpochMs = parts.length == 4 ? parseLongSafe(parts[3], -1L) : -1L;
                if (sha.length() != 64 || size < 0L || rel.isBlank() || (parts.length == 4 && lastModifiedEpochMs < 0L)) {
                    throw new IllegalStateException("Restore aborted: invalid manifest row");
                }
                out.add(new ManifestEntry(rel, size, sha, lastModifiedEpochMs));
            }
        }
        return out;
    }

    private SourceConfig resolveTenantSource(String tenantUuid, boolean strict) throws Exception {
        String tu = safe(tenantUuid).trim();
        if (tu.isBlank()) throw new IllegalArgumentException("tenantUuid required");
        LinkedHashMap<String, String> settings = tenant_settings.defaultStore().read(tu);
        String backend = normalizeBackend(settings.get("storage_backend"));
        if (!isExternalBackend(backend)) {
            if (strict) throw new IllegalStateException("External storage backend is not enabled for this tenant.");
            return null;
        }
        String endpoint = safe(settings.get("storage_endpoint")).trim();
        String accessKey = safe(settings.get("storage_access_key")).trim();
        String secret = safe(settings.get("storage_secret")).trim();
        String rootFolder = safe(settings.get("storage_root_folder")).trim();
        if (endpoint.isBlank() || accessKey.isBlank() || secret.isBlank()) {
            if (strict) throw new IllegalStateException("External storage endpoint/access key/secret are required.");
            return null;
        }
        return buildSourceConfig(tu, backend, endpoint, accessKey, secret, rootFolder);
    }

    private SourceConfig buildSourceConfig(String tenantUuid,
                                           String backendType,
                                           String endpoint,
                                           String accessKey,
                                           String secret,
                                           String rootFolder) throws Exception {
        String tu = safe(tenantUuid).trim();
        if (tu.isBlank()) throw new IllegalArgumentException("tenantUuid required");
        String backend = normalizeBackend(backendType);
        if (!isExternalBackend(backend)) {
            throw new IllegalArgumentException("backup/restore requires ftp, ftps, sftp, webdav, s3_compatible, or onedrive_business storage backend");
        }

        String ep = safe(endpoint).trim();
        String ak = safe(accessKey).trim();
        String sk = safe(secret).trim();
        String rf = normalizeSourceRootFolder(rootFolder);
        if (ep.isBlank() || ak.isBlank() || sk.isBlank()) {
            throw new IllegalArgumentException("external storage endpoint, access key, and secret are required");
        }

        String sourceId = sourceId(backend, ep, ak, rf);
        Path sourceRoot = resolveSourceRoot(backend, ep, sourceId, rf);
        Path dataRoot = dataRoot();
        if (sourceRoot.normalize().startsWith(dataRoot.normalize())) {
            throw new IllegalArgumentException("external storage source path cannot be inside the local data directory");
        }

        return new SourceConfig(tu, backend, ep, sourceId, sourceRoot, rf);
    }

    private Path resolveSourceRoot(String backend, String endpoint, String sourceId, String rootFolder) {
        Path endpointPath = parseLocalEndpointPath(endpoint);
        Path base;
        if (endpointPath != null) {
            base = endpointPath.toAbsolutePath().normalize();
        } else {
            String configured = safe(System.getProperty(SYSTEM_PROP_EXTERNAL_STORAGE_ROOT)).trim();
            if (!configured.isBlank()) {
                base = Paths.get(configured).toAbsolutePath().normalize();
            } else {
                String home = safe(System.getProperty("user.home")).trim();
                if (home.isBlank()) home = ".";
                base = Paths.get(home, ".controversies_external_storage").toAbsolutePath().normalize();
            }
            base = base.resolve(backend).resolve(sourceId);
        }
        String rf = normalizeSourceRootFolder(rootFolder);
        if (!rf.isBlank()) {
            base = base.resolve(rf);
        }
        return base.toAbsolutePath().normalize();
    }

    private static Path parseLocalEndpointPath(String endpoint) {
        String ep = safe(endpoint).trim();
        if (ep.isBlank()) return null;
        String lower = ep.toLowerCase(Locale.ROOT);
        try {
            if (lower.startsWith("file://")) {
                return Paths.get(URI.create(ep));
            }
        } catch (Exception ignored) {
            return null;
        }

        if (ep.startsWith("~/")) {
            String home = safe(System.getProperty("user.home")).trim();
            if (!home.isBlank()) return Paths.get(home + ep.substring(1));
        }
        if (ep.startsWith("/") || ep.startsWith("./") || ep.startsWith("../") || WINDOWS_ABSOLUTE_PATTERN.matcher(ep).matches()) {
            return Paths.get(ep);
        }
        return null;
    }

    private static String sourceId(String backend, String endpoint, String accessKey, String rootFolder) throws Exception {
        String material = safe(backend).trim().toLowerCase(Locale.ROOT)
                + "|"
                + safe(endpoint).trim().toLowerCase(Locale.ROOT)
                + "|"
                + safe(accessKey).trim()
                + "|"
                + normalizeSourceRootFolder(rootFolder).toLowerCase(Locale.ROOT);
        String hash = StorageCrypto.checksumSha256Hex(material.getBytes(StandardCharsets.UTF_8));
        if (hash.length() > 24) hash = hash.substring(0, 24);
        return safe(backend).trim().toLowerCase(Locale.ROOT) + "_" + hash;
    }

    private static String normalizeSourceRootFolder(String raw) {
        String src = safe(raw).replace('\\', '/').trim();
        if (src.isBlank()) return "";
        String[] rawParts = src.split("/");
        ArrayList<String> cleanParts = new ArrayList<String>();
        for (int i = 0; i < rawParts.length; i++) {
            String part = safe(rawParts[i]).trim();
            if (part.isBlank() || ".".equals(part)) continue;
            if ("..".equals(part)) return "";
            part = part.replaceAll("[^A-Za-z0-9._-]", "_");
            if (part.length() > 128) part = part.substring(0, 128);
            if (!part.isBlank()) cleanParts.add(part);
        }
        if (cleanParts.isEmpty()) return "";
        String joined = String.join("/", cleanParts);
        if (joined.length() > 1024) joined = joined.substring(0, 1024);
        return joined;
    }

    private static String snapshotIdNow() {
        return System.currentTimeMillis() + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private static String encodeManifestPath(String path) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(safe(path).getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeManifestPath(String encoded) {
        try {
            byte[] raw = Base64.getUrlDecoder().decode(safe(encoded).trim());
            return normalizeRelativePath(new String(raw, StandardCharsets.UTF_8));
        } catch (Exception ex) {
            return "";
        }
    }

    private static String normalizeRelativePath(String relPath) {
        String p = safe(relPath).replace('\\', '/').trim();
        if (p.isBlank()) throw new IllegalArgumentException("invalid relative path");
        if (WINDOWS_ABSOLUTE_PATTERN.matcher(p).matches()) throw new IllegalArgumentException("invalid relative path");
        while (p.startsWith("/")) p = p.substring(1);
        while (p.startsWith("./")) p = p.substring(2);
        if (p.isBlank()) throw new IllegalArgumentException("invalid relative path");

        String[] rawParts = p.split("/");
        ArrayList<String> parts = new ArrayList<String>();
        for (int i = 0; i < rawParts.length; i++) {
            String part = safe(rawParts[i]).trim();
            if (part.isEmpty() || ".".equals(part)) continue;
            if ("..".equals(part)) throw new IllegalArgumentException("invalid relative path");
            parts.add(part);
        }
        if (parts.isEmpty()) throw new IllegalArgumentException("invalid relative path");
        return String.join("/", parts);
    }

    private static String normalizeBackend(String backendType) {
        try {
            return FilesystemRemoteStorageBackend.normalizeBackendType(backendType);
        } catch (Exception ignored) {
            return "local";
        }
    }

    private static Path dataRoot() {
        String configured = safe(System.getProperty(SYSTEM_PROP_DATA_ROOT)).trim();
        if (configured.isBlank()) configured = "data";
        return Paths.get(configured).toAbsolutePath().normalize();
    }

    private static Path sourceRoot(SourceConfig cfg) {
        return cfg.sourceRoot.resolve(BACKUP_FORMAT_ROOT).toAbsolutePath().normalize();
    }

    private static Path snapshotsRoot(SourceConfig cfg) {
        return sourceRoot(cfg).resolve("snapshots").toAbsolutePath().normalize();
    }

    private static Path incomingRoot(SourceConfig cfg) {
        return sourceRoot(cfg).resolve("incoming").toAbsolutePath().normalize();
    }

    private static Path latestFile(SourceConfig cfg) {
        return sourceRoot(cfg).resolve(LATEST_FILE_NAME).toAbsolutePath().normalize();
    }

    private static String checksumFileSha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[COPY_BUFFER_BYTES];
        try (InputStream in = new BufferedInputStream(Files.newInputStream(path, StandardOpenOption.READ))) {
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read == 0) continue;
                digest.update(buffer, 0, read);
            }
        }
        return toHex(digest.digest());
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder((bytes == null ? 0 : bytes.length) * 2);
        byte[] src = bytes == null ? new byte[0] : bytes;
        for (int i = 0; i < src.length; i++) {
            sb.append(String.format("%02x", src[i]));
        }
        return sb.toString();
    }

    private static void movePath(Path from, Path to) throws Exception {
        if (from == null || to == null) return;
        Files.createDirectories(to.getParent());
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(from, to);
        }
    }

    private static void writePropertiesAtomic(Path path, Properties p, String comment) throws Exception {
        Files.createDirectories(path.getParent());
        Path tmp = path.resolveSibling(path.getFileName().toString() + ".tmp");
        try (OutputStream out = Files.newOutputStream(
                tmp,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        )) {
            p.store(out, safe(comment));
        }
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Properties loadProperties(Path p) throws Exception {
        Properties out = new Properties();
        if (p == null || !Files.isRegularFile(p)) return out;
        try (InputStream in = Files.newInputStream(p, StandardOpenOption.READ)) {
            out.load(in);
        }
        return out;
    }

    private static void pruneOldSnapshots(Path snapshotsRoot, String keepSnapshotId) {
        if (snapshotsRoot == null || !Files.isDirectory(snapshotsRoot)) return;
        try (Stream<Path> walk = Files.list(snapshotsRoot)) {
            List<Path> dirs = walk.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(path -> safe(path.getFileName() == null ? "" : path.getFileName().toString())))
                    .toList();
            int removable = Math.max(0, dirs.size() - DEFAULT_SNAPSHOT_RETENTION);
            for (int i = 0; i < removable; i++) {
                Path p = dirs.get(i);
                String id = safe(p.getFileName() == null ? "" : p.getFileName().toString()).trim();
                if (id.equals(keepSnapshotId)) continue;
                deleteTree(p);
            }
        } catch (Exception ex) {
            LOG.log(Level.FINE, "Unable to prune old external snapshots: " + safe(ex.getMessage()), ex);
        }
    }

    private static void pruneLocalRestoreBackups(Path root, String prefix) {
        if (root == null || !Files.isDirectory(root)) return;
        try (Stream<Path> stream = Files.list(root)) {
            List<Path> candidates = stream
                    .filter(Files::isDirectory)
                    .filter(path -> safe(path.getFileName() == null ? "" : path.getFileName().toString()).startsWith(prefix))
                    .sorted(Comparator.comparing(path -> safe(path.getFileName() == null ? "" : path.getFileName().toString())))
                    .toList();
            int removable = Math.max(0, candidates.size() - DEFAULT_LOCAL_RESTORE_BACKUP_RETENTION);
            for (int i = 0; i < removable; i++) {
                deleteTree(candidates.get(i));
            }
        } catch (Exception ex) {
            LOG.log(Level.FINE, "Unable to prune local restore backups: " + safe(ex.getMessage()), ex);
        }
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }

    private static Instant parseInstantSafe(String value) {
        String v = safe(value).trim();
        if (v.isBlank()) return null;
        try {
            return Instant.parse(v);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static long parseLongSafe(String value, long fallback) {
        try {
            return Long.parseLong(safe(value).trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static final class SourceConfig {
        final String tenantUuid;
        final String backendType;
        final String endpoint;
        final String sourceId;
        final Path sourceRoot;
        final String rootFolder;

        private SourceConfig(String tenantUuid,
                             String backendType,
                             String endpoint,
                             String sourceId,
                             Path sourceRoot,
                             String rootFolder) {
            this.tenantUuid = safe(tenantUuid).trim();
            this.backendType = safe(backendType).trim();
            this.endpoint = safe(endpoint).trim();
            this.sourceId = safe(sourceId).trim();
            this.sourceRoot = sourceRoot == null ? Paths.get(".").toAbsolutePath() : sourceRoot.toAbsolutePath().normalize();
            this.rootFolder = normalizeSourceRootFolder(rootFolder);
        }
    }

    private static final class SnapshotBuild {
        final String snapshotId;
        final String createdAt;
        final Path zipPath;
        final Path manifestPath;
        final String zipSha256;
        final String manifestSha256;
        final int fileCount;
        final Properties meta;

        private SnapshotBuild(String snapshotId,
                              String createdAt,
                              Path zipPath,
                              Path manifestPath,
                              String zipSha256,
                              String manifestSha256,
                              int fileCount,
                              Properties meta) {
            this.snapshotId = safe(snapshotId).trim();
            this.createdAt = safe(createdAt).trim();
            this.zipPath = zipPath;
            this.manifestPath = manifestPath;
            this.zipSha256 = safe(zipSha256).trim();
            this.manifestSha256 = safe(manifestSha256).trim();
            this.fileCount = Math.max(0, fileCount);
            this.meta = meta == null ? new Properties() : meta;
        }
    }

    private static final class ManifestEntry {
        final String relativePath;
        final long sizeBytes;
        final String sha256;
        final long lastModifiedEpochMs;

        private ManifestEntry(String relativePath, long sizeBytes, String sha256, long lastModifiedEpochMs) {
            this.relativePath = safe(relativePath).trim();
            this.sizeBytes = Math.max(0L, sizeBytes);
            this.sha256 = safe(sha256).trim().toLowerCase(Locale.ROOT);
            this.lastModifiedEpochMs = Math.max(-1L, lastModifiedEpochMs);
        }
    }

    public static final class BackupResult {
        public final boolean ok;
        public final boolean skipped;
        public final String tenantUuid;
        public final String sourceId;
        public final String snapshotId;
        public final int fileCount;
        public final String completedAt;
        public final String message;

        private BackupResult(boolean ok,
                             boolean skipped,
                             String tenantUuid,
                             String sourceId,
                             String snapshotId,
                             int fileCount,
                             String completedAt,
                             String message) {
            this.ok = ok;
            this.skipped = skipped;
            this.tenantUuid = safe(tenantUuid).trim();
            this.sourceId = safe(sourceId).trim();
            this.snapshotId = safe(snapshotId).trim();
            this.fileCount = Math.max(0, fileCount);
            this.completedAt = safe(completedAt).trim();
            this.message = safe(message).trim();
        }

        private static BackupResult completed(String tenantUuid,
                                              String sourceId,
                                              String snapshotId,
                                              int fileCount,
                                              String completedAt) {
            return new BackupResult(true, false, tenantUuid, sourceId, snapshotId, fileCount, completedAt, "backup completed");
        }

        private static BackupResult skipped(String tenantUuid,
                                            String sourceId,
                                            String snapshotId,
                                            String reason) {
            return new BackupResult(true, true, tenantUuid, sourceId, snapshotId, 0, "", reason);
        }

        private static BackupResult failed(String tenantUuid,
                                           String sourceId,
                                           String snapshotId,
                                           String message) {
            return new BackupResult(false, false, tenantUuid, sourceId, snapshotId, 0, "", message);
        }
    }

    public static final class RestoreResult {
        public final boolean ok;
        public final String tenantUuid;
        public final String sourceId;
        public final String snapshotId;
        public final String rollbackDirectory;
        public final String message;

        private RestoreResult(boolean ok,
                              String tenantUuid,
                              String sourceId,
                              String snapshotId,
                              String rollbackDirectory,
                              String message) {
            this.ok = ok;
            this.tenantUuid = safe(tenantUuid).trim();
            this.sourceId = safe(sourceId).trim();
            this.snapshotId = safe(snapshotId).trim();
            this.rollbackDirectory = safe(rollbackDirectory).trim();
            this.message = safe(message).trim();
        }

        private static RestoreResult completed(String tenantUuid,
                                               String sourceId,
                                               String snapshotId,
                                               Path rollbackDir) {
            return new RestoreResult(
                    true,
                    tenantUuid,
                    sourceId,
                    snapshotId,
                    rollbackDir == null ? "" : rollbackDir.toAbsolutePath().toString(),
                    "restore completed"
            );
        }
    }

    public static final class SnapshotInfo {
        public final boolean available;
        public final String tenantUuid;
        public final String sourceId;
        public final String snapshotId;
        public final String completedAt;
        public final long fileCount;
        public final String zipSha256;
        public final Path sourceRoot;
        public final Path snapshotsRoot;
        public final Path latestPointerPath;
        public final Path snapshotDir;
        public final Path zipPath;
        public final Path manifestPath;

        private SnapshotInfo(boolean available,
                             String tenantUuid,
                             String sourceId,
                             String snapshotId,
                             String completedAt,
                             long fileCount,
                             String zipSha256,
                             Path sourceRoot,
                             Path snapshotsRoot,
                             Path latestPointerPath,
                             Path snapshotDir,
                             Path zipPath,
                             Path manifestPath) {
            this.available = available;
            this.tenantUuid = safe(tenantUuid).trim();
            this.sourceId = safe(sourceId).trim();
            this.snapshotId = safe(snapshotId).trim();
            this.completedAt = safe(completedAt).trim();
            this.fileCount = Math.max(0L, fileCount);
            this.zipSha256 = safe(zipSha256).trim();
            this.sourceRoot = sourceRoot;
            this.snapshotsRoot = snapshotsRoot;
            this.latestPointerPath = latestPointerPath;
            this.snapshotDir = snapshotDir;
            this.zipPath = zipPath;
            this.manifestPath = manifestPath;
        }

        private static SnapshotInfo unavailable(String tenantUuid,
                                                String sourceId,
                                                Path sourceRoot,
                                                Path snapshotsRoot,
                                                Path latestPointerPath) {
            return new SnapshotInfo(
                    false,
                    tenantUuid,
                    sourceId,
                    "",
                    "",
                    0L,
                    "",
                    sourceRoot,
                    snapshotsRoot,
                    latestPointerPath,
                    null,
                    null,
                    null
            );
        }
    }
}
