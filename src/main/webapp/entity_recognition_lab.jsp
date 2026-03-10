<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ page import="java.util.ArrayList" %>
<%@ page import="net.familylawandprobate.controversies.entity_recognition_service" %>
<%@ include file="security.jspf" %>
<% if (!require_login()) return; %>
<% if (!require_permission("conflicts.access")) return; %>
<%!
  private static String safe(String s) { return s == null ? "" : s; }
  private static String csrfForRender(jakarta.servlet.http.HttpServletRequest req) {
    Object a = req == null ? null : req.getAttribute("csrfToken");
    if (a instanceof String s && !safe(s).trim().isBlank()) return s;
    try {
      jakarta.servlet.http.HttpSession sess = req == null ? null : req.getSession(false);
      Object t = sess == null ? null : sess.getAttribute("CSRF_TOKEN");
      if (t instanceof String cs && !safe(cs).trim().isBlank()) return cs;
    } catch (Exception ignored) {}
    return "";
  }
  private static String esc(String s) {
    return safe(s)
      .replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")
      .replace("'", "&#39;");
  }
%>
<%
String ctx = safe(request.getContextPath());
request.setAttribute("activeNav", "/entity_recognition_lab.jsp");

String action = safe(request.getParameter("action")).trim();
String inputText = safe(request.getParameter("input_text"));
String csrfToken = csrfForRender(request);
String error = "";
ArrayList<entity_recognition_service.EntityHit> hits = new ArrayList<entity_recognition_service.EntityHit>();
int personCount = 0;
int orgCount = 0;

if ("extract".equalsIgnoreCase(action)) {
  try {
    hits = entity_recognition_service.defaultService().extractPersonAndOrganizations(inputText);
    for (entity_recognition_service.EntityHit hit : hits) {
      if (hit == null) continue;
      if ("person".equalsIgnoreCase(safe(hit.entityType))) personCount++;
      if ("organization".equalsIgnoreCase(safe(hit.entityType))) orgCount++;
    }
  } catch (Exception ex) {
    error = safe(ex.getMessage());
    if (error.isBlank()) error = "Entity extraction failed.";
  }
}
%>
<jsp:include page="header.jsp" />

<section class="card">
  <h1 class="u-m-0">Entity Recognition Lab</h1>
  <div class="meta u-mt-6">Run <code>entity_recognition_service</code> against sample legal text and inspect extracted person/organization entities.</div>
</section>

<section class="card section-gap-12">
  <form method="post" action="<%= ctx %>/entity_recognition_lab.jsp" class="form">
    <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
    <input type="hidden" name="action" value="extract" />
    <label for="input_text">Source Text</label>
    <textarea id="input_text" name="input_text" rows="14" placeholder="Paste filing text, notes, or intake content here..."><%= esc(inputText) %></textarea>
    <div class="meta">The recognizer caps processing at 3,000,000 characters.</div>
    <div class="u-flex u-gap-8 u-items-center u-wrap">
      <button class="btn" type="submit">Extract Entities</button>
      <% if ("extract".equalsIgnoreCase(action) && error.isBlank()) { %>
        <span class="meta">Found <strong><%= hits.size() %></strong> entities (<%= personCount %> people, <%= orgCount %> organizations).</span>
      <% } %>
    </div>
  </form>
</section>

<% if (!error.isBlank()) { %>
<section class="card section-gap-12">
  <div class="alert alert-error"><%= esc(error) %></div>
</section>
<% } %>

<% if ("extract".equalsIgnoreCase(action) && error.isBlank()) { %>
<section class="card section-gap-12">
  <h2 class="u-m-0">Results</h2>
  <div class="table-wrap table-wrap-tight u-mt-8">
    <table class="table">
      <thead>
      <tr>
        <th>Type</th>
        <th>Value</th>
        <th>Normalized</th>
        <th>Engine</th>
      </tr>
      </thead>
      <tbody>
      <% if (hits.isEmpty()) { %>
      <tr><td colspan="4" class="meta">No entities matched.</td></tr>
      <% } else {
           for (entity_recognition_service.EntityHit hit : hits) {
             if (hit == null) continue;
      %>
      <tr>
        <td><%= esc(safe(hit.entityType)) %></td>
        <td><%= esc(safe(hit.value)) %></td>
        <td><code><%= esc(safe(hit.normalized)) %></code></td>
        <td><%= esc(safe(hit.engine)) %></td>
      </tr>
      <%   }
         } %>
      </tbody>
    </table>
  </div>
</section>
<% } %>

<jsp:include page="footer.jsp" />
