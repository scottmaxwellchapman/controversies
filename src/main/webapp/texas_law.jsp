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

<%@ page import="net.familylawandprobate.controversies.texas_law_library" %>
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

  private static String safeExternalHref(String raw) {
    String v = safe(raw).trim();
    if (v.isBlank()) return "";
    String lower = v.toLowerCase(Locale.ROOT);
    if (lower.startsWith("https://") || lower.startsWith("http://") || lower.startsWith("mailto:")) return v;
    return "";
  }

  private static int intOr(String raw, int fallback) {
    try {
      return Integer.parseInt(safe(raw).trim());
    } catch (Exception ignored) {
      return fallback;
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

  String q = safe(request.getParameter("q")).trim();
  String searchMode = safe(request.getParameter("search_mode")).trim().toLowerCase(Locale.ROOT);
  if (!"content".equals(searchMode) && !"both".equals(searchMode) && !"filename".equals(searchMode)) searchMode = "filename";
  boolean includeFileNameSearch = "filename".equals(searchMode) || "both".equals(searchMode);
  boolean includeContentSearch = "content".equals(searchMode) || "both".equals(searchMode);
  String extFilter = safe(request.getParameter("ext_filter")).trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
  String pathFilter = safe(request.getParameter("path_filter")).trim();
  String searchQs = "&q=" + enc(q) + "&search_mode=" + enc(searchMode) + "&ext_filter=" + enc(extFilter) + "&path_filter=" + enc(pathFilter);

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

  String viewRel = normalizeRelativePath(request.getParameter("view"));
  int viewPageParam = intOr(request.getParameter("page"), 0);
  int gotoPageParam = intOr(request.getParameter("goto_page"), -1);
  if (gotoPageParam > 0) viewPageParam = gotoPageParam - 1;
  if (viewPageParam < 0) viewPageParam = 0;
  texas_law_library.RenderedPage viewedPage = null;
  Path viewedTarget = null;
  if (!viewRel.isBlank()) {
    viewedTarget = resolveUnderRoot(root, viewRel);
    if (viewedTarget == null || !Files.isRegularFile(viewedTarget)) {
      error = "Viewer file not found.";
      viewRel = "";
    } else if (!texas_law_library.isRenderable(viewedTarget)) {
      error = "Viewer currently supports PDF and DOCX only.";
      viewRel = "";
    } else {
      try {
        viewedPage = texas_law_library.renderPage(viewedTarget, viewPageParam);
      } catch (Exception ex) {
        error = "Unable to render preview image: " + safe(ex.getMessage());
      }
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

  ArrayList<texas_law_library.SearchResult> searchResults = new ArrayList<texas_law_library.SearchResult>();
  if (!q.isBlank()) {
    searchResults.addAll(
      texas_law_library.search(root, q, extFilter, pathFilter, includeFileNameSearch, includeContentSearch, 200)
    );
  }

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
  .tx-law-layout { display:grid; gap:10px; margin-top:10px; }
  .tx-law-head { display:flex; gap:10px; justify-content:space-between; align-items:flex-start; flex-wrap:wrap; }
  .tx-law-actions { display:flex; gap:8px; flex-wrap:wrap; align-items:center; }
  .tx-law-tabs { display:flex; gap:8px; flex-wrap:wrap; }
  .tx-law-tabs .btn.is-active { border-color:var(--accent); box-shadow:var(--focus); }
  .tx-law-status-line { display:flex; flex-wrap:wrap; gap:10px 14px; font-size:13px; color:var(--muted); }
  .tx-law-status-line strong { color:var(--text); }
  .tx-law-search { border:1px solid var(--border); border-radius:10px; background:var(--surface); padding:8px 10px; }
  .tx-law-search summary { cursor:pointer; font-weight:600; color:var(--text); }
  .tx-law-search-form { margin-top:8px; display:grid; gap:8px; }
  .tx-law-search-main { display:grid; gap:8px; grid-template-columns:minmax(0, 1fr) auto auto; align-items:end; }
  .tx-law-search-tools { display:grid; gap:8px; grid-template-columns:repeat(3, minmax(0, 1fr)); }
  .tx-law-path { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size:12px; color:var(--muted); word-break:break-all; }
  .tx-law-grid { display:grid; gap:8px; grid-template-columns:minmax(0,1fr); }
  .tx-law-row { display:grid; gap:10px; grid-template-columns:minmax(0, 1fr) auto; align-items:center; border:1px solid var(--border); border-radius:9px; background:var(--surface); padding:8px 10px; }
  .tx-law-row .name { font-weight:600; color:var(--text); word-break:break-word; }
  .tx-law-row .meta { color:var(--muted); font-size:12px; word-break:break-word; }
  .tx-law-empty { border:1px dashed var(--border); border-radius:10px; background:var(--surface); padding:12px; color:var(--muted); }
  .tx-law-view-nav { display:flex; gap:8px; align-items:center; flex-wrap:wrap; }
  .tx-law-view-meta { color:var(--muted); font-size:12px; }
  .tx-law-view-img-wrap { border:1px solid var(--border); border-radius:10px; background:var(--surface-2); padding:8px; overflow:auto; }
  .tx-law-view-img-wrap img { display:block; width:100%; height:auto; background:#fff; border:1px solid var(--border); border-radius:8px; }
  .tx-law-view-page-jump { display:flex; gap:6px; align-items:center; margin:0; }
  .tx-law-view-page-jump input[type="number"] { width:90px; margin:0; }
  .tx-law-copy-btn { min-width:34px; padding:6px 8px; line-height:1; display:inline-flex; align-items:center; justify-content:center; }
  .tx-law-copy-btn svg { width:15px; height:15px; display:block; fill:currentColor; }
  .tx-law-copy-status { min-height:1em; }
  .tx-law-hidden-text { position:absolute; left:-9999px; width:1px; height:1px; opacity:0; pointer-events:none; }
  .tx-law-nav { margin-top:8px; border:1px solid var(--border); border-radius:10px; background:var(--surface); padding:6px 8px; }
  .tx-law-nav summary { cursor:pointer; font-weight:600; color:var(--text); }
  .tx-law-nav-list { margin-top:8px; display:grid; gap:6px; }
  .tx-law-nav-row { display:grid; gap:8px; grid-template-columns:minmax(0, 1fr) auto; align-items:center; border:1px solid var(--border); border-radius:8px; background:var(--surface-2); padding:6px 8px; }
  .tx-law-nav-kind { display:inline-block; margin-right:6px; padding:1px 6px; border:1px solid var(--border); border-radius:999px; font-size:11px; color:var(--muted); background:var(--surface); text-transform:capitalize; }
  .tx-law-nav-label { font-size:13px; color:var(--text); word-break:break-word; }
  .tx-law-nav-target { color:var(--muted); font-size:11px; word-break:break-word; margin-top:3px; }
  .tx-law-nav-actions { display:flex; gap:6px; align-items:center; flex-wrap:wrap; }
  .tx-law-nav-page { color:var(--muted); font-size:11px; }
  @media (max-width: 700px) {
    .tx-law-search-main { grid-template-columns:minmax(0,1fr); }
    .tx-law-search-tools { grid-template-columns:1fr; }
    .tx-law-row { grid-template-columns:minmax(0,1fr); align-items:start; }
    .tx-law-view-page-jump input[type="number"] { width:70px; }
    .tx-law-nav-row { grid-template-columns:minmax(0,1fr); }
  }
</style>

<div class="tx-law-layout">
  <section class="card">
    <div class="tx-law-head">
      <div>
        <h1 style="margin:0;">Texas Law</h1>
        <div class="meta">Shared material for all tenants. PDF/DOCX files open as PNG pages with navigation.</div>
      </div>
      <div class="tx-law-actions">
        <div class="tx-law-tabs">
          <a class="btn btn-ghost <%= "rules".equals(library) ? "is-active" : "" %>" href="<%= ctx %>/texas_law.jsp?library=rules<%= searchQs %>">Rules</a>
          <a class="btn btn-ghost <%= "codes".equals(library) ? "is-active" : "" %>" href="<%= ctx %>/texas_law.jsp?library=codes<%= searchQs %>">Codes</a>
        </div>
        <form method="post" action="<%= ctx %>/texas_law.jsp?library=<%= enc(library) %>&path=<%= enc(relPath) %><%= searchQs %>" style="margin:0;">
          <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
          <input type="hidden" name="action" value="run_now" />
          <button type="submit" class="btn btn-ghost">Run Refresh Now</button>
        </form>
      </div>
    </div>
    <% if (message != null) { %>
      <div class="alert alert-ok" style="margin-top:10px;"><%= esc(message) %></div>
    <% } %>
    <% if (error != null) { %>
      <div class="alert alert-error" style="margin-top:10px;"><%= esc(error) %></div>
    <% } %>
  </section>

  <section class="card">
    <div class="tx-law-status-line">
      <span>Status: <strong><%= "true".equals(running) ? "Running" : (safe(lastStatus).isBlank() ? "Idle" : esc(lastStatus)) %></strong></span>
      <span>Last Started: <strong><%= esc(fmtInstant(status.getProperty("last_started_at"))) %></strong></span>
      <span>Last Completed: <strong><%= esc(fmtInstant(status.getProperty("last_completed_at"))) %></strong></span>
      <span>Next Run: <strong><%= esc(fmtInstant(nextAt)) %></strong></span>
      <span>Schedule: <strong><%= esc(scheduleTime.isBlank() ? "06:45" : scheduleTime) %></strong> (<%= esc(scheduleZone.isBlank() ? ZoneId.systemDefault().getId() : scheduleZone) %>)</span>
      <span>Rules Exit: <strong><%= esc(safe(status.getProperty("rules_exit_code")).isBlank() ? "N/A" : safe(status.getProperty("rules_exit_code"))) %></strong></span>
      <span>Codes Exit: <strong><%= esc(safe(status.getProperty("codes_exit_code")).isBlank() ? "N/A" : safe(status.getProperty("codes_exit_code"))) %></strong></span>
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
    <details class="tx-law-search" <%= !q.isBlank() || !extFilter.isBlank() || !pathFilter.isBlank() || !"filename".equals(searchMode) ? "open" : "" %>>
      <summary>Search Texas Law (optional)</summary>
      <form class="tx-law-search-form" method="get" action="<%= ctx %>/texas_law.jsp">
        <input type="hidden" name="library" value="<%= esc(library) %>" />
        <input type="hidden" name="path" value="<%= esc(relPath) %>" />
        <div class="tx-law-search-main">
          <label style="margin:0;">
            <span>Search</span>
            <input type="text" name="q" value="<%= esc(q) %>" placeholder="Type search text" />
          </label>
          <button type="submit" class="btn btn-ghost">Search</button>
          <a class="btn btn-ghost" href="<%= ctx %>/texas_law.jsp?library=<%= enc(library) %>&path=<%= enc(relPath) %>">Clear</a>
        </div>
        <div class="tx-law-search-tools">
          <label style="margin:0;">
            <span>Mode</span>
            <select name="search_mode">
              <option value="filename" <%= "filename".equals(searchMode) ? "selected" : "" %>>Filename</option>
              <option value="content" <%= "content".equals(searchMode) ? "selected" : "" %>>Content</option>
              <option value="both" <%= "both".equals(searchMode) ? "selected" : "" %>>Filename + Content</option>
            </select>
          </label>
          <label style="margin:0;">
            <span>File Type</span>
            <select name="ext_filter">
              <option value="" <%= extFilter.isBlank() ? "selected" : "" %>>All</option>
              <option value="pdf" <%= "pdf".equals(extFilter) ? "selected" : "" %>>PDF</option>
              <option value="docx" <%= "docx".equals(extFilter) ? "selected" : "" %>>DOCX</option>
              <option value="txt" <%= "txt".equals(extFilter) ? "selected" : "" %>>TXT</option>
              <option value="json" <%= "json".equals(extFilter) ? "selected" : "" %>>JSON</option>
              <option value="xml" <%= "xml".equals(extFilter) ? "selected" : "" %>>XML</option>
            </select>
          </label>
          <label style="margin:0;">
            <span>Path Contains</span>
            <input type="text" name="path_filter" value="<%= esc(pathFilter) %>" placeholder="Optional folder/file filter" />
          </label>
        </div>
      </form>
    </details>
    <div class="tx-law-path" style="margin-top:8px;">Current: <%= esc(current.toString()) %></div>
  </section>

  <% if (viewedPage != null && viewedTarget != null && !safe(viewedPage.base64Png).isBlank()) {
       String viewedRel = relativize(root, viewedTarget);
       int viewedPageOne = viewedPage.pageIndex + 1;
       String viewedTotalLabel = viewedPage.totalKnown ? String.valueOf(viewedPage.totalPages) : (viewedPage.totalPages + "+");
       String gotoMaxAttr = viewedPage.totalKnown ? ("max=\"" + viewedPage.totalPages + "\"") : "";
       String copyText = safe(viewedPage.pageText);
       ArrayList<texas_law_library.NavigationEntry> navEntries = viewedPage.navigation == null ? new ArrayList<texas_law_library.NavigationEntry>() : viewedPage.navigation;
       int navDisplayLimit = 120;
       int navShown = Math.min(navEntries.size(), navDisplayLimit);
  %>
  <section class="card">
    <div class="section-head">
      <div>
        <h2 style="margin:0;"><%= esc(fileName(viewedTarget)) %></h2>
        <div class="tx-law-view-meta">
          Page <strong><%= viewedPageOne %></strong> of <strong><%= esc(viewedTotalLabel) %></strong>
          <% if (!safe(viewedPage.engine).isBlank()) { %> | Engine: <%= esc(viewedPage.engine) %><% } %>
        </div>
      </div>
      <div class="tx-law-view-nav">
        <% if (viewedPage.hasPrev) { %>
          <a class="btn btn-ghost" href="<%= ctx %>/texas_law.jsp?library=<%= enc(library) %>&path=<%= enc(relPath) %>&view=<%= enc(viewedRel) %>&page=<%= viewedPage.pageIndex - 1 %><%= searchQs %>">Previous</a>
        <% } %>
        <% if (viewedPage.hasNext) { %>
          <a class="btn btn-ghost" href="<%= ctx %>/texas_law.jsp?library=<%= enc(library) %>&path=<%= enc(relPath) %>&view=<%= enc(viewedRel) %>&page=<%= viewedPage.pageIndex + 1 %><%= searchQs %>">Next</a>
        <% } %>
        <form method="get" action="<%= ctx %>/texas_law.jsp" class="tx-law-view-page-jump">
          <input type="hidden" name="library" value="<%= esc(library) %>" />
          <input type="hidden" name="path" value="<%= esc(relPath) %>" />
          <input type="hidden" name="view" value="<%= esc(viewedRel) %>" />
          <input type="hidden" name="q" value="<%= esc(q) %>" />
          <input type="hidden" name="search_mode" value="<%= esc(searchMode) %>" />
          <input type="hidden" name="ext_filter" value="<%= esc(extFilter) %>" />
          <input type="hidden" name="path_filter" value="<%= esc(pathFilter) %>" />
          <label style="margin:0;">
            <span class="tx-law-view-meta">Page</span>
            <input type="number" name="goto_page" min="1" <%= gotoMaxAttr %> value="<%= viewedPageOne %>" />
          </label>
          <button class="btn btn-ghost" type="submit">Go</button>
        </form>
        <button type="button" class="btn btn-ghost tx-law-copy-btn" id="btnCopyPageText" title="Copy plain text of this page" aria-label="Copy plain text of this page">
          <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
            <path d="M16 1H6c-1.66 0-3 1.34-3 3v12h2V4c0-.55.45-1 1-1h10V1zm3 4H10c-1.66 0-3 1.34-3 3v13c0 1.66 1.34 3 3 3h9c1.66 0 3-1.34 3-3V8c0-1.66-1.34-3-3-3zm1 16c0 .55-.45 1-1 1h-9c-.55 0-1-.45-1-1V8c0-.55.45-1 1-1h9c.55 0 1 .45 1 1v13z"/>
          </svg>
        </button>
        <span id="txLawCopyStatus" class="tx-law-view-meta tx-law-copy-status" aria-live="polite"></span>
        <a class="btn btn-ghost" href="<%= ctx %>/texas_law.jsp?library=<%= enc(library) %>&path=<%= enc(relPath) %><%= searchQs %>">Close Viewer</a>
      </div>
    </div>
    <textarea id="txLawHiddenPageText" class="tx-law-hidden-text" aria-hidden="true" tabindex="-1"><%= esc(copyText) %></textarea>
    <% if (!safe(viewedPage.warning).isBlank()) { %>
      <div class="alert alert-warn" style="margin-top:8px;"><%= esc(viewedPage.warning) %></div>
    <% } %>
    <details class="tx-law-nav">
      <summary>Document Navigation (<%= navEntries.size() %>)</summary>
      <div class="tx-law-nav-list">
        <% if (navEntries.isEmpty()) { %>
          <div class="tx-law-empty">No built-in headings, bookmarks, or links were detected for this file.</div>
        <% } else { %>
          <% for (int i = 0; i < navShown; i++) {
               texas_law_library.NavigationEntry n = navEntries.get(i);
               if (n == null) continue;
               String nType = safe(n.type).toLowerCase(Locale.ROOT);
               String nLabel = safe(n.label);
               int nPageOne = n.pageIndex >= 0 ? (n.pageIndex + 1) : -1;
               String nTarget = safe(n.target);
               String externalHref = n.external ? safeExternalHref(nTarget) : "";
          %>
            <div class="tx-law-nav-row">
              <div>
                <div class="tx-law-nav-label">
                  <span class="tx-law-nav-kind"><%= esc(nType.isBlank() ? "entry" : nType) %></span>
                  <%= esc(nLabel.isBlank() ? "Untitled navigation entry" : nLabel) %>
                </div>
                <% if (!nTarget.isBlank()) { %>
                  <div class="tx-law-nav-target"><%= esc(nTarget) %></div>
                <% } %>
              </div>
              <div class="tx-law-nav-actions">
                <% if (nPageOne > 0) { %>
                  <a class="btn btn-ghost" href="<%= ctx %>/texas_law.jsp?library=<%= enc(library) %>&path=<%= enc(relPath) %>&view=<%= enc(viewedRel) %>&page=<%= n.pageIndex %><%= searchQs %>">Go</a>
                  <span class="tx-law-nav-page">P.<%= nPageOne %></span>
                <% } %>
                <% if (!externalHref.isBlank()) { %>
                  <a class="btn btn-ghost" href="<%= esc(externalHref) %>" target="_blank" rel="noopener noreferrer">Open Link</a>
                <% } %>
              </div>
            </div>
          <% } %>
          <% if (navEntries.size() > navDisplayLimit) { %>
            <div class="tx-law-view-meta">Showing first <%= navDisplayLimit %> entries.</div>
          <% } %>
        <% } %>
      </div>
    </details>
    <div class="tx-law-view-img-wrap" style="margin-top:8px;">
      <img src="data:image/png;base64,<%= esc(viewedPage.base64Png) %>" alt="Document page preview" />
    </div>
  </section>
  <% } %>

  <section class="card">
    <div class="section-head">
      <div>
        <h2 style="margin:0;"><%= q.isBlank() ? ("codes".equals(library) ? "Codes Files" : "Rules Files") : "Search Results" %></h2>
        <div class="meta"><%= q.isBlank() ? "Directories first, then files." : ("Query: " + esc(q) + " | Results: " + searchResults.size()) %></div>
      </div>
      <% if (q.isBlank() && !current.equals(root)) {
           Path parent = current.getParent();
           String parentRel = parent == null ? "" : relativize(root, parent);
      %>
        <a class="btn btn-ghost" href="<%= ctx %>/texas_law.jsp?library=<%= enc(library) %>&path=<%= enc(parentRel) %><%= searchQs %>">Parent Directory</a>
      <% } %>
    </div>

    <div class="tx-law-grid" style="margin-top:10px;">
      <% if (q.isBlank()) { %>
        <% if (directories.isEmpty() && files.isEmpty()) { %>
          <div class="tx-law-empty">No files found in this folder yet.</div>
        <% } else { %>
          <% for (Path d : directories) {
               if (d == null) continue;
               String nextRel = relativize(root, d);
          %>
            <div class="tx-law-row">
              <div>
                <div class="name"><%= esc(fileName(d)) %></div>
                <div class="meta">Folder</div>
              </div>
              <a class="btn btn-ghost" href="<%= ctx %>/texas_law.jsp?library=<%= enc(library) %>&path=<%= enc(nextRel) %><%= searchQs %>">Open</a>
            </div>
          <% } %>

          <% for (Path f : files) {
               if (f == null) continue;
               long size = 0L;
               try { size = Files.size(f); } catch (Exception ignored) {}
               String rel = relativize(root, f);
               boolean renderable = texas_law_library.isRenderable(f);
          %>
            <div class="tx-law-row">
              <div>
                <div class="name"><%= esc(fileName(f)) %></div>
                <div class="meta"><%= esc(humanSize(size)) %> | <%= esc(texas_law_library.extension(f).toUpperCase(Locale.ROOT)) %></div>
              </div>
              <% if (renderable) { %>
                <a class="btn btn-ghost" href="<%= ctx %>/texas_law.jsp?library=<%= enc(library) %>&path=<%= enc(relPath) %>&view=<%= enc(rel) %>&page=0<%= searchQs %>">View</a>
              <% } else { %>
                <a class="btn btn-ghost" href="<%= ctx %>/texas_law.jsp?library=<%= enc(library) %>&path=<%= enc(relPath) %>&download=<%= enc(rel) %><%= searchQs %>">Open</a>
              <% } %>
            </div>
          <% } %>
        <% } %>
      <% } else { %>
        <% if (searchResults.isEmpty()) { %>
          <div class="tx-law-empty">No matches found.</div>
        <% } else { %>
          <% for (texas_law_library.SearchResult sr : searchResults) {
               if (sr == null) continue;
               String srRel = safe(sr.relativePath);
               String srParent = "";
               int slash = srRel.lastIndexOf('/');
               if (slash > 0) srParent = srRel.substring(0, slash);
               boolean renderable = "pdf".equals(safe(sr.ext)) || "docx".equals(safe(sr.ext));
          %>
            <div class="tx-law-row">
              <div>
                <div class="name"><%= esc(sr.fileName) %></div>
                <div class="meta"><%= esc(sr.relativePath) %> | <%= esc(humanSize(sr.sizeBytes)) %> | match: <%= esc(sr.matchType) %></div>
                <% if (!safe(sr.snippet).isBlank()) { %>
                  <div class="meta" style="margin-top:4px;"><%= esc(sr.snippet) %></div>
                <% } %>
              </div>
              <% if (renderable) { %>
                <a class="btn btn-ghost" href="<%= ctx %>/texas_law.jsp?library=<%= enc(library) %>&path=<%= enc(srParent) %>&view=<%= enc(srRel) %>&page=0<%= searchQs %>">View</a>
              <% } else { %>
                <a class="btn btn-ghost" href="<%= ctx %>/texas_law.jsp?library=<%= enc(library) %>&path=<%= enc(srParent) %>&download=<%= enc(srRel) %><%= searchQs %>">Open</a>
              <% } %>
            </div>
          <% } %>
        <% } %>
      <% } %>
    </div>
  </section>
</div>

<script>
(() => {
  const copyBtn = document.getElementById("btnCopyPageText");
  if (!copyBtn) return;
  const textEl = document.getElementById("txLawHiddenPageText");
  const statusEl = document.getElementById("txLawCopyStatus");

  function setStatus(message, isError) {
    if (!statusEl) return;
    statusEl.textContent = message || "";
    statusEl.style.color = isError ? "var(--danger, #b91c1c)" : "var(--muted)";
  }

  async function copyPageText() {
    const value = (textEl && typeof textEl.value === "string") ? textEl.value : "";
    if (!value.trim()) {
      setStatus("No page text available.", true);
      return;
    }
    try {
      if (navigator.clipboard && window.isSecureContext) {
        await navigator.clipboard.writeText(value);
      } else {
        const temp = document.createElement("textarea");
        temp.value = value;
        temp.setAttribute("readonly", "readonly");
        temp.style.position = "absolute";
        temp.style.left = "-9999px";
        document.body.appendChild(temp);
        temp.select();
        const ok = document.execCommand("copy");
        document.body.removeChild(temp);
        if (!ok) throw new Error("copy command failed");
      }
      setStatus("Page text copied.", false);
    } catch (err) {
      setStatus("Clipboard copy is blocked by browser permissions.", true);
    }
  }

  copyBtn.addEventListener("click", () => { void copyPageText(); });
})();
</script>

<jsp:include page="footer.jsp" />
