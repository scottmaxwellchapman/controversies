package net.familylawandprobate.controversies.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * File-backed emulation backend for ftp/ftps/sftp/webdav/s3-compatible/onedrive integrations.
 * Keeps key semantics compatible with real object stores while allowing local execution.
 */
public final class FilesystemRemoteStorageBackend implements DocumentStorageBackend {
    private static final int DEFAULT_MAX_PATH_LENGTH = 0;
    private static final int DEFAULT_MAX_FILENAME_LENGTH = 0;
    private static final int DEFAULT_ONEDRIVE_MAX_PATH_LENGTH = 400;
    private static final int DEFAULT_ONEDRIVE_MAX_FILENAME_LENGTH = 255;
    private static final int ABSOLUTE_MAX_PATH_LENGTH = 8192;
    private static final int ABSOLUTE_MAX_FILENAME_LENGTH = 1024;
    private static final int MAX_COLLISION_ATTEMPTS = 4096;

    private final String backendType;
    private final Path root;
    private final Path objectRoot;
    private final int maxPathLength;
    private final int maxFilenameLength;
    private final boolean dedupLinksEnabled;

    public FilesystemRemoteStorageBackend(String backendType, String tenantUuid) {
        this(backendType, tenantUuid, DEFAULT_MAX_PATH_LENGTH, DEFAULT_MAX_FILENAME_LENGTH, true);
    }

    public FilesystemRemoteStorageBackend(String backendType,
                                          String tenantUuid,
                                          int maxPathLength,
                                          int maxFilenameLength) {
        this(backendType, tenantUuid, maxPathLength, maxFilenameLength, true);
    }

    public FilesystemRemoteStorageBackend(String backendType,
                                          String tenantUuid,
                                          int maxPathLength,
                                          int maxFilenameLength,
                                          boolean dedupLinksEnabled) {
        this.backendType = normalizeBackendType(backendType);
        String tenantToken = safeFileToken(tenantUuid);
        this.root = Paths.get("data", "tenants", tenantToken, "assembled_remote", this.backendType).toAbsolutePath();
        this.objectRoot = Paths.get("data", "tenants", tenantToken, "dedup_objects").toAbsolutePath();
        this.dedupLinksEnabled = dedupLinksEnabled;
        int effectiveMaxPathLength = maxPathLength;
        int effectiveMaxFilenameLength = maxFilenameLength;
        if (isOneDriveBackend(this.backendType)) {
            if (effectiveMaxPathLength <= 0) effectiveMaxPathLength = DEFAULT_ONEDRIVE_MAX_PATH_LENGTH;
            if (effectiveMaxFilenameLength <= 0) effectiveMaxFilenameLength = DEFAULT_ONEDRIVE_MAX_FILENAME_LENGTH;
        }
        this.maxPathLength = sanitizeLimit(effectiveMaxPathLength, DEFAULT_MAX_PATH_LENGTH, ABSOLUTE_MAX_PATH_LENGTH);
        this.maxFilenameLength = sanitizeLimit(effectiveMaxFilenameLength, DEFAULT_MAX_FILENAME_LENGTH, ABSOLUTE_MAX_FILENAME_LENGTH);
    }

    @Override
    public String put(String key, byte[] bytes) throws Exception {
        byte[] src = bytes == null ? new byte[0] : bytes;
        String normalized = normalizeKey(key);
        String writableKey = resolveNonDestructiveKey(normalized, src);
        Path p = pathFor(writableKey);
        if (dedupLinksEnabled) {
            DeduplicatedFileStore.write(p, src, objectRoot);
        } else {
            Files.createDirectories(p.getParent());
            Files.write(p, src, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            verifyWriteIntegrity(p, src);
        }
        return writableKey;
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
        out.put("backend", backendType);
        out.put("key", normalized);
        Path p = resolveReadPath(key);
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
            case "onedrive", "onedrive_business", "onedrive_for_business", "onedrive-for-business" -> "onedrive_business";
            case "ftp", "ftps", "sftp", "webdav", "s3_compatible" -> v;
            default -> throw new IllegalArgumentException("unsupported backend: " + backendType);
        };
    }

    private String normalizeKey(String key) {
        String cleaned = normalizeKeyLegacy(key);
        ArrayList<String> segments = splitSegments(cleaned);
        if (segments.isEmpty()) throw new IllegalArgumentException("invalid key");

        int tail = segments.size() - 1;
        String fileName = sanitizeFilenameSegment(segments.get(tail));
        if (fileName.isBlank()) fileName = "_";
        segments.set(tail, fileName);

        String limited = applyLimits(segments);
        if (limited.isBlank()) throw new IllegalArgumentException("invalid key");
        return limited;
    }

