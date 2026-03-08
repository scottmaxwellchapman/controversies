<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" isELIgnored="true" %>

<%@ page import="java.util.ArrayList" %>
<%@ page import="java.util.HashMap" %>
<%@ page import="java.util.LinkedHashMap" %>
<%@ page import="java.util.LinkedHashSet" %>
<%@ page import="java.util.List" %>
<%@ page import="java.util.Locale" %>
<%@ page import="java.util.Map" %>
<%@ page import="java.util.Set" %>

<%@ page import="net.familylawandprobate.controversies.custom_objects" %>
<%@ page import="net.familylawandprobate.controversies.permission_layers" %>
<%@ page import="net.familylawandprobate.controversies.permission_layers.GroupRec" %>
<%@ page import="net.familylawandprobate.controversies.permission_layers.PermissionDef" %>
<%@ page import="net.familylawandprobate.controversies.permission_layers.PermissionProfile" %>
<%@ page import="net.familylawandprobate.controversies.users_roles" %>
<%@ page import="net.familylawandprobate.controversies.users_roles.UserRec" %>

<%@ include file="security.jspf" %>
<% if (!require_login()) return; %>
<jsp:include page="header.jsp" />

<%!
  private static String pm_safe(String s) { return s == null ? "" : s; }

  private static String pm_esc(String s) {
    if (s == null) return "";
    return s.replace("&","&amp;")
            .replace("<","&lt;")
            .replace(">","&gt;")
            .replace("\"","&quot;")
            .replace("'","&#39;");
  }

  private static boolean pm_checked(String v) {
    if (v == null) return false;
    String s = v.trim().toLowerCase(Locale.ROOT);
    return "1".equals(s) || "true".equals(s) || "on".equals(s) || "yes".equals(s) || "y".equals(s);
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

  private static UserRec pm_findUser(List<UserRec> users, String userUuid) {
    String uu = pm_safe(userUuid).trim();
    if (uu.isBlank()) return null;
    for (UserRec u : users) {
      if (u != null && uu.equals(pm_safe(u.uuid).trim())) return u;
    }
    return null;
  }

  private static GroupRec pm_findGroup(List<GroupRec> groups, String groupUuid) {
    String gu = pm_safe(groupUuid).trim();
    if (gu.isBlank()) return null;
    for (GroupRec g : groups) {
      if (g != null && gu.equals(pm_safe(g.uuid).trim())) return g;
    }
    return null;
  }

  private static LinkedHashMap<String, String> pm_merge(Map<String, String> base, Map<String, String> overlay) {
    LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
    if (base != null) out.putAll(base);
    if (overlay != null) out.putAll(overlay);
    return out;
  }
%>

<%
  String ctx = pm_safe(request.getContextPath());
  String tenantUuid = pm_safe((String) session.getAttribute("tenant.uuid")).trim();
  String tenantLabel = pm_safe((String) session.getAttribute("tenant.label")).trim();
  if (tenantUuid.isBlank()) {
    response.sendRedirect(ctx + "/tenant_login.jsp");
    return;
  }

  String csrfToken = csrfForRender(request);
  String message = null;
  String error = null;

  permission_layers perms = permission_layers.defaultStore();
  users_roles usersStore = users_roles.defaultStore();
  custom_objects objectStore = custom_objects.defaultStore();

  try {
    perms.ensure(tenantUuid);
    usersStore.ensure(tenantUuid);
    objectStore.ensure(tenantUuid);
  } catch (Exception ex) {
    error = "Unable to initialize permission stores: " + pm_safe(ex.getMessage());
  }

  String selectedGroupUuid = pm_safe(request.getParameter("groupUuid")).trim();
  String selectedUserUuid = pm_safe(request.getParameter("userUuid")).trim();

  if (error == null && "POST".equalsIgnoreCase(request.getMethod())) {
    String action = pm_safe(request.getParameter("action")).trim();
    selectedGroupUuid = pm_safe(request.getParameter("groupUuid")).trim();
    selectedUserUuid = pm_safe(request.getParameter("userUuid")).trim();

    try {
      if ("tenant_set_perm".equals(action)) {
        String key = pm_safe(request.getParameter("tenantPermKey")).trim();
        String value = pm_safe(request.getParameter("tenantPermValue")).trim();
        if (key.isBlank()) error = "Tenant permission key is required.";
        else {
          boolean changed = perms.setTenantPermission(tenantUuid, key, value);
          message = changed ? "Tenant permission saved." : "No tenant permission changes.";
        }
      } else if ("tenant_remove_perm".equals(action)) {
        String key = pm_safe(request.getParameter("tenantPermKeyRemove")).trim();
        if (key.isBlank()) error = "Select a tenant permission key.";
        else {
          boolean changed = perms.removeTenantPermission(tenantUuid, key);
          message = changed ? "Tenant permission removed." : "No tenant permission changes.";
        }
      } else if ("tenant_apply_profile".equals(action)) {
        String profileKey = pm_safe(request.getParameter("tenantProfileKey")).trim();
        PermissionProfile p = perms.getProfile(profileKey);
        if (p == null) error = "Select a valid tenant profile.";
        else {
          LinkedHashMap<String, String> merged = pm_merge(perms.readTenantPermissions(tenantUuid), p.permissions);
          perms.replaceTenantPermissions(tenantUuid, merged);
          message = "Tenant profile applied: " + p.label + ".";
        }
      } else if ("group_create".equals(action)) {
        String label = pm_safe(request.getParameter("newGroupLabel")).trim();
        boolean enabled = pm_checked(request.getParameter("newGroupEnabled"));
        if (label.isBlank()) error = "Group label is required.";
        else {
          GroupRec g = perms.createGroup(tenantUuid, label, enabled);
          selectedGroupUuid = (g == null) ? "" : pm_safe(g.uuid);
          message = "Group created.";
        }
      } else if ("group_update".equals(action)) {
        if (selectedGroupUuid.isBlank()) error = "Select a group.";
        else {
          String label = pm_safe(request.getParameter("editGroupLabel")).trim();
          boolean enabled = pm_checked(request.getParameter("editGroupEnabled"));
          boolean c1 = false;
          boolean c2 = false;
          if (!label.isBlank()) c1 = perms.updateGroupLabel(tenantUuid, selectedGroupUuid, label);
          c2 = perms.updateGroupEnabled(tenantUuid, selectedGroupUuid, enabled);
          message = (c1 || c2) ? "Group updated." : "No group changes.";
        }
      } else if ("group_set_perm".equals(action)) {
        if (selectedGroupUuid.isBlank()) error = "Select a group.";
        else {
          String key = pm_safe(request.getParameter("groupPermKey")).trim();
          String value = pm_safe(request.getParameter("groupPermValue")).trim();
          if (key.isBlank()) error = "Group permission key is required.";
          else {
            boolean changed = perms.setGroupPermission(tenantUuid, selectedGroupUuid, key, value);
            message = changed ? "Group permission saved." : "No group permission changes.";
          }
        }
      } else if ("group_remove_perm".equals(action)) {
        if (selectedGroupUuid.isBlank()) error = "Select a group.";
        else {
          String key = pm_safe(request.getParameter("groupPermKeyRemove")).trim();
          if (key.isBlank()) error = "Select a group permission key.";
          else {
            boolean changed = perms.removeGroupPermission(tenantUuid, selectedGroupUuid, key);
            message = changed ? "Group permission removed." : "No group permission changes.";
          }
        }
      } else if ("group_apply_profile".equals(action)) {
        if (selectedGroupUuid.isBlank()) error = "Select a group.";
        else {
          String profileKey = pm_safe(request.getParameter("groupProfileKey")).trim();
          PermissionProfile p = perms.getProfile(profileKey);
          if (p == null) error = "Select a valid group profile.";
          else {
            GroupRec g = perms.getGroupByUuid(tenantUuid, selectedGroupUuid);
            if (g == null) error = "Group not found.";
            else {
              perms.replaceGroupPermissions(tenantUuid, selectedGroupUuid, pm_merge(g.permissions, p.permissions));
              message = "Group profile applied: " + p.label + ".";
            }
          }
        }
      } else if ("group_save_members".equals(action)) {
        if (selectedGroupUuid.isBlank()) error = "Select a group.";
        else {
          String[] selectedMembers = request.getParameterValues("groupMemberUserUuid");
          LinkedHashSet<String> memberSet = new LinkedHashSet<String>();
          if (selectedMembers != null) {
            for (String m : selectedMembers) {
              String uu = pm_safe(m).trim();
              if (!uu.isBlank()) memberSet.add(uu);
            }
          }
          boolean changed = perms.setGroupMembers(tenantUuid, selectedGroupUuid, memberSet);
          message = changed ? "Group members updated." : "No membership changes.";
        }
      } else if ("user_set_perm".equals(action)) {
        if (selectedUserUuid.isBlank()) error = "Select a user.";
        else {
          String key = pm_safe(request.getParameter("userPermKey")).trim();
          String value = pm_safe(request.getParameter("userPermValue")).trim();
          if (key.isBlank()) error = "User permission key is required.";
          else {
            boolean changed = perms.setUserPermission(tenantUuid, selectedUserUuid, key, value);
            message = changed ? "User override saved." : "No user override changes.";
          }
        }
      } else if ("user_remove_perm".equals(action)) {
        if (selectedUserUuid.isBlank()) error = "Select a user.";
        else {
          String key = pm_safe(request.getParameter("userPermKeyRemove")).trim();
          if (key.isBlank()) error = "Select a user permission key.";
          else {
            boolean changed = perms.removeUserPermission(tenantUuid, selectedUserUuid, key);
            message = changed ? "User override removed." : "No user override changes.";
          }
        }
      } else if ("user_apply_profile".equals(action)) {
        if (selectedUserUuid.isBlank()) error = "Select a user.";
        else {
          String profileKey = pm_safe(request.getParameter("userProfileKey")).trim();
          PermissionProfile p = perms.getProfile(profileKey);
          if (p == null) error = "Select a valid user profile.";
          else {
            LinkedHashMap<String, String> merged = pm_merge(perms.readUserPermissions(tenantUuid, selectedUserUuid), p.permissions);
            perms.replaceUserPermissions(tenantUuid, selectedUserUuid, merged);
            message = "User profile applied: " + p.label + ".";
          }
        }
      } else if ("user_save_groups".equals(action)) {
        if (selectedUserUuid.isBlank()) error = "Select a user.";
        else {
          String[] selectedGroups = request.getParameterValues("userGroupUuid");
          Set<String> selected = new LinkedHashSet<String>();
          if (selectedGroups != null) {
            for (String g : selectedGroups) {
              String gu = pm_safe(g).trim();
              if (!gu.isBlank()) selected.add(gu);
            }
          }

          List<GroupRec> all = perms.listGroups(tenantUuid);
          boolean changedAny = false;
          for (GroupRec g : all) {
            if (g == null) continue;
            LinkedHashSet<String> members = new LinkedHashSet<String>(g.memberUserUuids);
            boolean shouldContain = selected.contains(pm_safe(g.uuid));
            boolean had = members.contains(selectedUserUuid);
            if (shouldContain && !had) {
              members.add(selectedUserUuid);
              changedAny = perms.setGroupMembers(tenantUuid, g.uuid, members) || changedAny;
            } else if (!shouldContain && had) {
              members.remove(selectedUserUuid);
              changedAny = perms.setGroupMembers(tenantUuid, g.uuid, members) || changedAny;
            }
          }
          message = changedAny ? "User group memberships updated." : "No user group membership changes.";
        }
      }

      // Keep current session permission map in sync when the signed-in user is edited.
      permission_layers.defaultStore().refreshSessionPermissions(session);
    } catch (Exception ex) {
      error = pm_safe(ex.getMessage());
      if (error.isBlank()) error = "Operation failed.";
    }
  }

  List<UserRec> users = new ArrayList<UserRec>();
  List<GroupRec> groups = new ArrayList<GroupRec>();
  Map<String, String> tenantPerms = new LinkedHashMap<String, String>();
  Map<String, String> userPerms = new LinkedHashMap<String, String>();
  List<PermissionDef> catalog = new ArrayList<PermissionDef>(perms.permissionCatalog());
  List<PermissionProfile> profiles = new ArrayList<PermissionProfile>(perms.permissionProfiles());
  List<custom_objects.ObjectRec> customObjects = new ArrayList<custom_objects.ObjectRec>();

  try {
    users = new ArrayList<UserRec>(usersStore.listUsers(tenantUuid));
    users.sort((a,b) -> pm_safe(a.emailAddress).compareToIgnoreCase(pm_safe(b.emailAddress)));

    groups = new ArrayList<GroupRec>(perms.listGroups(tenantUuid));
    groups.sort((a,b) -> pm_safe(a.label).compareToIgnoreCase(pm_safe(b.label)));

    tenantPerms = new LinkedHashMap<String, String>(perms.readTenantPermissions(tenantUuid));
    customObjects = new ArrayList<custom_objects.ObjectRec>(objectStore.listAll(tenantUuid));
    customObjects.sort((a,b) -> Integer.compare(a.sortOrder, b.sortOrder));
  } catch (Exception ex) {
    if (error == null) error = "Unable to load permission data: " + pm_safe(ex.getMessage());
  }

  if (selectedGroupUuid.isBlank() && !groups.isEmpty()) selectedGroupUuid = pm_safe(groups.get(0).uuid);
  if (selectedUserUuid.isBlank() && !users.isEmpty()) selectedUserUuid = pm_safe(users.get(0).uuid);

  GroupRec selectedGroup = pm_findGroup(groups, selectedGroupUuid);
  UserRec selectedUser = pm_findUser(users, selectedUserUuid);
  List<GroupRec> selectedUserGroups = new ArrayList<GroupRec>();
  if (selectedUser != null) {
    try {
      userPerms = new LinkedHashMap<String, String>(perms.readUserPermissions(tenantUuid, selectedUser.uuid));
      selectedUserGroups = new ArrayList<GroupRec>(perms.listGroupsForUser(tenantUuid, selectedUser.uuid));
    } catch (Exception ex) {
      if (error == null) error = "Unable to load selected user overrides: " + pm_safe(ex.getMessage());
    }
  }
%>

<section class="card">
  <div class="section-head">
    <div>
      <h1 style="margin:0;">Permission Layers</h1>
      <div class="meta" style="margin-top:6px;">
        Tenant: <strong><%= pm_esc(tenantLabel.isBlank() ? tenantUuid : tenantLabel) %></strong>
      </div>
      <div class="meta" style="margin-top:6px;">
        Precedence: <code>tenant</code> -> <code>role</code> -> <code>group</code> -> <code>user</code>. Tenant admin is always unlimited.
      </div>
    </div>
  </div>

  <% if (message != null && !message.isBlank()) { %>
    <div class="alert alert-ok" style="margin-top:12px;"><%= pm_esc(message) %></div>
  <% } %>
  <% if (error != null && !error.isBlank()) { %>
    <div class="alert alert-error" style="margin-top:12px;"><%= pm_esc(error) %></div>
  <% } %>

  <div style="margin-top:10px;">
    <a class="btn btn-ghost" href="<%= ctx %>/users_roles.jsp">Open Users &amp; Roles</a>
  </div>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Permission Profiles</h2>
  <div class="table-wrap">
    <table class="table">
      <thead><tr><th>Profile</th><th>Description</th><th>Keys</th></tr></thead>
      <tbody>
      <% for (PermissionProfile p : profiles) { if (p == null) continue; %>
        <tr>
          <td><strong><%= pm_esc(p.label) %></strong><br/><small><code><%= pm_esc(p.key) %></code></small></td>
          <td><%= pm_esc(p.description) %></td>
          <td><%= p.permissions.size() %></td>
        </tr>
      <% } %>
      </tbody>
    </table>
  </div>
</section>

<div class="grid" style="display:grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap:12px; margin-top:12px;">
  <section class="card">
    <h2 style="margin-top:0;">Tenant-Level Permissions</h2>
    <div class="meta">Base permission map for every user in this tenant.</div>

    <div class="table-wrap" style="margin-top:10px;">
      <table class="table">
        <thead><tr><th>Key</th><th>Value</th><th style="width:110px;">Remove</th></tr></thead>
        <tbody>
        <% if (tenantPerms.isEmpty()) { %>
          <tr><td colspan="3" class="muted">No tenant-level overrides set.</td></tr>
        <% } else {
             for (Map.Entry<String, String> e : tenantPerms.entrySet()) {
               String key = pm_safe(e.getKey());
               String val = pm_safe(e.getValue());
        %>
          <tr>
            <td><code><%= pm_esc(key) %></code></td>
            <td><%= pm_esc(val) %></td>
            <td>
              <form method="post" action="<%= ctx %>/permissions_management.jsp" style="margin:0;">
                <input type="hidden" name="csrfToken" value="<%= pm_esc(csrfToken) %>" />
                <input type="hidden" name="action" value="tenant_remove_perm" />
                <input type="hidden" name="tenantPermKeyRemove" value="<%= pm_esc(key) %>" />
                <button class="btn btn-ghost" type="submit">Remove</button>
              </form>
            </td>
          </tr>
        <%   }
           } %>
        </tbody>
      </table>
    </div>

    <form class="form" method="post" action="<%= ctx %>/permissions_management.jsp" style="margin-top:12px;">
      <input type="hidden" name="csrfToken" value="<%= pm_esc(csrfToken) %>" />
      <input type="hidden" name="action" value="tenant_set_perm" />
      <label><span>Permission key</span><input type="text" name="tenantPermKey" placeholder="cases.access" required /></label>
      <label><span>Value</span><input type="text" name="tenantPermValue" placeholder="true or false" required /></label>
      <button class="btn" type="submit">Save Tenant Permission</button>
    </form>

    <form class="form" method="post" action="<%= ctx %>/permissions_management.jsp" style="margin-top:12px;">
      <input type="hidden" name="csrfToken" value="<%= pm_esc(csrfToken) %>" />
      <input type="hidden" name="action" value="tenant_apply_profile" />
      <label>
        <span>Apply profile (merge)</span>
        <select name="tenantProfileKey" required>
          <option value="" selected disabled>Select profile…</option>
          <% for (PermissionProfile p : profiles) { if (p == null) continue; %>
            <option value="<%= pm_esc(p.key) %>"><%= pm_esc(p.label) %></option>
          <% } %>
        </select>
      </label>
      <button class="btn btn-ghost" type="submit">Apply Tenant Profile</button>
    </form>
  </section>

  <section class="card">
    <h2 style="margin-top:0;">Groups</h2>
    <div class="meta">Users can belong to multiple groups. Group permissions override tenant and role.</div>

    <div class="table-wrap" style="margin-top:10px;">
      <table class="table">
        <thead><tr><th>Group</th><th>Enabled</th><th>Members</th><th>Permissions</th></tr></thead>
        <tbody>
        <% if (groups.isEmpty()) { %>
          <tr><td colspan="4" class="muted">No groups defined.</td></tr>
        <% } else { for (GroupRec g : groups) { if (g == null) continue; %>
          <tr>
            <td><a href="<%= ctx %>/permissions_management.jsp?groupUuid=<%= pm_esc(g.uuid) %>&userUuid=<%= pm_esc(selectedUserUuid) %>"><%= pm_esc(g.label) %></a></td>
            <td><%= g.enabled ? "Yes" : "No" %></td>
            <td><%= g.memberUserUuids.size() %></td>
            <td><%= g.permissions.size() %></td>
          </tr>
        <% } } %>
        </tbody>
      </table>
    </div>

    <form class="form" method="post" action="<%= ctx %>/permissions_management.jsp" style="margin-top:12px;">
      <input type="hidden" name="csrfToken" value="<%= pm_esc(csrfToken) %>" />
      <input type="hidden" name="action" value="group_create" />
      <input type="hidden" name="userUuid" value="<%= pm_esc(selectedUserUuid) %>" />
      <label><span>New group label</span><input type="text" name="newGroupLabel" placeholder="Litigation Team" required /></label>
      <div class="field-row">
        <input type="checkbox" id="newGroupEnabled" name="newGroupEnabled" checked />
        <label for="newGroupEnabled"><span style="margin:0; color:var(--text); font-weight:600;">Enabled</span></label>
      </div>
      <button class="btn" type="submit">Create Group</button>
    </form>
  </section>
</div>

<% if (selectedGroup != null) { %>
<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Edit Group: <%= pm_esc(selectedGroup.label) %></h2>
  <div class="grid" style="display:grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap:12px;">
    <article class="card" style="margin:0; padding:12px;">
      <h3 style="margin-top:0;">Group Settings</h3>
      <form class="form" method="post" action="<%= ctx %>/permissions_management.jsp">
        <input type="hidden" name="csrfToken" value="<%= pm_esc(csrfToken) %>" />
        <input type="hidden" name="action" value="group_update" />
        <input type="hidden" name="groupUuid" value="<%= pm_esc(selectedGroup.uuid) %>" />
        <input type="hidden" name="userUuid" value="<%= pm_esc(selectedUserUuid) %>" />
        <label><span>Label</span><input type="text" name="editGroupLabel" value="<%= pm_esc(selectedGroup.label) %>" /></label>
        <div class="field-row">
          <input type="checkbox" id="editGroupEnabled" name="editGroupEnabled" <%= selectedGroup.enabled ? "checked" : "" %> />
          <label for="editGroupEnabled"><span style="margin:0; color:var(--text); font-weight:600;">Enabled</span></label>
        </div>
        <button class="btn" type="submit">Save Group</button>
      </form>
    </article>

    <article class="card" style="margin:0; padding:12px;">
      <h3 style="margin-top:0;">Group Members</h3>
      <form class="form" method="post" action="<%= ctx %>/permissions_management.jsp">
        <input type="hidden" name="csrfToken" value="<%= pm_esc(csrfToken) %>" />
        <input type="hidden" name="action" value="group_save_members" />
        <input type="hidden" name="groupUuid" value="<%= pm_esc(selectedGroup.uuid) %>" />
        <input type="hidden" name="userUuid" value="<%= pm_esc(selectedUserUuid) %>" />
        <div class="table-wrap" style="max-height:240px; overflow:auto;">
          <table class="table">
            <thead><tr><th>Member</th><th>Email</th></tr></thead>
            <tbody>
            <% for (UserRec u : users) { if (u == null) continue; boolean inGroup = selectedGroup.memberUserUuids.contains(pm_safe(u.uuid)); %>
              <tr>
                <td>
                  <input type="checkbox" name="groupMemberUserUuid" value="<%= pm_esc(u.uuid) %>" <%= inGroup ? "checked" : "" %> />
                </td>
                <td><%= pm_esc(u.emailAddress) %></td>
              </tr>
            <% } %>
            </tbody>
          </table>
        </div>
        <button class="btn" type="submit">Save Members</button>
      </form>
    </article>
  </div>

  <div class="grid" style="display:grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap:12px; margin-top:12px;">
    <article class="card" style="margin:0; padding:12px;">
      <h3 style="margin-top:0;">Group Permissions</h3>
      <div class="table-wrap">
        <table class="table">
          <thead><tr><th>Key</th><th>Value</th><th style="width:110px;">Remove</th></tr></thead>
          <tbody>
          <% if (selectedGroup.permissions.isEmpty()) { %>
            <tr><td colspan="3" class="muted">No group permissions set.</td></tr>
          <% } else {
               for (Map.Entry<String, String> e : selectedGroup.permissions.entrySet()) {
                 String key = pm_safe(e.getKey());
                 String val = pm_safe(e.getValue());
          %>
            <tr>
              <td><code><%= pm_esc(key) %></code></td>
              <td><%= pm_esc(val) %></td>
              <td>
                <form method="post" action="<%= ctx %>/permissions_management.jsp" style="margin:0;">
                  <input type="hidden" name="csrfToken" value="<%= pm_esc(csrfToken) %>" />
                  <input type="hidden" name="action" value="group_remove_perm" />
                  <input type="hidden" name="groupUuid" value="<%= pm_esc(selectedGroup.uuid) %>" />
                  <input type="hidden" name="userUuid" value="<%= pm_esc(selectedUserUuid) %>" />
                  <input type="hidden" name="groupPermKeyRemove" value="<%= pm_esc(key) %>" />
                  <button class="btn btn-ghost" type="submit">Remove</button>
                </form>
              </td>
            </tr>
          <%   }
             } %>
          </tbody>
        </table>
      </div>

      <form class="form" method="post" action="<%= ctx %>/permissions_management.jsp" style="margin-top:12px;">
        <input type="hidden" name="csrfToken" value="<%= pm_esc(csrfToken) %>" />
        <input type="hidden" name="action" value="group_set_perm" />
        <input type="hidden" name="groupUuid" value="<%= pm_esc(selectedGroup.uuid) %>" />
        <input type="hidden" name="userUuid" value="<%= pm_esc(selectedUserUuid) %>" />
        <label><span>Permission key</span><input type="text" name="groupPermKey" placeholder="documents.access" required /></label>
        <label><span>Value</span><input type="text" name="groupPermValue" placeholder="true or false" required /></label>
        <button class="btn" type="submit">Save Group Permission</button>
      </form>
    </article>

    <article class="card" style="margin:0; padding:12px;">
      <h3 style="margin-top:0;">Apply Group Profile</h3>
      <form class="form" method="post" action="<%= ctx %>/permissions_management.jsp">
        <input type="hidden" name="csrfToken" value="<%= pm_esc(csrfToken) %>" />
        <input type="hidden" name="action" value="group_apply_profile" />
        <input type="hidden" name="groupUuid" value="<%= pm_esc(selectedGroup.uuid) %>" />
        <input type="hidden" name="userUuid" value="<%= pm_esc(selectedUserUuid) %>" />
        <label>
          <span>Profile (merge)</span>
          <select name="groupProfileKey" required>
            <option value="" selected disabled>Select profile…</option>
            <% for (PermissionProfile p : profiles) { if (p == null) continue; %>
              <option value="<%= pm_esc(p.key) %>"><%= pm_esc(p.label) %></option>
            <% } %>
          </select>
        </label>
        <button class="btn btn-ghost" type="submit">Apply Group Profile</button>
      </form>
    </article>
  </div>
</section>
<% } %>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">User-Level Overrides</h2>
  <div class="meta">User overrides are highest priority and override tenant/role/group permission values.</div>

  <form class="form" method="get" action="<%= ctx %>/permissions_management.jsp" style="margin-top:10px;">
    <input type="hidden" name="groupUuid" value="<%= pm_esc(selectedGroupUuid) %>" />
    <label>
      <span>Select user</span>
      <select name="userUuid" <%= users.isEmpty() ? "disabled" : "" %>>
        <% if (users.isEmpty()) { %>
          <option value="" selected>No users…</option>
        <% } else { for (UserRec u : users) { if (u == null) continue; %>
          <option value="<%= pm_esc(u.uuid) %>" <%= pm_safe(u.uuid).equals(selectedUserUuid) ? "selected" : "" %>><%= pm_esc(u.emailAddress) %></option>
        <% } } %>
      </select>
    </label>
    <button class="btn btn-ghost" type="submit" <%= users.isEmpty() ? "disabled" : "" %>>Load User</button>
  </form>

  <% if (selectedUser != null) { %>
  <div class="grid" style="display:grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap:12px; margin-top:12px;">
    <article class="card" style="margin:0; padding:12px;">
      <h3 style="margin-top:0;">User Group Memberships</h3>
      <form class="form" method="post" action="<%= ctx %>/permissions_management.jsp">
        <input type="hidden" name="csrfToken" value="<%= pm_esc(csrfToken) %>" />
        <input type="hidden" name="action" value="user_save_groups" />
        <input type="hidden" name="groupUuid" value="<%= pm_esc(selectedGroupUuid) %>" />
        <input type="hidden" name="userUuid" value="<%= pm_esc(selectedUser.uuid) %>" />
        <div class="table-wrap" style="max-height:240px; overflow:auto;">
          <table class="table">
            <thead><tr><th>Member</th><th>Group</th><th>Enabled</th></tr></thead>
            <tbody>
            <% for (GroupRec g : groups) { if (g == null) continue; boolean inGroup = g.memberUserUuids.contains(selectedUser.uuid); %>
              <tr>
                <td><input type="checkbox" name="userGroupUuid" value="<%= pm_esc(g.uuid) %>" <%= inGroup ? "checked" : "" %> /></td>
                <td><%= pm_esc(g.label) %></td>
                <td><%= g.enabled ? "Yes" : "No" %></td>
              </tr>
            <% } %>
            </tbody>
          </table>
        </div>
        <button class="btn" type="submit">Save User Groups</button>
      </form>
      <div class="meta" style="margin-top:10px;">
        Current groups:
        <% if (selectedUserGroups.isEmpty()) { %>
          none
        <% } else {
             List<String> labels = new ArrayList<String>();
             for (GroupRec g : selectedUserGroups) labels.add(pm_safe(g.label));
        %>
          <%= pm_esc(String.join(", ", labels)) %>
        <% } %>
      </div>
    </article>

    <article class="card" style="margin:0; padding:12px;">
      <h3 style="margin-top:0;">User Permission Overrides</h3>
      <div class="table-wrap">
        <table class="table">
          <thead><tr><th>Key</th><th>Value</th><th style="width:110px;">Remove</th></tr></thead>
          <tbody>
          <% if (userPerms.isEmpty()) { %>
            <tr><td colspan="3" class="muted">No user-level overrides set.</td></tr>
          <% } else {
               for (Map.Entry<String, String> e : userPerms.entrySet()) {
                 String key = pm_safe(e.getKey());
                 String val = pm_safe(e.getValue());
          %>
            <tr>
              <td><code><%= pm_esc(key) %></code></td>
              <td><%= pm_esc(val) %></td>
              <td>
                <form method="post" action="<%= ctx %>/permissions_management.jsp" style="margin:0;">
                  <input type="hidden" name="csrfToken" value="<%= pm_esc(csrfToken) %>" />
                  <input type="hidden" name="action" value="user_remove_perm" />
                  <input type="hidden" name="groupUuid" value="<%= pm_esc(selectedGroupUuid) %>" />
                  <input type="hidden" name="userUuid" value="<%= pm_esc(selectedUser.uuid) %>" />
                  <input type="hidden" name="userPermKeyRemove" value="<%= pm_esc(key) %>" />
                  <button class="btn btn-ghost" type="submit">Remove</button>
                </form>
              </td>
            </tr>
          <%   }
             } %>
          </tbody>
        </table>
      </div>

      <form class="form" method="post" action="<%= ctx %>/permissions_management.jsp" style="margin-top:12px;">
        <input type="hidden" name="csrfToken" value="<%= pm_esc(csrfToken) %>" />
        <input type="hidden" name="action" value="user_set_perm" />
        <input type="hidden" name="groupUuid" value="<%= pm_esc(selectedGroupUuid) %>" />
        <input type="hidden" name="userUuid" value="<%= pm_esc(selectedUser.uuid) %>" />
        <label><span>Permission key</span><input type="text" name="userPermKey" placeholder="cases.access" required /></label>
        <label><span>Value</span><input type="text" name="userPermValue" placeholder="true or false" required /></label>
        <button class="btn" type="submit">Save User Override</button>
      </form>

      <form class="form" method="post" action="<%= ctx %>/permissions_management.jsp" style="margin-top:12px;">
        <input type="hidden" name="csrfToken" value="<%= pm_esc(csrfToken) %>" />
        <input type="hidden" name="action" value="user_apply_profile" />
        <input type="hidden" name="groupUuid" value="<%= pm_esc(selectedGroupUuid) %>" />
        <input type="hidden" name="userUuid" value="<%= pm_esc(selectedUser.uuid) %>" />
        <label>
          <span>Apply profile (merge)</span>
          <select name="userProfileKey" required>
            <option value="" selected disabled>Select profile…</option>
            <% for (PermissionProfile p : profiles) { if (p == null) continue; %>
              <option value="<%= pm_esc(p.key) %>"><%= pm_esc(p.label) %></option>
            <% } %>
          </select>
        </label>
        <button class="btn btn-ghost" type="submit">Apply User Profile</button>
      </form>
    </article>
  </div>
  <% } %>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Permission Catalog</h2>
  <div class="table-wrap">
    <table class="table">
      <thead><tr><th>Key</th><th>Label</th><th>Category</th><th>Description</th></tr></thead>
      <tbody>
      <% for (PermissionDef d : catalog) { if (d == null) continue; %>
        <tr>
          <td><code><%= pm_esc(d.key) %></code></td>
          <td><%= pm_esc(d.label) %></td>
          <td><%= pm_esc(d.category) %><%= d.adminOnly ? " (admin)" : "" %></td>
          <td><%= pm_esc(d.description) %></td>
        </tr>
      <% } %>
      </tbody>
    </table>
  </div>

  <h3 style="margin-top:16px;">Custom Object Permission Keys</h3>
  <div class="meta">
    Generic keys: <code>custom_objects.records.access</code>, <code>custom_objects.records.create</code>, <code>custom_objects.records.edit</code>,
    <code>custom_objects.records.archive</code>, <code>custom_objects.records.export</code>.
  </div>
  <div class="meta" style="margin-top:6px;">
    Object-specific keys use this pattern: <code>custom_object.&lt;object_key&gt;.&lt;action&gt;</code> where action is <code>access</code>, <code>create</code>, <code>edit</code>, <code>archive</code>, or <code>export</code>.
  </div>
  <div class="table-wrap" style="margin-top:10px;">
    <table class="table">
      <thead><tr><th>Object</th><th>Example Keys</th></tr></thead>
      <tbody>
      <% if (customObjects.isEmpty()) { %>
        <tr><td colspan="2" class="muted">No custom objects defined.</td></tr>
      <% } else { for (custom_objects.ObjectRec ob : customObjects) {
           if (ob == null) continue;
           String key = pm_safe(ob.key);
      %>
        <tr>
          <td><strong><%= pm_esc(pm_safe(ob.label)) %></strong> (<code><%= pm_esc(key) %></code>)</td>
          <td>
            <code><%= pm_esc(permission_layers.customObjectPermissionKey(key, "access")) %></code><br/>
            <code><%= pm_esc(permission_layers.customObjectPermissionKey(key, "create")) %></code><br/>
            <code><%= pm_esc(permission_layers.customObjectPermissionKey(key, "edit")) %></code><br/>
            <code><%= pm_esc(permission_layers.customObjectPermissionKey(key, "archive")) %></code><br/>
            <code><%= pm_esc(permission_layers.customObjectPermissionKey(key, "export")) %></code>
          </td>
        </tr>
      <% } } %>
      </tbody>
    </table>
  </div>
</section>

<jsp:include page="footer.jsp" />
