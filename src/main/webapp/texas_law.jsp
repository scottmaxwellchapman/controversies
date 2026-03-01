<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" isELIgnored="true" %>
<%@ page import="java.net.URLEncoder" %>
<%@ page import="java.nio.charset.StandardCharsets" %>
<%@ page import="java.nio.file.DirectoryStream" %>
<%@ page import="java.nio.file.Files" %>
<%@ page import="java.nio.file.Path" %>
<%@ page import="java.time.Instant" %>
<%@ page import="java.time.ZoneId" %>
<%@ page import="java.time.format.DateTimeFormatter" %>
<%@ page import="java.util.ArrayList" %>
<%@ page import="java.util.Comparator" %>
<%@ page import="java.util.List" %>
<%@ page import="java.util.Locale" %>
<%@ page import="java.util.Properties" %>

<%@ page import="net.familylawandprobate.controversies.texas_law_sync" %>

<%@ include file="security.jspf" %>
<%
  if (!require_login()) return;
%>

<%!
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

  private static String enc(String s) {
    return URLEncoder.encode(safe(s), StandardCharsets.UTF_8);
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

  private static String normalizeRelativePath(String raw) {
    String s = safe(raw).trim().replace('\\', '/');
    while (s.startsWith("/")) s = s.substring(1);
    if (s.equals(".") || s.equals("./")) return "";
    if (s.contains("..")) return "";
    return s;
  }

  private static Path resolveUnderRoot(Path root, String rel) {
    if (root == null) return null;
    Path normalizedRoot = root.toAbsolutePath().normalize();
    Path candidate = normalizedRoot;
    String r = normalizeRelativePath(rel);
    if (!r.isBlank()) candidate = normalizedRoot.resolve(r).normalize();
    if (!candidate.startsWith(normalizedRoot)) return normalizedRoot;
    return candidate;
  }

  private static String relativize(Path root, Path p) {
    if (root == null || p == null) return "";
    try {
      String out = root.toAbsolutePath().normalize().relativize(p.toAbsolutePath().normalize()).toString();
      return out.replace('\\', '/');
    } catch (Exception ignored) {
      return "";
    }
  }

  private static String fileName(Path p) {
    if (p == null || p.getFileName() == null) return "";
    return safe(p.getFileName().toString());
  }

  private static String humanSize(long bytes) {
    long b = Math.max(0L, bytes);
    if (b < 1024L) return b + " B";
    double kb = b / 1024.0d;
    if (kb < 1024.0d) return String.format(Locale.US, "%.1f KB", kb);
    double mb = kb / 1024.0d;
    if (mb < 1024.0d) return String.format(Locale.US, "%.1f MB", mb);
    double gb = mb / 1024.0d;
    return String.format(Locale.US, "%.2f GB", gb);
  }

  private static String contentTypeFor(Path p, jakarta.servlet.ServletContext application) {
    String name = fileName(p).toLowerCase(Locale.ROOT);
    String guessed = null;
    try { guessed = application.getMimeType(name); } catch (Exception ignored) {}
    if (guessed != null && !guessed.isBlank()) return guessed;
    if (name.endsWith(".pdf")) return "application/pdf";
    if (name.endsWith(".txt")) return "text/plain; charset=UTF-8";
    if (name.endsWith(".json")) return "application/json";
    if (name.endsWith(".xml")) return "application/xml";
    if (name.endsWith(".doc")) return "application/msword";
    if (name.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    return "application/octet-stream";
  }

  private static String headerFileName(Path p) {
    String name = fileName(p);
    if (name.isBlank()) return "download.bin";
    return name.replace("\"", "_").replace("\r", "").replace("\n", "");
  }

  private static String fmtInstant(String raw) {
    String v = safe(raw).trim();
    if (v.isBlank()) return "N/A";
    try {
      Instant ins = Instant.parse(v);
      return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
              .withZone(ZoneId.systemDefault())
              .format(ins);
    } catch (Exception ignored) {
      return v;
    }
  }
%>

<%
  String ctx = request.getContextPath();
  if (ctx == null) ctx = "";
  request.setAttribute("activeNav", "/texas_law.jsp");

  String csrfToken = csrfForRender(request);
  texas_law_sync sync = texas_law_sync.defaultService();

  String message = null;
  String error = null;
  if ("POST".equalsIgnoreCase(request.getMethod())) {
    String action = safe(request.getParameter("action")).trim();
    if ("run_now".equalsIgnoreCase(action)) {
      boolean started = sync.triggerManualRun();
      if (started) message = "Texas law refresh started in the background.";
      else error = "A refresh is already running.";
    }
  }

  String library = safe(request.getParameter("library")).trim().toLowerCase(Locale.ROOT);
  if (!"codes".equals(library)) library = "rules";

  Path root = "codes".equals(library) ? texas_law_sync.codesDataDir() : texas_law_sync.rulesDataDir();
  root = root.toAbsolutePath().normalize();
  try { Files.createDirectories(root); } catch (Exception ignored) {}

  String relPath = normalizeRelativePath(request.getParameter("path"));
  Path current = resolveUnderRoot(root, relPath);
  if (current == null || !Files.isDirectory(current)) {
    current = root;
    relPath = "";
  } else {
    relPath = relativize(root, current);
  }

  String downloadRel = normalizeRelativePath(request.getParameter("download"));
  if (!downloadRel.isBlank()) {
    Path downloadTarget = resolveUnderRoot(root, downloadRel);
    if (downloadTarget != null && Files.isRegularFile(downloadTarget)) {
      response.setContentType(contentTypeFor(downloadTarget, application));
      response.setHeader("Content-Disposition", "inline; filename=\"" + headerFileName(downloadTarget) + "\"");
      response.setHeader("Cache-Control", "private, max-age=120");
      try (java.io.InputStream in = Files.newInputStream(downloadTarget);
           java.io.OutputStream os = response.getOutputStream()) {
        in.transferTo(os);
      }
      return;
    } else {
      response.setStatus(404);
      response.setContentType("text/plain; charset=UTF-8");
      response.getWriter().write("File not found.");
      return;
    }
  }

  ArrayList<Path> directories = new ArrayList<Path>();
  ArrayList<Path> files = new ArrayList<Path>();
  try (DirectoryStream<Path> ds = Files.newDirectoryStream(current)) {
    for (Path p : ds) {
      if (p == null) continue;
      if (Files.isDirectory(p)) directories.add(p);
      else if (Files.isRegularFile(p)) files.add(p);
    }
  } catch (Exception ex) {
    error = "Unable to read directory: " + safe(ex.getMessage());
  }
  directories.sort(Comparator.comparing(a -> fileName(a).toLowerCase(Locale.ROOT)));
  files.sort(Comparator.comparing(a -> fileName(a).toLowerCase(Locale.ROOT)));

  Properties status = sync.readStatusSnapshot();
  String running = safe(status.getProperty("running")).trim().toLowerCase(Locale.ROOT);
  String lastStatus = safe(status.getProperty("last_status"));
  String lastError = safe(status.getProperty("last_error"));
  String nextAt = safe(status.getProperty("next_scheduled_at"));
  String scheduleZone = safe(status.getProperty("schedule_zone"));
  String scheduleTime = safe(status.getProperty("schedule_time_local"));
%>

<jsp:include page="header.jsp" />

<style>
  .tx-law-layout { display:grid; gap:12px; margin-top:10px; }
  .tx-law-stats { display:grid; gap:10px; grid-template-columns:repeat(4, minmax(0,1fr)); }
  .tx-law-stat { border:1px solid var(--border); border-radius:12px; background:var(--surface); padding:10px; }
  .tx-law-stat .label { color:var(--muted); font-size:12px; }
  .tx-law-stat .value { color:var(--text); font-weight:600; margin-top:4px; word-break:break-word; }
  .tx-law-chooser { display:flex; gap:8px; flex-wrap:wrap; }
  .tx-law-chooser .btn.is-active { border-color:var(--accent); box-shadow:var(--focus); }
  .tx-law-path { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size:12px; color:var(--muted); word-break:break-all; }
  .tx-law-grid { display:grid; gap:12px; grid-template-columns:minmax(0,1fr); }
  .tx-law-row { display:grid; gap:10px; grid-template-columns:minmax(0, 1fr) auto auto; align-items:center; border:1px solid var(--border); border-radius:10px; background:var(--surface); padding:9px 10px; }
  .tx-law-row .name { font-weight:600; color:var(--text); word-break:break-word; }
  .tx-law-row .meta { color:var(--muted); font-size:12px; }
  .tx-law-empty { border:1px dashed var(--border); border-radius:10px; background:var(--surface); padding:12px; color:var(--muted); }
  @media (max-width: 1080px) { .tx-law-stats { grid-template-columns:repeat(2, minmax(0,1fr)); } }
  @media (max-width: 700px) {
    .tx-law-stats { grid-template-columns:1fr; }
    .tx-law-row { grid-template-columns:minmax(0,1fr); }
  }
</style>

<div class="tx-law-layout">
  <section class="card">
    <div class="section-head">
      <div>
        <h1 style="margin:0;">Texas Law</h1>
        <div class="meta">Shared legal material browser for all tenants. Source downloads refresh daily at <strong>6:45 AM</strong>.</div>
      </div>
      <form method="post" action="<%= ctx %>/texas_law.jsp?library=<%= enc(library) %>&path=<%= enc(relPath) %>" style="margin:0;">
        <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
        <input type="hidden" name="action" value="run_now" />
        <button type="submit" class="btn btn-ghost">Run Refresh Now</button>
      </form>
    </div>
    <% if (message != null) { %>
      <div class="alert alert-ok" style="margin-top:10px;"><%= esc(message) %></div>
    <% } %>
    <% if (error != null) { %>
      <div class="alert alert-error" style="margin-top:10px;"><%= esc(error) %></div>
    <% } %>
  </section>

  <section class="card">
    <div class="tx-law-stats">
      <div class="tx-law-stat">
        <div class="label">Sync Status</div>
        <div class="value"><%= "true".equals(running) ? "Running" : (safe(lastStatus).isBlank() ? "Idle" : esc(lastStatus)) %></div>
      </div>
      <div class="tx-law-stat">
        <div class="label">Last Started</div>
        <div class="value"><%= esc(fmtInstant(status.getProperty("last_started_at"))) %></div>
      </div>
      <div class="tx-law-stat">
        <div class="label">Last Completed</div>
        <div class="value"><%= esc(fmtInstant(status.getProperty("last_completed_at"))) %></div>
      </div>
      <div class="tx-law-stat">
        <div class="label">Next Scheduled</div>
        <div class="value"><%= esc(fmtInstant(nextAt)) %></div>
      </div>
    </div>
    <div class="meta" style="margin-top:10px;">
      Scheduler: <strong><%= esc(scheduleTime.isBlank() ? "06:45" : scheduleTime) %></strong> (<%= esc(scheduleZone.isBlank() ? ZoneId.systemDefault().getId() : scheduleZone) %>).
      Rules exit code: <code><%= esc(safe(status.getProperty("rules_exit_code")).isBlank() ? "N/A" : safe(status.getProperty("rules_exit_code"))) %></code>.
      Codes exit code: <code><%= esc(safe(status.getProperty("codes_exit_code")).isBlank() ? "N/A" : safe(status.getProperty("codes_exit_code"))) %></code>.
    </div>
    <% if (!lastError.isBlank()) { %>
      <div class="alert alert-warn" style="margin-top:10px;">Last error: <%= esc(lastError) %></div>
    <% } %>
    <div class="meta" style="margin-top:8px;">
      Rules root: <code><%= esc(texas_law_sync.rulesDataDir().toString()) %></code><br />
      Codes root: <code><%= esc(texas_law_sync.codesDataDir().toString()) %></code>
    </div>
  </section>

  <section class="card">
    <div class="tx-law-chooser">
      <a class="btn btn-ghost <%= "rules".equals(library) ? "is-active" : "" %>" href="<%= ctx %>/texas_law.jsp?library=rules">Rules And Standards</a>
      <a class="btn btn-ghost <%= "codes".equals(library) ? "is-active" : "" %>" href="<%= ctx %>/texas_law.jsp?library=codes">Codes And Statutes</a>
    </div>
    <div class="tx-law-path" style="margin-top:8px;">
      Root: <%= esc(root.toString()) %><br />
      Current: <%= esc(current.toString()) %>
    </div>
  </section>

  <section class="card">
    <div class="section-head">
      <div>
        <h2 style="margin:0;"><%= "codes".equals(library) ? "Codes And Statutes Files" : "Rules And Standards Files" %></h2>
        <div class="meta">Directories first, then files. Click files to open/download.</div>
      </div>
      <% if (!current.equals(root)) {
           Path parent = current.getParent();
           String parentRel = parent == null ? "" : relativize(root, parent);
      %>
        <a class="btn btn-ghost" href="<%= ctx %>/texas_law.jsp?library=<%= enc(library) %>&path=<%= enc(parentRel) %>">Parent Directory</a>
      <% } %>
    </div>

    <div class="tx-law-grid" style="margin-top:10px;">
      <% if (directories.isEmpty() && files.isEmpty()) { %>
        <div class="tx-law-empty">No files found in this folder yet.</div>
      <% } else { %>
        <% for (Path d : directories) {
             if (d == null) continue;
             String nextRel = relativize(root, d);
        %>
          <div class="tx-law-row">
            <div>
              <div class="name">Folder: <%= esc(fileName(d)) %></div>
              <div class="meta">Folder</div>
            </div>
            <div class="meta">-</div>
            <a class="btn btn-ghost" href="<%= ctx %>/texas_law.jsp?library=<%= enc(library) %>&path=<%= enc(nextRel) %>">Open</a>
          </div>
        <% } %>

        <% for (Path f : files) {
             if (f == null) continue;
             long size = 0L;
             try { size = Files.size(f); } catch (Exception ignored) {}
             String dlRel = relativize(root, f);
        %>
          <div class="tx-law-row">
            <div>
              <div class="name"><%= esc(fileName(f)) %></div>
              <div class="meta"><%= esc(humanSize(size)) %></div>
            </div>
            <div class="meta"><%= esc(humanSize(size)) %></div>
            <a class="btn btn-ghost" href="<%= ctx %>/texas_law.jsp?library=<%= enc(library) %>&path=<%= enc(relPath) %>&download=<%= enc(dlRel) %>">Open</a>
          </div>
        <% } %>
      <% } %>
    </div>
  </section>
</div>

<jsp:include page="footer.jsp" />
