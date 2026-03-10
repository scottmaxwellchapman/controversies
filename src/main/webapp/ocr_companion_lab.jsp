<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ page import="java.util.ArrayList" %>
<%@ page import="java.util.List" %>
<%@ page import="net.familylawandprobate.controversies.matters" %>
<%@ page import="net.familylawandprobate.controversies.documents" %>
<%@ page import="net.familylawandprobate.controversies.document_parts" %>
<%@ page import="net.familylawandprobate.controversies.part_versions" %>
<%@ page import="net.familylawandprobate.controversies.version_ocr_companion_service" %>
<%@ include file="security.jspf" %>
<% if (!require_login()) return; %>
<% if (!require_permission("documents.access")) return; %>
<%!
  private static String safe(String s) { return s == null ? "" : s; }
  private static String esc(String s) {
    return safe(s)
      .replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")
      .replace("'", "&#39;");
  }
  private static String clip(String s, int max) {
    String v = safe(s);
    if (max <= 0 || v.length() <= max) return v;
    return v.substring(0, max) + "...";
  }
  private static String csrfForRender(jakarta.servlet.http.HttpServletRequest req) {
    Object a = req == null ? null : req.getAttribute("csrfToken");
    if (a instanceof String s && !safe(s).trim().isBlank()) return s;
    try {
      jakarta.servlet.http.HttpSession sess = req == null ? null : req.getSession(false);
      Object t = sess == null ? null : sess.getAttribute("CSRF_TOKEN");
      if (t instanceof String cs && !safe(cs).trim().isBlank()) return cs;
    } catch (Exception ignored) {}
    return "";
  }
%>
<%
String ctx = safe(request.getContextPath());
request.setAttribute("activeNav", "/ocr_companion_lab.jsp");
String csrfToken = csrfForRender(request);
String tenantUuid = safe((String) session.getAttribute("tenant.uuid")).trim();
if (tenantUuid.isBlank()) {
  response.sendRedirect(ctx + "/tenant_login.jsp");
  return;
}

String action = safe(request.getParameter("action")).trim();
String selectedMatterUuid = safe(request.getParameter("matter_uuid")).trim();
String selectedDocUuid = safe(request.getParameter("doc_uuid")).trim();
String selectedPartUuid = safe(request.getParameter("part_uuid")).trim();
String selectedVersionUuid = safe(request.getParameter("version_uuid")).trim();

matters matterStore = matters.defaultStore();
documents docStore = documents.defaultStore();
document_parts partStore = document_parts.defaultStore();
part_versions versionStore = part_versions.defaultStore();
version_ocr_companion_service ocrService = version_ocr_companion_service.defaultService();

List<matters.MatterRec> matterRows = new ArrayList<matters.MatterRec>();
try {
  for (matters.MatterRec row : matterStore.listAll(tenantUuid)) {
    if (row == null || row.trashed || !row.enabled) continue;
    matterRows.add(row);
  }
} catch (Exception ignored) {}
if (selectedMatterUuid.isBlank() && !matterRows.isEmpty()) {
  selectedMatterUuid = safe(matterRows.get(0).uuid).trim();
}

List<documents.DocumentRec> docRows = new ArrayList<documents.DocumentRec>();
if (!selectedMatterUuid.isBlank()) {
  try {
    for (documents.DocumentRec row : docStore.listAll(tenantUuid, selectedMatterUuid)) {
      if (row == null || row.trashed) continue;
      docRows.add(row);
    }
  } catch (Exception ignored) {}
}
if (selectedDocUuid.isBlank() && !docRows.isEmpty()) {
  selectedDocUuid = safe(docRows.get(0).uuid).trim();
}

List<document_parts.PartRec> partRows = new ArrayList<document_parts.PartRec>();
if (!selectedMatterUuid.isBlank() && !selectedDocUuid.isBlank()) {
  try {
    for (document_parts.PartRec row : partStore.listAll(tenantUuid, selectedMatterUuid, selectedDocUuid)) {
      if (row == null || row.trashed) continue;
      partRows.add(row);
    }
  } catch (Exception ignored) {}
}
if (selectedPartUuid.isBlank() && !partRows.isEmpty()) {
  selectedPartUuid = safe(partRows.get(0).uuid).trim();
}

List<part_versions.VersionRec> versionRows = new ArrayList<part_versions.VersionRec>();
if (!selectedMatterUuid.isBlank() && !selectedDocUuid.isBlank() && !selectedPartUuid.isBlank()) {
  try {
    versionRows = versionStore.listAll(tenantUuid, selectedMatterUuid, selectedDocUuid, selectedPartUuid);
  } catch (Exception ignored) {}
}
if (selectedVersionUuid.isBlank() && !versionRows.isEmpty()) {
  selectedVersionUuid = safe(versionRows.get(0).uuid).trim();
}

part_versions.VersionRec sourceVersion = null;
for (part_versions.VersionRec row : versionRows) {
  if (row == null) continue;
  if (selectedVersionUuid.equals(safe(row.uuid).trim())) {
    sourceVersion = row;
    break;
  }
}
if (sourceVersion == null && !versionRows.isEmpty()) {
  sourceVersion = versionRows.get(0);
  selectedVersionUuid = safe(sourceVersion == null ? "" : sourceVersion.uuid).trim();
}

