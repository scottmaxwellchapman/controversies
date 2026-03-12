package net.familylawandprobate.controversies;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.Part;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Session-authenticated floating tenant chat endpoint.
 */
public final class tenant_chat_servlet extends HttpServlet {

    private static final Logger LOG = Logger.getLogger(tenant_chat_servlet.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String S_TENANT_UUID = "tenant.uuid";
    private static final String S_USER_UUID = users_roles.S_USER_UUID;
    private static final String S_USER_EMAIL = users_roles.S_USER_EMAIL;

    private static final String CHAT_CHANNEL = "internal_messages";
    private static final String CHAT_THREAD_KEY = "tenant_user_chat_global";
    private static final String CHAT_THREAD_SUBJECT = "Tenant User Chat";

    private static final int MAX_FILES_PER_MESSAGE = 10;

    private static final activity_log LOGS = activity_log.defaultStore();

    private static final class UploadFile {
        String fileName;
        String mimeType;
        byte[] bytes;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        handle(req, resp, "GET");
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        handle(req, resp, "POST");
    }

    private void handle(HttpServletRequest req, HttpServletResponse resp, String method) throws IOException {
        String tenantUuid = "";
        String userUuid = "";
        String userEmail = "";
        String actor = "";
        String action = "";

        try {
            HttpSession session = req.getSession(false);
            security.sec_bind(req, resp, null, session);
            if (!security.require_login()) return;

            session = req.getSession(false);
            tenantUuid = safe(session == null ? "" : (String) session.getAttribute(S_TENANT_UUID)).trim();
            userUuid = safe(session == null ? "" : (String) session.getAttribute(S_USER_UUID)).trim();
            userEmail = safe(session == null ? "" : (String) session.getAttribute(S_USER_EMAIL)).trim();
            actor = userEmail.isBlank() ? userUuid : userEmail;

            if (tenantUuid.isBlank() || actor.isBlank()) {
                writeError(resp, 401, "unauthorized", "Authenticated tenant user context is required.");
                return;
            }

            action = safe(req.getParameter("action")).trim().toLowerCase(Locale.ROOT);
            if (action.isBlank()) action = "POST".equalsIgnoreCase(method) ? "send" : "messages";

            if ("download".equals(action)) {
                handleDownload(req, resp, tenantUuid, userUuid, userEmail, actor);
                return;
            }

            if ("send".equals(action)) {
                if (!"POST".equalsIgnoreCase(method)) {
                    writeError(resp, 405, "method_not_allowed", "Use POST to send chat messages.");
                    return;
                }
                handleSend(req, resp, tenantUuid, userUuid, userEmail, actor);
                return;
            }

            if ("upload".equals(action)) {
                if (!"POST".equalsIgnoreCase(method)) {
                    writeError(resp, 405, "method_not_allowed", "Use POST to upload chat files.");
                    return;
                }
                handleUpload(req, resp, tenantUuid, userUuid, userEmail, actor);
                return;
            }

            if ("messages".equals(action) || "list".equals(action) || "history".equals(action)) {
                handleMessages(req, resp, tenantUuid, userUuid, userEmail, actor);
                return;
            }

            writeError(resp, 400, "bad_action", "Unknown tenant chat action.");
        } catch (IllegalArgumentException ex) {
            writeError(resp, 400, "bad_request", safe(ex.getMessage()));
        } catch (Exception ex) {
            LOG.log(
                    Level.WARNING,
                    "Tenant chat request failed action=" + safe(action)
                            + ", tenant=" + safe(tenantUuid)
                            + ", actor=" + safe(actor)
                            + ", message=" + safe(ex.getMessage()),
                    ex
            );
            writeError(resp, 500, "server_error", "Tenant chat request failed.");
        }
    }

    private void handleMessages(HttpServletRequest req,
                                HttpServletResponse resp,
                                String tenantUuid,
                                String userUuid,
                                String userEmail,
                                String actor) throws Exception {
        omnichannel_tickets store = omnichannel_tickets.defaultStore();
        omnichannel_tickets.TicketRec thread = resolveTenantChatThread(store, tenantUuid, actor);
        String threadUuid = safe(thread == null ? "" : thread.uuid).trim();

        int limit = intOrDefault(req.getParameter("limit"), 120);
        if (limit < 1) limit = 1;
        if (limit > 300) limit = 300;

        String after = safe(req.getParameter("after")).trim();

        List<omnichannel_tickets.MessageRec> rows = store.listMessages(tenantUuid, threadUuid);
        ArrayList<omnichannel_tickets.MessageRec> filtered = new ArrayList<omnichannel_tickets.MessageRec>();
        for (int i = 0; i < rows.size(); i++) {
            omnichannel_tickets.MessageRec row = rows.get(i);
            if (row == null) continue;
            if (!after.isBlank()) {
                String createdAt = safe(row.createdAt).trim();
                if (createdAt.isBlank() || createdAt.compareTo(after) <= 0) continue;
            }
            filtered.add(row);
        }

        List<omnichannel_tickets.AttachmentRec> allAttachments = store.listAttachments(tenantUuid, threadUuid);
        LinkedHashMap<String, ArrayList<omnichannel_tickets.AttachmentRec>> attachmentsByMessage =
                new LinkedHashMap<String, ArrayList<omnichannel_tickets.AttachmentRec>>();
        for (int i = 0; i < allAttachments.size(); i++) {
            omnichannel_tickets.AttachmentRec rec = allAttachments.get(i);
            if (rec == null) continue;
            String msgId = safe(rec.messageUuid).trim();
            if (msgId.isBlank()) continue;
            ArrayList<omnichannel_tickets.AttachmentRec> bucket = attachmentsByMessage.get(msgId);
            if (bucket == null) {
                bucket = new ArrayList<omnichannel_tickets.AttachmentRec>();
                attachmentsByMessage.put(msgId, bucket);
            }
            bucket.add(rec);
        }

        int keep = Math.min(limit, filtered.size());
        ArrayList<LinkedHashMap<String, Object>> messages = new ArrayList<LinkedHashMap<String, Object>>();
        for (int i = keep - 1; i >= 0; i--) {
            omnichannel_tickets.MessageRec row = filtered.get(i);
            ArrayList<omnichannel_tickets.AttachmentRec> messageAttachments =
                    attachmentsByMessage.get(safe(row == null ? "" : row.uuid).trim());
            messages.add(messageMap(row, messageAttachments, userUuid, userEmail, actor));
        }

        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", true);
        out.put("thread_uuid", threadUuid);
        out.put("thread_subject", safe(thread == null ? "" : thread.subject));
        out.put("message_count", filtered.size());
        out.put("returned_count", messages.size());
        out.put("messages", messages);
        out.put("server_time", app_clock.now().toString());
        writeJson(resp, 200, out);
    }

    private void handleSend(HttpServletRequest req,
                            HttpServletResponse resp,
                            String tenantUuid,
                            String userUuid,
                            String userEmail,
                            String actor) throws Exception {
        String body = safe(req.getParameter("body"));
        if (body.trim().isBlank()) {
            throw new IllegalArgumentException("Message body is required.");
        }

        omnichannel_tickets store = omnichannel_tickets.defaultStore();
        omnichannel_tickets.TicketRec thread = resolveTenantChatThread(store, tenantUuid, actor);
        String threadUuid = safe(thread == null ? "" : thread.uuid).trim();
        if (threadUuid.isBlank()) throw new IllegalStateException("Unable to resolve tenant chat thread.");

        omnichannel_tickets.MessageRec message = store.addMessage(
                tenantUuid,
                threadUuid,
                "internal",
                body,
                false,
                "",
                "",
                "",
                "",
                "",
                "",
                actor
        );

        logMessageSent(tenantUuid, userUuid, userEmail, threadUuid, message, 0);

        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", true);
        out.put("thread_uuid", threadUuid);
        out.put("message", messageMap(message, List.of(), userUuid, userEmail, actor));
        out.put("server_time", app_clock.now().toString());
        writeJson(resp, 201, out);
    }

    private void handleUpload(HttpServletRequest req,
                              HttpServletResponse resp,
                              String tenantUuid,
                              String userUuid,
                              String userEmail,
                              String actor) throws Exception {
        if (!isMultipart(req)) {
            throw new IllegalArgumentException("Multipart upload is required.");
        }

        String body = "";
        ArrayList<UploadFile> files = new ArrayList<UploadFile>();
        var parts = req.getParts();
        for (Part part : parts) {
            if (part == null) continue;

            String partName = safe(part.getName()).trim();
            String submitted = safe(part.getSubmittedFileName()).trim();

            if (submitted.isBlank()) {
                if ("body".equals(partName)) {
                    body = safe(new String(part.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
                }
                continue;
            }

            if (!"files".equals(partName)) continue;
            if (files.size() >= MAX_FILES_PER_MESSAGE) {
                throw new IllegalArgumentException("Maximum " + MAX_FILES_PER_MESSAGE + " files per message.");
            }

            long size = part.getSize();
            if (size <= 0L) continue;
            if (size > omnichannel_tickets.MAX_ATTACHMENT_BYTES) {
                throw new IllegalArgumentException("File exceeds max size of 20MB: " + submitted);
            }

            UploadFile in = new UploadFile();
            in.fileName = submitted;
            in.mimeType = safe(part.getContentType()).trim();
            in.bytes = part.getInputStream().readAllBytes();
            if (in.bytes == null || in.bytes.length == 0) continue;
            files.add(in);
        }

        if (files.isEmpty() && body.trim().isBlank()) {
            throw new IllegalArgumentException("Message body or at least one file is required.");
        }

        String messageBody = body;
        if (messageBody.trim().isBlank()) {
            messageBody = files.size() == 1 ? "📎 Uploaded 1 file." : "📎 Uploaded " + files.size() + " files.";
        }

        omnichannel_tickets store = omnichannel_tickets.defaultStore();
        omnichannel_tickets.TicketRec thread = resolveTenantChatThread(store, tenantUuid, actor);
        String threadUuid = safe(thread == null ? "" : thread.uuid).trim();
        if (threadUuid.isBlank()) throw new IllegalStateException("Unable to resolve tenant chat thread.");

        omnichannel_tickets.MessageRec message = store.addMessage(
                tenantUuid,
                threadUuid,
                "internal",
                messageBody,
                false,
                "",
                "",
                "",
                "",
                "",
                "",
                actor
        );

        String messageUuid = safe(message == null ? "" : message.uuid).trim();
        if (messageUuid.isBlank()) throw new IllegalStateException("Unable to create chat message for file upload.");

        ArrayList<omnichannel_tickets.AttachmentRec> saved = new ArrayList<omnichannel_tickets.AttachmentRec>();
        for (int i = 0; i < files.size(); i++) {
            UploadFile in = files.get(i);
            if (in == null || in.bytes == null || in.bytes.length == 0) continue;
            omnichannel_tickets.AttachmentRec rec = store.saveAttachment(
                    tenantUuid,
                    threadUuid,
                    messageUuid,
                    in.fileName,
                    in.mimeType,
                    in.bytes,
                    false,
                    actor
            );
            if (rec != null) saved.add(rec);
        }

        logMessageSent(tenantUuid, userUuid, userEmail, threadUuid, message, saved.size());

        LinkedHashMap<String, String> details = new LinkedHashMap<String, String>();
        details.put("thread_uuid", threadUuid);
        details.put("message_uuid", messageUuid);
        details.put("attachment_count", String.valueOf(saved.size()));
        details.put("message_length", String.valueOf(safe(messageBody).length()));
        LOGS.logVerbose("tenant_chat.attachment.uploaded", tenantUuid, userUuid.isBlank() ? userEmail : userUuid, "", "", details);

        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", true);
        out.put("thread_uuid", threadUuid);
        out.put("message", messageMap(message, saved, userUuid, userEmail, actor));
        out.put("uploaded_count", saved.size());
        out.put("server_time", app_clock.now().toString());
        writeJson(resp, 201, out);
    }

    private void handleDownload(HttpServletRequest req,
                                HttpServletResponse resp,
                                String tenantUuid,
                                String userUuid,
                                String userEmail,
                                String actor) throws Exception {
        String attachmentUuid = safe(req.getParameter("attachment_uuid")).trim();
        if (attachmentUuid.isBlank()) {
            writeError(resp, 400, "bad_request", "attachment_uuid is required.");
            return;
        }

        omnichannel_tickets store = omnichannel_tickets.defaultStore();
        omnichannel_tickets.TicketRec thread = resolveTenantChatThread(store, tenantUuid, actor);
        String threadUuid = safe(thread == null ? "" : thread.uuid).trim();
        if (threadUuid.isBlank()) {
            writeError(resp, 404, "not_found", "Tenant chat thread not found.");
            return;
        }

        omnichannel_tickets.AttachmentBlob blob = store.getAttachmentBlob(tenantUuid, threadUuid, attachmentUuid);
        if (blob == null || blob.attachment == null || blob.path == null) {
            writeError(resp, 404, "not_found", "Attachment not found.");
            return;
        }

        String contentType = safe(blob.attachment.mimeType).trim();
        if (contentType.isBlank()) contentType = "application/octet-stream";

        String fileName = safe(blob.attachment.fileName).trim();
        if (fileName.isBlank()) fileName = "attachment.bin";

        resp.setStatus(200);
        resp.setContentType(contentType);
        resp.setHeader(
                "Content-Disposition",
                "attachment; filename=\"" + safeHeaderFileName(fileName) + "\"; filename*=UTF-8''" + enc(fileName)
        );

        try (var in = java.nio.file.Files.newInputStream(blob.path)) {
            in.transferTo(resp.getOutputStream());
        }

        LinkedHashMap<String, String> details = new LinkedHashMap<String, String>();
        details.put("thread_uuid", threadUuid);
        details.put("attachment_uuid", safe(blob.attachment.uuid));
        details.put("file_name", safe(blob.attachment.fileName));
        details.put("message_uuid", safe(blob.attachment.messageUuid));
        LOGS.logVerbose("tenant_chat.attachment.downloaded", tenantUuid, userUuid.isBlank() ? userEmail : userUuid, "", "", details);
    }

    private static boolean isMultipart(HttpServletRequest req) {
        String contentType = safe(req == null ? "" : req.getContentType()).trim().toLowerCase(Locale.ROOT);
        return contentType.startsWith("multipart/form-data");
    }

    private static omnichannel_tickets.TicketRec resolveTenantChatThread(omnichannel_tickets store,
                                                                          String tenantUuid,
                                                                          String actor) throws Exception {
        if (store == null) throw new IllegalArgumentException("store required");
        String tu = safe(tenantUuid).trim();
        if (tu.isBlank()) throw new IllegalArgumentException("tenantUuid required");

        store.ensure(tu);
        List<omnichannel_tickets.TicketRec> tickets = store.listTickets(tu);

        omnichannel_tickets.TicketRec active = null;
        omnichannel_tickets.TicketRec archived = null;
        for (int i = 0; i < tickets.size(); i++) {
            omnichannel_tickets.TicketRec row = tickets.get(i);
            if (row == null) continue;
            if (!CHAT_CHANNEL.equalsIgnoreCase(safe(row.channel).trim())) continue;
            if (!CHAT_THREAD_KEY.equalsIgnoreCase(safe(row.threadKey).trim())) continue;
            if (!row.archived) {
                active = row;
                break;
            }
            if (archived == null) archived = row;
        }

        if (active != null) return active;

        if (archived != null) {
            store.setArchived(tu, safe(archived.uuid), false, actor);
            omnichannel_tickets.TicketRec restored = store.getTicket(tu, safe(archived.uuid));
            return restored == null ? archived : restored;
        }

        omnichannel_tickets.TicketRec created = store.createTicket(
                tu,
                "",
                CHAT_CHANNEL,
                CHAT_THREAD_SUBJECT,
                "open",
                "normal",
                "manual",
                "",
                "",
                "",
                "Tenant Users",
                "",
                "",
                CHAT_THREAD_KEY,
                "",
                "internal",
                "",
                "",
                "",
                false,
                actor,
                "tenant_chat"
        );
        if (created == null || safe(created.uuid).trim().isBlank()) {
            throw new IllegalStateException("Unable to initialize tenant chat thread.");
        }
        return created;
    }

    private static LinkedHashMap<String, Object> messageMap(omnichannel_tickets.MessageRec row,
                                                             List<omnichannel_tickets.AttachmentRec> attachments,
                                                             String userUuid,
                                                             String userEmail,
                                                             String actor) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        if (row == null) return out;

        String createdBy = safe(row.createdBy).trim();
        boolean self = false;
        if (!createdBy.isBlank()) {
            if (!safe(userEmail).trim().isBlank() && createdBy.equalsIgnoreCase(safe(userEmail).trim())) self = true;
            else if (!safe(userUuid).trim().isBlank() && createdBy.equalsIgnoreCase(safe(userUuid).trim())) self = true;
            else if (!safe(actor).trim().isBlank() && createdBy.equalsIgnoreCase(safe(actor).trim())) self = true;
        }

        ArrayList<LinkedHashMap<String, Object>> fileRows = new ArrayList<LinkedHashMap<String, Object>>();
        List<omnichannel_tickets.AttachmentRec> files = attachments == null ? List.of() : attachments;
        for (int i = 0; i < files.size(); i++) {
            omnichannel_tickets.AttachmentRec file = files.get(i);
            if (file == null) continue;
            fileRows.add(attachmentMap(file));
        }

        out.put("message_uuid", safe(row.uuid));
        out.put("thread_uuid", safe(row.ticketUuid));
        out.put("direction", safe(row.direction));
        out.put("channel", safe(row.channel));
        out.put("body", safe(row.body));
        out.put("created_by", createdBy);
        out.put("created_at", safe(row.createdAt));
        out.put("self", self);
        out.put("attachments", fileRows);
        return out;
    }

    private static LinkedHashMap<String, Object> attachmentMap(omnichannel_tickets.AttachmentRec row) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        if (row == null) return out;

        out.put("attachment_uuid", safe(row.uuid));
        out.put("message_uuid", safe(row.messageUuid));
        out.put("file_name", safe(row.fileName));
        out.put("mime_type", safe(row.mimeType));
        out.put("file_size_bytes", safe(row.fileSizeBytes));
        out.put("uploaded_at", safe(row.uploadedAt));
        out.put("download_url", "/tenant_chat?action=download&attachment_uuid=" + enc(safe(row.uuid)));
        return out;
    }

    private static String safeHeaderFileName(String raw) {
        String v = safe(raw).trim().replaceAll("[\\r\\n\\\\\"]", "_");
        if (v.isBlank()) v = "attachment.bin";
        return v;
    }

    private static String enc(String s) {
        return URLEncoder.encode(safe(s), StandardCharsets.UTF_8);
    }

    private static int intOrDefault(String raw, int fallback) {
        try {
            return Integer.parseInt(safe(raw).trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static void logMessageSent(String tenantUuid,
                                       String userUuid,
                                       String userEmail,
                                       String threadUuid,
                                       omnichannel_tickets.MessageRec message,
                                       int attachmentCount) {
        LinkedHashMap<String, String> details = new LinkedHashMap<String, String>();
        details.put("thread_uuid", safe(threadUuid));
        details.put("message_uuid", safe(message == null ? "" : message.uuid));
        details.put("message_length", String.valueOf(safe(message == null ? "" : message.body).length()));
        details.put("attachment_count", String.valueOf(Math.max(0, attachmentCount)));
        LOGS.logVerbose("tenant_chat.message.sent", safe(tenantUuid), userUuid.isBlank() ? safe(userEmail) : safe(userUuid), "", "", details);
    }

    private static void writeJson(HttpServletResponse resp, int status, Map<String, Object> payload) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(payload == null ? new LinkedHashMap<String, Object>() : payload);
        resp.setStatus(status);
        resp.setContentType("application/json; charset=UTF-8");
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resp.setContentLength(bytes.length);
        resp.getOutputStream().write(bytes);
    }

    private static void writeError(HttpServletResponse resp,
                                   int status,
                                   String code,
                                   String message) throws IOException {
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
