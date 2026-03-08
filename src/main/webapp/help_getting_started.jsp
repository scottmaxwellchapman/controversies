<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" isELIgnored="true" %>
<%@ include file="security.jspf" %>
<% if (!require_login()) return; %>
<jsp:include page="header.jsp" />

<section class="card">
  <h1 style="margin:0;">Getting Started For Legal Teams</h1>
  <p class="meta" style="margin-top:8px;">
    A practical first-day setup sequence for attorneys, paralegals, and legal support staff.
  </p>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">First 30 Minutes Checklist</h2>
  <ol style="margin:0; padding-left:20px; line-height:1.6;">
    <li>Complete tenant and user sign-in.</li>
    <li>Verify role permissions in <a href="<%= request.getContextPath() %>/users_roles.jsp">Users &amp; Security</a> and layered overrides in <a href="<%= request.getContextPath() %>/permissions_management.jsp">Permission Layers</a>.</li>
    <li>Set firm-wide values in <a href="<%= request.getContextPath() %>/tenant_fields.jsp">Tenant Fields</a> (firm name, address blocks, defaults).</li>
    <li>Create one test matter in <a href="<%= request.getContextPath() %>/cases.jsp">Cases</a>.</li>
    <li>Enter matter facts in <a href="<%= request.getContextPath() %>/case_fields.jsp">Case Fields</a>.</li>
    <li>Assemble one template in <a href="<%= request.getContextPath() %>/forms.jsp">Form Assembly</a>.</li>
    <li>Open <a href="<%= request.getContextPath() %>/assembled_forms.jsp">Assembled Forms</a> to confirm output appears and downloads correctly.</li>
  </ol>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Who Should Do What</h2>
  <div class="table-wrap">
    <table class="table">
      <thead>
        <tr>
          <th>Role</th>
          <th>Recommended Responsibilities</th>
          <th>Primary Screens</th>
        </tr>
      </thead>
      <tbody>
        <tr>
          <td>Administrator</td>
          <td>Set roles, enable settings, maintain security and integrations.</td>
          <td>Users &amp; Security, Tenant Settings, Logs</td>
        </tr>
        <tr>
          <td>Paralegal / Legal Assistant</td>
          <td>Create matters, maintain factual data, and run assembly workflows.</td>
          <td>Cases, Case Fields, Documents, Form Assembly</td>
        </tr>
        <tr>
          <td>Attorney / Reviewer</td>
          <td>Review output quality, verify filing details, and approve final drafts.</td>
          <td>Assembled Forms, Documents, Activity Logs</td>
        </tr>
      </tbody>
    </table>
  </div>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Data Placement Rules (Avoid Rework)</h2>
  <ul style="margin:0; padding-left:18px; line-height:1.5;">
    <li>Store reusable firm values in tenant fields (for example, firm address and signature data).</li>
    <li>Store matter-specific facts in case fields (for example, cause number, opposing party names).</li>
    <li>Use tokenized templates so updates in fields flow automatically into assembled documents.</li>
  </ul>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">New User Pitfalls</h2>
  <ul style="margin:0; padding-left:18px; line-height:1.5;">
    <li>Mixing tenant-level and case-level data for the same fact, which can produce unexpected replacements.</li>
    <li>Editing templates without token standards, leading to manual post-assembly edits.</li>
    <li>Skipping output verification before filing deadlines.</li>
  </ul>
  <div class="alert alert-warn" style="margin-top:10px;">
    Use the same internal naming convention for fields and tokens across all templates to reduce risk.
  </div>
</section>

<section class="card" style="margin-top:12px;">
  <div style="display:flex; gap:8px; flex-wrap:wrap;">
    <a class="btn" href="<%= request.getContextPath() %>/help_case_workflow.jsp">Next: Case Workflow Guide</a>
    <a class="btn btn-ghost" href="<%= request.getContextPath() %>/help_center.jsp">Back To Help Center</a>
  </div>
</section>

<jsp:include page="footer.jsp" />
