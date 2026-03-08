<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>

<%@ page import="java.util.LinkedHashMap" %>
<%@ page import="java.util.Map" %>

<%@ page import="net.familylawandprobate.controversies.tenant_fields" %>

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

  private static void logInfo(jakarta.servlet.ServletContext app, String message) {
    if (app == null) return;
    app.log("[tenant_fields] " + safe(message));
  }

  private static void logWarn(jakarta.servlet.ServletContext app, String message, Throwable ex) {
    if (app == null) return;
    if (ex == null) app.log("[tenant_fields] " + safe(message));
    else app.log("[tenant_fields] " + safe(message), ex);
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

  tenant_fields store = tenant_fields.defaultStore();

  String csrfToken = csrfForRender(request);
  String message = null;
  String error = null;

  if ("POST".equalsIgnoreCase(request.getMethod())) {
    String action = safe(request.getParameter("action")).trim();

    if ("save_fields".equalsIgnoreCase(action)) {
      try {
        String[] keys = request.getParameterValues("field_key");
        String[] vals = request.getParameterValues("field_value");

        LinkedHashMap<String,String> in = new LinkedHashMap<String,String>();
        int n = Math.max(keys == null ? 0 : keys.length, vals == null ? 0 : vals.length);
        for (int i = 0; i < n; i++) {
          String k = (keys != null && i < keys.length) ? safe(keys[i]) : "";
          String v = (vals != null && i < vals.length) ? safe(vals[i]) : "";
          in.put(k, v);
        }

        store.write(tenantUuid, in);
        logInfo(application, "Updated tenant fields for tenant " + tenantUuid + " by user " + safe((String)session.getAttribute("user.uuid")) + "; keys=" + in.size());
        response.sendRedirect(ctx + "/tenant_fields.jsp?saved=1");
        return;
      } catch (Exception ex) {
        error = "Unable to save tenant fields: " + safe(ex.getMessage());
        logWarn(application, "Failed saving tenant fields for tenant " + tenantUuid + ": " + safe(ex.getMessage()), ex);
      }
    }
  }

  if ("1".equals(request.getParameter("saved"))) message = "Tenant replacement fields saved.";

  LinkedHashMap<String,String> kv = new LinkedHashMap<String,String>();
  try { kv.putAll(store.read(tenantUuid)); } catch (Exception ex) { logWarn(application, "Failed reading tenant fields for tenant " + tenantUuid + ": " + safe(ex.getMessage()), ex); }

  int rows = Math.max(6, kv.size() + 1);
%>

<jsp:include page="header.jsp" />

<section class="card">
  <div class="section-head">
    <div>
      <h1 style="margin:0;">Tenant Fields</h1>
      <div class="meta">Global replacement keys shared by all users and cases in this tenant.</div>
      <% if (!tenantLabel.isBlank()) { %>
        <div class="meta" style="margin-top:4px;">Tenant: <strong><%= esc(tenantLabel) %></strong></div>
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
  <h2 style="margin-top:0;">Global Key/Value Store</h2>
  <div class="meta" style="margin-bottom:10px;">
    Use keys like <code>firm_name</code>, <code>firm_phone</code>, <code>attorney_name</code>. 
    Templates can reference tenant-specific keys as <code>{{tenant.firm_name}}</code>.
  </div>

  <form class="form" method="post" action="<%= ctx %>/tenant_fields.jsp">
    <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
    <input type="hidden" name="action" value="save_fields" />

    <div class="table-wrap">
      <table class="table">
        <thead>
          <tr>
            <th style="width:35%;">Key</th>
            <th>Value</th>
            <th style="width:96px;">&nbsp;</th>
          </tr>
        </thead>
        <tbody id="tenantFieldRows">
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
      <button type="button" class="btn btn-ghost" onclick="addFieldRow()">Add Field</button>
      <button type="submit" class="btn">Save Tenant Fields</button>
    </div>
  </form>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Merge Precedence</h2>
  <div class="meta">
    During assembly, values are merged in this order:
    <strong>Tenant fields first</strong>, then <strong>case fields</strong>.
    If both define the same key, the case value wins for <code>{{kv.key}}</code> and <code>{{key}}</code>.
  </div>
</section>

<script>
  function addFieldRow() {
    var tbody = document.getElementById("tenantFieldRows");
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
