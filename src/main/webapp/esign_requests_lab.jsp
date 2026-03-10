<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ page import="java.net.URLEncoder" %>
<%@ page import="java.nio.charset.StandardCharsets" %>
<%@ page import="java.util.ArrayList" %>
<%@ page import="java.util.List" %>
<%@ page import="net.familylawandprobate.controversies.esign_requests" %>
<%@ page import="net.familylawandprobate.controversies.users_roles" %>
<%@ include file="security.jspf" %>
<% if (!require_login()) return; %>
<% if (!require_permission("tenant_settings.manage")) return; %>
<%!
  private static String safe(String s) { return s == null ? "" : s; }
  private static String esc(String s) {
    return safe(s)
      .replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")
      .replace("'", "&#39;");
  }
  private static String enc(String s) { return URLEncoder.encode(safe(s), StandardCharsets.UTF_8); }
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
%>
<%
String ctx = safe(request.getContextPath());
request.setAttribute("activeNav", "/esign_requests_lab.jsp");
String tenantUuid = safe((String) session.getAttribute("tenant.uuid")).trim();
if (tenantUuid.isBlank()) {
  response.sendRedirect(ctx + "/tenant_login.jsp");
  return;
}
String actorUserUuid = safe((String) session.getAttribute(users_roles.S_USER_UUID)).trim();
String csrfToken = csrfForRender(request);

String action = safe(request.getParameter("action")).trim();
String selectedRequestUuid = safe(request.getParameter("request_uuid")).trim();
String message = "";
String error = "";

esign_requests store = esign_requests.defaultStore();
if (!action.isBlank()) {
  try {
    if ("create_request".equals(action)) {
      esign_requests.CreateInput in = new esign_requests.CreateInput();
      in.providerKey = safe(request.getParameter("provider_key"));
      in.providerRequestId = safe(request.getParameter("provider_request_id"));
      in.matterUuid = safe(request.getParameter("matter_uuid"));
      in.documentUuid = safe(request.getParameter("document_uuid"));
      in.partUuid = safe(request.getParameter("part_uuid"));
      in.versionUuid = safe(request.getParameter("version_uuid"));
      in.subject = safe(request.getParameter("subject"));
      in.toCsv = safe(request.getParameter("to_csv"));
      in.ccCsv = safe(request.getParameter("cc_csv"));
      in.bccCsv = safe(request.getParameter("bcc_csv"));
      in.signatureLink = safe(request.getParameter("signature_link"));
      in.deliveryMode = safe(request.getParameter("delivery_mode"));
      in.status = safe(request.getParameter("status"));
      in.requestedByUserUuid = actorUserUuid;
      esign_requests.SignatureRequestRec created = store.createRequest(tenantUuid, in);
      selectedRequestUuid = safe(created == null ? "" : created.requestUuid).trim();
      message = "Signature request created.";
    } else if ("update_status".equals(action)) {
      String requestUuid = safe(request.getParameter("request_uuid")).trim();
      if (requestUuid.isBlank()) throw new IllegalArgumentException("request_uuid is required.");
      store.updateStatus(
        tenantUuid,
        requestUuid,
        safe(request.getParameter("status")),
        safe(request.getParameter("event_type")),
        safe(request.getParameter("note")),
        actorUserUuid,
        safe(request.getParameter("provider_request_id"))
      );
      selectedRequestUuid = requestUuid;
      message = "Signature request status updated.";
    }
  } catch (Exception ex) {
    error = safe(ex.getMessage());
    if (error.isBlank()) error = "Signature request action failed.";
  }
}

List<esign_requests.SignatureRequestRec> requests = new ArrayList<esign_requests.SignatureRequestRec>();
List<esign_requests.SignatureEventRec> selectedEvents = new ArrayList<esign_requests.SignatureEventRec>();
esign_requests.SignatureRequestRec selectedRequest = null;

