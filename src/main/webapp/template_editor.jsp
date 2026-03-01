<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" isELIgnored="true" %>

<%@ page import="java.net.URLEncoder" %>
<%@ page import="java.nio.charset.StandardCharsets" %>
<%@ page import="java.util.ArrayList" %>
<%@ page import="java.util.Arrays" %>
<%@ page import="java.util.LinkedHashMap" %>
<%@ page import="java.util.List" %>
<%@ page import="java.util.Locale" %>
<%@ page import="java.util.Map" %>

<%@ page import="net.familylawandprobate.controversies.document_assembler" %>
<%@ page import="net.familylawandprobate.controversies.document_image_preview" %>
<%@ page import="net.familylawandprobate.controversies.form_templates" %>
<%@ page import="net.familylawandprobate.controversies.matters" %>

<%@ include file="security.jspf" %>
<%
  if (!require_login()) return;
%>

<%!
  private static final String S_TENANT_UUID = "tenant.uuid";
  private static final String CSRF_SESSION_KEY = "CSRF_TOKEN";
  private static final String EDITOR_SESSION_KEY = "template.editor.states";
  private static final int MAX_UNDO_STEPS = 20;
  private static final int MAX_EDITOR_STATES = 24;
  private static final int MAX_TEXT_PREVIEW_CHARS = 5000;

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

  private static int intOr(String raw, int fallback) {
    try {
      return Integer.parseInt(safe(raw).trim());
    } catch (Exception ignored) {
      return fallback;
    }
  }

  private static double parseMargin(String raw) {
    String s = safe(raw).trim();
    if (s.isBlank()) return Double.NaN;
    try {
      return Double.parseDouble(s);
    } catch (Exception ignored) {
      return Double.NaN;
    }
  }

  private static String templateDisplayPath(form_templates.TemplateRec t) {
    if (t == null) return "";
    String folder = safe(t.folderPath).trim();
    String label = safe(t.label).trim();
    if (folder.isBlank()) return label;
    if (label.isBlank()) return folder;
    return folder + "/" + label;
  }

  private static String shortError(Throwable ex) {
    if (ex == null) return "";
    String msg = safe(ex.getMessage()).trim();
    if (!msg.isBlank()) return msg;
    return ex.getClass().getSimpleName();
  }

  private static boolean isDocxExt(String ext) {
    return "docx".equals(safe(ext).trim().toLowerCase(Locale.ROOT));
  }

  private static byte[] copyBytes(byte[] src) {
    return src == null ? new byte[0] : Arrays.copyOf(src, src.length);
  }

  private static final class TemplateEditorState {
    public final String templateUuid;
    public final String fileName;
    public final String fileExt;
    private byte[] savedBytes;
    private byte[] workingBytes;
    private final ArrayList<byte[]> undoStack;
    private final ArrayList<byte[]> redoStack;

    TemplateEditorState(String templateUuid, String fileName, String fileExt, byte[] bytes) {
      this.templateUuid = safe(templateUuid).trim();
      this.fileName = safe(fileName).trim();
      this.fileExt = safe(fileExt).trim().toLowerCase(Locale.ROOT);
      this.savedBytes = copyBytes(bytes);
      this.workingBytes = copyBytes(bytes);
      this.undoStack = new ArrayList<byte[]>();
      this.redoStack = new ArrayList<byte[]>();
    }

    byte[] working() { return copyBytes(workingBytes); }
    byte[] saved() { return copyBytes(savedBytes); }
    int undoCount() { return undoStack.size(); }
    int redoCount() { return redoStack.size(); }
    boolean canUndo() { return !undoStack.isEmpty(); }
    boolean canRedo() { return !redoStack.isEmpty(); }
    boolean isDirty() { return !Arrays.equals(savedBytes, workingBytes); }

    void resetToSaved() {
      this.workingBytes = copyBytes(savedBytes);
      this.undoStack.clear();
      this.redoStack.clear();
    }

    void resetFromSavedBytes(byte[] freshSavedBytes) {
      this.savedBytes = copyBytes(freshSavedBytes);
      this.workingBytes = copyBytes(freshSavedBytes);
      this.undoStack.clear();
      this.redoStack.clear();
    }

    void applyChange(byte[] nextWorkingBytes) {
      byte[] next = copyBytes(nextWorkingBytes);
      if (Arrays.equals(this.workingBytes, next)) return;
      if (this.undoStack.size() >= MAX_UNDO_STEPS) this.undoStack.remove(0);
      this.undoStack.add(copyBytes(this.workingBytes));
      this.workingBytes = next;
      this.redoStack.clear();
    }

    boolean undo() {
      if (undoStack.isEmpty()) return false;
      redoStack.add(copyBytes(workingBytes));
      workingBytes = undoStack.remove(undoStack.size() - 1);
      return true;
    }

    boolean redo() {
      if (redoStack.isEmpty()) return false;
      undoStack.add(copyBytes(workingBytes));
      workingBytes = redoStack.remove(redoStack.size() - 1);
      return true;
    }

    void markSaved() {
      savedBytes = copyBytes(workingBytes);
      undoStack.clear();
      redoStack.clear();
    }
  }
