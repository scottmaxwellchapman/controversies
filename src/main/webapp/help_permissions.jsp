<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" isELIgnored="true" %>
<%@ include file="security.jspf" %>
<% if (!require_login()) return; %>
<jsp:include page="header.jsp" />

<section class="card">
  <h1 style="margin:0;">Permissions Guide</h1>
  <p class="meta" style="margin-top:8px;">
    Reference for Controversies permission layers, group memberships, profiles, and custom object permission keys.
  </p>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Permission Resolution Order</h2>
  <div class="table-wrap">
    <table class="table">
      <thead>
        <tr>
          <th>Priority</th>
          <th>Layer</th>
          <th>Behavior</th>
        </tr>
      </thead>
      <tbody>
        <tr>
          <td>1 (base)</td>
          <td>Tenant-level permissions</td>
          <td>Default permission values applied tenant-wide.</td>
        </tr>
        <tr>
          <td>2</td>
          <td>Role permissions</td>
          <td>Role map from Users &amp; Security overlays tenant defaults.</td>
        </tr>
        <tr>
          <td>3</td>
          <td>Group permissions</td>
          <td>Enabled groups that include the user override tenant and role values.</td>
        </tr>
        <tr>
          <td>4 (highest)</td>
          <td>User-level permissions</td>
          <td>User overrides take final precedence over all lower layers.</td>
        </tr>
        <tr>
          <td>Special</td>
          <td><code>tenant_admin=true</code></td>
          <td>Unlimited access to all features regardless of other values.</td>
        </tr>
      </tbody>
    </table>
  </div>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Managing Permission Layers</h2>
  <ol style="margin:0; padding-left:20px; line-height:1.6;">
    <li>Open <a href="<%= request.getContextPath() %>/permissions_management.jsp">Permission Layers</a>.</li>
    <li>Set tenant-level defaults for broad access policy.</li>
    <li>Create groups and assign users to those groups.</li>
    <li>Apply permission profiles to tenant, group, or user to accelerate setup.</li>
    <li>Add or remove targeted key overrides where needed.</li>
    <li>Use user-level overrides only for exceptions, because they override all lower layers.</li>
  </ol>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Profiles and Existing Features</h2>
  <p class="meta" style="margin-top:0;">
    Built-in profiles map to existing platform features and functions, including Cases, Documents, Tasks, Forms, Threads, Wiki,
    Help pages, tenant administration pages, logs, and security administration.
  </p>
  <ul style="margin:0; padding-left:18px; line-height:1.5;">
    <li><strong>Tenant Administrator</strong>: full control using <code>tenant_admin=true</code>.</li>
    <li><strong>Legacy Standard User</strong>: mirrors historical non-admin access behavior.</li>
    <li><strong>Case Operations</strong>: case workflow focus (cases, tasks, documents, forms, facts).</li>
    <li><strong>Review-Only</strong>: read-focused access for review workflows.</li>
    <li><strong>Security Manager</strong>: users, roles, permission layers, and logs.</li>
    <li><strong>Platform Manager</strong>: settings, attributes, custom object definitions, and plugin/business process administration.</li>
    <li><strong>Custom Object Operator</strong>: published custom object record operations.</li>
  </ul>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Custom Object Permission Keys</h2>
  <p class="meta" style="margin-top:0;">
    Custom object permissions support both generic and object-specific key patterns.
  </p>
  <div class="table-wrap">
    <table class="table">
      <thead>
        <tr>
          <th>Scope</th>
          <th>Pattern</th>
          <th>Example</th>
        </tr>
      </thead>
      <tbody>
        <tr>
          <td>Generic record access</td>
          <td><code>custom_objects.records.&lt;action&gt;</code></td>
          <td><code>custom_objects.records.edit</code></td>
        </tr>
        <tr>
          <td>Per-object access</td>
          <td><code>custom_object.&lt;object_key&gt;.&lt;action&gt;</code></td>
          <td><code>custom_object.billing_entry.archive</code></td>
        </tr>
        <tr>
          <td>Definition management</td>
          <td><code>custom_objects.manage</code></td>
          <td>Access custom object and custom attribute configuration pages.</td>
        </tr>
      </tbody>
    </table>
  </div>
  <p class="meta" style="margin-top:10px;">
    Actions are: <code>access</code>, <code>create</code>, <code>edit</code>, <code>archive</code>, and <code>export</code>.
  </p>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Operational Recommendations</h2>
  <ul style="margin:0; padding-left:18px; line-height:1.5;">
    <li>Use tenant-level values for broad defaults.</li>
    <li>Use groups for team-based exceptions instead of editing users one-by-one.</li>
    <li>Reserve user overrides for short-lived exceptions or emergency access changes.</li>
    <li>Test role/profile changes using a non-admin test account before production rollout.</li>
  </ul>
</section>

<section class="card" style="margin-top:12px;">
  <div style="display:flex; gap:8px; flex-wrap:wrap;">
    <a class="btn" href="<%= request.getContextPath() %>/permissions_management.jsp">Open Permission Layers</a>
    <a class="btn btn-ghost" href="<%= request.getContextPath() %>/help_center.jsp">Back To Help Center</a>
  </div>
</section>

<jsp:include page="footer.jsp" />
