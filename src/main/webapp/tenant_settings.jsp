<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>

<%@ page import="java.util.LinkedHashMap" %>
<%@ page import="java.util.ArrayList" %>
<%@ page import="java.util.Locale" %>
<%@ page import="java.util.List" %>
<%@ page import="java.util.Map" %>

<%@ page import="net.familylawandprobate.controversies.activity_log" %>
<%@ page import="net.familylawandprobate.controversies.secret_redactor" %>
<%@ page import="net.familylawandprobate.controversies.notification_emails" %>
<%@ page import="net.familylawandprobate.controversies.document_taxonomy" %>
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

  private static String tsIntRange(String raw, int fallback, int min, int max) {
    try {
      int v = Integer.parseInt(tsSafe(raw).trim());
      if (v < min || v > max) return String.valueOf(fallback);
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
  document_taxonomy taxonomyStore = document_taxonomy.defaultStore();

  String csrfToken = csrfForRender(request);
  String message = null;
  String error = null;

  LinkedHashMap<String,String> settings = new LinkedHashMap<String,String>();
  settings.putAll(store.read(tenantUuid));

  List<String> setupChecklist = new ArrayList<String>();
  String loadedStorageBackend = tsSafe(settings.get("storage_backend")).trim().toLowerCase(Locale.ROOT);
  String loadedStorageStatus = tsSafe(settings.get("storage_connection_status")).trim().toLowerCase(Locale.ROOT);
  String loadedClioStatus = tsSafe(settings.get("clio_connection_status")).trim().toLowerCase(Locale.ROOT);
  String loadedEmailProvider = tsSafe(settings.get("email_provider")).trim().toLowerCase(Locale.ROOT);
  String loadedEmailStatus = tsSafe(settings.get("email_connection_status")).trim().toLowerCase(Locale.ROOT);
  if (loadedStorageBackend.isBlank()) setupChecklist.add("Choose and save a storage backend.");
  if (!"ok".equals(loadedStorageStatus)) setupChecklist.add("Run 'Test Storage Connection' until status is OK.");
  if (tsSafe(settings.get("clio_base_url")).isBlank()) setupChecklist.add("Enter Clio Base URL if this tenant uses Clio sync.");
  if (!"ok".equals(loadedClioStatus)) setupChecklist.add("Run 'Test Clio Connection' after credentials are configured.");
  if (!"disabled".equals(loadedEmailProvider) && !"ok".equals(loadedEmailStatus)) {
    setupChecklist.add("Run 'Test Email Connection' after email provider credentials are configured.");
  }

  if ("POST".equalsIgnoreCase(request.getMethod())) {
    String action = tsSafe(request.getParameter("action")).trim();
    if ("add_taxonomy".equalsIgnoreCase(action)) {
      try {
        taxonomyStore.addValues(
          tenantUuid,
          java.util.Arrays.asList(tsSafe(request.getParameter("taxonomy_category"))),
          java.util.Arrays.asList(tsSafe(request.getParameter("taxonomy_subcategory"))),
          java.util.Arrays.asList(tsSafe(request.getParameter("taxonomy_status")))
        );
        logs.logVerbose("tenant_settings_taxonomy_updated", tenantUuid, userUuid, "", "", Map.of("action", "add_taxonomy"));
        response.sendRedirect(ctx + "/tenant_settings.jsp?status=taxonomy_saved");
        return;
      } catch (Exception ex) {
        error = "Unable to update taxonomy: " + tsSafe(ex.getMessage());
      }
    }

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

    String emailProvider = tsSafe(request.getParameter("email_provider")).trim().toLowerCase(Locale.ROOT);
    if (!"smtp".equals(emailProvider) && !"microsoft_graph".equals(emailProvider)) emailProvider = "disabled";
    String emailFromAddress = tsSafe(request.getParameter("email_from_address")).trim();
    String emailFromName = tsSafe(request.getParameter("email_from_name")).trim();
    String emailReplyTo = tsSafe(request.getParameter("email_reply_to")).trim();
    String emailConnectTimeoutMs = tsIntRange(request.getParameter("email_connect_timeout_ms"), 15000, 1000, 120000);
    String emailReadTimeoutMs = tsIntRange(request.getParameter("email_read_timeout_ms"), 20000, 1000, 180000);
    String emailQueuePollSeconds = tsIntRange(request.getParameter("email_queue_poll_seconds"), 5, 1, 300);
    String emailQueueBatchSize = tsIntRange(request.getParameter("email_queue_batch_size"), 10, 1, 200);
    String emailQueueMaxAttempts = tsIntRange(request.getParameter("email_queue_max_attempts"), 8, 1, 50);
    String emailQueueBackoffBaseMs = tsIntRange(request.getParameter("email_queue_backoff_base_ms"), 2000, 100, 600000);
    String emailQueueBackoffMaxMs = tsIntRange(request.getParameter("email_queue_backoff_max_ms"), 300000, 500, 3600000);
    String emailSmtpHost = tsSafe(request.getParameter("email_smtp_host")).trim();
    String emailSmtpPort = tsIntRange(request.getParameter("email_smtp_port"), 587, 1, 65535);
    String emailSmtpUsername = tsSafe(request.getParameter("email_smtp_username")).trim();
    String emailSmtpPassword = tsSafe(request.getParameter("email_smtp_password")).trim();
    boolean emailSmtpAuth = tsChecked(request.getParameter("email_smtp_auth"));
    boolean emailSmtpStartTls = tsChecked(request.getParameter("email_smtp_starttls"));
    boolean emailSmtpSsl = tsChecked(request.getParameter("email_smtp_ssl"));
    String emailSmtpHeloDomain = tsSafe(request.getParameter("email_smtp_helo_domain")).trim();
    String emailGraphTenantId = tsSafe(request.getParameter("email_graph_tenant_id")).trim();
    String emailGraphClientId = tsSafe(request.getParameter("email_graph_client_id")).trim();
    String emailGraphClientSecret = tsSafe(request.getParameter("email_graph_client_secret")).trim();
    String emailGraphSenderUser = tsSafe(request.getParameter("email_graph_sender_user")).trim();
    String emailGraphScope = tsSafe(request.getParameter("email_graph_scope")).trim();
    if (emailGraphScope.isBlank()) emailGraphScope = "https://graph.microsoft.com/.default";

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
    boolean saveEmailSmtpPassword = tsChecked(request.getParameter("save_email_smtp_password"));
    boolean saveEmailGraphClientSecret = tsChecked(request.getParameter("save_email_graph_client_secret"));

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
    settings.put("email_provider", emailProvider);
    settings.put("email_from_address", emailFromAddress);
    settings.put("email_from_name", emailFromName);
    settings.put("email_reply_to", emailReplyTo);
    settings.put("email_connect_timeout_ms", emailConnectTimeoutMs);
    settings.put("email_read_timeout_ms", emailReadTimeoutMs);
    settings.put("email_queue_poll_seconds", emailQueuePollSeconds);
    settings.put("email_queue_batch_size", emailQueueBatchSize);
    settings.put("email_queue_max_attempts", emailQueueMaxAttempts);
    settings.put("email_queue_backoff_base_ms", emailQueueBackoffBaseMs);
    settings.put("email_queue_backoff_max_ms", emailQueueBackoffMaxMs);
    settings.put("email_smtp_host", emailSmtpHost);
    settings.put("email_smtp_port", emailSmtpPort);
    settings.put("email_smtp_username", emailSmtpUsername);
    settings.put("email_smtp_auth", emailSmtpAuth ? "true" : "false");
    settings.put("email_smtp_starttls", emailSmtpStartTls ? "true" : "false");
    settings.put("email_smtp_ssl", emailSmtpSsl ? "true" : "false");
    settings.put("email_smtp_helo_domain", emailSmtpHeloDomain);
    settings.put("email_graph_tenant_id", emailGraphTenantId);
    settings.put("email_graph_client_id", emailGraphClientId);
    settings.put("email_graph_sender_user", emailGraphSenderUser);
    settings.put("email_graph_scope", emailGraphScope);
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
    if (saveEmailSmtpPassword && !emailSmtpPassword.isBlank()) settings.put("email_smtp_password", emailSmtpPassword);
    if (saveEmailGraphClientSecret && !emailGraphClientSecret.isBlank()) settings.put("email_graph_client_secret", emailGraphClientSecret);

    String effectiveStorageAccessKey = tsSafe(settings.get("storage_access_key")).trim();
    String effectiveStorageSecret = !storageSecret.isBlank() ? storageSecret : tsSafe(settings.get("storage_secret")).trim();
    String effectiveStorageEncryptionKey = !storageEncryptionKey.isBlank() ? storageEncryptionKey : tsSafe(settings.get("storage_encryption_key")).trim();
    String effectiveClioSecret = !clioClientSecret.isBlank() ? clioClientSecret : tsSafe(settings.get("clio_client_secret")).trim();
    String effectiveEmailSmtpPassword = !emailSmtpPassword.isBlank() ? emailSmtpPassword : tsSafe(settings.get("email_smtp_password")).trim();
    String effectiveEmailGraphClientSecret = !emailGraphClientSecret.isBlank() ? emailGraphClientSecret : tsSafe(settings.get("email_graph_client_secret")).trim();

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

    } else if ("test_email_connection".equalsIgnoreCase(action)) {
      LinkedHashMap<String, String> emailCfg = new LinkedHashMap<String, String>();
      emailCfg.putAll(settings);
      if (!effectiveEmailSmtpPassword.isBlank()) emailCfg.put("email_smtp_password", effectiveEmailSmtpPassword);
      if (!effectiveEmailGraphClientSecret.isBlank()) emailCfg.put("email_graph_client_secret", effectiveEmailGraphClientSecret);

      notification_emails.ValidationResult emailValidation = notification_emails.defaultStore().validateConfiguration(emailCfg);
      settings.put("email_connection_status", emailValidation.ok ? "ok" : "failed");
      settings.put("email_connection_checked_at", store.nowIso());
      if (emailValidation.ok) {
        message = "Email connection test succeeded for provider: " + tsSafe(settings.get("email_provider")) + ".";
      } else {
        message = "Email connection test failed: " + String.join("; ", emailValidation.issues) + ".";
      }
      Map<String, String> details = new LinkedHashMap<String, String>();
      details.put("action", "test_email_connection");
      details.put("result", emailValidation.ok ? "ok" : "failed");
      details.put("email_provider", tsSafe(settings.get("email_provider")));
      details.put("validation_issues", emailValidation.issues.isEmpty() ? "none" : String.join(" | ", emailValidation.issues));
      logs.logVerbose("tenant_settings_email_test", tenantUuid, userUuid, "", "", details);

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
      LinkedHashMap<String, String> validationEmailCfg = new LinkedHashMap<String, String>();
      validationEmailCfg.putAll(settings);
      if (!effectiveEmailSmtpPassword.isBlank()) validationEmailCfg.put("email_smtp_password", effectiveEmailSmtpPassword);
      if (!effectiveEmailGraphClientSecret.isBlank()) validationEmailCfg.put("email_graph_client_secret", effectiveEmailGraphClientSecret);
      notification_emails.ValidationResult emailValidation = notification_emails.defaultStore().validateConfiguration(validationEmailCfg);
      if (!emailValidation.ok) {
        valid = false;
        error = "Email provider settings are invalid: " + String.join("; ", emailValidation.issues) + ".";
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
          if (!"disabled".equalsIgnoreCase(tsSafe(settings.get("email_provider")))) {
            notification_emails.defaultStore().ensureQueue(tenantUuid);
          }

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
          details.put("email_provider", tsSafe(settings.get("email_provider")));
          details.put("email_connection_status", tsSafe(settings.get("email_connection_status")));
          details.put("email_queue_batch_size", tsSafe(settings.get("email_queue_batch_size")));
          details.put("email_queue_max_attempts", tsSafe(settings.get("email_queue_max_attempts")));
          details.put("storage_secret_saved", tsSafe(settings.get("storage_secret")).isBlank() ? "no" : "yes");
          details.put("storage_encryption_key_saved", tsSafe(settings.get("storage_encryption_key")).isBlank() ? "no" : "yes");
          details.put("clio_client_secret_saved", tsSafe(settings.get("clio_client_secret")).isBlank() ? "no" : "yes");
          details.put("email_smtp_password_saved", tsSafe(settings.get("email_smtp_password")).isBlank() ? "no" : "yes");
          details.put("email_graph_client_secret_saved", tsSafe(settings.get("email_graph_client_secret")).isBlank() ? "no" : "yes");
          details.put("save_storage_secret_requested", saveStorageSecret ? "true" : "false");
          details.put("save_storage_encryption_key_requested", saveStorageEncryptionKey ? "true" : "false");
          details.put("save_clio_secret_requested", saveClioSecret ? "true" : "false");
          details.put("save_email_smtp_password_requested", saveEmailSmtpPassword ? "true" : "false");
          details.put("save_email_graph_client_secret_requested", saveEmailGraphClientSecret ? "true" : "false");
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
  if ("taxonomy_saved".equals(status)) message = "Taxonomy updated.";

  String maskedStorageSecret = tsSafe(settings.get("storage_secret")).isBlank() ? "" : "********";
  String maskedClioSecret = tsSafe(settings.get("clio_client_secret")).isBlank() ? "" : "********";
  String maskedStorageEncryptionKey = tsSafe(settings.get("storage_encryption_key")).isBlank() ? "" : "********";
  String maskedStorageAccessKey = tsSafe(settings.get("storage_access_key")).isBlank() ? "" : "********";
  String maskedEmailSmtpPassword = tsSafe(settings.get("email_smtp_password")).isBlank() ? "" : "********";
  String maskedEmailGraphClientSecret = tsSafe(settings.get("email_graph_client_secret")).isBlank() ? "" : "********";
  String emailProviderMode = tsSafe(settings.get("email_provider")).trim().toLowerCase(Locale.ROOT);
  if (!"smtp".equals(emailProviderMode) && !"microsoft_graph".equals(emailProviderMode)) emailProviderMode = "disabled";
  String clioMode = tsSafe(settings.get("clio_auth_mode")).trim().toLowerCase(Locale.ROOT);
  if (!"private".equals(clioMode)) clioMode = "public";
  String themeMode = tsThemeMode(settings.get("theme_mode_default"));
  String themeTextSize = tsTextSize(settings.get("theme_text_size_default"));
  String themeLatitudeSaved = tsSafe(settings.get("theme_latitude")).trim();
  String themeLongitudeSaved = tsSafe(settings.get("theme_longitude")).trim();
  String themeLightHourSaved = tsHour(settings.get("theme_light_start_hour"), 7);
  String themeDarkHourSaved = tsHour(settings.get("theme_dark_start_hour"), 19);
  document_taxonomy.Taxonomy tx = new document_taxonomy.Taxonomy();
  try { tx = taxonomyStore.read(tenantUuid); } catch (Exception ignored) {}
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

<section class="card" style="margin-top:12px;" id="document-taxonomy">
  <h2 style="margin-top:0;">Manage Document Taxonomy</h2>
  <div class="meta" style="margin-bottom:10px;">These values drive Category/Subcategory/Status options on the Documents page.</div>
  <form class="form" method="post" action="<%= ctx %>/tenant_settings.jsp">
    <input type="hidden" name="csrfToken" value="<%= tsEsc(csrfToken) %>" />
    <input type="hidden" name="action" value="add_taxonomy" />
    <div class="grid grid-3">
      <label>Category
        <input type="text" name="taxonomy_category" />
      </label>
      <label>Subcategory
        <input type="text" name="taxonomy_subcategory" />
      </label>
      <label>Status
        <input type="text" name="taxonomy_status" />
      </label>
    </div>
    <button type="submit" class="btn btn-ghost">Save Taxonomy Values</button>
  </form>
  <div class="meta" style="margin-top:10px;">
    Categories: <%= tx.categories == null || tx.categories.isEmpty() ? "None" : tsEsc(String.join(", ", tx.categories)) %><br />
    Subcategories: <%= tx.subcategories == null || tx.subcategories.isEmpty() ? "None" : tsEsc(String.join(", ", tx.subcategories)) %><br />
    Statuses: <%= tx.statuses == null || tx.statuses.isEmpty() ? "None" : tsEsc(String.join(", ", tx.statuses)) %>
  </div>
</section>

<form class="form" method="post" action="<%= ctx %>/tenant_settings.jsp">
  <input type="hidden" name="csrfToken" value="<%= tsEsc(csrfToken) %>" />

  <section class="card" style="margin-top:12px;">
    <h2 style="margin-top:0;">Setup Guide (Quick + Advanced)</h2>
    <div class="meta" style="margin-bottom:8px;">For first-time setup: complete Storage, Clio (if used), and Notification Email (if used), then run each test and Save Settings. Experienced admins can update only the fields they need and run targeted tests.</div>
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
    <h2 style="margin-top:0;">Notification Email (Queue + Transport)</h2>
    <div class="meta" style="margin-bottom:10px;">Configure tenant-level notification email delivery with SMTP or Microsoft Graph. Technical queue/timeout settings are tenant-admin tunable.</div>

    <div class="grid grid-2">
      <label>Provider
        <select name="email_provider">
          <option value="disabled" <%= "disabled".equals(emailProviderMode) ? "selected" : "" %>>Disabled</option>
          <option value="smtp" <%= "smtp".equals(emailProviderMode) ? "selected" : "" %>>SMTP</option>
          <option value="microsoft_graph" <%= "microsoft_graph".equals(emailProviderMode) ? "selected" : "" %>>Microsoft Graph</option>
        </select>
      </label>
      <label>From Address
        <input type="text" name="email_from_address" value="<%= tsEsc(tsSafe(settings.get("email_from_address"))) %>" placeholder="notifications@example.com" />
      </label>
      <label>From Name
        <input type="text" name="email_from_name" value="<%= tsEsc(tsSafe(settings.get("email_from_name"))) %>" placeholder="Controversies Notifications" />
      </label>
      <label>Reply-To
        <input type="text" name="email_reply_to" value="<%= tsEsc(tsSafe(settings.get("email_reply_to"))) %>" placeholder="support@example.com" />
      </label>
      <label>Connect Timeout (ms)
        <input type="number" min="1000" max="120000" name="email_connect_timeout_ms" value="<%= tsEsc(tsIntRange(settings.get("email_connect_timeout_ms"), 15000, 1000, 120000)) %>" />
      </label>
      <label>Read Timeout (ms)
        <input type="number" min="1000" max="180000" name="email_read_timeout_ms" value="<%= tsEsc(tsIntRange(settings.get("email_read_timeout_ms"), 20000, 1000, 180000)) %>" />
      </label>
      <label>Queue Poll Seconds
        <input type="number" min="1" max="300" name="email_queue_poll_seconds" value="<%= tsEsc(tsIntRange(settings.get("email_queue_poll_seconds"), 5, 1, 300)) %>" />
      </label>
      <label>Queue Batch Size
        <input type="number" min="1" max="200" name="email_queue_batch_size" value="<%= tsEsc(tsIntRange(settings.get("email_queue_batch_size"), 10, 1, 200)) %>" />
      </label>
      <label>Queue Max Attempts
        <input type="number" min="1" max="50" name="email_queue_max_attempts" value="<%= tsEsc(tsIntRange(settings.get("email_queue_max_attempts"), 8, 1, 50)) %>" />
      </label>
      <label>Queue Backoff Base (ms)
        <input type="number" min="100" max="600000" name="email_queue_backoff_base_ms" value="<%= tsEsc(tsIntRange(settings.get("email_queue_backoff_base_ms"), 2000, 100, 600000)) %>" />
      </label>
      <label>Queue Backoff Max (ms)
        <input type="number" min="500" max="3600000" name="email_queue_backoff_max_ms" value="<%= tsEsc(tsIntRange(settings.get("email_queue_backoff_max_ms"), 300000, 500, 3600000)) %>" />
      </label>
      <div></div>
    </div>

    <h3 style="margin-top:14px;">SMTP Settings</h3>
    <div class="grid grid-2">
      <label>SMTP Host
        <input type="text" name="email_smtp_host" value="<%= tsEsc(tsSafe(settings.get("email_smtp_host"))) %>" placeholder="smtp.example.com" />
      </label>
      <label>SMTP Port
        <input type="number" min="1" max="65535" name="email_smtp_port" value="<%= tsEsc(tsIntRange(settings.get("email_smtp_port"), 587, 1, 65535)) %>" />
      </label>
      <label>SMTP Username
        <input type="text" name="email_smtp_username" value="<%= tsEsc(tsSafe(settings.get("email_smtp_username"))) %>" />
      </label>
      <label>SMTP Password
        <input type="password" name="email_smtp_password" value="" placeholder="<%= tsEsc(maskedEmailSmtpPassword) %>" />
      </label>
      <label>SMTP HELO Domain (optional)
        <input type="text" name="email_smtp_helo_domain" value="<%= tsEsc(tsSafe(settings.get("email_smtp_helo_domain"))) %>" />
      </label>
      <div style="display:flex; gap:10px; align-items:center; flex-wrap:wrap;">
        <label><input type="checkbox" name="email_smtp_auth" value="1" <%= "true".equalsIgnoreCase(tsSafe(settings.get("email_smtp_auth"))) ? "checked" : "" %> /> SMTP Auth</label>
        <label><input type="checkbox" name="email_smtp_starttls" value="1" <%= "true".equalsIgnoreCase(tsSafe(settings.get("email_smtp_starttls"))) ? "checked" : "" %> /> STARTTLS</label>
        <label><input type="checkbox" name="email_smtp_ssl" value="1" <%= "true".equalsIgnoreCase(tsSafe(settings.get("email_smtp_ssl"))) ? "checked" : "" %> /> SSL</label>
      </div>
    </div>

    <h3 style="margin-top:14px;">Microsoft Graph Settings</h3>
    <div class="grid grid-2">
      <label>Tenant ID
        <input type="text" name="email_graph_tenant_id" value="<%= tsEsc(tsSafe(settings.get("email_graph_tenant_id"))) %>" />
      </label>
      <label>Client ID
        <input type="text" name="email_graph_client_id" value="<%= tsEsc(tsSafe(settings.get("email_graph_client_id"))) %>" />
      </label>
      <label>Client Secret
        <input type="password" name="email_graph_client_secret" value="" placeholder="<%= tsEsc(maskedEmailGraphClientSecret) %>" />
      </label>
      <label>Sender User/Mailbox
        <input type="text" name="email_graph_sender_user" value="<%= tsEsc(tsSafe(settings.get("email_graph_sender_user"))) %>" placeholder="notifications@yourtenant.com" />
      </label>
      <label>Scope
        <input type="text" name="email_graph_scope" value="<%= tsEsc(tsSafe(settings.get("email_graph_scope"))) %>" placeholder="https://graph.microsoft.com/.default" />
      </label>
      <div></div>
    </div>

    <div class="actions" style="display:flex; gap:10px; margin-top:10px; align-items:center; flex-wrap:wrap;">
      <button type="submit" class="btn btn-ghost" name="action" value="test_email_connection">Test Email Connection</button>
      <label><input type="checkbox" name="save_email_smtp_password" value="1" /> Persist entered SMTP password on Save</label>
      <label><input type="checkbox" name="save_email_graph_client_secret" value="1" /> Persist entered Graph client secret on Save</label>
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
      <div>Email provider: <strong><%= tsEsc(tsSafe(settings.get("email_provider"))) %></strong></div>
      <div>Email connection: <strong><%= tsEsc(tsStatusLabel(tsSafe(settings.get("email_connection_status")))) %></strong> (<%= tsEsc(tsRotationLabel(tsSafe(settings.get("email_connection_checked_at")))) %>)</div>
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
