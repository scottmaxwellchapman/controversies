<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>

<%@ page import="java.nio.charset.StandardCharsets" %>
<%@ page import="java.nio.file.DirectoryStream" %>
<%@ page import="java.nio.file.Files" %>
<%@ page import="java.nio.file.Path" %>
<%@ page import="java.nio.file.Paths" %>
<%@ page import="java.nio.file.StandardOpenOption" %>
<%@ page import="java.time.Instant" %>
<%@ page import="java.time.ZoneId" %>
<%@ page import="java.time.format.DateTimeFormatter" %>
<%@ page import="java.util.ArrayList" %>
<%@ page import="java.util.Collections" %>
<%@ page import="java.util.Comparator" %>
<%@ page import="java.util.Locale" %>

<%@ include file="security.jspf" %>
<%
  if (!require_login()) return;
%>

<%!
  private static final String S_TENANT_UUID = "tenant.uuid";
  private static final String S_TENANT_LABEL = "tenant.label";
  private static final String CSRF_SESSION_KEY = "CSRF_TOKEN";

  private static String safe(String s) { return s == null ? "" : s; }

  private static String esc(String s) {
    if (s == null) return "";
    return s.replace("&","&amp;")
            .replace("<","&lt;")
            .replace(">","&gt;")
            .replace("\"","&quot;")
            .replace("'","&#39;");
  }

  private static String fmtBytes(long size) {
    long v = Math.max(0L, size);
    if (v < 1024L) return String.valueOf(v) + " B";
    double kb = v / 1024.0;
    if (kb < 1024.0) return String.format(Locale.ROOT, "%.1f KB", kb);
    double mb = kb / 1024.0;
    if (mb < 1024.0) return String.format(Locale.ROOT, "%.1f MB", mb);
    double gb = mb / 1024.0;
    return String.format(Locale.ROOT, "%.2f GB", gb);
  }

  private static String readUtf8(Path p) throws Exception {
    if (p == null || !Files.exists(p)) return "";
    byte[] raw = Files.readAllBytes(p);
    return new String(raw, StandardCharsets.UTF_8);
  }

  private static void writeUtf8(Path p, String text) throws Exception {
    if (p == null) return;
    Path parent = p.getParent();
    if (parent != null) Files.createDirectories(parent);
    String body = safe(text).replace("\r\n", "\n");
    Files.write(
      p,
      body.getBytes(StandardCharsets.UTF_8),
      StandardOpenOption.CREATE,
      StandardOpenOption.TRUNCATE_EXISTING,
      StandardOpenOption.WRITE
    );
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
%>

<%
  String ctx = request.getContextPath();
  if (ctx == null) ctx = "";

  String tenantUuid = safe((String)session.getAttribute(S_TENANT_UUID)).trim();
  String tenantLabel = safe((String)session.getAttribute(S_TENANT_LABEL)).trim();
  if (tenantUuid.isBlank()) {
    response.sendRedirect(ctx + "/tenant_login.jsp");
    return;
  }

  String csrfToken = csrfForRender(request);
  String message = null;
  String error = null;

  Path pluginsDir = Paths.get("data", "plugins").toAbsolutePath().normalize();
  Path stateDir = pluginsDir.resolve("state");
  Path configPath = pluginsDir.resolve("plugins.properties");

  try {
    Files.createDirectories(pluginsDir);
    Files.createDirectories(stateDir);
  } catch (Exception ex) {
    error = "Unable to prepare plugin directories: " + safe(ex.getMessage());
  }

  if ("POST".equalsIgnoreCase(request.getMethod())) {
    String action = safe(request.getParameter("action")).trim();
    if ("save_plugins_config".equalsIgnoreCase(action)) {
      try {
        String body = safe(request.getParameter("plugins_config"));
        if (body.length() > 200000) {
          throw new IllegalArgumentException("plugins.properties content is too large.");
        }
        writeUtf8(configPath, body);
        message = "Plugin configuration saved.";
      } catch (Exception ex) {
        error = "Unable to save plugin configuration: " + safe(ex.getMessage());
      }
    } else if ("clear_plugin_state".equalsIgnoreCase(action)) {
      int deleted = 0;
      try {
        Files.createDirectories(stateDir);
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(stateDir)) {
          for (Path p : ds) {
            if (p == null) continue;
            if (!Files.isRegularFile(p)) continue;
            Files.deleteIfExists(p);
            deleted++;
          }
        }
        message = "Plugin runtime state cleared (" + deleted + " file" + (deleted == 1 ? "" : "s") + ").";
      } catch (Exception ex) {
        error = "Unable to clear plugin runtime state: " + safe(ex.getMessage());
      }
    }
  }

  String pluginsConfigText = "";
  try {
    pluginsConfigText = readUtf8(configPath);
  } catch (Exception ex) {
    if (error == null) error = "Unable to read plugin configuration: " + safe(ex.getMessage());
  }

  ArrayList<Path> pluginFiles = new ArrayList<Path>();
  try {
    if (Files.exists(pluginsDir)) {
      try (DirectoryStream<Path> ds = Files.newDirectoryStream(pluginsDir)) {
        for (Path p : ds) {
          if (p == null || !Files.isRegularFile(p)) continue;
          String name = safe(p.getFileName() == null ? "" : p.getFileName().toString());
          if (name.isBlank()) continue;
          pluginFiles.add(p);
        }
      }
    }
  } catch (Exception ex) {
    if (error == null) error = "Unable to read plugin directory: " + safe(ex.getMessage());
  }
  Collections.sort(pluginFiles, new Comparator<Path>() {
    public int compare(Path a, Path b) {
      String an = a == null || a.getFileName() == null ? "" : safe(a.getFileName().toString()).toLowerCase(Locale.ROOT);
      String bn = b == null || b.getFileName() == null ? "" : safe(b.getFileName().toString()).toLowerCase(Locale.ROOT);
      return an.compareTo(bn);
    }
  });

  DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(ZoneId.systemDefault());