    private String normalizeKeyLegacy(String key) {
        String cleaned = key == null ? "" : key.replace("\\", "/").trim();
        cleaned = cleaned.replaceAll("^/+", "");
        if (cleaned.isBlank()) throw new IllegalArgumentException("invalid key");
        if (cleaned.contains("..")) throw new IllegalArgumentException("invalid key");
        return cleaned;
    }

    private Path pathFor(String key) {
        Path p = root.resolve(key).normalize();
        if (!p.startsWith(root)) throw new IllegalArgumentException("invalid key");
        return p;
    }

    private String resolveNonDestructiveKey(String normalized, byte[] src) throws Exception {
        String wantedHash = StorageCrypto.checksumSha256Hex(src);
        String candidate = normalized;
        for (int attempt = 0; attempt <= MAX_COLLISION_ATTEMPTS; attempt++) {
            Path p = pathFor(candidate);
            if (!Files.exists(p)) {
                return candidate;
            }
            long existingSize = Files.size(p);
            if (existingSize == src.length) {
                byte[] existingBytes = Files.readAllBytes(p);
                String existingHash = StorageCrypto.checksumSha256Hex(existingBytes);
                if (wantedHash.equals(existingHash)) {
                    return candidate;
                }
            }
            candidate = disambiguateKey(normalized, attempt + 1);
        }
        throw new IllegalStateException("unable to allocate unique key for external storage write");
    }

    private String disambiguateKey(String normalizedBase, int attempt) {
        ArrayList<String> segments = splitSegments(normalizedBase);
        if (segments.isEmpty()) throw new IllegalArgumentException("invalid key");
        int tail = segments.size() - 1;
        String fileName = segments.get(tail);
        String suffix = "_c" + attempt;
        segments.set(tail, injectSuffix(fileName, suffix));
        return applyLimits(segments);
    }

    private String applyLimits(ArrayList<String> rawSegments) {
        ArrayList<String> segments = new ArrayList<String>();
        for (int i = 0; i < rawSegments.size(); i++) {
            String segment = safe(rawSegments.get(i)).trim();
            if (segment.isBlank()) continue;
            if (i == rawSegments.size() - 1) {
                segment = sanitizeFilenameSegment(segment);
            }
            segment = sanitizeSegmentForBackend(segment, i == rawSegments.size() - 1);
            segment = enforceFilenameLimit(segment, maxFilenameLength);
            if (segment.isBlank()) {
                segment = shortHash("segment:" + i, 8);
            }
            segments.add(segment);
        }
        if (segments.isEmpty()) throw new IllegalArgumentException("invalid key");

        String joined = String.join("/", segments);
        if (maxPathLength <= 0 || joined.length() <= maxPathLength) return joined;

        ArrayList<String> shrunk = new ArrayList<String>(segments);
        int safety = 0;
        while (String.join("/", shrunk).length() > maxPathLength && safety < 100_000) {
            safety++;
            int idx = longestSegmentIndex(shrunk);
            if (idx < 0) break;
            String seg = shrunk.get(idx);
            if (seg.length() <= 1) break;
            boolean preserveExt = idx == shrunk.size() - 1;
            shrunk.set(idx, enforceFilenameLimit(seg, seg.length() - 1, preserveExt));
        }

        String candidate = String.join("/", shrunk);
        if (candidate.length() <= maxPathLength) return candidate;

        String fallback = fallbackKeyForPathLimit(segments);
        if (maxPathLength > 0 && fallback.length() > maxPathLength) {
            fallback = enforceFilenameLimit(fallback, maxPathLength, true);
        }
        if (fallback.isBlank()) {
            int cap = maxPathLength > 0 ? Math.max(1, maxPathLength) : 12;
            fallback = shortHash(String.join("/", segments), cap);
        }
        return fallback;
    }

    private String sanitizeSegmentForBackend(String segment, boolean isFileNameSegment) {
        if (isOneDriveBackend(backendType)) {
            return sanitizeOneDriveSegment(segment, isFileNameSegment);
        }
        return segment;
    }

    private static int longestSegmentIndex(ArrayList<String> segments) {
        int idx = -1;
        int max = 0;
        for (int i = 0; i < (segments == null ? 0 : segments.size()); i++) {
            String s = safe(segments.get(i));
            if (s.length() > max) {
                max = s.length();
                idx = i;
            }
        }
        return idx;
    }

    private String fallbackKeyForPathLimit(ArrayList<String> segments) {
        String original = String.join("/", segments);
        String tail = segments.isEmpty() ? "" : segments.get(segments.size() - 1);
        String ext = extensionOf(tail);
        String hash = shortHash(original, 24);
        String token = "obj_" + hash;
        if (!ext.isBlank()) token = token + "." + ext;
        if (maxFilenameLength > 0) {
            token = enforceFilenameLimit(token, maxFilenameLength, true);
        }
        return token;
    }

    private static String enforceFilenameLimit(String segment, int maxLen) {
        return enforceFilenameLimit(segment, maxLen, true);
    }

