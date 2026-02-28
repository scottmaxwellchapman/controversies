<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" isELIgnored="true" %>

<%@ page import="java.net.URLEncoder" %>
<%@ page import="java.nio.charset.StandardCharsets" %>
<%@ page import="java.util.ArrayList" %>
<%@ page import="java.util.LinkedHashMap" %>
<%@ page import="java.util.LinkedHashSet" %>
<%@ page import="java.util.List" %>
<%@ page import="java.util.Locale" %>
<%@ page import="java.util.Map" %>

<%@ page import="net.familylawandprobate.controversies.activity_log" %>
<%@ page import="net.familylawandprobate.controversies.assembled_forms" %>
<%@ page import="net.familylawandprobate.controversies.case_fields" %>
<%@ page import="net.familylawandprobate.controversies.case_list_items" %>
<%@ page import="net.familylawandprobate.controversies.document_assembler" %>
<%@ page import="net.familylawandprobate.controversies.document_image_preview" %>
<%@ page import="net.familylawandprobate.controversies.form_templates" %>
<%@ page import="net.familylawandprobate.controversies.matters" %>
<%@ page import="net.familylawandprobate.controversies.tenant_fields" %>
<%@ page import="net.familylawandprobate.controversies.tenant_settings" %>
<%@ page import="net.familylawandprobate.controversies.users_roles" %>

<%@ include file="security.jspf" %>
<%
  if (!require_login()) return;
%>

