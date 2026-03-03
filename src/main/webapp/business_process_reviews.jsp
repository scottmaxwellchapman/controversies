<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" isELIgnored="true" %>

<%@ page import="com.fasterxml.jackson.core.type.TypeReference" %>
<%@ page import="com.fasterxml.jackson.databind.DeserializationFeature" %>
<%@ page import="com.fasterxml.jackson.databind.ObjectMapper" %>
<%@ page import="java.net.URLEncoder" %>
<%@ page import="java.nio.charset.StandardCharsets" %>
<%@ page import="java.util.ArrayList" %>
<%@ page import="java.util.LinkedHashMap" %>
<%@ page import="java.util.List" %>
<%@ page import="java.util.Locale" %>
<%@ page import="java.util.Map" %>

<%@ page import="net.familylawandprobate.controversies.business_process_manager" %>

<%@ include file="security.jspf" %>
<%
  if (!require_login()) return;
  if (!require_permission("tenant_admin")) return;
%>

<%!
  private static final String S_TENANT_UUID = "tenant.uuid";
  private static final String S_USER_UUID = "user.uuid";
  private static final String CSRF_SESSION_KEY = "CSRF_TOKEN";

  private static final ObjectMapper JSON = new ObjectMapper()
      .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
  private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<LinkedHashMap<String, Object>>() {};

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

  private static LinkedHashMap<String, String> stringMap(Object raw) {
    LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
    if (!(raw instanceof Map<?, ?> m)) return out;
    for (Map.Entry<?, ?> e : m.entrySet()) {
      if (e == null) continue;
      String k = safe(String.valueOf(e.getKey())).trim();
      if (k.isBlank()) continue;
      out.put(k, safe(String.valueOf(e.getValue())));
    }
    return out;
  }

  private static String suggestedInputJson(List<String> requiredKeys) {
    if (requiredKeys == null || requiredKeys.isEmpty()) return "{}";
    StringBuilder sb = new StringBuilder();
    sb.append("{\n");
    for (int i = 0; i < requiredKeys.size(); i++) {
      String key = safe(requiredKeys.get(i)).trim();
      if (key.isBlank()) continue;
      sb.append("  \"").append(key.replace("\"","\\\"")).append("\": \"\"");
      if (i + 1 < requiredKeys.size()) sb.append(",");
      sb.append("\n");
    }
    sb.append("}");
    return sb.toString();
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

  String csrfToken = csrfForRender(request);
  business_process_manager bpm = business_process_manager.defaultService();
  try { bpm.ensureTenant(tenantUuid); } catch (Exception ignored) {}

  if ("POST".equalsIgnoreCase(request.getMethod())) {
    String action = safe(request.getParameter("action")).trim();
    try {
      if ("complete_review".equalsIgnoreCase(action)) {
        String reviewUuid = safe(request.getParameter("review_uuid")).trim();
        boolean approved = "approve".equalsIgnoreCase(safe(request.getParameter("decision")));
        String inputJson = safe(request.getParameter("input_json")).trim();
        LinkedHashMap<String, String> input = new LinkedHashMap<String, String>();
        if (!inputJson.isBlank()) input = stringMap(JSON.readValue(inputJson, MAP_TYPE));

        bpm.completeReview(
          tenantUuid,
          reviewUuid,
          approved,
          userUuid,
          input,
          safe(request.getParameter("comment"))
        );

        response.sendRedirect(ctx + "/business_process_reviews.jsp?done=1");
        return;
      }
    } catch (Exception ex) {
      response.sendRedirect(ctx + "/business_process_reviews.jsp?error=" + enc(safe(ex.getMessage())));
      return;
    }
  }

  String message = "1".equals(request.getParameter("done")) ? "Review decision saved." : null;
  String error = safe(request.getParameter("error")).trim();

  List<business_process_manager.HumanReviewTask> pending = bpm.listReviews(tenantUuid, true, 200);
  List<business_process_manager.HumanReviewTask> recent = bpm.listReviews(tenantUuid, false, 200);
%>

<jsp:include page="header.jsp" />

<section class="card">
  <h1 style="margin:0;">Business Process Human Review Queue</h1>
  <div class="meta" style="margin-top:6px;">
    Resolve human-review actions raised by automated business processes. Approving resumes the remaining steps.
  </div>

  <% if (message != null) { %>
    <div class="alert alert-ok" style="margin-top:12px;"><%= esc(message) %></div>
  <% } %>
  <% if (!error.isBlank()) { %>
    <div class="alert alert-error" style="margin-top:12px;"><%= esc(error) %></div>
  <% } %>

  <div style="margin-top:10px;">
    <a class="btn btn-ghost" href="<%= ctx %>/business_processes.jsp">Back to Business Processes</a>
  </div>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin:0 0 8px 0;">Pending Reviews (<%= pending.size() %>)</h2>

  <% if (pending.isEmpty()) { %>
    <div class="meta">No pending reviews.</div>
  <% } %>

  <% for (business_process_manager.HumanReviewTask r : pending) {
       if (r == null) continue;
  %>
    <div class="card" style="margin-top:10px; border:1px solid rgba(100,116,139,0.22);">
      <h3 style="margin:0;"><%= esc(safe(r.title)) %></h3>
      <div class="meta" style="margin-top:4px;">
        Process: <strong><%= esc(safe(r.processName)) %></strong>
        (<code><%= esc(safe(r.processUuid)) %></code>)
      </div>
      <div class="meta" style="margin-top:4px;">
        Review UUID: <code><%= esc(safe(r.reviewUuid)) %></code> • Event: <code><%= esc(safe(r.eventType)) %></code>
      </div>
      <div class="meta" style="margin-top:4px;">
        Created: <%= esc(safe(r.createdAt)) %> • Requested by: <code><%= esc(safe(r.requestedByUserUuid)) %></code>
      </div>

      <% if (!safe(r.instructions).isBlank()) { %>
        <div style="margin-top:8px;"><%= esc(safe(r.instructions)) %></div>
      <% } %>

      <form method="post" action="<%= ctx %>/business_process_reviews.jsp" style="margin-top:10px;">
        <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
        <input type="hidden" name="action" value="complete_review" />
        <input type="hidden" name="review_uuid" value="<%= esc(safe(r.reviewUuid)) %>" />

        <label>
          <div class="meta">Decision</div>
          <select name="decision">
            <option value="approve" selected>Approve</option>
            <option value="reject">Reject</option>
          </select>
        </label>

        <label style="display:block; margin-top:8px;">
          <div class="meta">Input JSON</div>
          <textarea name="input_json" rows="4" style="font-family:ui-monospace, SFMono-Regular, Menlo, monospace;"><%= esc(suggestedInputJson(r.requiredInputKeys)) %></textarea>
        </label>

        <label style="display:block; margin-top:8px;">
          <div class="meta">Comment</div>
          <textarea name="comment" rows="2"></textarea>
        </label>

        <div class="actions" style="display:flex; gap:8px; margin-top:8px;">
          <button class="btn" type="submit">Submit Decision</button>
        </div>
      </form>
    </div>
  <% } %>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin:0 0 8px 0;">Recent Review Activity</h2>
  <div class="table-wrap">
    <table class="table">
      <thead>
        <tr>
          <th>Created</th>
          <th>Process</th>
          <th>Status</th>
          <th>Resolved</th>
          <th>Resume Status</th>
        </tr>
      </thead>
      <tbody>
      <% for (business_process_manager.HumanReviewTask r : recent) {
           if (r == null) continue;
      %>
        <tr>
          <td><%= esc(safe(r.createdAt)) %></td>
          <td><%= esc(safe(r.processName)) %></td>
          <td><%= esc(safe(r.status)) %></td>
          <td><%= esc(safe(r.resolvedAt)) %></td>
          <td><%= esc(safe(r.resumeStatus)) %></td>
        </tr>
      <% } %>
      </tbody>
    </table>
  </div>
</section>

<jsp:include page="footer.jsp" />
