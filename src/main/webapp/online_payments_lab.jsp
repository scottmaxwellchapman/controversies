<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ page import="java.net.URLEncoder" %>
<%@ page import="java.nio.charset.StandardCharsets" %>
<%@ page import="java.util.ArrayList" %>
<%@ page import="java.util.LinkedHashMap" %>
<%@ page import="java.util.List" %>
<%@ page import="java.util.Locale" %>
<%@ page import="net.familylawandprobate.controversies.billing_accounting" %>
<%@ page import="net.familylawandprobate.controversies.billing_runtime_registry" %>
<%@ page import="net.familylawandprobate.controversies.matters" %>
<%@ page import="net.familylawandprobate.controversies.online_payments" %>
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
  private static long dollarsToCents(String raw) {
    String v = safe(raw).trim();
    if (v.isBlank()) return 0L;
    v = v.replace("$", "").replace(",", "").trim();
    boolean negative = v.startsWith("-");
    if (negative) v = v.substring(1).trim();
    try {
      java.math.BigDecimal n = new java.math.BigDecimal(v);
      long cents = n.multiply(java.math.BigDecimal.valueOf(100L))
                    .setScale(0, java.math.RoundingMode.HALF_UP)
                    .longValueExact();
      return negative ? -cents : cents;
    } catch (Exception ignored) {
      return 0L;
    }
  }
  private static String centsToText(long cents) {
    long abs = Math.abs(cents);
    long whole = abs / 100L;
    long rem = abs % 100L;
    String sign = cents < 0L ? "-" : "";
    return sign + whole + "." + String.format(Locale.ROOT, "%02d", rem);
  }
%>
<%
String ctx = safe(request.getContextPath());
request.setAttribute("activeNav", "/online_payments_lab.jsp");
String tenantUuid = safe((String) session.getAttribute("tenant.uuid")).trim();
if (tenantUuid.isBlank()) {
  response.sendRedirect(ctx + "/tenant_login.jsp");
  return;
}
String csrfToken = csrfForRender(request);

String action = safe(request.getParameter("action")).trim();
String selectedTransactionUuid = safe(request.getParameter("transaction_uuid")).trim();
String filterMatterUuid = safe(request.getParameter("filter_matter_uuid")).trim();
String filterInvoiceUuid = safe(request.getParameter("filter_invoice_uuid")).trim();
String filterStatus = safe(request.getParameter("filter_status")).trim();
String message = "";
String error = "";
String createdCheckoutUrl = "";

online_payments paymentStore = online_payments.defaultStore();
if (!action.isBlank()) {
  try {
    if ("create_checkout".equals(action)) {
      online_payments.CheckoutInput in = new online_payments.CheckoutInput();
      in.invoiceUuid = safe(request.getParameter("invoice_uuid")).trim();
      in.processorKey = safe(request.getParameter("processor_key")).trim();
      in.currency = "USD";
      long amountCents = dollarsToCents(request.getParameter("amount_usd"));
      if (amountCents > 0L) in.amountCents = amountCents;
      in.payerName = safe(request.getParameter("payer_name"));
      in.payerEmail = safe(request.getParameter("payer_email"));
      in.returnUrl = safe(request.getParameter("return_url"));
      in.cancelUrl = safe(request.getParameter("cancel_url"));
      in.metadataJson = safe(request.getParameter("metadata_json"));
      online_payments.CheckoutResult created = paymentStore.createCheckout(tenantUuid, in);
      selectedTransactionUuid = safe(created == null || created.transaction == null ? "" : created.transaction.transactionUuid).trim();
      createdCheckoutUrl = safe(created == null ? "" : created.checkoutUrl).trim();
      if (filterInvoiceUuid.isBlank()) filterInvoiceUuid = in.invoiceUuid;
      message = "Checkout created.";
    } else if ("mark_paid".equals(action)) {
      String tx = safe(request.getParameter("transaction_uuid")).trim();
      if (tx.isBlank()) throw new IllegalArgumentException("transaction_uuid is required.");
      paymentStore.markPaid(
        tenantUuid,
        tx,
        safe(request.getParameter("provider_payment_id")),
        safe(request.getParameter("reference")),
        safe(request.getParameter("posted_at"))
      );
      selectedTransactionUuid = tx;
      message = "Transaction marked paid.";
    } else if ("mark_failed".equals(action)) {
      String tx = safe(request.getParameter("transaction_uuid")).trim();
      if (tx.isBlank()) throw new IllegalArgumentException("transaction_uuid is required.");
      paymentStore.markFailed(tenantUuid, tx, safe(request.getParameter("error_message")));
      selectedTransactionUuid = tx;
      message = "Transaction marked failed.";
    } else if ("cancel_transaction".equals(action)) {
      String tx = safe(request.getParameter("transaction_uuid")).trim();
      if (tx.isBlank()) throw new IllegalArgumentException("transaction_uuid is required.");
      paymentStore.setCancelled(tenantUuid, tx, safe(request.getParameter("reason")));
      selectedTransactionUuid = tx;
      message = "Transaction cancelled.";
    }
  } catch (Exception ex) {
    error = safe(ex.getMessage());
    if (error.isBlank()) error = "Online payment action failed.";
  }
}

