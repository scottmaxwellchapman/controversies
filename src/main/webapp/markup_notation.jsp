<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" isELIgnored="true" %>
<%@ include file="security.jspf" %>
<% if (!require_login()) return; %>
<jsp:include page="header.jsp" />

<section class="card">
  <h1 style="margin:0;">Legal Form Markup Notation</h1>
  <p class="meta" style="margin-top:8px;">Simple notation for non-technical staff drafting legal templates.</p>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Core tokens</h2>
  <ul>
    <li><code>{{case.county}}</code> — strict token format (preferred).</li>
    <li><code>[name of defendant]</code> — loose label format; normalized to <code>name_of_defendant</code>.</li>
    <li><code>{A/B/C}</code> or <code>[A/B/C]</code> — first non-empty option wins.</li>
  </ul>
  <p class="meta">The assembler also tolerates common drafting mistakes: smart quotes used as delimiters, full-width brackets, and token delimiters split by formatting runs (including table cells).</p>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Legal drafting helpers</h2>
  <ul>
    <li>Use explicit namespaces for clarity: <code>{{tenant.*}}</code>, <code>{{case.*}}</code>, <code>{{kv.*}}</code>.</li>
    <li>When a value can be blank, use switch notation with fallback: <code>[case.cause_docket_number/No Docket Number Yet]</code>.</li>
    <li>Prefer one token per legal fact to support audit logging.</li>
  </ul>
</section>

<jsp:include page="footer.jsp" />
