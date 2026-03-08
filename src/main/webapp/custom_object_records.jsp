<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" isELIgnored="true" %>

<%@ page import="java.net.URLEncoder" %>
<%@ page import="java.nio.charset.StandardCharsets" %>
<%@ page import="java.time.Instant" %>
<%@ page import="java.util.ArrayList" %>
<%@ page import="java.util.HashMap" %>
<%@ page import="java.util.LinkedHashMap" %>
<%@ page import="java.util.List" %>
<%@ page import="java.util.Locale" %>
<%@ page import="java.util.Map" %>

<%@ page import="net.familylawandprobate.controversies.activity_log" %>
<%@ page import="net.familylawandprobate.controversies.custom_object_attributes" %>
<%@ page import="net.familylawandprobate.controversies.custom_object_records" %>
<%@ page import="net.familylawandprobate.controversies.custom_objects" %>
<%@ page import="net.familylawandprobate.controversies.permission_layers" %>
<%@ page import="net.familylawandprobate.controversies.users_roles" %>

<%@ include file="security.jspf" %>
<%
  if (!require_login()) return;
%>

<%!
  private static final String S_TENANT_UUID = "tenant.uuid";
  private static final String S_USER_UUID = "user.uuid";
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

  private static String csv(String s) {
    String v = safe(s).replace("\r", " ").replace("\n", " ");
    return "\"" + v.replace("\"", "\"\"") + "\"";
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

  private static void logInfo(jakarta.servlet.ServletContext app, String msg) {
    if (app == null) return;
    app.log("[custom_object_records] " + safe(msg));
  }

  private static void logWarn(jakarta.servlet.ServletContext app, String msg, Throwable ex) {
    if (app == null) return;
    if (ex == null) app.log("[custom_object_records] " + safe(msg));
    else app.log("[custom_object_records] " + safe(msg), ex);
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

  private static boolean hasCustomObjectActionPermission(jakarta.servlet.http.HttpSession session,
                                                         String objectKey,
                                                         String action) {
    List<String> keys = permission_layers.customObjectPermissionKeys(objectKey, action);
    for (int i = 0; i < keys.size(); i++) {
      String key = safe(keys.get(i)).trim();
      if (key.isBlank()) continue;
      if (users_roles.hasPermissionTrue(session, key)) return true;
    }
    return false;
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

  boolean tenantAdmin = users_roles.hasPermissionTrue(session, "tenant_admin");

  custom_objects objectStore = custom_objects.defaultStore();
  custom_object_attributes attrStore = custom_object_attributes.defaultStore();
  custom_object_records recordStore = custom_object_records.defaultStore();
  activity_log logs = activity_log.defaultStore();

  String objectUuid = safe(request.getParameter("object_uuid")).trim();
  String q = safe(request.getParameter("q")).trim();
  String show = safe(request.getParameter("show")).trim().toLowerCase(Locale.ROOT);
  if (show.isBlank()) show = "active";
  String reportAttr = safe(request.getParameter("report_attr")).trim();

  List<custom_objects.ObjectRec> availableObjects = new ArrayList<custom_objects.ObjectRec>();
  try {
    objectStore.ensure(tenantUuid);
    availableObjects = tenantAdmin ? objectStore.listAll(tenantUuid) : objectStore.listPublished(tenantUuid);
  } catch (Exception ex) {
    logWarn(application, "Unable to load object definitions for tenant=" + tenantUuid + ": " + shortErr(ex), ex);
  }

  if (objectUuid.isBlank() && !availableObjects.isEmpty()) objectUuid = safe(availableObjects.get(0).uuid);

  custom_objects.ObjectRec objectRec = null;
  if (!objectUuid.isBlank()) {
    try { objectRec = objectStore.getByUuid(tenantUuid, objectUuid); } catch (Exception ignored) {}
  }

  boolean allowRead = false;
  boolean allowCreate = false;
  boolean allowEdit = false;
  boolean allowArchive = false;
  boolean allowExport = false;
  if (objectRec != null) {
    if (tenantAdmin) {
      allowRead = true;
      allowCreate = true;
      allowEdit = true;
      allowArchive = true;
      allowExport = true;
    } else if (objectRec.enabled && objectRec.published) {
      String objectKey = safe(objectRec.key).trim();
      allowRead = hasCustomObjectActionPermission(session, objectKey, "access");
      allowCreate = allowRead && hasCustomObjectActionPermission(session, objectKey, "create");
      allowEdit = allowRead && hasCustomObjectActionPermission(session, objectKey, "edit");
      allowArchive = allowRead && hasCustomObjectActionPermission(session, objectKey, "archive");
      allowExport = allowRead && hasCustomObjectActionPermission(session, objectKey, "export");
    }
  }
  boolean allowWrite = allowCreate || allowEdit || allowArchive;

  String csrfToken = csrfForRender(request);
  String message = null;
  String error = null;

  List<custom_object_attributes.AttributeRec> enabledAttrDefs = new ArrayList<custom_object_attributes.AttributeRec>();
  LinkedHashMap<String, custom_object_attributes.AttributeRec> defByKey = new LinkedHashMap<String, custom_object_attributes.AttributeRec>();

  if (allowRead) {
    try {
      attrStore.ensure(tenantUuid, objectUuid);
      enabledAttrDefs = attrStore.listEnabled(tenantUuid, objectUuid);
      for (int i = 0; i < enabledAttrDefs.size(); i++) {
        custom_object_attributes.AttributeRec def = enabledAttrDefs.get(i);
        if (def == null) continue;
        String key = attrStore.normalizeKey(def.key);
        if (key.isBlank()) continue;
        defByKey.put(key, def);
      }
    } catch (Exception ex) {
      error = "Unable to load object attributes.";
      logWarn(application, "Unable to load attributes for tenant=" + tenantUuid + ", object=" + objectUuid + ": " + shortErr(ex), ex);
    }
  }

  if ("POST".equalsIgnoreCase(request.getMethod())) {
    String action = safe(request.getParameter("action")).trim();
    objectUuid = safe(request.getParameter("object_uuid")).trim();

    if ("create_record".equalsIgnoreCase(action) && !allowCreate) {
      error = "You do not have permission to create records for this object.";
    } else if ("save_record".equalsIgnoreCase(action) && !allowEdit) {
      error = "You do not have permission to edit records for this object.";
    } else if (("archive_record".equalsIgnoreCase(action) || "restore_record".equalsIgnoreCase(action)) && !allowArchive) {
      error = "You do not have permission to archive or restore records for this object.";
    } else if (!allowWrite) {
      error = "You do not have access to modify this object.";
    } else {
      try {
        if ("create_record".equalsIgnoreCase(action)) {
          String label = safe(request.getParameter("label")).trim();

          LinkedHashMap<String, String> values = new LinkedHashMap<String, String>();
          String[] attrKeys = request.getParameterValues("attr_def_key");
          String[] attrVals = request.getParameterValues("attr_def_value");
          int an = Math.max(attrKeys == null ? 0 : attrKeys.length, attrVals == null ? 0 : attrVals.length);
          for (int i = 0; i < an; i++) {
            String key = (attrKeys != null && i < attrKeys.length) ? attrStore.normalizeKey(attrKeys[i]) : "";
            if (key.isBlank()) continue;
            custom_object_attributes.AttributeRec def = defByKey.get(key);
            if (def == null) continue;

            String val = normalizeAttrValueByType(def.dataType, (attrVals != null && i < attrVals.length) ? attrVals[i] : "");
            if ("select".equals(def.dataType)) {
              List<String> opts = attrStore.optionList(def.options);
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
            values.put(key, val);
          }

          custom_object_records.RecordRec rec = recordStore.create(tenantUuid, objectUuid, label, values);
          logs.logVerbose("custom_object_record.created",
            tenantUuid,
            userUuid,
            "",
            "",
            Map.of(
              "object_uuid", safe(objectUuid),
              "object_key", safe(objectRec == null ? "" : objectRec.key),
              "record_uuid", safe(rec == null ? "" : rec.uuid),
              "label", safe(label)
            )
          );
          logInfo(application, "Created record tenant=" + tenantUuid + ", object=" + objectUuid + ", record=" + safe(rec == null ? "" : rec.uuid) + ", user=" + userUuid);
          response.sendRedirect(ctx + "/custom_object_records.jsp?object_uuid=" + enc(objectUuid) + "&created=1");
          return;
        }

        if ("save_record".equalsIgnoreCase(action)) {
          String recordUuid = safe(request.getParameter("uuid")).trim();
          String label = safe(request.getParameter("label")).trim();

          custom_object_records.RecordRec existing = recordStore.getByUuid(tenantUuid, objectUuid, recordUuid);
          if (existing == null) throw new IllegalStateException("Record not found.");

          LinkedHashMap<String, String> values = new LinkedHashMap<String, String>();
          String[] attrKeys = request.getParameterValues("attr_def_key");
          String[] attrVals = request.getParameterValues("attr_def_value");
          int an = Math.max(attrKeys == null ? 0 : attrKeys.length, attrVals == null ? 0 : attrVals.length);
          for (int i = 0; i < an; i++) {
            String key = (attrKeys != null && i < attrKeys.length) ? attrStore.normalizeKey(attrKeys[i]) : "";
            if (key.isBlank()) continue;
            custom_object_attributes.AttributeRec def = defByKey.get(key);
            if (def == null) continue;

            String val = normalizeAttrValueByType(def.dataType, (attrVals != null && i < attrVals.length) ? attrVals[i] : "");
            if ("select".equals(def.dataType)) {
              List<String> opts = attrStore.optionList(def.options);
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
            values.put(key, val);
          }

          boolean changed = recordStore.update(tenantUuid, objectUuid, recordUuid, label, values);
          if (!changed) throw new IllegalStateException("Record not found.");

          logs.logVerbose("custom_object_record.saved",
            tenantUuid,
            userUuid,
            "",
            "",
            Map.of(
              "object_uuid", safe(objectUuid),
              "object_key", safe(objectRec == null ? "" : objectRec.key),
              "record_uuid", safe(recordUuid),
              "label", safe(label)
            )
          );
          logInfo(application, "Saved record tenant=" + tenantUuid + ", object=" + objectUuid + ", record=" + recordUuid + ", user=" + userUuid);
          response.sendRedirect(ctx + "/custom_object_records.jsp?object_uuid=" + enc(objectUuid) + "&saved=1");
          return;
        }

        if ("archive_record".equalsIgnoreCase(action)) {
          String recordUuid = safe(request.getParameter("uuid")).trim();
          boolean ok = recordStore.setTrashed(tenantUuid, objectUuid, recordUuid, true);
          if (!ok) throw new IllegalStateException("Record not found.");

          logs.logVerbose("custom_object_record.archived",
            tenantUuid,
            userUuid,
            "",
            "",
            Map.of("object_uuid", safe(objectUuid), "record_uuid", safe(recordUuid))
          );
          logInfo(application, "Archived record tenant=" + tenantUuid + ", object=" + objectUuid + ", record=" + recordUuid + ", user=" + userUuid);
          response.sendRedirect(ctx + "/custom_object_records.jsp?object_uuid=" + enc(objectUuid) + "&archived=1");
          return;
        }

        if ("restore_record".equalsIgnoreCase(action)) {
          String recordUuid = safe(request.getParameter("uuid")).trim();
          boolean ok = recordStore.setTrashed(tenantUuid, objectUuid, recordUuid, false);
          if (!ok) throw new IllegalStateException("Record not found.");

          logs.logVerbose("custom_object_record.restored",
            tenantUuid,
            userUuid,
            "",
            "",
            Map.of("object_uuid", safe(objectUuid), "record_uuid", safe(recordUuid))
          );
          logInfo(application, "Restored record tenant=" + tenantUuid + ", object=" + objectUuid + ", record=" + recordUuid + ", user=" + userUuid);
          response.sendRedirect(ctx + "/custom_object_records.jsp?object_uuid=" + enc(objectUuid) + "&restored=1");
          return;
        }
      } catch (Exception ex) {
        error = "Unable to save record: " + safe(ex.getMessage());
        logs.logError("custom_object_record.action_failed",
          tenantUuid,
          userUuid,
          "",
          "",
          Map.of(
            "object_uuid", safe(objectUuid),
            "action", safe(action),
            "reason", safe(ex.getClass().getSimpleName()),
            "message", safe(ex.getMessage())
          )
        );
        logWarn(application, "Action failed tenant=" + tenantUuid + ", object=" + objectUuid + ", action=" + action + ": " + shortErr(ex), ex);
      }
    }
  }

  if ("1".equals(request.getParameter("created"))) message = "Record created.";
  if ("1".equals(request.getParameter("saved"))) message = "Record updated.";
  if ("1".equals(request.getParameter("archived"))) message = "Record archived.";
  if ("1".equals(request.getParameter("restored"))) message = "Record restored.";

  if (objectRec == null && !availableObjects.isEmpty()) {
    // If object was missing from query but list is available, ensure objectRec points to selected first.
    try { objectRec = objectStore.getByUuid(tenantUuid, objectUuid); } catch (Exception ignored) {}
  }

  List<custom_object_records.RecordRec> allRecords = new ArrayList<custom_object_records.RecordRec>();
  if (allowRead) {
    try {
      recordStore.ensure(tenantUuid, objectUuid);
      allRecords = recordStore.listAll(tenantUuid, objectUuid);
    } catch (Exception ex) {
      error = "Unable to load records.";
      logWarn(application, "Unable to list records for tenant=" + tenantUuid + ", object=" + objectUuid + ": " + shortErr(ex), ex);
    }
  }

  List<custom_object_records.RecordRec> filtered = new ArrayList<custom_object_records.RecordRec>();
  String ql = q.toLowerCase(Locale.ROOT);
  for (int i = 0; i < allRecords.size(); i++) {
    custom_object_records.RecordRec r = allRecords.get(i);
    if (r == null) continue;

    if ("active".equals(show) && r.trashed) continue;
    if ("archived".equals(show) && !r.trashed) continue;

    if (!q.isBlank()) {
      StringBuilder hay = new StringBuilder(512);
      hay.append(safe(r.label)).append(" ");
      for (Map.Entry<String, String> e : r.values.entrySet()) {
        if (e == null) continue;
        hay.append(safe(e.getKey())).append(" ").append(safe(e.getValue())).append(" ");
      }
      if (!hay.toString().toLowerCase(Locale.ROOT).contains(ql)) continue;
    }

    filtered.add(r);
  }

  int totalCount = allRecords.size();
  int activeCount = 0;
  int archivedCount = 0;
  for (int i = 0; i < allRecords.size(); i++) {
    custom_object_records.RecordRec r = allRecords.get(i);
    if (r == null) continue;
    if (r.trashed) archivedCount++;
    else activeCount++;
  }

  LinkedHashMap<String, Integer> reportCounts = new LinkedHashMap<String, Integer>();
  if (!reportAttr.isBlank()) {
    String reportKey = attrStore.normalizeKey(reportAttr);
    if (!reportKey.isBlank() && defByKey.containsKey(reportKey)) {
      for (int i = 0; i < filtered.size(); i++) {
        custom_object_records.RecordRec r = filtered.get(i);
        if (r == null) continue;
        String value = safe(r.values.get(reportKey)).trim();
        if (value.isBlank()) value = "(blank)";
        Integer count = reportCounts.get(value);
        reportCounts.put(value, Integer.valueOf((count == null ? 0 : count.intValue()) + 1));
      }
    }
  }

  if (allowRead && allowExport && "csv".equalsIgnoreCase(request.getParameter("export"))) {
    response.setContentType("text/csv; charset=UTF-8");
    String fname = "custom-object-" + (objectRec == null ? "records" : safe(objectRec.key)) + "-" + Instant.now().toEpochMilli() + ".csv";
    response.setHeader("Content-Disposition", "attachment; filename=\"" + fname.replaceAll("[^A-Za-z0-9._-]", "_") + "\"");

    StringBuilder csvOut = new StringBuilder(4096);
    csvOut.append(csv("Label")).append(',')
          .append(csv("Trashed")).append(',')
          .append(csv("Created At")).append(',')
          .append(csv("Updated At"));
    for (int i = 0; i < enabledAttrDefs.size(); i++) {
      custom_object_attributes.AttributeRec def = enabledAttrDefs.get(i);
      if (def == null) continue;
      csvOut.append(',').append(csv(safe(def.label)));
    }
    csvOut.append("\n");

    for (int i = 0; i < filtered.size(); i++) {
      custom_object_records.RecordRec r = filtered.get(i);
      if (r == null) continue;
      csvOut.append(csv(safe(r.label))).append(',')
            .append(csv(r.trashed ? "yes" : "no")).append(',')
            .append(csv(safe(r.createdAt))).append(',')
            .append(csv(safe(r.updatedAt)));
      for (int ai = 0; ai < enabledAttrDefs.size(); ai++) {
        custom_object_attributes.AttributeRec def = enabledAttrDefs.get(ai);
        if (def == null) continue;
        String key = attrStore.normalizeKey(def.key);
        csvOut.append(',').append(csv(safe(r.values.get(key))));
      }
      csvOut.append("\n");
    }

    logs.logVerbose("custom_object_report.export_csv",
      tenantUuid,
      userUuid,
      "",
      "",
      Map.of(
        "object_uuid", safe(objectUuid),
        "row_count", String.valueOf(filtered.size()),
        "query", safe(q),
        "show", safe(show)
      )
    );

    out.write(csvOut.toString());
    return;
  }
%>

<jsp:include page="header.jsp" />

<section class="card">
  <h1 style="margin:0;">Custom Object Records</h1>
  <div class="meta" style="margin-top:6px;">Run CRUD, search, and reporting operations for tenant-defined objects.</div>

  <% if (message != null) { %>
    <div class="alert alert-ok" style="margin-top:12px;"><%= esc(message) %></div>
  <% } %>
  <% if (error != null) { %>
    <div class="alert alert-error" style="margin-top:12px;"><%= esc(error) %></div>
  <% } %>
</section>

<section class="card" style="margin-top:12px;">
  <form class="form" method="get" action="<%= ctx %>/custom_object_records.jsp" style="margin-bottom:8px;">
    <label>
      <span>Object</span>
      <select name="object_uuid" onchange="this.form.submit()">
        <% for (int i = 0; i < availableObjects.size(); i++) {
             custom_objects.ObjectRec ob = availableObjects.get(i);
             if (ob == null) continue;
             String oid = safe(ob.uuid);
        %>
          <option value="<%= esc(oid) %>" <%= oid.equals(objectUuid) ? "selected" : "" %>><%= esc(safe(ob.pluralLabel)) %></option>
        <% } %>
      </select>
    </label>
  </form>

  <% if (objectRec == null) { %>
    <div class="muted">No custom object is available.</div>
    <% if (tenantAdmin) { %>
      <div style="margin-top:10px;"><a class="btn" href="<%= ctx %>/custom_objects.jsp">Manage Custom Objects</a></div>
    <% } %>
  <% } else if (!allowRead) { %>
    <div class="alert alert-warn">This object is not currently published for general access.</div>
    <% if (tenantAdmin) { %>
      <div class="meta" style="margin-top:6px;">Tenant admin access remains available. Publish this object in Custom Objects for non-admin users.</div>
    <% } %>
  <% } else { %>
    <div class="meta" style="margin-top:4px;">
      Working with: <strong><%= esc(safe(objectRec.pluralLabel)) %></strong>
      (<code><%= esc(safe(objectRec.key)) %></code>)
      <% if (tenantAdmin) { %>
        <a class="btn btn-ghost" style="margin-left:10px;" href="<%= ctx %>/custom_object_attributes.jsp?object_uuid=<%= enc(objectUuid) %>">Edit Attributes</a>
      <% } %>
    </div>

    <form class="form" method="get" action="<%= ctx %>/custom_object_records.jsp" style="margin-top:12px;">
      <input type="hidden" name="object_uuid" value="<%= esc(objectUuid) %>" />
      <div class="grid grid-3">
        <label>
          <span>Search</span>
          <input type="search" name="q" value="<%= esc(q) %>" placeholder="Search labels and attributes" />
        </label>
        <label>
          <span>Show</span>
          <select name="show">
            <option value="active" <%= "active".equals(show) ? "selected" : "" %>>Active</option>
            <option value="archived" <%= "archived".equals(show) ? "selected" : "" %>>Archived</option>
            <option value="all" <%= "all".equals(show) ? "selected" : "" %>>All</option>
          </select>
        </label>
        <label>
          <span>Report Field</span>
          <select name="report_attr">
            <option value=""></option>
            <% for (int i = 0; i < enabledAttrDefs.size(); i++) {
                 custom_object_attributes.AttributeRec def = enabledAttrDefs.get(i);
                 if (def == null) continue;
                 String key = attrStore.normalizeKey(def.key);
                 if (key.isBlank()) continue;
            %>
              <option value="<%= esc(key) %>" <%= key.equals(attrStore.normalizeKey(reportAttr)) ? "selected" : "" %>><%= esc(safe(def.label)) %></option>
            <% } %>
          </select>
        </label>
      </div>
      <div class="actions" style="display:flex; gap:10px; margin-top:10px;">
        <button class="btn" type="submit">Apply</button>
        <% if (allowExport) { %>
          <a class="btn btn-ghost" href="<%= ctx %>/custom_object_records.jsp?object_uuid=<%= enc(objectUuid) %>&q=<%= enc(q) %>&show=<%= enc(show) %>&export=csv">Export CSV</a>
        <% } %>
      </div>
    </form>

    <div class="grid" style="display:grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap:12px; margin-top:12px;">
      <article class="card" style="margin:0; padding:12px;"><div class="meta">Total</div><div style="font-size:1.4rem; font-weight:700;"><%= totalCount %></div></article>
      <article class="card" style="margin:0; padding:12px;"><div class="meta">Active</div><div style="font-size:1.4rem; font-weight:700;"><%= activeCount %></div></article>
      <article class="card" style="margin:0; padding:12px;"><div class="meta">Archived</div><div style="font-size:1.4rem; font-weight:700;"><%= archivedCount %></div></article>
      <article class="card" style="margin:0; padding:12px;"><div class="meta">Filtered</div><div style="font-size:1.4rem; font-weight:700;"><%= filtered.size() %></div></article>
    </div>

    <% if (!reportCounts.isEmpty()) { %>
      <div class="table-wrap" style="margin-top:12px;">
        <table class="table">
          <thead><tr><th>Report Value</th><th>Count</th></tr></thead>
          <tbody>
            <% for (Map.Entry<String, Integer> e : reportCounts.entrySet()) { if (e == null) continue; %>
              <tr><td><%= esc(safe(e.getKey())) %></td><td><%= e.getValue() == null ? 0 : e.getValue().intValue() %></td></tr>
            <% } %>
          </tbody>
        </table>
      </div>
    <% } %>
  <% } %>
</section>

<% if (allowCreate) { %>
<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Create <%= esc(safe(objectRec == null ? "Record" : objectRec.label)) %></h2>
  <form method="post" class="form" action="<%= ctx %>/custom_object_records.jsp">
    <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
    <input type="hidden" name="action" value="create_record" />
    <input type="hidden" name="object_uuid" value="<%= esc(objectUuid) %>" />

    <label>
      <span>Label</span>
      <input type="text" name="label" required placeholder="Record label" />
    </label>

    <% if (!enabledAttrDefs.isEmpty()) { %>
      <div class="grid" style="display:grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap:12px;">
        <% for (int i = 0; i < enabledAttrDefs.size(); i++) {
             custom_object_attributes.AttributeRec def = enabledAttrDefs.get(i);
             if (def == null) continue;
             String key = attrStore.normalizeKey(def.key);
             if (key.isBlank()) continue;
             List<String> opts = "select".equals(def.dataType) ? attrStore.optionList(def.options) : new ArrayList<String>();
        %>
          <label>
            <span><%= esc(safe(def.label)) %><%= def.required ? " *" : "" %></span>
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

    <button class="btn" type="submit">Create Record</button>
  </form>
</section>
<% } %>

<% if (allowRead) { %>
<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;"><%= esc(safe(objectRec == null ? "Records" : objectRec.pluralLabel)) %></h2>
  <div class="table-wrap">
    <table class="table">
      <thead>
        <tr>
          <th>Label</th>
          <th>Status</th>
          <th>Updated</th>
          <th>Attributes</th>
          <th style="width:360px;">Actions</th>
        </tr>
      </thead>
      <tbody>
      <% if (filtered.isEmpty()) { %>
        <tr><td colspan="5" class="muted">No records match your filters.</td></tr>
      <% } else {
           for (int i = 0; i < filtered.size(); i++) {
             custom_object_records.RecordRec r = filtered.get(i);
             if (r == null) continue;
             String rid = safe(r.uuid);
             String editRowId = "edit_record_" + i;
      %>
        <tr>
          <td><strong><%= esc(safe(r.label)) %></strong></td>
          <td><%= r.trashed ? "Archived" : "Active" %></td>
          <td><%= esc(safe(r.updatedAt)) %></td>
          <td>
            <% if (enabledAttrDefs.isEmpty()) { %>
              <span class="muted">None</span>
            <% } else { %>
              <% for (int ai = 0; ai < enabledAttrDefs.size(); ai++) {
                   custom_object_attributes.AttributeRec def = enabledAttrDefs.get(ai);
                   if (def == null) continue;
                   String key = attrStore.normalizeKey(def.key);
                   String vv = safe(r.values.get(key)).trim();
                   if (vv.isBlank()) continue;
              %>
                <div><span class="muted"><%= esc(safe(def.label)) %>:</span> <%= esc(vv) %></div>
              <% } %>
            <% } %>
          </td>
          <td>
            <% if (allowEdit) { %>
              <button type="button" class="btn btn-ghost" onclick="toggleEditRow('<%= esc(editRowId) %>')">Edit</button>
            <% } %>
            <% if (allowArchive) { %>
              <% if (!r.trashed) { %>
                <form method="post" action="<%= ctx %>/custom_object_records.jsp" style="display:inline;">
                  <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
                  <input type="hidden" name="action" value="archive_record" />
                  <input type="hidden" name="object_uuid" value="<%= esc(objectUuid) %>" />
                  <input type="hidden" name="uuid" value="<%= esc(rid) %>" />
                  <button class="btn btn-ghost" type="submit">Archive</button>
                </form>
              <% } else { %>
                <form method="post" action="<%= ctx %>/custom_object_records.jsp" style="display:inline;">
                  <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
                  <input type="hidden" name="action" value="restore_record" />
                  <input type="hidden" name="object_uuid" value="<%= esc(objectUuid) %>" />
                  <input type="hidden" name="uuid" value="<%= esc(rid) %>" />
                  <button class="btn btn-ghost" type="submit">Restore</button>
                </form>
              <% } %>
            <% } %>
          </td>
        </tr>
        <% if (allowEdit) { %>
        <tr id="<%= esc(editRowId) %>" style="display:none;">
          <td colspan="5">
            <form method="post" class="form" action="<%= ctx %>/custom_object_records.jsp">
              <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
              <input type="hidden" name="action" value="save_record" />
              <input type="hidden" name="object_uuid" value="<%= esc(objectUuid) %>" />
              <input type="hidden" name="uuid" value="<%= esc(rid) %>" />

              <label>
                <span>Label</span>
                <input type="text" name="label" value="<%= esc(safe(r.label)) %>" required />
              </label>

              <% if (!enabledAttrDefs.isEmpty()) { %>
                <div class="grid" style="display:grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap:12px;">
                  <% for (int ai = 0; ai < enabledAttrDefs.size(); ai++) {
                       custom_object_attributes.AttributeRec def = enabledAttrDefs.get(ai);
                       if (def == null) continue;
                       String key = attrStore.normalizeKey(def.key);
                       String vv = safe(r.values.get(key));
                       List<String> opts = "select".equals(def.dataType) ? attrStore.optionList(def.options) : new ArrayList<String>();
                  %>
                    <label>
                      <span><%= esc(safe(def.label)) %><%= def.required ? " *" : "" %></span>
                      <input type="hidden" name="attr_def_key" value="<%= esc(key) %>" />
                      <% if ("textarea".equals(def.dataType)) { %>
                        <textarea name="attr_def_value" rows="2"><%= esc(vv) %></textarea>
                      <% } else if ("date".equals(def.dataType)) { %>
                        <input type="date" name="attr_def_value" value="<%= esc(vv) %>" />
                      <% } else if ("datetime".equals(def.dataType)) { %>
                        <input type="datetime-local" name="attr_def_value" value="<%= esc(vv) %>" />
                      <% } else if ("time".equals(def.dataType)) { %>
                        <input type="time" name="attr_def_value" value="<%= esc(vv) %>" />
                      <% } else if ("number".equals(def.dataType)) { %>
                        <input type="number" name="attr_def_value" value="<%= esc(vv) %>" />
                      <% } else if ("boolean".equals(def.dataType)) { %>
                        <select name="attr_def_value">
                          <option value=""></option>
                          <option value="true" <%= "true".equals(normalizeAttrValueByType("boolean", vv)) ? "selected" : "" %>>Yes</option>
                          <option value="false" <%= "false".equals(normalizeAttrValueByType("boolean", vv)) ? "selected" : "" %>>No</option>
                        </select>
                      <% } else if ("email".equals(def.dataType)) { %>
                        <input type="email" name="attr_def_value" value="<%= esc(vv) %>" />
                      <% } else if ("phone".equals(def.dataType)) { %>
                        <input type="tel" name="attr_def_value" value="<%= esc(vv) %>" />
                      <% } else if ("url".equals(def.dataType)) { %>
                        <input type="url" name="attr_def_value" value="<%= esc(vv) %>" />
                      <% } else if ("select".equals(def.dataType) && !opts.isEmpty()) { %>
                        <select name="attr_def_value">
                          <option value=""></option>
                          <% for (int oi = 0; oi < opts.size(); oi++) {
                               String ov = safe(opts.get(oi));
                          %>
                            <option value="<%= esc(ov) %>" <%= ov.equals(vv) ? "selected" : "" %>><%= esc(ov) %></option>
                          <% } %>
                        </select>
                      <% } else { %>
                        <input type="text" name="attr_def_value" value="<%= esc(vv) %>" />
                      <% } %>
                    </label>
                  <% } %>
                </div>
              <% } %>

              <button class="btn" type="submit">Save Record</button>
            </form>
          </td>
        </tr>
        <% } %>
      <%   }
         } %>
      </tbody>
    </table>
  </div>
</section>
<% } %>

<script>
  function toggleEditRow(id) {
    var el = document.getElementById(id);
    if (!el) return;
    if (el.style.display === "none" || el.style.display === "") el.style.display = "table-row";
    else el.style.display = "none";
  }
</script>

<jsp:include page="footer.jsp" />
