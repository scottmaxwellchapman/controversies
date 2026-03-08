<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" isELIgnored="true" %>

<%@ page import="java.net.URLEncoder" %>
<%@ page import="java.nio.charset.StandardCharsets" %>
<%@ page import="java.util.ArrayList" %>
<%@ page import="java.util.HashSet" %>
<%@ page import="java.util.LinkedHashMap" %>
<%@ page import="java.util.List" %>
<%@ page import="java.util.Locale" %>
<%@ page import="java.util.Map" %>

<%@ page import="net.familylawandprobate.controversies.case_attributes" %>
<%@ page import="net.familylawandprobate.controversies.case_fields" %>
<%@ page import="net.familylawandprobate.controversies.matters" %>

<%@ include file="security.jspf" %>
<% if (!require_login()) return; %>
<% request.setAttribute("activeNav", "/cases.jsp"); %>
<jsp:include page="header.jsp" />

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

  private static String nonBlank(String s, String fallback) {
    String v = safe(s).trim();
    return v.isBlank() ? safe(fallback) : v;
  }

  private static void logWarn(jakarta.servlet.ServletContext app, String message, Throwable ex) {
    if (app == null) return;
    if (ex == null) app.log("[case_focus] " + safe(message));
    else app.log("[case_focus] " + safe(message), ex);
  }

  private static String shortErr(Throwable ex) {
    if (ex == null) return "";
    String m = safe(ex.getMessage()).trim();
    return m.isBlank() ? ex.getClass().getSimpleName() : m;
  }

  private static boolean isTrueLike(String raw) {
    String v = safe(raw).trim().toLowerCase(Locale.ROOT);
    return "true".equals(v) || "1".equals(v) || "yes".equals(v) || "on".equals(v) || "y".equals(v);
  }

  private static boolean isFalseLike(String raw) {
    String v = safe(raw).trim().toLowerCase(Locale.ROOT);
    return "false".equals(v) || "0".equals(v) || "no".equals(v) || "off".equals(v) || "n".equals(v);
  }

  private static boolean isClioManagedMatter(matters.MatterRec m) {
    return m != null && "clio".equalsIgnoreCase(safe(m.source).trim());
  }

  private static String normalizeAttrValueByType(String dataType, String raw) {
    String type = safe(dataType).trim().toLowerCase(Locale.ROOT);
    String v = safe(raw);
    if ("boolean".equals(type)) {
      if (isTrueLike(v)) return "true";
      if (isFalseLike(v)) return "false";
      return "";
    }
    if ("select".equals(type)
        || "number".equals(type)
        || "date".equals(type)
        || "datetime".equals(type)
        || "time".equals(type)
        || "email".equals(type)
        || "phone".equals(type)
        || "url".equals(type)) {
      return v.trim();
    }
    return v;
  }

  private static String caseAttrValue(case_attributes.AttributeRec def, matters.MatterRec m, Map<String,String> kv) {
    if (def == null) return "";
    String key = safe(def.key).trim();
    String v = kv == null ? "" : safe(kv.get(key));
    if (!v.isBlank()) return v;
    if ("cause_docket_number".equals(key)) return m == null ? "" : safe(m.causeDocketNumber);
    if ("county".equals(key)) return m == null ? "" : safe(m.county);
    return "";
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
  case_fields fieldsStore = case_fields.defaultStore();
  case_attributes attrsStore = case_attributes.defaultStore();

  try { matterStore.ensure(tenantUuid); } catch (Exception ex) { logWarn(application, "Unable to ensure matter store: " + shortErr(ex), ex); }
  try { attrsStore.ensure(tenantUuid); } catch (Exception ex) { logWarn(application, "Unable to ensure case attributes: " + shortErr(ex), ex); }

  String csrfToken = csrfForRender(request);
  String message = null;
  String error = null;

  String q = safe(request.getParameter("q")).trim();
  String show = safe(request.getParameter("show")).trim().toLowerCase(Locale.ROOT);
  if (show.isBlank()) show = "active";

  String caseUuid = safe(request.getParameter("case_uuid")).trim();
  if (caseUuid.isBlank()) caseUuid = safe(request.getParameter("matter_uuid")).trim();

  List<case_attributes.AttributeRec> allAttrDefs = new ArrayList<case_attributes.AttributeRec>();
  List<case_attributes.AttributeRec> enabledAttrDefs = new ArrayList<case_attributes.AttributeRec>();
  try {
    allAttrDefs = attrsStore.listAll(tenantUuid);
    enabledAttrDefs = attrsStore.listEnabled(tenantUuid);
  } catch (Exception ex) {
    logWarn(application, "Unable to load case attribute definitions: " + shortErr(ex), ex);
    error = "Unable to load case attribute definitions.";
  }

  LinkedHashMap<String, case_attributes.AttributeRec> defByKey = new LinkedHashMap<String, case_attributes.AttributeRec>();
  HashSet<String> attrKeySet = new HashSet<String>();
  for (int i = 0; i < allAttrDefs.size(); i++) {
    case_attributes.AttributeRec d = allAttrDefs.get(i);
    if (d == null) continue;
    String key = attrsStore.normalizeKey(d.key);
    if (key.isBlank()) continue;
    defByKey.put(key, d);
    attrKeySet.add(key);
  }

  if ("POST".equalsIgnoreCase(request.getMethod())) {
    String action = safe(request.getParameter("action")).trim();
    caseUuid = safe(request.getParameter("case_uuid")).trim();

    if ("save_case".equalsIgnoreCase(action)) {
      String label = safe(request.getParameter("label")).trim();
      try {
        matters.MatterRec current = matterStore.getByUuid(tenantUuid, caseUuid);
        if (current == null) throw new IllegalStateException("Case not found.");
        if (isClioManagedMatter(current)) throw new IllegalStateException("This case is synced from Clio and is read-only here.");

        LinkedHashMap<String, String> attrValues = new LinkedHashMap<String, String>();
        String[] attrKeys = request.getParameterValues("attr_def_key");
        String[] attrVals = request.getParameterValues("attr_def_value");
        int an = Math.max(attrKeys == null ? 0 : attrKeys.length, attrVals == null ? 0 : attrVals.length);
        for (int i = 0; i < an; i++) {
          String k = (attrKeys != null && i < attrKeys.length) ? attrsStore.normalizeKey(attrKeys[i]) : "";
          if (k.isBlank() || !defByKey.containsKey(k)) continue;
          String v = (attrVals != null && i < attrVals.length) ? safe(attrVals[i]) : "";
          attrValues.put(k, v);
        }

        for (int i = 0; i < enabledAttrDefs.size(); i++) {
          case_attributes.AttributeRec def = enabledAttrDefs.get(i);
          if (def == null) continue;
          String key = attrsStore.normalizeKey(def.key);
          if (key.isBlank()) continue;
          String val = normalizeAttrValueByType(def.dataType, attrValues.get(key));

          if ("select".equals(def.dataType)) {
            List<String> opts = attrsStore.optionList(def.options);
            if (!opts.isEmpty()) {
              boolean match = false;
              for (int oi = 0; oi < opts.size(); oi++) {
                if (safe(opts.get(oi)).equals(val)) { match = true; break; }
              }
              if (!match) val = "";
            }
          }

          if (def.required && safe(val).trim().isBlank()) {
            throw new IllegalArgumentException("Required field missing: " + safe(def.label));
          }
          attrValues.put(key, val);
        }

        LinkedHashMap<String, String> currentKv = new LinkedHashMap<String, String>();
        try { currentKv.putAll(fieldsStore.read(tenantUuid, caseUuid)); } catch (Exception ignored) {}
        for (String k : attrKeySet) {
          if (k == null || k.isBlank()) continue;
          if (!attrValues.containsKey(k) && currentKv.containsKey(k)) {
            attrValues.put(k, safe(currentKv.get(k)));
          }
        }

        LinkedHashMap<String,String> extraValues = new LinkedHashMap<String,String>();
        String[] keys = request.getParameterValues("field_key");
        String[] vals = request.getParameterValues("field_value");
        int n = Math.max(keys == null ? 0 : keys.length, vals == null ? 0 : vals.length);
        for (int i = 0; i < n; i++) {
          String k = (keys != null && i < keys.length) ? safe(keys[i]) : "";
          String v = (vals != null && i < vals.length) ? safe(vals[i]) : "";
          String nk = fieldsStore.normalizeKey(k);
          if (nk.isBlank()) continue;
          if (attrKeySet.contains(nk)) continue;
          extraValues.put(nk, v);
        }

        LinkedHashMap<String,String> merged = new LinkedHashMap<String,String>();
        merged.putAll(extraValues);
        for (String k : attrKeySet) {
          if (k == null || k.isBlank()) continue;
          if (attrValues.containsKey(k)) merged.put(k, safe(attrValues.get(k)));
        }

        String cause = safe(attrValues.get("cause_docket_number")).trim();
        String county = safe(attrValues.get("county")).trim();

        matterStore.update(
          tenantUuid,
          caseUuid,
          label,
          nonBlank(current.jurisdictionUuid, ""),
          nonBlank(current.matterCategoryUuid, ""),
          nonBlank(current.matterSubcategoryUuid, ""),
          nonBlank(current.matterStatusUuid, ""),
          nonBlank(current.matterSubstatusUuid, ""),
          cause,
          county
        );

        fieldsStore.write(tenantUuid, caseUuid, merged);
        response.sendRedirect(ctx + "/case_focus.jsp?case_uuid=" + enc(caseUuid) + "&saved=1&q=" + enc(q) + "&show=" + enc(show));
        return;
      } catch (Exception ex) {
        logWarn(application, "Unable to save case: " + shortErr(ex), ex);
        error = "Unable to save case: " + safe(ex.getMessage());
      }
    }

    if ("archive_case".equalsIgnoreCase(action)) {
      try {
        matters.MatterRec current = matterStore.getByUuid(tenantUuid, caseUuid);
        if (current == null) throw new IllegalStateException("Case not found.");
        if (isClioManagedMatter(current)) throw new IllegalStateException("This case is synced from Clio and is read-only here.");
        matterStore.trash(tenantUuid, caseUuid);
        response.sendRedirect(ctx + "/case_focus.jsp?case_uuid=" + enc(caseUuid) + "&archived=1&q=" + enc(q) + "&show=" + enc(show));
        return;
      } catch (Exception ex) {
        logWarn(application, "Unable to archive case: " + shortErr(ex), ex);
        error = "Unable to archive case: " + safe(ex.getMessage());
      }
    }

    if ("restore_case".equalsIgnoreCase(action)) {
      try {
        matters.MatterRec current = matterStore.getByUuid(tenantUuid, caseUuid);
        if (current == null) throw new IllegalStateException("Case not found.");
        if (isClioManagedMatter(current)) throw new IllegalStateException("This case is synced from Clio and is read-only here.");
        matterStore.restore(tenantUuid, caseUuid);
        response.sendRedirect(ctx + "/case_focus.jsp?case_uuid=" + enc(caseUuid) + "&restored=1&q=" + enc(q) + "&show=" + enc(show));
        return;
      } catch (Exception ex) {
        logWarn(application, "Unable to restore case: " + shortErr(ex), ex);
        error = "Unable to restore case: " + safe(ex.getMessage());
      }
    }
  }

  if ("1".equals(request.getParameter("saved"))) message = "Case saved.";
  if ("1".equals(request.getParameter("archived"))) message = "Case archived.";
  if ("1".equals(request.getParameter("restored"))) message = "Case restored.";

  List<matters.MatterRec> allCases = new ArrayList<matters.MatterRec>();
  try {
    allCases = matterStore.listAll(tenantUuid);
  } catch (Exception ex) {
    logWarn(application, "Unable to list cases: " + shortErr(ex), ex);
    if (error == null) error = "Unable to load cases.";
  }

  List<matters.MatterRec> activeCases = new ArrayList<matters.MatterRec>();
  for (int i = 0; i < allCases.size(); i++) {
    matters.MatterRec c = allCases.get(i);
    if (c == null) continue;
    if (!c.trashed) activeCases.add(c);
  }

  if (caseUuid.isBlank() && !activeCases.isEmpty()) {
    caseUuid = safe(activeCases.get(0).uuid);
  } else if (caseUuid.isBlank() && !allCases.isEmpty()) {
    caseUuid = safe(allCases.get(0).uuid);
  }

  matters.MatterRec selectedCase = null;
  try { selectedCase = matterStore.getByUuid(tenantUuid, caseUuid); } catch (Exception ignored) {}

  LinkedHashMap<String,String> caseKv = new LinkedHashMap<String,String>();
  if (selectedCase != null) {
    try { caseKv.putAll(fieldsStore.read(tenantUuid, selectedCase.uuid)); } catch (Exception ignored) {}
  }

  LinkedHashMap<String,String> extraKv = new LinkedHashMap<String,String>();
  for (Map.Entry<String,String> e : caseKv.entrySet()) {
    if (e == null) continue;
    String nk = fieldsStore.normalizeKey(e.getKey());
    if (nk.isBlank()) continue;
    if (attrKeySet.contains(nk)) continue;
    extraKv.put(nk, safe(e.getValue()));
  }
  int rows = Math.max(4, extraKv.size() + 1);

  String backToCasesHref = ctx + "/cases.jsp?q=" + enc(q) + "&show=" + enc(show);
  boolean clioManaged = isClioManagedMatter(selectedCase);
%>

<section class="card">
  <div class="section-head">
    <div>
      <h1 style="margin:0;">Case Focus</h1>
      <% if (selectedCase != null) { %>
        <div class="meta" style="margin-top:6px;">
          <strong><%= esc(safe(selectedCase.label)) %></strong>
          • <%= selectedCase.trashed ? "Archived" : "Active" %>
          <% if (clioManaged) { %> • Synced from Clio (read-only source)<% } %>
        </div>
      <% } else { %>
        <div class="meta" style="margin-top:6px;">Select a case to focus.</div>
      <% } %>
    </div>
  </div>

  <% if (message != null && !message.isBlank()) { %>
    <div class="alert alert-ok" style="margin-top:12px;"><%= esc(message) %></div>
  <% } %>
  <% if (error != null && !error.isBlank()) { %>
    <div class="alert alert-error" style="margin-top:12px;"><%= esc(error) %></div>
  <% } %>

  <div class="actions" style="display:flex; gap:10px; margin-top:12px; flex-wrap:wrap;">
    <a class="btn btn-ghost" href="<%= backToCasesHref %>">Back To Cases</a>
  </div>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Select Case</h2>
  <form class="form" method="get" action="<%= ctx %>/case_focus.jsp">
    <input type="hidden" name="q" value="<%= esc(q) %>" />
    <input type="hidden" name="show" value="<%= esc(show) %>" />
    <div class="grid" style="display:grid; grid-template-columns: 3fr auto; gap:12px;">
      <label>
        <span>Case</span>
        <select name="case_uuid" <%= allCases.isEmpty() ? "disabled" : "" %>>
          <% if (allCases.isEmpty()) { %>
            <option value="">No cases…</option>
          <% } else {
               for (int i = 0; i < allCases.size(); i++) {
                 matters.MatterRec c = allCases.get(i);
                 if (c == null) continue;
                 String id = safe(c.uuid);
                 boolean sel = id.equals(safe(caseUuid));
          %>
            <option value="<%= esc(id) %>" <%= sel ? "selected" : "" %>>
              <%= esc(safe(c.label)) %><%= c.trashed ? " (archived)" : "" %>
            </option>
          <%   }
             } %>
        </select>
      </label>
      <label>
        <span>&nbsp;</span>
        <button class="btn" type="submit" <%= allCases.isEmpty() ? "disabled" : "" %>>Load Focus</button>
      </label>
    </div>
  </form>
</section>

<% if (selectedCase != null) { %>
<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Case Actions</h2>
  <div class="grid" style="display:grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap:12px;">
    <article class="card" style="padding:12px; margin:0;">
      <h3 style="margin-top:0;">Case Fields</h3>
      <p class="muted">Edit case replacement values.</p>
      <a class="btn btn-ghost" href="<%= ctx %>/case_fields.jsp?matter_uuid=<%= enc(selectedCase.uuid) %>">Open Case Fields</a>
    </article>
    <article class="card" style="padding:12px; margin:0;">
      <h3 style="margin-top:0;">Form Assembly</h3>
      <p class="muted">Generate filing-ready forms for this case.</p>
      <a class="btn btn-ghost" href="<%= ctx %>/forms.jsp?matter_uuid=<%= enc(selectedCase.uuid) %>">Open Form Assembly</a>
    </article>
    <article class="card" style="padding:12px; margin:0;">
      <h3 style="margin-top:0;">Documents</h3>
      <p class="muted">Manage uploaded and generated documents.</p>
      <a class="btn btn-ghost" href="<%= ctx %>/documents.jsp?case_uuid=<%= enc(selectedCase.uuid) %>">Open Documents</a>
    </article>
    <article class="card" style="padding:12px; margin:0;">
      <h3 style="margin-top:0;">Facts</h3>
      <p class="muted">Capture evidence-linked factual records.</p>
      <a class="btn btn-ghost" href="<%= ctx %>/facts.jsp?case_uuid=<%= enc(selectedCase.uuid) %>">Open Facts</a>
    </article>
    <article class="card" style="padding:12px; margin:0;">
      <h3 style="margin-top:0;">Tasks</h3>
      <p class="muted">Track deadlines, assignees, and work items.</p>
      <a class="btn btn-ghost" href="<%= ctx %>/tasks.jsp?matter_uuid=<%= enc(selectedCase.uuid) %>">Open Tasks</a>
    </article>
    <article class="card" style="padding:12px; margin:0;">
      <h3 style="margin-top:0;">Activity</h3>
      <p class="muted">Review recent events for this case.</p>
      <a class="btn btn-ghost" href="<%= ctx %>/activity.jsp?case_uuid=<%= enc(selectedCase.uuid) %>">Open Activity</a>
    </article>
  </div>
  <div class="actions" style="display:flex; gap:10px; margin-top:10px; flex-wrap:wrap;">
    <a class="btn btn-ghost" href="<%= ctx %>/assembled_forms.jsp?matter_uuid=<%= enc(selectedCase.uuid) %>">Assembled Forms</a>
  </div>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Case Profile</h2>
  <% if (clioManaged) { %>
    <div class="alert alert-warn">
      This case is synced from Clio and is read-only in Controversies.
    </div>
  <% } else { %>
    <form class="form" method="post" action="<%= ctx %>/case_focus.jsp">
      <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
      <input type="hidden" name="action" value="save_case" />
      <input type="hidden" name="case_uuid" value="<%= esc(selectedCase.uuid) %>" />
      <input type="hidden" name="q" value="<%= esc(q) %>" />
      <input type="hidden" name="show" value="<%= esc(show) %>" />

      <label>
        <span>Case Label</span>
        <input type="text" name="label" value="<%= esc(safe(selectedCase.label)) %>" required />
      </label>

      <% if (!enabledAttrDefs.isEmpty()) { %>
        <div class="grid" style="display:grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap:12px;">
          <%
            for (int di = 0; di < enabledAttrDefs.size(); di++) {
              case_attributes.AttributeRec def = enabledAttrDefs.get(di);
              if (def == null) continue;
              String key = attrsStore.normalizeKey(def.key);
              if (key.isBlank()) continue;
              String val = caseAttrValue(def, selectedCase, caseKv);
              List<String> opts = "select".equals(def.dataType) ? attrsStore.optionList(def.options) : new ArrayList<String>();
          %>
            <label>
              <span><%= esc(safe(def.label)) %><%= def.required ? " *" : "" %></span>
              <input type="hidden" name="attr_def_key" value="<%= esc(key) %>" />
              <% if ("textarea".equals(def.dataType)) { %>
                <textarea name="attr_def_value" rows="2"><%= esc(val) %></textarea>
              <% } else if ("date".equals(def.dataType)) { %>
                <input type="date" name="attr_def_value" value="<%= esc(val) %>" />
              <% } else if ("datetime".equals(def.dataType)) { %>
                <input type="datetime-local" name="attr_def_value" value="<%= esc(val) %>" />
              <% } else if ("time".equals(def.dataType)) { %>
                <input type="time" name="attr_def_value" value="<%= esc(val) %>" />
              <% } else if ("number".equals(def.dataType)) { %>
                <input type="number" name="attr_def_value" value="<%= esc(val) %>" />
              <% } else if ("boolean".equals(def.dataType)) { %>
                <select name="attr_def_value">
                  <option value=""></option>
                  <option value="true" <%= "true".equals(normalizeAttrValueByType("boolean", val)) ? "selected" : "" %>>Yes</option>
                  <option value="false" <%= "false".equals(normalizeAttrValueByType("boolean", val)) ? "selected" : "" %>>No</option>
                </select>
              <% } else if ("email".equals(def.dataType)) { %>
                <input type="email" name="attr_def_value" value="<%= esc(val) %>" />
              <% } else if ("phone".equals(def.dataType)) { %>
                <input type="tel" name="attr_def_value" value="<%= esc(val) %>" />
              <% } else if ("url".equals(def.dataType)) { %>
                <input type="url" name="attr_def_value" value="<%= esc(val) %>" />
              <% } else if ("select".equals(def.dataType) && !opts.isEmpty()) { %>
                <select name="attr_def_value">
                  <option value=""></option>
                  <% for (int oi = 0; oi < opts.size(); oi++) { String ov = safe(opts.get(oi)); %>
                    <option value="<%= esc(ov) %>" <%= ov.equals(val) ? "selected" : "" %>><%= esc(ov) %></option>
                  <% } %>
                </select>
              <% } else { %>
                <input type="text" name="attr_def_value" value="<%= esc(val) %>" />
              <% } %>
            </label>
          <% } %>
        </div>
      <% } %>

      <h4 style="margin:14px 0 8px 0;">Additional Replacement Fields</h4>
      <div class="meta" style="margin-bottom:8px;">
        Custom keys outside configured case attributes. Example key: <code>client_name</code> used as <code>{{kv.client_name}}</code>.
      </div>

      <div class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th style="width:35%;">Key</th>
              <th>Value</th>
              <th style="width:90px;">&nbsp;</th>
            </tr>
          </thead>
          <tbody id="focus_fields_tbl">
          <%
            int idx = 0;
            for (Map.Entry<String,String> e : extraKv.entrySet()) {
              if (e == null) continue;
          %>
            <tr>
              <td><input type="text" name="field_key" value="<%= esc(safe(e.getKey())) %>" /></td>
              <td><input type="text" name="field_value" value="<%= esc(safe(e.getValue())) %>" /></td>
              <td><button type="button" class="btn btn-ghost" onclick="removeFieldRow(this)">Remove</button></td>
            </tr>
          <%
              idx++;
            }
            for (; idx < rows; idx++) {
          %>
            <tr>
              <td><input type="text" name="field_key" value="" /></td>
              <td><input type="text" name="field_value" value="" /></td>
              <td><button type="button" class="btn btn-ghost" onclick="removeFieldRow(this)">Remove</button></td>
            </tr>
          <% } %>
          </tbody>
        </table>
      </div>

      <div class="actions" style="display:flex; gap:10px; margin-top:10px;">
        <button type="button" class="btn btn-ghost" onclick="addFieldRow('focus_fields_tbl')">Add Field Row</button>
        <button type="submit" class="btn">Save Case</button>
      </div>
    </form>
  <% } %>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Case Status</h2>
  <% if (clioManaged) { %>
    <div class="muted">Status changes are controlled by Clio for this synced case.</div>
  <% } else if (!selectedCase.trashed) { %>
    <form method="post" action="<%= ctx %>/case_focus.jsp" style="margin:0;">
      <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
      <input type="hidden" name="action" value="archive_case" />
      <input type="hidden" name="case_uuid" value="<%= esc(selectedCase.uuid) %>" />
      <input type="hidden" name="q" value="<%= esc(q) %>" />
      <input type="hidden" name="show" value="<%= esc(show) %>" />
      <button class="btn btn-ghost" type="submit" onclick="return confirm('Archive this case?');">Archive Case</button>
    </form>
  <% } else { %>
    <form method="post" action="<%= ctx %>/case_focus.jsp" style="margin:0;">
      <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
      <input type="hidden" name="action" value="restore_case" />
      <input type="hidden" name="case_uuid" value="<%= esc(selectedCase.uuid) %>" />
      <input type="hidden" name="q" value="<%= esc(q) %>" />
      <input type="hidden" name="show" value="<%= esc(show) %>" />
      <button class="btn" type="submit">Restore Case</button>
    </form>
  <% } %>
</section>
<% } %>

<script>
  function addFieldRow(tbodyId) {
    var tbody = document.getElementById(tbodyId);
    if (!tbody) return;
    var tr = document.createElement("tr");
    tr.innerHTML =
      '<td><input type="text" name="field_key" value="" /></td>' +
      '<td><input type="text" name="field_value" value="" /></td>' +
      '<td><button type="button" class="btn btn-ghost" onclick="removeFieldRow(this)">Remove</button></td>';
    tbody.appendChild(tr);
  }

  function removeFieldRow(btn) {
    if (!btn) return;
    var tr = btn.closest("tr");
    if (!tr) return;
    tr.remove();
  }
</script>

<jsp:include page="footer.jsp" />
