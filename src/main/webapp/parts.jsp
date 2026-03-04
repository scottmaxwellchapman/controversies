<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ page import="java.util.List" %>
<%@ page import="java.util.Locale" %>
<%@ page import="net.familylawandprobate.controversies.documents" %>
<%@ page import="net.familylawandprobate.controversies.document_parts" %>
<%@ include file="security.jspf" %>
<% if (!require_login()) return; %>
<%!
private static final String S_TENANT_UUID = "tenant.uuid";
private static final String CSRF_SESSION_KEY = "CSRF_TOKEN";
private static String safe(String s){ return s == null ? "" : s; }
private static String esc(String s){ return safe(s).replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;"); }
private static String categoryCode(String raw) {
  String v = safe(raw).trim().toLowerCase(Locale.ROOT);
  return "lead".equals(v) ? "lead" : "attachment";
}
private static String categoryLabel(String raw) {
  return "lead".equals(categoryCode(raw)) ? "Lead" : "Attachment";
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
String ctx = safe(request.getContextPath());
String csrfToken = csrfForRender(request);
String tenantUuid = safe((String)session.getAttribute(S_TENANT_UUID)).trim();
if (tenantUuid.isBlank()) { response.sendRedirect(ctx + "/tenant_login.jsp"); return; }
String caseUuid = safe(request.getParameter("case_uuid")).trim();
String docUuid = safe(request.getParameter("doc_uuid")).trim();
documents docs = documents.defaultStore();
document_parts parts = document_parts.defaultStore();
documents.DocumentRec doc = docs.get(tenantUuid, caseUuid, docUuid);
boolean docReadOnly = documents.isReadOnly(doc);
String error = null;
if ("POST".equalsIgnoreCase(request.getMethod())) {
  String action = safe(request.getParameter("action")).trim();
  try {
    if ("create_part".equals(action)) {
      if (docReadOnly) throw new IllegalStateException(documents.readOnlyMessage(doc));
      parts.create(tenantUuid, caseUuid, docUuid, request.getParameter("label"), request.getParameter("part_category"), request.getParameter("sequence"), request.getParameter("confidentiality"), request.getParameter("author"), request.getParameter("notes"));
      response.sendRedirect(ctx + "/parts.jsp?case_uuid=" + java.net.URLEncoder.encode(caseUuid, java.nio.charset.StandardCharsets.UTF_8) + "&doc_uuid=" + java.net.URLEncoder.encode(docUuid, java.nio.charset.StandardCharsets.UTF_8));
      return;
    }
  } catch (Exception ex) { error = safe(ex.getMessage()); }
}
List<document_parts.PartRec> rows = parts.listAll(tenantUuid, caseUuid, docUuid);
boolean hasActiveLead = false;
for (document_parts.PartRec r : rows) {
  if (r == null || r.trashed) continue;
  if ("lead".equals(categoryCode(r.partType))) { hasActiveLead = true; break; }
}
String requestedCategory = categoryCode(request.getParameter("part_category"));
if (hasActiveLead && "lead".equals(requestedCategory)) requestedCategory = "attachment";
%>
<jsp:include page="header.jsp" />
<section class="card"><h1 style="margin:0;">Document Parts</h1><div class="meta">Document: <strong><%= esc(doc == null ? "" : doc.title) %></strong></div></section>
<% if (docReadOnly) { %>
<section class="card" style="margin-top:12px;"><div class="alert alert-error"><%= esc(documents.readOnlyMessage(doc)) %></div></section>
<% } %>
<section class="card" style="margin-top:12px;">
<form class="form" method="post" action="<%= ctx %>/parts.jsp?case_uuid=<%= java.net.URLEncoder.encode(caseUuid, java.nio.charset.StandardCharsets.UTF_8) %>&doc_uuid=<%= java.net.URLEncoder.encode(docUuid, java.nio.charset.StandardCharsets.UTF_8) %>">
<input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
<input type="hidden" name="action" value="create_part" />
<div class="grid grid-3">
  <div><label>Label</label><input type="text" name="label" required <%= docReadOnly ? "disabled" : "" %>/></div>
  <div>
    <label>Category</label>
    <select name="part_category" <%= docReadOnly ? "disabled" : "" %>>
      <option value="lead" <%= "lead".equals(requestedCategory) ? "selected" : "" %> <%= (hasActiveLead || docReadOnly) ? "disabled" : "" %>>Lead</option>
      <option value="attachment" <%= "attachment".equals(requestedCategory) ? "selected" : "" %>>Attachment</option>
    </select>
  </div>
</div>
<div class="grid grid-3"><div><label>Sequence</label><input type="text" name="sequence" placeholder="1.0" <%= docReadOnly ? "disabled" : "" %>/></div><div><label>Confidentiality</label><input type="text" name="confidentiality" placeholder="public/confidential/sealed" <%= docReadOnly ? "disabled" : "" %>/></div><div><label>Author</label><input type="text" name="author" <%= docReadOnly ? "disabled" : "" %> /></div></div>
<div><label>Notes</label><input type="text" name="notes" <%= docReadOnly ? "disabled" : "" %> /></div>
<% if (hasActiveLead) { %>
<div class="meta">A Lead part already exists for this document. Additional parts must be Attachment.</div>
<% } else { %>
<div class="meta">Exactly one part may be categorized as Lead.</div>
<% } %>
<button class="btn" type="submit" <%= docReadOnly ? "disabled" : "" %>>Add Part</button>
</form>
<% if (!safe(error).isBlank()) { %><div class="alert alert-error" style="margin-top:10px;"><%= esc(error) %></div><% } %>
</section>
<section class="card" style="margin-top:12px;"><table class="table"><thead><tr><th>Label</th><th>Category</th><th>Seq</th><th>Updated</th><th></th></tr></thead><tbody>
<% for (document_parts.PartRec r : rows) { if (r.trashed) continue; %>
<tr><td><%= esc(r.label) %></td><td><%= esc(categoryLabel(r.partType)) %></td><td><%= esc(r.sequence) %></td><td><%= esc(r.updatedAt) %></td><td><a class="btn btn-ghost" href="<%= ctx %>/versions.jsp?case_uuid=<%= java.net.URLEncoder.encode(caseUuid, java.nio.charset.StandardCharsets.UTF_8) %>&doc_uuid=<%= java.net.URLEncoder.encode(docUuid, java.nio.charset.StandardCharsets.UTF_8) %>&part_uuid=<%= java.net.URLEncoder.encode(r.uuid, java.nio.charset.StandardCharsets.UTF_8) %>">Versions</a></td></tr>
<% } %>
</tbody></table></section>
<jsp:include page="footer.jsp" />
