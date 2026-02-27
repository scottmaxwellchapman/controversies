package net.familylawandprobate.controversies.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

public final class LocalFsStorageBackend implements DocumentStorageBackend {
    private final Path root;

    public LocalFsStorageBackend(String tenantUuid, String matterUuid) {
        this.root = Paths.get("data", "tenants", safeFileToken(tenantUuid), "matters", safeFileToken(matterUuid), "assembled", "files").toAbsolutePath();
    }

    @Override
    public String put(String key, byte[] bytes) throws Exception {
        String normalized = normalizeKey(key);
        Path p = pathFor(normalized);
        Files.createDirectories(p.getParent());
        byte[] src = bytes == null ? new byte[0] : bytes;
        Files.write(p, src, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        verifyWriteIntegrity(p, src);
        return normalized;
    }

    @Override
    public byte[] get(String key) throws Exception {
        Path p = pathFor(normalizeKey(key));
        if (!Files.exists(p)) return new byte[0];
        return Files.readAllBytes(p);
    }

    @Override
    public void delete(String key) throws Exception {
        Files.deleteIfExists(pathFor(normalizeKey(key)));
    }

    @Override
    public boolean exists(String key) {
        return Files.exists(pathFor(normalizeKey(key)));
    }

    @Override
    public Map<String, String> metadata(String key) throws Exception {
        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
        String normalized = normalizeKey(key);
        Path p = pathFor(normalized);
        out.put("key", normalized);
        out.put("backend", "local");
        if (!Files.exists(p)) return out;
        byte[] bytes = Files.readAllBytes(p);
        out.put("size_bytes", Long.toString(bytes.length));
        out.put("checksum_sha256", StorageCrypto.checksumSha256Hex(bytes));
        out.put("checksum_md5", StorageCrypto.checksumMd5Hex(bytes));
        return out;
    }

    private Path pathFor(String key) {
        return root.resolve(key).normalize();
    }

    private static String normalizeKey(String key) {
        String cleaned = safe(key).replace("\\", "/").trim();
        cleaned = cleaned.replaceAll("^/+", "");
        if (cleaned.contains("..")) throw new IllegalArgumentException("invalid key");
        return cleaned;
    }

    private static String safeFileToken(String s) {
        String t = safe(s).trim();
        if (t.isBlank()) return "";
        return t.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }


    private static void verifyWriteIntegrity(Path p, byte[] expected) throws Exception {
        byte[] actual = Files.readAllBytes(p);
        String expectedMd5 = StorageCrypto.checksumMd5Hex(expected);
        String actualMd5 = StorageCrypto.checksumMd5Hex(actual);
        if (!expectedMd5.equals(actualMd5)) throw new IllegalStateException("file write integrity check failed: md5 mismatch");
    }
}
