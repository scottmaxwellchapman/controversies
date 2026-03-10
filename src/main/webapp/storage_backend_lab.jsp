<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ page import="java.nio.charset.StandardCharsets" %>
<%@ page import="java.util.ArrayList" %>
<%@ page import="java.util.Base64" %>
<%@ page import="java.util.LinkedHashMap" %>
<%@ page import="java.util.List" %>
<%@ page import="java.util.Map" %>
<%@ page import="net.familylawandprobate.controversies.matters" %>
<%@ page import="net.familylawandprobate.controversies.storage.DocumentStorageBackend" %>
<%@ page import="net.familylawandprobate.controversies.storage.StorageBackendResolver" %>
<%@ include file="security.jspf" %>
<% if (!require_login()) return; %>
<% if (!require_permission("tenant_settings.manage")) return; %>
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
request.setAttribute("activeNav", "/storage_backend_lab.jsp");
String csrfToken = csrfForRender(request);
String tenantUuid = safe((String) session.getAttribute("tenant.uuid")).trim();
if (tenantUuid.isBlank()) {
  response.sendRedirect(ctx + "/tenant_login.jsp");
  return;
}

String action = safe(request.getParameter("action")).trim();
String selectedMatterUuid = safe(request.getParameter("matter_uuid")).trim();
String storageKey = safe(request.getParameter("storage_key")).trim();
if (storageKey.isBlank()) storageKey = "lab/sample.txt";

List<matters.MatterRec> matterRows = new ArrayList<matters.MatterRec>();
try {
  for (matters.MatterRec row : matters.defaultStore().listAll(tenantUuid)) {
    if (row == null || row.trashed || !row.enabled) continue;
    matterRows.add(row);
  }
} catch (Exception ignored) {}
if (selectedMatterUuid.isBlank() && !matterRows.isEmpty()) {
  selectedMatterUuid = safe(matterRows.get(0).uuid).trim();
}

String error = "";
String backendType = "";
boolean exists = false;
long byteCount = 0L;
boolean ranInspect = "inspect".equalsIgnoreCase(action) || "read".equalsIgnoreCase(action);
boolean ranRead = "read".equalsIgnoreCase(action);
LinkedHashMap<String, String> metadata = new LinkedHashMap<String, String>();
String textPreview = "";
String base64Preview = "";
boolean base64Truncated = false;

if (ranInspect) {
  try {
    if (selectedMatterUuid.isBlank()) throw new IllegalArgumentException("Select a case first.");
    StorageBackendResolver resolver = new StorageBackendResolver();
    backendType = resolver.resolveBackendType(tenantUuid);
    DocumentStorageBackend backend = resolver.resolve(tenantUuid, selectedMatterUuid, backendType);
    exists = backend.exists(storageKey);
    metadata.putAll(backend.metadata(storageKey));

    if (ranRead && exists) {
      byte[] bytes = backend.get(storageKey);
      byteCount = bytes == null ? 0L : bytes.length;
      byte[] src = bytes == null ? new byte[0] : bytes;
      String decoded = new String(src, StandardCharsets.UTF_8);
      textPreview = clip(decoded, 4000);

      int b64Limit = Math.min(src.length, 1024);
      byte[] b64Bytes = new byte[b64Limit];
      if (b64Limit > 0) System.arraycopy(src, 0, b64Bytes, 0, b64Limit);
      base64Preview = Base64.getEncoder().encodeToString(b64Bytes);
      base64Truncated = src.length > b64Limit;
    }
  } catch (Exception ex) {
    error = safe(ex.getMessage());
    if (error.isBlank()) error = "Storage inspection failed.";
  }
}
%>
<jsp:include page="header.jsp" />

<section class="card">
  <h1 class="u-m-0">Storage Backend Lab</h1>
  <div class="meta u-mt-6">Inspect resolved storage backend behavior via <code>StorageBackendResolver</code> and <code>DocumentStorageBackend</code>.</div>
</section>

<section class="card section-gap-12">
  <form method="post" action="<%= ctx %>/storage_backend_lab.jsp" class="form">
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
        <label for="storage_key">Storage Key</label>
        <input id="storage_key" name="storage_key" type="text" value="<%= esc(storageKey) %>" placeholder="path/to/file.ext" />
      </div>
    </div>
    <div class="u-flex u-gap-8 u-items-center u-wrap">
      <button class="btn" name="action" value="inspect" type="submit">Inspect Metadata</button>
      <button class="btn btn-ghost" name="action" value="read" type="submit">Inspect + Read Preview</button>
    </div>
  </form>
</section>

<% if (!error.isBlank()) { %>
<section class="card section-gap-12">
  <div class="alert alert-error"><%= esc(error) %></div>
</section>
<% } %>

<% if (ranInspect && error.isBlank()) { %>
<section class="card section-gap-12">
  <h2 class="u-m-0">Inspection Result</h2>
  <div class="meta u-mt-6">Resolved backend: <strong><%= esc(backendType) %></strong> | Exists: <strong><%= exists ? "Yes" : "No" %></strong></div>
  <div class="table-wrap table-wrap-tight u-mt-8">
    <table class="table">
      <thead>
      <tr><th>Metadata Key</th><th>Value</th></tr>
      </thead>
      <tbody>
      <% if (metadata.isEmpty()) { %>
        <tr><td colspan="2" class="meta">No metadata returned.</td></tr>
      <% } else {
           for (Map.Entry<String, String> entry : metadata.entrySet()) {
      %>
        <tr>
          <td><code><%= esc(safe(entry.getKey())) %></code></td>
          <td><%= esc(safe(entry.getValue())) %></td>
        </tr>
      <%   }
         } %>
      </tbody>
    </table>
  </div>
</section>
<% } %>

<% if (ranRead && error.isBlank() && exists) { %>
<section class="card section-gap-12">
  <h2 class="u-m-0">Read Preview</h2>
  <div class="meta u-mt-6">Bytes read: <strong><%= byteCount %></strong></div>
  <h3>UTF-8 Text Preview</h3>
  <pre style="white-space:pre-wrap;"><%= esc(textPreview) %></pre>
  <h3>Base64 Preview (first 1024 bytes)</h3>
  <pre style="white-space:pre-wrap;"><%= esc(base64Preview) %></pre>
  <% if (base64Truncated) { %>
    <div class="meta">Base64 preview truncated to the first 1024 bytes.</div>
  <% } %>
</section>
<% } %>

<jsp:include page="footer.jsp" />
