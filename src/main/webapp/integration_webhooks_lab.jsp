<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ page import="com.fasterxml.jackson.core.type.TypeReference" %>
<%@ page import="com.fasterxml.jackson.databind.ObjectMapper" %>
<%@ page import="java.util.ArrayList" %>
<%@ page import="java.util.LinkedHashMap" %>
<%@ page import="java.util.List" %>
<%@ page import="net.familylawandprobate.controversies.integration_webhooks" %>
<%@ include file="security.jspf" %>
<% if (!require_login()) return; %>
<% if (!require_permission("tenant_settings.manage")) return; %>
<%!
  private static final ObjectMapper JSON = new ObjectMapper();
  private static String safe(String s) { return s == null ? "" : s; }
  private static String esc(String s) {
    return safe(s)
      .replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")
      .replace("'", "&#39;");
  }
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
request.setAttribute("activeNav", "/integration_webhooks_lab.jsp");
String tenantUuid = safe((String) session.getAttribute("tenant.uuid")).trim();
if (tenantUuid.isBlank()) {
  response.sendRedirect(ctx + "/tenant_login.jsp");
  return;
}
String csrfToken = csrfForRender(request);

String action = safe(request.getParameter("action")).trim();
String message = "";
String error = "";
integration_webhooks.DispatchResult dispatchResult = null;

integration_webhooks store = integration_webhooks.defaultStore();
if (!action.isBlank()) {
  try {
    if ("create_endpoint".equals(action)) {
      integration_webhooks.EndpointInput in = new integration_webhooks.EndpointInput();
      in.label = safe(request.getParameter("label"));
      in.url = safe(request.getParameter("url"));
      in.eventFilterCsv = safe(request.getParameter("event_filter_csv"));
      in.signingSecret = safe(request.getParameter("signing_secret"));
      in.enabled = "1".equals(safe(request.getParameter("enabled")));
      store.createEndpoint(tenantUuid, in);
      message = "Webhook endpoint created.";
    } else if ("delete_endpoint".equals(action)) {
      String webhookUuid = safe(request.getParameter("webhook_uuid")).trim();
      if (webhookUuid.isBlank()) throw new IllegalArgumentException("webhook_uuid is required.");
      boolean ok = store.deleteEndpoint(tenantUuid, webhookUuid);
      message = ok ? "Webhook endpoint deleted." : "Webhook endpoint not found.";
    } else if ("dispatch_event".equals(action)) {
      String eventType = safe(request.getParameter("event_type"));
      String payloadJson = safe(request.getParameter("payload_json"));
      LinkedHashMap<String, Object> payload = new LinkedHashMap<String, Object>();
      if (!payloadJson.isBlank()) {
        payload = JSON.readValue(payloadJson, new TypeReference<LinkedHashMap<String, Object>>() {});
      }
      dispatchResult = store.dispatchEvent(tenantUuid, eventType, payload);
      message = "Webhook event dispatched.";
    }
  } catch (Exception ex) {
    error = safe(ex.getMessage());
    if (error.isBlank()) error = "Webhook action failed.";
  }
}

List<integration_webhooks.EndpointRec> endpoints = new ArrayList<integration_webhooks.EndpointRec>();
List<integration_webhooks.DeliveryRec> deliveries = new ArrayList<integration_webhooks.DeliveryRec>();
try {
  endpoints = store.listEndpoints(tenantUuid);
  deliveries = store.listDeliveries(tenantUuid, 50);
} catch (Exception ex) {
  if (error.isBlank()) {
    error = safe(ex.getMessage());
    if (error.isBlank()) error = "Unable to load webhook data.";
  }
}
%>
<jsp:include page="header.jsp" />

<section class="card">
  <h1 class="u-m-0">Integration Webhooks Lab</h1>
  <div class="meta u-mt-6">Manage and dispatch webhook integrations via <code>integration_webhooks</code>.</div>
</section>

<section class="card section-gap-12">
  <% if (!message.isBlank()) { %><div class="alert alert-ok"><%= esc(message) %></div><% } %>
  <% if (!error.isBlank()) { %><div class="alert alert-error"><%= esc(error) %></div><% } %>
  <% if (dispatchResult != null) { %>
    <div class="meta u-mt-8">
      Dispatch summary: endpoints=<strong><%= dispatchResult.endpointCount %></strong>,
      attempted=<strong><%= dispatchResult.attemptedCount %></strong>,
      success=<strong><%= dispatchResult.successCount %></strong>,
      failure=<strong><%= dispatchResult.failureCount %></strong>.
    </div>
  <% } %>
