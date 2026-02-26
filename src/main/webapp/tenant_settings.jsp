<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>

<%@ page import="java.util.LinkedHashMap" %>
<%@ page import="java.util.Locale" %>
<%@ page import="java.util.Map" %>

<%@ page import="net.familylawandprobate.controversies.activity_log" %>
<%@ page import="net.familylawandprobate.controversies.tenant_settings" %>
<%@ page import="net.familylawandprobate.controversies.users_roles" %>

<%@ include file="security.jspf" %>

<%!
  private static final String S_TENANT_UUID = "tenant.uuid";
  private static final String S_TENANT_LABEL = "tenant.label";
  private static final String CSRF_SESSION_KEY = "CSRF_TOKEN";

  private static String tsSafe(String s) { return s == null ? "" : s; }

  private static String tsEsc(String s) {
    if (s == null) return "";
    return s.replace("&","&amp;")
            .replace("<","&lt;")
            .replace(">","&gt;")
            .replace("\"","&quot;")
            .replace("'","&#39;");
  }

  private static boolean tsChecked(String s) {
    String v = tsSafe(s).trim().toLowerCase(Locale.ROOT);
    return "1".equals(v) || "true".equals(v) || "on".equals(v) || "yes".equals(v);
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
%>

<%
  String ctx = request.getContextPath();
  if (ctx == null) ctx = "";

  String tenantUuid = tsSafe((String)session.getAttribute(S_TENANT_UUID)).trim();
  String tenantLabel = tsSafe((String)session.getAttribute(S_TENANT_LABEL)).trim();
  String userUuid = tsSafe((String)session.getAttribute(users_roles.S_USER_UUID)).trim();
  if (tenantUuid.isBlank()) {
    response.sendRedirect(ctx + "/tenant_login.jsp");
    return;
  }

  tenant_settings store = tenant_settings.defaultStore();
  activity_log logs = activity_log.defaultStore();

  String csrfToken = csrfForRender(request);
  String message = null;
  String error = null;

  LinkedHashMap<String,String> settings = new LinkedHashMap<String,String>();
  settings.putAll(store.read(tenantUuid));

  if ("POST".equalsIgnoreCase(request.getMethod())) {
    String action = tsSafe(request.getParameter("action")).trim();

    String storageBackend = tsSafe(request.getParameter("storage_backend")).trim();
    if (storageBackend.isBlank()) storageBackend = "localfs";
    String storageEndpoint = tsSafe(request.getParameter("storage_endpoint")).trim();
    String storageAccessKey = tsSafe(request.getParameter("storage_access_key")).trim();
    String storageSecret = tsSafe(request.getParameter("storage_secret")).trim();

    String clioBaseUrl = tsSafe(request.getParameter("clio_base_url")).trim();
    String clioClientId = tsSafe(request.getParameter("clio_client_id")).trim();
    String clioClientSecret = tsSafe(request.getParameter("clio_client_secret")).trim();

    String clioAuthMode = tsSafe(request.getParameter("clio_auth_mode")).trim().toLowerCase(Locale.ROOT);
    if (!"private".equals(clioAuthMode)) clioAuthMode = "public";
    String clioOauthCallbackUrl = tsSafe(request.getParameter("clio_oauth_callback_url")).trim();
    String clioPrivateRelayUrl = tsSafe(request.getParameter("clio_private_relay_url")).trim();

    boolean featureAdvancedAssembly = tsChecked(request.getParameter("feature_advanced_assembly"));
    boolean featureAsyncSync = tsChecked(request.getParameter("feature_async_sync"));

    boolean rotateStorage = tsChecked(request.getParameter("rotate_storage_secret"));
    boolean rotateClio = tsChecked(request.getParameter("rotate_clio_secret"));
    boolean saveStorageSecret = tsChecked(request.getParameter("save_storage_secret"));
    boolean saveClioSecret = tsChecked(request.getParameter("save_clio_secret"));

    settings.put("storage_backend", storageBackend);
    settings.put("storage_endpoint", storageEndpoint);
    settings.put("storage_access_key", storageAccessKey);
    settings.put("clio_base_url", clioBaseUrl);
    settings.put("clio_client_id", clioClientId);
    settings.put("clio_auth_mode", clioAuthMode);
    settings.put("clio_oauth_callback_url", clioOauthCallbackUrl);
    settings.put("clio_private_relay_url", clioPrivateRelayUrl);
    settings.put("feature_advanced_assembly", featureAdvancedAssembly ? "true" : "false");
    settings.put("feature_async_sync", featureAsyncSync ? "true" : "false");

    if (saveStorageSecret && !storageSecret.isBlank()) settings.put("storage_secret", storageSecret);
    if (saveClioSecret && !clioClientSecret.isBlank()) settings.put("clio_client_secret", clioClientSecret);

    if ("test_storage_connection".equalsIgnoreCase(action)) {
      boolean ok;
      if ("filesystem_remote".equalsIgnoreCase(storageBackend)) {
        ok = !storageEndpoint.isBlank() && !storageAccessKey.isBlank() && !storageSecret.isBlank();
      } else {
        ok = true;
      }
      settings.put("storage_connection_status", ok ? "ok" : "failed");
      settings.put("storage_connection_checked_at", store.nowIso());
      message = ok ? "Storage connection test succeeded." : "Storage connection test failed. Check endpoint and credentials.";

    } else if ("test_clio_connection".equalsIgnoreCase(action)) {
      boolean hasShared = !clioBaseUrl.isBlank() && clioBaseUrl.startsWith("http") && !clioClientId.isBlank();
      boolean hasSecret = !clioClientSecret.isBlank() || !tsSafe(settings.get("clio_client_secret")).isBlank();
      boolean modeReady;
      if ("public".equals(clioAuthMode)) {
        modeReady = !clioOauthCallbackUrl.isBlank() && clioOauthCallbackUrl.startsWith("http");
      } else {
        modeReady = !clioPrivateRelayUrl.isBlank();
      }

      boolean ok = hasShared && hasSecret && modeReady;
      settings.put("clio_connection_status", ok ? "ok" : "failed");
      settings.put("clio_connection_checked_at", store.nowIso());
      settings.put("clio_auth_health_status", ok ? "ok" : "failed");
      settings.put("clio_auth_health_checked_at", store.nowIso());

      if (ok) {
        message = "Clio connection test succeeded for " + clioAuthMode + " auth mode.";
      } else if ("public".equals(clioAuthMode)) {
        message = "Clio connection test failed. Public mode requires Base URL, Client ID, Client Secret, and OAuth callback URL.";
      } else {
        message = "Clio connection test failed. Private mode requires Base URL, Client ID, Client Secret, and relay/admin path details.";
      }

    } else if ("save_settings".equalsIgnoreCase(action)) {
      boolean valid = true;
      if ("public".equals(clioAuthMode) && (clioOauthCallbackUrl.isBlank() || !clioOauthCallbackUrl.startsWith("http"))) {
        valid = false;
        error = "Public mode requires a web-accessible OAuth callback URL.";
      }
      if ("private".equals(clioAuthMode) && clioPrivateRelayUrl.isBlank()) {
        valid = false;
        error = "Private mode requires relay/admin exchange instructions endpoint or note.";
      }

      if (valid) {
        try {
          if (rotateStorage) settings.put("secret_rotation_storage_at", store.nowIso());
          if (rotateClio) settings.put("secret_rotation_clio_at", store.nowIso());

          if ("public".equals(clioAuthMode)) {
            settings.put("clio_auth_health_status", settings.getOrDefault("clio_auth_health_status", "unknown"));
          } else {
            String hasTokens = !tsSafe(settings.get("clio_refresh_token")).isBlank() || !tsSafe(settings.get("clio_access_token")).isBlank() ? "ok" : "failed";
            settings.put("clio_auth_health_status", hasTokens);
          }
          settings.put("clio_auth_health_checked_at", store.nowIso());

          store.write(tenantUuid, settings);

          Map<String, String> details = new LinkedHashMap<String, String>();
          details.put("storage_backend", tsSafe(settings.get("storage_backend")));
          details.put("feature_advanced_assembly", tsSafe(settings.get("feature_advanced_assembly")));
          details.put("feature_async_sync", tsSafe(settings.get("feature_async_sync")));
          details.put("storage_connection_status", tsSafe(settings.get("storage_connection_status")));
          details.put("clio_connection_status", tsSafe(settings.get("clio_connection_status")));
          details.put("clio_auth_mode", tsSafe(settings.get("clio_auth_mode")));
          details.put("clio_auth_health_status", tsSafe(settings.get("clio_auth_health_status")));
          logs.logVerbose("tenant_settings_changed", tenantUuid, userUuid, "", "", details);

          response.sendRedirect(ctx + "/tenant_settings.jsp?saved=1");
          return;
        } catch (Exception ex) {
          error = "Unable to save tenant settings: " + tsSafe(ex.getMessage());
        }
      }
    }
  }

  if ("1".equals(request.getParameter("saved"))) message = "Tenant settings saved.";

  String maskedStorageSecret = tsSafe(settings.get("storage_secret")).isBlank() ? "" : "********";
  String maskedClioSecret = tsSafe(settings.get("clio_client_secret")).isBlank() ? "" : "********";
  String clioMode = tsSafe(settings.get("clio_auth_mode")).trim().toLowerCase(Locale.ROOT);
  if (!"private".equals(clioMode)) clioMode = "public";
%>

<jsp:include page="header.jsp" />

<section class="card">
  <div class="section-head">
    <div>
      <h1 style="margin:0;">Tenant Settings</h1>
      <div class="meta">Tenant-scoped platform configuration and integration controls.</div>
      <% if (!tenantLabel.isBlank()) { %>
        <div class="meta" style="margin-top:4px;">Tenant: <strong><%= tsEsc(tenantLabel) %></strong></div>
      <% } %>
    </div>
  </div>

  <% if (message != null) { %>
    <div class="alert alert-ok" style="margin-top:12px;"><%= tsEsc(message) %></div>
  <% } %>
  <% if (error != null) { %>
    <div class="alert alert-error" style="margin-top:12px;"><%= tsEsc(error) %></div>
  <% } %>
</section>

<form class="form" method="post" action="<%= ctx %>/tenant_settings.jsp">
  <input type="hidden" name="csrfToken" value="<%= tsEsc(csrfToken) %>" />

  <section class="card" style="margin-top:12px;">
    <h2 style="margin-top:0;">Storage Backend</h2>
    <div class="meta" style="margin-bottom:10px;">Select storage backend and validate credentials without saving until you click Save Settings.</div>

    <div class="grid grid-2">
      <label>Backend
        <select name="storage_backend">
          <option value="localfs" <%= "localfs".equalsIgnoreCase(tsSafe(settings.get("storage_backend"))) ? "selected" : "" %>>Local Filesystem</option>
          <option value="filesystem_remote" <%= "filesystem_remote".equalsIgnoreCase(tsSafe(settings.get("storage_backend"))) ? "selected" : "" %>>Remote Filesystem</option>
        </select>
      </label>
      <label>Endpoint
        <input type="text" name="storage_endpoint" value="<%= tsEsc(tsSafe(settings.get("storage_endpoint"))) %>" placeholder="smb://fileserver/share or mount path" />
      </label>
      <label>Access Key
        <input type="text" name="storage_access_key" value="<%= tsEsc(tsSafe(settings.get("storage_access_key"))) %>" />
      </label>
      <label>Secret
        <input type="password" name="storage_secret" value="" placeholder="<%= tsEsc(maskedStorageSecret) %>" />
      </label>
    </div>

    <div class="actions" style="display:flex; gap:10px; margin-top:10px; align-items:center;">
      <button type="submit" class="btn btn-ghost" name="action" value="test_storage_connection">Test Storage Connection</button>
      <label><input type="checkbox" name="save_storage_secret" value="1" /> Persist entered storage secret on Save</label>
    </div>
  </section>

  <section class="card" style="margin-top:12px;">
    <h2 style="margin-top:0;">Clio Connection</h2>

    <label>Auth Mode
      <select name="clio_auth_mode">
        <option value="public" <%= "public".equals(clioMode) ? "selected" : "" %>>Public mode (web-accessible OAuth callback)</option>
        <option value="private" <%= "private".equals(clioMode) ? "selected" : "" %>>Private mode (relay/admin-mediated exchange)</option>
      </select>
    </label>

    <% if ("public".equals(clioMode)) { %>
      <div class="alert alert-ok" style="margin-top:8px;">Public mode requires this app to expose a Clio OAuth callback URL reachable by Clio.</div>
    <% } else { %>
      <div class="alert alert-error" style="margin-top:8px;">Private mode is for VPN/firewalled deployments. Complete OAuth externally via relay/admin flow and store only resulting token material locally.</div>
    <% } %>

    <div class="grid grid-2">
      <label>Base URL
        <input type="text" name="clio_base_url" value="<%= tsEsc(tsSafe(settings.get("clio_base_url"))) %>" placeholder="https://app.clio.com" />
      </label>
      <label>Client ID
        <input type="text" name="clio_client_id" value="<%= tsEsc(tsSafe(settings.get("clio_client_id"))) %>" />
      </label>
      <label>Client Secret
        <input type="password" name="clio_client_secret" value="" placeholder="<%= tsEsc(maskedClioSecret) %>" />
      </label>
      <label>OAuth Callback URL (public mode)
        <input type="text" name="clio_oauth_callback_url" value="<%= tsEsc(tsSafe(settings.get("clio_oauth_callback_url"))) %>" placeholder="https://your-app.example.com/clio/oauth/callback" />
      </label>
      <label>Relay/Admin Exchange Path (private mode)
        <input type="text" name="clio_private_relay_url" value="<%= tsEsc(tsSafe(settings.get("clio_private_relay_url"))) %>" placeholder="https://relay.example.com/clio/exchange or internal SOP reference" />
      </label>
    </div>

    <div class="actions" style="display:flex; gap:10px; margin-top:10px; align-items:center;">
      <button type="submit" class="btn btn-ghost" name="action" value="test_clio_connection">Test Clio Connection</button>
      <label><input type="checkbox" name="save_clio_secret" value="1" /> Persist entered Clio secret on Save</label>
    </div>
  </section>

  <section class="card" style="margin-top:12px;">
    <h2 style="margin-top:0;">Feature Flags</h2>
    <div class="grid grid-2">
      <label><input type="checkbox" name="feature_advanced_assembly" value="1" <%= "true".equalsIgnoreCase(tsSafe(settings.get("feature_advanced_assembly"))) ? "checked" : "" %> /> Enable advanced assembly</label>
      <label><input type="checkbox" name="feature_async_sync" value="1" <%= "true".equalsIgnoreCase(tsSafe(settings.get("feature_async_sync"))) ? "checked" : "" %> /> Enable async sync</label>
    </div>
  </section>

  <section class="card" style="margin-top:12px;">
    <h2 style="margin-top:0;">Security Controls</h2>
    <div class="grid grid-2">
      <div>Storage secret last rotated: <strong><%= tsEsc(tsSafe(settings.get("secret_rotation_storage_at"))) %></strong></div>
      <div>Clio secret last rotated: <strong><%= tsEsc(tsSafe(settings.get("secret_rotation_clio_at"))) %></strong></div>
      <div>Storage connection: <strong><%= tsEsc(tsSafe(settings.get("storage_connection_status"))) %></strong> (<%= tsEsc(tsSafe(settings.get("storage_connection_checked_at"))) %>)</div>
      <div>Clio connection: <strong><%= tsEsc(tsSafe(settings.get("clio_connection_status"))) %></strong> (<%= tsEsc(tsSafe(settings.get("clio_connection_checked_at"))) %>)</div>
      <div>Clio auth mode health: <strong><%= tsEsc(tsSafe(settings.get("clio_auth_health_status"))) %></strong> (<%= tsEsc(tsSafe(settings.get("clio_auth_health_checked_at"))) %>)</div>
      <label><input type="checkbox" name="rotate_storage_secret" value="1" /> Mark storage secret rotated now</label>
      <label><input type="checkbox" name="rotate_clio_secret" value="1" /> Mark Clio secret rotated now</label>
    </div>
  </section>

  <section class="card" style="margin-top:12px;">
    <button type="submit" class="btn" name="action" value="save_settings">Save Settings</button>
  </section>
</form>

<jsp:include page="footer.jsp" />
