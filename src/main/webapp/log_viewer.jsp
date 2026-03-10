<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" isELIgnored="true" %>
<%@ page import="java.util.List" %>
<%@ page import="java.util.Map" %>
<%@ page import="net.familylawandprobate.controversies.activity_log" %>
<%@ include file="security.jspf" %>
<% if (!require_login()) return; %>
<%!
  private static String safe(String s) { return s == null ? "" : s; }
  private static String esc(String s) {
    return safe(s).replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");
  }
  private static String displayUser(String raw) {
    String v = safe(raw).trim();
    if (v.isBlank()) return "";
    if (v.contains("@")) return v;
    return "Account user";
  }
  private static String linkedLabel(String raw) {
    return safe(raw).trim().isBlank() ? "" : "Linked";
  }
  private static String redactUuid(String raw) {
    return safe(raw).replaceAll("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\b", "[hidden]");
  }
  private static String detailSummary(activity_log.LogEntry e) {
    if (e == null || e.detailMap == null || e.detailMap.isEmpty()) return safe(e == null ? "" : e.details);
    StringBuilder sb = new StringBuilder(Math.max(64, e.detailMap.size() * 18));
    for (Map.Entry<String, String> row : e.detailMap.entrySet()) {
      if (row == null) continue;
      String k = safe(row.getKey()).trim();
      if (k.isBlank()) continue;
      if (sb.length() > 0) sb.append("; ");
      sb.append(k).append("=").append(safe(row.getValue()));
    }
    return sb.toString();
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
    <thead><tr><th>Time</th><th>Level</th><th>Action</th><th>User</th><th>Case</th><th>Document</th><th>Details</th></tr></thead>
    <tbody>
      <% if (entries.isEmpty()) { %>
      <tr><td colspan="7" class="muted">No log entries yet.</td></tr>
      <% } else { for (activity_log.LogEntry e : entries) { if (e == null) continue; %>
      <tr>
        <td><%= esc(e.time) %></td>
        <td><code><%= esc(e.level) %></code></td>
        <td><code><%= esc(e.action) %></code></td>
        <td><%= esc(displayUser(e.userUuid)) %></td>
        <td><%= esc(linkedLabel(e.caseUuid)) %></td>
        <td><%= esc(linkedLabel(e.documentUuid)) %></td>
        <td><%= esc(redactUuid(detailSummary(e))) %></td>
      </tr>
      <% }} %>
    </tbody>
  </table></div>
</section>
<jsp:include page="footer.jsp" />
