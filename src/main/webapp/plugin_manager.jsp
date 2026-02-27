<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" isELIgnored="true" %>
<%@ page import="java.nio.charset.StandardCharsets" %>
<%@ page import="java.nio.file.Files" %>
<%@ page import="java.nio.file.Path" %>
<%@ page import="java.util.LinkedHashMap" %>
<%@ page import="java.util.List" %>
<%@ page import="java.util.Set" %>
<%@ page import="net.familylawandprobate.controversies.activity_log" %>
<%@ page import="net.familylawandprobate.controversies.users_roles" %>
<%@ page import="net.familylawandprobate.controversies.plugins.PluginDescriptor" %>
<%@ page import="net.familylawandprobate.controversies.plugins.PluginManager" %>
<%@ include file="security.jspf" %>
<%
  if (!require_permission("tenant_admin")) return;
%>

<%!
  private static String pm_safe(String s) { return s == null ? "" : s; }
  private static String pm_esc(String s) {
    return pm_safe(s).replace("&","&amp;")
            .replace("<","&lt;")
            .replace(">","&gt;")
            .replace("\"","&quot;")
            .replace("'","&#39;");
  }
  private static String pm_csrfForRender(jakarta.servlet.http.HttpServletRequest req) {
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
  private static String pm_joinSet(Set<String> vals) {
    if (vals == null || vals.isEmpty()) return "(none)";
    StringBuilder sb = new StringBuilder();
    int i = 0;
    for (String v : vals) {
      String x = pm_safe(v).trim();
      if (x.isBlank()) continue;
      if (i++ > 0) sb.append(", ");
      sb.append(x);
    }
    String out = sb.toString().trim();
    return out.isBlank() ? "(none)" : out;
  }
%>

<%
  String ctx = request.getContextPath();
  if (ctx == null) ctx = "";
  String tenantUuid = pm_safe((String) session.getAttribute("tenant.uuid")).trim();
  String userUuid = pm_safe((String) session.getAttribute(users_roles.S_USER_UUID)).trim();
  String csrfToken = pm_csrfForRender(request);

  PluginManager pm = PluginManager.defaultManager();
  activity_log logs = activity_log.defaultStore();

  String message = null;
  String error = null;

  if ("POST".equalsIgnoreCase(request.getMethod())) {
    String action = pm_safe(request.getParameter("action")).trim();
    if ("reload_plugins".equalsIgnoreCase(action)) {
      try {
        pm.reload();
        message = "Plugin manager reloaded successfully.";
        LinkedHashMap<String, String> details = new LinkedHashMap<String, String>();
        details.put("active_plugins", String.valueOf(pm.activePluginCount()));
        details.put("descriptor_count", String.valueOf(pm.descriptors().size()));
        logs.logInfo("plugin.manager.reload.manual", tenantUuid, userUuid, "", "", details);
      } catch (Exception e) {
        error = "Plugin reload failed: " + e.getMessage();
        LinkedHashMap<String, String> details = new LinkedHashMap<String, String>();
        details.put("error", pm_safe(e.getMessage()));
        logs.logError("plugin.manager.reload.failed", tenantUuid, userUuid, "", "", details);
      }
    } else {
      error = "Unsupported action.";
    }
  }

  boolean started = pm.isStarted();
  int activeCount = pm.activePluginCount();
  List<PluginDescriptor> descriptors = pm.descriptors();
  List<Path> pluginJars = pm.pluginJarPaths();
  Set<String> enabledIds = pm.enabledIdsConfigured();
  Set<String> disabledIds = pm.disabledIdsConfigured();
  Path pluginsDir = pm.pluginsDir();
  Path stateDir = pm.pluginStateDir();
  Path configPath = pm.configPath();

  boolean configExists = false;
  String configText = "";
  try {
    configExists = Files.isRegularFile(configPath);
    if (configExists) {
      configText = Files.readString(configPath, StandardCharsets.UTF_8);
      if (configText.length() > 6000) {
        configText = configText.substring(0, 6000) + "\n... (truncated)";
      }
    }
  } catch (Exception e) {
    error = (error == null) ? ("Unable to read plugins.properties: " + e.getMessage()) : error;
  }
%>

<jsp:include page="header.jsp" />

<section class="card">
  <h1 style="margin:0;">Plugin Manager</h1>
  <div class="meta" style="margin-top:6px;">Admin controls and diagnostics for plugin discovery, loading, and runtime lifecycle.</div>
</section>

<% if (message != null) { %>
  <section class="card" style="margin-top:12px;"><div class="alert alert-ok"><%= pm_esc(message) %></div></section>
<% } %>
<% if (error != null) { %>
  <section class="card" style="margin-top:12px;"><div class="alert alert-error"><%= pm_esc(error) %></div></section>
<% } %>

<section class="card" style="margin-top:12px;">
  <div class="section-head">
    <h2 style="margin:0;">Runtime Status</h2>
  </div>
  <div class="table-wrap">
    <table class="table">
      <tbody>
        <tr><th>Manager State</th><td><strong><%= started ? "Started" : "Stopped" %></strong></td></tr>
        <tr><th>Active Plugins</th><td><%= activeCount %></td></tr>
        <tr><th>Discovered Descriptors</th><td><%= descriptors.size() %></td></tr>
        <tr><th>Plugin JAR Count</th><td><%= pluginJars.size() %></td></tr>
        <tr><th>Plugins Directory</th><td><code><%= pm_esc(String.valueOf(pluginsDir)) %></code></td></tr>
        <tr><th>Plugin State Directory</th><td><code><%= pm_esc(String.valueOf(stateDir)) %></code></td></tr>
        <tr><th>Configuration File</th><td><code><%= pm_esc(String.valueOf(configPath)) %></code></td></tr>
        <tr><th>Enabled IDs (allowlist)</th><td><%= pm_esc(pm_joinSet(enabledIds)) %></td></tr>
        <tr><th>Disabled IDs (denylist)</th><td><%= pm_esc(pm_joinSet(disabledIds)) %></td></tr>
      </tbody>
    </table>
  </div>
  <form method="post" action="<%= ctx %>/plugin_manager.jsp" style="margin-top:12px;">
    <input type="hidden" name="action" value="reload_plugins" />
    <input type="hidden" name="csrfToken" value="<%= pm_esc(csrfToken) %>" />
    <button type="submit" class="btn">Reload Plugins</button>
    <a href="<%= ctx %>/plugin_manager.jsp" class="btn btn-ghost">Refresh</a>
    <a href="<%= ctx %>/log_viewer.jsp?scope=system" class="btn btn-ghost">System Logs</a>
  </form>
</section>

<section class="card" style="margin-top:12px;">
  <div class="section-head">
    <h2 style="margin:0;">plugins.properties</h2>
  </div>
  <% if (!configExists) { %>
    <div class="muted">No config file found. Defaults are applied.</div>
    <pre style="margin-top:10px; white-space:pre-wrap;"># data/plugins/plugins.properties
# enabled.ids=plugin.one,plugin.two
# disabled.ids=plugin.legacy
</pre>
  <% } else { %>
    <pre style="margin-top:10px; white-space:pre-wrap;"><%= pm_esc(configText) %></pre>
  <% } %>
</section>

<section class="card" style="margin-top:12px;">
  <div class="section-head">
    <h2 style="margin:0;">Plugin JAR Inventory</h2>
  </div>
  <% if (pluginJars.isEmpty()) { %>
    <div class="muted">No external plugin JARs discovered in <code><%= pm_esc(String.valueOf(pluginsDir)) %></code>.</div>
  <% } else { %>
    <div class="table-wrap">
      <table class="table">
        <thead><tr><th>#</th><th>Jar Path</th></tr></thead>
        <tbody>
          <% for (int i = 0; i < pluginJars.size(); i++) { Path p = pluginJars.get(i); %>
            <tr>
              <td><%= (i + 1) %></td>
              <td><code><%= pm_esc(String.valueOf(p)) %></code></td>
            </tr>
          <% } %>
        </tbody>
      </table>
    </div>
  <% } %>
</section>

<section class="card" style="margin-top:12px;">
  <div class="section-head">
    <h2 style="margin:0;">Discovered Plugins</h2>
  </div>
  <div class="table-wrap">
    <table class="table">
      <thead>
        <tr>
          <th>Active</th>
          <th>Plugin ID</th>
          <th>Display Name</th>
          <th>Version</th>
          <th>Source</th>
          <th>Error / Notes</th>
        </tr>
      </thead>
      <tbody>
        <% if (descriptors.isEmpty()) { %>
          <tr><td colspan="6" class="muted">No plugin descriptors found.</td></tr>
        <% } else { %>
          <% for (PluginDescriptor d : descriptors) { if (d == null) continue; %>
            <tr>
              <td><%= d.active() ? "yes" : "no" %></td>
              <td><code><%= pm_esc(d.id()) %></code></td>
              <td><%= pm_esc(d.displayName()) %></td>
              <td><%= pm_esc(d.version()) %></td>
              <td><code><%= pm_esc(d.source()) %></code></td>
              <td><%= pm_esc(d.error()) %></td>
            </tr>
          <% } %>
        <% } %>
      </tbody>
    </table>
  </div>
</section>

<jsp:include page="footer.jsp" />
