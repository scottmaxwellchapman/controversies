<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>

<%@ page import="java.net.URLEncoder" %>
<%@ page import="java.nio.charset.StandardCharsets" %>
<%@ page import="java.util.ArrayList" %>
<%@ page import="java.util.HashMap" %>
<%@ page import="java.util.LinkedHashMap" %>
<%@ page import="java.util.List" %>
<%@ page import="java.util.Locale" %>
<%@ page import="java.util.Map" %>

<%@ page import="net.familylawandprobate.controversies.matters" %>
<%@ page import="net.familylawandprobate.controversies.case_fields" %>

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

  private static String nonBlank(String s, String fallback) {
    String v = safe(s).trim();
    return v.isBlank() ? safe(fallback) : v;
  }
%>

<%
  String ctx = request.getContextPath();
  if (ctx == null) ctx = "";

  String tenantUuid = safe((String)session.getAttribute(S_TENANT_UUID)).trim();
  if (tenantUuid.isBlank()) {
    response.sendRedirect(ctx + "/tenant_login.jsp");
    return;
  }

  matters matterStore = matters.defaultStore();
  case_fields fieldsStore = case_fields.defaultStore();
  try { matterStore.ensure(tenantUuid); } catch (Exception ignored) {}

  String csrfToken = csrfForRender(request);

  String message = null;
  String error = null;

  if ("POST".equalsIgnoreCase(request.getMethod())) {
    String action = safe(request.getParameter("action")).trim();

    if ("create_case".equalsIgnoreCase(action)) {
      String label = safe(request.getParameter("label")).trim();
      String cause = safe(request.getParameter("cause_docket_number")).trim();
      String county = safe(request.getParameter("county")).trim();

      try {
        matterStore.create(tenantUuid, label, "", "", "", "", "", cause, county);
        response.sendRedirect(ctx + "/cases.jsp?created=1");
        return;
      } catch (Exception ex) {
        error = "Unable to create case: " + safe(ex.getMessage());
      }
    }

    if ("save_case".equalsIgnoreCase(action)) {
      String caseUuid = safe(request.getParameter("uuid")).trim();
      String label = safe(request.getParameter("label")).trim();
      String cause = safe(request.getParameter("cause_docket_number")).trim();
      String county = safe(request.getParameter("county")).trim();

      try {
        matters.MatterRec current = matterStore.getByUuid(tenantUuid, caseUuid);
        if (current == null) throw new IllegalStateException("Case not found.");

        matterStore.update(
          tenantUuid,
          caseUuid,
          label,
          nonBlank(current.jurisdictionUuid, ""),
          nonBlank(current.matterCategoryUuid, ""),
          nonBlank(current.matterSubcategoryUuid, ""),
          nonBlank(current.matterStatusUuid, ""),
          nonBlank(current.matterSubstatusUuid, ""),
          cause,
          county
        );

        String[] keys = request.getParameterValues("field_key");
        String[] vals = request.getParameterValues("field_value");
        LinkedHashMap<String,String> in = new LinkedHashMap<String,String>();
        int n = Math.max(keys == null ? 0 : keys.length, vals == null ? 0 : vals.length);
        for (int i = 0; i < n; i++) {
          String k = (keys != null && i < keys.length) ? safe(keys[i]) : "";
          String v = (vals != null && i < vals.length) ? safe(vals[i]) : "";
          in.put(k, v);
        }
        fieldsStore.write(tenantUuid, caseUuid, in);

        response.sendRedirect(ctx + "/cases.jsp?saved=1");
        return;
      } catch (Exception ex) {
        error = "Unable to save case: " + safe(ex.getMessage());
      }
    }

    if ("archive_case".equalsIgnoreCase(action)) {
      String caseUuid = safe(request.getParameter("uuid")).trim();
      try {
        matterStore.trash(tenantUuid, caseUuid);
        response.sendRedirect(ctx + "/cases.jsp?archived=1");
        return;
      } catch (Exception ex) {
        error = "Unable to archive case: " + safe(ex.getMessage());
      }
    }

    if ("restore_case".equalsIgnoreCase(action)) {
      String caseUuid = safe(request.getParameter("uuid")).trim();
      try {
        matterStore.restore(tenantUuid, caseUuid);
        response.sendRedirect(ctx + "/cases.jsp?restored=1");
        return;
      } catch (Exception ex) {
        error = "Unable to restore case: " + safe(ex.getMessage());
      }
    }
  }

  if ("1".equals(request.getParameter("created")))  message = "Case created.";
  if ("1".equals(request.getParameter("saved")))    message = "Case and replacement fields saved.";
  if ("1".equals(request.getParameter("archived"))) message = "Case archived.";
  if ("1".equals(request.getParameter("restored"))) message = "Case restored.";

  String q = safe(request.getParameter("q")).trim();
  String show = safe(request.getParameter("show")).trim().toLowerCase(Locale.ROOT);
  if (show.isBlank()) show = "active";

  List<matters.MatterRec> all = new ArrayList<matters.MatterRec>();
  try { all = matterStore.listAll(tenantUuid); } catch (Exception ignored) {}

  List<matters.MatterRec> filtered = new ArrayList<matters.MatterRec>();
  for (int i = 0; i < all.size(); i++) {
    matters.MatterRec m = all.get(i);
    if (m == null) continue;
    if ("active".equals(show) && m.trashed) continue;
    if ("archived".equals(show) && !m.trashed) continue;
    if (!q.isBlank()) {
      String h = safe(m.label).toLowerCase(Locale.ROOT) + " "
        + safe(m.causeDocketNumber).toLowerCase(Locale.ROOT) + " "
        + safe(m.county).toLowerCase(Locale.ROOT);
      if (!h.contains(q.toLowerCase(Locale.ROOT))) continue;
    }
    filtered.add(m);
  }

  Map<String, Map<String, String>> fieldCache = new HashMap<String, Map<String, String>>();
  for (int i = 0; i < filtered.size(); i++) {
    matters.MatterRec m = filtered.get(i);
    if (m == null) continue;
    try {
      fieldCache.put(safe(m.uuid), fieldsStore.read(tenantUuid, m.uuid));
    } catch (Exception ignored) {
      fieldCache.put(safe(m.uuid), new LinkedHashMap<String, String>());
    }
  }

  String baseQs =
    "q=" + URLEncoder.encode(q, StandardCharsets.UTF_8) +
    "&show=" + URLEncoder.encode(show, StandardCharsets.UTF_8);
