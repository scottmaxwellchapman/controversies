<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ page import="net.familylawandprobate.controversies.users_roles" %>

<%
  boolean tenantLoggedInIndex =
    session.getAttribute("tenant.uuid") != null &&
    !String.valueOf(session.getAttribute("tenant.uuid")).trim().isBlank();

  boolean userLoggedInIndex =
    session.getAttribute(users_roles.S_USER_UUID) != null &&
    !String.valueOf(session.getAttribute(users_roles.S_USER_UUID)).trim().isBlank();
%>

<%@ include file="header.jsp" %>

<section class="card">
  <div class="section-head">
    <div>
      <h1 style="margin:0;">Controversies</h1>
      <p class="meta" style="margin-top:6px;">Focused legal-document workflow: users, cases, tenant fields, and DOCX-first assembly.</p>
    </div>
  </div>

  <% if (!tenantLoggedInIndex) { %>
    <div class="alert alert-warn" style="margin-top:12px;">
      Sign in is required before accessing users, cases, tenant fields, and forms.
    </div>
  <% } else if (!userLoggedInIndex) { %>
    <div class="alert alert-warn" style="margin-top:12px;">
      Session is incomplete. Sign in again to continue.
    </div>
  <% } else { %>
    <div class="alert alert-ok" style="margin-top:12px;">
      Authenticated. Use the workflow cards below.
    </div>
  <% } %>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Workflow</h2>
  <div class="grid" style="display:grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap:12px;">
    <article class="card" style="padding:14px; margin:0;">
      <h3 style="margin-top:0;">1. Users & Security</h3>
      <p class="muted">Manage users, passwords, roles, and permission keys that control access.</p>
      <a class="btn" href="<%= request.getContextPath() %>/users_roles.jsp">Open Users &amp; Security</a>
    </article>

    <article class="card" style="padding:14px; margin:0;">
      <h3 style="margin-top:0;">2. Cases</h3>
      <p class="muted">Create and maintain case records plus key/value replacement fields for each case.</p>
      <a class="btn" href="<%= request.getContextPath() %>/cases.jsp">Open Cases</a>
    </article>

    <article class="card" style="padding:14px; margin:0;">
      <h3 style="margin-top:0;">3. Tenant Fields</h3>
      <p class="muted">Maintain global tenant replacement keys used across all cases and templates.</p>
      <a class="btn" href="<%= request.getContextPath() %>/tenant_fields.jsp">Open Tenant Fields</a>
    </article>

    <article class="card" style="padding:14px; margin:0;">
      <h3 style="margin-top:0;">4. Form Assembly</h3>
      <p class="muted">Assemble DOCX/DOC/RTF templates with style preservation and replace-once/all preview tools.</p>
      <a class="btn" href="<%= request.getContextPath() %>/forms.jsp">Open Form Assembly</a>
    </article>
  </div>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Template Tokens</h2>
  <div class="meta">Use double braces in templates. Examples:</div>
  <div class="table-wrap" style="margin-top:8px;">
    <table class="table">
      <thead>
        <tr>
          <th>Token</th>
          <th>Description</th>
        </tr>
      </thead>
      <tbody>
        <tr>
          <td><code>{{case.label}}</code></td>
          <td>Case label/title.</td>
        </tr>
        <tr>
          <td><code>{{case.cause_docket_number}}</code></td>
          <td>Cause or docket number.</td>
        </tr>
        <tr>
          <td><code>{{case.county}}</code></td>
          <td>Case county value.</td>
        </tr>
        <tr>
          <td><code>{{kv.client_name}}</code></td>
          <td>Shared key: tenant value by default, overridden by case value when present.</td>
        </tr>
        <tr>
          <td><code>{{tenant.firm_name}}</code></td>
          <td>Always the tenant-global key <code>firm_name</code>.</td>
        </tr>
        <tr>
          <td><code>{{case.client_name}}</code></td>
          <td>Always the case-level key <code>client_name</code>.</td>
        </tr>
      </tbody>
    </table>
  </div>
</section>

<%@ include file="footer.jsp" %>
