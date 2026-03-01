<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" isELIgnored="true" %>

<%@ page import="java.net.URLEncoder" %>
<%@ page import="java.nio.charset.StandardCharsets" %>
<%@ page import="java.util.ArrayList" %>
<%@ page import="java.util.LinkedHashMap" %>
<%@ page import="java.util.List" %>
<%@ page import="java.util.Locale" %>
<%@ page import="java.util.Map" %>

<%@ page import="net.familylawandprobate.controversies.case_fields" %>
<%@ page import="net.familylawandprobate.controversies.form_templates" %>
<%@ page import="net.familylawandprobate.controversies.matters" %>

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
  if (tenantUuid.isBlank()) {
    response.sendRedirect(ctx + "/tenant_login.jsp");
    return;
  }

  matters matterStore = matters.defaultStore();
  case_fields caseStore = case_fields.defaultStore();
  form_templates templateStore = form_templates.defaultStore();

  try { matterStore.ensure(tenantUuid); } catch (Exception ignored) {}

  String csrfToken = csrfForRender(request);

  String message = null;
  String error = null;

  String selectedMatterUuid = safe(request.getParameter("matter_uuid")).trim();
  String selectedTemplateUuid = safe(request.getParameter("template_uuid")).trim();

  if ("POST".equalsIgnoreCase(request.getMethod()) && "save_case_fields".equalsIgnoreCase(safe(request.getParameter("action")).trim())) {
    selectedMatterUuid = safe(request.getParameter("matter_uuid")).trim();
    selectedTemplateUuid = safe(request.getParameter("template_uuid")).trim();
    try {
      String[] keys = request.getParameterValues("case_field_key");
      String[] vals = request.getParameterValues("case_field_value");
      LinkedHashMap<String,String> in = new LinkedHashMap<String,String>();
      int n = Math.max(keys == null ? 0 : keys.length, vals == null ? 0 : vals.length);
      for (int i = 0; i < n; i++) {
        String k = (keys != null && i < keys.length) ? safe(keys[i]) : "";
        String v = (vals != null && i < vals.length) ? safe(vals[i]) : "";
        in.put(k, v);
      }
      caseStore.write(tenantUuid, selectedMatterUuid, in);
      response.sendRedirect(ctx + "/case_fields.jsp?matter_uuid=" + enc(selectedMatterUuid) + "&template_uuid=" + enc(selectedTemplateUuid) + "&fields_saved=1");
      return;
    } catch (Exception ex) {
      error = "Unable to save case fields: " + safe(ex.getMessage());
    }
  }

  if ("1".equals(request.getParameter("fields_saved"))) message = "Case replacement fields saved.";

  List<matters.MatterRec> allCases = new ArrayList<matters.MatterRec>();
  try { allCases = matterStore.listAll(tenantUuid); } catch (Exception ignored) {}

  List<matters.MatterRec> activeCases = new ArrayList<matters.MatterRec>();
  for (int i = 0; i < allCases.size(); i++) {
    matters.MatterRec c = allCases.get(i);
    if (c == null) continue;
    if (!c.trashed) activeCases.add(c);
  }

  if (selectedMatterUuid.isBlank() && !activeCases.isEmpty()) selectedMatterUuid = safe(activeCases.get(0).uuid);

  List<form_templates.TemplateRec> templates = new ArrayList<form_templates.TemplateRec>();
  try { templates = templateStore.list(tenantUuid); } catch (Exception ignored) {}
  if (selectedTemplateUuid.isBlank() && !templates.isEmpty()) selectedTemplateUuid = safe(templates.get(0).uuid);

  matters.MatterRec selectedCase = null;
  try { selectedCase = matterStore.getByUuid(tenantUuid, selectedMatterUuid); } catch (Exception ignored) {}
  if (selectedCase == null && !activeCases.isEmpty()) selectedCase = activeCases.get(0);
  if (selectedCase != null) selectedMatterUuid = safe(selectedCase.uuid);

  LinkedHashMap<String,String> caseKv = new LinkedHashMap<String,String>();
  if (selectedCase != null) {
    try { caseKv.putAll(caseStore.read(tenantUuid, selectedCase.uuid)); } catch (Exception ignored) {}
  }

  int caseFieldRows = Math.max(4, caseKv.size() + 1);
%>

<jsp:include page="header.jsp" />

<section class="card">
  <h1 style="margin:0;">Case Fields</h1>
  <div class="meta" style="margin-top:4px;">Edit case-specific replacement values used in form assembly.</div>

  <% if (message != null) { %>
    <div class="alert alert-ok" style="margin-top:12px;"><%= esc(message) %></div>
  <% } %>
  <% if (error != null) { %>
    <div class="alert alert-error" style="margin-top:12px;"><%= esc(error) %></div>
  <% } %>
</section>

