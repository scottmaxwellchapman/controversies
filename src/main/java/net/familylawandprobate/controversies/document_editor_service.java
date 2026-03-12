package net.familylawandprobate.controversies;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * ONLYOFFICE-backed WYSIWYG editor flow for document part versions.
 *
 * Supports DOCX, DOC, RTF/RTX, and ODT/ODF as editable sources.
 */
public final class document_editor_service {

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final SecureRandom RNG = new SecureRandom();
    private static final DateTimeFormatter LABEL_TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private static final long DEFAULT_SESSION_TTL_SECONDS = 8L * 60L * 60L;
    private static final long SESSION_RETENTION_SECONDS = 72L * 60L * 60L;

    private static final Path SESSION_ROOT = Paths.get("data", "sec", "editor_sessions").toAbsolutePath().normalize();
    private static final Path SESSION_FILES = SESSION_ROOT.resolve("files").toAbsolutePath().normalize();

    private static final part_versions VERSIONS = part_versions.defaultStore();
    private static final document_parts PARTS = document_parts.defaultStore();

    public static document_editor_service defaultService() {
        return new document_editor_service();
    }

    public static final class LaunchResult {
        public boolean ok;
        public String provider;
        public String message;
        public String token;
        public String scriptUrl;
        public String configJson;
        public String fileType;
        public String versionUuid;
    }

    public static final class SessionRecord {
        public String token;
        public String provider;
        public String tenantUuid;
        public String matterUuid;
        public String docUuid;
        public String partUuid;
        public String versionUuid;
        public String sourcePath;
        public String servedPath;
        public String sourceMimeType;
        public String sourceLabel;
        public String fileType;
        public String actor;
        public String actorDisplay;
        public String createdAt;
        public String expiresAt;
        public String lastSavedChecksum;
        public String lastSavedVersionUuid;
        public String lastSavedAt;
    }

    public static final class FileAccessResult {
        public Path filePath;
        public String mimeType;
        public String downloadName;
        public SessionRecord session;
    }

    public static final class CallbackResult {
        public int status;
        public boolean saved;
        public String message;
        public String versionUuid;
    }

    private static final class ProviderConfig {
        String provider;
        String docServerUrl;
        String publicBaseUrl;
        String jwtSecret;
        long sessionTtlSeconds;
    }

    public static boolean isWysiwygEditable(part_versions.VersionRec rec) {
        if (rec == null) return false;
        Path p = pdf_redaction_service.resolveStoragePath(rec.storagePath);
        return isWysiwygEditable(p, rec.mimeType);
    }

    public static boolean isWysiwygEditable(Path sourcePath, String mimeType) {
        return !detectEditableFileType(sourcePath, mimeType).isBlank();
    }

    public static String inferRequestBaseUrl(HttpServletRequest req) {
        if (req == null) return "";
        String scheme = safe(req.getScheme()).trim().toLowerCase(Locale.ROOT);
        String host = safe(req.getServerName()).trim();
        int port = req.getServerPort();
        String ctx = safe(req.getContextPath()).trim();
        if (host.isBlank() || scheme.isBlank()) return "";
        boolean defaultPort = ("https".equals(scheme) && port == 443)
                || ("http".equals(scheme) && port == 80);
        StringBuilder sb = new StringBuilder();
        sb.append(scheme).append("://").append(host);
        if (!defaultPort && port > 0) sb.append(":").append(port);
        if (!ctx.isBlank()) sb.append(ctx);
        return trimTrailingSlash(sb.toString());
    }

