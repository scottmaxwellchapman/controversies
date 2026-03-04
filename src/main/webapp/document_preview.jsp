<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" isELIgnored="true" %>
<%@ page import="java.net.URLEncoder" %>
<%@ page import="java.nio.charset.StandardCharsets" %>
<%@ page import="java.nio.file.Files" %>
<%@ page import="java.nio.file.Path" %>
<%@ page import="java.util.ArrayList" %>
<%@ page import="java.util.List" %>
<%@ page import="java.util.Locale" %>

<%@ page import="net.familylawandprobate.controversies.document_page_preview" %>
<%@ page import="net.familylawandprobate.controversies.document_parts" %>
<%@ page import="net.familylawandprobate.controversies.documents" %>
<%@ page import="net.familylawandprobate.controversies.part_versions" %>
<%@ page import="net.familylawandprobate.controversies.pdf_redaction_service" %>

<%@ include file="security.jspf" %>
<% if (!require_login()) return; %>

<%!
  private static final String S_TENANT_UUID = "tenant.uuid";

  private static final class PreviewPart {
    String partUuid;
    String partLabel;
    String partType;
    String partSequence;

    String versionUuid;
    String versionLabel;
    String versionCreatedAt;

    Path sourcePath;
    String extension;
    boolean renderable;
    String statusMessage;
  }

  private static String safe(String s) { return s == null ? "" : s; }

  private static String esc(String s) {
    return safe(s).replace("&","&amp;")
                  .replace("<","&lt;")
                  .replace(">","&gt;")
                  .replace("\"","&quot;")
                  .replace("'","&#39;");
  }

  private static String enc(String s) {
    return URLEncoder.encode(safe(s), StandardCharsets.UTF_8);
  }

  private static int intOr(String raw, int fallback) {
    try { return Integer.parseInt(safe(raw).trim()); } catch (Exception ex) { return fallback; }
  }

  private static String fileName(Path p) {
    if (p == null || p.getFileName() == null) return "";
    return safe(p.getFileName().toString());
  }

  private static String partDisplay(PreviewPart p) {
    if (p == null) return "Part";
    String seq = safe(p.partSequence).trim();
    String label = safe(p.partLabel).trim();
    String type = safe(p.partType).trim();
    String out = "";
    if (!seq.isBlank()) out = seq;
    if (!label.isBlank()) out = out.isBlank() ? label : (out + " - " + label);
    if (out.isBlank()) out = "Part";
    if (!type.isBlank()) out += " (" + type + ")";
    return out;
  }

  private static String safeExternalHref(String raw) {
    String v = safe(raw).trim();
    if (v.isBlank()) return "";
    String lower = v.toLowerCase(Locale.ROOT);
    if (lower.startsWith("https://") || lower.startsWith("http://") || lower.startsWith("mailto:")) return v;
    return "";
  }

  private static String shortErr(Throwable ex) {
    if (ex == null) return "";
    String m = safe(ex.getMessage()).trim();
    return m.isBlank() ? ex.getClass().getSimpleName() : m;
  }

  private static part_versions.VersionRec newestVersion(List<part_versions.VersionRec> rows) {
    if (rows == null || rows.isEmpty()) return null;
    for (int i = 0; i < rows.size(); i++) {
      part_versions.VersionRec rec = rows.get(i);
      if (rec != null) return rec;
    }
    return null;
  }
%>

