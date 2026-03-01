<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" isELIgnored="true" %>
<%@ page import="java.util.List" %>
<%@ page import="net.familylawandprobate.controversies.activity_log" %>
<%@ include file="security.jspf" %>
<% if (!require_login()) return; %>
<% if (!require_permission("tenant_admin")) return; %>
<%!
  private static String safe(String s) { return s == null ? "" : s; }
  private static String esc(String s) {
    return safe(s).replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");
  }
%>
<%
  String tenantUuid = safe((String) session.getAttribute("tenant.uuid"));
  String userUuid = safe((String) session.getAttribute("user.uuid"));
  activity_log logStore = activity_log.defaultStore();
  List<activity_log.LogEntry> entries = logStore.recent(tenantUuid, 200);
  application.log("[log_viewer] viewed by tenant=" + tenantUuid + ", user=" + userUuid + ", count=" + entries.size());
%>
<jsp:include page="header.jsp" />
<section class="card">
  <h1 style="margin:0;">Activity Logs</h1>
  <div class="meta" style="margin-top:6px;">Verbose XML-backed logs for tenant, user, case, and document actions.</div>
</section>
<section class="card" style="margin-top:12px;">
  <div class="table-wrap"><table class="table">
    <thead><tr><th>Time</th><th>Action</th><th>User</th><th>Case</th><th>Document</th><th>Details</th></tr></thead>
    <tbody>
      <% if (entries.isEmpty()) { %>
      <tr><td colspan="6" class="muted">No log entries yet.</td></tr>
      <% } else { for (activity_log.LogEntry e : entries) { if (e == null) continue; %>
      <tr>
        <td><%= esc(e.time) %></td>
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
<jsp:include page="footer.jsp" />