    public LaunchResult prepareOnlyOfficeLaunch(String tenantUuid,
                                                String matterUuid,
                                                String docUuid,
                                                String partUuid,
                                                String versionUuid,
                                                String actor,
                                                String actorDisplay,
                                                String requestBaseUrl) throws Exception {
        cleanupExpiredSessions();
        ProviderConfig cfg = loadProviderConfig();
        if (!"onlyoffice".equals(cfg.provider) || cfg.docServerUrl.isBlank()) {
            LaunchResult out = new LaunchResult();
            out.ok = false;
            out.provider = "onlyoffice";
            out.message = "ONLYOFFICE is not configured. Set CONTROVERSIES_ONLYOFFICE_DOCSERVER_URL.";
            return out;
        }

        String publicBase = cfg.publicBaseUrl.isBlank()
                ? trimTrailingSlash(safe(requestBaseUrl).trim())
                : trimTrailingSlash(cfg.publicBaseUrl);
        if (publicBase.isBlank()) {
            LaunchResult out = new LaunchResult();
            out.ok = false;
            out.provider = "onlyoffice";
            out.message = "Public base URL is unavailable for editor callbacks.";
            return out;
        }

        part_versions.VersionRec source = VERSIONS.get(tenantUuid, matterUuid, docUuid, partUuid, versionUuid);
        if (source == null) throw new IllegalArgumentException("Version not found.");
        Path sourcePath = pdf_redaction_service.resolveStoragePath(source.storagePath);
        pdf_redaction_service.requirePathWithinTenant(sourcePath, tenantUuid, "Version file path");
        if (sourcePath == null || !Files.isRegularFile(sourcePath)) {
            throw new IllegalArgumentException("Version file not found.");
        }

        String fileType = detectEditableFileType(sourcePath, source.mimeType);
        if (fileType.isBlank()) {
            throw new IllegalArgumentException("WYSIWYG editing supports DOCX, DOC, RTF/RTX, and ODT/ODF files only.");
        }

        String token = randomToken();
        Path servedPath = materializeServedPath(token, sourcePath, fileType);
        SessionRecord session = new SessionRecord();
        session.token = token;
        session.provider = "onlyoffice";
        session.tenantUuid = safe(tenantUuid).trim();
        session.matterUuid = safe(matterUuid).trim();
        session.docUuid = safe(docUuid).trim();
        session.partUuid = safe(partUuid).trim();
        session.versionUuid = safe(versionUuid).trim();
        session.sourcePath = sourcePath.toString();
        session.servedPath = servedPath.toString();
        session.sourceMimeType = safe(source.mimeType).trim();
        session.sourceLabel = safe(source.versionLabel).trim();
        session.fileType = fileType;
        session.actor = normalizedActor(actor);
        session.actorDisplay = safe(actorDisplay).trim();
        session.createdAt = app_clock.now().toString();
        session.expiresAt = Instant.ofEpochSecond(app_clock.now().getEpochSecond() + cfg.sessionTtlSeconds).toString();
        writeSession(session);

        String fileUrl = publicBase + "/version_editor/file?token=" + enc(token);
        String callbackUrl = publicBase + "/version_editor/callback?token=" + enc(token);
        String scriptUrl = trimTrailingSlash(cfg.docServerUrl) + "/web-apps/apps/api/documents/api.js";

        LinkedHashMap<String, Object> config = new LinkedHashMap<String, Object>();
        LinkedHashMap<String, Object> document = new LinkedHashMap<String, Object>();
        document.put("title", buildDownloadName(source.versionLabel, fileType));
        document.put("url", fileUrl);
        document.put("fileType", fileType);
        document.put("key", buildDocumentKey(source, session));
        config.put("documentType", "word");
        config.put("document", document);

        LinkedHashMap<String, Object> editorConfig = new LinkedHashMap<String, Object>();
        editorConfig.put("callbackUrl", callbackUrl);
        editorConfig.put("mode", "edit");
        editorConfig.put("lang", "en");
        LinkedHashMap<String, Object> user = new LinkedHashMap<String, Object>();
        user.put("id", normalizedActor(actor));
        user.put("name", safe(actorDisplay).trim().isBlank() ? normalizedActor(actor) : safe(actorDisplay).trim());
        editorConfig.put("user", user);
        LinkedHashMap<String, Object> customization = new LinkedHashMap<String, Object>();
        customization.put("autosave", true);
        customization.put("forcesave", true);
        editorConfig.put("customization", customization);
        config.put("editorConfig", editorConfig);

        LinkedHashMap<String, Object> permissions = new LinkedHashMap<String, Object>();
        permissions.put("edit", true);
        permissions.put("download", true);
        permissions.put("print", true);
        permissions.put("comment", false);
        permissions.put("review", false);
        config.put("permissions", permissions);

        if (!cfg.jwtSecret.isBlank()) {
            String jwt = signJwt(config, cfg.jwtSecret);
            config.put("token", jwt);
        }

        LaunchResult out = new LaunchResult();
        out.ok = true;
        out.provider = "onlyoffice";
        out.message = "Editor session prepared.";
        out.token = token;
        out.fileType = fileType;
        out.versionUuid = safe(versionUuid).trim();
        out.scriptUrl = scriptUrl;
        out.configJson = JSON.writeValueAsString(config);
        return out;
    }

