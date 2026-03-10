<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" isELIgnored="true" %>
<%@ page import="java.util.ArrayList" %>
<%@ page import="java.util.LinkedHashMap" %>
<%@ page import="java.util.List" %>

<%@ page import="net.familylawandprobate.controversies.integration_webhooks" %>

<%@ include file="security.jspf" %>
<%
  if (!require_login()) return;
%>

<%!
  private static final String S_TENANT_UUID = "tenant.uuid";
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
  request.setAttribute("activeNav", "/integrations_webhooks.jsp");
  String tenantUuid = safe((String) session.getAttribute(S_TENANT_UUID)).trim();
  if (tenantUuid.isBlank()) { response.sendRedirect(ctx + "/tenant_login.jsp"); return; }

  String csrfToken = csrfForRender(request);
  String message = "";
  String error = "";
  integration_webhooks store = integration_webhooks.defaultStore();

  if ("POST".equalsIgnoreCase(request.getMethod())) {
    String action = safe(request.getParameter("action")).trim();
    try {
      if ("create_webhook".equals(action)) {
        integration_webhooks.EndpointInput in = new integration_webhooks.EndpointInput();
        in.label = safe(request.getParameter("label")).trim();
        in.url = safe(request.getParameter("url")).trim();
        in.eventFilterCsv = safe(request.getParameter("event_filter_csv")).trim();
        in.signingSecret = safe(request.getParameter("signing_secret")).trim();
        in.enabled = "true".equalsIgnoreCase(safe(request.getParameter("enabled")).trim()) || "on".equalsIgnoreCase(safe(request.getParameter("enabled")).trim());
        store.createEndpoint(tenantUuid, in);
        message = "Webhook endpoint created.";
      } else if ("update_webhook".equals(action)) {
        String webhookUuid = safe(request.getParameter("webhook_uuid")).trim();
        integration_webhooks.EndpointRec cur = store.getEndpoint(tenantUuid, webhookUuid);
        if (cur == null) throw new IllegalArgumentException("Webhook not found.");
        integration_webhooks.EndpointInput in = new integration_webhooks.EndpointInput();
        in.label = safe(request.getParameter("label")).trim();
        in.url = safe(request.getParameter("url")).trim();
        in.eventFilterCsv = safe(request.getParameter("event_filter_csv")).trim();
        in.signingSecret = safe(request.getParameter("signing_secret")).trim();
        in.enabled = "true".equalsIgnoreCase(safe(request.getParameter("enabled")).trim()) || "on".equalsIgnoreCase(safe(request.getParameter("enabled")).trim());
        store.updateEndpoint(tenantUuid, webhookUuid, in);
        message = "Webhook endpoint updated.";
      } else if ("delete_webhook".equals(action)) {
        String webhookUuid = safe(request.getParameter("webhook_uuid")).trim();
        store.deleteEndpoint(tenantUuid, webhookUuid);
        message = "Webhook endpoint deleted.";
      } else if ("emit_event".equals(action)) {
        String eventType = safe(request.getParameter("event_type")).trim().toLowerCase(java.util.Locale.ROOT);
        if (eventType.isBlank()) throw new IllegalArgumentException("event_type is required.");
        LinkedHashMap<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("message", safe(request.getParameter("event_message")).trim());
        payload.put("timestamp", java.time.Instant.now().toString());
        integration_webhooks.DispatchResult res = store.dispatchEvent(tenantUuid, eventType, payload);
        message = "Event dispatched. Attempted=" + res.attemptedCount + ", success=" + res.successCount + ", failed=" + res.failureCount + ".";
      }
    } catch (Exception ex) {
      error = safe(ex.getMessage()).isBlank() ? ex.getClass().getSimpleName() : safe(ex.getMessage());
    }
  }

  List<integration_webhooks.EndpointRec> endpoints = new ArrayList<integration_webhooks.EndpointRec>();
  List<integration_webhooks.DeliveryRec> deliveries = new ArrayList<integration_webhooks.DeliveryRec>();
  try {
    endpoints = store.listEndpoints(tenantUuid);
    deliveries = store.listDeliveries(tenantUuid, 200);
  } catch (Exception ex) {
    if (error.isBlank()) error = "Unable to load webhook data: " + safe(ex.getMessage());
  }
%>

<jsp:include page="header.jsp" />

<section class="card">
  <h1 class="u-m-0">Integration Webhooks</h1>
  <div class="meta u-mt-6">Register outbound webhook endpoints, subscribe by event pattern, and monitor delivery history.</div>
  <% if (!message.isBlank()) { %><div class="alert alert-success u-mt-12"><%= esc(message) %></div><% } %>
  <% if (!error.isBlank()) { %><div class="alert alert-error u-mt-12"><%= esc(error) %></div><% } %>
</section>