List<online_payments.ProcessorInfo> processors = new ArrayList<online_payments.ProcessorInfo>();
List<matters.MatterRec> matterRows = new ArrayList<matters.MatterRec>();
LinkedHashMap<String, String> matterLabelByUuid = new LinkedHashMap<String, String>();
List<billing_accounting.InvoiceRec> invoiceRows = new ArrayList<billing_accounting.InvoiceRec>();
LinkedHashMap<String, billing_accounting.InvoiceRec> invoiceByUuid = new LinkedHashMap<String, billing_accounting.InvoiceRec>();

List<online_payments.PaymentTransactionRec> transactions = new ArrayList<online_payments.PaymentTransactionRec>();
online_payments.PaymentTransactionRec selectedTransaction = null;

try {
  processors = paymentStore.listProcessors();
  matterRows = matters.defaultStore().listAll(tenantUuid);
  billing_accounting ledger = billing_runtime_registry.tenantLedger(tenantUuid);
  for (matters.MatterRec m : matterRows) {
    if (m == null) continue;
    String matterUuid = safe(m.uuid).trim();
    if (matterUuid.isBlank()) continue;
    String label = safe(m.label).trim();
    if (label.isBlank()) label = matterUuid;
    matterLabelByUuid.put(matterUuid, label);
    for (billing_accounting.InvoiceRec inv : ledger.listInvoicesForMatter(matterUuid)) {
      if (inv == null) continue;
      String invUuid = safe(inv.uuid).trim();
      if (invUuid.isBlank()) continue;
      invoiceRows.add(inv);
      invoiceByUuid.put(invUuid, inv);
    }
  }
  invoiceRows.sort((a, b) -> safe(b == null ? "" : b.issuedAt).compareTo(safe(a == null ? "" : a.issuedAt)));

  transactions = paymentStore.listTransactions(tenantUuid, filterMatterUuid, filterInvoiceUuid, filterStatus);
  if (selectedTransactionUuid.isBlank() && !transactions.isEmpty()) {
    selectedTransactionUuid = safe(transactions.get(0).transactionUuid).trim();
  }
  if (!selectedTransactionUuid.isBlank()) {
    for (online_payments.PaymentTransactionRec row : transactions) {
      if (row == null) continue;
      if (selectedTransactionUuid.equals(safe(row.transactionUuid).trim())) {
        selectedTransaction = row;
        break;
      }
    }
    if (selectedTransaction == null) {
      selectedTransaction = paymentStore.getTransaction(tenantUuid, selectedTransactionUuid);
    }
  }
} catch (Exception ex) {
  if (error.isBlank()) {
    error = safe(ex.getMessage());
    if (error.isBlank()) error = "Unable to load online payment data.";
  }
}
%>
<jsp:include page="header.jsp" />

<section class="card">
  <h1 class="u-m-0">Online Payments Lab</h1>
  <div class="meta u-mt-6">Test payment processors and transactions via <code>online_payments</code>.</div>
</section>