    public FileAccessResult resolveEditorFile(String token) throws Exception {
        SessionRecord session = readSession(token, true);
        Path served = pathOrNull(session.servedPath);
        if (served == null || !Files.isRegularFile(served)) {
            throw new IllegalArgumentException("Editor source file is unavailable.");
        }
        if (!isPathWithinTenantOrSessionRoot(served, session.tenantUuid)) {
            throw new SecurityException("Editor source path is outside allowed boundaries.");
        }

        FileAccessResult out = new FileAccessResult();
        out.filePath = served;
        out.mimeType = mimeForFileType(session.fileType);
        out.downloadName = buildDownloadName(session.sourceLabel, session.fileType);
        out.session = session;
        return out;
    }

    public CallbackResult handleOnlyOfficeCallback(String token, Map<String, Object> payload) throws Exception {
        SessionRecord session = readSession(token, true);
        int status = asInt(payload == null ? null : payload.get("status"), -1);
        CallbackResult out = new CallbackResult();
        out.status = status;
        out.saved = false;
        out.message = "No save action required.";
        out.versionUuid = "";

        if (status != 2 && status != 6) {
            return out;
        }

        String downloadUrl = asString(payload == null ? null : payload.get("url")).trim();
        if (downloadUrl.isBlank()) {
            throw new IllegalArgumentException("Callback payload is missing the edited file URL.");
        }
        String callbackFileType = asString(payload == null ? null : payload.get("filetype")).trim().toLowerCase(Locale.ROOT);
        String outputFileType = normalizeOutputFileType(callbackFileType, session.fileType);

        byte[] editedBytes = downloadEditedBytes(downloadUrl);
        if (editedBytes.length == 0) throw new IllegalStateException("Edited file download returned empty content.");
        String checksum = sha256(editedBytes);

        List<part_versions.VersionRec> rows = VERSIONS.listAll(
                safe(session.tenantUuid).trim(),
                safe(session.matterUuid).trim(),
                safe(session.docUuid).trim(),
                safe(session.partUuid).trim()
        );
        part_versions.VersionRec newest = rows.isEmpty() ? null : rows.get(0);
        if (newest != null && checksum.equalsIgnoreCase(safe(newest.checksum).trim())) {
            out.saved = false;
            out.message = "Duplicate save ignored.";
            out.versionUuid = safe(newest.uuid).trim();
            return out;
        }

        Path partFolder = PARTS.partFolder(
                safe(session.tenantUuid).trim(),
                safe(session.matterUuid).trim(),
                safe(session.docUuid).trim(),
                safe(session.partUuid).trim()
        );
        if (partFolder == null) throw new IllegalArgumentException("Part folder unavailable.");
        Path outputDir = partFolder.resolve("version_files");
        Files.createDirectories(outputDir);
        String outputName = UUID.randomUUID().toString().replace("-", "_")
                + "__" + safeFileStem(session.sourceLabel) + "_edited." + outputFileType;
        Path outputPath = outputDir.resolve(outputName);
        Files.write(outputPath, editedBytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);

        String actor = normalizedActor(session.actor);
        if (actor.isBlank()) actor = "wysiwyg-editor";

        part_versions.VersionRec created = VERSIONS.create(
                safe(session.tenantUuid).trim(),
                safe(session.matterUuid).trim(),
                safe(session.docUuid).trim(),
                safe(session.partUuid).trim(),
                buildEditedLabel(session.sourceLabel),
                "wysiwyg.onlyoffice",
                mimeForFileType(outputFileType),
                checksum,
                String.valueOf(editedBytes.length),
                outputPath.toUri().toString(),
                actor,
                "Saved by ONLYOFFICE callback (status " + status + ") from version " + safe(session.versionUuid).trim() + ".",
                true
        );

        session.lastSavedChecksum = checksum;
        session.lastSavedAt = app_clock.now().toString();
        session.lastSavedVersionUuid = safe(created == null ? "" : created.uuid).trim();
        writeSession(session);

        out.saved = true;
        out.message = "Saved as new version.";
        out.versionUuid = safe(created == null ? "" : created.uuid).trim();
        return out;
    }

    public SessionRecord readSession(String token) throws Exception {
        return readSession(token, false);
    }

