<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" isELIgnored="true" %>

<%@ page import="java.net.URLEncoder" %>
<%@ page import="java.nio.charset.StandardCharsets" %>
<%@ page import="java.util.ArrayList" %>
<%@ page import="java.util.Base64" %>
<%@ page import="java.util.List" %>
<%@ page import="java.util.Locale" %>

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

  private static byte[] decodeBase64(String b64) {
    String raw = safe(b64).trim();
    if (raw.isBlank()) return new byte[0];
    try {
      return Base64.getDecoder().decode(raw);
    } catch (Exception ignored) {
      return new byte[0];
    }
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

  try { matterStore.ensure(tenantUuid); } catch (Exception ignored) {}
  try { templateStore.ensure(tenantUuid); } catch (Exception ignored) {}

  String csrfToken = csrfForRender(request);

  String message = null;
  String error = null;

  String selectedMatterUuid = safe(request.getParameter("matter_uuid")).trim();
  String selectedTemplateUuid = safe(request.getParameter("template_uuid")).trim();

  String action = "";

  if ("POST".equalsIgnoreCase(request.getMethod())) {
    action = safe(request.getParameter("action")).trim();
    selectedMatterUuid = safe(request.getParameter("matter_uuid")).trim();
    selectedTemplateUuid = safe(request.getParameter("template_uuid")).trim();

    if ("create_template".equalsIgnoreCase(action)) {
      String label = safe(request.getParameter("new_template_label")).trim();
      String uploadName = safe(request.getParameter("upload_file_name")).trim();
      byte[] uploadBytes = decodeBase64(request.getParameter("upload_file_b64"));
      try {
        form_templates.TemplateRec rec = templateStore.create(tenantUuid, label, uploadName, uploadBytes);
        response.sendRedirect(ctx + "/template_library.jsp?matter_uuid=" + enc(selectedMatterUuid) + "&template_uuid=" + enc(rec.uuid) + "&created=1");
        return;
      } catch (Exception ex) {
        error = "Unable to create template: " + safe(ex.getMessage());
      }
    }

    if ("save_template_label".equalsIgnoreCase(action)) {
      String label = safe(request.getParameter("template_label")).trim();
      try {
        boolean ok = templateStore.updateMeta(tenantUuid, selectedTemplateUuid, label);
        if (!ok) throw new IllegalStateException("Template not found.");
        response.sendRedirect(ctx + "/template_library.jsp?matter_uuid=" + enc(selectedMatterUuid) + "&template_uuid=" + enc(selectedTemplateUuid) + "&saved=1");
        return;
      } catch (Exception ex) {
        error = "Unable to save template metadata: " + safe(ex.getMessage());
      }
    }

    if ("replace_template_file".equalsIgnoreCase(action)) {
      String uploadName = safe(request.getParameter("replace_file_name")).trim();
      byte[] uploadBytes = decodeBase64(request.getParameter("replace_file_b64"));
      try {
        boolean ok = templateStore.replaceFile(tenantUuid, selectedTemplateUuid, uploadName, uploadBytes);
        if (!ok) throw new IllegalStateException("Template not found.");
        response.sendRedirect(ctx + "/template_library.jsp?matter_uuid=" + enc(selectedMatterUuid) + "&template_uuid=" + enc(selectedTemplateUuid) + "&file_saved=1");
        return;
      } catch (Exception ex) {
        error = "Unable to replace template file: " + safe(ex.getMessage());
      }
    }

    if ("delete_template".equalsIgnoreCase(action)) {
      try {
        templateStore.delete(tenantUuid, selectedTemplateUuid);
        response.sendRedirect(ctx + "/template_library.jsp?matter_uuid=" + enc(selectedMatterUuid) + "&deleted=1");
        return;
      } catch (Exception ex) {
        error = "Unable to delete template: " + safe(ex.getMessage());
      }
    }
  }

  if ("1".equals(request.getParameter("created"))) message = "Template created.";
  if ("1".equals(request.getParameter("saved"))) message = "Template details saved.";
  if ("1".equals(request.getParameter("file_saved"))) message = "Template file replaced.";
  if ("1".equals(request.getParameter("deleted"))) message = "Template deleted.";

  List<matters.MatterRec> allCases = new ArrayList<matters.MatterRec>();
  try { allCases = matterStore.listAll(tenantUuid); } catch (Exception ignored) {}

  List<matters.MatterRec> activeCases = new ArrayList<matters.MatterRec>();
  for (int i = 0; i < allCases.size(); i++) {
    matters.MatterRec c = allCases.get(i);
    if (c == null) continue;
    if (!c.trashed) activeCases.add(c);
  }

  if (selectedMatterUuid.isBlank() && !activeCases.isEmpty()) selectedMatterUuid = safe(activeCases.get(0).uuid);

  List<form_templates.TemplateRec> templates = new ArrayList<form_templates.TemplateRec>();
  try { templates = templateStore.list(tenantUuid); } catch (Exception ignored) {}
  if (selectedTemplateUuid.isBlank() && !templates.isEmpty()) selectedTemplateUuid = safe(templates.get(0).uuid);

  form_templates.TemplateRec selectedTemplate = null;
  for (int i = 0; i < templates.size(); i++) {
    form_templates.TemplateRec t = templates.get(i);
    if (t == null) continue;
    if (safe(t.uuid).trim().equals(selectedTemplateUuid)) {
      selectedTemplate = t;
      break;
    }
  }
  if (selectedTemplate == null && !templates.isEmpty()) {
    selectedTemplate = templates.get(0);
    selectedTemplateUuid = safe(selectedTemplate.uuid);
  }
%>

<jsp:include page="header.jsp" />

<section class="card">
  <h1 style="margin:0;">Template Library</h1>
  <div class="meta" style="margin-top:4px;">Manage legal document templates used for assembly.</div>

  <% if (message != null) { %>
    <div class="alert alert-ok" style="margin-top:12px;"><%= esc(message) %></div>
  <% } %>
  <% if (error != null) { %>
    <div class="alert alert-error" style="margin-top:12px;"><%= esc(error) %></div>
  <% } %>
</section>

<section class="card" style="margin-top:12px;">
  <form class="form" method="get" action="<%= ctx %>/template_library.jsp">
    <div class="grid" style="display:grid; grid-template-columns: 2fr 2fr auto; gap:12px;">
      <label>
        <span>Case Context</span>
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
        <span>Template</span>
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
    <a class="btn btn-ghost" href="<%= ctx %>/case_fields.jsp?matter_uuid=<%= enc(selectedMatterUuid) %>&template_uuid=<%= enc(selectedTemplateUuid) %>">Case Fields</a>
    <a class="btn btn-ghost" href="<%= ctx %>/token_guide.jsp?matter_uuid=<%= enc(selectedMatterUuid) %>&template_uuid=<%= enc(selectedTemplateUuid) %>">Token Guide</a>
  </div>
</section>

<section class="card" style="margin-top:12px;">
  <form id="createTemplateForm" class="form" method="post" action="<%= ctx %>/template_library.jsp">
    <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
    <input type="hidden" name="action" value="create_template" />
    <input type="hidden" name="matter_uuid" value="<%= esc(selectedMatterUuid) %>" />
    <input type="hidden" name="upload_file_name" id="create_upload_file_name" value="" />
    <input type="hidden" name="upload_file_b64" id="create_upload_file_b64" value="" />

    <label>
      <span>New Template Name</span>
      <input type="text" name="new_template_label" required placeholder="Original Petition" />
    </label>
    <label>
      <span>Template File (.docx, .doc, .rtf)</span>
      <input type="file" id="create_upload_file" accept=".docx,.doc,.rtf" required />
    </label>
    <button class="btn" type="submit">Create Template</button>
  </form>

  <% if (selectedTemplate != null) {
       String templateLabel = safe(selectedTemplate.label);
       String templateFileName = safe(selectedTemplate.fileName);
       String templateExt = safe(selectedTemplate.fileExt);
  %>
    <hr style="margin:14px 0;" />

    <div class="meta" style="margin-bottom:8px;">
      Active template file: <strong><%= esc(templateFileName) %></strong>
      <% if (!templateExt.isBlank()) { %>
        (<%= esc(templateExt.toUpperCase(Locale.ROOT)) %>)
      <% } %>
    </div>

    <form class="form" method="post" action="<%= ctx %>/template_library.jsp">
      <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
      <input type="hidden" name="action" value="save_template_label" />
      <input type="hidden" name="matter_uuid" value="<%= esc(selectedMatterUuid) %>" />
      <input type="hidden" name="template_uuid" value="<%= esc(selectedTemplateUuid) %>" />

      <label>
        <span>Template Name</span>
        <input type="text" name="template_label" value="<%= esc(templateLabel) %>" required />
      </label>
      <button class="btn" type="submit">Save Name</button>
    </form>

    <form id="replaceTemplateFileForm" class="form" method="post" action="<%= ctx %>/template_library.jsp" style="margin-top:10px;">
      <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
      <input type="hidden" name="action" value="replace_template_file" />
      <input type="hidden" name="matter_uuid" value="<%= esc(selectedMatterUuid) %>" />
      <input type="hidden" name="template_uuid" value="<%= esc(selectedTemplateUuid) %>" />
      <input type="hidden" name="replace_file_name" id="replace_file_name" value="" />
      <input type="hidden" name="replace_file_b64" id="replace_file_b64" value="" />

      <label>
        <span>Replace File</span>
        <input type="file" id="replace_upload_file" accept=".docx,.doc,.rtf" required />
      </label>
      <button class="btn btn-ghost" type="submit">Replace File</button>
    </form>

    <form method="post" action="<%= ctx %>/template_library.jsp" style="margin-top:10px;">
      <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
      <input type="hidden" name="action" value="delete_template" />
      <input type="hidden" name="matter_uuid" value="<%= esc(selectedMatterUuid) %>" />
      <input type="hidden" name="template_uuid" value="<%= esc(selectedTemplateUuid) %>" />
      <button class="btn btn-ghost" type="submit" onclick="return confirm('Delete this template?');">Delete Template</button>
    </form>
  <% } %>
</section>

<script>
  function attachEncodedFileSubmit(formId, inputId, nameId, b64Id) {
    var form = document.getElementById(formId);
    var input = document.getElementById(inputId);
    var nameEl = document.getElementById(nameId);
    var b64El = document.getElementById(b64Id);
    if (!form || !input || !nameEl || !b64El) return;

    form.addEventListener("submit", function (e) {
      if (form.dataset.fileReady === "1") {
        form.dataset.fileReady = "0";
        return;
      }

      var f = input.files && input.files.length > 0 ? input.files[0] : null;
      if (!f) {
        e.preventDefault();
        alert("Please choose a template file.");
        return;
      }

      e.preventDefault();
      var reader = new FileReader();
      reader.onload = function () {
        var raw = String(reader.result || "");
        var comma = raw.indexOf(",");
        var b64 = comma >= 0 ? raw.substring(comma + 1) : raw;

        nameEl.value = f.name || "template";
        b64El.value = b64;

        form.dataset.fileReady = "1";
        form.submit();
      };
      reader.readAsDataURL(f);
    });
  }

  attachEncodedFileSubmit("createTemplateForm", "create_upload_file", "create_upload_file_name", "create_upload_file_b64");
  attachEncodedFileSubmit("replaceTemplateFileForm", "replace_upload_file", "replace_file_name", "replace_file_b64");
</script>

<jsp:include page="footer.jsp" />
