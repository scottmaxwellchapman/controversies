<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" isELIgnored="true" %>

<%@ page import="java.net.URLEncoder" %>
<%@ page import="java.nio.charset.StandardCharsets" %>
<%@ page import="java.time.Instant" %>
<%@ page import="java.util.ArrayList" %>
<%@ page import="java.util.List" %>
<%@ page import="java.util.Locale" %>
<%@ page import="java.util.Map" %>

<%@ page import="net.familylawandprobate.controversies.activity_log" %>
<%@ page import="net.familylawandprobate.controversies.custom_object_attributes" %>
<%@ page import="net.familylawandprobate.controversies.custom_objects" %>

<%@ include file="security.jspf" %>
<%
  if (!require_login()) return;
%>

<%!
  private static final String S_TENANT_UUID = "tenant.uuid";
  private static final String S_USER_UUID = "user.uuid";
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

  private static boolean boolLike(String raw) {
    String v = safe(raw).trim().toLowerCase(Locale.ROOT);
    return "true".equals(v) || "1".equals(v) || "yes".equals(v) || "on".equals(v) || "y".equals(v);
  }

  private static int intLike(String raw, int fallback) {
    try { return Integer.parseInt(safe(raw).trim()); } catch (Exception ignored) { return fallback; }
  }

  private static void logInfo(jakarta.servlet.ServletContext app, String msg) {
    if (app == null) return;
    app.log("[custom_object_attributes] " + safe(msg));
  }

  private static void logWarn(jakarta.servlet.ServletContext app, String msg, Throwable ex) {
    if (app == null) return;
    if (ex == null) app.log("[custom_object_attributes] " + safe(msg));
    else app.log("[custom_object_attributes] " + safe(msg), ex);
  }
%>

<%
  String ctx = safe(request.getContextPath());
  String tenantUuid = safe((String) session.getAttribute(S_TENANT_UUID)).trim();
  String userUuid = safe((String) session.getAttribute(S_USER_UUID)).trim();
  if (tenantUuid.isBlank()) {
    response.sendRedirect(ctx + "/tenant_login.jsp");
    return;
  }

  custom_objects objectStore = custom_objects.defaultStore();
  custom_object_attributes attrStore = custom_object_attributes.defaultStore();
  activity_log logs = activity_log.defaultStore();

  List<custom_objects.ObjectRec> allObjects = new ArrayList<custom_objects.ObjectRec>();
  try {
    objectStore.ensure(tenantUuid);
    allObjects = objectStore.listAll(tenantUuid);
  } catch (Exception ex) {
    logWarn(application, "Failed loading custom objects for tenant=" + tenantUuid + ": " + safe(ex.getMessage()), ex);
  }

  String objectUuid = safe(request.getParameter("object_uuid")).trim();
  if (objectUuid.isBlank() && !allObjects.isEmpty()) objectUuid = safe(allObjects.get(0).uuid).trim();

  custom_objects.ObjectRec objectRec = null;
  if (!objectUuid.isBlank()) {
    try { objectRec = objectStore.getByUuid(tenantUuid, objectUuid); } catch (Exception ignored) {}
  }

  String csrfToken = csrfForRender(request);
  String message = null;
  String error = null;

  if ("POST".equalsIgnoreCase(request.getMethod())) {
    String action = safe(request.getParameter("action")).trim();
    objectUuid = safe(request.getParameter("object_uuid")).trim();

    if ("save_custom_object_attributes".equalsIgnoreCase(action)) {
      try {
        custom_objects.ObjectRec ob = objectStore.getByUuid(tenantUuid, objectUuid);
        if (ob == null) throw new IllegalArgumentException("Object not found.");

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

        ArrayList<custom_object_attributes.AttributeRec> rows = new ArrayList<custom_object_attributes.AttributeRec>();
        for (int i = 0; i < n; i++) {
          rows.add(new custom_object_attributes.AttributeRec(
            (uuids != null && i < uuids.length) ? uuids[i] : "",
            (keys != null && i < keys.length) ? keys[i] : "",
            (labels != null && i < labels.length) ? labels[i] : "",
            (types != null && i < types.length) ? types[i] : "",
            (options != null && i < options.length) ? options[i] : "",
            boolLike((required != null && i < required.length) ? required[i] : "false"),
            boolLike((enabled != null && i < enabled.length) ? enabled[i] : "true"),
            intLike((sort != null && i < sort.length) ? sort[i] : "", (i + 1) * 10),
            Instant.now().toString()
          ));
        }

        attrStore.saveAll(tenantUuid, objectUuid, rows);
        logs.logVerbose("custom_object_attributes.saved",
          tenantUuid,
          userUuid,
          "",
          "",
          Map.of(
            "object_uuid", safe(objectUuid),
            "object_key", safe(ob.key),
            "row_count", String.valueOf(rows.size())
          )
        );
        logInfo(application, "Saved attributes for tenant=" + tenantUuid + ", object=" + objectUuid + ", rows=" + rows.size());
        response.sendRedirect(ctx + "/custom_object_attributes.jsp?object_uuid=" + enc(objectUuid) + "&saved=1");
        return;
      } catch (Exception ex) {
        error = "Unable to save custom object attributes: " + safe(ex.getMessage());
        logs.logError("custom_object_attributes.save_failed",
          tenantUuid,
          userUuid,
          "",
          "",
          Map.of("object_uuid", safe(objectUuid), "reason", safe(ex.getClass().getSimpleName()), "message", safe(ex.getMessage()))
        );
        logWarn(application, "Failed saving object attributes for tenant=" + tenantUuid + ", object=" + objectUuid + ": " + safe(ex.getMessage()), ex);
      }
    }
  }

  if ("1".equals(request.getParameter("saved"))) message = "Custom object attributes saved.";

  objectRec = null;
  if (!objectUuid.isBlank()) {
    try { objectRec = objectStore.getByUuid(tenantUuid, objectUuid); } catch (Exception ignored) {}
  }

  List<custom_object_attributes.AttributeRec> attrs = new ArrayList<custom_object_attributes.AttributeRec>();
  if (objectRec != null) {
    try {
      attrStore.ensure(tenantUuid, objectUuid);
      attrs = attrStore.listAll(tenantUuid, objectUuid);
    } catch (Exception ex) {
      error = "Unable to load attribute definitions for this object.";
      logWarn(application, "Failed loading object attributes for tenant=" + tenantUuid + ", object=" + objectUuid + ": " + safe(ex.getMessage()), ex);
    }
  }

  int rows = Math.max(4, attrs.size() + 1);
