<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" isELIgnored="true" %>
<%@ include file="security.jspf" %>
<% if (!require_login()) return; %>
<jsp:include page="header.jsp" />

<section class="card">
  <h1 style="margin:0;">Facts Guide (Novice)</h1>
  <p class="meta" style="margin-top:8px;">
    Step-by-step guide for building a matter case plan using Claims, Elements, and Facts.
  </p>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Daily Workflow</h2>
  <ol style="margin:0; padding-left:20px; line-height:1.6;">
    <li>Open <a href="<%= request.getContextPath() %>/facts.jsp">Facts Case Plan</a>.</li>
    <li>Select the matter from the matter dropdown.</li>
    <li>Create one or more Claims (root legal theories).</li>
    <li>Add Elements under each Claim (what must be proven).</li>
    <li>Add Facts under each Element (evidence or assertions).</li>
    <li>Link each Fact to the source document, part, version, and page number.</li>
    <li>Review the side-tree to confirm structure and coverage.</li>
    <li>Regenerate the landscape PDF report after significant updates.</li>
  </ol>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">What To Enter At Each Level</h2>
  <div class="table-wrap">
    <table class="table">
      <thead>
        <tr><th>Level</th><th>Purpose</th><th>Example</th></tr>
      </thead>
      <tbody>
        <tr>
          <td>Claim</td>
          <td>Top-level legal claim for the matter.</td>
          <td>Breach of Contract</td>
        </tr>
        <tr>
          <td>Element</td>
          <td>Required legal element under the claim.</td>
          <td>Existence of valid contract</td>
        </tr>
        <tr>
          <td>Fact</td>
          <td>Specific factual support tied to evidence.</td>
          <td>Signed engagement letter on page 1 of Exhibit A</td>
        </tr>
      </tbody>
    </table>
  </div>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Beginner Tips</h2>
  <ul style="margin:0; padding-left:18px; line-height:1.5;">
    <li>Keep Claim and Element titles short; put details in summary/notes fields.</li>
    <li>Use Fact status and strength to prioritize what still needs proof.</li>
    <li>Always add page numbers when available for faster review.</li>
    <li>Use internal notes for user-only strategy comments.</li>
  </ul>
</section>

<section class="card" style="margin-top:12px;">
  <div style="display:flex; gap:8px; flex-wrap:wrap;">
    <a class="btn" href="<%= request.getContextPath() %>/facts.jsp">Open Facts Case Plan</a>
    <a class="btn btn-ghost" href="<%= request.getContextPath() %>/help_facts_expert.jsp">Expert Guide</a>
    <a class="btn btn-ghost" href="<%= request.getContextPath() %>/help_center.jsp">Back To Help Center</a>
  </div>
</section>

<jsp:include page="footer.jsp" />
