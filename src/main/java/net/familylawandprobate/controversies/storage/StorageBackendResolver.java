package net.familylawandprobate.controversies.storage;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

public final class StorageBackendResolver {

    public DocumentStorageBackend resolve(String tenantUuid, String matterUuid) throws Exception {
        String backendType = resolveBackendType(tenantUuid);
        return resolve(tenantUuid, matterUuid, backendType);
    }

    public DocumentStorageBackend resolve(String tenantUuid, String matterUuid, String backendType) {
        String normalized = FilesystemRemoteStorageBackend.normalizeBackendType(backendType);
        if ("local".equals(normalized)) return new LocalFsStorageBackend(tenantUuid, matterUuid);
        return new FilesystemRemoteStorageBackend(normalized, tenantUuid);
    }

    public String resolveBackendType(String tenantUuid) throws Exception {
        String sanitizedTenant = safeFileToken(tenantUuid);
        String sys = System.getProperty("controversies.tenant." + sanitizedTenant + ".assembled.storage_backend", "").trim();
        if (!sys.isBlank()) return FilesystemRemoteStorageBackend.normalizeBackendType(sys);

        String envKey = ("CONTROVERSIES_TENANT_" + sanitizedTenant + "_ASSEMBLED_STORAGE_BACKEND").toUpperCase();
        String env = safe(System.getenv(envKey)).trim();
        if (!env.isBlank()) return FilesystemRemoteStorageBackend.normalizeBackendType(env);

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
}