<section class="card section-gap-12">
  <form method="get" action="<%= ctx %>/online_payments_lab.jsp" class="form">
    <div class="grid grid-3">
      <div>
        <label>Matter Filter</label>
        <select name="filter_matter_uuid">
          <option value="">All Matters</option>
          <% for (matters.MatterRec m : matterRows) {
               if (m == null) continue;
               String mUuid = safe(m.uuid).trim();
               if (mUuid.isBlank()) continue;
               String mLabel = safe(m.label).trim();
               if (mLabel.isBlank()) mLabel = mUuid;
          %>
            <option value="<%= esc(mUuid) %>" <%= mUuid.equals(filterMatterUuid) ? "selected" : "" %>><%= esc(mLabel) %></option>
          <% } %>
        </select>
      </div>
      <div>
        <label>Invoice Filter</label>
        <select name="filter_invoice_uuid">
          <option value="">All Invoices</option>
          <% for (billing_accounting.InvoiceRec inv : invoiceRows) {
               if (inv == null) continue;
               String invUuid = safe(inv.uuid).trim();
               if (invUuid.isBlank()) continue;
          %>
            <option value="<%= esc(invUuid) %>" <%= invUuid.equals(filterInvoiceUuid) ? "selected" : "" %>>
              <%= esc(invUuid) %> (<%= esc(safe(inv.status)) %>, due <%= esc(centsToText(inv.outstandingCents)) %>)
            </option>
          <% } %>
        </select>
      </div>
      <div>
        <label>Status Filter</label>
        <select name="filter_status">
          <option value="">All Statuses</option>
          <option value="<%= online_payments.STATUS_PENDING %>" <%= online_payments.STATUS_PENDING.equalsIgnoreCase(filterStatus) ? "selected" : "" %>><%= online_payments.STATUS_PENDING %></option>
          <option value="<%= online_payments.STATUS_PAID %>" <%= online_payments.STATUS_PAID.equalsIgnoreCase(filterStatus) ? "selected" : "" %>><%= online_payments.STATUS_PAID %></option>
          <option value="<%= online_payments.STATUS_FAILED %>" <%= online_payments.STATUS_FAILED.equalsIgnoreCase(filterStatus) ? "selected" : "" %>><%= online_payments.STATUS_FAILED %></option>
          <option value="<%= online_payments.STATUS_CANCELLED %>" <%= online_payments.STATUS_CANCELLED.equalsIgnoreCase(filterStatus) ? "selected" : "" %>><%= online_payments.STATUS_CANCELLED %></option>
        </select>
      </div>
    </div>
    <button class="btn btn-ghost" type="submit">Refresh</button>
  </form>

  <% if (!message.isBlank()) { %><div class="alert alert-ok u-mt-8"><%= esc(message) %></div><% } %>
  <% if (!createdCheckoutUrl.isBlank()) { %>
    <div class="meta u-mt-8">Checkout URL: <a href="<%= esc(createdCheckoutUrl) %>" target="_blank" rel="noopener noreferrer"><%= esc(createdCheckoutUrl) %></a></div>
  <% } %>
  <% if (!error.isBlank()) { %><div class="alert alert-error u-mt-8"><%= esc(error) %></div><% } %>
</section>

