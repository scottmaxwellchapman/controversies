<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ page import="java.io.InputStream" %>
<%@ page import="java.nio.file.Files" %>
<%@ page import="java.nio.file.Path" %>
<%@ page import="java.nio.file.Paths" %>
<%@ page import="java.util.LinkedHashMap" %>
<%@ page import="java.util.Properties" %>
<%@ page import="net.familylawandprobate.controversies.bpm_aging_alarm_scheduler" %>
<%@ page import="net.familylawandprobate.controversies.clio_matter_sync_scheduler" %>
<%@ page import="net.familylawandprobate.controversies.external_storage_data_sync_scheduler" %>
<%@ page import="net.familylawandprobate.controversies.office365_contact_sync_scheduler" %>
<%@ page import="net.familylawandprobate.controversies.self_upgrade_scheduler" %>
<%@ page import="net.familylawandprobate.controversies.tenant_settings" %>
<%@ include file="security.jspf" %>
<% if (!require_login()) return; %>
<% if (!require_permission("tenant_settings.manage")) return; %>
<%!
  private static String safe(String s) { return s == null ? "" : s; }
  private static String csrfForRender(jakarta.servlet.http.HttpServletRequest req) {
    Object a = req == null ? null : req.getAttribute("csrfToken");
    if (a instanceof String s && !safe(s).trim().isBlank()) return s;
    try {
      jakarta.servlet.http.HttpSession sess = req == null ? null : req.getSession(false);
      Object t = sess == null ? null : sess.getAttribute("CSRF_TOKEN");
      if (t instanceof String cs && !safe(cs).trim().isBlank()) return cs;
    } catch (Exception ignored) {}
    return "";
  }
  private static String esc(String s) {
    return safe(s)
      .replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")
      .replace("'", "&#39;");
  }
%>
<%
String ctx = safe(request.getContextPath());
request.setAttribute("activeNav", "/scheduler_control.jsp");
String csrfToken = csrfForRender(request);
String tenantUuid = safe((String) session.getAttribute("tenant.uuid")).trim();
if (tenantUuid.isBlank()) {
  response.sendRedirect(ctx + "/tenant_login.jsp");
  return;
}

String action = safe(request.getParameter("action")).trim();
String message = "";
String error = "";

try {
  if ("start_all".equalsIgnoreCase(action)) {
    clio_matter_sync_scheduler.defaultService().startIfNeeded();
    office365_contact_sync_scheduler.defaultService().startIfNeeded();
    external_storage_data_sync_scheduler.defaultService().startIfNeeded();
    bpm_aging_alarm_scheduler.defaultService().startIfNeeded();
    self_upgrade_scheduler.defaultService().startIfNeeded();
    message = "All scheduler services have been initialized (if not already running).";
  } else if ("start_clio".equalsIgnoreCase(action)) {
    clio_matter_sync_scheduler.defaultService().startIfNeeded();
    message = "Clio scheduler initialized.";
  } else if ("start_office365".equalsIgnoreCase(action)) {
    office365_contact_sync_scheduler.defaultService().startIfNeeded();
    message = "Office 365 scheduler initialized.";
  } else if ("start_external_storage".equalsIgnoreCase(action)) {
    external_storage_data_sync_scheduler.defaultService().startIfNeeded();
    message = "External storage scheduler initialized.";
  } else if ("start_bpm_aging".equalsIgnoreCase(action)) {
    bpm_aging_alarm_scheduler.defaultService().startIfNeeded();
    message = "BPM aging alarm scheduler initialized.";
  } else if ("start_self_upgrade".equalsIgnoreCase(action)) {
    self_upgrade_scheduler.defaultService().startIfNeeded();
    message = "Self-upgrade scheduler initialized.";
  }
} catch (Exception ex) {
  error = safe(ex.getMessage());
  if (error.isBlank()) error = "Scheduler action failed.";
}

