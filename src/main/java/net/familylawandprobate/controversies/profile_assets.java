package net.familylawandprobate.controversies;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * profile_assets
 *
 * Stores tenant identity media (tenant logo + per-user photos) under:
 *   data/tenants/{tenantUuid}/identity
 */
public final class profile_assets {

    public static final long MAX_IMAGE_BYTES = 5L * 1024L * 1024L;
    private static final int URL_CONNECT_TIMEOUT_MS = 15000;
    private static final int URL_READ_TIMEOUT_MS = 20000;
    private static final int MAX_REDIRECTS = 5;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ConcurrentHashMap<String, ReentrantReadWriteLock> LOCKS =
            new ConcurrentHashMap<String, ReentrantReadWriteLock>();

    public static profile_assets defaultStore() {
        return new profile_assets();
    }

    public static final class AssetRec {
        public final String scope;
        public final String tenantUuid;
        public final String userUuid;
        public final String fileName;
        public final String mimeType;
        public final long sizeBytes;
        public final String updatedAt;
        public final String sourceUrl;
        public final String updatedBy;
        public final String relativePath;

        public AssetRec(String scope,
                        String tenantUuid,
                        String userUuid,
                        String fileName,
                        String mimeType,
                        long sizeBytes,
                        String updatedAt,
                        String sourceUrl,
                        String updatedBy,
                        String relativePath) {
            this.scope = safe(scope);
            this.tenantUuid = safe(tenantUuid);
            this.userUuid = safe(userUuid);
            this.fileName = safe(fileName);
            this.mimeType = safe(mimeType);
            this.sizeBytes = Math.max(0L, sizeBytes);
            this.updatedAt = safe(updatedAt);
            this.sourceUrl = safe(sourceUrl);
            this.updatedBy = safe(updatedBy);
            this.relativePath = safe(relativePath);
        }
    }

    public AssetRec readTenantLogo(String tenantUuid) {
        String tu = safeFileToken(tenantUuid);
        if (tu.isBlank()) return null;

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.readLock().lock();
        try {
            return readAssetLocked(tu, "tenant_logo", "");
        } finally {
            lock.readLock().unlock();
        }
    }

    public AssetRec readUserPhoto(String tenantUuid, String userUuid) {
        String tu = safeFileToken(tenantUuid);
        String uu = safeFileToken(userUuid);
        if (tu.isBlank() || uu.isBlank()) return null;

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.readLock().lock();
        try {
            return readAssetLocked(tu, "user_photo", uu);
        } finally {
            lock.readLock().unlock();
        }
    }

    public byte[] readAssetBytes(AssetRec rec) {
        if (rec == null) return new byte[0];
        Path p = resolveAssetPath(rec.tenantUuid, rec.relativePath);
        if (p == null || !Files.exists(p) || !Files.isRegularFile(p)) return new byte[0];
        try {
            return Files.readAllBytes(p);
        } catch (Exception ignored) {
            return new byte[0];
        }
    }

    public AssetRec saveTenantLogoUpload(String tenantUuid,
                                         String submittedFileName,
                                         String contentType,
                                         byte[] bytes,
                                         String actor) throws Exception {
        return saveAsset(tenantUuid, "tenant_logo", "", submittedFileName, contentType, bytes, "", actor);
    }

    public AssetRec saveUserPhotoUpload(String tenantUuid,
                                        String userUuid,
                                        String submittedFileName,
                                        String contentType,
                                        byte[] bytes,
                                        String actor) throws Exception {
        return saveAsset(tenantUuid, "user_photo", userUuid, submittedFileName, contentType, bytes, "", actor);
    }

    public AssetRec saveTenantLogoFromUrl(String tenantUuid, String sourceUrl, String actor) throws Exception {
        DownloadedImage downloaded = downloadImage(sourceUrl);
        return saveAsset(
                tenantUuid,
                "tenant_logo",
                "",
                downloaded.fileName,
                downloaded.contentType,
                downloaded.bytes,
                sourceUrl,
                actor
        );
    }

    public AssetRec saveUserPhotoFromUrl(String tenantUuid, String userUuid, String sourceUrl, String actor) throws Exception {
        DownloadedImage downloaded = downloadImage(sourceUrl);
        return saveAsset(
                tenantUuid,
                "user_photo",
                userUuid,
                downloaded.fileName,
                downloaded.contentType,
                downloaded.bytes,
                sourceUrl,
                actor
        );
    }

