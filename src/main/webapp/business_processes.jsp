<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" isELIgnored="true" %>

<%@ page import="com.fasterxml.jackson.core.type.TypeReference" %>
<%@ page import="com.fasterxml.jackson.databind.DeserializationFeature" %>
<%@ page import="com.fasterxml.jackson.databind.ObjectMapper" %>
<%@ page import="java.net.URLEncoder" %>
<%@ page import="java.nio.charset.StandardCharsets" %>
<%@ page import="java.util.ArrayList" %>
<%@ page import="java.util.LinkedHashMap" %>
<%@ page import="java.util.List" %>
<%@ page import="java.util.Locale" %>
<%@ page import="java.util.Map" %>

<%@ page import="net.familylawandprobate.controversies.business_process_manager" %>
<%@ page import="net.familylawandprobate.controversies.tenant_settings" %>

<%@ include file="security.jspf" %>
<%
  if (!require_login()) return;
%>

<%!
  private static final String S_TENANT_UUID = "tenant.uuid";
  private static final String S_USER_UUID = "user.uuid";
  private static final String CSRF_SESSION_KEY = "CSRF_TOKEN";

  private static final ObjectMapper JSON = new ObjectMapper()
      .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
  private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<LinkedHashMap<String, Object>>() {};

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
    return "true".equals(v) || "1".equals(v) || "yes".equals(v) || "on".equals(v) || "y".equals(v);
  }

  private static int intLike(String raw, int fallback) {
    try { return Integer.parseInt(safe(raw).trim()); } catch (Exception ignored) { return fallback; }
  }

  private static LinkedHashMap<String, String> stringMap(Object raw) {
    LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
    if (!(raw instanceof Map<?, ?>)) return out;
    Map<?, ?> m = (Map<?, ?>) raw;
    for (Map.Entry<?, ?> e : m.entrySet()) {
      if (e == null) continue;
      String k = safe(String.valueOf(e.getKey())).trim();
      if (k.isBlank()) continue;
      out.put(k, safe(String.valueOf(e.getValue())));
    }
    return out;
  }

  private static List<Map<String, Object>> objectList(Object raw) {
    ArrayList<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
    if (!(raw instanceof List<?>)) return out;
    List<?> xs = (List<?>) raw;
    for (Object v : xs) {
      if (!(v instanceof Map<?, ?>)) continue;
      Map<?, ?> m = (Map<?, ?>) v;
      LinkedHashMap<String, Object> row = new LinkedHashMap<String, Object>();
      for (Map.Entry<?, ?> e : m.entrySet()) {
        if (e == null) continue;
        String k = safe(String.valueOf(e.getKey())).trim();
        if (k.isBlank()) continue;
        row.put(k, e.getValue());
      }
      out.add(row);
    }
    return out;
  }

  private static business_process_manager.ProcessDefinition processFromMap(Map<String, Object> raw) {
    business_process_manager.ProcessDefinition out = new business_process_manager.ProcessDefinition();
    if (raw == null) return out;

    out.processUuid = safe(String.valueOf(raw.getOrDefault("process_uuid", raw.getOrDefault("uuid", "")))).trim();
    out.name = safe(String.valueOf(raw.getOrDefault("name", ""))).trim();
    out.description = safe(String.valueOf(raw.getOrDefault("description", ""))).trim();
    out.enabled = boolLike(String.valueOf(raw.getOrDefault("enabled", "true")));
    out.updatedAt = safe(String.valueOf(raw.getOrDefault("updated_at", ""))).trim();

    for (Map<String, Object> t : objectList(raw.get("triggers"))) {
      if (t == null) continue;
      business_process_manager.ProcessTrigger trg = new business_process_manager.ProcessTrigger();
      trg.type = safe(String.valueOf(t.getOrDefault("type", ""))).trim();
      trg.key = safe(String.valueOf(t.getOrDefault("key", ""))).trim();
      trg.op = safe(String.valueOf(t.getOrDefault("op", "equals"))).trim();
      trg.value = safe(String.valueOf(t.getOrDefault("value", "")));
      trg.enabled = boolLike(String.valueOf(t.getOrDefault("enabled", "true")));
      if (!trg.type.isBlank()) out.triggers.add(trg);
    }

    int idx = 0;
    for (Map<String, Object> s : objectList(raw.get("steps"))) {
      if (s == null) continue;
      business_process_manager.ProcessStep step = new business_process_manager.ProcessStep();
      step.stepId = safe(String.valueOf(s.getOrDefault("step_id", s.getOrDefault("id", "step_" + (++idx))))).trim();
      step.order = intLike(String.valueOf(s.getOrDefault("order", String.valueOf(idx * 10))), idx * 10);
      step.action = safe(String.valueOf(s.getOrDefault("action", ""))).trim();
      step.label = safe(String.valueOf(s.getOrDefault("label", ""))).trim();
      step.enabled = boolLike(String.valueOf(s.getOrDefault("enabled", "true")));
      step.settings = stringMap(s.get("settings"));

      for (Map<String, Object> c : objectList(s.get("conditions"))) {
        if (c == null) continue;
        business_process_manager.ProcessCondition cond = new business_process_manager.ProcessCondition();
        cond.source = safe(String.valueOf(c.getOrDefault("source", "event"))).trim();
        cond.key = safe(String.valueOf(c.getOrDefault("key", ""))).trim();
        cond.op = safe(String.valueOf(c.getOrDefault("op", "equals"))).trim();
        cond.value = safe(String.valueOf(c.getOrDefault("value", "")));
        if (!cond.key.isBlank()) step.conditions.add(cond);
      }

      if (!step.action.isBlank()) out.steps.add(step);
    }

    return out;
  }

  private static LinkedHashMap<String, Object> processToMap(business_process_manager.ProcessDefinition p) {
    LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
    if (p == null) return out;
    out.put("process_uuid", safe(p.processUuid));
    out.put("name", safe(p.name));
    out.put("description", safe(p.description));
    out.put("enabled", p.enabled);

    ArrayList<LinkedHashMap<String, Object>> triggers = new ArrayList<LinkedHashMap<String, Object>>();
    for (business_process_manager.ProcessTrigger t : p.triggers) {
      if (t == null) continue;
      LinkedHashMap<String, Object> row = new LinkedHashMap<String, Object>();
      row.put("type", safe(t.type));
      row.put("key", safe(t.key));
      row.put("op", safe(t.op));
      row.put("value", safe(t.value));
      row.put("enabled", t.enabled);
      triggers.add(row);
    }
    out.put("triggers", triggers);

    ArrayList<LinkedHashMap<String, Object>> steps = new ArrayList<LinkedHashMap<String, Object>>();
    for (business_process_manager.ProcessStep s : p.steps) {
      if (s == null) continue;
      LinkedHashMap<String, Object> row = new LinkedHashMap<String, Object>();
      row.put("step_id", safe(s.stepId));
      row.put("order", s.order);
      row.put("action", safe(s.action));
      row.put("label", safe(s.label));
      row.put("enabled", s.enabled);
      row.put("settings", new LinkedHashMap<String, String>(s.settings));

      ArrayList<LinkedHashMap<String, Object>> conds = new ArrayList<LinkedHashMap<String, Object>>();
      for (business_process_manager.ProcessCondition c : s.conditions) {
        if (c == null) continue;
        LinkedHashMap<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("source", safe(c.source));
        m.put("key", safe(c.key));
        m.put("op", safe(c.op));
        m.put("value", safe(c.value));
        conds.add(m);
      }
      row.put("conditions", conds);
      steps.add(row);
    }
    out.put("steps", steps);
    return out;
  }

  private static String settingsLines(Map<String, String> settings) {
    if (settings == null || settings.isEmpty()) return "";
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<String, String> e : settings.entrySet()) {
      if (e == null) continue;
      String k = safe(e.getKey()).trim();
      if (k.isBlank()) continue;
      sb.append(k).append("=").append(safe(e.getValue())).append("\n");
    }
    return sb.toString().trim();
  }
