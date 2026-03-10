<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ page import="java.util.ArrayList" %>
<%@ page import="java.util.List" %>
<%@ page import="java.io.InputStream" %>
<%@ page import="java.nio.file.Files" %>
<%@ page import="java.nio.file.Path" %>
<%@ page import="java.nio.file.StandardOpenOption" %>
<%@ page import="java.security.MessageDigest" %>
<%@ page import="java.util.Base64" %>
<%@ page import="java.util.Comparator" %>
<%@ page import="java.util.UUID" %>
<%@ page import="net.familylawandprobate.controversies.document_page_preview" %>
<%@ page import="net.familylawandprobate.controversies.part_versions" %>
<%@ page import="net.familylawandprobate.controversies.document_parts" %>
<%@ page import="net.familylawandprobate.controversies.documents" %>
<%@ page import="net.familylawandprobate.controversies.pdf_redaction_service" %>
<%@ page import="net.familylawandprobate.controversies.pdf_version_background_jobs" %>
<%@ page import="net.familylawandprobate.controversies.texas_pdf_compatibility_checker" %>
<%@ include file="security.jspf" %>
<% if (!require_login()) return; %>
<%!
private static final String S_TENANT_UUID = "tenant.uuid";
private static final String CSRF_SESSION_KEY = "CSRF_TOKEN";
private static final int MAX_UPLOAD_CHUNKS = 12000;
private static String safe(String s){ return s == null ? "" : s; }
private static String esc(String s){ return safe(s).replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;"); }
private static String enc(String s){ return java.net.URLEncoder.encode(safe(s), java.nio.charset.StandardCharsets.UTF_8); }
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
  String v = safe(s).trim().replaceAll("[^A-Za-z0-9.]", "_");
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
private static part_versions.VersionRec findVersion(List<part_versions.VersionRec> rows, String uuid) {
  if (rows == null || rows.isEmpty()) return null;
  String id = safe(uuid).trim();
  if (id.isBlank()) return null;
  for (part_versions.VersionRec r : rows) {
    if (r == null) continue;
    if (id.equals(safe(r.uuid).trim())) return r;
  }
  return null;
}
private static boolean isPreviewRenderable(Path sourcePath, String tenantUuid) {
  try {
    pdf_redaction_service.requirePathWithinTenant(sourcePath, tenantUuid, "Preview file path");
  } catch (Exception ex) {
    return false;
  }
  return sourcePath != null && Files.isRegularFile(sourcePath) && document_page_preview.isRenderable(sourcePath);
}
private static String fileName(Path p){
  if (p == null || p.getFileName() == null) return "";
  return safe(p.getFileName().toString());
}
private static String safeExternalHref(String raw) {
  String v = safe(raw).trim();
  if (v.isBlank()) return "";
  String lower = v.toLowerCase(java.util.Locale.ROOT);
  if (lower.startsWith("https://") || lower.startsWith("http://") || lower.startsWith("mailto:")) return v;
  return "";
}
private static String displayVersionLabel(part_versions.VersionRec rec) {
  String label = safe(rec == null ? "" : rec.versionLabel).trim();
  return label.isBlank() ? "Version" : label;
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
String actingUser = safe((String)session.getAttribute("user.email")).trim();
if (actingUser.isBlank()) actingUser = safe((String)session.getAttribute("user.uuid")).trim();
String caseUuid = safe(request.getParameter("case_uuid")).trim();
String docUuid = safe(request.getParameter("doc_uuid")).trim();
String partUuid = safe(request.getParameter("part_uuid")).trim();
part_versions versions = part_versions.defaultStore();
document_parts parts = document_parts.defaultStore();
documents docs = documents.defaultStore();
documents.DocumentRec doc = docs.get(tenantUuid, caseUuid, docUuid);
boolean docReadOnly = documents.isReadOnly(doc);
String error = null;
String message = null;
if ("POST".equalsIgnoreCase(request.getMethod())) {
  String action = safe(request.getParameter("action")).trim();
  boolean uploadAction = "upload_chunk_bin".equalsIgnoreCase(action) || "upload_chunk".equalsIgnoreCase(action) || "commit_upload".equalsIgnoreCase(action);
  try {
    if (docReadOnly) throw new IllegalStateException(documents.readOnlyMessage(doc));
    Path partFolder = parts.partFolder(tenantUuid, caseUuid, docUuid, partUuid);
    if (partFolder == null) throw new IllegalArgumentException("Part folder unavailable.");
    Path tempRoot = partFolder.resolve(".upload_staging");
    Files.createDirectories(tempRoot);
    if ("queue_ocr".equalsIgnoreCase(action)) {
      String sourceVersionUuid = safe(request.getParameter("source_version_uuid")).trim();
      String createdBy = safe((String)session.getAttribute("user.email")).trim();
      if (createdBy.isBlank()) createdBy = safe((String)session.getAttribute("user.uuid")).trim();
      String jobId = pdf_version_background_jobs.defaultService().enqueueOcr(
        tenantUuid, caseUuid, docUuid, partUuid, sourceVersionUuid, createdBy
      );
      response.sendRedirect(ctx + "/versions.jsp?case_uuid=" + java.net.URLEncoder.encode(caseUuid, java.nio.charset.StandardCharsets.UTF_8) + "&doc_uuid=" + java.net.URLEncoder.encode(docUuid, java.nio.charset.StandardCharsets.UTF_8) + "&part_uuid=" + java.net.URLEncoder.encode(partUuid, java.nio.charset.StandardCharsets.UTF_8) + "&ocr_queued=1&job_id=" + java.net.URLEncoder.encode(jobId, java.nio.charset.StandardCharsets.UTF_8));
      return;
    } else if ("queue_flat".equalsIgnoreCase(action)) {
      String sourceVersionUuid = safe(request.getParameter("source_version_uuid")).trim();
      String createdBy = safe((String)session.getAttribute("user.email")).trim();
      if (createdBy.isBlank()) createdBy = safe((String)session.getAttribute("user.uuid")).trim();
      String jobId = pdf_version_background_jobs.defaultService().enqueueFlatten(
        tenantUuid, caseUuid, docUuid, partUuid, sourceVersionUuid, createdBy
      );
      response.sendRedirect(ctx + "/versions.jsp?case_uuid=" + java.net.URLEncoder.encode(caseUuid, java.nio.charset.StandardCharsets.UTF_8) + "&doc_uuid=" + java.net.URLEncoder.encode(docUuid, java.nio.charset.StandardCharsets.UTF_8) + "&part_uuid=" + java.net.URLEncoder.encode(partUuid, java.nio.charset.StandardCharsets.UTF_8) + "&flat_queued=1&job_id=" + java.net.URLEncoder.encode(jobId, java.nio.charset.StandardCharsets.UTF_8));
      return;
    } else if ("upload_chunk_bin".equalsIgnoreCase(action)) {
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
        actingUser, request.getParameter("notes"), "1".equals(request.getParameter("make_current")));
      response.sendRedirect(ctx + "/versions.jsp?case_uuid=" + java.net.URLEncoder.encode(caseUuid, java.nio.charset.StandardCharsets.UTF_8) + "&doc_uuid=" + java.net.URLEncoder.encode(docUuid, java.nio.charset.StandardCharsets.UTF_8) + "&part_uuid=" + java.net.URLEncoder.encode(partUuid, java.nio.charset.StandardCharsets.UTF_8) + "&uploaded=1");
      return;
    } else {
      versions.create(tenantUuid, caseUuid, docUuid, partUuid,
        request.getParameter("version_label"), request.getParameter("source"), request.getParameter("mime_type"),
        request.getParameter("checksum"), request.getParameter("file_size_bytes"), request.getParameter("storage_path"),
        actingUser, request.getParameter("notes"), "1".equals(request.getParameter("make_current")));
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
if ("1".equals(request.getParameter("ocr_queued"))) message = "OCR job queued in background.";
if ("1".equals(request.getParameter("flat_queued"))) message = "Flatten job queued in background.";
List<part_versions.VersionRec> rows = versions.listAll(tenantUuid, caseUuid, docUuid, partUuid);
String checkPdfVersionUuid = safe(request.getParameter("check_pdf_version_uuid")).trim();
part_versions.VersionRec checkedPdfVersion = null;
texas_pdf_compatibility_checker.Report checkedPdfReport = null;
if (!checkPdfVersionUuid.isBlank()) {
  checkedPdfVersion = findVersion(rows, checkPdfVersionUuid);
  if (checkedPdfVersion == null) {
    if (safe(error).isBlank()) error = "Compatibility check version not found.";
  } else if (!isPdfVersion(checkedPdfVersion)) {
    if (safe(error).isBlank()) error = "Compatibility check is available for PDF versions only.";
  } else {
    try {
      Path checkedPdfPath = pdf_redaction_service.resolveStoragePath(checkedPdfVersion.storagePath);
      pdf_redaction_service.requirePathWithinTenant(checkedPdfPath, tenantUuid, "Compatibility check file path");
      if (checkedPdfPath == null || !Files.isRegularFile(checkedPdfPath)) {
        if (safe(error).isBlank()) error = "Compatibility check file not found.";
      } else {
        checkedPdfReport = texas_pdf_compatibility_checker.evaluate(checkedPdfPath);
      }
    } catch (Exception ex) {
      if (safe(error).isBlank()) error = "Unable to run compatibility check: " + safe(ex.getMessage());
    }
  }
}
String viewVersionUuid = safe(request.getParameter("view_version_uuid")).trim();
int viewPageParam = intOrDefault(request.getParameter("page"), 0);
int gotoPageParam = intOrDefault(request.getParameter("goto_page"), -1);
if (gotoPageParam > 0) viewPageParam = gotoPageParam - 1;
if (viewPageParam < 0) viewPageParam = 0;

part_versions.VersionRec viewedVersion = findVersion(rows, viewVersionUuid);
Path viewedVersionPath = null;
document_page_preview.RenderedPage viewedPage = null;
if (!viewVersionUuid.isBlank()) {
  if (viewedVersion == null) {
    if (safe(error).isBlank()) error = "Preview version not found.";
  } else {
    try {
      viewedVersionPath = pdf_redaction_service.resolveStoragePath(viewedVersion.storagePath);
      pdf_redaction_service.requirePathWithinTenant(viewedVersionPath, tenantUuid, "Preview file path");
      if (viewedVersionPath == null || !Files.isRegularFile(viewedVersionPath)) {
        if (safe(error).isBlank()) error = "Preview file not found.";
      } else if (!document_page_preview.isRenderable(viewedVersionPath)) {
        if (safe(error).isBlank()) error = "Preview supports PDF, DOCX, DOC, RTF, TXT, and ODT files.";
      } else {
        viewedPage = document_page_preview.renderPage(viewedVersionPath, viewPageParam);
      }
    } catch (Exception ex) {
      if (safe(error).isBlank()) error = "Unable to render preview: " + safe(ex.getMessage());
    }
  }
}
document_parts.PartRec part = parts.get(tenantUuid, caseUuid, docUuid, partUuid);
boolean pdfsandwichAvailable = false;
try { pdfsandwichAvailable = pdf_version_background_jobs.defaultService().isPdfSandwichAvailable(); } catch (Exception ignored) {}
%>
<jsp:include page="header.jsp" />
<style>
  .versions-preview-nav { display:flex; gap:8px; align-items:center; flex-wrap:wrap; }
  .versions-preview-meta { color:var(--muted); font-size:12px; }
  .versions-preview-jump { display:flex; gap:6px; align-items:center; margin:0; }
  .versions-preview-jump input[type="number"] { width:90px; margin:0; }
  .versions-preview-img-wrap { border:1px solid var(--border); border-radius:10px; background:var(--surface-2); padding:8px; overflow:auto; margin-top:8px; }
  .versions-preview-img-wrap img { display:block; width:100%; height:auto; background:#fff; border:1px solid var(--border); border-radius:8px; }
  .versions-preview-copy-btn { min-width:34px; padding:6px 8px; line-height:1; display:inline-flex; align-items:center; justify-content:center; }
  .versions-preview-copy-btn svg { width:15px; height:15px; display:block; fill:currentColor; }
  .versions-preview-copy-status { min-height:1em; }
  .versions-preview-hidden-text { position:absolute; left:-9999px; width:1px; height:1px; opacity:0; pointer-events:none; }
  .tx-compat-summary { display:flex; gap:10px; flex-wrap:wrap; align-items:center; }
  .tx-compat-badge { display:inline-flex; align-items:center; border:1px solid var(--border); border-radius:999px; padding:2px 8px; font-size:12px; font-weight:600; text-transform:uppercase; }
  .tx-compat-badge.pass { color:#065f46; border-color:#34d399; background:#ecfdf5; }
  .tx-compat-badge.warn { color:#92400e; border-color:#f59e0b; background:#fffbeb; }
  .tx-compat-badge.fail { color:#991b1b; border-color:#f87171; background:#fef2f2; }
  .tx-compat-badge.info { color:#1e3a8a; border-color:#93c5fd; background:#eff6ff; }
  .tx-compat-req { font-weight:600; color:var(--text); }
  .tx-compat-detail { color:var(--muted); font-size:12px; margin-top:2px; }
  .versions-preview-doc-nav { margin-top:8px; border:1px solid var(--border); border-radius:10px; background:var(--surface); padding:6px 8px; }
  .versions-preview-doc-nav summary { cursor:pointer; font-weight:600; color:var(--text); }
  .versions-preview-doc-nav-list { margin-top:8px; display:grid; gap:6px; }
  .versions-preview-doc-nav-row { display:grid; gap:8px; grid-template-columns:minmax(0, 1fr) auto; align-items:center; border:1px solid var(--border); border-radius:8px; background:var(--surface-2); padding:6px 8px; }
  .versions-preview-doc-nav-kind { display:inline-block; margin-right:6px; padding:1px 6px; border:1px solid var(--border); border-radius:999px; font-size:11px; color:var(--muted); background:var(--surface); text-transform:capitalize; }
  .versions-preview-doc-nav-label { font-size:13px; color:var(--text); word-break:break-word; }
  .versions-preview-doc-nav-target { color:var(--muted); font-size:11px; word-break:break-word; margin-top:3px; }
  .versions-preview-doc-nav-actions { display:flex; gap:6px; align-items:center; flex-wrap:wrap; }
  .versions-preview-doc-nav-page { color:var(--muted); font-size:11px; }
  @media (max-width: 700px) {
    .versions-preview-jump input[type="number"] { width:70px; }
    .versions-preview-doc-nav-row { grid-template-columns:minmax(0, 1fr); }
  }
</style>
<section class="card">
  <div style="display:flex; gap:10px; justify-content:space-between; align-items:flex-start; flex-wrap:wrap;">
    <div>
      <h1 style="margin:0;">Part Versions</h1>
      <div class="meta">Part: <strong><%= esc(part == null ? "" : part.label) %></strong></div>
      <% if (!pdfsandwichAvailable) { %>
      <div class="meta">OCR unavailable: <code>pdfsandwich</code> was not found on this server.</div>
      <% } %>
    </div>
    <% if (!docReadOnly) { %>
    <a class="btn btn-ghost" href="<%= ctx %>/pdf_redact.jsp?case_uuid=<%= java.net.URLEncoder.encode(caseUuid, java.nio.charset.StandardCharsets.UTF_8) %>&doc_uuid=<%= java.net.URLEncoder.encode(docUuid, java.nio.charset.StandardCharsets.UTF_8) %>&part_uuid=<%= java.net.URLEncoder.encode(partUuid, java.nio.charset.StandardCharsets.UTF_8) %>">Redact a PDF Version</a>
    <% } %>
  </div>
</section>
<% if (docReadOnly) { %>
<section class="card" style="margin-top:12px;"><div class="alert alert-error"><%= esc(documents.readOnlyMessage(doc)) %></div></section>
<% } %>
<section class="card" style="margin-top:12px;">
<form class="form" method="post" action="<%= ctx %>/versions.jsp?case_uuid=<%= java.net.URLEncoder.encode(caseUuid, java.nio.charset.StandardCharsets.UTF_8) %>&doc_uuid=<%= java.net.URLEncoder.encode(docUuid, java.nio.charset.StandardCharsets.UTF_8) %>&part_uuid=<%= java.net.URLEncoder.encode(partUuid, java.nio.charset.StandardCharsets.UTF_8) %>">
<input type="hidden" name="csrfToken" id="csrf_token" value="<%= esc(csrfToken) %>" />
<input type="hidden" name="action" id="version_form_action" value="create_metadata" />
<input type="hidden" name="upload_id" id="upload_id" value="" />
<input type="hidden" name="total_chunks" id="total_chunks" value="" />
<input type="hidden" name="upload_file_name" id="upload_file_name" value="" />
<input type="hidden" name="file_sha256" id="file_sha256" value="" />
<div class="grid grid-3"><div><label>Version Label</label><input type="text" name="version_label" required <%= docReadOnly ? "disabled" : "" %> /></div><div><label>Source</label><input type="text" name="source" placeholder="uploaded/ocr/generated" <%= docReadOnly ? "disabled" : "" %> /></div><div><label>MIME Type</label><input type="text" name="mime_type" placeholder="application/pdf" <%= docReadOnly ? "disabled" : "" %>/></div></div>
<div class="grid grid-3"><div><label>Checksum</label><input type="text" name="checksum" <%= docReadOnly ? "disabled" : "" %>/></div><div><label>File Size (bytes)</label><input type="text" name="file_size_bytes" <%= docReadOnly ? "disabled" : "" %>/></div><div><label>Storage Path</label><input type="text" name="storage_path" placeholder="vault://..." <%= docReadOnly ? "disabled" : "" %>/></div></div>
<div class="grid grid-3"><div><label>Version File (chunk upload)</label><input type="file" id="version_upload_file" <%= docReadOnly ? "disabled" : "" %> /></div><div><label>Chunk Status</label><div id="chunk_upload_status" class="meta"><%= docReadOnly ? "Read-only document." : "No file selected." %></div></div><div></div></div>
<div class="grid grid-3"><div><label>Created By</label><input type="text" name="created_by" value="<%= esc(actingUser) %>" readonly <%= docReadOnly ? "disabled" : "" %>/></div><div><label>Notes</label><input type="text" name="notes" <%= docReadOnly ? "disabled" : "" %>/></div><div><label><input type="checkbox" name="make_current" value="1" checked <%= docReadOnly ? "disabled" : "" %> /> Mark current</label></div></div>
<button class="btn" type="submit" <%= docReadOnly ? "disabled" : "" %>>Add Version</button>
</form>
<% if (!safe(message).isBlank()) { %><div class="alert" style="margin-top:10px;"><%= esc(message) %></div><% } %>
<% if (!safe(error).isBlank()) { %><div class="alert alert-error" style="margin-top:10px;"><%= esc(error) %></div><% } %>
</section>
<section class="card" style="margin-top:12px;"><table class="table"><thead><tr><th>Version</th><th>Source</th><th>MIME</th><th>Checksum</th><th>Created</th><th>Current</th><th>Actions</th></tr></thead><tbody>
<% for (part_versions.VersionRec r : rows) { %>
<tr>
  <td><%= esc(r.versionLabel) %></td>
  <td><%= esc(r.source) %></td>
  <td><%= esc(r.mimeType) %></td>
  <td><%= esc(r.checksum) %></td>
  <td><%= esc(r.createdAt) %></td>
  <td><%= r.current ? "Yes" : "" %></td>
  <td>
    <%
      Path rowSourcePath = pdf_redaction_service.resolveStoragePath(safe(r.storagePath));
      boolean rowPreviewRenderable = isPreviewRenderable(rowSourcePath, tenantUuid);
    %>
    <% if (rowPreviewRenderable) { %>
      <a class="btn btn-ghost" href="<%= ctx %>/versions.jsp?case_uuid=<%= enc(caseUuid) %>&doc_uuid=<%= enc(docUuid) %>&part_uuid=<%= enc(partUuid) %>&view_version_uuid=<%= enc(safe(r.uuid)) %>&page=0">Preview</a>
    <% } %>
    <% if (isPdfVersion(r)) { %>
      <a class="btn btn-ghost" href="<%= ctx %>/versions.jsp?case_uuid=<%= enc(caseUuid) %>&doc_uuid=<%= enc(docUuid) %>&part_uuid=<%= enc(partUuid) %>&check_pdf_version_uuid=<%= enc(safe(r.uuid)) %>">TX Check</a>
    <% } %>
    <% if (!docReadOnly && isPdfVersion(r)) { %>
      <a class="btn btn-ghost" href="<%= ctx %>/pdf_redact.jsp?case_uuid=<%= java.net.URLEncoder.encode(caseUuid, java.nio.charset.StandardCharsets.UTF_8) %>&doc_uuid=<%= java.net.URLEncoder.encode(docUuid, java.nio.charset.StandardCharsets.UTF_8) %>&part_uuid=<%= java.net.URLEncoder.encode(partUuid, java.nio.charset.StandardCharsets.UTF_8) %>&source_version_uuid=<%= java.net.URLEncoder.encode(safe(r.uuid), java.nio.charset.StandardCharsets.UTF_8) %>">Redact</a>
      <form method="post" action="<%= ctx %>/versions.jsp?case_uuid=<%= java.net.URLEncoder.encode(caseUuid, java.nio.charset.StandardCharsets.UTF_8) %>&doc_uuid=<%= java.net.URLEncoder.encode(docUuid, java.nio.charset.StandardCharsets.UTF_8) %>&part_uuid=<%= java.net.URLEncoder.encode(partUuid, java.nio.charset.StandardCharsets.UTF_8) %>" style="display:inline;">
        <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
        <input type="hidden" name="action" value="queue_ocr" />
        <input type="hidden" name="source_version_uuid" value="<%= esc(safe(r.uuid)) %>" />
        <button class="btn btn-ghost" type="submit" <%= pdfsandwichAvailable ? "" : "disabled title=\"pdfsandwich not found on server\"" %>>OCR</button>
      </form>
      <form method="post" action="<%= ctx %>/versions.jsp?case_uuid=<%= java.net.URLEncoder.encode(caseUuid, java.nio.charset.StandardCharsets.UTF_8) %>&doc_uuid=<%= java.net.URLEncoder.encode(docUuid, java.nio.charset.StandardCharsets.UTF_8) %>&part_uuid=<%= java.net.URLEncoder.encode(partUuid, java.nio.charset.StandardCharsets.UTF_8) %>" style="display:inline;">
        <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
        <input type="hidden" name="action" value="queue_flat" />
        <input type="hidden" name="source_version_uuid" value="<%= esc(safe(r.uuid)) %>" />
        <button class="btn btn-ghost" type="submit">FLAT</button>
      </form>
    <% } %>
  </td>
</tr>
<% } %>
</tbody></table></section>
<% if (checkedPdfReport != null && checkedPdfVersion != null) { %>
<section class="card" style="margin-top:12px;">
  <div class="section-head">
    <div>
      <h2 style="margin:0;">Texas PDF Compatibility Check</h2>
      <div class="meta">
        Version: <strong><%= esc(displayVersionLabel(checkedPdfVersion)) %></strong>
        |
        Standard:
        <a href="<%= esc(checkedPdfReport.standardsUrl) %>" target="_blank" rel="noopener noreferrer"><%= esc(checkedPdfReport.standardsLabel) %></a>
      </div>
    </div>
    <div class="tx-compat-summary">
      <% if (checkedPdfReport.compatible) { %>
        <span class="tx-compat-badge pass">Compatible</span>
      <% } else { %>
        <span class="tx-compat-badge fail">Not Compatible</span>
      <% } %>
      <span class="meta">Fails: <strong><%= checkedPdfReport.failCount %></strong></span>
      <span class="meta">Warnings: <strong><%= checkedPdfReport.warnCount %></strong></span>
      <span class="meta">Pages: <strong><%= checkedPdfReport.pageCount %></strong></span>
      <span class="meta">PDF v<strong><%= esc(checkedPdfReport.pdfVersion) %></strong></span>
    </div>
  </div>
  <div class="meta" style="margin-top:6px;">Checked at: <%= esc(checkedPdfReport.checkedAt) %> | File: <%= esc(checkedPdfReport.fileName) %></div>
  <div class="table-wrap" style="margin-top:10px;">
    <table class="table">
      <thead>
        <tr>
          <th style="width:110px;">Rule</th>
          <th style="width:130px;">Status</th>
          <th>Requirement + Result</th>
        </tr>
      </thead>
      <tbody>
      <% for (texas_pdf_compatibility_checker.CheckResult c : checkedPdfReport.checks) {
           if (c == null) continue;
           String status = safe(c.status == null ? "" : c.status.name()).toLowerCase(java.util.Locale.ROOT);
      %>
        <tr>
          <td><code><%= esc(c.code) %></code></td>
          <td><span class="tx-compat-badge <%= esc(status) %>"><%= esc(status) %></span></td>
          <td>
            <div class="tx-compat-req"><%= esc(c.requirement) %></div>
            <div class="tx-compat-detail"><%= esc(c.detail) %></div>
          </td>
        </tr>
      <% } %>
      </tbody>
    </table>
  </div>
</section>
<% } %>
<% if (viewedPage != null && viewedVersion != null && !safe(viewedPage.base64Png).isBlank()) {
     int viewedPageOne = viewedPage.pageIndex + 1;
     String viewedTotalLabel = viewedPage.totalKnown ? String.valueOf(viewedPage.totalPages) : (viewedPage.totalPages + "+");
     String gotoMaxAttr = viewedPage.totalKnown ? ("max=\"" + viewedPage.totalPages + "\"") : "";
     String viewedFileName = fileName(viewedVersionPath);
     ArrayList<document_page_preview.NavigationEntry> navEntries = viewedPage.navigation == null
       ? new ArrayList<document_page_preview.NavigationEntry>()
       : viewedPage.navigation;
     int navDisplayLimit = 120;
     int navShown = Math.min(navEntries.size(), navDisplayLimit);
%>
<section class="card" style="margin-top:12px;">
  <div class="section-head">
    <div>
      <h2 style="margin:0;"><%= esc(displayVersionLabel(viewedVersion)) %></h2>
      <div class="versions-preview-meta">
        <%= esc(viewedFileName) %> |
        Page <strong><%= viewedPageOne %></strong> of <strong><%= esc(viewedTotalLabel) %></strong>
        <% if (!safe(viewedPage.engine).isBlank()) { %> | Engine: <%= esc(viewedPage.engine) %><% } %>
      </div>
    </div>
    <div class="versions-preview-nav">
      <% if (viewedPage.hasPrev) { %>
        <a class="btn btn-ghost" href="<%= ctx %>/versions.jsp?case_uuid=<%= enc(caseUuid) %>&doc_uuid=<%= enc(docUuid) %>&part_uuid=<%= enc(partUuid) %>&view_version_uuid=<%= enc(safe(viewedVersion.uuid)) %>&page=<%= viewedPage.pageIndex - 1 %>">Previous</a>
      <% } %>
      <% if (viewedPage.hasNext) { %>
        <a class="btn btn-ghost" href="<%= ctx %>/versions.jsp?case_uuid=<%= enc(caseUuid) %>&doc_uuid=<%= enc(docUuid) %>&part_uuid=<%= enc(partUuid) %>&view_version_uuid=<%= enc(safe(viewedVersion.uuid)) %>&page=<%= viewedPage.pageIndex + 1 %>">Next</a>
      <% } %>
      <form method="get" action="<%= ctx %>/versions.jsp" class="versions-preview-jump">
        <input type="hidden" name="case_uuid" value="<%= esc(caseUuid) %>" />
        <input type="hidden" name="doc_uuid" value="<%= esc(docUuid) %>" />
        <input type="hidden" name="part_uuid" value="<%= esc(partUuid) %>" />
        <input type="hidden" name="view_version_uuid" value="<%= esc(safe(viewedVersion.uuid)) %>" />
        <label style="margin:0;">
          <span class="versions-preview-meta">Page</span>
          <input type="number" name="goto_page" min="1" <%= gotoMaxAttr %> value="<%= viewedPageOne %>" />
        </label>
        <button class="btn btn-ghost" type="submit">Go</button>
      </form>
      <button type="button" class="btn btn-ghost versions-preview-copy-btn" id="btnCopyVersionPageText" title="Copy plain text of this page" aria-label="Copy plain text of this page">
        <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
          <path d="M16 1H6c-1.66 0-3 1.34-3 3v12h2V4c0-.55.45-1 1-1h10V1zm3 4H10c-1.66 0-3 1.34-3 3v13c0 1.66 1.34 3 3 3h9c1.66 0 3-1.34 3-3V8c0-1.66-1.34-3-3-3zm1 16c0 .55-.45 1-1 1h-9c-.55 0-1-.45-1-1V8c0-.55.45-1 1-1h9c.55 0 1 .45 1 1v13z"/>
        </svg>
      </button>
      <span id="versionsPreviewCopyStatus" class="versions-preview-meta versions-preview-copy-status" aria-live="polite"></span>
      <a class="btn btn-ghost" href="<%= ctx %>/versions.jsp?case_uuid=<%= enc(caseUuid) %>&doc_uuid=<%= enc(docUuid) %>&part_uuid=<%= enc(partUuid) %>">Close Preview</a>
    </div>
  </div>
  <textarea id="versionsPreviewHiddenPageText" class="versions-preview-hidden-text" aria-hidden="true" tabindex="-1"><%= esc(safe(viewedPage.pageText)) %></textarea>
  <% if (!safe(viewedPage.warning).isBlank()) { %>
    <div class="alert alert-warn" style="margin-top:8px;"><%= esc(viewedPage.warning) %></div>
  <% } %>
  <details class="versions-preview-doc-nav">
    <summary>Document Navigation (<%= navEntries.size() %>)</summary>
    <div class="versions-preview-doc-nav-list">
      <% if (navEntries.isEmpty()) { %>
        <div class="meta">No built-in headings, bookmarks, or links were detected for this file.</div>
      <% } else { %>
        <% for (int i = 0; i < navShown; i++) {
             document_page_preview.NavigationEntry n = navEntries.get(i);
             if (n == null) continue;
             String nType = safe(n.type).toLowerCase(java.util.Locale.ROOT);
             String nLabel = safe(n.label);
             int nPageOne = n.pageIndex >= 0 ? (n.pageIndex + 1) : -1;
             String nTarget = safe(n.target);
             String externalHref = n.external ? safeExternalHref(nTarget) : "";
        %>
          <div class="versions-preview-doc-nav-row">
            <div>
              <div class="versions-preview-doc-nav-label">
                <span class="versions-preview-doc-nav-kind"><%= esc(nType.isBlank() ? "entry" : nType) %></span>
                <%= esc(nLabel.isBlank() ? "Untitled navigation entry" : nLabel) %>
              </div>
              <% if (!nTarget.isBlank()) { %>
                <div class="versions-preview-doc-nav-target"><%= esc(nTarget) %></div>
              <% } %>
            </div>
            <div class="versions-preview-doc-nav-actions">
              <% if (nPageOne > 0) { %>
                <a class="btn btn-ghost" href="<%= ctx %>/versions.jsp?case_uuid=<%= enc(caseUuid) %>&doc_uuid=<%= enc(docUuid) %>&part_uuid=<%= enc(partUuid) %>&view_version_uuid=<%= enc(safe(viewedVersion.uuid)) %>&page=<%= n.pageIndex %>">Go</a>
                <span class="versions-preview-doc-nav-page">P.<%= nPageOne %></span>
              <% } %>
              <% if (!externalHref.isBlank()) { %>
                <a class="btn btn-ghost" href="<%= esc(externalHref) %>" target="_blank" rel="noopener noreferrer">Open Link</a>
              <% } %>
            </div>
          </div>
        <% } %>
        <% if (navEntries.size() > navDisplayLimit) { %>
          <div class="versions-preview-meta">Showing first <%= navDisplayLimit %> entries.</div>
        <% } %>
      <% } %>
    </div>
  </details>
  <div class="versions-preview-img-wrap">
    <img src="data:image/png;base64,<%= esc(viewedPage.base64Png) %>" alt="Document page preview" />
  </div>
</section>
<% } %>
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
  var documentReadOnly = <%= docReadOnly ? "true" : "false" %>;

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
  if (!documentReadOnly) {
    setStatus("No file selected. Transport: " + uploadTransportVersion + ".");
  }

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
    if (documentReadOnly) {
      ev.preventDefault();
      setStatus("Upload blocked: read-only Clio-synced document.");
      alert("This document is synced from Clio and is read-only. Edit it in Clio.");
      return;
    }
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

(function () {
  var copyBtn = document.getElementById("btnCopyVersionPageText");
  if (!copyBtn) return;
  var textEl = document.getElementById("versionsPreviewHiddenPageText");
  var statusEl = document.getElementById("versionsPreviewCopyStatus");

  function setStatus(message, isError) {
    if (!statusEl) return;
    statusEl.textContent = message || "";
    statusEl.style.color = isError ? "var(--danger, #b91c1c)" : "var(--muted)";
  }

  async function copyPageText() {
    var value = (textEl && typeof textEl.value === "string") ? textEl.value : "";
    if (!value.trim()) {
      setStatus("No page text available.", true);
      return;
    }
    try {
      if (navigator.clipboard && window.isSecureContext) {
        await navigator.clipboard.writeText(value);
      } else {
        var temp = document.createElement("textarea");
        temp.value = value;
        temp.setAttribute("readonly", "readonly");
        temp.style.position = "absolute";
        temp.style.left = "-9999px";
        document.body.appendChild(temp);
        temp.select();
        var ok = document.execCommand("copy");
        document.body.removeChild(temp);
        if (!ok) throw new Error("copy command failed");
      }
      setStatus("Page text copied.", false);
    } catch (err) {
      setStatus("Clipboard copy is blocked by browser permissions.", true);
    }
  }

  copyBtn.addEventListener("click", function () { void copyPageText(); });
})();
</script>
<jsp:include page="footer.jsp" />