    public boolean clearTenantLogo(String tenantUuid) throws Exception {
        String tu = safeFileToken(tenantUuid);
        if (tu.isBlank()) return false;

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            AssetRec current = readAssetLocked(tu, "tenant_logo", "");
            boolean deleted = deleteAssetFile(current);
            Files.deleteIfExists(tenantMetaPath(tu));
            return deleted || current != null;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean clearUserPhoto(String tenantUuid, String userUuid) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String uu = safeFileToken(userUuid);
        if (tu.isBlank() || uu.isBlank()) return false;

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            AssetRec current = readAssetLocked(tu, "user_photo", uu);
            boolean deleted = deleteAssetFile(current);
            Files.deleteIfExists(userMetaPath(tu, uu));
            return deleted || current != null;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private AssetRec saveAsset(String tenantUuid,
                               String scope,
                               String userUuid,
                               String submittedFileName,
                               String contentType,
                               byte[] bytes,
                               String sourceUrl,
                               String actor) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String sc = normalizeScope(scope);
        String uu = "user_photo".equals(sc) ? safeFileToken(userUuid) : "";
        if (tu.isBlank()) throw new IllegalArgumentException("tenantUuid required");
        if ("user_photo".equals(sc) && uu.isBlank()) throw new IllegalArgumentException("userUuid required");

        ValidatedImage img = validateImage(submittedFileName, contentType, bytes);
        String rel = "tenant_logo".equals(sc)
                ? "tenant_logo." + img.extension
                : "users/" + uu + "." + img.extension;

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            Files.createDirectories(identityDir(tu));
            Files.createDirectories(identityUsersDir(tu));

            AssetRec prior = readAssetLocked(tu, sc, uu);
            if (prior != null && !safe(prior.relativePath).equals(rel)) {
                deleteAssetFile(prior);
            }

            Path p = resolveAssetPath(tu, rel);
            if (p == null) throw new IllegalStateException("Invalid asset path.");
            Files.createDirectories(p.getParent());
            Files.write(p, img.bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            LinkedHashMap<String, String> meta = new LinkedHashMap<String, String>();
            meta.put("scope", sc);
            meta.put("tenant_uuid", tu);
            meta.put("user_uuid", uu);
            meta.put("file_name", img.fileName);
            meta.put("mime_type", img.mimeType);
            meta.put("size_bytes", String.valueOf(img.bytes.length));
            meta.put("updated_at", Instant.now().toString());
            meta.put("source_url", safe(sourceUrl).trim());
            meta.put("updated_by", safe(actor).trim());
            meta.put("relative_path", rel);

            writeMeta(metaPath(tu, sc, uu), meta);
            return fromMeta(meta);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private AssetRec readAssetLocked(String tenantUuid, String scope, String userUuid) {
        LinkedHashMap<String, String> meta = readMeta(metaPath(tenantUuid, scope, userUuid));
        if (meta.isEmpty()) return null;
        AssetRec rec = fromMeta(meta);
        if (rec == null) return null;
        Path p = resolveAssetPath(tenantUuid, rec.relativePath);
        if (p == null || !Files.exists(p) || !Files.isRegularFile(p)) return null;
        return rec;
    }

    private static String normalizeScope(String scope) {
        String s = safe(scope).trim().toLowerCase(Locale.ROOT);
        if ("tenant_logo".equals(s)) return "tenant_logo";
        if ("user_photo".equals(s)) return "user_photo";
        return "";
    }

    private static Path metaPath(String tenantUuid, String scope, String userUuid) {
        String sc = normalizeScope(scope);
        if ("tenant_logo".equals(sc)) return tenantMetaPath(tenantUuid);
        if ("user_photo".equals(sc)) return userMetaPath(tenantUuid, userUuid);
        return null;
    }

    private static Path tenantMetaPath(String tenantUuid) {
        return identityDir(tenantUuid).resolve("tenant_logo.json");
    }

    private static Path userMetaPath(String tenantUuid, String userUuid) {
        return identityUsersDir(tenantUuid).resolve(safeFileToken(userUuid) + ".json");
    }

    private static Path resolveAssetPath(String tenantUuid, String relativePath) {
        String tu = safeFileToken(tenantUuid);
        String rel = safe(relativePath).replace('\\', '/').trim();
        if (tu.isBlank() || rel.isBlank()) return null;
        if (rel.contains("..")) return null;

        Path root = identityDir(tu).toAbsolutePath().normalize();
        Path resolved = root.resolve(rel).normalize();
        if (!resolved.startsWith(root)) return null;
        return resolved;
    }

    private static boolean deleteAssetFile(AssetRec rec) {
        if (rec == null) return false;
        Path p = resolveAssetPath(rec.tenantUuid, rec.relativePath);
        if (p == null) return false;
        try {
            return Files.deleteIfExists(p);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static LinkedHashMap<String, String> readMeta(Path p) {
        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
        if (p == null || !Files.exists(p) || !Files.isRegularFile(p)) return out;
        try {
            String raw = Files.readString(p, StandardCharsets.UTF_8);
            if (safe(raw).trim().isBlank()) return out;
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = JSON.readValue(raw, Map.class);
            if (parsed == null) return out;
            for (Map.Entry<String, Object> e : parsed.entrySet()) {
                if (e == null) continue;
                out.put(safe(e.getKey()), safe(e.getValue() == null ? "" : String.valueOf(e.getValue())));
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static void writeMeta(Path p, Map<String, String> meta) throws Exception {
        if (p == null) throw new IllegalArgumentException("meta path required");
        Files.createDirectories(p.getParent());
        String raw = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(meta == null ? Map.of() : meta);
        Files.writeString(
                p,
                raw,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        );
    }

    private static AssetRec fromMeta(Map<String, String> meta) {
        if (meta == null || meta.isEmpty()) return null;
        String scope = normalizeScope(meta.get("scope"));
        String tenantUuid = safeFileToken(meta.get("tenant_uuid"));
        String userUuid = safeFileToken(meta.get("user_uuid"));
        String fileName = safe(meta.get("file_name")).trim();
        String mimeType = safe(meta.get("mime_type")).trim();
        long size = parseLong(meta.get("size_bytes"), 0L);
        String updatedAt = safe(meta.get("updated_at")).trim();
        String sourceUrl = safe(meta.get("source_url")).trim();
        String updatedBy = safe(meta.get("updated_by")).trim();
        String relativePath = safe(meta.get("relative_path")).trim();

        if (scope.isBlank() || tenantUuid.isBlank() || relativePath.isBlank()) return null;
        if ("user_photo".equals(scope) && userUuid.isBlank()) return null;
        return new AssetRec(scope, tenantUuid, userUuid, fileName, mimeType, size, updatedAt, sourceUrl, updatedBy, relativePath);
    }

    private static ReentrantReadWriteLock lockFor(String tenantUuid) {
        return LOCKS.computeIfAbsent(safeFileToken(tenantUuid), k -> new ReentrantReadWriteLock());
    }

    private static Path identityDir(String tenantUuid) {
        return Paths.get("data", "tenants", safeFileToken(tenantUuid), "identity").toAbsolutePath();
    }

    private static Path identityUsersDir(String tenantUuid) {
        return identityDir(tenantUuid).resolve("users");
    }

    private static String safeFileToken(String s) {
        String t = safe(s).trim();
        if (t.isBlank()) return "";
        return t.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static long parseLong(String raw, long fallback) {
        try {
            return Long.parseLong(safe(raw).trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static final class ValidatedImage {
        final byte[] bytes;
        final String extension;
        final String mimeType;
        final String fileName;

        ValidatedImage(byte[] bytes, String extension, String mimeType, String fileName) {
            this.bytes = bytes == null ? new byte[0] : bytes;
            this.extension = safe(extension).trim();
            this.mimeType = safe(mimeType).trim();
            this.fileName = safe(fileName).trim();
        }
    }

    private static ValidatedImage validateImage(String submittedFileName, String providedContentType, byte[] bytes) {
        byte[] raw = bytes == null ? new byte[0] : bytes;
        if (raw.length == 0) throw new IllegalArgumentException("Image file is required.");
        if (raw.length > MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException("Image file exceeds max size of " + MAX_IMAGE_BYTES + " bytes.");
        }

        String extension = "";
        String mimeType = "";
        if (isPng(raw)) {
            extension = "png";
            mimeType = "image/png";
        } else if (isJpeg(raw)) {
            extension = "jpg";
            mimeType = "image/jpeg";
        } else if (isGif(raw)) {
            extension = "gif";
            mimeType = "image/gif";
        }

        if (extension.isBlank()) {
            String guessed = "";
            try (var in = new java.io.ByteArrayInputStream(raw)) {
                guessed = safe(URLConnection.guessContentTypeFromStream(in)).trim().toLowerCase(Locale.ROOT);
            } catch (java.io.IOException ignored) {
            }
            if ("image/png".equals(guessed)) {
                extension = "png";
                mimeType = "image/png";
            } else if ("image/jpeg".equals(guessed)) {
                extension = "jpg";
                mimeType = "image/jpeg";
            } else if ("image/gif".equals(guessed)) {
                extension = "gif";
                mimeType = "image/gif";
            }
        }

        if (extension.isBlank() || mimeType.isBlank()) {
            throw new IllegalArgumentException("Unsupported image format. Use PNG, JPEG, or GIF.");
        }

        String normalizedName = normalizeFileName(submittedFileName, extension);
        if (normalizedName.isBlank()) normalizedName = "image." + extension;

        String provided = safe(providedContentType).trim().toLowerCase(Locale.ROOT);
        if (!provided.isBlank() && !provided.startsWith("multipart/") && !provided.startsWith("application/octet-stream")) {
            String base = provided;
            int semi = base.indexOf(';');
            if (semi >= 0) base = base.substring(0, semi).trim();
            if (!base.isBlank() && !base.equals(mimeType)) {
                // Keep detected mime type; mismatches are common and should not fail valid images.
            }
        }
        return new ValidatedImage(raw, extension, mimeType, normalizedName);
    }

    private static String normalizeFileName(String rawName, String requiredExt) {
        String n = safe(rawName).trim();
        if (n.isBlank()) n = "image";

        int slash = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
        if (slash >= 0 && slash + 1 < n.length()) n = n.substring(slash + 1);

        StringBuilder sb = new StringBuilder(Math.min(140, n.length() + 8));
        for (int i = 0; i < n.length(); i++) {
            char ch = n.charAt(i);
            boolean ok = (ch >= 'A' && ch <= 'Z')
                    || (ch >= 'a' && ch <= 'z')
                    || (ch >= '0' && ch <= '9')
                    || ch == '.' || ch == '_' || ch == '-';
            sb.append(ok ? ch : '_');
        }
        String out = sb.toString().trim();
        if (out.isBlank()) out = "image";
        if (out.length() > 120) out = out.substring(0, 120);

        String ext = safe(requiredExt).trim().toLowerCase(Locale.ROOT);
        if (ext.isBlank()) return out;

        String lower = out.toLowerCase(Locale.ROOT);
        if (lower.endsWith("." + ext)) return out;

        int dot = out.lastIndexOf('.');
        if (dot > 0) out = out.substring(0, dot);
        if (out.isBlank()) out = "image";
        return out + "." + ext;
    }

    private static boolean isPng(byte[] bytes) {
        if (bytes == null || bytes.length < 8) return false;
        return (bytes[0] & 0xFF) == 0x89
                && (bytes[1] & 0xFF) == 0x50
                && (bytes[2] & 0xFF) == 0x4E
                && (bytes[3] & 0xFF) == 0x47
                && (bytes[4] & 0xFF) == 0x0D
                && (bytes[5] & 0xFF) == 0x0A
                && (bytes[6] & 0xFF) == 0x1A
                && (bytes[7] & 0xFF) == 0x0A;
    }

    private static boolean isJpeg(byte[] bytes) {
        if (bytes == null || bytes.length < 3) return false;
        return (bytes[0] & 0xFF) == 0xFF
                && (bytes[1] & 0xFF) == 0xD8
                && (bytes[2] & 0xFF) == 0xFF;
    }

    private static boolean isGif(byte[] bytes) {
        if (bytes == null || bytes.length < 6) return false;
        return (bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == '8'
                && ((bytes[4] == '7' && bytes[5] == 'a') || (bytes[4] == '9' && bytes[5] == 'a')));
    }

    private static final class DownloadedImage {
        final byte[] bytes;
        final String fileName;
        final String contentType;

        DownloadedImage(byte[] bytes, String fileName, String contentType) {
            this.bytes = bytes == null ? new byte[0] : bytes;
            this.fileName = safe(fileName);
            this.contentType = safe(contentType);
        }
    }

    private static DownloadedImage downloadImage(String sourceUrl) throws Exception {
        String raw = safe(sourceUrl).trim();
        if (raw.isBlank()) throw new IllegalArgumentException("Image URL is required.");

        URI uri;
        try {
            uri = URI.create(raw);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid image URL.");
        }
        String scheme = safe(uri.getScheme()).trim().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("Image URL must use http or https.");
        }

        URI current = uri;
        for (int i = 0; i <= MAX_REDIRECTS; i++) {
            validateRemoteUri(current);
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) current.toURL().openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(URL_CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(URL_READ_TIMEOUT_MS);
                conn.setInstanceFollowRedirects(false);
                conn.setRequestProperty("User-Agent", "Controversies/identity-assets");
                conn.connect();

                int status = conn.getResponseCode();
                if (status >= 300 && status < 400) {
                    String location = safe(conn.getHeaderField("Location")).trim();
                    if (location.isBlank()) throw new IllegalArgumentException("Image URL redirect is missing Location header.");
                    current = current.resolve(location);
                    continue;
                }
                if (status < 200 || status > 299) {
                    throw new IllegalArgumentException("Image URL download failed (HTTP " + status + ").");
                }

                long contentLength = parseLong(conn.getHeaderField("Content-Length"), -1L);
                if (contentLength > MAX_IMAGE_BYTES) {
                    throw new IllegalArgumentException("Image URL content exceeds max size of " + MAX_IMAGE_BYTES + " bytes.");
                }

                byte[] bytes;
                try (InputStream in = conn.getInputStream()) {
                    bytes = readBytesLimited(in, MAX_IMAGE_BYTES);
                }
                String contentType = safe(conn.getContentType()).trim();
                String fileName = fileNameFromUri(current);
                return new DownloadedImage(bytes, fileName, contentType);
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        throw new IllegalArgumentException("Image URL redirected too many times.");
    }

    private static byte[] readBytesLimited(InputStream in, long limit) throws Exception {
        if (in == null) return new byte[0];
        long cap = Math.max(1L, limit);
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        byte[] buf = new byte[8192];
        long total = 0L;
        while (true) {
            int n = in.read(buf);
            if (n < 0) break;
            total += n;
            if (total > cap) {
                throw new IllegalArgumentException("Image content exceeds max size of " + limit + " bytes.");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static String fileNameFromUri(URI uri) {
        if (uri == null) return "image";
        String path = safe(uri.getPath()).trim();
        if (path.isBlank()) return "image";
        int slash = path.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < path.length()) return path.substring(slash + 1);
        return path;
    }

    private static void validateRemoteUri(URI uri) {
        if (uri == null) throw new IllegalArgumentException("Invalid image URL.");
        String host = safe(uri.getHost()).trim();
        if (host.isBlank()) throw new IllegalArgumentException("Image URL host is required.");
        String lowerHost = host.toLowerCase(Locale.ROOT);
        if ("localhost".equals(lowerHost) || lowerHost.endsWith(".local")) {
            throw new IllegalArgumentException("Local/private image URLs are not allowed.");
        }

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Unable to resolve image URL host.");
        }
        if (addresses == null || addresses.length == 0) {
            throw new IllegalArgumentException("Unable to resolve image URL host.");
        }
        for (InetAddress addr : addresses) {
            if (addr == null) continue;
            if (addr.isAnyLocalAddress()
                    || addr.isLoopbackAddress()
                    || addr.isSiteLocalAddress()
                    || addr.isLinkLocalAddress()
                    || addr.isMulticastAddress()) {
                throw new IllegalArgumentException("Local/private image URLs are not allowed.");
            }
            if (addr instanceof Inet4Address) {
                byte[] b = addr.getAddress();
                int first = b[0] & 0xFF;
                int second = b[1] & 0xFF;
                if (first == 127
                        || first == 10
                        || (first == 172 && second >= 16 && second <= 31)
                        || (first == 192 && second == 168)
                        || (first == 169 && second == 254)
                        || (first == 100 && second >= 64 && second <= 127)) {
                    throw new IllegalArgumentException("Local/private image URLs are not allowed.");
                }
            } else if (addr instanceof Inet6Address) {
                byte[] b = addr.getAddress();
                if (b != null && b.length > 0) {
                    int first = b[0] & 0xFF;
                    if ((first & 0xFE) == 0xFC) {
                        throw new IllegalArgumentException("Local/private image URLs are not allowed.");
                    }
                }
            }
        }
    }
}
