package net.familylawandprobate.controversies;

import java.io.InputStream;
import java.io.IOException;
import java.io.StringReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Locale;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.jsp.PageContext;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.*;
import org.xml.sax.InputSource;

/**
 * security
 *
 * IMPORTANT:
 * - Preserves legacy JSP usage:
 *     require_loopback()
 *     require_login()
 *     require_permission(String)
 *
 * - Avoids JSP duplicate-declaration errors by moving implementation to Java.
 *
 * - When not authenticated, ALWAYS redirects to login pages with ?next=... (does not "process" final redirect).
 */
public final class security {

    private security() {}

    // Session keys (tenant)
    private static final String S_TENANT_UUID  = "tenant.uuid";
    private static final String S_TENANT_LABEL = "tenant.label";

    // Cookie names
    private static final String TENANT_BIND_COOKIE = "TENANT_BIND";
    private static final String USER_BIND_COOKIE   = "USER_BIND";

    // -----------------------------
    // Thread-local request context
    // -----------------------------
    private static final class Ctx {
        final HttpServletRequest req;
        final HttpServletResponse res;
        final PageContext pc;
        final HttpSession session;
        Ctx(HttpServletRequest req, HttpServletResponse res, PageContext pc, HttpSession session) {
            this.req = req;
            this.res = res;
            this.pc = pc;
            this.session = session;
        }
    }

    private static final ThreadLocal<Ctx> TL = new ThreadLocal<Ctx>();

    /** Called by security.jspf on every request. Safe to call multiple times. */
    public static void sec_bind(HttpServletRequest req,
                               HttpServletResponse res,
                               PageContext pc,
                               HttpSession session) {
        TL.set(new Ctx(req, res, pc, session));
    }

    private static Ctx ctx() {
        Ctx c = TL.get();
        if (c == null || c.req == null || c.res == null) {
            throw new IllegalStateException("security not bound. Ensure <%@ include file=\"security.jspf\" %> is present.");
        }
        return c;
    }

    // -----------------------------
    // Helpers
    // -----------------------------
    private static String safe(String s) { return (s == null) ? "" : s; }

    private static boolean isBlank(String s) { return safe(s).trim().isEmpty(); }

    private static String normalizeNext(String next) {
        String n = safe(next).trim();
        if (n.isEmpty()) return "/index.jsp";
        if (n.contains("://") || n.startsWith("//") || n.contains("\r") || n.contains("\n")) return "/index.jsp";
        if (!n.startsWith("/")) return "/index.jsp";
        return n;
    }