</section>

<section class="card section-gap-12">
  <h2 class="u-m-0">Create Endpoint</h2>
  <form method="post" action="<%= ctx %>/integration_webhooks_lab.jsp" class="form">
    <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
    <input type="hidden" name="action" value="create_endpoint" />
    <div class="grid grid-3">
      <div><label>Label</label><input name="label" type="text" value="Webhook Endpoint" /></div>
      <div><label>URL</label><input name="url" type="text" placeholder="https://example.com/webhook" /></div>
      <div><label>Event Filter CSV</label><input name="event_filter_csv" type="text" value="*" /></div>
      <div><label>Signing Secret (optional)</label><input name="signing_secret" type="text" /></div>
      <div class="u-flex u-items-end"><label><input type="checkbox" name="enabled" value="1" checked /> Enabled</label></div>
    </div>
    <button class="btn" type="submit">Create Endpoint</button>
  </form>
</section>

<section class="card section-gap-12">
  <h2 class="u-m-0">Dispatch Test Event</h2>
  <form method="post" action="<%= ctx %>/integration_webhooks_lab.jsp" class="form">
    <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
    <input type="hidden" name="action" value="dispatch_event" />
    <div class="grid grid-2">
      <div><label>Event Type</label><input name="event_type" type="text" value="test.event" /></div>
    </div>
    <label>Payload JSON</label>
    <textarea name="payload_json" rows="8">{
  "message": "test delivery",
  "source": "integration_webhooks_lab"
}</textarea>
    <button class="btn" type="submit">Dispatch Event</button>
  </form>
</section>

<section class="card section-gap-12">
  <h2 class="u-m-0">Endpoints</h2>
  <div class="table-wrap table-wrap-tight u-mt-8">
    <table class="table">
      <thead>
      <tr>
        <th>Webhook UUID</th>
        <th>Label</th>
        <th>URL</th>
        <th>Filter</th>
        <th>Enabled</th>
        <th>Last Status</th>
        <th></th>
      </tr>
      </thead>
      <tbody>
      <% if (endpoints.isEmpty()) { %>
        <tr><td colspan="7" class="meta">No webhook endpoints configured.</td></tr>
      <% } else {
           for (integration_webhooks.EndpointRec row : endpoints) {
             if (row == null) continue;
      %>
        <tr>
          <td><code><%= esc(safe(row.webhookUuid)) %></code></td>
          <td><%= esc(safe(row.label)) %></td>
          <td><%= esc(safe(row.url)) %></td>
          <td><%= esc(safe(row.eventFilterCsv)) %></td>
          <td><%= row.enabled ? "Yes" : "No" %></td>
          <td><%= row.lastStatusCode %></td>
          <td>
            <form method="post" action="<%= ctx %>/integration_webhooks_lab.jsp" style="display:inline;">
              <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
              <input type="hidden" name="action" value="delete_endpoint" />
              <input type="hidden" name="webhook_uuid" value="<%= esc(safe(row.webhookUuid)) %>" />
              <button class="btn btn-ghost" type="submit" onclick="return confirm('Delete this endpoint?');">Delete</button>
            </form>
          </td>
        </tr>
      <%   }
         } %>
      </tbody>
    </table>
  </div>
</section>

<section class="card section-gap-12">
  <h2 class="u-m-0">Recent Deliveries</h2>
  <div class="table-wrap table-wrap-tight u-mt-8">
    <table class="table">
      <thead>
      <tr>
        <th>Attempted At</th>
        <th>Webhook UUID</th>
        <th>Event</th>
        <th>Success</th>
        <th>Status</th>
        <th>Error</th>
      </tr>
      </thead>
      <tbody>
      <% if (deliveries.isEmpty()) { %>
        <tr><td colspan="6" class="meta">No deliveries.</td></tr>
      <% } else {
           for (integration_webhooks.DeliveryRec row : deliveries) {
             if (row == null) continue;
      %>
        <tr>
          <td><%= esc(safe(row.attemptedAt)) %></td>
          <td><code><%= esc(safe(row.webhookUuid)) %></code></td>
          <td><%= esc(safe(row.eventType)) %></td>
          <td><%= row.success ? "Yes" : "No" %></td>
          <td><%= row.statusCode %></td>
          <td><%= esc(safe(row.error)) %></td>
        </tr>
      <%   }
         } %>
      </tbody>
    </table>
  </div>
</section>

<jsp:include page="footer.jsp" />
