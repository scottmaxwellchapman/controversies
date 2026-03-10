<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" isELIgnored="true" %>
<%@ page import="java.util.ArrayList" %>
<%@ page import="java.util.LinkedHashMap" %>
<%@ page import="java.util.List" %>
<%@ page import="java.util.Locale" %>

<%@ page import="net.familylawandprobate.controversies.billing_accounting" %>
<%@ page import="net.familylawandprobate.controversies.billing_runtime_registry" %>
<%@ page import="net.familylawandprobate.controversies.matters" %>
<%@ page import="net.familylawandprobate.controversies.online_payments" %>
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
  private static long dollarsToCents(String raw) {
    String v = safe(raw).trim();
    if (v.isBlank()) return 0L;
    v = v.replace("$","").replace(",","");
    try {
      java.math.BigDecimal bd = new java.math.BigDecimal(v);
      return bd.multiply(java.math.BigDecimal.valueOf(100L)).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
    } catch (Exception ignored) { return 0L; }
  }
%>

<%
  String ctx = safe(request.getContextPath());
  request.setAttribute("activeNav", "/online_payments.jsp");
  String tenantUuid = safe((String) session.getAttribute(S_TENANT_UUID)).trim();
  String userUuid = safe((String) session.getAttribute(S_USER_UUID)).trim();
  String userEmail = safe((String) session.getAttribute(users_roles.S_USER_EMAIL)).trim();
  if (tenantUuid.isBlank()) { response.sendRedirect(ctx + "/tenant_login.jsp"); return; }
  String actorUser = userUuid.isBlank() ? userEmail : userUuid;
  String csrfToken = csrfForRender(request);

  String message = "";
  String error = "";

  online_payments payments = online_payments.defaultStore();
  billing_accounting ledger = billing_runtime_registry.tenantLedger(tenantUuid);
  matters matterStore = matters.defaultStore();

  if ("POST".equalsIgnoreCase(request.getMethod())) {
    String action = safe(request.getParameter("action")).trim();
    try {
      if ("create_checkout".equals(action)) {
        online_payments.CheckoutInput in = new online_payments.CheckoutInput();
        in.invoiceUuid = safe(request.getParameter("invoice_uuid")).trim();
        in.processorKey = safe(request.getParameter("processor_key")).trim();
        in.amountCents = dollarsToCents(request.getParameter("amount"));
        in.currency = safe(request.getParameter("currency")).trim();
        in.payerName = safe(request.getParameter("payer_name")).trim();
        in.payerEmail = safe(request.getParameter("payer_email")).trim();
        online_payments.CheckoutResult created = payments.createCheckout(tenantUuid, in);
        message = "Checkout created. URL: " + safe(created == null ? "" : created.checkoutUrl);
      } else if ("mark_paid".equals(action)) {
        String tx = safe(request.getParameter("transaction_uuid")).trim();
        payments.markPaid(
                tenantUuid,
                tx,
                safe(request.getParameter("provider_payment_id")).trim(),
                safe(request.getParameter("reference")).trim(),
                ""
        );
        message = "Transaction marked paid and posted to billing ledger.";
      } else if ("mark_failed".equals(action)) {
        String tx = safe(request.getParameter("transaction_uuid")).trim();
        payments.markFailed(tenantUuid, tx, safe(request.getParameter("error_message")).trim());
        message = "Transaction marked failed.";
      } else if ("cancel_txn".equals(action)) {
        String tx = safe(request.getParameter("transaction_uuid")).trim();
        payments.setCancelled(tenantUuid, tx, safe(request.getParameter("reason")).trim());
        message = "Transaction cancelled.";
      }
    } catch (Exception ex) {
      error = safe(ex.getMessage()).isBlank() ? ex.getClass().getSimpleName() : safe(ex.getMessage());
    }
  }

  List<matters.MatterRec> allMatters = new ArrayList<matters.MatterRec>();
  List<online_payments.ProcessorInfo> processors = payments.listProcessors();
  List<online_payments.PaymentTransactionRec> transactions = new ArrayList<online_payments.PaymentTransactionRec>();
  LinkedHashMap<String, String> invoiceLabels = new LinkedHashMap<String, String>();
  try {
    allMatters = matterStore.listAll(tenantUuid);
    for (matters.MatterRec m : allMatters) {
      if (m == null) continue;
      String mu = safe(m.uuid).trim();
      if (mu.isBlank()) continue;
      for (billing_accounting.InvoiceRec inv : ledger.listInvoicesForMatter(mu)) {
        if (inv == null) continue;
        String id = safe(inv.uuid).trim();
        if (id.isBlank()) continue;
        invoiceLabels.put(id, safe(m.label) + " | " + id + " | " + safe(inv.status) + " | outstanding " + inv.outstandingCents + " cents");
      }
    }
    transactions = payments.listTransactions(tenantUuid, "", "", "");
  } catch (Exception ex) {
    if (error.isBlank()) error = "Unable to load payment data: " + safe(ex.getMessage());
  }
%>

<jsp:include page="header.jsp" />

