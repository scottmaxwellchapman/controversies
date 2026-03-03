<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" isELIgnored="true" %>
<%@ include file="security.jspf" %>
<% if (!require_login()) return; %>
<jsp:include page="header.jsp" />

<section class="card">
  <h1 style="margin:0;">Threads Guide (Novice)</h1>
  <p class="meta" style="margin-top:8px;">
    Practical first-use guide for omnichannel Threads (SMS/MMS + email channels).
  </p>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Daily Thread Workflow</h2>
  <ol style="margin:0; padding-left:20px; line-height:1.6;">
    <li>Open <a href="<%= request.getContextPath() %>/omnichannel.jsp">Omnichannel Threads</a>.</li>
    <li>Create a new thread with the correct channel and linked matter.</li>
    <li>Set status, priority, reminder, due date, and assignment.</li>
    <li>Add external messages to preserve customer communication history.</li>
    <li>Add internal notes for staff-only strategy and follow-up items.</li>
    <li>Upload media and files (MMS/email attachments) as evidence.</li>
    <li>Regenerate the matter PDF report after key updates.</li>
    <li>Use the manifest page to verify embedded multimedia and inclusion status.</li>
  </ol>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">What Goes Where</h2>
  <div class="table-wrap">
    <table class="table">
      <thead>
        <tr><th>Content Type</th><th>Where To Enter It</th><th>Visibility</th></tr>
      </thead>
      <tbody>
        <tr>
          <td>Customer-facing message</td>
          <td>Add External Message</td>
          <td>Included in thread history and report content.</td>
        </tr>
        <tr>
          <td>Internal legal/team note</td>
          <td>Add Internal Note</td>
          <td>Visible to users; excluded from external-facing report content.</td>
        </tr>
        <tr>
          <td>MMS or attachment file</td>
          <td>Add Attachment / Media</td>
          <td>Embedded in report unless linked to an internal-only note.</td>
        </tr>
      </tbody>
    </table>
  </div>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Assignment Basics</h2>
  <ul style="margin:0; padding-left:18px; line-height:1.5;">
    <li>Use manual mode for direct staffing decisions.</li>
    <li>Use round-robin mode to balance intake load across users.</li>
    <li>Use multi-user assignment to coordinate attorney + support staff coverage.</li>
    <li>Record assignment reasons so the timeline explains why responsibility changed.</li>
  </ul>
</section>

<section class="card" style="margin-top:12px;">
  <div style="display:flex; gap:8px; flex-wrap:wrap;">
    <a class="btn" href="<%= request.getContextPath() %>/omnichannel.jsp">Open Threads</a>
    <a class="btn btn-ghost" href="<%= request.getContextPath() %>/help_threads_expert.jsp">Expert Guide</a>
    <a class="btn btn-ghost" href="<%= request.getContextPath() %>/help_center.jsp">Back To Help Center</a>
  </div>
</section>

<jsp:include page="footer.jsp" />
