<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" isELIgnored="true" %>

<%@ page import="java.util.ArrayList" %>
<%@ page import="java.util.List" %>

<%@ page import="net.familylawandprobate.controversies.task_attributes" %>

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

  private static boolean boolLike(String raw) {
    String v = safe(raw).trim().toLowerCase(java.util.Locale.ROOT);
    return "true".equals(v) || "1".equals(v) || "yes".equals(v) || "on".equals(v) || "y".equals(v);
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

  task_attributes store = task_attributes.defaultStore();
  try { store.ensure(tenantUuid); } catch (Exception ignored) {}

  String csrfToken = csrfForRender(request);
  String message = null;
  String error = null;

  if ("POST".equalsIgnoreCase(request.getMethod())) {
    String action = safe(request.getParameter("action")).trim();
    if ("save_task_attributes".equalsIgnoreCase(action)) {
      try {
        String[] uuids = request.getParameterValues("attr_uuid");
        String[] keys = request.getParameterValues("attr_key");
        String[] labels = request.getParameterValues("attr_label");
        String[] types = request.getParameterValues("attr_type");
        String[] options = request.getParameterValues("attr_options");
        String[] required = request.getParameterValues("attr_required");
        String[] enabled = request.getParameterValues("attr_enabled");
        String[] sort = request.getParameterValues("attr_sort");

        int n = 0;
        n = Math.max(n, uuids == null ? 0 : uuids.length);
        n = Math.max(n, keys == null ? 0 : keys.length);
        n = Math.max(n, labels == null ? 0 : labels.length);
        n = Math.max(n, types == null ? 0 : types.length);
        n = Math.max(n, options == null ? 0 : options.length);
        n = Math.max(n, required == null ? 0 : required.length);
        n = Math.max(n, enabled == null ? 0 : enabled.length);
        n = Math.max(n, sort == null ? 0 : sort.length);

        ArrayList<task_attributes.AttributeRec> rows = new ArrayList<task_attributes.AttributeRec>();
        for (int i = 0; i < n; i++) {
          String uuid = (uuids != null && i < uuids.length) ? uuids[i] : "";
          String key = (keys != null && i < keys.length) ? keys[i] : "";
          String label = (labels != null && i < labels.length) ? labels[i] : "";
          String type = (types != null && i < types.length) ? types[i] : "";
          String opts = (options != null && i < options.length) ? options[i] : "";
          boolean req = boolLike((required != null && i < required.length) ? required[i] : "false");
          boolean en = boolLike((enabled != null && i < enabled.length) ? enabled[i] : "true");
          int ord = 0;
          try { ord = Integer.parseInt(safe((sort != null && i < sort.length) ? sort[i] : "").trim()); } catch (Exception ignored) {}

          rows.add(new task_attributes.AttributeRec(uuid, key, label, type, opts, req, en, ord, ""));
        }

        store.saveAll(tenantUuid, rows);
        response.sendRedirect(ctx + "/task_attributes.jsp?saved=1");
        return;
      } catch (Exception ex) {
        error = "Unable to save task attributes: " + safe(ex.getMessage());
      }
    }
  }

  if ("1".equals(request.getParameter("saved"))) message = "Task attribute definitions saved.";

  List<task_attributes.AttributeRec> attrs = new ArrayList<task_attributes.AttributeRec>();
  try { attrs = store.listAll(tenantUuid); } catch (Exception ignored) {}
  int rows = Math.max(4, attrs.size() + 1);
%>

<jsp:include page="header.jsp" />

<section class="card">
  <h1 style="margin:0;">Task Attributes</h1>
  <div class="meta" style="margin-top:4px;">Tenant-scoped task metadata fields. Use these on the Tasks page to capture structured task values.</div>

  <% if (message != null) { %>
    <div class="alert alert-ok" style="margin-top:12px;"><%= esc(message) %></div>
  <% } %>
  <% if (error != null) { %>
    <div class="alert alert-error" style="margin-top:12px;"><%= esc(error) %></div>
  <% } %>
</section>

<section class="card" style="margin-top:12px;">
  <form class="form" method="post" action="<%= ctx %>/task_attributes.jsp">
    <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
    <input type="hidden" name="action" value="save_task_attributes" />

    <div class="table-wrap">
      <table class="table">
        <thead>
          <tr>
            <th style="width:70px;">Order</th>
            <th style="width:22%;">Label</th>
            <th style="width:20%;">Key</th>
            <th style="width:14%;">Type</th>
            <th>Select Options (for dropdown)</th>
            <th style="width:90px;">Required</th>
            <th style="width:90px;">Enabled</th>
            <th style="width:90px;">&nbsp;</th>
          </tr>
        </thead>
        <tbody id="taskAttrRows">
        <%
          int idx = 0;
          for (int i = 0; i < attrs.size(); i++) {
            task_attributes.AttributeRec a = attrs.get(i);
            if (a == null) continue;
        %>
          <tr>
            <td>
              <input type="hidden" name="attr_uuid" value="<%= esc(safe(a.uuid)) %>" />
              <input type="number" name="attr_sort" value="<%= a.sortOrder %>" min="0" step="10" />
            </td>
            <td><input type="text" name="attr_label" value="<%= esc(safe(a.label)) %>" /></td>
            <td><input type="text" name="attr_key" value="<%= esc(safe(a.key)) %>" placeholder="task_phase" /></td>
            <td>
              <select name="attr_type">
                <option value="text" <%= "text".equals(a.dataType) ? "selected" : "" %>>Text</option>
                <option value="textarea" <%= "textarea".equals(a.dataType) ? "selected" : "" %>>Textarea</option>
                <option value="number" <%= "number".equals(a.dataType) ? "selected" : "" %>>Number</option>
                <option value="date" <%= "date".equals(a.dataType) ? "selected" : "" %>>Date</option>
                <option value="datetime" <%= "datetime".equals(a.dataType) ? "selected" : "" %>>Date + Time</option>
                <option value="time" <%= "time".equals(a.dataType) ? "selected" : "" %>>Time</option>
                <option value="boolean" <%= "boolean".equals(a.dataType) ? "selected" : "" %>>Yes/No</option>
                <option value="email" <%= "email".equals(a.dataType) ? "selected" : "" %>>Email</option>
                <option value="phone" <%= "phone".equals(a.dataType) ? "selected" : "" %>>Phone</option>
                <option value="url" <%= "url".equals(a.dataType) ? "selected" : "" %>>URL</option>
                <option value="select" <%= "select".equals(a.dataType) ? "selected" : "" %>>Dropdown</option>
              </select>
            </td>
            <td><textarea name="attr_options" rows="2" placeholder="Option 1&#10;Option 2"><%= esc(safe(a.options)) %></textarea></td>
            <td>
              <select name="attr_required">
                <option value="false" <%= a.required ? "" : "selected" %>>No</option>
                <option value="true" <%= a.required ? "selected" : "" %>>Yes</option>
              </select>
            </td>
            <td>
              <select name="attr_enabled">
                <option value="true" <%= a.enabled ? "selected" : "" %>>Yes</option>
                <option value="false" <%= a.enabled ? "" : "selected" %>>No</option>
              </select>
            </td>
            <td><button type="button" class="btn btn-ghost" onclick="removeTaskAttrRow(this)">Remove</button></td>
          </tr>
        <%
            idx++;
          }
          for (; idx < rows; idx++) {
        %>
          <tr>
            <td>
              <input type="hidden" name="attr_uuid" value="" />
              <input type="number" name="attr_sort" value="<%= (idx + 1) * 10 %>" min="0" step="10" />
            </td>
            <td><input type="text" name="attr_label" value="" /></td>
            <td><input type="text" name="attr_key" value="" placeholder="task_phase" /></td>
            <td>
              <select name="attr_type">
                <option value="text" selected>Text</option>
                <option value="textarea">Textarea</option>
                <option value="number">Number</option>
                <option value="date">Date</option>
                <option value="datetime">Date + Time</option>
                <option value="time">Time</option>
                <option value="boolean">Yes/No</option>
                <option value="email">Email</option>
                <option value="phone">Phone</option>
                <option value="url">URL</option>
                <option value="select">Dropdown</option>
              </select>
            </td>
            <td><textarea name="attr_options" rows="2" placeholder="Option 1&#10;Option 2"></textarea></td>
            <td>
              <select name="attr_required">
                <option value="false" selected>No</option>
                <option value="true">Yes</option>
              </select>
            </td>
            <td>
              <select name="attr_enabled">
                <option value="true" selected>Yes</option>
                <option value="false">No</option>
              </select>
            </td>
            <td><button type="button" class="btn btn-ghost" onclick="removeTaskAttrRow(this)">Remove</button></td>
          </tr>
        <% } %>
        </tbody>
      </table>
    </div>

    <div class="actions" style="display:flex; gap:10px; margin-top:10px;">
      <button type="button" class="btn btn-ghost" onclick="addTaskAttrRow()">Add Attribute</button>
      <button type="submit" class="btn">Save Task Attributes</button>
    </div>
  </form>
</section>

<script>
  function addTaskAttrRow() {
    var tbody = document.getElementById("taskAttrRows");
    if (!tbody) return;
    var idx = tbody.querySelectorAll("tr").length + 1;
    var tr = document.createElement("tr");
    tr.innerHTML =
      '<td>' +
        '<input type="hidden" name="attr_uuid" value="" />' +
        '<input type="number" name="attr_sort" value="' + (idx * 10) + '" min="0" step="10" />' +
      '</td>' +
      '<td><input type="text" name="attr_label" value="" /></td>' +
      '<td><input type="text" name="attr_key" value="" placeholder="task_phase" /></td>' +
      '<td><select name="attr_type">' +
        '<option value="text" selected>Text</option>' +
        '<option value="textarea">Textarea</option>' +
        '<option value="number">Number</option>' +
        '<option value="date">Date</option>' +
        '<option value="datetime">Date + Time</option>' +
        '<option value="time">Time</option>' +
        '<option value="boolean">Yes/No</option>' +
        '<option value="email">Email</option>' +
        '<option value="phone">Phone</option>' +
        '<option value="url">URL</option>' +
        '<option value="select">Dropdown</option>' +
      '</select></td>' +
      '<td><textarea name="attr_options" rows="2" placeholder="Option 1&#10;Option 2"></textarea></td>' +
      '<td><select name="attr_required"><option value="false" selected>No</option><option value="true">Yes</option></select></td>' +
      '<td><select name="attr_enabled"><option value="true" selected>Yes</option><option value="false">No</option></select></td>' +
      '<td><button type="button" class="btn btn-ghost" onclick="removeTaskAttrRow(this)">Remove</button></td>';
    tbody.appendChild(tr);
  }

  function removeTaskAttrRow(btn) {
    if (!btn) return;
    var tr = btn.closest("tr");
    if (!tr) return;
    tr.remove();
  }
</script>

<jsp:include page="footer.jsp" />