<section class="card">
  <h1 class="u-m-0">Online Payments</h1>
  <div class="meta u-mt-6">Create checkout transactions for issued invoices and post paid events into billing ledger entries.</div>
  <% if (!message.isBlank()) { %><div class="alert alert-success u-mt-12"><%= esc(message) %></div><% } %>
  <% if (!error.isBlank()) { %><div class="alert alert-error u-mt-12"><%= esc(error) %></div><% } %>
</section>

<section class="card section-gap-12">
  <h2 class="u-m-0">Create Checkout</h2>
  <form method="post" action="<%= ctx %>/online_payments.jsp" class="form">
    <input type="hidden" name="csrf_token" value="<%= esc(csrfToken) %>" />
    <input type="hidden" name="action" value="create_checkout" />
    <div class="grid grid-3">
      <label><span>Invoice</span>
        <select name="invoice_uuid" required>
          <option value="">Select invoice</option>
          <% for (java.util.Map.Entry<String,String> e : invoiceLabels.entrySet()) { if (e == null) continue; %>
          <option value="<%= esc(safe(e.getKey())) %>"><%= esc(safe(e.getValue())) %></option>
          <% } %>
        </select>
      </label>
      <label><span>Processor</span>
        <select name="processor_key">
          <% for (online_payments.ProcessorInfo p : processors) { if (p == null) continue; %>
          <option value="<%= esc(safe(p.processorKey)) %>"><%= esc(safe(p.label)) %> (<%= esc(safe(p.mode)) %>)</option>
          <% } %>
        </select>
      </label>
      <label><span>Amount (USD)</span><input type="text" name="amount" placeholder="Leave blank for full outstanding" /></label>
      <label><span>Currency</span><input type="text" name="currency" value="USD" /></label>
      <label><span>Payer Name</span><input type="text" name="payer_name" /></label>
      <label><span>Payer Email</span><input type="email" name="payer_email" /></label>
    </div>
    <div class="u-mt-12"><button class="btn" type="submit">Create Checkout</button></div>
  </form>
</section>

<section class="card section-gap-12">
  <h2 class="u-m-0">Payment Transactions</h2>
  <div class="table-wrap table-wrap-tight">
    <table class="table">
      <thead>
      <tr>
        <th>Transaction</th>
        <th>Invoice</th>
        <th>Processor</th>
        <th>Status</th>
        <th>Amount</th>
        <th>Checkout URL</th>
        <th>Updated</th>
        <th>Actions</th>
      </tr>
      </thead>
      <tbody>
      <% if (transactions.isEmpty()) { %>
      <tr><td colspan="8" class="meta">No payment transactions yet.</td></tr>
      <% } else {
           for (online_payments.PaymentTransactionRec tx : transactions) {
             if (tx == null) continue;
      %>
      <tr>
        <td><code><%= esc(safe(tx.transactionUuid)) %></code></td>
        <td><%= esc(safe(tx.invoiceUuid)) %></td>
        <td><%= esc(safe(tx.processorKey)) %></td>
        <td><%= esc(safe(tx.status)) %></td>
        <td><%= tx.amountCents %> <%= esc(safe(tx.currency)) %></td>
        <td><code><%= esc(safe(tx.checkoutUrl)) %></code></td>
        <td><%= esc(safe(tx.updatedAt)) %></td>
        <td>
          <details>
            <summary class="btn btn-ghost">Update</summary>
            <form method="post" action="<%= ctx %>/online_payments.jsp" class="form u-mt-8">
              <input type="hidden" name="csrf_token" value="<%= esc(csrfToken) %>" />
              <input type="hidden" name="action" value="mark_paid" />
              <input type="hidden" name="transaction_uuid" value="<%= esc(safe(tx.transactionUuid)) %>" />
              <label><span>Provider Payment ID</span><input type="text" name="provider_payment_id" value="<%= esc(safe(tx.providerPaymentId)) %>" /></label>
              <label><span>Reference</span><input type="text" name="reference" value="<%= esc(safe(tx.reference)) %>" /></label>
              <button class="btn btn-ghost" type="submit">Mark Paid</button>
            </form>
            <form method="post" action="<%= ctx %>/online_payments.jsp" class="form u-mt-8">
              <input type="hidden" name="csrf_token" value="<%= esc(csrfToken) %>" />
              <input type="hidden" name="action" value="mark_failed" />
              <input type="hidden" name="transaction_uuid" value="<%= esc(safe(tx.transactionUuid)) %>" />
              <label><span>Error Message</span><input type="text" name="error_message" /></label>
              <button class="btn btn-ghost" type="submit">Mark Failed</button>
            </form>
            <form method="post" action="<%= ctx %>/online_payments.jsp" class="form u-mt-8">
              <input type="hidden" name="csrf_token" value="<%= esc(csrfToken) %>" />
              <input type="hidden" name="action" value="cancel_txn" />
              <input type="hidden" name="transaction_uuid" value="<%= esc(safe(tx.transactionUuid)) %>" />
              <label><span>Reason</span><input type="text" name="reason" /></label>
              <button class="btn btn-ghost" type="submit">Cancel</button>
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

<jsp:include page="footer.jsp" />