<section class="card section-gap-12">
  <h2 class="u-m-0">Create Checkout</h2>
  <form method="post" action="<%= ctx %>/online_payments_lab.jsp" class="form">
    <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
    <input type="hidden" name="action" value="create_checkout" />
    <input type="hidden" name="filter_matter_uuid" value="<%= esc(filterMatterUuid) %>" />
    <input type="hidden" name="filter_invoice_uuid" value="<%= esc(filterInvoiceUuid) %>" />
    <input type="hidden" name="filter_status" value="<%= esc(filterStatus) %>" />
    <div class="grid grid-3">
      <div>
        <label>Invoice UUID</label>
        <select name="invoice_uuid">
          <option value="">Select invoice</option>
          <% for (billing_accounting.InvoiceRec inv : invoiceRows) {
               if (inv == null) continue;
               String invUuid = safe(inv.uuid).trim();
               if (invUuid.isBlank()) continue;
               String selected = invUuid.equals(filterInvoiceUuid) ? "selected" : "";
          %>
            <option value="<%= esc(invUuid) %>" <%= selected %>>
              <%= esc(invUuid) %> (<%= esc(safe(inv.status)) %>, due <%= esc(centsToText(inv.outstandingCents)) %>)
            </option>
          <% } %>
        </select>
      </div>
      <div>
        <label>Processor</label>
        <select name="processor_key">
          <% for (online_payments.ProcessorInfo p : processors) {
               if (p == null) continue;
          %>
            <option value="<%= esc(safe(p.processorKey)) %>"><%= esc(safe(p.processorKey)) %> - <%= esc(safe(p.label)) %> (<%= esc(safe(p.mode)) %>)</option>
          <% } %>
        </select>
      </div>
      <div><label>Amount (USD, optional)</label><input name="amount_usd" type="text" placeholder="blank uses invoice outstanding" /></div>
      <div><label>Payer Name</label><input name="payer_name" type="text" /></div>
      <div><label>Payer E-Mail</label><input name="payer_email" type="text" /></div>
      <div><label>Return URL</label><input name="return_url" type="text" placeholder="<%= esc(ctx) %>/billing.jsp" /></div>
      <div><label>Cancel URL</label><input name="cancel_url" type="text" placeholder="<%= esc(ctx) %>/billing.jsp" /></div>
    </div>
    <label>Metadata JSON (optional string blob)</label>
    <textarea name="metadata_json" rows="3">{}</textarea>
    <button class="btn" type="submit">Create Checkout Transaction</button>
  </form>
</section>

<section class="card section-gap-12">
  <h2 class="u-m-0">Transactions</h2>
  <div class="table-wrap table-wrap-tight u-mt-8">
    <table class="table">
      <thead>
        <tr>
          <th>Transaction</th>
          <th>Matter</th>
          <th>Invoice</th>
          <th>Processor</th>
          <th>Status</th>
          <th>Amount</th>
          <th>Updated</th>
          <th></th>
        </tr>
      </thead>
      <tbody>
      <% if (transactions.isEmpty()) { %>
        <tr><td colspan="8" class="meta">No payment transactions.</td></tr>
      <% } else {
           for (online_payments.PaymentTransactionRec row : transactions) {
             if (row == null) continue;
             String txUuid = safe(row.transactionUuid).trim();
             String matterLabel = matterLabelByUuid.getOrDefault(safe(row.matterUuid).trim(), safe(row.matterUuid).trim());
      %>
        <tr>
          <td><code><%= esc(txUuid) %></code></td>
          <td><%= esc(safe(matterLabel)) %></td>
          <td><code><%= esc(safe(row.invoiceUuid)) %></code></td>
          <td><%= esc(safe(row.processorKey)) %></td>
          <td><%= esc(safe(row.status)) %></td>
          <td><code><%= esc(centsToText(row.amountCents)) %></code> <%= esc(safe(row.currency)) %></td>
          <td><%= esc(safe(row.updatedAt)) %></td>
          <td>
            <a class="btn btn-ghost" href="<%= ctx %>/online_payments_lab.jsp?transaction_uuid=<%= enc(txUuid) %>&filter_matter_uuid=<%= enc(filterMatterUuid) %>&filter_invoice_uuid=<%= enc(filterInvoiceUuid) %>&filter_status=<%= enc(filterStatus) %>">Open</a>
          </td>
        </tr>
      <%   }
         } %>
      </tbody>
    </table>
  </div>
</section>

