<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" isELIgnored="true" %>

<%@ page import="java.net.URLEncoder" %>
<%@ page import="java.nio.charset.StandardCharsets" %>
<%@ page import="java.nio.file.Files" %>
<%@ page import="java.nio.file.Path" %>
<%@ page import="java.nio.file.StandardOpenOption" %>
<%@ page import="java.security.MessageDigest" %>
<%@ page import="java.util.ArrayList" %>
<%@ page import="java.util.LinkedHashMap" %>
<%@ page import="java.util.List" %>
<%@ page import="java.util.Locale" %>
<%@ page import="java.util.UUID" %>

<%@ page import="net.familylawandprobate.controversies.assembled_forms" %>
<%@ page import="net.familylawandprobate.controversies.document_parts" %>
<%@ page import="net.familylawandprobate.controversies.documents" %>
<%@ page import="net.familylawandprobate.controversies.form_templates" %>
<%@ page import="net.familylawandprobate.controversies.matters" %>
<%@ page import="net.familylawandprobate.controversies.part_versions" %>

<%@ include file="security.jspf" %>
<%
  if (!require_login()) return;
%>

<%!
  private static final String S_TENANT_UUID = "tenant.uuid";
  private static final String CSRF_SESSION_KEY = "CSRF_TOKEN";

  private static String safe(String s) { return s == null ? "" : s; }

  private static String esc(String s) {
    if (s == null) return "";
    return s.replace("&","&amp;")
            .replace("<","&lt;")
            .replace(">","&gt;")
            .replace("\"","&quot;")
            .replace("'","&#39;");
  }

  private static String enc(String s) {
    return URLEncoder.encode(safe(s), StandardCharsets.UTF_8);
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

  private static boolean boolLike(String raw) {
    String v = safe(raw).trim().toLowerCase(Locale.ROOT);
    return "1".equals(v) || "true".equals(v) || "yes".equals(v) || "on".equals(v) || "y".equals(v);
  }

  private static String normalizeExt(String extOrName) {
    String v = safe(extOrName).trim().toLowerCase(Locale.ROOT);
    if (v.isBlank()) return "";
    int slash = Math.max(v.lastIndexOf('/'), v.lastIndexOf('\\'));
    if (slash >= 0 && slash + 1 < v.length()) v = v.substring(slash + 1);
    int dot = v.lastIndexOf('.');
    if (dot >= 0 && dot + 1 < v.length()) v = v.substring(dot + 1);
    return v.replaceAll("[^a-z0-9]", "");
  }

  private static String safeFileName(String raw) {
    String v = safe(raw).trim().replaceAll("[^A-Za-z0-9.]", "_");
    if (v.isBlank()) return "version.bin";
    if (v.length() > 160) return v.substring(v.length() - 160);
    return v;
  }

  private static String fileNameWithExt(String rawName, String ext) {
    String outExt = normalizeExt(ext);
    String base = safeFileName(rawName);
    if (outExt.isBlank()) return base;
    String currentExt = normalizeExt(base);
    if (!outExt.equals(currentExt)) return base + "." + outExt;
    return base;
  }

  private static String contentTypeForExt(String ext) {
    String e = safe(ext).trim().toLowerCase(Locale.ROOT);
    if (e.equals("docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    if (e.equals("doc")) return "application/msword";
    if (e.equals("rtf")) return "application/rtf";
    if (e.equals("odt")) return "application/vnd.oasis.opendocument.text";
    if (e.equals("txt")) return "text/plain; charset=UTF-8";
    return "application/octet-stream";
  }

  private static String mimeTypeForExt(String ext) {
    String e = safe(ext).trim().toLowerCase(Locale.ROOT);
    if (e.equals("docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    if (e.equals("doc")) return "application/msword";
    if (e.equals("rtf")) return "application/rtf";
    if (e.equals("odt")) return "application/vnd.oasis.opendocument.text";
    if (e.equals("txt")) return "text/plain";
    return "application/octet-stream";
  }

  private static String hex(byte[] raw) {
    if (raw == null) return "";
    StringBuilder sb = new StringBuilder(raw.length * 2);
    for (byte b : raw) sb.append(String.format(Locale.ROOT, "%02x", b));
    return sb.toString();
  }

  private static String sha256(byte[] raw) throws Exception {
    MessageDigest md = MessageDigest.getInstance("SHA-256");
    return hex(md.digest(raw == null ? new byte[0] : raw));
  }

  private static Path writeVersionFile(document_parts partStore,
                                       String tenantUuid,
                                       String matterUuid,
                                       String docUuid,
                                       String partUuid,
                                       String outputName,
                                       String outputExt,
                                       byte[] bytes) throws Exception {
    Path partFolder = partStore.partFolder(tenantUuid, matterUuid, docUuid, partUuid);
    if (partFolder == null) throw new IllegalArgumentException("Part folder unavailable.");
    Path versionDir = partFolder.resolve("version_files");
    Files.createDirectories(versionDir);
    String finalName = UUID.randomUUID().toString().replace("-", "_") + "__" + fileNameWithExt(outputName, outputExt);
    Path out = versionDir.resolve(finalName);
    Files.write(out, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    return out;
  }
%>

<%
  String ctx = request.getContextPath();
  if (ctx == null) ctx = "";

  String tenantUuid = safe((String)session.getAttribute(S_TENANT_UUID)).trim();
  if (tenantUuid.isBlank()) {
    response.sendRedirect(ctx + "/tenant_login.jsp");
    return;
  }

  matters matterStore = matters.defaultStore();
  form_templates templateStore = form_templates.defaultStore();
  assembled_forms assembledStore = assembled_forms.defaultStore();
  documents docStore = documents.defaultStore();
  document_parts partStore = document_parts.defaultStore();
  part_versions versionStore = part_versions.defaultStore();

  try { matterStore.ensure(tenantUuid); } catch (Exception ignored) {}
  try { templateStore.ensure(tenantUuid); } catch (Exception ignored) {}

  String csrfToken = csrfForRender(request);

  String message = null;
  String error = null;

  boolean showTrashed = boolLike(request.getParameter("show_trashed"));
  String selectedMatterUuid = safe(request.getParameter("matter_uuid")).trim();
  String selectedTemplateUuid = safe(request.getParameter("template_uuid")).trim();
  String actor = safe((String)session.getAttribute("user.email")).trim();
  if (actor.isBlank()) actor = safe((String)session.getAttribute("user.uuid")).trim();

  if ("POST".equalsIgnoreCase(request.getMethod())) {
    String action = safe(request.getParameter("action")).trim().toLowerCase(Locale.ROOT);
    selectedMatterUuid = safe(request.getParameter("matter_uuid")).trim();
    selectedTemplateUuid = safe(request.getParameter("template_uuid")).trim();
    showTrashed = boolLike(request.getParameter("show_trashed"));

    if ("retry_sync".equals(action)) {
      String assemblyUuid = safe(request.getParameter("assembly_uuid")).trim();
      boolean retried = assembledStore.retrySyncNow(tenantUuid, selectedMatterUuid, assemblyUuid);
      if (retried) message = "Sync retry queued.";
      else error = "Unable to queue sync retry.";
    } else if ("download_completed".equals(action)) {
      String assemblyUuid = safe(request.getParameter("assembly_uuid")).trim();
      try {
        assembled_forms.AssemblyRec rec = assembledStore.getByUuid(tenantUuid, selectedMatterUuid, assemblyUuid);
        if (rec == null || rec.trashed || !"completed".equals(safe(rec.status))) {
          throw new IllegalArgumentException("Completed assembled form was not found.");
        }
        byte[] bytes = assembledStore.readOutputBytes(tenantUuid, selectedMatterUuid, assemblyUuid);
        if (bytes == null || bytes.length == 0) {
          throw new IllegalArgumentException("Saved assembled output file is unavailable.");
        }
        String ext = normalizeExt(safe(rec.outputFileExt).isBlank() ? safe(rec.templateExt) : safe(rec.outputFileExt));
        if (ext.isBlank()) ext = "txt";
        String contentType = contentTypeForExt(ext);
        String rawName = safe(rec.outputFileName).trim();
        if (rawName.isBlank()) rawName = safe(rec.templateLabel).trim().isBlank() ? "assembled-form" : safe(rec.templateLabel).trim();
        String filename = fileNameWithExt(rawName, ext);
        response.setContentType(contentType);
        response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        response.getOutputStream().write(bytes);
        return;
      } catch (Exception ex) {
        error = "Unable to download assembled form: " + safe(ex.getMessage());
      }
    } else if ("trash_assembly".equals(action) || "restore_assembly".equals(action)) {
      String assemblyUuid = safe(request.getParameter("assembly_uuid")).trim();
      boolean trash = "trash_assembly".equals(action);
      try {
        boolean ok = assembledStore.setTrashed(tenantUuid, selectedMatterUuid, assemblyUuid, trash);
        if (ok) message = trash ? "Assembled form moved to trash." : "Assembled form restored.";
        else error = "Unable to update assembled form status.";
      } catch (Exception ex) {
        error = "Unable to update assembled form status: " + safe(ex.getMessage());
      }
    } else if ("convert_new_document".equals(action) || "convert_existing_document".equals(action)) {
      String assemblyUuid = safe(request.getParameter("assembly_uuid")).trim();
      try {
        if (selectedMatterUuid.isBlank()) throw new IllegalArgumentException("Case is required.");

        assembled_forms.AssemblyRec rec = assembledStore.getByUuid(tenantUuid, selectedMatterUuid, assemblyUuid);
        if (rec == null || rec.trashed || !"completed".equals(safe(rec.status))) {
          throw new IllegalArgumentException("Completed assembled form was not found.");
        }
        byte[] bytes = assembledStore.readOutputBytes(tenantUuid, selectedMatterUuid, assemblyUuid);
        if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("Saved assembled output file is unavailable.");

        String ext = normalizeExt(safe(rec.outputFileExt).isBlank() ? safe(rec.templateExt) : safe(rec.outputFileExt));
        if (ext.isBlank()) ext = "txt";

        String outputName = safe(rec.outputFileName).trim();
        if (outputName.isBlank()) {
          outputName = safe(rec.templateLabel).trim().isBlank() ? "assembled-form" : safe(rec.templateLabel).trim();
        }

        String versionLabel = safe(request.getParameter("version_label")).trim();
        if (versionLabel.isBlank()) {
          String templatePart = safe(rec.templateLabel).trim();
          if (templatePart.isBlank()) templatePart = "Assembled Form";
          versionLabel = templatePart + " - assembled";
        }
        String versionNotes = safe(request.getParameter("version_notes")).trim();
        String checksum = sha256(bytes);
        String createdBy = actor.isBlank() ? "system" : actor;

        if ("convert_new_document".equals(action)) {
          String newDocumentTitle = safe(request.getParameter("new_document_title")).trim();
          if (newDocumentTitle.isBlank()) {
            String templatePart = safe(rec.templateLabel).trim();
            if (templatePart.isBlank()) templatePart = "Assembled Form";
            newDocumentTitle = templatePart + " (Assembled)";
          }
          String newPartLabel = safe(request.getParameter("new_part_label")).trim();
          if (newPartLabel.isBlank()) newPartLabel = "Lead";

          documents.DocumentRec createdDoc = docStore.create(
              tenantUuid, selectedMatterUuid, newDocumentTitle,
              "", "", "", createdBy, "", "", "",
              "Created from assembled form " + safe(rec.uuid)
          );
          document_parts.PartRec createdPart = partStore.create(
              tenantUuid, selectedMatterUuid, createdDoc.uuid,
              newPartLabel, "lead", "1", "", createdBy,
              "Created from assembled form " + safe(rec.uuid)
          );
          Path outputPath = writeVersionFile(
              partStore, tenantUuid, selectedMatterUuid, createdDoc.uuid, createdPart.uuid,
              outputName, ext, bytes
          );
          versionStore.create(
              tenantUuid, selectedMatterUuid, createdDoc.uuid, createdPart.uuid,
              versionLabel, "assembled_form", mimeTypeForExt(ext),
              checksum, String.valueOf(bytes.length), outputPath.toUri().toString(),
              createdBy, versionNotes, true
          );
          message = "Converted to new document \"" + safe(createdDoc.title) + "\" as one part with one version.";
        } else {
          String targetDocUuid = safe(request.getParameter("target_doc_uuid")).trim();
          String targetPartUuid = safe(request.getParameter("target_part_uuid")).trim();
          documents.DocumentRec targetDoc = docStore.get(tenantUuid, selectedMatterUuid, targetDocUuid);
          if (targetDoc == null || targetDoc.trashed) throw new IllegalArgumentException("Target document not found.");
          document_parts.PartRec targetPart = partStore.get(tenantUuid, selectedMatterUuid, targetDocUuid, targetPartUuid);
          if (targetPart == null || targetPart.trashed) throw new IllegalArgumentException("Target part not found.");

          Path outputPath = writeVersionFile(
              partStore, tenantUuid, selectedMatterUuid, targetDocUuid, targetPartUuid,
              outputName, ext, bytes
          );
          versionStore.create(
              tenantUuid, selectedMatterUuid, targetDocUuid, targetPartUuid,
              versionLabel, "assembled_form", mimeTypeForExt(ext),
              checksum, String.valueOf(bytes.length), outputPath.toUri().toString(),
              createdBy, versionNotes, true
          );
          message = "Converted to existing document \"" + safe(targetDoc.title) + "\" part \"" + safe(targetPart.label) + "\".";
        }
      } catch (Exception ex) {
        error = "Unable to convert assembled form: " + safe(ex.getMessage());
      }
    }
  }

  List<matters.MatterRec> allCases = new ArrayList<matters.MatterRec>();
  try { allCases = matterStore.listAll(tenantUuid); } catch (Exception ignored) {}

  List<matters.MatterRec> activeCases = new ArrayList<matters.MatterRec>();
  for (int i = 0; i < allCases.size(); i++) {
    matters.MatterRec c = allCases.get(i);
    if (c == null) continue;
    if (!c.trashed) activeCases.add(c);
  }

  if (selectedMatterUuid.isBlank() && !activeCases.isEmpty()) selectedMatterUuid = safe(activeCases.get(0).uuid);

  matters.MatterRec selectedCase = null;
  try { selectedCase = matterStore.getByUuid(tenantUuid, selectedMatterUuid); } catch (Exception ignored) {}
  if (selectedCase == null && !activeCases.isEmpty()) selectedCase = activeCases.get(0);
  if (selectedCase != null) selectedMatterUuid = safe(selectedCase.uuid);

  List<form_templates.TemplateRec> templates = new ArrayList<form_templates.TemplateRec>();
  try { templates = templateStore.list(tenantUuid); } catch (Exception ignored) {}
  if (selectedTemplateUuid.isBlank() && !templates.isEmpty()) selectedTemplateUuid = safe(templates.get(0).uuid);

  List<assembled_forms.AssemblyRec> records = new ArrayList<assembled_forms.AssemblyRec>();
  if (selectedCase != null) {
    try {
      assembledStore.ensure(tenantUuid, selectedCase.uuid);
      records = assembledStore.listByMatter(tenantUuid, selectedCase.uuid);
    } catch (Exception ex) {
      error = "Unable to read assembled forms for this case: " + safe(ex.getMessage());
    }
  }

  List<documents.DocumentRec> activeDocuments = new ArrayList<documents.DocumentRec>();
  LinkedHashMap<String, List<document_parts.PartRec>> activePartsByDoc = new LinkedHashMap<String, List<document_parts.PartRec>>();
  int activePartCount = 0;
  String firstDocWithPartsUuid = "";
  if (selectedCase != null) {
    try {
      docStore.ensure(tenantUuid, selectedCase.uuid);
      List<documents.DocumentRec> docs = docStore.listAll(tenantUuid, selectedCase.uuid);
      for (int i = 0; i < docs.size(); i++) {
        documents.DocumentRec d = docs.get(i);
        if (d == null || d.trashed) continue;
        activeDocuments.add(d);

        List<document_parts.PartRec> parts = partStore.listAll(tenantUuid, selectedCase.uuid, d.uuid);
        List<document_parts.PartRec> activeParts = new ArrayList<document_parts.PartRec>();
        for (int pi = 0; pi < parts.size(); pi++) {
          document_parts.PartRec p = parts.get(pi);
          if (p == null || p.trashed) continue;
          activeParts.add(p);
        }
        if (!activeParts.isEmpty() && firstDocWithPartsUuid.isBlank()) firstDocWithPartsUuid = safe(d.uuid);
        activePartCount += activeParts.size();
        activePartsByDoc.put(safe(d.uuid), activeParts);
      }
    } catch (Exception ex) {
      if (error == null) error = "Unable to load documents and parts for conversion: " + safe(ex.getMessage());
    }
  }

  List<assembled_forms.AssemblyRec> inProgress = new ArrayList<assembled_forms.AssemblyRec>();
  List<assembled_forms.AssemblyRec> completed = new ArrayList<assembled_forms.AssemblyRec>();
  List<assembled_forms.AssemblyRec> trashedRecords = new ArrayList<assembled_forms.AssemblyRec>();
  for (int i = 0; i < records.size(); i++) {
    assembled_forms.AssemblyRec r = records.get(i);
    if (r == null) continue;
    if (r.trashed) {
      trashedRecords.add(r);
      continue;
    }
    if ("completed".equals(safe(r.status))) completed.add(r);
    else inProgress.add(r);
  }
%>

<jsp:include page="header.jsp" />

<section class="card">
  <h1 style="margin:0;">Assembled Forms</h1>
  <div class="meta" style="margin-top:4px;">Browse completed and in-progress assembly work per case.</div>

  <% if (message != null) { %>
    <div class="alert alert-ok" style="margin-top:12px;"><%= esc(message) %></div>
  <% } %>
  <% if (error != null) { %>
    <div class="alert alert-error" style="margin-top:12px;"><%= esc(error) %></div>
  <% } %>
</section>

<section class="card" style="margin-top:12px;">
  <form class="form" method="get" action="<%= ctx %>/assembled_forms.jsp">
    <div class="grid" style="display:grid; grid-template-columns: 2fr 2fr auto auto; gap:12px; align-items:end;">
      <label>
        <span>Case</span>
        <select name="matter_uuid">
          <% if (activeCases.isEmpty()) { %>
            <option value="">No active cases</option>
          <% } else {
               for (int i = 0; i < activeCases.size(); i++) {
                 matters.MatterRec c = activeCases.get(i);
                 if (c == null) continue;
                 String id = safe(c.uuid);
                 boolean sel = id.equals(safe(selectedMatterUuid));
          %>
            <option value="<%= esc(id) %>" <%= sel ? "selected" : "" %>><%= esc(safe(c.label)) %></option>
          <%   }
             } %>
        </select>
      </label>

      <label>
        <span>Template Context</span>
        <select name="template_uuid">
          <% if (templates.isEmpty()) { %>
            <option value="">No templates</option>
          <% } else {
               for (int i = 0; i < templates.size(); i++) {
                 form_templates.TemplateRec t = templates.get(i);
                 if (t == null) continue;
                 String id = safe(t.uuid);
                 boolean sel = id.equals(safe(selectedTemplateUuid));
          %>
            <option value="<%= esc(id) %>" <%= sel ? "selected" : "" %>>
              <%= esc(safe(t.label)) %> (<%= esc(safe(t.fileExt).toUpperCase(Locale.ROOT)) %>)
            </option>
          <%   }
             } %>
        </select>
      </label>

      <label style="display:flex; align-items:center; gap:8px; margin-bottom:3px;">
        <input type="checkbox" name="show_trashed" value="1" <%= showTrashed ? "checked" : "" %> />
        <span>Show Trashed</span>
      </label>

      <label>
        <span>&nbsp;</span>
        <button class="btn" type="submit">Load</button>
      </label>
    </div>
  </form>

  <div class="actions" style="display:flex; gap:10px; margin-top:10px; flex-wrap:wrap;">
    <a class="btn btn-ghost" href="<%= ctx %>/forms.jsp?matter_uuid=<%= enc(selectedMatterUuid) %>&template_uuid=<%= enc(selectedTemplateUuid) %>">Back To Form Assembly</a>
    <a class="btn btn-ghost" href="<%= ctx %>/template_library.jsp?matter_uuid=<%= enc(selectedMatterUuid) %>&template_uuid=<%= enc(selectedTemplateUuid) %>">Template Library</a>
    <a class="btn btn-ghost" href="<%= ctx %>/template_editor.jsp?matter_uuid=<%= enc(selectedMatterUuid) %>&template_uuid=<%= enc(selectedTemplateUuid) %>">Template Editor</a>
    <a class="btn btn-ghost" href="<%= ctx %>/case_fields.jsp?matter_uuid=<%= enc(selectedMatterUuid) %>&template_uuid=<%= enc(selectedTemplateUuid) %>">Case Fields</a>
    <a class="btn btn-ghost" href="<%= ctx %>/token_guide.jsp?matter_uuid=<%= enc(selectedMatterUuid) %>&template_uuid=<%= enc(selectedTemplateUuid) %>">Token Guide</a>
  </div>
</section>

<section class="card" style="margin-top:12px;">
  <% if (selectedCase == null) { %>
    <div class="muted">Select a case to browse assembled forms.</div>
  <% } else { %>
    <div class="meta" style="margin-bottom:8px;">
      Case: <strong><%= esc(safe(selectedCase.label)) %></strong>
      <% if (!safe(selectedCase.causeDocketNumber).isBlank()) { %>
        • <%= esc(safe(selectedCase.causeDocketNumber)) %>
      <% } %>
      • Active: <strong><%= inProgress.size() + completed.size() %></strong>
      • Trashed: <strong><%= trashedRecords.size() %></strong>
    </div>

    <h2 style="margin:0 0 8px 0;">In Progress</h2>
    <% if (inProgress.isEmpty()) { %>
      <div class="muted" style="margin-bottom:14px;">No in-progress assemblies for this case.</div>
    <% } else { %>
      <div class="table-wrap" style="margin-bottom:14px;">
        <table class="table">
          <thead>
            <tr>
              <th style="width:210px;">Updated</th>
              <th>Template</th>
              <th style="width:120px;">Overrides</th>
              <th style="width:240px;">User</th>
              <th style="width:220px;">Actions</th>
            </tr>
          </thead>
          <tbody>
            <% for (int i = 0; i < inProgress.size(); i++) {
                 assembled_forms.AssemblyRec r = inProgress.get(i);
                 if (r == null) continue;
                 String openTemplateUuid = safe(r.templateUuid).isBlank() ? selectedTemplateUuid : safe(r.templateUuid);
            %>
              <tr>
                <td><code><%= esc(safe(r.updatedAt)) %></code></td>
                <td>
                  <strong><%= esc(safe(r.templateLabel).isBlank() ? "(Unnamed Template)" : safe(r.templateLabel)) %></strong>
                  <% if (!safe(r.templateExt).isBlank()) { %>
                    <span class="meta">(<%= esc(safe(r.templateExt).toUpperCase(Locale.ROOT)) %>)</span>
                  <% } %>
                </td>
                <td><%= r.overrideCount %></td>
                <td><%= esc(safe(r.userEmail).isBlank() ? "Unknown user" : safe(r.userEmail)) %></td>
                <td>
                  <div style="display:flex; gap:8px; flex-wrap:wrap;">
                    <a class="btn btn-ghost" href="<%= ctx %>/forms.jsp?matter_uuid=<%= enc(selectedMatterUuid) %>&template_uuid=<%= enc(openTemplateUuid) %>&assembly_uuid=<%= enc(safe(r.uuid)) %>&focus=1&render_preview=1">Continue</a>
                    <form method="post" action="<%= ctx %>/assembled_forms.jsp" style="margin:0;">
                      <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
                      <input type="hidden" name="action" value="trash_assembly" />
                      <input type="hidden" name="matter_uuid" value="<%= esc(selectedMatterUuid) %>" />
                      <input type="hidden" name="template_uuid" value="<%= esc(selectedTemplateUuid) %>" />
                      <input type="hidden" name="show_trashed" value="<%= showTrashed ? "1" : "0" %>" />
                      <input type="hidden" name="assembly_uuid" value="<%= esc(safe(r.uuid)) %>" />
                      <button class="btn btn-ghost" type="submit" onclick="return confirm('Move this assembled form to trash?');">Trash</button>
                    </form>
                  </div>
                </td>
              </tr>
            <% } %>
          </tbody>
        </table>
      </div>
    <% } %>

    <h2 style="margin:0 0 8px 0;">Completed</h2>
    <% if (completed.isEmpty()) { %>
      <div class="muted">No completed assemblies for this case yet.</div>
    <% } else { %>
      <div class="meta" style="margin-bottom:8px;">Convert completed assembly output into a document version: either one new document (one part, one version) or a version in an existing document part.</div>
      <% if (activeDocuments.isEmpty()) { %>
        <div class="meta" style="margin-bottom:10px;">No active documents yet. Create one in <a href="<%= ctx %>/documents.jsp?case_uuid=<%= enc(selectedMatterUuid) %>">Documents</a>.</div>
      <% } else if (activePartCount == 0) { %>
        <div class="meta" style="margin-bottom:10px;">No active parts yet. Add a part in <a href="<%= ctx %>/documents.jsp?case_uuid=<%= enc(selectedMatterUuid) %>">Documents → Parts</a>.</div>
      <% } %>
      <div class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th style="width:210px;">Updated</th>
              <th>Template</th>
              <th style="width:120px;">Overrides</th>
              <th style="width:140px;">Output Size</th>
              <th style="width:120px;">Sync</th>
              <th style="width:620px;">Actions</th>
            </tr>
          </thead>
          <tbody>
            <% for (int i = 0; i < completed.size(); i++) {
                 assembled_forms.AssemblyRec r = completed.get(i);
                 if (r == null) continue;
                 String openTemplateUuid = safe(r.templateUuid).isBlank() ? selectedTemplateUuid : safe(r.templateUuid);
                 String templatePart = safe(r.templateLabel).trim().isBlank() ? "Assembled Form" : safe(r.templateLabel).trim();
                 String suggestedVersionLabel = templatePart + " - assembled";
                 String suggestedDocTitle = templatePart + " (Assembled)";
            %>
              <tr>
                <td><code><%= esc(safe(r.updatedAt)) %></code></td>
                <td>
                  <strong><%= esc(safe(r.templateLabel).isBlank() ? "(Unnamed Template)" : safe(r.templateLabel)) %></strong>
                  <% if (!safe(r.outputFileName).isBlank()) { %>
                    <div class="meta" style="margin-top:3px;"><%= esc(safe(r.outputFileName)) %></div>
                  <% } %>
                </td>
                <td><%= r.overrideCount %></td>
                <td><%= r.outputSizeBytes %> bytes</td>
                <%
                  String syncState = safe(assembledStore.syncState(tenantUuid, selectedMatterUuid, safe(r.uuid))).toLowerCase(Locale.ROOT);
                  String syncLabel = "Pending";
                  if ("synced".equals(syncState)) syncLabel = "Synced";
                  if ("failed".equals(syncState)) syncLabel = "Failed";
                %>
                <td><strong><%= esc(syncLabel) %></strong></td>
                <td>
                  <div style="display:flex; gap:8px; flex-wrap:wrap; align-items:center;">
                    <a class="btn btn-ghost" href="<%= ctx %>/forms.jsp?matter_uuid=<%= enc(selectedMatterUuid) %>&template_uuid=<%= enc(openTemplateUuid) %>&assembly_uuid=<%= enc(safe(r.uuid)) %>&focus=1&render_preview=1">Open</a>
                    <form method="post" action="<%= ctx %>/assembled_forms.jsp" style="margin:0;">
                      <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
                      <input type="hidden" name="action" value="download_completed" />
                      <input type="hidden" name="matter_uuid" value="<%= esc(selectedMatterUuid) %>" />
                      <input type="hidden" name="template_uuid" value="<%= esc(selectedTemplateUuid) %>" />
                      <input type="hidden" name="show_trashed" value="<%= showTrashed ? "1" : "0" %>" />
                      <input type="hidden" name="assembly_uuid" value="<%= esc(safe(r.uuid)) %>" />
                      <button class="btn" type="submit">Download</button>
                    </form>
                    <% if ("failed".equals(syncState) || "pending".equals(syncState)) { %>
                    <form method="post" action="<%= ctx %>/assembled_forms.jsp" style="margin:0;">
                      <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
                      <input type="hidden" name="action" value="retry_sync" />
                      <input type="hidden" name="matter_uuid" value="<%= esc(selectedMatterUuid) %>" />
                      <input type="hidden" name="template_uuid" value="<%= esc(selectedTemplateUuid) %>" />
                      <input type="hidden" name="show_trashed" value="<%= showTrashed ? "1" : "0" %>" />
                      <input type="hidden" name="assembly_uuid" value="<%= esc(safe(r.uuid)) %>" />
                      <button class="btn btn-ghost" type="submit">Retry Sync</button>
                    </form>
                    <% } %>
                    <form method="post" action="<%= ctx %>/assembled_forms.jsp" style="margin:0;">
                      <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
                      <input type="hidden" name="action" value="trash_assembly" />
                      <input type="hidden" name="matter_uuid" value="<%= esc(selectedMatterUuid) %>" />
                      <input type="hidden" name="template_uuid" value="<%= esc(selectedTemplateUuid) %>" />
                      <input type="hidden" name="show_trashed" value="<%= showTrashed ? "1" : "0" %>" />
                      <input type="hidden" name="assembly_uuid" value="<%= esc(safe(r.uuid)) %>" />
                      <button class="btn btn-ghost" type="submit" onclick="return confirm('Move this assembled form to trash?');">Trash</button>
                    </form>
                  </div>

                  <details style="margin-top:10px;">
                    <summary style="cursor:pointer;">Convert To Document Version</summary>
                    <div style="display:grid; grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); gap:10px; margin-top:10px;">
                      <form method="post" action="<%= ctx %>/assembled_forms.jsp" style="border:1px solid var(--border); border-radius:10px; padding:10px; background:rgba(0,0,0,0.02);">
                        <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
                        <input type="hidden" name="action" value="convert_new_document" />
                        <input type="hidden" name="matter_uuid" value="<%= esc(selectedMatterUuid) %>" />
                        <input type="hidden" name="template_uuid" value="<%= esc(selectedTemplateUuid) %>" />
                        <input type="hidden" name="show_trashed" value="<%= showTrashed ? "1" : "0" %>" />
                        <input type="hidden" name="assembly_uuid" value="<%= esc(safe(r.uuid)) %>" />
                        <div style="font-weight:600; margin-bottom:8px;">New Document</div>
                        <label><span>Document Title</span><input type="text" name="new_document_title" value="<%= esc(suggestedDocTitle) %>" required /></label>
                        <label><span>Part Label</span><input type="text" name="new_part_label" value="Lead" required /></label>
                        <label><span>Version Label</span><input type="text" name="version_label" value="<%= esc(suggestedVersionLabel) %>" required /></label>
                        <label><span>Version Notes</span><input type="text" name="version_notes" placeholder="optional" /></label>
                        <button class="btn" type="submit" style="margin-top:8px;">Convert To New Document</button>
                      </form>

                      <form method="post" action="<%= ctx %>/assembled_forms.jsp" class="assembly-convert-existing" style="border:1px solid var(--border); border-radius:10px; padding:10px; background:rgba(0,0,0,0.02);">
                        <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
                        <input type="hidden" name="action" value="convert_existing_document" />
                        <input type="hidden" name="matter_uuid" value="<%= esc(selectedMatterUuid) %>" />
                        <input type="hidden" name="template_uuid" value="<%= esc(selectedTemplateUuid) %>" />
                        <input type="hidden" name="show_trashed" value="<%= showTrashed ? "1" : "0" %>" />
                        <input type="hidden" name="assembly_uuid" value="<%= esc(safe(r.uuid)) %>" />
                        <div style="font-weight:600; margin-bottom:8px;">Existing Document + Part</div>
                        <label>
                          <span>Document</span>
                          <select name="target_doc_uuid" class="convert-doc-select" <%= activePartCount > 0 ? "required" : "disabled" %>>
                            <option value="">Select document</option>
                            <% for (int di = 0; di < activeDocuments.size(); di++) {
                                 documents.DocumentRec d = activeDocuments.get(di);
                                 if (d == null) continue;
                                 List<document_parts.PartRec> docParts = activePartsByDoc.get(safe(d.uuid));
                                 if (docParts == null || docParts.isEmpty()) continue;
                                 String did = safe(d.uuid);
                            %>
                              <option value="<%= esc(did) %>" <%= did.equals(firstDocWithPartsUuid) ? "selected" : "" %>><%= esc(safe(d.title)) %></option>
                            <% } %>
                          </select>
                        </label>
                        <label>
                          <span>Part</span>
                          <select name="target_part_uuid" class="convert-part-select" <%= activePartCount > 0 ? "required" : "disabled" %>>
                            <option value="">Select part</option>
                            <% for (int di = 0; di < activeDocuments.size(); di++) {
                                 documents.DocumentRec d = activeDocuments.get(di);
                                 if (d == null) continue;
                                 String did = safe(d.uuid);
                                 List<document_parts.PartRec> docParts = activePartsByDoc.get(did);
                                 if (docParts == null) continue;
                                 for (int pi = 0; pi < docParts.size(); pi++) {
                                   document_parts.PartRec p = docParts.get(pi);
                                   if (p == null) continue;
                            %>
                              <option value="<%= esc(safe(p.uuid)) %>" data-doc="<%= esc(did) %>"><%= esc(safe(p.label)) %> — <%= esc(safe(d.title)) %></option>
                            <%   }
                               } %>
                          </select>
                        </label>
                        <label><span>Version Label</span><input type="text" name="version_label" value="<%= esc(suggestedVersionLabel) %>" required /></label>
                        <label><span>Version Notes</span><input type="text" name="version_notes" placeholder="optional" /></label>
                        <button class="btn" type="submit" style="margin-top:8px;" <%= activePartCount > 0 ? "" : "disabled" %>>Convert To Existing Part</button>
                      </form>
                    </div>
                  </details>
                </td>
              </tr>
            <% } %>
          </tbody>
        </table>
      </div>
    <% } %>

    <% if (showTrashed) { %>
      <h2 style="margin:14px 0 8px 0;">Trashed</h2>
      <% if (trashedRecords.isEmpty()) { %>
        <div class="muted">No trashed assembled forms for this case.</div>
      <% } else { %>
        <div class="table-wrap">
          <table class="table">
            <thead>
              <tr>
                <th style="width:210px;">Updated</th>
                <th>Template</th>
                <th style="width:120px;">Status</th>
                <th style="width:220px;">Actions</th>
              </tr>
            </thead>
            <tbody>
              <% for (int i = 0; i < trashedRecords.size(); i++) {
                   assembled_forms.AssemblyRec r = trashedRecords.get(i);
                   if (r == null) continue;
                   String openTemplateUuid = safe(r.templateUuid).isBlank() ? selectedTemplateUuid : safe(r.templateUuid);
              %>
                <tr>
                  <td><code><%= esc(safe(r.updatedAt)) %></code></td>
                  <td><strong><%= esc(safe(r.templateLabel).isBlank() ? "(Unnamed Template)" : safe(r.templateLabel)) %></strong></td>
                  <td><%= "completed".equals(safe(r.status)) ? "Completed" : "In Progress" %></td>
                  <td>
                    <div style="display:flex; gap:8px; flex-wrap:wrap;">
                      <a class="btn btn-ghost" href="<%= ctx %>/forms.jsp?matter_uuid=<%= enc(selectedMatterUuid) %>&template_uuid=<%= enc(openTemplateUuid) %>&assembly_uuid=<%= enc(safe(r.uuid)) %>&focus=1&render_preview=1">Open</a>
                      <form method="post" action="<%= ctx %>/assembled_forms.jsp" style="margin:0;">
                        <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
                        <input type="hidden" name="action" value="restore_assembly" />
                        <input type="hidden" name="matter_uuid" value="<%= esc(selectedMatterUuid) %>" />
                        <input type="hidden" name="template_uuid" value="<%= esc(selectedTemplateUuid) %>" />
                        <input type="hidden" name="show_trashed" value="1" />
                        <input type="hidden" name="assembly_uuid" value="<%= esc(safe(r.uuid)) %>" />
                        <button class="btn" type="submit">Restore</button>
                      </form>
                    </div>
                  </td>
                </tr>
              <% } %>
            </tbody>
          </table>
        </div>
      <% } %>
    <% } else if (!trashedRecords.isEmpty()) { %>
      <div class="meta" style="margin-top:12px;"><%= trashedRecords.size() %> assembled form(s) in trash. Enable "Show Trashed" to restore.</div>
    <% } %>
  <% } %>
</section>

<script>
(function () {
  var forms = document.querySelectorAll("form.assembly-convert-existing");
  function syncPartOptions(form) {
    if (!form) return;
    var docSelect = form.querySelector(".convert-doc-select");
    var partSelect = form.querySelector(".convert-part-select");
    if (!docSelect || !partSelect) return;
    var targetDoc = docSelect.value;
    var hasVisible = false;
    var selectedStillVisible = false;
    for (var i = 0; i < partSelect.options.length; i++) {
      var opt = partSelect.options[i];
      if (i === 0) {
        opt.hidden = false;
        opt.disabled = false;
        continue;
      }
      var matches = !!targetDoc && opt.getAttribute("data-doc") === targetDoc;
      opt.hidden = !matches;
      opt.disabled = !matches;
      if (matches) hasVisible = true;
      if (matches && opt.value === partSelect.value) selectedStillVisible = true;
    }
    partSelect.disabled = !targetDoc || !hasVisible;
    if (!selectedStillVisible) partSelect.value = "";
  }
  for (var i = 0; i < forms.length; i++) {
    var form = forms[i];
    syncPartOptions(form);
    var docSelect = form.querySelector(".convert-doc-select");
    if (!docSelect) continue;
    docSelect.addEventListener("change", (function (f) {
      return function () { syncPartOptions(f); };
    })(form));
  }
})();
</script>

<jsp:include page="footer.jsp" />
