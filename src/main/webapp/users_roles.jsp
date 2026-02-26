<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>

<%@ page import="java.util.*" %>

<%@ page import="net.familylawandprobate.controversies.tenants" %>
<%@ page import="net.familylawandprobate.controversies.users_roles" %>
<%@ page import="net.familylawandprobate.controversies.users_roles.UserRec" %>
<%@ page import="net.familylawandprobate.controversies.users_roles.RoleRec" %>

<%!
  // NOTE: Do NOT define safe/esc/S_TENANT_UUID here; security.jspf already defines those.
  // Use unique helper names to avoid collisions after directive include.

  private static String u_safe(String s){ return s == null ? "" : s; }

  private static String u_esc(String s) {
    if (s == null) return "";
    return s.replace("&","&amp;")
            .replace("<","&lt;")
            .replace(">","&gt;")
            .replace("\"","&quot;")
            .replace("'","&#39;");
  }

  private static boolean u_checked(String v) {
    if (v == null) return false;
    String s = v.trim().toLowerCase(Locale.ROOT);
    return "1".equals(s) || "true".equals(s) || "on".equals(s) || "yes".equals(s);
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
        Object t = sess.getAttribute("CSRF_TOKEN");
        if (t instanceof String) {
          String cs = (String) t;
          if (cs != null && !cs.trim().isEmpty()) return cs;
        }
      }
    } catch (Exception ignored) {}
    return "";
  }

  private static RoleRec u_findRole(List<RoleRec> roles, String roleUuid) {
    String ru = u_safe(roleUuid).trim();
    if (ru.isBlank()) return null;
    for (RoleRec r : roles) {
      if (r != null && ru.equals(u_safe(r.uuid))) return r;
    }
    return null;
  }

  private static UserRec u_findUser(List<UserRec> users, String userUuid) {
    String uu = u_safe(userUuid).trim();
    if (uu.isBlank()) return null;
    for (UserRec u : users) {
      if (u != null && uu.equals(u_safe(u.uuid))) return u;
    }
    return null;
  }

  private static String u_roleLabel(Map<String,String> roleLabels, String roleUuid) {
    String ru = u_safe(roleUuid).trim();
    String lbl = roleLabels.get(ru);
    return (lbl == null) ? "" : lbl;
  }
%>

<jsp:include page="header.jsp" />
<%@ include file="security.jspf" %>
<% if (!require_permission("tenant_admin")) return; %>