    private SessionRecord readSession(String token, boolean requireFresh) throws Exception {
        String t = sanitizeToken(token);
        if (t.isBlank()) throw new IllegalArgumentException("Missing editor session token.");
        Path p = sessionPath(t);
        if (!Files.isRegularFile(p)) throw new IllegalArgumentException("Editor session not found.");
        SessionRecord rec = JSON.readValue(Files.readString(p, StandardCharsets.UTF_8), SessionRecord.class);
        if (rec == null || !t.equals(safe(rec.token).trim())) {
            throw new IllegalArgumentException("Editor session metadata is invalid.");
        }
        Instant exp = parseInstant(rec.expiresAt);
        if (requireFresh && exp != null && app_clock.now().isAfter(exp)) {
            throw new SecurityException("Editor session expired.");
        }
        return rec;
    }

    private static ProviderConfig loadProviderConfig() {
        ProviderConfig out = new ProviderConfig();
        out.provider = configValue("CONTROVERSIES_EDITOR_PROVIDER", "controversies.editor.provider");
        if (out.provider.isBlank()) out.provider = "onlyoffice";
        out.provider = out.provider.toLowerCase(Locale.ROOT);
        out.docServerUrl = trimTrailingSlash(configValue("CONTROVERSIES_ONLYOFFICE_DOCSERVER_URL", "controversies.onlyoffice.docserver.url"));
        out.publicBaseUrl = trimTrailingSlash(configValue("CONTROVERSIES_EDITOR_PUBLIC_BASE_URL", "controversies.editor.public.base.url"));
        out.jwtSecret = configValue("CONTROVERSIES_ONLYOFFICE_JWT_SECRET", "controversies.onlyoffice.jwt.secret");
        out.sessionTtlSeconds = longOrDefault(configValue(
                "CONTROVERSIES_EDITOR_SESSION_TTL_SECONDS",
                "controversies.editor.session.ttl.seconds"
        ), DEFAULT_SESSION_TTL_SECONDS);
        if (out.sessionTtlSeconds <= 0L) out.sessionTtlSeconds = DEFAULT_SESSION_TTL_SECONDS;
        if (out.sessionTtlSeconds > 48L * 60L * 60L) out.sessionTtlSeconds = 48L * 60L * 60L;
        return out;
    }

    private static String detectEditableFileType(Path sourcePath, String mimeType) {
        String ext = extension(sourcePath);
        if ("docx".equals(ext) || "doc".equals(ext) || "odt".equals(ext) || "rtf".equals(ext)) return ext;
        if ("rtx".equals(ext)) return "rtf";
        if ("odf".equals(ext)) return "odt";

        String mime = safe(mimeType).trim().toLowerCase(Locale.ROOT);
        if (mime.contains("wordprocessingml")) return "docx";
        if (mime.contains("msword")) return "doc";
        if (mime.contains("opendocument")) return "odt";
        if (mime.contains("rtf") || mime.contains("richtext")) return "rtf";
        return "";
    }

    private static String normalizeOutputFileType(String callbackFileType, String fallbackFileType) {
        String raw = safe(callbackFileType).trim().toLowerCase(Locale.ROOT);
        if ("docx".equals(raw) || "doc".equals(raw) || "odt".equals(raw) || "rtf".equals(raw)) return raw;
        if ("rtx".equals(raw)) return "rtf";
        if ("odf".equals(raw)) return "odt";
        String fallback = safe(fallbackFileType).trim().toLowerCase(Locale.ROOT);
        if ("docx".equals(fallback) || "doc".equals(fallback) || "odt".equals(fallback) || "rtf".equals(fallback)) {
            return fallback;
        }
        return "docx";
    }

    private static Path materializeServedPath(String token, Path sourcePath, String fileType) throws Exception {
        String ext = extension(sourcePath);
        boolean alias = ext.isBlank()
                || ("rtx".equals(ext) && "rtf".equals(fileType))
                || ("odf".equals(ext) && "odt".equals(fileType));
        if (!alias) return sourcePath.toAbsolutePath().normalize();

        Files.createDirectories(SESSION_FILES);
        Path out = SESSION_FILES.resolve(sanitizeToken(token) + "." + fileType).toAbsolutePath().normalize();
        Files.copy(sourcePath, out, StandardCopyOption.REPLACE_EXISTING);
        return out;
    }

