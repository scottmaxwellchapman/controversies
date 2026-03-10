<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ page import="java.util.Locale" %>
<%@ page import="net.familylawandprobate.controversies.search_query_operator" %>
<%@ page import="net.familylawandprobate.controversies.users_roles" %>
<%@ include file="security.jspf" %>
<% if (!require_login()) return; %>
<%
jakarta.servlet.http.HttpSession sess = request.getSession(false);
boolean canDocuments = users_roles.hasPermissionTrue(sess, "documents.access");
boolean canConflicts = users_roles.hasPermissionTrue(sess, "conflicts.access");
if (!canDocuments && !canConflicts) {
  if (!require_permission("documents.access")) return;
}
%>
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
request.setAttribute("activeNav", "/search_operator_lab.jsp");
String csrfToken = csrfForRender(request);

String action = safe(request.getParameter("action")).trim().toLowerCase(Locale.ROOT);
String haystack = safe(request.getParameter("haystack"));
String query = safe(request.getParameter("query"));
String opKey = safe(request.getParameter("operator"));
boolean caseSensitive = "1".equals(safe(request.getParameter("case_sensitive"))) || "on".equalsIgnoreCase(safe(request.getParameter("case_sensitive")));

search_query_operator selectedOp = search_query_operator.fromKey(opKey);
boolean ran = "run".equals(action);
boolean matched = false;
String error = "";

if (ran) {
  try {
    matched = selectedOp.matches(haystack, query, caseSensitive);
  } catch (Exception ex) {
    error = safe(ex.getMessage());
    if (error.isBlank()) error = "Operator evaluation failed.";
  }
}
%>
<jsp:include page="header.jsp" />

<section class="card">
  <h1 class="u-m-0">Search Operator Lab</h1>
  <div class="meta u-mt-6">Test <code>search_query_operator</code> rules against sample text and query strings before using them in search jobs.</div>
</section>

<section class="card section-gap-12">
  <form method="post" action="<%= ctx %>/search_operator_lab.jsp" class="form">
    <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
    <input type="hidden" name="action" value="run" />

    <div class="grid grid-3">
      <div>
        <label for="operator">Operator</label>
        <select id="operator" name="operator">
          <% for (search_query_operator op : search_query_operator.values()) {
               String key = safe(op.key());
               boolean sel = key.equalsIgnoreCase(safe(selectedOp.key()));
          %>
            <option value="<%= esc(key) %>" <%= sel ? "selected" : "" %>><%= esc(op.label()) %></option>
          <% } %>
        </select>
      </div>
      <div class="u-flex u-items-end">
        <label><input type="checkbox" name="case_sensitive" value="1" <%= caseSensitive ? "checked" : "" %> /> Case Sensitive</label>
      </div>
    </div>

    <label for="query">Query</label>
    <input id="query" name="query" type="text" value="<%= esc(query) %>" placeholder="Query text or regex pattern" />

    <label for="haystack">Haystack Text</label>
    <textarea id="haystack" name="haystack" rows="12" placeholder="Text to evaluate"><%= esc(haystack) %></textarea>

    <div class="u-flex u-gap-8 u-items-center u-wrap">
      <button class="btn" type="submit">Run Operator</button>
      <span class="meta">Operator key: <code><%= esc(selectedOp.key()) %></code></span>
    </div>
  </form>
</section>

<% if (!error.isBlank()) { %>
<section class="card section-gap-12">
  <div class="alert alert-error"><%= esc(error) %></div>
</section>
<% } %>

<% if (ran && error.isBlank()) { %>
<section class="card section-gap-12">
  <h2 class="u-m-0">Result</h2>
  <div class="u-mt-8">
    <strong><%= matched ? "MATCH" : "NO MATCH" %></strong>
    <span class="meta"> using <code><%= esc(selectedOp.key()) %></code> (<%= caseSensitive ? "case-sensitive" : "case-insensitive" %>).</span>
  </div>
</section>
<% } %>

<jsp:include page="footer.jsp" />
