<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ page import="net.familylawandprobate.controversies.documents" %>
<%@ page import="net.familylawandprobate.controversies.document_parts" %>
<%@ page import="net.familylawandprobate.controversies.part_versions" %>
<%@ page import="net.familylawandprobate.controversies.document_editor_service" %>
<%@ include file="security.jspf" %>
<% if (!require_login()) return; %>
<%!
private static final String S_TENANT_UUID = "tenant.uuid";
private static String safe(String s){ return s == null ? "" : s; }
private static String esc(String s){ return safe(s).replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;"); }
private static String enc(String s){ return java.net.URLEncoder.encode(safe(s), java.nio.charset.StandardCharsets.UTF_8); }
%>
<%
String ctx = safe(request.getContextPath());
String tenantUuid = safe((String)session.getAttribute(S_TENANT_UUID)).trim();
if (tenantUuid.isBlank()) { response.sendRedirect(ctx + "/tenant_login.jsp"); return; }

String caseUuid = safe(request.getParameter("case_uuid")).trim();
String docUuid = safe(request.getParameter("doc_uuid")).trim();
String partUuid = safe(request.getParameter("part_uuid")).trim();
String versionUuid = safe(request.getParameter("version_uuid")).trim();

documents docs = documents.defaultStore();
document_parts parts = document_parts.defaultStore();
part_versions versions = part_versions.defaultStore();
document_editor_service editorService = document_editor_service.defaultService();

documents.DocumentRec doc = null;
document_parts.PartRec part = null;
part_versions.VersionRec sourceVersion = null;
document_editor_service.LaunchResult launch = null;
String error = "";

if (caseUuid.isBlank() || docUuid.isBlank() || partUuid.isBlank() || versionUuid.isBlank()) {
  error = "Missing required editor parameters.";
} else {
  try {
    doc = docs.get(tenantUuid, caseUuid, docUuid);
    part = parts.get(tenantUuid, caseUuid, docUuid, partUuid);
    sourceVersion = versions.get(tenantUuid, caseUuid, docUuid, partUuid, versionUuid);
    if (doc == null) {
      error = "Document not found.";
    } else if (part == null) {
      error = "Document part not found.";
    } else if (sourceVersion == null) {
      error = "Version not found.";
    } else if (documents.isReadOnly(doc)) {
      error = documents.readOnlyMessage(doc);
    } else {
      String actor = safe((String)session.getAttribute("user.email")).trim();
      if (actor.isBlank()) actor = safe((String)session.getAttribute("user.uuid")).trim();
      String actorDisplay = safe((String)session.getAttribute("user.display_name")).trim();
      if (actorDisplay.isBlank()) actorDisplay = actor;
      launch = editorService.prepareOnlyOfficeLaunch(
        tenantUuid,
        caseUuid,
        docUuid,
        partUuid,
        versionUuid,
        actor,
        actorDisplay,
        document_editor_service.inferRequestBaseUrl(request)
      );
      if (launch == null || !launch.ok) {
        error = safe(launch == null ? "Unable to prepare editor launch." : launch.message);
      }
    }
  } catch (Exception ex) {
    error = safe(ex.getMessage());
  }
}
%>
<jsp:include page="header.jsp" />
<style>
  .version-editor-shell { margin-top:12px; }
  .version-editor-frame {
    height:78vh;
    min-height:560px;
    border:1px solid var(--border);
    border-radius:12px;
    background:var(--surface-2);
    overflow:hidden;
  }
  .version-editor-stage { width:100%; height:100%; }
  @media (max-width: 900px) {
    .version-editor-frame { min-height:460px; height:74vh; }
  }
</style>

<section class="card">
  <div style="display:flex; gap:10px; justify-content:space-between; align-items:flex-start; flex-wrap:wrap;">
    <div>
      <h1 style="margin:0;">WYSIWYG Version Editor</h1>
      <div class="meta">Part: <strong><%= esc(part == null ? "" : part.label) %></strong></div>
      <div class="meta">Version: <strong><%= esc(sourceVersion == null ? "" : sourceVersion.versionLabel) %></strong></div>
    </div>
    <a class="btn btn-ghost" href="<%= ctx %>/versions.jsp?case_uuid=<%= enc(caseUuid) %>&doc_uuid=<%= enc(docUuid) %>&part_uuid=<%= enc(partUuid) %>">Back to Versions</a>
  </div>
</section>

<section class="card version-editor-shell">
  <% if (!safe(error).isBlank()) { %>
    <div class="alert alert-error"><%= esc(error) %></div>
    <div class="meta" style="margin-top:8px;">
      Configure ONLYOFFICE with <code>CONTROVERSIES_ONLYOFFICE_DOCSERVER_URL</code> and ensure your app base URL is reachable by the document server.
    </div>
  <% } else { %>
    <div class="meta" style="margin-bottom:8px;">
      Saving in the editor creates a new current part version.
    </div>
    <script src="<%= esc(safe(launch == null ? "" : launch.scriptUrl)) %>"></script>
    <script type="application/json" id="onlyofficeEditorConfigJson"><%= esc(safe(launch == null ? "" : launch.configJson)) %></script>
    <div class="version-editor-frame">
      <div id="onlyofficeEditorHost" class="version-editor-stage"></div>
    </div>
    <script>
      (function () {
        var host = document.getElementById("onlyofficeEditorHost");
        var cfgEl = document.getElementById("onlyofficeEditorConfigJson");
        var cfg = {};
        try {
          cfg = JSON.parse(cfgEl ? (cfgEl.textContent || "{}") : "{}");
        } catch (ignore) {
          host.innerHTML = "<div class='alert alert-error' style='margin:12px;'>Invalid editor config payload.</div>";
          return;
        }
        if (!window.DocsAPI || typeof window.DocsAPI.DocEditor !== "function") {
          host.innerHTML = "<div class='alert alert-error' style='margin:12px;'>ONLYOFFICE client API failed to load.</div>";
          return;
        }
        new window.DocsAPI.DocEditor("onlyofficeEditorHost", cfg);
      })();
    </script>
  <% } %>
</section>

<jsp:include page="footer.jsp" />