    private static boolean isPathWithinTenantOrSessionRoot(Path path, String tenantUuid) {
        Path p = path == null ? null : path.toAbsolutePath().normalize();
        if (p == null) return false;
        if (p.startsWith(SESSION_FILES)) return true;
        return pdf_redaction_service.isPathWithinTenant(p, tenantUuid);
    }

    private static String buildDownloadName(String label, String fileType) {
        String stem = safeFileStem(label);
        return stem + "." + safe(fileType).trim().toLowerCase(Locale.ROOT);
    }

    private static String safeFileStem(String raw) {
        String v = safe(raw).trim();
        if (v.isBlank()) v = "version";
        v = v.replaceAll("[^A-Za-z0-9._-]", "_");
        while (v.contains("__")) v = v.replace("__", "_");
        if (v.startsWith(".")) v = "version" + v;
        if (v.length() > 80) v = v.substring(0, 80);
        if (v.isBlank()) v = "version";
        return v;
    }

    private static String buildEditedLabel(String sourceLabel) {
        String stem = safe(sourceLabel).trim();
        if (stem.isBlank()) stem = "Version";
        return stem + " (Edited " + LABEL_TS.format(app_clock.now()) + ")";
    }

    private static String buildDocumentKey(part_versions.VersionRec source, SessionRecord session) throws Exception {
        String payload = safe(session.tenantUuid).trim() + "|"
                + safe(session.matterUuid).trim() + "|"
                + safe(session.docUuid).trim() + "|"
                + safe(session.partUuid).trim() + "|"
                + safe(source == null ? "" : source.uuid).trim() + "|"
                + safe(source == null ? "" : source.checksum).trim() + "|"
                + safe(source == null ? "" : source.fileSizeBytes).trim();
        return sha256(payload.getBytes(StandardCharsets.UTF_8)).substring(0, 40);
    }

    private static String mimeForFileType(String fileType) {
        String ft = safe(fileType).trim().toLowerCase(Locale.ROOT);
        if ("docx".equals(ft)) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if ("doc".equals(ft)) return "application/msword";
        if ("odt".equals(ft)) return "application/vnd.oasis.opendocument.text";
        if ("rtf".equals(ft)) return "application/rtf";
        return "application/octet-stream";
    }

