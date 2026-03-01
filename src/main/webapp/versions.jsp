<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ page import="java.util.List" %>
<%@ page import="java.io.InputStream" %>
<%@ page import="java.nio.file.Files" %>
<%@ page import="java.nio.file.Path" %>
<%@ page import="java.nio.file.StandardOpenOption" %>
<%@ page import="java.security.MessageDigest" %>
<%@ page import="java.util.Base64" %>
<%@ page import="java.util.Comparator" %>
<%@ page import="java.util.UUID" %>
<%@ page import="net.familylawandprobate.controversies.part_versions" %>
<%@ page import="net.familylawandprobate.controversies.document_parts" %>
<%@ include file="security.jspf" %>
<% if (!require_login()) return; %>
<%!
private static final String S_TENANT_UUID = "tenant.uuid";
private static final String CSRF_SESSION_KEY = "CSRF_TOKEN";
private static final int MAX_UPLOAD_CHUNKS = 12000;
private static String safe(String s){ return s == null ? "" : s; }
private static String esc(String s){ return safe(s).replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;"); }
private static String csrfForRender(jakarta.servlet.http.HttpServletRequest req) {
  Object a = req.getAttribute("csrfToken");
  if (a instanceof String) {
    String s = (String) a;
    if (s != null && !s.trim().isEmpty()) return s;
  }
  try {
    jakarta.servlet.http.HttpSession sess = req.getSession(false);
    if (sess != null) {
      Object t = sess.getAttribute(CSRF_SESSION_KEY);
      if (t instanceof String) {
        String cs = (String) t;
        if (cs != null && !cs.trim().isEmpty()) return cs;
      }
    }
  } catch (Exception ignored) {}
  return "";
}
private static String onlyDigits(String s){ return safe(s).replaceAll("[^0-9]", ""); }
private static int intOrDefault(String s, int d){ try { return Integer.parseInt(safe(s).trim()); } catch (Exception ex) { return d; } }
private static String safeFileName(String s){
  String v = safe(s).trim().replaceAll("[^A-Za-z0-9._-]", "_");
  if (v.isBlank()) return "version.bin";
  if (v.length() > 140) return v.substring(v.length() - 140);
  return v;
}
private static String hex(byte[] raw){
  if (raw == null) return "";
  StringBuilder sb = new StringBuilder(raw.length * 2);
  for (byte b : raw) sb.append(String.format(java.util.Locale.ROOT, "%02x", b));
  return sb.toString();
}
private static String sha256(byte[] raw) throws Exception {
  MessageDigest md = MessageDigest.getInstance("SHA-256");
  return hex(md.digest(raw == null ? new byte[0] : raw));
}
private static String sha256(Path p) throws Exception {
  MessageDigest md = MessageDigest.getInstance("SHA-256");
  try (InputStream in = Files.newInputStream(p, StandardOpenOption.READ)) {
    byte[] buf = new byte[8192];
    int n;
    while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
  }
  return hex(md.digest());
}
private static void deleteRecursively(Path root) {
  try {
    if (root == null || !Files.exists(root)) return;
    Files.walk(root)
      .sorted(Comparator.reverseOrder())
      .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignore) {} });
  } catch (Exception ignore) {}
}
private static boolean isPdfVersion(part_versions.VersionRec rec) {
  if (rec == null) return false;
  String mime = safe(rec.mimeType).trim().toLowerCase(java.util.Locale.ROOT);
  if (mime.contains("pdf")) return true;
  String storage = safe(rec.storagePath).trim().toLowerCase(java.util.Locale.ROOT);
  return storage.endsWith(".pdf") || storage.contains(".pdf?");
}
%>
<%
String ctx = safe(request.getContextPath());
response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
response.setHeader("Pragma", "no-cache");
response.setDateHeader("Expires", 0L);
String csrfToken = csrfForRender(request);
String tenantUuid = safe((String)session.getAttribute(S_TENANT_UUID)).trim();
if (tenantUuid.isBlank()) { response.sendRedirect(ctx + "/tenant_login.jsp"); return; }
String caseUuid = safe(request.getParameter("case_uuid")).trim();
String docUuid = safe(request.getParameter("doc_uuid")).trim();
String partUuid = safe(request.getParameter("part_uuid")).trim();
part_versions versions = part_versions.defaultStore();
document_parts parts = document_parts.defaultStore();
String error = null;
String message = null;
if ("POST".equalsIgnoreCase(request.getMethod())) {
  String action = safe(request.getParameter("action")).trim();
  boolean uploadAction = "upload_chunk_bin".equalsIgnoreCase(action) || "upload_chunk".equalsIgnoreCase(action) || "commit_upload".equalsIgnoreCase(action);
  try {
    Path partFolder = parts.partFolder(tenantUuid, caseUuid, docUuid, partUuid);
    if (partFolder == null) throw new IllegalArgumentException("Part folder unavailable.");
    Path tempRoot = partFolder.resolve(".upload_staging");
    Files.createDirectories(tempRoot);
    if ("upload_chunk_bin".equalsIgnoreCase(action)) {
      String uploadId = safe(request.getParameter("upload_id")).replaceAll("[^A-Za-z0-9_-]", "");
      int chunkIndex = intOrDefault(request.getParameter("chunk_index"), -1);
      int totalChunks = intOrDefault(request.getParameter("total_chunks"), -1);
      String chunkSha = safe(request.getHeader("X-Chunk-SHA256")).trim().toLowerCase(java.util.Locale.ROOT);
      if (chunkSha.isBlank()) chunkSha = safe(request.getParameter("chunk_sha256")).trim().toLowerCase(java.util.Locale.ROOT);
      if (uploadId.isBlank()) throw new IllegalArgumentException("Missing upload id.");
      if (chunkIndex < 0 || totalChunks <= 0 || totalChunks > MAX_UPLOAD_CHUNKS || chunkIndex >= totalChunks) throw new IllegalArgumentException("Invalid chunk index.");
      byte[] chunkBytes;
      try (java.io.InputStream raw = request.getInputStream()) {
        chunkBytes = raw.readAllBytes();
      }
      if (chunkBytes == null || chunkBytes.length == 0) throw new IllegalArgumentException("Chunk payload missing.");
      String actualChunkSha = sha256(chunkBytes);
      if (!chunkSha.equals(actualChunkSha)) throw new IllegalArgumentException("Chunk integrity check failed at chunk " + chunkIndex + ".");
      Path uploadDir = tempRoot.resolve(uploadId);
      Files.createDirectories(uploadDir);
      Path chunkPath = uploadDir.resolve("chunk-" + chunkIndex + ".bin");
      Path redundantChunkPath = uploadDir.resolve("chunk-" + chunkIndex + ".bak");
      Files.write(chunkPath, chunkBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
      Files.write(redundantChunkPath, chunkBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
      response.setContentType("text/plain;charset=UTF-8");
      out.clearBuffer();
      out.print("ok");
      return;
    } else if ("upload_chunk".equalsIgnoreCase(action)) {
      String uploadId = safe(request.getParameter("upload_id")).replaceAll("[^A-Za-z0-9_-]", "");
      int chunkIndex = intOrDefault(request.getParameter("chunk_index"), -1);
      int totalChunks = intOrDefault(request.getParameter("total_chunks"), -1);
      String chunkSha = safe(request.getParameter("chunk_sha256")).trim().toLowerCase(java.util.Locale.ROOT);
      if (uploadId.isBlank()) throw new IllegalArgumentException("Missing upload id.");
      if (chunkIndex < 0 || totalChunks <= 0 || totalChunks > MAX_UPLOAD_CHUNKS || chunkIndex >= totalChunks) throw new IllegalArgumentException("Invalid chunk index.");
      byte[] chunkBytes = Base64.getDecoder().decode(safe(request.getParameter("chunk_b64")));
      String actualChunkSha = sha256(chunkBytes);
      if (!chunkSha.equals(actualChunkSha)) throw new IllegalArgumentException("Chunk integrity check failed at chunk " + chunkIndex + ".");
      Path uploadDir = tempRoot.resolve(uploadId);
      Files.createDirectories(uploadDir);
      Path chunkPath = uploadDir.resolve("chunk-" + chunkIndex + ".bin");
      Path redundantChunkPath = uploadDir.resolve("chunk-" + chunkIndex + ".bak");
      Files.write(chunkPath, chunkBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
      Files.write(redundantChunkPath, chunkBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
      response.setContentType("text/plain;charset=UTF-8");
      out.clearBuffer();
      out.print("ok");
      return;
    } else if ("commit_upload".equalsIgnoreCase(action)) {
      String uploadId = safe(request.getParameter("upload_id")).replaceAll("[^A-Za-z0-9_-]", "");
      int totalChunks = intOrDefault(request.getParameter("total_chunks"), -1);
      String expectedFileSha = safe(request.getParameter("file_sha256")).trim().toLowerCase(java.util.Locale.ROOT);
      long expectedBytes = Long.parseLong(onlyDigits(request.getParameter("file_size_bytes")).isBlank() ? "0" : onlyDigits(request.getParameter("file_size_bytes")));
      String uploadName = safeFileName(request.getParameter("upload_file_name"));
      if (uploadId.isBlank() || totalChunks <= 0 || totalChunks > MAX_UPLOAD_CHUNKS) throw new IllegalArgumentException("Invalid upload metadata.");
      Path uploadDir = tempRoot.resolve(uploadId);
      if (!Files.exists(uploadDir)) throw new IllegalArgumentException("Upload session not found.");
      Path outputDir = partFolder.resolve("version_files");
      Files.createDirectories(outputDir);
      String versionUuid = UUID.randomUUID().toString();
      Path outputPath = outputDir.resolve(versionUuid + "__" + uploadName);
      long assembledSize = 0L;
      try (java.io.OutputStream os = Files.newOutputStream(outputPath, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
        for (int i = 0; i < totalChunks; i++) {
          Path chunkPath = uploadDir.resolve("chunk-" + i + ".bin");
          if (!Files.exists(chunkPath)) {
            Path backup = uploadDir.resolve("chunk-" + i + ".bak");
            if (Files.exists(backup)) {
              Files.copy(backup, chunkPath);
            } else {
              throw new IllegalArgumentException("Missing chunk " + i + ".");
            }
          }
          byte[] b = Files.readAllBytes(chunkPath);
          os.write(b);
          assembledSize += b.length;
        }
      }
      String finalSha = sha256(outputPath);
      if (!expectedFileSha.isBlank() && !expectedFileSha.equals(finalSha)) {
        Files.deleteIfExists(outputPath);
        throw new IllegalArgumentException("File integrity check failed after assembly.");
      }
      if (expectedBytes > 0 && expectedBytes != assembledSize) {
        Files.deleteIfExists(outputPath);
        throw new IllegalArgumentException("File size check failed after assembly.");
      }
      deleteRecursively(uploadDir);
      versions.create(tenantUuid, caseUuid, docUuid, partUuid,
        request.getParameter("version_label"), request.getParameter("source"), request.getParameter("mime_type"),
        finalSha, String.valueOf(assembledSize), outputPath.toUri().toString(),
        request.getParameter("created_by"), request.getParameter("notes"), "1".equals(request.getParameter("make_current")));
      response.sendRedirect(ctx + "/versions.jsp?case_uuid=" + java.net.URLEncoder.encode(caseUuid, java.nio.charset.StandardCharsets.UTF_8) + "&doc_uuid=" + java.net.URLEncoder.encode(docUuid, java.nio.charset.StandardCharsets.UTF_8) + "&part_uuid=" + java.net.URLEncoder.encode(partUuid, java.nio.charset.StandardCharsets.UTF_8) + "&uploaded=1");
      return;
    } else {
      versions.create(tenantUuid, caseUuid, docUuid, partUuid,
        request.getParameter("version_label"), request.getParameter("source"), request.getParameter("mime_type"),
        request.getParameter("checksum"), request.getParameter("file_size_bytes"), request.getParameter("storage_path"),
        request.getParameter("created_by"), request.getParameter("notes"), "1".equals(request.getParameter("make_current")));
      response.sendRedirect(ctx + "/versions.jsp?case_uuid=" + java.net.URLEncoder.encode(caseUuid, java.nio.charset.StandardCharsets.UTF_8) + "&doc_uuid=" + java.net.URLEncoder.encode(docUuid, java.nio.charset.StandardCharsets.UTF_8) + "&part_uuid=" + java.net.URLEncoder.encode(partUuid, java.nio.charset.StandardCharsets.UTF_8));
      return;
    }
  } catch (Exception ex) {
    if (uploadAction) {
      response.setStatus(400);
      response.setContentType("text/plain;charset=UTF-8");
      response.getWriter().write(safe(ex.getMessage()));
      return;
    }
    error = safe(ex.getMessage());
  }
}
if ("1".equals(request.getParameter("uploaded"))) message = "Version file uploaded, verified, and recorded.";
if ("1".equals(request.getParameter("redacted"))) message = "Redacted PDF saved as a new document part version.";
List<part_versions.VersionRec> rows = versions.listAll(tenantUuid, caseUuid, docUuid, partUuid);
document_parts.PartRec part = parts.get(tenantUuid, caseUuid, docUuid, partUuid);
%>
<jsp:include page="header.jsp" />
<section class="card">
  <div style="display:flex; gap:10px; justify-content:space-between; align-items:flex-start; flex-wrap:wrap;">
    <div>
      <h1 style="margin:0;">Part Versions</h1>
      <div class="meta">Part: <strong><%= esc(part == null ? "" : part.label) %></strong></div>
    </div>
    <a class="btn btn-ghost" href="<%= ctx %>/pdf_redact.jsp?case_uuid=<%= java.net.URLEncoder.encode(caseUuid, java.nio.charset.StandardCharsets.UTF_8) %>&doc_uuid=<%= java.net.URLEncoder.encode(docUuid, java.nio.charset.StandardCharsets.UTF_8) %>&part_uuid=<%= java.net.URLEncoder.encode(partUuid, java.nio.charset.StandardCharsets.UTF_8) %>">Redact a PDF Version</a>
  </div>
</section>
<section class="card" style="margin-top:12px;">
<form class="form" method="post" action="<%= ctx %>/versions.jsp?case_uuid=<%= java.net.URLEncoder.encode(caseUuid, java.nio.charset.StandardCharsets.UTF_8) %>&doc_uuid=<%= java.net.URLEncoder.encode(docUuid, java.nio.charset.StandardCharsets.UTF_8) %>&part_uuid=<%= java.net.URLEncoder.encode(partUuid, java.nio.charset.StandardCharsets.UTF_8) %>">
<input type="hidden" name="csrfToken" id="csrf_token" value="<%= esc(csrfToken) %>" />
<input type="hidden" name="action" id="version_form_action" value="create_metadata" />
<input type="hidden" name="upload_id" id="upload_id" value="" />
<input type="hidden" name="total_chunks" id="total_chunks" value="" />
<input type="hidden" name="upload_file_name" id="upload_file_name" value="" />
<input type="hidden" name="file_sha256" id="file_sha256" value="" />
<div class="grid grid-3"><div><label>Version Label</label><input type="text" name="version_label" required /></div><div><label>Source</label><input type="text" name="source" placeholder="uploaded/ocr/generated" /></div><div><label>MIME Type</label><input type="text" name="mime_type" placeholder="application/pdf"/></div></div>
<div class="grid grid-3"><div><label>Checksum</label><input type="text" name="checksum"/></div><div><label>File Size (bytes)</label><input type="text" name="file_size_bytes"/></div><div><label>Storage Path</label><input type="text" name="storage_path" placeholder="vault://..."/></div></div>
<div class="grid grid-3"><div><label>Version File (chunk upload)</label><input type="file" id="version_upload_file" /></div><div><label>Chunk Status</label><div id="chunk_upload_status" class="meta">No file selected.</div></div><div></div></div>
<div class="grid grid-3"><div><label>Created By</label><input type="text" name="created_by"/></div><div><label>Notes</label><input type="text" name="notes"/></div><div><label><input type="checkbox" name="make_current" value="1" checked /> Mark current</label></div></div>
<button class="btn" type="submit">Add Version</button>
</form>
<% if (!safe(message).isBlank()) { %><div class="alert" style="margin-top:10px;"><%= esc(message) %></div><% } %>
<% if (!safe(error).isBlank()) { %><div class="alert alert-error" style="margin-top:10px;"><%= esc(error) %></div><% } %>
</section>
<section class="card" style="margin-top:12px;"><table class="table"><thead><tr><th>Version</th><th>Source</th><th>MIME</th><th>Checksum</th><th>Created</th><th>Current</th><th></th></tr></thead><tbody>
<% for (part_versions.VersionRec r : rows) { %>
<tr><td><%= esc(r.versionLabel) %></td><td><%= esc(r.source) %></td><td><%= esc(r.mimeType) %></td><td><%= esc(r.checksum) %></td><td><%= esc(r.createdAt) %></td><td><%= r.current ? "Yes" : "" %></td><td><% if (isPdfVersion(r)) { %><a class="btn btn-ghost" href="<%= ctx %>/pdf_redact.jsp?case_uuid=<%= java.net.URLEncoder.encode(caseUuid, java.nio.charset.StandardCharsets.UTF_8) %>&doc_uuid=<%= java.net.URLEncoder.encode(docUuid, java.nio.charset.StandardCharsets.UTF_8) %>&part_uuid=<%= java.net.URLEncoder.encode(partUuid, java.nio.charset.StandardCharsets.UTF_8) %>&source_version_uuid=<%= java.net.URLEncoder.encode(safe(r.uuid), java.nio.charset.StandardCharsets.UTF_8) %>">Redact</a><% } %></td></tr>
<% } %>
</tbody></table></section>
<script>
(function () {
  var form = document.querySelector("form.form");
  if (!form) return;
  var fileInput = document.getElementById("version_upload_file");
  var statusEl = document.getElementById("chunk_upload_status");
  var actionInput = document.getElementById("version_form_action");
  var uploadIdInput = document.getElementById("upload_id");
  var totalChunksInput = document.getElementById("total_chunks");
  var uploadFileNameInput = document.getElementById("upload_file_name");
  var fileShaInput = document.getElementById("file_sha256");
  var csrfTokenInput = document.getElementById("csrf_token");
  var appContext = "<%= esc(ctx) %>";
  var caseUuid = "<%= esc(caseUuid) %>";
  var docUuid = "<%= esc(docUuid) %>";
  var partUuid = "<%= esc(partUuid) %>";
  var uploadTransportVersion = "version-upload-servlet-v1";
  var CHUNK_SIZE = 128 * 1024;

  function setStatus(msg) { if (statusEl) statusEl.textContent = msg; }
  function toHex(buffer) {
    var arr = new Uint8Array(buffer);
    var out = "";
    for (var i = 0; i < arr.length; i++) out += arr[i].toString(16).padStart(2, "0");
    return out;
  }
  async function sha256Hex(blob) {
    var digest = await crypto.subtle.digest("SHA-256", await blob.arrayBuffer());
    return toHex(digest);
  }
  setStatus("No file selected. Transport: " + uploadTransportVersion + ".");

  async function parseUploadResponse(resp) {
    var text = "";
    try { text = await resp.text(); } catch (ignore) {}
    var payload = null;
    if (text) {
      try { payload = JSON.parse(text); } catch (ignore2) {}
    }
    return { text: text, json: payload };
  }

  async function assertUploadEndpoint() {
    var pingResp = await fetch(appContext + "/versions_upload", {
      method: "POST",
      credentials: "same-origin",
      headers: {
        "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8",
        "X-CSRF-Token": csrfTokenInput.value
      },
      body: "action=diag"
    });
    if (!pingResp.ok) {
      var parsed = await parseUploadResponse(pingResp);
      var msg = "Upload endpoint check failed (HTTP " + pingResp.status + ").";
      if (parsed.json && parsed.json.message) msg += " " + parsed.json.message;
      else if (parsed.text) msg += " " + parsed.text.trim();
      throw new Error(msg);
    }
    var json = {};
    try { json = await pingResp.json(); } catch (ignore) {}
    if (!json || !json.ok) {
      throw new Error("Upload endpoint diagnostics failed.");
    }
  }

  form.addEventListener("submit", async function (ev) {
    var file = fileInput && fileInput.files && fileInput.files.length ? fileInput.files[0] : null;
    if (!file) {
      actionInput.value = "create_metadata";
      return;
    }
    if (!csrfTokenInput || !csrfTokenInput.value) {
      ev.preventDefault();
      setStatus("Upload blocked: CSRF token missing. Reload page.");
      alert("Upload blocked. CSRF token missing. Reload the page and try again.");
      return;
    }
    ev.preventDefault();
    try {
      setStatus("Checking upload endpoint...");
      await assertUploadEndpoint();
      var uploadId = (Date.now().toString(36) + "-" + Math.random().toString(36).slice(2, 10)).replace(/[^a-z0-9_-]/gi, "");
      var totalChunks = Math.ceil(file.size / CHUNK_SIZE);
      if (totalChunks <= 0) throw new Error("Selected file is empty.");
      setStatus("Hashing file...");
      var fileSha = await sha256Hex(file);
      for (var i = 0; i < totalChunks; i++) {
        var start = i * CHUNK_SIZE;
        var end = Math.min(file.size, start + CHUNK_SIZE);
        var chunk = file.slice(start, end);
        var chunkSha = await sha256Hex(chunk);
        var chunkBytes = await chunk.arrayBuffer();
        var uploadUrl = appContext + "/versions_upload";
        setStatus("Uploading chunk " + (i + 1) + " / " + totalChunks + "...");
        var resp = await fetch(uploadUrl, {
          method: "POST",
          credentials: "same-origin",
          headers: {
            "Content-Type": "application/octet-stream",
            "X-CSRF-Token": csrfTokenInput.value,
            "X-Upload-Action": "chunk",
            "X-Case-UUID": caseUuid,
            "X-Doc-UUID": docUuid,
            "X-Part-UUID": partUuid,
            "X-Upload-Id": uploadId,
            "X-Chunk-Index": String(i),
            "X-Total-Chunks": String(totalChunks),
            "X-Chunk-SHA256": chunkSha
          },
          body: chunkBytes
        });
        if (!resp.ok) {
          var parsed = await parseUploadResponse(resp);
          var errText = "";
          if (parsed.json && parsed.json.message) {
            errText = String(parsed.json.message);
            if (parsed.json.request_id) errText += " [request_id=" + parsed.json.request_id + "]";
          } else {
            errText = (parsed.text || "").trim();
          }
          var prefix = "Chunk upload failed at " + (i + 1) + " (HTTP " + resp.status + ").";
          throw new Error(errText ? (prefix + " " + errText) : prefix);
        }
      }

      form.elements["checksum"].value = fileSha;
      form.elements["file_size_bytes"].value = String(file.size);
      form.elements["storage_path"].value = "pending://assembled";
      if (!form.elements["source"].value) form.elements["source"].value = "uploaded";
      if (!form.elements["mime_type"].value) form.elements["mime_type"].value = file.type || "application/octet-stream";
      setStatus("Upload complete. Finalizing...");

      var commitParams = new URLSearchParams();
      commitParams.set("action", "commit");
      commitParams.set("case_uuid", caseUuid);
      commitParams.set("doc_uuid", docUuid);
      commitParams.set("part_uuid", partUuid);
      commitParams.set("upload_id", uploadId);
      commitParams.set("total_chunks", String(totalChunks));
      commitParams.set("upload_file_name", file.name || "version.bin");
      commitParams.set("file_sha256", fileSha);
      commitParams.set("file_size_bytes", String(file.size));
      commitParams.set("version_label", form.elements["version_label"].value || "");
      commitParams.set("source", form.elements["source"].value || "");
      commitParams.set("mime_type", form.elements["mime_type"].value || "");
      commitParams.set("created_by", form.elements["created_by"].value || "");
      commitParams.set("notes", form.elements["notes"].value || "");
      commitParams.set("make_current", form.elements["make_current"] && form.elements["make_current"].checked ? "1" : "0");

      var commitResp = await fetch(appContext + "/versions_upload", {
        method: "POST",
        credentials: "same-origin",
        headers: {
          "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8",
          "X-CSRF-Token": csrfTokenInput.value
        },
        body: commitParams.toString()
      });
      if (!commitResp.ok) {
        var commitParsed = await parseUploadResponse(commitResp);
        var commitText = "";
        if (commitParsed.json && commitParsed.json.message) {
          commitText = String(commitParsed.json.message);
          if (commitParsed.json.request_id) commitText += " [request_id=" + commitParsed.json.request_id + "]";
        } else {
          commitText = (commitParsed.text || "").trim();
        }
        throw new Error("Commit failed (HTTP " + commitResp.status + "). " + commitText);
      }

      var commitJson = {};
      try { commitJson = await commitResp.json(); } catch (ignore3) {}
      if (!commitJson || !commitJson.ok) {
        var msg = commitJson && commitJson.message ? String(commitJson.message) : "Commit response invalid.";
        if (commitJson && commitJson.request_id) msg += " [request_id=" + commitJson.request_id + "]";
        throw new Error(msg);
      }
      var redirect = commitJson.redirect || "";
      if (redirect) {
        window.location.assign(redirect);
      } else {
        window.location.reload();
      }
    } catch (err) {
      setStatus("Upload failed: " + (err && err.message ? err.message : "unknown error"));
      alert("Version file upload failed. " + (err && err.message ? err.message : ""));
    }
  });
})();
</script>
<jsp:include page="footer.jsp" />