try {
  requests = store.listRequests(tenantUuid);
  if (selectedRequestUuid.isBlank() && !requests.isEmpty()) {
    selectedRequestUuid = safe(requests.get(0).requestUuid).trim();
  }
  for (esign_requests.SignatureRequestRec row : requests) {
    if (row == null) continue;
    if (safe(row.requestUuid).trim().equals(selectedRequestUuid)) {
      selectedRequest = row;
      break;
    }
  }
  if (selectedRequest != null) {
    selectedEvents = store.listEvents(tenantUuid, selectedRequest.requestUuid);
  }
} catch (Exception ex) {
  if (error.isBlank()) {
    error = safe(ex.getMessage());
    if (error.isBlank()) error = "Unable to load signature requests.";
  }
}
%>
<jsp:include page="header.jsp" />

<section class="card">
  <h1 class="u-m-0">E-Sign Requests Lab</h1>
  <div class="meta u-mt-6">Manage request lifecycle data in <code>esign_requests</code>.</div>
</section>

<section class="card section-gap-12">
  <% if (!message.isBlank()) { %><div class="alert alert-ok"><%= esc(message) %></div><% } %>
  <% if (!error.isBlank()) { %><div class="alert alert-error"><%= esc(error) %></div><% } %>
</section>

<section class="card section-gap-12">
  <h2 class="u-m-0">Create Request</h2>
  <form method="post" action="<%= ctx %>/esign_requests_lab.jsp" class="form">
    <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
    <input type="hidden" name="action" value="create_request" />
    <div class="grid grid-3">
      <div><label>Provider Key</label><input name="provider_key" type="text" value="manual_notice" /></div>
      <div><label>Status</label>
        <select name="status">
          <option value="<%= esign_requests.STATUS_DRAFT %>"><%= esign_requests.STATUS_DRAFT %></option>
          <option value="<%= esign_requests.STATUS_SENT %>" selected><%= esign_requests.STATUS_SENT %></option>
          <option value="<%= esign_requests.STATUS_DELIVERED %>"><%= esign_requests.STATUS_DELIVERED %></option>
          <option value="<%= esign_requests.STATUS_VIEWED %>"><%= esign_requests.STATUS_VIEWED %></option>
          <option value="<%= esign_requests.STATUS_SIGNED %>"><%= esign_requests.STATUS_SIGNED %></option>
          <option value="<%= esign_requests.STATUS_DECLINED %>"><%= esign_requests.STATUS_DECLINED %></option>
          <option value="<%= esign_requests.STATUS_EXPIRED %>"><%= esign_requests.STATUS_EXPIRED %></option>
          <option value="<%= esign_requests.STATUS_CANCELLED %>"><%= esign_requests.STATUS_CANCELLED %></option>
          <option value="<%= esign_requests.STATUS_FAILED %>"><%= esign_requests.STATUS_FAILED %></option>
        </select>
      </div>
      <div><label>Provider Request ID</label><input name="provider_request_id" type="text" /></div>
      <div><label>Subject</label><input name="subject" type="text" /></div>
      <div><label>To CSV</label><input name="to_csv" type="text" /></div>
      <div><label>CC CSV</label><input name="cc_csv" type="text" /></div>
      <div><label>BCC CSV</label><input name="bcc_csv" type="text" /></div>
      <div><label>Signature Link</label><input name="signature_link" type="text" /></div>
      <div><label>Delivery Mode</label><input name="delivery_mode" type="text" /></div>
      <div><label>Matter UUID</label><input name="matter_uuid" type="text" /></div>
      <div><label>Document UUID</label><input name="document_uuid" type="text" /></div>
      <div><label>Part UUID</label><input name="part_uuid" type="text" /></div>
      <div><label>Version UUID</label><input name="version_uuid" type="text" /></div>
    </div>
    <button class="btn" type="submit">Create Signature Request</button>
  </form>
</section>

