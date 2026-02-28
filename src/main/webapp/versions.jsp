<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ page import="java.util.List" %>
<%@ page import="net.familylawandprobate.controversies.part_versions" %>
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
String partUuid = safe(request.getParameter("part_uuid")).trim();
part_versions versions = part_versions.defaultStore();
document_parts parts = document_parts.defaultStore();
String error = null;
if ("POST".equalsIgnoreCase(request.getMethod())) {
  try {
    versions.create(tenantUuid, caseUuid, docUuid, partUuid,
      request.getParameter("version_label"), request.getParameter("source"), request.getParameter("mime_type"),
      request.getParameter("checksum"), request.getParameter("file_size_bytes"), request.getParameter("storage_path"),
      request.getParameter("created_by"), request.getParameter("notes"), "1".equals(request.getParameter("make_current")));
    response.sendRedirect(ctx + "/versions.jsp?case_uuid=" + java.net.URLEncoder.encode(caseUuid, java.nio.charset.StandardCharsets.UTF_8) + "&doc_uuid=" + java.net.URLEncoder.encode(docUuid, java.nio.charset.StandardCharsets.UTF_8) + "&part_uuid=" + java.net.URLEncoder.encode(partUuid, java.nio.charset.StandardCharsets.UTF_8));
    return;
  } catch (Exception ex) { error = safe(ex.getMessage()); }
}
List<part_versions.VersionRec> rows = versions.listAll(tenantUuid, caseUuid, docUuid, partUuid);
document_parts.PartRec part = parts.get(tenantUuid, caseUuid, docUuid, partUuid);
%>
<jsp:include page="header.jsp" />
<section class="card"><h1 style="margin:0;">Part Versions</h1><div class="meta">Part: <strong><%= esc(part == null ? "" : part.label) %></strong></div></section>
<section class="card" style="margin-top:12px;">
<form class="form" method="post" action="<%= ctx %>/versions.jsp?case_uuid=<%= java.net.URLEncoder.encode(caseUuid, java.nio.charset.StandardCharsets.UTF_8) %>&doc_uuid=<%= java.net.URLEncoder.encode(docUuid, java.nio.charset.StandardCharsets.UTF_8) %>&part_uuid=<%= java.net.URLEncoder.encode(partUuid, java.nio.charset.StandardCharsets.UTF_8) %>">
<div class="grid grid-3"><div><label>Version Label</label><input type="text" name="version_label" required /></div><div><label>Source</label><input type="text" name="source" placeholder="uploaded/ocr/generated" /></div><div><label>MIME Type</label><input type="text" name="mime_type" placeholder="application/pdf"/></div></div>
<div class="grid grid-3"><div><label>Checksum</label><input type="text" name="checksum"/></div><div><label>File Size (bytes)</label><input type="text" name="file_size_bytes"/></div><div><label>Storage Path</label><input type="text" name="storage_path" placeholder="vault://..."/></div></div>
<div class="grid grid-3"><div><label>Created By</label><input type="text" name="created_by"/></div><div><label>Notes</label><input type="text" name="notes"/></div><div><label><input type="checkbox" name="make_current" value="1" checked /> Mark current</label></div></div>
<button class="btn" type="submit">Add Version</button>
</form>
<% if (!safe(error).isBlank()) { %><div class="alert alert-error" style="margin-top:10px;"><%= esc(error) %></div><% } %>
</section>
<section class="card" style="margin-top:12px;"><table class="table"><thead><tr><th>Version</th><th>Source</th><th>MIME</th><th>Checksum</th><th>Created</th><th>Current</th></tr></thead><tbody>
<% for (part_versions.VersionRec r : rows) { %>
<tr><td><%= esc(r.versionLabel) %></td><td><%= esc(r.source) %></td><td><%= esc(r.mimeType) %></td><td><%= esc(r.checksum) %></td><td><%= esc(r.createdAt) %></td><td><%= r.current ? "Yes" : "" %></td></tr>
<% } %>
</tbody></table></section>
<jsp:include page="footer.jsp" />
