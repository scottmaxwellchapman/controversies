package net.familylawandprobate.controversies.storage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Bounded local cache wrapper for non-local storage backends.
 * Cache root: data/tenants/{tenant}/storage_cache/{sourceToken}
 */
public final class CachedDocumentStorageBackend implements DocumentStorageBackend {
    private final DocumentStorageBackend delegate;
    private final Path cacheRoot;
    private final long maxCacheBytes;

    public CachedDocumentStorageBackend(DocumentStorageBackend delegate,
                                        String tenantUuid,
                                        String sourceToken,
                                        long maxCacheBytes) {
        this.delegate = delegate;
        this.cacheRoot = Paths.get(
                "data",
                "tenants",
                safeFileToken(tenantUuid),
                "storage_cache",
                safeFileToken(sourceToken)
        ).toAbsolutePath();
        this.maxCacheBytes = Math.max(0L, maxCacheBytes);
    }

    @Override
    public String put(String key, byte[] bytes) throws Exception {
        byte[] src = bytes == null ? new byte[0] : bytes;
        String storedKey = delegate.put(key, src);
        cacheWrite(storedKey, src);
        return storedKey;
    }

    @Override
    public byte[] get(String key) throws Exception {
        byte[] cached = cacheRead(key);
        if (cached != null) return cached;

        byte[] bytes = delegate.get(key);
        if (bytes == null) bytes = new byte[0];
        if (bytes.length == 0) {
            boolean exists = delegate.exists(key);
            if (!exists) return bytes;
        }
        cacheWrite(key, bytes);
        return bytes;
    }

    @Override
    public void delete(String key) throws Exception {
        delegate.delete(key);
        cacheDelete(key);
    }

    @Override
    public boolean exists(String key) throws Exception {
        if (cachePresent(key)) return true;
        return delegate.exists(key);
    }

    @Override
    public Map<String, String> metadata(String key) throws Exception {
        return delegate.metadata(key);
    }

    private byte[] cacheRead(String key) {
        if (maxCacheBytes <= 0L) return null;
        try {
            Path p = cachePath(key);
            if (!Files.exists(p)) return null;
            byte[] bytes = Files.readAllBytes(p);
            touch(p);
            return bytes;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void cacheWrite(String key, byte[] bytes) {
        if (maxCacheBytes <= 0L) return;
        byte[] src = bytes == null ? new byte[0] : bytes;
        try {
            if (src.length > maxCacheBytes) {
                cacheDelete(key);
                return;
            }
            Path p = cachePath(key);
            Files.createDirectories(p.getParent());
            Files.write(p, src, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            touch(p);
            evictIfNeeded();
        } catch (Exception ignored) {
            // Cache failures are non-fatal; delegate reads remain authoritative.
        }
    }

    private void cacheDelete(String key) {
        if (maxCacheBytes <= 0L) return;
        try {
            Files.deleteIfExists(cachePath(key));
        } catch (Exception ignored) {
        }
    }

    private boolean cachePresent(String key) {
        if (maxCacheBytes <= 0L) return false;
        try {
            Path p = cachePath(key);
            if (!Files.exists(p)) return false;
            touch(p);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private Path cachePath(String key) {
        String normalized = normalizeKey(key);
        String hash = hashForKey(normalized);
        String shard = hash.substring(0, 2);
        return cacheRoot.resolve(shard).resolve(hash + ".bin");
    }

    private String hashForKey(String normalizedKey) {
        try {
            return StorageCrypto.checksumSha256Hex(normalizedKey.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            String hex = Integer.toHexString(normalizedKey.hashCode());
            StringBuilder sb = new StringBuilder(64);
            while (sb.length() < 64) sb.append(hex);
            return sb.substring(0, 64);
        }
    }

    private void evictIfNeeded() {
        if (maxCacheBytes <= 0L) return;
        if (!Files.exists(cacheRoot)) return;
        try {
            List<Path> files = new ArrayList<Path>();
            try (Stream<Path> walk = Files.walk(cacheRoot)) {
                walk.filter(Files::isRegularFile).forEach(files::add);
            }
            long total = 0L;
            for (int i = 0; i < files.size(); i++) {
                Path f = files.get(i);
                total += fileSize(f);
            }
            if (total <= maxCacheBytes) return;

            files.sort(Comparator.comparingLong(this::lastModifiedMillis));
            for (int i = 0; i < files.size() && total > maxCacheBytes; i++) {
                Path f = files.get(i);
                long sz = fileSize(f);
                Files.deleteIfExists(f);
                total -= sz;
            }
        } catch (Exception ignored) {
        }
    }

    private long fileSize(Path p) {
        try {
            return Files.size(p);
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private long lastModifiedMillis(Path p) {
        try {
            FileTime ft = Files.getLastModifiedTime(p);
            return ft == null ? 0L : ft.toMillis();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private void touch(Path p) {
        try {
            Files.setLastModifiedTime(p, FileTime.from(Instant.now()));
        } catch (Exception ignored) {
        }
    }

    private static String normalizeKey(String key) {
        String cleaned = safe(key).replace("\\", "/").trim();
        cleaned = cleaned.replaceAll("^/+", "");
        if (cleaned.isBlank() || cleaned.contains("..")) throw new IllegalArgumentException("invalid key");
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
}
