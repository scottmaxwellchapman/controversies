<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>

<%@ page import="java.util.LinkedHashMap" %>
<%@ page import="java.util.ArrayList" %>
<%@ page import="java.util.Locale" %>
<%@ page import="java.util.List" %>
<%@ page import="java.util.Map" %>

<%@ page import="net.familylawandprobate.controversies.activity_log" %>
<%@ page import="net.familylawandprobate.controversies.secret_redactor" %>
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

  private static String tsRotationLabel(String iso) {
    String s = tsSafe(iso).trim();
    return s.isBlank() ? "Never" : s;
  }

  private static String tsStatusLabel(String raw) {
    String s = tsSafe(raw).trim().toLowerCase(Locale.ROOT);
    if ("ok".equals(s)) return "OK";
    if ("failed".equals(s)) return "Failed";
    if (s.isBlank()) return "Not tested";
    return s;
  }

  private static String tsThemeMode(String raw) {
    String v = tsSafe(raw).trim().toLowerCase(Locale.ROOT);
    if (!"light".equals(v) && !"dark".equals(v) && !"auto".equals(v)) return "auto";
    return v;
  }

  private static String tsTextSize(String raw) {
    String v = tsSafe(raw).trim().toLowerCase(Locale.ROOT);
    if (!"sm".equals(v) && !"md".equals(v) && !"lg".equals(v) && !"xl".equals(v)) return "md";
    return v;
  }

  private static String tsHour(String raw, int fallback) {
    try {
      int v = Integer.parseInt(tsSafe(raw).trim());
      if (v < 0 || v > 23) return String.valueOf(fallback);
      return String.valueOf(v);
    } catch (Exception ignored) {
      return String.valueOf(fallback);
    }
  }

  private static String tsLatitude(String raw) {
    String v = tsSafe(raw).trim();
    if (v.isBlank()) return "";
    try {
      double lat = Double.parseDouble(v);
      if (lat < -90.0 || lat > 90.0) return "";
      return String.valueOf(lat);
    } catch (Exception ignored) {
      return "";
    }
  }

  private static String tsLongitude(String raw) {
    String v = tsSafe(raw).trim();
    if (v.isBlank()) return "";
    try {
      double lon = Double.parseDouble(v);
      if (lon < -180.0 || lon > 180.0) return "";
      return String.valueOf(lon);
    } catch (Exception ignored) {
      return "";
    }
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

  List<String> setupChecklist = new ArrayList<String>();
  String loadedStorageBackend = tsSafe(settings.get("storage_backend")).trim().toLowerCase(Locale.ROOT);
  String loadedStorageStatus = tsSafe(settings.get("storage_connection_status")).trim().toLowerCase(Locale.ROOT);
  String loadedClioStatus = tsSafe(settings.get("clio_connection_status")).trim().toLowerCase(Locale.ROOT);
  if (loadedStorageBackend.isBlank()) setupChecklist.add("Choose and save a storage backend.");
  if (!"ok".equals(loadedStorageStatus)) setupChecklist.add("Run 'Test Storage Connection' until status is OK.");
  if (tsSafe(settings.get("clio_base_url")).isBlank()) setupChecklist.add("Enter Clio Base URL if this tenant uses Clio sync.");
  if (!"ok".equals(loadedClioStatus)) setupChecklist.add("Run 'Test Clio Connection' after credentials are configured.");

  if ("POST".equalsIgnoreCase(request.getMethod())) {
    String action = tsSafe(request.getParameter("action")).trim();

    String storageBackend = tsSafe(request.getParameter("storage_backend")).trim();
    if (storageBackend.isBlank()) storageBackend = "local";
    String storageEndpoint = tsSafe(request.getParameter("storage_endpoint")).trim();
    String storageAccessKey = tsSafe(request.getParameter("storage_access_key")).trim();
    String storageSecret = tsSafe(request.getParameter("storage_secret")).trim();
    String storageEncryptionMode = tsSafe(request.getParameter("storage_encryption_mode")).trim().toLowerCase(Locale.ROOT);
    if (!"tenant_managed".equals(storageEncryptionMode)) storageEncryptionMode = "disabled";
    String storageEncryptionKey = tsSafe(request.getParameter("storage_encryption_key")).trim();
    String storageS3SseMode = tsSafe(request.getParameter("storage_s3_sse_mode")).trim().toLowerCase(Locale.ROOT);
    if (!"aes256".equals(storageS3SseMode) && !"aws_kms".equals(storageS3SseMode)) storageS3SseMode = "none";
    String storageS3SseKmsKeyId = tsSafe(request.getParameter("storage_s3_sse_kms_key_id")).trim();

    String clioBaseUrl = tsSafe(request.getParameter("clio_base_url")).trim();
    String clioClientId = tsSafe(request.getParameter("clio_client_id")).trim();
    String clioClientSecret = tsSafe(request.getParameter("clio_client_secret")).trim();

    String clioAuthMode = tsSafe(request.getParameter("clio_auth_mode")).trim().toLowerCase(Locale.ROOT);
    if (!"private".equals(clioAuthMode)) clioAuthMode = "public";
    String clioOauthCallbackUrl = tsSafe(request.getParameter("clio_oauth_callback_url")).trim();
    String clioPrivateRelayUrl = tsSafe(request.getParameter("clio_private_relay_url")).trim();

    boolean featureAdvancedAssembly = tsChecked(request.getParameter("feature_advanced_assembly"));
    boolean featureAsyncSync = tsChecked(request.getParameter("feature_async_sync"));
    String themeModeDefault = tsThemeMode(request.getParameter("theme_mode_default"));
    boolean themeUseLocation = tsChecked(request.getParameter("theme_use_location"));
    String themeLatitude = tsLatitude(request.getParameter("theme_latitude"));
    String themeLongitude = tsLongitude(request.getParameter("theme_longitude"));
    String themeLightStartHour = tsHour(request.getParameter("theme_light_start_hour"), 7);
    String themeDarkStartHour = tsHour(request.getParameter("theme_dark_start_hour"), 19);
    String themeTextSizeDefault = tsTextSize(request.getParameter("theme_text_size_default"));

    boolean saveStorageSecret = tsChecked(request.getParameter("save_storage_secret"));
    boolean saveStorageEncryptionKey = tsChecked(request.getParameter("save_storage_encryption_key"));
    boolean saveClioSecret = tsChecked(request.getParameter("save_clio_secret"));

    settings.put("storage_backend", storageBackend);
    settings.put("storage_endpoint", storageEndpoint);
    settings.put("storage_encryption_mode", storageEncryptionMode);
    settings.put("storage_s3_sse_mode", storageS3SseMode);
    settings.put("storage_s3_sse_kms_key_id", storageS3SseKmsKeyId);
    if (!storageAccessKey.isBlank()) settings.put("storage_access_key", storageAccessKey);
    settings.put("clio_base_url", clioBaseUrl);
    settings.put("clio_client_id", clioClientId);
    settings.put("clio_auth_mode", clioAuthMode);
    settings.put("clio_oauth_callback_url", clioOauthCallbackUrl);
    settings.put("clio_private_relay_url", clioPrivateRelayUrl);
    settings.put("feature_advanced_assembly", featureAdvancedAssembly ? "true" : "false");
    settings.put("feature_async_sync", featureAsyncSync ? "true" : "false");
    settings.put("theme_mode_default", themeModeDefault);
    settings.put("theme_use_location", themeUseLocation ? "true" : "false");
    settings.put("theme_latitude", themeLatitude);
    settings.put("theme_longitude", themeLongitude);
    settings.put("theme_light_start_hour", themeLightStartHour);
    settings.put("theme_dark_start_hour", themeDarkStartHour);
    settings.put("theme_text_size_default", themeTextSizeDefault);

    if (saveStorageSecret && !storageSecret.isBlank()) settings.put("storage_secret", storageSecret);
    if (saveStorageEncryptionKey && !storageEncryptionKey.isBlank()) settings.put("storage_encryption_key", storageEncryptionKey);
    if (saveClioSecret && !clioClientSecret.isBlank()) settings.put("clio_client_secret", clioClientSecret);

    String effectiveStorageAccessKey = tsSafe(settings.get("storage_access_key")).trim();
    String effectiveStorageSecret = !storageSecret.isBlank() ? storageSecret : tsSafe(settings.get("storage_secret")).trim();
    String effectiveStorageEncryptionKey = !storageEncryptionKey.isBlank() ? storageEncryptionKey : tsSafe(settings.get("storage_encryption_key")).trim();
    String effectiveClioSecret = !clioClientSecret.isBlank() ? clioClientSecret : tsSafe(settings.get("clio_client_secret")).trim();

    if ("test_storage_connection".equalsIgnoreCase(action)) {
      List<String> storageFailures = new ArrayList<String>();
      boolean ok;
      if (!"local".equalsIgnoreCase(storageBackend) && !"localfs".equalsIgnoreCase(storageBackend)) {
        ok = !storageEndpoint.isBlank() && !effectiveStorageAccessKey.isBlank() && !effectiveStorageSecret.isBlank();
        if (storageEndpoint.isBlank()) storageFailures.add("Missing storage endpoint");
        if (effectiveStorageAccessKey.isBlank()) storageFailures.add("Missing storage access key");
        if (effectiveStorageSecret.isBlank()) storageFailures.add("Missing storage secret");
      } else {
        ok = true;
      }
      if ("tenant_managed".equals(storageEncryptionMode) && effectiveStorageEncryptionKey.isBlank()) {
        ok = false;
        storageFailures.add("Tenant-managed encryption selected without encryption key");
      }
      if ("s3_compatible".equalsIgnoreCase(storageBackend) && "aws_kms".equals(storageS3SseMode) && storageS3SseKmsKeyId.isBlank()) {
        ok = false;
        storageFailures.add("SSE-KMS selected without KMS key id");
      }
      settings.put("storage_connection_status", ok ? "ok" : "failed");
      settings.put("storage_connection_checked_at", store.nowIso());
      if (ok) {
        message = "Storage connection test succeeded.";
      } else {
        message = "Storage connection test failed: " + String.join("; ", storageFailures) + ".";
      }
      Map<String, String> details = new LinkedHashMap<String, String>();
      details.put("action", "test_storage_connection");
      details.put("result", ok ? "ok" : "failed");
      details.put("storage_backend", storageBackend);
      details.put("storage_encryption_mode", storageEncryptionMode);
      details.put("storage_s3_sse_mode", storageS3SseMode);
      details.put("validation_issues", storageFailures.isEmpty() ? "none" : String.join(" | ", storageFailures));
      logs.logVerbose("tenant_settings_storage_test", tenantUuid, userUuid, "", "", details);

    } else if ("test_clio_connection".equalsIgnoreCase(action)) {
      List<String> clioFailures = new ArrayList<String>();
      boolean hasShared = !clioBaseUrl.isBlank() && clioBaseUrl.startsWith("http") && !clioClientId.isBlank();
      if (clioBaseUrl.isBlank() || !clioBaseUrl.startsWith("http")) clioFailures.add("Base URL must start with http/https");
      if (clioClientId.isBlank()) clioFailures.add("Missing Clio Client ID");
      boolean hasSecret = !effectiveClioSecret.isBlank();
      if (!hasSecret) clioFailures.add("Missing Clio Client Secret");
      boolean modeReady;
      if ("public".equals(clioAuthMode)) {
        modeReady = !clioOauthCallbackUrl.isBlank() && clioOauthCallbackUrl.startsWith("http");
        if (!modeReady) clioFailures.add("Public mode requires web-accessible OAuth callback URL");
      } else {
        modeReady = !clioPrivateRelayUrl.isBlank();
        if (!modeReady) clioFailures.add("Private mode requires relay/admin exchange path");
      }

      boolean ok = hasShared && hasSecret && modeReady;
      settings.put("clio_connection_status", ok ? "ok" : "failed");
      settings.put("clio_connection_checked_at", store.nowIso());
      settings.put("clio_auth_health_status", ok ? "ok" : "failed");
      settings.put("clio_auth_health_checked_at", store.nowIso());

      if (ok) {
        message = "Clio connection test succeeded for " + clioAuthMode + " auth mode.";
      } else {
        message = "Clio connection test failed: " + String.join("; ", clioFailures) + ".";
      }
      Map<String, String> details = new LinkedHashMap<String, String>();
      details.put("action", "test_clio_connection");
      details.put("result", ok ? "ok" : "failed");
      details.put("clio_auth_mode", clioAuthMode);
      details.put("clio_auth_health_status", tsSafe(settings.get("clio_auth_health_status")));
      details.put("validation_issues", clioFailures.isEmpty() ? "none" : String.join(" | ", clioFailures));
      logs.logVerbose("tenant_settings_clio_test", tenantUuid, userUuid, "", "", details);

    } else if ("rotate_storage_secret".equalsIgnoreCase(action)) {
      if (storageSecret.isBlank()) {
        error = "Enter a new storage secret before rotating.";
        logs.logVerbose("tenant_settings_storage_secret_rotation_rejected", tenantUuid, userUuid, "", "", Map.of("reason", "missing_new_secret"));
      } else {
        settings.put("storage_secret", storageSecret);
        settings.put("secret_rotation_storage_at", store.nowIso());
        message = "Storage secret rotated.";
        logs.logVerbose("tenant_settings_storage_secret_rotated", tenantUuid, userUuid, "", "", Map.of("status", "accepted_pending_save"));
      }
    } else if ("rotate_clio_secret".equalsIgnoreCase(action)) {
      if (clioClientSecret.isBlank()) {
        error = "Enter a new Clio secret before rotating.";
        logs.logVerbose("tenant_settings_clio_secret_rotation_rejected", tenantUuid, userUuid, "", "", Map.of("reason", "missing_new_secret"));
      } else {
        settings.put("clio_client_secret", clioClientSecret);
        settings.put("secret_rotation_clio_at", store.nowIso());
        message = "Clio secret rotated.";
        logs.logVerbose("tenant_settings_clio_secret_rotated", tenantUuid, userUuid, "", "", Map.of("status", "accepted_pending_save"));
      }
    }

    if (("save_settings".equalsIgnoreCase(action)
            || "rotate_storage_secret".equalsIgnoreCase(action)
            || "rotate_clio_secret".equalsIgnoreCase(action))
            && error == null) {
      boolean valid = true;
      boolean clioConfigured = !clioBaseUrl.isBlank() || !clioClientId.isBlank() || !effectiveClioSecret.isBlank()
              || !clioOauthCallbackUrl.isBlank() || !clioPrivateRelayUrl.isBlank();
      if (clioConfigured && "public".equals(clioAuthMode) && (clioOauthCallbackUrl.isBlank() || !clioOauthCallbackUrl.startsWith("http"))) {
        valid = false;
        error = "Public mode requires a web-accessible OAuth callback URL.";
      }
      if (clioConfigured && (clioBaseUrl.isBlank() || !clioBaseUrl.startsWith("http"))) {
        valid = false;
        error = "Clio Base URL must start with http:// or https://.";
      }
      if (clioConfigured && "private".equals(clioAuthMode) && clioPrivateRelayUrl.isBlank()) {
        valid = false;
        error = "Private mode requires relay/admin exchange instructions endpoint or note.";
      }
      if ("tenant_managed".equals(storageEncryptionMode) && effectiveStorageEncryptionKey.isBlank()) {
        valid = false;
        error = "Tenant-managed encryption requires an application encryption key.";
      }
      if ("s3_compatible".equalsIgnoreCase(storageBackend) && "aws_kms".equals(storageS3SseMode) && storageS3SseKmsKeyId.isBlank()) {
        valid = false;
        error = "S3 SSE aws_kms requires a KMS key id.";
      }
      if ((themeLatitude.isBlank() && !themeLongitude.isBlank()) || (!themeLatitude.isBlank() && themeLongitude.isBlank())) {
        valid = false;
        error = "Provide both theme latitude and longitude, or leave both blank.";
      }

      if (!valid) {
        logs.logVerbose("tenant_settings_validation_failed", tenantUuid, userUuid, "", "", Map.of("action", action, "error", tsSafe(error)));
      }

      if (valid) {
        try {
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
          details.put("theme_mode_default", tsSafe(settings.get("theme_mode_default")));
          details.put("theme_use_location", tsSafe(settings.get("theme_use_location")));
          details.put("theme_text_size_default", tsSafe(settings.get("theme_text_size_default")));
          details.put("storage_connection_status", tsSafe(settings.get("storage_connection_status")));
          details.put("storage_encryption_mode", tsSafe(settings.get("storage_encryption_mode")));
          details.put("storage_s3_sse_mode", tsSafe(settings.get("storage_s3_sse_mode")));
          details.put("clio_connection_status", tsSafe(settings.get("clio_connection_status")));
          details.put("clio_auth_mode", tsSafe(settings.get("clio_auth_mode")));
          details.put("clio_auth_health_status", tsSafe(settings.get("clio_auth_health_status")));
          details.put("storage_secret_saved", tsSafe(settings.get("storage_secret")).isBlank() ? "no" : "yes");
          details.put("storage_encryption_key_saved", tsSafe(settings.get("storage_encryption_key")).isBlank() ? "no" : "yes");
          details.put("clio_client_secret_saved", tsSafe(settings.get("clio_client_secret")).isBlank() ? "no" : "yes");
          details.put("save_storage_secret_requested", saveStorageSecret ? "true" : "false");
          details.put("save_storage_encryption_key_requested", saveStorageEncryptionKey ? "true" : "false");
          details.put("save_clio_secret_requested", saveClioSecret ? "true" : "false");
          logs.logVerbose("tenant_settings_changed", tenantUuid, userUuid, "", "", details);

          String status = "saved";
          if ("rotate_storage_secret".equalsIgnoreCase(action)) status = "rotated_storage";
          if ("rotate_clio_secret".equalsIgnoreCase(action)) status = "rotated_clio";
          response.sendRedirect(ctx + "/tenant_settings.jsp?status=" + status);
          return;
        } catch (Exception ex) {
          error = "Unable to save tenant settings.";
          logs.logVerbose("tenant_settings_save_failed", tenantUuid, userUuid, "", "", Map.of("reason", ex.getClass().getSimpleName()));
        }
      }
    }
  }

  String status = tsSafe(request.getParameter("status"));
  if ("saved".equals(status)) message = "Tenant settings saved.";
  if ("rotated_storage".equals(status)) message = "Storage secret rotated and saved.";
  if ("rotated_clio".equals(status)) message = "Clio secret rotated and saved.";

  String maskedStorageSecret = tsSafe(settings.get("storage_secret")).isBlank() ? "" : "********";
  String maskedClioSecret = tsSafe(settings.get("clio_client_secret")).isBlank() ? "" : "********";
  String maskedStorageEncryptionKey = tsSafe(settings.get("storage_encryption_key")).isBlank() ? "" : "********";
  String maskedStorageAccessKey = tsSafe(settings.get("storage_access_key")).isBlank() ? "" : "********";
  String clioMode = tsSafe(settings.get("clio_auth_mode")).trim().toLowerCase(Locale.ROOT);
  if (!"private".equals(clioMode)) clioMode = "public";
  String themeMode = tsThemeMode(settings.get("theme_mode_default"));
  String themeTextSize = tsTextSize(settings.get("theme_text_size_default"));
  String themeLatitudeSaved = tsSafe(settings.get("theme_latitude")).trim();
  String themeLongitudeSaved = tsSafe(settings.get("theme_longitude")).trim();
  String themeLightHourSaved = tsHour(settings.get("theme_light_start_hour"), 7);
  String themeDarkHourSaved = tsHour(settings.get("theme_dark_start_hour"), 19);
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
    <h2 style="margin-top:0;">Setup Guide (Quick + Advanced)</h2>
    <div class="meta" style="margin-bottom:8px;">For first-time setup: complete Storage, then Clio (if used), test both, then Save Settings. Experienced admins can update only the fields they need and run targeted tests.</div>
    <div class="grid grid-2">
      <div>
        <h3 style="margin:0 0 6px 0;">First-time checklist</h3>
        <% if (setupChecklist.isEmpty()) { %>
          <div class="alert alert-ok">Great news: baseline setup checks look complete.</div>
        <% } else { %>
          <ul style="margin:0; padding-left:20px;">
            <% for (String item : setupChecklist) { %>
              <li><%= tsEsc(item) %></li>
            <% } %>
          </ul>
        <% } %>
      </div>
      <div>
        <h3 style="margin:0 0 6px 0;">Experienced-user tips</h3>
        <ul style="margin:0; padding-left:20px;">
          <li>Use <strong>Test</strong> actions before Save to validate targeted updates.</li>
          <li>Leave password fields blank to keep current secrets unchanged.</li>
          <li>Use rotation buttons to timestamp key changes for audit trail visibility.</li>
        </ul>
      </div>
    </div>
  </section>

  <section class="card" style="margin-top:12px;">
    <h2 style="margin-top:0;">Storage Backend</h2>
    <div class="meta" style="margin-bottom:10px;">Configure where assembled documents are stored. Keep secret fields blank to retain currently saved values.</div>

    <div class="grid grid-2">
      <label>Backend
        <select name="storage_backend">
          <option value="local" <%= "local".equalsIgnoreCase(tsSafe(settings.get("storage_backend"))) ? "selected" : "" %>>Local Filesystem</option>
          <option value="ftp" <%= "ftp".equalsIgnoreCase(tsSafe(settings.get("storage_backend"))) ? "selected" : "" %>>FTP</option>
          <option value="ftps" <%= "ftps".equalsIgnoreCase(tsSafe(settings.get("storage_backend"))) ? "selected" : "" %>>FTPS</option>
          <option value="sftp" <%= "sftp".equalsIgnoreCase(tsSafe(settings.get("storage_backend"))) ? "selected" : "" %>>SFTP</option>
          <option value="s3_compatible" <%= "s3_compatible".equalsIgnoreCase(tsSafe(settings.get("storage_backend"))) ? "selected" : "" %>>S3 Compatible</option>
        </select>
      </label>
      <label>Endpoint
        <input type="text" name="storage_endpoint" value="<%= tsEsc(tsSafe(settings.get("storage_endpoint"))) %>" placeholder="ftp://, sftp://, or https://s3.endpoint.example" />
      </label>
      <label>Access Key
        <input type="password" name="storage_access_key" value="" placeholder="<%= tsEsc(maskedStorageAccessKey) %>" />
      </label>
      <label>Secret
        <input type="password" name="storage_secret" value="" placeholder="<%= tsEsc(maskedStorageSecret) %>" />
      </label>
      <label>Application Encryption
        <select name="storage_encryption_mode">
          <option value="disabled" <%= "disabled".equalsIgnoreCase(tsSafe(settings.get("storage_encryption_mode"))) ? "selected" : "" %>>Disabled</option>
          <option value="tenant_managed" <%= "tenant_managed".equalsIgnoreCase(tsSafe(settings.get("storage_encryption_mode"))) ? "selected" : "" %>>Tenant-managed key</option>
        </select>
      </label>
      <label>Application Encryption Key
        <input type="password" name="storage_encryption_key" value="" placeholder="<%= tsEsc(maskedStorageEncryptionKey) %>" />
      </label>
      <label>S3 SSE Mode
        <select name="storage_s3_sse_mode">
          <option value="none" <%= "none".equalsIgnoreCase(tsSafe(settings.get("storage_s3_sse_mode"))) ? "selected" : "" %>>None</option>
          <option value="aes256" <%= "aes256".equalsIgnoreCase(tsSafe(settings.get("storage_s3_sse_mode"))) ? "selected" : "" %>>SSE-S3 (AES256)</option>
          <option value="aws_kms" <%= "aws_kms".equalsIgnoreCase(tsSafe(settings.get("storage_s3_sse_mode"))) ? "selected" : "" %>>SSE-KMS (aws:kms)</option>
        </select>
      </label>
      <label>S3 SSE KMS Key Id
        <input type="text" name="storage_s3_sse_kms_key_id" value="<%= tsEsc(tsSafe(settings.get("storage_s3_sse_kms_key_id"))) %>" placeholder="arn:aws:kms:... or key id" />
      </label>
    </div>

    <div class="actions" style="display:flex; gap:10px; margin-top:10px; align-items:center; flex-wrap:wrap;">
      <button type="submit" class="btn btn-ghost" name="action" value="test_storage_connection">Test Storage Connection</button>
      <button type="submit" class="btn btn-ghost" name="action" value="rotate_storage_secret">Rotate Storage Secret</button>
      <label><input type="checkbox" name="save_storage_secret" value="1" /> Persist entered storage secret on Save</label>
      <label><input type="checkbox" name="save_storage_encryption_key" value="1" /> Persist entered application encryption key on Save</label>
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

    <div class="actions" style="display:flex; gap:10px; margin-top:10px; align-items:center; flex-wrap:wrap;">
      <button type="submit" class="btn btn-ghost" name="action" value="test_clio_connection">Test Clio Connection</button>
      <button type="submit" class="btn btn-ghost" name="action" value="rotate_clio_secret">Rotate Clio Secret</button>
      <label><input type="checkbox" name="save_clio_secret" value="1" /> Persist entered Clio secret on Save</label>
    </div>
  </section>

  <section class="card" style="margin-top:12px;">
    <h2 style="margin-top:0;">Appearance & Theme</h2>
    <div class="meta" style="margin-bottom:10px;">
      Default tenant theme behavior for all pages. End users can still manually override in the navigation bar.
    </div>
    <div class="grid grid-2">
      <label>Default Theme Mode
        <select name="theme_mode_default">
          <option value="auto" <%= "auto".equals(themeMode) ? "selected" : "" %>>Auto (time + sunrise/sunset)</option>
          <option value="light" <%= "light".equals(themeMode) ? "selected" : "" %>>Light</option>
          <option value="dark" <%= "dark".equals(themeMode) ? "selected" : "" %>>Dark</option>
        </select>
      </label>
      <label>Default Text Size
        <select name="theme_text_size_default">
          <option value="sm" <%= "sm".equals(themeTextSize) ? "selected" : "" %>>Smaller</option>
          <option value="md" <%= "md".equals(themeTextSize) ? "selected" : "" %>>Normal</option>
          <option value="lg" <%= "lg".equals(themeTextSize) ? "selected" : "" %>>Larger</option>
          <option value="xl" <%= "xl".equals(themeTextSize) ? "selected" : "" %>>Largest</option>
        </select>
      </label>
      <label>Fallback Light Start Hour (0-23)
        <input type="number" min="0" max="23" name="theme_light_start_hour" value="<%= tsEsc(themeLightHourSaved) %>" />
      </label>
      <label>Fallback Dark Start Hour (0-23)
        <input type="number" min="0" max="23" name="theme_dark_start_hour" value="<%= tsEsc(themeDarkHourSaved) %>" />
      </label>
      <label>Latitude (optional)
        <input type="text" name="theme_latitude" value="<%= tsEsc(themeLatitudeSaved) %>" placeholder="35.2271" />
      </label>
      <label>Longitude (optional)
        <input type="text" name="theme_longitude" value="<%= tsEsc(themeLongitudeSaved) %>" placeholder="-80.8431" />
      </label>
      <label><input type="checkbox" name="theme_use_location" value="1" <%= "true".equalsIgnoreCase(tsSafe(settings.get("theme_use_location"))) ? "checked" : "" %> /> Use browser location for sunrise/sunset auto theme</label>
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
      <div>Storage secret last rotated: <strong><%= tsEsc(tsRotationLabel(tsSafe(settings.get("secret_rotation_storage_at")))) %></strong></div>
      <div>Clio secret last rotated: <strong><%= tsEsc(tsRotationLabel(tsSafe(settings.get("secret_rotation_clio_at")))) %></strong></div>
      <div>Storage connection: <strong><%= tsEsc(tsStatusLabel(tsSafe(settings.get("storage_connection_status")))) %></strong> (<%= tsEsc(tsRotationLabel(tsSafe(settings.get("storage_connection_checked_at")))) %>)</div>
      <div>Application encryption: <strong><%= tsEsc(tsSafe(settings.get("storage_encryption_mode"))) %></strong></div>
      <div>S3 SSE mode: <strong><%= tsEsc(tsSafe(settings.get("storage_s3_sse_mode"))) %></strong></div>
      <div>Clio connection: <strong><%= tsEsc(tsStatusLabel(tsSafe(settings.get("clio_connection_status")))) %></strong> (<%= tsEsc(tsRotationLabel(tsSafe(settings.get("clio_connection_checked_at")))) %>)</div>
      <div>Clio auth mode health: <strong><%= tsEsc(tsStatusLabel(tsSafe(settings.get("clio_auth_health_status")))) %></strong> (<%= tsEsc(tsRotationLabel(tsSafe(settings.get("clio_auth_health_checked_at")))) %>)</div>
      <div>Theme default: <strong><%= tsEsc(themeMode) %></strong> • text size <strong><%= tsEsc(themeTextSize) %></strong></div>
      <div>Theme auto schedule: light <strong><%= tsEsc(themeLightHourSaved) %>:00</strong>, dark <strong><%= tsEsc(themeDarkHourSaved) %>:00</strong></div>
      <div>Sensitive output policy: <strong><%= tsEsc(secret_redactor.redactValue("hidden")) %></strong></div>
      <div>Audit logging: <strong>Verbose tenant events with secret redaction enabled</strong></div>
    </div>
  </section>

  <section class="card" style="margin-top:12px;">
    <button type="submit" class="btn" name="action" value="save_settings">Save Settings</button>
  </section>
</form>

<jsp:include page="footer.jsp" />