<%
  String ctx = safe(request.getContextPath());
  request.setAttribute("activeNav", "/documents.jsp");
  String tenantUuid = safe((String)session.getAttribute(S_TENANT_UUID)).trim();
  if (tenantUuid.isBlank()) { response.sendRedirect(ctx + "/tenant_login.jsp"); return; }

  String caseUuid = safe(request.getParameter("case_uuid")).trim();
  String docUuid = safe(request.getParameter("doc_uuid")).trim();

  if (caseUuid.isBlank() || docUuid.isBlank()) {
    response.sendRedirect(ctx + "/documents.jsp?case_uuid=" + enc(caseUuid));
    return;
  }

  documents docs = documents.defaultStore();
  document_parts parts = document_parts.defaultStore();
  part_versions versions = part_versions.defaultStore();

  String error = null;
  documents.DocumentRec doc = null;
  try {
    doc = docs.get(tenantUuid, caseUuid, docUuid);
  } catch (Exception ex) {
    error = "Unable to load document: " + shortErr(ex);
  }
  if (doc == null && safe(error).isBlank()) {
    error = "Document not found.";
  }

  ArrayList<PreviewPart> previewParts = new ArrayList<PreviewPart>();
  try {
    List<document_parts.PartRec> partRows = parts.listAll(tenantUuid, caseUuid, docUuid);
    for (int i = 0; i < partRows.size(); i++) {
      document_parts.PartRec part = partRows.get(i);
      if (part == null || part.trashed) continue;

      PreviewPart row = new PreviewPart();
      row.partUuid = safe(part.uuid).trim();
      row.partLabel = safe(part.label);
      row.partType = safe(part.partType);
      row.partSequence = safe(part.sequence);

      List<part_versions.VersionRec> versionRows = versions.listAll(tenantUuid, caseUuid, docUuid, row.partUuid);
      part_versions.VersionRec latest = newestVersion(versionRows);
      if (latest == null) {
        row.statusMessage = "No version uploaded for this part yet.";
        previewParts.add(row);
        continue;
      }

      row.versionUuid = safe(latest.uuid).trim();
      row.versionLabel = safe(latest.versionLabel);
      row.versionCreatedAt = safe(latest.createdAt);

      Path sourcePath = pdf_redaction_service.resolveStoragePath(latest.storagePath);
      row.sourcePath = sourcePath;
      row.extension = document_page_preview.extension(sourcePath).toUpperCase(Locale.ROOT);

      try {
        pdf_redaction_service.requirePathWithinTenant(sourcePath, tenantUuid, "Preview file path");
      } catch (Exception ex) {
        row.statusMessage = "Latest version path is outside tenant storage boundary.";
        previewParts.add(row);
        continue;
      }

      if (sourcePath == null || !Files.isRegularFile(sourcePath)) {
        row.statusMessage = "Latest version file is missing or unavailable.";
        previewParts.add(row);
        continue;
      }

      if (!document_page_preview.isRenderable(sourcePath)) {
        String ext = row.extension.isBlank() ? "unknown" : row.extension;
        row.statusMessage = "Latest version format (" + ext + ") is not previewable.";
        previewParts.add(row);
        continue;
      }

      row.renderable = true;
      previewParts.add(row);
    }
  } catch (Exception ex) {
    error = "Unable to load part/version preview data: " + shortErr(ex);
  }

  int partIndex = intOr(request.getParameter("part_index"), 0);
  if (partIndex < 0) partIndex = 0;
  if (partIndex >= previewParts.size()) partIndex = Math.max(0, previewParts.size() - 1);

  int pageParam = intOr(request.getParameter("page"), 0);
  int gotoPageParam = intOr(request.getParameter("goto_page"), -1);
  if (gotoPageParam > 0) pageParam = gotoPageParam - 1;
  if (pageParam < 0) pageParam = 0;

  PreviewPart selected = previewParts.isEmpty() ? null : previewParts.get(partIndex);
  document_page_preview.RenderedPage rendered = null;
  if (selected != null && selected.renderable) {
    try {
      rendered = document_page_preview.renderPage(selected.sourcePath, pageParam);
    } catch (Exception ex) {
      selected.renderable = false;
      selected.statusMessage = "Unable to render latest version preview: " + shortErr(ex);
      if (safe(error).isBlank()) error = selected.statusMessage;
    }
  }

  int prevPartIndex = -1;
  int prevPageIndex = 0;
  int nextPartIndex = -1;
  int nextPageIndex = 0;

  if (selected != null) {
    if (selected.renderable && rendered != null && rendered.hasPrev) {
      prevPartIndex = partIndex;
      prevPageIndex = Math.max(0, rendered.pageIndex - 1);
    } else if (partIndex > 0) {
      prevPartIndex = partIndex - 1;
      PreviewPart prevPart = previewParts.get(prevPartIndex);
      if (prevPart != null && prevPart.renderable) {
        try {
          document_page_preview.RenderedPage tail = document_page_preview.renderPage(prevPart.sourcePath, Integer.MAX_VALUE);
          prevPageIndex = Math.max(0, tail.pageIndex);
        } catch (Exception ignored) {
          prevPageIndex = 0;
        }
      }
    }

    if (selected.renderable && rendered != null && rendered.hasNext) {
      nextPartIndex = partIndex;
      nextPageIndex = rendered.pageIndex + 1;
    } else if (partIndex + 1 < previewParts.size()) {
      nextPartIndex = partIndex + 1;
      nextPageIndex = 0;
    }
  }

  String backHref = ctx + "/documents.jsp?case_uuid=" + enc(caseUuid);
