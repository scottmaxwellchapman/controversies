<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ page import="java.net.URLEncoder" %>
<%@ page import="java.nio.charset.StandardCharsets" %>
<%@ page import="java.util.ArrayList" %>
<%@ page import="java.util.List" %>
<%@ page import="java.util.Locale" %>
<%@ page import="net.familylawandprobate.controversies.matters" %>
<%@ page import="net.familylawandprobate.controversies.matter_conflicts" %>
<%@ page import="net.familylawandprobate.controversies.conflicts_scan_service" %>
<%@ include file="security.jspf" %>
<% if (!require_login()) return; %>
<% request.setAttribute("activeNav", "/case_conflicts.jsp"); %>

<%!
  private static final String S_TENANT_UUID = "tenant.uuid";
  private static final String CSRF_SESSION_KEY = "CSRF_TOKEN";

  private static String safe(String s){ return s == null ? "" : s; }
  private static String esc(String s){ return safe(s).replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;"); }
  private static String enc(String s){ return URLEncoder.encode(safe(s), StandardCharsets.UTF_8); }

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

  private static void logWarn(jakarta.servlet.ServletContext app, String message, Throwable ex) {
    if (app == null) return;
    if (ex == null) app.log("[case_conflicts] " + safe(message));
    else app.log("[case_conflicts] " + safe(message), ex);
  }
%>

<%
String ctx = safe(request.getContextPath());
String tenantUuid = safe((String) session.getAttribute(S_TENANT_UUID)).trim();
if (tenantUuid.isBlank()) { response.sendRedirect(ctx + "/tenant_login.jsp"); return; }
boolean canManageConflicts = net.familylawandprobate.controversies.users_roles.hasPermissionTrue(session, "conflicts.manage");
String csrfToken = csrfForRender(request);

matters matterStore = matters.defaultStore();
matter_conflicts conflictStore = matter_conflicts.defaultStore();
conflicts_scan_service scanService = conflicts_scan_service.defaultService();

try { matterStore.ensure(tenantUuid); } catch (Exception ex) { logWarn(application, "Unable to ensure matter store: " + safe(ex.getMessage()), ex); }

String matterUuid = safe(request.getParameter("matter_uuid")).trim();
if (matterUuid.isBlank()) matterUuid = safe(request.getParameter("case_uuid")).trim();
String q = safe(request.getParameter("q")).trim();

if ("POST".equalsIgnoreCase(request.getMethod())) {
  String action = safe(request.getParameter("action")).trim().toLowerCase(Locale.ROOT);
  matterUuid = safe(request.getParameter("matter_uuid")).trim();
  if (matterUuid.isBlank()) matterUuid = safe(request.getParameter("case_uuid")).trim();
  q = safe(request.getParameter("q")).trim();
  boolean manageAction = "scan_case".equals(action)
      || "scan_all_cases".equals(action)
      || "upsert_entry".equals(action)
      || "delete_entry".equals(action);

  try {
    if (manageAction && !canManageConflicts) {
      throw new SecurityException("conflicts.manage permission is required.");
    }
    if ("scan_case".equals(action)) {
      if (matterUuid.isBlank()) throw new IllegalArgumentException("Select a case first.");
      if (matterStore.getByUuid(tenantUuid, matterUuid) == null) throw new IllegalArgumentException("Case not found.");
      conflicts_scan_service.ScanSummary summary = scanService.scanMatter(tenantUuid, matterUuid, true);
      response.sendRedirect(ctx + "/case_conflicts.jsp?matter_uuid=" + enc(matterUuid) + "&q=" + enc(q) + "&msg=" + enc(summary.message));
      return;
    }
    if ("scan_all_cases".equals(action)) {
      ArrayList<conflicts_scan_service.ScanSummary> rows = scanService.scanAllMatters(tenantUuid);
      int scanned = 0;
      int changed = 0;
      for (conflicts_scan_service.ScanSummary r : rows) {
        if (r == null) continue;
        scanned += Math.max(0, r.versionsScanned);
        changed += Math.max(0, r.entriesChanged);
      }
      String msg = "Scanned all cases. Version scans: " + scanned + ", entry changes: " + changed + ".";
      response.sendRedirect(ctx + "/case_conflicts.jsp?matter_uuid=" + enc(matterUuid) + "&q=" + enc(q) + "&msg=" + enc(msg));
      return;
    }
    if ("upsert_entry".equals(action)) {
      if (matterUuid.isBlank()) throw new IllegalArgumentException("Select a case first.");
      if (matterStore.getByUuid(tenantUuid, matterUuid) == null) throw new IllegalArgumentException("Case not found.");
      matter_conflicts.ConflictEntry in = new matter_conflicts.ConflictEntry();
      in.entityType = safe(request.getParameter("entity_type")).trim();
      in.displayName = safe(request.getParameter("display_name")).trim();
      in.notes = safe(request.getParameter("notes")).trim();
      in.sourceTags = "manual";
      conflictStore.upsertEntry(tenantUuid, matterUuid, in);
      response.sendRedirect(ctx + "/case_conflicts.jsp?matter_uuid=" + enc(matterUuid) + "&q=" + enc(q) + "&msg=" + enc("Conflict entry saved."));
      return;
    }
    if ("delete_entry".equals(action)) {
      if (matterUuid.isBlank()) throw new IllegalArgumentException("Select a case first.");
      if (matterStore.getByUuid(tenantUuid, matterUuid) == null) throw new IllegalArgumentException("Case not found.");
      String entryUuid = safe(request.getParameter("entry_uuid")).trim();
      if (entryUuid.isBlank()) throw new IllegalArgumentException("entry_uuid is required.");
      conflictStore.deleteEntry(tenantUuid, matterUuid, entryUuid);
      response.sendRedirect(ctx + "/case_conflicts.jsp?matter_uuid=" + enc(matterUuid) + "&q=" + enc(q) + "&msg=" + enc("Conflict entry deleted."));
      return;
    }
  } catch (Exception ex) {
    logWarn(application, "Action failed: " + safe(ex.getMessage()), ex);
    response.sendRedirect(ctx + "/case_conflicts.jsp?matter_uuid=" + enc(matterUuid) + "&q=" + enc(q) + "&err=" + enc(safe(ex.getMessage())));
    return;
  }
}

List<matters.MatterRec> allCases = new ArrayList<matters.MatterRec>();
try { allCases = matterStore.listAll(tenantUuid); } catch (Exception ex) { logWarn(application, "Unable to list cases: " + safe(ex.getMessage()), ex); }
List<matters.MatterRec> activeCases = new ArrayList<matters.MatterRec>();
for (matters.MatterRec row : allCases) {
  if (row == null) continue;
  if (!row.trashed && row.enabled) activeCases.add(row);
}
if (matterUuid.isBlank()) {
  if (!activeCases.isEmpty()) matterUuid = safe(activeCases.get(0).uuid);
  else if (!allCases.isEmpty()) matterUuid = safe(allCases.get(0).uuid);
}

matters.MatterRec selected = null;
try { selected = matterStore.getByUuid(tenantUuid, matterUuid); } catch (Exception ignored) {}

matter_conflicts.FileRec file = new matter_conflicts.FileRec();
if (selected != null) {
  try { file = conflictStore.read(tenantUuid, selected.uuid); }
  catch (Exception ex) { logWarn(application, "Unable to read conflicts.xml: " + safe(ex.getMessage()), ex); }
}
ArrayList<matter_conflicts.ConflictEntry> rows = new ArrayList<matter_conflicts.ConflictEntry>(file.entries == null ? List.of() : file.entries);
if (!q.isBlank()) {
  String ql = q.toLowerCase(Locale.ROOT);
  rows.removeIf(r -> r == null
      || !(safe(r.displayName).toLowerCase(Locale.ROOT).contains(ql)
      || safe(r.entityType).toLowerCase(Locale.ROOT).contains(ql)
      || safe(r.notes).toLowerCase(Locale.ROOT).contains(ql)
      || safe(r.sourceTags).toLowerCase(Locale.ROOT).contains(ql)
      || safe(r.linkedContactUuids).toLowerCase(Locale.ROOT).contains(ql)));
}

String message = safe(request.getParameter("msg")).trim();
String error = safe(request.getParameter("err")).trim();
%>

<jsp:include page="header.jsp" />

<section class="card">
  <h1 style="margin:0;">Case Conflicts</h1>
  <div class="meta" style="margin-top:6px;">Browse and manage each case <code>conflicts.xml</code>. Scans include version text, OCR/tesseract companion text, and linked contacts.</div>
  <% if (!message.isBlank()) { %><div class="alert alert-ok" style="margin-top:12px;"><%= esc(message) %></div><% } %>
  <% if (!error.isBlank()) { %><div class="alert alert-error" style="margin-top:12px;"><%= esc(error) %></div><% } %>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin:0;">Select Case</h2>
  <form class="form" method="get" action="<%= ctx %>/case_conflicts.jsp" style="margin-top:10px;">
    <div class="grid" style="display:grid; grid-template-columns: 3fr 2fr auto; gap:12px;">
      <label>
        <span>Case</span>
        <select name="matter_uuid" <%= allCases.isEmpty() ? "disabled" : "" %>>
          <% if (allCases.isEmpty()) { %>
            <option value="">No cases</option>
          <% } else { for (matters.MatterRec c : allCases) {
               if (c == null) continue;
               String id = safe(c.uuid);
          %>
            <option value="<%= esc(id) %>" <%= id.equals(matterUuid) ? "selected" : "" %>>
              <%= esc(safe(c.label)) %><%= c.trashed ? " (archived)" : "" %>
            </option>
          <% } } %>
        </select>
      </label>
      <label>
        <span>Filter Entries</span>
        <input type="text" name="q" value="<%= esc(q) %>" placeholder="Name, entity type, source..." />
      </label>
      <label>
        <span>&nbsp;</span>
        <button class="btn" type="submit">Load</button>
      </label>
    </div>
  </form>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin:0;">Scan</h2>
  <div class="meta" style="margin-top:6px;">Last scanned: <%= esc(safe(file.lastScannedAt).isBlank() ? "never" : file.lastScannedAt) %></div>
  <% if (!canManageConflicts) { %>
    <div class="meta" style="margin-top:10px;">View-only mode. Ask an administrator for <code>conflicts.manage</code> to run scans.</div>
  <% } else { %>
  <div class="actions" style="display:flex; gap:10px; margin-top:10px; flex-wrap:wrap;">
    <form method="post" action="<%= ctx %>/case_conflicts.jsp" style="margin:0;">
      <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
      <input type="hidden" name="action" value="scan_case" />
      <input type="hidden" name="matter_uuid" value="<%= esc(matterUuid) %>" />
      <input type="hidden" name="q" value="<%= esc(q) %>" />
      <button class="btn" type="submit" <%= selected == null ? "disabled" : "" %>>Scan Selected Case</button>
    </form>
    <form method="post" action="<%= ctx %>/case_conflicts.jsp" style="margin:0;">
      <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
      <input type="hidden" name="action" value="scan_all_cases" />
      <input type="hidden" name="matter_uuid" value="<%= esc(matterUuid) %>" />
      <input type="hidden" name="q" value="<%= esc(q) %>" />
      <button class="btn btn-ghost" type="submit">Scan All Cases</button>
    </form>
  </div>
  <% } %>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin:0;">Add Manual Conflict Entry</h2>
  <% if (!canManageConflicts) { %>
    <div class="meta" style="margin-top:10px;">View-only mode. Manual entries require <code>conflicts.manage</code>.</div>
  <% } else { %>
  <form class="form" method="post" action="<%= ctx %>/case_conflicts.jsp" style="margin-top:10px;">
    <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
    <input type="hidden" name="action" value="upsert_entry" />
    <input type="hidden" name="matter_uuid" value="<%= esc(matterUuid) %>" />
    <input type="hidden" name="q" value="<%= esc(q) %>" />
    <div class="grid" style="display:grid; grid-template-columns: 1fr 1fr 2fr auto; gap:12px;">
      <label>
        <span>Entity Type</span>
        <select name="entity_type">
          <option value="person">Person</option>
          <option value="organization">Organization</option>
        </select>
      </label>
      <label>
        <span>Name</span>
        <input type="text" name="display_name" required />
      </label>
      <label>
        <span>Notes</span>
        <input type="text" name="notes" placeholder="Optional notes" />
      </label>
      <label>
        <span>&nbsp;</span>
        <button class="btn" type="submit" <%= selected == null ? "disabled" : "" %>>Save</button>
      </label>
    </div>
  </form>
  <% } %>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin:0;">Entries (<%= rows.size() %>)</h2>
  <div class="meta" style="margin-top:6px;">File updated: <%= esc(safe(file.updatedAt).isBlank() ? "unknown" : file.updatedAt) %></div>
  <div class="table-wrap" style="margin-top:10px;">
    <table class="table">
      <thead>
        <tr>
          <th>Name</th>
          <th>Type</th>
          <th>Source Tags</th>
          <th>Occurrences</th>
          <th>Last Seen</th>
          <th>Linked Contacts</th>
          <th>Notes</th>
          <th></th>
        </tr>
      </thead>
      <tbody>
      <% if (rows.isEmpty()) { %>
        <tr><td colspan="8" class="muted">No conflict entries yet.</td></tr>
      <% } else { for (matter_conflicts.ConflictEntry row : rows) {
           if (row == null) continue;
      %>
        <tr>
          <td><%= esc(safe(row.displayName)) %></td>
          <td><%= esc(safe(row.entityType)) %></td>
          <td><%= esc(safe(row.sourceTags)) %></td>
          <td><%= row.occurrenceCount %></td>
          <td><%= esc(safe(row.lastSeenAt)) %></td>
          <td><%= esc(safe(row.linkedContactUuids)) %></td>
          <td><%= esc(safe(row.notes)) %></td>
          <td>
            <% if (canManageConflicts) { %>
            <form method="post" action="<%= ctx %>/case_conflicts.jsp" style="margin:0;">
              <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
              <input type="hidden" name="action" value="delete_entry" />
              <input type="hidden" name="matter_uuid" value="<%= esc(matterUuid) %>" />
              <input type="hidden" name="entry_uuid" value="<%= esc(safe(row.uuid)) %>" />
              <input type="hidden" name="q" value="<%= esc(q) %>" />
              <button class="btn btn-ghost" type="submit">Delete</button>
            </form>
            <% } else { %>
              <span class="muted">View only</span>
            <% } %>
          </td>
        </tr>
      <% } } %>
      </tbody>
    </table>
  </div>
</section>

<jsp:include page="footer.jsp" />