LinkedHashMap<String, String> cfg = tenant_settings.defaultStore().read(tenantUuid);
String[] keys = new String[] {
  "clio_enabled",
  "clio_matters_sync_interval_minutes",
  "office365_contacts_sync_enabled",
  "office365_contacts_sync_interval_minutes",
  "office365_contacts_sync_sources_json",
  "bpm_aging_alarm_enabled",
  "bpm_aging_alarm_interval_minutes",
  "bpm_aging_alarm_running_stale_minutes",
  "bpm_aging_alarm_review_stale_minutes",
  "bpm_aging_alarm_max_issues_per_scan",
  "self_upgrade_enabled",
  "storage_backend"
};

Path selfUpgradeStatusPath = Paths.get("data", "shared", "self_upgrade", "self_upgrade_status.properties").toAbsolutePath().normalize();
Properties selfUpgradeStatus = new Properties();
boolean hasSelfUpgradeStatus = false;
if (Files.exists(selfUpgradeStatusPath)) {
  try (InputStream in = Files.newInputStream(selfUpgradeStatusPath)) {
    selfUpgradeStatus.load(in);
    hasSelfUpgradeStatus = true;
  } catch (Exception ignored) {}
}
%>
<jsp:include page="header.jsp" />

<section class="card">
  <h1 class="u-m-0">Scheduler Control</h1>
  <div class="meta u-mt-6">Administrative controls for background scheduler services that run under the hood.</div>
</section>

<section class="card section-gap-12">
  <form method="post" action="<%= ctx %>/scheduler_control.jsp" class="u-flex u-gap-8 u-items-center u-wrap">
    <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
    <button class="btn" name="action" value="start_all" type="submit">Start All</button>
    <button class="btn btn-ghost" name="action" value="start_clio" type="submit">Start Clio</button>
    <button class="btn btn-ghost" name="action" value="start_office365" type="submit">Start Office 365</button>
    <button class="btn btn-ghost" name="action" value="start_external_storage" type="submit">Start External Storage</button>
    <button class="btn btn-ghost" name="action" value="start_bpm_aging" type="submit">Start BPM Aging Alarm</button>
    <button class="btn btn-ghost" name="action" value="start_self_upgrade" type="submit">Start Self Upgrade</button>
  </form>
  <% if (!message.isBlank()) { %>
    <div class="alert alert-ok u-mt-8"><%= esc(message) %></div>
  <% } %>
  <% if (!error.isBlank()) { %>
    <div class="alert alert-error u-mt-8"><%= esc(error) %></div>
  <% } %>
</section>

<section class="card section-gap-12">
  <h2 class="u-m-0">Current Tenant Scheduler Settings</h2>
  <div class="table-wrap table-wrap-tight u-mt-8">
    <table class="table">
      <thead><tr><th>Setting</th><th>Value</th></tr></thead>
      <tbody>
      <% for (String key : keys) { %>
        <tr>
          <td><code><%= esc(safe(key)) %></code></td>
          <td><%= esc(safe(cfg.get(key))) %></td>
        </tr>
      <% } %>
      </tbody>
    </table>
  </div>
</section>

<section class="card section-gap-12">
  <h2 class="u-m-0">Self-Upgrade Status Snapshot</h2>
  <div class="meta u-mt-6"><code><%= esc(selfUpgradeStatusPath.toString()) %></code></div>
  <% if (!hasSelfUpgradeStatus) { %>
    <div class="meta u-mt-8">No self-upgrade status file found yet.</div>
  <% } else { %>
    <div class="table-wrap table-wrap-tight u-mt-8">
      <table class="table">
        <thead><tr><th>Key</th><th>Value</th></tr></thead>
        <tbody>
        <% for (String key : selfUpgradeStatus.stringPropertyNames()) { %>
          <tr>
            <td><code><%= esc(safe(key)) %></code></td>
            <td><%= esc(safe(selfUpgradeStatus.getProperty(key))) %></td>
          </tr>
        <% } %>
        </tbody>
      </table>
    </div>
  <% } %>
</section>

<jsp:include page="footer.jsp" />
