<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" isELIgnored="true" %>
<%@ include file="security.jspf" %>
<% if (!require_login()) return; %>
<jsp:include page="header.jsp" />

<section class="card">
  <h1 style="margin:0;">Help Center</h1>
  <p class="meta" style="margin-top:8px;">
    Practical guidance for attorneys, paralegals, and legal staff using Controversies for daily case and document work.
  </p>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Start Here</h2>
  <div class="grid" style="display:grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap:12px;">
    <article class="card" style="padding:14px; margin:0;">
      <h3 style="margin-top:0;">Getting Started</h3>
      <p class="muted">First-day setup for legal teams: sign-in flow, role setup, first case, and first assembled form.</p>
      <a class="btn" href="<%= request.getContextPath() %>/help_getting_started.jsp">Open Guide</a>
    </article>

    <article class="card" style="padding:14px; margin:0;">
      <h3 style="margin-top:0;">Case Workflow</h3>
      <p class="muted">Intake-to-output workflow with the exact pages to use at each stage of a matter.</p>
      <a class="btn" href="<%= request.getContextPath() %>/help_case_workflow.jsp">Open Guide</a>
    </article>

    <article class="card" style="padding:14px; margin:0;">
      <h3 style="margin-top:0;">Form Assembly</h3>
      <p class="muted">Template drafting standards, token usage, and pre-filing quality checks.</p>
      <a class="btn" href="<%= request.getContextPath() %>/help_form_assembly.jsp">Open Guide</a>
    </article>

    <article class="card" style="padding:14px; margin:0;">
      <h3 style="margin-top:0;">Token + Markup Reference</h3>
      <p class="muted">Technical references for token sources, fallback behavior, and markup notation details.</p>
      <div style="display:flex; gap:8px; flex-wrap:wrap;">
        <a class="btn btn-ghost" href="<%= request.getContextPath() %>/token_guide.jsp">Token Guide</a>
        <a class="btn btn-ghost" href="<%= request.getContextPath() %>/markup_notation.jsp">Markup Notation</a>
      </div>
    </article>

    <article class="card" style="padding:14px; margin:0;">
      <h3 style="margin-top:0;">Threads (Novice)</h3>
      <p class="muted">Step-by-step thread handling for daily legal team communication workflows.</p>
      <a class="btn" href="<%= request.getContextPath() %>/help_threads_novice.jsp">Open Guide</a>
    </article>

    <article class="card" style="padding:14px; margin:0;">
      <h3 style="margin-top:0;">Threads (Expert)</h3>
      <p class="muted">Advanced API/BPM and reporting practices for omnichannel thread operations.</p>
      <a class="btn" href="<%= request.getContextPath() %>/help_threads_expert.jsp">Open Guide</a>
    </article>
  </div>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Common Legal Team Tasks</h2>
  <div class="table-wrap">
    <table class="table">
      <thead>
        <tr>
          <th>Task</th>
          <th>Primary Screen</th>
          <th>When To Use It</th>
        </tr>
      </thead>
      <tbody>
        <tr>
          <td>Open a new matter</td>
          <td><a href="<%= request.getContextPath() %>/cases.jsp">Cases</a></td>
          <td>At intake, when a client matter is accepted.</td>
        </tr>
        <tr>
          <td>Capture matter facts</td>
          <td><a href="<%= request.getContextPath() %>/case_fields.jsp">Case Fields</a></td>
          <td>Before drafting pleadings or notices.</td>
        </tr>
        <tr>
          <td>Maintain reusable firm values</td>
          <td><a href="<%= request.getContextPath() %>/tenant_fields.jsp">Tenant Fields</a></td>
          <td>For values reused across many matters (firm, venue defaults, signature blocks).</td>
        </tr>
        <tr>
          <td>Assemble a filing draft</td>
          <td><a href="<%= request.getContextPath() %>/forms.jsp">Form Assembly</a></td>
          <td>When generating a matter-specific document from a template.</td>
        </tr>
        <tr>
          <td>Review generated outputs</td>
          <td><a href="<%= request.getContextPath() %>/assembled_forms.jsp">Assembled Forms</a></td>
          <td>To download, verify, and manage completed assembled files.</td>
        </tr>
        <tr>
          <td>Confirm audit history</td>
          <td><a href="<%= request.getContextPath() %>/log_viewer.jsp">Activity Logs</a></td>
          <td>For internal review, troubleshooting, or compliance checks.</td>
        </tr>
      </tbody>
    </table>
  </div>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Quick Tips For New Users</h2>
  <ul style="margin:0; padding-left:18px; line-height:1.5;">
    <li>Use consistent naming for cases and templates to make retrieval faster under deadline pressure.</li>
    <li>Prefer explicit tokens such as <code>{{case.county}}</code> over free-text placeholders.</li>
    <li>Save shared data in tenant fields and matter-specific data in case fields.</li>
    <li>Before filing, perform one final assembled output review in the destination format.</li>
  </ul>
</section>

<jsp:include page="footer.jsp" />
