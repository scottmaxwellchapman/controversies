<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ page import="java.util.Locale" %>
<%@ page import="net.familylawandprobate.controversies.integrations.clio.ClioBillingMapper" %>
<%@ include file="security.jspf" %>
<% if (!require_login()) return; %>
<% if (!require_permission("tenant_settings.manage")) return; %>
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
request.setAttribute("activeNav", "/clio_billing_mapper_lab.jsp");
String csrfToken = csrfForRender(request);

String action = safe(request.getParameter("action")).trim().toLowerCase(Locale.ROOT);
String payloadType = safe(request.getParameter("payload_type")).trim().toLowerCase(Locale.ROOT);
if (payloadType.isBlank()) payloadType = "activity";
String rawJson = safe(request.getParameter("raw_json"));
String error = "";
boolean ran = "parse".equals(action);

ClioBillingMapper.ClioActivityRec activity = null;
ClioBillingMapper.ClioExpenseRec expense = null;
ClioBillingMapper.ClioBillRec bill = null;
ClioBillingMapper.ClioTrustTransactionRec trustTxn = null;

if (ran) {
  try {
    if (rawJson.isBlank()) throw new IllegalArgumentException("Provide a JSON payload.");
    if ("activity".equals(payloadType)) {
      activity = ClioBillingMapper.activityFromJson(rawJson);
    } else if ("expense".equals(payloadType)) {
      expense = ClioBillingMapper.expenseFromJson(rawJson);
    } else if ("bill".equals(payloadType)) {
      bill = ClioBillingMapper.billFromJson(rawJson);
    } else if ("trust".equals(payloadType)) {
      trustTxn = ClioBillingMapper.trustTransactionFromJson(rawJson);
    } else {
      throw new IllegalArgumentException("Unsupported payload type.");
    }
  } catch (Exception ex) {
    error = safe(ex.getMessage());
    if (error.isBlank()) error = "Mapper parse failed.";
  }
}
%>
<jsp:include page="header.jsp" />

<section class="card">
  <h1 class="u-m-0">Clio Billing Mapper Lab</h1>
  <div class="meta u-mt-6">Parse Clio billing payloads through <code>ClioBillingMapper</code> and inspect normalized fields.</div>
</section>

<section class="card section-gap-12">
  <form method="post" action="<%= ctx %>/clio_billing_mapper_lab.jsp" class="form">
    <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
    <input type="hidden" name="action" value="parse" />
    <div class="grid grid-2">
      <div>
        <label for="payload_type">Payload Type</label>
        <select id="payload_type" name="payload_type">
          <option value="activity" <%= "activity".equals(payloadType) ? "selected" : "" %>>Activity</option>
          <option value="expense" <%= "expense".equals(payloadType) ? "selected" : "" %>>Expense</option>
          <option value="bill" <%= "bill".equals(payloadType) ? "selected" : "" %>>Bill</option>
          <option value="trust" <%= "trust".equals(payloadType) ? "selected" : "" %>>Trust Transaction</option>
        </select>
      </div>
    </div>
    <label for="raw_json">Raw JSON</label>
    <textarea id="raw_json" name="raw_json" rows="14" placeholder="{ ... }"><%= esc(rawJson) %></textarea>
    <button class="btn" type="submit">Parse Payload</button>
  </form>
</section>

<% if (!error.isBlank()) { %>
<section class="card section-gap-12">
  <div class="alert alert-error"><%= esc(error) %></div>
</section>
<% } %>

<% if (ran && error.isBlank()) { %>
<section class="card section-gap-12">
  <h2 class="u-m-0">Mapped Output</h2>
  <div class="table-wrap table-wrap-tight u-mt-8">
    <table class="table">
      <tbody>
      <% if (activity != null) { %>
        <tr><th>ID</th><td><%= esc(activity.id) %></td></tr>
        <tr><th>Matter ID</th><td><%= esc(activity.matterId) %></td></tr>
        <tr><th>User ID</th><td><%= esc(activity.userId) %></td></tr>
        <tr><th>Description</th><td><%= esc(activity.description) %></td></tr>
        <tr><th>Note</th><td><%= esc(activity.note) %></td></tr>
        <tr><th>Date</th><td><%= esc(activity.date) %></td></tr>
        <tr><th>Rate (cents/hour)</th><td><%= activity.rateCentsPerHour %></td></tr>
        <tr><th>Minutes</th><td><%= activity.minutes %></td></tr>
        <tr><th>Currency</th><td><%= esc(activity.currency) %></td></tr>
      <% } else if (expense != null) { %>
        <tr><th>ID</th><td><%= esc(expense.id) %></td></tr>
        <tr><th>Matter ID</th><td><%= esc(expense.matterId) %></td></tr>
        <tr><th>Description</th><td><%= esc(expense.description) %></td></tr>
        <tr><th>Date</th><td><%= esc(expense.date) %></td></tr>
        <tr><th>Amount (cents)</th><td><%= expense.amountCents %></td></tr>
        <tr><th>Tax (cents)</th><td><%= expense.taxCents %></td></tr>
        <tr><th>Currency</th><td><%= esc(expense.currency) %></td></tr>
      <% } else if (bill != null) { %>
        <tr><th>ID</th><td><%= esc(bill.id) %></td></tr>
        <tr><th>Matter ID</th><td><%= esc(bill.matterId) %></td></tr>
        <tr><th>State</th><td><%= esc(bill.state) %></td></tr>
        <tr><th>Issued At</th><td><%= esc(bill.issuedAt) %></td></tr>
        <tr><th>Due At</th><td><%= esc(bill.dueAt) %></td></tr>
        <tr><th>Subtotal (cents)</th><td><%= bill.subtotalCents %></td></tr>
        <tr><th>Tax (cents)</th><td><%= bill.taxCents %></td></tr>
        <tr><th>Total (cents)</th><td><%= bill.totalCents %></td></tr>
        <tr><th>Paid (cents)</th><td><%= bill.paidCents %></td></tr>
        <tr><th>Currency</th><td><%= esc(bill.currency) %></td></tr>
      <% } else if (trustTxn != null) { %>
        <tr><th>ID</th><td><%= esc(trustTxn.id) %></td></tr>
        <tr><th>Matter ID</th><td><%= esc(trustTxn.matterId) %></td></tr>
        <tr><th>Bill ID</th><td><%= esc(trustTxn.billId) %></td></tr>
        <tr><th>Kind</th><td><%= esc(trustTxn.kind) %></td></tr>
        <tr><th>Amount (cents)</th><td><%= trustTxn.amountCents %></td></tr>
        <tr><th>Date</th><td><%= esc(trustTxn.date) %></td></tr>
        <tr><th>Currency</th><td><%= esc(trustTxn.currency) %></td></tr>
        <tr><th>Reference</th><td><%= esc(trustTxn.reference) %></td></tr>
      <% } else { %>
        <tr><td colspan="2" class="meta">No mapped output.</td></tr>
      <% } %>
      </tbody>
    </table>
  </div>
</section>
<% } %>

<jsp:include page="footer.jsp" />
