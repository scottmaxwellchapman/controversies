package net.familylawandprobate.controversies;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.Part;
import org.jsoup.Jsoup;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;

public final class wiki_file_servlet extends HttpServlet {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String S_TENANT_UUID = "tenant.uuid";
    private static final activity_log ACTIVITY_LOGS = activity_log.defaultStore();

    private final tenant_wikis wikiStore = tenant_wikis.defaultStore();

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
        String actor = safe(session == null ? "" : (String) session.getAttribute(users_roles.S_USER_UUID)).trim();
        String actorEmail = safe(session == null ? "" : (String) session.getAttribute(users_roles.S_USER_EMAIL)).trim();
        if (!actorEmail.isBlank()) actor = actorEmail;
        if (tenantUuid.isBlank()) {
            resp.sendError(401);
            return;
        }

        String action = safe(req.getParameter("action")).trim().toLowerCase(Locale.ROOT);
        String pageUuid = safe(req.getParameter("page_uuid")).trim();
        if (action.isBlank()) {
            ACTIVITY_LOGS.logWarning(
                    "wiki.file_request.invalid",
                    tenantUuid,
                    actor,
                    "",
                    "",
                    details("reason", "missing_action")
            );
            writeError(resp, 400, "bad_request", "Action is required.");
            return;
        }

        boolean canView = canView(session);
        boolean canEdit = canEdit(session);

