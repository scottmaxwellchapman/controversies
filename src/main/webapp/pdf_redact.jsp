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
private static int intOr(String raw, int fallback){
  try { return Integer.parseInt(safe(raw).trim()); } catch (Exception ex) { return fallback; }
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
  if (!when.isBlank()) label = label + " \u00b7 " + when;
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

String caseUuid = safe(request.getParameter("case_uuid")).trim();
String docUuid = safe(request.getParameter("doc_uuid")).trim();
String partUuid = safe(request.getParameter("part_uuid")).trim();

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
    pdf_redaction_service.RenderedPage rendered = pdf_redaction_service.renderPage(selectedPath, pageIndex);
    response.setContentType("image/png");
    response.setHeader("Cache-Control", "private, max-age=30");
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
      Path outputDir = partFolder.resolve("version_files");
      Files.createDirectories(outputDir);

      String redactedFileName = pdf_redaction_service.suggestRedactedFileName(sourcePath.getFileName() == null ? "document.pdf" : sourcePath.getFileName().toString());
      String versionUuid = UUID.randomUUID().toString();
      Path outputPath = outputDir.resolve(versionUuid + "__" + redactedFileName);

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
  .pdf-redact-controls { display:grid; gap:10px; grid-template-columns:1fr; }
  .pdf-redact-toolbar { display:flex; gap:8px; flex-wrap:wrap; align-items:center; }
  .pdf-redact-toolbar .meta { margin-left:auto; }
  .pdf-redact-nav { display:flex; gap:8px; flex-wrap:wrap; align-items:center; }
  .pdf-redact-nav input[type="number"] { width:90px; margin:0; }
  .pdf-redact-view-wrap { border:1px solid var(--border); border-radius:10px; background:var(--surface-2); padding:8px; overflow:auto; }
  .pdf-redact-viewport { position:relative; width:min(100%, 980px); margin:0 auto; }
  .pdf-redact-viewport img { display:block; width:100%; height:auto; background:#fff; border:1px solid var(--border); border-radius:8px; }
  .pdf-redact-overlay { position:absolute; inset:0; cursor:crosshair; touch-action:none; }
  .pdf-redact-rect { position:absolute; border:2px solid rgba(220,38,38,0.95); background:rgba(220,38,38,0.24); border-radius:4px; pointer-events:none; }
  .pdf-redact-ghost { position:absolute; border:2px dashed rgba(220,38,38,0.85); background:rgba(220,38,38,0.17); border-radius:4px; pointer-events:none; display:none; }
  .pdf-redact-empty { border:1px dashed var(--border); border-radius:10px; background:var(--surface); padding:12px; color:var(--muted); }
  .pdf-redact-source-row { display:grid; gap:8px; grid-template-columns:minmax(0,1fr) auto; align-items:end; }
  .pdf-redact-save-grid { display:grid; gap:8px; grid-template-columns:repeat(3,minmax(0,1fr)); }
  @media (max-width: 760px) {
    .pdf-redact-source-row { grid-template-columns:1fr; }
    .pdf-redact-save-grid { grid-template-columns:1fr; }
    .pdf-redact-toolbar .meta { margin-left:0; width:100%; }
  }
</style>

<div class="pdf-redact-stack">
  <section class="card">
    <div class="pdf-redact-head">
      <div>
        <h1 style="margin:0;">PDF Redaction Workspace</h1>
        <div class="meta">Select an existing document part PDF version, apply redaction boxes, then save a new redacted version.</div>
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
      <form class="pdf-redact-source-row" method="get" action="<%= ctx %>/pdf_redact.jsp">
        <input type="hidden" name="case_uuid" value="<%= esc(caseUuid) %>" />
        <input type="hidden" name="doc_uuid" value="<%= esc(docUuid) %>" />
        <input type="hidden" name="part_uuid" value="<%= esc(partUuid) %>" />
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
        <button type="button" class="btn btn-ghost" id="pdf_undo_btn">Undo</button>
        <button type="button" class="btn btn-ghost" id="pdf_clear_page_btn">Clear Page</button>
        <button type="button" class="btn btn-ghost" id="pdf_clear_all_btn">Clear All</button>
        <div class="meta" id="pdf_redact_status">0 redactions</div>
      </div>
      <div class="pdf-redact-view-wrap">
        <div class="pdf-redact-viewport" id="pdf_viewport">
          <img id="pdf_page_image" alt="PDF redaction preview page" />
          <div class="pdf-redact-overlay" id="pdf_overlay"></div>
          <div class="pdf-redact-ghost" id="pdf_draw_ghost"></div>
        </div>
      </div>
    </section>

    <section class="card">
      <form class="form" id="pdf_commit_form" method="post" action="<%= ctx %>/pdf_redact.jsp?case_uuid=<%= enc(caseUuid) %>&doc_uuid=<%= enc(docUuid) %>&part_uuid=<%= enc(partUuid) %>&source_version_uuid=<%= enc(selectedSourceUuid) %>">
        <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
        <input type="hidden" name="action" value="commit_redaction" />
        <input type="hidden" name="source_version_uuid" value="<%= esc(selectedSourceUuid) %>" />
        <input type="hidden" name="redactions_payload" id="redactions_payload" value="" />
        <div class="pdf-redact-save-grid">
          <div>
            <label>New Version Label</label>
            <input type="text" name="version_label" value="<%= esc(safe(selectedVersion.versionLabel).isBlank() ? "Redacted PDF" : safe(selectedVersion.versionLabel) + " (Redacted)") %>" />
          </div>
          <div>
            <label>Created By</label>
            <input type="text" name="created_by" value="<%= esc(actingUser) %>" readonly />
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

      var img = document.getElementById("pdf_page_image");
      var overlay = document.getElementById("pdf_overlay");
      var ghost = document.getElementById("pdf_draw_ghost");
      var status = document.getElementById("pdf_redact_status");
      var payloadInput = document.getElementById("redactions_payload");
      var gotoInput = document.getElementById("pdf_goto_page");
      var commitForm = document.getElementById("pdf_commit_form");

      var prevBtn = document.getElementById("pdf_prev_btn");
      var nextBtn = document.getElementById("pdf_next_btn");
      var undoBtn = document.getElementById("pdf_undo_btn");
      var clearPageBtn = document.getElementById("pdf_clear_page_btn");
      var clearAllBtn = document.getElementById("pdf_clear_all_btn");

      var rectsByPage = Object.create(null);
      var drawState = { active: false, pointerId: null, startX: 0, startY: 0 };

      function pageKey(index) { return String(index); }
      function rectsForPage(index) {
        var key = pageKey(index);
        if (!rectsByPage[key]) rectsByPage[key] = [];
        return rectsByPage[key];
      }
      function clamp01(v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }
      function totalRectCount() {
        var keys = Object.keys(rectsByPage);
        var total = 0;
        for (var i = 0; i < keys.length; i++) total += rectsByPage[keys[i]].length;
        return total;
      }
      function updateStatus() {
        var perPage = rectsForPage(currentPage).length;
        var total = totalRectCount();
        status.textContent = "Page " + (currentPage + 1) + " of " + totalPages + " \u00b7 " + perPage + " on this page \u00b7 " + total + " total";
        if (gotoInput) gotoInput.value = String(currentPage + 1);
      }
      function encodePayload() {
        var rows = [];
        var keys = Object.keys(rectsByPage);
        for (var i = 0; i < keys.length; i++) {
          var page = parseInt(keys[i], 10);
          if (!Number.isFinite(page) || page < 0) continue;
          var list = rectsByPage[keys[i]] || [];
          for (var j = 0; j < list.length; j++) {
            var r = list[j];
            rows.push(page + "," + r.x.toFixed(6) + "," + r.y.toFixed(6) + "," + r.w.toFixed(6) + "," + r.h.toFixed(6));
          }
        }
        return rows.join(";");
      }
      function loadPage(index) {
        if (totalPages <= 0) return;
        currentPage = Math.max(0, Math.min(totalPages - 1, index));
        img.src = imageBase + "&page=" + currentPage + "&_ts=" + Date.now();
        renderRects();
        updateStatus();
      }
      function renderRects() {
        while (overlay.firstChild) overlay.removeChild(overlay.firstChild);
        var list = rectsForPage(currentPage);
        for (var i = 0; i < list.length; i++) {
          var r = list[i];
          var box = document.createElement("div");
          box.className = "pdf-redact-rect";
          box.style.left = (r.x * 100) + "%";
          box.style.top = (r.y * 100) + "%";
          box.style.width = (r.w * 100) + "%";
          box.style.height = (r.h * 100) + "%";
          overlay.appendChild(box);
        }
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
        ghost.style.left = (left * 100) + "%";
        ghost.style.top = (top * 100) + "%";
        ghost.style.width = (width * 100) + "%";
        ghost.style.height = (height * 100) + "%";
      }
      function hideGhost() {
        ghost.style.display = "none";
        ghost.style.width = "0";
        ghost.style.height = "0";
      }
      function beginDraw(ev) {
        if (ev.button !== 0) return;
        var point = pointFromEvent(ev);
        if (!point) return;
        drawState.active = true;
        drawState.pointerId = ev.pointerId;
        drawState.startX = point.x;
        drawState.startY = point.y;
        overlay.setPointerCapture(ev.pointerId);
        drawGhost(point.x, point.y, point.x, point.y);
        ev.preventDefault();
      }
      function moveDraw(ev) {
        if (!drawState.active || ev.pointerId !== drawState.pointerId) return;
        var point = pointFromEvent(ev);
        if (!point) return;
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

        rectsForPage(currentPage).push({ x: x, y: y, w: w, h: h });
        renderRects();
        updateStatus();
      }

      prevBtn.addEventListener("click", function () { loadPage(currentPage - 1); });
      nextBtn.addEventListener("click", function () { loadPage(currentPage + 1); });
      undoBtn.addEventListener("click", function () {
        var list = rectsForPage(currentPage);
        if (list.length > 0) list.pop();
        renderRects();
        updateStatus();
      });
      clearPageBtn.addEventListener("click", function () {
        rectsByPage[pageKey(currentPage)] = [];
        renderRects();
        updateStatus();
      });
      clearAllBtn.addEventListener("click", function () {
        rectsByPage = Object.create(null);
        renderRects();
        updateStatus();
      });
      document.getElementById("pdf_goto_form").addEventListener("submit", function (ev) {
        ev.preventDefault();
        var wanted = parseInt(String(gotoInput.value || "").trim(), 10);
        if (!Number.isFinite(wanted)) return;
        loadPage(wanted - 1);
      });
      commitForm.addEventListener("submit", function (ev) {
        var payload = encodePayload();
        if (!payload) {
          ev.preventDefault();
          alert("Add at least one redaction area before creating the redacted version.");
          return;
        }
        payloadInput.value = payload;
      });

      overlay.addEventListener("pointerdown", beginDraw);
      overlay.addEventListener("pointermove", moveDraw);
      overlay.addEventListener("pointerup", endDraw);
      overlay.addEventListener("pointercancel", function () { hideGhost(); drawState.active = false; });

      loadPage(currentPage);
    })();
    </script>
  <% } %>
</div>

<jsp:include page="footer.jsp" />