<% if (selectedTransaction != null) { %>
<section class="card section-gap-12">
  <h2 class="u-m-0">Selected Transaction</h2>
  <div class="table-wrap table-wrap-tight u-mt-8">
    <table class="table">
      <tbody>
        <tr><th>Transaction UUID</th><td><code><%= esc(safe(selectedTransaction.transactionUuid)) %></code></td></tr>
        <tr><th>Status</th><td><%= esc(safe(selectedTransaction.status)) %></td></tr>
        <tr><th>Processor</th><td><%= esc(safe(selectedTransaction.processorKey)) %></td></tr>
        <tr><th>Invoice UUID</th><td><code><%= esc(safe(selectedTransaction.invoiceUuid)) %></code></td></tr>
        <tr><th>Matter UUID</th><td><code><%= esc(safe(selectedTransaction.matterUuid)) %></code></td></tr>
        <tr><th>Amount</th><td><code><%= esc(centsToText(selectedTransaction.amountCents)) %></code> <%= esc(safe(selectedTransaction.currency)) %></td></tr>
        <tr><th>Checkout URL</th><td><%= safe(selectedTransaction.checkoutUrl).trim().isBlank() ? "" : "<a href=\"" + esc(selectedTransaction.checkoutUrl) + "\" target=\"_blank\" rel=\"noopener noreferrer\">" + esc(selectedTransaction.checkoutUrl) + "</a>" %></td></tr>
        <tr><th>Provider Checkout ID</th><td><code><%= esc(safe(selectedTransaction.providerCheckoutId)) %></code></td></tr>
        <tr><th>Provider Payment ID</th><td><code><%= esc(safe(selectedTransaction.providerPaymentId)) %></code></td></tr>
        <tr><th>Reference</th><td><%= esc(safe(selectedTransaction.reference)) %></td></tr>
        <tr><th>Error Message</th><td><%= esc(safe(selectedTransaction.errorMessage)) %></td></tr>
        <tr><th>Created / Updated / Paid / Failed</th><td><%= esc(safe(selectedTransaction.createdAt)) %> / <%= esc(safe(selectedTransaction.updatedAt)) %> / <%= esc(safe(selectedTransaction.paidAt)) %> / <%= esc(safe(selectedTransaction.failedAt)) %></td></tr>
      </tbody>
    </table>
  </div>

  <h3 class="u-mt-12">Mark Paid</h3>
  <form method="post" action="<%= ctx %>/online_payments_lab.jsp" class="form">
    <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
    <input type="hidden" name="action" value="mark_paid" />
    <input type="hidden" name="transaction_uuid" value="<%= esc(safe(selectedTransaction.transactionUuid)) %>" />
    <input type="hidden" name="filter_matter_uuid" value="<%= esc(filterMatterUuid) %>" />
    <input type="hidden" name="filter_invoice_uuid" value="<%= esc(filterInvoiceUuid) %>" />
    <input type="hidden" name="filter_status" value="<%= esc(filterStatus) %>" />
    <div class="grid grid-3">
      <div><label>Provider Payment ID</label><input name="provider_payment_id" type="text" /></div>
      <div><label>Reference</label><input name="reference" type="text" value="manual settlement" /></div>
      <div><label>Posted At (ISO, optional)</label><input name="posted_at" type="text" placeholder="2026-03-10T10:00:00Z" /></div>
    </div>
    <button class="btn btn-ghost" type="submit">Mark Paid</button>
  </form>

  <h3 class="u-mt-12">Mark Failed</h3>
  <form method="post" action="<%= ctx %>/online_payments_lab.jsp" class="form">
    <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
    <input type="hidden" name="action" value="mark_failed" />
    <input type="hidden" name="transaction_uuid" value="<%= esc(safe(selectedTransaction.transactionUuid)) %>" />
    <input type="hidden" name="filter_matter_uuid" value="<%= esc(filterMatterUuid) %>" />
    <input type="hidden" name="filter_invoice_uuid" value="<%= esc(filterInvoiceUuid) %>" />
    <input type="hidden" name="filter_status" value="<%= esc(filterStatus) %>" />
    <label>Error Message</label>
    <input name="error_message" type="text" value="payment declined by processor" />
    <button class="btn btn-ghost" type="submit">Mark Failed</button>
  </form>

  <h3 class="u-mt-12">Cancel Transaction</h3>
  <form method="post" action="<%= ctx %>/online_payments_lab.jsp" class="form">
    <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
    <input type="hidden" name="action" value="cancel_transaction" />
    <input type="hidden" name="transaction_uuid" value="<%= esc(safe(selectedTransaction.transactionUuid)) %>" />
    <input type="hidden" name="filter_matter_uuid" value="<%= esc(filterMatterUuid) %>" />
    <input type="hidden" name="filter_invoice_uuid" value="<%= esc(filterInvoiceUuid) %>" />
    <input type="hidden" name="filter_status" value="<%= esc(filterStatus) %>" />
    <label>Reason</label>
    <input name="reason" type="text" value="user requested cancellation" />
    <button class="btn btn-ghost" type="submit" onclick="return confirm('Cancel this transaction?');">Cancel Transaction</button>
  </form>
</section>
<% } %>

<jsp:include page="footer.jsp" />
