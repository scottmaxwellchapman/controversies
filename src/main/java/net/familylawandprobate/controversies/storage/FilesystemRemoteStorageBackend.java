package net.familylawandprobate.controversies.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * File-backed emulation backend for ftp/ftps/sftp/s3-compatible integrations.
 * Keeps key semantics compatible with real object stores while allowing local execution.
 */
public final class FilesystemRemoteStorageBackend implements DocumentStorageBackend {
    private final String backendType;
    private final Path root;

    public FilesystemRemoteStorageBackend(String backendType, String tenantUuid) {
        this.backendType = normalizeBackendType(backendType);
        this.root = Paths.get("data", "tenants", safeFileToken(tenantUuid), "assembled_remote", this.backendType).toAbsolutePath();
    }

    @Override
    public String put(String key, byte[] bytes) throws Exception {
        String normalized = normalizeKey(key);
        Path p = root.resolve(normalized).normalize();
        Files.createDirectories(p.getParent());
        byte[] src = bytes == null ? new byte[0] : bytes;
        Files.write(p, src, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        verifyWriteIntegrity(p, src);
        return normalized;
    }

    @Override
    public byte[] get(String key) throws Exception {
        Path p = root.resolve(normalizeKey(key)).normalize();
        if (!Files.exists(p)) return new byte[0];
        return Files.readAllBytes(p);
    }

    @Override
    public void delete(String key) throws Exception {
        Files.deleteIfExists(root.resolve(normalizeKey(key)).normalize());
    }

    @Override
    public boolean exists(String key) {
        return Files.exists(root.resolve(normalizeKey(key)).normalize());
    }

    @Override
    public Map<String, String> metadata(String key) throws Exception {
        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
        String normalized = normalizeKey(key);
        out.put("backend", backendType);
        out.put("key", normalized);
        Path p = root.resolve(normalized).normalize();
        if (!Files.exists(p)) return out;
        byte[] bytes = Files.readAllBytes(p);
        out.put("size_bytes", Long.toString(bytes.length));
        out.put("checksum_sha256", StorageCrypto.checksumSha256Hex(bytes));
        out.put("checksum_md5", StorageCrypto.checksumMd5Hex(bytes));
        return out;
    }

    public static String normalizeBackendType(String backendType) {
        String v = backendType == null ? "" : backendType.trim().toLowerCase(Locale.ROOT);
        if (v.isBlank()) return "local";
        return switch (v) {
            case "local", "localfs" -> "local";
            case "filesystem_remote" -> "sftp";
            case "ftp", "ftps", "sftp", "s3_compatible" -> v;
            default -> throw new IllegalArgumentException("unsupported backend: " + backendType);
        };
    }

    private static String normalizeKey(String key) {
        String cleaned = key == null ? "" : key.replace("\\", "/").trim();
        cleaned = cleaned.replaceAll("^/+", "");
        if (cleaned.isBlank() || cleaned.contains("..")) throw new IllegalArgumentException("invalid key");
        return cleaned;
    }

    private static String safeFileToken(String s) {
        String t = s == null ? "" : s.trim();
        if (t.isBlank()) return "";
        return t.replaceAll("[^A-Za-z0-9._-]", "_");
    }


    private static void verifyWriteIntegrity(Path p, byte[] expected) throws Exception {
        byte[] actual = Files.readAllBytes(p);
        String expectedMd5 = StorageCrypto.checksumMd5Hex(expected);
        String actualMd5 = StorageCrypto.checksumMd5Hex(actual);
        if (!expectedMd5.equals(actualMd5)) throw new IllegalStateException("file write integrity check failed: md5 mismatch");
    }
}
