<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" isELIgnored="true" %>

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

<%@ page import="net.familylawandprobate.controversies.document_attributes" %>
<%@ page import="net.familylawandprobate.controversies.document_fields" %>
<%@ page import="net.familylawandprobate.controversies.document_taxonomy" %>
<%@ page import="net.familylawandprobate.controversies.documents" %>
<%@ page import="net.familylawandprobate.controversies.matters" %>

<%@ include file="security.jspf" %>
<%
  if (!require_login()) return;
%>

<%! 
  private static final String S_TENANT_UUID = "tenant.uuid";
  private static final String S_USER_UUID = "user.uuid";
  private static final String S_USER_EMAIL = "user.email";
  private static final String CSRF_SESSION_KEY = "CSRF_TOKEN";

  private static String safe(String s){ return s == null ? "" : s; }

  private static String esc(String s){
    return safe(s).replace("&","&amp;")
                  .replace("<","&lt;")
                  .replace(">","&gt;")
                  .replace("\"","&quot;")
                  .replace("'","&#39;");
  }

  private static String enc(String s) {
    return URLEncoder.encode(safe(s), StandardCharsets.UTF_8);
  }

  private static String actorLabel(String actor, String currentActor) {
    String value = safe(actor).trim();
    if (value.isBlank()) return "another user";
    if (!safe(currentActor).trim().isBlank() && value.equalsIgnoreCase(safe(currentActor).trim())) return "you";
    return value;
  }

  private static boolean boolLike(String raw) {
    String v = safe(raw).trim().toLowerCase(Locale.ROOT);
    return "true".equals(v) || "1".equals(v) || "yes".equals(v) || "on".equals(v) || "y".equals(v);
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

  private static void logWarn(jakarta.servlet.ServletContext app, String message, Throwable ex) {
    if (app == null) return;
    if (ex == null) app.log("[documents] " + safe(message));
    else app.log("[documents] " + safe(message), ex);
  }

  private static String shortErr(Throwable ex) {
    if (ex == null) return "";
    String m = safe(ex.getMessage()).trim();
    return m.isBlank() ? ex.getClass().getSimpleName() : m;
  }

  private static String docAttrValue(document_attributes.AttributeRec def, Map<String,String> values) {
    if (def == null || values == null) return "";
    return safe(values.get(safe(def.key).trim()));
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
%>

<%
  String ctx = safe(request.getContextPath());
  String tenantUuid = safe((String)session.getAttribute(S_TENANT_UUID)).trim();
  if (tenantUuid.isBlank()) { response.sendRedirect(ctx + "/tenant_login.jsp"); return; }
  String currentActor = safe((String)session.getAttribute(S_USER_EMAIL)).trim();
  if (currentActor.isBlank()) currentActor = safe((String)session.getAttribute(S_USER_UUID)).trim();
  if (currentActor.isBlank()) currentActor = "unknown";

  matters matterStore = matters.defaultStore();
  documents docStore = documents.defaultStore();
  document_taxonomy taxonomyStore = document_taxonomy.defaultStore();
  document_attributes attrStore = document_attributes.defaultStore();
  document_fields docFieldStore = document_fields.defaultStore();

  try { matterStore.ensure(tenantUuid); } catch (Exception ex) { logWarn(application, "Unable to ensure matters for tenant " + tenantUuid + ": " + shortErr(ex), ex); }
  try { attrStore.ensure(tenantUuid); } catch (Exception ex) { logWarn(application, "Unable to ensure document attribute definitions for tenant " + tenantUuid + ": " + shortErr(ex), ex); }

  String csrfToken = csrfForRender(request);
  String message = null;
  String error = null;

  List<matters.MatterRec> allCases = new ArrayList<matters.MatterRec>();
  try { allCases = matterStore.listAll(tenantUuid); } catch (Exception ex) { logWarn(application, "Unable to list matters for tenant " + tenantUuid + ": " + shortErr(ex), ex); }

  List<matters.MatterRec> activeCases = new ArrayList<matters.MatterRec>();
  for (int i = 0; i < allCases.size(); i++) {
    matters.MatterRec c = allCases.get(i);
    if (c == null || c.trashed) continue;
    activeCases.add(c);
  }

  String caseUuid = safe(request.getParameter("case_uuid")).trim();
  if (caseUuid.isBlank() && !activeCases.isEmpty()) caseUuid = safe(activeCases.get(0).uuid);

  List<document_attributes.AttributeRec> allAttrDefs = new ArrayList<document_attributes.AttributeRec>();
  List<document_attributes.AttributeRec> enabledAttrDefs = new ArrayList<document_attributes.AttributeRec>();
  try {
    allAttrDefs = attrStore.listAll(tenantUuid);
    enabledAttrDefs = attrStore.listEnabled(tenantUuid);
  } catch (Exception ex) {
    logWarn(application, "Unable to load document attributes for tenant " + tenantUuid + ": " + shortErr(ex), ex);
    error = "Unable to load document attributes.";
  }

  LinkedHashMap<String, document_attributes.AttributeRec> defByKey = new LinkedHashMap<String, document_attributes.AttributeRec>();
  HashSet<String> attrKeySet = new HashSet<String>();
  for (int i = 0; i < allAttrDefs.size(); i++) {
    document_attributes.AttributeRec d = allAttrDefs.get(i);
    if (d == null) continue;
    String k = attrStore.normalizeKey(d.key);
    if (k.isBlank()) continue;
    defByKey.put(k, d);
    attrKeySet.add(k);
  }

  if ("POST".equalsIgnoreCase(request.getMethod())) {
    String action = safe(request.getParameter("action")).trim();
    caseUuid = safe(request.getParameter("case_uuid")).trim();

    try {
      if ("create_document".equals(action)) {
        LinkedHashMap<String, String> attrValues = new LinkedHashMap<String, String>();
        String[] attrKeys = request.getParameterValues("attr_def_key");
        String[] attrVals = request.getParameterValues("attr_def_value");
        int an = Math.max(attrKeys == null ? 0 : attrKeys.length, attrVals == null ? 0 : attrVals.length);
        for (int i = 0; i < an; i++) {
          String k = (attrKeys != null && i < attrKeys.length) ? attrStore.normalizeKey(attrKeys[i]) : "";
          if (k.isBlank() || !defByKey.containsKey(k)) continue;
          String v = (attrVals != null && i < attrVals.length) ? safe(attrVals[i]) : "";
          attrValues.put(k, v);
        }
        for (int i = 0; i < enabledAttrDefs.size(); i++) {
          document_attributes.AttributeRec def = enabledAttrDefs.get(i);
          if (def == null) continue;
          String k = attrStore.normalizeKey(def.key);
          if (k.isBlank()) continue;
          String v = normalizeAttrValueByType(def.dataType, attrValues.get(k));

          if ("select".equals(def.dataType)) {
            List<String> opts = attrStore.optionList(def.options);
            if (!opts.isEmpty()) {
              boolean match = false;
              for (int oi = 0; oi < opts.size(); oi++) {
                if (safe(opts.get(oi)).equals(v)) { match = true; break; }
              }
              if (!match) v = "";
            }
          }
          if (def.required && safe(v).trim().isBlank()) {
            throw new IllegalArgumentException("Required field missing: " + safe(def.label));
          }
          attrValues.put(k, v);
        }

        documents.DocumentRec rec = docStore.create(
            tenantUuid,
            caseUuid,
            request.getParameter("title"),
            request.getParameter("category"),
            request.getParameter("subcategory"),
            request.getParameter("status"),
            request.getParameter("owner"),
            request.getParameter("privilege_level"),
            request.getParameter("filed_on"),
            request.getParameter("external_reference"),
            request.getParameter("notes")
        );
        docFieldStore.write(tenantUuid, caseUuid, rec.uuid, attrValues);
        response.sendRedirect(ctx + "/documents.jsp?case_uuid=" + enc(caseUuid) + "&saved=1");
        return;
      }

      if ("check_out_document".equals(action)) {
        docStore.checkOut(tenantUuid, caseUuid, request.getParameter("uuid"), currentActor);
        response.sendRedirect(ctx + "/documents.jsp?case_uuid=" + enc(caseUuid) + "&saved=1&msg=checked_out");
        return;
      }

      if ("check_in_document".equals(action)) {
        docStore.checkIn(tenantUuid, caseUuid, request.getParameter("uuid"), currentActor);
        response.sendRedirect(ctx + "/documents.jsp?case_uuid=" + enc(caseUuid) + "&saved=1&msg=checked_in");
        return;
      }

      if ("save_document".equals(action)) {
        String docUuid = safe(request.getParameter("uuid")).trim();
        documents.DocumentRec existing = docStore.requireEditable(tenantUuid, caseUuid, docUuid, currentActor);
        if (existing == null) throw new IllegalStateException("Document not found.");

        documents.DocumentRec in = new documents.DocumentRec();
        in.uuid = docUuid;
        in.title = safe(request.getParameter("title"));
        in.category = safe(request.getParameter("category"));
        in.subcategory = safe(request.getParameter("subcategory"));
        in.status = safe(request.getParameter("status"));
        in.owner = safe(request.getParameter("owner"));
        in.privilegeLevel = safe(request.getParameter("privilege_level"));
        in.filedOn = safe(request.getParameter("filed_on"));
        in.externalReference = safe(request.getParameter("external_reference"));
        in.notes = safe(request.getParameter("notes"));
        docStore.update(tenantUuid, caseUuid, in, currentActor);

        LinkedHashMap<String, String> attrValues = new LinkedHashMap<String, String>();
        String[] attrKeys = request.getParameterValues("attr_def_key");
        String[] attrVals = request.getParameterValues("attr_def_value");
        int an = Math.max(attrKeys == null ? 0 : attrKeys.length, attrVals == null ? 0 : attrVals.length);
        for (int i = 0; i < an; i++) {
          String k = (attrKeys != null && i < attrKeys.length) ? attrStore.normalizeKey(attrKeys[i]) : "";
          if (k.isBlank() || !defByKey.containsKey(k)) continue;
          String v = (attrVals != null && i < attrVals.length) ? safe(attrVals[i]) : "";
          attrValues.put(k, v);
        }

        LinkedHashMap<String, String> existingValues = new LinkedHashMap<String, String>();
        try { existingValues.putAll(docFieldStore.read(tenantUuid, caseUuid, docUuid)); } catch (Exception ignored) {}
        for (String k : attrKeySet) {
          if (k == null || k.isBlank()) continue;
          if (!attrValues.containsKey(k) && existingValues.containsKey(k)) {
            attrValues.put(k, safe(existingValues.get(k)));
          }
        }

        for (int i = 0; i < enabledAttrDefs.size(); i++) {
          document_attributes.AttributeRec def = enabledAttrDefs.get(i);
          if (def == null) continue;
          String k = attrStore.normalizeKey(def.key);
          if (k.isBlank()) continue;
          String v = normalizeAttrValueByType(def.dataType, attrValues.get(k));

          if ("select".equals(def.dataType)) {
            List<String> opts = attrStore.optionList(def.options);
            if (!opts.isEmpty()) {
              boolean match = false;
              for (int oi = 0; oi < opts.size(); oi++) {
                if (safe(opts.get(oi)).equals(v)) { match = true; break; }
              }
              if (!match) v = "";
            }
          }
          if (def.required && safe(v).trim().isBlank()) {
            throw new IllegalArgumentException("Required field missing: " + safe(def.label));
          }
          attrValues.put(k, v);
        }

        docFieldStore.write(tenantUuid, caseUuid, docUuid, attrValues);
        response.sendRedirect(ctx + "/documents.jsp?case_uuid=" + enc(caseUuid) + "&saved=1");
        return;
      }

      if ("archive_document".equals(action)) {
        docStore.requireEditable(tenantUuid, caseUuid, request.getParameter("uuid"), currentActor);
        docStore.setTrashed(tenantUuid, caseUuid, request.getParameter("uuid"), true, currentActor);
        response.sendRedirect(ctx + "/documents.jsp?case_uuid=" + enc(caseUuid) + "&saved=1");
        return;
      }

      if ("restore_document".equals(action)) {
        docStore.requireEditable(tenantUuid, caseUuid, request.getParameter("uuid"), currentActor);
        docStore.setTrashed(tenantUuid, caseUuid, request.getParameter("uuid"), false, currentActor);
        response.sendRedirect(ctx + "/documents.jsp?case_uuid=" + enc(caseUuid) + "&saved=1");
        return;
      }
    } catch (Exception ex) {
      logWarn(application, "Document action failed (" + action + ") for tenant " + tenantUuid + ": " + shortErr(ex), ex);
      error = "Unable to save: " + safe(ex.getMessage());
    }
  }

  if ("1".equals(request.getParameter("saved"))) {
    String msgKey = safe(request.getParameter("msg")).trim();
    if ("checked_out".equals(msgKey)) message = "Document checked out for editing (auto-expires in 72 hours).";
    else if ("checked_in".equals(msgKey)) message = "Document checked in.";
    else message = "Document updated.";
  }

  if (!caseUuid.isBlank()) {
    try { docStore.ensure(tenantUuid, caseUuid); } catch (Exception ex) { logWarn(application, "Unable to ensure document store for case " + caseUuid + ": " + shortErr(ex), ex); }
  }

  document_taxonomy.Taxonomy tx = new document_taxonomy.Taxonomy();
  try { tx = taxonomyStore.read(tenantUuid); } catch (Exception ex) { logWarn(application, "Unable to read taxonomy for tenant " + tenantUuid + ": " + shortErr(ex), ex); }

  List<documents.DocumentRec> docs = new ArrayList<documents.DocumentRec>();
  if (!caseUuid.isBlank()) {
    try { docs = docStore.listAll(tenantUuid, caseUuid); } catch (Exception ex) { logWarn(application, "Unable to list documents for case " + caseUuid + ": " + shortErr(ex), ex); }
  }

  Map<String, Map<String, String>> docFieldCache = new HashMap<String, Map<String, String>>();
  for (int i = 0; i < docs.size(); i++) {
    documents.DocumentRec d = docs.get(i);
    if (d == null) continue;
    try {
      docFieldCache.put(safe(d.uuid), docFieldStore.read(tenantUuid, caseUuid, d.uuid));
    } catch (Exception ex) {
      logWarn(application, "Unable to read document fields for doc " + safe(d.uuid) + ": " + shortErr(ex), ex);
      docFieldCache.put(safe(d.uuid), new LinkedHashMap<String, String>());
    }
  }
%>

<jsp:include page="header.jsp" />

<section class="card">
  <h1 style="margin:0;">Case Documents</h1>
  <div class="meta">Legal-document index with lifecycle metadata, taxonomy, and tenant-defined document attributes.</div>
</section>

<section class="card" style="margin-top:12px;">
  <form class="form" method="get" action="<%= ctx %>/documents.jsp">
    <label>
      <span>Case</span>
      <select name="case_uuid" onchange="this.form.submit()">
        <% for (int i = 0; i < activeCases.size(); i++) {
             matters.MatterRec c = activeCases.get(i);
             if (c == null) continue;
             String cid = safe(c.uuid);
        %>
          <option value="<%= esc(cid) %>" <%= cid.equals(caseUuid) ? "selected" : "" %>><%= esc(safe(c.label)) %></option>
        <% } %>
      </select>
    </label>
  </form>

  <div class="actions" style="display:flex; gap:10px; margin-top:10px; flex-wrap:wrap;">
    <a class="btn btn-ghost" href="<%= ctx %>/attribute_editor.jsp">Attribute Editor</a>
    <a class="btn btn-ghost" href="<%= ctx %>/document_attributes.jsp">Manage Document Attributes</a>
    <a class="btn btn-ghost" href="<%= ctx %>/tenant_settings.jsp#document-taxonomy">Manage Taxonomy</a>
  </div>

  <% if (message != null) { %><div class="alert alert-ok" style="margin-top:10px;"><%= esc(message) %></div><% } %>
  <% if (error != null) { %><div class="alert alert-error" style="margin-top:10px;"><%= esc(error) %></div><% } %>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Add Document</h2>
  <form method="post" class="form" action="<%= ctx %>/documents.jsp">
    <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
    <input type="hidden" name="action" value="create_document" />
    <input type="hidden" name="case_uuid" value="<%= esc(caseUuid) %>" />

    <div class="grid grid-3">
      <label><span>Title</span><input type="text" name="title" required /></label>
      <label><span>Category</span>
        <select name="category"><option value=""></option><% for (String cat : tx.categories) { String v = safe(cat); %><option value="<%= esc(v) %>"><%= esc(v) %></option><% } %></select>
      </label>
      <label><span>Subcategory</span>
        <select name="subcategory"><option value=""></option><% for (String sub : tx.subcategories) { String v = safe(sub); %><option value="<%= esc(v) %>"><%= esc(v) %></option><% } %></select>
      </label>
    </div>

    <div class="grid grid-3">
      <label><span>Status</span>
        <select name="status"><option value=""></option><% for (String stat : tx.statuses) { String v = safe(stat); %><option value="<%= esc(v) %>"><%= esc(v) %></option><% } %></select>
      </label>
      <label><span>Owner</span><input type="text" name="owner" /></label>
      <label><span>Privilege</span><input type="text" name="privilege_level" placeholder="attorney_client/work_product/public" /></label>
    </div>

    <div class="grid grid-3">
      <label><span>Filed On</span><input type="date" name="filed_on" /></label>
      <label><span>External Ref</span><input type="text" name="external_reference"/></label>
      <label><span>Notes</span><input type="text" name="notes"/></label>
    </div>

    <% if (!enabledAttrDefs.isEmpty()) { %>
      <h4 style="margin:14px 0 8px 0;">Custom Attributes</h4>
      <div class="grid" style="display:grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap:12px;">
        <%
          for (int i = 0; i < enabledAttrDefs.size(); i++) {
            document_attributes.AttributeRec def = enabledAttrDefs.get(i);
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

    <button class="btn" type="submit">Create Document</button>
  </form>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Documents</h2>
  <div class="table-wrap">
    <table class="table">
      <thead>
        <tr>
          <th>Title</th>
          <th>Status</th>
          <th>Attributes</th>
          <th>Owner</th>
          <th>Updated</th>
          <th style="width:360px;">Actions</th>
        </tr>
      </thead>
      <tbody>
      <%
        if (docs.isEmpty()) {
      %>
        <tr><td colspan="6" class="muted">No documents yet.</td></tr>
      <%
        } else {
          for (int i = 0; i < docs.size(); i++) {
            documents.DocumentRec d = docs.get(i);
            if (d == null) continue;
            boolean docReadOnly = documents.isReadOnly(d);
            boolean checkoutActive = documents.isCheckoutActive(d);
            boolean checkedOutByMe = documents.isCheckedOutBy(d, currentActor);
            boolean checkedOutByOther = documents.isCheckedOutByOther(d, currentActor);
            String checkoutBy = documents.checkoutBy(d);
            String checkoutExpiresAt = documents.checkoutExpiresAt(d);
            String checkoutMessage = documents.checkoutMessage(d, currentActor);
            boolean docLockedForEdit = docReadOnly || checkedOutByOther;
            String editBlockReason = "";
            if (docReadOnly) editBlockReason = "Synced from Clio. Edit in Clio.";
            else if (checkedOutByOther) editBlockReason = checkoutMessage;
            String editDisabledAttr = docLockedForEdit ? "disabled title=\"" + esc(editBlockReason) + "\"" : "";
            String lockDisabledAttr = checkedOutByOther ? "disabled title=\"" + esc(checkoutMessage) + "\"" : "";
            String did = safe(d.uuid);
            Map<String,String> docVals = docFieldCache.get(did);
            if (docVals == null) docVals = new LinkedHashMap<String, String>();
            String editRowId = "edit_doc_" + i;
      %>
        <tr>
          <td>
            <strong><%= esc(safe(d.title)) %></strong>
            <div class="muted"><%= d.trashed ? "Archived" : "Active" %></div>
            <% if (docReadOnly) { %>
              <div class="muted">Synced from Clio (read-only)</div>
            <% } %>
            <% if (checkoutActive) { %>
              <div class="muted">
                Checked out by <%= esc(actorLabel(checkoutBy, currentActor)) %><% if (!safe(checkoutExpiresAt).isBlank()) { %> until <%= esc(checkoutExpiresAt) %><% } %>
              </div>
            <% } %>
          </td>
          <td><%= esc(safe(d.status)) %></td>
          <td>
            <%
              int shown = 0;
              for (int ai = 0; ai < enabledAttrDefs.size(); ai++) {
                document_attributes.AttributeRec def = enabledAttrDefs.get(ai);
                if (def == null) continue;
                String val = docAttrValue(def, docVals);
                if (safe(val).trim().isBlank()) continue;
            %>
              <div><span class="muted"><%= esc(safe(def.label)) %>:</span> <%= esc(val) %></div>
            <%
                shown++;
                if (shown >= 3) break;
              }
              if (shown == 0) {
            %>
              <span class="muted">No values</span>
            <% } %>
          </td>
          <td><%= esc(safe(d.owner)) %></td>
          <td><%= esc(safe(d.updatedAt)) %></td>
          <td>
            <button class="btn btn-ghost" type="button" onclick="toggleDocEdit('<%= editRowId %>')" <%= editDisabledAttr %>>Edit</button>
            <a class="btn btn-ghost" href="<%= ctx %>/parts.jsp?case_uuid=<%= enc(caseUuid) %>&doc_uuid=<%= enc(did) %>">Parts</a>
            <a class="btn btn-ghost" href="<%= ctx %>/document_preview.jsp?case_uuid=<%= enc(caseUuid) %>&doc_uuid=<%= enc(did) %>">Preview</a>
            <% if (!docReadOnly) { %>
              <% if (checkoutActive && checkedOutByMe) { %>
                <form method="post" action="<%= ctx %>/documents.jsp" style="display:inline;">
                  <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
                  <input type="hidden" name="action" value="check_in_document" />
                  <input type="hidden" name="case_uuid" value="<%= esc(caseUuid) %>" />
                  <input type="hidden" name="uuid" value="<%= esc(did) %>" />
                  <button class="btn btn-ghost" type="submit">Check In</button>
                </form>
              <% } else if (!checkoutActive) { %>
                <form method="post" action="<%= ctx %>/documents.jsp" style="display:inline;">
                  <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
                  <input type="hidden" name="action" value="check_out_document" />
                  <input type="hidden" name="case_uuid" value="<%= esc(caseUuid) %>" />
                  <input type="hidden" name="uuid" value="<%= esc(did) %>" />
                  <button class="btn btn-ghost" type="submit">Check Out</button>
                </form>
              <% } else if (checkedOutByOther) { %>
                <button class="btn btn-ghost" type="button" <%= lockDisabledAttr %>>Check Out</button>
              <% } %>
            <% } %>
            <% if (!d.trashed && !docReadOnly) { %>
              <form method="post" action="<%= ctx %>/documents.jsp" style="display:inline;">
                <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
                <input type="hidden" name="action" value="archive_document" />
                <input type="hidden" name="case_uuid" value="<%= esc(caseUuid) %>" />
                <input type="hidden" name="uuid" value="<%= esc(did) %>" />
                <button class="btn btn-ghost" type="submit" onclick="return confirm('Archive this document?');" <%= lockDisabledAttr %>>Archive</button>
              </form>
            <% } else if (d.trashed && !docReadOnly) { %>
              <form method="post" action="<%= ctx %>/documents.jsp" style="display:inline;">
                <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
                <input type="hidden" name="action" value="restore_document" />
                <input type="hidden" name="case_uuid" value="<%= esc(caseUuid) %>" />
                <input type="hidden" name="uuid" value="<%= esc(did) %>" />
                <button class="btn" type="submit" <%= lockDisabledAttr %>>Restore</button>
              </form>
            <% } %>
          </td>
        </tr>

        <tr id="<%= editRowId %>" style="display:none;">
          <td colspan="6">
            <div class="card" style="margin:8px 0; padding:14px; background:rgba(0,0,0,0.02);">
              <form class="form" method="post" action="<%= ctx %>/documents.jsp">
                <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
                <input type="hidden" name="action" value="save_document" />
                <input type="hidden" name="case_uuid" value="<%= esc(caseUuid) %>" />
                <input type="hidden" name="uuid" value="<%= esc(did) %>" />
                <% if (docReadOnly) { %>
                  <div class="alert alert-error" style="margin-bottom:10px;">This document is synced from Clio and is read-only. Edit it in Clio.</div>
                <% } %>
                <% if (checkedOutByOther) { %>
                  <div class="alert alert-error" style="margin-bottom:10px;"><%= esc(checkoutMessage) %> Checkout lock expires automatically after 72 hours.</div>
                <% } %>

                <div class="grid grid-3">
                  <label><span>Title</span><input type="text" name="title" value="<%= esc(safe(d.title)) %>" required <%= docLockedForEdit ? "disabled" : "" %> /></label>
                  <label><span>Category</span>
                    <select name="category" <%= docLockedForEdit ? "disabled" : "" %>><option value=""></option><% for (String cat : tx.categories) { String v = safe(cat); %><option value="<%= esc(v) %>" <%= v.equals(safe(d.category)) ? "selected" : "" %>><%= esc(v) %></option><% } %></select>
                  </label>
                  <label><span>Subcategory</span>
                    <select name="subcategory" <%= docLockedForEdit ? "disabled" : "" %>><option value=""></option><% for (String sub : tx.subcategories) { String v = safe(sub); %><option value="<%= esc(v) %>" <%= v.equals(safe(d.subcategory)) ? "selected" : "" %>><%= esc(v) %></option><% } %></select>
                  </label>
                </div>

                <div class="grid grid-3">
                  <label><span>Status</span>
                    <select name="status" <%= docLockedForEdit ? "disabled" : "" %>><option value=""></option><% for (String stat : tx.statuses) { String v = safe(stat); %><option value="<%= esc(v) %>" <%= v.equals(safe(d.status)) ? "selected" : "" %>><%= esc(v) %></option><% } %></select>
                  </label>
                  <label><span>Owner</span><input type="text" name="owner" value="<%= esc(safe(d.owner)) %>" <%= docLockedForEdit ? "disabled" : "" %> /></label>
                  <label><span>Privilege</span><input type="text" name="privilege_level" value="<%= esc(safe(d.privilegeLevel)) %>" <%= docLockedForEdit ? "disabled" : "" %> /></label>
                </div>

                <div class="grid grid-3">
                  <label><span>Filed On</span><input type="date" name="filed_on" value="<%= esc(safe(d.filedOn)) %>" <%= docLockedForEdit ? "disabled" : "" %> /></label>
                  <label><span>External Ref</span><input type="text" name="external_reference" value="<%= esc(safe(d.externalReference)) %>" <%= docLockedForEdit ? "disabled" : "" %> /></label>
                  <label><span>Notes</span><input type="text" name="notes" value="<%= esc(safe(d.notes)) %>" <%= docLockedForEdit ? "disabled" : "" %> /></label>
                </div>

                <% if (!enabledAttrDefs.isEmpty()) { %>
                  <h4 style="margin:14px 0 8px 0;">Custom Attributes</h4>
                  <div class="grid" style="display:grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap:12px;">
                    <%
                      for (int ai = 0; ai < enabledAttrDefs.size(); ai++) {
                        document_attributes.AttributeRec def = enabledAttrDefs.get(ai);
                        if (def == null) continue;
                        String key = attrStore.normalizeKey(def.key);
                        if (key.isBlank()) continue;
                        String val = safe(docVals.get(key));
                        List<String> opts = "select".equals(def.dataType) ? attrStore.optionList(def.options) : new ArrayList<String>();
                    %>
                      <label>
                        <span><%= esc(safe(def.label)) %><%= def.required ? " *" : "" %></span>
                        <input type="hidden" name="attr_def_key" value="<%= esc(key) %>" />
                        <% if ("textarea".equals(def.dataType)) { %>
                          <textarea name="attr_def_value" rows="2" <%= docLockedForEdit ? "disabled" : "" %>><%= esc(val) %></textarea>
                        <% } else if ("date".equals(def.dataType)) { %>
                          <input type="date" name="attr_def_value" value="<%= esc(val) %>" <%= docLockedForEdit ? "disabled" : "" %> />
                        <% } else if ("datetime".equals(def.dataType)) { %>
                          <input type="datetime-local" name="attr_def_value" value="<%= esc(val) %>" <%= docLockedForEdit ? "disabled" : "" %> />
                        <% } else if ("time".equals(def.dataType)) { %>
                          <input type="time" name="attr_def_value" value="<%= esc(val) %>" <%= docLockedForEdit ? "disabled" : "" %> />
                        <% } else if ("number".equals(def.dataType)) { %>
                          <input type="number" name="attr_def_value" value="<%= esc(val) %>" <%= docLockedForEdit ? "disabled" : "" %> />
                        <% } else if ("boolean".equals(def.dataType)) { %>
                          <select name="attr_def_value" <%= docLockedForEdit ? "disabled" : "" %>>
                            <option value=""></option>
                            <option value="true" <%= "true".equals(normalizeAttrValueByType("boolean", val)) ? "selected" : "" %>>Yes</option>
                            <option value="false" <%= "false".equals(normalizeAttrValueByType("boolean", val)) ? "selected" : "" %>>No</option>
                          </select>
                        <% } else if ("email".equals(def.dataType)) { %>
                          <input type="email" name="attr_def_value" value="<%= esc(val) %>" <%= docLockedForEdit ? "disabled" : "" %> />
                        <% } else if ("phone".equals(def.dataType)) { %>
                          <input type="tel" name="attr_def_value" value="<%= esc(val) %>" <%= docLockedForEdit ? "disabled" : "" %> />
                        <% } else if ("url".equals(def.dataType)) { %>
                          <input type="url" name="attr_def_value" value="<%= esc(val) %>" <%= docLockedForEdit ? "disabled" : "" %> />
                        <% } else if ("select".equals(def.dataType) && !opts.isEmpty()) { %>
                          <select name="attr_def_value" <%= docLockedForEdit ? "disabled" : "" %>>
                            <option value=""></option>
                            <% for (int oi = 0; oi < opts.size(); oi++) { String ov = safe(opts.get(oi)); %>
                              <option value="<%= esc(ov) %>" <%= ov.equals(val) ? "selected" : "" %>><%= esc(ov) %></option>
                            <% } %>
                          </select>
                        <% } else { %>
                          <input type="text" name="attr_def_value" value="<%= esc(val) %>" <%= docLockedForEdit ? "disabled" : "" %> />
                        <% } %>
                      </label>
                    <% } %>
                  </div>
                <% } %>

                <div class="actions" style="display:flex; gap:10px; margin-top:10px;">
                  <button class="btn" type="submit" <%= docLockedForEdit ? "disabled" : "" %>>Save Document</button>
                  <button class="btn btn-ghost" type="button" onclick="toggleDocEdit('<%= editRowId %>')">Cancel</button>
                </div>
              </form>
            </div>
          </td>
        </tr>
      <%
          }
        }
      %>
      </tbody>
    </table>
  </div>
</section>

<script>
  function toggleDocEdit(id) {
    var row = document.getElementById(id);
    if (!row) return;
    row.style.display = (row.style.display === "none" || row.style.display === "") ? "table-row" : "none";
  }
</script>

<jsp:include page="footer.jsp" />
