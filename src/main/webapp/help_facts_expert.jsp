<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" isELIgnored="true" %>
<%@ include file="security.jspf" %>
<% if (!require_login()) return; %>
<jsp:include page="header.jsp" />

<section class="card">
  <h1 style="margin:0;">Facts Guide (Expert)</h1>
  <p class="meta" style="margin-top:8px;">
    Advanced guidance for API usage, evidence linkage quality, and reporting controls.
  </p>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Operational Model</h2>
  <ul style="margin:0; padding-left:18px; line-height:1.5;">
    <li>Case plan hierarchy is matter-scoped and stored as Claims -> Elements -> Facts.</li>
    <li>Fact records can link to document UUID, part UUID, version UUID, and page number.</li>
    <li>Hierarchy updates auto-refresh a matter-linked, versioned landscape PDF report.</li>
    <li>Report versions are stored in standard document-part-version storage for auditability.</li>
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
          <td>Hierarchy read</td>
          <td><code>facts.tree.get</code>, <code>facts.claims.list</code>, <code>facts.elements.list</code>, <code>facts.facts.list</code></td>
        </tr>
        <tr>
          <td>Hierarchy write</td>
          <td><code>facts.claims.create/update</code>, <code>facts.elements.create/update</code>, <code>facts.facts.create/update</code></td>
        </tr>
        <tr>
          <td>Evidence linkage</td>
          <td><code>facts.facts.link_document</code> and <code>facts.facts.update</code> document/part/version/page fields</td>
        </tr>
        <tr>
          <td>Report control</td>
          <td><code>facts.report.refresh</code></td>
        </tr>
      </tbody>
    </table>
  </div>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Quality Control Checklist</h2>
  <ul style="margin:0; padding-left:18px; line-height:1.5;">
    <li>Reject facts with no traceable source linkage for filing-critical elements.</li>
    <li>Require page number entry when a source version UUID is present.</li>
    <li>Use status transitions (unverified -> corroborated -> proven) to drive review queues.</li>
    <li>Monitor report version growth and archive stale facts when matters evolve.</li>
  </ul>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Automation Recommendations</h2>
  <ul style="margin:0; padding-left:18px; line-height:1.5;">
    <li>Trigger business processes on facts lifecycle events for team notifications.</li>
    <li>Use <code>facts.tree.get</code> snapshots for review packets and external system syncs.</li>
    <li>Schedule periodic <code>facts.report.refresh</code> for high-velocity matters.</li>
  </ul>
</section>

<section class="card" style="margin-top:12px;">
  <div style="display:flex; gap:8px; flex-wrap:wrap;">
    <a class="btn" href="<%= request.getContextPath() %>/facts.jsp">Open Facts Case Plan</a>
    <a class="btn btn-ghost" href="<%= request.getContextPath() %>/help_facts_novice.jsp">Novice Guide</a>
    <a class="btn btn-ghost" href="<%= request.getContextPath() %>/help_center.jsp">Back To Help Center</a>
  </div>
</section>

<jsp:include page="footer.jsp" />