<%!
  private static final String S_TENANT_UUID = "tenant.uuid";
  private static final String S_TENANT_LABEL = "tenant.label";
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

  private static String js(String s) {
    if (s == null) return "";
    return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\r", "")
            .replace("\n", "\\n")
            .replace("<", "\\u003c")
            .replace(">", "\\u003e");
  }

  private static String jsonStr(String s) {
    return "\"" + js(s) + "\"";
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

  private static String enc(String s) {
    return URLEncoder.encode(safe(s), StandardCharsets.UTF_8);
  }

  private static String tokenPreview(String token) {
    String t = safe(token).trim();
    if (t.isBlank()) return "";
    return "{{" + t + "}}";
  }

  private static String fileSafe(String s) {
    String t = safe(s).trim();
    if (t.isBlank()) return "assembled-form";
    t = t.replaceAll("[^A-Za-z0-9._-]", "_");
    if (t.length() > 80) t = t.substring(0, 80);
    return t;
  }

  private static void putToken(Map<String,String> values, String key, String value) {
    if (values == null) return;
    String k = safe(key).trim();
    if (k.isBlank()) return;
    values.put(k, safe(value));
  }

  private static String extractCurlyTokenKey(String tokenLiteral) {
    String t = safe(tokenLiteral).trim();
    if (t.length() < 4) return "";
    if (!t.startsWith("{{") || !t.endsWith("}}")) return "";

    String body = safe(t.substring(2, t.length() - 2)).trim();
    if (body.isBlank()) return "";
    for (int i = 0; i < body.length(); i++) {
      char ch = body.charAt(i);
      boolean ok = (ch >= 'A' && ch <= 'Z')
          || (ch >= 'a' && ch <= 'z')
          || (ch >= '0' && ch <= '9')
          || ch == '_' || ch == '-' || ch == '.';
      if (!ok) return "";
    }
    return body;
  }

  private static void applyLiteralOverride(Map<String,String> mergeValues, String tokenLiteral, String tokenValue) {
    if (mergeValues == null) return;
    String literal = safe(tokenLiteral).trim();
    if (literal.isBlank()) return;

    String value = safe(tokenValue);
    mergeValues.put(literal, value);

    String key = extractCurlyTokenKey(literal);
    if (key.isBlank()) return;
    mergeValues.put(key, value);

    int dot = key.indexOf('.');
    if (dot <= 0 || dot + 1 >= key.length()) return;

    String prefix = safe(key.substring(0, dot)).toLowerCase(Locale.ROOT);
    String tail = safe(key.substring(dot + 1)).trim();
    if (tail.isBlank()) return;

    if ("case".equals(prefix) || "kv".equals(prefix)) {
      mergeValues.put("case." + tail, value);
      mergeValues.put("kv." + tail, value);
      mergeValues.put(tail, value);
    } else if ("tenant".equals(prefix)) {
      mergeValues.put("tenant." + tail, value);
      mergeValues.put("kv." + tail, value);
      mergeValues.put(tail, value);
    }
  }
%>

<%
  String ctx = request.getContextPath();
  if (ctx == null) ctx = "";

  String tenantUuid = safe((String)session.getAttribute(S_TENANT_UUID)).trim();
  String tenantLabel = safe((String)session.getAttribute(S_TENANT_LABEL)).trim();
  String userUuid = safe((String)session.getAttribute(users_roles.S_USER_UUID)).trim();
  String userEmail = safe((String)session.getAttribute(users_roles.S_USER_EMAIL)).trim();
  if (tenantUuid.isBlank()) {
    response.sendRedirect(ctx + "/tenant_login.jsp");
    return;
  }

  matters matterStore = matters.defaultStore();
  case_fields caseStore = case_fields.defaultStore();
  case_list_items caseListStore = case_list_items.defaultStore();
  tenant_fields tenantStore = tenant_fields.defaultStore();
  tenant_settings tenantSettingsStore = tenant_settings.defaultStore();
  form_templates templateStore = form_templates.defaultStore();
  assembled_forms assembledStore = assembled_forms.defaultStore();
  document_assembler assembler = new document_assembler();
  document_image_preview imagePreviewer = new document_image_preview();
  activity_log activityLog = activity_log.defaultStore();

  try { matterStore.ensure(tenantUuid); } catch (Exception ignored) {}
  try { templateStore.ensure(tenantUuid); } catch (Exception ignored) {}

  String csrfToken = csrfForRender(request);

  String message = null;
  String error = null;

  String selectedMatterUuid = safe(request.getParameter("matter_uuid")).trim();
  String selectedTemplateUuid = safe(request.getParameter("template_uuid")).trim();
  String selectedAssemblyUuid = safe(request.getParameter("assembly_uuid")).trim();
  String focusTokenParam = safe(request.getParameter("focus_token")).trim();
  boolean focusMode = "1".equals(safe(request.getParameter("focus")).trim());
  boolean renderPreview = !"0".equals(safe(request.getParameter("render_preview")).trim());
  boolean resumePromptAfterLists = "1".equals(safe(request.getParameter("resume_prompt")).trim());
  boolean assembleAfterResume = "1".equals(safe(request.getParameter("assemble_after")).trim());
  String focusQs = focusMode ? "&focus=1" : "";
  String renderPreviewQs = renderPreview ? "&render_preview=1" : "&render_preview=0";
  String assemblyQs = selectedAssemblyUuid.isBlank() ? "" : "&assembly_uuid=" + enc(selectedAssemblyUuid);

  String action = "";
  boolean wantsDownload = false;

  if ("POST".equalsIgnoreCase(request.getMethod())) {
    action = safe(request.getParameter("action")).trim();
    selectedMatterUuid = safe(request.getParameter("matter_uuid")).trim();
    selectedTemplateUuid = safe(request.getParameter("template_uuid")).trim();
    selectedAssemblyUuid = safe(request.getParameter("assembly_uuid")).trim();

    if ("download_assembled".equalsIgnoreCase(action)) {
      wantsDownload = true;
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

  List<form_templates.TemplateRec> templates = new ArrayList<form_templates.TemplateRec>();
  try { templates = templateStore.list(tenantUuid); } catch (Exception ignored) {}

  if (selectedTemplateUuid.isBlank() && !templates.isEmpty()) selectedTemplateUuid = safe(templates.get(0).uuid);

  matters.MatterRec selectedCase = null;
  try { selectedCase = matterStore.getByUuid(tenantUuid, selectedMatterUuid); } catch (Exception ignored) {}
  if (selectedCase == null && !activeCases.isEmpty()) selectedCase = activeCases.get(0);
  if (selectedCase != null) selectedMatterUuid = safe(selectedCase.uuid);
  try { if (selectedCase != null) assembledStore.ensure(tenantUuid, selectedCase.uuid); } catch (Exception ignored) {}

  assembled_forms.AssemblyRec selectedAssembly = null;
  if (selectedCase != null && !selectedAssemblyUuid.isBlank()) {
    try { selectedAssembly = assembledStore.getByUuid(tenantUuid, selectedCase.uuid, selectedAssemblyUuid); } catch (Exception ignored) {}
    if (selectedAssembly != null && !safe(selectedAssembly.templateUuid).isBlank()) {
      selectedTemplateUuid = safe(selectedAssembly.templateUuid);
    }
  }

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

  LinkedHashMap<String,String> tenantKv = new LinkedHashMap<String,String>();
  try { tenantKv.putAll(tenantStore.read(tenantUuid)); } catch (Exception ignored) {}

  LinkedHashMap<String,String> caseKv = new LinkedHashMap<String,String>();
  if (selectedCase != null) {
    try { caseKv.putAll(caseStore.read(tenantUuid, selectedCase.uuid)); } catch (Exception ignored) {}
  }

  LinkedHashMap<String,String> caseListKv = new LinkedHashMap<String,String>();
  if (selectedCase != null) {
    try { caseListKv.putAll(caseListStore.read(tenantUuid, selectedCase.uuid)); } catch (Exception ignored) {}
  }

  LinkedHashMap<String,String> tenantSettingsKv = new LinkedHashMap<String,String>();
  try { tenantSettingsKv.putAll(tenantSettingsStore.read(tenantUuid)); } catch (Exception ignored) {}

  LinkedHashMap<String,String> mergeValues = new LinkedHashMap<String,String>();

  putToken(mergeValues, "tenant.uuid", tenantUuid);
  putToken(mergeValues, "tenant.label", tenantLabel);

  if (selectedCase != null) {
    putToken(mergeValues, "case.uuid", safe(selectedCase.uuid));
    putToken(mergeValues, "case.label", safe(selectedCase.label));
    putToken(mergeValues, "case.cause_docket_number", safe(selectedCase.causeDocketNumber));
    putToken(mergeValues, "case.county", safe(selectedCase.county));
  }

  for (Map.Entry<String,String> e : tenantKv.entrySet()) {
    if (e == null) continue;
    String nk = tenantStore.normalizeKey(e.getKey());
    if (nk.isBlank()) continue;
    String val = safe(e.getValue());

    putToken(mergeValues, "tenant." + nk, val);
    if (!mergeValues.containsKey("kv." + nk)) putToken(mergeValues, "kv." + nk, val);
    if (!mergeValues.containsKey(nk)) putToken(mergeValues, nk, val);
  }

  for (Map.Entry<String,String> e : caseKv.entrySet()) {
    if (e == null) continue;
    String nk = caseStore.normalizeKey(e.getKey());
    if (nk.isBlank()) continue;
    String val = safe(e.getValue());

    putToken(mergeValues, "case." + nk, val);
    putToken(mergeValues, "kv." + nk, val);
    putToken(mergeValues, nk, val);
  }

  for (Map.Entry<String,String> e : caseListKv.entrySet()) {
    if (e == null) continue;
    String nk = caseListStore.normalizeKey(e.getKey());
    if (nk.isBlank()) continue;
    String val = safe(e.getValue());

    putToken(mergeValues, "case." + nk, val);
    putToken(mergeValues, "kv." + nk, val);
    putToken(mergeValues, nk, val);
  }

  boolean advancedAssemblyEnabled = "true".equalsIgnoreCase(safe(tenantSettingsKv.get("feature_advanced_assembly")));
  putToken(mergeValues, "tenant.advanced_assembly_enabled", advancedAssemblyEnabled ? "true" : "false");
  putToken(mergeValues, "advanced_assembly_enabled", advancedAssemblyEnabled ? "true" : "false");
  putToken(mergeValues, "kv.advanced_assembly_enabled", advancedAssemblyEnabled ? "true" : "false");

  boolean asyncSyncEnabled = "true".equalsIgnoreCase(safe(tenantSettingsKv.get("feature_async_sync")));
  putToken(mergeValues, "tenant.async_sync_enabled", asyncSyncEnabled ? "true" : "false");
  putToken(mergeValues, "async_sync_enabled", asyncSyncEnabled ? "true" : "false");
  putToken(mergeValues, "kv.async_sync_enabled", asyncSyncEnabled ? "true" : "false");

  LinkedHashMap<String,String> literalOverrides = new LinkedHashMap<String,String>();
  if (selectedAssembly != null && selectedAssembly.overrides != null && !selectedAssembly.overrides.isEmpty()) {
    literalOverrides.putAll(selectedAssembly.overrides);
  }
  String[] overrideTokens = request.getParameterValues("override_token");
  String[] overrideValues = request.getParameterValues("override_value");
  if (overrideTokens != null && overrideValues != null) {
    int n = Math.min(overrideTokens.length, overrideValues.length);
    for (int i = 0; i < n; i++) {
      String tokenLiteral = safe(overrideTokens[i]).trim();
      if (tokenLiteral.isBlank()) continue;
      String tokenValue = safe(overrideValues[i]);
      literalOverrides.put(tokenLiteral, tokenValue);
    }
  }
  for (Map.Entry<String,String> e : literalOverrides.entrySet()) {
    if (e == null) continue;
    applyLiteralOverride(mergeValues, safe(e.getKey()), safe(e.getValue()));
  }

  byte[] templateBytes = new byte[0];
  String templateLabel = "";
  String templateExt = "txt";

  if (selectedTemplate != null) {
    templateLabel = safe(selectedTemplate.label);
    templateExt = assembler.normalizeExtension(safe(selectedTemplate.fileExt));
    templateBytes = templateStore.readBytes(tenantUuid, selectedTemplate.uuid);
  }

  document_assembler.PreviewResult preview = assembler.preview(templateBytes, templateExt, mergeValues);
  String sourcePreviewText = safe(preview.sourceText);
  LinkedHashSet<String> usedTokens = preview.usedTokens;
  LinkedHashSet<String> missingTokens = preview.missingTokens;
  LinkedHashMap<String, Integer> tokenCounts = preview.tokenCounts;
  LinkedHashMap<String, String> workspaceTokenDefaults = assembler.workspaceTokenDefaults(sourcePreviewText, mergeValues);

  document_assembler.StyledPreview styledPreview = assembler.previewStyled(templateBytes, templateExt);
  ArrayList<document_assembler.StyledSegment> workspaceSegments =
      styledPreview == null ? new ArrayList<document_assembler.StyledSegment>() : styledPreview.segments;
  boolean styledWorkspace = styledPreview != null && styledPreview.styled;
  if (workspaceSegments == null) workspaceSegments = new ArrayList<document_assembler.StyledSegment>();
  if (workspaceSegments.isEmpty()) workspaceSegments.add(new document_assembler.StyledSegment(sourcePreviewText, ""));

  ArrayList<String> initialNeedles = new ArrayList<String>(workspaceTokenDefaults.keySet());
  document_image_preview.PreviewResult imagePreviewResult = document_image_preview.PreviewResult.empty();
  if (renderPreview && selectedTemplate != null && templateBytes.length > 0) {
    imagePreviewResult = imagePreviewer.render(templateBytes, templateExt, literalOverrides, initialNeedles, 6);
  }
  ArrayList<document_image_preview.PageImage> imagePreviewPages = imagePreviewResult.pages;
  LinkedHashMap<String, ArrayList<document_image_preview.HitRect>> imagePreviewHits = imagePreviewResult.hitRects;

  try {
    LinkedHashMap<String,String> logDetails = new LinkedHashMap<String,String>();
    logDetails.put("template_uuid", selectedTemplateUuid);
    logDetails.put("used_token_count", String.valueOf(usedTokens.size()));
    logDetails.put("missing_token_count", String.valueOf(missingTokens.size()));
    logDetails.put("preview_mode", renderPreview ? "rendered" : "text");
    if (selectedTemplate != null) logDetails.put("template_ext", safe(selectedTemplate.fileExt));
    activityLog.logVerbose("form.preview.loaded", tenantUuid, userUuid, selectedMatterUuid, selectedTemplateUuid, logDetails);
  } catch (Exception ignored) {}
  String imagePreviewWarning = safe(imagePreviewResult.warning);
  String imagePreviewEngine = safe(imagePreviewResult.engine);
  boolean renderedAvailable = imagePreviewPages != null && !imagePreviewPages.isEmpty();

  if ("save_draft_overrides".equalsIgnoreCase(action)) {
    boolean ok = false;
    String saveError = "";
    if (selectedCase == null || selectedTemplate == null) {
      saveError = "Select a case and template first.";
    } else {
      try {
        assembled_forms.AssemblyRec draft = assembledStore.upsertInProgress(
            tenantUuid,
            selectedCase.uuid,
            selectedAssemblyUuid,
            selectedTemplate.uuid,
            templateLabel,
            templateExt,
            userUuid,
            userEmail,
            literalOverrides
        );
        if (draft != null && !safe(draft.uuid).isBlank()) {
          selectedAssemblyUuid = safe(draft.uuid);
          ok = true;
        } else {
          saveError = "Unable to save draft overrides.";
        }
      } catch (Exception ex) {
        saveError = safe(ex.getMessage());
      }
    }

    response.setContentType("application/json; charset=UTF-8");
    response.setHeader("Cache-Control", "no-store");
    StringBuilder saveJson = new StringBuilder(512);
    saveJson.append('{');
    saveJson.append("\"ok\":").append(ok ? "true" : "false").append(',');
    saveJson.append("\"assemblyUuid\":").append(jsonStr(selectedAssemblyUuid)).append(',');
    saveJson.append("\"overrideCount\":").append(literalOverrides.size()).append(',');
    saveJson.append("\"error\":").append(jsonStr(saveError));
    saveJson.append('}');
    response.getWriter().write(saveJson.toString());
    return;
  }

  if ("render_preview_json".equalsIgnoreCase(action)) {
    String tokenToHighlight = safe(request.getParameter("highlight_token")).trim();
    ArrayList<String> highlightNeedles = new ArrayList<String>();
    if (!tokenToHighlight.isBlank()) {
      highlightNeedles.add(tokenToHighlight);
    } else {
      highlightNeedles.addAll(initialNeedles);
    }

    document_image_preview.PreviewResult dynamicPreview = document_image_preview.PreviewResult.empty();
    if (selectedTemplate != null && templateBytes.length > 0) {
      dynamicPreview = imagePreviewer.render(templateBytes, templateExt, literalOverrides, highlightNeedles, 6);
    }

    if (selectedCase != null && selectedTemplate != null) {
      try {
        assembled_forms.AssemblyRec draft = assembledStore.upsertInProgress(
            tenantUuid,
            selectedCase.uuid,
            selectedAssemblyUuid,
            selectedTemplate.uuid,
            templateLabel,
            templateExt,
            userUuid,
            userEmail,
            literalOverrides
        );
        if (draft != null && !safe(draft.uuid).isBlank()) selectedAssemblyUuid = safe(draft.uuid);
      } catch (Exception ignored) {}
    }

    response.setContentType("application/json; charset=UTF-8");
    response.setHeader("Cache-Control", "no-store");

    StringBuilder jsonOut = new StringBuilder(65536);
    jsonOut.append("{");
    jsonOut.append("\"assemblyUuid\":").append(jsonStr(selectedAssemblyUuid)).append(',');
    jsonOut.append("\"engine\":").append(jsonStr(safe(dynamicPreview.engine))).append(',');
    jsonOut.append("\"warning\":").append(jsonStr(safe(dynamicPreview.warning))).append(',');

    jsonOut.append("\"pages\":[");
    ArrayList<document_image_preview.PageImage> pages = dynamicPreview.pages;
    if (pages != null) {
      boolean firstPage = true;
      for (int pi = 0; pi < pages.size(); pi++) {
        document_image_preview.PageImage pageImg = pages.get(pi);
        if (pageImg == null) continue;
        if (!firstPage) jsonOut.append(',');
        jsonOut.append('{');
        jsonOut.append("\"pageIndex\":").append(pageImg.pageIndex).append(',');
        jsonOut.append("\"width\":").append(pageImg.width).append(',');
        jsonOut.append("\"height\":").append(pageImg.height).append(',');
        jsonOut.append("\"base64Png\":").append(jsonStr(safe(pageImg.base64Png)));
        jsonOut.append('}');
        firstPage = false;
      }
    }
    jsonOut.append("],");

    jsonOut.append("\"hitRects\":{");
    LinkedHashMap<String, ArrayList<document_image_preview.HitRect>> hits = dynamicPreview.hitRects;
    if (hits != null) {
      boolean firstKey = true;
      for (Map.Entry<String, ArrayList<document_image_preview.HitRect>> hitEntry : hits.entrySet()) {
        if (hitEntry == null) continue;
        String k = safe(hitEntry.getKey());
        if (!firstKey) jsonOut.append(',');
        jsonOut.append(jsonStr(k)).append(':').append('[');
        boolean firstHit = true;
        ArrayList<document_image_preview.HitRect> rects = hitEntry.getValue();
        if (rects != null) {
          for (int hi = 0; hi < rects.size(); hi++) {
            document_image_preview.HitRect r = rects.get(hi);
            if (r == null) continue;
            if (!firstHit) jsonOut.append(',');
            jsonOut.append('{');
            jsonOut.append("\"page\":").append(r.pageIndex).append(',');
            jsonOut.append("\"x\":").append(r.x).append(',');
            jsonOut.append("\"y\":").append(r.y).append(',');
            jsonOut.append("\"w\":").append(r.width).append(',');
            jsonOut.append("\"h\":").append(r.height);
            jsonOut.append('}');
            firstHit = false;
          }
        }
        jsonOut.append(']');
        firstKey = false;
      }
    }
    jsonOut.append("}");
    jsonOut.append("}");

    response.getWriter().write(jsonOut.toString());
    return;
  }

  if (wantsDownload && selectedTemplate != null) {
    try {
      document_assembler.AssembledFile assembledFile = assembler.assemble(templateBytes, templateExt, mergeValues);
      try {
        LinkedHashMap<String,String> logDetails = new LinkedHashMap<String,String>();
        logDetails.put("template_uuid", selectedTemplateUuid);
        logDetails.put("assembly_uuid", selectedAssemblyUuid);
        logDetails.put("output_extension", safe(assembledFile.extension));
        logDetails.put("output_bytes", String.valueOf(assembledFile.bytes == null ? 0 : assembledFile.bytes.length));
        logDetails.put("literal_override_count", String.valueOf(literalOverrides.size()));
        logDetails.put("used_token_count", String.valueOf(usedTokens.size()));
        logDetails.put("missing_token_count", String.valueOf(missingTokens.size()));
        activityLog.logVerbose("form.assembly.download", tenantUuid, userUuid, selectedMatterUuid, selectedTemplateUuid, logDetails);
      } catch (Exception ignored) {}
      String casePart = selectedCase == null ? "case" : fileSafe(selectedCase.label);
      String templatePart = fileSafe(templateLabel);
      String ext = safe(assembledFile.extension).isBlank() ? "txt" : safe(assembledFile.extension);
      String filename = casePart + "__" + templatePart + "." + ext;

      if (selectedCase != null) {
        try {
          assembled_forms.AssemblyRec completed = assembledStore.markCompleted(
              tenantUuid,
              selectedCase.uuid,
              selectedAssemblyUuid,
              selectedTemplate.uuid,
              templateLabel,
              templateExt,
              userUuid,
              userEmail,
              literalOverrides,
              filename,
              ext,
              assembledFile.bytes
          );
          if (completed != null && !safe(completed.uuid).isBlank()) selectedAssemblyUuid = safe(completed.uuid);
        } catch (Exception ignored) {}
      }

      response.setContentType(safe(assembledFile.contentType).isBlank() ? "application/octet-stream" : assembledFile.contentType);
      response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
      response.getOutputStream().write(assembledFile.bytes);
      return;
    } catch (Exception ex) {
      error = "Unable to assemble document: " + safe(ex.getMessage());
    }
  }

  assemblyQs = selectedAssemblyUuid.isBlank() ? "" : "&assembly_uuid=" + enc(selectedAssemblyUuid);
  if ("1".equals(safe(request.getParameter("lists_saved")).trim())) {
    message = "Case list/grid datasets saved.";
  }

%>
<jsp:include page="header.jsp" />

<style>
  body > main.container.main {
    width: min(1820px, calc(100vw - 0.9rem));
    max-width: none;
    padding-top: 0.7rem;
    padding-bottom: 0.7rem;
  }

  #formsFastRoot {
    display: grid;
    gap: 10px;
  }

  #formsFastRoot .card {
    margin: 0 !important;
  }

  .forms-workbench-wrap {
    display: grid;
    grid-template-columns: 1fr;
    gap: 10px;
    align-items: start;
    margin-top: 4px;
  }

  .forms-pane-card {
    height: calc(100vh - var(--header-h) - var(--footer-h) - 230px);
    min-height: 500px;
    display: flex;
    flex-direction: column;
    gap: 8px;
    padding-top: 10px !important;
  }

  .forms-pane-scroll {
    flex: 1 1 auto;
    min-height: 0;
    overflow: auto;
    -webkit-overflow-scrolling: touch;
    scrollbar-gutter: stable;
  }

  .forms-toolbar {
    position: sticky;
    top: 0;
    z-index: 20;
    background: #fff;
    border: 1px solid #d8dee7;
    border-radius: 10px;
    padding: 10px;
  }

  .forms-toolbar-grid {
    display: grid;
    grid-template-columns: minmax(180px, 2fr) minmax(180px, 2fr) repeat(6, auto);
    gap: 8px;
    align-items: end;
  }

  .forms-compact-meta {
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: 8px;
    flex-wrap: wrap;
  }

  .hotkey-hints {
    margin-top: 8px;
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    color: #526072;
    font-size: 12px;
  }

  .hotkey-hints code {
    display: inline-block;
    padding: 2px 7px;
    border: 1px solid #d8dee7;
    border-radius: 999px;
    background: #eef3fa;
    color: #334155;
    font-size: 11px;
  }

  #contextPreviewViewport {
    height: 100%;
    min-height: 460px;
    overflow: auto;
    -webkit-overflow-scrolling: touch;
    scrollbar-gutter: stable;
    border: 1px solid #d8dee7;
    border-radius: 10px;
    background: #eef3fa;
    padding: 12px;
  }

  #contextPreviewCanvas {
    display: block;
    width: 100%;
    height: auto;
    background: #fff;
    border: 1px solid #d7dde7;
    border-radius: 8px;
    box-shadow: 0 1px 0 rgba(16,24,40,0.03), 0 8px 20px rgba(16,24,40,0.08);
  }

  @media (max-width: 1400px) {
    .forms-pane-card {
      height: auto;
      min-height: 420px;
    }
    .forms-toolbar {
      position: static;
    }
  }

  @media (max-width: 980px) {
    body > main.container.main {
      width: calc(100% - 0.5rem);
      padding-top: 0.5rem;
      padding-bottom: 0.5rem;
    }
    .forms-toolbar-grid {
      grid-template-columns: 1fr 1fr;
    }
  }
