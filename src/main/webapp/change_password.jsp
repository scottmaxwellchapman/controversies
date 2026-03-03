<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>

<%@ page import="java.util.ArrayList" %>
<%@ page import="java.util.List" %>

<%@ page import="net.familylawandprobate.controversies.tenant_settings" %>
<%@ page import="net.familylawandprobate.controversies.users_roles" %>
<%@ page import="net.familylawandprobate.controversies.users_roles.UserRec" %>
<%@ include file="security.jspf" %>

<%
  if (!require_login()) return;
%>

<%!
  private static final String S_TENANT_UUID = "tenant.uuid";
  private static final String CSRF_SESSION_KEY = "CSRF_TOKEN";

  private static String cpSafe(String s) { return s == null ? "" : s; }

  private static String cpEsc(String s) {
    if (s == null) return "";
    return s.replace("&","&amp;")
            .replace("<","&lt;")
            .replace(">","&gt;")
            .replace("\"","&quot;")
            .replace("'","&#39;");
  }

  private static boolean cpChecked(String s) {
    String v = cpSafe(s).trim().toLowerCase(java.util.Locale.ROOT);
    return "1".equals(v) || "true".equals(v) || "on".equals(v) || "yes".equals(v);
  }

  private static String cpCsrfForRender(jakarta.servlet.http.HttpServletRequest req) {
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

  String tenantUuid = cpSafe((String)session.getAttribute(S_TENANT_UUID)).trim();
  String sessionUserUuid = cpSafe((String)session.getAttribute(users_roles.S_USER_UUID)).trim();
  String sessionUserEmail = cpSafe((String)session.getAttribute(users_roles.S_USER_EMAIL)).trim();
  boolean tenantAdmin = users_roles.hasPermissionTrue(session, "tenant_admin");

  if (tenantUuid.isBlank() || sessionUserUuid.isBlank() || sessionUserEmail.isBlank()) {
    response.sendRedirect(ctx + "/tenant_login.jsp");
    return;
  }

  String message = null;
  String error = null;
  String action = cpSafe(request.getParameter("action")).trim();

  String selectedAdminUserUuid = cpSafe(request.getParameter("adminUserUuid")).trim();
  String currentPassword = cpSafe(request.getParameter("currentPassword"));
  String newPassword = cpSafe(request.getParameter("newPassword"));
  String confirmPassword = cpSafe(request.getParameter("confirmPassword"));
  String adminNewPassword = cpSafe(request.getParameter("adminNewPassword"));
  String adminConfirmPassword = cpSafe(request.getParameter("adminConfirmPassword"));
  boolean adminBypassPasswordPolicy = cpChecked(request.getParameter("adminBypassPasswordPolicy"));

  users_roles store = users_roles.defaultStore();
  tenant_settings settingsStore = tenant_settings.defaultStore();
  tenant_settings.PasswordPolicy policy = settingsStore.readPasswordPolicy(tenantUuid);

  try { store.ensure(tenantUuid); } catch (Exception ignored) {}

  List<UserRec> allUsers = new ArrayList<UserRec>();
  if (tenantAdmin) {
    try {
      allUsers = new ArrayList<UserRec>(store.listUsers(tenantUuid));
      allUsers.sort((a, b) -> cpSafe(a.emailAddress).compareToIgnoreCase(cpSafe(b.emailAddress)));
      if (selectedAdminUserUuid.isBlank() && !allUsers.isEmpty()) {
        selectedAdminUserUuid = cpSafe(allUsers.get(0).uuid).trim();
      }
    } catch (Exception ex) {
      error = "Unable to load users for tenant.";
      application.log("[change_password] unable to load users", ex);
    }
  }

  if ("POST".equalsIgnoreCase(request.getMethod())) {
    if ("self_change_password".equalsIgnoreCase(action)) {
      if (currentPassword.isBlank()) {
        error = "Current password is required.";
      } else if (newPassword.isBlank()) {
        error = "New password is required.";
      } else if (!newPassword.equals(confirmPassword)) {
        error = "Password confirmation does not match.";
      } else {
        try {
          users_roles.AuthResult ar = store.authenticate(tenantUuid, sessionUserEmail, currentPassword.toCharArray());
          if (ar == null || ar.user == null || !sessionUserUuid.equals(cpSafe(ar.user.uuid).trim())) {
            error = "Current password is incorrect.";
          } else {
            boolean changed = store.updateUserPassword(tenantUuid, sessionUserUuid, newPassword.toCharArray());
            if (changed) message = "Password updated.";
            else error = "No changes were made.";
          }
        } catch (Exception ex) {
          String m = cpSafe(ex.getMessage()).trim();
          error = m.isBlank() ? "Unable to update password." : m;
        }
      }
    } else if ("admin_change_password".equalsIgnoreCase(action)) {
      if (!tenantAdmin) {
        error = "Tenant administrator permission is required.";
      } else if (selectedAdminUserUuid.isBlank()) {
        error = "Select a user.";
      } else if (adminNewPassword.isBlank()) {
        error = "New password is required.";
      } else if (!adminNewPassword.equals(adminConfirmPassword)) {
        error = "Password confirmation does not match.";
      } else {
        try {
          boolean changed = store.updateUserPassword(
                  tenantUuid,
                  selectedAdminUserUuid,
                  adminNewPassword.toCharArray(),
                  adminBypassPasswordPolicy
          );
          if (changed) message = "User password updated.";
          else error = "No changes were made.";
        } catch (Exception ex) {
          String m = cpSafe(ex.getMessage()).trim();
          error = m.isBlank() ? "Unable to update user password." : m;
        }
      }
    }
  }

  String csrfToken = cpCsrfForRender(request);
%>

<jsp:include page="header.jsp" />

<div class="container main">
  <section class="card narrow">
    <div class="section-head">
      <div>
        <h1>Change Password</h1>
        <div class="meta">Update your password for the current tenant account.</div>
      </div>
    </div>

    <% if (message != null) { %>
      <div class="alert alert-ok"><%= cpEsc(message) %></div>
      <div style="height:12px;"></div>
    <% } %>
    <% if (error != null) { %>
      <div class="alert alert-error"><%= cpEsc(error) %></div>
      <div style="height:12px;"></div>
    <% } %>

    <% if (policy != null && policy.enabled) { %>
      <div class="alert alert-warn" style="margin-bottom:12px;">
        Password policy is enabled for this tenant (minimum length: <strong><%= policy.minLength %></strong>).
      </div>
    <% } %>

    <form class="form" method="post" action="<%= ctx %>/change_password.jsp">
      <input type="hidden" name="csrfToken" value="<%= cpEsc(csrfToken) %>" />
      <input type="hidden" name="action" value="self_change_password" />

      <label>
        <span>Current Password</span>
        <input type="password" name="currentPassword" autocomplete="current-password" required />
      </label>

      <label>
        <span>New Password</span>
        <input type="password" name="newPassword" autocomplete="new-password" required />
      </label>

      <label>
        <span>Confirm New Password</span>
        <input type="password" name="confirmPassword" autocomplete="new-password" required />
      </label>

      <div class="actions">
        <button class="btn" type="submit">Update Password</button>
      </div>
    </form>
  </section>

  <% if (tenantAdmin) { %>
    <section class="card narrow" style="margin-top:12px;">
      <div class="section-head">
        <div>
          <h2 style="margin:0;">Admin: Set User Password</h2>
          <div class="meta">Tenant administrators can set passwords for any user and optionally override policy.</div>
        </div>
      </div>

      <form class="form" method="post" action="<%= ctx %>/change_password.jsp">
        <input type="hidden" name="csrfToken" value="<%= cpEsc(csrfToken) %>" />
        <input type="hidden" name="action" value="admin_change_password" />

        <label>
          <span>User</span>
          <select name="adminUserUuid" required <%= allUsers.isEmpty() ? "disabled" : "" %>>
            <% if (allUsers.isEmpty()) { %>
              <option value="" selected>No users available</option>
            <% } else { %>
              <% for (int i = 0; i < allUsers.size(); i++) {
                   UserRec u = allUsers.get(i);
                   String uUuid = cpSafe(u.uuid).trim();
                   boolean selected = !selectedAdminUserUuid.isBlank() && selectedAdminUserUuid.equals(uUuid);
              %>
                <option value="<%= cpEsc(uUuid) %>" <%= selected ? "selected" : "" %>><%= cpEsc(cpSafe(u.emailAddress)) %></option>
              <% } %>
            <% } %>
          </select>
        </label>

        <label>
          <span>New Password</span>
          <input type="password" name="adminNewPassword" autocomplete="new-password" required />
        </label>

        <label>
          <span>Confirm New Password</span>
          <input type="password" name="adminConfirmPassword" autocomplete="new-password" required />
        </label>

        <div class="field-row">
          <input type="checkbox" id="adminBypassPasswordPolicy" name="adminBypassPasswordPolicy" value="1" <%= adminBypassPasswordPolicy ? "checked" : "" %> />
          <label for="adminBypassPasswordPolicy"><span style="margin:0; color:var(--text); font-weight:600;">Override password policy for this change</span></label>
        </div>

        <div class="actions">
          <button class="btn" type="submit" <%= allUsers.isEmpty() ? "disabled" : "" %>>Set User Password</button>
        </div>
      </form>
    </section>
  <% } %>
</div>

<jsp:include page="footer.jsp" />