<%
  String ctx = request.getContextPath();
  if (ctx == null) ctx = "";

  // Session tenant (who you are logged into)
  String sessionTenantUuid  = u_safe((String) session.getAttribute("tenant.uuid")).trim();
  String sessionTenantLabel = u_safe((String) session.getAttribute("tenant.label")).trim();

  String csrfToken = csrfForRender(request);

  String message = null;
  String error = null;

  // Load all tenants for dropdown
  tenants tstore = tenants.defaultStore();
  try { tstore.ensure(); } catch (Exception ignored) {}

  List<tenants.Tenant> allTenants = new ArrayList<>();
  try {
    List<tenants.Tenant> tmp = tstore.list();
    if (tmp != null) allTenants.addAll(tmp);
    allTenants.sort((a,b) -> u_safe(a.label).compareToIgnoreCase(u_safe(b.label)));
  } catch (Exception e) {
    error = "Unable to load tenants: " + e.getMessage();
  }

  // Target tenant (administer THIS tenant)
  String targetTenantUuid = u_safe(request.getParameter("targetTenantUuid")).trim();
  if (targetTenantUuid.isBlank()) targetTenantUuid = sessionTenantUuid;

  // Validate target tenant exists
  tenants.Tenant targetTenant = null;
  if (error == null) {
    for (tenants.Tenant t : allTenants) {
      if (t != null && targetTenantUuid.equals(u_safe(t.uuid))) { targetTenant = t; break; }
    }
    if (targetTenant == null && !targetTenantUuid.isBlank()) {
      error = "Selected tenant does not exist.";
    }
  }

  // Selections (GET/POST) within target tenant
  String selectedUserUuid = u_safe(request.getParameter("userUuid")).trim();
  String selectedRoleUuid = u_safe(request.getParameter("roleUuid")).trim();

  users_roles store = users_roles.defaultStore();

  // Ensure target tenant user/role files exist (also applies bootstrap patches)
  if (error == null) {
    try {
      if (!targetTenantUuid.isBlank()) store.ensure(targetTenantUuid);
    } catch (Exception e) {
      error = "Unable to initialize users/roles for tenant: " + e.getMessage();
    }
  }

  // Handle POST actions (all operate on targetTenantUuid)
  if (error == null && "POST".equalsIgnoreCase(request.getMethod())) {
    String action = u_safe(request.getParameter("action")).trim();

    try {
      if ("createRole".equalsIgnoreCase(action)) {
        String label = u_safe(request.getParameter("newRoleLabel")).trim();
        boolean enabled = u_checked(request.getParameter("newRoleEnabled"));

        if (label.isBlank()) error = "Role label is required.";
        else {
          RoleRec r = store.createRole(targetTenantUuid, label, enabled);
          message = "Role created.";
          if (r != null) selectedRoleUuid = u_safe(r.uuid);
        }

      } else if ("updateRole".equalsIgnoreCase(action)) {
        String roleUuid = u_safe(request.getParameter("editRoleUuid")).trim();
        String label = u_safe(request.getParameter("editRoleLabel")).trim();
        boolean enabled = u_checked(request.getParameter("editRoleEnabled"));

        if (roleUuid.isBlank()) error = "Select a role to update.";
        else {
          boolean c1 = false, c2 = false;
          if (!label.isBlank()) c1 = store.updateRoleLabel(targetTenantUuid, roleUuid, label);
          c2 = store.updateRoleEnabled(targetTenantUuid, roleUuid, enabled);
          message = (c1 || c2) ? "Role updated." : "No changes.";
          selectedRoleUuid = roleUuid;
        }

      } else if ("setRolePerm".equalsIgnoreCase(action)) {
        String roleUuid = u_safe(request.getParameter("permRoleUuid")).trim();
        String key = u_safe(request.getParameter("permKey")).trim();
        String value = u_safe(request.getParameter("permValue"));

        if (roleUuid.isBlank()) error = "Select a role.";
        else if (key.isBlank()) error = "Permission key is required.";
        else {
          boolean changed = store.setRolePermission(targetTenantUuid, roleUuid, key, value);
          message = changed ? "Permission saved." : "No changes.";
          selectedRoleUuid = roleUuid;
        }

      } else if ("removeRolePerm".equalsIgnoreCase(action)) {
        String roleUuid = u_safe(request.getParameter("permRoleUuid")).trim();
        String key = u_safe(request.getParameter("permKeyRemove")).trim();

        if (roleUuid.isBlank()) error = "Select a role.";
        else if (key.isBlank()) error = "Select a permission to remove.";
        else {
          boolean changed = store.removeRolePermission(targetTenantUuid, roleUuid, key);
          message = changed ? "Permission removed." : "No changes.";
          selectedRoleUuid = roleUuid;
        }

      } else if ("createUser".equalsIgnoreCase(action)) {
        String email = u_safe(request.getParameter("newUserEmail")).trim();
        String roleUuid = u_safe(request.getParameter("newUserRoleUuid")).trim();
        boolean enabled = u_checked(request.getParameter("newUserEnabled"));
        String pw = u_safe(request.getParameter("newUserPassword"));

        if (email.isBlank()) error = "Email address is required.";
        else if (roleUuid.isBlank()) error = "Role is required.";
        else if (pw.isBlank()) error = "Password is required.";
        else {
          UserRec u = store.createUser(targetTenantUuid, email, roleUuid, enabled, pw.toCharArray());
          message = "User created.";
          if (u != null) selectedUserUuid = u_safe(u.uuid);
        }

      } else if ("updateUser".equalsIgnoreCase(action)) {
        String userUuid = u_safe(request.getParameter("editUserUuid")).trim();
        String email = u_safe(request.getParameter("editUserEmail")).trim();
        String roleUuid = u_safe(request.getParameter("editUserRoleUuid")).trim();
        boolean enabled = u_checked(request.getParameter("editUserEnabled"));

        if (userUuid.isBlank()) error = "Select a user to update.";
        else {
          boolean c1 = false, c2 = false, c3 = false;
          if (!email.isBlank()) c1 = store.updateUserEmail(targetTenantUuid, userUuid, email);
          if (!roleUuid.isBlank()) c2 = store.updateUserRole(targetTenantUuid, userUuid, roleUuid);
          c3 = store.updateUserEnabled(targetTenantUuid, userUuid, enabled);

          message = (c1 || c2 || c3) ? "User updated." : "No changes.";
          selectedUserUuid = userUuid;
        }

      } else if ("resetUserPassword".equalsIgnoreCase(action)) {
        String userUuid = u_safe(request.getParameter("pwUserUuid")).trim();
        String pw = u_safe(request.getParameter("pwNewPassword"));

        if (userUuid.isBlank()) error = "Select a user.";
        else if (pw.isBlank()) error = "New password is required.";
        else {
          boolean changed = store.updateUserPassword(targetTenantUuid, userUuid, pw.toCharArray());
          message = changed ? "Password updated." : "No changes.";
          selectedUserUuid = userUuid;
        }
      }

      // Re-ensure after edits (re-assert bootstrap defaults)
      try { store.ensure(targetTenantUuid); } catch (Exception ignored) {}

    } catch (Exception e) {
      error = u_safe(e.getMessage());
      if (error.isBlank()) error = "Operation failed.";
    }
  }

  // Load roles/users for target tenant
  List<RoleRec> roles = new ArrayList<>();
  List<UserRec> users = new ArrayList<>();
  Map<String,String> roleLabels = new HashMap<>();

  if (error == null) {
    try {
      roles = new ArrayList<>(store.listRoles(targetTenantUuid));
      roles.sort((a,b) -> u_safe(a.label).compareToIgnoreCase(u_safe(b.label)));
      for (RoleRec r : roles) if (r != null) roleLabels.put(u_safe(r.uuid), u_safe(r.label));

      users = new ArrayList<>(store.listUsers(targetTenantUuid));
      users.sort((a,b) -> u_safe(a.emailAddress).compareToIgnoreCase(u_safe(b.emailAddress)));
    } catch (Exception e) {
      error = "Unable to load users/roles: " + e.getMessage();
    }
  }

  // Defaults
  if (selectedRoleUuid.isBlank() && !roles.isEmpty()) selectedRoleUuid = u_safe(roles.get(0).uuid);
  if (selectedUserUuid.isBlank() && !users.isEmpty()) selectedUserUuid = u_safe(users.get(0).uuid);

  RoleRec selectedRole = u_findRole(roles, selectedRoleUuid);
  UserRec selectedUser = u_findUser(users, selectedUserUuid);

  boolean selectedRoleIsBootstrap = (selectedRole != null && "Tenant Administrator".equalsIgnoreCase(u_safe(selectedRole.label).trim()));
  boolean selectedUserIsBootstrap = (selectedUser != null && "tenant_admin".equalsIgnoreCase(u_safe(selectedUser.emailAddress).trim()));

  String targetTenantLabel = (targetTenant != null) ? u_safe(targetTenant.label) : "";
