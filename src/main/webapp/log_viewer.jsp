<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" isELIgnored="true" %>
<%@ page import="java.util.List" %>
<%@ page import="net.familylawandprobate.controversies.activity_log" %>
<%@ page import="net.familylawandprobate.controversies.users_roles" %>
<%@ include file="security.jspf" %>
<% if (!require_login()) return; %>
<%!
  private static String safe(String s) { return s == null ? "" : s; }
  private static String esc(String s) {
    return safe(s).replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");
  }
  private static int parseLimit(String raw, int fallback) {
    try {
      int n = Integer.parseInt(safe(raw).trim());
      if (n < 10) return 10;
      if (n > 500) return 500;
      return n;
    } catch (Exception ignored) {
      return fallback;
    }
  }
%>
<%
  String tenantUuid = safe((String) session.getAttribute("tenant.uuid"));
  boolean canViewSystem = users_roles.hasPermissionTrue(session, "tenant_admin");
  String scope = safe(request.getParameter("scope")).trim().toLowerCase();
  if (!"tenant".equals(scope) && !"system".equals(scope) && !"both".equals(scope)) {
    scope = canViewSystem ? "both" : "tenant";
  }
  if (!canViewSystem && !"tenant".equals(scope)) scope = "tenant";
  int limit = parseLimit(request.getParameter("limit"), 200);

  activity_log logStore = activity_log.defaultStore();
  List<activity_log.LogEntry> tenantEntries =
      ("tenant".equals(scope) || "both".equals(scope)) ? logStore.recent(tenantUuid, limit) : java.util.List.of();
  List<activity_log.LogEntry> systemEntries =
      (canViewSystem && ("system".equals(scope) || "both".equals(scope))) ? logStore.recentSystem(limit) : java.util.List.of();
%>
<jsp:include page="header.jsp" />
<section class="card">
  <h1 style="margin:0;">Activity Logs</h1>
  <div class="meta" style="margin-top:6px;">Tenant and system activity logs with level-based diagnostics for operations, plugins, and auth events.</div>
  <form method="get" action="<%= request.getContextPath() %>/log_viewer.jsp" style="margin-top:10px; display:flex; gap:10px; align-items:flex-end; flex-wrap:wrap;">
    <label>
      <span class="muted">Scope</span><br />
      <select name="scope">
        <option value="tenant" <%= "tenant".equals(scope) ? "selected" : "" %>>Tenant</option>
        <% if (canViewSystem) { %>
          <option value="both" <%= "both".equals(scope) ? "selected" : "" %>>Tenant + System</option>
          <option value="system" <%= "system".equals(scope) ? "selected" : "" %>>System only</option>
        <% } %>
      </select>
    </label>
    <label>
      <span class="muted">Limit</span><br />
      <input type="number" min="10" max="500" name="limit" value="<%= limit %>" />
    </label>
    <button class="btn" type="submit">Apply</button>
    <% if (canViewSystem) { %>
      <a class="btn btn-ghost" href="<%= request.getContextPath() %>/plugin_manager.jsp">Plugin Manager</a>
    <% } %>
  </form>
</section>
<% if (!tenantEntries.isEmpty() || "tenant".equals(scope) || "both".equals(scope)) { %>
<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Tenant Activity</h2>
  <div class="table-wrap"><table class="table">
    <thead><tr><th>Time</th><th>Level</th><th>Action</th><th>User</th><th>Case</th><th>Document</th><th>Details</th></tr></thead>
    <tbody>
      <% if (tenantEntries.isEmpty()) { %>
      <tr><td colspan="7" class="muted">No tenant log entries yet.</td></tr>
      <% } else { for (activity_log.LogEntry e : tenantEntries) { if (e == null) continue; %>
      <tr>
        <td><%= esc(e.time) %></td>
        <td><code><%= esc(e.level) %></code></td>
        <td><code><%= esc(e.action) %></code></td>
        <td><%= esc(e.userUuid) %></td>
        <td><%= esc(e.caseUuid) %></td>
        <td><%= esc(e.documentUuid) %></td>
        <td><%= esc(e.details) %></td>
      </tr>
      <% }} %>
    </tbody>
  </table></div>
</section>
<% } %>

<% if (canViewSystem && ("system".equals(scope) || "both".equals(scope))) { %>
<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">System Activity</h2>
  <div class="table-wrap"><table class="table">
    <thead><tr><th>Time</th><th>Level</th><th>Action</th><th>User</th><th>Tenant</th><th>Details</th></tr></thead>
    <tbody>
      <% if (systemEntries.isEmpty()) { %>
      <tr><td colspan="6" class="muted">No system log entries yet.</td></tr>
      <% } else { for (activity_log.LogEntry e : systemEntries) { if (e == null) continue; %>
      <tr>
        <td><%= esc(e.time) %></td>
        <td><code><%= esc(e.level) %></code></td>
        <td><code><%= esc(e.action) %></code></td>
        <td><%= esc(e.userUuid) %></td>
        <td><%= esc(e.tenantUuid) %></td>
        <td><%= esc(e.details) %></td>
      </tr>
      <% }} %>
    </tbody>
  </table></div>
</section>
<% } %>
<jsp:include page="footer.jsp" />
