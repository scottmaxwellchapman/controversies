<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ page import="java.net.URLEncoder" %>
<%@ page import="java.nio.charset.StandardCharsets" %>
<%@ page import="java.util.ArrayList" %>
<%@ page import="java.util.List" %>
<%@ page import="java.util.Locale" %>
<%@ page import="net.familylawandprobate.controversies.matters" %>
<%@ page import="net.familylawandprobate.controversies.documents" %>
<%@ page import="net.familylawandprobate.controversies.document_parts" %>
<%@ page import="net.familylawandprobate.controversies.part_versions" %>
<%@ page import="net.familylawandprobate.controversies.version_image_similarity_service" %>
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
  private static String enc(String s) {
    return URLEncoder.encode(safe(s), StandardCharsets.UTF_8);
  }
  private static int intOrDefault(String raw, int def) {
    try { return Integer.parseInt(safe(raw).trim()); } catch (Exception ignored) { return def; }
  }
  private static int clamp(int v, int min, int max) {
    if (v < min) return min;
    if (v > max) return max;
    return v;
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
request.setAttribute("activeNav", "/version_similarity_lab.jsp");
String csrfToken = csrfForRender(request);
String tenantUuid = safe((String) session.getAttribute("tenant.uuid")).trim();
if (tenantUuid.isBlank()) {
  response.sendRedirect(ctx + "/tenant_login.jsp");
  return;
}

String action = safe(request.getParameter("action")).trim().toLowerCase(Locale.ROOT);
String selectedMatterUuid = safe(request.getParameter("matter_uuid")).trim();
String selectedDocUuid = safe(request.getParameter("doc_uuid")).trim();
String selectedPartUuid = safe(request.getParameter("part_uuid")).trim();
String selectedVersionUuid = safe(request.getParameter("version_uuid")).trim();
String scope = safe(request.getParameter("scope")).trim().toLowerCase(Locale.ROOT);
if (!"tenant".equals(scope) && !"matter".equals(scope)) scope = "matter";
int maxResults = clamp(intOrDefault(request.getParameter("max_results"), 25), 1, 200);
int maxHammingDistance = clamp(intOrDefault(request.getParameter("max_hamming_distance"), 8), 0, 64);

matters matterStore = matters.defaultStore();
documents docStore = documents.defaultStore();
document_parts partStore = document_parts.defaultStore();
part_versions versionStore = part_versions.defaultStore();

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

String error = "";
ArrayList<version_image_similarity_service.SimilarityRec> results = new ArrayList<version_image_similarity_service.SimilarityRec>();
boolean ran = "find".equals(action);
if (ran) {
  try {
    if (selectedMatterUuid.isBlank()) throw new IllegalArgumentException("Select a case first.");
    if (selectedDocUuid.isBlank()) throw new IllegalArgumentException("Select a document first.");
    if (selectedPartUuid.isBlank()) throw new IllegalArgumentException("Select a part first.");
    if (sourceVersion == null) throw new IllegalArgumentException("Select a source version first.");
    results = version_image_similarity_service.defaultService().findSimilarVersions(
      tenantUuid,
      selectedMatterUuid,
      selectedDocUuid,
      selectedPartUuid,
      sourceVersion,
      scope,
      maxResults,
      maxHammingDistance
    );
  } catch (Exception ex) {
    error = safe(ex.getMessage());
    if (error.isBlank()) error = "Similarity search failed.";
  }
}
%>
<jsp:include page="header.jsp" />

<section class="card">
  <h1 class="u-m-0">Version Similarity Lab</h1>
  <div class="meta u-mt-6">Run <code>version_image_similarity_service</code> from the UI. This auto-generates OCR/image-hash companion records as needed.</div>
</section>

<section class="card section-gap-12">
  <form method="post" action="<%= ctx %>/version_similarity_lab.jsp" class="form">
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
        <label for="version_uuid">Source Version</label>
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

    <div class="grid grid-3">
      <div>
        <label for="scope">Scope</label>
        <select id="scope" name="scope">
          <option value="matter" <%= "matter".equals(scope) ? "selected" : "" %>>Current Case</option>
          <option value="tenant" <%= "tenant".equals(scope) ? "selected" : "" %>>Entire Tenant</option>
        </select>
      </div>
      <div>
        <label for="max_results">Max Results</label>
        <input id="max_results" name="max_results" type="number" min="1" max="200" value="<%= maxResults %>" />
      </div>
      <div>
        <label for="max_hamming_distance">Max Hamming Distance</label>
        <input id="max_hamming_distance" name="max_hamming_distance" type="number" min="0" max="64" value="<%= maxHammingDistance %>" />
      </div>
    </div>

    <div class="u-flex u-gap-8 u-items-center u-wrap">
      <button class="btn" name="action" value="find" type="submit">Find Similar Versions</button>
      <button class="btn btn-ghost" name="action" value="refresh" type="submit">Refresh Selection Lists</button>
    </div>
  </form>
</section>

<% if (!error.isBlank()) { %>
<section class="card section-gap-12">
  <div class="alert alert-error"><%= esc(error) %></div>
</section>
<% } %>

<% if (ran && error.isBlank()) { %>
<section class="card section-gap-12">
  <h2 class="u-m-0">Similarity Results</h2>
  <div class="meta u-mt-6">Found <strong><%= results.size() %></strong> matching versions.</div>
  <div class="table-wrap table-wrap-tight u-mt-8">
    <table class="table">
      <thead>
      <tr>
        <th>Case</th>
        <th>Document</th>
        <th>Part</th>
        <th>Version</th>
        <th>Similarity %</th>
        <th>Exact Pages</th>
        <th>Near Pages</th>
        <th>Best Distance</th>
        <th>Duplicate</th>
        <th></th>
      </tr>
      </thead>
      <tbody>
      <% if (results.isEmpty()) { %>
        <tr><td colspan="10" class="meta">No similar versions found with this threshold.</td></tr>
      <% } else {
           for (version_image_similarity_service.SimilarityRec row : results) {
             if (row == null) continue;
      %>
        <tr>
          <td><%= esc(safe(row.matterLabel)) %></td>
          <td><%= esc(safe(row.documentTitle)) %></td>
          <td><%= esc(safe(row.partLabel)) %></td>
          <td><%= esc(safe(row.versionLabel).isBlank() ? safe(row.versionUuid) : safe(row.versionLabel)) %></td>
          <td><%= row.similarityPercent %>%</td>
          <td><%= row.exactPageMatches %> / <%= row.sourcePages %></td>
          <td><%= row.nearPageMatches %> / <%= row.sourcePages %></td>
          <td><%= row.bestHammingDistance %></td>
          <td><%= row.duplicateCandidate ? "Yes" : "No" %></td>
          <td>
            <a class="btn btn-ghost" href="<%= ctx %>/versions.jsp?case_uuid=<%= enc(row.matterUuid) %>&doc_uuid=<%= enc(row.documentUuid) %>&part_uuid=<%= enc(row.partUuid) %>">Open</a>
          </td>
        </tr>
      <%   }
         } %>
      </tbody>
    </table>
  </div>
</section>
<% } %>

<jsp:include page="footer.jsp" />