<section class="card section-gap-12">
  <h2 class="u-m-0">Requests</h2>
  <div class="table-wrap table-wrap-tight u-mt-8">
    <table class="table">
      <thead>
      <tr>
        <th>Request</th>
        <th>Status</th>
        <th>Subject</th>
        <th>To</th>
        <th>Updated</th>
        <th></th>
      </tr>
      </thead>
      <tbody>
      <% if (requests.isEmpty()) { %>
        <tr><td colspan="6" class="meta">No signature requests.</td></tr>
      <% } else {
           for (esign_requests.SignatureRequestRec row : requests) {
             if (row == null) continue;
             String requestUuid = safe(row.requestUuid).trim();
      %>
        <tr>
          <td><code><%= esc(requestUuid) %></code></td>
          <td><%= esc(safe(row.status)) %></td>
          <td><%= esc(safe(row.subject)) %></td>
          <td><%= esc(safe(row.toCsv)) %></td>
          <td><%= esc(safe(row.updatedAt)) %></td>
          <td><a class="btn btn-ghost" href="<%= ctx %>/esign_requests_lab.jsp?request_uuid=<%= enc(requestUuid) %>">Open</a></td>
        </tr>
      <%   }
         } %>
      </tbody>
    </table>
  </div>
</section>

<% if (selectedRequest != null) { %>
<section class="card section-gap-12">
  <h2 class="u-m-0">Selected Request</h2>
  <div class="meta u-mt-6"><code><%= esc(safe(selectedRequest.requestUuid)) %></code></div>

  <form method="post" action="<%= ctx %>/esign_requests_lab.jsp" class="form u-mt-8">
    <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
    <input type="hidden" name="action" value="update_status" />
    <input type="hidden" name="request_uuid" value="<%= esc(safe(selectedRequest.requestUuid)) %>" />
    <div class="grid grid-3">
      <div><label>Status</label>
        <select name="status">
          <option value="<%= esign_requests.STATUS_DRAFT %>"><%= esign_requests.STATUS_DRAFT %></option>
          <option value="<%= esign_requests.STATUS_SENT %>"><%= esign_requests.STATUS_SENT %></option>
          <option value="<%= esign_requests.STATUS_DELIVERED %>"><%= esign_requests.STATUS_DELIVERED %></option>
          <option value="<%= esign_requests.STATUS_VIEWED %>"><%= esign_requests.STATUS_VIEWED %></option>
          <option value="<%= esign_requests.STATUS_SIGNED %>"><%= esign_requests.STATUS_SIGNED %></option>
          <option value="<%= esign_requests.STATUS_DECLINED %>"><%= esign_requests.STATUS_DECLINED %></option>
          <option value="<%= esign_requests.STATUS_EXPIRED %>"><%= esign_requests.STATUS_EXPIRED %></option>
          <option value="<%= esign_requests.STATUS_CANCELLED %>"><%= esign_requests.STATUS_CANCELLED %></option>
          <option value="<%= esign_requests.STATUS_FAILED %>"><%= esign_requests.STATUS_FAILED %></option>
        </select>
      </div>
      <div><label>Event Type</label><input name="event_type" type="text" value="status_update" /></div>
      <div><label>Provider Request ID</label><input name="provider_request_id" type="text" value="<%= esc(safe(selectedRequest.providerRequestId)) %>" /></div>
    </div>
    <label>Note</label>
    <textarea name="note" rows="3"></textarea>
    <button class="btn" type="submit">Update Status</button>
  </form>

  <h3 class="u-mt-12">Events</h3>
  <div class="table-wrap table-wrap-tight">
    <table class="table">
      <thead><tr><th>When</th><th>Type</th><th>Status</th><th>Actor</th><th>Note</th></tr></thead>
      <tbody>
      <% if (selectedEvents.isEmpty()) { %>
        <tr><td colspan="5" class="meta">No events.</td></tr>
      <% } else {
           for (esign_requests.SignatureEventRec e : selectedEvents) {
             if (e == null) continue;
      %>
        <tr>
          <td><%= esc(safe(e.createdAt)) %></td>
          <td><%= esc(safe(e.eventType)) %></td>
          <td><%= esc(safe(e.status)) %></td>
          <td><%= esc(safe(e.actorUserUuid)) %></td>
          <td><%= esc(safe(e.note)) %></td>
        </tr>
      <%   }
         } %>
      </tbody>
    </table>
  </div>
</section>
<% } %>

<jsp:include page="footer.jsp" />