%>

<%
  request.setAttribute("activeNav", "/template_editor.jsp");

  String ctx = request.getContextPath();
  if (ctx == null) ctx = "";

  String tenantUuid = safe((String)session.getAttribute(S_TENANT_UUID)).trim();
  if (tenantUuid.isBlank()) {
    response.sendRedirect(ctx + "/tenant_login.jsp");
    return;
  }

  matters matterStore = matters.defaultStore();
  form_templates templateStore = form_templates.defaultStore();
  document_assembler assembler = new document_assembler();
  document_image_preview previewer = new document_image_preview();

  try { matterStore.ensure(tenantUuid); } catch (Exception ignored) {}
  try { templateStore.ensure(tenantUuid); } catch (Exception ignored) {}

  String csrfToken = csrfForRender(request);

  String message = "";
  String error = "";

  String selectedMatterUuid = safe(request.getParameter("matter_uuid")).trim();
  String selectedTemplateUuid = safe(request.getParameter("template_uuid")).trim();
  String action = "";
  if ("POST".equalsIgnoreCase(request.getMethod())) {
    action = safe(request.getParameter("action")).trim();
    if (selectedMatterUuid.isBlank()) selectedMatterUuid = safe(request.getParameter("selected_matter_uuid")).trim();
    if (selectedTemplateUuid.isBlank()) selectedTemplateUuid = safe(request.getParameter("selected_template_uuid")).trim();
  }

  String headerTextInput = safe(request.getParameter("header_text"));
  String footerTextInput = safe(request.getParameter("footer_text"));
  String footerPrefixInput = safe(request.getParameter("footer_prefix"));
  String fontFamilyInput = safe(request.getParameter("font_family")).trim();
  String fontSizeInput = safe(request.getParameter("font_size_pt")).trim();
  String marginTopInput = safe(request.getParameter("margin_top")).trim();
  String marginRightInput = safe(request.getParameter("margin_right")).trim();
  String marginBottomInput = safe(request.getParameter("margin_bottom")).trim();
  String marginLeftInput = safe(request.getParameter("margin_left")).trim();
  if (fontFamilyInput.isBlank()) fontFamilyInput = "Times New Roman";
  if (fontSizeInput.isBlank()) fontSizeInput = "12";
  if (marginTopInput.isBlank()) marginTopInput = "1.0";
  if (marginRightInput.isBlank()) marginRightInput = "1.0";
  if (marginBottomInput.isBlank()) marginBottomInput = "1.0";
  if (marginLeftInput.isBlank()) marginLeftInput = "1.0";

  List<matters.MatterRec> allCases = new ArrayList<matters.MatterRec>();
  try { allCases = matterStore.listAll(tenantUuid); } catch (Exception ignored) {}
  List<matters.MatterRec> activeCases = new ArrayList<matters.MatterRec>();
  for (int i = 0; i < allCases.size(); i++) {
    matters.MatterRec c = allCases.get(i);
    if (c == null || c.trashed) continue;
    activeCases.add(c);
  }
  if (selectedMatterUuid.isBlank() && !activeCases.isEmpty()) selectedMatterUuid = safe(activeCases.get(0).uuid);

  List<form_templates.TemplateRec> templates = new ArrayList<form_templates.TemplateRec>();
  try { templates = templateStore.list(tenantUuid); } catch (Exception ignored) {}
  if (selectedTemplateUuid.isBlank() && !templates.isEmpty()) selectedTemplateUuid = safe(templates.get(0).uuid);

  form_templates.TemplateRec selectedTemplate = null;
  for (int i = 0; i < templates.size(); i++) {
    form_templates.TemplateRec t = templates.get(i);
    if (t == null) continue;
    if (safe(t.uuid).equals(selectedTemplateUuid)) {
      selectedTemplate = t;
      break;
    }
  }
  if (selectedTemplate == null && !templates.isEmpty()) {
    selectedTemplate = templates.get(0);
    selectedTemplateUuid = safe(selectedTemplate.uuid);
  }

  @SuppressWarnings("unchecked")
  LinkedHashMap<String, TemplateEditorState> editorStates =
      (LinkedHashMap<String, TemplateEditorState>) session.getAttribute(EDITOR_SESSION_KEY);
  if (editorStates == null) {
    editorStates = new LinkedHashMap<String, TemplateEditorState>();
    session.setAttribute(EDITOR_SESSION_KEY, editorStates);
  }

  TemplateEditorState state = null;
  if (selectedTemplate != null) {
    byte[] savedBytes = templateStore.readBytes(tenantUuid, selectedTemplateUuid);
    if (savedBytes == null) savedBytes = new byte[0];

    state = editorStates.get(selectedTemplateUuid);
    boolean mustInitState = state == null
        || !selectedTemplateUuid.equals(safe(state.templateUuid))
        || !safe(selectedTemplate.fileName).equals(safe(state.fileName))
        || !safe(selectedTemplate.fileExt).equalsIgnoreCase(safe(state.fileExt));
    if (mustInitState) {
      state = new TemplateEditorState(selectedTemplateUuid, selectedTemplate.fileName, selectedTemplate.fileExt, savedBytes);
      editorStates.put(selectedTemplateUuid, state);
    } else if (!state.isDirty() && !Arrays.equals(state.saved(), savedBytes)) {
      state.resetFromSavedBytes(savedBytes);
    }

    if (editorStates.size() > MAX_EDITOR_STATES) {
      java.util.Iterator<String> it = editorStates.keySet().iterator();
      while (editorStates.size() > MAX_EDITOR_STATES && it.hasNext()) {
        String key = it.next();
        if (key == null) continue;
        if (key.equals(selectedTemplateUuid)) continue;
        it.remove();
      }
    }
  }

  if ("POST".equalsIgnoreCase(request.getMethod()) && !action.isBlank()) {
    if (selectedTemplate == null || state == null) {
      error = "Select a template first.";
    } else {
      String ext = safe(selectedTemplate.fileExt).trim().toLowerCase(Locale.ROOT);
      boolean docxTools = isDocxExt(ext);
      byte[] currentBytes = state.working();

      try {
        if ("undo".equals(action)) {
          if (state.undo()) {
            message = "Undid the last edit.";
          } else {
            error = "Nothing to undo.";
          }
        } else if ("redo".equals(action)) {
          if (state.redo()) {
            message = "Redid the last edit.";
          } else {
            error = "Nothing to redo.";
          }
        } else if ("revert_saved".equals(action)) {
          state.resetToSaved();
          message = "Reverted editor workspace to the last saved template.";
        } else if ("reload_saved".equals(action)) {
          byte[] fresh = templateStore.readBytes(tenantUuid, selectedTemplateUuid);
          state.resetFromSavedBytes(fresh);
          message = "Reloaded template from storage.";
        } else if ("save_changes".equals(action)) {
          if (!state.isDirty()) {
            message = "No unsaved changes to save.";
          } else {
            boolean ok = templateStore.replaceFile(tenantUuid, selectedTemplateUuid, selectedTemplate.fileName, currentBytes);
            if (!ok) {
              error = "Unable to save template changes.";
            } else {
              state.markSaved();
              selectedTemplate = templateStore.get(tenantUuid, selectedTemplateUuid);
              if (selectedTemplate == null) {
                selectedTemplate = templates.isEmpty() ? null : templates.get(0);
                selectedTemplateUuid = selectedTemplate == null ? "" : safe(selectedTemplate.uuid);
              }
              message = "Template changes saved.";
            }
          }
        } else if ("remove_header".equals(action)) {
          if (!docxTools) throw new IllegalArgumentException("Header/footer and formatting tools currently support DOCX templates only.");
          state.applyChange(assembler.deleteHeader(currentBytes, ext));
          message = "Header removed in editor workspace.";
        } else if ("add_header".equals(action)) {
          if (!docxTools) throw new IllegalArgumentException("Header/footer and formatting tools currently support DOCX templates only.");
          state.applyChange(assembler.addHeader(currentBytes, ext, headerTextInput));
          message = "Header added in editor workspace.";
        } else if ("remove_footer".equals(action)) {
          if (!docxTools) throw new IllegalArgumentException("Header/footer and formatting tools currently support DOCX templates only.");
          state.applyChange(assembler.deleteFooter(currentBytes, ext));
          message = "Footer removed in editor workspace.";
        } else if ("add_footer".equals(action)) {
          if (!docxTools) throw new IllegalArgumentException("Header/footer and formatting tools currently support DOCX templates only.");
          state.applyChange(assembler.addFooter(currentBytes, ext, footerTextInput));
          message = "Footer added in editor workspace.";
        } else if ("add_footer_pagination".equals(action)) {
          if (!docxTools) throw new IllegalArgumentException("Header/footer and formatting tools currently support DOCX templates only.");
          state.applyChange(assembler.addFooterWithPagination(currentBytes, ext, footerPrefixInput));
          message = "Pagination footer added in editor workspace.";
        } else if ("normalize_font_family".equals(action)) {
          if (!docxTools) throw new IllegalArgumentException("Header/footer and formatting tools currently support DOCX templates only.");
          state.applyChange(assembler.normalizeFontFamily(currentBytes, ext, fontFamilyInput));
          message = "Font family normalized in editor workspace.";
        } else if ("normalize_font_size".equals(action)) {
          if (!docxTools) throw new IllegalArgumentException("Header/footer and formatting tools currently support DOCX templates only.");
          int sizePt = intOr(fontSizeInput, 12);
          state.applyChange(assembler.normalizeFontSize(currentBytes, ext, sizePt));
          message = "Font size normalized in editor workspace.";
        } else if ("normalize_margins".equals(action)) {
          if (!docxTools) throw new IllegalArgumentException("Header/footer and formatting tools currently support DOCX templates only.");
          double top = parseMargin(marginTopInput);
          double right = parseMargin(marginRightInput);
          double bottom = parseMargin(marginBottomInput);
          double left = parseMargin(marginLeftInput);
          if (Double.isNaN(top) || Double.isNaN(right) || Double.isNaN(bottom) || Double.isNaN(left)) {
            throw new IllegalArgumentException("All margin values are required.");
          }
          state.applyChange(assembler.normalizeMargins(currentBytes, ext, top, right, bottom, left));
          message = "Margins normalized in editor workspace.";
        } else if ("select_template".equals(action)) {
          // No-op; selection handled by parameters.
        } else {
          error = "Unknown action.";
        }
      } catch (Exception ex) {
        if (error.isBlank()) error = shortError(ex);
      }
    }
  }

  boolean hasSelection = selectedTemplate != null && state != null;
  boolean docxToolsAvailable = hasSelection && isDocxExt(safe(selectedTemplate.fileExt));
  boolean dirty = hasSelection && state.isDirty();
  int undoCount = hasSelection ? state.undoCount() : 0;
  int redoCount = hasSelection ? state.redoCount() : 0;

  document_image_preview.PreviewResult imagePreview = document_image_preview.PreviewResult.empty();
  document_image_preview.PageImage firstPage = null;
  String imageWarning = "";
  String textPreview = "";
  if (hasSelection) {
    try {
      imagePreview = previewer.render(state.working(), safe(selectedTemplate.fileExt), null, 1);
      if (imagePreview != null && imagePreview.pages != null && !imagePreview.pages.isEmpty()) {
        firstPage = imagePreview.pages.get(0);
      }
      imageWarning = safe(imagePreview == null ? "" : imagePreview.warning);
    } catch (Exception ex) {
      imageWarning = "Preview generation failed: " + shortError(ex);
    }
    try {
      document_assembler.PreviewResult txt = assembler.preview(state.working(), safe(selectedTemplate.fileExt), new LinkedHashMap<String, String>());
      textPreview = safe(txt == null ? "" : txt.sourceText);
    } catch (Exception ignored) {
      textPreview = "";
    }
  }
