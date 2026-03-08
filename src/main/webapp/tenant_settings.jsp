<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>

<%@ page import="java.nio.file.Files" %>
<%@ page import="java.nio.file.Path" %>
<%@ page import="java.nio.file.Paths" %>
<%@ page import="java.nio.file.StandardCopyOption" %>
<%@ page import="java.nio.file.StandardOpenOption" %>
<%@ page import="java.util.LinkedHashMap" %>
<%@ page import="java.util.ArrayList" %>
<%@ page import="java.util.Locale" %>
<%@ page import="java.util.List" %>
<%@ page import="java.util.Map" %>
<%@ page import="java.util.Properties" %>

<%@ page import="net.familylawandprobate.controversies.activity_log" %>
<%@ page import="net.familylawandprobate.controversies.secret_redactor" %>
<%@ page import="net.familylawandprobate.controversies.notification_emails" %>
<%@ page import="net.familylawandprobate.controversies.document_taxonomy" %>
<%@ page import="net.familylawandprobate.controversies.tenant_settings" %>
<%@ page import="net.familylawandprobate.controversies.users_roles" %>
<%@ page import="net.familylawandprobate.controversies.api_credentials" %>
<%@ page import="net.familylawandprobate.controversies.integrations.clio.ClioIntegrationService" %>
<%@ page import="net.familylawandprobate.controversies.integrations.office365.Office365ContactsSyncService" %>

<%@ include file="security.jspf" %>

