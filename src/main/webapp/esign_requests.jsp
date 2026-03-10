<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" isELIgnored="true" %>
<%@ page import="java.util.ArrayList" %>
<%@ page import="java.util.List" %>
<%@ page import="java.util.Locale" %>

<%@ page import="net.familylawandprobate.controversies.esign_requests" %>
<%@ page import="net.familylawandprobate.controversies.integration_webhooks" %>
<%@ page import="net.familylawandprobate.controversies.matters" %>
<%@ page import="net.familylawandprobate.controversies.users_roles" %>

<%@ include file="security.jspf" %>
<%
  if (!require_login()) return;
%>

<%!
  private static final String S_TENANT_UUID = "tenant.uuid";
  private static final String S_USER_UUID = "user.uuid";
  private static final String CSRF_SESSION_KEY = "CSRF_TOKEN";
  private static String safe(String s){ return s == null ? "" : s; }
  private static String esc(String s){ return safe(s).replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;"); }
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
  request.setAttribute("activeNav", "/esign_requests.jsp");
  String tenantUuid = safe((String) session.getAttribute(S_TENANT_UUID)).trim();
  String userUuid = safe((String) session.getAttribute(S_USER_UUID)).trim();
  String userEmail = safe((String) session.getAttribute(users_roles.S_USER_EMAIL)).trim();
  if (tenantUuid.isBlank()) { response.sendRedirect(ctx + "/tenant_login.jsp"); return; }
  String actorUser = userUuid.isBlank() ? userEmail : userUuid;
  String csrfToken = csrfForRender(request);

  String message = "";
  String error = "";

  esign_requests store = esign_requests.defaultStore();
  List<matters.MatterRec> allMatters = new ArrayList<matters.MatterRec>();
  try { allMatters = matters.defaultStore().listAll(tenantUuid); } catch (Exception ignored) {}

  if ("POST".equalsIgnoreCase(request.getMethod())) {
    String action = safe(request.getParameter("action")).trim();
    try {
      if ("create_request".equals(action)) {
        esign_requests.CreateInput in = new esign_requests.CreateInput();
        in.providerKey = safe(request.getParameter("provider_key")).trim();
        in.providerRequestId = safe(request.getParameter("provider_request_id")).trim();
        in.matterUuid = safe(request.getParameter("matter_uuid")).trim();
        in.documentUuid = safe(request.getParameter("document_uuid")).trim();
        in.partUuid = safe(request.getParameter("part_uuid")).trim();
        in.versionUuid = safe(request.getParameter("version_uuid")).trim();
        in.subject = safe(request.getParameter("subject")).trim();
        in.toCsv = safe(request.getParameter("to")).trim();
        in.ccCsv = safe(request.getParameter("cc")).trim();
        in.bccCsv = safe(request.getParameter("bcc")).trim();
        in.signatureLink = safe(request.getParameter("signature_link")).trim();
        in.deliveryMode = safe(request.getParameter("delivery_mode")).trim();
        in.requestedByUserUuid = actorUser;
        in.status = "sent";
        esign_requests.SignatureRequestRec created = store.createRequest(tenantUuid, in);
        try {
          java.util.LinkedHashMap<String, Object> payload = new java.util.LinkedHashMap<String, Object>();
          payload.put("request_uuid", safe(created == null ? "" : created.requestUuid));
          payload.put("status", safe(created == null ? "" : created.status));
          payload.put("matter_uuid", safe(created == null ? "" : created.matterUuid));
          integration_webhooks.defaultStore().dispatchEvent(tenantUuid, "esign.request.created", payload);
        } catch (Exception ignored) {}
        message = "Signature request created.";
      } else if ("update_status".equals(action)) {
        String requestUuid = safe(request.getParameter("request_uuid")).trim();
        String status = safe(request.getParameter("status")).trim();
        String note = safe(request.getParameter("note")).trim();
        esign_requests.SignatureRequestRec updated = store.updateStatus(
                tenantUuid,
                requestUuid,
                status,
                "manual_status_update",
                note,
                actorUser,
                safe(request.getParameter("provider_request_id")).trim()
        );
        try {
          java.util.LinkedHashMap<String, Object> payload = new java.util.LinkedHashMap<String, Object>();
          payload.put("request_uuid", safe(updated == null ? "" : updated.requestUuid));
          payload.put("status", safe(updated == null ? "" : updated.status));
          payload.put("matter_uuid", safe(updated == null ? "" : updated.matterUuid));
          integration_webhooks.defaultStore().dispatchEvent(tenantUuid, "esign.request.status_changed", payload);
        } catch (Exception ignored) {}
        message = "Signature status updated.";
      }
    } catch (Exception ex) {
      error = safe(ex.getMessage()).isBlank() ? ex.getClass().getSimpleName() : safe(ex.getMessage());
    }
  }

  String filterStatus = safe(request.getParameter("status_filter")).trim().toLowerCase(Locale.ROOT);
  String filterMatter = safe(request.getParameter("matter_uuid")).trim();
  String selectedRequestUuid = safe(request.getParameter("request_uuid")).trim();
  List<esign_requests.SignatureRequestRec> rows = new ArrayList<esign_requests.SignatureRequestRec>();
  esign_requests.SignatureRequestRec selected = null;
  List<esign_requests.SignatureEventRec> selectedEvents = new ArrayList<esign_requests.SignatureEventRec>();
  try {
    for (esign_requests.SignatureRequestRec row : store.listRequests(tenantUuid)) {
      if (row == null) continue;
      if (!filterStatus.isBlank() && !filterStatus.equalsIgnoreCase(safe(row.status))) continue;
      if (!filterMatter.isBlank() && !filterMatter.equalsIgnoreCase(safe(row.matterUuid))) continue;
      rows.add(row);
    }
    if (selectedRequestUuid.isBlank() && !rows.isEmpty()) selectedRequestUuid = safe(rows.get(0).requestUuid).trim();
    if (!selectedRequestUuid.isBlank()) {
      selected = store.getRequest(tenantUuid, selectedRequestUuid);
      selectedEvents = store.listEvents(tenantUuid, selectedRequestUuid);
    }
  } catch (Exception ex) {
    if (error.isBlank()) error = "Unable to load signature requests: " + safe(ex.getMessage());
  }
%>

<jsp:include page="header.jsp" />

<section class="card">
  <h1 class="u-m-0">E-Sign Requests</h1>
  <div class="meta u-mt-6">Track request lifecycle state (`sent`, `viewed`, `signed`, `declined`, `expired`) and keep matter-linked audit history.</div>
  <% if (!message.isBlank()) { %><div class="alert alert-success u-mt-12"><%= esc(message) %></div><% } %>
  <% if (!error.isBlank()) { %><div class="alert alert-error u-mt-12"><%= esc(error) %></div><% } %>
</section>

<section class="card section-gap-12">
  <h2 class="u-m-0">Create Signature Request</h2>
  <form method="post" action="<%= ctx %>/esign_requests.jsp" class="form">
    <input type="hidden" name="csrf_token" value="<%= esc(csrfToken) %>" />
    <input type="hidden" name="action" value="create_request" />
    <div class="grid grid-3">
      <label><span>Provider</span>
        <select name="provider_key">
          <option value="manual_notice">Manual Notice</option>
          <option value="docusign_stub">DocuSign (Stub)</option>
          <option value="adobesign_stub">Adobe Sign (Stub)</option>
        </select>
      </label>
      <label><span>Matter</span>
        <select name="matter_uuid">
          <option value="">(optional)</option>
          <% for (matters.MatterRec m : allMatters) { if (m == null || m.trashed) continue; %>
          <option value="<%= esc(safe(m.uuid)) %>"><%= esc(safe(m.label)) %></option>
          <% } %>
        </select>
      </label>
      <label><span>Subject</span><input type="text" name="subject" required /></label>
      <label><span>To (CSV)</span><input type="text" name="to" placeholder="client@example.com" required /></label>
      <label><span>CC (CSV)</span><input type="text" name="cc" /></label>
      <label><span>BCC (CSV)</span><input type="text" name="bcc" /></label>
      <label><span>Signature Link</span><input type="text" name="signature_link" placeholder="https://..." /></label>
      <label><span>Document UUID</span><input type="text" name="document_uuid" /></label>
      <label><span>Part UUID</span><input type="text" name="part_uuid" /></label>
      <label><span>Version UUID</span><input type="text" name="version_uuid" /></label>
      <label><span>Provider Request ID</span><input type="text" name="provider_request_id" /></label>
      <label><span>Delivery Mode</span><input type="text" name="delivery_mode" placeholder="queue or send_now" /></label>
    </div>
    <div class="u-mt-12"><button class="btn" type="submit">Create Request</button></div>
  </form>
</section>

<section class="card section-gap-12">
  <div class="u-flex u-justify-between u-items-center u-gap-8 u-wrap">
    <h2 class="u-m-0">Request Queue</h2>
    <form method="get" action="<%= ctx %>/esign_requests.jsp" class="u-flex u-gap-8 u-wrap">
      <select name="status_filter">
        <option value="">All statuses</option>
        <option value="sent" <%= "sent".equals(filterStatus) ? "selected" : "" %>>Sent</option>
        <option value="delivered" <%= "delivered".equals(filterStatus) ? "selected" : "" %>>Delivered</option>
        <option value="viewed" <%= "viewed".equals(filterStatus) ? "selected" : "" %>>Viewed</option>
        <option value="signed" <%= "signed".equals(filterStatus) ? "selected" : "" %>>Signed</option>
        <option value="declined" <%= "declined".equals(filterStatus) ? "selected" : "" %>>Declined</option>
        <option value="expired" <%= "expired".equals(filterStatus) ? "selected" : "" %>>Expired</option>
        <option value="failed" <%= "failed".equals(filterStatus) ? "selected" : "" %>>Failed</option>
      </select>
      <button class="btn btn-ghost" type="submit">Apply</button>
    </form>
  </div>
  <div class="table-wrap table-wrap-tight">
    <table class="table">
      <thead><tr><th>Request</th><th>Status</th><th>Provider</th><th>Matter</th><th>Updated</th></tr></thead>
      <tbody>
      <% if (rows.isEmpty()) { %>
      <tr><td colspan="5" class="meta">No signature requests found.</td></tr>
      <% } else {
           for (esign_requests.SignatureRequestRec row : rows) {
             if (row == null) continue;
      %>
      <tr>
        <td><a href="<%= ctx %>/esign_requests.jsp?request_uuid=<%= esc(safe(row.requestUuid)) %>"><%= esc(safe(row.subject)) %></a></td>
        <td><%= esc(safe(row.status)) %></td>
        <td><%= esc(safe(row.providerKey)) %></td>
        <td><%= esc(safe(row.matterUuid)) %></td>
        <td><%= esc(safe(row.updatedAt)) %></td>
      </tr>
      <%   }
         } %>
      </tbody>
    </table>
  </div>
</section>

<% if (selected != null) { %>
<section class="card section-gap-12">
  <h2 class="u-m-0">Update Lifecycle Status</h2>
  <form method="post" action="<%= ctx %>/esign_requests.jsp?request_uuid=<%= esc(safe(selected.requestUuid)) %>" class="form">
    <input type="hidden" name="csrf_token" value="<%= esc(csrfToken) %>" />
    <input type="hidden" name="action" value="update_status" />
    <input type="hidden" name="request_uuid" value="<%= esc(safe(selected.requestUuid)) %>" />
    <div class="grid grid-3">
      <label><span>Status</span>
        <select name="status">
          <option value="sent">Sent</option>
          <option value="delivered">Delivered</option>
          <option value="viewed">Viewed</option>
          <option value="signed">Signed</option>
          <option value="declined">Declined</option>
          <option value="expired">Expired</option>
          <option value="cancelled">Cancelled</option>
          <option value="failed">Failed</option>
        </select>
      </label>
      <label><span>Provider Request ID</span><input type="text" name="provider_request_id" value="<%= esc(safe(selected.providerRequestId)) %>" /></label>
      <label><span>Note</span><input type="text" name="note" /></label>
    </div>
    <div class="u-mt-12"><button class="btn" type="submit">Update Status</button></div>
  </form>
</section>

<section class="card section-gap-12">
  <h2 class="u-m-0">Lifecycle Events</h2>
  <div class="table-wrap table-wrap-tight">
    <table class="table">
      <thead><tr><th>Created</th><th>Event</th><th>Status</th><th>Actor</th><th>Note</th></tr></thead>
      <tbody>
      <% if (selectedEvents.isEmpty()) { %>
      <tr><td colspan="5" class="meta">No lifecycle events yet.</td></tr>
      <% } else {
           for (esign_requests.SignatureEventRec row : selectedEvents) {
             if (row == null) continue;
      %>
      <tr>
        <td><%= esc(safe(row.createdAt)) %></td>
        <td><%= esc(safe(row.eventType)) %></td>
        <td><%= esc(safe(row.status)) %></td>
        <td><%= esc(safe(row.actorUserUuid)) %></td>
        <td><%= esc(safe(row.note)) %></td>
      </tr>
      <%   }
         } %>
      </tbody>
    </table>
  </div>
</section>
<% } %>

<jsp:include page="footer.jsp" />
