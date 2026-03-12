<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>

<%@ page import="java.util.LinkedHashMap" %>
<%@ page import="java.util.Map" %>

<%@ page import="net.familylawandprobate.controversies.assembly_identity_tokens" %>
<%@ page import="net.familylawandprobate.controversies.profile_assets" %>
<%@ page import="net.familylawandprobate.controversies.tenant_fields" %>

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

  private static void logInfo(jakarta.servlet.ServletContext app, String message) {
    if (app == null) return;
    app.log("[tenant_fields] " + safe(message));
  }

  private static void logWarn(jakarta.servlet.ServletContext app, String message, Throwable ex) {
    if (app == null) return;
    if (ex == null) app.log("[tenant_fields] " + safe(message));
    else app.log("[tenant_fields] " + safe(message), ex);
  }

  private static String firstValue(Map<String,String> map, String... keys) {
    if (map == null || keys == null) return "";
    for (String key : keys) {
      String k = safe(key).trim();
      if (k.isBlank()) continue;
      String v = safe(map.get(k)).trim();
      if (!v.isBlank()) return v;
    }
    return "";
  }

  private static boolean isCanonicalTenantIdentityKey(tenant_fields store, String key) {
    if (store == null) return false;
    String k = store.normalizeKey(key);
    return "mailing_address".equals(k)
        || "tenant_mailing_address".equals(k)
        || "firm_mailing_address".equals(k)
        || "physical_address".equals(k)
        || "tenant_physical_address".equals(k)
        || "firm_physical_address".equals(k)
        || "address".equals(k)
        || "phone".equals(k)
        || "tenant_phone".equals(k)
        || "firm_phone".equals(k)
        || "fax".equals(k)
        || "tenant_fax".equals(k)
        || "firm_fax".equals(k)
        || "email".equals(k)
        || "tenant_email".equals(k)
        || "firm_email".equals(k);
  }
%>

<%
  String ctx = request.getContextPath();
  if (ctx == null) ctx = "";

  String tenantUuid = safe((String)session.getAttribute(S_TENANT_UUID)).trim();
  String tenantLabel = safe((String)session.getAttribute(S_TENANT_LABEL)).trim();
  if (tenantUuid.isBlank()) {
    response.sendRedirect(ctx + "/tenant_login.jsp");
    return;
  }

  tenant_fields store = tenant_fields.defaultStore();
  profile_assets identityStore = profile_assets.defaultStore();

  String csrfToken = csrfForRender(request);
  String message = null;
  String error = null;

  if ("POST".equalsIgnoreCase(request.getMethod())) {
    String action = safe(request.getParameter("action")).trim();

    if ("save_fields".equalsIgnoreCase(action)) {
      try {
        String[] keys = request.getParameterValues("field_key");
        String[] vals = request.getParameterValues("field_value");

        LinkedHashMap<String,String> in = new LinkedHashMap<String,String>();
        int n = Math.max(keys == null ? 0 : keys.length, vals == null ? 0 : vals.length);
        for (int i = 0; i < n; i++) {
          String k = (keys != null && i < keys.length) ? safe(keys[i]) : "";
          String v = (vals != null && i < vals.length) ? safe(vals[i]) : "";
          in.put(k, v);
        }
        in.put("mailing_address", safe(request.getParameter("tenant_mailing_address")));
        in.put("physical_address", safe(request.getParameter("tenant_physical_address")));
        in.put("phone", safe(request.getParameter("tenant_phone")));
        in.put("fax", safe(request.getParameter("tenant_fax")));
        in.put("email", safe(request.getParameter("tenant_email")));

        store.write(tenantUuid, in);
        logInfo(application, "Updated tenant fields for tenant " + tenantUuid + " by user " + safe((String)session.getAttribute("user.uuid")) + "; keys=" + in.size());
        response.sendRedirect(ctx + "/tenant_fields.jsp?saved=1");
        return;
      } catch (Exception ex) {
        error = "Unable to save tenant fields: " + safe(ex.getMessage());
        logWarn(application, "Failed saving tenant fields for tenant " + tenantUuid + ": " + safe(ex.getMessage()), ex);
      }
    }
  }

  if ("1".equals(request.getParameter("saved"))) message = "Tenant replacement fields saved.";
  String assetStatus = safe(request.getParameter("asset_status")).trim();
  String assetError = safe(request.getParameter("asset_error")).trim();
  if (!assetError.isBlank()) error = assetError;
  else if ("tenant_logo_saved".equalsIgnoreCase(assetStatus)) message = "Tenant logo saved.";
  else if ("tenant_logo_cleared".equalsIgnoreCase(assetStatus)) message = "Tenant logo removed.";

  LinkedHashMap<String,String> kv = new LinkedHashMap<String,String>();
  try { kv.putAll(store.read(tenantUuid)); } catch (Exception ex) { logWarn(application, "Failed reading tenant fields for tenant " + tenantUuid + ": " + safe(ex.getMessage()), ex); }
  String mailingAddress = firstValue(kv, "mailing_address", "tenant_mailing_address", "firm_mailing_address");
  String physicalAddress = firstValue(kv, "physical_address", "tenant_physical_address", "firm_physical_address", "address");
  String tenantPhone = firstValue(kv, "phone", "tenant_phone", "firm_phone");
  String tenantFax = firstValue(kv, "fax", "tenant_fax", "firm_fax");
  String tenantEmail = firstValue(kv, "email", "tenant_email", "firm_email");

  LinkedHashMap<String,String> customKv = new LinkedHashMap<String,String>();
  for (Map.Entry<String,String> e : kv.entrySet()) {
    if (e == null) continue;
    if (isCanonicalTenantIdentityKey(store, e.getKey())) continue;
    customKv.put(safe(e.getKey()), safe(e.getValue()));
  }

  profile_assets.AssetRec tenantLogo = null;
  try { tenantLogo = identityStore.readTenantLogo(tenantUuid); } catch (Exception ignored) {}
  String tenantLogoUrl = assembly_identity_tokens.tenantLogoUrl(ctx);

  int rows = Math.max(6, customKv.size() + 1);
