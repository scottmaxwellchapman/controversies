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
<%@ page import="net.familylawandprobate.controversies.users_roles" %>
<%@ page import="net.familylawandprobate.controversies.ip_lists" %>
<%@ page import="net.familylawandprobate.controversies.case_attributes" %>
<%@ page import="net.familylawandprobate.controversies.document_attributes" %>
<%@ page import="net.familylawandprobate.controversies.two_factor_auth" %>
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

    // User convenience attributes
    private static final String S_USER_IP  = "user.ip";
    private static final String S_USER_CB  = "user.cookieBind";

    // Cookie bindings
    private static final String TENANT_BIND_COOKIE = "TENANT_BIND";
    private static final String USER_BIND_COOKIE   = "USER_BIND";

    // CSRF session key (matches filter default)
    private static final String CSRF_SESSION_KEY = "CSRF_TOKEN";

    // Pending two-factor login keys (stored in-session until challenge verifies)
    private static final String S_2FA_CHALLENGE_ID = "auth.2fa.challenge_id";
    private static final String S_2FA_TENANT_UUID  = "auth.2fa.tenant_uuid";
    private static final String S_2FA_TENANT_LABEL = "auth.2fa.tenant_label";
    private static final String S_2FA_USER_UUID    = "auth.2fa.user_uuid";
    private static final String S_2FA_ENGINE       = "auth.2fa.engine";
    private static final String S_2FA_MASKED_DEST  = "auth.2fa.masked_destination";
    private static final String S_2FA_CLIENT_IP    = "auth.2fa.client_ip";
    private static final String S_2FA_NEXT         = "auth.2fa.next";

    // Brute force tracking (memory) + escalation thresholds
    private static final String FAILMAP_KEY = "unifiedLogin.failMap.v1";
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

    // -----------------------------
    // Tenant binding XML (MULTI-SESSION)
    // data/tenants/{uuid}/bindings/tenant_binding_{sessionId}.xml
    // legacy: data/tenants/{uuid}/bindings/tenant_binding.xml
    // -----------------------------
    private static String safeFileToken(String s) {
        s = safe(s).trim();
        if (s.isBlank()) return "unknown";
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static Path bindingPath(String tenantUuid, String sessionId) {
        String tu  = safeFileToken(tenantUuid);
        String sid = safeFileToken(sessionId);
        return Paths.get("data", "tenants", tu, "bindings", "tenant_binding_" + sid + ".xml").toAbsolutePath();
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
    private static String normalizeNext(String next) {
        if (next == null) return "/index.jsp";
        String n = next.trim();
        if (n.isBlank()) return "/index.jsp";
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

    private static void clearPending2fa(HttpSession session) {
        if (session == null) return;
        session.removeAttribute(S_2FA_CHALLENGE_ID);
        session.removeAttribute(S_2FA_TENANT_UUID);
        session.removeAttribute(S_2FA_TENANT_LABEL);
        session.removeAttribute(S_2FA_USER_UUID);
        session.removeAttribute(S_2FA_ENGINE);
        session.removeAttribute(S_2FA_MASKED_DEST);
        session.removeAttribute(S_2FA_CLIENT_IP);
        session.removeAttribute(S_2FA_NEXT);
    }

    private static String engineLabel(String engine) {
        String e = safe(engine).trim().toLowerCase(Locale.ROOT);
        if ("flowroute_sms".equals(e)) return "Flowroute SMS";
        return "Email PIN";
    }
%>

<%
    String ctx = request.getContextPath();
    if (ctx == null) ctx = "";

    String next = normalizeNext(request.getParameter("next"));
    String redirectTo = ctx + next;

    String clientIp = getClientIp(request);
    String tenantCookieBind = getCookieValue(request, TENANT_BIND_COOKIE);
    String userCookieBind = getCookieValue(request, USER_BIND_COOKIE);

    String message = null;
    String error = null;

    tenants tenantStore = tenants.defaultStore();
    users_roles ur = users_roles.defaultStore();
    ip_lists lists = (ip_lists) application.getAttribute("ipLists");
    if (lists == null) {
        lists = ip_lists.defaultStore();
        application.setAttribute("ipLists", lists);
    }

    try { tenantStore.ensure(); } catch (Exception ignored) {}
    try { lists.ensure(); } catch (Exception ignored) {}

    // Build dropdown list: enabled tenants only
    List<tenants.Tenant> enabledTenants = new ArrayList<>();
    try {
        List<tenants.Tenant> all = tenantStore.list();
        for (tenants.Tenant t : all) {
            if (t != null
                    && t.enabled
                    && t.uuid != null && !t.uuid.isBlank()
                    && t.label != null && !t.label.isBlank()) {
                enabledTenants.add(t);
            }
        }
        enabledTenants.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
    } catch (Exception e) {
        error = "Unable to load tenants: " + e.getMessage();
    }

    String selectedTenantUuid = safe(request.getParameter("tenantUuid")).trim();
    if (selectedTenantUuid.isBlank() && enabledTenants.size() == 1) {
        selectedTenantUuid = safe(enabledTenants.get(0).uuid).trim();
    }
    String email = safe(request.getParameter("email")).trim();

    // If already fully authenticated and bindings are valid, continue to destination.
    String sessTenantUuid  = safe((String) session.getAttribute(S_TENANT_UUID)).trim();
    String sessTenantLabel = safe((String) session.getAttribute(S_TENANT_LABEL)).trim();
    String sessUserUuid    = safe((String) session.getAttribute(users_roles.S_USER_UUID)).trim();
    String sessRoleUuid    = safe((String) session.getAttribute(users_roles.S_ROLE_UUID)).trim();

    boolean fullSessionLooksPresent = !sessTenantUuid.isBlank()
            && !sessTenantLabel.isBlank()
            && !sessUserUuid.isBlank()
            && !sessRoleUuid.isBlank();

    if (fullSessionLooksPresent) {
        Path p = bindingPath(sessTenantUuid, session.getId());
        Binding b = readBinding(p);

        if (b == null) {
            Path legacy = legacyBindingPath(sessTenantUuid);
            Binding lb = readBinding(legacy);
            if (lb != null && bindingMatches(lb, clientIp, session.getId(), tenantCookieBind)) {
                b = lb;
                try { writeBinding(p, lb); } catch (Exception ignored) {}
            }
        }

        boolean tenantOk = (b != null && bindingMatches(b, clientIp, session.getId(), tenantCookieBind));

        boolean userOk = false;
        if (tenantOk) {
            try {
                userOk = ur.userBindingMatches(sessTenantUuid, sessUserUuid, session.getId(), clientIp, userCookieBind);
            } catch (Exception ignored) {
                userOk = false;
            }
        }

        if (tenantOk && userOk) {
            try {
                users_roles.UserRec u = ur.getUserByUuid(sessTenantUuid, sessUserUuid);
                users_roles.RoleRec r = ur.getRoleByUuid(sessTenantUuid, sessRoleUuid);
                if (u != null && u.enabled && r != null && r.enabled) {
                    response.sendRedirect(redirectTo);
                    return;
                }
            } catch (Exception ignored) {}
        }
    }

    two_factor_auth tfa = two_factor_auth.defaultStore();
    String pendingChallengeId = safe((String) session.getAttribute(S_2FA_CHALLENGE_ID)).trim();
    String pendingTenantUuid = safe((String) session.getAttribute(S_2FA_TENANT_UUID)).trim();
    String pendingTenantLabel = safe((String) session.getAttribute(S_2FA_TENANT_LABEL)).trim();
    String pendingUserUuid = safe((String) session.getAttribute(S_2FA_USER_UUID)).trim();
    String pendingEngine = safe((String) session.getAttribute(S_2FA_ENGINE)).trim();
    String pendingMaskedDestination = safe((String) session.getAttribute(S_2FA_MASKED_DEST)).trim();
    String pendingClientIp = safe((String) session.getAttribute(S_2FA_CLIENT_IP)).trim();
    String pendingNext = normalizeNext((String) session.getAttribute(S_2FA_NEXT));
    boolean pending2fa = !pendingChallengeId.isBlank()
            && !pendingTenantUuid.isBlank()
            && !pendingUserUuid.isBlank()
            && !pendingEngine.isBlank();

    // Clear stale/partial full-auth state before presenting login.
    boolean hasAnyAuthState = !safe((String) session.getAttribute(S_TENANT_UUID)).trim().isBlank()
            || !safe((String) session.getAttribute(S_TENANT_LABEL)).trim().isBlank()
            || !safe((String) session.getAttribute(users_roles.S_USER_UUID)).trim().isBlank()
            || !safe((String) session.getAttribute(users_roles.S_ROLE_UUID)).trim().isBlank()
            || !tenantCookieBind.isBlank()
            || !userCookieBind.isBlank();

    if (hasAnyAuthState) {
        String clearTenantUuid = safe((String) session.getAttribute(S_TENANT_UUID)).trim();
        String clearUserUuid = safe((String) session.getAttribute(users_roles.S_USER_UUID)).trim();
        String clearSessionId = safe(session.getId()).trim();

        session.removeAttribute(S_TENANT_UUID);
        session.removeAttribute(S_TENANT_LABEL);
        session.removeAttribute(S_TENANT_SID);
        session.removeAttribute(S_TENANT_IP);
        session.removeAttribute(S_TENANT_CB);
        session.removeAttribute(S_USER_IP);
        session.removeAttribute(S_USER_CB);

        try { ur.clearSessionAuth(session); } catch (Exception ignored) {}
        try { deleteCookie(request, response, TENANT_BIND_COOKIE); } catch (Exception ignored) {}
        try { deleteCookie(request, response, USER_BIND_COOKIE); } catch (Exception ignored) {}

        if (!clearTenantUuid.isBlank()) {
            try { deleteQuiet(bindingPath(clearTenantUuid, clearSessionId)); } catch (Exception ignored) {}
        }
        if (!clearTenantUuid.isBlank() && !clearUserUuid.isBlank() && !clearSessionId.isBlank()) {
            try { ur.deleteUserBinding(clearTenantUuid, clearUserUuid, clearSessionId); } catch (Exception ignored) {}
        }
        clearPending2fa(session);
        pending2fa = false;
        pendingChallengeId = "";
        pendingTenantUuid = "";
        pendingTenantLabel = "";
        pendingUserUuid = "";
        pendingEngine = "";
        pendingMaskedDestination = "";
        pendingClientIp = "";
        pendingNext = "/index.jsp";
    }

    String action = safe(request.getParameter("action")).trim();

    // Handle pending two-factor actions.
    if ("POST".equalsIgnoreCase(request.getMethod()) && "cancel_2fa".equalsIgnoreCase(action)) {
        if (pending2fa) {
            try { tfa.invalidateChallenge(pendingTenantUuid, pendingChallengeId); } catch (Exception ignored) {}
        }
        clearPending2fa(session);
        pending2fa = false;
        message = "Sign-in verification was canceled.";
    }

    if ("POST".equalsIgnoreCase(request.getMethod()) && "resend_2fa".equalsIgnoreCase(action)) {
        if (!pending2fa) {
            error = "No pending verification challenge was found. Please sign in again.";
        } else {
            try {
                users_roles.UserRec pendingUser = ur.getUserByUuid(pendingTenantUuid, pendingUserUuid);
                if (pendingUser == null || !pendingUser.enabled) {
                    clearPending2fa(session);
                    pending2fa = false;
                    error = "User session changed. Please sign in again.";
                } else {
                    two_factor_auth.ChallengeStartResult resend = tfa.startChallenge(pendingTenantUuid, pendingUser, pendingUser.uuid, pendingClientIp.isBlank() ? clientIp : pendingClientIp);
                    if (!resend.success || resend.challengeId.isBlank()) {
                        error = resend.issue.isBlank() ? "Unable to resend verification code." : resend.issue;
                    } else {
                        session.setAttribute(S_2FA_CHALLENGE_ID, resend.challengeId);
                        session.setAttribute(S_2FA_ENGINE, resend.engine);
                        session.setAttribute(S_2FA_MASKED_DEST, resend.maskedDestination);
                        pendingChallengeId = resend.challengeId;
                        pendingEngine = resend.engine;
                        pendingMaskedDestination = resend.maskedDestination;
                        message = "A new verification code was sent via " + engineLabel(resend.engine) + ".";
                    }
                }
            } catch (Exception ex) {
                error = "Unable to resend verification code.";
            }
        }
    }

    if ("POST".equalsIgnoreCase(request.getMethod()) && "verify_2fa".equalsIgnoreCase(action)) {
        if (!pending2fa) {
            error = "No pending verification challenge was found. Please sign in again.";
        } else {
            String code = safe(request.getParameter("verificationCode"));
            two_factor_auth.VerifyResult vr = null;
            try {
                vr = tfa.verifyChallenge(pendingTenantUuid, pendingUserUuid, pendingChallengeId, code, pendingClientIp.isBlank() ? clientIp : pendingClientIp);
            } catch (Exception ex) {
                vr = new two_factor_auth.VerifyResult(false, false, 0, "Unable to verify the code.");
            }
            if (vr != null && vr.success) {
                try {
                    users_roles.UserRec u = ur.getUserByUuid(pendingTenantUuid, pendingUserUuid);
                    if (u == null || !u.enabled) {
                        clearPending2fa(session);
                        pending2fa = false;
                        error = "User is no longer active. Please contact your tenant administrator.";
                    } else {
                        users_roles.RoleRec r = ur.getRoleByUuid(pendingTenantUuid, u.roleUuid);
                        if (r == null || !r.enabled) {
                            clearPending2fa(session);
                            pending2fa = false;
                            error = "User role is unavailable. Please contact your tenant administrator.";
                        } else {
                            users_roles.AuthResult finalAr = new users_roles.AuthResult(u, r);

                            String tenantLabelForSession = pendingTenantLabel;
                            if (tenantLabelForSession.isBlank()) {
                                try {
                                    tenants.Tenant t = tenantStore.getByUuid(pendingTenantUuid);
                                    if (t != null) tenantLabelForSession = safe(t.label).trim();
                                } catch (Exception ignored) {}
                            }
                            if (tenantLabelForSession.isBlank()) tenantLabelForSession = pendingTenantUuid;

                            String finalRedirect = ctx + pendingNext;
                            String finalClientIp = pendingClientIp.isBlank() ? clientIp : pendingClientIp;

                            String newTenantBind = randomTokenUrlSafe(32);
                            String newUserBind = randomTokenUrlSafe(32);
                            setHttpOnlyCookie(request, response, TENANT_BIND_COOKIE, newTenantBind, 30 * 24 * 60 * 60);
                            setHttpOnlyCookie(request, response, USER_BIND_COOKIE, newUserBind, 30 * 24 * 60 * 60);

                            try { session.invalidate(); } catch (Exception ignored) {}
                            HttpSession s2 = request.getSession(true);

                            s2.setAttribute(S_TENANT_UUID, pendingTenantUuid);
                            s2.setAttribute(S_TENANT_LABEL, tenantLabelForSession);
                            s2.setAttribute(S_TENANT_SID, s2.getId());
                            s2.setAttribute(S_TENANT_IP, finalClientIp);
                            s2.setAttribute(S_TENANT_CB, newTenantBind);

                            ur.bindToSession(s2, finalAr);
                            s2.setAttribute(S_USER_IP, finalClientIp);
                            s2.setAttribute(S_USER_CB, newUserBind);

                            try {
                                Binding b = new Binding(pendingTenantUuid, tenantLabelForSession, finalClientIp, s2.getId(), newTenantBind);
                                writeBinding(bindingPath(pendingTenantUuid, s2.getId()), b);
                            } catch (Exception ignored) {}

                            try {
                                ur.writeUserBinding(
                                        pendingTenantUuid,
                                        finalAr.user.uuid,
                                        finalAr.user.emailAddress,
                                        finalAr.role.uuid,
                                        finalClientIp,
                                        s2.getId(),
                                        newUserBind
                                );
                            } catch (Exception ignored) {}

                            response.sendRedirect(finalRedirect);
                            return;
                        }
                    }
                } catch (Exception ex) {
                    error = "Unable to finalize sign-in.";
                }
            } else {
                if (vr != null && vr.expired) {
                    clearPending2fa(session);
                    pending2fa = false;
                }
                String baseError = (vr == null || vr.issue.isBlank()) ? "Verification code is invalid." : vr.issue;
                if (vr != null && !vr.success && vr.remainingAttempts > 0 && !vr.expired) {
                    error = baseError + " " + vr.remainingAttempts + " attempt(s) remaining.";
                } else {
                    error = baseError;
                }
            }
        }
    }

    // Handle login POST (tenant + email + password in one form)
    if ("POST".equalsIgnoreCase(request.getMethod()) && "login".equalsIgnoreCase(action)) {

        clearPending2fa(session);
        pending2fa = false;

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
            } else if (selectedTenantUuid.isBlank()) {
                error = "Please select a tenant.";
            } else if (email.isBlank() || email.length() < 3) {
                error = "Email is required.";
            } else {
                String pwStr = safe(request.getParameter("password"));
                if (pwStr.isBlank()) {
                    error = "Password is required.";
                } else {
                    tenants.Tenant found = null;
                    for (tenants.Tenant t : enabledTenants) {
                        if (t != null && selectedTenantUuid.equals(t.uuid)) {
                            found = t;
                            break;
                        }
                    }

                    if (found == null) {
                        int c = noteFailure(application, clientIp);
                        error = "Invalid tenant, email, or password.";
                        if (!isLocalhostIp(clientIp)) {
                            try {
                                if (c == 5) lists.banTemporary(clientIp, 10 * 60, "login: 5 failures");
                                else if (c == 8) lists.banTemporary(clientIp, 30 * 60, "login: 8 failures");
                                else if (c >= 12) {
                                    lists.banTemporary(clientIp, 2 * 60 * 60, "login: 12+ failures");
                                    clearFailures(application, clientIp);
                                }
                            } catch (Exception ignored) {}
                        }
                    } else {
                        try { ur.ensure(found.uuid); } catch (Exception ignored) {}
                        try { case_attributes.defaultStore().ensure(found.uuid); } catch (Exception ex) { application.log("[tenant_login] unable to seed case attributes for tenant " + found.uuid, ex); }
                        try { document_attributes.defaultStore().ensure(found.uuid); } catch (Exception ex) { application.log("[tenant_login] unable to seed document attributes for tenant " + found.uuid, ex); }

                        users_roles.AuthResult ar = null;
                        char[] pw = pwStr.toCharArray();
                        try {
                            ar = ur.authenticate(found.uuid, email, pw);
                        } catch (Exception ignored) {
                            ar = null;
                        }

                        if (ar == null || ar.user == null || ar.role == null) {
                            int c = noteFailure(application, clientIp);
                            error = "Invalid tenant, email, or password.";
                            if (!isLocalhostIp(clientIp)) {
                                try {
                                    if (c == 5) lists.banTemporary(clientIp, 10 * 60, "login: 5 failures");
                                    else if (c == 8) lists.banTemporary(clientIp, 30 * 60, "login: 8 failures");
                                    else if (c >= 12) {
                                        lists.banTemporary(clientIp, 2 * 60 * 60, "login: 12+ failures");
                                        clearFailures(application, clientIp);
                                    }
                                } catch (Exception ignored) {}
                            }
                        } else {
                            clearFailures(application, clientIp);

                            two_factor_auth.Requirement req = tfa.resolveRequirement(found.uuid, ar.user);
                            if (req.required) {
                                if (!req.issue.isBlank()) {
                                    error = req.issue;
                                } else {
                                    two_factor_auth.ChallengeStartResult sr;
                                    try {
                                        sr = tfa.startChallenge(found.uuid, ar.user, ar.user.uuid, clientIp);
                                    } catch (Exception ex) {
                                        sr = new two_factor_auth.ChallengeStartResult(false, true, "", req.engine, "", "Unable to start two-factor verification.");
                                    }
                                    if (!sr.success || sr.challengeId.isBlank()) {
                                        error = sr.issue.isBlank() ? "Unable to start two-factor verification." : sr.issue;
                                    } else {
                                        session.setAttribute(S_2FA_CHALLENGE_ID, sr.challengeId);
                                        session.setAttribute(S_2FA_TENANT_UUID, found.uuid);
                                        session.setAttribute(S_2FA_TENANT_LABEL, found.label);
                                        session.setAttribute(S_2FA_USER_UUID, ar.user.uuid);
                                        session.setAttribute(S_2FA_ENGINE, sr.engine);
                                        session.setAttribute(S_2FA_MASKED_DEST, sr.maskedDestination);
                                        session.setAttribute(S_2FA_CLIENT_IP, clientIp);
                                        session.setAttribute(S_2FA_NEXT, next);

                                        pending2fa = true;
                                        pendingChallengeId = sr.challengeId;
                                        pendingTenantUuid = found.uuid;
                                        pendingTenantLabel = found.label;
                                        pendingUserUuid = ar.user.uuid;
                                        pendingEngine = sr.engine;
                                        pendingMaskedDestination = sr.maskedDestination;
                                        pendingClientIp = clientIp;
                                        pendingNext = next;

                                        selectedTenantUuid = found.uuid;
                                        email = ar.user.emailAddress;
                                        message = "Verification code sent via " + engineLabel(sr.engine) + " to " + sr.maskedDestination + ".";
                                    }
                                }
                            } else {
                                String newTenantBind = randomTokenUrlSafe(32);
                                String newUserBind = randomTokenUrlSafe(32);

                                setHttpOnlyCookie(request, response, TENANT_BIND_COOKIE, newTenantBind, 30 * 24 * 60 * 60);
                                setHttpOnlyCookie(request, response, USER_BIND_COOKIE, newUserBind, 30 * 24 * 60 * 60);

                                try { session.invalidate(); } catch (Exception ignored) {}
                                HttpSession s2 = request.getSession(true);

                                s2.setAttribute(S_TENANT_UUID, found.uuid);
                                s2.setAttribute(S_TENANT_LABEL, found.label);
                                s2.setAttribute(S_TENANT_SID, s2.getId());
                                s2.setAttribute(S_TENANT_IP, clientIp);
                                s2.setAttribute(S_TENANT_CB, newTenantBind);

                                ur.bindToSession(s2, ar);
                                s2.setAttribute(S_USER_IP, clientIp);
                                s2.setAttribute(S_USER_CB, newUserBind);

                                try {
                                    Binding b = new Binding(found.uuid, found.label, clientIp, s2.getId(), newTenantBind);
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
                                            newUserBind
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
    }

    if (pending2fa) {
        selectedTenantUuid = pendingTenantUuid;
        try {
            users_roles.UserRec pendingUser = ur.getUserByUuid(pendingTenantUuid, pendingUserUuid);
            if (pendingUser != null) {
                email = pendingUser.emailAddress;
            }
        } catch (Exception ignored) {}
    }

    boolean loggedOut = "1".equals(request.getParameter("loggedOut"));
    if (loggedOut && message == null && error == null) message = "You have been logged out.";

    String csrfToken = csrfForRender(request);
%>

<jsp:include page="header.jsp" />

<div class="container main">
  <section class="card narrow">
    <div class="section-head">
      <div>
        <h1>Sign in</h1>
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
        No enabled tenants are available. Create or enable a tenant in <strong>Tenants</strong> first.
      </div>
      <div style="height:12px;"></div>
    <% } %>

    <% if (pending2fa) { %>
      <form class="form" method="post" action="<%= ctx %>/tenant_login.jsp">
        <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
        <input type="hidden" name="next" value="<%= esc(pendingNext) %>" />

        <div class="alert alert-ok" style="margin-bottom:12px;">
          Enter the 6-digit code sent via <strong><%= esc(engineLabel(pendingEngine)) %></strong>
          to <strong><%= esc(pendingMaskedDestination) %></strong>.
        </div>

        <label>
          <span>Verification Code</span>
          <input type="text" name="verificationCode" inputmode="numeric" pattern="[0-9]{6}" maxlength="6" autocomplete="one-time-code" required />
        </label>

        <div class="actions">
          <button class="btn" type="submit" name="action" value="verify_2fa">Verify</button>
          <button class="btn btn-ghost" type="submit" name="action" value="resend_2fa">Resend Code</button>
          <button class="btn btn-ghost" type="submit" name="action" value="cancel_2fa">Start Over</button>
        </div>

        <div class="help">
          Security: codes expire after 10 minutes and are attempt-limited.
        </div>
      </form>
    <% } else { %>
      <form class="form" method="post" action="<%= ctx %>/tenant_login.jsp">
        <input type="hidden" name="action" value="login" />
        <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
        <input type="hidden" name="next" value="<%= esc(next) %>" />

        <label>
          <span>Tenant</span>
          <select name="tenantUuid" required <%= enabledTenants.isEmpty() ? "disabled" : "" %>>
            <% if (enabledTenants.size() != 1) { %>
              <option value="" <%= selectedTenantUuid.isBlank() ? "selected" : "" %> disabled>Select a tenant…</option>
            <% } %>
            <%
              for (tenants.Tenant t : enabledTenants) {
                  String tUuid = safe(t.uuid).trim();
                  String lbl = safe(t.label).trim();
                  boolean sel = !selectedTenantUuid.isBlank() && tUuid.equals(selectedTenantUuid);
            %>
              <option value="<%= esc(tUuid) %>" <%= sel ? "selected" : "" %>><%= esc(lbl) %></option>
            <%
              }
            %>
          </select>
        </label>

        <label>
          <span>Email</span>
          <input type="text" name="email" value="<%= esc(email) %>" autocomplete="username" required />
        </label>

        <label>
          <span>Password</span>
          <input type="password" name="password" autocomplete="current-password" required />
        </label>

        <div class="actions">
          <button class="btn" type="submit" <%= enabledTenants.isEmpty() ? "disabled" : "" %>>Sign in</button>
          <a class="btn btn-ghost" href="<%= ctx %>/index.jsp">Cancel</a>
        </div>

        <div style="margin-top:8px;">
          <a href="<%= ctx %>/forgot_password.jsp">Forgot password?</a>
        </div>

        <div class="help">
          Security: attempts are rate-limited (temporary IP bans). Sessions are bound to IP + cookie + session id.
        </div>
      </form>
    <% } %>
  </section>
</div>

<jsp:include page="footer.jsp" />