</style>
<div id="formsFastRoot">

<section class="card">
  <div class="section-head">
    <div>
      <h1 style="margin:0;">Form Assembly</h1>
      <div class="meta">Assemble legal templates (<code>.docx</code>, <code>.doc</code>, <code>.rtf</code>) using tenant + case replacement values.</div>
      <div class="meta" style="margin-top:4px;">
        Merge order: <strong>tenant fields</strong> then <strong>case fields</strong>. Case values override same-key tenant values for <code>{{kv.key}}</code>.
      </div>
    </div>
  </div>

  <% if (message != null) { %>
    <div class="alert alert-ok" style="margin-top:12px;"><%= esc(message) %></div>
  <% } %>
  <% if (error != null) { %>
    <div class="alert alert-error" style="margin-top:12px;"><%= esc(error) %></div>
  <% } %>
</section>

<% if (!focusMode) { %>
<section class="card" style="margin-top:12px;">
  <form class="form" method="get" action="<%= ctx %>/forms.jsp">
    <input type="hidden" name="render_preview" value="<%= renderPreview ? "1" : "0" %>" />

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

    <div class="actions" style="display:flex; gap:10px; margin-top:10px; flex-wrap:wrap;">
      <% if (selectedTemplate != null) { %>
        <a class="btn btn-ghost" href="<%= ctx %>/forms.jsp?matter_uuid=<%= enc(selectedMatterUuid) %>&template_uuid=<%= enc(selectedTemplateUuid) %>&focus=1<%= renderPreviewQs %><%= assemblyQs %>">Start Focus Mode</a>
      <% } %>
      <a class="btn btn-ghost" href="<%= ctx %>/template_library.jsp?matter_uuid=<%= enc(selectedMatterUuid) %>&template_uuid=<%= enc(selectedTemplateUuid) %>">Template Library</a>
      <a class="btn btn-ghost" href="<%= ctx %>/case_fields.jsp?matter_uuid=<%= enc(selectedMatterUuid) %>&template_uuid=<%= enc(selectedTemplateUuid) %>">Case Fields</a>
      <a class="btn btn-ghost" href="<%= ctx %>/case_lists.jsp?matter_uuid=<%= enc(selectedMatterUuid) %>&template_uuid=<%= enc(selectedTemplateUuid) %><%= assemblyQs %><%= focusQs %><%= renderPreviewQs %>">Case Lists/Grids</a>
      <a class="btn btn-ghost" href="<%= ctx %>/token_guide.jsp?matter_uuid=<%= enc(selectedMatterUuid) %>&template_uuid=<%= enc(selectedTemplateUuid) %>">Token Guide</a>
      <a class="btn btn-ghost" href="<%= ctx %>/markup_notation.jsp">Markup Notation</a>
      <a class="btn btn-ghost" href="<%= ctx %>/log_viewer.jsp">Logs</a>
      <a class="btn btn-ghost" href="<%= ctx %>/assembled_forms.jsp?matter_uuid=<%= enc(selectedMatterUuid) %>&template_uuid=<%= enc(selectedTemplateUuid) %>">Assembled Forms</a>
      <% if (renderPreview) { %>
        <a class="btn btn-ghost" href="<%= ctx %>/forms.jsp?matter_uuid=<%= enc(selectedMatterUuid) %>&template_uuid=<%= enc(selectedTemplateUuid) %>&render_preview=0<%= assemblyQs %>">Disable Rendered Preview</a>
      <% } else { %>
        <a class="btn btn-ghost" href="<%= ctx %>/forms.jsp?matter_uuid=<%= enc(selectedMatterUuid) %>&template_uuid=<%= enc(selectedTemplateUuid) %>&render_preview=1<%= assemblyQs %>">Enable Rendered Preview</a>
      <% } %>
    </div>
  </form>
</section>
<% } else { %>
<section class="card" style="margin-top:12px;">
  <div style="display:flex; justify-content:space-between; align-items:flex-end; gap:12px; flex-wrap:wrap;">
    <div>
      <h2 style="margin:0;">Assembly Focus Mode</h2>
      <div class="meta">Setup panels are hidden so the user can focus on navigating and replacing text in the working document.</div>
    </div>
    <div style="display:flex; gap:10px; flex-wrap:wrap;">
      <a class="btn btn-ghost" href="<%= ctx %>/forms.jsp?matter_uuid=<%= enc(selectedMatterUuid) %>&template_uuid=<%= enc(selectedTemplateUuid) %><%= renderPreviewQs %><%= assemblyQs %>">Exit Focus Mode</a>
      <a class="btn btn-ghost" href="<%= ctx %>/template_library.jsp?matter_uuid=<%= enc(selectedMatterUuid) %>&template_uuid=<%= enc(selectedTemplateUuid) %>">Template Library</a>
      <a class="btn btn-ghost" href="<%= ctx %>/case_fields.jsp?matter_uuid=<%= enc(selectedMatterUuid) %>&template_uuid=<%= enc(selectedTemplateUuid) %>">Case Fields</a>
      <a class="btn btn-ghost" href="<%= ctx %>/case_lists.jsp?matter_uuid=<%= enc(selectedMatterUuid) %>&template_uuid=<%= enc(selectedTemplateUuid) %><%= assemblyQs %><%= focusQs %><%= renderPreviewQs %>">Case Lists/Grids</a>
      <a class="btn btn-ghost" href="<%= ctx %>/token_guide.jsp?matter_uuid=<%= enc(selectedMatterUuid) %>&template_uuid=<%= enc(selectedTemplateUuid) %>">Token Guide</a>
      <a class="btn btn-ghost" href="<%= ctx %>/markup_notation.jsp">Markup Notation</a>
      <a class="btn btn-ghost" href="<%= ctx %>/log_viewer.jsp">Logs</a>
      <a class="btn btn-ghost" href="<%= ctx %>/assembled_forms.jsp?matter_uuid=<%= enc(selectedMatterUuid) %>&template_uuid=<%= enc(selectedTemplateUuid) %>">Assembled Forms</a>
    </div>
  </div>
</section>
<% } %>

