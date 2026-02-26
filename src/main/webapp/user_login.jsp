<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>

<%@ page import="java.net.URLEncoder" %>
<%@ page import="java.nio.charset.StandardCharsets" %>
<%@ page import="java.security.SecureRandom" %>
<%@ page import="java.time.*" %>
<%@ page import="java.util.*" %>
<%@ page import="java.util.concurrent.ConcurrentHashMap" %>

<%@ page import="jakarta.servlet.ServletContext" %>
<%@ page import="jakarta.servlet.http.Cookie" %>
<%@ page import="jakarta.servlet.http.HttpSession" %>

<%@ page import="net.familylawandprobate.controversies.users_roles" %>
<%@ page import="net.familylawandprobate.controversies.ip_lists" %>

<%!
    // -----------------------------
    // Tenant session keys (must already be authenticated at tenant level)
    // -----------------------------
    private static final String S_TENANT_UUID  = "tenant.uuid";
    private static final String S_TENANT_LABEL = "tenant.label";

    // -----------------------------
    // User session helpers (optional convenience)
    // users_roles.bindToSession sets:
    //  user.uuid, user.email, user.role.uuid, user.role.label, user.perms.map + perm.*
    // -----------------------------
    private static final String S_USER_IP  = "user.ip";
    private static final String S_USER_CB  = "user.cookieBind";

    // Cookie binding for USER auth step
    private static final String USER_BIND_COOKIE = "USER_BIND";

    // CSRF session key (matches your filter default)
    private static final String CSRF_SESSION_KEY = "CSRF_TOKEN";

    // Brute force tracking (memory) + escalation thresholds
    private static final String FAILMAP_KEY = "userLogin.failMap.v1";
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
        sb.append(name).append("=").append(value == null ? "" : value)
          .append("; Path=").append(ctx)
          .append("; Max-Age=").append(maxAgeSeconds);
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

    // Simple safe "next" handling (only allow app-local absolute paths)
    private static String normalizeNext(String next) {
        if (next == null) return "/index.jsp";
        String n = next.trim();
        if (n.isBlank()) return "/index.jsp";
        if (n.contains("://") || n.contains("\r") || n.contains("\n")) return "/index.jsp";
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

    String next = normalizeNext(request.getParameter("next"));
    String redirectTo = ctx + next;

    String clientIp = getClientIp(request);

    // Must already have tenant login
    String tenantUuid  = safe((String) session.getAttribute(S_TENANT_UUID)).trim();
    String tenantLabel = safe((String) session.getAttribute(S_TENANT_LABEL)).trim();

    if (tenantUuid.isBlank() || tenantLabel.isBlank()) {
        // No tenant session: force tenant login first
        String go = ctx + "/tenant_login.jsp?next=" + URLEncoder.encode(next, StandardCharsets.UTF_8.name());
        response.sendRedirect(go);
        return;
    }

    // IP lists store (shared with filter)
    ip_lists lists = (ip_lists) application.getAttribute("ipLists");
    if (lists == null) {
        lists = ip_lists.defaultStore();
        application.setAttribute("ipLists", lists);
    }
    try { lists.ensure(); } catch (Exception ignored) {}

    String message = null;
    String error = null;

    // If already user-authenticated, verify user binding; if ok -> redirect; else clear user auth + cookie
    String userUuid = safe((String) session.getAttribute(users_roles.S_USER_UUID)).trim();
    String userBindCookie = getCookieValue(request, USER_BIND_COOKIE);

    users_roles ur = users_roles.defaultStore();
    try { ur.ensure(tenantUuid); } catch (Exception ignored) {}

    if (!userUuid.isBlank()) {
        boolean ok = false;
        try {
            ok = ur.userBindingMatches(tenantUuid, userUuid, session.getId(), clientIp, userBindCookie);
        } catch (Exception ignored) { ok = false; }

        if (ok) {
            response.sendRedirect(redirectTo);
            return;
        } else {
            try { ur.clearSessionAuth(session); } catch (Exception ignored) {}
            session.removeAttribute(S_USER_IP);
            session.removeAttribute(S_USER_CB);
            deleteCookie(request, response, USER_BIND_COOKIE);
        }
    }

    // Handle login POST
    String email = safe(request.getParameter("email")).trim();
    if ("POST".equalsIgnoreCase(request.getMethod()) && "login".equalsIgnoreCase(request.getParameter("action"))) {

        // If currently temp-banned, show message (filter will still allow because ban might be applied after failures)
        ip_lists.Decision d = ip_lists.Decision.ALLOW;
        try { d = lists.check(clientIp); } catch (Exception ignored) { d = ip_lists.Decision.ALLOW; }

        if (d == ip_lists.Decision.DENY_TEMP_BAN) {
            Instant until = null;
            try {
                Optional<Instant> ex = lists.getTempBanExpiryIfAny(clientIp);
                if (ex != null && ex.isPresent()) until = ex.get();
            } catch (Exception ignored) {}
            error = "Too many login attempts. Please try again in " + formatRemaining(until) + ".";
        } else {
            String pwStr = safe(request.getParameter("password"));
            if (email.isBlank() || email.length() < 3) {
                error = "Email is required.";
            } else if (pwStr.isBlank()) {
                error = "Password is required.";
            } else {
                char[] pw = pwStr.toCharArray();
                users_roles.AuthResult ar = null;

                try {
                    ar = ur.authenticate(tenantUuid, email, pw);
                } catch (Exception ex) {
                    ar = null;
                }

                if (ar == null || ar.user == null || ar.role == null) {
                    int c = noteFailure(application, clientIp);
                    error = "Invalid email or password.";

                    // Escalate via temporary IP bans (never ban localhost)
                    if (!isLocalhostIp(clientIp)) {
                        try {
                            if (c == 5) lists.banTemporary(clientIp, 10 * 60, "user_login: 5 failures");
                            else if (c == 8) lists.banTemporary(clientIp, 30 * 60, "user_login: 8 failures");
                            else if (c >= 12) { lists.banTemporary(clientIp, 2 * 60 * 60, "user_login: 12+ failures"); clearFailures(application, clientIp); }
                        } catch (Exception ignored) {}
                    }
                } else {
                    // SUCCESS
                    clearFailures(application, clientIp);

                    // Rotate user binding cookie
                    String newUserBind = randomTokenUrlSafe(32);
                    setHttpOnlyCookie(request, response, USER_BIND_COOKIE, newUserBind, 30 * 24 * 60 * 60); // 30 days

                    // Load user + role + permissions into session
                    ur.bindToSession(session, ar);

                    // Store convenience attributes
                    session.setAttribute(S_USER_IP, clientIp);
                    session.setAttribute(S_USER_CB, newUserBind);

                    // Write per-user-per-session binding file
                    try {
                        ur.writeUserBinding(
                                tenantUuid,
                                ar.user.uuid,
                                ar.user.emailAddress,
                                ar.role.uuid,
                                clientIp,
                                session.getId(),
                                newUserBind
                        );
                    } catch (Exception ignored) {}

                    response.sendRedirect(redirectTo);
                    return;
                }
            }
        }
    }

    boolean loggedOut = "1".equals(request.getParameter("loggedOut"));
    if (loggedOut && message == null && error == null) message = "You have been logged out.";

    // CSRF token render-safe (works on GET and on POST re-render; filter enforces)
    String csrfToken = csrfForRender(request);
%>

<jsp:include page="header.jsp" />

<div class="container main">
  <section class="card narrow">
    <div class="section-head">
      <div>
        <h1>User login</h1>
        <div class="meta">
          Tenant: <strong><%= esc(tenantLabel) %></strong> — sign in with your email and password.
        </div>
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

    <form class="form" method="post" action="<%= ctx %>/user_login.jsp">
      <input type="hidden" name="action" value="login" />
      <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
      <input type="hidden" name="next" value="<%= esc(next) %>" />

      <label>
        <span>Email</span>
        <input type="text" name="email" value="<%= esc(email) %>" autocomplete="username" required />
      </label>

      <label>
        <span>Password</span>
        <input type="password" name="password" autocomplete="current-password" required />
      </label>

      <div class="actions">
        <button class="btn" type="submit">Sign in</button>
        <a class="btn btn-ghost" href="<%= ctx %>/index.jsp">Cancel</a>
      </div>

      <div class="help">
        Security: attempts are rate-limited (temporary IP bans). User sessions are bound to IP + cookie + session id.
        Your permissions are loaded into the session on successful login.
      </div>
    </form>
  </section>
</div>

<jsp:include page="footer.jsp" />