%>

<jsp:include page="header.jsp" />

<section class="card">
  <div class="section-head">
    <div>
      <h1 style="margin:0;">Cases</h1>
      <div class="meta">Manage case records and the replacement key/value fields used in form assembly.</div>
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
  <h2 style="margin-top:0;">Create Case</h2>
  <form class="form" method="post" action="<%= ctx %>/cases.jsp">
    <input type="hidden" name="action" value="create_case" />
    <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />

    <div class="grid" style="display:grid; grid-template-columns: 2fr 1fr 1fr auto; gap:12px;">
      <label>
        <span>Case Label</span>
        <input type="text" name="label" required placeholder="Smith v. Jones" />
      </label>
      <label>
        <span>Cause / Docket</span>
        <input type="text" name="cause_docket_number" placeholder="2026-CV-001234" />
      </label>
      <label>
        <span>County</span>
        <input type="text" name="county" placeholder="Harris" />
      </label>
      <label>
        <span>&nbsp;</span>
        <button class="btn" type="submit">Create Case</button>
      </label>
    </div>
  </form>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Find Cases</h2>
  <form class="form" method="get" action="<%= ctx %>/cases.jsp">
    <div class="grid" style="display:grid; grid-template-columns: 3fr 1fr auto auto; gap:12px;">
      <label>
        <span>Search</span>
        <input type="text" name="q" value="<%= esc(q) %>" placeholder="Case, docket, or county" />
      </label>
      <label>
        <span>Status</span>
        <select name="show">
          <option value="active" <%= "active".equals(show) ? "selected" : "" %>>Active</option>
          <option value="archived" <%= "archived".equals(show) ? "selected" : "" %>>Archived</option>
          <option value="all" <%= "all".equals(show) ? "selected" : "" %>>All</option>
        </select>
      </label>
      <label>
        <span>&nbsp;</span>
        <button class="btn" type="submit">Apply</button>
      </label>
      <label>
        <span>&nbsp;</span>
        <a class="btn btn-ghost" href="<%= ctx %>/cases.jsp">Reset</a>
      </label>
    </div>
  </form>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Case List</h2>

  <div class="table-wrap">
    <table class="table">
      <thead>
        <tr>
          <th>Case</th>
          <th>Docket</th>
          <th>County</th>
          <th>Replacement Keys</th>
          <th style="width:320px;">Actions</th>
        </tr>
      </thead>
      <tbody>
      <%
        if (filtered.isEmpty()) {
      %>
        <tr>
          <td colspan="5" class="muted">No cases match your filter.</td>
        </tr>
      <%
        } else {
          for (int i = 0; i < filtered.size(); i++) {
            matters.MatterRec m = filtered.get(i);
            if (m == null) continue;

            String id = safe(m.uuid);
            String editRowId = "edit_case_" + i;

            Map<String,String> kv = fieldCache.get(id);
            if (kv == null) kv = new LinkedHashMap<String,String>();
            int rows = Math.max(4, kv.size() + 1);
      %>
        <tr>
          <td>
            <strong><%= esc(safe(m.label)) %></strong>
            <div class="muted" style="margin-top:4px;">
              <%= m.trashed ? "Archived" : "Active" %>
            </div>
          </td>
          <td><%= esc(safe(m.causeDocketNumber)) %></td>
          <td><%= esc(safe(m.county)) %></td>
          <td><%= kv.size() %></td>
          <td>
            <button class="btn btn-ghost" type="button" onclick="toggleEdit('<%= editRowId %>')">Edit</button>
            <a class="btn btn-ghost" href="<%= ctx %>/forms.jsp?matter_uuid=<%= URLEncoder.encode(id, StandardCharsets.UTF_8) %>&<%= baseQs %>">Assemble Forms</a>
            <% if (!m.trashed) { %>
              <form method="post" action="<%= ctx %>/cases.jsp?<%= baseQs %>" style="display:inline;">
                <input type="hidden" name="action" value="archive_case" />
                <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
                <input type="hidden" name="uuid" value="<%= esc(id) %>" />
                <button class="btn btn-ghost" type="submit" onclick="return confirm('Archive this case?');">Archive</button>
              </form>
            <% } else { %>
              <form method="post" action="<%= ctx %>/cases.jsp?<%= baseQs %>" style="display:inline;">
                <input type="hidden" name="action" value="restore_case" />
                <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
                <input type="hidden" name="uuid" value="<%= esc(id) %>" />
                <button class="btn" type="submit">Restore</button>
              </form>
            <% } %>
          </td>
        </tr>

        <tr id="<%= editRowId %>" style="display:none;">
          <td colspan="5">
            <div class="card" style="margin:8px 0; padding:14px; background:rgba(0,0,0,0.02);">
              <form class="form" method="post" action="<%= ctx %>/cases.jsp?<%= baseQs %>">
                <input type="hidden" name="action" value="save_case" />
                <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
                <input type="hidden" name="uuid" value="<%= esc(id) %>" />

                <div class="grid" style="display:grid; grid-template-columns: 2fr 1fr 1fr; gap:12px;">
                  <label>
                    <span>Case Label</span>
                    <input type="text" name="label" value="<%= esc(safe(m.label)) %>" required />
                  </label>
                  <label>
                    <span>Cause / Docket</span>
                    <input type="text" name="cause_docket_number" value="<%= esc(safe(m.causeDocketNumber)) %>" />
                  </label>
                  <label>
                    <span>County</span>
                    <input type="text" name="county" value="<%= esc(safe(m.county)) %>" />
                  </label>
                </div>

                <h4 style="margin:14px 0 8px 0;">Replacement Fields</h4>
                <div class="meta" style="margin-bottom:8px;">Use keys like <code>client_name</code> or <code>hearing_date</code>. Templates reference them as <code>{{kv.client_name}}</code>.</div>

                <div class="table-wrap">
                  <table class="table">
                    <thead>
                      <tr>
                        <th style="width:35%;">Key</th>
                        <th>Value</th>
                        <th style="width:90px;">&nbsp;</th>
                      </tr>
                    </thead>
                    <tbody id="fields_tbl_<%= i %>">
                    <%
                      int idx = 0;
                      for (Map.Entry<String,String> e : kv.entrySet()) {
                        if (e == null) continue;
                    %>
                      <tr>
                        <td><input type="text" name="field_key" value="<%= esc(safe(e.getKey())) %>" /></td>
                        <td><input type="text" name="field_value" value="<%= esc(safe(e.getValue())) %>" /></td>
                        <td><button type="button" class="btn btn-ghost" onclick="removeFieldRow(this)">Remove</button></td>
                      </tr>
                    <%
                        idx++;
                      }
                      for (; idx < rows; idx++) {
                    %>
                      <tr>
                        <td><input type="text" name="field_key" value="" /></td>
                        <td><input type="text" name="field_value" value="" /></td>
                        <td><button type="button" class="btn btn-ghost" onclick="removeFieldRow(this)">Remove</button></td>
                      </tr>
                    <% } %>
                    </tbody>
                  </table>
                </div>

                <div class="actions" style="display:flex; gap:10px; margin-top:10px;">
                  <button type="button" class="btn btn-ghost" onclick="addFieldRow('fields_tbl_<%= i %>')">Add Field Row</button>
                  <button type="submit" class="btn">Save Case</button>
                  <button type="button" class="btn btn-ghost" onclick="toggleEdit('<%= editRowId %>')">Cancel</button>
                </div>
              </form>
            </div>
          </td>
        </tr>
      <%
          }
        }
      %>
      </tbody>
    </table>
  </div>
</section>

<script>
  function toggleEdit(id) {
    var row = document.getElementById(id);
    if (!row) return;
    row.style.display = (row.style.display === "none" || row.style.display === "") ? "table-row" : "none";
  }

  function addFieldRow(tbodyId) {
    var tbody = document.getElementById(tbodyId);
    if (!tbody) return;
    var tr = document.createElement("tr");
    tr.innerHTML =
      '<td><input type="text" name="field_key" value="" /></td>' +
      '<td><input type="text" name="field_value" value="" /></td>' +
      '<td><button type="button" class="btn btn-ghost" onclick="removeFieldRow(this)">Remove</button></td>';
    tbody.appendChild(tr);
  }

  function removeFieldRow(btn) {
    if (!btn) return;
    var tr = btn.closest("tr");
    if (!tr) return;
    tr.remove();
  }
</script>

<jsp:include page="footer.jsp" />
