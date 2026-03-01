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

  private static String templateDisplayPath(form_templates.TemplateRec t) {
    if (t == null) return "";
    String folder = safe(t.folderPath).trim();
    String label = safe(t.label).trim();
    if (folder.isBlank()) return label;
    if (label.isBlank()) return folder;
    return folder + "/" + label;
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

  private static int intOr(String raw, int fallback) {
    try {
      return Integer.parseInt(safe(raw).trim());
    } catch (Exception ignored) {
      return fallback;
    }
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
  String selectedTemplateDisplayPath = selectedTemplate == null ? "" : templateDisplayPath(selectedTemplate);

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
    String caseCause = safe(caseKv.get("cause_docket_number"));
    if (caseCause.isBlank()) caseCause = safe(selectedCase.causeDocketNumber);
    String caseCounty = safe(caseKv.get("county"));
    if (caseCounty.isBlank()) caseCounty = safe(selectedCase.county);

    putToken(mergeValues, "case.uuid", safe(selectedCase.uuid));
    putToken(mergeValues, "case.label", safe(selectedCase.label));
    putToken(mergeValues, "case.cause_docket_number", caseCause);
    putToken(mergeValues, "case.county", caseCounty);
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
  LinkedHashMap<String,String> previewReplacementValues = new LinkedHashMap<String,String>();
  for (Map.Entry<String,String> e : literalOverrides.entrySet()) {
    if (e == null) continue;
    String literalKey = safe(e.getKey());
    String literalVal = safe(e.getValue());
    applyLiteralOverride(mergeValues, literalKey, literalVal);
    applyLiteralOverride(previewReplacementValues, literalKey, literalVal);
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
    imagePreviewResult = imagePreviewer.render(templateBytes, templateExt, previewReplacementValues, initialNeedles, 6);
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
    int highlightIndex = intOr(request.getParameter("highlight_index"), 0);
    String previewModeParam = safe(request.getParameter("preview_mode")).trim().toLowerCase(Locale.ROOT);
    boolean previewFullPage = "full".equals(previewModeParam);
    String livePreviewToken = safe(request.getParameter("live_preview_token")).trim();
    String livePreviewValue = safe(request.getParameter("live_preview_value"));
    String workspacePreviewText = safe(request.getParameter("workspace_text"));
    if (workspacePreviewText.length() > 800000) {
      workspacePreviewText = workspacePreviewText.substring(0, 800000);
    }
    LinkedHashMap<String,String> previewRuntimeValues = new LinkedHashMap<String,String>();
    previewRuntimeValues.putAll(previewReplacementValues);
    if (!livePreviewToken.isBlank()) {
      applyLiteralOverride(previewRuntimeValues, livePreviewToken, livePreviewValue);
    }
    ArrayList<String> highlightNeedles = new ArrayList<String>();
    if (!tokenToHighlight.isBlank()) {
      highlightNeedles.add(tokenToHighlight);
    } else {
      highlightNeedles.addAll(initialNeedles);
    }

    document_image_preview.PreviewResult dynamicPreview = document_image_preview.PreviewResult.empty();
    if (!workspacePreviewText.isBlank()) {
      String previewText = workspacePreviewText;
      if (!previewRuntimeValues.isEmpty()) {
        previewText = assembler.applyReplacementsToText(previewText, previewRuntimeValues);
      }
      dynamicPreview = imagePreviewer.renderPlainText(
          previewText,
          highlightNeedles,
          6,
          "",
          "Workspace Text Renderer"
      );
    } else if (selectedTemplate != null && templateBytes.length > 0) {
      dynamicPreview = imagePreviewer.render(templateBytes, templateExt, previewRuntimeValues, highlightNeedles, 6);
    }
    document_image_preview.FocusPreview focusPreview =
        imagePreviewer.renderFocusPreview(dynamicPreview, tokenToHighlight, highlightIndex, previewFullPage);

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
      if (!tokenToHighlight.isBlank() && !hits.containsKey(tokenToHighlight)) {
        if (!firstKey) jsonOut.append(',');
        jsonOut.append(jsonStr(tokenToHighlight)).append(':').append("[]");
      }
    } else if (!tokenToHighlight.isBlank()) {
      jsonOut.append(jsonStr(tokenToHighlight)).append(':').append("[]");
    }
    jsonOut.append("},");
    jsonOut.append("\"contextPreview\":{");
    jsonOut.append("\"token\":").append(jsonStr(safe(focusPreview.token))).append(',');
    jsonOut.append("\"pageIndex\":").append(focusPreview.pageIndex).append(',');
    jsonOut.append("\"hitIndex\":").append(focusPreview.hitIndex).append(',');
    jsonOut.append("\"hitCount\":").append(focusPreview.hitCount).append(',');
    jsonOut.append("\"width\":").append(focusPreview.width).append(',');
    jsonOut.append("\"height\":").append(focusPreview.height).append(',');
    jsonOut.append("\"mode\":").append(jsonStr(safe(focusPreview.mode))).append(',');
    jsonOut.append("\"message\":").append(jsonStr(safe(focusPreview.message))).append(',');
    jsonOut.append("\"base64Png\":").append(jsonStr(safe(focusPreview.base64Png)));
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
  html, body {
    overflow-y: auto;
  }

  body > main.container.main {
    width: min(1820px, calc(100vw - 0.9rem));
    max-width: none;
    padding-top: 0.7rem;
    padding-bottom: 0.7rem;
    overflow: visible;
  }

  #formsFastRoot {
    display: grid;
    gap: 12px;
    --forms-pane-max-h: calc(100vh - var(--header-h) - var(--footer-h) - 210px);
  }

  @supports (height: 100dvh) {
    #formsFastRoot {
      --forms-pane-max-h: calc(100dvh - var(--header-h) - var(--footer-h) - 210px);
    }
  }

  #formsFastRoot .card {
    margin: 0 !important;
  }

  .forms-workbench-wrap {
    margin-top: 4px;
  }

  .forms-workbench-split {
    display: grid;
    grid-template-columns: minmax(320px, 30fr) minmax(0, 70fr);
    gap: 12px;
    align-items: stretch;
  }

  .forms-side-card,
  .forms-preview-card {
    min-height: 520px;
    max-height: var(--forms-pane-max-h);
    display: flex;
    flex-direction: column;
    gap: 8px;
    padding-top: 10px !important;
    border-radius: 14px;
  }

  .forms-side-card {
    background: linear-gradient(180deg, var(--surface) 0%, var(--surface-2) 100%);
    border-color: var(--border);
  }

  .forms-preview-card {
    background: linear-gradient(180deg, var(--surface) 0%, var(--surface-2) 100%);
    border-color: var(--border);
  }

  .forms-side-head,
  .forms-preview-head {
    border-bottom: 1px solid var(--border);
    padding-bottom: 8px;
  }

  .forms-side-scroll,
  .forms-preview-scroll,
  .forms-pane-scroll {
    flex: 1 1 auto;
    min-height: 0;
    overflow: auto;
    -webkit-overflow-scrolling: touch;
    scrollbar-gutter: stable;
  }

  .forms-side-scroll,
  .forms-preview-scroll {
    display: grid;
    gap: 10px;
    align-content: start;
    padding-right: 2px;
  }

  .forms-quick-actions {
    display: grid;
    gap: 8px;
    align-items: stretch;
  }

  .forms-quick-actions.has-download {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  .forms-quick-actions.no-download {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .forms-quick-actions form {
    margin: 0;
    display: flex;
    min-width: 0;
  }

  .forms-quick-actions form .btn {
    flex: 1 1 auto;
  }

  .forms-quick-actions .btn {
    width: 100%;
    justify-content: center;
    white-space: nowrap;
  }

  .forms-tabs {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 6px;
    border: 1px solid var(--border);
    border-radius: 12px;
    background: var(--surface);
    padding: 6px;
  }

  .forms-tab-btn {
    appearance: none;
    border: 1px solid transparent;
    background: transparent;
    color: var(--muted);
    border-radius: 10px;
    padding: 7px 8px;
    font-size: 12px;
    font-weight: 600;
    cursor: pointer;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    gap: 6px;
  }

  .forms-tab-btn:hover {
    background: var(--ghost-hover);
    color: var(--text);
  }

  .forms-tab-btn.is-active {
    border-color: var(--border);
    background: var(--surface-2);
    color: var(--text);
  }

  .forms-tab-btn:focus-visible {
    outline: none;
    box-shadow: var(--focus);
  }

  .forms-tab-pill {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    min-width: 18px;
    height: 18px;
    border-radius: 999px;
    padding: 0 5px;
    font-size: 11px;
    line-height: 1;
    background: var(--warn-fill);
    color: #7a4a00;
    border: 1px solid rgba(217, 119, 6, 0.32);
  }

  .forms-tab-panels {
    display: grid;
    gap: 10px;
  }

  .forms-tab-panel {
    display: none;
  }

  .forms-tab-panel.is-active {
    display: block;
  }

  .forms-toolbar {
    background: var(--surface);
    border: 1px solid var(--border);
    border-radius: 12px;
    padding: 11px;
    box-shadow: var(--shadow-1);
    position: sticky;
    top: 0;
    z-index: 4;
  }

  .forms-toolbar-stack {
    display: grid;
    grid-template-columns: 1fr;
    gap: 8px;
  }

  .forms-toolbar-actions {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 8px;
    margin-top: 8px;
  }

  .forms-toolbar-actions .btn {
    width: 100%;
    justify-content: center;
    white-space: nowrap;
  }

  .forms-side-meta {
    display: flex;
    flex-direction: column;
    gap: 8px;
    margin-top: 8px;
  }

  .tokens-found {
    border: 1px solid var(--border);
    border-radius: 12px;
    background: var(--surface);
    padding: 8px 10px;
    max-height: 140px;
    overflow: auto;
    line-height: 1.45;
    font-size: 12px;
    color: var(--text);
  }

  .tokens-found code {
    background: var(--chip-bg);
    border: 1px solid var(--border);
    border-radius: 8px;
    padding: 1px 5px;
  }

  .hotkey-hints {
    margin-top: 2px;
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    color: #526072;
    font-size: 12px;
  }

  .hotkey-hints code {
    display: inline-block;
    padding: 2px 7px;
    border: 1px solid var(--border);
    border-radius: 999px;
    background: var(--chip-bg);
    color: var(--text);
    font-size: 11px;
  }

  #missingValuesAlert {
    border-radius: 12px;
  }

  #missingValuesText {
    max-height: 130px;
    overflow: auto;
    -webkit-overflow-scrolling: touch;
  }

  #contextPreviewViewport {
    flex: 1 1 auto;
    height: 100%;
    min-height: 460px;
    overflow: auto;
    -webkit-overflow-scrolling: touch;
    scrollbar-gutter: stable;
    border: 1px solid var(--border);
    border-radius: 10px;
    background: linear-gradient(180deg, var(--surface-2) 0%, var(--bg) 100%);
    padding: 12px;
  }

  #contextPreviewImage {
    display: block;
    width: 100%;
    height: auto;
    background: var(--surface);
    border: 1px solid var(--border);
    border-radius: 8px;
    box-shadow: var(--shadow-1);
  }

  html[data-theme="dark"] #contextPreviewImage {
    background: var(--surface);
  }

  .template-picker-field {
    display: grid;
    gap: 8px;
  }

  .template-picker-current {
    border: 1px solid var(--border);
    border-radius: 10px;
    background: var(--surface);
    padding: 9px 10px;
    min-height: 40px;
    display: flex;
    align-items: center;
    color: var(--text);
  }

  .template-picker-current.is-empty {
    color: var(--muted);
  }

  .template-modal {
    position: fixed;
    inset: 0;
    z-index: 90;
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 14px;
  }

  .template-modal[hidden] {
    display: none !important;
  }

  .template-modal-backdrop {
    position: absolute;
    inset: 0;
    background: rgba(11, 20, 34, 0.54);
  }

  .template-modal-dialog {
    position: relative;
    width: min(860px, 100%);
    max-height: min(86vh, 900px);
    display: grid;
    grid-template-rows: auto auto 1fr auto;
    gap: 10px;
    background: var(--surface);
    border: 1px solid var(--border);
    border-radius: 14px;
    padding: 14px;
    box-shadow: var(--shadow-2);
  }

  .template-modal-head {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 10px;
  }

  .template-modal-list {
    border: 1px solid var(--border);
    border-radius: 10px;
    background: var(--surface-2);
    padding: 8px;
    overflow: auto;
    min-height: 280px;
  }

  .template-modal-group {
    margin-bottom: 10px;
  }

  .template-modal-group:last-child {
    margin-bottom: 0;
  }

  .template-modal-group-head {
    font-size: 12px;
    font-weight: 600;
    color: var(--muted);
    margin: 6px 2px;
  }

  .template-modal-item {
    width: 100%;
    text-align: left;
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 10px;
    padding: 8px 10px;
    border: 1px solid var(--border);
    border-radius: 10px;
    background: var(--surface);
    color: var(--text);
    cursor: pointer;
    margin-bottom: 6px;
  }

  .template-modal-item:last-child {
    margin-bottom: 0;
  }

  .template-modal-item:hover {
    background: var(--surface-2);
  }

  .template-modal-item.is-selected {
    border-color: var(--accent);
    box-shadow: var(--focus);
  }

  .template-modal-item-main {
    display: flex;
    flex-direction: column;
    gap: 2px;
    min-width: 0;
  }

  .template-modal-item-name {
    font-size: 13px;
    font-weight: 600;
    color: var(--text);
    word-break: break-word;
  }

  .template-modal-item-file {
    font-size: 11px;
    color: var(--muted);
    word-break: break-word;
  }

  .template-modal-item-ext {
    font-size: 11px;
    color: var(--muted);
    border: 1px solid var(--border);
    border-radius: 999px;
    background: var(--chip-bg);
    padding: 3px 8px;
    flex: 0 0 auto;
  }

  .template-modal-empty {
    display: none;
    border: 1px dashed var(--border);
    border-radius: 10px;
    padding: 12px;
    color: var(--muted);
    background: var(--surface);
  }

  .template-modal-empty.is-visible {
    display: block;
  }

  @media (max-width: 1400px) {
    .forms-workbench-split {
      grid-template-columns: minmax(300px, 34fr) minmax(0, 66fr);
    }
    .forms-side-card,
    .forms-preview-card {
      max-height: none;
      min-height: 420px;
    }
  }

  @media (max-width: 980px) {
    body > main.container.main {
      width: calc(100% - 0.5rem);
      padding-top: 0.5rem;
      padding-bottom: 0.5rem;
    }
    .forms-workbench-split {
      grid-template-columns: 1fr;
    }
    .forms-toolbar-actions {
      grid-template-columns: 1fr 1fr;
    }
    .forms-quick-actions {
      grid-template-columns: 1fr;
    }
  }

  @media (max-width: 1240px) {
    .forms-toolbar-actions {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }
    .forms-quick-actions.has-download {
      grid-template-columns: repeat(2, minmax(0, 1fr));
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
  <form class="form" method="get" action="<%= ctx %>/forms.jsp" id="formsSelectionForm">
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

      <div class="template-picker-field">
        <span>Template</span>
        <input type="hidden" name="template_uuid" id="selectedTemplateUuidField" value="<%= esc(selectedTemplateUuid) %>" />
        <div class="template-picker-current <%= selectedTemplate == null ? "is-empty" : "" %>" id="selectedTemplatePath">
          <% if (selectedTemplate == null) { %>
            No template selected
          <% } else { %>
            <%= esc(selectedTemplateDisplayPath) %> (<%= esc(safe(selectedTemplate.fileExt).toUpperCase(Locale.ROOT)) %>)
          <% } %>
        </div>
        <div style="display:flex; gap:8px; flex-wrap:wrap;">
          <button class="btn btn-ghost" type="button" id="btnOpenTemplatePicker" <%= templates.isEmpty() ? "disabled" : "" %>>Choose Template</button>
          <a class="btn btn-ghost" href="<%= ctx %>/template_library.jsp?matter_uuid=<%= enc(selectedMatterUuid) %>&template_uuid=<%= enc(selectedTemplateUuid) %>">Manage Templates</a>
        </div>
      </div>

      <label>
        <span>&nbsp;</span>
        <button class="btn" type="submit">Load</button>
      </label>
    </div>

    <div class="actions" style="display:flex; gap:10px; margin-top:10px; flex-wrap:wrap;">
      <% if (selectedTemplate != null) { %>
        <a class="btn btn-ghost" href="<%= ctx %>/forms.jsp?matter_uuid=<%= enc(selectedMatterUuid) %>&template_uuid=<%= enc(selectedTemplateUuid) %>&focus=1<%= renderPreviewQs %><%= assemblyQs %>">Start Focus Mode</a>
      <% } %>
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

  <div class="template-modal" id="templatePickerModal" hidden>
    <div class="template-modal-backdrop" data-template-modal-close="1"></div>
    <div class="template-modal-dialog" role="dialog" aria-modal="true" aria-labelledby="templatePickerTitle">
      <div class="template-modal-head">
        <div>
          <h3 id="templatePickerTitle" style="margin:0;">Select Template</h3>
          <div class="meta">Browse folders and subfolders, then choose one template.</div>
        </div>
        <button type="button" class="btn btn-ghost" id="btnCloseTemplatePicker">Close</button>
      </div>

      <label style="margin:0;">
        <span>Search</span>
        <input type="text" id="templatePickerSearch" placeholder="Search path, name, or extension" />
      </label>

      <div class="template-modal-list" id="templatePickerList">
        <% if (templates.isEmpty()) { %>
          <div class="template-modal-empty is-visible" id="templatePickerEmpty">No templates found.</div>
        <% } else { %>
          <%
            String currentFolderPath = "__none__";
            boolean groupOpen = false;
            boolean hasTemplateRows = false;
            for (int i = 0; i < templates.size(); i++) {
              form_templates.TemplateRec t = templates.get(i);
              if (t == null) continue;
              String id = safe(t.uuid).trim();
              if (id.isBlank()) continue;
              hasTemplateRows = true;
              String folderPath = safe(t.folderPath).trim();
              String folderLabel = folderPath.isBlank() ? "Root" : folderPath;
              String displayPath = templateDisplayPath(t);
              String fileExt = safe(t.fileExt).trim().toUpperCase(Locale.ROOT);
              String fileName = safe(t.fileName).trim();
              String searchBlob = (folderPath + " " + safe(t.label) + " " + fileExt + " " + fileName).toLowerCase(Locale.ROOT);
              boolean selected = id.equals(safe(selectedTemplateUuid));
              if (!folderPath.equals(currentFolderPath)) {
                if (groupOpen) {
          %>
              </div>
            </div>
          <%
                }
                currentFolderPath = folderPath;
                groupOpen = true;
          %>
            <div class="template-modal-group" data-template-group="1">
              <div class="template-modal-group-head"><%= esc(folderLabel) %></div>
              <div>
          <% } %>
                <button
                  type="button"
                  class="template-modal-item <%= selected ? "is-selected" : "" %>"
                  data-template-item="1"
                  data-template-id="<%= esc(id) %>"
                  data-template-display="<%= esc(displayPath + " (" + fileExt + ")") %>"
                  data-template-search="<%= esc(searchBlob) %>">
                  <span class="template-modal-item-main">
                    <span class="template-modal-item-name"><%= esc(safe(t.label)) %></span>
                    <span class="template-modal-item-file"><%= esc(fileName) %></span>
                  </span>
                  <span class="template-modal-item-ext"><%= esc(fileExt) %></span>
                </button>
          <% } %>
          <% if (groupOpen) { %>
              </div>
            </div>
          <% } %>
          <div class="template-modal-empty <%= hasTemplateRows ? "" : "is-visible" %>" id="templatePickerEmpty">
            <%= hasTemplateRows ? "No templates match your search." : "No templates found." %>
          </div>
        <% } %>
      </div>

      <div class="actions" style="display:flex; justify-content:flex-end; gap:8px; margin:0;">
        <button type="button" class="btn btn-ghost" data-template-modal-close="1">Done</button>
      </div>
    </div>
  </div>
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
<div class="forms-workbench-split">
<aside class="card forms-side-card">
  <div class="forms-side-head">
    <h2 style="margin:0;">Assembly Controls</h2>
    <div class="meta">Token-first controls, replacements, and missing-value prompts.</div>
  </div>
  <div class="forms-side-scroll">
    <div class="forms-quick-actions <%= selectedTemplate != null ? "has-download" : "no-download" %>">
      <% if (selectedTemplate != null) { %>
        <form method="post" action="<%= ctx %>/forms.jsp" id="downloadForm">
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
      <button type="button" class="btn btn-ghost" id="btnResetWorkspace" title="Alt+0">Reset (Alt+0)</button>
      <button type="button" class="btn btn-ghost" id="btnPreviewMode">Preview: Context</button>
    </div>

    <div class="forms-tabs" role="tablist" aria-label="Assembly controls tabs">
      <button type="button" class="forms-tab-btn is-active" id="formsTabReplace" data-forms-tab-target="replace" role="tab" aria-selected="true" aria-controls="formsPanelReplace">Replace</button>
      <button type="button" class="forms-tab-btn" id="formsTabMissing" data-forms-tab-target="missing" role="tab" aria-selected="false" aria-controls="formsPanelMissing">Missing <span class="forms-tab-pill" id="formsMissingCountPill" style="display:none;">0</span></button>
      <button type="button" class="forms-tab-btn" id="formsTabTokens" data-forms-tab-target="tokens" role="tab" aria-selected="false" aria-controls="formsPanelTokens">Tokens</button>
    </div>

    <div class="forms-tab-panels">
      <section class="forms-tab-panel is-active" id="formsPanelReplace" data-forms-tab-panel="replace" role="tabpanel" aria-labelledby="formsTabReplace">
        <div class="forms-toolbar">
          <div class="forms-toolbar-stack">
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
          </div>
          <div class="forms-toolbar-actions">
            <button type="button" class="btn btn-ghost" id="btnFirstToken" title="Alt+1">First</button>
            <button type="button" class="btn btn-ghost" id="btnPrevToken" title="Alt+2">Previous</button>
            <button type="button" class="btn btn-ghost" id="btnNextToken" title="Alt+3">Next</button>
            <button type="button" class="btn btn-ghost" id="btnLastToken" title="Alt+4">Last</button>
            <button type="button" class="btn btn-ghost" id="btnReplaceOnce" title="Alt+R">Replace Once</button>
            <button type="button" class="btn btn-ghost" id="btnReplaceAll" title="Alt+Shift+R">Replace All</button>
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
      </section>

      <section class="forms-tab-panel" id="formsPanelMissing" data-forms-tab-panel="missing" role="tabpanel" aria-labelledby="formsTabMissing">
        <div
          id="missingValuesAlert"
          class="<%= missingTokens.isEmpty() ? "meta" : "alert alert-warn" %>"
          style="margin:0;<%= missingTokens.isEmpty() ? "display:none;" : "" %>">
          <div id="missingValuesText">
            <% if (!missingTokens.isEmpty()) { %>
              Missing values for:
              <% int mi = 0; for (String t : missingTokens) { if (mi > 0) { %>, <% } %><code><%= esc(tokenPreview(t)) %></code><% mi++; } %>
            <% } %>
          </div>
          <div
            id="missingValuesActions"
            style="margin-top:8px; gap:8px; flex-wrap:wrap;<%= missingTokens.isEmpty() ? "display:none;" : "display:flex;" %>">
            <button type="button" class="btn btn-ghost" id="btnPromptMissingSave">Prompt Missing + Save</button>
            <button type="button" class="btn btn-ghost" id="btnPromptMissingAssemble">Prompt Missing + Save + Assemble</button>
            <a class="btn btn-ghost" id="btnOpenCaseLists" href="<%= ctx %>/case_lists.jsp?matter_uuid=<%= enc(selectedMatterUuid) %>&template_uuid=<%= enc(selectedTemplateUuid) %><%= assemblyQs %><%= focusQs %><%= renderPreviewQs %>">Open List/Grid Editor</a>
          </div>
        </div>
        <div id="missingValuesEmpty" class="meta" style="<%= missingTokens.isEmpty() ? "" : "display:none;" %>">No unresolved scalar values. List/grid datasets are prompted only when focused.</div>
      </section>

      <section class="forms-tab-panel" id="formsPanelTokens" data-forms-tab-panel="tokens" role="tabpanel" aria-labelledby="formsTabTokens">
        <div class="forms-side-meta" style="margin-top:0;">
          <div class="meta">
            Tokens found: <%= workspaceTokenDefaults.size() %>
          </div>
          <div class="meta tokens-found">
            <% if (!usedTokens.isEmpty()) { %>
              <% int ui = 0; for (String t : usedTokens) { if (ui > 0) { %>, <% } %><code><%= esc(tokenPreview(t)) %></code> (<%= tokenCounts.getOrDefault(t, 0) %>)<% ui++; } %>
            <% } else { %>
              No recognized replacement tokens in current template source.
            <% } %>
          </div>
        </div>
      </section>
    </div>
  </div>
</aside>

<section class="card forms-preview-card">
  <div class="forms-preview-head">
    <h2 style="margin:0;">Interactive Preview Workspace</h2>
    <div class="meta">Live document preview and token navigation context.</div>
    <div class="meta" id="tokenMatchMeta" style="margin-top:6px;">Select a token to navigate matches. Replacements also apply inside table cells and tolerate common delimiter mistakes (smart quotes/full-width brackets).</div>
  </div>

  <div class="forms-preview-scroll">
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
        <img id="contextPreviewImage" alt="Focused token preview image" />
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
</section>
</div>
<textarea id="workspaceText" rows="2" style="position:absolute; left:-10000px; top:auto; width:1px; height:1px; opacity:0;"><%= esc(sourcePreviewText) %></textarea>
</div>

<script>
  var tokenSelect = document.getElementById("tokenSelect");
  var replaceValue = document.getElementById("replaceValue");
  var workspace = document.getElementById("workspaceText");
  var workspaceStyled = null;
  var tokenMatchMeta = document.getElementById("tokenMatchMeta");
  var downloadForm = document.getElementById("downloadForm");
  var formsSelectionForm = document.getElementById("formsSelectionForm");
  var downloadOverrides = document.getElementById("downloadOverrides");
  var assemblyUuidField = document.getElementById("assemblyUuidField");
  var missingValuesAlert = document.getElementById("missingValuesAlert");
  var missingValuesText = document.getElementById("missingValuesText");
  var missingValuesActions = document.getElementById("missingValuesActions");
  var missingValuesEmpty = document.getElementById("missingValuesEmpty");
  var formsMissingCountPill = document.getElementById("formsMissingCountPill");
  var formsTabButtons = Array.prototype.slice.call(document.querySelectorAll("[data-forms-tab-target]"));
  var formsTabPanels = Array.prototype.slice.call(document.querySelectorAll("[data-forms-tab-panel]"));
  var selectedTemplateUuidField = document.getElementById("selectedTemplateUuidField");
  var selectedTemplatePath = document.getElementById("selectedTemplatePath");
  var btnOpenTemplatePicker = document.getElementById("btnOpenTemplatePicker");
  var templatePickerModal = document.getElementById("templatePickerModal");
  var btnCloseTemplatePicker = document.getElementById("btnCloseTemplatePicker");
  var templatePickerSearch = document.getElementById("templatePickerSearch");
  var templatePickerList = document.getElementById("templatePickerList");
  var templatePickerEmpty = document.getElementById("templatePickerEmpty");
  var templatePickerItems = Array.prototype.slice.call(document.querySelectorAll("[data-template-item]"));
  var templatePickerGroups = Array.prototype.slice.call(document.querySelectorAll("[data-template-group]"));
  var templatePickerCloseTriggers = Array.prototype.slice.call(document.querySelectorAll("[data-template-modal-close]"));
  var renderedPreviewPages = document.getElementById("renderedPreviewPages");
  var renderedPreviewMeta = document.getElementById("renderedPreviewMeta");
  var renderedPreviewWarning = document.getElementById("renderedPreviewWarning");
  var contextPreviewViewport = document.getElementById("contextPreviewViewport");
  var contextPreviewImage = document.getElementById("contextPreviewImage");
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
  var serverContextPreview = null;
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
  var livePreviewEnabled = false;
  var eachDirectiveKeys = Object.create(null);
  var formsLeftTab = "replace";
  var formsLeftTabInitialized = false;

  var btnFirstToken = document.getElementById("btnFirstToken");
  var btnPrevToken = document.getElementById("btnPrevToken");
  var btnNextToken = document.getElementById("btnNextToken");
  var btnLastToken = document.getElementById("btnLastToken");
  var btnReplaceOnce = document.getElementById("btnReplaceOnce");
  var btnReplaceAll = document.getElementById("btnReplaceAll");
  var btnPromptMissingSave = document.getElementById("btnPromptMissingSave");
  var btnPromptMissingAssemble = document.getElementById("btnPromptMissingAssemble");
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

  function appendStyledPiece(parent, text, css, hitKind) {
    if (!parent) return;
    var t = String(text || "");
    if (!t) return;

    var span = document.createElement("span");
    span.textContent = t;

    var baseCss = String(css || "");
    var kind = String(hitKind || "");
    if (kind === "active") {
      span.style.cssText = (baseCss ? (baseCss + ";") : "")
        + "background:#ffe79a;outline:1px solid #e6ba11;outline-offset:-1px;border-radius:2px;";
      span.setAttribute("data-hit", "1");
      span.setAttribute("data-hit-kind", "active");
    } else if (kind === "multi") {
      span.style.cssText = (baseCss ? (baseCss + ";") : "")
        + "background:rgba(244,114,182,0.35);outline:1px solid rgba(190,24,93,0.88);outline-offset:-1px;border-radius:2px;";
      span.setAttribute("data-hit-kind", "multi");
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
      var ranges = [];
      for (var hi = 0; hi < tokenMatches.length; hi++) {
        var h = tokenMatches[hi];
        if (!h) continue;
        var hs = Number(h.start);
        var he = Number(h.end);
        if (!isFinite(hs) || !isFinite(he) || he <= hs) continue;
        if (he <= segStart || hs >= segEnd) continue;
        ranges.push({
          start: hs,
          end: he,
          kind: hi === tokenMatchIndex ? "active" : "multi"
        });
      }

      if (!ranges.length) {
        appendStyledPiece(frag, text, css, "");
      } else {
        ranges.sort(function (a, b) {
          if (a.start !== b.start) return a.start - b.start;
          return a.end - b.end;
        });

        var cursor = segStart;
        for (var ri = 0; ri < ranges.length && cursor < segEnd; ri++) {
          var r = ranges[ri];
          if (!r) continue;
          if (r.end <= cursor) continue;

          var preEnd = Math.min(segEnd, Math.max(cursor, r.start));
          if (preEnd > cursor) {
            var a1 = cursor - segStart;
            var b1 = preEnd - segStart;
            appendStyledPiece(frag, text.substring(a1, b1), css, "");
            cursor = preEnd;
          }
          if (cursor >= segEnd) break;

          var hs2 = Math.max(cursor, r.start);
          var he2 = Math.min(segEnd, r.end);
          if (he2 > hs2) {
            var a2 = hs2 - segStart;
            var b2 = he2 - segStart;
            appendStyledPiece(frag, text.substring(a2, b2), css, r.kind);
            cursor = he2;
          }
        }
        if (cursor < segEnd) {
          var a3 = cursor - segStart;
          appendStyledPiece(frag, text.substring(a3), css, "");
        }
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
    if (!contextPreviewImage) return;
    contextPreviewImage.removeAttribute("src");
    contextPreviewImage.style.display = "none";
  }

  function tokenHitsForLiteral(tokenLiteral) {
    var current = findTokenHits(imagePreviewHits, tokenLiteral);
    if (current.found) return current.hits;
    var anchor = findTokenHits(imagePreviewAnchorHits, tokenLiteral);
    return anchor.found ? anchor.hits : [];
  }

  function selectedTokenImageHits() {
    if (!tokenSelect) return [];
    return tokenHitsForLiteral(String(tokenSelect.value || ""));
  }

  function normalizedTokenLookupKey(tokenLiteral) {
    var t = String(tokenLiteral || "").trim();
    if (!t) return "";

    var curly = t.match(/^\{\{\s*([A-Za-z0-9_.-]+)\s*\}\}$/);
    if (curly) return "curly:" + String(curly[1] || "").toLowerCase();

    var bracket = t.match(/^\[\s*([^\[\]\r\n]{1,120})\s*\]$/);
    if (bracket) return "bracket:" + String(bracket[1] || "").trim().toLowerCase();

    var switchBrace = t.match(/^\{\s*([^{}\r\n]{1,240})\s*\}$/);
    if (switchBrace && String(switchBrace[1] || "").indexOf("/") >= 0) {
      return "switch:" + String(switchBrace[1] || "").trim().toLowerCase();
    }

    if (/^[A-Za-z0-9_.-]+$/.test(t)) return "curly:" + t.toLowerCase();
    return "raw:" + t.toLowerCase();
  }

  function findTokenHits(map, tokenLiteral) {
    var out = { found: false, hits: [] };
    var token = String(tokenLiteral || "").trim();
    if (!token || !map) return out;

    if (hasOwn(map, token)) {
      var direct = map[token];
      out.found = true;
      out.hits = Array.isArray(direct) ? direct : [];
      return out;
    }

    var targetKey = normalizedTokenLookupKey(token);
    var keys = Object.keys(map || {});
    for (var i = 0; i < keys.length; i++) {
      var k = String(keys[i] || "").trim();
      if (!k || !hasOwn(map, k)) continue;

      if (!equivalentPromptTokens(k, token)) {
        if (!targetKey) continue;
        if (normalizedTokenLookupKey(k) !== targetKey) continue;
      }

      out.found = true;
      out.hits = Array.isArray(map[k]) ? map[k] : [];
      return out;
    }
    return out;
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

  function selectTokenByOrdinal(ordinal, opts) {
    if (!tokenSelect) return false;
    var options = opts || {};
    if (!options.skipRecord) recordCurrentTokenOverride();
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
      btnPreviewMode.textContent = "Preview: Full";
      btnPreviewMode.title = "Switch to context preview";
    } else {
      btnPreviewMode.textContent = "Preview: Context";
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
    if (renderPreviewEnabled) refreshRenderedPreviewNow();
  }

  function setContextImage(src, message) {
    if (!contextPreviewImage) return false;
    var s = String(src || "");
    if (!s) {
      clearContextPreview();
      if (contextPreviewMeta) contextPreviewMeta.textContent = String(message || "Rendered preview is unavailable for this token.");
      return false;
    }
    contextPreviewImage.src = s;
    contextPreviewImage.style.display = "block";
    if (contextPreviewMeta) contextPreviewMeta.textContent = String(message || "");
    if (contextPreviewViewport) {
      if (previewMode === "full") {
        var fullMax = Math.max(0, contextPreviewViewport.scrollHeight - contextPreviewViewport.clientHeight);
        if (fullPreviewScrollTop > fullMax) fullPreviewScrollTop = fullMax;
        contextPreviewViewport.scrollTop = fullPreviewScrollTop;
      } else {
        contextPreviewViewport.scrollTop = 0;
      }
    }
    return true;
  }

  function contextTokenMatches(requestedToken, contextToken) {
    var req = String(requestedToken || "").trim();
    var ctx = String(contextToken || "").trim();
    if (!req || !ctx) return false;
    if (req === ctx) return true;
    if (equivalentPromptTokens(req, ctx)) return true;
    return normalizedTokenLookupKey(req) === normalizedTokenLookupKey(ctx);
  }

  function applyServerContextPreview(tokenLiteral) {
    var cp = serverContextPreview;
    if (!cp || typeof cp !== "object") return false;
    var b64 = String(cp.base64Png || "");
    if (!b64) return false;
    var msg = String(cp.message || "");
    return setContextImage("data:image/png;base64," + b64, msg);
  }

  function showPageFallback(pageIndex, message) {
    var idx = Math.max(0, Number(pageIndex) || 0);
    var img = pageImageEl(idx);
    var msg = String(message || ("Showing page " + (idx + 1) + " (no token hit found)."));
    if (!img) {
      clearContextPreview();
      if (contextPreviewMeta) contextPreviewMeta.textContent = msg;
      return;
    }
    setContextImage(String(img.src || ""), msg);
  }

  function renderImageTokenHighlights(tokenLiteral, activeIndex) {
    if (!renderPreviewEnabled) return;
    var key = String(tokenLiteral || "");
    if (!key) {
      if (contextPreviewMeta) contextPreviewMeta.textContent = "Select a token to preview.";
      clearContextPreview();
      return;
    }

    if (applyServerContextPreview(key)) return;

    var usingAnchor = false;
    var current = findTokenHits(imagePreviewHits, key);
    var hits = current.hits;
    if (!current.found) {
      var anchor = findTokenHits(imagePreviewAnchorHits, key);
      hits = anchor.hits;
      usingAnchor = anchor.found && hits.length > 0;
    }
    if (!Array.isArray(hits) || hits.length === 0) {
      showPageFallback(0, "No rendered highlight was found for the selected token. Showing page 1.");
      return;
    }

    var idx = Number(activeIndex);
    if (!isFinite(idx) || idx < 0) idx = 0;
    idx = idx % hits.length;
    var hit = hits[idx] || {};
    var pageIdx = Math.max(0, Number(hit.page) || 0);
    var msg = "Match " + (idx + 1) + " of " + hits.length
      + " • page " + (pageIdx + 1)
      + (hits.length > 1 ? " • multi-instance token" : "")
      + (usingAnchor ? " • anchored token position" : "")
      + (previewMode === "full" ? " • full page preview" : " • context preview");
    showPageFallback(pageIdx, msg);
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

  function gotoFirstToken(skipRecord) {
    selectTokenByOrdinal(0, { skipRecord: !!skipRecord });
  }

  function gotoLastToken(skipRecord) {
    var idxs = tokenOptionIndices();
    if (!idxs.length) return;
    selectTokenByOrdinal(idxs.length - 1, { skipRecord: !!skipRecord });
  }

  function gotoPrevToken(skipRecord) {
    var idxs = tokenOptionIndices();
    if (!idxs.length) return;
    var ord = currentTokenOrdinal(idxs);
    if (ord < 0) ord = 0;
    selectTokenByOrdinal(ord - 1, { skipRecord: !!skipRecord });
  }

  function gotoNextToken(skipRecord) {
    var idxs = tokenOptionIndices();
    if (!idxs.length) return;
    var ord = currentTokenOrdinal(idxs);
    if (ord < 0) ord = 0;
    selectTokenByOrdinal(ord + 1, { skipRecord: !!skipRecord });
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

  function updateTemplatePickerSelectionUi() {
    if (!templatePickerItems || templatePickerItems.length === 0) return;
    var selectedId = selectedTemplateUuidField ? String(selectedTemplateUuidField.value || "").trim() : "";
    for (var i = 0; i < templatePickerItems.length; i++) {
      var item = templatePickerItems[i];
      if (!item) continue;
      var id = String(item.getAttribute("data-template-id") || "").trim();
      if (id && selectedId && id === selectedId) item.classList.add("is-selected");
      else item.classList.remove("is-selected");
    }
  }

  function filterTemplatePickerList() {
    if (!templatePickerItems || templatePickerItems.length === 0) {
      if (templatePickerEmpty) templatePickerEmpty.classList.add("is-visible");
      return;
    }
    var query = templatePickerSearch ? String(templatePickerSearch.value || "").trim().toLowerCase() : "";
    var visibleCount = 0;
    for (var i = 0; i < templatePickerItems.length; i++) {
      var item = templatePickerItems[i];
      if (!item) continue;
      var hay = String(item.getAttribute("data-template-search") || "").toLowerCase();
      var visible = !query || hay.indexOf(query) >= 0;
      item.style.display = visible ? "" : "none";
      if (visible) visibleCount++;
    }
    for (var gi = 0; gi < templatePickerGroups.length; gi++) {
      var grp = templatePickerGroups[gi];
      if (!grp) continue;
      var itemsInGroup = grp.querySelectorAll("[data-template-item]");
      var visibleInGroup = 0;
      for (var j = 0; j < itemsInGroup.length; j++) {
        if (itemsInGroup[j] && itemsInGroup[j].style.display !== "none") visibleInGroup++;
      }
      grp.style.display = visibleInGroup > 0 ? "" : "none";
    }
    if (templatePickerEmpty) {
      if (visibleCount > 0) templatePickerEmpty.classList.remove("is-visible");
      else templatePickerEmpty.classList.add("is-visible");
    }
  }

  function closeTemplatePicker() {
    if (!templatePickerModal || templatePickerModal.hidden) return;
    templatePickerModal.hidden = true;
    if (btnOpenTemplatePicker) btnOpenTemplatePicker.focus();
  }

  function openTemplatePicker() {
    if (!templatePickerModal) return;
    templatePickerModal.hidden = false;
    if (templatePickerSearch) templatePickerSearch.value = "";
    updateTemplatePickerSelectionUi();
    filterTemplatePickerList();
    if (templatePickerSearch) templatePickerSearch.focus();
  }

  function chooseTemplateFromPicker(item) {
    if (!item) return;
    var templateId = String(item.getAttribute("data-template-id") || "").trim();
    var templateDisplay = String(item.getAttribute("data-template-display") || "").trim();
    if (!templateId) return;

    if (selectedTemplateUuidField) selectedTemplateUuidField.value = templateId;
    if (selectedTemplatePath) {
      selectedTemplatePath.textContent = templateDisplay || "Template selected";
      selectedTemplatePath.classList.remove("is-empty");
    }
    updateTemplatePickerSelectionUi();
    closeTemplatePicker();
    if (formsSelectionForm) {
      if (formsSelectionForm.requestSubmit) formsSelectionForm.requestSubmit();
      else formsSelectionForm.submit();
    }
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

  function updateMissingTabCount(count) {
    if (!formsMissingCountPill) return;
    var n = Math.max(0, Number(count) || 0);
    if (n <= 0) {
      formsMissingCountPill.style.display = "none";
      formsMissingCountPill.textContent = "0";
      return;
    }
    formsMissingCountPill.style.display = "inline-flex";
    formsMissingCountPill.textContent = n > 99 ? "99+" : String(n);
  }

  function setFormsLeftTab(tabName, opts) {
    var target = String(tabName || "").trim().toLowerCase();
    if (target !== "replace" && target !== "missing" && target !== "tokens") target = "replace";
    var options = opts || {};
    var focus = !!options.focusTabButton;

    for (var i = 0; i < formsTabButtons.length; i++) {
      var btn = formsTabButtons[i];
      if (!btn) continue;
      var key = String(btn.getAttribute("data-forms-tab-target") || "").toLowerCase();
      var active = key === target;
      if (active) btn.classList.add("is-active");
      else btn.classList.remove("is-active");
      btn.setAttribute("aria-selected", active ? "true" : "false");
      if (active && focus && btn.focus) {
        try { btn.focus(); } catch (ignored) {}
      }
    }

    for (var j = 0; j < formsTabPanels.length; j++) {
      var panel = formsTabPanels[j];
      if (!panel) continue;
      var panelKey = String(panel.getAttribute("data-forms-tab-panel") || "").toLowerCase();
      var show = panelKey === target;
      if (show) panel.classList.add("is-active");
      else panel.classList.remove("is-active");
    }

    formsLeftTab = target;
  }

  function initializeFormsLeftTab() {
    if (formsLeftTabInitialized) return;
    formsLeftTabInitialized = true;
    setFormsLeftTab("replace");
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
      if (missingValuesEmpty) missingValuesEmpty.style.display = "";
      updateMissingTabCount(0);
      return;
    }
    missingValuesAlert.style.display = "";
    missingValuesAlert.className = "alert alert-warn";
    missingValuesActions.style.display = "flex";
    if (missingValuesEmpty) missingValuesEmpty.style.display = "none";
    updateMissingTabCount(allTokens.length);
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
    serverContextPreview = d && d.contextPreview ? d.contextPreview : null;
    imagePreviewHits = Object.create(null);
    var hitKeys = Object.keys(hits);
    for (var hk = 0; hk < hitKeys.length; hk++) {
      var key = String(hitKeys[hk] || "");
      if (!key) continue;
      var val = hits[key];
      var arr = Array.isArray(val) ? val : [];
      imagePreviewHits[key] = arr;
      imagePreviewAnchorHits[key] = arr.slice();
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
        ? ("Engine: " + engine + ". Preview image is server-rendered PNG (full page or focused context).")
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
    addPair("preview_mode", previewMode === "full" ? "full" : "context");
    if (workspace && typeof workspace.value === "string") addPair("workspace_text", workspace.value);
    if (livePreviewEnabled && tokenSelect && tokenSelect.value && replaceValue) {
      addPair("live_preview_token", String(tokenSelect.value));
      addPair("live_preview_value", selectedReplacementValue());
    }
    if (focusModeEnabled) addPair("focus", "1");
    if (tokenSelect && tokenSelect.value) addPair("highlight_token", String(tokenSelect.value));
    if (tokenMatchIndex >= 0) addPair("highlight_index", String(tokenMatchIndex));

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
      var ct = "";
      try { ct = String(res.headers.get("content-type") || "").toLowerCase(); } catch (ignored) {}
      if (ct.indexOf("application/json") < 0) throw new Error("preview_refresh_non_json");
      return res.json();
    })
    .then(function (data) {
      if (reqId !== previewRequestSeq) return;
      applyRenderedPreviewData(data);
      return data;
    })
    .catch(function (err) {
      // Keep token navigation fully functional even when preview refresh fails.
      if (window.console && console.error) {
        try { console.error("forms.jsp preview refresh failed", err); } catch (ignored) {}
      }
      if (renderedPreviewWarning) {
        renderedPreviewWarning.innerHTML = "";
        var warn = document.createElement("div");
        warn.className = "alert alert-warn";
        warn.style.marginBottom = "10px";
        warn.textContent = "Preview refresh failed. The page may be stale; reload forms.jsp if this persists.";
        renderedPreviewWarning.appendChild(warn);
      }
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

  function recordCurrentTokenOverride(forcePersist) {
    if (!tokenSelect) return;
    var token = String(tokenSelect.value || "");
    if (!token) return;
    var current = selectedReplacementValue();
    var opt = tokenSelect.options[tokenSelect.selectedIndex];
    var def = defaultValueForToken(token, opt);
    var force = !!forcePersist;
    if (!force && current === def) {
      if (hasOwn(tokenOverrideValues, token)) delete tokenOverrideValues[token];
    } else {
      tokenOverrideValues[token] = current;
    }
    syncDownloadOverrides();
    refreshMissingValuesBanner();
    scheduleDraftSave(260);
  }

  function replaceOnce() {
    if (!tokenSelect) return;
    var token = String(tokenSelect.value || "");
    var literalReplaced = false;
    if (ensureMatchesReady() && tokenMatches.length > 0) {
      if (tokenMatchIndex < 0) tokenMatchIndex = 0;
      if (tokenMatchIndex >= tokenMatches.length) tokenMatchIndex = tokenMatches.length - 1;
      var hit = tokenMatches[tokenMatchIndex];
      if (hit) {
        replaceRange(hit.start, hit.end, selectedReplacementValue());
        literalReplaced = true;
      }
    }

    if (literalReplaced) {
      // Replace Once is intentionally local to the current literal match.
      clearPromptTokenValue(token);
      syncDownloadOverrides();
      refreshMissingValuesBanner();
      scheduleDraftSave(260);
    } else {
      recordCurrentTokenOverride(true);
      if (tokenMatchMeta) {
        tokenMatchMeta.textContent = "No literal token match in text. Replacement value saved for assembly.";
      }
    }

    livePreviewEnabled = false;
    var idxs = tokenOptionIndices();
    if (idxs.length > 1) {
      gotoNextToken(true);
      if (renderPreviewEnabled) refreshRenderedPreviewNow();
      return;
    }
    recalcMatches();
    if (renderPreviewEnabled) refreshRenderedPreviewNow();
    else scheduleRenderedPreviewRefresh();
  }

  function replaceAllTokens() {
    if (!tokenSelect) return;
    recordCurrentTokenOverride(true);

    var token = tokenSelect.value;
    var re = tokenRegex(token);
    if (re) {
      if (tokenMatches.length === 0) {
        recalcMatches();
      }
      if (tokenMatches.length > 0) {
        var hits = tokenMatches.slice();
        for (var i = hits.length - 1; i >= 0; i--) {
          replaceRange(hits[i].start, hits[i].end, selectedReplacementValue());
        }
      } else if (tokenMatchMeta) {
        tokenMatchMeta.textContent = "No literal token matches found to replace. Replacement value saved for assembly.";
      }
    } else if (tokenMatchMeta) {
      tokenMatchMeta.textContent = "Selected token is not replaceable in text. Replacement value saved for assembly.";
    }

    livePreviewEnabled = false;
    var idxs = tokenOptionIndices();
    if (idxs.length > 1) {
      gotoNextToken(true);
      if (renderPreviewEnabled) refreshRenderedPreviewNow();
      return;
    }
    recalcMatches();
    if (renderPreviewEnabled) refreshRenderedPreviewNow();
    else scheduleRenderedPreviewRefresh();
  }

  function resetWorkspace() {
    workspaceSegments = normalizeSegments(cloneSegments(originalWorkspaceSegments));
    tokenOverrideValues = Object.create(null);
    livePreviewEnabled = false;
    syncDownloadOverrides();
    syncWorkspaceTextValue();
    refreshMissingValuesBanner();
    scheduleDraftSave(0);
    recalcMatches();
    scheduleRenderedPreviewRefresh();
  }

  function syncDefaultReplaceValue() {
    if (!tokenSelect || !replaceValue) return;
    livePreviewEnabled = false;
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
    if (templatePickerModal && !templatePickerModal.hidden) return;
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

  for (var tbi = 0; tbi < formsTabButtons.length; tbi++) {
    (function () {
      var btn = formsTabButtons[tbi];
      if (!btn) return;
      btn.addEventListener("click", function () {
        var target = String(btn.getAttribute("data-forms-tab-target") || "").toLowerCase();
        setFormsLeftTab(target, { focusTabButton: false });
      });
    })();
  }

  function bindActionButton(button, handler) {
    if (!button || typeof handler !== "function") return;
    button.addEventListener("click", function (ev) {
      if (ev) ev.preventDefault();
      handler();
    });
  }

  bindActionButton(btnFirstToken, gotoFirstToken);
  bindActionButton(btnPrevToken, gotoPrevToken);
  bindActionButton(btnNextToken, gotoNextToken);
  bindActionButton(btnLastToken, gotoLastToken);
  bindActionButton(btnReplaceOnce, replaceOnce);
  bindActionButton(btnReplaceAll, replaceAllTokens);
  bindActionButton(btnResetWorkspace, resetWorkspace);
  bindActionButton(btnPreviewMode, togglePreviewMode);
  bindActionButton(btnPromptMissingSave, function () { promptForMissingValues(false); });
  bindActionButton(btnPromptMissingAssemble, function () { promptForMissingValues(true); });
  bindActionButton(btnOpenTemplatePicker, openTemplatePicker);

  if (btnCloseTemplatePicker) {
    btnCloseTemplatePicker.addEventListener("click", function (ev) {
      if (ev) ev.preventDefault();
      closeTemplatePicker();
    });
  }
  for (var cti = 0; cti < templatePickerCloseTriggers.length; cti++) {
    (function () {
      var trigger = templatePickerCloseTriggers[cti];
      if (!trigger) return;
      trigger.addEventListener("click", function (ev) {
        if (ev) ev.preventDefault();
        closeTemplatePicker();
      });
    })();
  }
  for (var tpi = 0; tpi < templatePickerItems.length; tpi++) {
    (function () {
      var item = templatePickerItems[tpi];
      if (!item) return;
      item.addEventListener("click", function (ev) {
        if (ev) ev.preventDefault();
        chooseTemplateFromPicker(item);
      });
    })();
  }
  if (templatePickerSearch) {
    templatePickerSearch.addEventListener("input", filterTemplatePickerList);
  }

  if (tokenSelect) {
    tokenSelect.addEventListener("change", function () {
      livePreviewEnabled = false;
      syncDefaultReplaceValue();
      recalcMatches();
      scheduleRenderedPreviewRefresh();
      syncCaseListsLink();
    });
  }

  if (replaceValue) {
    replaceValue.addEventListener("input", function () {
      // Keep typed value local (not persisted), but update preview with a transient server-side override.
      livePreviewEnabled = true;
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
  document.addEventListener("keydown", function (ev) {
    if (!ev) return;
    if (!templatePickerModal || templatePickerModal.hidden) return;
    if (ev.key === "Escape") {
      ev.preventDefault();
      closeTemplatePicker();
    }
  }, true);
  document.addEventListener("keydown", handleHotKeys, true);

  syncWorkspaceTextValue();
  syncAssemblyUuid(currentAssemblyUuid);
  if (focusTokenParam) selectTokenByLiteral(focusTokenParam);
  syncDownloadOverrides();
  syncDefaultReplaceValue();
  updateTemplatePickerSelectionUi();
  filterTemplatePickerList();
  refreshMissingValuesBanner();
  initializeFormsLeftTab();
  syncCaseListsLink();
  updatePreviewModeUi();
  recalcMatches();
  renderAllSurfaces(false);
  if (renderPreviewEnabled) scheduleRenderedPreviewRefresh();
  if (resumePromptAfterLists) {
    setTimeout(function () {
      promptForMissingValues(resumeAssembleAfterLists);
    }, 50);
  }
</script>

<jsp:include page="footer.jsp" />