<section class="card" style="margin-top:12px;">
  <form class="form" method="get" action="<%= ctx %>/case_fields.jsp">
    <div class="grid" style="display:grid; grid-template-columns: 2fr 2fr auto; gap:12px;">
      <label>
        <span>Case</span>
        <select name="matter_uuid">
          <% if (activeCases.isEmpty()) { %>
            <option value="">No active cases</option>
          <% } else {
               for (int i = 0; i < activeCases.size(); i++) {
                 matters.MatterRec c = activeCases.get(i);
                 if (c == null) continue;
                 String id = safe(c.uuid);
                 boolean sel = id.equals(safe(selectedMatterUuid));
          %>
            <option value="<%= esc(id) %>" <%= sel ? "selected" : "" %>><%= esc(safe(c.label)) %></option>
          <%   }
             } %>
        </select>
      </label>

      <label>
        <span>Template Context</span>
        <select name="template_uuid">
          <% if (templates.isEmpty()) { %>
            <option value="">No templates</option>
          <% } else {
               for (int i = 0; i < templates.size(); i++) {
                 form_templates.TemplateRec t = templates.get(i);
                 if (t == null) continue;
                 String id = safe(t.uuid);
                 boolean sel = id.equals(safe(selectedTemplateUuid));
          %>
            <option value="<%= esc(id) %>" <%= sel ? "selected" : "" %>>
              <%= esc(safe(t.label)) %> (<%= esc(safe(t.fileExt).toUpperCase(Locale.ROOT)) %>)
            </option>
          <%   }
             } %>
        </select>
      </label>

      <label>
        <span>&nbsp;</span>
        <button class="btn" type="submit">Load</button>
      </label>
    </div>
  </form>

  <div class="actions" style="display:flex; gap:10px; margin-top:10px; flex-wrap:wrap;">
    <a class="btn btn-ghost" href="<%= ctx %>/forms.jsp?matter_uuid=<%= enc(selectedMatterUuid) %>&template_uuid=<%= enc(selectedTemplateUuid) %>">Back To Form Assembly</a>
    <a class="btn btn-ghost" href="<%= ctx %>/case_lists.jsp?matter_uuid=<%= enc(selectedMatterUuid) %>&template_uuid=<%= enc(selectedTemplateUuid) %>">Case Lists/Grids</a>
    <a class="btn btn-ghost" href="<%= ctx %>/template_library.jsp?matter_uuid=<%= enc(selectedMatterUuid) %>&template_uuid=<%= enc(selectedTemplateUuid) %>">Template Library</a>
    <a class="btn btn-ghost" href="<%= ctx %>/token_guide.jsp?matter_uuid=<%= enc(selectedMatterUuid) %>&template_uuid=<%= enc(selectedTemplateUuid) %>">Token Guide</a>
  </div>
</section>

<section class="card" style="margin-top:12px;">
  <% if (selectedCase == null) { %>
    <div class="muted">Select or create a case first.</div>
  <% } else { %>
    <%
      String caseCause = safe(caseKv.get("cause_docket_number"));
      if (caseCause.isBlank()) caseCause = safe(selectedCase.causeDocketNumber);
    %>
    <div class="meta" style="margin-bottom:8px;">
      Case: <strong><%= esc(safe(selectedCase.label)) %></strong>
      <% if (!caseCause.isBlank()) { %>
        • <%= esc(caseCause) %>
      <% } %>
    </div>

    <form class="form" method="post" action="<%= ctx %>/case_fields.jsp">
      <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
      <input type="hidden" name="action" value="save_case_fields" />
      <input type="hidden" name="matter_uuid" value="<%= esc(selectedMatterUuid) %>" />
      <input type="hidden" name="template_uuid" value="<%= esc(selectedTemplateUuid) %>" />

      <div class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th style="width:38%;">Key</th>
              <th>Value</th>
              <th style="width:96px;">&nbsp;</th>
            </tr>
          </thead>
          <tbody id="caseFieldRows">
          <%
            int idx = 0;
            for (Map.Entry<String,String> e : caseKv.entrySet()) {
              if (e == null) continue;
          %>
            <tr>
              <td><input type="text" name="case_field_key" value="<%= esc(safe(e.getKey())) %>" /></td>
              <td><input type="text" name="case_field_value" value="<%= esc(safe(e.getValue())) %>" /></td>
              <td><button type="button" class="btn btn-ghost" onclick="removeRow(this)">Remove</button></td>
            </tr>
          <%
              idx++;
            }
            for (; idx < caseFieldRows; idx++) {
          %>
            <tr>
              <td><input type="text" name="case_field_key" value="" /></td>
              <td><input type="text" name="case_field_value" value="" /></td>
              <td><button type="button" class="btn btn-ghost" onclick="removeRow(this)">Remove</button></td>
            </tr>
          <% } %>
          </tbody>
        </table>
      </div>

      <div class="actions" style="display:flex; gap:10px; margin-top:10px;">
        <button type="button" class="btn btn-ghost" onclick="addCaseFieldRow()">Add Field</button>
        <button type="submit" class="btn">Save Case Fields</button>
      </div>
    </form>
  <% } %>
</section>

<script>
  function addCaseFieldRow() {
    var tbody = document.getElementById("caseFieldRows");
    if (!tbody) return;
    var tr = document.createElement("tr");
    tr.innerHTML =
      '<td><input type="text" name="case_field_key" value="" /></td>' +
      '<td><input type="text" name="case_field_value" value="" /></td>' +
      '<td><button type="button" class="btn btn-ghost" onclick="removeRow(this)">Remove</button></td>';
    tbody.appendChild(tr);
  }

  function removeRow(btn) {
    if (!btn) return;
    var tr = btn.closest("tr");
    if (!tr) return;
    tr.remove();
  }
</script>

<jsp:include page="footer.jsp" />
