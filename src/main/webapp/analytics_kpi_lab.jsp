<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ page import="java.util.ArrayList" %>
<%@ page import="java.util.List" %>
<%@ page import="java.util.Locale" %>
<%@ page import="net.familylawandprobate.controversies.kpi_analytics" %>
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
  private static int clampDays(String raw, int fallback) {
    try {
      int n = Integer.parseInt(safe(raw).trim());
      if (n < 1) return 1;
      if (n > 366) return 366;
      return n;
    } catch (Exception ignored) {
      return fallback;
    }
  }
  private static String centsToText(long cents) {
    long abs = Math.abs(cents);
    long whole = abs / 100L;
    long rem = abs % 100L;
    String sign = cents < 0L ? "-" : "";
    return sign + whole + "." + String.format(Locale.ROOT, "%02d", rem);
  }
  private static String pctText(double value) {
    return String.format(Locale.ROOT, "%.2f%%", value);
  }
%>
<%
String ctx = safe(request.getContextPath());
request.setAttribute("activeNav", "/analytics_kpi_lab.jsp");
String tenantUuid = safe((String) session.getAttribute("tenant.uuid")).trim();
if (tenantUuid.isBlank()) {
  response.sendRedirect(ctx + "/tenant_login.jsp");
  return;
}

int days = clampDays(request.getParameter("days"), 30);
String error = "";

kpi_analytics service = kpi_analytics.defaultService();
kpi_analytics.SummaryRec summary = null;
List<kpi_analytics.DailyRec> dailyRows = new ArrayList<kpi_analytics.DailyRec>();

try {
  summary = service.summary(tenantUuid);
  dailyRows = service.dailySeries(tenantUuid, days);
} catch (Exception ex) {
  error = safe(ex.getMessage());
  if (error.isBlank()) error = "Unable to load KPI analytics.";
}
%>
<jsp:include page="header.jsp" />

<section class="card">
  <h1 class="u-m-0">KPI Analytics Lab</h1>
  <div class="meta u-mt-6">Tenant-level KPIs from <code>kpi_analytics</code>.</div>
</section>

<section class="card section-gap-12">
  <form method="get" action="<%= ctx %>/analytics_kpi_lab.jsp" class="u-flex u-gap-8 u-items-center u-wrap">
    <label>Daily Window
      <input name="days" type="number" min="1" max="366" value="<%= days %>" />
    </label>
    <button class="btn btn-ghost" type="submit">Refresh</button>
  </form>
  <% if (!error.isBlank()) { %>
    <div class="alert alert-error u-mt-8"><%= esc(error) %></div>
  <% } %>
</section>

<% if (summary != null) { %>
<section class="card section-gap-12">
  <h2 class="u-m-0">Summary</h2>
  <div class="table-wrap table-wrap-tight u-mt-8">
    <table class="table">
      <tbody>
        <tr><th>Generated At</th><td><%= esc(safe(summary.generatedAt)) %></td></tr>
        <tr><th>Matters (total / active)</th><td><%= summary.mattersTotal %> / <%= summary.mattersActive %></td></tr>
        <tr><th>Leads (total / retained)</th><td><%= summary.leadsTotal %> / <%= summary.leadsRetained %></td></tr>
        <tr><th>Lead Conversion Rate</th><td><%= esc(pctText(summary.leadConversionRate)) %></td></tr>
        <tr><th>Leads By Status</th><td>new=<%= summary.leadsNew %>, qualified=<%= summary.leadsQualified %>, consult=<%= summary.leadsConsultScheduled %>, retained=<%= summary.leadsRetained %>, closed_lost=<%= summary.leadsClosedLost %></td></tr>
        <tr><th>Tasks (total / open / completed / overdue)</th><td><%= summary.tasksTotal %> / <%= summary.tasksOpen %> / <%= summary.tasksCompleted %> / <%= summary.tasksOverdue %></td></tr>
        <tr><th>Invoices (total / issued / paid)</th><td><%= summary.invoicesTotal %> / <%= summary.invoicesIssued %> / <%= summary.invoicesPaid %></td></tr>
        <tr><th>Invoice Outstanding</th><td><code><%= esc(centsToText(summary.invoiceOutstandingCents)) %></code> USD</td></tr>
        <tr><th>Invoice Paid</th><td><code><%= esc(centsToText(summary.invoicePaidCents)) %></code> USD</td></tr>
        <tr><th>Trust Balance Total</th><td><code><%= esc(centsToText(summary.trustBalanceTotalCents)) %></code> USD</td></tr>
        <tr><th>Payments Received</th><td><code><%= esc(centsToText(summary.paymentsReceivedCents)) %></code> USD</td></tr>
        <tr><th>Payment Transactions (pending / paid / failed)</th><td><%= summary.paymentTransactionsPending %> / <%= summary.paymentTransactionsPaid %> / <%= summary.paymentTransactionsFailed %></td></tr>
        <tr><th>Signatures (total / pending / signed / declined)</th><td><%= summary.signaturesTotal %> / <%= summary.signaturesPending %> / <%= summary.signaturesSigned %> / <%= summary.signaturesDeclined %></td></tr>
      </tbody>
    </table>
  </div>
</section>
<% } %>

<section class="card section-gap-12">
  <h2 class="u-m-0">Daily Series (<%= days %> days)</h2>
  <div class="table-wrap table-wrap-tight u-mt-8">
    <table class="table">
      <thead>
        <tr>
          <th>Date</th>
          <th>Leads Created</th>
          <th>Leads Converted</th>
          <th>Payments Collected (USD)</th>
          <th>Signatures Completed</th>
          <th>Tasks Completed</th>
        </tr>
      </thead>
      <tbody>
      <% if (dailyRows.isEmpty()) { %>
        <tr><td colspan="6" class="meta">No series rows available.</td></tr>
      <% } else {
           for (kpi_analytics.DailyRec row : dailyRows) {
             if (row == null) continue;
      %>
        <tr>
          <td><%= esc(safe(row.date)) %></td>
          <td><%= row.leadsCreated %></td>
          <td><%= row.leadsConverted %></td>
          <td><code><%= esc(centsToText(row.paymentsCollectedCents)) %></code></td>
          <td><%= row.signaturesCompleted %></td>
          <td><%= row.tasksCompleted %></td>
        </tr>
      <%   }
         } %>
      </tbody>
    </table>
  </div>
</section>

<jsp:include page="footer.jsp" />
