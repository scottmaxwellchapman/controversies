<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" isELIgnored="true" %>
<%@ include file="security.jspf" %>
<% if (!require_login()) return; %>
<jsp:include page="header.jsp" />

<section class="card">
  <h1 style="margin:0;">Tasks Guide (Expert)</h1>
  <p class="meta" style="margin-top:8px;">
    Advanced guidance for task API usage, assignment balancing, BPM automation, and reporting.
  </p>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Operational Model</h2>
  <ul style="margin:0; padding-left:18px; line-height:1.5;">
    <li>Tasks support multi-user assignment, assignment history, and optional round-robin selection.</li>
    <li>Each task requires due date/time and estimate minutes for scheduling clarity.</li>
    <li>Tasks can be linked to matter facts (claim/element/fact), document references (doc/part/version/page), and threads.</li>
    <li>Task updates can regenerate a matter-linked PDF report stored in document version history.</li>
  </ul>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">API Operations</h2>
  <div class="table-wrap">
    <table class="table">
      <thead>
        <tr><th>Operation Group</th><th>Examples</th></tr>
      </thead>
      <tbody>
        <tr>
          <td>Task records</td>
          <td><code>tasks.list</code>, <code>tasks.get</code>, <code>tasks.create</code>, <code>tasks.update</code>, <code>tasks.set_archived</code></td>
        </tr>
        <tr>
          <td>Task notes/assignments</td>
          <td><code>tasks.notes.list</code>, <code>tasks.notes.add</code>, <code>tasks.assignments.list</code></td>
        </tr>
        <tr>
          <td>Task custom data</td>
          <td><code>task.attributes.list/save</code>, <code>task.fields.get/update</code></td>
        </tr>
        <tr>
          <td>Balancing/reporting</td>
          <td><code>tasks.round_robin.next_assignee</code>, <code>tasks.report.refresh</code></td>
        </tr>
      </tbody>
    </table>
  </div>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">BPM Action Extensions</h2>
  <ul style="margin:0; padding-left:18px; line-height:1.5;">
    <li><code>set_task_field</code>: reversible task custom-field mutation with undo/redo support.</li>
    <li><code>update_task</code>: update task metadata, assignments, scheduling, and associations.</li>
    <li><code>add_task_note</code>: append internal task notes from automation flows.</li>
    <li><code>update_fact</code> and <code>refresh_facts_report</code>: automate fact maintenance/report refresh alongside task workflows.</li>
  </ul>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Quality Control Checklist</h2>
  <ul style="margin:0; padding-left:18px; line-height:1.5;">
    <li>Reject creation/update payloads that omit due date/time or estimate minutes.</li>
    <li>Keep association references valid (fact and document references must resolve).</li>
    <li>Use assignment reasons for reassignments to improve downstream audit review.</li>
    <li>Review task PDF version history for critical matter milestones.</li>
  </ul>
</section>

<section class="card" style="margin-top:12px;">
  <div style="display:flex; gap:8px; flex-wrap:wrap;">
    <a class="btn" href="<%= request.getContextPath() %>/tasks.jsp">Open Tasks</a>
    <a class="btn btn-ghost" href="<%= request.getContextPath() %>/help_tasks_novice.jsp">Novice Guide</a>
    <a class="btn btn-ghost" href="<%= request.getContextPath() %>/help_center.jsp">Back To Help Center</a>
  </div>
</section>

<jsp:include page="footer.jsp" />
