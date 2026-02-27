<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>

<%@ page import="java.io.*" %>
<%@ page import="java.nio.file.*" %>
<%@ page import="java.util.LinkedHashMap" %>

<%@ page import="jakarta.servlet.http.Cookie" %>
<%@ page import="jakarta.servlet.http.HttpSession" %>

<%@ page import="net.familylawandprobate.controversies.activity_log" %>
<%@ page import="net.familylawandprobate.controversies.users_roles" %>

<%!
    // -----------------------------
    // Tenant session keys
    // -----------------------------
    private static final String S_TENANT_UUID  = "tenant.uuid";
    private static final String S_TENANT_LABEL = "tenant.label";
    private static final String S_TENANT_SID   = "tenant.sid";
    private static final String S_TENANT_IP    = "tenant.ip";
    private static final String S_TENANT_CB    = "tenant.cookieBind";

    // Tenant cookie binding
    private static final String TENANT_BIND_COOKIE = "TENANT_BIND";

    // User cookie binding (from user_login.jsp)
    private static final String USER_BIND_COOKIE = "USER_BIND";

    private static String safe(String s){ return s == null ? "" : s; }

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

    private static Path tenantBindingPath(String tenantUuid, String sessionId) {
        String sid = safeFileToken(sessionId);
        return Paths.get("data", "tenants", tenantUuid, "bindings",
                "tenant_binding_" + sid + ".xml").toAbsolutePath();
    }

    private static Path legacyTenantBindingPath(String tenantUuid) {
        return Paths.get("data", "tenants", tenantUuid, "bindings", "tenant_binding.xml").toAbsolutePath();
    }

    // -----------------------------
    // User binding XML (per user + per session)
    // data/tenants/{tenantUuid}/bindings/user_binding_{userUuid}_session_{sessionId}.xml
    // -----------------------------
    private static Path userBindingPath(String tenantUuid, String userUuid, String sessionId) {
        String uu = safeFileToken(userUuid);
        String sid = safeFileToken(sessionId);
        return Paths.get("data", "tenants", tenantUuid, "bindings",
                "user_binding_" + uu + "_session_" + sid + ".xml").toAbsolutePath();
    }

    private static void deleteQuiet(Path p) {
        if (p == null) return;
        try { Files.deleteIfExists(p); } catch (Exception ignored) {}
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
%>

<%
    String ctx = request.getContextPath();
    if (ctx == null) ctx = "";

    // read tenant identity + session id BEFORE invalidating
    String tenantUuid  = safe((String) session.getAttribute(S_TENANT_UUID)).trim();
    String tenantLabel = safe((String) session.getAttribute(S_TENANT_LABEL)).trim(); // not required here
    String sessionId   = session.getId();
    String clientIp = safe(request.getRemoteAddr());

    // read user identity BEFORE clearing session
    String userUuid = safe((String) session.getAttribute(users_roles.S_USER_UUID)).trim();
    String userEmail = safe((String) session.getAttribute(users_roles.S_USER_EMAIL)).trim();

    activity_log authLog = activity_log.defaultStore();
    LinkedHashMap<String, String> logDetails = new LinkedHashMap<String, String>();
    logDetails.put("ip", clientIp);
    logDetails.put("tenant_label", tenantLabel);
    logDetails.put("user_email", userEmail);
    logDetails.put("session_id", sessionId);
    if (!tenantUuid.isEmpty() || !userUuid.isEmpty()) {
        authLog.logInfo("auth.logout", tenantUuid, userUuid, "", "", logDetails);
    } else {
        authLog.logSystem("info", "auth.logout.anon", logDetails);
    }

    // Remove THIS SESSION's tenant binding file (recommended)
    if (!tenantUuid.isEmpty()) {
        deleteQuiet(tenantBindingPath(tenantUuid, sessionId));

        // Optional legacy cleanup:
        // deleteQuiet(legacyTenantBindingPath(tenantUuid));
    }

    // Remove THIS SESSION's user binding file (recommended)
    if (!tenantUuid.isEmpty() && !userUuid.isEmpty()) {
        deleteQuiet(userBindingPath(tenantUuid, userUuid, sessionId));
    }

    // Clear cookie bindings
    deleteCookie(request, response, TENANT_BIND_COOKIE);
    deleteCookie(request, response, USER_BIND_COOKIE);

    // Clear session attributes (belt-and-suspenders; session.invalidate will also drop them)
    try {
        session.removeAttribute(S_TENANT_UUID);
        session.removeAttribute(S_TENANT_LABEL);
        session.removeAttribute(S_TENANT_SID);
        session.removeAttribute(S_TENANT_IP);
        session.removeAttribute(S_TENANT_CB);

        users_roles ur = users_roles.defaultStore();
        ur.clearSessionAuth(session);
    } catch (Exception ignored) {}

    // Invalidate session
    try {
        session.invalidate();
    } catch (Exception ignored) {}

    // Redirect back to tenant login (tenant logout implies user logout)
    response.sendRedirect(ctx + "/tenant_login.jsp?loggedOut=1");
%>