%>

<%
  String ctx = safe(request.getContextPath());
  String tenantUuid = safe((String) session.getAttribute(S_TENANT_UUID)).trim();
  String userUuid = safe((String) session.getAttribute(S_USER_UUID)).trim();
  if (tenantUuid.isBlank()) {
    response.sendRedirect(ctx + "/tenant_login.jsp");
    return;
  }

  String csrfToken = csrfForRender(request);
  business_process_manager bpm = business_process_manager.defaultService();
  tenant_settings tenantSettings = tenant_settings.defaultStore();
  try { bpm.ensureTenant(tenantUuid); } catch (Exception ignored) {}

  LinkedHashMap<String, String> tenantCfg = tenantSettings.read(tenantUuid);
  int configuredRunningThreshold = Math.max(1, intLike(tenantCfg.get("bpm_aging_alarm_running_stale_minutes"), 60));
  int configuredReviewThreshold = Math.max(1, intLike(tenantCfg.get("bpm_aging_alarm_review_stale_minutes"), 1440));
  boolean agingAlarmEnabled = boolLike(tenantCfg.get("bpm_aging_alarm_enabled"));
  int agingAlarmIntervalMinutes = Math.max(1, intLike(tenantCfg.get("bpm_aging_alarm_interval_minutes"), 15));
  int agingAlarmMaxIssues = Math.max(1, intLike(tenantCfg.get("bpm_aging_alarm_max_issues_per_scan"), 200));

  int runningThresholdMinutes = Math.max(1, intLike(request.getParameter("running_threshold_mins"), configuredRunningThreshold));
  int reviewThresholdMinutes = Math.max(1, intLike(request.getParameter("review_threshold_mins"), configuredReviewThreshold));
  String thresholdQuery = "&running_threshold_mins=" + runningThresholdMinutes
      + "&review_threshold_mins=" + reviewThresholdMinutes;

  if ("POST".equalsIgnoreCase(request.getMethod())) {
    String action = safe(request.getParameter("action")).trim();

    try {
      if ("save_aging_alarm_settings".equalsIgnoreCase(action)) {
        boolean enabled = boolLike(request.getParameter("aging_alarm_enabled"));
        int intervalMinutes = Math.max(1, intLike(request.getParameter("aging_alarm_interval_minutes"), agingAlarmIntervalMinutes));
        intervalMinutes = Math.min(intervalMinutes, 1440);
        int runningMinutes = Math.max(1, intLike(request.getParameter("aging_alarm_running_stale_minutes"), runningThresholdMinutes));
        runningMinutes = Math.min(runningMinutes, 43200);
        int reviewMinutes = Math.max(1, intLike(request.getParameter("aging_alarm_review_stale_minutes"), reviewThresholdMinutes));
        reviewMinutes = Math.min(reviewMinutes, 43200);
        int maxIssues = Math.max(1, intLike(request.getParameter("aging_alarm_max_issues_per_scan"), agingAlarmMaxIssues));
        maxIssues = Math.min(maxIssues, 2000);

        LinkedHashMap<String, String> nextCfg = tenantSettings.read(tenantUuid);
        nextCfg.put("bpm_aging_alarm_enabled", enabled ? "true" : "false");
        nextCfg.put("bpm_aging_alarm_interval_minutes", String.valueOf(intervalMinutes));
        nextCfg.put("bpm_aging_alarm_running_stale_minutes", String.valueOf(runningMinutes));
        nextCfg.put("bpm_aging_alarm_review_stale_minutes", String.valueOf(reviewMinutes));
        nextCfg.put("bpm_aging_alarm_max_issues_per_scan", String.valueOf(maxIssues));
        tenantSettings.write(tenantUuid, nextCfg);

        response.sendRedirect(ctx + "/business_processes.jsp?alarm_saved=1"
            + "&running_threshold_mins=" + runningMinutes
            + "&review_threshold_mins=" + reviewMinutes);
        return;
      }

      if ("save_process".equalsIgnoreCase(action)) {
        String processJson = safe(request.getParameter("process_json")).trim();
        if (processJson.isBlank()) throw new IllegalArgumentException("Process JSON is required.");

        LinkedHashMap<String, Object> raw = JSON.readValue(processJson, MAP_TYPE);
        String processUuid = safe(request.getParameter("process_uuid")).trim();
        if (!processUuid.isBlank()) raw.put("process_uuid", processUuid);

        business_process_manager.ProcessDefinition saved = bpm.saveProcess(tenantUuid, processFromMap(raw));
        response.sendRedirect(ctx + "/business_processes.jsp?saved=1&process_uuid=" + enc(saved.processUuid));
        return;
      }

      if ("delete_process".equalsIgnoreCase(action)) {
        String processUuid = safe(request.getParameter("process_uuid")).trim();
        bpm.deleteProcess(tenantUuid, processUuid);
        response.sendRedirect(ctx + "/business_processes.jsp?deleted=1");
        return;
      }

      if ("trigger_process".equalsIgnoreCase(action)) {
        String processUuid = safe(request.getParameter("process_uuid")).trim();
        String eventType = safe(request.getParameter("event_type")).trim();
        if (eventType.isBlank()) eventType = "manual";
        String payloadJson = safe(request.getParameter("payload_json")).trim();

        LinkedHashMap<String, String> payload = new LinkedHashMap<String, String>();
        if (!payloadJson.isBlank()) payload = stringMap(JSON.readValue(payloadJson, MAP_TYPE));

        bpm.triggerProcess(tenantUuid, processUuid, eventType, payload, userUuid, "jsp.business_processes.trigger");
        response.sendRedirect(ctx + "/business_processes.jsp?triggered=1&process_uuid=" + enc(processUuid));
        return;
      }

      if ("undo_run".equalsIgnoreCase(action)) {
        bpm.undoRun(tenantUuid, safe(request.getParameter("run_uuid")), userUuid);
        response.sendRedirect(ctx + "/business_processes.jsp?undone=1");
        return;
      }

      if ("redo_run".equalsIgnoreCase(action)) {
        bpm.redoRun(tenantUuid, safe(request.getParameter("run_uuid")), userUuid);
        response.sendRedirect(ctx + "/business_processes.jsp?redone=1");
        return;
      }

      if ("mark_run_failed".equalsIgnoreCase(action)) {
        bpm.markRunFailed(
            tenantUuid,
            safe(request.getParameter("run_uuid")),
            userUuid,
            safe(request.getParameter("operator_note"))
        );
        response.sendRedirect(ctx + "/business_processes.jsp?repair=failed" + thresholdQuery);
        return;
      }

      if ("retry_run".equalsIgnoreCase(action)) {
        business_process_manager.RunResult retried = bpm.retryRun(
            tenantUuid,
            safe(request.getParameter("run_uuid")),
            userUuid,
            safe(request.getParameter("operator_note"))
        );
        response.sendRedirect(ctx + "/business_processes.jsp?repair=retried&retry_run_uuid="
            + enc(safe(retried == null ? "" : retried.runUuid)) + thresholdQuery);
        return;
      }

      if ("reject_review".equalsIgnoreCase(action)) {
        bpm.completeReview(
            tenantUuid,
            safe(request.getParameter("review_uuid")),
            false,
            userUuid,
            new LinkedHashMap<String, String>(),
            safe(request.getParameter("operator_note"))
        );
        response.sendRedirect(ctx + "/business_processes.jsp?repair=review_rejected" + thresholdQuery);
        return;
      }
    } catch (Exception ex) {
      response.sendRedirect(ctx + "/business_processes.jsp?error=" + enc(safe(ex.getMessage())));
      return;
    }
  }

  String message = null;
  if ("1".equals(request.getParameter("saved"))) message = "Business process saved.";
  else if ("1".equals(request.getParameter("deleted"))) message = "Business process deleted.";
  else if ("1".equals(request.getParameter("triggered"))) message = "Business process triggered.";
  else if ("1".equals(request.getParameter("alarm_saved"))) message = "Aging alarm settings saved.";
  else if ("1".equals(request.getParameter("undone"))) message = "Run undo operation completed.";
  else if ("1".equals(request.getParameter("redone"))) message = "Run redo operation completed.";
  else if ("failed".equalsIgnoreCase(safe(request.getParameter("repair")))) message = "Run was marked failed.";
  else if ("review_rejected".equalsIgnoreCase(safe(request.getParameter("repair")))) message = "Human review was rejected.";
  else if ("retried".equalsIgnoreCase(safe(request.getParameter("repair")))) {
    String retryRunUuid = safe(request.getParameter("retry_run_uuid")).trim();
    message = retryRunUuid.isBlank()
        ? "Run retry started."
        : "Run retry started. New run UUID: " + retryRunUuid;
  }

  String error = safe(request.getParameter("error")).trim();

  List<business_process_manager.ProcessDefinition> processes = bpm.listProcesses(tenantUuid);
  String selectedProcessUuid = safe(request.getParameter("process_uuid")).trim();
  business_process_manager.ProcessDefinition selected = null;
  for (business_process_manager.ProcessDefinition p : processes) {
    if (p == null) continue;
    if (selectedProcessUuid.equals(safe(p.processUuid))) {
      selected = p;
      break;
    }
  }
  if (selected == null && !processes.isEmpty()) selected = processes.get(0);

  String processJson = "{\n"
      + "  \"name\": \"Matter Intake Automation\",\n"
      + "  \"description\": \"Example process with automated and human-review steps.\",\n"
      + "  \"enabled\": true,\n"
      + "  \"triggers\": [\n"
      + "    {\"type\": \"matter.created\", \"enabled\": true}\n"
      + "  ],\n"
      + "  \"steps\": [\n"
      + "    {\"step_id\": \"set_priority\", \"order\": 10, \"action\": \"set_case_field\", \"label\": \"Set priority\", \"enabled\": true, \"settings\": {\"matter_uuid\": \"{{event.matter_uuid}}\", \"field_key\": \"priority\", \"field_value\": \"High\"}, \"conditions\": []},\n"
      + "    {\"step_id\": \"review\", \"order\": 20, \"action\": \"human_review\", \"label\": \"Attorney review\", \"enabled\": true, \"settings\": {\"title\": \"Review new matter\", \"instructions\": \"Confirm intake quality before continuing.\", \"required_input_keys\": \"decision,notes\"}, \"conditions\": []}\n"
      + "  ]\n"
      + "}";

  if (selected != null) {
    processJson = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(processToMap(selected));
  }

  List<business_process_manager.RunResult> runs = bpm.listRuns(tenantUuid, 100);
  List<business_process_manager.RunHealthIssue> runHealth = bpm.inspectRunHealth(
      tenantUuid,
      runningThresholdMinutes,
      reviewThresholdMinutes,
      200
  );
  int pendingReviews = bpm.listReviews(tenantUuid, true, 100).size();
