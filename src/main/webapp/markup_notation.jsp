<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" isELIgnored="true" %>
<%@ include file="security.jspf" %>
<% if (!require_login()) return; %>
<jsp:include page="header.jsp" />

<section class="card">
  <h1 style="margin:0;">Legal Form Markup Notation</h1>
  <p class="meta" style="margin-top:8px;">Reference for drafting template tokens, switch fallbacks, and advanced directives.</p>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Core Token Syntax</h2>
  <ul style="margin:0; padding-left:18px; line-height:1.5;">
    <li><code>{{case.county}}</code> - strict token format (preferred).</li>
    <li><code>[name of defendant]</code> - loose label format; normalized to <code>name_of_defendant</code>.</li>
    <li><code>{A/B/C}</code> or <code>[A/B/C]</code> - switch fallback; first non-empty option wins.</li>
  </ul>
  <div class="meta" style="margin-top:10px;">
    The assembler tolerates common drafting mistakes including smart-quote delimiters, full-width brackets,
    and token delimiters split across formatting runs (including table cells).
  </div>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Namespaces And Fallbacks</h2>
  <ul style="margin:0; padding-left:18px; line-height:1.5;">
    <li>Use explicit namespaces for clarity: <code>{{tenant.*}}</code>, <code>{{case.*}}</code>, <code>{{kv.*}}</code>.</li>
    <li>Case values override same-key tenant values when using <code>{{kv.key}}</code>.</li>
    <li>For optional values, prefer a switch fallback: <code>[case.cause_docket_number/No Docket Number Yet]</code>.</li>
    <li>Prefer one token per legal fact for cleaner auditing and safer redaction.</li>
  </ul>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Advanced Directives (Opt-In)</h2>
  <div class="meta" style="margin-bottom:10px;">
    Advanced directives run only when tenant flag <code>advanced_assembly_enabled</code> is enabled.
    Otherwise they remain plain text and regular token replacement still runs.
  </div>
  <ul style="margin:0; padding-left:18px; line-height:1.5;">
    <li><code>{{#if kv.child_support}}...{{/if}}</code> - includes content only when the value is truthy.</li>
    <li><code>{{#each case.parties}}Party: {{this}}&#10;{{/each}}</code> - repeats a block for each item.</li>
    <li><code>{{format.date case.filing_date "MM/dd/yyyy"}}</code> - formats a date value inline.</li>
  </ul>
  <div class="meta" style="margin-top:10px;">
    Truthy values: anything except blank, <code>false</code>, <code>0</code>, <code>no</code>, or <code>off</code>.
  </div>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">List/Loop Data Shapes</h2>
  <ul style="margin:0; padding-left:18px; line-height:1.5;">
    <li>Simple lists can be newline, pipe, or comma separated.</li>
    <li>XML lists are supported via <code>&lt;list&gt;</code> with <code>&lt;item&gt;</code> or <code>&lt;row&gt;</code> nodes.</li>
    <li>Inside loops, use <code>{{this}}</code> for the row text and <code>{{item.field_name}}</code> for XML child fields.</li>
  </ul>
  <div class="meta" style="margin-top:10px;">
    Example XML: <code>&lt;list&gt;&lt;row&gt;&lt;name&gt;John Doe&lt;/name&gt;&lt;address&gt;100 Main St&lt;/address&gt;&lt;/row&gt;&lt;/list&gt;</code>
  </div>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Strict Mode And Logging</h2>
  <div class="meta" style="line-height:1.5;">
    Optional strict mode (<code>advanced_assembly_strict_mode</code>) records unresolved directives and
    unresolved tokens in activity logs while still producing output.
  </div>
</section>

<jsp:include page="footer.jsp" />
