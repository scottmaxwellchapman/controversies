package net.familylawandprobate.controversies.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

public final class LocalFsStorageBackend implements DocumentStorageBackend {
    private final Path root;
    private final Path objectRoot;
    private final boolean dedupLinksEnabled;

    public LocalFsStorageBackend(String tenantUuid, String matterUuid) {
        this(tenantUuid, matterUuid, true);
    }

    public LocalFsStorageBackend(String tenantUuid, String matterUuid, boolean dedupLinksEnabled) {
        String tenantToken = safeFileToken(tenantUuid);
        this.root = Paths.get("data", "tenants", tenantToken, "matters", safeFileToken(matterUuid), "assembled", "files").toAbsolutePath();
        this.objectRoot = Paths.get("data", "tenants", tenantToken, "dedup_objects").toAbsolutePath();
        this.dedupLinksEnabled = dedupLinksEnabled;
    }

    @Override
    public String put(String key, byte[] bytes) throws Exception {
        String normalized = normalizeKey(key);
        Path p = pathFor(normalized);
        byte[] src = bytes == null ? new byte[0] : bytes;
        if (dedupLinksEnabled) {
            DeduplicatedFileStore.write(p, src, objectRoot);
        } else {
            Files.createDirectories(p.getParent());
            Files.write(p, src, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            verifyWriteIntegrity(p, src);
        }
        return normalized;
    }

    @Override
    public byte[] get(String key) throws Exception {
        Path p = resolveReadPath(key);
        if (!Files.exists(p)) return new byte[0];
        return Files.readAllBytes(p);
    }

    @Override
    public void delete(String key) throws Exception {
        String strict = normalizeKey(key);
        String legacy = normalizeKeyLegacy(key);
        Files.deleteIfExists(pathFor(strict));
        if (!strict.equals(legacy)) {
            Files.deleteIfExists(pathFor(legacy));
        }
    }

    @Override
    public boolean exists(String key) {
        try {
            return Files.exists(resolveReadPath(key));
        } catch (Exception ex) {
            return false;
        }
    }

    @Override
    public Map<String, String> metadata(String key) throws Exception {
        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
        String normalized = normalizeKey(key);
        Path p = resolveReadPath(key);
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
        Path p = root.resolve(key).normalize();
        if (!p.startsWith(root)) throw new IllegalArgumentException("invalid key");
        return p;
    }

    private static String normalizeKey(String key) {
        ArrayList<String> segments = splitSegments(normalizeKeyLegacy(key));
        if (segments.isEmpty()) throw new IllegalArgumentException("invalid key");
        int tail = segments.size() - 1;
        String fileName = sanitizeFilenameSegment(segments.get(tail));
        if (fileName.isBlank()) fileName = "_";
        segments.set(tail, fileName);
        return String.join("/", segments);
    }

    private static String normalizeKeyLegacy(String key) {
        String cleaned = safe(key).replace("\\", "/").trim();
        cleaned = cleaned.replaceAll("^/+", "");
        if (cleaned.isBlank() || cleaned.contains("..")) throw new IllegalArgumentException("invalid key");
        return cleaned;
    }

    private Path resolveReadPath(String key) {
        String strict = normalizeKey(key);
        Path strictPath = pathFor(strict);
        if (Files.exists(strictPath)) return strictPath;
        String legacy = normalizeKeyLegacy(key);
        if (!strict.equals(legacy)) {
            Path legacyPath = pathFor(legacy);
            if (Files.exists(legacyPath)) return legacyPath;
        }
        return strictPath;
    }

    private static ArrayList<String> splitSegments(String key) {
        String[] parts = safe(key).split("/");
        ArrayList<String> out = new ArrayList<String>();
        for (int i = 0; i < parts.length; i++) {
            String seg = safe(parts[i]).trim();
            if (seg.isBlank() || ".".equals(seg)) continue;
            if ("..".equals(seg)) throw new IllegalArgumentException("invalid key");
            out.add(seg);
        }
        return out;
    }

    private static String sanitizeFilenameSegment(String fileName) {
        return safe(fileName).replaceAll("[^A-Za-z0-9.]", "_");
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
