<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" isELIgnored="true" %>

<%@ page import="java.net.URLEncoder" %>
<%@ page import="java.nio.charset.StandardCharsets" %>
<%@ page import="java.util.ArrayList" %>
<%@ page import="java.util.Collections" %>
<%@ page import="java.util.Comparator" %>
<%@ page import="java.util.LinkedHashMap" %>
<%@ page import="java.util.List" %>
<%@ page import="java.util.Locale" %>
<%@ page import="java.util.Map" %>

<%@ page import="net.familylawandprobate.controversies.case_fields" %>
<%@ page import="net.familylawandprobate.controversies.form_templates" %>
<%@ page import="net.familylawandprobate.controversies.matters" %>
<%@ page import="net.familylawandprobate.controversies.tenant_fields" %>

<%@ include file="security.jspf" %>
<%
  if (!require_login()) return;
%>

<%!
  private static final String S_TENANT_UUID = "tenant.uuid";
  private static final String S_TENANT_LABEL = "tenant.label";

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

  private static String tokenPreview(String token) {
    String t = safe(token).trim();
    if (t.isBlank()) return "";
    return "{{" + t + "}}";
  }

  private static void putToken(Map<String,String> values, Map<String,String> source, String key, String value, String src) {
    if (values == null || source == null) return;
    String k = safe(key).trim();
    if (k.isBlank()) return;
    values.put(k, safe(value));
    source.put(k, safe(src));
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

  matters matterStore = matters.defaultStore();
  case_fields caseStore = case_fields.defaultStore();
  tenant_fields tenantStore = tenant_fields.defaultStore();
  form_templates templateStore = form_templates.defaultStore();

  try { matterStore.ensure(tenantUuid); } catch (Exception ignored) {}

  String selectedMatterUuid = safe(request.getParameter("matter_uuid")).trim();
  String selectedTemplateUuid = safe(request.getParameter("template_uuid")).trim();

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

  LinkedHashMap<String,String> tenantKv = new LinkedHashMap<String,String>();
  try { tenantKv.putAll(tenantStore.read(tenantUuid)); } catch (Exception ignored) {}

  LinkedHashMap<String,String> caseKv = new LinkedHashMap<String,String>();
  if (selectedCase != null) {
    try { caseKv.putAll(caseStore.read(tenantUuid, selectedCase.uuid)); } catch (Exception ignored) {}
  }

  LinkedHashMap<String,String> mergeValues = new LinkedHashMap<String,String>();
  LinkedHashMap<String,String> tokenSource = new LinkedHashMap<String,String>();

  putToken(mergeValues, tokenSource, "tenant.uuid", tenantUuid, "system");
  putToken(mergeValues, tokenSource, "tenant.label", tenantLabel, "system");

  if (selectedCase != null) {
    putToken(mergeValues, tokenSource, "case.uuid", safe(selectedCase.uuid), "system");
    putToken(mergeValues, tokenSource, "case.label", safe(selectedCase.label), "system");
    putToken(mergeValues, tokenSource, "case.cause_docket_number", safe(selectedCase.causeDocketNumber), "system");
    putToken(mergeValues, tokenSource, "case.county", safe(selectedCase.county), "system");
  }

  for (Map.Entry<String,String> e : tenantKv.entrySet()) {
    if (e == null) continue;
    String nk = tenantStore.normalizeKey(e.getKey());
    if (nk.isBlank()) continue;
    String val = safe(e.getValue());

    putToken(mergeValues, tokenSource, "tenant." + nk, val, "tenant");
    if (!mergeValues.containsKey("kv." + nk)) putToken(mergeValues, tokenSource, "kv." + nk, val, "tenant");
    if (!mergeValues.containsKey(nk)) putToken(mergeValues, tokenSource, nk, val, "tenant");
  }

  for (Map.Entry<String,String> e : caseKv.entrySet()) {
    if (e == null) continue;
    String nk = caseStore.normalizeKey(e.getKey());
    if (nk.isBlank()) continue;
    String val = safe(e.getValue());

    putToken(mergeValues, tokenSource, "case." + nk, val, "case");
    putToken(mergeValues, tokenSource, "kv." + nk, val, "case");
    putToken(mergeValues, tokenSource, nk, val, "case");
  }

  ArrayList<Map.Entry<String,String>> tokenRows = new ArrayList<Map.Entry<String,String>>(mergeValues.entrySet());
  Collections.sort(tokenRows, new Comparator<Map.Entry<String,String>>() {
    public int compare(Map.Entry<String,String> a, Map.Entry<String,String> b) {
      return safe(a == null ? "" : a.getKey()).compareToIgnoreCase(safe(b == null ? "" : b.getKey()));
    }
  });
%>

<jsp:include page="header.jsp" />

<section class="card">
  <h1 style="margin:0;">Token Guide</h1>
  <div class="meta" style="margin-top:4px;">Available tokens from system, tenant store, and selected case store.</div>
</section>

<section class="card" style="margin-top:12px;">
  <form class="form" method="get" action="<%= ctx %>/token_guide.jsp">
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
    <a class="btn btn-ghost" href="<%= ctx %>/template_library.jsp?matter_uuid=<%= enc(selectedMatterUuid) %>&template_uuid=<%= enc(selectedTemplateUuid) %>">Template Library</a>
    <a class="btn btn-ghost" href="<%= ctx %>/case_fields.jsp?matter_uuid=<%= enc(selectedMatterUuid) %>&template_uuid=<%= enc(selectedTemplateUuid) %>">Case Fields</a>
  </div>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin:0 0 6px 0;">Standard Tokens (Default)</h2>
  <div class="meta" style="margin-bottom:10px;">These are always supported and remain the default behavior for all tenants.</div>
  <div class="table-wrap">
    <table class="table">
      <thead>
        <tr>
          <th>Token</th>
          <th>Current Value</th>
          <th>Source</th>
        </tr>
      </thead>
      <tbody>
        <% if (tokenRows.isEmpty()) { %>
          <tr><td colspan="3" class="muted">No tokens available.</td></tr>
        <% } else {
             for (Map.Entry<String,String> e : tokenRows) {
               if (e == null) continue;
               String key = safe(e.getKey());
               String source = safe(tokenSource.get(key));
        %>
          <tr>
            <td><code><%= esc(tokenPreview(key)) %></code></td>
            <td><%= esc(safe(e.getValue())) %></td>
            <td><%= esc(source.isBlank() ? "derived" : source) %></td>
          </tr>
        <%   }
           } %>
      </tbody>
    </table>
  </div>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin:0 0 6px 0;">Advanced (opt-in)</h2>
  <div class="meta" style="margin-bottom:10px;">
    Advanced directives only run when tenant feature flag <code>advanced_assembly_enabled</code> is enabled.
    Otherwise they are treated as plain text and standard token replacement continues unchanged.
  </div>
  <ul style="margin:0; padding-left:18px; line-height:1.5;">
    <li><code>{{#if kv.child_support}}...{{/if}}</code> – include enclosed text only when the referenced token is truthy.</li>
    <li><code>{{#each case.parties}}Party: {{this}}&#10;{{/each}}</code> – repeat enclosed text for each list item (newline, pipe, or comma separated values).</li>
    <li><code>{{format.date case.filing_date "MM/dd/yyyy"}}</code> – render a date token with a specific output format.</li>
  </ul>
  <div class="meta" style="margin-top:10px;">
    Optional strict mode (<code>advanced_assembly_strict_mode</code>) records unresolved directives/tokens in activity logs and still produces the document.
  </div>
</section>

<jsp:include page="footer.jsp" />