        try {
            if ("download_attachment".equals(action)) {
                if (!canView) {
                    ACTIVITY_LOGS.logWarning("wiki.file_request.denied", tenantUuid, actor, "", "", details("action", action));
                    resp.sendError(403);
                    return;
                }
                handleDownloadAttachment(req, resp, tenantUuid, session);
                return;
            }
            if ("download_revision".equals(action)) {
                if (!canView) {
                    ACTIVITY_LOGS.logWarning("wiki.file_request.denied", tenantUuid, actor, "", "", details("action", action));
                    resp.sendError(403);
                    return;
                }
                handleDownloadRevision(req, resp, tenantUuid, session);
                return;
            }
            if ("upload_attachment".equals(action)) {
                if (!"POST".equalsIgnoreCase(safe(req.getMethod()))) {
                    resp.sendError(405);
                    return;
                }
                if (!canEdit) {
                    ACTIVITY_LOGS.logWarning("wiki.file_request.denied", tenantUuid, actor, "", "", details("action", action));
                    resp.sendError(403);
                    return;
                }
                handleUploadAttachment(req, resp, tenantUuid, session);
                return;
            }
            ACTIVITY_LOGS.logWarning("wiki.file_request.invalid", tenantUuid, actor, "", "", details("action", action, "reason", "unknown_action"));
            writeError(resp, 404, "not_found", "Unknown action.");
        } catch (IllegalArgumentException ex) {
            LinkedHashMap<String, String> details = new LinkedHashMap<String, String>();
            details.put("action", action);
            details.put("page_uuid", pageUuid);
            details.put("reason", safe(ex.getMessage()));
            ACTIVITY_LOGS.logWarning("wiki.file_request.invalid", tenantUuid, actor, "", "", details);
            writeError(resp, 400, "bad_request", safe(ex.getMessage()));
        } catch (Exception ex) {
            LinkedHashMap<String, String> details = new LinkedHashMap<String, String>();
            details.put("action", action);
            details.put("page_uuid", pageUuid);
            details.put("reason", safe(ex.getMessage()));
            ACTIVITY_LOGS.logError("wiki.file_request.failed", tenantUuid, actor, "", "", details);
            writeError(resp, 500, "server_error", safe(ex.getMessage()));
        }
    }

    private void handleDownloadAttachment(HttpServletRequest req,
                                          HttpServletResponse resp,
                                          String tenantUuid,
                                          HttpSession session) throws Exception {
        String pageUuid = safe(req.getParameter("page_uuid")).trim();
        String attachmentUuid = safe(req.getParameter("attachment_uuid")).trim();
        if (pageUuid.isBlank() || attachmentUuid.isBlank()) throw new IllegalArgumentException("Missing page or attachment id.");
        requirePagePermission(session, tenantUuid, pageUuid, false);

        tenant_wikis.AttachmentRec rec = wikiStore.getAttachment(tenantUuid, pageUuid, attachmentUuid);
        Path p = wikiStore.resolveAttachmentPath(tenantUuid, pageUuid, attachmentUuid);
        if (rec == null || p == null || !Files.exists(p) || !Files.isRegularFile(p)) {
            throw new IllegalArgumentException("Attachment not found.");
        }

        String contentType = safe(rec.mimeType).trim();
        if (contentType.isBlank()) {
            try {
                contentType = safe(Files.probeContentType(p)).trim();
            } catch (Exception ignored) {
                contentType = "";
            }
        }
        if (contentType.isBlank()) contentType = "application/octet-stream";

        String fileName = safe(rec.fileName).trim();
        if (fileName.isBlank()) fileName = "attachment.bin";

        resp.setContentType(contentType);
        resp.setHeader("Content-Disposition", "attachment; filename=\"" + fileName + "\"");
        try (var in = Files.newInputStream(p)) {
            in.transferTo(resp.getOutputStream());
        }
        LinkedHashMap<String, String> details = new LinkedHashMap<String, String>();
        details.put("page_uuid", pageUuid);
        details.put("attachment_uuid", attachmentUuid);
        details.put("file_name", fileName);
        details.put("mime_type", contentType);
        ACTIVITY_LOGS.logVerbose("wiki.attachment.downloaded", safe(tenantUuid).trim(), "", "", "", details);
    }

    private void handleDownloadRevision(HttpServletRequest req,
                                        HttpServletResponse resp,
                                        String tenantUuid,
                                        HttpSession session) throws Exception {
        String pageUuid = safe(req.getParameter("page_uuid")).trim();
        String revisionUuid = safe(req.getParameter("revision_uuid")).trim();
        String format = safe(req.getParameter("format")).trim().toLowerCase(Locale.ROOT);
        if (pageUuid.isBlank() || revisionUuid.isBlank()) throw new IllegalArgumentException("Missing page or revision id.");
        if (!"txt".equals(format) && !"html".equals(format)) format = "html";
        requirePagePermission(session, tenantUuid, pageUuid, false);

        tenant_wikis.RevisionRec rec = wikiStore.getRevision(tenantUuid, pageUuid, revisionUuid);
        if (rec == null) throw new IllegalArgumentException("Revision not found.");
        String html = wikiStore.readRevisionHtml(tenantUuid, pageUuid, revisionUuid);
        String fileBase = "wiki-" + safe(rec.label).trim().replaceAll("[^A-Za-z0-9._-]", "_");
        if (fileBase.isBlank() || "wiki-".equals(fileBase)) fileBase = "wiki-revision";

        if ("txt".equals(format)) {
            org.jsoup.nodes.Document d = Jsoup.parseBodyFragment(html);
            d.select("br").append("\\n");
            d.select("p,div,li,h1,h2,h3,h4,h5,h6,blockquote,tr,pre").append("\\n");
            String txt = safe(d.text()).replace("\\n", "\n").replace('\u00A0', ' ');
            resp.setContentType("text/plain; charset=UTF-8");
            resp.setHeader("Content-Disposition", "attachment; filename=\"" + fileBase + ".txt\"");
            resp.getOutputStream().write(txt.getBytes(StandardCharsets.UTF_8));
            LinkedHashMap<String, String> details = new LinkedHashMap<String, String>();
            details.put("page_uuid", pageUuid);
            details.put("revision_uuid", revisionUuid);
            details.put("format", "txt");
            ACTIVITY_LOGS.logVerbose("wiki.revision.downloaded", safe(tenantUuid).trim(), "", "", "", details);
            return;
        }

        resp.setContentType("text/html; charset=UTF-8");
        resp.setHeader("Content-Disposition", "attachment; filename=\"" + fileBase + ".html\"");
        resp.getOutputStream().write(safe(html).getBytes(StandardCharsets.UTF_8));
        LinkedHashMap<String, String> details = new LinkedHashMap<String, String>();
        details.put("page_uuid", pageUuid);
        details.put("revision_uuid", revisionUuid);
        details.put("format", "html");
        ACTIVITY_LOGS.logVerbose("wiki.revision.downloaded", safe(tenantUuid).trim(), "", "", "", details);
    }

    private void handleUploadAttachment(HttpServletRequest req,
                                        HttpServletResponse resp,
                                        String tenantUuid,
                                        HttpSession session) throws Exception {
        String pageUuid = safe(req.getParameter("page_uuid")).trim();
        if (pageUuid.isBlank()) throw new IllegalArgumentException("Page is required.");
        requirePagePermission(session, tenantUuid, pageUuid, true);

        Part filePart = null;
        try {
            filePart = req.getPart("file");
        } catch (Exception ignored) {
            filePart = null;
        }
        if (filePart == null) throw new IllegalArgumentException("Attachment file is required.");

        String fileName = submittedFileName(filePart);
        String mime = safe(filePart.getContentType()).trim();
        byte[] bytes;
        try (var in = filePart.getInputStream()) {
            bytes = in.readAllBytes();
        }

        String actor = safe(session == null ? "" : (String) session.getAttribute(users_roles.S_USER_EMAIL)).trim();
        if (actor.isBlank()) actor = safe(session == null ? "" : (String) session.getAttribute(users_roles.S_USER_UUID)).trim();
        if (actor.isBlank()) actor = "unknown";

        tenant_wikis.AttachmentRec rec = wikiStore.saveAttachment(tenantUuid, pageUuid, fileName, mime, bytes, actor);
        String ctx = safe(req.getContextPath());
        String downloadUrl = ctx + "/wiki_files?action=download_attachment&page_uuid=" + enc(pageUuid)
                + "&attachment_uuid=" + enc(rec.uuid);

        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", true);
        out.put("attachment_uuid", safe(rec.uuid));
        out.put("file_name", safe(rec.fileName));
        out.put("mime_type", safe(rec.mimeType));
        out.put("file_size_bytes", safe(rec.fileSizeBytes));
        out.put("download_url", downloadUrl);
        writeJson(resp, 200, out);
        LinkedHashMap<String, String> details = new LinkedHashMap<String, String>();
        details.put("page_uuid", pageUuid);
        details.put("attachment_uuid", safe(rec.uuid));
        details.put("file_name", safe(rec.fileName));
        ACTIVITY_LOGS.logVerbose("wiki.attachment.upload_request_completed", safe(tenantUuid).trim(), actor, "", "", details);
    }

    private void requirePagePermission(HttpSession session,
                                       String tenantUuid,
                                       String pageUuid,
                                       boolean requireEditRole) throws Exception {
        tenant_wikis.PageRec page = wikiStore.getPage(tenantUuid, pageUuid);
        if (page == null) throw new IllegalArgumentException("Page not found.");

        boolean tenantAdmin = users_roles.hasPermissionTrue(session, "tenant_admin");
        if (tenantAdmin) return;

        if (requireEditRole && !canEdit(session)) {
            throw new IllegalArgumentException("Edit permission required.");
        }
        if (!requireEditRole && !canView(session)) {
            throw new IllegalArgumentException("View permission required.");
        }

        String key = safe(page.permissionKey).trim();
        if (key.isBlank()) return;
        if (users_roles.hasPermissionTrue(session, key)) return;
        throw new IllegalArgumentException("Missing page permission: " + key);
    }

    private static boolean canView(HttpSession session) {
        return users_roles.hasPermissionTrue(session, "tenant_admin")
                || users_roles.hasPermissionTrue(session, "wiki.view")
                || users_roles.hasPermissionTrue(session, "wiki.edit")
                || users_roles.hasPermissionTrue(session, "wiki.manage");
    }

    private static boolean canEdit(HttpSession session) {
        return users_roles.hasPermissionTrue(session, "tenant_admin")
                || users_roles.hasPermissionTrue(session, "wiki.edit")
                || users_roles.hasPermissionTrue(session, "wiki.manage");
    }

    private static String submittedFileName(Part part) {
        if (part == null) return "attachment.bin";
        String v = safe(part.getSubmittedFileName()).trim();
        if (v.isBlank()) v = "attachment.bin";
        return v;
    }

    private static String enc(String v) {
        return URLEncoder.encode(safe(v), StandardCharsets.UTF_8);
    }

    private static LinkedHashMap<String, String> details(String key, String value) {
        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
        out.put(safe(key), safe(value));
        return out;
    }

    private static LinkedHashMap<String, String> details(String key1, String value1, String key2, String value2) {
        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
        out.put(safe(key1), safe(value1));
        out.put(safe(key2), safe(value2));
        return out;
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

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
