<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>

<%@ page import="java.net.URLEncoder" %>
<%@ page import="java.nio.charset.StandardCharsets" %>
<%@ page import="java.util.ArrayList" %>
<%@ page import="java.util.HashMap" %>
<%@ page import="java.util.HashSet" %>
<%@ page import="java.util.LinkedHashMap" %>
<%@ page import="java.util.List" %>
<%@ page import="java.util.Locale" %>
<%@ page import="java.util.Map" %>
<%@ page import="java.util.Set" %>

<%@ page import="net.familylawandprobate.controversies.case_attributes" %>
<%@ page import="net.familylawandprobate.controversies.case_fields" %>
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
    if (ex == null) app.log("[cases] " + safe(message));
    else app.log("[cases] " + safe(message), ex);
  }

  private static String shortErr(Throwable ex) {
    if (ex == null) return "";
    String m = safe(ex.getMessage()).trim();
    return m.isBlank() ? ex.getClass().getSimpleName() : m;
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

  try {
    matterStore.ensure(tenantUuid);
  } catch (Exception ex) {
    logWarn(application, "Unable to ensure matter store for tenant " + tenantUuid + ": " + shortErr(ex), ex);
  }
  try {
    attrsStore.ensure(tenantUuid);
  } catch (Exception ex) {
    logWarn(application, "Unable to ensure case attribute definitions for tenant " + tenantUuid + ": " + shortErr(ex), ex);
  }

  String csrfToken = csrfForRender(request);
  String message = null;
  String error = null;

  List<case_attributes.AttributeRec> allAttrDefs = new ArrayList<case_attributes.AttributeRec>();
  List<case_attributes.AttributeRec> enabledAttrDefs = new ArrayList<case_attributes.AttributeRec>();
  try {
    allAttrDefs = attrsStore.listAll(tenantUuid);
    enabledAttrDefs = attrsStore.listEnabled(tenantUuid);
  } catch (Exception ex) {
    logWarn(application, "Unable to load case attribute definitions for tenant " + tenantUuid + ": " + shortErr(ex), ex);
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

    if ("create_case".equalsIgnoreCase(action)) {
      String label = safe(request.getParameter("label")).trim();

      try {
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

        String cause = safe(attrValues.get("cause_docket_number")).trim();
        String county = safe(attrValues.get("county")).trim();
        matters.MatterRec rec = matterStore.create(tenantUuid, label, "", "", "", "", "", cause, county);

        fieldsStore.write(tenantUuid, rec.uuid, attrValues);
        response.sendRedirect(ctx + "/cases.jsp?created=1");
        return;
      } catch (Exception ex) {
        logWarn(application, "Unable to create case for tenant " + tenantUuid + ": " + shortErr(ex), ex);
        error = "Unable to create case: " + safe(ex.getMessage());
      }
    }

    if ("save_case".equalsIgnoreCase(action)) {
      String caseUuid = safe(request.getParameter("uuid")).trim();
      String label = safe(request.getParameter("label")).trim();

      try {
        matters.MatterRec current = matterStore.getByUuid(tenantUuid, caseUuid);
        if (current == null) throw new IllegalStateException("Case not found.");

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
        response.sendRedirect(ctx + "/cases.jsp?saved=1");
        return;
      } catch (Exception ex) {
        logWarn(application, "Unable to save case for tenant " + tenantUuid + ": " + shortErr(ex), ex);
        error = "Unable to save case: " + safe(ex.getMessage());
      }
    }

    if ("archive_case".equalsIgnoreCase(action)) {
      String caseUuid = safe(request.getParameter("uuid")).trim();
      try {
        matterStore.trash(tenantUuid, caseUuid);
        response.sendRedirect(ctx + "/cases.jsp?archived=1");
        return;
      } catch (Exception ex) {
        logWarn(application, "Unable to archive case for tenant " + tenantUuid + ": " + shortErr(ex), ex);
        error = "Unable to archive case: " + safe(ex.getMessage());
      }
    }

    if ("restore_case".equalsIgnoreCase(action)) {
      String caseUuid = safe(request.getParameter("uuid")).trim();
      try {
        matterStore.restore(tenantUuid, caseUuid);
        response.sendRedirect(ctx + "/cases.jsp?restored=1");
        return;
      } catch (Exception ex) {
        logWarn(application, "Unable to restore case for tenant " + tenantUuid + ": " + shortErr(ex), ex);
        error = "Unable to restore case: " + safe(ex.getMessage());
      }
    }
  }

  if ("1".equals(request.getParameter("created")))  message = "Case created.";
  if ("1".equals(request.getParameter("saved")))    message = "Case and replacement fields saved.";
  if ("1".equals(request.getParameter("archived"))) message = "Case archived.";
  if ("1".equals(request.getParameter("restored"))) message = "Case restored.";

  String q = safe(request.getParameter("q")).trim();
  String show = safe(request.getParameter("show")).trim().toLowerCase(Locale.ROOT);
  if (show.isBlank()) show = "active";

  List<matters.MatterRec> all = new ArrayList<matters.MatterRec>();
  try {
    all = matterStore.listAll(tenantUuid);
  } catch (Exception ex) {
    logWarn(application, "Unable to list matters for tenant " + tenantUuid + ": " + shortErr(ex), ex);
  }

  Map<String, Map<String, String>> fieldCache = new HashMap<String, Map<String, String>>();
  List<matters.MatterRec> filtered = new ArrayList<matters.MatterRec>();
  String ql = q.toLowerCase(Locale.ROOT);

  for (int i = 0; i < all.size(); i++) {
    matters.MatterRec m = all.get(i);
    if (m == null) continue;

    LinkedHashMap<String,String> kv = new LinkedHashMap<String,String>();
    try {
      kv.putAll(fieldsStore.read(tenantUuid, m.uuid));
    } catch (Exception ex) {
      logWarn(application, "Unable to read case fields for case " + safe(m.uuid) + ": " + shortErr(ex), ex);
    }
    fieldCache.put(safe(m.uuid), kv);

    if ("active".equals(show) && m.trashed) continue;
    if ("archived".equals(show) && !m.trashed) continue;

    if (!q.isBlank()) {
      StringBuilder hay = new StringBuilder(512);
      hay.append(safe(m.label)).append(" ")
         .append(safe(m.causeDocketNumber)).append(" ")
         .append(safe(m.county)).append(" ");
      for (Map.Entry<String,String> e : kv.entrySet()) {
        if (e == null) continue;
        hay.append(safe(e.getKey())).append(" ").append(safe(e.getValue())).append(" ");
      }
      if (!hay.toString().toLowerCase(Locale.ROOT).contains(ql)) continue;
    }
    filtered.add(m);
  }

  String baseQs =
    "q=" + URLEncoder.encode(q, StandardCharsets.UTF_8) +
    "&show=" + URLEncoder.encode(show, StandardCharsets.UTF_8);
%>

<jsp:include page="header.jsp" />

<section class="card">
  <div class="section-head">
    <div>
      <h1 style="margin:0;">Cases</h1>
      <div class="meta">Manage case records and tenant-defined case attributes used in form assembly.</div>
    </div>
  </div>

  <% if (message != null) { %>
    <div class="alert alert-ok" style="margin-top:12px;"><%= esc(message) %></div>
  <% } %>
  <% if (error != null) { %>
    <div class="alert alert-error" style="margin-top:12px;"><%= esc(error) %></div>
  <% } %>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Create Case</h2>
  <form class="form" method="post" action="<%= ctx %>/cases.jsp">
    <input type="hidden" name="action" value="create_case" />
    <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />

    <label>
      <span>Case Label</span>
      <input type="text" name="label" required placeholder="Smith v. Jones" />
    </label>

    <% if (!enabledAttrDefs.isEmpty()) { %>
      <div class="grid" style="display:grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap:12px;">
        <%
          for (int i = 0; i < enabledAttrDefs.size(); i++) {
            case_attributes.AttributeRec def = enabledAttrDefs.get(i);
            if (def == null) continue;
            String key = attrsStore.normalizeKey(def.key);
            if (key.isBlank()) continue;
            String label = safe(def.label);
            List<String> opts = "select".equals(def.dataType) ? attrsStore.optionList(def.options) : new ArrayList<String>();
        %>
          <label>
            <span><%= esc(label) %><%= def.required ? " *" : "" %></span>
            <input type="hidden" name="attr_def_key" value="<%= esc(key) %>" />
            <% if ("textarea".equals(def.dataType)) { %>
              <textarea name="attr_def_value" rows="2"></textarea>
            <% } else if ("date".equals(def.dataType)) { %>
              <input type="date" name="attr_def_value" />
            <% } else if ("datetime".equals(def.dataType)) { %>
              <input type="datetime-local" name="attr_def_value" />
            <% } else if ("time".equals(def.dataType)) { %>
              <input type="time" name="attr_def_value" />
            <% } else if ("number".equals(def.dataType)) { %>
              <input type="number" name="attr_def_value" />
            <% } else if ("boolean".equals(def.dataType)) { %>
              <select name="attr_def_value">
                <option value=""></option>
                <option value="true">Yes</option>
                <option value="false">No</option>
              </select>
            <% } else if ("email".equals(def.dataType)) { %>
              <input type="email" name="attr_def_value" />
            <% } else if ("phone".equals(def.dataType)) { %>
              <input type="tel" name="attr_def_value" />
            <% } else if ("url".equals(def.dataType)) { %>
              <input type="url" name="attr_def_value" />
            <% } else if ("select".equals(def.dataType) && !opts.isEmpty()) { %>
              <select name="attr_def_value">
                <option value=""></option>
                <% for (int oi = 0; oi < opts.size(); oi++) { String ov = safe(opts.get(oi)); %>
                  <option value="<%= esc(ov) %>"><%= esc(ov) %></option>
                <% } %>
              </select>
            <% } else { %>
              <input type="text" name="attr_def_value" />
            <% } %>
          </label>
        <% } %>
      </div>
    <% } %>

    <div class="actions" style="display:flex; gap:10px; margin-top:10px;">
      <button class="btn" type="submit">Create Case</button>
      <a class="btn btn-ghost" href="<%= ctx %>/attribute_editor.jsp">Attribute Editor</a>
      <a class="btn btn-ghost" href="<%= ctx %>/case_attributes.jsp">Manage Case Attributes</a>
    </div>
  </form>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Find Cases</h2>
  <form class="form" method="get" action="<%= ctx %>/cases.jsp">
    <div class="grid" style="display:grid; grid-template-columns: 3fr 1fr auto auto; gap:12px;">
      <label>
        <span>Search</span>
        <input type="text" name="q" value="<%= esc(q) %>" placeholder="Case label or attribute value" />
      </label>
      <label>
        <span>Status</span>
        <select name="show">
          <option value="active" <%= "active".equals(show) ? "selected" : "" %>>Active</option>
          <option value="archived" <%= "archived".equals(show) ? "selected" : "" %>>Archived</option>
          <option value="all" <%= "all".equals(show) ? "selected" : "" %>>All</option>
        </select>
      </label>
      <label>
        <span>&nbsp;</span>
        <button class="btn" type="submit">Apply</button>
      </label>
      <label>
        <span>&nbsp;</span>
        <a class="btn btn-ghost" href="<%= ctx %>/cases.jsp">Reset</a>
      </label>
    </div>
  </form>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Case List</h2>

  <div class="table-wrap">
    <table class="table">
      <thead>
        <tr>
          <th>Case</th>
          <th>Attributes</th>
          <th>Replacement Keys</th>
          <th style="width:360px;">Actions</th>
        </tr>
      </thead>
      <tbody>
      <%
        if (filtered.isEmpty()) {
      %>
        <tr>
          <td colspan="4" class="muted">No cases match your filter.</td>
        </tr>
      <%
        } else {
          for (int i = 0; i < filtered.size(); i++) {
            matters.MatterRec m = filtered.get(i);
            if (m == null) continue;
            boolean clioManaged = isClioManagedMatter(m);

            String id = safe(m.uuid);
            String editRowId = "edit_case_" + i;

            Map<String,String> kv = fieldCache.get(id);
            if (kv == null) kv = new LinkedHashMap<String,String>();

            LinkedHashMap<String,String> extraKv = new LinkedHashMap<String,String>();
            for (Map.Entry<String,String> e : kv.entrySet()) {
              if (e == null) continue;
              String nk = fieldsStore.normalizeKey(e.getKey());
              if (nk.isBlank()) continue;
              if (attrKeySet.contains(nk)) continue;
              extraKv.put(nk, safe(e.getValue()));
            }
            int rows = Math.max(4, extraKv.size() + 1);
      %>
        <tr>
          <td>
            <strong><%= esc(safe(m.label)) %></strong>
            <div class="muted" style="margin-top:4px;"><%= m.trashed ? "Archived" : "Active" %></div>
            <% if (clioManaged) { %>
              <div class="muted" style="margin-top:4px;">Synced from Clio. Edit in Clio.</div>
            <% } %>
          </td>
          <td>
            <%
              int shown = 0;
              for (int di = 0; di < enabledAttrDefs.size(); di++) {
                case_attributes.AttributeRec def = enabledAttrDefs.get(di);
                if (def == null) continue;
                String val = caseAttrValue(def, m, kv);
                if (safe(val).trim().isBlank()) continue;
            %>
              <div><span class="muted"><%= esc(safe(def.label)) %>:</span> <%= esc(val) %></div>
            <%
                shown++;
                if (shown >= 4) break;
              }
              if (shown == 0) {
            %>
              <span class="muted">No values</span>
            <% } %>
          </td>
          <td><%= extraKv.size() %></td>
          <td>
            <% if (clioManaged) { %>
              <button class="btn btn-ghost" type="button" disabled title="Synced from Clio. Edit in Clio.">Edit</button>
            <% } else { %>
              <button class="btn btn-ghost" type="button" onclick="toggleEdit('<%= editRowId %>')">Edit</button>
            <% } %>
            <a class="btn btn-ghost" href="<%= ctx %>/forms.jsp?matter_uuid=<%= URLEncoder.encode(id, StandardCharsets.UTF_8) %>&<%= baseQs %>">Assemble Forms</a>
            <% if (clioManaged) { %>
              <span class="muted">Read-only. Edit in Clio.</span>
            <% } else if (!m.trashed) { %>
              <form method="post" action="<%= ctx %>/cases.jsp?<%= baseQs %>" style="display:inline;">
                <input type="hidden" name="action" value="archive_case" />
                <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
                <input type="hidden" name="uuid" value="<%= esc(id) %>" />
                <button class="btn btn-ghost" type="submit" onclick="return confirm('Archive this case?');">Archive</button>
              </form>
            <% } else { %>
              <form method="post" action="<%= ctx %>/cases.jsp?<%= baseQs %>" style="display:inline;">
                <input type="hidden" name="action" value="restore_case" />
                <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
                <input type="hidden" name="uuid" value="<%= esc(id) %>" />
                <button class="btn" type="submit">Restore</button>
              </form>
            <% } %>
          </td>
        </tr>

        <% if (!clioManaged) { %>
        <tr id="<%= editRowId %>" style="display:none;">
          <td colspan="4">
            <div class="card" style="margin:8px 0; padding:14px; background:rgba(0,0,0,0.02);">
              <form class="form" method="post" action="<%= ctx %>/cases.jsp?<%= baseQs %>">
                <input type="hidden" name="action" value="save_case" />
                <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
                <input type="hidden" name="uuid" value="<%= esc(id) %>" />

                <label>
                  <span>Case Label</span>
                  <input type="text" name="label" value="<%= esc(safe(m.label)) %>" required />
                </label>

                <% if (!enabledAttrDefs.isEmpty()) { %>
                  <div class="grid" style="display:grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap:12px;">
                    <%
                      for (int di = 0; di < enabledAttrDefs.size(); di++) {
                        case_attributes.AttributeRec def = enabledAttrDefs.get(di);
                        if (def == null) continue;
                        String key = attrsStore.normalizeKey(def.key);
                        if (key.isBlank()) continue;
                        String val = caseAttrValue(def, m, kv);
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
                <div class="meta" style="margin-bottom:8px;">Custom keys (outside configured case attributes). Use keys like <code>client_name</code> referenced as <code>{{kv.client_name}}</code>.</div>

                <div class="table-wrap">
                  <table class="table">
                    <thead>
                      <tr>
                        <th style="width:35%;">Key</th>
                        <th>Value</th>
                        <th style="width:90px;">&nbsp;</th>
                      </tr>
                    </thead>
                    <tbody id="fields_tbl_<%= i %>">
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
                  <button type="button" class="btn btn-ghost" onclick="addFieldRow('fields_tbl_<%= i %>')">Add Field Row</button>
                  <button type="submit" class="btn">Save Case</button>
                  <button type="button" class="btn btn-ghost" onclick="toggleEdit('<%= editRowId %>')">Cancel</button>
                </div>
              </form>
            </div>
          </td>
        </tr>
        <% } %>
      <%
          }
        }
      %>
      </tbody>
    </table>
  </div>
</section>

<script>
  function toggleEdit(id) {
    var row = document.getElementById(id);
    if (!row) return;
    row.style.display = (row.style.display === "none" || row.style.display === "") ? "table-row" : "none";
  }

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