%>
<jsp:include page="header.jsp" />
<div class="container">
  <section class="card">
    <h1 style="margin:0;">Template Editor</h1>
    <div class="meta" style="margin-top:4px;">Edit template structure and formatting with undo/redo. Supports selection/preview across <code>.docx</code>, <code>.doc</code>, <code>.rtf</code>, <code>.odt</code>, and <code>.txt</code>.</div>
  </section>

  <% if (!message.isBlank()) { %>
    <div class="alert alert-ok" style="margin-top:12px;"><%= esc(message) %></div>
  <% } %>
  <% if (!error.isBlank()) { %>
    <div class="alert alert-error" style="margin-top:12px;"><%= esc(error) %></div>
  <% } %>

  <section class="card" style="margin-top:12px;">
    <form class="form" method="get" action="<%= ctx %>/template_editor.jsp">
      <div class="grid cols-3">
        <label>
          <span>Case (for navigation context)</span>
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
                <%= esc(templateDisplayPath(t)) %> (<%= esc(safe(t.fileExt).toUpperCase(Locale.ROOT)) %>)
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
      <a class="btn btn-ghost" href="<%= ctx %>/forms.jsp?matter_uuid=<%= enc(selectedMatterUuid) %>&template_uuid=<%= enc(selectedTemplateUuid) %>">Form Assembly</a>
      <a class="btn btn-ghost" href="<%= ctx %>/template_library.jsp?matter_uuid=<%= enc(selectedMatterUuid) %>&template_uuid=<%= enc(selectedTemplateUuid) %>">Template Library</a>
      <a class="btn btn-ghost" href="<%= ctx %>/assembled_forms.jsp?matter_uuid=<%= enc(selectedMatterUuid) %>&template_uuid=<%= enc(selectedTemplateUuid) %>">Assembled Forms</a>
      <a class="btn btn-ghost" href="<%= ctx %>/token_guide.jsp?matter_uuid=<%= enc(selectedMatterUuid) %>&template_uuid=<%= enc(selectedTemplateUuid) %>">Token Guide</a>
    </div>
  </section>

  <section class="card" style="margin-top:12px;">
    <% if (!hasSelection) { %>
      <div class="muted">Select a template to start editing.</div>
    <% } else { %>
      <div class="meta" style="margin-bottom:8px;">
        Editing template: <strong><%= esc(templateDisplayPath(selectedTemplate)) %></strong><br />
        File: <strong><%= esc(safe(selectedTemplate.fileName)) %></strong> (<%= esc(safe(selectedTemplate.fileExt).toUpperCase(Locale.ROOT)) %>)<br />
        State: <strong><%= dirty ? "Unsaved changes" : "All changes saved" %></strong>
      </div>

      <% if (!docxToolsAvailable) { %>
        <div class="alert alert-warn" style="margin-bottom:10px;">
          Advanced editing tools currently require <code>.docx</code>. For this <%= esc(safe(selectedTemplate.fileExt).toUpperCase(Locale.ROOT)) %> template, preview and navigation remain available.
        </div>
      <% } %>

      <div class="actions" style="display:flex; gap:8px; flex-wrap:wrap; margin-bottom:10px;">
        <form method="post" action="<%= ctx %>/template_editor.jsp" style="margin:0;">
          <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
          <input type="hidden" name="action" value="undo" />
          <input type="hidden" name="selected_matter_uuid" value="<%= esc(selectedMatterUuid) %>" />
          <input type="hidden" name="selected_template_uuid" value="<%= esc(selectedTemplateUuid) %>" />
          <button class="btn btn-ghost" type="submit" <%= undoCount <= 0 ? "disabled" : "" %>>Undo (<%= undoCount %>)</button>
        </form>

        <form method="post" action="<%= ctx %>/template_editor.jsp" style="margin:0;">
          <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
          <input type="hidden" name="action" value="redo" />
          <input type="hidden" name="selected_matter_uuid" value="<%= esc(selectedMatterUuid) %>" />
          <input type="hidden" name="selected_template_uuid" value="<%= esc(selectedTemplateUuid) %>" />
          <button class="btn btn-ghost" type="submit" <%= redoCount <= 0 ? "disabled" : "" %>>Redo (<%= redoCount %>)</button>
        </form>

        <form method="post" action="<%= ctx %>/template_editor.jsp" style="margin:0;">
          <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
          <input type="hidden" name="action" value="revert_saved" />
          <input type="hidden" name="selected_matter_uuid" value="<%= esc(selectedMatterUuid) %>" />
          <input type="hidden" name="selected_template_uuid" value="<%= esc(selectedTemplateUuid) %>" />
          <button class="btn btn-ghost" type="submit" <%= !dirty ? "disabled" : "" %>>Revert To Saved</button>
        </form>

        <form method="post" action="<%= ctx %>/template_editor.jsp" style="margin:0;">
          <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
          <input type="hidden" name="action" value="reload_saved" />
          <input type="hidden" name="selected_matter_uuid" value="<%= esc(selectedMatterUuid) %>" />
          <input type="hidden" name="selected_template_uuid" value="<%= esc(selectedTemplateUuid) %>" />
          <button class="btn btn-ghost" type="submit">Reload From Storage</button>
        </form>

        <form method="post" action="<%= ctx %>/template_editor.jsp" style="margin:0;">
          <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
          <input type="hidden" name="action" value="save_changes" />
          <input type="hidden" name="selected_matter_uuid" value="<%= esc(selectedMatterUuid) %>" />
          <input type="hidden" name="selected_template_uuid" value="<%= esc(selectedTemplateUuid) %>" />
          <button class="btn" type="submit" <%= !dirty ? "disabled" : "" %>>Save Changes</button>
        </form>
      </div>

      <div class="grid cols-2" style="align-items:start; gap:16px;">
        <div>
          <h2 style="margin:0 0 8px 0;">Editor Tools</h2>

          <form class="form" method="post" action="<%= ctx %>/template_editor.jsp">
            <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
            <input type="hidden" name="action" value="add_header" />
            <input type="hidden" name="selected_matter_uuid" value="<%= esc(selectedMatterUuid) %>" />
            <input type="hidden" name="selected_template_uuid" value="<%= esc(selectedTemplateUuid) %>" />
            <label>
              <span>Add Header</span>
              <textarea name="header_text" rows="3" placeholder="Header text"><%= esc(headerTextInput) %></textarea>
            </label>
            <button class="btn btn-ghost" type="submit" <%= !docxToolsAvailable ? "disabled" : "" %>>Add Header</button>
          </form>

          <form method="post" action="<%= ctx %>/template_editor.jsp" style="margin-top:8px;">
            <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
            <input type="hidden" name="action" value="remove_header" />
            <input type="hidden" name="selected_matter_uuid" value="<%= esc(selectedMatterUuid) %>" />
            <input type="hidden" name="selected_template_uuid" value="<%= esc(selectedTemplateUuid) %>" />
            <button class="btn btn-ghost" type="submit" <%= !docxToolsAvailable ? "disabled" : "" %>>Remove Header</button>
          </form>

          <hr style="margin:12px 0;" />

          <form class="form" method="post" action="<%= ctx %>/template_editor.jsp">
            <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
            <input type="hidden" name="action" value="add_footer" />
            <input type="hidden" name="selected_matter_uuid" value="<%= esc(selectedMatterUuid) %>" />
            <input type="hidden" name="selected_template_uuid" value="<%= esc(selectedTemplateUuid) %>" />
            <label>
              <span>Add Footer</span>
              <textarea name="footer_text" rows="3" placeholder="Footer text"><%= esc(footerTextInput) %></textarea>
            </label>
            <button class="btn btn-ghost" type="submit" <%= !docxToolsAvailable ? "disabled" : "" %>>Add Footer</button>
          </form>

          <form method="post" action="<%= ctx %>/template_editor.jsp" style="margin-top:8px;">
            <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
            <input type="hidden" name="action" value="remove_footer" />
            <input type="hidden" name="selected_matter_uuid" value="<%= esc(selectedMatterUuid) %>" />
            <input type="hidden" name="selected_template_uuid" value="<%= esc(selectedTemplateUuid) %>" />
            <button class="btn btn-ghost" type="submit" <%= !docxToolsAvailable ? "disabled" : "" %>>Remove Footer</button>
          </form>

          <form class="form" method="post" action="<%= ctx %>/template_editor.jsp" style="margin-top:8px;">
            <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
            <input type="hidden" name="action" value="add_footer_pagination" />
            <input type="hidden" name="selected_matter_uuid" value="<%= esc(selectedMatterUuid) %>" />
            <input type="hidden" name="selected_template_uuid" value="<%= esc(selectedTemplateUuid) %>" />
            <label>
              <span>Pagination Footer Prefix (optional)</span>
              <input type="text" name="footer_prefix" value="<%= esc(footerPrefixInput) %>" placeholder="Confidential" />
            </label>
            <button class="btn btn-ghost" type="submit" <%= !docxToolsAvailable ? "disabled" : "" %>>Add Footer Pagination</button>
          </form>

          <hr style="margin:12px 0;" />

          <form class="form" method="post" action="<%= ctx %>/template_editor.jsp">
            <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
            <input type="hidden" name="action" value="normalize_font_family" />
            <input type="hidden" name="selected_matter_uuid" value="<%= esc(selectedMatterUuid) %>" />
            <input type="hidden" name="selected_template_uuid" value="<%= esc(selectedTemplateUuid) %>" />
            <label>
              <span>Normalize Font Family</span>
              <input type="text" name="font_family" value="<%= esc(fontFamilyInput) %>" />
            </label>
            <button class="btn btn-ghost" type="submit" <%= !docxToolsAvailable ? "disabled" : "" %>>Normalize Font Family</button>
          </form>

          <form class="form" method="post" action="<%= ctx %>/template_editor.jsp" style="margin-top:8px;">
            <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
            <input type="hidden" name="action" value="normalize_font_size" />
            <input type="hidden" name="selected_matter_uuid" value="<%= esc(selectedMatterUuid) %>" />
            <input type="hidden" name="selected_template_uuid" value="<%= esc(selectedTemplateUuid) %>" />
            <label>
              <span>Normalize Font Size (pt)</span>
              <input type="number" min="6" max="72" step="1" name="font_size_pt" value="<%= esc(fontSizeInput) %>" />
            </label>
            <button class="btn btn-ghost" type="submit" <%= !docxToolsAvailable ? "disabled" : "" %>>Normalize Font Size</button>
          </form>

          <form class="form" method="post" action="<%= ctx %>/template_editor.jsp" style="margin-top:8px;">
            <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
            <input type="hidden" name="action" value="normalize_margins" />
            <input type="hidden" name="selected_matter_uuid" value="<%= esc(selectedMatterUuid) %>" />
            <input type="hidden" name="selected_template_uuid" value="<%= esc(selectedTemplateUuid) %>" />
            <div class="grid cols-2">
              <label>
                <span>Top Margin (in)</span>
                <input type="number" min="0" max="3" step="0.05" name="margin_top" value="<%= esc(marginTopInput) %>" />
              </label>
              <label>
                <span>Right Margin (in)</span>
                <input type="number" min="0" max="3" step="0.05" name="margin_right" value="<%= esc(marginRightInput) %>" />
              </label>
              <label>
                <span>Bottom Margin (in)</span>
                <input type="number" min="0" max="3" step="0.05" name="margin_bottom" value="<%= esc(marginBottomInput) %>" />
              </label>
              <label>
                <span>Left Margin (in)</span>
                <input type="number" min="0" max="3" step="0.05" name="margin_left" value="<%= esc(marginLeftInput) %>" />
              </label>
            </div>
            <button class="btn btn-ghost" type="submit" <%= !docxToolsAvailable ? "disabled" : "" %>>Normalize Margins</button>
          </form>
        </div>

        <div>
          <h2 style="margin:0 0 8px 0;">PNG Preview</h2>
          <% if (!imageWarning.isBlank()) { %>
            <div class="alert alert-warn" style="margin-bottom:8px;"><%= esc(imageWarning) %></div>
          <% } %>
          <% if (firstPage != null && !safe(firstPage.base64Png).isBlank()) { %>
            <img
                src="data:image/png;base64,<%= firstPage.base64Png %>"
                alt="Template preview page 1"
                style="width:100%; border:1px solid rgba(17,24,39,.15); border-radius:10px; background:#fff;" />
          <% } else { %>
            <div class="muted">PNG preview is unavailable for this template right now.</div>
          <% } %>

          <h3 style="margin:12px 0 6px 0;">Text Extraction Preview</h3>
          <% String previewText = safe(textPreview);
             if (previewText.length() > MAX_TEXT_PREVIEW_CHARS) {
               previewText = previewText.substring(0, MAX_TEXT_PREVIEW_CHARS) + "\n\n[Preview truncated]";
             }
          %>
          <pre style="max-height:320px; overflow:auto; border:1px solid rgba(17,24,39,.12); border-radius:10px; padding:10px; background:#fff; margin:0;"><%= esc(previewText) %></pre>
        </div>
      </div>
    <% } %>
  </section>
</div>
<jsp:include page="footer.jsp" />
