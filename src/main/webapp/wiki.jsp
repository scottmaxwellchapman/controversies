<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" isELIgnored="true" %>

<%@ page import="java.net.URLEncoder" %>
<%@ page import="java.nio.charset.StandardCharsets" %>
<%@ page import="java.util.ArrayList" %>
<%@ page import="java.util.HashSet" %>
<%@ page import="java.util.LinkedHashMap" %>
<%@ page import="java.util.List" %>
<%@ page import="java.util.Locale" %>
<%@ page import="java.util.Map" %>

<%@ page import="net.familylawandprobate.controversies.tenant_wikis" %>
<%@ page import="net.familylawandprobate.controversies.users_roles" %>

<%@ include file="security.jspf" %>
<%
  if (!require_login()) return;
%>

<%!
  private static final String S_TENANT_UUID = "tenant.uuid";
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

  private static int intOr(String raw, int fallback) {
    try { return Integer.parseInt(safe(raw).trim()); } catch (Exception ignored) { return fallback; }
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

  private static boolean boolParam(String raw) {
    String v = safe(raw).trim().toLowerCase(Locale.ROOT);
    return "1".equals(v) || "true".equals(v) || "yes".equals(v) || "on".equals(v);
  }

  private static int depthFor(Map<String, tenant_wikis.PageRec> byId, tenant_wikis.PageRec rec) {
    if (rec == null || byId == null || byId.isEmpty()) return 0;
    String parent = safe(rec.parentUuid).trim();
    int depth = 0;
    HashSet<String> seen = new HashSet<String>();
    while (!parent.isBlank() && depth < 8) {
      if (seen.contains(parent)) break;
      seen.add(parent);
      tenant_wikis.PageRec p = byId.get(parent);
      if (p == null) break;
      parent = safe(p.parentUuid).trim();
      depth++;
    }
    return depth;
  }

  private static boolean hasAny(jakarta.servlet.http.HttpSession session, String... keys) {
    if (session == null || keys == null) return false;
    for (String key : keys) {
      if (users_roles.hasPermissionTrue(session, key)) return true;
    }
    return false;
  }

  private static boolean pagePermissionGranted(jakarta.servlet.http.HttpSession session, boolean tenantAdmin, tenant_wikis.PageRec page) {
    if (tenantAdmin) return true;
    if (page == null) return false;
    String key = safe(page.permissionKey).trim();
    if (key.isBlank()) return true;
    return users_roles.hasPermissionTrue(session, key);
  }

  private static String statusMessage(jakarta.servlet.http.HttpServletRequest req) {
    if ("1".equals(req.getParameter("created"))) return "Wiki page created.";
    if ("1".equals(req.getParameter("saved"))) return "Revision saved.";
    if ("1".equals(req.getParameter("meta_saved"))) return "Page settings updated.";
    if ("1".equals(req.getParameter("restored"))) return "Revision restored as latest.";
    if ("1".equals(req.getParameter("upload"))) return "Attachment uploaded.";
    return "";
  }
%>

<%
  request.setAttribute("activeNav", "/wiki.jsp");

  String ctx = safe(request.getContextPath());
  String csrfToken = csrfForRender(request);
  String tenantUuid = safe((String)session.getAttribute(S_TENANT_UUID)).trim();
  if (tenantUuid.isBlank()) {
    response.sendRedirect(ctx + "/tenant_login.jsp");
    return;
  }

  boolean tenantAdmin = users_roles.hasPermissionTrue(session, "tenant_admin");
  boolean canView = tenantAdmin || hasAny(session, "wiki.view", "wiki.edit", "wiki.manage");
  boolean canEdit = tenantAdmin || hasAny(session, "wiki.edit", "wiki.manage");
  if (!canView) {
    response.setStatus(403);
%>
<jsp:include page="header.jsp" />
<section class="card">
  <h1 style="margin:0;">Knowledge Wiki</h1>
  <div class="alert alert-error" style="margin-top:12px;">Missing permission. Grant <code>wiki.view</code>, <code>wiki.edit</code>, or <code>wiki.manage</code> in Users &amp; Security.</div>
</section>
<jsp:include page="footer.jsp" />
<%
    return;
  }

  tenant_wikis wikiStore = tenant_wikis.defaultStore();
  try { wikiStore.ensure(tenantUuid); } catch (Exception ignored) {}

  String error = "";
  String showArchivedRaw = safe(request.getParameter("show_archived")).trim();
  boolean showArchived = canEdit && boolParam(showArchivedRaw);
  String pageUuid = safe(request.getParameter("page_uuid")).trim();
  String requestAction = safe(request.getParameter("action")).trim();

  if ("POST".equalsIgnoreCase(request.getMethod())) {
    String action = requestAction;
    try {
      if (!canEdit) throw new IllegalArgumentException("Edit permissions are required.");

      if ("create_page".equals(action)) {
        tenant_wikis.PageRec created = wikiStore.createPage(
          tenantUuid,
          request.getParameter("new_title"),
          request.getParameter("new_slug"),
          request.getParameter("new_parent_uuid"),
          request.getParameter("new_permission_key"),
          safe((String)session.getAttribute(users_roles.S_USER_EMAIL)).trim(),
          request.getParameter("new_initial_summary"),
          request.getParameter("new_initial_html")
        );
        response.sendRedirect(ctx + "/wiki.jsp?page_uuid=" + enc(created == null ? "" : created.uuid) + "&created=1" + (showArchived ? "&show_archived=1" : ""));
        return;
      }

      if ("save_revision".equals(action)) {
        String targetPageUuid = safe(request.getParameter("page_uuid")).trim();
        tenant_wikis.PageRec targetPage = wikiStore.getPage(tenantUuid, targetPageUuid);
        if (!pagePermissionGranted(session, tenantAdmin, targetPage)) {
          throw new IllegalArgumentException("You do not have permission to edit that page.");
        }
        wikiStore.saveRevision(
          tenantUuid,
          targetPageUuid,
          request.getParameter("content_html"),
          request.getParameter("revision_summary"),
          safe((String)session.getAttribute(users_roles.S_USER_EMAIL)).trim(),
          safe((String)session.getAttribute(users_roles.S_USER_UUID)).trim()
        );
        response.sendRedirect(ctx + "/wiki.jsp?page_uuid=" + enc(targetPageUuid) + "&saved=1" + (showArchived ? "&show_archived=1" : ""));
        return;
      }

      if ("update_page_meta".equals(action)) {
        String targetPageUuid = safe(request.getParameter("page_uuid")).trim();
        tenant_wikis.PageRec targetPage = wikiStore.getPage(tenantUuid, targetPageUuid);
        if (!pagePermissionGranted(session, tenantAdmin, targetPage)) {
          throw new IllegalArgumentException("You do not have permission to edit that page.");
        }
        wikiStore.updatePageMeta(
          tenantUuid,
          targetPageUuid,
          request.getParameter("page_title"),
          request.getParameter("page_slug"),
          request.getParameter("parent_uuid"),
          request.getParameter("permission_key"),
          intOr(request.getParameter("nav_order"), 0),
          boolParam(request.getParameter("archived"))
        );
        response.sendRedirect(ctx + "/wiki.jsp?page_uuid=" + enc(targetPageUuid) + "&meta_saved=1" + (showArchived ? "&show_archived=1" : ""));
        return;
      }

      if ("restore_revision".equals(action)) {
        String targetPageUuid = safe(request.getParameter("page_uuid")).trim();
        String restoreRevisionUuid = safe(request.getParameter("restore_revision_uuid")).trim();
        tenant_wikis.PageRec targetPage = wikiStore.getPage(tenantUuid, targetPageUuid);
        if (!pagePermissionGranted(session, tenantAdmin, targetPage)) {
          throw new IllegalArgumentException("You do not have permission to edit that page.");
        }
        wikiStore.restoreRevision(
          tenantUuid,
          targetPageUuid,
          restoreRevisionUuid,
          request.getParameter("restore_summary"),
          safe((String)session.getAttribute(users_roles.S_USER_EMAIL)).trim(),
          safe((String)session.getAttribute(users_roles.S_USER_UUID)).trim()
        );
        response.sendRedirect(ctx + "/wiki.jsp?page_uuid=" + enc(targetPageUuid) + "&restored=1" + (showArchived ? "&show_archived=1" : ""));
        return;
      }
    } catch (Exception ex) {
      error = safe(ex.getMessage());
    }
  }

  List<tenant_wikis.PageRec> list = new ArrayList<tenant_wikis.PageRec>();
  try { list = wikiStore.listPages(tenantUuid, showArchived); } catch (Exception ex) { error = safe(ex.getMessage()); }
  List<tenant_wikis.PageRec> navPages = new ArrayList<tenant_wikis.PageRec>();
  for (tenant_wikis.PageRec p : list) {
    if (p == null) continue;
    if (!pagePermissionGranted(session, tenantAdmin, p)) continue;
    navPages.add(p);
  }

  if (pageUuid.isBlank() && !navPages.isEmpty()) pageUuid = safe(navPages.get(0).uuid);

  tenant_wikis.PageRec selectedPage = null;
  if (!pageUuid.isBlank()) {
    try { selectedPage = wikiStore.getPage(tenantUuid, pageUuid); } catch (Exception ex) { error = safe(ex.getMessage()); }
    if (!pagePermissionGranted(session, tenantAdmin, selectedPage)) {
      selectedPage = null;
      error = "You do not have permission to view that page.";
    }
  }

  Map<String, tenant_wikis.PageRec> pageById = new LinkedHashMap<String, tenant_wikis.PageRec>();
  for (tenant_wikis.PageRec p : navPages) {
    if (p == null) continue;
    pageById.put(safe(p.uuid), p);
  }

  List<tenant_wikis.RevisionRec> revisions = new ArrayList<tenant_wikis.RevisionRec>();
  List<tenant_wikis.AttachmentRec> attachments = new ArrayList<tenant_wikis.AttachmentRec>();
  tenant_wikis.RevisionRec currentRevision = null;
  String currentHtml = "";
  if (selectedPage != null) {
    try {
      revisions = wikiStore.listRevisions(tenantUuid, selectedPage.uuid);
      attachments = wikiStore.listAttachments(tenantUuid, selectedPage.uuid);
      currentRevision = wikiStore.getCurrentRevision(tenantUuid, selectedPage.uuid);
      currentHtml = wikiStore.readCurrentHtml(tenantUuid, selectedPage.uuid);
    } catch (Exception ex) {
      error = safe(ex.getMessage());
    }
  }

  String previewRevisionUuid = safe(request.getParameter("revision_uuid")).trim();
  if (previewRevisionUuid.isBlank() && currentRevision != null) previewRevisionUuid = safe(currentRevision.uuid);
  String previewHtml = currentHtml;
  tenant_wikis.RevisionRec previewRevision = currentRevision;
  if (selectedPage != null && !previewRevisionUuid.isBlank()) {
    try {
      previewRevision = wikiStore.getRevision(tenantUuid, selectedPage.uuid, previewRevisionUuid);
      if (previewRevision != null) previewHtml = wikiStore.readRevisionHtml(tenantUuid, selectedPage.uuid, previewRevision.uuid);
    } catch (Exception ex) {
      error = safe(ex.getMessage());
    }
  }

  String diffFrom = safe(request.getParameter("diff_from")).trim();
  String diffTo = safe(request.getParameter("diff_to")).trim();
  if (diffTo.isBlank() && currentRevision != null) diffTo = safe(currentRevision.uuid);
  if (diffFrom.isBlank() && revisions.size() > 1) diffFrom = safe(revisions.get(1).uuid);
  if (diffFrom.isBlank()) diffFrom = diffTo;

  tenant_wikis.DiffResult diff = null;
  if (selectedPage != null && !diffFrom.isBlank() && !diffTo.isBlank()) {
    try { diff = wikiStore.diffRevisions(tenantUuid, selectedPage.uuid, diffFrom, diffTo); } catch (Exception ex) { error = safe(ex.getMessage()); }
  }

  String statusMessage = statusMessage(request);
  boolean createPaneOpen = navPages.isEmpty()
    || ("POST".equalsIgnoreCase(request.getMethod()) && "create_page".equals(requestAction));
%>

<jsp:include page="header.jsp" />

<style>
  .wiki-layout { display:grid; grid-template-columns: 320px 1fr; gap:12px; }
  .wiki-nav-list { list-style:none; padding:0; margin:0; max-height:520px; overflow:auto; }
  .wiki-nav-link { display:block; padding:6px 8px; border-radius:6px; text-decoration:none; color:inherit; }
  .wiki-nav-link.is-active { background:#f1f5f9; font-weight:600; }
  .wiki-meta-pill { display:inline-block; font-size:12px; border:1px solid #d1d5db; border-radius:999px; padding:2px 8px; margin-left:6px; color:#374151; }
  .wiki-toolbar { display:flex; gap:6px; flex-wrap:wrap; margin-bottom:8px; }
  .wiki-toolbar button {
    border:1px solid var(--border);
    background:var(--input-bg);
    color:var(--text);
    border-radius:6px;
    padding:6px 10px;
    cursor:pointer;
  }
  .wiki-toolbar button:hover { border-color:var(--accent); }
  .wiki-toolbar button:focus-visible { outline:none; box-shadow:var(--focus); }
  .wiki-editor {
    min-height:280px;
    border:1px solid var(--border);
    border-radius:8px;
    padding:10px;
    background:var(--input-bg);
    color:var(--text);
    overflow-wrap:anywhere;
  }
  .wiki-editor:focus { outline:none; border-color:var(--accent); box-shadow:var(--focus); }
  .wiki-render {
    border:1px solid var(--stroke);
    border-radius:8px;
    padding:10px;
    background:var(--surface-2);
    color:var(--text);
  }
  .wiki-editor a, .wiki-render a { color:var(--accent); }
  .wiki-diff-table { width:100%; border-collapse:collapse; font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace; font-size:12px; }
  .wiki-diff-table th, .wiki-diff-table td { border:1px solid #e5e7eb; padding:4px 6px; vertical-align:top; }
  .wiki-diff-add { background:#ecfdf3; }
  .wiki-diff-del { background:#fef2f2; }
  .wiki-diff-ctx { background:#fff; }
  .wiki-line-no { color:#6b7280; width:56px; text-align:right; }
  .wiki-muted { color:#6b7280; font-size:13px; }
  .wiki-expand-pane { border:1px solid #e5e7eb; border-radius:8px; background:#f8fafc; }
  .wiki-expand-pane > summary {
    display:flex;
    justify-content:space-between;
    align-items:center;
    gap:8px;
    cursor:pointer;
    list-style:none;
    padding:10px 12px;
    font-weight:600;
  }
  .wiki-expand-pane > summary::-webkit-details-marker { display:none; }
  .wiki-expand-icon { font-size:12px; color:#6b7280; transition:transform 0.15s ease; }
  .wiki-expand-pane[open] .wiki-expand-icon { transform:rotate(90deg); }
  .wiki-expand-body { border-top:1px solid #e5e7eb; padding:10px 12px 12px; }
  @media (max-width: 980px) {
    .wiki-layout { grid-template-columns: 1fr; }
  }
</style>

<section class="card">
  <h1 style="margin:0;">Knowledge Wiki</h1>
  <div class="wiki-muted" style="margin-top:6px;">
    Tenant-level firm knowledge base with WYSIWYG editing, revisions, diffs, and attachments.
    <% if (canEdit) { %> Permission keys: <code>wiki.view</code>, <code>wiki.edit</code>, <code>wiki.manage</code>.<% } %>
  </div>
  <% if (!statusMessage.isBlank()) { %>
    <div class="alert alert-ok" style="margin-top:10px;"><%= esc(statusMessage) %></div>
  <% } %>
  <% if (!error.isBlank()) { %>
    <div class="alert alert-error" style="margin-top:10px;"><%= esc(error) %></div>
  <% } %>
</section>

<section class="wiki-layout" style="margin-top:12px;">
  <section class="card">
    <div style="display:flex; align-items:center; justify-content:space-between; gap:8px;">
      <h2 style="margin:0;">Navigation</h2>
      <% if (canEdit) { %>
      <a class="btn btn-ghost" href="<%= ctx %>/wiki.jsp?<%= showArchived ? "show_archived=0" : "show_archived=1" %>">
        <%= showArchived ? "Hide Archived" : "Show Archived" %>
      </a>
      <% } %>
    </div>
    <ul class="wiki-nav-list" style="margin-top:10px;">
      <% if (navPages.isEmpty()) { %>
      <li class="wiki-muted">No wiki pages yet.</li>
      <% } else { for (tenant_wikis.PageRec p : navPages) {
           if (p == null) continue;
           int depth = depthFor(pageById, p);
           boolean active = safe(p.uuid).equals(safe(pageUuid));
      %>
      <li style="padding-left:<%= depth * 14 %>px;">
        <a class="wiki-nav-link <%= active ? "is-active" : "" %>" href="<%= ctx %>/wiki.jsp?page_uuid=<%= enc(p.uuid) %><%= showArchived ? "&show_archived=1" : "" %>">
          <%= esc(safe(p.title).isBlank() ? "(Untitled)" : p.title) %>
          <% if (p.archived) { %><span class="wiki-meta-pill">Archived</span><% } %>
        </a>
      </li>
      <% } } %>
    </ul>

    <% if (canEdit) { %>
    <hr style="margin:12px 0;" />
    <details class="wiki-expand-pane" <%= createPaneOpen ? "open" : "" %>>
      <summary>
        <span>Create Page</span>
        <span class="wiki-expand-icon" aria-hidden="true">▶</span>
      </summary>
      <div class="wiki-expand-body">
        <form class="form" method="post" action="<%= ctx %>/wiki.jsp<%= showArchived ? "?show_archived=1" : "" %>">
          <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
          <input type="hidden" name="action" value="create_page" />
          <label><span>Title</span><input type="text" name="new_title" required /></label>
          <label><span>Slug (optional)</span><input type="text" name="new_slug" placeholder="employment-policies" /></label>
          <label>
            <span>Parent Page</span>
            <select name="new_parent_uuid">
              <option value="">(none)</option>
              <% for (tenant_wikis.PageRec p : navPages) { if (p == null) continue; %>
              <option value="<%= esc(p.uuid) %>"><%= esc(p.title) %></option>
              <% } %>
            </select>
          </label>
          <label><span>Permission Key (optional)</span><input type="text" name="new_permission_key" placeholder="wiki.hr" /></label>
          <label><span>Initial Revision Note</span><input type="text" name="new_initial_summary" placeholder="Initial draft." /></label>
          <label><span>Initial Content (HTML optional)</span><textarea name="new_initial_html" rows="4" placeholder="<p>Start drafting here.</p>"></textarea></label>
          <button class="btn" type="submit">Create</button>
        </form>
      </div>
    </details>
    <% } %>
  </section>

  <section>
    <% if (selectedPage == null) { %>
    <section class="card">
      <h2 style="margin:0;">Select a Wiki Page</h2>
      <div class="wiki-muted" style="margin-top:6px;">Choose a page from the left to read or edit content.</div>
    </section>
    <% } else { %>
    <section class="card">
      <div style="display:flex; justify-content:space-between; align-items:flex-start; gap:12px; flex-wrap:wrap;">
        <div>
          <h2 style="margin:0;"><%= esc(selectedPage.title) %></h2>
          <div class="wiki-muted" style="margin-top:4px;">
            Slug: <code><%= esc(selectedPage.slug) %></code>
            <% if (!safe(selectedPage.permissionKey).isBlank()) { %> • Permission: <code><%= esc(selectedPage.permissionKey) %></code><% } %>
            <% if (selectedPage.archived) { %> • <strong>Archived</strong><% } %>
          </div>
        </div>
        <div class="wiki-muted">
          Updated: <code><%= esc(selectedPage.updatedAt) %></code>
        </div>
      </div>

      <% if (canEdit) { %>
      <form class="form" method="post" action="<%= ctx %>/wiki.jsp?page_uuid=<%= enc(selectedPage.uuid) %><%= showArchived ? "&show_archived=1" : "" %>" style="margin-top:12px;">
        <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
        <input type="hidden" name="action" value="update_page_meta" />
        <input type="hidden" name="page_uuid" value="<%= esc(selectedPage.uuid) %>" />
        <div class="grid grid-3">
          <label><span>Title</span><input type="text" name="page_title" value="<%= esc(selectedPage.title) %>" required /></label>
          <label><span>Slug</span><input type="text" name="page_slug" value="<%= esc(selectedPage.slug) %>" required /></label>
          <label><span>Parent</span>
            <select name="parent_uuid">
              <option value="">(none)</option>
              <% for (tenant_wikis.PageRec p : navPages) { if (p == null) continue; if (safe(p.uuid).equals(safe(selectedPage.uuid))) continue; %>
              <option value="<%= esc(p.uuid) %>" <%= safe(p.uuid).equals(safe(selectedPage.parentUuid)) ? "selected" : "" %>><%= esc(p.title) %></option>
              <% } %>
            </select>
          </label>
        </div>
        <div class="grid grid-3">
          <label><span>Permission Key</span><input type="text" name="permission_key" value="<%= esc(selectedPage.permissionKey) %>" placeholder="wiki.hr" /></label>
          <label><span>Sort Order</span><input type="number" name="nav_order" value="<%= selectedPage.navOrder %>" /></label>
          <label><span>&nbsp;</span><label><input type="checkbox" name="archived" value="1" <%= selectedPage.archived ? "checked" : "" %> /> Archived</label></label>
        </div>
        <button class="btn btn-ghost" type="submit">Save Page Settings</button>
      </form>
      <% } %>
    </section>

    <% if (canEdit) { %>
    <section class="card" style="margin-top:12px;">
      <h3 style="margin-top:0;">WYSIWYG Editor</h3>
      <form method="post" id="wiki_editor_form" action="<%= ctx %>/wiki.jsp?page_uuid=<%= enc(selectedPage.uuid) %><%= showArchived ? "&show_archived=1" : "" %>">
        <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
        <input type="hidden" name="action" value="save_revision" />
        <input type="hidden" name="page_uuid" value="<%= esc(selectedPage.uuid) %>" />
        <input type="hidden" name="content_html" id="wiki_content_html" />
        <div class="wiki-toolbar">
          <button type="button" data-cmd="bold"><strong>B</strong></button>
          <button type="button" data-cmd="italic"><em>I</em></button>
          <button type="button" data-cmd="underline"><u>U</u></button>
          <button type="button" data-cmd="formatBlock" data-val="H2">H2</button>
          <button type="button" data-cmd="formatBlock" data-val="H3">H3</button>
          <button type="button" data-cmd="insertUnorderedList">Bullets</button>
          <button type="button" data-cmd="insertOrderedList">Numbers</button>
          <button type="button" data-link="1">Link</button>
          <button type="button" data-cmd="removeFormat">Clear</button>
        </div>
        <div id="wiki_editor_surface" class="wiki-editor" contenteditable="true"><%= currentHtml %></div>
        <div class="grid grid-3" style="margin-top:10px;">
          <label style="grid-column:1 / span 2;"><span>Revision Note</span><input type="text" name="revision_summary" placeholder="What changed in this revision?" /></label>
          <div style="display:flex; align-items:flex-end;">
            <button class="btn" type="submit">Save Revision</button>
          </div>
        </div>
      </form>
      <div class="wiki-muted" style="margin-top:8px;">Each save creates a new immutable revision and updates the current page version.</div>
    </section>
    <% } %>

    <section class="card" style="margin-top:12px;">
      <div style="display:flex; justify-content:space-between; align-items:flex-start; gap:10px; flex-wrap:wrap;">
        <div>
          <h3 style="margin:0;">Rendered Content</h3>
          <div class="wiki-muted">
            <% if (previewRevision != null) { %>
            Revision: <code><%= esc(previewRevision.label) %></code> at <code><%= esc(previewRevision.createdAt) %></code>
            <% } else { %>
            No revision selected.
            <% } %>
          </div>
        </div>
        <div style="display:flex; gap:8px; flex-wrap:wrap;">
          <% if (previewRevision != null) { %>
          <a class="btn btn-ghost" href="<%= ctx %>/wiki_files?action=download_revision&page_uuid=<%= enc(selectedPage.uuid) %>&revision_uuid=<%= enc(previewRevision.uuid) %>&format=html">Download HTML</a>
          <a class="btn btn-ghost" href="<%= ctx %>/wiki_files?action=download_revision&page_uuid=<%= enc(selectedPage.uuid) %>&revision_uuid=<%= enc(previewRevision.uuid) %>&format=txt">Download TXT</a>
          <% } %>
        </div>
      </div>
      <div class="wiki-render" style="margin-top:10px;"><%= previewHtml %></div>
    </section>

    <section class="card" style="margin-top:12px;">
      <h3 style="margin-top:0;">Attachments</h3>
      <div class="table-wrap">
        <table class="table">
          <thead><tr><th>File</th><th>MIME</th><th>Size</th><th>Uploaded</th><th></th></tr></thead>
          <tbody>
            <% if (attachments.isEmpty()) { %>
            <tr><td colspan="5"><span class="wiki-muted">No attachments uploaded.</span></td></tr>
            <% } else { for (tenant_wikis.AttachmentRec a : attachments) { if (a == null) continue; %>
            <tr>
              <td><%= esc(a.fileName) %></td>
              <td><code><%= esc(a.mimeType) %></code></td>
              <td><%= esc(a.fileSizeBytes) %> bytes</td>
              <td><code><%= esc(a.uploadedAt) %></code></td>
              <td><a class="btn btn-ghost" href="<%= ctx %>/wiki_files?action=download_attachment&page_uuid=<%= enc(selectedPage.uuid) %>&attachment_uuid=<%= enc(a.uuid) %>">Download</a></td>
            </tr>
            <% } } %>
          </tbody>
        </table>
      </div>
      <% if (canEdit) { %>
      <form id="wiki_attach_form" style="margin-top:10px;">
        <input type="file" id="wiki_attach_file" name="file" />
        <button class="btn" type="submit">Upload Attachment</button>
        <span class="wiki-muted" id="wiki_attach_status">Max size: <%= tenant_wikis.MAX_ATTACHMENT_BYTES / (1024 * 1024) %>MB</span>
      </form>
      <% } %>
    </section>

    <section class="card" style="margin-top:12px;">
      <h3 style="margin-top:0;">Revision History</h3>
      <div class="table-wrap">
        <table class="table">
          <thead><tr><th>Revision</th><th>Created</th><th>Editor</th><th>Summary</th><th>Actions</th></tr></thead>
          <tbody>
            <% if (revisions.isEmpty()) { %>
            <tr><td colspan="5"><span class="wiki-muted">No revisions yet.</span></td></tr>
            <% } else { for (tenant_wikis.RevisionRec r : revisions) { if (r == null) continue; %>
            <tr>
              <td><strong><%= esc(r.label) %></strong> <% if (r.current) { %><span class="wiki-meta-pill">Current</span><% } %></td>
              <td><code><%= esc(r.createdAt) %></code></td>
              <td><%= esc(safe(r.editorEmail).isBlank() ? "Unknown user" : r.editorEmail) %></td>
              <td><%= esc(r.summary) %></td>
              <td>
                <a class="btn btn-ghost" href="<%= ctx %>/wiki.jsp?page_uuid=<%= enc(selectedPage.uuid) %>&revision_uuid=<%= enc(r.uuid) %><%= showArchived ? "&show_archived=1" : "" %>">View</a>
                <% if (canEdit && !r.current) { %>
                <form method="post" action="<%= ctx %>/wiki.jsp?page_uuid=<%= enc(selectedPage.uuid) %><%= showArchived ? "&show_archived=1" : "" %>" style="display:inline;">
                  <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
                  <input type="hidden" name="action" value="restore_revision" />
                  <input type="hidden" name="page_uuid" value="<%= esc(selectedPage.uuid) %>" />
                  <input type="hidden" name="restore_revision_uuid" value="<%= esc(r.uuid) %>" />
                  <input type="hidden" name="restore_summary" value="Restore <%= esc(r.label) %>" />
                  <button class="btn btn-ghost" type="submit">Restore</button>
                </form>
                <% } %>
              </td>
            </tr>
            <% } } %>
          </tbody>
        </table>
      </div>
    </section>

    <section class="card" style="margin-top:12px;">
      <h3 style="margin-top:0;">Diff Tracking</h3>
      <form class="form" method="get" action="<%= ctx %>/wiki.jsp">
        <input type="hidden" name="page_uuid" value="<%= esc(selectedPage.uuid) %>" />
        <% if (showArchived) { %><input type="hidden" name="show_archived" value="1" /><% } %>
        <div class="grid grid-3">
          <label><span>From Revision</span>
            <select name="diff_from">
              <% for (tenant_wikis.RevisionRec r : revisions) { if (r == null) continue; %>
              <option value="<%= esc(r.uuid) %>" <%= safe(r.uuid).equals(diffFrom) ? "selected" : "" %>><%= esc(r.label) %> • <%= esc(r.createdAt) %></option>
              <% } %>
            </select>
          </label>
          <label><span>To Revision</span>
            <select name="diff_to">
              <% for (tenant_wikis.RevisionRec r : revisions) { if (r == null) continue; %>
              <option value="<%= esc(r.uuid) %>" <%= safe(r.uuid).equals(diffTo) ? "selected" : "" %>><%= esc(r.label) %> • <%= esc(r.createdAt) %></option>
              <% } %>
            </select>
          </label>
          <label><span>&nbsp;</span><button class="btn" type="submit">Compare</button></label>
        </div>
      </form>

      <% if (diff != null) { %>
      <div class="wiki-muted" style="margin-top:10px;">
        Added lines: <strong><%= diff.added %></strong> • Removed lines: <strong><%= diff.removed %></strong>
      </div>
      <div style="overflow:auto; margin-top:8px;">
        <table class="wiki-diff-table">
          <thead><tr><th class="wiki-line-no">Old</th><th class="wiki-line-no">New</th><th>Content</th></tr></thead>
          <tbody>
            <% int shown = 0; for (tenant_wikis.DiffLine line : diff.lines) {
                 if (line == null) continue;
                 shown++;
                 if (shown > 800) break;
                 String cls = line.type == '+' ? "wiki-diff-add" : (line.type == '-' ? "wiki-diff-del" : "wiki-diff-ctx");
            %>
            <tr class="<%= cls %>">
              <td class="wiki-line-no"><%= line.leftLine <= 0 ? "" : line.leftLine %></td>
              <td class="wiki-line-no"><%= line.rightLine <= 0 ? "" : line.rightLine %></td>
              <td><code><%= esc(line.type + " " + safe(line.text)) %></code></td>
            </tr>
            <% } %>
            <% if (diff.lines.size() > 800) { %>
            <tr><td colspan="3" class="wiki-muted">Diff truncated to first 800 lines.</td></tr>
            <% } %>
          </tbody>
        </table>
      </div>
      <% } %>
    </section>
    <% } %>
  </section>
</section>

<script>
(function () {
  var editor = document.getElementById("wiki_editor_surface");
  var form = document.getElementById("wiki_editor_form");
  var hidden = document.getElementById("wiki_content_html");
  if (editor && form && hidden) {
    var toolbar = form.querySelector(".wiki-toolbar");
    if (toolbar) {
      toolbar.addEventListener("click", function (ev) {
        var t = ev.target;
        if (!t) return;
        if (t.tagName !== "BUTTON") t = t.closest("button");
        if (!t) return;
        var cmd = t.getAttribute("data-cmd");
        var val = t.getAttribute("data-val");
        if (t.getAttribute("data-link") === "1") {
          var href = window.prompt("Enter URL (https://...)");
          if (href) document.execCommand("createLink", false, href);
          return;
        }
        if (!cmd) return;
        document.execCommand(cmd, false, val || null);
        editor.focus();
      });
    }
    form.addEventListener("submit", function () {
      hidden.value = editor.innerHTML;
    });
  }

  var attachForm = document.getElementById("wiki_attach_form");
  var attachFile = document.getElementById("wiki_attach_file");
  var attachStatus = document.getElementById("wiki_attach_status");
  if (attachForm && attachFile) {
    attachForm.addEventListener("submit", async function (ev) {
      ev.preventDefault();
      if (!attachFile.files || attachFile.files.length === 0) {
        if (attachStatus) attachStatus.textContent = "Choose a file first.";
        return;
      }
      var fd = new FormData();
      fd.append("action", "upload_attachment");
      fd.append("page_uuid", "<%= esc(selectedPage == null ? "" : selectedPage.uuid) %>");
      fd.append("file", attachFile.files[0]);
      if (attachStatus) attachStatus.textContent = "Uploading...";
      try {
        var resp = await fetch("<%= esc(ctx) %>/wiki_files", {
          method: "POST",
          credentials: "same-origin",
          headers: { "X-CSRF-Token": "<%= esc(csrfToken) %>" },
          body: fd
        });
        var text = await resp.text();
        var json = {};
        try { json = JSON.parse(text); } catch (ignore) {}
        if (!resp.ok || !json.ok) {
          var msg = (json && json.message) ? json.message : ("Upload failed (HTTP " + resp.status + ").");
          if (attachStatus) attachStatus.textContent = msg;
          return;
        }
        window.location.href = "<%= esc(ctx) %>/wiki.jsp?page_uuid=<%= esc(selectedPage == null ? "" : selectedPage.uuid) %>&upload=1<%= showArchived ? "&show_archived=1" : "" %>";
      } catch (err) {
        if (attachStatus) attachStatus.textContent = String(err);
      }
    });
  }
})();
</script>

<jsp:include page="footer.jsp" />