    private static String buildNextFromRequest(HttpServletRequest req) {
        if (req == null) return "/index.jsp";

        String ctx = safe(req.getContextPath());
        String uri = safe(req.getRequestURI());
        if (uri.isEmpty()) uri = "/index.jsp";

        if (!ctx.isEmpty() && uri.startsWith(ctx)) uri = uri.substring(ctx.length());
        if (uri.isEmpty()) uri = "/index.jsp";

        String qs = req.getQueryString();
        String combined = (qs == null || qs.isEmpty()) ? uri : (uri + "?" + qs);

        return normalizeNext(combined);
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(safe(s), StandardCharsets.UTF_8.name());
        } catch (Exception ignored) {
            return "";
        }
    }

    private static boolean isLoopbackOnly(String ip) {
        String s = safe(ip).trim();
        return "127.0.0.1".equals(s)
                || "::1".equals(s)
                || "0:0:0:0:0:0:0:1".equalsIgnoreCase(s);
    }

    private static boolean isLocalhostIp(String ip) {
        String s = safe(ip).trim();
        return "127.0.0.1".equals(s)
                || "::1".equals(s)
                || "0:0:0:0:0:0:0:1".equalsIgnoreCase(s)
                || s.startsWith("127.");
    }

    private static boolean isPrivateOrLoopback(String ip) {
        String s = safe(ip).trim();
        if (isLocalhostIp(s)) return true;
        if (s.startsWith("10.")) return true;
        if (s.startsWith("192.168.")) return true;
        if (s.startsWith("172.")) {
            String[] parts = s.split("\\.");
            if (parts.length >= 2) {
                try {
                    int b = Integer.parseInt(parts[1]);
                    return b >= 16 && b <= 31;
                } catch (Exception ignored) {}
            }
        }
        return false;
    }

    /** Trust proxy headers only when remoteAddr is private/loopback. */
    private static String getClientIp(HttpServletRequest req) {
        if (req == null) return "";
        String ra = safe(req.getRemoteAddr());

        if (isPrivateOrLoopback(ra)) {
            String xff = req.getHeader("X-Forwarded-For");
            if (xff != null && !xff.trim().isEmpty()) {
                int comma = xff.indexOf(',');
                String first = (comma > 0 ? xff.substring(0, comma) : xff).trim();
                if (!first.isEmpty()) return first;
            }
            String xrip = req.getHeader("X-Real-IP");
            if (xrip != null && !xrip.trim().isEmpty()) return xrip.trim();
        }
        return ra;
    }

    private static String getCookieValue(HttpServletRequest req, String name) {
        if (req == null || isBlank(name)) return "";
        Cookie[] cs = req.getCookies();
        if (cs == null) return "";
        for (int i = 0; i < cs.length; i++) {
            Cookie c = cs[i];
            if (c != null && name.equals(c.getName())) return safe(c.getValue());
        }
        return "";
    }

    private static void deleteCookie(HttpServletRequest req, HttpServletResponse res, String name) {
        if (req == null || res == null || isBlank(name)) return;

        String ctx = safe(req.getContextPath());
        if (ctx.isEmpty()) ctx = "/";

        boolean secure = req.isSecure();

        StringBuilder sb = new StringBuilder();
        sb.append(name).append("=; Path=").append(ctx).append("; Max-Age=0");
        sb.append("; HttpOnly");
        if (secure) sb.append("; Secure");
        sb.append("; SameSite=Strict");
        res.addHeader("Set-Cookie", sb.toString());
    }

    private static void deleteQuiet(Path p) {
        if (p == null) return;
        try { Files.deleteIfExists(p); } catch (Exception ignored) {}
    }

    private static String safeFileToken(String s) {
        String t = safe(s).trim();
        if (t.isEmpty()) return "unknown";
        return t.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static Path tenantBindingPath(String tenantUuid, String sessionId) {
        String tu = safeFileToken(tenantUuid);
        String sid = safeFileToken(sessionId);
        return Paths.get("data", "tenants", tu, "bindings", "tenant_binding_" + sid + ".xml").toAbsolutePath();
    }

    private static Path legacyTenantBindingPath(String tenantUuid) {
        String tu = safeFileToken(tenantUuid);
        return Paths.get("data", "tenants", tu, "bindings", "tenant_binding.xml").toAbsolutePath();
    }

    private static DocumentBuilder secureBuilder() throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false);
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);

        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

        DocumentBuilder b = f.newDocumentBuilder();
        b.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
        return b;
    }

    private static final class TenantBinding {
        final String tenantUuid;
        final String label;
        final String ip;
        final String sessionId;
        final String cookie;
        TenantBinding(String tenantUuid, String label, String ip, String sessionId, String cookie) {
            this.tenantUuid = safe(tenantUuid);
            this.label = safe(label);
            this.ip = safe(ip);
            this.sessionId = safe(sessionId);
            this.cookie = safe(cookie);
        }
    }

    private static TenantBinding readTenantBinding(Path p) {
        try {
            if (p == null || !Files.exists(p)) return null;

            DocumentBuilder b = secureBuilder();
            try (InputStream in = Files.newInputStream(p)) {
                Document d = b.parse(in);
                d.getDocumentElement().normalize();
                Element root = d.getDocumentElement();
                if (root == null) return null;

                String tu = safe(root.getAttribute("tenantUuid"));
                String lbl = safe(root.getAttribute("label"));

                String ip = "";
                String sid = "";
                String cb = "";

                NodeList nl = root.getChildNodes();
                for (int i = 0; i < nl.getLength(); i++) {
                    Node n = nl.item(i);
                    if (!(n instanceof Element)) continue;
                    Element e = (Element) n;

                    String tag = safe(e.getTagName()).trim();
                    String val = safe(e.getTextContent()).trim();

                    if ("ip".equalsIgnoreCase(tag)) ip = val;
                    else if ("sessionId".equalsIgnoreCase(tag)) sid = val;
                    else if ("cookie".equalsIgnoreCase(tag)) cb = val;
                }

                return new TenantBinding(tu, lbl, ip, sid, cb);
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean tenantBindingMatches(TenantBinding b,
                                               String tenantUuid,
                                               String tenantLabel,
                                               String ip,
                                               String sessionId,
                                               String cookie) {
        if (b == null) return false;
        return safe(tenantUuid).equals(b.tenantUuid)
                && safe(tenantLabel).equalsIgnoreCase(b.label)
                && safe(ip).equals(b.ip)
                && safe(sessionId).equals(b.sessionId)
                && safe(cookie).equals(b.cookie);
    }

    // -----------------------------
    // PUBLIC METHODS (SIGNATURES UNCHANGED)
    // -----------------------------
    public static boolean require_loopback() throws IOException {
        Ctx c = ctx();
        String remote = safe(c.req.getRemoteAddr()).trim();
        if (isLoopbackOnly(remote)) return true;

        c.res.setStatus(403);
        c.res.setContentType("text/html; charset=UTF-8");
        c.res.getWriter().write("<html><body><h1>Forbidden</h1><p>This page may only be accessed from the local machine.</p></body></html>");
        return false;
    }

    /**
     * Enforces tenant+user auth + binding files.
     * If missing/invalid => redirect to unified login page with ?next=... ALWAYS.
     */
    public static boolean require_login() throws IOException {
        Ctx c = ctx();
        HttpServletRequest req = c.req;
        HttpServletResponse res = c.res;

        String ctxPath = safe(req.getContextPath());
        if (ctxPath.isEmpty()) ctxPath = "";

        // next: prefer explicit param, else build from current request
        String nextParam = req.getParameter("next");
        String next = normalizeNext(isBlank(nextParam) ? buildNextFromRequest(req) : nextParam);

        // Prevent accidental self-redirect loops if someone calls require_login() on login pages.
        String uri = safe(req.getRequestURI());
        if (!ctxPath.isEmpty() && uri.startsWith(ctxPath)) uri = uri.substring(ctxPath.length());
        String uriLower = safe(uri).toLowerCase(Locale.ROOT);
        if (uriLower.endsWith("/tenant_login.jsp") || uriLower.endsWith("/user_login.jsp")) {
            return true;
        }

        HttpSession sess = (c.session != null) ? c.session : req.getSession(false);
        if (sess == null) {
            res.sendRedirect(ctxPath + "/tenant_login.jsp?next=" + enc(next));
            return false;
        }

        String sid = safe(sess.getId()).trim();
        String clientIp = getClientIp(req);

        // tenant session
        String tenantUuid  = safe((String) sess.getAttribute(S_TENANT_UUID)).trim();
        String tenantLabel = safe((String) sess.getAttribute(S_TENANT_LABEL)).trim();

        if (tenantUuid.isEmpty() || tenantLabel.isEmpty()) {
            cleanupTenant(sess, req, res, tenantUuid, sid);
            res.sendRedirect(ctxPath + "/tenant_login.jsp?next=" + enc(next));
            return false;
        }

        // tenant binding check (session file; allow legacy match too)
        String tenantCookie = getCookieValue(req, TENANT_BIND_COOKIE);

        TenantBinding tb = readTenantBinding(tenantBindingPath(tenantUuid, sid));
        if (tb == null) {
            TenantBinding legacy = readTenantBinding(legacyTenantBindingPath(tenantUuid));
            if (legacy != null && tenantBindingMatches(legacy, tenantUuid, tenantLabel, clientIp, sid, tenantCookie)) {
                tb = legacy;
            }
        }

        if (!tenantBindingMatches(tb, tenantUuid, tenantLabel, clientIp, sid, tenantCookie)) {
            cleanupTenant(sess, req, res, tenantUuid, sid);
            res.sendRedirect(ctxPath + "/tenant_login.jsp?next=" + enc(next));
            return false;
        }

        // user session
        String userUuid = safe((String) sess.getAttribute(users_roles.S_USER_UUID)).trim();
        String userEmail = safe((String) sess.getAttribute(users_roles.S_USER_EMAIL)).trim();
        String roleUuid = safe((String) sess.getAttribute(users_roles.S_ROLE_UUID)).trim();

        if (userUuid.isEmpty() || userEmail.isEmpty() || roleUuid.isEmpty()) {
            cleanupUser(sess, req, res, tenantUuid, userUuid, sid);
            res.sendRedirect(ctxPath + "/tenant_login.jsp?next=" + enc(next));
            return false;
        }

        // user binding file check
        String userCookie = getCookieValue(req, USER_BIND_COOKIE);
        users_roles ur = users_roles.defaultStore();

        boolean ok = false;
        try {
            ok = ur.userBindingMatches(tenantUuid, userUuid, sid, clientIp, userCookie);
        } catch (Exception ignored) {
            ok = false;
        }

        if (!ok) {
            cleanupUser(sess, req, res, tenantUuid, userUuid, sid);
            res.sendRedirect(ctxPath + "/tenant_login.jsp?next=" + enc(next));
            return false;
        }

        // enforce still enabled (user + role)
        try {
            users_roles.UserRec u = ur.getUserByUuid(tenantUuid, userUuid);
            if (u == null || !u.enabled) {
                cleanupUser(sess, req, res, tenantUuid, userUuid, sid);
                res.sendRedirect(ctxPath + "/tenant_login.jsp?next=" + enc(next));
                return false;
            }
            users_roles.RoleRec r = ur.getRoleByUuid(tenantUuid, roleUuid);
            if (r == null || !r.enabled) {
                cleanupUser(sess, req, res, tenantUuid, userUuid, sid);
                res.sendRedirect(ctxPath + "/tenant_login.jsp?next=" + enc(next));
                return false;
            }
        } catch (Exception ignored) {
            cleanupUser(sess, req, res, tenantUuid, userUuid, sid);
            res.sendRedirect(ctxPath + "/tenant_login.jsp?next=" + enc(next));
            return false;
        }

        return true;
    }

    public static boolean require_permission(String key) throws IOException {
        Ctx c = ctx();
        if (!require_login()) return false;

        HttpSession sess = (c.session != null) ? c.session : c.req.getSession(false);
        String k = safe(key).trim();

        if (sess == null || k.isEmpty()) {
            c.res.setStatus(403);
            c.res.setContentType("text/plain; charset=UTF-8");
            c.res.getWriter().write("Forbidden");
            return false;
        }

        if (users_roles.hasPermissionTrue(sess, k)) return true;

        c.res.setStatus(403);
        c.res.setContentType("text/plain; charset=UTF-8");
        c.res.getWriter().write("Forbidden");
        return false;
    }

    // -----------------------------
    // Cleanup helpers
    // -----------------------------
    private static void cleanupTenant(HttpSession sess,
                                      HttpServletRequest req,
                                      HttpServletResponse res,
                                      String tenantUuid,
                                      String sessionId) {
        try {
            if (sess != null) {
                sess.removeAttribute(S_TENANT_UUID);
                sess.removeAttribute(S_TENANT_LABEL);
                sess.removeAttribute("tenant.sid");
                sess.removeAttribute("tenant.ip");
                sess.removeAttribute("tenant.cookieBind");
            }
        } catch (Exception ignored) {}

        try { deleteCookie(req, res, TENANT_BIND_COOKIE); } catch (Exception ignored) {}
        try { deleteQuiet(tenantBindingPath(tenantUuid, sessionId)); } catch (Exception ignored) {}

        // also clear user
        try { cleanupUser(sess, req, res, tenantUuid, "", sessionId); } catch (Exception ignored) {}
    }

    private static void cleanupUser(HttpSession sess,
                                    HttpServletRequest req,
                                    HttpServletResponse res,
                                    String tenantUuid,
                                    String userUuid,
                                    String sessionId) {
        try {
            if (sess != null) {
                users_roles ur = users_roles.defaultStore();
                ur.clearSessionAuth(sess);
            }
        } catch (Exception ignored) {}

        try { deleteCookie(req, res, USER_BIND_COOKIE); } catch (Exception ignored) {}

        try {
            if (!isBlank(tenantUuid) && !isBlank(userUuid) && !isBlank(sessionId)) {
                users_roles ur = users_roles.defaultStore();
                ur.deleteUserBinding(tenantUuid, userUuid, sessionId);
            }
        } catch (Exception ignored) {}
    }
}
