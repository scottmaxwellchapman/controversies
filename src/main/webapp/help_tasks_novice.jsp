<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" isELIgnored="true" %>
<%@ include file="security.jspf" %>
<% if (!require_login()) return; %>
<jsp:include page="header.jsp" />

<section class="card">
  <h1 style="margin:0;">Tasks Guide (Novice)</h1>
  <p class="meta" style="margin-top:8px;">
    Step-by-step guide for creating, assigning, and tracking matter work using Tasks.
  </p>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Daily Workflow</h2>
  <ol style="margin:0; padding-left:20px; line-height:1.6;">
    <li>Open <a href="<%= request.getContextPath() %>/tasks.jsp">Tasks</a>.</li>
    <li>Create a new task with a clear title and description.</li>
    <li>Set a required due date/time and required time estimate.</li>
    <li>Assign one or more users manually, or use round-robin mode for balancing.</li>
    <li>Link the task to matter facts, documents/parts/versions/pages, and/or a thread when relevant.</li>
    <li>Add sub-tasks under the parent task for detailed execution planning.</li>
    <li>Use internal notes for strategy and team-only communication.</li>
    <li>Review assignment history and refresh the task PDF report as needed.</li>
  </ol>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">What Makes A Good Task</h2>
  <div class="table-wrap">
    <table class="table">
      <thead>
        <tr><th>Field</th><th>Recommendation</th><th>Example</th></tr>
      </thead>
      <tbody>
        <tr>
          <td>Title</td>
          <td>Use action-first wording and specific scope.</td>
          <td>Draft Motion To Compel (Rule 192)</td>
        </tr>
        <tr>
          <td>Due Date/Time</td>
          <td>Enter the legal or internal deadline precisely.</td>
          <td>2026-04-10 17:00 local</td>
        </tr>
        <tr>
          <td>Time Estimate</td>
          <td>Use realistic planning minutes for balancing.</td>
          <td>120</td>
        </tr>
        <tr>
          <td>Associations</td>
          <td>Link to facts/documents/threads so context is one click away.</td>
          <td>Fact F2.3, Exhibit B page 5, Thread intake-2026-04</td>
        </tr>
      </tbody>
    </table>
  </div>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Beginner Tips</h2>
  <ul style="margin:0; padding-left:18px; line-height:1.5;">
    <li>Use one task per deliverable, then add sub-tasks for discrete steps.</li>
    <li>Keep due times explicit to reduce missed deadlines.</li>
    <li>Use internal notes instead of overloading the main description field.</li>
    <li>Capture custom task attributes for repeatable reporting and automation.</li>
  </ul>
</section>

<section class="card" style="margin-top:12px;">
  <div style="display:flex; gap:8px; flex-wrap:wrap;">
    <a class="btn" href="<%= request.getContextPath() %>/tasks.jsp">Open Tasks</a>
    <a class="btn btn-ghost" href="<%= request.getContextPath() %>/help_tasks_expert.jsp">Expert Guide</a>
    <a class="btn btn-ghost" href="<%= request.getContextPath() %>/help_center.jsp">Back To Help Center</a>
  </div>
</section>

<jsp:include page="footer.jsp" />