<%!
  private static final String S_TENANT_UUID = "tenant.uuid";
  private static final String S_TENANT_LABEL = "tenant.label";
  private static final String CSRF_SESSION_KEY = "CSRF_TOKEN";
  private static final String S_API_NEW_KEY = "tenant_settings.api.new_key";
  private static final String S_API_NEW_SECRET = "tenant_settings.api.new_secret";
  private static final String S_API_NEW_LABEL = "tenant_settings.api.new_label";
  private static final String SSL_RUNTIME_MODE_KEY = "CONTROVERSIES_SSL_MODE";
  private static final String SSL_RUNTIME_KEYSTORE_PATH_KEY = "CONTROVERSIES_SSL_KEYSTORE_PATH";
  private static final String SSL_RUNTIME_KEYSTORE_ALIAS_KEY = "CONTROVERSIES_SSL_KEYSTORE_ALIAS";
  private static final String SSL_RUNTIME_CERTBOT_DOMAIN_KEY = "CONTROVERSIES_CERTBOT_DOMAIN";
  private static final String SSL_RUNTIME_CERTBOT_LIVE_DIR_KEY = "CONTROVERSIES_CERTBOT_LIVE_DIR";
  private static final String SSL_RUNTIME_CERTBOT_OPENSSL_CMD_KEY = "CONTROVERSIES_CERTBOT_OPENSSL_CMD";
  private static final String SSL_RUNTIME_CERTBOT_FORCE_REBUILD_KEY = "CONTROVERSIES_CERTBOT_FORCE_REBUILD";

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

  private static String tsNormalizeSslMode(String raw) {
    String v = tsSafe(raw).trim().toLowerCase(Locale.ROOT);
    if ("self-signed".equals(v) || "selfsigned".equals(v)) return "self_signed";
    if ("self_signed".equals(v) || "certbot".equals(v) || "custom".equals(v)) return v;
    return "auto";
  }

  private static Path tsRuntimeSslConfigPath() {
    return Paths.get("data", "sec", "ssl", "runtime_ssl.properties").toAbsolutePath().normalize();
  }

  private static Properties tsLoadRuntimeSslConfig() throws Exception {
    Properties p = new Properties();
    Path path = tsRuntimeSslConfigPath();
    if (!Files.isRegularFile(path)) return p;
    try (var in = Files.newInputStream(path, StandardOpenOption.READ)) {
      p.load(in);
    }
    return p;
  }

  private static void tsSetOrRemove(Properties p, String key, String value) {
    String cleaned = tsSafe(value).trim();
    if (cleaned.isBlank()) p.remove(key);
    else p.setProperty(key, cleaned);
  }

  private static void tsSaveRuntimeSslConfig(Properties p) throws Exception {
    Path path = tsRuntimeSslConfigPath();
    Files.createDirectories(path.getParent());
    Path tmp = path.resolveSibling(path.getFileName().toString() + ".tmp");
    try (var out = Files.newOutputStream(tmp,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE)) {
      p.store(out, "Controversies runtime SSL settings");
    }
    try {
      Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (Exception ignored) {
      Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
    }
  }
%>

<%
  String ctx = request.getContextPath();
  if (ctx == null) ctx = "";

  String tenantUuid = tsSafe((String)session.getAttribute(S_TENANT_UUID)).trim();
  String tenantLabel = tsSafe((String)session.getAttribute(S_TENANT_LABEL)).trim();
  String userUuid = tsSafe((String)session.getAttribute(users_roles.S_USER_UUID)).trim();
  boolean canManageApiCredentials = users_roles.hasPermissionTrue(session, "api.credentials.manage");
  if (tenantUuid.isBlank()) {
    response.sendRedirect(ctx + "/tenant_login.jsp");
    return;
  }

  tenant_settings store = tenant_settings.defaultStore();
  api_credentials apiCredentialStore = api_credentials.defaultStore();
  activity_log logs = activity_log.defaultStore();
  document_taxonomy taxonomyStore = document_taxonomy.defaultStore();

  String csrfToken = csrfForRender(request);
  String message = null;
  String error = null;

  LinkedHashMap<String,String> settings = new LinkedHashMap<String,String>();
  settings.putAll(store.read(tenantUuid));
  Properties runtimeSslConfig = new Properties();
  try {
    runtimeSslConfig = tsLoadRuntimeSslConfig();
  } catch (Exception ex) {
    if (error == null || error.isBlank()) {
      error = "Unable to read runtime SSL settings: " + tsSafe(ex.getMessage());
    }
  }
  String sslModeSaved = tsNormalizeSslMode(runtimeSslConfig.getProperty(SSL_RUNTIME_MODE_KEY));
  String sslKeystorePathSaved = tsSafe(runtimeSslConfig.getProperty(SSL_RUNTIME_KEYSTORE_PATH_KEY)).trim();
  String sslKeystoreAliasSaved = tsSafe(runtimeSslConfig.getProperty(SSL_RUNTIME_KEYSTORE_ALIAS_KEY)).trim();
  String sslCertbotDomainSaved = tsSafe(runtimeSslConfig.getProperty(SSL_RUNTIME_CERTBOT_DOMAIN_KEY)).trim();
  String sslCertbotLiveDirSaved = tsSafe(runtimeSslConfig.getProperty(SSL_RUNTIME_CERTBOT_LIVE_DIR_KEY)).trim();
  String sslCertbotOpenSslCmdSaved = tsSafe(runtimeSslConfig.getProperty(SSL_RUNTIME_CERTBOT_OPENSSL_CMD_KEY)).trim();
  boolean sslCertbotForceRebuildSaved = "true".equalsIgnoreCase(tsSafe(
          runtimeSslConfig.getProperty(SSL_RUNTIME_CERTBOT_FORCE_REBUILD_KEY)).trim());

  List<String> setupChecklist = new ArrayList<String>();
  String loadedStorageBackend = tsSafe(settings.get("storage_backend")).trim().toLowerCase(Locale.ROOT);
  String loadedStorageStatus = tsSafe(settings.get("storage_connection_status")).trim().toLowerCase(Locale.ROOT);
  boolean loadedClioEnabled = "true".equalsIgnoreCase(tsSafe(settings.get("clio_enabled")));
  String loadedClioStatus = tsSafe(settings.get("clio_connection_status")).trim().toLowerCase(Locale.ROOT);
  boolean loadedOffice365Enabled = "true".equalsIgnoreCase(tsSafe(settings.get("office365_contacts_sync_enabled")));
  String loadedEmailProvider = tsSafe(settings.get("email_provider")).trim().toLowerCase(Locale.ROOT);
  String loadedEmailStatus = tsSafe(settings.get("email_connection_status")).trim().toLowerCase(Locale.ROOT);
  String loadedTwoFactorPolicy = tsSafe(settings.get("two_factor_policy")).trim().toLowerCase(Locale.ROOT);
  String loadedTwoFactorEngine = tsSafe(settings.get("two_factor_default_engine")).trim().toLowerCase(Locale.ROOT);
  if (loadedStorageBackend.isBlank()) setupChecklist.add("Choose and save a storage backend.");
  if (!"ok".equals(loadedStorageStatus)) setupChecklist.add("Run 'Test Storage Connection' until status is OK.");
  if (loadedClioEnabled && tsSafe(settings.get("clio_base_url")).isBlank()) setupChecklist.add("Enter Clio Base URL if this tenant uses Clio sync.");
  if (loadedClioEnabled && !"ok".equals(loadedClioStatus)) setupChecklist.add("Run 'Test Clio Connection' after credentials are configured.");
  if (loadedClioEnabled && tsSafe(settings.get("clio_matters_last_sync_at")).isBlank()) setupChecklist.add("Run 'Sync Clio Matters + Documents Now' once after enabling Clio sync.");
  if (loadedClioEnabled && tsSafe(settings.get("clio_documents_last_sync_at")).isBlank()) setupChecklist.add("Confirm Clio documents and versions have completed at least one sync cycle.");
  if (loadedClioEnabled && tsSafe(settings.get("clio_contacts_last_sync_at")).isBlank()) setupChecklist.add("Run 'Sync Clio Contacts Now' once after enabling Clio sync.");
  String loadedOffice365Sources = tsSafe(settings.get("office365_contacts_sync_sources_json")).trim();
  if (loadedOffice365Enabled && (loadedOffice365Sources.isBlank() || "[]".equals(loadedOffice365Sources) || "{}".equals(loadedOffice365Sources))) setupChecklist.add("Provide Office 365 contact sync sources JSON for one or more source mailboxes/folders.");
  if (loadedOffice365Enabled && tsSafe(settings.get("office365_contacts_last_sync_at")).isBlank()) setupChecklist.add("Run 'Sync Office 365 Contacts Now' once after enabling Office 365 contact sync.");
  if (!"disabled".equals(loadedEmailProvider) && !"ok".equals(loadedEmailStatus)) {
    setupChecklist.add("Run 'Test Email Connection' after email provider credentials are configured.");
  }
  if ("required".equals(loadedTwoFactorPolicy)) {
    if ("flowroute_sms".equals(loadedTwoFactorEngine)) {
      if (tsSafe(settings.get("flowroute_sms_access_key")).isBlank()
              || tsSafe(settings.get("flowroute_sms_secret_key")).isBlank()
              || tsSafe(settings.get("flowroute_sms_from_number")).isBlank()) {
        setupChecklist.add("Required two-factor is set to Flowroute SMS, but Flowroute credentials are incomplete.");
      }
    } else {
      if ("disabled".equals(loadedEmailProvider)) {
        setupChecklist.add("Required two-factor is set to Email PIN, but notification email is disabled.");
      }
    }
  }
  String activeTab = tsSafe(request.getParameter("tab")).trim().toLowerCase(Locale.ROOT);
  if (!"experience".equals(activeTab) && !"security".equals(activeTab) && !"operations".equals(activeTab)) {
    activeTab = "integrations";
  }
  String tabSuffix = "&tab=" + activeTab;

  if ("POST".equalsIgnoreCase(request.getMethod())) {
    String action = tsSafe(request.getParameter("action")).trim();
    if ("save_ssl_runtime_config".equalsIgnoreCase(action)) {
      try {
        String sslModeInput = tsNormalizeSslMode(request.getParameter("ssl_mode"));
        String sslKeystorePathInput = tsSafe(request.getParameter("ssl_keystore_path")).trim();
        String sslKeystoreAliasInput = tsSafe(request.getParameter("ssl_keystore_alias")).trim();
        String sslCertbotDomainInput = tsSafe(request.getParameter("ssl_certbot_domain")).trim();
        String sslCertbotLiveDirInput = tsSafe(request.getParameter("ssl_certbot_live_dir")).trim();
        String sslCertbotOpenSslCmdInput = tsSafe(request.getParameter("ssl_certbot_openssl_cmd")).trim();
        boolean sslCertbotForceRebuildInput = tsChecked(request.getParameter("ssl_certbot_force_rebuild"));

        Properties updatedRuntimeSsl = tsLoadRuntimeSslConfig();
        tsSetOrRemove(updatedRuntimeSsl, SSL_RUNTIME_MODE_KEY, "auto".equals(sslModeInput) ? "" : sslModeInput);
        tsSetOrRemove(updatedRuntimeSsl, SSL_RUNTIME_KEYSTORE_PATH_KEY, sslKeystorePathInput);
        tsSetOrRemove(updatedRuntimeSsl, SSL_RUNTIME_KEYSTORE_ALIAS_KEY, sslKeystoreAliasInput);
        tsSetOrRemove(updatedRuntimeSsl, SSL_RUNTIME_CERTBOT_DOMAIN_KEY, sslCertbotDomainInput);
        tsSetOrRemove(updatedRuntimeSsl, SSL_RUNTIME_CERTBOT_LIVE_DIR_KEY, sslCertbotLiveDirInput);
        tsSetOrRemove(updatedRuntimeSsl, SSL_RUNTIME_CERTBOT_OPENSSL_CMD_KEY, sslCertbotOpenSslCmdInput);
        tsSetOrRemove(updatedRuntimeSsl, SSL_RUNTIME_CERTBOT_FORCE_REBUILD_KEY,
                sslCertbotForceRebuildInput ? "true" : "");
        tsSaveRuntimeSslConfig(updatedRuntimeSsl);

        LinkedHashMap<String, String> details = new LinkedHashMap<String, String>();
        details.put("ssl_mode", sslModeInput);
        details.put("has_custom_keystore_path", sslKeystorePathInput.isBlank() ? "false" : "true");
        details.put("has_custom_keystore_alias", sslKeystoreAliasInput.isBlank() ? "false" : "true");
        details.put("has_certbot_domain", sslCertbotDomainInput.isBlank() ? "false" : "true");
        details.put("has_certbot_live_dir", sslCertbotLiveDirInput.isBlank() ? "false" : "true");
        details.put("has_certbot_openssl_cmd", sslCertbotOpenSslCmdInput.isBlank() ? "false" : "true");
        details.put("certbot_force_rebuild", sslCertbotForceRebuildInput ? "true" : "false");
        logs.logVerbose("tenant_settings_ssl_runtime_saved", tenantUuid, userUuid, "", "", details);

        response.sendRedirect(ctx + "/tenant_settings.jsp?status=ssl_runtime_saved&tab=operations");
        return;
      } catch (Exception ex) {
        error = "Unable to save SSL runtime settings: " + tsSafe(ex.getMessage());
        logs.logVerbose("tenant_settings_ssl_runtime_save_failed", tenantUuid, userUuid, "", "", Map.of(
          "reason", tsSafe(ex.getClass().getSimpleName())
        ));
      }
    }

    if ("generate_api_credential".equalsIgnoreCase(action)) {
      if (!canManageApiCredentials) {
        error = "Permission denied: api.credentials.manage is required.";
      } else {
        try {
          String apiLabel = tsSafe(request.getParameter("api_credential_label")).trim();
          api_credentials.GeneratedCredential generated = apiCredentialStore.create(tenantUuid, apiLabel, userUuid);
          session.setAttribute(S_API_NEW_KEY, generated.apiKey);
          session.setAttribute(S_API_NEW_SECRET, generated.apiSecret);
          session.setAttribute(S_API_NEW_LABEL, generated.credential == null ? "" : tsSafe(generated.credential.label));
          logs.logVerbose("tenant_settings_api_credential_created", tenantUuid, userUuid, "", "", Map.of("label", apiLabel));
          response.sendRedirect(ctx + "/tenant_settings.jsp?status=api_credential_created" + tabSuffix);
          return;
        } catch (Exception ex) {
          error = "Unable to create API credential: " + tsSafe(ex.getMessage());
        }
      }
    }

    if ("revoke_api_credential".equalsIgnoreCase(action)) {
      if (!canManageApiCredentials) {
        error = "Permission denied: api.credentials.manage is required.";
      } else {
        try {
          String credentialId = tsSafe(request.getParameter("api_credential_id")).trim();
          boolean changed = apiCredentialStore.revoke(tenantUuid, credentialId);
          if (changed) {
            logs.logVerbose("tenant_settings_api_credential_revoked", tenantUuid, userUuid, "", "", Map.of("credential_id", credentialId));
            response.sendRedirect(ctx + "/tenant_settings.jsp?status=api_credential_revoked" + tabSuffix);
            return;
          }
          error = "API credential not found or already revoked.";
        } catch (Exception ex) {
          error = "Unable to revoke API credential: " + tsSafe(ex.getMessage());
        }
      }
    }

    if ("add_taxonomy".equalsIgnoreCase(action)) {
      try {
        taxonomyStore.addValues(
          tenantUuid,
          java.util.Arrays.asList(tsSafe(request.getParameter("taxonomy_category"))),
          java.util.Arrays.asList(tsSafe(request.getParameter("taxonomy_subcategory"))),
          java.util.Arrays.asList(tsSafe(request.getParameter("taxonomy_status")))
        );
        logs.logVerbose("tenant_settings_taxonomy_updated", tenantUuid, userUuid, "", "", Map.of("action", "add_taxonomy"));
        response.sendRedirect(ctx + "/tenant_settings.jsp?status=taxonomy_saved" + tabSuffix);
        return;
      } catch (Exception ex) {
        error = "Unable to update taxonomy: " + tsSafe(ex.getMessage());
      }
    }

    String storageBackend = tsSafe(request.getParameter("storage_backend")).trim();
    if (storageBackend.isBlank()) storageBackend = "local";
    String storageBackendLower = storageBackend.toLowerCase(Locale.ROOT);
    if ("onedrive".equals(storageBackendLower) || "onedrive_for_business".equals(storageBackendLower) || "onedrive-for-business".equals(storageBackendLower)) {
      storageBackend = "onedrive_business";
    }
    String storageEndpoint = tsSafe(request.getParameter("storage_endpoint")).trim();
    String storageRootFolder = tsSafe(request.getParameter("storage_root_folder")).trim();
    String storageAccessKey = tsSafe(request.getParameter("storage_access_key")).trim();
    String storageSecret = tsSafe(request.getParameter("storage_secret")).trim();
    String storageEncryptionMode = tsSafe(request.getParameter("storage_encryption_mode")).trim().toLowerCase(Locale.ROOT);
    if (!"tenant_managed".equals(storageEncryptionMode)) storageEncryptionMode = "disabled";
    String storageEncryptionKey = tsSafe(request.getParameter("storage_encryption_key")).trim();
    String storageS3SseMode = tsSafe(request.getParameter("storage_s3_sse_mode")).trim().toLowerCase(Locale.ROOT);
    if (!"aes256".equals(storageS3SseMode) && !"aws_kms".equals(storageS3SseMode)) storageS3SseMode = "none";
    String storageS3SseKmsKeyId = tsSafe(request.getParameter("storage_s3_sse_kms_key_id")).trim();
    String storageOnedriveAuthMode = tsSafe(request.getParameter("storage_onedrive_auth_mode")).trim().toLowerCase(Locale.ROOT);
    if (!"public".equals(storageOnedriveAuthMode) && !"private".equals(storageOnedriveAuthMode) && !"app_credentials".equals(storageOnedriveAuthMode)) {
      storageOnedriveAuthMode = "app_credentials";
    }
    String storageOnedriveOauthCallbackUrl = tsSafe(request.getParameter("storage_onedrive_oauth_callback_url")).trim();
    String storageOnedrivePrivateRelayUrl = tsSafe(request.getParameter("storage_onedrive_private_relay_url")).trim();
    String storageCacheSizeFtpMb = tsIntRange(request.getParameter("storage_cache_size_ftp_mb"), 1024, 0, 1048576);
    String storageCacheSizeFtpsMb = tsIntRange(request.getParameter("storage_cache_size_ftps_mb"), 1024, 0, 1048576);
    String storageCacheSizeSftpMb = tsIntRange(request.getParameter("storage_cache_size_sftp_mb"), 1024, 0, 1048576);
    String storageCacheSizeWebdavMb = tsIntRange(request.getParameter("storage_cache_size_webdav_mb"), 1024, 0, 1048576);
    String storageCacheSizeS3CompatibleMb = tsIntRange(request.getParameter("storage_cache_size_s3_compatible_mb"), 1024, 0, 1048576);
    String storageCacheSizeOnedriveBusinessMb = tsIntRange(request.getParameter("storage_cache_size_onedrive_business_mb"), 1024, 0, 1048576);
    String storageMaxPathLength = tsIntRange(request.getParameter("storage_max_path_length"), 0, 0, 8192);
    String storageMaxFilenameLength = tsIntRange(request.getParameter("storage_max_filename_length"), 0, 0, 1024);

    String clioBaseUrl = tsSafe(request.getParameter("clio_base_url")).trim();
    String clioClientId = tsSafe(request.getParameter("clio_client_id")).trim();
    String clioClientSecret = tsSafe(request.getParameter("clio_client_secret")).trim();

    String clioAuthMode = tsSafe(request.getParameter("clio_auth_mode")).trim().toLowerCase(Locale.ROOT);
    if (!"private".equals(clioAuthMode)) clioAuthMode = "public";
    String clioOauthCallbackUrl = tsSafe(request.getParameter("clio_oauth_callback_url")).trim();
    String clioPrivateRelayUrl = tsSafe(request.getParameter("clio_private_relay_url")).trim();
    boolean clioEnabled = tsChecked(request.getParameter("clio_enabled"));
    String clioStorageMode = tsSafe(request.getParameter("clio_storage_mode")).trim().toLowerCase(Locale.ROOT);
    if (!"enabled".equals(clioStorageMode)) clioStorageMode = "disabled";
    String clioMattersSyncIntervalMinutes = tsIntRange(request.getParameter("clio_matters_sync_interval_minutes"), 15, 1, 1440);
    boolean office365ContactsSyncEnabled = tsChecked(request.getParameter("office365_contacts_sync_enabled"));
    String office365ContactsSyncIntervalMinutes = tsIntRange(request.getParameter("office365_contacts_sync_interval_minutes"), 30, 1, 1440);
    String office365ContactsSyncSourcesJson = tsSafe(request.getParameter("office365_contacts_sync_sources_json")).trim();
    if (office365ContactsSyncSourcesJson.isBlank()) office365ContactsSyncSourcesJson = "[]";

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
    boolean passwordPolicyEnabled = tsChecked(request.getParameter("password_policy_enabled"));
    String passwordPolicyMinLength = tsIntRange(request.getParameter("password_policy_min_length"), 12, 8, 128);
    boolean passwordPolicyRequireUppercase = tsChecked(request.getParameter("password_policy_require_uppercase"));
    boolean passwordPolicyRequireLowercase = tsChecked(request.getParameter("password_policy_require_lowercase"));
    boolean passwordPolicyRequireNumber = tsChecked(request.getParameter("password_policy_require_number"));
    boolean passwordPolicyRequireSymbol = tsChecked(request.getParameter("password_policy_require_symbol"));
    String twoFactorPolicy = tsSafe(request.getParameter("two_factor_policy")).trim().toLowerCase(Locale.ROOT);
    if (!"optional".equals(twoFactorPolicy) && !"required".equals(twoFactorPolicy)) twoFactorPolicy = "off";
    String twoFactorDefaultEngine = tsSafe(request.getParameter("two_factor_default_engine")).trim().toLowerCase(Locale.ROOT);
    if (!"flowroute_sms".equals(twoFactorDefaultEngine)) twoFactorDefaultEngine = "email_pin";
    String flowrouteSmsFromNumber = tsSafe(request.getParameter("flowroute_sms_from_number")).trim();
    flowrouteSmsFromNumber = flowrouteSmsFromNumber.replaceAll("[^0-9+]", "");
    String flowrouteSmsAccessKey = tsSafe(request.getParameter("flowroute_sms_access_key")).trim();
    String flowrouteSmsSecretKey = tsSafe(request.getParameter("flowroute_sms_secret_key")).trim();
    String flowrouteSmsApiBaseUrl = tsSafe(request.getParameter("flowroute_sms_api_base_url")).trim();
    if (flowrouteSmsApiBaseUrl.isBlank()) flowrouteSmsApiBaseUrl = "https://api.flowroute.com/v2.2/messages";

    boolean saveStorageSecret = tsChecked(request.getParameter("save_storage_secret"));
    boolean saveStorageEncryptionKey = tsChecked(request.getParameter("save_storage_encryption_key"));
    boolean saveClioSecret = tsChecked(request.getParameter("save_clio_secret"));
    boolean saveEmailSmtpPassword = tsChecked(request.getParameter("save_email_smtp_password"));
    boolean saveEmailGraphClientSecret = tsChecked(request.getParameter("save_email_graph_client_secret"));
    boolean saveFlowrouteSmsAccessKey = tsChecked(request.getParameter("save_flowroute_sms_access_key"));
    boolean saveFlowrouteSmsSecretKey = tsChecked(request.getParameter("save_flowroute_sms_secret_key"));

    settings.put("storage_backend", storageBackend);
    settings.put("storage_endpoint", storageEndpoint);
    settings.put("storage_root_folder", storageRootFolder);
    settings.put("storage_encryption_mode", storageEncryptionMode);
    settings.put("storage_s3_sse_mode", storageS3SseMode);
    settings.put("storage_s3_sse_kms_key_id", storageS3SseKmsKeyId);
    settings.put("storage_onedrive_auth_mode", storageOnedriveAuthMode);
    settings.put("storage_onedrive_oauth_callback_url", storageOnedriveOauthCallbackUrl);
    settings.put("storage_onedrive_private_relay_url", storageOnedrivePrivateRelayUrl);
    settings.put("storage_cache_size_ftp_mb", storageCacheSizeFtpMb);
    settings.put("storage_cache_size_ftps_mb", storageCacheSizeFtpsMb);
    settings.put("storage_cache_size_sftp_mb", storageCacheSizeSftpMb);
    settings.put("storage_cache_size_webdav_mb", storageCacheSizeWebdavMb);
    settings.put("storage_cache_size_s3_compatible_mb", storageCacheSizeS3CompatibleMb);
    settings.put("storage_cache_size_onedrive_business_mb", storageCacheSizeOnedriveBusinessMb);
    settings.put("storage_max_path_length", storageMaxPathLength);
    settings.put("storage_max_filename_length", storageMaxFilenameLength);
    if (!"onedrive_business".equalsIgnoreCase(storageBackend)) {
      settings.put("storage_onedrive_auth_mode", "app_credentials");
      settings.put("storage_onedrive_oauth_callback_url", "");
      settings.put("storage_onedrive_private_relay_url", "");
    }
    if (!storageAccessKey.isBlank()) settings.put("storage_access_key", storageAccessKey);
    settings.put("clio_base_url", clioBaseUrl);
    settings.put("clio_client_id", clioClientId);
    settings.put("clio_auth_mode", clioAuthMode);
    settings.put("clio_oauth_callback_url", clioOauthCallbackUrl);
    settings.put("clio_private_relay_url", clioPrivateRelayUrl);
    settings.put("clio_enabled", clioEnabled ? "true" : "false");
    settings.put("clio_storage_mode", clioStorageMode);
    settings.put("clio_matters_sync_interval_minutes", clioMattersSyncIntervalMinutes);
    settings.put("office365_contacts_sync_enabled", office365ContactsSyncEnabled ? "true" : "false");
    settings.put("office365_contacts_sync_interval_minutes", office365ContactsSyncIntervalMinutes);
    settings.put("office365_contacts_sync_sources_json", office365ContactsSyncSourcesJson);
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
    settings.put("password_policy_enabled", passwordPolicyEnabled ? "true" : "false");
    settings.put("password_policy_min_length", passwordPolicyMinLength);
    settings.put("password_policy_require_uppercase", passwordPolicyRequireUppercase ? "true" : "false");
    settings.put("password_policy_require_lowercase", passwordPolicyRequireLowercase ? "true" : "false");
    settings.put("password_policy_require_number", passwordPolicyRequireNumber ? "true" : "false");
    settings.put("password_policy_require_symbol", passwordPolicyRequireSymbol ? "true" : "false");
    settings.put("two_factor_policy", twoFactorPolicy);
    settings.put("two_factor_default_engine", twoFactorDefaultEngine);
    settings.put("flowroute_sms_from_number", flowrouteSmsFromNumber);
    settings.put("flowroute_sms_api_base_url", flowrouteSmsApiBaseUrl);

    if (saveStorageSecret && !storageSecret.isBlank()) settings.put("storage_secret", storageSecret);
    if (saveStorageEncryptionKey && !storageEncryptionKey.isBlank()) settings.put("storage_encryption_key", storageEncryptionKey);
    if (saveClioSecret && !clioClientSecret.isBlank()) settings.put("clio_client_secret", clioClientSecret);
    if (saveEmailSmtpPassword && !emailSmtpPassword.isBlank()) settings.put("email_smtp_password", emailSmtpPassword);
    if (saveEmailGraphClientSecret && !emailGraphClientSecret.isBlank()) settings.put("email_graph_client_secret", emailGraphClientSecret);
    if (saveFlowrouteSmsAccessKey && !flowrouteSmsAccessKey.isBlank()) settings.put("flowroute_sms_access_key", flowrouteSmsAccessKey);
    if (saveFlowrouteSmsSecretKey && !flowrouteSmsSecretKey.isBlank()) settings.put("flowroute_sms_secret_key", flowrouteSmsSecretKey);

    String effectiveStorageAccessKey = tsSafe(settings.get("storage_access_key")).trim();
    String effectiveStorageSecret = !storageSecret.isBlank() ? storageSecret : tsSafe(settings.get("storage_secret")).trim();
    String effectiveStorageEncryptionKey = !storageEncryptionKey.isBlank() ? storageEncryptionKey : tsSafe(settings.get("storage_encryption_key")).trim();
    String effectiveClioSecret = !clioClientSecret.isBlank() ? clioClientSecret : tsSafe(settings.get("clio_client_secret")).trim();
    String effectiveEmailSmtpPassword = !emailSmtpPassword.isBlank() ? emailSmtpPassword : tsSafe(settings.get("email_smtp_password")).trim();
    String effectiveEmailGraphClientSecret = !emailGraphClientSecret.isBlank() ? emailGraphClientSecret : tsSafe(settings.get("email_graph_client_secret")).trim();
    String effectiveFlowrouteSmsAccessKey = !flowrouteSmsAccessKey.isBlank() ? flowrouteSmsAccessKey : tsSafe(settings.get("flowroute_sms_access_key")).trim();
    String effectiveFlowrouteSmsSecretKey = !flowrouteSmsSecretKey.isBlank() ? flowrouteSmsSecretKey : tsSafe(settings.get("flowroute_sms_secret_key")).trim();

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
      if ("onedrive_business".equalsIgnoreCase(storageBackend)) {
        if ("public".equals(storageOnedriveAuthMode) && (storageOnedriveOauthCallbackUrl.isBlank() || !storageOnedriveOauthCallbackUrl.startsWith("http"))) {
          ok = false;
          storageFailures.add("OneDrive public profile requires OAuth callback URL");
        }
        if ("private".equals(storageOnedriveAuthMode) && storageOnedrivePrivateRelayUrl.isBlank()) {
          ok = false;
          storageFailures.add("OneDrive private profile requires relay/admin exchange URL");
        }
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
      details.put("storage_root_folder", storageRootFolder);
      details.put("storage_encryption_mode", storageEncryptionMode);
      details.put("storage_s3_sse_mode", storageS3SseMode);
      details.put("storage_onedrive_auth_mode", storageOnedriveAuthMode);
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

    } else if ("sync_clio_matters_now".equalsIgnoreCase(action)) {
      try {
        ClioIntegrationService clioService = new ClioIntegrationService();
        int synced = clioService.syncMatters(tenantUuid, true);
        ClioIntegrationService.DocumentSyncResult docsResult = clioService.syncDocuments(tenantUuid, true);
        settings.putAll(store.read(tenantUuid));
        if (docsResult.ok) {
          message = "Clio sync completed. Synchronized " + synced + " matter(s), upserted "
                  + docsResult.documentsUpserted + " document(s), and imported "
                  + docsResult.versionsImported + " version(s).";
        } else {
          error = "Matter sync completed, but document sync had issues: " + tsSafe(docsResult.error);
        }
        logs.logVerbose("tenant_settings_clio_sync_now", tenantUuid, userUuid, "", "", Map.of(
          "result", docsResult.ok ? "ok" : "failed",
          "synced", String.valueOf(synced),
          "documents_upserted", String.valueOf(docsResult.documentsUpserted),
          "versions_imported", String.valueOf(docsResult.versionsImported)
        ));
      } catch (Exception ex) {
        error = "Unable to sync Clio matters/documents: " + tsSafe(ex.getMessage());
        logs.logVerbose("tenant_settings_clio_sync_now", tenantUuid, userUuid, "", "", Map.of(
          "result", "failed",
          "error", tsSafe(ex.getClass().getSimpleName())
        ));
      }

    } else if ("sync_clio_contacts_now".equalsIgnoreCase(action)) {
      try {
        ClioIntegrationService.ContactSyncResult result = new ClioIntegrationService().syncContactsToMatters(tenantUuid, true);
        settings.putAll(store.read(tenantUuid));
        if (result.ok) {
          message = "Clio contact sync completed. Upserted " + result.contactsUpserted
                  + " contact(s) and applied " + result.linksApplied + " matter-contact link(s).";
        } else {
          error = "Clio contact sync completed with issues: " + tsSafe(result.error);
        }
        logs.logVerbose("tenant_settings_clio_contacts_sync_now", tenantUuid, userUuid, "", "", Map.of(
          "result", result.ok ? "ok" : "failed",
          "contacts_upserted", String.valueOf(result.contactsUpserted),
          "links_applied", String.valueOf(result.linksApplied),
          "link_errors", String.valueOf(result.linkErrors)
        ));
      } catch (Exception ex) {
        error = "Unable to sync Clio contacts: " + tsSafe(ex.getMessage());
        logs.logVerbose("tenant_settings_clio_contacts_sync_now", tenantUuid, userUuid, "", "", Map.of(
          "result", "failed",
          "error", tsSafe(ex.getClass().getSimpleName())
        ));
      }

    } else if ("sync_office365_contacts_now".equalsIgnoreCase(action)) {
      try {
        Office365ContactsSyncService.SyncResult result = new Office365ContactsSyncService().syncContacts(tenantUuid, true);
        settings.putAll(store.read(tenantUuid));
        if (result.ok) {
          message = "Office 365 contact sync completed. Upserted " + result.contactsUpserted
                  + " contact(s) from " + result.sourcesProcessed + " source(s).";
        } else {
          error = "Office 365 contact sync completed with issues: " + tsSafe(result.error);
        }
        logs.logVerbose("tenant_settings_office365_contacts_sync_now", tenantUuid, userUuid, "", "", Map.of(
          "result", result.ok ? "ok" : "failed",
          "sources_configured", String.valueOf(result.sourcesConfigured),
          "sources_processed", String.valueOf(result.sourcesProcessed),
          "contacts_upserted", String.valueOf(result.contactsUpserted)
        ));
      } catch (Exception ex) {
        error = "Unable to sync Office 365 contacts: " + tsSafe(ex.getMessage());
        logs.logVerbose("tenant_settings_office365_contacts_sync_now", tenantUuid, userUuid, "", "", Map.of(
          "result", "failed",
          "error", tsSafe(ex.getClass().getSimpleName())
        ));
      }

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
      String officeSourcesRaw = tsSafe(office365ContactsSyncSourcesJson).trim();
      if (office365ContactsSyncEnabled
              && (officeSourcesRaw.isBlank() || "[]".equals(officeSourcesRaw) || "{}".equals(officeSourcesRaw))) {
        valid = false;
        error = "Office 365 contact sync is enabled, but no sync sources are configured.";
      }
      if ("tenant_managed".equals(storageEncryptionMode) && effectiveStorageEncryptionKey.isBlank()) {
        valid = false;
        error = "Tenant-managed encryption requires an application encryption key.";
      }
      if ("s3_compatible".equalsIgnoreCase(storageBackend) && "aws_kms".equals(storageS3SseMode) && storageS3SseKmsKeyId.isBlank()) {
        valid = false;
        error = "S3 SSE aws_kms requires a KMS key id.";
      }
      if ("onedrive_business".equalsIgnoreCase(storageBackend)) {
        if ("public".equals(storageOnedriveAuthMode) && (storageOnedriveOauthCallbackUrl.isBlank() || !storageOnedriveOauthCallbackUrl.startsWith("http"))) {
          valid = false;
          error = "OneDrive public auth profile requires a web-accessible OAuth callback URL.";
        }
        if ("private".equals(storageOnedriveAuthMode) && storageOnedrivePrivateRelayUrl.isBlank()) {
          valid = false;
          error = "OneDrive private auth profile requires relay/admin exchange instructions endpoint or note.";
        }
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
      if ("required".equals(twoFactorPolicy)) {
        if ("flowroute_sms".equals(twoFactorDefaultEngine)) {
          if (effectiveFlowrouteSmsAccessKey.isBlank()
                  || effectiveFlowrouteSmsSecretKey.isBlank()
                  || flowrouteSmsFromNumber.isBlank()) {
            valid = false;
            error = "Required two-factor with Flowroute SMS needs access key, secret key, and from number.";
          }
          if (!flowrouteSmsApiBaseUrl.startsWith("http://") && !flowrouteSmsApiBaseUrl.startsWith("https://")) {
            valid = false;
            error = "Flowroute API endpoint must start with http:// or https://.";
          }
        } else {
          if ("disabled".equals(emailProvider) || !emailValidation.ok) {
            valid = false;
            error = "Required two-factor with Email PIN needs a valid notification email provider configuration.";
          }
        }
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
          details.put("storage_root_folder", tsSafe(settings.get("storage_root_folder")));
          details.put("feature_advanced_assembly", tsSafe(settings.get("feature_advanced_assembly")));
          details.put("feature_async_sync", tsSafe(settings.get("feature_async_sync")));
          details.put("theme_mode_default", tsSafe(settings.get("theme_mode_default")));
          details.put("theme_use_location", tsSafe(settings.get("theme_use_location")));
          details.put("theme_text_size_default", tsSafe(settings.get("theme_text_size_default")));
          details.put("password_policy_enabled", tsSafe(settings.get("password_policy_enabled")));
          details.put("password_policy_min_length", tsSafe(settings.get("password_policy_min_length")));
          details.put("password_policy_require_uppercase", tsSafe(settings.get("password_policy_require_uppercase")));
          details.put("password_policy_require_lowercase", tsSafe(settings.get("password_policy_require_lowercase")));
          details.put("password_policy_require_number", tsSafe(settings.get("password_policy_require_number")));
          details.put("password_policy_require_symbol", tsSafe(settings.get("password_policy_require_symbol")));
          details.put("storage_connection_status", tsSafe(settings.get("storage_connection_status")));
          details.put("storage_encryption_mode", tsSafe(settings.get("storage_encryption_mode")));
          details.put("storage_s3_sse_mode", tsSafe(settings.get("storage_s3_sse_mode")));
          details.put("storage_onedrive_auth_mode", tsSafe(settings.get("storage_onedrive_auth_mode")));
          details.put("storage_cache_size_ftp_mb", tsSafe(settings.get("storage_cache_size_ftp_mb")));
          details.put("storage_cache_size_ftps_mb", tsSafe(settings.get("storage_cache_size_ftps_mb")));
          details.put("storage_cache_size_sftp_mb", tsSafe(settings.get("storage_cache_size_sftp_mb")));
          details.put("storage_cache_size_webdav_mb", tsSafe(settings.get("storage_cache_size_webdav_mb")));
          details.put("storage_cache_size_s3_compatible_mb", tsSafe(settings.get("storage_cache_size_s3_compatible_mb")));
          details.put("storage_cache_size_onedrive_business_mb", tsSafe(settings.get("storage_cache_size_onedrive_business_mb")));
          details.put("storage_max_path_length", tsSafe(settings.get("storage_max_path_length")));
          details.put("storage_max_filename_length", tsSafe(settings.get("storage_max_filename_length")));
          details.put("clio_connection_status", tsSafe(settings.get("clio_connection_status")));
          details.put("clio_auth_mode", tsSafe(settings.get("clio_auth_mode")));
          details.put("clio_auth_health_status", tsSafe(settings.get("clio_auth_health_status")));
          details.put("clio_enabled", tsSafe(settings.get("clio_enabled")));
          details.put("clio_storage_mode", tsSafe(settings.get("clio_storage_mode")));
          details.put("clio_matters_sync_interval_minutes", tsSafe(settings.get("clio_matters_sync_interval_minutes")));
          details.put("clio_documents_last_sync_at", tsSafe(settings.get("clio_documents_last_sync_at")));
          details.put("clio_contacts_last_sync_status", tsSafe(settings.get("clio_contacts_last_sync_status")));
          details.put("clio_contacts_last_sync_at", tsSafe(settings.get("clio_contacts_last_sync_at")));
          details.put("office365_contacts_sync_enabled", tsSafe(settings.get("office365_contacts_sync_enabled")));
          details.put("office365_contacts_sync_interval_minutes", tsSafe(settings.get("office365_contacts_sync_interval_minutes")));
          details.put("office365_contacts_last_sync_status", tsSafe(settings.get("office365_contacts_last_sync_status")));
          details.put("office365_contacts_last_sync_at", tsSafe(settings.get("office365_contacts_last_sync_at")));
          details.put("email_provider", tsSafe(settings.get("email_provider")));
          details.put("email_connection_status", tsSafe(settings.get("email_connection_status")));
          details.put("email_queue_batch_size", tsSafe(settings.get("email_queue_batch_size")));
          details.put("email_queue_max_attempts", tsSafe(settings.get("email_queue_max_attempts")));
          details.put("two_factor_policy", tsSafe(settings.get("two_factor_policy")));
          details.put("two_factor_default_engine", tsSafe(settings.get("two_factor_default_engine")));
          details.put("flowroute_sms_from_number_set", tsSafe(settings.get("flowroute_sms_from_number")).isBlank() ? "no" : "yes");
          details.put("storage_secret_saved", tsSafe(settings.get("storage_secret")).isBlank() ? "no" : "yes");
          details.put("storage_encryption_key_saved", tsSafe(settings.get("storage_encryption_key")).isBlank() ? "no" : "yes");
          details.put("clio_client_secret_saved", tsSafe(settings.get("clio_client_secret")).isBlank() ? "no" : "yes");
          details.put("email_smtp_password_saved", tsSafe(settings.get("email_smtp_password")).isBlank() ? "no" : "yes");
          details.put("email_graph_client_secret_saved", tsSafe(settings.get("email_graph_client_secret")).isBlank() ? "no" : "yes");
          details.put("flowroute_sms_access_key_saved", tsSafe(settings.get("flowroute_sms_access_key")).isBlank() ? "no" : "yes");
          details.put("flowroute_sms_secret_key_saved", tsSafe(settings.get("flowroute_sms_secret_key")).isBlank() ? "no" : "yes");
          details.put("save_storage_secret_requested", saveStorageSecret ? "true" : "false");
          details.put("save_storage_encryption_key_requested", saveStorageEncryptionKey ? "true" : "false");
          details.put("save_clio_secret_requested", saveClioSecret ? "true" : "false");
          details.put("save_email_smtp_password_requested", saveEmailSmtpPassword ? "true" : "false");
          details.put("save_email_graph_client_secret_requested", saveEmailGraphClientSecret ? "true" : "false");
          details.put("save_flowroute_sms_access_key_requested", saveFlowrouteSmsAccessKey ? "true" : "false");
          details.put("save_flowroute_sms_secret_key_requested", saveFlowrouteSmsSecretKey ? "true" : "false");
          logs.logVerbose("tenant_settings_changed", tenantUuid, userUuid, "", "", details);

          String status = "saved";
          if ("rotate_storage_secret".equalsIgnoreCase(action)) status = "rotated_storage";
          if ("rotate_clio_secret".equalsIgnoreCase(action)) status = "rotated_clio";
          response.sendRedirect(ctx + "/tenant_settings.jsp?status=" + status + tabSuffix);
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
  if ("api_credential_created".equals(status)) message = "API credential created.";
  if ("api_credential_revoked".equals(status)) message = "API credential revoked.";
  if ("ssl_runtime_saved".equals(status)) message = "TLS runtime settings saved. Restart the application to apply updates.";

  String newApiKey = tsSafe((String)session.getAttribute(S_API_NEW_KEY)).trim();
  String newApiSecret = tsSafe((String)session.getAttribute(S_API_NEW_SECRET)).trim();
  String newApiLabel = tsSafe((String)session.getAttribute(S_API_NEW_LABEL)).trim();
  if (!newApiKey.isBlank() || !newApiSecret.isBlank()) {
    session.removeAttribute(S_API_NEW_KEY);
    session.removeAttribute(S_API_NEW_SECRET);
    session.removeAttribute(S_API_NEW_LABEL);
  }

  String maskedStorageSecret = tsSafe(settings.get("storage_secret")).isBlank() ? "" : "********";
  String maskedClioSecret = tsSafe(settings.get("clio_client_secret")).isBlank() ? "" : "********";
  String maskedStorageEncryptionKey = tsSafe(settings.get("storage_encryption_key")).isBlank() ? "" : "********";
  String maskedStorageAccessKey = tsSafe(settings.get("storage_access_key")).isBlank() ? "" : "********";
  String maskedEmailSmtpPassword = tsSafe(settings.get("email_smtp_password")).isBlank() ? "" : "********";
  String maskedEmailGraphClientSecret = tsSafe(settings.get("email_graph_client_secret")).isBlank() ? "" : "********";
  String maskedFlowrouteSmsAccessKey = tsSafe(settings.get("flowroute_sms_access_key")).isBlank() ? "" : "********";
  String maskedFlowrouteSmsSecretKey = tsSafe(settings.get("flowroute_sms_secret_key")).isBlank() ? "" : "********";
  String emailProviderMode = tsSafe(settings.get("email_provider")).trim().toLowerCase(Locale.ROOT);
  if (!"smtp".equals(emailProviderMode) && !"microsoft_graph".equals(emailProviderMode)) emailProviderMode = "disabled";
  String twoFactorPolicyMode = tsSafe(settings.get("two_factor_policy")).trim().toLowerCase(Locale.ROOT);
  if (!"optional".equals(twoFactorPolicyMode) && !"required".equals(twoFactorPolicyMode)) twoFactorPolicyMode = "off";
  String twoFactorDefaultEngineMode = tsSafe(settings.get("two_factor_default_engine")).trim().toLowerCase(Locale.ROOT);
  if (!"flowroute_sms".equals(twoFactorDefaultEngineMode)) twoFactorDefaultEngineMode = "email_pin";
  String clioMode = tsSafe(settings.get("clio_auth_mode")).trim().toLowerCase(Locale.ROOT);
  if (!"private".equals(clioMode)) clioMode = "public";
  String storageOnedriveAuthModeSaved = tsSafe(settings.get("storage_onedrive_auth_mode")).trim().toLowerCase(Locale.ROOT);
  if (!"public".equals(storageOnedriveAuthModeSaved)
          && !"private".equals(storageOnedriveAuthModeSaved)
          && !"app_credentials".equals(storageOnedriveAuthModeSaved)) {
    storageOnedriveAuthModeSaved = "app_credentials";
  }
  String storageOnedriveOauthCallbackUrlSaved = tsSafe(settings.get("storage_onedrive_oauth_callback_url")).trim();
  String storageOnedrivePrivateRelayUrlSaved = tsSafe(settings.get("storage_onedrive_private_relay_url")).trim();
  String clioStorageModeSaved = tsSafe(settings.get("clio_storage_mode")).trim().toLowerCase(Locale.ROOT);
  if (!"enabled".equals(clioStorageModeSaved)) clioStorageModeSaved = "disabled";
  String clioSyncIntervalMinutesSaved = tsIntRange(settings.get("clio_matters_sync_interval_minutes"), 15, 1, 1440);
  String clioContactsSyncStatusSaved = tsSafe(settings.get("clio_contacts_last_sync_status")).trim().toLowerCase(Locale.ROOT);
  if (!"ok".equals(clioContactsSyncStatusSaved) && !"failed".equals(clioContactsSyncStatusSaved)) clioContactsSyncStatusSaved = "never";
  String clioContactsSyncErrorSaved = tsSafe(settings.get("clio_contacts_last_sync_error")).trim();
  String office365ContactsSyncIntervalMinutesSaved = tsIntRange(settings.get("office365_contacts_sync_interval_minutes"), 30, 1, 1440);
  String office365ContactsSyncStatusSaved = tsSafe(settings.get("office365_contacts_last_sync_status")).trim().toLowerCase(Locale.ROOT);
  if (!"ok".equals(office365ContactsSyncStatusSaved) && !"failed".equals(office365ContactsSyncStatusSaved)) office365ContactsSyncStatusSaved = "never";
  String office365ContactsSyncErrorSaved = tsSafe(settings.get("office365_contacts_last_sync_error")).trim();
  String office365SourcesJsonSaved = tsSafe(settings.get("office365_contacts_sync_sources_json"));
  String themeMode = tsThemeMode(settings.get("theme_mode_default"));
  String themeTextSize = tsTextSize(settings.get("theme_text_size_default"));
  String themeLatitudeSaved = tsSafe(settings.get("theme_latitude")).trim();
  String themeLongitudeSaved = tsSafe(settings.get("theme_longitude")).trim();
  String themeLightHourSaved = tsHour(settings.get("theme_light_start_hour"), 7);
  String themeDarkHourSaved = tsHour(settings.get("theme_dark_start_hour"), 19);
  String passwordPolicyMinLengthSaved = tsIntRange(settings.get("password_policy_min_length"), 12, 8, 128);
  List<api_credentials.CredentialRec> apiCredentials = new ArrayList<api_credentials.CredentialRec>();
  if (canManageApiCredentials) {
    try { apiCredentials = apiCredentialStore.list(tenantUuid); } catch (Exception ignored) {}
  }
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
  <% if (!newApiSecret.isBlank()) { %>
    <div class="alert alert-ok" style="margin-top:12px;">
      <strong>Store this API secret now.</strong><br />
      Credential: <code><%= tsEsc(newApiLabel.isBlank() ? "Automation Key" : newApiLabel) %></code><br />
      Key: <code><%= tsEsc(newApiKey) %></code><br />
      Secret: <code><%= tsEsc(newApiSecret) %></code>
    </div>
  <% } %>
</section>

<section class="card" style="margin-top:12px;">
  <div class="tabs" id="tenantSettingsTabs" data-default-tab="<%= tsEsc(activeTab) %>" role="tablist" aria-label="Tenant settings sections">
    <button type="button" class="tab" data-ts-tab-btn="integrations">Integrations</button>
    <button type="button" class="tab" data-ts-tab-btn="experience">Experience</button>
    <button type="button" class="tab" data-ts-tab-btn="security">Security</button>
    <button type="button" class="tab" data-ts-tab-btn="operations">Operations</button>
  </div>
</section>

<section class="card" style="margin-top:12px;" id="document-taxonomy" data-ts-panel="operations">
  <h2 style="margin-top:0;">Manage Document Taxonomy</h2>
  <div class="meta" style="margin-bottom:10px;">These values drive Category/Subcategory/Status options on the Documents page.</div>
  <form class="form" method="post" action="<%= ctx %>/tenant_settings.jsp">
    <input type="hidden" name="csrfToken" value="<%= tsEsc(csrfToken) %>" />
    <input type="hidden" name="action" value="add_taxonomy" />
    <input type="hidden" name="tab" id="tenantSettingsTabFieldTaxonomy" value="<%= tsEsc(activeTab) %>" />
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

<section class="card" style="margin-top:12px;" data-ts-panel="operations">
  <h2 style="margin-top:0;">TLS / SSL Runtime</h2>
  <div class="meta" style="margin-bottom:10px;">
    Configure runtime TLS source selection for startup. These settings are stored in
    <code><%= tsEsc(tsRuntimeSslConfigPath().toString()) %></code>.
  </div>
  <form class="form" method="post" action="<%= ctx %>/tenant_settings.jsp">
    <input type="hidden" name="csrfToken" value="<%= tsEsc(csrfToken) %>" />
    <input type="hidden" name="action" value="save_ssl_runtime_config" />
    <input type="hidden" name="tab" id="tenantSettingsTabFieldSsl" value="<%= tsEsc(activeTab) %>" />

    <div class="grid grid-2">
      <label>TLS Mode
        <select name="ssl_mode">
          <option value="auto" <%= "auto".equals(sslModeSaved) ? "selected" : "" %>>Auto (custom, then certbot, then self-signed)</option>
          <option value="self_signed" <%= "self_signed".equals(sslModeSaved) ? "selected" : "" %>>Self-signed only</option>
          <option value="certbot" <%= "certbot".equals(sslModeSaved) ? "selected" : "" %>>Certbot only</option>
          <option value="custom" <%= "custom".equals(sslModeSaved) ? "selected" : "" %>>Custom keystore only</option>
        </select>
      </label>
      <label>Custom Keystore Path
        <input type="text" name="ssl_keystore_path" value="<%= tsEsc(sslKeystorePathSaved) %>" placeholder="/path/to/keystore.p12" />
      </label>
      <label>Custom Keystore Alias
        <input type="text" name="ssl_keystore_alias" value="<%= tsEsc(sslKeystoreAliasSaved) %>" placeholder="tomcat" />
      </label>
      <label>Certbot Domain
        <input type="text" name="ssl_certbot_domain" value="<%= tsEsc(sslCertbotDomainSaved) %>" placeholder="example.com" />
      </label>
      <label>Certbot Live Directory
        <input type="text" name="ssl_certbot_live_dir" value="<%= tsEsc(sslCertbotLiveDirSaved) %>" placeholder="/etc/letsencrypt/live/example.com" />
      </label>
      <label>OpenSSL Command
        <input type="text" name="ssl_certbot_openssl_cmd" value="<%= tsEsc(sslCertbotOpenSslCmdSaved) %>" placeholder="openssl" />
      </label>
      <label><input type="checkbox" name="ssl_certbot_force_rebuild" value="1" <%= sslCertbotForceRebuildSaved ? "checked" : "" %> /> Force certbot PKCS12 rebuild at startup</label>
    </div>

    <div class="meta" style="margin-top:8px;">
      Keep keystore password management in environment/JVM options only:
      <code>CONTROVERSIES_SSL_KEYSTORE_PASSWORD</code>. Restart the application after saving TLS runtime settings.
    </div>
    <div class="actions" style="display:flex; gap:10px; margin-top:10px; align-items:center; flex-wrap:wrap;">
      <button type="submit" class="btn btn-ghost">Save TLS Runtime Settings</button>
    </div>
  </form>
</section>

<form class="form" method="post" action="<%= ctx %>/tenant_settings.jsp">
  <input type="hidden" name="csrfToken" value="<%= tsEsc(csrfToken) %>" />
  <input type="hidden" name="api_credential_id" value="" />
  <input type="hidden" name="tab" id="tenantSettingsTabFieldMain" value="<%= tsEsc(activeTab) %>" />

  <section class="card" style="margin-top:12px;" data-ts-panel="integrations">
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

  <section class="card" style="margin-top:12px;" data-ts-panel="integrations">
    <h2 style="margin-top:0;">Storage Backend</h2>
    <div class="meta" style="margin-bottom:10px;">Configure where assembled documents are stored. Keep secret fields blank to retain currently saved values.</div>

    <div class="grid grid-2">
      <label>Backend
        <select name="storage_backend">
          <option value="local" <%= "local".equalsIgnoreCase(tsSafe(settings.get("storage_backend"))) ? "selected" : "" %>>Local Filesystem</option>
          <option value="ftp" <%= "ftp".equalsIgnoreCase(tsSafe(settings.get("storage_backend"))) ? "selected" : "" %>>FTP</option>
          <option value="ftps" <%= "ftps".equalsIgnoreCase(tsSafe(settings.get("storage_backend"))) ? "selected" : "" %>>FTPS</option>
          <option value="sftp" <%= "sftp".equalsIgnoreCase(tsSafe(settings.get("storage_backend"))) ? "selected" : "" %>>SFTP</option>
          <option value="webdav" <%= "webdav".equalsIgnoreCase(tsSafe(settings.get("storage_backend"))) ? "selected" : "" %>>WebDAV</option>
          <option value="s3_compatible" <%= "s3_compatible".equalsIgnoreCase(tsSafe(settings.get("storage_backend"))) ? "selected" : "" %>>S3 Compatible</option>
          <option value="onedrive_business" <%= "onedrive_business".equalsIgnoreCase(tsSafe(settings.get("storage_backend"))) ? "selected" : "" %>>OneDrive for Business</option>
        </select>
      </label>
      <label>Endpoint
        <input type="text" name="storage_endpoint" value="<%= tsEsc(tsSafe(settings.get("storage_endpoint"))) %>" placeholder="ftp://, sftp://, webdav://, https://s3.endpoint.example, or https://graph.microsoft.com/v1.0/drives/{drive-id}/root:/controversies" />
      </label>
      <label>External Root Folder (optional)
        <input type="text" name="storage_root_folder" value="<%= tsEsc(tsSafe(settings.get("storage_root_folder"))) %>" placeholder="backups/primary" />
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
      <label>OneDrive Auth Profile
        <select name="storage_onedrive_auth_mode">
          <option value="app_credentials" <%= "app_credentials".equals(storageOnedriveAuthModeSaved) ? "selected" : "" %>>App credentials only</option>
          <option value="public" <%= "public".equals(storageOnedriveAuthModeSaved) ? "selected" : "" %>>Public callback</option>
          <option value="private" <%= "private".equals(storageOnedriveAuthModeSaved) ? "selected" : "" %>>Private relay/admin flow</option>
        </select>
      </label>
      <label>OneDrive OAuth Callback URL (public profile)
        <input type="text" name="storage_onedrive_oauth_callback_url" value="<%= tsEsc(storageOnedriveOauthCallbackUrlSaved) %>" placeholder="https://your-app.example.com/onedrive/oauth/callback" />
      </label>
      <label>OneDrive Relay/Admin Exchange URL (private profile)
        <input type="text" name="storage_onedrive_private_relay_url" value="<%= tsEsc(storageOnedrivePrivateRelayUrlSaved) %>" placeholder="https://relay.example.com/onedrive/exchange or internal SOP reference" />
      </label>
      <label>FTP Cache Size (MB)
        <input type="number" min="0" max="1048576" name="storage_cache_size_ftp_mb" value="<%= tsEsc(tsIntRange(settings.get("storage_cache_size_ftp_mb"), 1024, 0, 1048576)) %>" />
      </label>
      <label>FTPS Cache Size (MB)
        <input type="number" min="0" max="1048576" name="storage_cache_size_ftps_mb" value="<%= tsEsc(tsIntRange(settings.get("storage_cache_size_ftps_mb"), 1024, 0, 1048576)) %>" />
      </label>
      <label>SFTP Cache Size (MB)
        <input type="number" min="0" max="1048576" name="storage_cache_size_sftp_mb" value="<%= tsEsc(tsIntRange(settings.get("storage_cache_size_sftp_mb"), 1024, 0, 1048576)) %>" />
      </label>
      <label>WebDAV Cache Size (MB)
        <input type="number" min="0" max="1048576" name="storage_cache_size_webdav_mb" value="<%= tsEsc(tsIntRange(settings.get("storage_cache_size_webdav_mb"), 1024, 0, 1048576)) %>" />
      </label>
      <label>S3-Compatible Cache Size (MB)
        <input type="number" min="0" max="1048576" name="storage_cache_size_s3_compatible_mb" value="<%= tsEsc(tsIntRange(settings.get("storage_cache_size_s3_compatible_mb"), 1024, 0, 1048576)) %>" />
      </label>
      <label>OneDrive Business Cache Size (MB)
        <input type="number" min="0" max="1048576" name="storage_cache_size_onedrive_business_mb" value="<%= tsEsc(tsIntRange(settings.get("storage_cache_size_onedrive_business_mb"), 1024, 0, 1048576)) %>" />
      </label>
      <label>External Max Path Length (chars)
        <input type="number" min="0" max="8192" name="storage_max_path_length" value="<%= tsEsc(tsIntRange(settings.get("storage_max_path_length"), 0, 0, 8192)) %>" />
      </label>
      <label>External Max Filename Length (chars)
        <input type="number" min="0" max="1024" name="storage_max_filename_length" value="<%= tsEsc(tsIntRange(settings.get("storage_max_filename_length"), 0, 0, 1024)) %>" />
      </label>
    </div>

    <div class="meta" style="margin-top:8px;">Each external source keeps a bounded local cache to reduce bandwidth costs. Set a source to <code>0</code> to disable that cache. Default is 1024 MB (1 GB) per source. Path/filename limits apply only to external object keys, and collisions are stored under unique non-destructive keys. OneDrive for Business defaults to 400-path / 255-filename limits if tenant limits are unset and supports app-credentials-only, public callback, or private relay auth profiles.</div>

    <div class="actions" style="display:flex; gap:10px; margin-top:10px; align-items:center; flex-wrap:wrap;">
      <button type="submit" class="btn btn-ghost" name="action" value="test_storage_connection">Test Storage Connection</button>
      <button type="submit" class="btn btn-ghost" name="action" value="rotate_storage_secret">Rotate Storage Secret</button>
      <label><input type="checkbox" name="save_storage_secret" value="1" /> Persist entered storage secret on Save</label>
      <label><input type="checkbox" name="save_storage_encryption_key" value="1" /> Persist entered application encryption key on Save</label>
    </div>
  </section>

  <section class="card" style="margin-top:12px;" data-ts-panel="integrations">
    <h2 style="margin-top:0;">Clio Connection</h2>
    <div class="meta" style="margin-bottom:10px;">One-way sync imports Clio matters, documents, and contacts into Controversies, links contacts to matters, and keeps Clio-synced documents read-only here.</div>

    <label><input type="checkbox" name="clio_enabled" value="1" <%= "true".equalsIgnoreCase(tsSafe(settings.get("clio_enabled"))) ? "checked" : "" %> /> Enable one-way Clio matter sync</label>

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
      <label>Matter + Document Sync Interval (minutes)
        <input type="number" min="1" max="1440" name="clio_matters_sync_interval_minutes" value="<%= tsEsc(clioSyncIntervalMinutesSaved) %>" />
      </label>
      <label>Assembled-Form Upload to Clio
        <select name="clio_storage_mode">
          <option value="disabled" <%= "disabled".equals(clioStorageModeSaved) ? "selected" : "" %>>Disabled</option>
          <option value="enabled" <%= "enabled".equals(clioStorageModeSaved) ? "selected" : "" %>>Enabled</option>
        </select>
      </label>
      <label>Last Successful Matter Sync
        <input type="text" value="<%= tsEsc(tsRotationLabel(tsSafe(settings.get("clio_matters_last_sync_at")))) %>" disabled />
      </label>
      <label>Last Document Sync
        <input type="text" value="<%= tsEsc(tsRotationLabel(tsSafe(settings.get("clio_documents_last_sync_at")))) %>" disabled />
      </label>
      <label>Last Contact Sync
        <input type="text" value="<%= tsEsc(tsRotationLabel(tsSafe(settings.get("clio_contacts_last_sync_at")))) %>" disabled />
      </label>
      <label>Last Contact Sync Status
        <input type="text" value="<%= tsEsc(tsStatusLabel(clioContactsSyncStatusSaved)) %>" disabled />
      </label>
      <label>Last Contact Sync Error
        <input type="text" value="<%= tsEsc(clioContactsSyncErrorSaved.isBlank() ? "None" : clioContactsSyncErrorSaved) %>" disabled />
      </label>
    </div>

    <div class="actions" style="display:flex; gap:10px; margin-top:10px; align-items:center; flex-wrap:wrap;">
      <button type="submit" class="btn btn-ghost" name="action" value="test_clio_connection">Test Clio Connection</button>
      <button type="submit" class="btn btn-ghost" name="action" value="sync_clio_matters_now">Sync Clio Matters + Documents Now</button>
      <button type="submit" class="btn btn-ghost" name="action" value="sync_clio_contacts_now">Sync Clio Contacts Now</button>
      <button type="submit" class="btn btn-ghost" name="action" value="rotate_clio_secret">Rotate Clio Secret</button>
      <label><input type="checkbox" name="save_clio_secret" value="1" /> Persist entered Clio secret on Save</label>
    </div>
  </section>

  <section class="card" style="margin-top:12px;" data-ts-panel="integrations">
    <h2 style="margin-top:0;">Office 365 Contact Sync</h2>
    <div class="meta" style="margin-bottom:10px;">One-way sync imports contacts from one or more Microsoft Graph sources (mailboxes/folders) into Controversies as read-only external contacts.</div>

    <label><input type="checkbox" name="office365_contacts_sync_enabled" value="1" <%= "true".equalsIgnoreCase(tsSafe(settings.get("office365_contacts_sync_enabled"))) ? "checked" : "" %> /> Enable one-way Office 365 contact sync</label>

    <div class="grid grid-2">
      <label>Sync Interval (minutes)
        <input type="number" min="1" max="1440" name="office365_contacts_sync_interval_minutes" value="<%= tsEsc(office365ContactsSyncIntervalMinutesSaved) %>" />
      </label>
      <label>Last Contact Sync
        <input type="text" value="<%= tsEsc(tsRotationLabel(tsSafe(settings.get("office365_contacts_last_sync_at")))) %>" disabled />
      </label>
      <label>Last Contact Sync Status
        <input type="text" value="<%= tsEsc(tsStatusLabel(office365ContactsSyncStatusSaved)) %>" disabled />
      </label>
      <label>Last Contact Sync Error
        <input type="text" value="<%= tsEsc(office365ContactsSyncErrorSaved.isBlank() ? "None" : office365ContactsSyncErrorSaved) %>" disabled />
      </label>
    </div>

    <label>Sources JSON
      <textarea name="office365_contacts_sync_sources_json" rows="10" spellcheck="false" placeholder='[{"source_id":"corp_directory","tenant_id":"00000000-0000-0000-0000-000000000000","client_id":"app-client-id","client_secret":"app-client-secret","user_principal":"contacts@yourfirm.com","contact_folder_id":"","enabled":true}]'><%= tsEsc(office365SourcesJsonSaved) %></textarea>
    </label>
    <div class="meta" style="margin-top:6px;">Each source supports keys: <code>source_id</code>, <code>tenant_id</code>, <code>client_id</code>, <code>client_secret</code>, <code>user_principal</code>, optional <code>contact_folder_id</code>, optional <code>scope</code>, and <code>enabled</code>.</div>

    <div class="actions" style="display:flex; gap:10px; margin-top:10px; align-items:center; flex-wrap:wrap;">
      <button type="submit" class="btn btn-ghost" name="action" value="sync_office365_contacts_now">Sync Office 365 Contacts Now</button>
    </div>
  </section>

  <section class="card" style="margin-top:12px;" data-ts-panel="integrations">
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

  <section class="card" style="margin-top:12px;" data-ts-panel="experience">
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

  <section class="card" style="margin-top:12px;" data-ts-panel="experience">
    <h2 style="margin-top:0;">Feature Flags</h2>
    <div class="grid grid-2">
      <label><input type="checkbox" name="feature_advanced_assembly" value="1" <%= "true".equalsIgnoreCase(tsSafe(settings.get("feature_advanced_assembly"))) ? "checked" : "" %> /> Enable advanced assembly</label>
      <label><input type="checkbox" name="feature_async_sync" value="1" <%= "true".equalsIgnoreCase(tsSafe(settings.get("feature_async_sync"))) ? "checked" : "" %> /> Enable async sync</label>
    </div>
  </section>

  <section class="card" style="margin-top:12px;" data-ts-panel="security">
    <h2 style="margin-top:0;">Password Policy (Optional)</h2>
    <div class="meta" style="margin-bottom:10px;">
      Applies to user-created and user-updated passwords by default. Tenant admins can explicitly override policy when setting passwords.
    </div>
    <div class="grid grid-2">
      <label><input type="checkbox" name="password_policy_enabled" value="1" <%= "true".equalsIgnoreCase(tsSafe(settings.get("password_policy_enabled"))) ? "checked" : "" %> /> Enable password policy</label>
      <label>Minimum Length
        <input type="number" min="8" max="128" name="password_policy_min_length" value="<%= tsEsc(passwordPolicyMinLengthSaved) %>" />
      </label>
      <label><input type="checkbox" name="password_policy_require_uppercase" value="1" <%= "true".equalsIgnoreCase(tsSafe(settings.get("password_policy_require_uppercase"))) ? "checked" : "" %> /> Require uppercase letter</label>
      <label><input type="checkbox" name="password_policy_require_lowercase" value="1" <%= "true".equalsIgnoreCase(tsSafe(settings.get("password_policy_require_lowercase"))) ? "checked" : "" %> /> Require lowercase letter</label>
      <label><input type="checkbox" name="password_policy_require_number" value="1" <%= "true".equalsIgnoreCase(tsSafe(settings.get("password_policy_require_number"))) ? "checked" : "" %> /> Require number</label>
      <label><input type="checkbox" name="password_policy_require_symbol" value="1" <%= "true".equalsIgnoreCase(tsSafe(settings.get("password_policy_require_symbol"))) ? "checked" : "" %> /> Require symbol</label>
    </div>
  </section>

  <section class="card" style="margin-top:12px;" data-ts-panel="security">
    <h2 style="margin-top:0;">Two-Factor Authentication</h2>
    <div class="meta" style="margin-bottom:10px;">
      Tenant policy can force two-factor for everyone, or allow users to opt in individually in Users &amp; Roles.
    </div>

    <div class="grid grid-2">
      <label>Tenant 2FA Policy
        <select name="two_factor_policy">
          <option value="off" <%= "off".equals(twoFactorPolicyMode) ? "selected" : "" %>>Off</option>
          <option value="optional" <%= "optional".equals(twoFactorPolicyMode) ? "selected" : "" %>>Optional (user opt-in)</option>
          <option value="required" <%= "required".equals(twoFactorPolicyMode) ? "selected" : "" %>>Required (force for all users)</option>
        </select>
      </label>
      <label>Default Engine
        <select name="two_factor_default_engine">
          <option value="email_pin" <%= "email_pin".equals(twoFactorDefaultEngineMode) ? "selected" : "" %>>Email 6-digit PIN</option>
          <option value="flowroute_sms" <%= "flowroute_sms".equals(twoFactorDefaultEngineMode) ? "selected" : "" %>>Flowroute SMS</option>
        </select>
      </label>
      <div class="meta" style="grid-column:1 / -1;">
        Email PIN uses the Notification Email provider configured above. Flowroute SMS requires the tenant credentials below and per-user phone numbers.
      </div>
    </div>

    <h3 style="margin-top:14px;">Flowroute SMS</h3>
    <div class="grid grid-2">
      <label>Access Key
        <input type="password" name="flowroute_sms_access_key" value="" placeholder="<%= tsEsc(maskedFlowrouteSmsAccessKey) %>" />
      </label>
      <label>Secret Key
        <input type="password" name="flowroute_sms_secret_key" value="" placeholder="<%= tsEsc(maskedFlowrouteSmsSecretKey) %>" />
      </label>
      <label>From Number (E.164 preferred)
        <input type="text" name="flowroute_sms_from_number" value="<%= tsEsc(tsSafe(settings.get("flowroute_sms_from_number"))) %>" placeholder="+12065550100" />
      </label>
      <label>API Endpoint
        <input type="text" name="flowroute_sms_api_base_url" value="<%= tsEsc(tsSafe(settings.get("flowroute_sms_api_base_url"))) %>" placeholder="https://api.flowroute.com/v2.2/messages" />
      </label>
    </div>

    <div class="actions" style="display:flex; gap:10px; margin-top:10px; align-items:center; flex-wrap:wrap;">
      <label><input type="checkbox" name="save_flowroute_sms_access_key" value="1" /> Persist entered Flowroute access key on Save</label>
      <label><input type="checkbox" name="save_flowroute_sms_secret_key" value="1" /> Persist entered Flowroute secret key on Save</label>
    </div>
  </section>

  <section class="card" style="margin-top:12px;" data-ts-panel="operations">
    <h2 style="margin-top:0;">API Credentials</h2>
    <div class="meta" style="margin-bottom:10px;">
      Tenant-scoped automation credentials for n8n/OpenClaw. Use headers <code>X-Tenant-UUID</code>, <code>X-API-Key</code>, and <code>X-API-Secret</code>.
    </div>
    <% if (!canManageApiCredentials) { %>
      <div class="meta">This section requires <code>api.credentials.manage</code>.</div>
    <% } %>
    <% if (canManageApiCredentials) { %>
      <div class="actions" style="display:flex; gap:8px; align-items:center; flex-wrap:wrap; margin-bottom:8px;">
        <input type="text" name="api_credential_label" placeholder="Credential label (optional)" style="min-width:260px;" />
        <button type="submit" class="btn btn-ghost" name="action" value="generate_api_credential">Generate API Credential</button>
      </div>
      <% if (apiCredentials == null || apiCredentials.isEmpty()) { %>
        <div class="meta">No API credentials created yet.</div>
      <% } else { %>
        <div style="display:grid; gap:8px;">
          <% for (api_credentials.CredentialRec cred : apiCredentials) { %>
            <% if (cred == null) continue; %>
            <div style="display:grid; gap:8px; grid-template-columns:minmax(0,1fr) auto; align-items:center; border:1px solid var(--border); border-radius:10px; padding:8px 10px; background:var(--surface);">
              <div>
                <div><strong><%= tsEsc(tsSafe(cred.label).isBlank() ? "Automation Key" : cred.label) %></strong></div>
                <div class="meta">ID: <code><%= tsEsc(cred.credentialId) %></code></div>
                <div class="meta">Scope: <code><%= tsEsc(cred.scope) %></code> • Created: <%= tsEsc(tsRotationLabel(tsSafe(cred.createdAt))) %></div>
                <div class="meta">Last used: <%= tsEsc(tsRotationLabel(tsSafe(cred.lastUsedAt))) %> <%= tsSafe(cred.lastUsedFromIp).isBlank() ? "" : ("from " + tsEsc(cred.lastUsedFromIp)) %></div>
                <% if (cred.revoked) { %>
                  <div class="meta" style="color:#9b2c2c;">Revoked</div>
                <% } %>
              </div>
              <div>
                <% if (!cred.revoked) { %>
                  <button type="submit"
                          class="btn btn-ghost"
                          name="action"
                          value="revoke_api_credential"
                          onclick="this.form.api_credential_id.value='<%= tsEsc(cred.credentialId) %>';">Revoke</button>
                <% } %>
              </div>
            </div>
          <% } %>
        </div>
      <% } %>
    <% } %>
  </section>

  <section class="card" style="margin-top:12px;" data-ts-panel="security">
    <h2 style="margin-top:0;">Security Controls</h2>
    <div class="grid grid-2">
      <div>Storage secret last rotated: <strong><%= tsEsc(tsRotationLabel(tsSafe(settings.get("secret_rotation_storage_at")))) %></strong></div>
      <div>Clio secret last rotated: <strong><%= tsEsc(tsRotationLabel(tsSafe(settings.get("secret_rotation_clio_at")))) %></strong></div>
      <div>Storage connection: <strong><%= tsEsc(tsStatusLabel(tsSafe(settings.get("storage_connection_status")))) %></strong> (<%= tsEsc(tsRotationLabel(tsSafe(settings.get("storage_connection_checked_at")))) %>)</div>
      <div>Application encryption: <strong><%= tsEsc(tsSafe(settings.get("storage_encryption_mode"))) %></strong></div>
      <div>TLS mode: <strong><%= tsEsc(sslModeSaved) %></strong></div>
      <div>TLS custom keystore path: <strong><%= tsEsc(sslKeystorePathSaved.isBlank() ? "Not set" : "Set") %></strong></div>
      <div>TLS certbot domain: <strong><%= tsEsc(sslCertbotDomainSaved.isBlank() ? "Not set" : sslCertbotDomainSaved) %></strong></div>
      <div>TLS certbot live directory: <strong><%= tsEsc(sslCertbotLiveDirSaved.isBlank() ? "Not set" : sslCertbotLiveDirSaved) %></strong></div>
      <div>S3 SSE mode: <strong><%= tsEsc(tsSafe(settings.get("storage_s3_sse_mode"))) %></strong></div>
      <div>OneDrive auth profile: <strong><%= tsEsc(storageOnedriveAuthModeSaved) %></strong></div>
      <div>OneDrive callback URL: <strong><%= tsEsc(storageOnedriveOauthCallbackUrlSaved.isBlank() ? "Not set" : "Set") %></strong></div>
      <div>OneDrive private relay URL: <strong><%= tsEsc(storageOnedrivePrivateRelayUrlSaved.isBlank() ? "Not set" : "Set") %></strong></div>
      <div>FTP cache size: <strong><%= tsEsc(tsIntRange(settings.get("storage_cache_size_ftp_mb"), 1024, 0, 1048576)) %> MB</strong></div>
      <div>FTPS cache size: <strong><%= tsEsc(tsIntRange(settings.get("storage_cache_size_ftps_mb"), 1024, 0, 1048576)) %> MB</strong></div>
      <div>SFTP cache size: <strong><%= tsEsc(tsIntRange(settings.get("storage_cache_size_sftp_mb"), 1024, 0, 1048576)) %> MB</strong></div>
      <div>WebDAV cache size: <strong><%= tsEsc(tsIntRange(settings.get("storage_cache_size_webdav_mb"), 1024, 0, 1048576)) %> MB</strong></div>
      <div>S3-compatible cache size: <strong><%= tsEsc(tsIntRange(settings.get("storage_cache_size_s3_compatible_mb"), 1024, 0, 1048576)) %> MB</strong></div>
      <div>OneDrive Business cache size: <strong><%= tsEsc(tsIntRange(settings.get("storage_cache_size_onedrive_business_mb"), 1024, 0, 1048576)) %> MB</strong></div>
      <div>External max path length: <strong><%= tsEsc(tsIntRange(settings.get("storage_max_path_length"), 0, 0, 8192)) %></strong></div>
      <div>External max filename length: <strong><%= tsEsc(tsIntRange(settings.get("storage_max_filename_length"), 0, 0, 1024)) %></strong></div>
      <div>Clio connection: <strong><%= tsEsc(tsStatusLabel(tsSafe(settings.get("clio_connection_status")))) %></strong> (<%= tsEsc(tsRotationLabel(tsSafe(settings.get("clio_connection_checked_at")))) %>)</div>
      <div>Clio auth mode health: <strong><%= tsEsc(tsStatusLabel(tsSafe(settings.get("clio_auth_health_status")))) %></strong> (<%= tsEsc(tsRotationLabel(tsSafe(settings.get("clio_auth_health_checked_at")))) %>)</div>
      <div>Clio sync enabled: <strong><%= "true".equalsIgnoreCase(tsSafe(settings.get("clio_enabled"))) ? "Yes" : "No" %></strong></div>
      <div>Clio sync interval (minutes): <strong><%= tsEsc(clioSyncIntervalMinutesSaved) %></strong></div>
      <div>Last Clio matter sync: <strong><%= tsEsc(tsRotationLabel(tsSafe(settings.get("clio_matters_last_sync_at")))) %></strong></div>
      <div>Last Clio document sync: <strong><%= tsEsc(tsRotationLabel(tsSafe(settings.get("clio_documents_last_sync_at")))) %></strong></div>
      <div>Last Clio contact sync: <strong><%= tsEsc(tsRotationLabel(tsSafe(settings.get("clio_contacts_last_sync_at")))) %></strong></div>
      <div>Clio contact sync status: <strong><%= tsEsc(tsStatusLabel(clioContactsSyncStatusSaved)) %></strong></div>
      <div>Office 365 contact sync enabled: <strong><%= "true".equalsIgnoreCase(tsSafe(settings.get("office365_contacts_sync_enabled"))) ? "Yes" : "No" %></strong></div>
      <div>Office 365 sync interval (minutes): <strong><%= tsEsc(office365ContactsSyncIntervalMinutesSaved) %></strong></div>
      <div>Last Office 365 contact sync: <strong><%= tsEsc(tsRotationLabel(tsSafe(settings.get("office365_contacts_last_sync_at")))) %></strong></div>
      <div>Office 365 contact sync status: <strong><%= tsEsc(tsStatusLabel(office365ContactsSyncStatusSaved)) %></strong></div>
      <div>Email provider: <strong><%= tsEsc(tsSafe(settings.get("email_provider"))) %></strong></div>
      <div>Email connection: <strong><%= tsEsc(tsStatusLabel(tsSafe(settings.get("email_connection_status")))) %></strong> (<%= tsEsc(tsRotationLabel(tsSafe(settings.get("email_connection_checked_at")))) %>)</div>
      <div>Two-factor policy: <strong><%= tsEsc(tsSafe(settings.get("two_factor_policy"))) %></strong></div>
      <div>Two-factor default engine: <strong><%= tsEsc(tsSafe(settings.get("two_factor_default_engine"))) %></strong></div>
      <div>Flowroute from number: <strong><%= tsEsc(tsSafe(settings.get("flowroute_sms_from_number")).isBlank() ? "Not set" : "Set") %></strong></div>
      <div>Flowroute credentials: <strong><%= tsEsc(tsSafe(settings.get("flowroute_sms_access_key")).isBlank() || tsSafe(settings.get("flowroute_sms_secret_key")).isBlank() ? "Incomplete" : "Configured") %></strong></div>
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

<script>
(function () {
  var tabsHost = document.getElementById("tenantSettingsTabs");
  if (!tabsHost) return;
  var buttons = Array.prototype.slice.call(tabsHost.querySelectorAll("[data-ts-tab-btn]"));
  var panels = Array.prototype.slice.call(document.querySelectorAll("[data-ts-panel]"));
  var hiddenMain = document.getElementById("tenantSettingsTabFieldMain");
  var hiddenTaxonomy = document.getElementById("tenantSettingsTabFieldTaxonomy");
  var hiddenSsl = document.getElementById("tenantSettingsTabFieldSsl");
  if (!buttons.length || !panels.length) return;

  function setTab(name) {
    var key = String(name || "").trim().toLowerCase();
    if (!key) key = "integrations";
    buttons.forEach(function (btn) {
      var bKey = String(btn.getAttribute("data-ts-tab-btn") || "").toLowerCase();
      var isActive = bKey === key;
      btn.classList.toggle("active", isActive);
      btn.setAttribute("aria-selected", isActive ? "true" : "false");
    });
    panels.forEach(function (panel) {
      var pKey = String(panel.getAttribute("data-ts-panel") || "").toLowerCase();
      panel.style.display = (pKey === key) ? "" : "none";
    });
    if (hiddenMain) hiddenMain.value = key;
    if (hiddenTaxonomy) hiddenTaxonomy.value = key;
    if (hiddenSsl) hiddenSsl.value = key;
  }

  buttons.forEach(function (btn) {
    btn.addEventListener("click", function () {
      setTab(btn.getAttribute("data-ts-tab-btn"));
    });
  });

  setTab(tabsHost.getAttribute("data-default-tab"));
})();
</script>

<jsp:include page="footer.jsp" />