    private static byte[] downloadEditedBytes(String rawUrl) throws Exception {
        String u = safe(rawUrl).trim();
        if (u.isBlank()) throw new IllegalArgumentException("Edited file URL is missing.");
        URI uri = URI.create(u);
        String scheme = safe(uri.getScheme()).trim().toLowerCase(Locale.ROOT);
        if ("data".equals(scheme)) {
            return decodeDataUrl(u);
        }
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("Edited file URL must use HTTP or HTTPS.");
        }
        HttpRequest req = HttpRequest.newBuilder(uri)
                .GET()
                .timeout(Duration.ofSeconds(60))
                .build();
        HttpResponse<byte[]> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
        int status = resp.statusCode();
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("Edited file download failed (HTTP " + status + ").");
        }
        byte[] body = resp.body();
        return body == null ? new byte[0] : body;
    }

    private static byte[] decodeDataUrl(String raw) {
        String value = safe(raw).trim();
        int comma = value.indexOf(',');
        if (comma < 5) throw new IllegalArgumentException("Invalid data URL payload.");
        String meta = value.substring(5, comma).toLowerCase(Locale.ROOT);
        String data = value.substring(comma + 1);
        if (meta.contains(";base64")) {
            try {
                return Base64.getDecoder().decode(data);
            } catch (Exception ex) {
                throw new IllegalArgumentException("Invalid base64 in callback data URL.");
            }
        }
        return URLDecoder.decode(data, StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8);
    }

    private static String signJwt(Map<String, Object> payload, String secret) throws Exception {
        String headerJson = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
        String header = base64Url(headerJson.getBytes(StandardCharsets.UTF_8));
        String body = base64Url(JSON.writeValueAsBytes(payload));
        byte[] sig = hmacSha256((header + "." + body).getBytes(StandardCharsets.UTF_8), secret.getBytes(StandardCharsets.UTF_8));
        return header + "." + body + "." + base64Url(sig);
    }

    private static byte[] hmacSha256(byte[] data, byte[] key) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data == null ? new byte[0] : data);
    }

    private static String base64Url(byte[] raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw == null ? new byte[0] : raw);
    }

    private static void writeSession(SessionRecord session) throws Exception {
        Files.createDirectories(SESSION_ROOT);
        String token = sanitizeToken(session == null ? "" : session.token);
        if (token.isBlank()) throw new IllegalArgumentException("Missing editor session token.");
        String json = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(session);
        document_workflow_support.writeAtomic(sessionPath(token), json);
    }

    private static Path sessionPath(String token) {
        return SESSION_ROOT.resolve(sanitizeToken(token) + ".json").toAbsolutePath().normalize();
    }

    private static void cleanupExpiredSessions() {
        try {
            Files.createDirectories(SESSION_ROOT);
            Files.createDirectories(SESSION_FILES);
        } catch (Exception ignored) {
            return;
        }

        long cutoff = app_clock.now().minusSeconds(SESSION_RETENTION_SECONDS).toEpochMilli();
        ArrayList<Path> tokensToDelete = new ArrayList<Path>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(SESSION_ROOT, "*.json")) {
            for (Path p : ds) {
                try {
                    if (!Files.isRegularFile(p)) continue;
                    long modified = Files.getLastModifiedTime(p).toMillis();
                    if (modified >= cutoff) continue;
                    tokensToDelete.add(p);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
            return;
        }

        for (Path p : tokensToDelete) {
            String name = safe(p.getFileName() == null ? "" : p.getFileName().toString()).trim();
            String token = name.endsWith(".json") ? name.substring(0, name.length() - 5) : "";
            try {
                Files.deleteIfExists(p);
            } catch (Exception ignored) {
            }
            if (token.isBlank()) continue;
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(SESSION_FILES, token + ".*")) {
                for (Path f : ds) {
                    try {
                        Files.deleteIfExists(f);
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ignored) {
            }
        }
    }

    private static String randomToken() {
        byte[] raw = new byte[32];
        RNG.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private static String sanitizeToken(String token) {
        String t = safe(token).trim();
        if (!t.matches("[A-Za-z0-9_-]{20,200}")) return "";
        return t;
    }

    private static int asInt(Object value, int d) {
        try {
            if (value instanceof Number n) return n.intValue();
            String raw = asString(value).trim();
            if (raw.isBlank()) return d;
            return Integer.parseInt(raw);
        } catch (Exception ex) {
            return d;
        }
    }

    private static long longOrDefault(String raw, long d) {
        try {
            String v = safe(raw).trim();
            if (v.isBlank()) return d;
            return Long.parseLong(v);
        } catch (Exception ex) {
            return d;
        }
    }

    private static String normalizedActor(String actor) {
        String v = safe(actor).trim().toLowerCase(Locale.ROOT);
        if (v.isBlank()) return "editor-user";
        if (v.length() > 160) return v.substring(0, 160);
        return v;
    }

    private static String extension(Path p) {
        if (p == null || p.getFileName() == null) return "";
        String name = safe(p.getFileName().toString()).trim();
        int idx = name.lastIndexOf('.');
        if (idx < 0 || idx + 1 >= name.length()) return "";
        return name.substring(idx + 1).trim().toLowerCase(Locale.ROOT);
    }

    private static String configValue(String envKey, String propKey) {
        String v = safe(System.getenv(envKey)).trim();
        if (!v.isBlank()) return v;
        return safe(System.getProperty(propKey)).trim();
    }

    private static String trimTrailingSlash(String raw) {
        String v = safe(raw).trim();
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        return v;
    }

    private static String enc(String s) {
        return URLEncoder.encode(safe(s), StandardCharsets.UTF_8);
    }

    private static Path pathOrNull(String raw) {
        try {
            String v = safe(raw).trim();
            if (v.isBlank()) return null;
            Path p = Paths.get(v);
            if (!p.isAbsolute()) p = p.toAbsolutePath();
            return p.normalize();
        } catch (Exception ex) {
            return null;
        }
    }

    private static Instant parseInstant(String raw) {
        try {
            String v = safe(raw).trim();
            if (v.isBlank()) return null;
            return Instant.parse(v);
        } catch (Exception ex) {
            return null;
        }
    }

    private static String sha256(byte[] raw) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(raw == null ? new byte[0] : raw);
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) sb.append(String.format(Locale.ROOT, "%02x", b));
        return sb.toString();
    }

    private static String asString(Object value) {
        if (value == null) return "";
        if (value instanceof String s) return s;
        return String.valueOf(value);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