%>

<jsp:include page="header.jsp" />

<style>
  .doc-preview-layout { display:grid; gap:10px; margin-top:10px; }
  .doc-preview-grid { display:grid; gap:10px; grid-template-columns:minmax(240px, 320px) minmax(0,1fr); align-items:start; }
  .doc-preview-list { border:1px solid var(--border); border-radius:10px; background:var(--surface); padding:8px; display:grid; gap:6px; max-height:72vh; overflow:auto; }
  .doc-preview-item { border:1px solid var(--border); border-radius:8px; background:var(--surface-2); padding:7px 8px; }
  .doc-preview-item.is-active { border-color:var(--accent); box-shadow:var(--focus); }
  .doc-preview-item-title { font-size:13px; font-weight:600; color:var(--text); word-break:break-word; }
  .doc-preview-item-meta { font-size:11px; color:var(--muted); word-break:break-word; margin-top:3px; }
  .doc-preview-item-status { margin-top:4px; font-size:12px; color:var(--muted); }
  .doc-preview-nav { display:flex; gap:8px; align-items:center; flex-wrap:wrap; }
  .doc-preview-meta { color:var(--muted); font-size:12px; }
  .doc-preview-jump { display:flex; gap:6px; align-items:center; margin:0; }
  .doc-preview-jump input[type="number"] { width:90px; margin:0; }
  .doc-preview-view { border:1px solid var(--border); border-radius:10px; background:var(--surface); padding:10px; }
  .doc-preview-image-wrap { border:1px solid var(--border); border-radius:10px; background:var(--surface-2); padding:8px; overflow:auto; margin-top:8px; }
  .doc-preview-image-wrap img { display:block; width:100%; height:auto; background:#fff; border:1px solid var(--border); border-radius:8px; }
  .doc-preview-empty { border:1px dashed var(--border); border-radius:10px; background:var(--surface); padding:12px; color:var(--muted); }
  .doc-preview-copy-btn { min-width:34px; padding:6px 8px; line-height:1; display:inline-flex; align-items:center; justify-content:center; }
  .doc-preview-copy-btn svg { width:15px; height:15px; display:block; fill:currentColor; }
  .doc-preview-copy-status { min-height:1em; }
  .doc-preview-hidden-text { position:absolute; left:-9999px; width:1px; height:1px; opacity:0; pointer-events:none; }
  .doc-preview-doc-nav { margin-top:8px; border:1px solid var(--border); border-radius:10px; background:var(--surface); padding:6px 8px; }
  .doc-preview-doc-nav summary { cursor:pointer; font-weight:600; color:var(--text); }
  .doc-preview-doc-nav-list { margin-top:8px; display:grid; gap:6px; }
  .doc-preview-doc-nav-row { display:grid; gap:8px; grid-template-columns:minmax(0, 1fr) auto; align-items:center; border:1px solid var(--border); border-radius:8px; background:var(--surface-2); padding:6px 8px; }
  .doc-preview-doc-nav-kind { display:inline-block; margin-right:6px; padding:1px 6px; border:1px solid var(--border); border-radius:999px; font-size:11px; color:var(--muted); background:var(--surface); text-transform:capitalize; }
  .doc-preview-doc-nav-label { font-size:13px; color:var(--text); word-break:break-word; }
  .doc-preview-doc-nav-target { color:var(--muted); font-size:11px; word-break:break-word; margin-top:3px; }
  .doc-preview-doc-nav-actions { display:flex; gap:6px; align-items:center; flex-wrap:wrap; }
  .doc-preview-doc-nav-page { color:var(--muted); font-size:11px; }
  @media (max-width: 980px) {
    .doc-preview-grid { grid-template-columns:minmax(0,1fr); }
    .doc-preview-list { max-height:none; }
  }
  @media (max-width: 700px) {
    .doc-preview-jump input[type="number"] { width:70px; }
    .doc-preview-doc-nav-row { grid-template-columns:minmax(0,1fr); }
  }
</style>

<div class="doc-preview-layout">
  <section class="card">
    <div class="section-head" style="align-items:flex-start;">
      <div>
        <h1 style="margin:0;">Document Combined Preview</h1>
        <div class="meta">Newest version from each active part, in part order, including mixed file formats.</div>
        <div class="meta" style="margin-top:4px;">Document: <strong><%= esc(safe(doc == null ? "" : doc.title)) %></strong></div>
      </div>
      <a class="btn btn-ghost" href="<%= backHref %>">Back to Documents</a>
    </div>
    <% if (!safe(error).isBlank()) { %>
      <div class="alert alert-error" style="margin-top:10px;"><%= esc(error) %></div>
    <% } %>
  </section>

  <% if (previewParts.isEmpty()) { %>
    <section class="card">
      <div class="doc-preview-empty">No active parts are available for preview in this document.</div>
    </section>
  <% } else { %>
    <section class="doc-preview-grid">
      <aside class="doc-preview-list" aria-label="Part preview list">
        <% for (int i = 0; i < previewParts.size(); i++) {
             PreviewPart p = previewParts.get(i);
             if (p == null) continue;
             boolean active = (i == partIndex);
             String openHref = ctx + "/document_preview.jsp?case_uuid=" + enc(caseUuid) + "&doc_uuid=" + enc(docUuid) + "&part_index=" + i + "&page=0";
        %>
          <div class="doc-preview-item <%= active ? "is-active" : "" %>">
            <div class="doc-preview-item-title"><%= esc(partDisplay(p)) %></div>
            <div class="doc-preview-item-meta">
              <% if (!safe(p.versionLabel).isBlank()) { %>
                Version: <%= esc(p.versionLabel) %>
              <% } else { %>
                Version: none
              <% } %>
              <% if (!safe(p.extension).isBlank()) { %> | <%= esc(p.extension) %><% } %>
            </div>
            <% if (!safe(p.versionCreatedAt).isBlank()) { %>
              <div class="doc-preview-item-meta">Created: <%= esc(p.versionCreatedAt) %></div>
            <% } %>
            <% if (!safe(p.statusMessage).isBlank()) { %>
              <div class="doc-preview-item-status"><%= esc(p.statusMessage) %></div>
            <% } %>
            <div style="margin-top:6px;">
              <a class="btn btn-ghost" href="<%= openHref %>">Open</a>
            </div>
          </div>
        <% } %>
      </aside>

      <div class="doc-preview-view">
        <% if (selected == null) { %>
          <div class="doc-preview-empty">Select a part to preview.</div>
        <% } else if (!selected.renderable || rendered == null || safe(rendered.base64Png).isBlank()) { %>
          <h2 style="margin:0;"><%= esc(partDisplay(selected)) %></h2>
          <div class="doc-preview-meta" style="margin-top:4px;">Newest version cannot be rendered.</div>
          <div class="doc-preview-empty" style="margin-top:10px;"><%= esc(safe(selected.statusMessage).isBlank() ? "Preview unavailable for this part." : selected.statusMessage) %></div>
          <div class="doc-preview-nav" style="margin-top:10px;">
            <% if (prevPartIndex >= 0) { %>
              <a class="btn btn-ghost" href="<%= ctx %>/document_preview.jsp?case_uuid=<%= enc(caseUuid) %>&doc_uuid=<%= enc(docUuid) %>&part_index=<%= prevPartIndex %>&page=<%= prevPageIndex %>">Previous</a>
            <% } %>
            <% if (nextPartIndex >= 0) { %>
              <a class="btn btn-ghost" href="<%= ctx %>/document_preview.jsp?case_uuid=<%= enc(caseUuid) %>&doc_uuid=<%= enc(docUuid) %>&part_index=<%= nextPartIndex %>&page=<%= nextPageIndex %>">Next</a>
            <% } %>
          </div>
        <% } else {
             int viewedPageOne = rendered.pageIndex + 1;
             String viewedTotalLabel = rendered.totalKnown ? String.valueOf(rendered.totalPages) : (rendered.totalPages + "+");
             String gotoMaxAttr = rendered.totalKnown ? ("max=\"" + rendered.totalPages + "\"") : "";
             String selectedSourceName = fileName(selected.sourcePath);
             ArrayList<document_page_preview.NavigationEntry> navEntries = rendered.navigation == null
               ? new ArrayList<document_page_preview.NavigationEntry>() : rendered.navigation;
             int navDisplayLimit = 120;
             int navShown = Math.min(navEntries.size(), navDisplayLimit);
        %>
          <div class="section-head" style="align-items:flex-start;">
            <div>
              <h2 style="margin:0;"><%= esc(partDisplay(selected)) %></h2>
              <div class="doc-preview-meta"><%= esc(selectedSourceName) %></div>
              <div class="doc-preview-meta">
                Part <strong><%= partIndex + 1 %></strong> of <strong><%= previewParts.size() %></strong>
                | Page <strong><%= viewedPageOne %></strong> of <strong><%= esc(viewedTotalLabel) %></strong>
                <% if (!safe(rendered.engine).isBlank()) { %> | Engine: <%= esc(rendered.engine) %><% } %>
              </div>
            </div>
            <div class="doc-preview-nav">
              <% if (prevPartIndex >= 0) { %>
                <a class="btn btn-ghost" href="<%= ctx %>/document_preview.jsp?case_uuid=<%= enc(caseUuid) %>&doc_uuid=<%= enc(docUuid) %>&part_index=<%= prevPartIndex %>&page=<%= prevPageIndex %>">Previous</a>
              <% } %>
              <% if (nextPartIndex >= 0) { %>
                <a class="btn btn-ghost" href="<%= ctx %>/document_preview.jsp?case_uuid=<%= enc(caseUuid) %>&doc_uuid=<%= enc(docUuid) %>&part_index=<%= nextPartIndex %>&page=<%= nextPageIndex %>">Next</a>
              <% } %>
              <form method="get" action="<%= ctx %>/document_preview.jsp" class="doc-preview-jump">
                <input type="hidden" name="case_uuid" value="<%= esc(caseUuid) %>" />
                <input type="hidden" name="doc_uuid" value="<%= esc(docUuid) %>" />
                <input type="hidden" name="part_index" value="<%= partIndex %>" />
                <label style="margin:0;">
                  <span class="doc-preview-meta">Page</span>
                  <input type="number" name="goto_page" min="1" <%= gotoMaxAttr %> value="<%= viewedPageOne %>" />
                </label>
                <button class="btn btn-ghost" type="submit">Go</button>
              </form>
              <button type="button" class="btn btn-ghost doc-preview-copy-btn" id="btnCopyCombinedPageText" title="Copy plain text of this page" aria-label="Copy plain text of this page">
                <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
                  <path d="M16 1H6c-1.66 0-3 1.34-3 3v12h2V4c0-.55.45-1 1-1h10V1zm3 4H10c-1.66 0-3 1.34-3 3v13c0 1.66 1.34 3 3 3h9c1.66 0 3-1.34 3-3V8c0-1.66-1.34-3-3-3zm1 16c0 .55-.45 1-1 1h-9c-.55 0-1-.45-1-1V8c0-.55.45-1 1-1h9c.55 0 1 .45 1 1v13z"/>
                </svg>
              </button>
              <span id="docPreviewCopyStatus" class="doc-preview-meta doc-preview-copy-status" aria-live="polite"></span>
            </div>
          </div>
          <textarea id="docPreviewHiddenPageText" class="doc-preview-hidden-text" aria-hidden="true" tabindex="-1"><%= esc(safe(rendered.pageText)) %></textarea>
          <% if (!safe(rendered.warning).isBlank()) { %>
            <div class="alert alert-warn" style="margin-top:8px;"><%= esc(rendered.warning) %></div>
          <% } %>
          <details class="doc-preview-doc-nav">
            <summary>Document Navigation (<%= navEntries.size() %>)</summary>
            <div class="doc-preview-doc-nav-list">
              <% if (navEntries.isEmpty()) { %>
                <div class="doc-preview-empty">No built-in headings, bookmarks, or links were detected for this part.</div>
              <% } else { %>
                <% for (int ni = 0; ni < navShown; ni++) {
                     document_page_preview.NavigationEntry n = navEntries.get(ni);
                     if (n == null) continue;
                     String nType = safe(n.type).toLowerCase(Locale.ROOT);
                     String nLabel = safe(n.label);
                     int nPageOne = n.pageIndex >= 0 ? (n.pageIndex + 1) : -1;
                     String nTarget = safe(n.target);
                     String externalHref = n.external ? safeExternalHref(nTarget) : "";
                %>
                  <div class="doc-preview-doc-nav-row">
                    <div>
                      <div class="doc-preview-doc-nav-label">
                        <span class="doc-preview-doc-nav-kind"><%= esc(nType.isBlank() ? "entry" : nType) %></span>
                        <%= esc(nLabel.isBlank() ? "Untitled navigation entry" : nLabel) %>
                      </div>
                      <% if (!nTarget.isBlank()) { %>
                        <div class="doc-preview-doc-nav-target"><%= esc(nTarget) %></div>
                      <% } %>
                    </div>
                    <div class="doc-preview-doc-nav-actions">
                      <% if (nPageOne > 0) { %>
                        <a class="btn btn-ghost" href="<%= ctx %>/document_preview.jsp?case_uuid=<%= enc(caseUuid) %>&doc_uuid=<%= enc(docUuid) %>&part_index=<%= partIndex %>&page=<%= n.pageIndex %>">Go</a>
                        <span class="doc-preview-doc-nav-page">P.<%= nPageOne %></span>
                      <% } %>
                      <% if (!externalHref.isBlank()) { %>
                        <a class="btn btn-ghost" href="<%= esc(externalHref) %>" target="_blank" rel="noopener noreferrer">Open Link</a>
                      <% } %>
                    </div>
                  </div>
                <% } %>
                <% if (navEntries.size() > navDisplayLimit) { %>
                  <div class="doc-preview-meta">Showing first <%= navDisplayLimit %> entries.</div>
                <% } %>
              <% } %>
            </div>
          </details>
          <div class="doc-preview-image-wrap">
            <img src="data:image/png;base64,<%= esc(rendered.base64Png) %>" alt="Combined document preview page" />
          </div>
        <% } %>
      </div>
    </section>
  <% } %>
</div>

<script>
(() => {
  const copyBtn = document.getElementById("btnCopyCombinedPageText");
  if (!copyBtn) return;
  const textEl = document.getElementById("docPreviewHiddenPageText");
  const statusEl = document.getElementById("docPreviewCopyStatus");

  function setStatus(message, isError) {
    if (!statusEl) return;
    statusEl.textContent = message || "";
    statusEl.style.color = isError ? "var(--danger, #b91c1c)" : "var(--muted)";
  }

  async function copyPageText() {
    const value = (textEl && typeof textEl.value === "string") ? textEl.value : "";
    if (!value.trim()) {
      setStatus("No page text available.", true);
      return;
    }
    try {
      if (navigator.clipboard && window.isSecureContext) {
        await navigator.clipboard.writeText(value);
      } else {
        const temp = document.createElement("textarea");
        temp.value = value;
        temp.setAttribute("readonly", "readonly");
        temp.style.position = "absolute";
        temp.style.left = "-9999px";
        document.body.appendChild(temp);
        temp.select();
        const ok = document.execCommand("copy");
        document.body.removeChild(temp);
        if (!ok) throw new Error("copy command failed");
      }
      setStatus("Page text copied.", false);
    } catch (err) {
      setStatus("Clipboard copy is blocked by browser permissions.", true);
    }
  }

  copyBtn.addEventListener("click", () => { void copyPageText(); });
})();
</script>

<jsp:include page="footer.jsp" />
