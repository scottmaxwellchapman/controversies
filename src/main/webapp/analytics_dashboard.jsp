<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" isELIgnored="true" %>
<%@ page import="java.util.ArrayList" %>
<%@ page import="java.util.List" %>

<%@ page import="net.familylawandprobate.controversies.kpi_analytics" %>

<%@ include file="security.jspf" %>
<%
  if (!require_login()) return;
%>

<%!
  private static final String S_TENANT_UUID = "tenant.uuid";
  private static String safe(String s){ return s == null ? "" : s; }
  private static String esc(String s){ return safe(s).replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;"); }
%>

<%
  String ctx = safe(request.getContextPath());
  request.setAttribute("activeNav", "/analytics_dashboard.jsp");
  String tenantUuid = safe((String) session.getAttribute(S_TENANT_UUID)).trim();
  if (tenantUuid.isBlank()) { response.sendRedirect(ctx + "/tenant_login.jsp"); return; }

  String error = "";
  int days = 30;
  try { days = Math.max(7, Math.min(90, Integer.parseInt(safe(request.getParameter("days")).trim()))); } catch (Exception ignored) {}

  kpi_analytics.SummaryRec summary = null;
  List<kpi_analytics.DailyRec> daily = new ArrayList<kpi_analytics.DailyRec>();
  try {
    kpi_analytics service = kpi_analytics.defaultService();
    summary = service.summary(tenantUuid);
    daily = service.dailySeries(tenantUuid, days);
  } catch (Exception ex) {
    error = safe(ex.getMessage()).isBlank() ? ex.getClass().getSimpleName() : safe(ex.getMessage());
  }
%>

<jsp:include page="header.jsp" />

<section class="card">
  <h1 class="u-m-0">Firm Analytics Dashboard</h1>
  <div class="meta u-mt-6">KPI summary across intake, tasks, billing/trust, online payments, and signature workflows.</div>
  <% if (!error.isBlank()) { %><div class="alert alert-error u-mt-12"><%= esc(error) %></div><% } %>
</section>

<section class="card section-gap-12">
  <div class="u-flex u-justify-between u-items-center u-wrap u-gap-8">
    <h2 class="u-m-0">KPI Snapshot</h2>
    <form method="get" action="<%= ctx %>/analytics_dashboard.jsp" class="u-flex u-gap-8 u-items-center">
      <label>Trend Days
        <select name="days">
          <option value="14" <%= days == 14 ? "selected" : "" %>>14</option>
          <option value="30" <%= days == 30 ? "selected" : "" %>>30</option>
          <option value="60" <%= days == 60 ? "selected" : "" %>>60</option>
          <option value="90" <%= days == 90 ? "selected" : "" %>>90</option>
        </select>
      </label>
      <button class="btn btn-ghost" type="submit">Refresh</button>
    </form>
  </div>

  <% if (summary != null) { %>
  <div class="grid grid-3 u-mt-12">
    <div class="panel"><strong>Matters</strong><div class="meta">Active: <%= summary.mattersActive %> / Total: <%= summary.mattersTotal %></div></div>
    <div class="panel"><strong>Lead Conversion</strong><div class="meta"><%= String.format(java.util.Locale.ROOT, "%.2f", summary.leadConversionRate) %>% retained</div></div>
    <div class="panel"><strong>Tasks</strong><div class="meta">Open: <%= summary.tasksOpen %> | Overdue: <%= summary.tasksOverdue %></div></div>
    <div class="panel"><strong>Invoices</strong><div class="meta">Outstanding (cents): <%= summary.invoiceOutstandingCents %></div></div>
    <div class="panel"><strong>Trust Balance</strong><div class="meta">Total (cents): <%= summary.trustBalanceTotalCents %></div></div>
    <div class="panel"><strong>Payments</strong><div class="meta">Collected (cents): <%= summary.paymentsReceivedCents %></div></div>
    <div class="panel"><strong>Online Txn</strong><div class="meta">Pending: <%= summary.paymentTransactionsPending %> | Paid: <%= summary.paymentTransactionsPaid %></div></div>
    <div class="panel"><strong>Signatures</strong><div class="meta">Pending: <%= summary.signaturesPending %> | Signed: <%= summary.signaturesSigned %></div></div>
    <div class="panel"><strong>Generated</strong><div class="meta"><%= esc(safe(summary.generatedAt)) %></div></div>
  </div>
  <% } %>
</section>

<section class="card section-gap-12">
  <h2 class="u-m-0">Daily Trend</h2>
  <div class="table-wrap table-wrap-tight u-mt-12">
    <table class="table">
      <thead>
      <tr>
        <th>Date</th>
        <th>Leads Created</th>
        <th>Leads Converted</th>
        <th>Payments Collected (cents)</th>
        <th>Signatures Completed</th>
        <th>Tasks Completed</th>
      </tr>
      </thead>
      <tbody>
      <% if (daily.isEmpty()) { %>
      <tr><td colspan="6" class="meta">No trend data available.</td></tr>
      <% } else {
           for (kpi_analytics.DailyRec row : daily) {
             if (row == null) continue;
      %>
      <tr>
        <td><%= esc(safe(row.date)) %></td>
        <td><%= row.leadsCreated %></td>
        <td><%= row.leadsConverted %></td>
        <td><%= row.paymentsCollectedCents %></td>
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