boolean tesseractAvailable = ocrService.isTesseractAvailable();
String error = "";
version_ocr_companion_service.CompanionRec companion = null;
boolean ran = "generate".equalsIgnoreCase(action);
if (ran) {
  try {
    if (selectedMatterUuid.isBlank()) throw new IllegalArgumentException("Select a case first.");
    if (selectedDocUuid.isBlank()) throw new IllegalArgumentException("Select a document first.");
    if (selectedPartUuid.isBlank()) throw new IllegalArgumentException("Select a part first.");
    if (sourceVersion == null) throw new IllegalArgumentException("Select a version first.");
    companion = ocrService.ensureCompanion(tenantUuid, selectedMatterUuid, selectedDocUuid, selectedPartUuid, sourceVersion);
  } catch (Exception ex) {
    error = safe(ex.getMessage());
    if (error.isBlank()) error = "OCR companion generation failed.";
  }
}
%>
<jsp:include page="header.jsp" />

<section class="card">
  <h1 class="u-m-0">OCR Companion Lab</h1>
  <div class="meta u-mt-6">Generate and inspect <code>version_ocr_companion_service</code> outputs for a selected version.</div>
  <div class="meta u-mt-6">Tesseract available: <strong><%= tesseractAvailable ? "Yes" : "No" %></strong></div>
</section>

<section class="card section-gap-12">
  <form method="post" action="<%= ctx %>/ocr_companion_lab.jsp" class="form">
    <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
    <div class="grid grid-2">
      <div>
        <label for="matter_uuid">Case</label>
        <select id="matter_uuid" name="matter_uuid">
          <% if (matterRows.isEmpty()) { %>
            <option value="">No available cases</option>
          <% } else {
               for (matters.MatterRec row : matterRows) {
                 if (row == null) continue;
                 String uuid = safe(row.uuid).trim();
                 boolean selected = uuid.equals(selectedMatterUuid);
          %>
            <option value="<%= esc(uuid) %>" <%= selected ? "selected" : "" %>><%= esc(safe(row.label)) %></option>
          <%   }
             } %>
        </select>
      </div>
      <div>
        <label for="doc_uuid">Document</label>
        <select id="doc_uuid" name="doc_uuid">
          <% if (docRows.isEmpty()) { %>
            <option value="">No available documents</option>
          <% } else {
               for (documents.DocumentRec row : docRows) {
                 if (row == null) continue;
                 String uuid = safe(row.uuid).trim();
                 boolean selected = uuid.equals(selectedDocUuid);
          %>
            <option value="<%= esc(uuid) %>" <%= selected ? "selected" : "" %>><%= esc(safe(row.title)) %></option>
          <%   }
             } %>
        </select>
      </div>
      <div>
        <label for="part_uuid">Part</label>
        <select id="part_uuid" name="part_uuid">
          <% if (partRows.isEmpty()) { %>
            <option value="">No available parts</option>
          <% } else {
               for (document_parts.PartRec row : partRows) {
                 if (row == null) continue;
                 String uuid = safe(row.uuid).trim();
                 boolean selected = uuid.equals(selectedPartUuid);
          %>
            <option value="<%= esc(uuid) %>" <%= selected ? "selected" : "" %>><%= esc(safe(row.label)) %></option>
          <%   }
             } %>
        </select>
      </div>
      <div>
        <label for="version_uuid">Version</label>
        <select id="version_uuid" name="version_uuid">
          <% if (versionRows.isEmpty()) { %>
            <option value="">No available versions</option>
          <% } else {
               for (part_versions.VersionRec row : versionRows) {
                 if (row == null) continue;
                 String uuid = safe(row.uuid).trim();
                 String label = safe(row.versionLabel);
                 if (label.isBlank()) label = uuid;
                 boolean selected = uuid.equals(selectedVersionUuid);
          %>
            <option value="<%= esc(uuid) %>" <%= selected ? "selected" : "" %>><%= esc(label) %></option>
          <%   }
             } %>
        </select>
      </div>
    </div>
    <div class="u-flex u-gap-8 u-items-center u-wrap">
      <button class="btn" name="action" value="generate" type="submit">Generate / Refresh Companion</button>
      <button class="btn btn-ghost" name="action" value="refresh" type="submit">Refresh Selection Lists</button>
    </div>
  </form>
</section>

<% if (!error.isBlank()) { %>
<section class="card section-gap-12">
  <div class="alert alert-error"><%= esc(error) %></div>
</section>
<% } %>

<% if (ran && error.isBlank() && companion != null) { %>
<section class="card section-gap-12">
  <h2 class="u-m-0">Companion Output</h2>
  <div class="table-wrap table-wrap-tight u-mt-8">
    <table class="table">
      <tbody>
      <tr><th>Version UUID</th><td><code><%= esc(safe(companion.versionUuid)) %></code></td></tr>
      <tr><th>Generated At</th><td><%= esc(safe(companion.generatedAt)) %></td></tr>
      <tr><th>Engine</th><td><%= esc(safe(companion.engine)) %></td></tr>
      <tr><th>Used Tesseract</th><td><%= companion.usedTesseract ? "Yes" : "No" %></td></tr>
      <tr><th>Page Count</th><td><%= companion.pageCount %></td></tr>
      <tr><th>Image Hash Rows</th><td><%= companion.imageHashes == null ? 0 : companion.imageHashes.size() %></td></tr>
      <tr><th>Source Bytes</th><td><%= companion.sourceBytes %></td></tr>
      <tr><th>Source Checksum</th><td><code><%= esc(safe(companion.sourceChecksum)) %></code></td></tr>
      <tr><th>Companion XML Path</th><td><code><%= esc(safe(companion.xmlPath == null ? "" : companion.xmlPath.toString())) %></code></td></tr>
      </tbody>
    </table>
  </div>
  <h3>Text Preview</h3>
  <pre style="white-space:pre-wrap;"><%= esc(clip(companion.fullText, 4000)) %></pre>
</section>
<% } %>

<jsp:include page="footer.jsp" />