<div class="forms-workbench-wrap">
<section class="card forms-pane-card">
  <div style="display:flex; justify-content:space-between; align-items:flex-start; gap:10px; flex-wrap:wrap;">
    <div>
      <h2 style="margin:0;">Interactive Preview Workspace</h2>
      <div class="meta">Token-first workflow with keyboard shortcuts and live rendered preview sync.</div>
    </div>
    <div style="display:flex; gap:8px; flex-wrap:wrap; align-items:center;">
      <% if (selectedTemplate != null) { %>
        <form method="post" action="<%= ctx %>/forms.jsp" id="downloadForm" style="margin:0;">
          <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
          <input type="hidden" name="focus" value="<%= focusMode ? "1" : "" %>" />
          <input type="hidden" name="render_preview" value="<%= renderPreview ? "1" : "0" %>" />
          <input type="hidden" name="action" value="download_assembled" />
          <input type="hidden" name="matter_uuid" value="<%= esc(selectedMatterUuid) %>" />
          <input type="hidden" name="template_uuid" value="<%= esc(selectedTemplateUuid) %>" />
          <input type="hidden" name="assembly_uuid" id="assemblyUuidField" value="<%= esc(selectedAssemblyUuid) %>" />
          <div id="downloadOverrides"></div>
          <button class="btn" type="submit" id="btnDownloadAssembled" title="Alt+D">Download (Alt+D)</button>
        </form>
      <% } %>
      <button type="button" class="btn btn-ghost" id="btnResetWorkspace" title="Alt+0" onclick="resetWorkspace(); return false;">Reset (Alt+0)</button>
      <button type="button" class="btn btn-ghost" id="btnPreviewMode" onclick="togglePreviewMode(); return false;">Full Page: Off</button>
    </div>
  </div>

  <div
    id="missingValuesAlert"
    class="<%= missingTokens.isEmpty() ? "meta" : "alert alert-warn" %>"
    style="margin:6px 0 0 0;<%= missingTokens.isEmpty() ? "display:none;" : "" %>">
    <div id="missingValuesText">
      <% if (!missingTokens.isEmpty()) { %>
        Missing values for:
        <% int mi = 0; for (String t : missingTokens) { if (mi > 0) { %>, <% } %><code><%= esc(tokenPreview(t)) %></code><% mi++; } %>
      <% } %>
    </div>
    <div
      id="missingValuesActions"
      style="margin-top:8px; gap:8px; flex-wrap:wrap;<%= missingTokens.isEmpty() ? "display:none;" : "display:flex;" %>">
      <button type="button" class="btn btn-ghost" id="btnPromptMissingSave" onclick="promptForMissingValues(false); return false;">Prompt Missing + Save</button>
      <button type="button" class="btn btn-ghost" id="btnPromptMissingAssemble" onclick="promptForMissingValues(true); return false;">Prompt Missing + Save + Assemble</button>
      <a class="btn btn-ghost" id="btnOpenCaseLists" href="<%= ctx %>/case_lists.jsp?matter_uuid=<%= enc(selectedMatterUuid) %>&template_uuid=<%= enc(selectedTemplateUuid) %><%= assemblyQs %><%= focusQs %><%= renderPreviewQs %>">Open List/Grid Editor</a>
    </div>
  </div>

  <div class="forms-compact-meta">
    <div class="meta" id="tokenMatchMeta">Select a token to navigate matches. Replacements also apply inside table cells and tolerate common delimiter mistakes (smart quotes/full-width brackets).</div>
    <div class="meta">
      Tokens found: <%= workspaceTokenDefaults.size() %>
      <% if (!usedTokens.isEmpty()) { %>
        •
        <% int ui = 0; for (String t : usedTokens) { if (ui > 0) { %>, <% } %><code><%= esc(tokenPreview(t)) %></code> (<%= tokenCounts.getOrDefault(t, 0) %>)<% ui++; } %>
      <% } %>
    </div>
  </div>

  <div class="forms-toolbar">
    <div class="forms-toolbar-grid">
      <label>
        <span>Token</span>
        <select id="tokenSelect">
          <% if (workspaceTokenDefaults.isEmpty()) { %>
            <option value="">No tokens found</option>
          <% } else {
               for (Map.Entry<String,String> tokenEntry : workspaceTokenDefaults.entrySet()) {
                 if (tokenEntry == null) continue;
                 String token = safe(tokenEntry.getKey());
                 String defVal = safe(tokenEntry.getValue());
          %>
            <option value="<%= esc(token) %>" data-default-value="<%= esc(defVal) %>"><%= esc(token) %></option>
          <%   }
             } %>
        </select>
      </label>

      <label>
        <span>Replacement Value</span>
        <input type="text" id="replaceValue" value="" />
      </label>

      <button type="button" class="btn btn-ghost" id="btnFirstToken" title="Alt+1" onclick="gotoFirstToken(); return false;">First (Alt+1)</button>
      <button type="button" class="btn btn-ghost" id="btnPrevToken" title="Alt+2" onclick="gotoPrevToken(); return false;">Previous (Alt+2)</button>
      <button type="button" class="btn btn-ghost" id="btnNextToken" title="Alt+3" onclick="gotoNextToken(); return false;">Next (Alt+3)</button>
      <button type="button" class="btn btn-ghost" id="btnLastToken" title="Alt+4" onclick="gotoLastToken(); return false;">Last (Alt+4)</button>
      <button type="button" class="btn btn-ghost" id="btnReplaceOnce" title="Alt+R" onclick="replaceOnce(); return false;">Replace Once (Alt+R)</button>
      <button type="button" class="btn btn-ghost" id="btnReplaceAll" title="Alt+Shift+R" onclick="replaceAllTokens(); return false;">Replace All (Alt+Shift+R)</button>
    </div>
    <div class="hotkey-hints" aria-label="Keyboard shortcuts">
      <code>Alt+1</code> First
      <code>Alt+2</code> Previous
      <code>Alt+3</code> Next
      <code>Alt+4</code> Last
      <code>Alt+R</code> Replace Once
      <code>Alt+Shift+R</code> Replace All
      <code>Alt+0</code> Reset
      <code>Alt+D</code> Download
    </div>
  </div>

  <div class="forms-pane-scroll">
    <% if (!renderPreview) { %>
      <div class="meta" style="margin-bottom:8px;">Rendered image preview is disabled.</div>
      <a class="btn btn-ghost" href="<%= ctx %>/forms.jsp?matter_uuid=<%= enc(selectedMatterUuid) %>&template_uuid=<%= enc(selectedTemplateUuid) %><%= focusQs %>&render_preview=1<%= assemblyQs %>">Enable Image Preview</a>
    <% } else { %>
      <div class="meta" id="renderedPreviewMeta" style="margin-bottom:8px;">
        <% if (!safe(imagePreviewEngine).isBlank()) { %>
          Engine: <strong><%= esc(imagePreviewEngine) %></strong>. Context preview shows 1.5 inches above and below the highlighted match.
        <% } else { %>
          Rendered preview engine is unavailable.
        <% } %>
      </div>
      <div id="renderedPreviewWarning">
        <% if (!safe(imagePreviewWarning).isBlank()) { %>
          <div class="alert alert-warn" style="margin-bottom:10px;"><%= esc(imagePreviewWarning) %></div>
        <% } %>
      </div>
      <div id="contextPreviewViewport">
        <canvas id="contextPreviewCanvas"></canvas>
      </div>
      <div class="meta" id="contextPreviewMeta" style="margin-top:8px;">Navigate tokens to focus image context.</div>
      <% if (!renderedAvailable) { %>
        <div class="muted" style="margin-top:8px;">Rendered preview is unavailable for this template right now.</div>
      <% } %>
      <div id="renderedPreviewPages" style="display:none;">
        <% for (int pi = 0; pi < imagePreviewPages.size(); pi++) {
             document_image_preview.PageImage pageImg = imagePreviewPages.get(pi);
             if (pageImg == null) continue;
        %>
          <img
            id="previewPageImg<%= pageImg.pageIndex %>"
            data-page-index="<%= pageImg.pageIndex %>"
            src="data:image/png;base64,<%= esc(pageImg.base64Png) %>"
            width="<%= pageImg.width %>"
            height="<%= pageImg.height %>"
            alt="Preview page <%= pageImg.pageIndex + 1 %>"
          />
        <% } %>
      </div>
    <% } %>
  </div>
  <textarea id="workspaceText" rows="2" style="position:absolute; left:-10000px; top:auto; width:1px; height:1px; opacity:0;"><%= esc(sourcePreviewText) %></textarea>
</section>
</div>

