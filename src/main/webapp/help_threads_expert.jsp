<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" isELIgnored="true" %>
<%@ include file="security.jspf" %>
<% if (!require_login()) return; %>
<jsp:include page="header.jsp" />

<section class="card">
  <h1 style="margin:0;">Threads Guide (Expert)</h1>
  <p class="meta" style="margin-top:8px;">
    Advanced operational guidance for automation, API use, assignment policy, and BPM orchestration.
  </p>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Automation Patterns</h2>
  <ul style="margin:0; padding-left:18px; line-height:1.5;">
    <li>Use API operations under <code>omnichannel.*</code> for low-latency ingestion and workflow updates.</li>
    <li>Use <code>bpm.actions.catalog</code> to dynamically build process editors or validation rules.</li>
    <li>Trigger BPM from thread lifecycle events (<code>omnichannel.thread.created</code>, <code>...updated</code>, <code>...attachment.added</code>).</li>
    <li>Use <code>update_thread</code> and <code>add_thread_note</code> BPM step actions for policy enforcement.</li>
  </ul>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Report Integrity Model</h2>
  <ul style="margin:0; padding-left:18px; line-height:1.5;">
    <li>Thread updates regenerate a matter-linked PDF report with document/part/version history.</li>
    <li>Public attachments are embedded as PDF attachments and image pages when image media is present.</li>
    <li>Internal-note-linked media is excluded from embedded external report content.</li>
    <li>The manifest page should be used as the canonical embedded-content audit surface.</li>
  </ul>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Assignment Policy Recommendations</h2>
  <div class="table-wrap">
    <table class="table">
      <thead>
        <tr><th>Policy Goal</th><th>Recommended Configuration</th></tr>
      </thead>
      <tbody>
        <tr>
          <td>Balanced intake load</td>
          <td>Set <code>assignment_mode=round_robin</code> and provide a stable queue candidate list.</td>
        </tr>
        <tr>
          <td>Attorney + support co-ownership</td>
          <td>Use multi-user assignment values and require assignment reason on re-assignment.</td>
        </tr>
        <tr>
          <td>SLA tracking</td>
          <td>Enforce <code>reminder_at</code>/<code>due_at</code> on create/update via BPM rules.</td>
        </tr>
      </tbody>
    </table>
  </div>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Operational Safety Checklist</h2>
  <ul style="margin:0; padding-left:18px; line-height:1.5;">
    <li>Never expose raw API secrets in scripts, logs, or exported payloads.</li>
    <li>Use API capability discovery before deploying automation changes.</li>
    <li>Run targeted tests after BPM/action changes and API expansion.</li>
    <li>Validate report generation against threads with attachments and internal notes.</li>
  </ul>
</section>

<section class="card" style="margin-top:12px;">
  <div style="display:flex; gap:8px; flex-wrap:wrap;">
    <a class="btn" href="<%= request.getContextPath() %>/business_processes.jsp">Open Business Processes</a>
    <a class="btn btn-ghost" href="<%= request.getContextPath() %>/help_threads_novice.jsp">Novice Guide</a>
    <a class="btn btn-ghost" href="<%= request.getContextPath() %>/help_center.jsp">Back To Help Center</a>
  </div>
</section>

<jsp:include page="footer.jsp" />
