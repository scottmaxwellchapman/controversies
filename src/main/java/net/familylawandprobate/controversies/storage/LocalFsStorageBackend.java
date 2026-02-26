package net.familylawandprobate.controversies.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
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
        Files.write(p, bytes == null ? new byte[0] : bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
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
        out.put("checksum_sha256", sha256Hex(bytes));
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

    private static String sha256Hex(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] out = digest.digest(bytes == null ? new byte[0] : bytes);
        StringBuilder sb = new StringBuilder(out.length * 2);
        for (int i = 0; i < out.length; i++) sb.append(String.format("%02x", out[i]));
        return sb.toString();
    }
}