%>

<jsp:include page="header.jsp" />

<section class="card">
  <h1 style="margin:0;">Business Process Manager</h1>
  <div class="meta" style="margin-top:6px;">
    Tenant-specific, XML-backed process automation with multi-trigger steps, human review gates, and run-level undo/redo.
  </div>
  <div class="meta" style="margin-top:8px;">
    Pending human reviews: <strong><%= pendingReviews %></strong>
    <a class="btn btn-ghost" style="margin-left:10px;" href="<%= ctx %>/business_process_reviews.jsp">Open Review Queue</a>
  </div>

  <% if (message != null) { %>
    <div class="alert alert-ok" style="margin-top:12px;"><%= esc(message) %></div>
  <% } %>
  <% if (!error.isBlank()) { %>
    <div class="alert alert-error" style="margin-top:12px;"><%= esc(error) %></div>
  <% } %>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin:0 0 8px 0;">Saved Processes</h2>
  <div class="table-wrap">
    <table class="table">
      <thead>
        <tr>
          <th>Name</th>
          <th>Enabled</th>
          <th>Triggers</th>
          <th>Steps</th>
          <th>Actions</th>
        </tr>
      </thead>
      <tbody>
      <% for (business_process_manager.ProcessDefinition p : processes) {
           if (p == null) continue;
      %>
        <tr>
          <td><%= esc(safe(p.name)) %></td>
          <td><%= p.enabled ? "Yes" : "No" %></td>
          <td><%= p.triggers == null ? 0 : p.triggers.size() %></td>
          <td><%= p.steps == null ? 0 : p.steps.size() %></td>
          <td style="display:flex; gap:8px; flex-wrap:wrap;">
            <a class="btn btn-ghost" href="<%= ctx %>/business_processes.jsp?process_uuid=<%= enc(safe(p.processUuid)) %>">Edit</a>
            <form method="post" action="<%= ctx %>/business_processes.jsp" style="display:inline;">
              <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
              <input type="hidden" name="action" value="delete_process" />
              <input type="hidden" name="process_uuid" value="<%= esc(safe(p.processUuid)) %>" />
              <button class="btn btn-ghost" type="submit" onclick="return confirm('Delete this process?');">Delete</button>
            </form>
          </td>
        </tr>
      <% } %>
      </tbody>
    </table>
  </div>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin:0 0 8px 0;"><%= selected == null ? "Create Process" : "Edit Process" %></h2>

  <div class="meta" style="margin-bottom:8px;">
    Novice mode: guided trigger/step builder. Expert mode: direct JSON editing for advanced conditions and settings.
  </div>

  <div style="display:flex; gap:10px; margin-bottom:10px;">
    <button type="button" id="modeNoviceBtn" class="btn btn-ghost" onclick="setEditorMode('novice')">Novice Mode</button>
    <button type="button" id="modeExpertBtn" class="btn btn-ghost" onclick="setEditorMode('expert')">Expert Mode</button>
  </div>

  <form id="processForm" method="post" action="<%= ctx %>/business_processes.jsp" onsubmit="return prepareProcessPayload();">
    <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
    <input type="hidden" name="action" value="save_process" />
    <input type="hidden" name="process_uuid" id="processUuid" value="<%= esc(selected == null ? "" : safe(selected.processUuid)) %>" />
    <input type="hidden" name="editor_mode" id="editorMode" value="novice" />

    <div style="display:grid; grid-template-columns:repeat(auto-fit, minmax(220px, 1fr)); gap:10px;">
      <label>
        <div class="meta">Process Name</div>
        <input type="text" id="processName" value="<%= esc(selected == null ? "" : safe(selected.name)) %>" />
      </label>
      <label>
        <div class="meta">Enabled</div>
        <select id="processEnabled">
          <option value="true" <%= selected == null || selected.enabled ? "selected" : "" %>>Yes</option>
          <option value="false" <%= selected != null && !selected.enabled ? "selected" : "" %>>No</option>
        </select>
      </label>
    </div>
    <label style="display:block; margin-top:10px;">
      <div class="meta">Description</div>
      <textarea id="processDescription" rows="2"><%= esc(selected == null ? "" : safe(selected.description)) %></textarea>
    </label>

    <div id="novicePanel" style="margin-top:12px;">
      <h3 style="margin:0 0 8px 0;">Triggers</h3>
      <div class="table-wrap">
        <table class="table" id="triggerTable">
          <thead>
            <tr>
              <th>Type</th>
              <th>If Key</th>
              <th>Op</th>
              <th>Value</th>
              <th>Enabled</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody id="triggerRows">
          <% if (selected != null && selected.triggers != null && !selected.triggers.isEmpty()) {
               for (business_process_manager.ProcessTrigger t : selected.triggers) {
                 if (t == null) continue;
          %>
            <tr>
              <td><input type="text" class="trg-type" value="<%= esc(safe(t.type)) %>" placeholder="matter.created" /></td>
              <td><input type="text" class="trg-key" value="<%= esc(safe(t.key)) %>" placeholder="matter_status_uuid" /></td>
              <td><input type="text" class="trg-op" value="<%= esc(safe(t.op)) %>" placeholder="equals" /></td>
              <td><input type="text" class="trg-value" value="<%= esc(safe(t.value)) %>" placeholder="open" /></td>
              <td>
                <select class="trg-enabled">
                  <option value="true" <%= t.enabled ? "selected" : "" %>>Yes</option>
                  <option value="false" <%= t.enabled ? "" : "selected" %>>No</option>
                </select>
              </td>
              <td><button type="button" class="btn btn-ghost" onclick="removeRow(this)">Remove</button></td>
            </tr>
          <% }} else { %>
            <tr>
              <td><input type="text" class="trg-type" value="matter.created" placeholder="matter.created" /></td>
              <td><input type="text" class="trg-key" value="" placeholder="optional key" /></td>
              <td><input type="text" class="trg-op" value="equals" placeholder="equals" /></td>
              <td><input type="text" class="trg-value" value="" placeholder="optional value" /></td>
              <td><select class="trg-enabled"><option value="true" selected>Yes</option><option value="false">No</option></select></td>
              <td><button type="button" class="btn btn-ghost" onclick="removeRow(this)">Remove</button></td>
            </tr>
          <% } %>
          </tbody>
        </table>
      </div>
      <button type="button" class="btn btn-ghost" style="margin-top:8px;" onclick="addTriggerRow()">Add Trigger</button>

      <h3 style="margin:14px 0 8px 0;">Steps</h3>
      <div class="table-wrap">
        <table class="table" id="stepTable">
          <thead>
            <tr>
              <th>Order</th>
              <th>Action</th>
              <th>Label</th>
              <th>Enabled</th>
              <th>On Error</th>
              <th>Condition (source/key/op/value)</th>
              <th>Settings (key=value per line)</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody id="stepRows">
          <% if (selected != null && selected.steps != null && !selected.steps.isEmpty()) {
               for (business_process_manager.ProcessStep s : selected.steps) {
                 if (s == null) continue;
                 business_process_manager.ProcessCondition c = (s.conditions == null || s.conditions.isEmpty()) ? null : s.conditions.get(0);
          %>
            <tr>
              <td><input type="number" class="st-order" value="<%= s.order %>" min="0" step="10" /></td>
              <td>
                <select class="st-action">
                  <option value="set_case_field" <%= "set_case_field".equalsIgnoreCase(s.action) ? "selected" : "" %>>Set Case Field</option>
                  <option value="set_case_list_item" <%= "set_case_list_item".equalsIgnoreCase(s.action) ? "selected" : "" %>>Set Case List Item</option>
                  <option value="set_document_field" <%= "set_document_field".equalsIgnoreCase(s.action) ? "selected" : "" %>>Set Document Field</option>
                  <option value="set_tenant_field" <%= "set_tenant_field".equalsIgnoreCase(s.action) ? "selected" : "" %>>Set Tenant Field</option>
                  <option value="set_task_field" <%= "set_task_field".equalsIgnoreCase(s.action) ? "selected" : "" %>>Set Task Field</option>
                  <option value="set_custom_object_record_field" <%= "set_custom_object_record_field".equalsIgnoreCase(s.action) ? "selected" : "" %>>Set Custom Object Field</option>
                  <option value="update_custom_object_record" <%= "update_custom_object_record".equalsIgnoreCase(s.action) ? "selected" : "" %>>Update Custom Object Record</option>
                  <option value="log_note" <%= "log_note".equalsIgnoreCase(s.action) ? "selected" : "" %>>Log Note</option>
                  <option value="set_variable" <%= "set_variable".equalsIgnoreCase(s.action) ? "selected" : "" %>>Set Variable</option>
                  <option value="trigger_event" <%= "trigger_event".equalsIgnoreCase(s.action) ? "selected" : "" %>>Trigger Event</option>
                  <option value="send_webhook" <%= "send_webhook".equalsIgnoreCase(s.action) ? "selected" : "" %>>Send Webhook</option>
                  <option value="update_thread" <%= "update_thread".equalsIgnoreCase(s.action) ? "selected" : "" %>>Update Thread</option>
                  <option value="add_thread_note" <%= "add_thread_note".equalsIgnoreCase(s.action) ? "selected" : "" %>>Add Thread Note</option>
                  <option value="update_task" <%= "update_task".equalsIgnoreCase(s.action) ? "selected" : "" %>>Update Task</option>
                  <option value="add_task_note" <%= "add_task_note".equalsIgnoreCase(s.action) ? "selected" : "" %>>Add Task Note</option>
                  <option value="update_fact" <%= "update_fact".equalsIgnoreCase(s.action) ? "selected" : "" %>>Update Fact</option>
                  <option value="refresh_facts_report" <%= "refresh_facts_report".equalsIgnoreCase(s.action) ? "selected" : "" %>>Refresh Facts Report</option>
                  <option value="document_assembly" <%= "document_assembly".equalsIgnoreCase(s.action) ? "selected" : "" %>>Document Assembly</option>
                  <option value="notice_communication" <%= "notice_communication".equalsIgnoreCase(s.action) ? "selected" : "" %>>Notice Communication</option>
                  <option value="send_for_signature" <%= "send_for_signature".equalsIgnoreCase(s.action) ? "selected" : "" %>>Send For Signature</option>
                  <option value="conflict_check" <%= "conflict_check".equalsIgnoreCase(s.action) ? "selected" : "" %>>Conflict Check</option>
                  <option value="deadline_rule_calculation" <%= "deadline_rule_calculation".equalsIgnoreCase(s.action) ? "selected" : "" %>>Deadline Rule Calculation</option>
                  <option value="court_filing_service" <%= "court_filing_service".equalsIgnoreCase(s.action) ? "selected" : "" %>>Court Filing / Service</option>
                  <option value="payment_fee_verification" <%= "payment_fee_verification".equalsIgnoreCase(s.action) ? "selected" : "" %>>Payment/Fee Verification</option>
                  <option value="wait_for_external_event" <%= "wait_for_external_event".equalsIgnoreCase(s.action) ? "selected" : "" %>>Wait For External Event</option>
                  <option value="request_information" <%= "request_information".equalsIgnoreCase(s.action) ? "selected" : "" %>>Request Information</option>
                  <option value="escalation" <%= "escalation".equalsIgnoreCase(s.action) ? "selected" : "" %>>Escalation</option>
                  <option value="audit_snapshot" <%= "audit_snapshot".equalsIgnoreCase(s.action) ? "selected" : "" %>>Audit Snapshot</option>
                  <option value="human_review" <%= "human_review".equalsIgnoreCase(s.action) ? "selected" : "" %>>Human Review</option>
                </select>
              </td>
              <td><input type="text" class="st-label" value="<%= esc(safe(s.label)) %>" /></td>
              <td><select class="st-enabled"><option value="true" <%= s.enabled ? "selected" : "" %>>Yes</option><option value="false" <%= s.enabled ? "" : "selected" %>>No</option></select></td>
              <td><select class="st-on-error"><option value="stop" <%= "continue".equalsIgnoreCase(safe(s.settings.get("on_error"))) ? "" : "selected" %>>Stop</option><option value="continue" <%= "continue".equalsIgnoreCase(safe(s.settings.get("on_error"))) ? "selected" : "" %>>Continue</option></select></td>
              <td>
                <input type="text" class="st-cond-source" value="<%= esc(c == null ? "event" : safe(c.source)) %>" placeholder="event" />
                <input type="text" class="st-cond-key" value="<%= esc(c == null ? "" : safe(c.key)) %>" placeholder="matter_status_uuid" style="margin-top:4px;" />
                <input type="text" class="st-cond-op" value="<%= esc(c == null ? "equals" : safe(c.op)) %>" placeholder="equals" style="margin-top:4px;" />
                <input type="text" class="st-cond-value" value="<%= esc(c == null ? "" : safe(c.value)) %>" placeholder="open" style="margin-top:4px;" />
              </td>
              <td><textarea class="st-settings" rows="5" placeholder="field_key=priority&#10;field_value=High"><%= esc(settingsLines(s.settings)) %></textarea></td>
              <td><button type="button" class="btn btn-ghost" onclick="removeRow(this)">Remove</button></td>
            </tr>
          <% }} else { %>
            <tr>
              <td><input type="number" class="st-order" value="10" min="0" step="10" /></td>
              <td>
                <select class="st-action">
                  <option value="set_case_field" selected>Set Case Field</option>
                  <option value="set_case_list_item">Set Case List Item</option>
                  <option value="set_document_field">Set Document Field</option>
                  <option value="set_tenant_field">Set Tenant Field</option>
                  <option value="set_task_field">Set Task Field</option>
                  <option value="set_custom_object_record_field">Set Custom Object Field</option>
                  <option value="update_custom_object_record">Update Custom Object Record</option>
                  <option value="log_note">Log Note</option>
                  <option value="set_variable">Set Variable</option>
                  <option value="trigger_event">Trigger Event</option>
                  <option value="send_webhook">Send Webhook</option>
                  <option value="update_thread">Update Thread</option>
                  <option value="add_thread_note">Add Thread Note</option>
                  <option value="update_task">Update Task</option>
                  <option value="add_task_note">Add Task Note</option>
                  <option value="update_fact">Update Fact</option>
                  <option value="refresh_facts_report">Refresh Facts Report</option>
                  <option value="document_assembly">Document Assembly</option>
                  <option value="notice_communication">Notice Communication</option>
                  <option value="send_for_signature">Send For Signature</option>
                  <option value="conflict_check">Conflict Check</option>
                  <option value="deadline_rule_calculation">Deadline Rule Calculation</option>
                  <option value="court_filing_service">Court Filing / Service</option>
                  <option value="payment_fee_verification">Payment/Fee Verification</option>
                  <option value="wait_for_external_event">Wait For External Event</option>
                  <option value="request_information">Request Information</option>
                  <option value="escalation">Escalation</option>
                  <option value="audit_snapshot">Audit Snapshot</option>
                  <option value="human_review">Human Review</option>
                </select>
              </td>
              <td><input type="text" class="st-label" value="Set Priority" /></td>
              <td><select class="st-enabled"><option value="true" selected>Yes</option><option value="false">No</option></select></td>
              <td><select class="st-on-error"><option value="stop" selected>Stop</option><option value="continue">Continue</option></select></td>
              <td>
                <input type="text" class="st-cond-source" value="event" placeholder="event" />
                <input type="text" class="st-cond-key" value="" placeholder="optional key" style="margin-top:4px;" />
                <input type="text" class="st-cond-op" value="equals" placeholder="equals" style="margin-top:4px;" />
                <input type="text" class="st-cond-value" value="" placeholder="optional value" style="margin-top:4px;" />
              </td>
              <td><textarea class="st-settings" rows="5" placeholder="matter_uuid={{event.matter_uuid}}&#10;field_key=priority&#10;field_value=High">matter_uuid={{event.matter_uuid}}&#10;field_key=priority&#10;field_value=High</textarea></td>
              <td><button type="button" class="btn btn-ghost" onclick="removeRow(this)">Remove</button></td>
            </tr>
          <% } %>
          </tbody>
        </table>
      </div>
      <button type="button" class="btn btn-ghost" style="margin-top:8px;" onclick="addStepRow()">Add Step</button>

      <div class="card" style="margin-top:10px; padding:10px;">
        <div class="meta" style="margin-bottom:6px;">
          Built-in action quick reference (detailed settings are available via API operation <code>bpm.actions.catalog</code>).
        </div>
        <div class="table-wrap">
          <table class="table">
            <thead>
              <tr><th>Action</th><th>Required Settings</th><th>Notes</th></tr>
            </thead>
            <tbody>
              <tr><td><code>set_case_field</code></td><td><code>matter_uuid</code>, <code>field_key</code>, <code>field_value</code></td><td>Undo/redo supported.</td></tr>
              <tr><td><code>set_case_list_item</code></td><td><code>matter_uuid</code>, <code>list_key</code>, <code>list_value</code></td><td>Undo/redo supported.</td></tr>
              <tr><td><code>set_document_field</code></td><td><code>matter_uuid</code>, <code>doc_uuid</code>, <code>field_key</code>, <code>field_value</code></td><td>Undo/redo supported.</td></tr>
              <tr><td><code>set_tenant_field</code></td><td><code>field_key</code>, <code>field_value</code></td><td>Undo/redo supported.</td></tr>
              <tr><td><code>set_task_field</code></td><td><code>task_uuid</code>, <code>field_key</code>, <code>field_value</code></td><td>Undo/redo supported.</td></tr>
              <tr><td><code>set_custom_object_record_field</code></td><td><code>object_uuid</code>, <code>record_uuid</code>, <code>field_key</code>, <code>field_value</code></td><td>Undo/redo supported.</td></tr>
              <tr><td><code>update_thread</code></td><td><code>thread_uuid</code></td><td>Patch thread status/priority/assignees and SLA fields.</td></tr>
              <tr><td><code>add_thread_note</code></td><td><code>thread_uuid</code>, <code>body</code></td><td>Adds internal note (not external-facing).</td></tr>
              <tr><td><code>document_assembly</code></td><td><code>matter_uuid</code>, <code>template_uuid</code></td><td>Builds + stores assembled output from template values.</td></tr>
              <tr><td><code>notice_communication</code></td><td><code>to</code>, <code>subject</code></td><td>Queues/sends outbound legal notices using tenant email provider.</td></tr>
              <tr><td><code>send_for_signature</code></td><td><code>to</code>, <code>subject</code></td><td>Signature notice with optional wait-for-confirmation review loop.</td></tr>
              <tr><td><code>conflict_check</code></td><td><code>matter_uuid</code></td><td>Writes clear/potential status variables and can require review.</td></tr>
              <tr><td><code>deadline_rule_calculation</code></td><td><code>days_offset</code></td><td>Computes deadline date (business-day aware optional).</td></tr>
              <tr><td><code>court_filing_service</code></td><td><code>matter_uuid</code>, <code>title</code></td><td>Creates filing/service task and optional approval gate.</td></tr>
              <tr><td><code>payment_fee_verification</code></td><td><code>required_amount</code></td><td>Evaluates paid vs required fee and can branch to review.</td></tr>
              <tr><td><code>wait_for_external_event</code></td><td><code>title</code></td><td>Pauses run until external status is entered in review input.</td></tr>
              <tr><td><code>request_information</code></td><td><code>title</code></td><td>Structured info request queue with required response keys.</td></tr>
              <tr><td><code>escalation</code></td><td><code>matter_uuid</code>, <code>title</code></td><td>Creates urgent task and optional escalation notice.</td></tr>
              <tr><td><code>audit_snapshot</code></td><td>(none)</td><td>Logs hashed context snapshot for defensibility.</td></tr>
              <tr><td><code>human_review</code></td><td><code>title</code></td><td>Pauses run; supports assignee user/role or permission queue.</td></tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>

    <div id="expertPanel" style="display:none; margin-top:12px;">
      <label>
        <div class="meta">Expert JSON</div>
        <textarea id="expertJson" rows="22" style="font-family:ui-monospace, SFMono-Regular, Menlo, monospace;"><%= esc(processJson) %></textarea>
      </label>
    </div>

    <input type="hidden" name="process_json" id="processJsonField" value="" />

    <div class="actions" style="display:flex; gap:10px; margin-top:10px;">
      <button type="submit" class="btn">Save Process</button>
      <button type="button" class="btn btn-ghost" onclick="setEditorMode('expert');">Switch to Expert</button>
    </div>
  </form>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin:0 0 8px 0;">Manual Trigger</h2>
  <form method="post" action="<%= ctx %>/business_processes.jsp" class="form">
    <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
    <input type="hidden" name="action" value="trigger_process" />

    <div style="display:grid; grid-template-columns:repeat(auto-fit, minmax(220px, 1fr)); gap:10px;">
      <label>
        <div class="meta">Process</div>
        <select name="process_uuid" required>
          <% for (business_process_manager.ProcessDefinition p : processes) {
               if (p == null) continue;
          %>
            <option value="<%= esc(safe(p.processUuid)) %>" <%= selected != null && safe(p.processUuid).equals(safe(selected.processUuid)) ? "selected" : "" %>><%= esc(safe(p.name)) %></option>
          <% } %>
        </select>
      </label>
      <label>
        <div class="meta">Event Type</div>
        <input type="text" name="event_type" value="manual" placeholder="manual" />
      </label>
    </div>

    <label style="display:block; margin-top:8px;">
      <div class="meta">Payload JSON (optional)</div>
      <textarea name="payload_json" rows="4" style="font-family:ui-monospace, SFMono-Regular, Menlo, monospace;">{}</textarea>
    </label>

    <div class="actions" style="margin-top:8px;">
      <button type="submit" class="btn btn-ghost">Trigger Process</button>
    </div>
  </form>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin:0 0 8px 0;">Run Health Inspector (Stuck/Hung Detection)</h2>
  <div class="meta" style="margin-bottom:8px;">
    Inspect stale running runs and stale waiting-human-review runs. Use repair actions to fail, retry, or reject stale reviews.
  </div>

  <form method="post" action="<%= ctx %>/business_processes.jsp" style="display:grid; grid-template-columns:repeat(auto-fit, minmax(220px, 1fr)); gap:10px; align-items:end; margin-bottom:10px;">
    <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
    <input type="hidden" name="action" value="save_aging_alarm_settings" />
    <label>
      <div class="meta">Email Aging Alarms To Tenant Admins</div>
      <select name="aging_alarm_enabled">
        <option value="true" <%= agingAlarmEnabled ? "selected" : "" %>>Enabled</option>
        <option value="false" <%= agingAlarmEnabled ? "" : "selected" %>>Disabled</option>
      </select>
    </label>
    <label>
      <div class="meta">Alarm Scan Interval (minutes)</div>
      <input type="number" name="aging_alarm_interval_minutes" value="<%= agingAlarmIntervalMinutes %>" min="1" max="1440" step="1" />
    </label>
    <label>
      <div class="meta">Running Stale Alarm (minutes)</div>
      <input type="number" name="aging_alarm_running_stale_minutes" value="<%= runningThresholdMinutes %>" min="1" max="43200" step="1" />
    </label>
    <label>
      <div class="meta">Review Stale Alarm (minutes)</div>
      <input type="number" name="aging_alarm_review_stale_minutes" value="<%= reviewThresholdMinutes %>" min="1" max="43200" step="1" />
    </label>
    <label>
      <div class="meta">Max Issues Per Scan</div>
      <input type="number" name="aging_alarm_max_issues_per_scan" value="<%= agingAlarmMaxIssues %>" min="1" max="2000" step="1" />
    </label>
    <div>
      <button type="submit" class="btn btn-ghost">Save Aging Alarm Settings</button>
    </div>
  </form>
  <div class="meta" style="margin-bottom:10px;">
    When enabled, background checks queue email notifications for newly stale/incomplete runs to users with <code>tenant_admin=true</code>. Requires tenant email provider configuration.
  </div>

  <form method="get" action="<%= ctx %>/business_processes.jsp" style="display:grid; grid-template-columns:repeat(auto-fit, minmax(220px, 1fr)); gap:10px; align-items:end;">
    <label>
      <div class="meta">Running Stale Threshold (minutes)</div>
      <input type="number" name="running_threshold_mins" value="<%= runningThresholdMinutes %>" min="1" step="1" />
    </label>
    <label>
      <div class="meta">Review Stale Threshold (minutes)</div>
      <input type="number" name="review_threshold_mins" value="<%= reviewThresholdMinutes %>" min="1" step="1" />
    </label>
    <div>
      <button type="submit" class="btn btn-ghost">Re-scan Run Health</button>
    </div>
  </form>

  <% if (runHealth.isEmpty()) { %>
    <div class="meta" style="margin-top:10px;">No stuck or hung runs detected for the current thresholds.</div>
  <% } else { %>
    <div class="table-wrap" style="margin-top:10px;">
      <table class="table">
        <thead>
          <tr>
            <th>Severity</th>
            <th>Issue</th>
            <th>Process</th>
            <th>Run</th>
            <th>Status</th>
            <th>Age (min)</th>
            <th>Recommended Fix</th>
            <th>Actions</th>
          </tr>
        </thead>
        <tbody>
        <% for (business_process_manager.RunHealthIssue issue : runHealth) {
             if (issue == null) continue;
        %>
          <tr>
            <td><strong><%= esc(safe(issue.severity)) %></strong></td>
            <td><%= esc(safe(issue.summary)) %></td>
            <td>
              <div><%= esc(safe(issue.processName)) %></div>
              <div class="meta"><code><%= esc(safe(issue.processUuid)) %></code></div>
            </td>
            <td><code><%= esc(safe(issue.runUuid)) %></code></td>
            <td><%= esc(safe(issue.status)) %></td>
            <td><%= issue.ageMinutes < 0 ? "n/a" : String.valueOf(issue.ageMinutes) %></td>
            <td><%= esc(safe(issue.recommendation)) %></td>
            <td style="display:flex; gap:8px; flex-wrap:wrap;">
              <% if (issue.canMarkFailed) { %>
                <form method="post" action="<%= ctx %>/business_processes.jsp" style="display:inline;">
                  <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
                  <input type="hidden" name="action" value="mark_run_failed" />
                  <input type="hidden" name="run_uuid" value="<%= esc(safe(issue.runUuid)) %>" />
                  <input type="hidden" name="operator_note" value="Operator marked stale/hung run failed from inspector." />
                  <input type="hidden" name="running_threshold_mins" value="<%= runningThresholdMinutes %>" />
                  <input type="hidden" name="review_threshold_mins" value="<%= reviewThresholdMinutes %>" />
                  <button type="submit" class="btn btn-ghost">Mark Failed</button>
                </form>
              <% } %>

              <% if (issue.canRetry) { %>
                <form method="post" action="<%= ctx %>/business_processes.jsp" style="display:inline;">
                  <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
                  <input type="hidden" name="action" value="retry_run" />
                  <input type="hidden" name="run_uuid" value="<%= esc(safe(issue.runUuid)) %>" />
                  <input type="hidden" name="operator_note" value="Operator retry from run-health inspector." />
                  <input type="hidden" name="running_threshold_mins" value="<%= runningThresholdMinutes %>" />
                  <input type="hidden" name="review_threshold_mins" value="<%= reviewThresholdMinutes %>" />
                  <button type="submit" class="btn btn-ghost">Retry</button>
                </form>
              <% } %>

              <% if (issue.canResolveReview && !safe(issue.humanReviewUuid).isBlank()) { %>
                <form method="post" action="<%= ctx %>/business_processes.jsp" style="display:inline;">
                  <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
                  <input type="hidden" name="action" value="reject_review" />
                  <input type="hidden" name="review_uuid" value="<%= esc(safe(issue.humanReviewUuid)) %>" />
                  <input type="hidden" name="operator_note" value="Review rejected by run-health inspector due to staleness." />
                  <input type="hidden" name="running_threshold_mins" value="<%= runningThresholdMinutes %>" />
                  <input type="hidden" name="review_threshold_mins" value="<%= reviewThresholdMinutes %>" />
                  <button type="submit" class="btn btn-ghost">Reject Review</button>
                </form>
              <% } %>
            </td>
          </tr>
        <% } %>
        </tbody>
      </table>
    </div>
  <% } %>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin:0 0 8px 0;">Recent Runs (Undo/Redo)</h2>
  <div class="table-wrap">
    <table class="table">
      <thead>
        <tr>
          <th>Started</th>
          <th>Process</th>
          <th>Status</th>
          <th>Undo State</th>
          <th>Message</th>
          <th>Actions</th>
        </tr>
      </thead>
      <tbody>
      <% for (business_process_manager.RunResult r : runs) {
           if (r == null) continue;
      %>
        <tr>
          <td><%= esc(safe(r.startedAt)) %></td>
          <td><%= esc(safe(r.processName)) %></td>
          <td><%= esc(safe(r.status)) %></td>
          <td><%= esc(safe(r.undoState)) %></td>
          <td><%= esc(safe(r.message)) %></td>
          <td style="display:flex; gap:8px; flex-wrap:wrap;">
            <form method="post" action="<%= ctx %>/business_processes.jsp" style="display:inline;">
              <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
              <input type="hidden" name="action" value="undo_run" />
              <input type="hidden" name="run_uuid" value="<%= esc(safe(r.runUuid)) %>" />
              <button type="submit" class="btn btn-ghost" <%= "undone".equalsIgnoreCase(safe(r.undoState)) ? "disabled" : "" %>>Undo</button>
            </form>
            <form method="post" action="<%= ctx %>/business_processes.jsp" style="display:inline;">
              <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
              <input type="hidden" name="action" value="redo_run" />
              <input type="hidden" name="run_uuid" value="<%= esc(safe(r.runUuid)) %>" />
              <button type="submit" class="btn btn-ghost" <%= !"undone".equalsIgnoreCase(safe(r.undoState)) && !"undo_partial".equalsIgnoreCase(safe(r.undoState)) ? "disabled" : "" %>>Redo</button>
            </form>
          </td>
        </tr>
      <% } %>
      </tbody>
    </table>
  </div>
