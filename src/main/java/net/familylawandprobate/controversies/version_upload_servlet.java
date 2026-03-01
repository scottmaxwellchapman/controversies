package net.familylawandprobate.controversies;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Dedicated endpoint for chunked version uploads to avoid JSP parser edge cases.
 */
public final class version_upload_servlet extends HttpServlet {

    private static final Logger LOG = Logger.getLogger(version_upload_servlet.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String S_TENANT_UUID = "tenant.uuid";

    private final version_upload_service service = version_upload_service.defaultService();

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String requestId = UUID.randomUUID().toString();
        try {
            HttpSession sess = req.getSession(false);
            String tenantUuid = safe(sess == null ? null : (String) sess.getAttribute(S_TENANT_UUID)).trim();
            if (tenantUuid.isBlank()) {
                writeError(resp, 401, "unauthorized", "Authentication required.", requestId);
                return;
            }

            String action = safe(req.getParameter("action")).trim().toLowerCase(Locale.ROOT);
            if (action.isBlank()) action = safe(req.getHeader("X-Upload-Action")).trim().toLowerCase(Locale.ROOT);

            if ("chunk".equals(action)) {
                handleChunk(req, resp, tenantUuid, requestId);
                return;
            }
            if ("commit".equals(action)) {
                handleCommit(req, resp, tenantUuid, requestId);
                return;
            }
            if ("diag".equals(action) || "diagnostics".equals(action)) {
                writeDiagnostics(req, resp, requestId);
                return;
            }
            writeError(resp, 400, "bad_action", "Unknown upload action.", requestId);
        } catch (IllegalArgumentException ex) {
            writeError(resp, 400, "bad_request", safe(ex.getMessage()), requestId);
        } catch (Exception ex) {
            LOG.log(Level.SEVERE, "Version upload failed [" + requestId + "]: " + ex.getMessage(), ex);
            writeError(resp, 500, "server_error", safe(ex.getMessage()), requestId);
        }
    }

    private void handleChunk(HttpServletRequest req,
                             HttpServletResponse resp,
                             String tenantUuid,
                             String requestId) throws Exception {
        String caseUuid = pick(req, "case_uuid", "X-Case-UUID");
        String docUuid = pick(req, "doc_uuid", "X-Doc-UUID");
        String partUuid = pick(req, "part_uuid", "X-Part-UUID");
        String uploadId = pick(req, "upload_id", "X-Upload-Id");
        int chunkIndex = intOrDefault(pick(req, "chunk_index", "X-Chunk-Index"), -1);
        int totalChunks = intOrDefault(pick(req, "total_chunks", "X-Total-Chunks"), -1);
        String chunkSha = pick(req, "chunk_sha256", "X-Chunk-SHA256");
        byte[] body = req.getInputStream().readAllBytes();

        version_upload_service.ChunkResult out = service.storeChunk(
                tenantUuid,
                caseUuid,
                docUuid,
                partUuid,
                uploadId,
                chunkIndex,
                totalChunks,
                chunkSha,
                body
        );

        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("ok", true);
        result.put("request_id", requestId);
        result.put("chunk_index", out.chunkIndex);
        result.put("total_chunks", out.totalChunks);
        result.put("bytes_written", out.bytesWritten);
        result.put("chunk_sha256", out.chunkSha256);
        writeJson(resp, 200, result);
    }

    private void handleCommit(HttpServletRequest req,
                              HttpServletResponse resp,
                              String tenantUuid,
                              String requestId) throws Exception {
        String caseUuid = pick(req, "case_uuid", "X-Case-UUID");
        String docUuid = pick(req, "doc_uuid", "X-Doc-UUID");
        String partUuid = pick(req, "part_uuid", "X-Part-UUID");

        version_upload_service.CommitRequest commitReq = new version_upload_service.CommitRequest();
        commitReq.uploadId = pick(req, "upload_id", "X-Upload-Id");
        commitReq.totalChunks = intOrDefault(pick(req, "total_chunks", "X-Total-Chunks"), -1);
        commitReq.expectedFileSha256 = pick(req, "file_sha256", "X-File-SHA256");
        commitReq.expectedBytes = longOrDefault(pick(req, "file_size_bytes", "X-File-Size-Bytes"), 0L);
        commitReq.uploadFileName = pick(req, "upload_file_name", "X-Upload-File-Name");
        commitReq.versionLabel = req.getParameter("version_label");
        commitReq.source = req.getParameter("source");
        commitReq.mimeType = req.getParameter("mime_type");
        commitReq.createdBy = req.getParameter("created_by");
        commitReq.notes = req.getParameter("notes");
        commitReq.makeCurrent = "1".equals(req.getParameter("make_current"));

        version_upload_service.CommitResult out = service.commitUpload(tenantUuid, caseUuid, docUuid, partUuid, commitReq);

        String ctx = safe(req.getContextPath());
        String redirect = ctx + "/versions.jsp?case_uuid=" + enc(caseUuid)
                + "&doc_uuid=" + enc(docUuid)
                + "&part_uuid=" + enc(partUuid)
                + "&uploaded=1";

        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("ok", true);
        result.put("request_id", requestId);
        result.put("version_uuid", out.version == null ? "" : safe(out.version.uuid));
        result.put("checksum", safe(out.checksum));
        result.put("file_size_bytes", out.assembledBytes);
        result.put("redirect", redirect);
        writeJson(resp, 200, result);
    }

    private void writeDiagnostics(HttpServletRequest req, HttpServletResponse resp, String requestId) throws IOException {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", true);
        out.put("request_id", requestId);
        out.put("method", safe(req.getMethod()));
        out.put("request_uri", safe(req.getRequestURI()));
        out.put("query", safe(req.getQueryString()));
        out.put("content_type", safe(req.getContentType()));
        out.put("content_length", req.getContentLengthLong());
        out.put("action_param", safe(req.getParameter("action")));
        out.put("action_header", safe(req.getHeader("X-Upload-Action")));
        writeJson(resp, 200, out);
    }

    private static String pick(HttpServletRequest req, String paramName, String headerName) {
        String v = safe(req.getParameter(paramName)).trim();
        if (!v.isBlank()) return v;
        return safe(req.getHeader(headerName)).trim();
    }

    private static int intOrDefault(String raw, int d) {
        try {
            return Integer.parseInt(safe(raw).trim());
        } catch (Exception ignored) {
            return d;
        }
    }

    private static long longOrDefault(String raw, long d) {
        try {
            return Long.parseLong(safe(raw).trim());
        } catch (Exception ignored) {
            return d;
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String enc(String s) {
        return URLEncoder.encode(safe(s), StandardCharsets.UTF_8);
    }

    private static void writeJson(HttpServletResponse resp, int status, Object payload) throws IOException {
        byte[] bytes = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(payload);
        resp.setStatus(status);
        resp.setContentType("application/json; charset=UTF-8");
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resp.setContentLength(bytes.length);
        resp.getOutputStream().write(bytes);
    }

    private static void writeError(HttpServletResponse resp,
                                   int status,
                                   String code,
                                   String message,
                                   String requestId) throws IOException {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", false);
        out.put("error", safe(code));
        out.put("message", safe(message));
        out.put("request_id", safe(requestId));
        writeJson(resp, status, out);
    }
}