<script>
  var tokenSelect = document.getElementById("tokenSelect");
  var replaceValue = document.getElementById("replaceValue");
  var workspace = document.getElementById("workspaceText");
  var workspaceStyled = null;
  var tokenMatchMeta = document.getElementById("tokenMatchMeta");
  var downloadForm = document.getElementById("downloadForm");
  var downloadOverrides = document.getElementById("downloadOverrides");
  var assemblyUuidField = document.getElementById("assemblyUuidField");
  var missingValuesAlert = document.getElementById("missingValuesAlert");
  var missingValuesText = document.getElementById("missingValuesText");
  var missingValuesActions = document.getElementById("missingValuesActions");
  var renderedPreviewPages = document.getElementById("renderedPreviewPages");
  var renderedPreviewMeta = document.getElementById("renderedPreviewMeta");
  var renderedPreviewWarning = document.getElementById("renderedPreviewWarning");
  var contextPreviewViewport = document.getElementById("contextPreviewViewport");
  var contextPreviewCanvas = document.getElementById("contextPreviewCanvas");
  var contextPreviewMeta = document.getElementById("contextPreviewMeta");
  var btnOpenCaseLists = document.getElementById("btnOpenCaseLists");
  var formsEndpoint = "<%= js(ctx + "/forms.jsp") %>";
  var caseListsBaseUrl = "<%= js(ctx + "/case_lists.jsp?matter_uuid=" + enc(selectedMatterUuid) + "&template_uuid=" + enc(selectedTemplateUuid) + "&return_to=forms" + assemblyQs + focusQs + renderPreviewQs) %>";
  var csrfTokenValue = "<%= js(csrfToken) %>";
  var selectedMatterUuid = "<%= js(selectedMatterUuid) %>";
  var selectedTemplateUuid = "<%= js(selectedTemplateUuid) %>";
  var currentAssemblyUuid = "<%= js(selectedAssemblyUuid) %>";
  var focusTokenParam = "<%= js(focusTokenParam) %>";
  var resumePromptAfterLists = <%= resumePromptAfterLists ? "true" : "false" %>;
  var resumeAssembleAfterLists = <%= assembleAfterResume ? "true" : "false" %>;
  var focusModeEnabled = <%= focusMode ? "true" : "false" %>;
  var renderPreviewEnabled = <%= renderPreview ? "true" : "false" %>;
  var templateSourceText = "<%= js(sourcePreviewText) %>";
  var imagePreviewHits = Object.create(null);
  var imagePreviewAnchorHits = Object.create(null);
  var tokenDefaultValues = Object.create(null);
  var tokenOverrideValues = Object.create(null);
  var initialMissingKeys = [
    <% int mki = 0; for (String t : missingTokens) { if (mki > 0) { %>, <% } %>"<%= js(t) %>"<% mki++; } %>
  ];
  var templateHasAdvancedDirectives = <%= sourcePreviewText.contains("{{#if") || sourcePreviewText.contains("{{#each") || sourcePreviewText.contains("{{format.date") ? "true" : "false" %>;
  var previewRefreshTimer = null;
  var previewRequestSeq = 0;
  var draftSaveTimer = null;
  var draftSaveQueued = false;
  var draftSaveInFlight = false;
  var eachDirectiveKeys = Object.create(null);

  var btnFirstToken = document.getElementById("btnFirstToken");
  var btnPrevToken = document.getElementById("btnPrevToken");
  var btnNextToken = document.getElementById("btnNextToken");
  var btnLastToken = document.getElementById("btnLastToken");
  var btnReplaceOnce = document.getElementById("btnReplaceOnce");
  var btnReplaceAll = document.getElementById("btnReplaceAll");
  var btnResetWorkspace = document.getElementById("btnResetWorkspace");
  var btnDownloadAssembled = document.getElementById("btnDownloadAssembled");
  var btnPreviewMode = document.getElementById("btnPreviewMode");
  var previewMode = "context";
  var fullPreviewScrollTop = 0;
  try {
    var storedPreviewMode = String(localStorage.getItem("forms.preview.mode") || "");
    if (storedPreviewMode === "full" || storedPreviewMode === "context") previewMode = storedPreviewMode;
  } catch (ignored) {}

  <% for (Map.Entry<String, ArrayList<document_image_preview.HitRect>> hitEntry : imagePreviewHits.entrySet()) {
       if (hitEntry == null) continue;
       String hitKey = safe(hitEntry.getKey());
       ArrayList<document_image_preview.HitRect> hitVals = hitEntry.getValue();
  %>
  imagePreviewHits["<%= js(hitKey) %>"] = [
    <%
      boolean firstHit = true;
      if (hitVals != null) {
        for (int hi = 0; hi < hitVals.size(); hi++) {
          document_image_preview.HitRect r = hitVals.get(hi);
          if (r == null) continue;
          if (!firstHit) {
    %>,<%
          }
    %>
    { page: <%= r.pageIndex %>, x: <%= r.x %>, y: <%= r.y %>, w: <%= r.width %>, h: <%= r.height %> }
    <%
          firstHit = false;
        }
      }
    %>
  ];
  <% } %>

  <% for (Map.Entry<String,String> tokenEntry : workspaceTokenDefaults.entrySet()) {
       if (tokenEntry == null) continue;
       String token = safe(tokenEntry.getKey());
       String defVal = safe(tokenEntry.getValue());
  %>
  tokenDefaultValues["<%= js(token) %>"] = "<%= js(defVal) %>";
  <% } %>

  function cloneHitMap(src) {
    var out = Object.create(null);
    var keys = Object.keys(src || {});
    for (var i = 0; i < keys.length; i++) {
      var k = String(keys[i] || "");
      if (!k) continue;
      var hits = src[k];
      out[k] = Array.isArray(hits) ? hits.slice() : [];
    }
    return out;
  }
  imagePreviewAnchorHits = cloneHitMap(imagePreviewHits);

  function cloneSegments(list) {
    var out = [];
    var src = Array.isArray(list) ? list : [];
    for (var i = 0; i < src.length; i++) {
      var s = src[i] || {};
      out.push({ text: String(s.text || ""), css: String(s.css || "") });
    }
    return out;
  }

  function normalizeSegments(list) {
    var src = cloneSegments(list);
    var out = [];
    for (var i = 0; i < src.length; i++) {
      var t = String(src[i].text || "");
      if (!t) continue;
      var c = String(src[i].css || "");
      if (out.length > 0 && out[out.length - 1].css === c) {
        out[out.length - 1].text += t;
      } else {
        out.push({ text: t, css: c });
      }
    }
    if (out.length === 0) out.push({ text: "", css: "" });
    return out;
  }

  var workspaceSegments = [
    <% for (int si = 0; si < workspaceSegments.size(); si++) {
         document_assembler.StyledSegment seg = workspaceSegments.get(si);
         if (seg == null) continue;
    %>
    { text: "<%= js(seg.text) %>", css: "<%= js(seg.css) %>" }<%= (si + 1 < workspaceSegments.size()) ? "," : "" %>
    <% } %>
  ];
  workspaceSegments = normalizeSegments(workspaceSegments);
  var originalWorkspaceSegments = cloneSegments(workspaceSegments);

  var tokenMatches = [];
  var tokenMatchIndex = -1;

  function escRegExp(s) {
    return String(s || "").replace(/[-\/\\^$*+?.()|[\]{}]/g, "\\$&");
  }

  function tokenRegex(token) {
    var t = String(token || "").trim();
    if (!t) return null;
    var curly = t.match(/^\{\{\s*([A-Za-z0-9_.-]+)\s*\}\}$/);
    if (curly) return new RegExp("\\{\\{\\s*" + escRegExp(curly[1]) + "\\s*\\}\\}", "g");

    var switchBrace = t.match(/^\{\s*([^{}\r\n]*\/[^{}\r\n]*)\s*\}$/);
    if (switchBrace) return new RegExp("\\{\\s*" + escRegExp(switchBrace[1]) + "\\s*\\}", "g");

    var bracket = t.match(/^\[\s*([^\[\]\r\n]{1,120})\s*\]$/);
    if (bracket) return new RegExp("\\[\\s*" + escRegExp(bracket[1]) + "\\s*\\]", "g");

    return new RegExp(escRegExp(t), "g");
  }

  function segmentsToText(list) {
    var src = Array.isArray(list) ? list : [];
    var out = "";
    for (var i = 0; i < src.length; i++) {
      out += String((src[i] && src[i].text) || "");
    }
    return out;
  }

  function syncWorkspaceTextValue() {
    if (!workspace) return;
    workspace.value = segmentsToText(workspaceSegments);
  }

  function boundaryForOffset(offset, preferNextOnBoundary) {
    var n = workspaceSegments.length;
    if (n === 0) return { index: 0, offset: 0 };

    var want = Number(offset);
    if (!isFinite(want) || want < 0) want = 0;

    var pos = 0;
    for (var i = 0; i < n; i++) {
      var text = String((workspaceSegments[i] && workspaceSegments[i].text) || "");
      var len = text.length;
      var next = pos + len;

      if (want < next) return { index: i, offset: want - pos };
      if (want === next) {
        if (preferNextOnBoundary && i + 1 < n) return { index: i + 1, offset: 0 };
        return { index: i, offset: len };
      }
      pos = next;
    }

    var lastText = String((workspaceSegments[n - 1] && workspaceSegments[n - 1].text) || "");
    return { index: n - 1, offset: lastText.length };
  }

  function replaceRange(start, end, replacement) {
    var s = Number(start);
    var e = Number(end);
    if (!isFinite(s) || !isFinite(e)) return;
    if (e < s) return;

    var startB = boundaryForOffset(s, true);
    var endB = boundaryForOffset(e, false);
    if (!startB || !endB) return;
    if (startB.index > endB.index) return;

    var out = [];
    for (var i = 0; i < startB.index; i++) out.push({
      text: String((workspaceSegments[i] && workspaceSegments[i].text) || ""),
      css: String((workspaceSegments[i] && workspaceSegments[i].css) || "")
    });

    var startSeg = workspaceSegments[startB.index] || { text: "", css: "" };
    var endSeg = workspaceSegments[endB.index] || startSeg;
    var startText = String(startSeg.text || "");
    var endText = String(endSeg.text || "");

    var prefix = startText.substring(0, Math.max(0, Math.min(startText.length, startB.offset)));
    var suffix = endText.substring(Math.max(0, Math.min(endText.length, endB.offset)));

    if (prefix) out.push({ text: prefix, css: String(startSeg.css || "") });

    var replText = String(replacement || "");
    var replCss = String(startSeg.css || endSeg.css || "");
    if (replText) out.push({ text: replText, css: replCss });

    if (suffix) out.push({ text: suffix, css: String(endSeg.css || "") });

    for (var j = endB.index + 1; j < workspaceSegments.length; j++) out.push({
      text: String((workspaceSegments[j] && workspaceSegments[j].text) || ""),
      css: String((workspaceSegments[j] && workspaceSegments[j].css) || "")
    });

    workspaceSegments = normalizeSegments(out);
    syncWorkspaceTextValue();
  }

  function appendStyledPiece(parent, text, css, activeHit) {
    if (!parent) return;
    var t = String(text || "");
    if (!t) return;

    var span = document.createElement("span");
    span.textContent = t;

    var baseCss = String(css || "");
    if (activeHit) {
      span.style.cssText = (baseCss ? (baseCss + ";") : "")
        + "background:#ffe79a;outline:1px solid #e6ba11;outline-offset:-1px;border-radius:2px;";
      span.setAttribute("data-hit", "1");
    } else if (baseCss) {
      span.style.cssText = baseCss;
    }
    parent.appendChild(span);
  }

  function activeMatch() {
    if (tokenMatchIndex < 0 || tokenMatchIndex >= tokenMatches.length) return null;
    return tokenMatches[tokenMatchIndex];
  }

  function scrollIntoViewSafe(el, center) {
    if (!el || !el.scrollIntoView) return;
    try {
      el.scrollIntoView({ behavior: "smooth", block: center ? "center" : "nearest" });
      return;
    } catch (ignored) {}
    try {
      el.scrollIntoView(center);
      return;
    } catch (ignored2) {}
    try {
      el.scrollIntoView();
    } catch (ignored3) {}
  }

  function renderStyledContainer(container, scrollToActive) {
    if (!container) return;
    container.innerHTML = "";

    var active = activeMatch();
    var activeStart = active ? active.start : -1;
    var activeEnd = active ? active.end : -1;

    var frag = document.createDocumentFragment();
    var pos = 0;
    for (var i = 0; i < workspaceSegments.length; i++) {
      var seg = workspaceSegments[i] || {};
      var text = String(seg.text || "");
      var css = String(seg.css || "");
      var len = text.length;
      if (len === 0) continue;

      var segStart = pos;
      var segEnd = pos + len;

      if (!active || activeEnd <= segStart || activeStart >= segEnd) {
        appendStyledPiece(frag, text, css, false);
      } else {
        var rs = Math.max(0, activeStart - segStart);
        var re = Math.min(len, activeEnd - segStart);
        if (rs > 0) appendStyledPiece(frag, text.substring(0, rs), css, false);
        if (re > rs) appendStyledPiece(frag, text.substring(rs, re), css, true);
        if (re < len) appendStyledPiece(frag, text.substring(re), css, false);
      }
      pos = segEnd;
    }

    container.appendChild(frag);
    if (scrollToActive) {
      var marker = container.querySelector("[data-hit='1']");
      scrollIntoViewSafe(marker, true);
    }
  }

  function renderWorkspace(scrollToActive) {
    renderStyledContainer(workspaceStyled, !!scrollToActive);
  }

  function clearContextPreview() {
    if (!contextPreviewCanvas) return;
    var g = contextPreviewCanvas.getContext("2d");
    contextPreviewCanvas.width = 1;
    contextPreviewCanvas.height = 1;
    if (g) {
      g.fillStyle = "#ffffff";
      g.fillRect(0, 0, 1, 1);
    }
  }

  function tokenHitsForLiteral(tokenLiteral) {
    var token = String(tokenLiteral || "");
    if (!token) return [];
    var currentHits = imagePreviewHits[token];
    if (Array.isArray(currentHits) && currentHits.length > 0) return currentHits;
    var anchorHits = imagePreviewAnchorHits[token];
    return Array.isArray(anchorHits) ? anchorHits : [];
  }

  function selectedTokenImageHits() {
    if (!tokenSelect) return [];
    return tokenHitsForLiteral(String(tokenSelect.value || ""));
  }

  function hasOwn(obj, key) {
    return Object.prototype.hasOwnProperty.call(obj, key);
  }

  function syncAssemblyUuid(value) {
    var v = String(value || "").trim();
    currentAssemblyUuid = v;
    if (assemblyUuidField) assemblyUuidField.value = v;
  }

  function defaultValueForToken(token, opt) {
    var t = String(token || "");
    if (hasOwn(tokenDefaultValues, t)) return String(tokenDefaultValues[t]);
    if (opt) return String(opt.getAttribute("data-default-value") || "");
    return "";
  }

  function tokenOptionIndices() {
    var out = [];
    if (!tokenSelect) return out;
    for (var i = 0; i < tokenSelect.options.length; i++) {
      var opt = tokenSelect.options[i];
      if (!opt) continue;
      var v = String(opt.value || "").trim();
      if (v) out.push(i);
    }
    return out;
  }

  function selectTokenByLiteral(tokenLiteral) {
    if (!tokenSelect) return false;
    var target = displayTokenLiteral(tokenLiteral);
    if (!target) return false;
    for (var i = 0; i < tokenSelect.options.length; i++) {
      var opt = tokenSelect.options[i];
      if (!opt) continue;
      var v = String(opt.value || "").trim();
      if (!v) continue;
      if (v === target || equivalentPromptTokens(v, target)) {
        tokenSelect.selectedIndex = i;
        return true;
      }
    }
    return false;
  }

  function currentTokenOrdinal(indices) {
    var idxs = Array.isArray(indices) ? indices : tokenOptionIndices();
    if (!idxs.length || !tokenSelect) return -1;
    var sel = Number(tokenSelect.selectedIndex);
    for (var i = 0; i < idxs.length; i++) {
      if (idxs[i] === sel) return i;
    }
    return 0;
  }

  function selectTokenByOrdinal(ordinal) {
    if (!tokenSelect) return false;
    recordCurrentTokenOverride();
    var idxs = tokenOptionIndices();
    if (!idxs.length) return false;
    var n = idxs.length;
    var ord = Number(ordinal);
    if (!isFinite(ord)) ord = 0;
    ord = ((ord % n) + n) % n;
    tokenSelect.selectedIndex = idxs[ord];
    syncDefaultReplaceValue();
    recalcMatches();
    scheduleRenderedPreviewRefresh();
    return true;
  }

  function navigationMatchCount() {
    if (tokenMatches.length > 0) return tokenMatches.length;
    return selectedTokenImageHits().length;
  }

  function pageImageEl(pageIndex) {
    var id = "previewPageImg" + String(pageIndex);
    return document.getElementById(id);
  }

  function updatePreviewModeUi() {
    if (!btnPreviewMode) return;
    if (previewMode === "full") {
      btnPreviewMode.textContent = "Full Page: On";
      btnPreviewMode.title = "Switch to context preview";
    } else {
      btnPreviewMode.textContent = "Full Page: Off";
      btnPreviewMode.title = "Switch to full page preview";
    }
  }

  function togglePreviewMode() {
    if (previewMode === "full" && contextPreviewViewport) {
      fullPreviewScrollTop = Math.max(0, Number(contextPreviewViewport.scrollTop) || 0);
    }
    previewMode = (previewMode === "full") ? "context" : "full";
    try { localStorage.setItem("forms.preview.mode", previewMode); } catch (ignored) {}
    if (previewMode === "context" && contextPreviewViewport) contextPreviewViewport.scrollTop = 0;
    updatePreviewModeUi();
    renderImageTokenHighlights(tokenSelect ? tokenSelect.value : "", tokenMatchIndex);
  }

  function drawContextForHit(hit, index, total, usingAnchor) {
    if (!contextPreviewCanvas || !hit) return;
    var pageIdx = Math.max(0, Number(hit.page) || 0);
    var img = pageImageEl(pageIdx);
    if (!img) {
      if (contextPreviewMeta) contextPreviewMeta.textContent = "Preview image for page " + (pageIdx + 1) + " is unavailable.";
      clearContextPreview();
      return;
    }

    var paint = function () {
      var iw = Math.max(1, Number(img.naturalWidth || img.width || img.getAttribute("width")) || 1);
      var ih = Math.max(1, Number(img.naturalHeight || img.height || img.getAttribute("height")) || 1);

      var hx = Math.max(0, Number(hit.x) || 0);
      var hy = Math.max(0, Number(hit.y) || 0);
      var hw = Math.max(1, Number(hit.w) || 1);
      var hh = Math.max(1, Number(hit.h) || 1);

      var y1;
      var y2;
      if (previewMode === "full") {
        y1 = 0;
        y2 = ih;
      } else {
        var contextPadY = Math.max(72, Math.round((ih / 11.0) * 1.5));
        y1 = Math.max(0, Math.floor(hy - contextPadY));
        y2 = Math.min(ih, Math.ceil(hy + hh + contextPadY));
        if (y2 <= y1) {
          y1 = 0;
          y2 = ih;
        }
      }

      var cropW = iw;
      var cropH = Math.max(1, y2 - y1);

      contextPreviewCanvas.width = cropW;
      contextPreviewCanvas.height = cropH;
      var g = contextPreviewCanvas.getContext("2d");
      if (!g) return;
      g.clearRect(0, 0, cropW, cropH);
      g.drawImage(img, 0, y1, cropW, cropH, 0, 0, cropW, cropH);

      var ry = Math.max(0, hy - y1);
      var multiInstance = Number(total) > 1;
      if (multiInstance) {
        g.fillStyle = "rgba(244, 114, 182, 0.33)";
        g.strokeStyle = "rgba(190, 24, 93, 0.95)";
      } else {
        g.fillStyle = "rgba(255, 222, 89, 0.35)";
        g.strokeStyle = "rgba(199, 133, 0, 1)";
      }
      g.lineWidth = 2;
      g.fillRect(hx, ry, hw, hh);
      g.strokeRect(hx, ry, hw, hh);

      if (contextPreviewMeta) {
        contextPreviewMeta.textContent =
          "Match " + (index + 1) + " of " + total +
          " • page " + (pageIdx + 1) +
          (multiInstance ? " • multi-instance token" : "") +
          (usingAnchor ? " • anchored token position" : "") +
          (previewMode === "full"
            ? " • full page preview"
            : " • context: 1.5in above and below highlight");
      }
      if (contextPreviewViewport) {
        if (previewMode === "full") {
          var fullMax = Math.max(0, contextPreviewViewport.scrollHeight - contextPreviewViewport.clientHeight);
          if (fullPreviewScrollTop > fullMax) fullPreviewScrollTop = fullMax;
          contextPreviewViewport.scrollTop = fullPreviewScrollTop;
        } else {
          contextPreviewViewport.scrollTop = 0;
        }
      }
    };

    if (img.complete && (img.naturalWidth || img.width)) {
      paint();
    } else {
      img.onload = paint;
      img.onerror = function () {
        if (contextPreviewMeta) contextPreviewMeta.textContent = "Unable to load preview image.";
        clearContextPreview();
      };
    }
  }

  function drawPageFallback(pageIndex, message) {
    if (!contextPreviewCanvas) return;
    var msg = String(message == null ? "" : message);
    var idx = Math.max(0, Number(pageIndex) || 0);
    var img = pageImageEl(idx);
    if (!img) {
      clearContextPreview();
      if (contextPreviewMeta) contextPreviewMeta.textContent = msg || "Rendered preview is unavailable for this token.";
      return;
    }

    var paint = function () {
      var iw = Math.max(1, Number(img.naturalWidth || img.width || img.getAttribute("width")) || 1);
      var ih = Math.max(1, Number(img.naturalHeight || img.height || img.getAttribute("height")) || 1);
      contextPreviewCanvas.width = iw;
      contextPreviewCanvas.height = ih;
      var g = contextPreviewCanvas.getContext("2d");
      if (!g) return;
      g.clearRect(0, 0, iw, ih);
      g.drawImage(img, 0, 0, iw, ih, 0, 0, iw, ih);
      if (contextPreviewMeta) {
        contextPreviewMeta.textContent = msg || ("Showing page " + (idx + 1) + " (no token hit found).");
      }
      if (contextPreviewViewport) {
        if (previewMode === "full") {
          var fallbackMax = Math.max(0, contextPreviewViewport.scrollHeight - contextPreviewViewport.clientHeight);
          if (fullPreviewScrollTop > fallbackMax) fullPreviewScrollTop = fallbackMax;
          contextPreviewViewport.scrollTop = fullPreviewScrollTop;
        } else {
          contextPreviewViewport.scrollTop = 0;
        }
      }
    };

    if (img.complete && (img.naturalWidth || img.width)) {
      paint();
    } else {
      img.onload = paint;
      img.onerror = function () {
        clearContextPreview();
        if (contextPreviewMeta) contextPreviewMeta.textContent = "Unable to load preview image.";
      };
    }
  }

  function renderImageTokenHighlights(tokenLiteral, activeIndex) {
    if (!renderPreviewEnabled) return;
    var key = String(tokenLiteral || "");
    if (!key) {
      if (contextPreviewMeta) contextPreviewMeta.textContent = "Select a token to preview.";
      clearContextPreview();
      return;
    }

    var usingAnchor = false;
    var hits = imagePreviewHits[key];
    if (!Array.isArray(hits) || hits.length === 0) {
      hits = imagePreviewAnchorHits[key];
      usingAnchor = Array.isArray(hits) && hits.length > 0;
    }
    if (!Array.isArray(hits) || hits.length === 0) {
      drawPageFallback(0, "No rendered highlight was found for the selected token. Showing page 1.");
      return;
    }

    var idx = Number(activeIndex);
    if (!isFinite(idx) || idx < 0) idx = 0;
    idx = idx % hits.length;
    drawContextForHit(hits[idx], idx, hits.length, usingAnchor);
  }

  function renderAllSurfaces(scrollToActive) {
    renderWorkspace(scrollToActive);
    renderImageTokenHighlights(tokenSelect ? tokenSelect.value : "", tokenMatchIndex);
  }

  function recalcMatches() {
    tokenMatches = [];
    tokenMatchIndex = -1;

    syncWorkspaceTextValue();
    if (!workspace || !tokenSelect) return;

    var token = tokenSelect.value;
    var re = tokenRegex(token);
    if (!re) {
      if (tokenMatchMeta) tokenMatchMeta.textContent = "Select a token to navigate matches.";
      renderAllSurfaces(false);
      return;
    }

    var text = workspace.value || "";
    var m;
    while ((m = re.exec(text)) !== null) {
      tokenMatches.push({ start: m.index, end: m.index + m[0].length });
      if (m.index === re.lastIndex) re.lastIndex++;
    }

    if (tokenMatches.length === 0) {
      var hitCount = selectedTokenImageHits().length;
      if (hitCount > 0) {
        tokenMatchIndex = 0;
        if (tokenMatchMeta) tokenMatchMeta.textContent = "Match 1 of " + hitCount;
      } else if (tokenMatchMeta) {
        tokenMatchMeta.textContent = "No matches for selected token.";
      }
      renderAllSurfaces(false);
      return;
    }
    tokenMatchIndex = 0;
    focusMatch();
  }

  function focusMatch() {
    var count = navigationMatchCount();
    if (count <= 0) return;

    if (tokenMatchIndex < 0) tokenMatchIndex = 0;
    if (tokenMatchIndex >= count) tokenMatchIndex = count - 1;

    if (tokenMatchMeta) tokenMatchMeta.textContent = "Match " + (tokenMatchIndex + 1) + " of " + count;
    renderAllSurfaces(true);

    if (tokenMatches.length > 0 && !workspaceStyled && workspace) {
      var hit = tokenMatches[tokenMatchIndex];
      if (hit) {
        workspace.focus();
        workspace.setSelectionRange(hit.start, hit.end);
      }
    }
  }

  function ensureMatchesReady() {
    if (navigationMatchCount() > 0) return true;
    recalcMatches();
    return navigationMatchCount() > 0;
  }

  function gotoFirstToken() {
    selectTokenByOrdinal(0);
  }

  function gotoLastToken() {
    var idxs = tokenOptionIndices();
    if (!idxs.length) return;
    selectTokenByOrdinal(idxs.length - 1);
  }

  function gotoPrevToken() {
    var idxs = tokenOptionIndices();
    if (!idxs.length) return;
    var ord = currentTokenOrdinal(idxs);
    if (ord < 0) ord = 0;
    selectTokenByOrdinal(ord - 1);
  }

  function gotoNextToken() {
    var idxs = tokenOptionIndices();
    if (!idxs.length) return;
    var ord = currentTokenOrdinal(idxs);
    if (ord < 0) ord = 0;
    selectTokenByOrdinal(ord + 1);
  }

  function selectedReplacementValue() {
    return replaceValue ? String(replaceValue.value || "") : "";
  }

  function htmlEscape(value) {
    return String(value == null ? "" : value)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/\"/g, "&quot;")
      .replace(/'/g, "&#39;");
  }

  function displayTokenLiteral(tokenLiteral) {
    var t = String(tokenLiteral || "").trim();
    if (!t) return "";
    if ((t.indexOf("{{") === 0 && t.lastIndexOf("}}") === t.length - 2)
        || (t.indexOf("[") === 0 && t.lastIndexOf("]") === t.length - 1)
        || (t.indexOf("{") === 0 && t.lastIndexOf("}") === t.length - 1)) {
      return t;
    }
    return "{{" + t + "}}";
  }

  function promptTokenKey(tokenLiteral) {
    var t = String(tokenLiteral || "").trim();
    if (!t) return "";
    var curly = t.match(/^\{\{\s*([A-Za-z0-9_.-]+)\s*\}\}$/);
    if (curly) return String(curly[1] || "").toLowerCase();
    if (/^[A-Za-z0-9_.-]+$/.test(t)) return t.toLowerCase();
    return "";
  }

  function canonicalPromptKey(tokenLiteral) {
    var key = promptTokenKey(tokenLiteral);
    if (!key) return "";
    var scoped = key.match(/^(case|kv|tenant)\.([A-Za-z0-9_.-]+)$/);
    if (scoped) return String(scoped[2] || "");
    return key;
  }

  function equivalentPromptTokens(a, b) {
    var ka = promptTokenKey(a);
    var kb = promptTokenKey(b);
    if (!ka || !kb) return false;
    if (ka === kb) return true;
    var ca = canonicalPromptKey(ka);
    var cb = canonicalPromptKey(kb);
    if (!ca || !cb) return false;
    return ca === cb;
  }

  function rebuildEachDirectiveKeys() {
    eachDirectiveKeys = Object.create(null);
    var src = String(templateSourceText || "");
    if (!src) return;
    var eachRe = /\{\{\s*#each\s+([A-Za-z0-9_.-]+)\s*\}\}/g;
    var m;
    while ((m = eachRe.exec(src)) !== null) {
      var expr = String(m[1] || "").trim().toLowerCase();
      if (!expr) continue;
      var key = canonicalPromptKey(expr);
      if (!key) continue;
      eachDirectiveKeys[key] = true;
    }
  }

  function collectDirectivePromptTokens() {
    rebuildEachDirectiveKeys();
    var out = [];
    var seen = Object.create(null);
    var src = String(templateSourceText || "");
    if (!src) return out;

    function addExpr(rawExpr) {
      var expr = String(rawExpr || "").trim().toLowerCase();
      if (!expr) return;
      if (!/^[A-Za-z0-9_.-]+$/.test(expr)) return;
      var literal = "{{" + expr + "}}";
      if (isPromptInternalToken(literal)) return;
      if (hasOwn(seen, expr)) return;
      seen[expr] = true;
      out.push(literal);
    }

    var eachRe = /\{\{\s*#each\s+([A-Za-z0-9_.-]+)\s*\}\}/g;
    var m;
    while ((m = eachRe.exec(src)) !== null) addExpr(m[1]);

    var ifRe = /\{\{\s*#if\s+([A-Za-z0-9_.-]+)\s*\}\}/g;
    while ((m = ifRe.exec(src)) !== null) addExpr(m[1]);

    var fmtRe = /\{\{\s*format\.date\s+([A-Za-z0-9_.-]+)\s*,[^}]*\}\}/g;
    while ((m = fmtRe.exec(src)) !== null) addExpr(m[1]);

    return out;
  }

  function looksLikeStructuredListValue(value) {
    var v = String(value == null ? "" : value).trim();
    if (!v) return false;
    if (/^<\s*list[\s>]/i.test(v)) return true;
    if (/<\s*(item|row)\b/i.test(v)) return true;
    if (v.indexOf("\n") >= 0 && v.indexOf("|") >= 0) return true;
    return false;
  }

  function tokenValueForPrompt(tokenLiteral) {
    var t = displayTokenLiteral(tokenLiteral);
    if (!t) return "";
    if (hasOwn(tokenOverrideValues, t)) return String(tokenOverrideValues[t] == null ? "" : tokenOverrideValues[t]);
    if (hasOwn(tokenDefaultValues, t)) return String(tokenDefaultValues[t] == null ? "" : tokenDefaultValues[t]);

    var overrideKeys = Object.keys(tokenOverrideValues);
    for (var oi = 0; oi < overrideKeys.length; oi++) {
      var ovk = String(overrideKeys[oi] || "");
      if (!ovk) continue;
      if (!equivalentPromptTokens(ovk, t)) continue;
      var ov = String(tokenOverrideValues[ovk] == null ? "" : tokenOverrideValues[ovk]);
      if (ov.trim() !== "") return ov;
    }

    var defaultKeys = Object.keys(tokenDefaultValues);
    for (var di = 0; di < defaultKeys.length; di++) {
      var dvk = String(defaultKeys[di] || "");
      if (!dvk) continue;
      if (!equivalentPromptTokens(dvk, t)) continue;
      var dv = String(tokenDefaultValues[dvk] == null ? "" : tokenDefaultValues[dvk]);
      if (dv.trim() !== "") return dv;
    }
    return "";
  }

  function isPromptInternalToken(tokenLiteral) {
    var t = String(tokenLiteral || "").trim();
    if (!t) return true;
    if (t === "{{this}}") return true;
    if (/^\{\{\s*item\./.test(t)) return true;
    if (/^\{\{#/.test(t) || /^\{\{\//.test(t)) return true;
    return false;
  }

  function isListOrGridToken(tokenLiteral) {
    var literal = displayTokenLiteral(tokenLiteral);
    if (!literal) return false;
    if (templateHasAdvancedDirectives && Object.keys(eachDirectiveKeys).length === 0) rebuildEachDirectiveKeys();

    var key = canonicalPromptKey(literal);
    if (key && hasOwn(eachDirectiveKeys, key)) return true;
    if (key && /(^|[._-])(rows|row|list|items|table|grid|columns)$/.test(key)) return true;
    return looksLikeStructuredListValue(tokenValueForPrompt(literal));
  }

  function listGridFocusKey() {
    if (!tokenSelect) return "";
    var selected = String(tokenSelect.value || "").trim();
    if (!selected) return "";
    if (!isListOrGridToken(selected)) return "";
    return canonicalPromptKey(selected);
  }

  function buildCaseListsUrl(focusKey, assembleAfterSave) {
    var url = String(caseListsBaseUrl || "");
    if (currentAssemblyUuid && url.indexOf("assembly_uuid=") < 0) {
      url += "&assembly_uuid=" + encodeURIComponent(String(currentAssemblyUuid));
    }
    var focus = String(focusKey || "").trim();
    if (focus) url += "&focus_list_key=" + encodeURIComponent(focus);
    if (assembleAfterSave) url += "&assemble_after=1";
    return url;
  }

  function syncCaseListsLink() {
    if (!btnOpenCaseLists) return;
    var focus = listGridFocusKey();
    btnOpenCaseLists.href = buildCaseListsUrl(focus, false);
  }

  function applyPromptTokenValue(tokenLiteral, value) {
    var target = displayTokenLiteral(tokenLiteral);
    if (!target) return;
    var v = String(value == null ? "" : value);
    var matched = false;

    var defaults = Object.keys(tokenDefaultValues);
    for (var i = 0; i < defaults.length; i++) {
      var lit = String(defaults[i] || "").trim();
      if (!lit) continue;
      if (isPromptInternalToken(lit)) continue;
      if (!equivalentPromptTokens(lit, target)) continue;
      tokenOverrideValues[lit] = v;
      matched = true;
    }

    var existing = Object.keys(tokenOverrideValues);
    for (var j = 0; j < existing.length; j++) {
      var ex = String(existing[j] || "").trim();
      if (!ex) continue;
      if (isPromptInternalToken(ex)) continue;
      if (!equivalentPromptTokens(ex, target)) continue;
      tokenOverrideValues[ex] = v;
      matched = true;
    }

    if (!matched) tokenOverrideValues[target] = v;
  }

  function clearPromptTokenValue(tokenLiteral) {
    var target = displayTokenLiteral(tokenLiteral);
    if (!target) return;
    var matched = false;

    var existing = Object.keys(tokenOverrideValues);
    for (var i = 0; i < existing.length; i++) {
      var ex = String(existing[i] || "").trim();
      if (!ex) continue;
      if (!equivalentPromptTokens(ex, target)) continue;
      delete tokenOverrideValues[ex];
      matched = true;
    }

    if (!matched && hasOwn(tokenOverrideValues, target)) delete tokenOverrideValues[target];
  }

  function tokenListContainsEquivalent(list, tokenLiteral) {
    var arr = Array.isArray(list) ? list : [];
    var literal = String(tokenLiteral || "");
    if (!literal) return false;
    for (var i = 0; i < arr.length; i++) {
      if (equivalentPromptTokens(arr[i], literal)) return true;
    }
    return false;
  }

  function addPromptToken(out, seen, tokenLiteral, opts) {
    var literal = displayTokenLiteral(tokenLiteral);
    if (!literal) return;
    if (isPromptInternalToken(literal)) return;
    if (String(tokenValueForPrompt(literal) || "").trim() !== "") return;

    var includeListGrid = !!(opts && opts.includeListGrid);
    var focusLiteral = displayTokenLiteral((opts && opts.focusToken) || "");
    if (isListOrGridToken(literal) && !includeListGrid) {
      if (!focusLiteral || !equivalentPromptTokens(literal, focusLiteral)) return;
    }

    var key = canonicalPromptKey(literal);
    if (!key) key = literal;
    if (hasOwn(seen, key)) return;
    seen[key] = true;
    out.push(literal);
  }

  function collectPromptTokens(opts) {
    var out = [];
    var seen = Object.create(null);
    var options = opts || {};

    var directiveTokens = collectDirectivePromptTokens();
    for (var d = 0; d < directiveTokens.length; d++) {
      addPromptToken(out, seen, directiveTokens[d], options);
    }

    var literals = Object.keys(tokenDefaultValues);
    for (var i = 0; i < literals.length; i++) {
      var literal = String(literals[i] || "").trim();
      if (!literal) continue;
      addPromptToken(out, seen, literal, options);
    }

    for (var j = 0; j < initialMissingKeys.length; j++) {
      var k = String(initialMissingKeys[j] || "").trim();
      if (!k) continue;
      if (!/^[A-Za-z0-9_.-]+$/.test(k)) continue;
      var curly = "{{" + k + "}}";
      addPromptToken(out, seen, curly, options);
    }

    return out;
  }

  function refreshMissingValuesBanner() {
    if (!missingValuesAlert || !missingValuesText || !missingValuesActions) return;
    var focusToken = tokenSelect ? tokenSelect.value : "";
    var allTokens = collectPromptTokens({ includeListGrid: true, focusToken: focusToken });
    var promptNow = collectPromptTokens({ includeListGrid: false, focusToken: focusToken });
    var deferred = [];
    for (var i = 0; i < allTokens.length; i++) {
      var t = String(allTokens[i] || "");
      if (!t) continue;
      if (!isListOrGridToken(t)) continue;
      if (tokenListContainsEquivalent(promptNow, t)) continue;
      deferred.push(t);
    }

    if (!allTokens.length) {
      missingValuesAlert.style.display = "none";
      missingValuesActions.style.display = "none";
      return;
    }
    missingValuesAlert.style.display = "";
    missingValuesAlert.className = "alert alert-warn";
    missingValuesActions.style.display = "flex";
    var parts = [];
    for (var j = 0; j < allTokens.length; j++) {
      parts.push("<code>" + htmlEscape(displayTokenLiteral(allTokens[j])) + "</code>");
    }
    var html = "Missing values for: " + parts.join(", ");
    if (deferred.length > 0) {
      var deferredParts = [];
      for (var k = 0; k < deferred.length; k++) {
        deferredParts.push("<code>" + htmlEscape(displayTokenLiteral(deferred[k])) + "</code>");
      }
      html += "<br><span class=\"meta\">List/grid values are prompted only when that token is focused: "
        + deferredParts.join(", ") + ".</span>";
    }
    missingValuesText.innerHTML = html;
    syncCaseListsLink();
  }

  function ensureAdvancedModeForPrompt() {
    if (!templateHasAdvancedDirectives) return;
    var current = String(tokenValueForPrompt("{{tenant.advanced_assembly_enabled}}") || "").trim().toLowerCase();
    if (current === "true" || current === "1" || current === "yes" || current === "on") return;

    tokenOverrideValues["tenant.advanced_assembly_enabled"] = "true";
    tokenOverrideValues["advanced_assembly_enabled"] = "true";
    tokenOverrideValues["kv.advanced_assembly_enabled"] = "true";
    syncDownloadOverrides();
  }

  function saveDraftOverridesNow() {
    if (!selectedMatterUuid || !selectedTemplateUuid) return Promise.resolve(false);

    var pairs = [];
    function addPair(k, v) {
      pairs.push(encodeURIComponent(String(k || "")) + "=" + encodeURIComponent(String(v == null ? "" : v)));
    }

    addPair("csrfToken", csrfTokenValue);
    addPair("action", "save_draft_overrides");
    addPair("matter_uuid", selectedMatterUuid);
    addPair("template_uuid", selectedTemplateUuid);
    if (currentAssemblyUuid) addPair("assembly_uuid", currentAssemblyUuid);
    if (focusModeEnabled) addPair("focus", "1");
    addPair("render_preview", renderPreviewEnabled ? "1" : "0");

    var keys = Object.keys(tokenOverrideValues);
    for (var i = 0; i < keys.length; i++) {
      var token = String(keys[i] || "");
      if (!token) continue;
      addPair("override_token", token);
      addPair("override_value", String(tokenOverrideValues[token] == null ? "" : tokenOverrideValues[token]));
    }

    return fetch(formsEndpoint, {
      method: "POST",
      credentials: "same-origin",
      headers: { "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8" },
      body: pairs.join("&")
    })
    .then(function (res) {
      if (!res || !res.ok) throw new Error("save_draft_failed");
      return res.json();
    })
    .then(function (data) {
      var d = data || {};
      if (d.assemblyUuid != null) {
        var incoming = String(d.assemblyUuid || "").trim();
        if (incoming) syncAssemblyUuid(incoming);
      }
      return d.ok !== false;
    })
    .catch(function () {
      return false;
    });
  }

  function flushQueuedDraftSave() {
    if (!draftSaveQueued) return Promise.resolve(true);
    if (draftSaveInFlight) return Promise.resolve(true);

    draftSaveQueued = false;
    draftSaveInFlight = true;
    return saveDraftOverridesNow()
      .then(function (saved) {
        return !!saved;
      })
      .catch(function () {
        return false;
      })
      .then(function (saved) {
        draftSaveInFlight = false;
        if (draftSaveQueued) return flushQueuedDraftSave();
        return saved;
      });
  }

  function scheduleDraftSave(delayMs) {
    if (!selectedMatterUuid || !selectedTemplateUuid) return;
    draftSaveQueued = true;

    var wait = Number(delayMs);
    if (!isFinite(wait) || wait < 0) wait = 260;
    if (draftSaveTimer) {
      clearTimeout(draftSaveTimer);
      draftSaveTimer = null;
    }
    draftSaveTimer = setTimeout(function () {
      draftSaveTimer = null;
      flushQueuedDraftSave();
    }, wait);
  }

  function promptForMissingValues(assembleAfterSave) {
    var shouldAssemble = !!assembleAfterSave;
    ensureAdvancedModeForPrompt();

    var focusToken = tokenSelect ? tokenSelect.value : "";
    var targets = collectPromptTokens({ includeListGrid: false, focusToken: focusToken });
    var allMissing = collectPromptTokens({ includeListGrid: true, focusToken: focusToken });
    if (!targets.length) {
      var deferredList = [];
      for (var di = 0; di < allMissing.length; di++) {
        var dl = String(allMissing[di] || "");
        if (!dl) continue;
        if (!isListOrGridToken(dl)) continue;
        deferredList.push(dl);
      }
      if (deferredList.length > 0) {
        var focusedKey = listGridFocusKey();
        if (!focusedKey) {
          window.alert("List/grid values are still missing. Select that list/grid token first, then prompt again, or open List/Grid Editor.");
        }
      }
      saveDraftOverridesNow().then(function (saved) {
        if (!saved) return;
        if (!shouldAssemble) {
          if (renderPreviewEnabled) refreshRenderedPreviewNow();
          refreshMissingValuesBanner();
          return;
        }
        if (renderPreviewEnabled) {
          refreshRenderedPreviewNow().then(function () {
            submitDownloadAssembled();
          });
        } else {
          submitDownloadAssembled();
        }
      });
      return;
    }

    for (var i = 0; i < targets.length; i++) {
      var token = String(targets[i] || "");
      if (!token) continue;

      if (isListOrGridToken(token)) {
        var datasetKey = canonicalPromptKey(token);
        var openEditor = window.confirm(
          displayTokenLiteral(token) + " is list/grid data. Click OK to open the Case Lists/Grids editor for this dataset."
        );
        if (openEditor) {
          syncDownloadOverrides();
          saveDraftOverridesNow().then(function () {
            window.location.href = buildCaseListsUrl(datasetKey, shouldAssemble);
          }).catch(function () {
            window.location.href = buildCaseListsUrl(datasetKey, shouldAssemble);
          });
          return;
        }
        continue;
      }

      var current = tokenValueForPrompt(token);
      var answer = window.prompt(
        "Enter value for " + displayTokenLiteral(token) + "\\nLeave blank to keep unresolved. Click Cancel to stop prompting.",
        current
      );
      if (answer === null) break;
      var value = String(answer);
      if (value.trim() === "") {
        clearPromptTokenValue(token);
      } else {
        applyPromptTokenValue(token, value);
      }
    }

    syncDownloadOverrides();
    syncDefaultReplaceValue();
    recalcMatches();
    refreshMissingValuesBanner();

    saveDraftOverridesNow().then(function (saved) {
      if (!saved) return;
      if (renderPreviewEnabled) {
        refreshRenderedPreviewNow().then(function () {
          if (shouldAssemble) submitDownloadAssembled();
        });
      } else if (shouldAssemble) {
        submitDownloadAssembled();
      }
    });
  }

  function syncDownloadOverrides() {
    if (!downloadOverrides) return;
    downloadOverrides.innerHTML = "";

    var tokens = Object.keys(tokenOverrideValues);
    for (var i = 0; i < tokens.length; i++) {
      var token = String(tokens[i] || "");
      if (!token) continue;

      var value = tokenOverrideValues[token];

      var tokenInput = document.createElement("input");
      tokenInput.type = "hidden";
      tokenInput.name = "override_token";
      tokenInput.value = token;
      downloadOverrides.appendChild(tokenInput);

      var valueInput = document.createElement("input");
      valueInput.type = "hidden";
      valueInput.name = "override_value";
      valueInput.value = String(value == null ? "" : value);
      downloadOverrides.appendChild(valueInput);
    }
  }

  function applyRenderedPreviewData(data) {
    if (!renderPreviewEnabled || !renderedPreviewPages) return;
    var d = data || {};
    if (d && d.assemblyUuid != null) {
      var incomingAssemblyUuid = String(d.assemblyUuid || "").trim();
      if (incomingAssemblyUuid) syncAssemblyUuid(incomingAssemblyUuid);
    }
    var pages = Array.isArray(d.pages) ? d.pages : [];
    var hits = d.hitRects || {};
    imagePreviewHits = Object.create(null);
    var hitKeys = Object.keys(hits);
    for (var hk = 0; hk < hitKeys.length; hk++) {
      var key = String(hitKeys[hk] || "");
      if (!key) continue;
      var val = hits[key];
      var arr = Array.isArray(val) ? val : [];
      imagePreviewHits[key] = arr;
      if (arr.length > 0) imagePreviewAnchorHits[key] = arr.slice();
    }

    renderedPreviewPages.innerHTML = "";
    for (var i = 0; i < pages.length; i++) {
      var p = pages[i] || {};
      var pageIndex = Number(p.pageIndex);
      if (!isFinite(pageIndex) || pageIndex < 0) pageIndex = i;
      var w = Math.max(1, Number(p.width) || 1);
      var h = Math.max(1, Number(p.height) || 1);
      var b64 = String(p.base64Png || "");

      var img = document.createElement("img");
      img.id = "previewPageImg" + String(pageIndex);
      img.setAttribute("data-page-index", String(pageIndex));
      img.src = "data:image/png;base64," + b64;
      img.width = w;
      img.height = h;
      img.alt = "Preview page " + (pageIndex + 1);
      img.style.display = "none";
      renderedPreviewPages.appendChild(img);
    }

    if (renderedPreviewMeta) {
      var engine = String(d.engine || "");
      renderedPreviewMeta.textContent = engine
        ? ("Engine: " + engine + ". Context preview shows 1.5 inches above and below the highlighted match.")
        : "Rendered preview engine is unavailable.";
    }

    if (renderedPreviewWarning) {
      renderedPreviewWarning.innerHTML = "";
      var warning = String(d.warning || "");
      if (warning) {
        var warn = document.createElement("div");
        warn.className = "alert alert-warn";
        warn.style.marginBottom = "10px";
        warn.textContent = warning;
        renderedPreviewWarning.appendChild(warn);
      }
    }

    if (!pages.length) {
      if (contextPreviewMeta) contextPreviewMeta.textContent = "Rendered preview is unavailable for this template right now.";
      clearContextPreview();
      return;
    }

    renderImageTokenHighlights(tokenSelect ? tokenSelect.value : "", tokenMatchIndex);
  }

  function refreshRenderedPreviewNow() {
    if (!renderPreviewEnabled || !renderedPreviewPages) return Promise.resolve(null);

    var reqId = ++previewRequestSeq;
    var pairs = [];
    function addPair(k, v) {
      pairs.push(encodeURIComponent(String(k || "")) + "=" + encodeURIComponent(String(v == null ? "" : v)));
    }
    addPair("csrfToken", csrfTokenValue);
    addPair("action", "render_preview_json");
    addPair("matter_uuid", selectedMatterUuid);
    addPair("template_uuid", selectedTemplateUuid);
    if (currentAssemblyUuid) addPair("assembly_uuid", currentAssemblyUuid);
    addPair("render_preview", "1");
    if (focusModeEnabled) addPair("focus", "1");
    if (tokenSelect && tokenSelect.value) addPair("highlight_token", String(tokenSelect.value));

    var keys = Object.keys(tokenOverrideValues);
    for (var i = 0; i < keys.length; i++) {
      var token = String(keys[i] || "");
      if (!token) continue;
      addPair("override_token", token);
      addPair("override_value", String(tokenOverrideValues[token] == null ? "" : tokenOverrideValues[token]));
    }

    return fetch(formsEndpoint, {
      method: "POST",
      credentials: "same-origin",
      headers: { "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8" },
      body: pairs.join("&")
    })
    .then(function (res) {
      if (!res || !res.ok) throw new Error("preview_refresh_failed");
      return res.json();
    })
    .then(function (data) {
      if (reqId !== previewRequestSeq) return;
      applyRenderedPreviewData(data);
      return data;
    })
    .catch(function () {
      // Keep token navigation fully functional even when preview refresh fails.
      return null;
    });
  }

  function scheduleRenderedPreviewRefresh() {
    if (!renderPreviewEnabled || !renderedPreviewPages) return;
    if (previewRefreshTimer) {
      clearTimeout(previewRefreshTimer);
      previewRefreshTimer = null;
    }
    previewRefreshTimer = setTimeout(function () {
      previewRefreshTimer = null;
      refreshRenderedPreviewNow();
    }, 220);
  }

  function recordCurrentTokenOverride() {
    if (!tokenSelect) return;
    var token = String(tokenSelect.value || "");
    if (!token) return;
    var current = selectedReplacementValue();
    var opt = tokenSelect.options[tokenSelect.selectedIndex];
    var def = defaultValueForToken(token, opt);
    if (current === def) {
      if (hasOwn(tokenOverrideValues, token)) delete tokenOverrideValues[token];
    } else {
      tokenOverrideValues[token] = current;
    }
    syncDownloadOverrides();
    refreshMissingValuesBanner();
    scheduleDraftSave(260);
  }

  function replaceOnce() {
    if (!ensureMatchesReady()) return;
    if (tokenMatches.length === 0) {
      recalcMatches();
      if (tokenMatches.length === 0) return;
    }
    if (tokenMatchIndex < 0) tokenMatchIndex = 0;
    if (tokenMatchIndex >= tokenMatches.length) tokenMatchIndex = tokenMatches.length - 1;

    recordCurrentTokenOverride();
    var hit = tokenMatches[tokenMatchIndex];
    if (!hit) return;
    replaceRange(hit.start, hit.end, selectedReplacementValue());
    var idxs = tokenOptionIndices();
    if (idxs.length > 1) {
      gotoNextToken();
      return;
    }
    recalcMatches();
    scheduleRenderedPreviewRefresh();
  }

  function replaceAllTokens() {
    if (!tokenSelect) return;
    var token = tokenSelect.value;
    var re = tokenRegex(token);
    if (!re) return;

    if (tokenMatches.length === 0) {
      recalcMatches();
      if (tokenMatches.length === 0) return;
    }
    recordCurrentTokenOverride();
    var hits = tokenMatches.slice();
    for (var i = hits.length - 1; i >= 0; i--) {
      replaceRange(hits[i].start, hits[i].end, selectedReplacementValue());
    }
    recalcMatches();
    scheduleRenderedPreviewRefresh();
  }

  function resetWorkspace() {
    workspaceSegments = normalizeSegments(cloneSegments(originalWorkspaceSegments));
    tokenOverrideValues = Object.create(null);
    syncDownloadOverrides();
    syncWorkspaceTextValue();
    refreshMissingValuesBanner();
    scheduleDraftSave(0);
    recalcMatches();
    scheduleRenderedPreviewRefresh();
  }

  function syncDefaultReplaceValue() {
    if (!tokenSelect || !replaceValue) return;
    var opt = tokenSelect.options[tokenSelect.selectedIndex];
    if (!opt) {
      replaceValue.value = "";
      return;
    }
    var token = String(opt.value || "");
    var def = defaultValueForToken(token, opt);
    replaceValue.value = hasOwn(tokenOverrideValues, token) ? String(tokenOverrideValues[token]) : def;
  }

  function submitDownloadAssembled() {
    if (!downloadForm) return;
    if (btnDownloadAssembled && btnDownloadAssembled.disabled) return;
    syncDownloadOverrides();
    if (downloadForm.requestSubmit) {
      downloadForm.requestSubmit();
    } else {
      downloadForm.submit();
    }
  }

  function handleHotKeys(ev) {
    if (!ev || !ev.altKey || ev.ctrlKey || ev.metaKey) return;
    var key = String(ev.key || "").toLowerCase();
    if (!key) return;

    if (key === "1") {
      ev.preventDefault();
      gotoFirstToken();
      return;
    }
    if (key === "2") {
      ev.preventDefault();
      gotoPrevToken();
      return;
    }
    if (key === "3") {
      ev.preventDefault();
      gotoNextToken();
      return;
    }
    if (key === "4") {
      ev.preventDefault();
      gotoLastToken();
      return;
    }
    if (key === "0") {
      ev.preventDefault();
      resetWorkspace();
      return;
    }
    if (key === "d") {
      ev.preventDefault();
      submitDownloadAssembled();
      return;
    }
    if (key === "r") {
      ev.preventDefault();
      if (ev.shiftKey) replaceAllTokens();
      else replaceOnce();
    }
  }

  if (tokenSelect) {
    tokenSelect.addEventListener("change", function () {
      syncDefaultReplaceValue();
      recalcMatches();
      scheduleRenderedPreviewRefresh();
      syncCaseListsLink();
    });
  }

  if (replaceValue) {
    replaceValue.addEventListener("input", function () {
      recordCurrentTokenOverride();
      scheduleRenderedPreviewRefresh();
    });
    replaceValue.addEventListener("keydown", function (ev) {
      if (!ev) return;
      if (ev.key === "Enter") {
        ev.preventDefault();
        replaceOnce();
      }
    });
  }

  if (downloadForm) {
    downloadForm.addEventListener("submit", function () {
      syncDownloadOverrides();
    });
  }
  if (contextPreviewViewport) {
    contextPreviewViewport.addEventListener("scroll", function () {
      if (previewMode !== "full") return;
      fullPreviewScrollTop = Math.max(0, Number(contextPreviewViewport.scrollTop) || 0);
    });
  }
  document.addEventListener("keydown", handleHotKeys, true);

  syncWorkspaceTextValue();
  syncAssemblyUuid(currentAssemblyUuid);
  if (focusTokenParam) selectTokenByLiteral(focusTokenParam);
  syncDownloadOverrides();
  syncDefaultReplaceValue();
  refreshMissingValuesBanner();
  syncCaseListsLink();
  updatePreviewModeUi();
  recalcMatches();
  renderAllSurfaces(false);
  if (resumePromptAfterLists) {
    setTimeout(function () {
      promptForMissingValues(resumeAssembleAfterLists);
    }, 50);
  }
</script>

<jsp:include page="footer.jsp" />