%>

<div class="container main">
  <section class="card">
    <div class="section-head">
      <div>
        <h1>Users & Roles (All Tenants)</h1>
        <div class="meta">
          Logged in tenant: <strong><%= u_esc(sessionTenantLabel.isBlank() ? sessionTenantUuid : sessionTenantLabel) %></strong>
        </div>
      </div>
    </div>

    <% if (message != null && !message.isBlank()) { %>
      <div class="alert alert-ok"><%= u_esc(message) %></div>
      <div style="height:12px;"></div>
    <% } %>

    <% if (error != null && !error.isBlank()) { %>
      <div class="alert alert-error"><%= u_esc(error) %></div>
      <div style="height:12px;"></div>
    <% } %>

    <section class="card">
      <div class="section-head">
        <div>
          <h2>Target tenant</h2>
          <div class="meta">Choose which tenant you want to administer.</div>
        </div>
      </div>

      <form class="form" method="get" action="<%= ctx %>/users_roles.jsp">
        <label>
          <span>Tenant</span>
          <select name="targetTenantUuid" required <%= allTenants.isEmpty() ? "disabled" : "" %>>
            <% if (allTenants.isEmpty()) { %>
              <option value="" selected>No tenants…</option>
            <% } else {
                 for (tenants.Tenant t : allTenants) {
                   if (t == null) continue;
                   String tu = u_safe(t.uuid);
                   String lbl = u_safe(t.label);
                   boolean sel = tu.equals(targetTenantUuid);
            %>
              <option value="<%= u_esc(tu) %>" <%= sel ? "selected" : "" %>>
                <%= u_esc(lbl.isBlank() ? tu : lbl) %><%= t.enabled ? "" : " (disabled)" %>
              </option>
            <%   }
               } %>
          </select>
        </label>

        <div class="actions">
          <button class="btn btn-ghost" type="submit" <%= allTenants.isEmpty() ? "disabled" : "" %>>Load tenant</button>
        </div>

        <% if (!targetTenantUuid.isBlank()) { %>
          <div class="help">
            Administering: <strong><%= u_esc(targetTenantLabel.isBlank() ? targetTenantUuid : targetTenantLabel) %></strong>
          </div>
        <% } %>
      </form>
    </section>

    <div style="height:12px;"></div>

    <div class="grid">

      <!-- USERS -->
      <section class="card">
        <div class="section-head">
          <div>
            <h2>Users</h2>
            <div class="meta">Create users, enable/disable them, reset passwords, and assign roles.</div>
          </div>
        </div>

        <div class="table-wrap">
          <table class="table">
            <thead>
              <tr><th>Email</th><th>Role</th><th>Enabled</th></tr>
            </thead>
            <tbody>
              <% if (users.isEmpty()) { %>
                <tr><td colspan="3"><small>No users found.</small></td></tr>
              <% } else { for (UserRec u : users) { if (u == null) continue; %>
                <tr>
                  <td><%= u_esc(u.emailAddress) %></td>
                  <td><%= u_esc(u_roleLabel(roleLabels, u.roleUuid)) %></td>
                  <td><%= u.enabled ? "<span class=\"badge\">Yes</span>" : "<span class=\"badge\">No</span>" %></td>
                </tr>
              <% } } %>
            </tbody>
          </table>
        </div>

        <hr/>

        <h3>Create user</h3>
        <form class="form" method="post" action="<%= ctx %>/users_roles.jsp">
          <input type="hidden" name="csrfToken" value="<%= u_esc(csrfToken) %>" />
          <input type="hidden" name="action" value="createUser" />
          <input type="hidden" name="targetTenantUuid" value="<%= u_esc(targetTenantUuid) %>" />

          <label>
            <span>Email address</span>
            <input type="text" name="newUserEmail" placeholder="name@example.com" required />
          </label>

          <label>
            <span>Role</span>
            <select name="newUserRoleUuid" required <%= roles.isEmpty() ? "disabled" : "" %>>
              <option value="" disabled selected>Select a role…</option>
              <% for (RoleRec r : roles) { if (r == null) continue; %>
                <option value="<%= u_esc(r.uuid) %>"><%= u_esc(r.label) %><%= r.enabled ? "" : " (disabled)" %></option>
              <% } %>
            </select>
          </label>

          <label>
            <span>Initial password</span>
            <input type="password" name="newUserPassword" required />
          </label>

          <div class="field-row">
            <input type="checkbox" id="newUserEnabled" name="newUserEnabled" checked />
            <label for="newUserEnabled"><span style="margin:0; color:var(--text); font-weight:600;">Enabled</span></label>
          </div>

          <div class="actions">
            <button class="btn" type="submit" <%= roles.isEmpty() ? "disabled" : "" %>>Create user</button>
          </div>
        </form>

        <hr/>

        <h3>Edit user</h3>
        <form class="form" method="get" action="<%= ctx %>/users_roles.jsp">
          <input type="hidden" name="targetTenantUuid" value="<%= u_esc(targetTenantUuid) %>" />

          <label>
            <span>Select user</span>
            <select name="userUuid" <%= users.isEmpty() ? "disabled" : "" %>>
              <% if (users.isEmpty()) { %>
                <option value="" selected>No users…</option>
              <% } else { for (UserRec u : users) { if (u == null) continue; %>
                <option value="<%= u_esc(u.uuid) %>" <%= u_safe(u.uuid).equals(selectedUserUuid) ? "selected" : "" %>>
                  <%= u_esc(u.emailAddress) %>
                </option>
              <% } } %>
            </select>
          </label>

          <div class="actions">
            <button class="btn btn-ghost" type="submit" <%= users.isEmpty() ? "disabled" : "" %>>Load</button>
          </div>
        </form>

        <% if (selectedUser != null) { %>
          <form class="form" method="post" action="<%= ctx %>/users_roles.jsp" style="margin-top:12px;">
            <input type="hidden" name="csrfToken" value="<%= u_esc(csrfToken) %>" />
            <input type="hidden" name="action" value="updateUser" />
            <input type="hidden" name="targetTenantUuid" value="<%= u_esc(targetTenantUuid) %>" />
            <input type="hidden" name="editUserUuid" value="<%= u_esc(selectedUser.uuid) %>" />

            <label>
              <span>Email address</span>
              <input type="text" name="editUserEmail" value="<%= u_esc(selectedUser.emailAddress) %>" <%= selectedUserIsBootstrap ? "readonly" : "" %> />
            </label>

            <label>
              <span>Role</span>
              <select name="editUserRoleUuid" required <%= roles.isEmpty() ? "disabled" : "" %>>
                <% for (RoleRec r : roles) { if (r == null) continue; %>
                  <option value="<%= u_esc(r.uuid) %>" <%= u_safe(r.uuid).equals(u_safe(selectedUser.roleUuid)) ? "selected" : "" %>>
                    <%= u_esc(r.label) %><%= r.enabled ? "" : " (disabled)" %>
                  </option>
                <% } %>
              </select>
            </label>

            <div class="field-row">
              <input type="checkbox" id="editUserEnabled" name="editUserEnabled"
                     <%= selectedUser.enabled ? "checked" : "" %> <%= selectedUserIsBootstrap ? "disabled" : "" %> />
              <label for="editUserEnabled"><span style="margin:0; color:var(--text); font-weight:600;">Enabled</span></label>
              <% if (selectedUserIsBootstrap) { %><small>(bootstrap user is enforced enabled)</small><% } %>
            </div>

            <div class="actions">
              <button class="btn" type="submit" <%= roles.isEmpty() ? "disabled" : "" %>>Save user</button>
            </div>
          </form>

          <form class="form" method="post" action="<%= ctx %>/users_roles.jsp" style="margin-top:12px;">
            <input type="hidden" name="csrfToken" value="<%= u_esc(csrfToken) %>" />
            <input type="hidden" name="action" value="resetUserPassword" />
            <input type="hidden" name="targetTenantUuid" value="<%= u_esc(targetTenantUuid) %>" />
            <input type="hidden" name="pwUserUuid" value="<%= u_esc(selectedUser.uuid) %>" />

            <label>
              <span>Reset password</span>
              <input type="password" name="pwNewPassword" placeholder="New password…" required />
            </label>

            <div class="actions">
              <button class="btn" type="submit">Update password</button>
            </div>
          </form>
        <% } %>
      </section>

      <!-- ROLES -->
      <section class="card">
        <div class="section-head">
          <div>
            <h2>Roles & Permissions</h2>
            <div class="meta">Create roles, enable/disable them, and manage key/value permissions.</div>
          </div>
        </div>

        <div class="table-wrap">
          <table class="table">
            <thead><tr><th>Label</th><th>Enabled</th><th>Permissions</th></tr></thead>
            <tbody>
              <% if (roles.isEmpty()) { %>
                <tr><td colspan="3"><small>No roles found.</small></td></tr>
              <% } else { for (RoleRec r : roles) { if (r == null) continue; %>
                <tr>
                  <td><%= u_esc(r.label) %></td>
                  <td><%= r.enabled ? "<span class=\"badge\">Yes</span>" : "<span class=\"badge\">No</span>" %></td>
                  <td><%= (r.permissions == null) ? 0 : r.permissions.size() %></td>
                </tr>
              <% } } %>
            </tbody>
          </table>
        </div>

        <hr/>

        <h3>Create role</h3>
        <form class="form" method="post" action="<%= ctx %>/users_roles.jsp">
          <input type="hidden" name="csrfToken" value="<%= u_esc(csrfToken) %>" />
          <input type="hidden" name="action" value="createRole" />
          <input type="hidden" name="targetTenantUuid" value="<%= u_esc(targetTenantUuid) %>" />

          <label>
            <span>Role label</span>
            <input type="text" name="newRoleLabel" required />
          </label>

          <div class="field-row">
            <input type="checkbox" id="newRoleEnabled" name="newRoleEnabled" checked />
            <label for="newRoleEnabled"><span style="margin:0; color:var(--text); font-weight:600;">Enabled</span></label>
          </div>

          <div class="actions">
            <button class="btn" type="submit">Create role</button>
          </div>
        </form>

        <hr/>

        <h3>Edit role</h3>
        <form class="form" method="get" action="<%= ctx %>/users_roles.jsp">
          <input type="hidden" name="targetTenantUuid" value="<%= u_esc(targetTenantUuid) %>" />
          <label>
            <span>Select role</span>
            <select name="roleUuid" <%= roles.isEmpty() ? "disabled" : "" %>>
              <% if (roles.isEmpty()) { %>
                <option value="" selected>No roles…</option>
              <% } else { for (RoleRec r : roles) { if (r == null) continue; %>
                <option value="<%= u_esc(r.uuid) %>" <%= u_safe(r.uuid).equals(selectedRoleUuid) ? "selected" : "" %>>
                  <%= u_esc(r.label) %>
                </option>
              <% } } %>
            </select>
          </label>

          <div class="actions">
            <button class="btn btn-ghost" type="submit" <%= roles.isEmpty() ? "disabled" : "" %>>Load</button>
          </div>
        </form>

        <% if (selectedRole != null) { %>
          <form class="form" method="post" action="<%= ctx %>/users_roles.jsp" style="margin-top:12px;">
            <input type="hidden" name="csrfToken" value="<%= u_esc(csrfToken) %>" />
            <input type="hidden" name="action" value="updateRole" />
            <input type="hidden" name="targetTenantUuid" value="<%= u_esc(targetTenantUuid) %>" />
            <input type="hidden" name="editRoleUuid" value="<%= u_esc(selectedRole.uuid) %>" />

            <label>
              <span>Role label</span>
              <input type="text" name="editRoleLabel" value="<%= u_esc(selectedRole.label) %>" <%= selectedRoleIsBootstrap ? "readonly" : "" %> />
            </label>

            <div class="field-row">
              <input type="checkbox" id="editRoleEnabled" name="editRoleEnabled"
                     <%= selectedRole.enabled ? "checked" : "" %> <%= selectedRoleIsBootstrap ? "disabled" : "" %> />
              <label for="editRoleEnabled"><span style="margin:0; color:var(--text); font-weight:600;">Enabled</span></label>
              <% if (selectedRoleIsBootstrap) { %><small>(bootstrap role is enforced enabled)</small><% } %>
            </div>

            <div class="actions">
              <button class="btn" type="submit">Save role</button>
            </div>
          </form>

          <hr/>

          <h3>Permissions for: <%= u_esc(selectedRole.label) %></h3>

          <div class="table-wrap">
            <table class="table">
              <thead><tr><th>Key</th><th>Value</th><th style="width:120px;">Remove</th></tr></thead>
              <tbody>
                <%
                  Map<String,String> perms = (selectedRole.permissions == null) ? new LinkedHashMap<>() : selectedRole.permissions;
                  if (perms.isEmpty()) {
                %>
                  <tr><td colspan="3"><small>No permissions.</small></td></tr>
                <%
                  } else {
                    for (Map.Entry<String,String> e : perms.entrySet()) {
                      String k = u_safe(e.getKey());
                      String v = u_safe(e.getValue());
                %>
                  <tr>
                    <td><code><%= u_esc(k) %></code></td>
                    <td><%= u_esc(v) %></td>
                    <td>
                      <form method="post" action="<%= ctx %>/users_roles.jsp" style="margin:0;">
                        <input type="hidden" name="csrfToken" value="<%= u_esc(csrfToken) %>" />
                        <input type="hidden" name="action" value="removeRolePerm" />
                        <input type="hidden" name="targetTenantUuid" value="<%= u_esc(targetTenantUuid) %>" />
                        <input type="hidden" name="permRoleUuid" value="<%= u_esc(selectedRole.uuid) %>" />
                        <input type="hidden" name="permKeyRemove" value="<%= u_esc(k) %>" />
                        <button class="btn btn-ghost" type="submit" <%= (selectedRoleIsBootstrap && "tenant_admin".equals(k)) ? "disabled" : "" %>>
                          Remove
                        </button>
                      </form>
                    </td>
                  </tr>
                <%
                    }
                  }
                %>
              </tbody>
            </table>
          </div>

          <form class="form" method="post" action="<%= ctx %>/users_roles.jsp" style="margin-top:12px;">
            <input type="hidden" name="csrfToken" value="<%= u_esc(csrfToken) %>" />
            <input type="hidden" name="action" value="setRolePerm" />
            <input type="hidden" name="targetTenantUuid" value="<%= u_esc(targetTenantUuid) %>" />
            <input type="hidden" name="permRoleUuid" value="<%= u_esc(selectedRole.uuid) %>" />

            <label>
              <span>Permission key</span>
              <input type="text" name="permKey" placeholder="e.g., tenant_admin" required />
            </label>

            <label>
              <span>Value</span>
              <input type="text" name="permValue" placeholder="e.g., true" />
            </label>

            <div class="actions">
              <button class="btn" type="submit">Save permission</button>
            </div>
          </form>

          <% if (selectedRoleIsBootstrap) { %>
            <div style="height:12px;"></div>
            <div class="alert alert-warn">
              The <strong>Tenant Administrator</strong> role and <code>tenant_admin=true</code> are enforced by bootstrap and may be restored automatically.
            </div>
          <% } %>
        <% } %>
      </section>

    </div>
  </section>
</div>

<jsp:include page="footer.jsp" />
