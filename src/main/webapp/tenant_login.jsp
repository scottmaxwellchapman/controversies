<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>

<%@ page import="java.io.*" %>
<%@ page import="java.nio.charset.StandardCharsets" %>
<%@ page import="java.nio.file.*" %>
<%@ page import="java.security.SecureRandom" %>
<%@ page import="java.time.*" %>
<%@ page import="java.util.*" %>
<%@ page import="java.util.concurrent.ConcurrentHashMap" %>

<%@ page import="jakarta.servlet.ServletContext" %>
<%@ page import="jakarta.servlet.http.Cookie" %>
<%@ page import="jakarta.servlet.http.HttpSession" %>

<%@ page import="javax.xml.XMLConstants" %>
<%@ page import="javax.xml.parsers.DocumentBuilder" %>
<%@ page import="javax.xml.parsers.DocumentBuilderFactory" %>
<%@ page import="org.w3c.dom.*" %>

<%@ page import="net.familylawandprobate.controversies.tenants" %>
<%@ page import="net.familylawandprobate.controversies.ip_lists" %>
<%@ page import="net.familylawandprobate.controversies.users_roles" %>
<%@ include file="security.jspf" %>

<%
  if (!require_loopback()) return;
%>

<%!
    // -----------------------------
    // Session keys (tenant auth)
    // -----------------------------
    private static final String S_TENANT_UUID  = "tenant.uuid";
    private static final String S_TENANT_LABEL = "tenant.label";
    private static final String S_TENANT_SID   = "tenant.sid";
    private static final String S_TENANT_IP    = "tenant.ip";
    private static final String S_TENANT_CB    = "tenant.cookieBind";

    // Cookie binding
    private static final String TENANT_BIND_COOKIE = "TENANT_BIND";
    private static final String USER_BIND_COOKIE = "USER_BIND";

    // CSRF session key (matches filter default)
    private static final String CSRF_SESSION_KEY = "CSRF_TOKEN";

    // Brute force tracking (memory) + escalation thresholds
    private static final String FAILMAP_KEY = "tenantLogin.failMap.v1";
    private static final long FAIL_WINDOW_MS = 10L * 60L * 1000L; // 10 minutes

    private static final SecureRandom RNG = new SecureRandom();

    private static final class FailInfo {
        int count;
        long firstMs;
        long lastMs;
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&","&amp;")
                .replace("<","&lt;")
                .replace(">","&gt;")
                .replace("\"","&quot;")
                .replace("'","&#39;");
    }

    private static String xmlAttr(String s) {
        if (s == null) return "";
        return s.replace("&","&amp;")
                .replace("\"","&quot;")
                .replace("<","&lt;")
                .replace(">","&gt;")
                .replace("'","&apos;");
    }

    private static boolean isLocalhostIp(String ip) {
        if (ip == null) return false;
        String s = ip.trim();
        return "127.0.0.1".equals(s)
                || "::1".equals(s)
                || "0:0:0:0:0:0:0:1".equalsIgnoreCase(s)
                || s.startsWith("127.");
    }

    private static boolean isPrivateOrLoopback(String ip) {
        if (ip == null) return false;
        String s = ip.trim();
        if (isLocalhostIp(s)) return true;
        if (s.startsWith("10.")) return true;
        if (s.startsWith("192.168.")) return true;
        if (s.startsWith("172.")) {
            // 172.16.0.0 - 172.31.255.255
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

    // Safer client IP: only trust proxy headers if remoteAddr is private/loopback
    private static String getClientIp(jakarta.servlet.http.HttpServletRequest req) {
        String ra = req.getRemoteAddr();
        if (isPrivateOrLoopback(ra)) {
            String xff = req.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                int comma = xff.indexOf(',');
                String first = (comma > 0 ? xff.substring(0, comma) : xff).trim();
                if (!first.isBlank()) return first;
            }
            String xrip = req.getHeader("X-Real-IP");
            if (xrip != null && !xrip.isBlank()) return xrip.trim();
        }
        return ra;
    }

    private static String getCookieValue(jakarta.servlet.http.HttpServletRequest req, String name) {
        Cookie[] cs = req.getCookies();
        if (cs == null) return "";
        for (Cookie c : cs) {
            if (c != null && name.equals(c.getName())) return safe(c.getValue());
        }
        return "";
    }

    private static void setHttpOnlyCookie(jakarta.servlet.http.HttpServletRequest req,
                                         jakarta.servlet.http.HttpServletResponse res,
                                         String name,
                                         String value,
                                         int maxAgeSeconds) {
        String ctx = req.getContextPath();
        if (ctx == null || ctx.isBlank()) ctx = "/";

        boolean secure = req.isSecure();

        // Set-Cookie string so we can add SameSite.
        StringBuilder sb = new StringBuilder();
        sb.append(name).append("=").append(value == null ? "" : value).append("; Path=").append(ctx).append("; Max-Age=").append(maxAgeSeconds);
        sb.append("; HttpOnly");
        if (secure) sb.append("; Secure");
        sb.append("; SameSite=Strict");
        res.addHeader("Set-Cookie", sb.toString());
    }

    private static void deleteCookie(jakarta.servlet.http.HttpServletRequest req,
                                     jakarta.servlet.http.HttpServletResponse res,
                                     String name) {
        String ctx = req.getContextPath();
        if (ctx == null || ctx.isBlank()) ctx = "/";

        boolean secure = req.isSecure();

        StringBuilder sb = new StringBuilder();
        sb.append(name).append("=; Path=").append(ctx).append("; Max-Age=0");
        sb.append("; HttpOnly");
        if (secure) sb.append("; Secure");
        sb.append("; SameSite=Strict");
        res.addHeader("Set-Cookie", sb.toString());
    }

    private static String randomTokenUrlSafe(int bytes) {
        byte[] b = new byte[Math.max(16, bytes)];
        RNG.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static ConcurrentHashMap<String, FailInfo> failMap(ServletContext app) {
        Object o = app.getAttribute(FAILMAP_KEY);
        if (o instanceof ConcurrentHashMap) {
            @SuppressWarnings("unchecked")
            ConcurrentHashMap<String, FailInfo> m = (ConcurrentHashMap<String, FailInfo>) o;
            return m;
        }
        ConcurrentHashMap<String, FailInfo> m = new ConcurrentHashMap<>();
        app.setAttribute(FAILMAP_KEY, m);
        return m;
    }

    private static int noteFailure(ServletContext app, String ip) {
        if (ip == null) ip = "";
        long now = System.currentTimeMillis();
        ConcurrentHashMap<String, FailInfo> m = failMap(app);

        FailInfo f = m.compute(ip, (k, v) -> {
            if (v == null) {
                FailInfo n = new FailInfo();
                n.count = 1;
                n.firstMs = now;
                n.lastMs = now;
                return n;
            }
            if (now - v.firstMs > FAIL_WINDOW_MS) {
                v.count = 1;
                v.firstMs = now;
                v.lastMs = now;
                return v;
            }
            v.count++;
            v.lastMs = now;
            return v;
        });

        return f == null ? 1 : f.count;
    }

    private static void clearFailures(ServletContext app, String ip) {
        if (ip == null) return;
        failMap(app).remove(ip);
    }

    private static String formatRemaining(Instant until) {
        if (until == null) return "";
        long sec = Duration.between(Instant.now(), until).getSeconds();
        if (sec <= 0) return "a moment";
        long min = (sec + 59) / 60;
        if (min <= 1) return "about 1 minute";
        if (min < 60) return "about " + min + " minutes";
        long hr = (min + 59) / 60;
        if (hr <= 1) return "about 1 hour";
        return "about " + hr + " hours";
    }

    // -----------------------------
    // Tenant binding XML (MULTI-SESSION)
    // data/tenants/{uuid}/bindings/tenant_binding_{sessionId}.xml
    // legacy: data/tenants/{uuid}/bindings/tenant_binding.xml
    // -----------------------------
    private static String safeFileToken(String s) {
        s = safe(s).trim();
        if (s.isBlank()) return "unknown";
        // Keep filenames safe + deterministic
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static Path bindingPath(String tenantUuid, String sessionId) {
        String tu  = safeFileToken(tenantUuid);
        String sid = safeFileToken(sessionId);
        return Paths.get("data", "tenants", tu, "bindings",
                "tenant_binding_" + sid + ".xml").toAbsolutePath();
    }

    private static Path legacyBindingPath(String tenantUuid) {
        String tu = safeFileToken(tenantUuid);
        return Paths.get("data", "tenants", tu, "bindings", "tenant_binding.xml").toAbsolutePath();
    }

    private static void deleteQuiet(Path p) {
        if (p == null) return;
        try { Files.deleteIfExists(p); } catch (Exception ignored) {}
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
        b.setEntityResolver((publicId, systemId) -> new org.xml.sax.InputSource(new java.io.StringReader("")));
        return b;
    }

    private static final class Binding {
        final String tenantUuid;
        final String label;
        final String ip;
        final String sessionId;
        final String cookie;
        Binding(String tenantUuid, String label, String ip, String sessionId, String cookie) {
            this.tenantUuid = safe(tenantUuid);
            this.label = safe(label);
            this.ip = safe(ip);
            this.sessionId = safe(sessionId);
            this.cookie = safe(cookie);
        }
    }

    private static Binding readBinding(Path p) {
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
                    String tag = e.getTagName();
                    String val = safe(e.getTextContent()).trim();
                    if ("ip".equalsIgnoreCase(tag)) ip = val;
                    else if ("sessionId".equalsIgnoreCase(tag)) sid = val;
                    else if ("cookie".equalsIgnoreCase(tag)) cb = val;
                }
                return new Binding(tu, lbl, ip, sid, cb);
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void writeBinding(Path p, Binding b) throws IOException {
        if (p == null || b == null) return;
        Files.createDirectories(p.getParent());

        String now = Instant.now().toString();

        String xml =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<tenantBinding created=\"" + xmlAttr(now) + "\" updated=\"" + xmlAttr(now) + "\" " +
            "tenantUuid=\"" + xmlAttr(b.tenantUuid) + "\" label=\"" + xmlAttr(b.label) + "\">\n" +
            "  <ip>" + xmlAttr(b.ip) + "</ip>\n" +
            "  <sessionId>" + xmlAttr(b.sessionId) + "</sessionId>\n" +
            "  <cookie>" + xmlAttr(b.cookie) + "</cookie>\n" +
            "</tenantBinding>\n";

        Files.writeString(p, xml, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static boolean bindingMatches(Binding b, String ip, String sid, String cookie) {
        if (b == null) return false;
        if (ip == null) ip = "";
        if (sid == null) sid = "";
        if (cookie == null) cookie = "";
        return ip.equals(b.ip) && sid.equals(b.sessionId) && cookie.equals(b.cookie);
    }

    // Simple safe "next" handling (only allow app-local absolute paths)
    // NOTE: next is the FINAL destination after user login completes.
    private static String normalizeNext(String next) {
        if (next == null) return "/index.jsp";
        String n = next.trim();
        if (n.isBlank()) return "/index.jsp";
        // block absolute URLs + scheme-relative redirects
        if (n.contains("://") || n.startsWith("//") || n.contains("\r") || n.contains("\n")) return "/index.jsp";
        if (!n.startsWith("/")) return "/index.jsp";
        return n;
    }

    private static String csrfForRender(jakarta.servlet.http.HttpServletRequest req) {
        Object a = req.getAttribute("csrfToken");
        if (a instanceof String) {
            String s = (String) a;
            if (s != null && !s.trim().isEmpty()) return s;
        }
        try {
            jakarta.servlet.http.HttpSession sess = req.getSession(false);
            if (sess != null) {
                Object t = sess.getAttribute(CSRF_SESSION_KEY);
                if (t instanceof String) {
                    String cs = (String) t;
                    if (cs != null && !cs.trim().isEmpty()) return cs;
                }
            }
        } catch (Exception ignored) {}
        return "";
    }
%>

<%
    String ctx = request.getContextPath();
    if (ctx == null) ctx = "";

    // Final destination after successful sign-in
    String next = normalizeNext(request.getParameter("next"));
    String redirectTo = ctx + next;

    String clientIp = getClientIp(request);
    String cookieBind = getCookieValue(request, TENANT_BIND_COOKIE);

    String message = null;
    String error = null;

    tenants store = tenants.defaultStore();
    ip_lists lists = (ip_lists) application.getAttribute("ipLists");
    if (lists == null) {
        lists = ip_lists.defaultStore();
        application.setAttribute("ipLists", lists);
    }

    try { store.ensure(); } catch (Exception ignored) {}
    try { lists.ensure(); } catch (Exception ignored) {}
    users_roles ur = users_roles.defaultStore();

    // Build dropdown list: enabled tenants only
    List<tenants.Tenant> enabledTenants = new ArrayList<>();
    try {
        List<tenants.Tenant> all = store.list();
        for (tenants.Tenant t : all) {
            if (t != null && t.enabled && t.label != null && !t.label.isBlank()) enabledTenants.add(t);
        }
        enabledTenants.sort((a,b) -> a.label.compareToIgnoreCase(b.label));
    } catch (Exception e) {
        error = "Unable to load tenants: " + e.getMessage();
    }

    // Handle login POST
    String selectedLabel = safe(request.getParameter("tenantLabel")).trim();
    String email = safe(request.getParameter("email")).trim();

    // If already authenticated at tenant layer, enforce binding.
    // If full user auth is still valid, go straight to destination.
    String sessUuid  = (String) session.getAttribute(S_TENANT_UUID);
    String sessLabel = (String) session.getAttribute(S_TENANT_LABEL);

    if (sessUuid != null && !sessUuid.isBlank() && sessLabel != null && !sessLabel.isBlank()) {
        String tu = sessUuid.trim();
        Path p = bindingPath(tu, session.getId());
        Binding b = readBinding(p);

        // Legacy fallback: if old single file exists and matches, migrate to session file
        if (b == null) {
            Path legacy = legacyBindingPath(tu);
            Binding lb = readBinding(legacy);
            if (lb != null && bindingMatches(lb, clientIp, session.getId(), cookieBind)) {
                b = lb;
                try { writeBinding(p, lb); } catch (Exception ignored) {}
                // Optional: keep or remove legacy file after successful migration
                // deleteQuiet(legacy);
            }
        }

        if (b != null && bindingMatches(b, clientIp, session.getId(), cookieBind)) {
            String sessUserUuid = safe((String) session.getAttribute(users_roles.S_USER_UUID)).trim();
            String sessUserEmail = safe((String) session.getAttribute(users_roles.S_USER_EMAIL)).trim();
            String sessRoleUuid = safe((String) session.getAttribute(users_roles.S_ROLE_UUID)).trim();
            String userCookieBind = getCookieValue(request, USER_BIND_COOKIE);
            boolean userOk = false;

            if (!sessUserUuid.isBlank() && !sessUserEmail.isBlank() && !sessRoleUuid.isBlank()) {
                try {
                    ur.ensure(tu);
                    userOk = ur.userBindingMatches(tu, sessUserUuid, session.getId(), clientIp, userCookieBind);
                    if (userOk) {
                        users_roles.UserRec u = ur.getUserByUuid(tu, sessUserUuid);
                        users_roles.RoleRec r = ur.getRoleByUuid(tu, sessRoleUuid);
                        userOk = (u != null && u.enabled && r != null && r.enabled);
                    }
                } catch (Exception ignored) {
                    userOk = false;
                }
            }

            if (userOk) {
                response.sendRedirect(redirectTo);
                return;
            }

            // Keep tenant layer, clear stale user layer.
            try { ur.clearSessionAuth(session); } catch (Exception ignored) {}
            if (!sessUserUuid.isBlank()) {
                try { ur.deleteUserBinding(tu, sessUserUuid, session.getId()); } catch (Exception ignored) {}
            }
            deleteCookie(request, response, USER_BIND_COOKIE);
            if (selectedLabel.isBlank()) selectedLabel = safe(sessLabel).trim();
        } else {
            String sessUserUuid = safe((String) session.getAttribute(users_roles.S_USER_UUID)).trim();
            try { ur.clearSessionAuth(session); } catch (Exception ignored) {}
            if (!sessUserUuid.isBlank()) {
                try { ur.deleteUserBinding(tu, sessUserUuid, session.getId()); } catch (Exception ignored) {}
            }
            session.removeAttribute(S_TENANT_UUID);
            session.removeAttribute(S_TENANT_LABEL);
            session.removeAttribute(S_TENANT_SID);
            session.removeAttribute(S_TENANT_IP);
            session.removeAttribute(S_TENANT_CB);
            deleteCookie(request, response, TENANT_BIND_COOKIE);
            deleteCookie(request, response, USER_BIND_COOKIE);

            // remove this session's binding file (if any)
            deleteQuiet(p);
        }
    }

    if ("POST".equalsIgnoreCase(request.getMethod()) && "login".equalsIgnoreCase(request.getParameter("action"))) {

        // If no enabled tenants, stop early.
        if (enabledTenants.isEmpty()) {
            error = "No enabled tenants are available.";
        } else {
            ip_lists.Decision d = ip_lists.Decision.ALLOW;
            try { d = lists.check(clientIp); } catch (Exception ignored) {}

            if (d == ip_lists.Decision.DENY_TEMP_BAN) {
                Instant until = null;
                try {
                    Optional<Instant> ex = lists.getTempBanExpiryIfAny(clientIp);
                    if (ex != null && ex.isPresent()) until = ex.get();
                } catch (Exception ignored) {}
                error = "Too many login attempts. Please try again in " + formatRemaining(until) + ".";
            } else {
                String pwStr = safe(request.getParameter("password"));

                if (selectedLabel.length() < 3) {
                    error = "Please select a tenant.";
                } else if (email.isBlank() || email.length() < 3) {
                    error = "Email is required.";
                } else if (pwStr.isBlank()) {
                    error = "Password is required.";
                } else {
                    tenants.Tenant found = null;

                    // Only allow selecting an ENABLED tenant from our list (prevents tampering)
                    for (tenants.Tenant t : enabledTenants) {
                        if (t.label != null && t.label.equalsIgnoreCase(selectedLabel)) {
                            found = t;
                            break;
                        }
                    }

                    if (found == null) {
                        int c = noteFailure(application, clientIp);
                        error = "Invalid tenant, email, or password.";
                        if (!isLocalhostIp(clientIp)) {
                            try {
                                if (c == 5) lists.banTemporary(clientIp, 10 * 60, "tenant_login: 5 failures");
                                else if (c == 8) lists.banTemporary(clientIp, 30 * 60, "tenant_login: 8 failures");
                                else if (c >= 12) { lists.banTemporary(clientIp, 2 * 60 * 60, "tenant_login: 12+ failures"); clearFailures(application, clientIp); }
                            } catch (Exception ignored) {}
                        }
                    } else {
                        users_roles.AuthResult ar = null;
                        try {
                            ur.ensure(found.uuid);
                            ar = ur.authenticate(found.uuid, email, pwStr.toCharArray());
                        } catch (Exception ignored) {
                            ar = null;
                        }

                        if (ar == null || ar.user == null || ar.role == null) {
                            int c = noteFailure(application, clientIp);
                            error = "Invalid tenant, email, or password.";
                            if (!isLocalhostIp(clientIp)) {
                                try {
                                    if (c == 5) lists.banTemporary(clientIp, 10 * 60, "tenant_login: 5 failures");
                                    else if (c == 8) lists.banTemporary(clientIp, 30 * 60, "tenant_login: 8 failures");
                                    else if (c >= 12) { lists.banTemporary(clientIp, 2 * 60 * 60, "tenant_login: 12+ failures"); clearFailures(application, clientIp); }
                                } catch (Exception ignored) {}
                            }
                        } else {
                            // SUCCESS
                            clearFailures(application, clientIp);

                            // rotate cookie bindings
                            String newTenantCookieBind = randomTokenUrlSafe(32);
                            String newUserCookieBind = randomTokenUrlSafe(32);
                            setHttpOnlyCookie(request, response, TENANT_BIND_COOKIE, newTenantCookieBind, 30 * 24 * 60 * 60); // 30 days
                            setHttpOnlyCookie(request, response, USER_BIND_COOKIE, newUserCookieBind, 30 * 24 * 60 * 60); // 30 days

                            // rotate session to prevent fixation
                            try { session.invalidate(); } catch (Exception ignored) {}
                            HttpSession s2 = request.getSession(true);

                            // store tenant identity in session
                            s2.setAttribute(S_TENANT_UUID, found.uuid);
                            s2.setAttribute(S_TENANT_LABEL, found.label);
                            s2.setAttribute(S_TENANT_SID, s2.getId());
                            s2.setAttribute(S_TENANT_IP, clientIp);
                            s2.setAttribute(S_TENANT_CB, newTenantCookieBind);

                            // bind signed-in user + role + permissions
                            ur.bindToSession(s2, ar);

                            // write binding file (IP + session + cookie) - PER SESSION
                            try {
                                Binding b = new Binding(found.uuid, found.label, clientIp, s2.getId(), newTenantCookieBind);
                                writeBinding(bindingPath(found.uuid, s2.getId()), b);
                            } catch (Exception ignored) {}

                            try {
                                ur.writeUserBinding(
                                        found.uuid,
                                        ar.user.uuid,
                                        ar.user.emailAddress,
                                        ar.role.uuid,
                                        clientIp,
                                        s2.getId(),
                                        newUserCookieBind
                                );
                            } catch (Exception ignored) {}

                            response.sendRedirect(redirectTo);
                            return;
                        }
                    }
                }
            }
        }
    }

    boolean loggedOut = "1".equals(request.getParameter("loggedOut"));
    if (loggedOut && message == null && error == null) message = "You have been logged out.";

    // Make CSRF token render-safe (works on GET and on POST re-render)
    String csrfToken = csrfForRender(request);
%>

<jsp:include page="header.jsp" />

<div class="container main">
  <section class="card narrow">
    <div class="section-head">
      <div>
        <h1>Tenant login</h1>
        <div class="meta">Select your tenant, then sign in with your email and password.</div>
      </div>
    </div>

    <% if (message != null) { %>
      <div class="alert alert-ok"><%= esc(message) %></div>
      <div style="height:12px;"></div>
    <% } %>

    <% if (error != null) { %>
      <div class="alert alert-error"><%= esc(error) %></div>
      <div style="height:12px;"></div>
    <% } %>

    <% if (enabledTenants.isEmpty()) { %>
      <div class="alert alert-warn">
        No enabled tenants are available. Create/enable a tenant in <strong>Tenants</strong> first.
      </div>
      <div style="height:12px;"></div>
    <% } %>

    <form class="form" method="post" action="<%= ctx %>/tenant_login.jsp">
      <input type="hidden" name="action" value="login" />
      <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
      <!-- IMPORTANT: preserve the FINAL destination -->
      <input type="hidden" name="next" value="<%= esc(next) %>" />

      <label>
        <span>Tenant</span>
        <select name="tenantLabel" required <%= enabledTenants.isEmpty() ? "disabled" : "" %>>
          <option value="" <%= selectedLabel.isBlank() ? "selected" : "" %> disabled>Select a tenant…</option>
          <%
            for (tenants.Tenant t : enabledTenants) {
                String lbl = (t.label == null) ? "" : t.label;
                boolean sel = !selectedLabel.isBlank() && lbl.equalsIgnoreCase(selectedLabel);
          %>
            <option value="<%= esc(lbl) %>" <%= sel ? "selected" : "" %>><%= esc(lbl) %></option>
          <%
            }
          %>
        </select>
      </label>

      <label>
        <span>Email</span>
        <input type="text" name="email" value="<%= esc(email) %>" autocomplete="username" required <%= enabledTenants.isEmpty() ? "disabled" : "" %> />
      </label>

      <label>
        <span>Password</span>
        <input type="password" name="password" autocomplete="current-password" required <%= enabledTenants.isEmpty() ? "disabled" : "" %> />
      </label>

      <div class="actions">
        <button class="btn" type="submit" <%= enabledTenants.isEmpty() ? "disabled" : "" %>>Sign in</button>
        <a class="btn btn-ghost" href="<%= ctx %>/index.jsp">Cancel</a>
      </div>

      <div class="help">
        Security: attempts are rate-limited (temporary IP bans). Sessions are bound to IP + cookie + session id.
        Tenant and user authentication complete in this single form. The <code>next</code> destination is preserved.
      </div>
    </form>
  </section>
</div>

<jsp:include page="footer.jsp" />
