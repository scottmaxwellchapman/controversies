package net.familylawandprobate.controversies.storage;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import net.familylawandprobate.controversies.tenant_settings;

public final class StorageBackendResolver {

    public DocumentStorageBackend resolve(String tenantUuid, String matterUuid) throws Exception {
        String backendType = resolveBackendType(tenantUuid);
        return resolve(tenantUuid, matterUuid, backendType);
    }

    public DocumentStorageBackend resolve(String tenantUuid, String matterUuid, String backendType) {
        String normalized = FilesystemRemoteStorageBackend.normalizeBackendType(backendType);
        LinkedHashMap<String, String> settings = tenant_settings.defaultStore().read(tenantUuid);
        int maxPathLength = parseInt(settings.get("storage_max_path_length"), 0);
        int maxFilenameLength = parseInt(settings.get("storage_max_filename_length"), 0);
        DocumentStorageBackend base = "local".equals(normalized)
                ? new LocalFsStorageBackend(tenantUuid, matterUuid)
                : new FilesystemRemoteStorageBackend(normalized, tenantUuid, maxPathLength, maxFilenameLength);
        if (!"local".equals(normalized)) {
            long cacheMaxBytes = resolveCacheBytes(settings, normalized);
            if (cacheMaxBytes > 0L) {
                String sourceToken = cacheSourceToken(
                        normalized,
                        settings.get("storage_endpoint"),
                        settings.get("storage_root_folder")
                );
                base = new CachedDocumentStorageBackend(base, tenantUuid, sourceToken, cacheMaxBytes);
            }
        }
        return new EncryptedDocumentStorageBackend(
                base,
                safe(settings.get("storage_encryption_mode")),
                safe(settings.get("storage_encryption_key")),
                "s3_compatible".equals(normalized) ? safe(settings.get("storage_s3_sse_mode")) : "none",
                "s3_compatible".equals(normalized) ? safe(settings.get("storage_s3_sse_kms_key_id")) : ""
        );
    }

    public String resolveBackendType(String tenantUuid) throws Exception {
        String sanitizedTenant = safeFileToken(tenantUuid);
        String sys = System.getProperty("controversies.tenant." + sanitizedTenant + ".assembled.storage_backend", "").trim();
        if (!sys.isBlank()) return FilesystemRemoteStorageBackend.normalizeBackendType(sys);

        String envKey = ("CONTROVERSIES_TENANT_" + sanitizedTenant + "_ASSEMBLED_STORAGE_BACKEND").toUpperCase();
        String env = safe(System.getenv(envKey)).trim();
        if (!env.isBlank()) return FilesystemRemoteStorageBackend.normalizeBackendType(env);

        Path tenantSettingsPath = Paths.get("data", "tenants", sanitizedTenant, "settings", "tenant_settings.json").toAbsolutePath();
        if (Files.exists(tenantSettingsPath)) {
            LinkedHashMap<String, String> settings = tenant_settings.defaultStore().read(tenantUuid);
            String tenantSettingBackend = safe(settings.get("storage_backend")).trim();
            if (!tenantSettingBackend.isBlank()) return FilesystemRemoteStorageBackend.normalizeBackendType(tenantSettingBackend);
        }

        Path cfg = Paths.get("data", "tenants", sanitizedTenant, "assembled", "storage.properties").toAbsolutePath();
        if (Files.exists(cfg)) {
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(cfg)) {
                p.load(in);
            }
            String type = safe(p.getProperty("backend", "")).trim();
            if (!type.isBlank()) return FilesystemRemoteStorageBackend.normalizeBackendType(type);
        }

        return "local";
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String safeFileToken(String s) {
        String t = safe(s).trim();
        if (t.isBlank()) return "";
        return t.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String cacheSourceToken(String backendType, String endpoint, String rootFolder) {
        String normalizedBackend = FilesystemRemoteStorageBackend.normalizeBackendType(backendType);
        String ep = safe(endpoint).trim().toLowerCase(Locale.ROOT);
        String root = safe(rootFolder).trim().toLowerCase(Locale.ROOT);
        String endpointHash = "default";
        if (!ep.isBlank() || !root.isBlank()) {
            try {
                String material = ep + "|" + root;
                endpointHash = StorageCrypto.checksumSha256Hex(material.getBytes(StandardCharsets.UTF_8)).substring(0, 12);
            } catch (Exception ignored) {
                endpointHash = Integer.toHexString((ep + "|" + root).hashCode());
            }
        }
        return normalizedBackend + "_" + endpointHash;
    }

    private static long resolveCacheBytes(Map<String, String> settings, String backendType) {
        String normalizedBackend = FilesystemRemoteStorageBackend.normalizeBackendType(backendType);
        String key = switch (normalizedBackend) {
            case "ftp" -> "storage_cache_size_ftp_mb";
            case "ftps" -> "storage_cache_size_ftps_mb";
            case "sftp" -> "storage_cache_size_sftp_mb";
            case "webdav" -> "storage_cache_size_webdav_mb";
            case "s3_compatible" -> "storage_cache_size_s3_compatible_mb";
            case "onedrive_business" -> "storage_cache_size_onedrive_business_mb";
            default -> "";
        };
        if (key.isBlank()) return 0L;

        int sizeMb = parseInt(settings == null ? "" : settings.get(key), 1024);
        if (sizeMb < 0 || sizeMb > 1048576) sizeMb = 1024;
        return sizeMb * 1024L * 1024L;
    }

    private static int parseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(safe(raw).trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }
}
