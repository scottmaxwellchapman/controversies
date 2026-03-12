<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ page import="java.net.URLEncoder" %>
<%@ page import="java.nio.charset.StandardCharsets" %>
<%@ page import="java.nio.file.Files" %>
<%@ page import="java.nio.file.Path" %>
<%@ page import="java.util.ArrayList" %>
<%@ page import="java.util.LinkedHashMap" %>
<%@ page import="java.util.List" %>
<%@ page import="java.util.Locale" %>
<%@ page import="java.util.UUID" %>
<%@ page import="net.familylawandprobate.controversies.document_parts" %>
<%@ page import="net.familylawandprobate.controversies.documents" %>
<%@ page import="net.familylawandprobate.controversies.part_versions" %>
<%@ page import="net.familylawandprobate.controversies.pdf_redaction_service" %>
<%@ include file="security.jspf" %>
<% if (!require_login()) return; %>
<%!
private static final String S_TENANT_UUID = "tenant.uuid";
private static final String CSRF_SESSION_KEY = "CSRF_TOKEN";

private static String safe(String s){ return s == null ? "" : s; }
private static String esc(String s){ return safe(s).replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;"); }
private static String enc(String s){ return URLEncoder.encode(safe(s), StandardCharsets.UTF_8); }
private static String jsq(String s){ return safe(s).replace("\\", "\\\\").replace("\"", "\\\""); }
private static int intOr(String raw, int fallback){
  try { return Integer.parseInt(safe(raw).trim()); } catch (Exception ex) { return fallback; }
}
private static double doubleOr(String raw, double fallback){
  try { return Double.parseDouble(safe(raw).trim()); } catch (Exception ex) { return fallback; }
}
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
private static String displayVersionLabel(part_versions.VersionRec v) {
  if (v == null) return "";
  String label = safe(v.versionLabel).trim();
  if (label.isBlank()) label = "Version";
  String when = safe(v.createdAt).trim();
  if (!when.isBlank()) label = label + " | " + when;
  return label;
}
%>
<%
String ctx = safe(request.getContextPath());
String csrfToken = csrfForRender(request);
String tenantUuid = safe((String)session.getAttribute(S_TENANT_UUID)).trim();
if (tenantUuid.isBlank()) { response.sendRedirect(ctx + "/tenant_login.jsp"); return; }
String actingUser = safe((String)session.getAttribute("user.email")).trim();
if (actingUser.isBlank()) actingUser = safe((String)session.getAttribute("user.uuid")).trim();
if (actingUser.isBlank()) actingUser = "System";

String caseUuid = safe(request.getParameter("case_uuid")).trim();
String docUuid = safe(request.getParameter("doc_uuid")).trim();
String partUuid = safe(request.getParameter("part_uuid")).trim();

double requestedRenderQuality = pdf_redaction_service.normalizePreviewRenderQuality(
  doubleOr(request.getParameter("quality"), 0.70d)
);
String renderQualityParam = String.format(Locale.ROOT, "%.2f", requestedRenderQuality);

part_versions versions = part_versions.defaultStore();
document_parts parts = document_parts.defaultStore();

String error = null;
String message = null;

document_parts.PartRec part = null;
try {
  part = parts.get(tenantUuid, caseUuid, docUuid, partUuid);
} catch (Exception ex) {
  error = safe(ex.getMessage());
}

List<part_versions.VersionRec> allVersions = new ArrayList<part_versions.VersionRec>();
try {
  allVersions = versions.listAll(tenantUuid, caseUuid, docUuid, partUuid);
} catch (Exception ex) {
  error = safe(ex.getMessage());
}

ArrayList<part_versions.VersionRec> pdfVersions = new ArrayList<part_versions.VersionRec>();
LinkedHashMap<String, Path> sourcePathByVersionUuid = new LinkedHashMap<String, Path>();
for (part_versions.VersionRec rec : allVersions) {
  if (!pdf_redaction_service.isPdfVersion(rec)) continue;
  Path p = pdf_redaction_service.resolveStoragePath(rec == null ? "" : rec.storagePath);
  if (!pdf_redaction_service.isPathWithinTenant(p, tenantUuid)) continue;
  if (p == null || !Files.isRegularFile(p)) continue;
  pdfVersions.add(rec);
  sourcePathByVersionUuid.put(safe(rec.uuid).trim(), p);
}

String selectedSourceUuid = safe(request.getParameter("source_version_uuid")).trim();
if (selectedSourceUuid.isBlank()) {
  for (part_versions.VersionRec rec : pdfVersions) {
    if (rec == null || !rec.current) continue;
    selectedSourceUuid = safe(rec.uuid).trim();
    break;
  }
}
if (selectedSourceUuid.isBlank() && !pdfVersions.isEmpty()) {
  selectedSourceUuid = safe(pdfVersions.get(0).uuid).trim();
}
if (!sourcePathByVersionUuid.containsKey(selectedSourceUuid)) selectedSourceUuid = "";

String action = safe(request.getParameter("action")).trim().toLowerCase(Locale.ROOT);
if ("page_image".equals(action)) {
  if (selectedSourceUuid.isBlank()) {
    response.setStatus(404);
    response.setContentType("text/plain; charset=UTF-8");
    response.getWriter().write("Source PDF version not found.");
    return;
  }
  Path selectedPath = sourcePathByVersionUuid.get(selectedSourceUuid);
  pdf_redaction_service.requirePathWithinTenant(selectedPath, tenantUuid, "Source PDF path");
  if (selectedPath == null || !Files.isRegularFile(selectedPath)) {
    response.setStatus(404);
    response.setContentType("text/plain; charset=UTF-8");
    response.getWriter().write("Source PDF file not found.");
    return;
  }
  try {
    int pageIndex = intOr(request.getParameter("page"), 0);
    double imageQuality = pdf_redaction_service.normalizePreviewRenderQuality(
      doubleOr(request.getParameter("quality"), requestedRenderQuality)
    );
    pdf_redaction_service.RenderedPage rendered = pdf_redaction_service.renderPage(selectedPath, pageIndex, imageQuality);
    response.setContentType("image/png");
    response.setHeader("Cache-Control", "private, max-age=600");
    response.setHeader("Vary", "Accept-Encoding");
    try (java.io.OutputStream os = response.getOutputStream()) {
      os.write(rendered.pngBytes);
    }
    return;
  } catch (Exception ex) {
    response.setStatus(500);
    response.setContentType("text/plain; charset=UTF-8");
    response.getWriter().write("Unable to render page image: " + safe(ex.getMessage()));
    return;
  }
}

if ("POST".equalsIgnoreCase(request.getMethod())) {
  String postAction = safe(request.getParameter("action")).trim().toLowerCase(Locale.ROOT);
  if ("commit_redaction".equals(postAction)) {
    try {
      documents.defaultStore().requireEditable(tenantUuid, caseUuid, docUuid, actingUser);

      selectedSourceUuid = safe(request.getParameter("source_version_uuid")).trim();
      if (selectedSourceUuid.isBlank()) throw new IllegalArgumentException("Choose a source PDF version.");
      part_versions.VersionRec sourceVersion = findVersion(pdfVersions, selectedSourceUuid);
      if (sourceVersion == null) throw new IllegalArgumentException("Selected source version is unavailable.");
      Path sourcePath = sourcePathByVersionUuid.get(selectedSourceUuid);
      pdf_redaction_service.requirePathWithinTenant(sourcePath, tenantUuid, "Source PDF path");
      if (sourcePath == null || !Files.isRegularFile(sourcePath)) throw new IllegalArgumentException("Source PDF file is missing.");

      List<pdf_redaction_service.RedactionRectNorm> normalized =
        pdf_redaction_service.parseNormalizedPayload(request.getParameter("redactions_payload"));
      List<pdf_redaction_service.RedactionRectPt> rects = pdf_redaction_service.toPageCoordinates(sourcePath, normalized);
      if (rects.isEmpty()) throw new IllegalArgumentException("Add at least one redaction area before saving.");

      Path partFolder = parts.partFolder(tenantUuid, caseUuid, docUuid, partUuid);
      if (partFolder == null) throw new IllegalArgumentException("Part folder unavailable.");
      pdf_redaction_service.requirePathWithinTenant(partFolder, tenantUuid, "Part folder path");
      Path outputDir = partFolder.resolve("version_files");
      Files.createDirectories(outputDir);
      pdf_redaction_service.requirePathWithinTenant(outputDir, tenantUuid, "Output folder path");

      String sourceFileName = sourcePath.getFileName() == null ? "document.pdf" : sourcePath.getFileName().toString();
      String redactedFileName = pdf_redaction_service.suggestRedactedFileName(sourceFileName);
      String versionUuid = UUID.randomUUID().toString().replace("-", "_");
      Path outputPath = outputDir.resolve(versionUuid + "__" + redactedFileName);
      pdf_redaction_service.requirePathWithinTenant(outputPath, tenantUuid, "Output PDF path");

      pdf_redaction_service.RedactionRun run = pdf_redaction_service.redact(sourcePath, outputPath, rects);
      long bytes = Files.size(outputPath);
      String sha256 = pdf_redaction_service.sha256(outputPath);

      String versionLabel = safe(request.getParameter("version_label")).trim();
      if (versionLabel.isBlank()) {
        String baseLabel = safe(sourceVersion.versionLabel).trim();
        if (baseLabel.isBlank()) baseLabel = "PDF";
        versionLabel = baseLabel + " (Redacted)";
      }

      String notes = safe(request.getParameter("notes")).trim();
      if (!run.usedPdfRedactor) {
        String warn = "Rendered with PDFBox rasterized redaction burn-in.";
        notes = notes.isBlank() ? warn : notes + " " + warn;
      }

      versions.create(
        tenantUuid,
        caseUuid,
        docUuid,
        partUuid,
        versionLabel,
        "redacted",
        "application/pdf",
        sha256,
        String.valueOf(bytes),
        outputPath.toUri().toString(),
        actingUser,
        notes,
        "1".equals(request.getParameter("make_current"))
      );

      response.sendRedirect(
        ctx + "/versions.jsp?case_uuid=" + enc(caseUuid) + "&doc_uuid=" + enc(docUuid) + "&part_uuid=" + enc(partUuid) + "&redacted=1"
      );
      return;
    } catch (Exception ex) {
      error = safe(ex.getMessage());
    }
  }
}

part_versions.VersionRec selectedVersion = findVersion(pdfVersions, selectedSourceUuid);
Path selectedSourcePath = sourcePathByVersionUuid.get(selectedSourceUuid);
int totalPages = 0;
if (selectedSourcePath != null && Files.isRegularFile(selectedSourcePath)) {
  try {
    pdf_redaction_service.PdfInfo info = pdf_redaction_service.inspect(selectedSourcePath);
    totalPages = info == null ? 0 : info.totalPages;
  } catch (Exception ex) {
    error = safe(ex.getMessage());
  }
}

if ("1".equals(request.getParameter("redacted"))) {
  message = "Redacted PDF saved as a new document part version.";
}

int initialPage = intOr(request.getParameter("page"), 1);
if (initialPage < 1) initialPage = 1;
if (totalPages > 0 && initialPage > totalPages) initialPage = totalPages;
%>
<jsp:include page="header.jsp" />

<style>
  .pdf-redact-stack { display:grid; gap:10px; margin-top:10px; }
  .pdf-redact-head { display:flex; justify-content:space-between; align-items:flex-start; gap:10px; flex-wrap:wrap; }
  .pdf-redact-source-row { display:grid; gap:8px; grid-template-columns:minmax(240px,1fr) auto; align-items:end; }
  .pdf-redact-controls { display:grid; gap:10px; }
  .pdf-redact-toolbar { display:grid; gap:8px; grid-template-columns:minmax(0,1fr) auto auto; align-items:end; }
  .pdf-redact-nav { display:flex; gap:8px; flex-wrap:wrap; align-items:center; }
  .pdf-redact-actions { display:flex; gap:8px; flex-wrap:wrap; align-items:center; justify-content:flex-end; }
  .pdf-redact-quality { display:grid; gap:4px; min-width:160px; }
  .pdf-redact-quality label { margin:0; }
  .pdf-redact-nav input[type="number"] { width:95px; margin:0; }
  .pdf-redact-meta-row { display:flex; gap:10px; flex-wrap:wrap; justify-content:space-between; }
  .pdf-redact-view-wrap { border:1px solid var(--border); border-radius:10px; background:var(--surface-2); padding:8px; overflow:auto; }
  .pdf-redact-viewport { position:relative; width:min(100%, 980px); margin:0 auto; }
  .pdf-redact-viewport img { display:block; width:100%; height:auto; background:#fff; border:1px solid var(--border); border-radius:8px; }
  .pdf-redact-viewport img.is-loading { opacity:0.55; filter:grayscale(0.1); }
  .pdf-redact-overlay { position:absolute; inset:0; cursor:crosshair; touch-action:none; }
  .pdf-redact-rect { position:absolute; border:2px solid rgba(220,38,38,0.95); background:rgba(220,38,38,0.24); border-radius:4px; }
  .pdf-redact-rect.is-selected { border-color:rgba(30,64,175,0.95); background:rgba(30,64,175,0.20); }
  .pdf-redact-ghost { position:absolute; border:2px dashed rgba(220,38,38,0.85); background:rgba(220,38,38,0.17); border-radius:4px; pointer-events:none; display:none; }
  .pdf-redact-rect-list { border:1px solid var(--border); border-radius:10px; background:var(--surface); padding:8px; display:grid; gap:6px; max-height:200px; overflow:auto; }
  .pdf-redact-rect-row { display:flex; gap:8px; align-items:center; justify-content:space-between; border:1px solid var(--border); border-radius:8px; padding:6px 8px; background:#fff; }
  .pdf-redact-rect-row.is-selected { border-color:rgba(30,64,175,0.65); box-shadow:0 0 0 1px rgba(30,64,175,0.35) inset; }
  .pdf-redact-rect-row .meta { margin:0; }
  .pdf-redact-empty { border:1px dashed var(--border); border-radius:10px; background:var(--surface); padding:12px; color:var(--muted); }
  .pdf-redact-save-grid { display:grid; gap:8px; grid-template-columns:repeat(3,minmax(0,1fr)); }

  @media (max-width: 940px) {
    .pdf-redact-toolbar { grid-template-columns:1fr; }
    .pdf-redact-actions { justify-content:flex-start; }
  }

  @media (max-width: 760px) {
    .pdf-redact-source-row { grid-template-columns:1fr; }
    .pdf-redact-save-grid { grid-template-columns:1fr; }
  }
</style>

<div class="pdf-redact-stack">
  <section class="card">
    <div class="pdf-redact-head">
      <div>
        <h1 style="margin:0;">PDF Redaction Workspace</h1>
        <div class="meta">Select a document part PDF version, draw redaction boxes, then save a new redacted part version.</div>
      </div>
      <a class="btn btn-ghost" href="<%= ctx %>/versions.jsp?case_uuid=<%= enc(caseUuid) %>&doc_uuid=<%= enc(docUuid) %>&part_uuid=<%= enc(partUuid) %>">Back to Versions</a>
    </div>
    <div class="meta" style="margin-top:6px;">Part: <strong><%= esc(part == null ? "" : part.label) %></strong></div>
    <% if (!safe(message).isBlank()) { %><div class="alert alert-ok" style="margin-top:10px;"><%= esc(message) %></div><% } %>
    <% if (!safe(error).isBlank()) { %><div class="alert alert-error" style="margin-top:10px;"><%= esc(error) %></div><% } %>
  </section>

  <section class="card">
    <% if (pdfVersions.isEmpty()) { %>
      <div class="pdf-redact-empty">No PDF document part versions are currently available for redaction.</div>
    <% } else { %>
      <form class="pdf-redact-source-row" method="get" action="<%= ctx %>/pdf_redact.jsp" id="pdf_source_form">
        <input type="hidden" name="case_uuid" value="<%= esc(caseUuid) %>" />
        <input type="hidden" name="doc_uuid" value="<%= esc(docUuid) %>" />
        <input type="hidden" name="part_uuid" value="<%= esc(partUuid) %>" />
        <input type="hidden" name="quality" id="pdf_quality_hidden_input" value="<%= esc(renderQualityParam) %>" />
        <div>
          <label>Source PDF Version</label>
          <select name="source_version_uuid">
            <% for (part_versions.VersionRec v : pdfVersions) { %>
              <option value="<%= esc(v.uuid) %>" <%= safe(v.uuid).equals(selectedSourceUuid) ? "selected" : "" %>><%= esc(displayVersionLabel(v)) %><%= v.current ? " (Current)" : "" %></option>
            <% } %>
          </select>
        </div>
        <button class="btn" type="submit">Load PDF</button>
      </form>
    <% } %>
  </section>

  <% if (selectedVersion != null && selectedSourcePath != null && totalPages > 0) { %>
    <section class="card pdf-redact-controls">
      <div class="pdf-redact-toolbar">
        <div class="pdf-redact-nav">
          <button type="button" class="btn btn-ghost" id="pdf_prev_btn">Prev</button>
          <button type="button" class="btn btn-ghost" id="pdf_next_btn">Next</button>
          <form id="pdf_goto_form" style="margin:0; display:flex; gap:6px; align-items:center;">
            <label for="pdf_goto_page" style="margin:0;">Go to</label>
            <input type="number" id="pdf_goto_page" min="1" max="<%= totalPages %>" value="<%= initialPage %>" />
            <button type="submit" class="btn btn-ghost">Page</button>
          </form>
        </div>
        <div class="pdf-redact-actions">
          <button type="button" class="btn btn-ghost" id="pdf_undo_btn">Undo</button>
          <button type="button" class="btn btn-ghost" id="pdf_delete_btn">Delete Selected</button>
          <button type="button" class="btn btn-ghost" id="pdf_clear_page_btn">Clear Page</button>
          <button type="button" class="btn btn-ghost" id="pdf_clear_all_btn">Clear All</button>
        </div>
        <div class="pdf-redact-quality">
          <label for="pdf_quality_select">Preview quality</label>
          <select id="pdf_quality_select">
            <option value="0.45" <%= Math.abs(requestedRenderQuality - 0.45d) < 0.001d ? "selected" : "" %>>Fast (45%)</option>
            <option value="0.70" <%= Math.abs(requestedRenderQuality - 0.70d) < 0.001d ? "selected" : "" %>>Balanced (70%)</option>
            <option value="1.00" <%= Math.abs(requestedRenderQuality - 1.00d) < 0.001d ? "selected" : "" %>>High (100%)</option>
          </select>
        </div>
      </div>

      <div class="pdf-redact-meta-row">
        <div class="meta" id="pdf_redact_status">Preparing preview...</div>
        <div class="meta" id="pdf_cursor_meta">Cursor: --</div>
        <div class="meta" id="pdf_selection_meta">Selection: none</div>
      </div>

      <div class="pdf-redact-view-wrap">
        <div class="pdf-redact-viewport" id="pdf_viewport">
          <img id="pdf_page_image" alt="PDF redaction preview page" />
          <div class="pdf-redact-overlay" id="pdf_overlay"></div>
          <div class="pdf-redact-ghost" id="pdf_draw_ghost"></div>
        </div>
      </div>

      <div class="pdf-redact-rect-list" id="pdf_rect_list"></div>
    </section>

    <section class="card">
      <form class="form" id="pdf_commit_form" method="post" action="<%= ctx %>/pdf_redact.jsp?case_uuid=<%= enc(caseUuid) %>&doc_uuid=<%= enc(docUuid) %>&part_uuid=<%= enc(partUuid) %>&source_version_uuid=<%= enc(selectedSourceUuid) %>&quality=<%= enc(renderQualityParam) %>">
        <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
        <input type="hidden" name="action" value="commit_redaction" />
        <input type="hidden" name="source_version_uuid" value="<%= esc(selectedSourceUuid) %>" />
        <input type="hidden" name="quality" id="pdf_commit_quality" value="<%= esc(renderQualityParam) %>" />
        <input type="hidden" name="redactions_payload" id="redactions_payload" value="" />

        <div class="pdf-redact-save-grid">
          <div>
            <label>New Version Label</label>
            <input type="text" name="version_label" value="<%= esc(safe(selectedVersion.versionLabel).isBlank() ? "Redacted PDF" : safe(selectedVersion.versionLabel) + " (Redacted)") %>" />
          </div>
          <div>
            <label>Created By</label>
            <input type="text" value="<%= esc(actingUser) %>" readonly />
          </div>
          <div>
            <label><input type="checkbox" name="make_current" value="1" checked /> Mark as current version</label>
          </div>
        </div>

        <div>
          <label>Notes</label>
          <input type="text" name="notes" placeholder="Optional redaction note" />
        </div>

        <button class="btn" type="submit" id="pdf_commit_btn">Create Redacted Version</button>
      </form>
    </section>

    <script>
    (function () {
      var totalPages = <%= totalPages %>;
      var currentPage = Math.max(0, Math.min(totalPages - 1, <%= Math.max(0, initialPage - 1) %>));
      var imageBase = "<%= ctx %>/pdf_redact.jsp?case_uuid=<%= enc(caseUuid) %>&doc_uuid=<%= enc(docUuid) %>&part_uuid=<%= enc(partUuid) %>&source_version_uuid=<%= enc(selectedSourceUuid) %>&action=page_image";
      var initialRenderQuality = <%= renderQualityParam %>;

      var img = document.getElementById("pdf_page_image");
      var overlay = document.getElementById("pdf_overlay");
      var ghost = document.getElementById("pdf_draw_ghost");
      var rectList = document.getElementById("pdf_rect_list");
      var status = document.getElementById("pdf_redact_status");
      var cursorMeta = document.getElementById("pdf_cursor_meta");
      var selectionMeta = document.getElementById("pdf_selection_meta");
      var payloadInput = document.getElementById("redactions_payload");
      var gotoInput = document.getElementById("pdf_goto_page");
      var qualitySelect = document.getElementById("pdf_quality_select");
      var sourceQualityInput = document.getElementById("pdf_quality_hidden_input");
      var commitQualityInput = document.getElementById("pdf_commit_quality");
      var commitForm = document.getElementById("pdf_commit_form");
      var commitBtn = document.getElementById("pdf_commit_btn");

      var prevBtn = document.getElementById("pdf_prev_btn");
      var nextBtn = document.getElementById("pdf_next_btn");
      var undoBtn = document.getElementById("pdf_undo_btn");
      var deleteBtn = document.getElementById("pdf_delete_btn");
      var clearPageBtn = document.getElementById("pdf_clear_page_btn");
      var clearAllBtn = document.getElementById("pdf_clear_all_btn");
      var gotoForm = document.getElementById("pdf_goto_form");

      var renderQuality = clampQuality(initialRenderQuality);
      var rectsByPage = Object.create(null);
      var selectedRectIndex = -1;
      var history = [];
      var maxHistory = 120;
      var loadSequence = 0;
      var pageUrlCache = Object.create(null);
      var drawState = { active: false, pointerId: null, startX: 0, startY: 0 };

      var draftKey = "pdf-redact:<%= jsq(tenantUuid) %>:<%= jsq(caseUuid) %>:<%= jsq(docUuid) %>:<%= jsq(partUuid) %>:<%= jsq(selectedSourceUuid) %>";
      var shouldClearDraft = <%= "1".equals(request.getParameter("redacted")) ? "true" : "false" %>;
      if (shouldClearDraft) {
        try { sessionStorage.removeItem(draftKey); } catch (ignored) {}
      }

      hydrateDraft();
      setQualityControls(renderQuality);
      updateStatus();
      renderRects();
      loadPage(currentPage);

      prevBtn.addEventListener("click", function () { loadPage(currentPage - 1); });
      nextBtn.addEventListener("click", function () { loadPage(currentPage + 1); });

      undoBtn.addEventListener("click", function () {
        if (history.length <= 0) return;
        var snapshot = history.pop();
        restoreSnapshot(snapshot);
        selectedRectIndex = -1;
        renderRects();
        updateStatus();
        persistDraft();
      });

      deleteBtn.addEventListener("click", function () {
        removeSelectedRect();
      });

      clearPageBtn.addEventListener("click", function () {
        var rows = rectsForPage(currentPage, false);
        if (!rows || rows.length === 0) return;
        pushHistory();
        rectsByPage[pageKey(currentPage)] = [];
        selectedRectIndex = -1;
        renderRects();
        updateStatus();
        persistDraft();
      });

      clearAllBtn.addEventListener("click", function () {
        if (totalRectCount() <= 0) return;
        if (!window.confirm("Clear all redaction rectangles across every page?")) return;
        pushHistory();
        rectsByPage = Object.create(null);
        selectedRectIndex = -1;
        renderRects();
        updateStatus();
        persistDraft();
      });

      gotoForm.addEventListener("submit", function (ev) {
        ev.preventDefault();
        var wanted = parseInt(String(gotoInput.value || "").trim(), 10);
        if (!Number.isFinite(wanted)) return;
        loadPage(wanted - 1);
      });

      qualitySelect.addEventListener("change", function () {
        var nextQuality = clampQuality(parseFloat(String(qualitySelect.value || "").trim()));
        if (Math.abs(nextQuality - renderQuality) < 0.0001) return;
        renderQuality = nextQuality;
        setQualityControls(renderQuality);
        loadPage(currentPage);
      });

      commitForm.addEventListener("submit", function (ev) {
        var payload = encodePayload();
        if (!payload) {
          ev.preventDefault();
          alert("Add at least one redaction area before creating the redacted version.");
          return;
        }
        payloadInput.value = payload;
        commitQualityInput.value = renderQuality.toFixed(2);
        if (commitBtn) {
          commitBtn.disabled = true;
          commitBtn.textContent = "Creating...";
        }
      });

      overlay.addEventListener("pointerdown", beginDraw);
      overlay.addEventListener("pointermove", moveDraw);
      overlay.addEventListener("pointerup", endDraw);
      overlay.addEventListener("pointercancel", cancelDraw);

      document.addEventListener("keydown", function (ev) {
        if (isTypingElement(ev.target)) return;
        if ((ev.ctrlKey || ev.metaKey) && (ev.key === "z" || ev.key === "Z")) {
          ev.preventDefault();
          undoBtn.click();
          return;
        }
        if (ev.key === "ArrowLeft") {
          ev.preventDefault();
          prevBtn.click();
          return;
        }
        if (ev.key === "ArrowRight") {
          ev.preventDefault();
          nextBtn.click();
          return;
        }
        if (ev.key === "Delete" || ev.key === "Backspace") {
          ev.preventDefault();
          removeSelectedRect();
          return;
        }
        if (ev.key === "Escape") {
          ev.preventDefault();
          selectedRectIndex = -1;
          hideGhost();
          renderRects();
        }
      });

      window.addEventListener("beforeunload", function () {
        persistDraft();
      });

      function clampQuality(value) {
        if (!Number.isFinite(value)) return 0.70;
        if (value < 0.25) return 0.25;
        if (value > 1.00) return 1.00;
        return value;
      }

      function setQualityControls(value) {
        var formatted = value.toFixed(2);
        if (qualitySelect) qualitySelect.value = formatted;
        if (sourceQualityInput) sourceQualityInput.value = formatted;
        if (commitQualityInput) commitQualityInput.value = formatted;
      }

      function pageKey(index) {
        return String(index);
      }

      function rectsForPage(index, createIfMissing) {
        var key = pageKey(index);
        if (!rectsByPage[key] && createIfMissing) rectsByPage[key] = [];
        return rectsByPage[key] || [];
      }

      function clamp01(value) {
        if (value < 0) return 0;
        if (value > 1) return 1;
        return value;
      }

      function normalizeRect(rawRect) {
        if (!rawRect) return null;
        var x = clamp01(parseFloat(rawRect.x));
        var y = clamp01(parseFloat(rawRect.y));
        var w = clamp01(parseFloat(rawRect.w));
        var h = clamp01(parseFloat(rawRect.h));
        if (!Number.isFinite(x) || !Number.isFinite(y) || !Number.isFinite(w) || !Number.isFinite(h)) return null;
        if (x + w > 1) w = 1 - x;
        if (y + h > 1) h = 1 - y;
        if (w <= 0 || h <= 0) return null;
        return { x: x, y: y, w: w, h: h };
      }

      function totalRectCount() {
        var keys = Object.keys(rectsByPage);
        var total = 0;
        for (var i = 0; i < keys.length; i++) {
          var rows = rectsByPage[keys[i]];
          if (!rows || !rows.length) continue;
          total += rows.length;
        }
        return total;
      }

      function snapshotRects() {
        return JSON.stringify(rectsByPage);
      }

      function restoreSnapshot(snapshot) {
        var next = Object.create(null);
        if (!snapshot || typeof snapshot !== "string") {
          rectsByPage = next;
          return;
        }
        try {
          var parsed = JSON.parse(snapshot);
          if (!parsed || typeof parsed !== "object") {
            rectsByPage = next;
            return;
          }
          var keys = Object.keys(parsed);
          for (var i = 0; i < keys.length; i++) {
            var key = keys[i];
            var pageIndex = parseInt(key, 10);
            if (!Number.isFinite(pageIndex) || pageIndex < 0 || pageIndex >= totalPages) continue;
            var rows = parsed[key];
            if (!Array.isArray(rows)) continue;
            var clean = [];
            for (var j = 0; j < rows.length; j++) {
              var norm = normalizeRect(rows[j]);
              if (norm) clean.push(norm);
            }
            if (clean.length > 0) next[key] = clean;
          }
        } catch (ignored) {
          rectsByPage = Object.create(null);
          return;
        }
        rectsByPage = next;
      }

      function pushHistory() {
        history.push(snapshotRects());
        if (history.length > maxHistory) {
          history.shift();
        }
      }

      function draftPayload() {
        return snapshotRects();
      }

      function persistDraft() {
        try {
          if (totalRectCount() <= 0) {
            sessionStorage.removeItem(draftKey);
            return;
          }
          sessionStorage.setItem(draftKey, draftPayload());
        } catch (ignored) {
        }
      }

      function hydrateDraft() {
        try {
          var raw = sessionStorage.getItem(draftKey);
          if (!raw) return;
          restoreSnapshot(raw);
        } catch (ignored) {
          rectsByPage = Object.create(null);
        }
      }

      function encodePayload() {
        var rows = [];
        var pageIndexes = Object.keys(rectsByPage)
          .map(function (k) { return parseInt(k, 10); })
          .filter(function (n) { return Number.isFinite(n) && n >= 0 && n < totalPages; })
          .sort(function (a, b) { return a - b; });

        for (var i = 0; i < pageIndexes.length; i++) {
          var page = pageIndexes[i];
          var list = rectsForPage(page, false);
          if (!list || !list.length) continue;
          for (var j = 0; j < list.length; j++) {
            var r = normalizeRect(list[j]);
            if (!r) continue;
            rows.push(page + "," + r.x.toFixed(6) + "," + r.y.toFixed(6) + "," + r.w.toFixed(6) + "," + r.h.toFixed(6));
          }
        }
        return rows.join(";");
      }

      function buildPageUrl(pageIndex) {
        return imageBase + "&page=" + encodeURIComponent(String(pageIndex)) + "&quality=" + encodeURIComponent(renderQuality.toFixed(2));
      }

      function pageCacheKey(pageIndex) {
        return renderQuality.toFixed(2) + ":" + String(pageIndex);
      }

      function prefetchPage(pageIndex) {
        if (pageIndex < 0 || pageIndex >= totalPages) return;
        var key = pageCacheKey(pageIndex);
        if (pageUrlCache[key]) return;
        var url = buildPageUrl(pageIndex);
        var preload = new Image();
        preload.decoding = "async";
        preload.src = url;
        pageUrlCache[key] = url;
      }

      function updateStatus() {
        var pageRows = rectsForPage(currentPage, false);
        var onPage = pageRows ? pageRows.length : 0;
        var all = totalRectCount();
        status.textContent = "Page " + (currentPage + 1) + " of " + totalPages + " | " + onPage + " on this page | " + all + " total | Quality " + Math.round(renderQuality * 100) + "%";
        if (gotoInput) gotoInput.value = String(currentPage + 1);
        prevBtn.disabled = currentPage <= 0;
        nextBtn.disabled = currentPage >= (totalPages - 1);
      }

      function updateCursorMeta(point) {
        if (!point || !Number.isFinite(point.x) || !Number.isFinite(point.y)) {
          cursorMeta.textContent = "Cursor: --";
          return;
        }
        var widthPx = Math.max(1, img.naturalWidth || 1);
        var heightPx = Math.max(1, img.naturalHeight || 1);
        var px = Math.round(point.x * widthPx);
        var py = Math.round(point.y * heightPx);
        cursorMeta.textContent = "Cursor: x=" + px + "px, y=" + py + "px (" + (point.x * 100).toFixed(1) + "%, " + (point.y * 100).toFixed(1) + "%)";
      }

      function updateSelectionMeta() {
        var rows = rectsForPage(currentPage, false);
        if (!rows || selectedRectIndex < 0 || selectedRectIndex >= rows.length) {
          selectionMeta.textContent = "Selection: none";
          return;
        }
        var r = rows[selectedRectIndex];
        var widthPx = Math.max(1, img.naturalWidth || 1);
        var heightPx = Math.max(1, img.naturalHeight || 1);
        var x = Math.round(r.x * widthPx);
        var y = Math.round(r.y * heightPx);
        var w = Math.round(r.w * widthPx);
        var h = Math.round(r.h * heightPx);
        selectionMeta.textContent = "Selection: #" + (selectedRectIndex + 1) + " [x=" + x + ", y=" + y + ", w=" + w + ", h=" + h + "]";
      }

      function selectRect(index) {
        var rows = rectsForPage(currentPage, false);
        if (!rows || rows.length <= 0) {
          selectedRectIndex = -1;
        } else if (index < 0 || index >= rows.length) {
          selectedRectIndex = -1;
        } else {
          selectedRectIndex = index;
        }
        renderRects();
      }

      function removeSelectedRect() {
        var rows = rectsForPage(currentPage, false);
        if (!rows || rows.length <= 0) return;
        if (selectedRectIndex < 0 || selectedRectIndex >= rows.length) return;
        pushHistory();
        rows.splice(selectedRectIndex, 1);
        if (rows.length <= 0) {
          rectsByPage[pageKey(currentPage)] = [];
          selectedRectIndex = -1;
        } else if (selectedRectIndex >= rows.length) {
          selectedRectIndex = rows.length - 1;
        }
        renderRects();
        updateStatus();
        persistDraft();
      }

      function renderRectList() {
        while (rectList.firstChild) rectList.removeChild(rectList.firstChild);
        var rows = rectsForPage(currentPage, false);
        if (!rows || rows.length <= 0) {
          var empty = document.createElement("div");
          empty.className = "meta";
          empty.textContent = "No redactions on this page. Click and drag on the page image to add one.";
          rectList.appendChild(empty);
          return;
        }

        for (var i = 0; i < rows.length; i++) {
          (function (index) {
            var r = rows[index];
            var row = document.createElement("div");
            row.className = "pdf-redact-rect-row" + (index === selectedRectIndex ? " is-selected" : "");

            var summary = document.createElement("div");
            summary.className = "meta";
            var widthPx = Math.max(1, img.naturalWidth || 1);
            var heightPx = Math.max(1, img.naturalHeight || 1);
            var x = Math.round(r.x * widthPx);
            var y = Math.round(r.y * heightPx);
            var w = Math.round(r.w * widthPx);
            var h = Math.round(r.h * heightPx);
            summary.textContent = "#" + (index + 1) + " - x=" + x + ", y=" + y + ", w=" + w + ", h=" + h;

            var actions = document.createElement("div");
            actions.style.display = "flex";
            actions.style.gap = "6px";

            var focusBtn = document.createElement("button");
            focusBtn.type = "button";
            focusBtn.className = "btn btn-ghost";
            focusBtn.textContent = "Select";
            focusBtn.addEventListener("click", function () {
              selectedRectIndex = index;
              renderRects();
            });

            var removeBtn = document.createElement("button");
            removeBtn.type = "button";
            removeBtn.className = "btn btn-ghost";
            removeBtn.textContent = "Remove";
            removeBtn.addEventListener("click", function () {
              selectedRectIndex = index;
              removeSelectedRect();
            });

            actions.appendChild(focusBtn);
            actions.appendChild(removeBtn);
            row.appendChild(summary);
            row.appendChild(actions);

            row.addEventListener("click", function () {
              selectedRectIndex = index;
              renderRects();
            });

            rectList.appendChild(row);
          })(i);
        }
      }

      function renderRects() {
        while (overlay.firstChild) overlay.removeChild(overlay.firstChild);
        var rows = rectsForPage(currentPage, false);
        if (rows && rows.length > 0) {
          for (var i = 0; i < rows.length; i++) {
            (function (index) {
              var r = rows[index];
              var box = document.createElement("div");
              box.className = "pdf-redact-rect" + (index === selectedRectIndex ? " is-selected" : "");
              box.style.left = (r.x * 100).toFixed(4) + "%";
              box.style.top = (r.y * 100).toFixed(4) + "%";
              box.style.width = (r.w * 100).toFixed(4) + "%";
              box.style.height = (r.h * 100).toFixed(4) + "%";
              box.addEventListener("pointerdown", function (ev) {
                ev.preventDefault();
                ev.stopPropagation();
                selectedRectIndex = index;
                renderRects();
              });
              overlay.appendChild(box);
            })(i);
          }
        }
        renderRectList();
        updateSelectionMeta();
      }

      function pointFromEvent(ev) {
        var rect = overlay.getBoundingClientRect();
        if (!rect || rect.width <= 0 || rect.height <= 0) return null;
        var x = (ev.clientX - rect.left) / rect.width;
        var y = (ev.clientY - rect.top) / rect.height;
        return { x: clamp01(x), y: clamp01(y) };
      }

      function drawGhost(x1, y1, x2, y2) {
        var left = Math.min(x1, x2);
        var top = Math.min(y1, y2);
        var width = Math.abs(x2 - x1);
        var height = Math.abs(y2 - y1);
        ghost.style.display = "block";
        ghost.style.left = (left * 100).toFixed(4) + "%";
        ghost.style.top = (top * 100).toFixed(4) + "%";
        ghost.style.width = (width * 100).toFixed(4) + "%";
        ghost.style.height = (height * 100).toFixed(4) + "%";
      }

      function hideGhost() {
        ghost.style.display = "none";
        ghost.style.width = "0";
        ghost.style.height = "0";
      }

      function beginDraw(ev) {
        if (ev.button !== 0) return;
        if (ev.target !== overlay) return;
        var point = pointFromEvent(ev);
        if (!point) return;
        drawState.active = true;
        drawState.pointerId = ev.pointerId;
        drawState.startX = point.x;
        drawState.startY = point.y;
        selectedRectIndex = -1;
        overlay.setPointerCapture(ev.pointerId);
        drawGhost(point.x, point.y, point.x, point.y);
        updateCursorMeta(point);
        ev.preventDefault();
      }

      function moveDraw(ev) {
        var point = pointFromEvent(ev);
        updateCursorMeta(point);
        if (!drawState.active || ev.pointerId !== drawState.pointerId || !point) return;
        drawGhost(drawState.startX, drawState.startY, point.x, point.y);
      }

      function endDraw(ev) {
        if (!drawState.active || ev.pointerId !== drawState.pointerId) return;
        var point = pointFromEvent(ev);
        overlay.releasePointerCapture(ev.pointerId);
        drawState.active = false;
        drawState.pointerId = null;
        if (!point) { hideGhost(); return; }

        var x = Math.min(drawState.startX, point.x);
        var y = Math.min(drawState.startY, point.y);
        var w = Math.abs(point.x - drawState.startX);
        var h = Math.abs(point.y - drawState.startY);
        hideGhost();
        if (w < 0.002 || h < 0.002) return;

        pushHistory();
        var list = rectsForPage(currentPage, true);
        list.push({ x: x, y: y, w: w, h: h });
        selectedRectIndex = list.length - 1;
        renderRects();
        updateStatus();
        persistDraft();
      }

      function cancelDraw() {
        hideGhost();
        drawState.active = false;
        drawState.pointerId = null;
      }

      function isTypingElement(el) {
        if (!el || !el.tagName) return false;
        var tag = el.tagName.toLowerCase();
        return tag === "input" || tag === "textarea" || tag === "select" || !!el.isContentEditable;
      }

      function loadPage(index) {
        if (totalPages <= 0) return;
        currentPage = Math.max(0, Math.min(totalPages - 1, index));
        selectedRectIndex = -1;
        updateStatus();
        renderRects();

        var key = pageCacheKey(currentPage);
        var url = pageUrlCache[key] || buildPageUrl(currentPage);
        pageUrlCache[key] = url;

        var seq = ++loadSequence;
        img.classList.add("is-loading");

        function handleLoaded() {
          if (seq !== loadSequence) return;
          img.classList.remove("is-loading");
          renderRects();
          updateStatus();
          updateCursorMeta(null);
          prefetchPage(currentPage - 1);
          prefetchPage(currentPage + 1);
        }

        img.onload = handleLoaded;
        img.onerror = function () {
          if (seq !== loadSequence) return;
          img.classList.remove("is-loading");
          status.textContent = "Unable to load page image. Try again or reduce quality.";
        };

        if (img.getAttribute("src") !== url) {
          img.setAttribute("src", url);
        } else if (img.complete && img.naturalWidth > 0) {
          handleLoaded();
        }
      }
    })();
    </script>
  <% } %>
</div>

<jsp:include page="footer.jsp" />