%>

<jsp:include page="header.jsp" />

<section class="card">
  <div class="section-head">
    <div>
      <h1 style="margin:0;">Tenant Fields</h1>
      <div class="meta">Global replacement keys shared by all users and cases in this tenant.</div>
      <% if (!tenantLabel.isBlank()) { %>
        <div class="meta" style="margin-top:4px;">Tenant: <strong><%= esc(tenantLabel) %></strong></div>
      <% } %>
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
  <h2 style="margin-top:0;">Tenant Identity</h2>
  <div class="meta" style="margin-bottom:10px;">
    Configure firm logo + contact details used by canonical tokens:
    <code>{{tenant.logo_url}}</code>,
    <code>{{tenant.mailing_address}}</code>,
    <code>{{tenant.physical_address}}</code>,
    <code>{{tenant.phone}}</code>,
    <code>{{tenant.fax}}</code>,
    <code>{{tenant.email}}</code>.
  </div>

  <div style="display:grid; grid-template-columns: minmax(220px, 320px) minmax(0, 1fr); gap:12px; align-items:start;">
    <div style="border:1px solid var(--line); border-radius:12px; padding:12px; min-height:220px; display:flex; align-items:center; justify-content:center; background:var(--surface-2);">
      <% if (tenantLogo != null) { %>
        <img src="<%= esc(tenantLogoUrl) %>&t=<%= System.currentTimeMillis() %>" alt="Tenant logo" style="max-width:100%; max-height:200px; object-fit:contain;" />
      <% } else { %>
        <div class="meta">No tenant logo uploaded.</div>
      <% } %>
    </div>
    <div style="display:grid; gap:10px;">
      <% if (tenantLogo != null) { %>
        <div class="meta">
          Current logo:
          <strong><%= esc(safe(tenantLogo.fileName)) %></strong>
          (<%= esc(safe(tenantLogo.mimeType)) %>, <%= tenantLogo.sizeBytes %> bytes)
        </div>
      <% } %>
      <form class="form" method="post" action="<%= ctx %>/profile_assets" enctype="multipart/form-data">
        <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
        <input type="hidden" name="action" value="upload_tenant_logo" />
        <input type="hidden" name="next" value="/tenant_fields.jsp" />
        <label>
          <span>Upload tenant logo (PNG/JPG/GIF, max <%= profile_assets.MAX_IMAGE_BYTES %> bytes)</span>
          <input type="file" name="file" accept="image/png,image/jpeg,image/gif" required />
        </label>
        <div class="actions"><button class="btn" type="submit">Upload Logo</button></div>
      </form>

      <form class="form" method="post" action="<%= ctx %>/profile_assets">
        <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
        <input type="hidden" name="action" value="import_tenant_logo_url" />
        <input type="hidden" name="next" value="/tenant_fields.jsp" />
        <label>
          <span>Or import logo from URL (Controversies downloads and stores it)</span>
          <input type="url" name="source_url" placeholder="https://example.com/logo.png" required />
        </label>
        <div class="actions"><button class="btn btn-ghost" type="submit">Import Logo URL</button></div>
      </form>

      <form class="form" method="post" action="<%= ctx %>/profile_assets">
        <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
        <input type="hidden" name="action" value="clear_tenant_logo" />
        <input type="hidden" name="next" value="/tenant_fields.jsp" />
        <button class="btn btn-ghost" type="submit" <%= tenantLogo == null ? "disabled" : "" %>>Remove Logo</button>
      </form>
    </div>
  </div>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Global Key/Value Store</h2>
  <div class="meta" style="margin-bottom:10px;">
    Use keys like <code>firm_name</code>, <code>firm_phone</code>, <code>attorney_name</code>. 
    Templates can reference tenant-specific keys as <code>{{tenant.firm_name}}</code>.
  </div>

  <form class="form" method="post" action="<%= ctx %>/tenant_fields.jsp">
    <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
    <input type="hidden" name="action" value="save_fields" />

    <div class="grid" style="display:grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap:12px; margin-bottom:12px;">
      <label>
        <span>Mailing Address Token (<code>{{tenant.mailing_address}}</code>)</span>
        <textarea name="tenant_mailing_address" rows="3" placeholder="P.O. Box or mailing address"><%= esc(mailingAddress) %></textarea>
      </label>
      <label>
        <span>Physical Address Token (<code>{{tenant.physical_address}}</code>)</span>
        <textarea name="tenant_physical_address" rows="3" placeholder="Street address"><%= esc(physicalAddress) %></textarea>
      </label>
      <label>
        <span>Phone Token (<code>{{tenant.phone}}</code>)</span>
        <input type="text" name="tenant_phone" value="<%= esc(tenantPhone) %>" placeholder="(555) 123-4567" />
      </label>
      <label>
        <span>Fax Token (<code>{{tenant.fax}}</code>)</span>
        <input type="text" name="tenant_fax" value="<%= esc(tenantFax) %>" placeholder="(555) 123-0000" />
      </label>
      <label>
        <span>Email Token (<code>{{tenant.email}}</code>)</span>
        <input type="text" name="tenant_email" value="<%= esc(tenantEmail) %>" placeholder="intake@firm.com" />
      </label>
    </div>

    <div class="table-wrap">
      <table class="table">
        <thead>
          <tr>
            <th style="width:35%;">Key</th>
            <th>Value</th>
            <th style="width:96px;">&nbsp;</th>
          </tr>
        </thead>
        <tbody id="tenantFieldRows">
        <%
          int idx = 0;
          for (Map.Entry<String,String> e : customKv.entrySet()) {
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
      <button type="button" class="btn btn-ghost" onclick="addFieldRow()">Add Field</button>
      <button type="submit" class="btn">Save Tenant Fields</button>
    </div>
  </form>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Merge Precedence</h2>
  <div class="meta">
    During assembly, values are merged in this order:
    <strong>Tenant fields first</strong>, then <strong>case fields</strong>.
    If both define the same key, the case value wins for <code>{{kv.key}}</code> and <code>{{key}}</code>.
  </div>
</section>

<script>
  function addFieldRow() {
    var tbody = document.getElementById("tenantFieldRows");
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
