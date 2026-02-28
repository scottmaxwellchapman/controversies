<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ page import="java.util.List" %>
<%@ page import="net.familylawandprobate.controversies.documents" %>
<%@ page import="net.familylawandprobate.controversies.document_parts" %>
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
String caseUuid = safe(request.getParameter("case_uuid")).trim();
String docUuid = safe(request.getParameter("doc_uuid")).trim();
documents docs = documents.defaultStore();
document_parts parts = document_parts.defaultStore();
String error = null;
if ("POST".equalsIgnoreCase(request.getMethod())) {
  String action = safe(request.getParameter("action")).trim();
  try {
    if ("create_part".equals(action)) {
      parts.create(tenantUuid, caseUuid, docUuid, request.getParameter("label"), request.getParameter("part_type"), request.getParameter("status"), request.getParameter("sequence"), request.getParameter("confidentiality"), request.getParameter("author"), request.getParameter("notes"));
      response.sendRedirect(ctx + "/parts.jsp?case_uuid=" + java.net.URLEncoder.encode(caseUuid, java.nio.charset.StandardCharsets.UTF_8) + "&doc_uuid=" + java.net.URLEncoder.encode(docUuid, java.nio.charset.StandardCharsets.UTF_8));
      return;
    }
  } catch (Exception ex) { error = safe(ex.getMessage()); }
}
List<document_parts.PartRec> rows = parts.listAll(tenantUuid, caseUuid, docUuid);
documents.DocumentRec doc = docs.get(tenantUuid, caseUuid, docUuid);
%>
<jsp:include page="header.jsp" />
<section class="card"><h1 style="margin:0;">Document Parts</h1><div class="meta">Document: <strong><%= esc(doc == null ? "" : doc.title) %></strong></div></section>
<section class="card" style="margin-top:12px;">
<form class="form" method="post" action="<%= ctx %>/parts.jsp?case_uuid=<%= java.net.URLEncoder.encode(caseUuid, java.nio.charset.StandardCharsets.UTF_8) %>&doc_uuid=<%= java.net.URLEncoder.encode(docUuid, java.nio.charset.StandardCharsets.UTF_8) %>">
<input type="hidden" name="action" value="create_part" />
<div class="grid grid-3"><div><label>Label</label><input type="text" name="label" required/></div><div><label>Type</label><input type="text" name="part_type" placeholder="pleading/exhibit/affidavit"/></div><div><label>Status</label><input type="text" name="status" placeholder="draft/final/executed"/></div></div>
<div class="grid grid-3"><div><label>Sequence</label><input type="text" name="sequence" placeholder="1.0"/></div><div><label>Confidentiality</label><input type="text" name="confidentiality" placeholder="public/confidential/sealed"/></div><div><label>Author</label><input type="text" name="author" /></div></div>
<div><label>Notes</label><input type="text" name="notes" /></div>
<button class="btn" type="submit">Add Part</button>
</form>
<% if (!safe(error).isBlank()) { %><div class="alert alert-error" style="margin-top:10px;"><%= esc(error) %></div><% } %>
</section>
<section class="card" style="margin-top:12px;"><table class="table"><thead><tr><th>Label</th><th>Type</th><th>Status</th><th>Seq</th><th>Updated</th><th></th></tr></thead><tbody>
<% for (document_parts.PartRec r : rows) { if (r.trashed) continue; %>
<tr><td><%= esc(r.label) %></td><td><%= esc(r.partType) %></td><td><%= esc(r.status) %></td><td><%= esc(r.sequence) %></td><td><%= esc(r.updatedAt) %></td><td><a class="btn btn-ghost" href="<%= ctx %>/versions.jsp?case_uuid=<%= java.net.URLEncoder.encode(caseUuid, java.nio.charset.StandardCharsets.UTF_8) %>&doc_uuid=<%= java.net.URLEncoder.encode(docUuid, java.nio.charset.StandardCharsets.UTF_8) %>&part_uuid=<%= java.net.URLEncoder.encode(r.uuid, java.nio.charset.StandardCharsets.UTF_8) %>">Versions</a></td></tr>
<% } %>
</tbody></table></section>
<jsp:include page="footer.jsp" />
