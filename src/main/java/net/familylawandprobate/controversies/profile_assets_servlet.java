package net.familylawandprobate.controversies;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.Part;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;

/**
 * profile_assets_servlet
 *
 * Endpoints:
 *  GET  /profile_assets?action=tenant_logo
 *  GET  /profile_assets?action=user_photo[&user_uuid=...]
 *  POST /profile_assets?action=upload_tenant_logo (multipart file part: file)
 *  POST /profile_assets?action=import_tenant_logo_url (source_url)
 *  POST /profile_assets?action=clear_tenant_logo
 *  POST /profile_assets?action=upload_user_photo (multipart file part: file, optional user_uuid)
 *  POST /profile_assets?action=import_user_photo_url (source_url, optional user_uuid)
 *  POST /profile_assets?action=clear_user_photo (optional user_uuid)
 */
public final class profile_assets_servlet extends HttpServlet {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String S_TENANT_UUID = "tenant.uuid";
    private static final String S_USER_UUID = users_roles.S_USER_UUID;
    private static final String S_USER_EMAIL = users_roles.S_USER_EMAIL;

    private final profile_assets store = profile_assets.defaultStore();
    private final activity_log logs = activity_log.defaultStore();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            handle(req, resp);
        } catch (ServletException ex) {
            throw new IOException(ex);
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            handle(req, resp);
        } catch (ServletException ex) {
            throw new IOException(ex);
        }
    }

    private void handle(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException {
        HttpSession session = req.getSession(false);
        security.sec_bind(req, resp, null, session);
        if (!security.require_login()) return;

        String tenantUuid = safe(session == null ? "" : (String) session.getAttribute(S_TENANT_UUID)).trim();
        String actorUserUuid = safe(session == null ? "" : (String) session.getAttribute(S_USER_UUID)).trim();
        String actorEmail = safe(session == null ? "" : (String) session.getAttribute(S_USER_EMAIL)).trim();
        String actor = actorEmail.isBlank() ? actorUserUuid : actorEmail;
        if (tenantUuid.isBlank()) {
            resp.sendError(401);
            return;
        }

        String method = safe(req.getMethod()).trim().toUpperCase(Locale.ROOT);
        String action = safe(req.getParameter("action")).trim().toLowerCase(Locale.ROOT);
        if (action.isBlank()) {
            if ("GET".equals(method)) action = "tenant_logo";
            else {
                writeError(resp, 400, "bad_request", "Action is required.");
                return;
            }
        }

        try {
            if ("GET".equals(method)) {
                if ("tenant_logo".equals(action)) {
                    serveTenantLogo(req, resp, tenantUuid);
                    return;
                }
                if ("user_photo".equals(action)) {
                    serveUserPhoto(req, resp, tenantUuid, actorUserUuid);
                    return;
                }
                writeError(resp, 404, "not_found", "Unknown action.");
                return;
            }

            switch (action) {
                case "upload_tenant_logo" -> {
                    requireTenantIdentityManage(session);
                    Part filePart = safePart(req, "file");
                    if (filePart == null) throw new IllegalArgumentException("Logo file is required.");
                    byte[] bytes = readBytes(filePart);
                    profile_assets.AssetRec rec = store.saveTenantLogoUpload(
                            tenantUuid,
                            safe(filePart.getSubmittedFileName()),
                            safe(filePart.getContentType()),
                            bytes,
                            actor
                    );
                    logVerbose("identity.tenant_logo.updated", tenantUuid, actorUserUuid, rec);
                    writeSuccessOrRedirect(req, resp, rec, "tenant_fields.jsp", "tenant_logo_saved");
                    return;
                }
                case "import_tenant_logo_url" -> {
                    requireTenantIdentityManage(session);
                    String sourceUrl = safe(req.getParameter("source_url")).trim();
                    profile_assets.AssetRec rec = store.saveTenantLogoFromUrl(tenantUuid, sourceUrl, actor);
                    logVerbose("identity.tenant_logo.imported", tenantUuid, actorUserUuid, rec);
                    writeSuccessOrRedirect(req, resp, rec, "tenant_fields.jsp", "tenant_logo_saved");
                    return;
                }
                case "clear_tenant_logo" -> {
                    requireTenantIdentityManage(session);
                    boolean cleared = store.clearTenantLogo(tenantUuid);
                    logVerbose("identity.tenant_logo.cleared", tenantUuid, actorUserUuid, null);
                    writeClearOrRedirect(req, resp, cleared, "tenant_fields.jsp", "tenant_logo_cleared");
                    return;
                }
                case "upload_user_photo" -> {
                    String targetUserUuid = resolveTargetUserUuid(req, actorUserUuid);
                    requireUserPhotoManage(session, actorUserUuid, targetUserUuid);
                    Part filePart = safePart(req, "file");
                    if (filePart == null) throw new IllegalArgumentException("Photo file is required.");
                    byte[] bytes = readBytes(filePart);
                    profile_assets.AssetRec rec = store.saveUserPhotoUpload(
                            tenantUuid,
                            targetUserUuid,
                            safe(filePart.getSubmittedFileName()),
                            safe(filePart.getContentType()),
                            bytes,
                            actor
                    );
                    logVerbose("identity.user_photo.updated", tenantUuid, actorUserUuid, rec);
                    writeSuccessOrRedirect(req, resp, rec, "user_settings.jsp", "user_photo_saved");
                    return;
                }
                case "import_user_photo_url" -> {
                    String targetUserUuid = resolveTargetUserUuid(req, actorUserUuid);
                    requireUserPhotoManage(session, actorUserUuid, targetUserUuid);
                    String sourceUrl = safe(req.getParameter("source_url")).trim();
                    profile_assets.AssetRec rec = store.saveUserPhotoFromUrl(tenantUuid, targetUserUuid, sourceUrl, actor);
                    logVerbose("identity.user_photo.imported", tenantUuid, actorUserUuid, rec);
                    writeSuccessOrRedirect(req, resp, rec, "user_settings.jsp", "user_photo_saved");
                    return;
                }
                case "clear_user_photo" -> {
                    String targetUserUuid = resolveTargetUserUuid(req, actorUserUuid);
                    requireUserPhotoManage(session, actorUserUuid, targetUserUuid);
                    boolean cleared = store.clearUserPhoto(tenantUuid, targetUserUuid);
                    logVerbose("identity.user_photo.cleared", tenantUuid, actorUserUuid, null);
                    writeClearOrRedirect(req, resp, cleared, "user_settings.jsp", "user_photo_cleared");
                    return;
                }
                default -> {
                    writeError(resp, 404, "not_found", "Unknown action.");
                    return;
                }
            }
        } catch (IllegalArgumentException ex) {
            logWarning("identity.asset.invalid", tenantUuid, actorUserUuid, ex.getMessage());
            writeErrorOrRedirect(req, resp, 400, "bad_request", safe(ex.getMessage()));
        } catch (Exception ex) {
            logWarning("identity.asset.failed", tenantUuid, actorUserUuid, ex.getMessage());
            writeErrorOrRedirect(req, resp, 500, "server_error", "Unable to process request.");
        }
    }

    private void serveTenantLogo(HttpServletRequest req, HttpServletResponse resp, String tenantUuid) throws IOException {
        profile_assets.AssetRec rec = store.readTenantLogo(tenantUuid);
        if (rec == null) {
            resp.sendError(404);
            return;
        }
        writeImageResponse(resp, rec);
    }

    private void serveUserPhoto(HttpServletRequest req,
                                HttpServletResponse resp,
                                String tenantUuid,
                                String actorUserUuid) throws IOException {
        String userUuid = resolveTargetUserUuid(req, actorUserUuid);
        profile_assets.AssetRec rec = store.readUserPhoto(tenantUuid, userUuid);
        if (rec == null) {
            resp.sendError(404);
            return;
        }
        writeImageResponse(resp, rec);
    }

    private void writeImageResponse(HttpServletResponse resp, profile_assets.AssetRec rec) throws IOException {
        byte[] bytes = store.readAssetBytes(rec);
        if (bytes.length == 0) {
            resp.sendError(404);
            return;
        }

        String contentType = safe(rec.mimeType).trim();
        if (contentType.isBlank()) contentType = "application/octet-stream";

        resp.setStatus(200);
        resp.setContentType(contentType);
        resp.setHeader("Cache-Control", "no-store");
        String fileName = safe(rec.fileName).trim();
        if (fileName.isBlank()) fileName = "image.bin";
        resp.setHeader("Content-Disposition",
                "inline; filename=\"" + safeHeaderFileName(fileName) + "\"; filename*=UTF-8''" + enc(fileName));
        resp.setContentLength(bytes.length);
        resp.getOutputStream().write(bytes);
    }

    private void writeSuccessOrRedirect(HttpServletRequest req,
                                        HttpServletResponse resp,
                                        profile_assets.AssetRec rec,
                                        String defaultNextPage,
                                        String statusCode) throws IOException {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", true);
        out.put("status", safe(statusCode));
        out.put("asset", assetMap(req, rec));
        if (wantsJson(req)) {
            writeJson(resp, 200, out);
            return;
        }
        String next = nextPath(req, defaultNextPage);
        redirectWithStatus(resp, next, statusCode, "");
    }

    private void writeClearOrRedirect(HttpServletRequest req,
                                      HttpServletResponse resp,
                                      boolean cleared,
                                      String defaultNextPage,
                                      String statusCode) throws IOException {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", true);
        out.put("status", safe(statusCode));
        out.put("cleared", cleared);
        if (wantsJson(req)) {
            writeJson(resp, 200, out);
            return;
        }
        String next = nextPath(req, defaultNextPage);
        redirectWithStatus(resp, next, statusCode, "");
    }

    private void writeErrorOrRedirect(HttpServletRequest req,
                                      HttpServletResponse resp,
                                      int status,
                                      String code,
                                      String message) throws IOException {
        if (wantsJson(req)) {
            writeError(resp, status, code, message);
            return;
        }
        String fallbackPage = "/tenant_fields.jsp";
        String action = safe(req.getParameter("action")).trim().toLowerCase(Locale.ROOT);
        if (action.contains("user_photo")) fallbackPage = "/user_settings.jsp";
        String next = nextPath(req, fallbackPage);
        redirectWithStatus(resp, next, "", message);
    }

    private void logVerbose(String eventKey, String tenantUuid, String actorUserUuid, profile_assets.AssetRec rec) {
        try {
            LinkedHashMap<String, String> details = new LinkedHashMap<String, String>();
            if (rec != null) {
                details.put("scope", safe(rec.scope));
                details.put("user_uuid", safe(rec.userUuid));
                details.put("file_name", safe(rec.fileName));
                details.put("mime_type", safe(rec.mimeType));
                details.put("size_bytes", String.valueOf(rec.sizeBytes));
                details.put("source_url", safe(rec.sourceUrl));
            }
            logs.logVerbose(safe(eventKey), safe(tenantUuid), safe(actorUserUuid), "", "", details);
        } catch (Exception ignored) {}
    }

    private void logWarning(String eventKey, String tenantUuid, String actorUserUuid, String message) {
        try {
            LinkedHashMap<String, String> details = new LinkedHashMap<String, String>();
            details.put("reason", safe(message));
            logs.logWarning(safe(eventKey), safe(tenantUuid), safe(actorUserUuid), "", "", details);
        } catch (Exception ignored) {}
    }

    private static LinkedHashMap<String, Object> assetMap(HttpServletRequest req, profile_assets.AssetRec rec) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        if (rec == null) return out;

        String ctx = safe(req.getContextPath());
        String assetUrl;
        if ("tenant_logo".equals(rec.scope)) {
            assetUrl = ctx + "/profile_assets?action=tenant_logo";
        } else {
            assetUrl = ctx + "/profile_assets?action=user_photo&user_uuid=" + enc(rec.userUuid);
        }

        out.put("scope", safe(rec.scope));
        out.put("tenant_uuid", safe(rec.tenantUuid));
        out.put("user_uuid", safe(rec.userUuid));
        out.put("file_name", safe(rec.fileName));
        out.put("mime_type", safe(rec.mimeType));
        out.put("size_bytes", rec.sizeBytes);
        out.put("updated_at", safe(rec.updatedAt));
        out.put("source_url", safe(rec.sourceUrl));
        out.put("updated_by", safe(rec.updatedBy));
        out.put("asset_url", assetUrl);
        return out;
    }

    private static String resolveTargetUserUuid(HttpServletRequest req, String fallbackUserUuid) {
        String userUuid = safe(req.getParameter("user_uuid")).trim();
        if (userUuid.isBlank()) userUuid = safe(fallbackUserUuid).trim();
        return userUuid;
    }

    private static void requireTenantIdentityManage(HttpSession session) {
        if (session == null) throw new IllegalArgumentException("Authentication required.");
        if (users_roles.hasPermissionTrue(session, "tenant_admin")) return;
        if (users_roles.hasPermissionTrue(session, "tenant_fields.manage")) return;
        if (users_roles.hasPermissionTrue(session, "tenant_settings.manage")) return;
        throw new IllegalArgumentException("Permission denied.");
    }

    private static void requireUserPhotoManage(HttpSession session, String actorUserUuid, String targetUserUuid) {
        String actor = safe(actorUserUuid).trim();
        String target = safe(targetUserUuid).trim();
        if (target.isBlank()) throw new IllegalArgumentException("user_uuid is required.");
        if (actor.equals(target)) return;
        if (users_roles.hasPermissionTrue(session, "tenant_admin")) return;
        throw new IllegalArgumentException("Permission denied.");
    }

    private static Part safePart(HttpServletRequest req, String name) {
        if (req == null) return null;
        try {
            return req.getPart(safe(name));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static byte[] readBytes(Part part) throws Exception {
        if (part == null) return new byte[0];
        try (InputStreamHolder holder = new InputStreamHolder(part)) {
            return holder.readAllBytes();
        }
    }

    private static String nextPath(HttpServletRequest req, String defaultPage) {
        String fallback = "/" + safe(defaultPage).replace("\\", "/").replaceAll("^/+", "");
        if ("/".equals(fallback)) fallback = "/index.jsp";
        String raw = safe(req.getParameter("next")).trim();
        if (raw.isBlank()) return fallback;
        if (!raw.startsWith("/")) return fallback;
        if (raw.contains("://") || raw.startsWith("//") || raw.contains("\r") || raw.contains("\n")) return fallback;
        return raw;
    }

    private static void redirectWithStatus(HttpServletResponse resp, String next, String status, String error) throws IOException {
        String n = safe(next);
        if (n.isBlank()) n = "/index.jsp";
        String sep = n.contains("?") ? "&" : "?";
        StringBuilder out = new StringBuilder(n.length() + 128);
        out.append(n);
        if (!safe(status).isBlank()) {
            out.append(sep).append("asset_status=").append(enc(status));
            sep = "&";
        }
        if (!safe(error).isBlank()) {
            out.append(sep).append("asset_error=").append(enc(error));
        }
        resp.sendRedirect(out.toString());
    }

    private static boolean wantsJson(HttpServletRequest req) {
        String format = safe(req.getParameter("format")).trim().toLowerCase(Locale.ROOT);
        if ("json".equals(format)) return true;
        String accept = safe(req.getHeader("Accept")).trim().toLowerCase(Locale.ROOT);
        return accept.contains("application/json");
    }

    private static void writeJson(HttpServletResponse resp, int status, Object payload) throws IOException {
        byte[] bytes = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(payload);
        resp.setStatus(status);
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resp.setContentType("application/json; charset=UTF-8");
        resp.setContentLength(bytes.length);
        resp.getOutputStream().write(bytes);
    }

    private static void writeError(HttpServletResponse resp, int status, String code, String message) throws IOException {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", false);
        out.put("error", safe(code));
        out.put("message", safe(message));
        writeJson(resp, status, out);
    }

    private static String safeHeaderFileName(String name) {
        String n = safe(name).trim();
        if (n.isBlank()) return "file.bin";
        return n.replace("\\", "_").replace("\"", "_").replace("\r", "").replace("\n", "");
    }

    private static String enc(String v) {
        return URLEncoder.encode(safe(v), StandardCharsets.UTF_8);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static final class InputStreamHolder implements AutoCloseable {
        private final Part part;
        private java.io.InputStream in;

        InputStreamHolder(Part part) throws Exception {
            this.part = part;
            this.in = part == null ? null : part.getInputStream();
        }

        byte[] readAllBytes() throws Exception {
            if (in == null) return new byte[0];
            return in.readAllBytes();
        }

        @Override
        public void close() {
            if (in == null) return;
            try {
                in.close();
            } catch (Exception ignored) {
            } finally {
                in = null;
            }
        }
    }
}
