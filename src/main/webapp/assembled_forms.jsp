<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" isELIgnored="true" %>

<%@ page import="java.net.URLEncoder" %>
<%@ page import="java.nio.charset.StandardCharsets" %>
<%@ page import="java.util.ArrayList" %>
<%@ page import="java.util.List" %>
<%@ page import="java.util.Locale" %>

<%@ page import="net.familylawandprobate.controversies.assembled_forms" %>
<%@ page import="net.familylawandprobate.controversies.form_templates" %>
<%@ page import="net.familylawandprobate.controversies.matters" %>

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

  private static String contentTypeForExt(String ext) {
    String e = safe(ext).trim().toLowerCase(Locale.ROOT);
    if (e.equals("docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    if (e.equals("doc")) return "application/msword";
    if (e.equals("rtf")) return "application/rtf";
    if (e.equals("txt")) return "text/plain; charset=UTF-8";
    return "application/octet-stream";
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

  try { matterStore.ensure(tenantUuid); } catch (Exception ignored) {}
  try { templateStore.ensure(tenantUuid); } catch (Exception ignored) {}

  String csrfToken = csrfForRender(request);

  String message = null;
  String error = null;

  String selectedMatterUuid = safe(request.getParameter("matter_uuid")).trim();
  String selectedTemplateUuid = safe(request.getParameter("template_uuid")).trim();



  if ("POST".equalsIgnoreCase(request.getMethod()) && "retry_sync".equalsIgnoreCase(safe(request.getParameter("action")).trim())) {
    selectedMatterUuid = safe(request.getParameter("matter_uuid")).trim();
    selectedTemplateUuid = safe(request.getParameter("template_uuid")).trim();
    String assemblyUuid = safe(request.getParameter("assembly_uuid")).trim();
    boolean retried = assembledStore.retrySyncNow(tenantUuid, selectedMatterUuid, assemblyUuid);
    if (retried) {
      message = "Sync retry queued.";
    } else {
      error = "Unable to queue sync retry.";
    }
  }

  if ("POST".equalsIgnoreCase(request.getMethod()) && "download_completed".equalsIgnoreCase(safe(request.getParameter("action")).trim())) {
    selectedMatterUuid = safe(request.getParameter("matter_uuid")).trim();
    selectedTemplateUuid = safe(request.getParameter("template_uuid")).trim();
    String assemblyUuid = safe(request.getParameter("assembly_uuid")).trim();
    try {
      assembled_forms.AssemblyRec rec = assembledStore.getByUuid(tenantUuid, selectedMatterUuid, assemblyUuid);
      if (rec == null || !"completed".equals(safe(rec.status))) {
        throw new IllegalArgumentException("Completed assembled form was not found.");
      }
      byte[] bytes = assembledStore.readOutputBytes(tenantUuid, selectedMatterUuid, assemblyUuid);
      if (bytes == null || bytes.length == 0) {
        throw new IllegalArgumentException("Saved assembled output file is unavailable.");
      }
      String ext = safe(rec.outputFileExt).isBlank() ? safe(rec.templateExt) : safe(rec.outputFileExt);
      String contentType = contentTypeForExt(ext);
      String filename = safe(rec.outputFileName).trim();
      if (filename.isBlank()) {
        String templatePart = safe(rec.templateLabel).trim().isBlank() ? "assembled-form" : safe(rec.templateLabel).trim().replaceAll("[^A-Za-z0-9._-]", "_");
        filename = templatePart + "." + (safe(ext).isBlank() ? "txt" : safe(ext).toLowerCase(Locale.ROOT));
      }

      response.setContentType(contentType);
      response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
      response.getOutputStream().write(bytes);
      return;
    } catch (Exception ex) {
      error = "Unable to download assembled form: " + safe(ex.getMessage());
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

  List<assembled_forms.AssemblyRec> inProgress = new ArrayList<assembled_forms.AssemblyRec>();
  List<assembled_forms.AssemblyRec> completed = new ArrayList<assembled_forms.AssemblyRec>();
  for (int i = 0; i < records.size(); i++) {
    assembled_forms.AssemblyRec r = records.get(i);
    if (r == null) continue;
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
    <div class="grid" style="display:grid; grid-template-columns: 2fr 2fr auto; gap:12px;">
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

      <label>
        <span>&nbsp;</span>
        <button class="btn" type="submit">Load</button>
      </label>
    </div>
  </form>

  <div class="actions" style="display:flex; gap:10px; margin-top:10px; flex-wrap:wrap;">
    <a class="btn btn-ghost" href="<%= ctx %>/forms.jsp?matter_uuid=<%= enc(selectedMatterUuid) %>&template_uuid=<%= enc(selectedTemplateUuid) %>">Back To Form Assembly</a>
    <a class="btn btn-ghost" href="<%= ctx %>/template_library.jsp?matter_uuid=<%= enc(selectedMatterUuid) %>&template_uuid=<%= enc(selectedTemplateUuid) %>">Template Library</a>
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
                <td><%= esc(safe(r.userEmail).isBlank() ? safe(r.userUuid) : safe(r.userEmail)) %></td>
                <td>
                  <a class="btn btn-ghost" href="<%= ctx %>/forms.jsp?matter_uuid=<%= enc(selectedMatterUuid) %>&template_uuid=<%= enc(openTemplateUuid) %>&assembly_uuid=<%= enc(safe(r.uuid)) %>&focus=1&render_preview=1">Continue</a>
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
      <div class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th style="width:210px;">Updated</th>
              <th>Template</th>
              <th style="width:120px;">Overrides</th>
              <th style="width:140px;">Output Size</th>
              <th style="width:120px;">Sync</th>
              <th style="width:280px;">Actions</th>
            </tr>
          </thead>
          <tbody>
            <% for (int i = 0; i < completed.size(); i++) {
                 assembled_forms.AssemblyRec r = completed.get(i);
                 if (r == null) continue;
                 String openTemplateUuid = safe(r.templateUuid).isBlank() ? selectedTemplateUuid : safe(r.templateUuid);
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
                      <input type="hidden" name="assembly_uuid" value="<%= esc(safe(r.uuid)) %>" />
                      <button class="btn" type="submit">Download</button>
                    </form>
                    <% if ("failed".equals(syncState) || "pending".equals(syncState)) { %>
                    <form method="post" action="<%= ctx %>/assembled_forms.jsp" style="margin:0;">
                      <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
                      <input type="hidden" name="action" value="retry_sync" />
                      <input type="hidden" name="matter_uuid" value="<%= esc(selectedMatterUuid) %>" />
                      <input type="hidden" name="template_uuid" value="<%= esc(selectedTemplateUuid) %>" />
                      <input type="hidden" name="assembly_uuid" value="<%= esc(safe(r.uuid)) %>" />
                      <button class="btn btn-ghost" type="submit">Retry Sync</button>
                    </form>
                    <% } %>
                  </div>
                </td>
              </tr>
            <% } %>
          </tbody>
        </table>
      </div>
    <% } %>
  <% } %>
</section>

<jsp:include page="footer.jsp" />