</section>

<script>
  function setEditorMode(mode) {
    var novice = document.getElementById('novicePanel');
    var expert = document.getElementById('expertPanel');
    var modeField = document.getElementById('editorMode');
    var noviceBtn = document.getElementById('modeNoviceBtn');
    var expertBtn = document.getElementById('modeExpertBtn');
    if (!novice || !expert || !modeField) return;
    modeField.value = mode === 'expert' ? 'expert' : 'novice';
    novice.style.display = modeField.value === 'novice' ? '' : 'none';
    expert.style.display = modeField.value === 'expert' ? '' : 'none';
    if (noviceBtn) noviceBtn.classList.toggle('btn', modeField.value === 'novice');
    if (expertBtn) expertBtn.classList.toggle('btn', modeField.value === 'expert');
  }

  function removeRow(btn) {
    if (!btn) return;
    var tr = btn.closest('tr');
    if (!tr) return;
    tr.remove();
  }

  function addTriggerRow() {
    var tbody = document.getElementById('triggerRows');
    if (!tbody) return;
    var tr = document.createElement('tr');
    tr.innerHTML =
      '<td><input type="text" class="trg-type" value="manual" placeholder="matter.created" /></td>' +
      '<td><input type="text" class="trg-key" value="" placeholder="optional key" /></td>' +
      '<td><input type="text" class="trg-op" value="equals" placeholder="equals" /></td>' +
      '<td><input type="text" class="trg-value" value="" placeholder="optional value" /></td>' +
      '<td><select class="trg-enabled"><option value="true" selected>Yes</option><option value="false">No</option></select></td>' +
      '<td><button type="button" class="btn btn-ghost" onclick="removeRow(this)">Remove</button></td>';
    tbody.appendChild(tr);
  }

  function addStepRow() {
    var tbody = document.getElementById('stepRows');
    if (!tbody) return;
    var tr = document.createElement('tr');
    tr.innerHTML =
      '<td><input type="number" class="st-order" value="10" min="0" step="10" /></td>' +
      '<td><select class="st-action">' +
        '<option value="set_case_field" selected>Set Case Field</option>' +
        '<option value="set_case_list_item">Set Case List Item</option>' +
        '<option value="set_document_field">Set Document Field</option>' +
        '<option value="set_tenant_field">Set Tenant Field</option>' +
        '<option value="set_task_field">Set Task Field</option>' +
        '<option value="set_custom_object_record_field">Set Custom Object Field</option>' +
        '<option value="update_custom_object_record">Update Custom Object Record</option>' +
        '<option value="log_note">Log Note</option>' +
        '<option value="set_variable">Set Variable</option>' +
        '<option value="trigger_event">Trigger Event</option>' +
        '<option value="send_webhook">Send Webhook</option>' +
        '<option value="update_thread">Update Thread</option>' +
        '<option value="add_thread_note">Add Thread Note</option>' +
        '<option value="update_task">Update Task</option>' +
        '<option value="add_task_note">Add Task Note</option>' +
        '<option value="update_fact">Update Fact</option>' +
        '<option value="refresh_facts_report">Refresh Facts Report</option>' +
        '<option value="document_assembly">Document Assembly</option>' +
        '<option value="notice_communication">Notice Communication</option>' +
        '<option value="send_for_signature">Send For Signature</option>' +
        '<option value="conflict_check">Conflict Check</option>' +
        '<option value="deadline_rule_calculation">Deadline Rule Calculation</option>' +
        '<option value="court_filing_service">Court Filing / Service</option>' +
        '<option value="payment_fee_verification">Payment/Fee Verification</option>' +
        '<option value="wait_for_external_event">Wait For External Event</option>' +
        '<option value="request_information">Request Information</option>' +
        '<option value="escalation">Escalation</option>' +
        '<option value="audit_snapshot">Audit Snapshot</option>' +
        '<option value="human_review">Human Review</option>' +
      '</select></td>' +
      '<td><input type="text" class="st-label" value="" /></td>' +
      '<td><select class="st-enabled"><option value="true" selected>Yes</option><option value="false">No</option></select></td>' +
      '<td><select class="st-on-error"><option value="stop" selected>Stop</option><option value="continue">Continue</option></select></td>' +
      '<td>' +
        '<input type="text" class="st-cond-source" value="event" placeholder="event" />' +
        '<input type="text" class="st-cond-key" value="" placeholder="optional key" style="margin-top:4px;" />' +
        '<input type="text" class="st-cond-op" value="equals" placeholder="equals" style="margin-top:4px;" />' +
        '<input type="text" class="st-cond-value" value="" placeholder="optional value" style="margin-top:4px;" />' +
      '</td>' +
      '<td><textarea class="st-settings" rows="5" placeholder="key=value per line"></textarea></td>' +
      '<td><button type="button" class="btn btn-ghost" onclick="removeRow(this)">Remove</button></td>';
    tbody.appendChild(tr);
  }

  function parseSettings(text) {
    var settings = {};
    var lines = (text || '').split(/\r?\n/);
    for (var i = 0; i < lines.length; i++) {
      var line = lines[i] || '';
      if (!line.trim()) continue;
      var eq = line.indexOf('=');
      if (eq <= 0) continue;
      var key = line.slice(0, eq).trim();
      var val = line.slice(eq + 1);
      if (!key) continue;
      settings[key] = val;
    }
    return settings;
  }

  function collectNoviceProcess() {
    var process = {
      process_uuid: (document.getElementById('processUuid') || {}).value || '',
      name: (document.getElementById('processName') || {}).value || '',
      description: (document.getElementById('processDescription') || {}).value || '',
      enabled: ((document.getElementById('processEnabled') || {}).value || 'true') === 'true',
      triggers: [],
      steps: []
    };

    var trgRows = document.querySelectorAll('#triggerRows tr');
    for (var i = 0; i < trgRows.length; i++) {
      var row = trgRows[i];
      var type = (row.querySelector('.trg-type') || {}).value || '';
      if (!type.trim()) continue;
      process.triggers.push({
        type: type.trim(),
        key: ((row.querySelector('.trg-key') || {}).value || '').trim(),
        op: ((row.querySelector('.trg-op') || {}).value || 'equals').trim(),
        value: (row.querySelector('.trg-value') || {}).value || '',
        enabled: ((row.querySelector('.trg-enabled') || {}).value || 'true') === 'true'
      });
    }

    var stepRows = document.querySelectorAll('#stepRows tr');
    for (var j = 0; j < stepRows.length; j++) {
      var r = stepRows[j];
      var action = ((r.querySelector('.st-action') || {}).value || '').trim();
      if (!action) continue;
      var condKey = ((r.querySelector('.st-cond-key') || {}).value || '').trim();
      var onError = ((r.querySelector('.st-on-error') || {}).value || 'stop').trim();
      var settings = parseSettings((r.querySelector('.st-settings') || {}).value || '');
      if (onError === 'continue') settings.on_error = 'continue';
      process.steps.push({
        step_id: 'step_' + (j + 1),
        order: parseInt(((r.querySelector('.st-order') || {}).value || '10'), 10) || ((j + 1) * 10),
        action: action,
        label: ((r.querySelector('.st-label') || {}).value || '').trim(),
        enabled: ((r.querySelector('.st-enabled') || {}).value || 'true') === 'true',
        settings: settings,
        conditions: condKey ? [{
          source: ((r.querySelector('.st-cond-source') || {}).value || 'event').trim(),
          key: condKey,
          op: ((r.querySelector('.st-cond-op') || {}).value || 'equals').trim(),
          value: ((r.querySelector('.st-cond-value') || {}).value || '')
        }] : []
      });
    }

    return process;
  }

  function prepareProcessPayload() {
    var mode = (document.getElementById('editorMode') || {}).value || 'novice';
    var hidden = document.getElementById('processJsonField');
    if (!hidden) return false;

    try {
      if (mode === 'expert') {
        hidden.value = (document.getElementById('expertJson') || {}).value || '';
      } else {
        var novice = collectNoviceProcess();
        var text = JSON.stringify(novice, null, 2);
        hidden.value = text;
        var expert = document.getElementById('expertJson');
        if (expert) expert.value = text;
      }
      return true;
    } catch (e) {
      alert('Unable to build process payload: ' + (e && e.message ? e.message : String(e)));
      return false;
    }
  }

  setEditorMode('novice');
</script>

<jsp:include page="footer.jsp" />
