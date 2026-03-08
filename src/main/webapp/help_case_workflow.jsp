<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" isELIgnored="true" %>
<%@ include file="security.jspf" %>
<% if (!require_login()) return; %>
<jsp:include page="header.jsp" />

<section class="card">
  <h1 style="margin:0;">Case Workflow Guide</h1>
  <p class="meta" style="margin-top:8px;">
    Matter lifecycle steps for legal teams, from intake through finalized document output.
  </p>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Intake To Output Sequence</h2>
  <ol style="margin:0; padding-left:20px; line-height:1.6;">
    <li>
      <strong>Access and permissions:</strong> confirm your team can access required screens in
      <a href="<%= request.getContextPath() %>/users_roles.jsp">Users &amp; Security</a> and
      <a href="<%= request.getContextPath() %>/permissions_management.jsp">Permission Layers</a>.
    </li>
    <li>
      <strong>Create the matter:</strong> open <a href="<%= request.getContextPath() %>/cases.jsp">Cases</a>
      and create a clearly labeled case record.
    </li>
    <li>
      <strong>Capture facts:</strong> maintain matter-specific facts in
      <a href="<%= request.getContextPath() %>/case_fields.jsp">Case Fields</a>.
    </li>
    <li>
      <strong>Prepare supporting documents:</strong> manage matter documents in
      <a href="<%= request.getContextPath() %>/documents.jsp">Case Documents</a>.
    </li>
    <li>
      <strong>Assemble forms:</strong> use <a href="<%= request.getContextPath() %>/forms.jsp">Form Assembly</a>
      with the selected case and template.
    </li>
    <li>
      <strong>Validate outputs:</strong> review generated files in
      <a href="<%= request.getContextPath() %>/assembled_forms.jsp">Assembled Forms</a>.
    </li>
    <li>
      <strong>Confirm audit activity:</strong> use
      <a href="<%= request.getContextPath() %>/log_viewer.jsp">Activity Logs</a> when troubleshooting or auditing work.
    </li>
  </ol>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Recommended Intake Data Standard</h2>
  <div class="table-wrap">
    <table class="table">
      <thead>
        <tr>
          <th>Data Category</th>
          <th>Examples</th>
          <th>Where To Store</th>
        </tr>
      </thead>
      <tbody>
        <tr>
          <td>Firm-wide constants</td>
          <td>Firm name, office address, default signature block</td>
          <td><a href="<%= request.getContextPath() %>/tenant_fields.jsp">Tenant Fields</a></td>
        </tr>
        <tr>
          <td>Matter identity</td>
          <td>Matter label, cause or docket number, county</td>
          <td><a href="<%= request.getContextPath() %>/cases.jsp">Cases</a> and <a href="<%= request.getContextPath() %>/case_fields.jsp">Case Fields</a></td>
        </tr>
        <tr>
          <td>Dynamic drafting facts</td>
          <td>Party names, service details, hearing dates</td>
          <td><a href="<%= request.getContextPath() %>/case_fields.jsp">Case Fields</a> and case lists/grids when repeating data is needed</td>
        </tr>
      </tbody>
    </table>
  </div>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Deadline-Driven Review Workflow</h2>
  <ul style="margin:0; padding-left:18px; line-height:1.5;">
    <li>Run assembly with the final selected matter immediately before filing.</li>
    <li>Confirm date, county, cause number, and party fields in the assembled output.</li>
    <li>If a value is wrong, fix the source field first, then re-assemble.</li>
    <li>Avoid manual edits in final output when tokenized source data can resolve the issue.</li>
  </ul>
</section>

<section class="card" style="margin-top:12px;">
  <div style="display:flex; gap:8px; flex-wrap:wrap;">
    <a class="btn" href="<%= request.getContextPath() %>/help_form_assembly.jsp">Next: Form Assembly Guide</a>
    <a class="btn btn-ghost" href="<%= request.getContextPath() %>/help_center.jsp">Back To Help Center</a>
  </div>
</section>

<jsp:include page="footer.jsp" />
