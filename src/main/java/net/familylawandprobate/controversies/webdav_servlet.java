package net.familylawandprobate.controversies;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class webdav_servlet extends HttpServlet {

    private static final Logger LOG = Logger.getLogger(webdav_servlet.class.getName());

    private static final DateTimeFormatter HTTP_DATE =
            DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC);

    private static final String ALLOW = "OPTIONS, GET, HEAD, PROPFIND, PUT, MKCOL, DELETE, LOCK, UNLOCK";

    private final webdav_service service = webdav_service.defaultService();

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String method = safe(req == null ? "" : req.getMethod()).trim().toUpperCase(Locale.ROOT);
        try {
            if ("OPTIONS".equals(method)) {
                handleOptions(resp);
                return;
            }
            if ("PROPFIND".equals(method)) {
                handlePropfind(req, resp);
                return;
            }
            if ("GET".equals(method)) {
                handleGet(req, resp, false);
                return;
            }
            if ("HEAD".equals(method)) {
                handleGet(req, resp, true);
                return;
            }
            if ("PUT".equals(method)) {
                handlePut(req, resp);
                return;
            }
            if ("MKCOL".equals(method)) {
                handleMkcol(req, resp);
                return;
            }
            if ("DELETE".equals(method)) {
                handleDelete(req, resp);
                return;
            }
            if ("LOCK".equals(method)) {
                handleLock(req, resp);
                return;
            }
            if ("UNLOCK".equals(method)) {
                handleUnlock(req, resp);
                return;
            }

            resp.setHeader("Allow", ALLOW);
            resp.sendError(405, "WebDAV method not supported.");
        } catch (webdav_service.WebDavException ex) {
            writeWebDavError(resp, ex);
        } catch (Exception ex) {
            LOG.log(Level.WARNING, "WebDAV request failed: " + ex.getMessage(), ex);
            resp.sendError(500, "WebDAV server error.");
        }
    }

    private void handleOptions(HttpServletResponse resp) {
        resp.setStatus(200);
        resp.setHeader("Allow", ALLOW);
        resp.setHeader("DAV", "1, 2");
        resp.setHeader("MS-Author-Via", "DAV");
        resp.setHeader("Accept-Ranges", "bytes");
    }

    private void handlePropfind(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        webdav_service.Principal principal = requirePrincipal(req);
        int depth = parseDepthHeader(req);

        List<webdav_service.Resource> resources = service.propfind(principal, req.getPathInfo(), depth);
        String xml = buildMultiStatusXml(req, resources);
        byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);

        resp.setStatus(207);
        resp.setContentType("application/xml; charset=UTF-8");
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resp.setContentLength(bytes.length);
        resp.getOutputStream().write(bytes);
    }

    private void handleGet(HttpServletRequest req, HttpServletResponse resp, boolean headOnly) throws Exception {
        webdav_service.Principal principal = requirePrincipal(req);
        webdav_service.Resource file = service.fileForGet(principal, req.getPathInfo());

        if (file.storagePath == null) {
            throw new webdav_service.WebDavException(404, "Version file not found.", false);
        }

        String mime = safe(file.contentType).trim();
        if (mime.isBlank()) mime = "application/octet-stream";

        resp.setStatus(200);
        resp.setHeader("Allow", ALLOW);
        resp.setHeader("Accept-Ranges", "bytes");
        if (!safe(file.etag).isBlank()) resp.setHeader("ETag", safe(file.etag));
        if (file.modifiedAt != null) resp.setHeader("Last-Modified", httpDate(file.modifiedAt));
        resp.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + enc(safe(file.fileName)));
        resp.setContentType(mime);
        if (file.contentLength >= 0L && file.contentLength <= Integer.MAX_VALUE) {
            resp.setContentLength((int) file.contentLength);
        }
        if (!headOnly) {
            try (var in = java.nio.file.Files.newInputStream(file.storagePath)) {
                in.transferTo(resp.getOutputStream());
            }
        }
    }

    private void handlePut(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        webdav_service.Principal principal = requirePrincipal(req);
        webdav_service.Resource created = service.put(
                principal,
                req.getPathInfo(),
                req.getContentType(),
                req.getInputStream()
        );

        resp.setStatus(201);
        resp.setHeader("Location", href(req, created));
        resp.setContentType("text/plain; charset=UTF-8");
        byte[] body = "Created".getBytes(StandardCharsets.UTF_8);
        resp.setContentLength(body.length);
        resp.getOutputStream().write(body);
    }

    private void handleMkcol(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        webdav_service.Principal principal = requirePrincipal(req);
        webdav_service.Resource created = service.mkcol(principal, req.getPathInfo());

        resp.setStatus(201);
        resp.setHeader("Location", href(req, created));
        resp.setContentLength(0);
    }

    private void handleDelete(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        webdav_service.Principal principal = requirePrincipal(req);
        service.delete(principal, req.getPathInfo());
        resp.setStatus(204);
        resp.setContentLength(0);
    }

    private void handleLock(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        requirePrincipal(req);

        String token = "opaquelocktoken:" + UUID.randomUUID();
        String timeout = safe(req.getHeader("Timeout")).trim();
        if (timeout.isBlank()) timeout = "Second-3600";

        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<D:prop xmlns:D=\"DAV:\">\n");
        sb.append("  <D:lockdiscovery>\n");
        sb.append("    <D:activelock>\n");
        sb.append("      <D:locktype><D:write/></D:locktype>\n");
        sb.append("      <D:lockscope><D:exclusive/></D:lockscope>\n");
        sb.append("      <D:depth>Infinity</D:depth>\n");
        sb.append("      <D:timeout>").append(xml(timeout)).append("</D:timeout>\n");
        sb.append("      <D:locktoken><D:href>").append(xml(token)).append("</D:href></D:locktoken>\n");
        sb.append("    </D:activelock>\n");
        sb.append("  </D:lockdiscovery>\n");
        sb.append("</D:prop>\n");

        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        resp.setStatus(200);
        resp.setHeader("Lock-Token", "<" + token + ">");
        resp.setContentType("application/xml; charset=UTF-8");
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resp.setContentLength(bytes.length);
        resp.getOutputStream().write(bytes);
    }

    private void handleUnlock(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        requirePrincipal(req);
        resp.setStatus(204);
        resp.setContentLength(0);
    }

    private webdav_service.Principal requirePrincipal(HttpServletRequest req) throws Exception {
        String auth = req == null ? "" : safe(req.getHeader("Authorization"));
        return service.authenticate(auth);
    }

    private static int parseDepthHeader(HttpServletRequest req) {
        String raw = safe(req == null ? "" : req.getHeader("Depth")).trim().toLowerCase(Locale.ROOT);
        if ("0".equals(raw)) return 0;
        if ("1".equals(raw) || "infinity".equals(raw)) return 1;
        if (raw.isBlank()) return 1;
        return 1;
    }

    private String buildMultiStatusXml(HttpServletRequest req, List<webdav_service.Resource> resources) {
        ArrayList<webdav_service.Resource> rows = new ArrayList<webdav_service.Resource>(resources == null ? List.of() : resources);

        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<D:multistatus xmlns:D=\"DAV:\">\n");

        for (webdav_service.Resource r : rows) {
            String href = href(req, r);
            sb.append("  <D:response>\n");
            sb.append("    <D:href>").append(xml(href)).append("</D:href>\n");
            sb.append("    <D:propstat>\n");
            sb.append("      <D:prop>\n");
            sb.append("        <D:displayname>").append(xml(r == null ? "" : r.displayName)).append("</D:displayname>\n");

            if (r != null && r.collection) {
                sb.append("        <D:resourcetype><D:collection/></D:resourcetype>\n");
                sb.append("        <D:getcontentlength>0</D:getcontentlength>\n");
            } else {
                sb.append("        <D:resourcetype/>\n");
                long len = r == null ? -1L : r.contentLength;
                sb.append("        <D:getcontentlength>").append(len < 0L ? 0L : len).append("</D:getcontentlength>\n");
                String ct = safe(r == null ? "" : r.contentType).trim();
                if (!ct.isBlank()) {
                    sb.append("        <D:getcontenttype>").append(xml(ct)).append("</D:getcontenttype>\n");
                }
                String etag = safe(r == null ? "" : r.etag).trim();
                if (!etag.isBlank()) {
                    sb.append("        <D:getetag>").append(xml(etag)).append("</D:getetag>\n");
                }
            }

            Instant modified = r == null ? null : r.modifiedAt;
            if (modified != null) {
                sb.append("        <D:getlastmodified>").append(xml(httpDate(modified))).append("</D:getlastmodified>\n");
            }

            sb.append("      </D:prop>\n");
            sb.append("      <D:status>HTTP/1.1 200 OK</D:status>\n");
            sb.append("    </D:propstat>\n");
            sb.append("  </D:response>\n");
        }

        sb.append("</D:multistatus>\n");
        return sb.toString();
    }

    private static String href(HttpServletRequest req, webdav_service.Resource resource) {
        String contextPath = safe(req == null ? "" : req.getContextPath());
        StringBuilder sb = new StringBuilder();
        sb.append(contextPath).append("/webdav");

        List<String> segments = resource == null ? List.of() : resource.canonicalSegments;
        if (segments == null || segments.isEmpty()) {
            sb.append("/");
            return sb.toString();
        }

        for (String segment : segments) {
            sb.append('/').append(enc(segment));
        }
        if (resource.collection) sb.append('/');
        return sb.toString();
    }

    private static String httpDate(Instant instant) {
        Instant ts = instant == null ? app_clock.now() : instant;
        return HTTP_DATE.format(ts);
    }

    private void writeWebDavError(HttpServletResponse resp, webdav_service.WebDavException ex) throws IOException {
        if (ex == null) {
            resp.sendError(500, "WebDAV server error.");
            return;
        }
        if (ex.challenge || ex.status == 401) {
            resp.setHeader("WWW-Authenticate", "Basic realm=\"" + webdav_service.REALM + "\", charset=\"UTF-8\"");
        }
        int code = ex.status <= 0 ? 500 : ex.status;
        String msg = safe(ex.getMessage()).trim();
        if (msg.isBlank()) {
            resp.sendError(code);
        } else {
            resp.sendError(code, msg);
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(safe(s), StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String xml(String s) {
        return safe(s)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
