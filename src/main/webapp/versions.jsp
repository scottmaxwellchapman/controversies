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
private static final int MAX_UPLOAD_CHUNKS = 12000;
private static String safe(String s){ return s == null ? "" : s; }
private static String esc(String s){ return safe(s).replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;"); }
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
%>
<%
String ctx = safe(request.getContextPath());
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
  try {
    String action = safe(request.getParameter("action")).trim();
    Path partFolder = parts.partFolder(tenantUuid, caseUuid, docUuid, partUuid);
    if (partFolder == null) throw new IllegalArgumentException("Part folder unavailable.");
    Path tempRoot = partFolder.resolve(".upload_staging");
    Files.createDirectories(tempRoot);
    if ("upload_chunk".equalsIgnoreCase(action)) {
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
  } catch (Exception ex) { error = safe(ex.getMessage()); }
}
if ("1".equals(request.getParameter("uploaded"))) message = "Version file uploaded, verified, and recorded.";
List<part_versions.VersionRec> rows = versions.listAll(tenantUuid, caseUuid, docUuid, partUuid);
document_parts.PartRec part = parts.get(tenantUuid, caseUuid, docUuid, partUuid);
%>
<jsp:include page="header.jsp" />
<section class="card"><h1 style="margin:0;">Part Versions</h1><div class="meta">Part: <strong><%= esc(part == null ? "" : part.label) %></strong></div></section>
<section class="card" style="margin-top:12px;">
<form class="form" method="post" action="<%= ctx %>/versions.jsp?case_uuid=<%= java.net.URLEncoder.encode(caseUuid, java.nio.charset.StandardCharsets.UTF_8) %>&doc_uuid=<%= java.net.URLEncoder.encode(docUuid, java.nio.charset.StandardCharsets.UTF_8) %>&part_uuid=<%= java.net.URLEncoder.encode(partUuid, java.nio.charset.StandardCharsets.UTF_8) %>">
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
<section class="card" style="margin-top:12px;"><table class="table"><thead><tr><th>Version</th><th>Source</th><th>MIME</th><th>Checksum</th><th>Created</th><th>Current</th></tr></thead><tbody>
<% for (part_versions.VersionRec r : rows) { %>
<tr><td><%= esc(r.versionLabel) %></td><td><%= esc(r.source) %></td><td><%= esc(r.mimeType) %></td><td><%= esc(r.checksum) %></td><td><%= esc(r.createdAt) %></td><td><%= r.current ? "Yes" : "" %></td></tr>
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
  var CHUNK_SIZE = 192 * 1024;

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
  function chunkToBase64(chunk) {
    return new Promise(function(resolve, reject) {
      var reader = new FileReader();
      reader.onload = function() {
        var v = String(reader.result || "");
        var idx = v.indexOf(",");
        resolve(idx >= 0 ? v.substring(idx + 1) : v);
      };
      reader.onerror = reject;
      reader.readAsDataURL(chunk);
    });
  }

  form.addEventListener("submit", async function (ev) {
    var file = fileInput && fileInput.files && fileInput.files.length ? fileInput.files[0] : null;
    if (!file) {
      actionInput.value = "create_metadata";
      return;
    }
    ev.preventDefault();
    try {
      var uploadId = (Date.now().toString(36) + "-" + Math.random().toString(36).slice(2, 10)).replace(/[^a-z0-9_-]/gi, "");
      var totalChunks = Math.ceil(file.size / CHUNK_SIZE);
      setStatus("Hashing file...");
      var fileSha = await sha256Hex(file);
      for (var i = 0; i < totalChunks; i++) {
        var start = i * CHUNK_SIZE;
        var end = Math.min(file.size, start + CHUNK_SIZE);
        var chunk = file.slice(start, end);
        var chunkSha = await sha256Hex(chunk);
        var chunkB64 = await chunkToBase64(chunk);
        var body = new URLSearchParams();
        body.set("action", "upload_chunk");
        body.set("upload_id", uploadId);
        body.set("chunk_index", String(i));
        body.set("total_chunks", String(totalChunks));
        body.set("chunk_sha256", chunkSha);
        body.set("chunk_b64", chunkB64);
        setStatus("Uploading chunk " + (i + 1) + " / " + totalChunks + "...");
        var resp = await fetch(form.action, {
          method: "POST",
          headers: { "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8" },
          body: body.toString()
        });
        if (!resp.ok) throw new Error("Chunk upload failed at " + (i + 1) + ".");
      }

      actionInput.value = "commit_upload";
      uploadIdInput.value = uploadId;
      totalChunksInput.value = String(totalChunks);
      uploadFileNameInput.value = file.name || "version.bin";
      fileShaInput.value = fileSha;
      form.elements["checksum"].value = fileSha;
      form.elements["file_size_bytes"].value = String(file.size);
      form.elements["storage_path"].value = "pending://assembled";
      if (!form.elements["source"].value) form.elements["source"].value = "uploaded";
      if (!form.elements["mime_type"].value) form.elements["mime_type"].value = file.type || "application/octet-stream";
      setStatus("Upload complete. Finalizing...");
      form.submit();
    } catch (err) {
      setStatus("Upload failed: " + (err && err.message ? err.message : "unknown error"));
      alert("Version file upload failed. " + (err && err.message ? err.message : ""));
    }
  });
})();
</script>
<jsp:include page="footer.jsp" />