    private static String enforceFilenameLimit(String segment, int maxLen, boolean preserveExtension) {
        String s = safe(segment);
        int limit = maxLen <= 0 ? 0 : maxLen;
        if (limit == 0 || s.length() <= limit) return s;
        String ext = preserveExtension ? extensionOf(s) : "";
        String base = !ext.isBlank() && s.length() > ext.length() + 1
                ? s.substring(0, s.length() - ext.length() - 1)
                : s;
        String suffix = "_" + shortHash(s, 10);
        int keep = limit - suffix.length() - (ext.isBlank() ? 0 : ext.length() + 1);
        if (keep >= 1) {
            return base.substring(0, Math.min(base.length(), keep))
                    + suffix
                    + (ext.isBlank() ? "" : "." + ext);
        }
        if (limit <= suffix.length()) {
            return suffix.substring(0, Math.max(1, limit));
        }
        int prefix = limit - suffix.length();
        return s.substring(0, Math.max(1, prefix)) + suffix;
    }

    private static String injectSuffix(String fileName, String suffix) {
        String name = safe(fileName);
        String ext = extensionOf(name);
        if (!ext.isBlank() && name.length() > ext.length() + 1) {
            String base = name.substring(0, name.length() - ext.length() - 1);
            return base + safe(suffix) + "." + ext;
        }
        return name + safe(suffix);
    }

    private static String extensionOf(String fileName) {
        String name = safe(fileName).trim();
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot >= name.length() - 1) return "";
        String ext = name.substring(dot + 1);
        if (ext.length() > 24) ext = ext.substring(0, 24);
        return ext;
    }

    private static String sanitizeOneDriveSegment(String segment, boolean isFileNameSegment) {
        String out = safe(segment);
        out = out.replaceAll("[\\\\/*<>?:|#%\"]", "_");
        if (isFileNameSegment) {
            out = sanitizeFilenameSegment(out);
        }
        out = out.replaceAll("\\p{Cntrl}", "_");
        while (out.endsWith(".") || out.endsWith(" ")) {
            out = out.substring(0, out.length() - 1);
            if (out.isBlank()) break;
        }
        if (out.isBlank()) return "";

        String base = out;
        int dot = out.indexOf('.');
        if (dot > 0) base = out.substring(0, dot);
        String upperBase = base.toUpperCase(Locale.ROOT);
        if (isWindowsReservedName(upperBase)) {
            out = "_" + out;
        }
        return out;
    }

    private static boolean isWindowsReservedName(String name) {
        if (name == null || name.isBlank()) return false;
        if ("CON".equals(name) || "PRN".equals(name) || "AUX".equals(name) || "NUL".equals(name)) return true;
        for (int i = 1; i <= 9; i++) {
            if (("COM" + i).equals(name) || ("LPT" + i).equals(name)) return true;
        }
        return false;
    }

    private static boolean isOneDriveBackend(String normalizedBackendType) {
        return "onedrive_business".equals(safe(normalizedBackendType).trim().toLowerCase(Locale.ROOT));
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
        String cleaned = safe(key).replace("\\", "/").trim();
        while (cleaned.startsWith("/")) cleaned = cleaned.substring(1);
        String[] parts = cleaned.split("/");
        ArrayList<String> out = new ArrayList<String>();
        for (int i = 0; i < parts.length; i++) {
            String segment = safe(parts[i]).trim();
            if (segment.isBlank() || ".".equals(segment)) continue;
            if ("..".equals(segment)) throw new IllegalArgumentException("invalid key");
            out.add(segment);
        }
        return out;
    }

    private static String sanitizeFilenameSegment(String fileName) {
        return safe(fileName).replaceAll("[^A-Za-z0-9.]", "_");
    }

    private static String shortHash(String value, int maxLen) {
        int cap = maxLen <= 0 ? 8 : maxLen;
        String hex = "";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(safe(value).getBytes(StandardCharsets.UTF_8));
            hex = toHex(hashed);
        } catch (Exception ignored) {
            hex = Integer.toHexString(safe(value).hashCode());
        }
        if (hex.length() > cap) return hex.substring(0, cap);
        if (hex.isBlank()) return "x";
        return hex;
    }

    private static String toHex(byte[] bytes) {
        byte[] src = bytes == null ? new byte[0] : bytes;
        StringBuilder sb = new StringBuilder(src.length * 2);
        for (int i = 0; i < src.length; i++) {
            sb.append(String.format("%02x", src[i]));
        }
        return sb.toString();
    }

    private static int sanitizeLimit(int raw, int fallback, int max) {
        int cap = max <= 0 ? Integer.MAX_VALUE : max;
        int out = raw;
        if (out < 0 || out > cap) out = fallback;
        if (out < 0) out = fallback;
        return out;
    }

    private static String safeFileToken(String s) {
        String t = s == null ? "" : s.trim();
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
