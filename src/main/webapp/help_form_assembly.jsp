<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" isELIgnored="true" %>
<%@ include file="security.jspf" %>
<% if (!require_login()) return; %>
<jsp:include page="header.jsp" />

<section class="card">
  <h1 style="margin:0;">Form Assembly Guide</h1>
  <p class="meta" style="margin-top:8px;">
    Drafting and quality-control practices for legal professionals assembling pleadings, notices, and other matter documents.
  </p>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Before You Assemble</h2>
  <ol style="margin:0; padding-left:20px; line-height:1.6;">
    <li>Select the correct matter in <a href="<%= request.getContextPath() %>/forms.jsp">Form Assembly</a>.</li>
    <li>Verify matter facts in <a href="<%= request.getContextPath() %>/case_fields.jsp">Case Fields</a>.</li>
    <li>Confirm firm defaults in <a href="<%= request.getContextPath() %>/tenant_fields.jsp">Tenant Fields</a>.</li>
    <li>Check that the intended template version is selected from the template context list.</li>
  </ol>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Token Drafting Standards</h2>
  <ul style="margin:0; padding-left:18px; line-height:1.5;">
    <li>Use explicit token scopes: <code>{{case.*}}</code>, <code>{{tenant.*}}</code>, and <code>{{kv.*}}</code>.</li>
    <li>Prefer one legal fact per token to improve traceability and reduce editing risk.</li>
    <li>Use fallback notation when a value may be blank, for example: <code>[case.county/County Pending]</code>.</li>
    <li>For advanced references, use <a href="<%= request.getContextPath() %>/token_guide.jsp">Token Guide</a> and <a href="<%= request.getContextPath() %>/markup_notation.jsp">Markup Notation</a>.</li>
  </ul>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Quality Control Checklist</h2>
  <div class="table-wrap">
    <table class="table">
      <thead>
        <tr>
          <th>Checkpoint</th>
          <th>Why It Matters</th>
        </tr>
      </thead>
      <tbody>
        <tr>
          <td>Party names and roles are correct</td>
          <td>Prevents service or filing errors.</td>
        </tr>
        <tr>
          <td>Cause/docket number and county match the matter record</td>
          <td>Avoids rejected filings and clerical corrections.</td>
        </tr>
        <tr>
          <td>Dates render in the expected format</td>
          <td>Reduces ambiguity in court-facing documents.</td>
        </tr>
        <tr>
          <td>No unresolved tokens remain in output</td>
          <td>Ensures final documents are ready for review or filing.</td>
        </tr>
        <tr>
          <td>Output saved in the matter record</td>
          <td>Supports repeatability and audit trail review.</td>
        </tr>
      </tbody>
    </table>
  </div>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">If Something Looks Wrong</h2>
  <ol style="margin:0; padding-left:20px; line-height:1.6;">
    <li>Identify the incorrect value in the assembled output.</li>
    <li>Locate the source field (tenant-level or case-level).</li>
    <li>Correct the source data, then run assembly again.</li>
    <li>Check <a href="<%= request.getContextPath() %>/log_viewer.jsp">Activity Logs</a> if behavior is unexpected.</li>
  </ol>
  <div class="alert alert-ok" style="margin-top:10px;">
    Best practice: correct source data first, then regenerate; avoid manual final-file fixes whenever possible.
  </div>
</section>

<section class="card" style="margin-top:12px;">
  <div style="display:flex; gap:8px; flex-wrap:wrap;">
    <a class="btn btn-ghost" href="<%= request.getContextPath() %>/help_case_workflow.jsp">Back: Case Workflow Guide</a>
    <a class="btn btn-ghost" href="<%= request.getContextPath() %>/help_center.jsp">Help Center</a>
  </div>
</section>

<jsp:include page="footer.jsp" />