%>

<jsp:include page="header.jsp" />

<section class="card">
  <h1 style="margin:0;">Custom Object Attributes</h1>
  <div class="meta" style="margin-top:6px;">
    Configure fields for each custom object. Tokens become available as <code>{{object.&lt;key&gt;}}</code> for relevant workflows.
  </div>

  <% if (message != null) { %>
    <div class="alert alert-ok" style="margin-top:12px;"><%= esc(message) %></div>
  <% } %>
  <% if (error != null) { %>
    <div class="alert alert-error" style="margin-top:12px;"><%= esc(error) %></div>
  <% } %>
</section>

<section class="card" style="margin-top:12px;">
  <form class="form" method="get" action="<%= ctx %>/custom_object_attributes.jsp" style="margin-bottom:8px;">
    <label>
      <span>Object</span>
      <select name="object_uuid" onchange="this.form.submit()">
        <% for (int i = 0; i < allObjects.size(); i++) {
             custom_objects.ObjectRec ob = allObjects.get(i);
             if (ob == null) continue;
             String oid = safe(ob.uuid);
        %>
          <option value="<%= esc(oid) %>" <%= oid.equals(objectUuid) ? "selected" : "" %>><%= esc(safe(ob.pluralLabel)) %></option>
        <% } %>
      </select>
    </label>
  </form>

  <% if (objectRec == null) { %>
    <div class="muted">Create and save at least one custom object before defining attributes.</div>
    <div style="margin-top:10px;"><a class="btn" href="<%= ctx %>/custom_objects.jsp">Go to Custom Objects</a></div>
  <% } else { %>
    <div class="meta" style="margin-bottom:8px;">
      Editing: <strong><%= esc(safe(objectRec.pluralLabel)) %></strong> (<code><%= esc(safe(objectRec.key)) %></code>)
    </div>

    <form class="form" method="post" action="<%= ctx %>/custom_object_attributes.jsp">
      <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
      <input type="hidden" name="action" value="save_custom_object_attributes" />
      <input type="hidden" name="object_uuid" value="<%= esc(objectUuid) %>" />

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
          <tbody id="objAttrRows">
          <%
            int idx = 0;
            for (int i = 0; i < attrs.size(); i++) {
              custom_object_attributes.AttributeRec a = attrs.get(i);
              if (a == null) continue;
          %>
            <tr>
              <td>
                <input type="hidden" name="attr_uuid" value="<%= esc(safe(a.uuid)) %>" />
                <input type="number" name="attr_sort" value="<%= a.sortOrder %>" min="0" step="10" />
              </td>
              <td><input type="text" name="attr_label" value="<%= esc(safe(a.label)) %>" /></td>
              <td><input type="text" name="attr_key" value="<%= esc(safe(a.key)) %>" placeholder="entry_date" /></td>
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
              <td><button type="button" class="btn btn-ghost" onclick="removeObjAttrRow(this)">Remove</button></td>
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
              <td><input type="text" name="attr_key" value="" placeholder="entry_date" /></td>
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
              <td><button type="button" class="btn btn-ghost" onclick="removeObjAttrRow(this)">Remove</button></td>
            </tr>
          <% } %>
          </tbody>
        </table>
      </div>

      <div class="actions" style="display:flex; gap:10px; margin-top:10px;">
        <button type="button" class="btn btn-ghost" onclick="addObjAttrRow()">Add Attribute</button>
        <button type="submit" class="btn">Save Attributes</button>
        <a class="btn btn-ghost" href="<%= ctx %>/custom_object_records.jsp?object_uuid=<%= enc(objectUuid) %>">Open Records</a>
      </div>
    </form>
  <% } %>
</section>

<script>
  function addObjAttrRow() {
    var tbody = document.getElementById("objAttrRows");
    if (!tbody) return;
    var idx = tbody.querySelectorAll("tr").length + 1;
    var tr = document.createElement("tr");
    tr.innerHTML =
      '<td>' +
        '<input type="hidden" name="attr_uuid" value="" />' +
        '<input type="number" name="attr_sort" value="' + (idx * 10) + '" min="0" step="10" />' +
      '</td>' +
      '<td><input type="text" name="attr_label" value="" /></td>' +
      '<td><input type="text" name="attr_key" value="" placeholder="entry_date" /></td>' +
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
      '<td><button type="button" class="btn btn-ghost" onclick="removeObjAttrRow(this)">Remove</button></td>';
    tbody.appendChild(tr);
  }

  function removeObjAttrRow(btn) {
    if (!btn) return;
    var tr = btn.closest("tr");
    if (!tr) return;
    tr.remove();
  }
</script>

<jsp:include page="footer.jsp" />
