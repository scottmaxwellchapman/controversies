<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" isELIgnored="true" %>

<%@ include file="security.jspf" %>
<%
  if (!require_login()) return;
  if (!require_permission("tenant_admin")) return;

  String ctx = request.getContextPath();
  if (ctx == null) ctx = "";
%>

<jsp:include page="header.jsp" />

<section class="card">
  <h1 style="margin:0;">Attribute Editor</h1>
  <div class="meta" style="margin-top:6px;">
    Configure tenant-scoped custom attributes for matters (cases) and documents.
  </div>
</section>

<section class="card" style="margin-top:12px;">
  <div class="grid" style="display:grid; grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); gap:12px;">
    <article class="card" style="padding:14px; margin:0;">
      <h2 style="margin-top:0;">Matter Attributes</h2>
      <p class="muted">
        Manage custom fields captured on the Cases page. Includes default attributes like
        <code>cause_docket_number</code> and <code>county</code>.
      </p>
      <a class="btn" href="<%= ctx %>/case_attributes.jsp">Edit Matter Attributes</a>
    </article>

    <article class="card" style="padding:14px; margin:0;">
      <h2 style="margin-top:0;">Document Attributes</h2>
      <p class="muted">
        Manage custom fields captured on the Documents page, including dropdown options and required settings.
      </p>
      <a class="btn" href="<%= ctx %>/document_attributes.jsp">Edit Document Attributes</a>
    </article>

    <article class="card" style="padding:14px; margin:0;">
      <h2 style="margin-top:0;">Custom Object Attributes</h2>
      <p class="muted">
        Create tenant-specific objects, publish them to the menu, and configure their attribute schemas.
      </p>
      <a class="btn" href="<%= ctx %>/custom_objects.jsp">Manage Custom Objects</a>
    </article>
  </div>
</section>

<jsp:include page="footer.jsp" />