%>

<jsp:include page="header.jsp" />

<section class="card">
  <div class="section-head">
    <div>
      <h1 style="margin:0;">Plugin Manager</h1>
      <div class="meta">Manage plugin runtime configuration and inspect plugin artifacts in this deployment.</div>
      <% if (!tenantLabel.isBlank()) { %>
        <div class="meta" style="margin-top:4px;">Tenant context: <strong><%= esc(tenantLabel) %></strong></div>
      <% } %>
    </div>
  </div>

  <% if (message != null) { %>
    <div class="alert alert-ok" style="margin-top:12px;"><%= esc(message) %></div>
  <% } %>
  <% if (error != null) { %>
    <div class="alert alert-error" style="margin-top:12px;"><%= esc(error) %></div>
  <% } %>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Runtime Paths</h2>
  <div class="meta">Plugin directory: <code><%= esc(pluginsDir.toString()) %></code></div>
  <div class="meta">State directory: <code><%= esc(stateDir.toString()) %></code></div>
  <div class="meta">Config file: <code><%= esc(configPath.toString()) %></code></div>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Plugin Config</h2>
  <div class="meta" style="margin-bottom:10px;">
    Edit <code>plugins.properties</code>. Restart the server after changes to reload plugin descriptors and activation flags.
  </div>
  <form method="post" action="<%= ctx %>/plugin_manager.jsp">
    <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
    <input type="hidden" name="action" value="save_plugins_config" />
    <textarea
      name="plugins_config"
      spellcheck="false"
      style="width:100%; min-height:240px; font-family:ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;"><%= esc(pluginsConfigText) %></textarea>
    <div class="actions" style="display:flex; gap:10px; margin-top:10px;">
      <button type="submit" class="btn">Save Plugin Config</button>
    </div>
  </form>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Plugin Artifacts</h2>
  <div class="meta" style="margin-bottom:10px;">
    Drop plugin JAR files into the plugin directory. They are loaded on startup according to descriptor and config settings.
  </div>

  <% if (pluginFiles.isEmpty()) { %>
    <div class="meta">No plugin artifact files found in <code><%= esc(pluginsDir.toString()) %></code>.</div>
  <% } else { %>
    <div class="table-wrap">
      <table class="table">
        <thead>
          <tr>
            <th>Filename</th>
            <th style="width:140px;">Size</th>
            <th style="width:260px;">Modified</th>
          </tr>
        </thead>
        <tbody>
          <% for (Path p : pluginFiles) {
               if (p == null) continue;
               String name = safe(p.getFileName() == null ? "" : p.getFileName().toString());
               long size = 0L;
               String modified = "";
               try { size = Files.size(p); } catch (Exception ignored) {}
               try { modified = dtf.format(Instant.ofEpochMilli(Files.getLastModifiedTime(p).toMillis())); } catch (Exception ignored) {}
          %>
            <tr>
              <td><code><%= esc(name) %></code></td>
              <td><%= esc(fmtBytes(size)) %></td>
              <td><%= esc(modified) %></td>
            </tr>
          <% } %>
        </tbody>
      </table>
    </div>
  <% } %>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">State Maintenance</h2>
  <div class="meta" style="margin-bottom:10px;">
    Clears files under <code><%= esc(stateDir.toString()) %></code>. Use this when a plugin cache/state reset is required.
  </div>
  <form method="post" action="<%= ctx %>/plugin_manager.jsp">
    <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
    <input type="hidden" name="action" value="clear_plugin_state" />
    <button type="submit" class="btn btn-ghost">Clear Plugin State</button>
  </form>
</section>

<jsp:include page="footer.jsp" />
