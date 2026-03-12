package net.familylawandprobate.controversies;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * File and callback endpoints for the ONLYOFFICE WYSIWYG document editor.
 */
public final class version_editor_servlet extends HttpServlet {

    private static final Logger LOG = Logger.getLogger(version_editor_servlet.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE =
            new TypeReference<LinkedHashMap<String, Object>>() {};

    private final document_editor_service service = document_editor_service.defaultService();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String path = normalizePath(req.getPathInfo());
        if ("/file".equals(path)) {
            handleFile(req, resp);
            return;
        }
        writeText(resp, 404, "Not found.");
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String path = normalizePath(req.getPathInfo());
        if ("/callback".equals(path)) {
            handleCallback(req, resp);
            return;
        }
        writeJson(resp, 404, errorPayload(1, "Not found."));
    }

    private void handleFile(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String token = safe(req.getParameter("token")).trim();
        try {
            document_editor_service.FileAccessResult file = service.resolveEditorFile(token);
            Path p = file == null ? null : file.filePath;
            if (p == null || !Files.isRegularFile(p)) {
                writeText(resp, 404, "Editor file not found.");
                return;
            }
            String mime = safe(file.mimeType).trim();
            if (mime.isBlank()) mime = "application/octet-stream";
            String downloadName = safe(file.downloadName).trim();
            if (downloadName.isBlank()) downloadName = "version.bin";
            resp.setStatus(200);
            resp.setHeader("Cache-Control", "no-store, max-age=0");
            resp.setHeader("Pragma", "no-cache");
            resp.setDateHeader("Expires", 0L);
            resp.setHeader(
                    "Content-Disposition",
                    "attachment; filename=\"" + safeHeaderFileName(downloadName) + "\"; filename*=UTF-8''" + enc(downloadName)
            );
            resp.setContentType(mime);
            long len = Files.size(p);
            if (len >= 0L && len <= Integer.MAX_VALUE) resp.setContentLength((int) len);
            Files.copy(p, resp.getOutputStream());
        } catch (IllegalArgumentException ex) {
            writeText(resp, 400, safe(ex.getMessage()));
        } catch (SecurityException ex) {
            writeText(resp, 403, safe(ex.getMessage()));
        } catch (Exception ex) {
            LOG.log(Level.WARNING, "Editor file request failed: " + ex.getMessage(), ex);
            writeText(resp, 500, "Editor file request failed.");
        }
    }

    private void handleCallback(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String token = safe(req.getParameter("token")).trim();
        LinkedHashMap<String, Object> payload = new LinkedHashMap<String, Object>();
        try {
            byte[] body = req.getInputStream().readAllBytes();
            if (body != null && body.length > 0) {
                payload = JSON.readValue(body, MAP_TYPE);
            }
        } catch (Exception ex) {
            writeJson(resp, 200, errorPayload(1, "Invalid callback payload."));
            return;
        }

        try {
            document_editor_service.CallbackResult result = service.handleOnlyOfficeCallback(token, payload);
            LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("error", 0);
            out.put("saved", result == null ? false : result.saved);
            out.put("status", result == null ? -1 : result.status);
            out.put("version_uuid", safe(result == null ? "" : result.versionUuid));
            out.put("message", safe(result == null ? "" : result.message));
            writeJson(resp, 200, out);
        } catch (Exception ex) {
            LOG.log(Level.WARNING, "Editor callback failed: " + ex.getMessage(), ex);
            writeJson(resp, 200, errorPayload(1, safe(ex.getMessage())));
        }
    }

    private static LinkedHashMap<String, Object> errorPayload(int code, String message) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("error", code);
        out.put("message", safe(message));
        return out;
    }

    private static String normalizePath(String raw) {
        String p = safe(raw).trim();
        if (p.isBlank()) return "/";
        if (!p.startsWith("/")) p = "/" + p;
        return p.toLowerCase(Locale.ROOT);
    }

    private static String safeHeaderFileName(String raw) {
        String v = safe(raw).trim().replaceAll("[\\r\\n\\\\\"]", "_");
        if (v.isBlank()) v = "version.bin";
        return v;
    }

    private static String enc(String s) {
        return URLEncoder.encode(safe(s), StandardCharsets.UTF_8);
    }

    private static void writeJson(HttpServletResponse resp, int status, Map<String, Object> payload) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(payload == null ? new LinkedHashMap<String, Object>() : payload);
        resp.setStatus(status);
        resp.setContentType("application/json; charset=UTF-8");
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resp.setContentLength(bytes.length);
        resp.getOutputStream().write(bytes);
    }

    private static void writeText(HttpServletResponse resp, int status, String message) throws IOException {
        byte[] bytes = safe(message).getBytes(StandardCharsets.UTF_8);
        resp.setStatus(status);
        resp.setContentType("text/plain; charset=UTF-8");
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resp.setContentLength(bytes.length);
        resp.getOutputStream().write(bytes);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
