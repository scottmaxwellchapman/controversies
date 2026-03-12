package net.familylawandprobate.controversies;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class caldav_servlet extends HttpServlet {

    private static final Logger LOG = Logger.getLogger(caldav_servlet.class.getName());

    private static final DateTimeFormatter HTTP_DATE =
            DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC);

    private static final String ALLOW = "OPTIONS, GET, HEAD, PROPFIND, REPORT, PUT, DELETE, MKCALENDAR";

    private final caldav_service service = caldav_service.defaultService();

    private static final class ReportRequest {
        String reportType = "";
        String timeRangeStart = "";
        String timeRangeEnd = "";
        ArrayList<String> hrefs = new ArrayList<String>();
    }

    private static final class MkcalendarRequest {
        String displayName = "";
        String timezone = "";
        String color = "";
    }

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
            if ("REPORT".equals(method)) {
                handleReport(req, resp);
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
            if ("DELETE".equals(method)) {
                handleDelete(req, resp);
                return;
            }
            if ("MKCALENDAR".equals(method)) {
                handleMkcalendar(req, resp);
                return;
            }

            resp.setHeader("Allow", ALLOW);
            resp.sendError(405, "CalDAV method not supported.");
        } catch (caldav_service.CalDavException ex) {
            writeCalDavError(resp, ex);
        } catch (Exception ex) {
            LOG.log(Level.WARNING, "CalDAV request failed: " + ex.getMessage(), ex);
            resp.sendError(500, "CalDAV server error.");
        }
    }

    private void handleOptions(HttpServletResponse resp) {
        resp.setStatus(200);
        resp.setHeader("Allow", ALLOW);
        resp.setHeader("DAV", "1, calendar-access");
        resp.setHeader("MS-Author-Via", "DAV");
    }

    private void handlePropfind(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        caldav_service.Principal principal = requirePrincipal(req);
        int depth = parseDepthHeader(req);

        List<caldav_service.Resource> resources = service.propfind(principal, req.getPathInfo(), depth);
        String xml = buildPropfindXml(req, principal, resources);
        byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);

        resp.setStatus(207);
        resp.setContentType("application/xml; charset=UTF-8");
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resp.setContentLength(bytes.length);
        resp.getOutputStream().write(bytes);
    }

    private void handleReport(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        caldav_service.Principal principal = requirePrincipal(req);
        ReportRequest report = parseReportRequest(req);

        List<caldav_service.Resource> resources;
        if ("calendar-multiget".equals(report.reportType)) {
            resources = service.reportCalendarMultiGet(principal, req.getPathInfo(), report.hrefs);
        } else {
            resources = service.reportCalendarQuery(principal, req.getPathInfo(), report.timeRangeStart, report.timeRangeEnd);
        }

        String xml = buildReportXml(req, resources);
        byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);

        resp.setStatus(207);
        resp.setContentType("application/xml; charset=UTF-8");
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resp.setContentLength(bytes.length);
        resp.getOutputStream().write(bytes);
    }

    private void handleGet(HttpServletRequest req, HttpServletResponse resp, boolean headOnly) throws Exception {
        caldav_service.Principal principal = requirePrincipal(req);
        caldav_service.Resource event = service.eventForGet(principal, req.getPathInfo());

        String ics = service.eventAsIcs(event);
        byte[] bytes = ics.getBytes(StandardCharsets.UTF_8);

        resp.setStatus(200);
        resp.setHeader("Allow", ALLOW);
        if (!safe(event.etag).isBlank()) resp.setHeader("ETag", safe(event.etag));
        if (event.modifiedAt != null) resp.setHeader("Last-Modified", httpDate(event.modifiedAt));
        resp.setContentType("text/calendar; charset=UTF-8");
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resp.setContentLength(bytes.length);

        if (!headOnly) {
            resp.getOutputStream().write(bytes);
        }
    }

    private void handlePut(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        caldav_service.Principal principal = requirePrincipal(req);
        caldav_service.PutResult saved = service.putCalendarObject(principal, req.getPathInfo(), req.getInputStream());

        int status = saved.created ? 201 : 204;
        resp.setStatus(status);
        if (saved.resource != null) {
            resp.setHeader("Location", href(req, saved.resource));
            if (!safe(saved.resource.etag).isBlank()) resp.setHeader("ETag", safe(saved.resource.etag));
        }
        resp.setContentLength(0);
    }

    private void handleDelete(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        caldav_service.Principal principal = requirePrincipal(req);
        service.deleteCalendarObject(principal, req.getPathInfo());
        resp.setStatus(204);
        resp.setContentLength(0);
    }

    private void handleMkcalendar(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        caldav_service.Principal principal = requirePrincipal(req);
        MkcalendarRequest parsed = parseMkcalendarRequest(req);

        caldav_service.Resource created = service.mkcalendar(
                principal,
                req.getPathInfo(),
                parsed.displayName,
                parsed.timezone,
                parsed.color
        );

        resp.setStatus(201);
        resp.setHeader("Location", href(req, created));
        resp.setContentLength(0);
    }

    private caldav_service.Principal requirePrincipal(HttpServletRequest req) throws Exception {
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

    private static String buildPropfindXml(HttpServletRequest req,
                                           caldav_service.Principal principal,
                                           List<caldav_service.Resource> resources) {
        ArrayList<caldav_service.Resource> rows = new ArrayList<caldav_service.Resource>(resources == null ? List.of() : resources);

        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<D:multistatus xmlns:D=\"DAV:\" xmlns:C=\"urn:ietf:params:xml:ns:caldav\">\n");

        for (caldav_service.Resource row : rows) {
            String href = href(req, row);
            sb.append("  <D:response>\n");
            sb.append("    <D:href>").append(xml(href)).append("</D:href>\n");
            sb.append("    <D:propstat>\n");
            sb.append("      <D:prop>\n");

            sb.append("        <D:displayname>").append(xml(safe(row == null ? "" : row.displayName))).append("</D:displayname>\n");

            appendResourceType(sb, row);

            if (row != null && row.kind == caldav_service.Kind.ROOT) {
                String principalHref = req == null
                        ? "/caldav/principals/"
                        : safe(req.getContextPath()) + "/caldav/principals/" + enc(principal.tenantSlug) + "/" + enc(principal.email) + "/";
                sb.append("        <D:current-user-principal><D:href>")
                        .append(xml(principalHref))
                        .append("</D:href></D:current-user-principal>\n");
            }

            if (row != null && row.kind == caldav_service.Kind.PRINCIPAL) {
                String homeHref = req == null
                        ? "/caldav/calendars/"
                        : safe(req.getContextPath()) + "/caldav/calendars/" + enc(principal.tenantSlug) + "/" + enc(principal.email) + "/";
                sb.append("        <C:calendar-home-set><D:href>")
                        .append(xml(homeHref))
                        .append("</D:href></C:calendar-home-set>\n");
            }

            if (row != null && row.kind == caldav_service.Kind.CALENDAR) {
                sb.append("        <C:supported-calendar-component-set>")
                        .append("<C:comp name=\"VEVENT\"/>")
                        .append("</C:supported-calendar-component-set>\n");
            }

            if (row != null && row.kind == caldav_service.Kind.EVENT) {
                if (!safe(row.etag).isBlank()) {
                    sb.append("        <D:getetag>").append(xml(row.etag)).append("</D:getetag>\n");
                }
                sb.append("        <D:getcontenttype>text/calendar; charset=UTF-8</D:getcontenttype>\n");
            }

            if (row != null && row.modifiedAt != null) {
                sb.append("        <D:getlastmodified>").append(xml(httpDate(row.modifiedAt))).append("</D:getlastmodified>\n");
            }

            sb.append("      </D:prop>\n");
            sb.append("      <D:status>HTTP/1.1 200 OK</D:status>\n");
            sb.append("    </D:propstat>\n");
            sb.append("  </D:response>\n");
        }

        sb.append("</D:multistatus>\n");
        return sb.toString();
    }

    private static String buildReportXml(HttpServletRequest req,
                                         List<caldav_service.Resource> resources) {
        ArrayList<caldav_service.Resource> rows = new ArrayList<caldav_service.Resource>(resources == null ? List.of() : resources);

        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<D:multistatus xmlns:D=\"DAV:\" xmlns:C=\"urn:ietf:params:xml:ns:caldav\">\n");

        for (caldav_service.Resource row : rows) {
            if (row == null || row.kind != caldav_service.Kind.EVENT) continue;

            String href = href(req, row);
            String ics = calendar_system.defaultStore().toIcs(row.event);

            sb.append("  <D:response>\n");
            sb.append("    <D:href>").append(xml(href)).append("</D:href>\n");
            sb.append("    <D:propstat>\n");
            sb.append("      <D:prop>\n");
            if (!safe(row.etag).isBlank()) {
                sb.append("        <D:getetag>").append(xml(row.etag)).append("</D:getetag>\n");
            }
            sb.append("        <C:calendar-data>").append(xml(ics)).append("</C:calendar-data>\n");
            sb.append("      </D:prop>\n");
            sb.append("      <D:status>HTTP/1.1 200 OK</D:status>\n");
            sb.append("    </D:propstat>\n");
            sb.append("  </D:response>\n");
        }

        sb.append("</D:multistatus>\n");
        return sb.toString();
    }

    private static void appendResourceType(StringBuilder sb, caldav_service.Resource row) {
        if (row == null) {
            sb.append("        <D:resourcetype/>\n");
            return;
        }

        if (row.kind == caldav_service.Kind.CALENDAR) {
            sb.append("        <D:resourcetype><D:collection/><C:calendar/></D:resourcetype>\n");
            return;
        }

        if (row.kind == caldav_service.Kind.PRINCIPAL) {
            sb.append("        <D:resourcetype><D:collection/><D:principal/></D:resourcetype>\n");
            return;
        }

        if (row.collection) {
            sb.append("        <D:resourcetype><D:collection/></D:resourcetype>\n");
            return;
        }

        sb.append("        <D:resourcetype/>\n");
    }

    private static String href(HttpServletRequest req, caldav_service.Resource resource) {
        String contextPath = safe(req == null ? "" : req.getContextPath());
        StringBuilder sb = new StringBuilder();
        sb.append(contextPath).append("/caldav");

        List<String> segments = resource == null ? List.of() : resource.canonicalSegments;
        if (segments == null || segments.isEmpty()) {
            sb.append('/');
            return sb.toString();
        }

        for (String segment : segments) {
            sb.append('/').append(enc(segment));
        }
        if (resource.collection) sb.append('/');
        return sb.toString();
    }

    private static ReportRequest parseReportRequest(HttpServletRequest req) throws Exception {
        ReportRequest out = new ReportRequest();
        byte[] payload = readBody(req == null ? null : req.getInputStream(), 512_000);
        if (payload.length == 0) {
            out.reportType = "calendar-query";
            return out;
        }

        String xml = new String(payload, StandardCharsets.UTF_8);
        Document doc = parseXml(xml);
        Element root = doc == null ? null : doc.getDocumentElement();
        if (root == null) {
            out.reportType = "calendar-query";
            return out;
        }

        String local = localName(root);
        if ("calendar-multiget".equals(local)) {
            out.reportType = "calendar-multiget";
        } else {
            out.reportType = "calendar-query";
        }

        if ("calendar-query".equals(out.reportType)) {
            NodeList times = root.getElementsByTagNameNS("*", "time-range");
            if (times != null && times.getLength() > 0) {
                for (int i = 0; i < times.getLength(); i++) {
                    Node n = times.item(i);
                    if (!(n instanceof Element el)) continue;
                    String start = safe(el.getAttribute("start")).trim();
                    String end = safe(el.getAttribute("end")).trim();
                    if (!start.isBlank()) out.timeRangeStart = normalizeReportDateTime(start);
                    if (!end.isBlank()) out.timeRangeEnd = normalizeReportDateTime(end);
                    if (!out.timeRangeStart.isBlank() || !out.timeRangeEnd.isBlank()) break;
                }
            }
            return out;
        }

        NodeList hrefNodes = root.getElementsByTagNameNS("*", "href");
        if (hrefNodes != null) {
            for (int i = 0; i < hrefNodes.getLength(); i++) {
                Node n = hrefNodes.item(i);
                if (n == null) continue;
                String href = safe(n.getTextContent()).trim();
                if (href.isBlank()) continue;
                out.hrefs.add(href);
            }
        }

        return out;
    }

    private static MkcalendarRequest parseMkcalendarRequest(HttpServletRequest req) throws Exception {
        MkcalendarRequest out = new MkcalendarRequest();
        byte[] payload = readBody(req == null ? null : req.getInputStream(), 256_000);
        if (payload.length == 0) return out;

        String xml = new String(payload, StandardCharsets.UTF_8);
        Document doc = parseXml(xml);
        if (doc == null || doc.getDocumentElement() == null) return out;

        Element root = doc.getDocumentElement();

        String display = firstElementText(root, "displayname");
        String tz = firstElementText(root, "calendar-timezone");
        String color = firstElementText(root, "calendar-color");

        out.displayName = safe(display).trim();
        out.timezone = safe(tz).trim();
        out.color = safe(color).trim();
        return out;
    }

    private static String normalizeReportDateTime(String raw) {
        String v = safe(raw).trim();
        if (v.isBlank()) return "";

        try {
            if (v.matches("\\d{8}T\\d{6}Z")) {
                java.time.LocalDateTime dt = java.time.LocalDateTime.parse(
                        v,
                        java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                );
                return dt.toInstant(ZoneOffset.UTC).toString();
            }
        } catch (Exception ignored) {
        }

        try {
            return Instant.parse(v).toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String firstElementText(Element root, String localName) {
        if (root == null || safe(localName).isBlank()) return "";
        NodeList nodes = root.getElementsByTagNameNS("*", localName);
        if (nodes == null || nodes.getLength() == 0) return "";
        for (int i = 0; i < nodes.getLength(); i++) {
            Node n = nodes.item(i);
            if (!(n instanceof Element e)) continue;
            String v = safe(e.getTextContent()).trim();
            if (!v.isBlank()) return v;
        }
        return "";
    }

    private static Document parseXml(String xml) {
        try {
            if (safe(xml).trim().isBlank()) return null;
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

            DocumentBuilder b = f.newDocumentBuilder();
            b.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
            return b.parse(new InputSource(new StringReader(xml)));
        } catch (Exception ex) {
            return null;
        }
    }

    private static String localName(Node n) {
        if (n == null) return "";
        String local = safe(n.getLocalName());
        if (!local.isBlank()) return local;
        String name = safe(n.getNodeName());
        int idx = name.indexOf(':');
        if (idx >= 0 && idx + 1 < name.length()) return name.substring(idx + 1);
        return name;
    }

    private static byte[] readBody(java.io.InputStream in, int maxBytes) throws Exception {
        if (in == null) return new byte[0];
        int cap = Math.max(1024, maxBytes);
        try (java.io.InputStream body = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int total = 0;
            while (true) {
                int n = body.read(buf);
                if (n < 0) break;
                if (n == 0) continue;
                total += n;
                if (total > cap) throw new IllegalArgumentException("Request body too large.");
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static String httpDate(Instant instant) {
        Instant ts = instant == null ? app_clock.now() : instant;
        return HTTP_DATE.format(ts);
    }

    private void writeCalDavError(HttpServletResponse resp, caldav_service.CalDavException ex) throws IOException {
        if (ex == null) {
            resp.sendError(500, "CalDAV server error.");
            return;
        }
        if (ex.challenge || ex.status == 401) {
            resp.setHeader("WWW-Authenticate", "Basic realm=\"" + caldav_service.REALM + "\", charset=\"UTF-8\"");
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