<section class="card section-gap-12">
  <h2 class="u-m-0">Create Endpoint</h2>
  <form method="post" action="<%= ctx %>/integrations_webhooks.jsp" class="form">
    <input type="hidden" name="csrf_token" value="<%= esc(csrfToken) %>" />
    <input type="hidden" name="action" value="create_webhook" />
    <div class="grid grid-3">
      <label><span>Label</span><input type="text" name="label" placeholder="Accounting Connector" required /></label>
      <label><span>URL</span><input type="text" name="url" placeholder="https://example.com/hooks/controversies" required /></label>
      <label><span>Event Filter (CSV)</span><input type="text" name="event_filter_csv" value="*" /></label>
      <label><span>Signing Secret (optional)</span><input type="text" name="signing_secret" placeholder="Generated if blank" /></label>
      <label><input type="checkbox" name="enabled" value="true" checked /> Enabled</label>
    </div>
    <div class="u-mt-12"><button class="btn" type="submit">Create Endpoint</button></div>
  </form>
</section>

<section class="card section-gap-12">
  <h2 class="u-m-0">Emit Test Event</h2>
  <form method="post" action="<%= ctx %>/integrations_webhooks.jsp" class="form">
    <input type="hidden" name="csrf_token" value="<%= esc(csrfToken) %>" />
    <input type="hidden" name="action" value="emit_event" />
    <div class="grid grid-2">
      <label><span>Event Type</span><input type="text" name="event_type" placeholder="payments.transaction.paid" required /></label>
      <label><span>Message</span><input type="text" name="event_message" placeholder="Manual webhook test" /></label>
    </div>
    <div class="u-mt-12"><button class="btn btn-ghost" type="submit">Emit Event</button></div>
  </form>
</section>

<section class="card section-gap-12">
  <h2 class="u-m-0">Registered Endpoints</h2>
  <div class="table-wrap table-wrap-tight">
    <table class="table">
      <thead><tr><th>Label</th><th>URL</th><th>Events</th><th>Status</th><th>Last Attempt</th><th>Last Result</th><th></th></tr></thead>
      <tbody>
      <% if (endpoints.isEmpty()) { %>
      <tr><td colspan="7" class="meta">No webhook endpoints yet.</td></tr>
      <% } else {
           for (integration_webhooks.EndpointRec row : endpoints) {
             if (row == null) continue;
      %>
      <tr>
        <td><%= esc(safe(row.label)) %></td>
        <td><code><%= esc(safe(row.url)) %></code></td>
        <td><%= esc(safe(row.eventFilterCsv)) %></td>
        <td><%= row.enabled ? "Enabled" : "Disabled" %></td>
        <td><%= esc(safe(row.lastAttemptAt)) %></td>
        <td><%= row.lastStatusCode %><%= safe(row.lastError).isBlank() ? "" : " / " + esc(safe(row.lastError)) %></td>
        <td>
          <details>
            <summary class="btn btn-ghost">Edit</summary>
            <form method="post" action="<%= ctx %>/integrations_webhooks.jsp" class="form u-mt-8">
              <input type="hidden" name="csrf_token" value="<%= esc(csrfToken) %>" />
              <input type="hidden" name="action" value="update_webhook" />
              <input type="hidden" name="webhook_uuid" value="<%= esc(safe(row.webhookUuid)) %>" />
              <label><span>Label</span><input type="text" name="label" value="<%= esc(safe(row.label)) %>" /></label>
              <label><span>URL</span><input type="text" name="url" value="<%= esc(safe(row.url)) %>" /></label>
              <label><span>Event Filter (CSV)</span><input type="text" name="event_filter_csv" value="<%= esc(safe(row.eventFilterCsv)) %>" /></label>
              <label><span>Signing Secret (optional rotate)</span><input type="text" name="signing_secret" /></label>
              <label><input type="checkbox" name="enabled" value="true" <%= row.enabled ? "checked" : "" %> /> Enabled</label>
              <div class="u-flex u-gap-8 u-wrap">
                <button class="btn btn-ghost" type="submit">Save</button>
              </div>
            </form>
            <form method="post" action="<%= ctx %>/integrations_webhooks.jsp" class="u-mt-8">
              <input type="hidden" name="csrf_token" value="<%= esc(csrfToken) %>" />
              <input type="hidden" name="action" value="delete_webhook" />
              <input type="hidden" name="webhook_uuid" value="<%= esc(safe(row.webhookUuid)) %>" />
              <button class="btn btn-ghost" type="submit">Delete Endpoint</button>
            </form>
          </details>
        </td>
      </tr>
      <%   }
         } %>
      </tbody>
    </table>
  </div>
</section>

<section class="card section-gap-12">
  <h2 class="u-m-0">Delivery Log</h2>
  <div class="table-wrap table-wrap-tight">
    <table class="table">
      <thead><tr><th>Attempted</th><th>Event</th><th>Webhook</th><th>Success</th><th>Status</th><th>Error</th></tr></thead>
      <tbody>
      <% if (deliveries.isEmpty()) { %>
      <tr><td colspan="6" class="meta">No delivery attempts yet.</td></tr>
      <% } else {
           for (integration_webhooks.DeliveryRec row : deliveries) {
             if (row == null) continue;
      %>
      <tr>
        <td><%= esc(safe(row.attemptedAt)) %></td>
        <td><%= esc(safe(row.eventType)) %></td>
        <td><%= esc(safe(row.webhookUuid)) %></td>
        <td><%= row.success ? "true" : "false" %></td>
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
