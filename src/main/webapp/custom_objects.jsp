<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" isELIgnored="true" %>

<%@ page import="java.net.URLEncoder" %>
<%@ page import="java.nio.charset.StandardCharsets" %>
<%@ page import="java.time.Instant" %>
<%@ page import="java.util.ArrayList" %>
<%@ page import="java.util.List" %>
<%@ page import="java.util.Locale" %>
<%@ page import="java.util.Map" %>

<%@ page import="net.familylawandprobate.controversies.activity_log" %>
<%@ page import="net.familylawandprobate.controversies.custom_objects" %>

<%@ include file="security.jspf" %>
<%
  if (!require_login()) return;
  if (!require_permission("tenant_admin")) return;
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
    app.log("[custom_objects] " + safe(msg));
  }

  private static void logWarn(jakarta.servlet.ServletContext app, String msg, Throwable ex) {
    if (app == null) return;
    if (ex == null) app.log("[custom_objects] " + safe(msg));
    else app.log("[custom_objects] " + safe(msg), ex);
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

  custom_objects store = custom_objects.defaultStore();
  activity_log logs = activity_log.defaultStore();
  try { store.ensure(tenantUuid); } catch (Exception ex) {
    logWarn(application, "Unable to ensure custom object store for tenant=" + tenantUuid + ": " + safe(ex.getMessage()), ex);
  }

  String csrfToken = csrfForRender(request);
  String message = null;
  String error = null;

  if ("POST".equalsIgnoreCase(request.getMethod())) {
    String action = safe(request.getParameter("action")).trim();

    if ("save_custom_objects".equalsIgnoreCase(action)) {
      try {
        String[] uuids = request.getParameterValues("object_uuid");
        String[] keys = request.getParameterValues("object_key");
        String[] labels = request.getParameterValues("object_label");
        String[] plurals = request.getParameterValues("object_plural");
        String[] enabled = request.getParameterValues("object_enabled");
        String[] published = request.getParameterValues("object_published");
        String[] sort = request.getParameterValues("object_sort");

        int n = 0;
        n = Math.max(n, uuids == null ? 0 : uuids.length);
        n = Math.max(n, keys == null ? 0 : keys.length);
        n = Math.max(n, labels == null ? 0 : labels.length);
        n = Math.max(n, plurals == null ? 0 : plurals.length);
        n = Math.max(n, enabled == null ? 0 : enabled.length);
        n = Math.max(n, published == null ? 0 : published.length);
        n = Math.max(n, sort == null ? 0 : sort.length);

        ArrayList<custom_objects.ObjectRec> rows = new ArrayList<custom_objects.ObjectRec>();
        for (int i = 0; i < n; i++) {
          rows.add(new custom_objects.ObjectRec(
            (uuids != null && i < uuids.length) ? uuids[i] : "",
            (keys != null && i < keys.length) ? keys[i] : "",
            (labels != null && i < labels.length) ? labels[i] : "",
            (plurals != null && i < plurals.length) ? plurals[i] : "",
            boolLike((enabled != null && i < enabled.length) ? enabled[i] : "true"),
            boolLike((published != null && i < published.length) ? published[i] : "false"),
            intLike((sort != null && i < sort.length) ? sort[i] : "", (i + 1) * 10),
            Instant.now().toString()
          ));
        }

        store.saveAll(tenantUuid, rows);

        logs.logVerbose("custom_object_definitions.saved",
          tenantUuid,
          userUuid,
          "",
          "",
          Map.of(
            "row_count", String.valueOf(rows.size()),
            "time", Instant.now().toString()
          )
        );

        logInfo(application, "Saved custom objects for tenant=" + tenantUuid + ", user=" + userUuid + ", rows=" + rows.size());
        response.sendRedirect(ctx + "/custom_objects.jsp?saved=1");
        return;
      } catch (Exception ex) {
        error = "Unable to save custom objects: " + safe(ex.getMessage());
        logs.logError("custom_object_definitions.save_failed",
          tenantUuid,
          userUuid,
          "",
          "",
          Map.of("reason", safe(ex.getClass().getSimpleName()), "message", safe(ex.getMessage()))
        );
        logWarn(application, "Failed saving custom objects for tenant=" + tenantUuid + ": " + safe(ex.getMessage()), ex);
      }
    }
  }

  if ("1".equals(request.getParameter("saved"))) message = "Custom object definitions saved.";

  List<custom_objects.ObjectRec> objects = new ArrayList<custom_objects.ObjectRec>();
  try {
    objects = store.listAll(tenantUuid);
  } catch (Exception ex) {
    error = "Unable to load custom object definitions.";
    logWarn(application, "Failed loading custom objects for tenant=" + tenantUuid + ": " + safe(ex.getMessage()), ex);
  }

  int rows = Math.max(4, objects.size() + 1);
%>

<jsp:include page="header.jsp" />

<section class="card">
  <h1 style="margin:0;">Custom Objects</h1>
  <div class="meta" style="margin-top:6px;">
    Create tenant-specific data objects. Publish an object to place it in the navigation menu for non-admin users.
  </div>

  <% if (message != null) { %>
    <div class="alert alert-ok" style="margin-top:12px;"><%= esc(message) %></div>
  <% } %>
  <% if (error != null) { %>
    <div class="alert alert-error" style="margin-top:12px;"><%= esc(error) %></div>
  <% } %>
</section>

<section class="card" style="margin-top:12px;">
  <form class="form" method="post" action="<%= ctx %>/custom_objects.jsp">
    <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
    <input type="hidden" name="action" value="save_custom_objects" />

    <div class="table-wrap">
      <table class="table">
        <thead>
          <tr>
            <th style="width:72px;">Order</th>
            <th style="width:20%;">Label</th>
            <th style="width:20%;">Plural Label</th>
            <th style="width:20%;">Key</th>
            <th style="width:92px;">Enabled</th>
            <th style="width:92px;">Published</th>
            <th style="width:220px;">Actions</th>
          </tr>
        </thead>
        <tbody id="customObjectRows">
        <%
          int idx = 0;
          for (int i = 0; i < objects.size(); i++) {
            custom_objects.ObjectRec r = objects.get(i);
            if (r == null) continue;
            String objectUuid = safe(r.uuid);
        %>
          <tr>
            <td>
              <input type="hidden" name="object_uuid" value="<%= esc(objectUuid) %>" />
              <input type="number" name="object_sort" value="<%= r.sortOrder %>" min="0" step="10" />
            </td>
            <td><input type="text" name="object_label" value="<%= esc(safe(r.label)) %>" /></td>
            <td><input type="text" name="object_plural" value="<%= esc(safe(r.pluralLabel)) %>" /></td>
            <td><input type="text" name="object_key" value="<%= esc(safe(r.key)) %>" placeholder="billing_entry" /></td>
            <td>
              <select name="object_enabled">
                <option value="true" <%= r.enabled ? "selected" : "" %>>Yes</option>
                <option value="false" <%= r.enabled ? "" : "selected" %>>No</option>
              </select>
            </td>
            <td>
              <select name="object_published">
                <option value="true" <%= r.published ? "selected" : "" %>>Yes</option>
                <option value="false" <%= r.published ? "" : "selected" %>>No</option>
              </select>
            </td>
            <td style="display:flex; gap:8px; flex-wrap:wrap; align-items:center;">
              <a class="btn btn-ghost" href="<%= ctx %>/custom_object_attributes.jsp?object_uuid=<%= enc(objectUuid) %>">Attributes</a>
              <a class="btn btn-ghost" href="<%= ctx %>/custom_object_records.jsp?object_uuid=<%= enc(objectUuid) %>">Records</a>
              <button type="button" class="btn btn-ghost" onclick="removeCustomObjectRow(this)">Remove</button>
            </td>
          </tr>
        <%
            idx++;
          }
          for (; idx < rows; idx++) {
        %>
          <tr>
            <td>
              <input type="hidden" name="object_uuid" value="" />
              <input type="number" name="object_sort" value="<%= (idx + 1) * 10 %>" min="0" step="10" />
            </td>
            <td><input type="text" name="object_label" value="" placeholder="Billing Entry" /></td>
            <td><input type="text" name="object_plural" value="" placeholder="Billing Entries" /></td>
            <td><input type="text" name="object_key" value="" placeholder="billing_entry" /></td>
            <td>
              <select name="object_enabled">
                <option value="true" selected>Yes</option>
                <option value="false">No</option>
              </select>
            </td>
            <td>
              <select name="object_published">
                <option value="true">Yes</option>
                <option value="false" selected>No</option>
              </select>
            </td>
            <td style="display:flex; gap:8px; flex-wrap:wrap; align-items:center;">
              <span class="muted">Save first to manage attributes/records.</span>
              <button type="button" class="btn btn-ghost" onclick="removeCustomObjectRow(this)">Remove</button>
            </td>
          </tr>
        <% } %>
        </tbody>
      </table>
    </div>

    <div class="actions" style="display:flex; gap:10px; margin-top:10px;">
      <button type="button" class="btn btn-ghost" onclick="addCustomObjectRow()">Add Object</button>
      <button type="submit" class="btn">Save Custom Objects</button>
    </div>
  </form>
</section>

<script>
  function addCustomObjectRow() {
    var tbody = document.getElementById("customObjectRows");
    if (!tbody) return;
    var idx = tbody.querySelectorAll("tr").length + 1;
    var tr = document.createElement("tr");
    tr.innerHTML =
      '<td>' +
        '<input type="hidden" name="object_uuid" value="" />' +
        '<input type="number" name="object_sort" value="' + (idx * 10) + '" min="0" step="10" />' +
      '</td>' +
      '<td><input type="text" name="object_label" value="" placeholder="Billing Entry" /></td>' +
      '<td><input type="text" name="object_plural" value="" placeholder="Billing Entries" /></td>' +
      '<td><input type="text" name="object_key" value="" placeholder="billing_entry" /></td>' +
      '<td><select name="object_enabled"><option value="true" selected>Yes</option><option value="false">No</option></select></td>' +
      '<td><select name="object_published"><option value="true">Yes</option><option value="false" selected>No</option></select></td>' +
      '<td style="display:flex; gap:8px; flex-wrap:wrap; align-items:center;">' +
        '<span class="muted">Save first to manage attributes/records.</span>' +
        '<button type="button" class="btn btn-ghost" onclick="removeCustomObjectRow(this)">Remove</button>' +
      '</td>';
    tbody.appendChild(tr);
  }

  function removeCustomObjectRow(btn) {
    if (!btn) return;
    var tr = btn.closest("tr");
    if (!tr) return;
    tr.remove();
  }
</script>

<jsp:include page="footer.jsp" />
