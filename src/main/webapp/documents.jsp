<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ page import="java.util.ArrayList" %>
<%@ page import="java.util.List" %>
<%@ page import="net.familylawandprobate.controversies.matters" %>
<%@ page import="net.familylawandprobate.controversies.documents" %>
<%@ page import="net.familylawandprobate.controversies.document_taxonomy" %>
<%@ include file="security.jspf" %>
<% if (!require_login()) return; %>
<%!
  private static final String S_TENANT_UUID = "tenant.uuid";
  private static String safe(String s){ return s == null ? "" : s; }
  private static String esc(String s){ return safe(s).replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;"); }
%>
<%
String ctx = safe(request.getContextPath());
String tenantUuid = safe((String)session.getAttribute(S_TENANT_UUID)).trim();
if (tenantUuid.isBlank()) { response.sendRedirect(ctx + "/tenant_login.jsp"); return; }
matters matterStore = matters.defaultStore();
documents docStore = documents.defaultStore();
document_taxonomy taxonomyStore = document_taxonomy.defaultStore();
List<matters.MatterRec> cases = matterStore.listAll(tenantUuid);
String caseUuid = safe(request.getParameter("case_uuid")).trim();
if (caseUuid.isBlank() && !cases.isEmpty()) caseUuid = safe(cases.get(0).uuid);
String message = null; String error = null;
if ("POST".equalsIgnoreCase(request.getMethod())) {
  String action = safe(request.getParameter("action")).trim();
  try {
    if ("add_taxonomy".equals(action)) {
      taxonomyStore.addValues(tenantUuid,
        java.util.Arrays.asList(safe(request.getParameter("category"))),
        java.util.Arrays.asList(safe(request.getParameter("subcategory"))),
        java.util.Arrays.asList(safe(request.getParameter("status"))));
      response.sendRedirect(ctx + "/documents.jsp?case_uuid=" + java.net.URLEncoder.encode(caseUuid, java.nio.charset.StandardCharsets.UTF_8) + "&tax_saved=1");
      return;
    }
    if ("create_document".equals(action)) {
      docStore.create(tenantUuid, caseUuid, request.getParameter("title"), request.getParameter("category"), request.getParameter("subcategory"), request.getParameter("status"), request.getParameter("owner"), request.getParameter("privilege_level"), request.getParameter("filed_on"), request.getParameter("external_reference"), request.getParameter("notes"));
      response.sendRedirect(ctx + "/documents.jsp?case_uuid=" + java.net.URLEncoder.encode(caseUuid, java.nio.charset.StandardCharsets.UTF_8) + "&saved=1");
      return;
    }
    if ("archive_document".equals(action)) {
      docStore.setTrashed(tenantUuid, caseUuid, request.getParameter("uuid"), true);
      response.sendRedirect(ctx + "/documents.jsp?case_uuid=" + java.net.URLEncoder.encode(caseUuid, java.nio.charset.StandardCharsets.UTF_8) + "&saved=1");
      return;
    }
  } catch (Exception ex) { error = "Unable to save: " + safe(ex.getMessage()); }
}
if ("1".equals(request.getParameter("saved"))) message = "Document updated.";
if ("1".equals(request.getParameter("tax_saved"))) message = "Taxonomy updated.";
document_taxonomy.Taxonomy tx = taxonomyStore.read(tenantUuid);
List<documents.DocumentRec> docs = caseUuid.isBlank() ? new ArrayList<documents.DocumentRec>() : docStore.listAll(tenantUuid, caseUuid);
%>
<jsp:include page="header.jsp" />
<section class="card"><h1 style="margin:0;">Case Documents</h1><div class="meta">Legal-document index with lifecycle metadata and tenant-defined taxonomy.</div></section>
<section class="card" style="margin-top:12px;">
<form method="get" action="<%= ctx %>/documents.jsp"><label>Case</label><select name="case_uuid" onchange="this.form.submit()"><% for (matters.MatterRec c: cases){ %><option value="<%= esc(c.uuid) %>" <%= c.uuid.equals(caseUuid)?"selected":"" %>><%= esc(c.label) %></option><% } %></select></form>
<% if (message != null) { %><div class="alert alert-ok" style="margin-top:10px;"><%= esc(message) %></div><% } %>
<% if (error != null) { %><div class="alert alert-error" style="margin-top:10px;"><%= esc(error) %></div><% } %>
</section>
<section class="card" style="margin-top:12px;">
<h2 style="margin-top:0;">Manage Taxonomy</h2>
<form method="post" class="form" action="<%= ctx %>/documents.jsp?case_uuid=<%= java.net.URLEncoder.encode(caseUuid, java.nio.charset.StandardCharsets.UTF_8) %>">
<input type="hidden" name="action" value="add_taxonomy" />
<div class="grid grid-3">
<div><label>Category</label><input type="text" name="category"/></div>
<div><label>Subcategory</label><input type="text" name="subcategory"/></div>
<div><label>Status</label><input type="text" name="status"/></div>
</div><button class="btn" type="submit">Save Taxonomy Values</button></form>
</section>
<section class="card" style="margin-top:12px;">
<h2 style="margin-top:0;">Add Document</h2>
<form method="post" class="form" action="<%= ctx %>/documents.jsp?case_uuid=<%= java.net.URLEncoder.encode(caseUuid, java.nio.charset.StandardCharsets.UTF_8) %>">
<input type="hidden" name="action" value="create_document" />
<div class="grid grid-3"><div><label>Title</label><input type="text" name="title" required /></div>
<div><label>Category</label><select name="category"><option value=""></option><% for (String v: tx.categories){ %><option value="<%= esc(v) %>"><%= esc(v) %></option><% } %></select></div>
<div><label>Subcategory</label><select name="subcategory"><option value=""></option><% for (String v: tx.subcategories){ %><option value="<%= esc(v) %>"><%= esc(v) %></option><% } %></select></div>
</div>
<div class="grid grid-3"><div><label>Status</label><select name="status"><option value=""></option><% for (String v: tx.statuses){ %><option value="<%= esc(v) %>"><%= esc(v) %></option><% } %></select></div>
<div><label>Owner</label><input type="text" name="owner" /></div><div><label>Privilege</label><input type="text" name="privilege_level" placeholder="attorney_client/work_product/public" /></div></div>
<div class="grid grid-3"><div><label>Filed On</label><input type="date" name="filed_on" /></div><div><label>External Ref</label><input type="text" name="external_reference"/></div><div><label>Notes</label><input type="text" name="notes"/></div></div>
<button class="btn" type="submit">Create Document</button></form>
</section>
<section class="card" style="margin-top:12px;"><h2 style="margin-top:0;">Documents</h2>
<table class="table"><thead><tr><th>Title</th><th>Category</th><th>Status</th><th>Owner</th><th>Updated</th><th></th></tr></thead><tbody>
<% for (documents.DocumentRec d : docs) { if (d.trashed) continue; %>
<tr><td><%= esc(d.title) %></td><td><%= esc(d.category + " / " + d.subcategory) %></td><td><%= esc(d.status) %></td><td><%= esc(d.owner) %></td><td><%= esc(d.updatedAt) %></td><td><a class="btn btn-ghost" href="<%= ctx %>/parts.jsp?case_uuid=<%= java.net.URLEncoder.encode(caseUuid, java.nio.charset.StandardCharsets.UTF_8) %>&doc_uuid=<%= java.net.URLEncoder.encode(d.uuid, java.nio.charset.StandardCharsets.UTF_8) %>">Parts</a></td></tr>
<% } %>
</tbody></table></section>
<jsp:include page="footer.jsp" />
