<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>

<%@ page import="java.security.SecureRandom" %>
<%@ page import="java.util.ArrayList" %>
<%@ page import="java.util.Arrays" %>
<%@ page import="java.util.LinkedHashMap" %>
<%@ page import="java.util.List" %>
<%@ page import="java.util.Locale" %>
<%@ page import="java.util.Map" %>

<%@ page import="net.familylawandprobate.controversies.external_storage_data_sync" %>
<%@ page import="net.familylawandprobate.controversies.installation_state" %>
<%@ page import="net.familylawandprobate.controversies.tenant_settings" %>
<%@ page import="net.familylawandprobate.controversies.tenants" %>
<%@ page import="net.familylawandprobate.controversies.users_roles" %>
<%@ include file="security.jspf" %>

<%
  if (!require_loopback()) return;
%>

<%!
  private static final String S_INSTALL_STEP1_DONE = "install.step1.done";
  private static final String S_INSTALL_STEP2_DONE = "install.step2.done";
  private static final String S_INSTALL_STEP3_DONE = "install.step3.done";
  private static final String S_INSTALL_STEP4_DONE = "install.step4.done";
  private static final String S_INSTALL_ADMIN_EMAIL = "install.admin.email";
  private static final String S_INSTALL_FLASH_MESSAGE = "install.flash.message";

  private static final String BOOTSTRAP_ADMIN_EMAIL = "tenant_admin";
  private static final String TENANT_ADMIN_ROLE_LABEL = "Tenant Administrator";

  private static final SecureRandom INSTALL_RNG = new SecureRandom();

  private static String safe(String s) { return s == null ? "" : s; }

  private static String esc(String s) {
    if (s == null) return "";
    return s.replace("&","&amp;")
            .replace("<","&lt;")
            .replace(">","&gt;")
            .replace("\"","&quot;")
            .replace("'","&#39;");
  }

  private static boolean truthy(String v) {
    String s = safe(v).trim().toLowerCase(Locale.ROOT);
    return "1".equals(s) || "true".equals(s) || "on".equals(s) || "yes".equals(s);
  }

  private static int parseIntBounded(String raw, int fallback, int min, int max) {
    try {
      int n = Integer.parseInt(safe(raw).trim());
      if (n < min) return min;
      if (n > max) return max;
      return n;
    } catch (Exception ignored) {
      return fallback;
    }
  }

  private static int parseStep(String raw) {
    int step = parseIntBounded(raw, 1, 1, 5);
    if (step < 1 || step > 5) return 1;
    return step;
  }

  private static String normalizeEmail(String raw) {
    return safe(raw).trim().toLowerCase(Locale.ROOT);
  }

  private static boolean looksLikeEmail(String raw) {
    String e = normalizeEmail(raw);
    int at = e.indexOf('@');
    if (at <= 0 || at >= e.length() - 1) return false;
    return e.indexOf('@', at + 1) < 0;
  }

  private static String normalizeTwoFactorPolicy(String raw) {
    String s = safe(raw).trim().toLowerCase(Locale.ROOT);
    if ("optional".equals(s) || "required".equals(s)) return s;
    return "off";
  }

  private static String normalizeTwoFactorEngine(String raw) {
    String s = safe(raw).trim().toLowerCase(Locale.ROOT);
    if ("flowroute_sms".equals(s)) return "flowroute_sms";
    return "email_pin";
  }

  private static String normalizeStorageBackend(String raw) {
    String s = safe(raw).trim().toLowerCase(Locale.ROOT);
    if ("onedrive".equals(s) || "onedrive_for_business".equals(s) || "onedrive-for-business".equals(s)) return "onedrive_business";
    if ("ftp".equals(s) || "ftps".equals(s) || "sftp".equals(s) || "webdav".equals(s)
            || "s3_compatible".equals(s) || "onedrive_business".equals(s)) return s;
    return "local";
  }

  private static String normalizeExternalDataAction(String raw) {
    String s = safe(raw).trim().toLowerCase(Locale.ROOT);
    if ("backup_now".equals(s) || "restore_latest".equals(s)) return s;
    return "none";
  }

  private static String normalizeEmailProvider(String raw) {
    String s = safe(raw).trim().toLowerCase(Locale.ROOT);
    if ("smtp".equals(s) || "microsoft_graph".equals(s)) return s;
    return "disabled";
  }

  private static String normalizeClioAuthMode(String raw) {
    String s = safe(raw).trim().toLowerCase(Locale.ROOT);
    if ("private".equals(s)) return "private";
    return "public";
  }

  private static String normalizeOneDriveAuthMode(String raw) {
    String s = safe(raw).trim().toLowerCase(Locale.ROOT);
    if ("public".equals(s) || "private".equals(s) || "app_credentials".equals(s)) return s;
    return "app_credentials";
  }

  private static boolean looksHttpUrl(String raw) {
    String s = safe(raw).trim().toLowerCase(Locale.ROOT);
    return s.startsWith("http://") || s.startsWith("https://");
  }

  private static String randomPassword(int length) {
    String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@$%*-_";
    int len = Math.max(20, length);
    StringBuilder sb = new StringBuilder(len);
    for (int i = 0; i < len; i++) {
      sb.append(alphabet.charAt(INSTALL_RNG.nextInt(alphabet.length())));
    }
    return sb.toString();
  }

  private static tenants.Tenant resolveInstallTenant(List<tenants.Tenant> all) {
    if (all == null || all.isEmpty()) return null;
    tenants.Tenant firstEnabled = null;
    for (tenants.Tenant t : all) {
      if (t == null || !t.enabled) continue;
      if (safe(t.uuid).isBlank()) continue;
      if (safe(t.label).isBlank()) continue;
      if ("default tenant".equalsIgnoreCase(safe(t.label).trim())) return t;
      if (firstEnabled == null) firstEnabled = t;
    }
    return firstEnabled;
  }

  private static users_roles.RoleRec findRoleByLabel(List<users_roles.RoleRec> roles, String label) {
    String target = safe(label).trim();
    if (target.isBlank() || roles == null) return null;
    for (users_roles.RoleRec r : roles) {
      if (r == null) continue;
      if (target.equalsIgnoreCase(safe(r.label).trim())) return r;
    }
    return null;
  }

  private static boolean isBlankChars(char[] chars) {
    if (chars == null || chars.length == 0) return true;
    for (char c : chars) {
      if (!Character.isWhitespace(c)) return false;
    }
    return true;
  }
%>

<%
  String ctx = request.getContextPath();
  if (ctx == null) ctx = "";

  installation_state installStore = installation_state.defaultStore();
  Map<String, String> completedSnapshot = installStore.read();
  boolean installCompleted = "true".equalsIgnoreCase(safe(completedSnapshot.get(installation_state.KEY_COMPLETED)).trim());
  String installCompletedAt = safe(completedSnapshot.get(installation_state.KEY_COMPLETED_AT)).trim();
  String installCompletedAdmin = safe(completedSnapshot.get(installation_state.KEY_ADMIN_EMAIL)).trim();

  String message = null;
  String error = null;
  String action = safe(request.getParameter("action")).trim();
  int step = parseStep(request.getParameter("step"));

  String flashMessage = safe((String) session.getAttribute(S_INSTALL_FLASH_MESSAGE)).trim();
  if (!flashMessage.isBlank()) {
    message = flashMessage;
    session.removeAttribute(S_INSTALL_FLASH_MESSAGE);
  }

  String csrfToken = safe((String) request.getAttribute("csrfToken"));
  if (csrfToken.isBlank()) csrfToken = safe((String) session.getAttribute("CSRF_TOKEN"));

  tenants tenantStore = tenants.defaultStore();
  users_roles userStore = users_roles.defaultStore();
  tenant_settings settingsStore = tenant_settings.defaultStore();

  tenants.Tenant activeTenant = null;
  String tenantUuid = "";
  String tenantLabel = "";
  LinkedHashMap<String, String> settings = new LinkedHashMap<String, String>();

  String tenantLabelInput = safe(request.getParameter("tenantLabel")).trim();
  String adminEmailInput = normalizeEmail(request.getParameter("adminEmail"));

  boolean step1Done = Boolean.TRUE.equals(session.getAttribute(S_INSTALL_STEP1_DONE));
  boolean step2Done = Boolean.TRUE.equals(session.getAttribute(S_INSTALL_STEP2_DONE));
  boolean step3Done = Boolean.TRUE.equals(session.getAttribute(S_INSTALL_STEP3_DONE));
  boolean step4Done = Boolean.TRUE.equals(session.getAttribute(S_INSTALL_STEP4_DONE));

  String configuredAdminEmail = safe((String) session.getAttribute(S_INSTALL_ADMIN_EMAIL)).trim();

  String twoFactorPolicy = "off";
  String twoFactorEngine = "email_pin";
  boolean passwordPolicyEnabled = false;
  int passwordPolicyMinLength = 12;
  boolean passwordPolicyRequireUpper = false;
  boolean passwordPolicyRequireLower = false;
  boolean passwordPolicyRequireNumber = false;
  boolean passwordPolicyRequireSymbol = false;

  String storageBackend = "local";
  String storageEndpoint = "";
  String storageRootFolder = "";
  String storageAccessKey = "";
  String storageSecret = "";
  String storageOnedriveAuthMode = "app_credentials";
  String storageOnedriveOauthCallbackUrl = "";
  String storageOnedrivePrivateRelayUrl = "";
  int storageMaxPathLength = 0;
  int storageMaxFilenameLength = 0;
  String externalDataAction = normalizeExternalDataAction(request.getParameter("externalDataAction"));
  boolean externalRestoreConfirm = truthy(request.getParameter("externalRestoreConfirm"));
  external_storage_data_sync.SnapshotInfo externalLatestSnapshot = null;

  boolean clioEnabled = false;
  String clioBaseUrl = "";
  String clioClientId = "";
  String clioClientSecret = "";
  String clioAuthMode = "public";
  String clioOauthCallbackUrl = "";
  String clioPrivateRelayUrl = "";

  String emailProvider = "disabled";
  String emailFromAddress = "";
  String emailFromName = "";
  String emailReplyTo = "";
  String emailSmtpHost = "";
  String emailSmtpPort = "587";
  String emailSmtpUsername = "";
  String emailSmtpPassword = "";
  boolean emailSmtpAuth = true;
  boolean emailSmtpStarttls = true;
  boolean emailSmtpSsl = false;
  String emailGraphTenantId = "";
  String emailGraphClientId = "";
  String emailGraphClientSecret = "";
  String emailGraphSenderUser = "";

  if (!installCompleted) {
    try {
      tenantStore.ensure();
      List<tenants.Tenant> allTenants = tenantStore.list();
      activeTenant = resolveInstallTenant(allTenants);
      if (activeTenant == null) {
        char[] tenantPassword = randomPassword(32).toCharArray();
        String createdTenantUuid;
        try {
          createdTenantUuid = tenantStore.create("Default Tenant", tenantPassword);
        } finally {
          Arrays.fill(tenantPassword, '\0');
        }
        activeTenant = tenantStore.getByUuid(createdTenantUuid);
      }
    } catch (Exception ex) {
      error = "Unable to initialize tenant: " + safe(ex.getMessage());
    }

    if (activeTenant != null) {
      tenantUuid = safe(activeTenant.uuid).trim();
      tenantLabel = safe(activeTenant.label).trim();
    }

    if (!tenantUuid.isBlank()) {
      try { userStore.ensure(tenantUuid); } catch (Exception ex) {
        if (error == null || error.isBlank()) error = "Unable to initialize users: " + safe(ex.getMessage());
      }
      settings = settingsStore.read(tenantUuid);
    }

    if (tenantLabelInput.isBlank()) tenantLabelInput = tenantLabel;
    if (adminEmailInput.isBlank()) adminEmailInput = configuredAdminEmail;

    twoFactorPolicy = normalizeTwoFactorPolicy(settings.get("two_factor_policy"));
    twoFactorEngine = normalizeTwoFactorEngine(settings.get("two_factor_default_engine"));
    passwordPolicyEnabled = truthy(settings.get("password_policy_enabled"));
    passwordPolicyMinLength = parseIntBounded(settings.get("password_policy_min_length"), 12, 8, 128);
    passwordPolicyRequireUpper = truthy(settings.get("password_policy_require_uppercase"));
    passwordPolicyRequireLower = truthy(settings.get("password_policy_require_lowercase"));
    passwordPolicyRequireNumber = truthy(settings.get("password_policy_require_number"));
    passwordPolicyRequireSymbol = truthy(settings.get("password_policy_require_symbol"));

    storageBackend = normalizeStorageBackend(settings.get("storage_backend"));
    storageEndpoint = safe(settings.get("storage_endpoint")).trim();
    storageRootFolder = safe(settings.get("storage_root_folder")).trim();
    storageAccessKey = safe(settings.get("storage_access_key")).trim();
    storageSecret = safe(settings.get("storage_secret")).trim();
    storageOnedriveAuthMode = normalizeOneDriveAuthMode(settings.get("storage_onedrive_auth_mode"));
    storageOnedriveOauthCallbackUrl = safe(settings.get("storage_onedrive_oauth_callback_url")).trim();
    storageOnedrivePrivateRelayUrl = safe(settings.get("storage_onedrive_private_relay_url")).trim();
    storageMaxPathLength = parseIntBounded(settings.get("storage_max_path_length"), 0, 0, 8192);
    storageMaxFilenameLength = parseIntBounded(settings.get("storage_max_filename_length"), 0, 0, 1024);

    clioEnabled = truthy(settings.get("clio_enabled"));
    clioBaseUrl = safe(settings.get("clio_base_url")).trim();
    clioClientId = safe(settings.get("clio_client_id")).trim();
    clioClientSecret = safe(settings.get("clio_client_secret")).trim();
    clioAuthMode = normalizeClioAuthMode(settings.get("clio_auth_mode"));
    clioOauthCallbackUrl = safe(settings.get("clio_oauth_callback_url")).trim();
    clioPrivateRelayUrl = safe(settings.get("clio_private_relay_url")).trim();

    emailProvider = normalizeEmailProvider(settings.get("email_provider"));
    emailFromAddress = safe(settings.get("email_from_address")).trim();
    emailFromName = safe(settings.get("email_from_name")).trim();
    emailReplyTo = safe(settings.get("email_reply_to")).trim();
    emailSmtpHost = safe(settings.get("email_smtp_host")).trim();
    emailSmtpPort = safe(settings.get("email_smtp_port")).trim();
    if (emailSmtpPort.isBlank()) emailSmtpPort = "587";
    emailSmtpUsername = safe(settings.get("email_smtp_username")).trim();
    emailSmtpPassword = safe(settings.get("email_smtp_password")).trim();
    emailSmtpAuth = truthy(settings.get("email_smtp_auth"));
    emailSmtpStarttls = truthy(settings.get("email_smtp_starttls"));
    emailSmtpSsl = truthy(settings.get("email_smtp_ssl"));
    emailGraphTenantId = safe(settings.get("email_graph_tenant_id")).trim();
    emailGraphClientId = safe(settings.get("email_graph_client_id")).trim();
    emailGraphClientSecret = safe(settings.get("email_graph_client_secret")).trim();
    emailGraphSenderUser = safe(settings.get("email_graph_sender_user")).trim();

    if ("POST".equalsIgnoreCase(request.getMethod())) {
      if ("step3_save".equalsIgnoreCase(action)) {
        twoFactorPolicy = normalizeTwoFactorPolicy(request.getParameter("twoFactorPolicy"));
        twoFactorEngine = normalizeTwoFactorEngine(request.getParameter("twoFactorEngine"));
        passwordPolicyEnabled = truthy(request.getParameter("passwordPolicyEnabled"));
        passwordPolicyMinLength = parseIntBounded(request.getParameter("passwordPolicyMinLength"), 12, 8, 128);
        passwordPolicyRequireUpper = truthy(request.getParameter("passwordPolicyRequireUppercase"));
        passwordPolicyRequireLower = truthy(request.getParameter("passwordPolicyRequireLowercase"));
        passwordPolicyRequireNumber = truthy(request.getParameter("passwordPolicyRequireNumber"));
        passwordPolicyRequireSymbol = truthy(request.getParameter("passwordPolicyRequireSymbol"));
      } else if ("step4_save".equalsIgnoreCase(action)) {
        storageBackend = normalizeStorageBackend(request.getParameter("storageBackend"));
        storageEndpoint = safe(request.getParameter("storageEndpoint")).trim();
        storageRootFolder = safe(request.getParameter("storageRootFolder")).trim();
        storageAccessKey = safe(request.getParameter("storageAccessKey")).trim();
        storageSecret = safe(request.getParameter("storageSecret")).trim();
        storageOnedriveAuthMode = normalizeOneDriveAuthMode(request.getParameter("storageOnedriveAuthMode"));
        storageOnedriveOauthCallbackUrl = safe(request.getParameter("storageOnedriveOauthCallbackUrl")).trim();
        storageOnedrivePrivateRelayUrl = safe(request.getParameter("storageOnedrivePrivateRelayUrl")).trim();
        storageMaxPathLength = parseIntBounded(request.getParameter("storageMaxPathLength"), 0, 0, 8192);
        storageMaxFilenameLength = parseIntBounded(request.getParameter("storageMaxFilenameLength"), 0, 0, 1024);
        externalDataAction = normalizeExternalDataAction(request.getParameter("externalDataAction"));
        externalRestoreConfirm = truthy(request.getParameter("externalRestoreConfirm"));

        clioEnabled = truthy(request.getParameter("clioEnabled"));
        clioBaseUrl = safe(request.getParameter("clioBaseUrl")).trim();
        clioClientId = safe(request.getParameter("clioClientId")).trim();
        clioClientSecret = safe(request.getParameter("clioClientSecret")).trim();
        clioAuthMode = normalizeClioAuthMode(request.getParameter("clioAuthMode"));
        clioOauthCallbackUrl = safe(request.getParameter("clioOauthCallbackUrl")).trim();
        clioPrivateRelayUrl = safe(request.getParameter("clioPrivateRelayUrl")).trim();

        emailProvider = normalizeEmailProvider(request.getParameter("emailProvider"));
        emailFromAddress = normalizeEmail(request.getParameter("emailFromAddress"));
        emailFromName = safe(request.getParameter("emailFromName")).trim();
        emailReplyTo = normalizeEmail(request.getParameter("emailReplyTo"));
        emailSmtpHost = safe(request.getParameter("emailSmtpHost")).trim();
        emailSmtpPort = safe(request.getParameter("emailSmtpPort")).trim();
        emailSmtpUsername = safe(request.getParameter("emailSmtpUsername")).trim();
        emailSmtpPassword = safe(request.getParameter("emailSmtpPassword")).trim();
        emailSmtpAuth = truthy(request.getParameter("emailSmtpAuth"));
        emailSmtpStarttls = truthy(request.getParameter("emailSmtpStarttls"));
        emailSmtpSsl = truthy(request.getParameter("emailSmtpSsl"));
        emailGraphTenantId = safe(request.getParameter("emailGraphTenantId")).trim();
        emailGraphClientId = safe(request.getParameter("emailGraphClientId")).trim();
        emailGraphClientSecret = safe(request.getParameter("emailGraphClientSecret")).trim();
        emailGraphSenderUser = normalizeEmail(request.getParameter("emailGraphSenderUser"));
      }
    }

    if ("POST".equalsIgnoreCase(request.getMethod()) && (error == null || error.isBlank())) {
      try {
        if ("step1_save".equalsIgnoreCase(action)) {
          tenantLabelInput = safe(request.getParameter("tenantLabel")).trim();
          if (tenantUuid.isBlank()) {
            error = "Tenant context is unavailable.";
          } else if (tenantLabelInput.isBlank()) {
            error = "Tenant name is required.";
          } else {
            tenantStore.setLabel(tenantUuid, tenantLabelInput);
            session.setAttribute(S_INSTALL_STEP1_DONE, Boolean.TRUE);
            step1Done = true;
            response.sendRedirect(ctx + "/install.jsp?step=2");
            return;
          }
        } else if ("step2_save".equalsIgnoreCase(action)) {
          if (!step1Done) {
            error = "Complete Step 1 first.";
          } else if (tenantUuid.isBlank()) {
            error = "Tenant context is unavailable.";
          } else {
            adminEmailInput = normalizeEmail(request.getParameter("adminEmail"));
            char[] adminPasswordChars = safe(request.getParameter("adminPassword")).toCharArray();
            char[] adminConfirmChars = safe(request.getParameter("adminConfirmPassword")).toCharArray();
            try {
              if (!looksLikeEmail(adminEmailInput)) {
                error = "A valid administrator email is required.";
              } else if (isBlankChars(adminPasswordChars)) {
                error = "Administrator password is required.";
              } else if (!Arrays.equals(adminPasswordChars, adminConfirmChars)) {
                error = "Passwords do not match.";
              } else {
                List<users_roles.RoleRec> roles = new ArrayList<users_roles.RoleRec>(userStore.listRoles(tenantUuid));
                users_roles.RoleRec adminRole = findRoleByLabel(roles, TENANT_ADMIN_ROLE_LABEL);
                if (adminRole == null) {
                  adminRole = userStore.createRole(tenantUuid, TENANT_ADMIN_ROLE_LABEL, true);
                } else if (!adminRole.enabled) {
                  userStore.updateRoleEnabled(tenantUuid, adminRole.uuid, true);
                }
                userStore.setRolePermission(tenantUuid, adminRole.uuid, "tenant_admin", "true");

                users_roles.UserRec adminUser = userStore.getUserByEmail(tenantUuid, adminEmailInput);
                if (adminUser == null) {
                  userStore.createUser(tenantUuid, adminEmailInput, adminRole.uuid, true, adminPasswordChars, false);
                } else {
                  userStore.updateUserEnabled(tenantUuid, adminUser.uuid, true);
                  userStore.updateUserRole(tenantUuid, adminUser.uuid, adminRole.uuid);
                  userStore.updateUserPassword(tenantUuid, adminUser.uuid, adminPasswordChars, false);
                }

                users_roles.UserRec bootstrap = userStore.getUserByEmail(tenantUuid, BOOTSTRAP_ADMIN_EMAIL);
                if (bootstrap != null && !adminEmailInput.equalsIgnoreCase(BOOTSTRAP_ADMIN_EMAIL)) {
                  char[] bootstrapPassword = randomPassword(40).toCharArray();
                  try {
                    userStore.updateUserPassword(tenantUuid, bootstrap.uuid, bootstrapPassword, true);
                  } finally {
                    Arrays.fill(bootstrapPassword, '\0');
                  }
                }

                session.setAttribute(S_INSTALL_ADMIN_EMAIL, adminEmailInput);
                session.setAttribute(S_INSTALL_STEP2_DONE, Boolean.TRUE);
                step2Done = true;
                configuredAdminEmail = adminEmailInput;
                response.sendRedirect(ctx + "/install.jsp?step=3");
                return;
              }
            } finally {
              Arrays.fill(adminPasswordChars, '\0');
              Arrays.fill(adminConfirmChars, '\0');
            }
          }
        } else if ("step3_skip".equalsIgnoreCase(action)) {
          if (!step2Done) {
            error = "Complete Step 2 first.";
          } else {
            session.setAttribute(S_INSTALL_STEP3_DONE, Boolean.TRUE);
            step3Done = true;
            response.sendRedirect(ctx + "/install.jsp?step=4");
            return;
          }
        } else if ("step3_save".equalsIgnoreCase(action)) {
          if (!step2Done) {
            error = "Complete Step 2 first.";
          } else if (tenantUuid.isBlank()) {
            error = "Tenant context is unavailable.";
          } else {
            LinkedHashMap<String, String> toSave = settingsStore.read(tenantUuid);
            toSave.put("two_factor_policy", twoFactorPolicy);
            toSave.put("two_factor_default_engine", twoFactorEngine);
            toSave.put("password_policy_enabled", passwordPolicyEnabled ? "true" : "false");
            toSave.put("password_policy_min_length", String.valueOf(passwordPolicyMinLength));
            toSave.put("password_policy_require_uppercase", passwordPolicyRequireUpper ? "true" : "false");
            toSave.put("password_policy_require_lowercase", passwordPolicyRequireLower ? "true" : "false");
            toSave.put("password_policy_require_number", passwordPolicyRequireNumber ? "true" : "false");
            toSave.put("password_policy_require_symbol", passwordPolicyRequireSymbol ? "true" : "false");
            settingsStore.write(tenantUuid, toSave);

            session.setAttribute(S_INSTALL_STEP3_DONE, Boolean.TRUE);
            step3Done = true;
            response.sendRedirect(ctx + "/install.jsp?step=4");
            return;
          }
        } else if ("step4_skip".equalsIgnoreCase(action)) {
          if (!step2Done) {
            error = "Complete Step 2 first.";
          } else {
            session.setAttribute(S_INSTALL_STEP4_DONE, Boolean.TRUE);
            step4Done = true;
            response.sendRedirect(ctx + "/install.jsp?step=5");
            return;
          }
        } else if ("step4_save".equalsIgnoreCase(action)) {
          if (!step2Done) {
            error = "Complete Step 2 first.";
          } else if (tenantUuid.isBlank()) {
            error = "Tenant context is unavailable.";
          } else if (!"local".equals(storageBackend) && (storageEndpoint.isBlank() || storageAccessKey.isBlank() || storageSecret.isBlank())) {
            error = "Remote storage requires endpoint, access key, and secret.";
          } else if ("local".equals(storageBackend) && !"none".equals(externalDataAction)) {
            error = "External backup/restore actions require an external storage backend.";
          } else if ("restore_latest".equals(externalDataAction) && !externalRestoreConfirm) {
            error = "Confirm restore acknowledgement before restoring from external backup.";
          } else if ("onedrive_business".equals(storageBackend) && "public".equals(storageOnedriveAuthMode)
                  && !looksHttpUrl(storageOnedriveOauthCallbackUrl)) {
            error = "OneDrive public auth profile requires a valid OAuth callback URL.";
          } else if ("onedrive_business".equals(storageBackend) && "private".equals(storageOnedriveAuthMode)
                  && storageOnedrivePrivateRelayUrl.isBlank()) {
            error = "OneDrive private auth profile requires relay/admin exchange instructions.";
          } else if (clioEnabled && (!looksHttpUrl(clioBaseUrl) || clioClientId.isBlank() || clioClientSecret.isBlank())) {
            error = "Clio requires base URL, client ID, and client secret.";
          } else if (clioEnabled && "public".equals(clioAuthMode) && !looksHttpUrl(clioOauthCallbackUrl)) {
            error = "Clio public auth requires a valid OAuth callback URL.";
          } else if (clioEnabled && "private".equals(clioAuthMode) && clioPrivateRelayUrl.isBlank()) {
            error = "Clio private auth requires relay/admin exchange instructions.";
          } else if ("smtp".equals(emailProvider) && emailSmtpHost.isBlank()) {
            error = "SMTP email provider requires host.";
          } else if ("smtp".equals(emailProvider) && emailFromAddress.isBlank()) {
            error = "SMTP email provider requires From address.";
          } else if ("smtp".equals(emailProvider) && emailSmtpAuth
                  && (emailSmtpUsername.isBlank() || emailSmtpPassword.isBlank())) {
            error = "SMTP authentication requires username and password.";
          } else if ("microsoft_graph".equals(emailProvider)
                  && (emailGraphTenantId.isBlank() || emailGraphClientId.isBlank()
                  || emailGraphClientSecret.isBlank() || emailGraphSenderUser.isBlank())) {
            error = "Microsoft Graph requires tenant ID, client ID, client secret, and sender user.";
          } else if ("microsoft_graph".equals(emailProvider) && emailFromAddress.isBlank()) {
            error = "Microsoft Graph email provider requires From address.";
          } else {
            LinkedHashMap<String, String> toSave = settingsStore.read(tenantUuid);
            external_storage_data_sync syncService = external_storage_data_sync.defaultService();

            toSave.put("storage_backend", storageBackend);
            toSave.put("storage_max_path_length", String.valueOf(storageMaxPathLength));
            toSave.put("storage_max_filename_length", String.valueOf(storageMaxFilenameLength));
            if ("local".equals(storageBackend)) {
              toSave.put("storage_endpoint", "");
              toSave.put("storage_root_folder", "");
              toSave.put("storage_access_key", "");
              toSave.put("storage_secret", "");
            } else {
              toSave.put("storage_endpoint", storageEndpoint);
              toSave.put("storage_root_folder", storageRootFolder);
              toSave.put("storage_access_key", storageAccessKey);
              toSave.put("storage_secret", storageSecret);
            }
            toSave.put("storage_onedrive_auth_mode", "onedrive_business".equals(storageBackend) ? storageOnedriveAuthMode : "app_credentials");
            toSave.put("storage_onedrive_oauth_callback_url",
                    ("onedrive_business".equals(storageBackend) && "public".equals(storageOnedriveAuthMode))
                            ? storageOnedriveOauthCallbackUrl
                            : "");
            toSave.put("storage_onedrive_private_relay_url",
                    ("onedrive_business".equals(storageBackend) && "private".equals(storageOnedriveAuthMode))
                            ? storageOnedrivePrivateRelayUrl
                            : "");

            toSave.put("clio_enabled", clioEnabled ? "true" : "false");
            toSave.put("clio_base_url", clioEnabled ? clioBaseUrl : "");
            toSave.put("clio_client_id", clioEnabled ? clioClientId : "");
            toSave.put("clio_client_secret", clioEnabled ? clioClientSecret : "");
            toSave.put("clio_auth_mode", clioEnabled ? clioAuthMode : "public");
            toSave.put("clio_oauth_callback_url", (clioEnabled && "public".equals(clioAuthMode)) ? clioOauthCallbackUrl : "");
            toSave.put("clio_private_relay_url", (clioEnabled && "private".equals(clioAuthMode)) ? clioPrivateRelayUrl : "");

            toSave.put("email_provider", emailProvider);
            toSave.put("email_from_address", emailFromAddress);
            toSave.put("email_from_name", emailFromName);
            toSave.put("email_reply_to", emailReplyTo);
            toSave.put("email_smtp_host", "smtp".equals(emailProvider) ? emailSmtpHost : "");
            toSave.put("email_smtp_port", "smtp".equals(emailProvider) ? emailSmtpPort : "587");
            toSave.put("email_smtp_username", "smtp".equals(emailProvider) ? emailSmtpUsername : "");
            toSave.put("email_smtp_password", "smtp".equals(emailProvider) ? emailSmtpPassword : "");
            toSave.put("email_smtp_auth", ("smtp".equals(emailProvider) && emailSmtpAuth) ? "true" : "false");
            toSave.put("email_smtp_starttls", ("smtp".equals(emailProvider) && emailSmtpStarttls) ? "true" : "false");
            toSave.put("email_smtp_ssl", ("smtp".equals(emailProvider) && emailSmtpSsl) ? "true" : "false");
            toSave.put("email_graph_tenant_id", "microsoft_graph".equals(emailProvider) ? emailGraphTenantId : "");
            toSave.put("email_graph_client_id", "microsoft_graph".equals(emailProvider) ? emailGraphClientId : "");
            toSave.put("email_graph_client_secret", "microsoft_graph".equals(emailProvider) ? emailGraphClientSecret : "");
            toSave.put("email_graph_sender_user", "microsoft_graph".equals(emailProvider) ? emailGraphSenderUser : "");

            if ("restore_latest".equals(externalDataAction)) {
              external_storage_data_sync.RestoreResult restoreResult = syncService.restoreLatestForConfig(
                tenantUuid,
                storageBackend,
                storageEndpoint,
                storageAccessKey,
                storageSecret,
                storageRootFolder
              );
              String restoredSnapshot = safe(restoreResult.snapshotId).trim();
              message = "Restore completed from external snapshot " + (restoredSnapshot.isBlank() ? "(latest)" : restoredSnapshot) + ".";
              session.setAttribute(S_INSTALL_FLASH_MESSAGE, message);

              boolean restoredInstallComplete = false;
              try {
                restoredInstallComplete = installation_state.defaultStore().isCompleted();
              } catch (Exception ignored) {
                restoredInstallComplete = false;
              }
              if (restoredInstallComplete) {
                session.removeAttribute(S_INSTALL_STEP1_DONE);
                session.removeAttribute(S_INSTALL_STEP2_DONE);
                session.removeAttribute(S_INSTALL_STEP3_DONE);
                session.removeAttribute(S_INSTALL_STEP4_DONE);
                session.removeAttribute(S_INSTALL_ADMIN_EMAIL);
                response.sendRedirect(ctx + "/tenant_login.jsp?installed=1");
                return;
              }
            } else {
              settingsStore.write(tenantUuid, toSave);
              if ("backup_now".equals(externalDataAction) && !"local".equals(storageBackend)) {
                external_storage_data_sync.BackupResult backupResult = syncService.backupNowForConfig(
                  tenantUuid,
                  storageBackend,
                  storageEndpoint,
                  storageAccessKey,
                  storageSecret,
                  storageRootFolder
                );
                String backupSnapshot = safe(backupResult.snapshotId).trim();
                message = "External backup snapshot created: " + (backupSnapshot.isBlank() ? "(latest)" : backupSnapshot) + ".";
                session.setAttribute(S_INSTALL_FLASH_MESSAGE, message);
              }
            }

            session.setAttribute(S_INSTALL_STEP4_DONE, Boolean.TRUE);
            step4Done = true;
            response.sendRedirect(ctx + "/install.jsp?step=5");
            return;
          }
        } else if ("finish_install".equalsIgnoreCase(action)) {
          if (!(step1Done && step2Done && step3Done && step4Done)) {
            error = "Complete all setup steps first (you may use Skip on optional steps).";
          } else if (tenantUuid.isBlank()) {
            error = "Tenant context is unavailable.";
          } else {
            String adminForState = safe((String) session.getAttribute(S_INSTALL_ADMIN_EMAIL)).trim();
            if (!looksLikeEmail(adminForState)) adminForState = adminEmailInput;
            if (!looksLikeEmail(adminForState)) {
              error = "Administrator email is missing from setup state.";
            } else {
              installStore.markCompleted(tenantUuid, adminForState);
              session.removeAttribute(S_INSTALL_STEP1_DONE);
              session.removeAttribute(S_INSTALL_STEP2_DONE);
              session.removeAttribute(S_INSTALL_STEP3_DONE);
              session.removeAttribute(S_INSTALL_STEP4_DONE);
              session.removeAttribute(S_INSTALL_ADMIN_EMAIL);
              response.sendRedirect(ctx + "/tenant_login.jsp?installed=1");
              return;
            }
          }
        }
      } catch (Exception ex) {
        error = safe(ex.getMessage());
        if (error.isBlank()) error = "Installer action failed.";
      }
    }

    step1Done = Boolean.TRUE.equals(session.getAttribute(S_INSTALL_STEP1_DONE));
    step2Done = Boolean.TRUE.equals(session.getAttribute(S_INSTALL_STEP2_DONE));
    step3Done = Boolean.TRUE.equals(session.getAttribute(S_INSTALL_STEP3_DONE));
    step4Done = Boolean.TRUE.equals(session.getAttribute(S_INSTALL_STEP4_DONE));
    configuredAdminEmail = safe((String) session.getAttribute(S_INSTALL_ADMIN_EMAIL)).trim();
    if (!configuredAdminEmail.isBlank()) adminEmailInput = configuredAdminEmail;

    if (!step1Done) step = 1;
    else if (!step2Done && step > 2) step = 2;
    else if (!step3Done && step > 3) step = 3;
    else if (!step4Done && step > 4) step = 4;
    if (step < 1) step = 1;
    if (step > 5) step = 5;

    if (!"local".equals(storageBackend)
            && !tenantUuid.isBlank()
            && !storageEndpoint.isBlank()
            && !storageAccessKey.isBlank()
            && !storageSecret.isBlank()) {
      try {
        externalLatestSnapshot = external_storage_data_sync.defaultService().latestSnapshotForConfig(
          tenantUuid,
          storageBackend,
          storageEndpoint,
          storageAccessKey,
          storageSecret,
          storageRootFolder
        );
      } catch (Exception ignored) {
        externalLatestSnapshot = null;
      }
    }
  } else {
    response.setStatus(403);
  }
%>

<jsp:include page="header.jsp" />

<div class="container main">
  <section class="card">
    <div class="section-head">
      <div>
        <h1>Fresh Install Setup</h1>
        <div class="meta">Guided tenant bootstrap with required admin credentials and optional major configuration.</div>
      </div>
    </div>

    <% if (installCompleted) { %>
      <div class="alert alert-ok">
        Installation is already complete, so this page is locked.
      </div>
      <div style="height:12px;"></div>
      <div class="meta">
        Completed at: <strong><%= esc(installCompletedAt.isBlank() ? "(unknown)" : installCompletedAt) %></strong><br/>
        Administrator: <strong><%= esc(installCompletedAdmin.isBlank() ? "(unknown)" : installCompletedAdmin) %></strong>
      </div>
      <div class="actions" style="margin-top:12px;">
        <a class="btn" href="<%= ctx %>/tenant_login.jsp">Go to Sign In</a>
      </div>
    <% } else { %>
      <% if (message != null && !message.isBlank()) { %>
        <div class="alert alert-ok"><%= esc(message) %></div>
        <div style="height:12px;"></div>
      <% } %>
      <% if (error != null && !error.isBlank()) { %>
        <div class="alert alert-error"><%= esc(error) %></div>
        <div style="height:12px;"></div>
      <% } %>

      <div class="card" style="margin:0 0 12px 0;">
        <div class="meta">Step <strong><%= step %></strong> of 5</div>
        <div class="meta" style="margin-top:6px;">
          1) Tenant profile <%= step1Done ? "✓" : "" %> •
          2) Administrator credentials <%= step2Done ? "✓" : "" %> •
          3) Security defaults (optional) <%= step3Done ? "✓" : "" %> •
          4) Integrations/storage (optional) <%= step4Done ? "✓" : "" %> •
          5) Review and lock install
        </div>
      </div>

      <% if (step == 1) { %>
        <form class="form" method="post" action="<%= ctx %>/install.jsp?step=1">
          <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
          <input type="hidden" name="action" value="step1_save" />

          <label>
            <span>Tenant Name</span>
            <input type="text" name="tenantLabel" value="<%= esc(tenantLabelInput) %>" maxlength="64" required />
          </label>

          <div class="help">This label appears on the sign-in screen and in tenant-aware headers.</div>

          <div class="actions">
            <button class="btn" type="submit">Save and Continue</button>
          </div>
        </form>
      <% } %>

      <% if (step == 2) { %>
        <form class="form" method="post" action="<%= ctx %>/install.jsp?step=2">
          <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
          <input type="hidden" name="action" value="step2_save" />

          <label>
            <span>Tenant Administrator Email</span>
            <input type="email" name="adminEmail" value="<%= esc(adminEmailInput) %>" autocomplete="username" required />
          </label>

          <label>
            <span>Tenant Administrator Password</span>
            <input type="password" name="adminPassword" autocomplete="new-password" required />
          </label>

          <label>
            <span>Confirm Password</span>
            <input type="password" name="adminConfirmPassword" autocomplete="new-password" required />
          </label>

          <div class="help">
            The installer creates or upgrades this user to tenant administrator privileges.
          </div>

          <div class="actions">
            <button class="btn" type="submit">Save and Continue</button>
          </div>
        </form>
      <% } %>

      <% if (step == 3) { %>
        <form class="form" method="post" action="<%= ctx %>/install.jsp?step=3">
          <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />

          <label>
            <span>Two-Factor Policy</span>
            <select name="twoFactorPolicy">
              <option value="off" <%= "off".equals(twoFactorPolicy) ? "selected" : "" %>>Off</option>
              <option value="optional" <%= "optional".equals(twoFactorPolicy) ? "selected" : "" %>>Optional</option>
              <option value="required" <%= "required".equals(twoFactorPolicy) ? "selected" : "" %>>Required</option>
            </select>
          </label>

          <label>
            <span>Default Two-Factor Engine</span>
            <select name="twoFactorEngine">
              <option value="email_pin" <%= "email_pin".equals(twoFactorEngine) ? "selected" : "" %>>Email PIN</option>
              <option value="flowroute_sms" <%= "flowroute_sms".equals(twoFactorEngine) ? "selected" : "" %>>Flowroute SMS</option>
            </select>
          </label>

          <label class="inline">
            <input type="checkbox" name="passwordPolicyEnabled" value="1" <%= passwordPolicyEnabled ? "checked" : "" %> />
            <span>Enable password policy</span>
          </label>

          <label>
            <span>Password Minimum Length</span>
            <input type="number" min="8" max="128" name="passwordPolicyMinLength" value="<%= passwordPolicyMinLength %>" />
          </label>

          <label class="inline">
            <input type="checkbox" name="passwordPolicyRequireUppercase" value="1" <%= passwordPolicyRequireUpper ? "checked" : "" %> />
            <span>Require uppercase letter</span>
          </label>
          <label class="inline">
            <input type="checkbox" name="passwordPolicyRequireLowercase" value="1" <%= passwordPolicyRequireLower ? "checked" : "" %> />
            <span>Require lowercase letter</span>
          </label>
          <label class="inline">
            <input type="checkbox" name="passwordPolicyRequireNumber" value="1" <%= passwordPolicyRequireNumber ? "checked" : "" %> />
            <span>Require number</span>
          </label>
          <label class="inline">
            <input type="checkbox" name="passwordPolicyRequireSymbol" value="1" <%= passwordPolicyRequireSymbol ? "checked" : "" %> />
            <span>Require symbol</span>
          </label>

          <div class="actions">
            <button class="btn" type="submit" name="action" value="step3_save">Save and Continue</button>
            <button class="btn btn-ghost" type="submit" name="action" value="step3_skip">Skip For Now</button>
          </div>
        </form>
      <% } %>

      <% if (step == 4) { %>
        <form class="form" method="post" action="<%= ctx %>/install.jsp?step=4">
          <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />

          <h2 style="margin:0;">Storage</h2>
          <label>
            <span>Storage Backend</span>
            <select name="storageBackend">
              <option value="local" <%= "local".equals(storageBackend) ? "selected" : "" %>>Local (recommended)</option>
              <option value="sftp" <%= "sftp".equals(storageBackend) ? "selected" : "" %>>SFTP</option>
              <option value="ftp" <%= "ftp".equals(storageBackend) ? "selected" : "" %>>FTP</option>
              <option value="ftps" <%= "ftps".equals(storageBackend) ? "selected" : "" %>>FTPS</option>
              <option value="webdav" <%= "webdav".equals(storageBackend) ? "selected" : "" %>>WebDAV</option>
              <option value="s3_compatible" <%= "s3_compatible".equals(storageBackend) ? "selected" : "" %>>S3 Compatible</option>
              <option value="onedrive_business" <%= "onedrive_business".equals(storageBackend) ? "selected" : "" %>>OneDrive for Business</option>
            </select>
          </label>
          <label>
            <span>Storage Endpoint (remote backends)</span>
            <input type="text" name="storageEndpoint" value="<%= esc(storageEndpoint) %>" placeholder="file:///path, sftp://host/path, webdav://host/path, https://s3.endpoint.example, or https://graph.microsoft.com/v1.0/drives/{drive-id}/root:/controversies" />
          </label>
          <label>
            <span>Storage Access Key (remote backends)</span>
            <input type="text" name="storageAccessKey" value="<%= esc(storageAccessKey) %>" />
          </label>
          <label>
            <span>External Root Folder (optional)</span>
            <input type="text" name="storageRootFolder" value="<%= esc(storageRootFolder) %>" placeholder="backups/primary" />
          </label>
          <label>
            <span>Storage Secret (remote backends)</span>
            <input type="password" name="storageSecret" value="<%= esc(storageSecret) %>" />
          </label>
          <label>
            <span>OneDrive Auth Profile</span>
            <select name="storageOnedriveAuthMode">
              <option value="app_credentials" <%= "app_credentials".equals(storageOnedriveAuthMode) ? "selected" : "" %>>App credentials only</option>
              <option value="public" <%= "public".equals(storageOnedriveAuthMode) ? "selected" : "" %>>Public callback</option>
              <option value="private" <%= "private".equals(storageOnedriveAuthMode) ? "selected" : "" %>>Private relay/admin flow</option>
            </select>
          </label>
          <label>
            <span>OneDrive OAuth Callback URL (public profile)</span>
            <input type="text" name="storageOnedriveOauthCallbackUrl" value="<%= esc(storageOnedriveOauthCallbackUrl) %>" />
          </label>
          <label>
            <span>OneDrive Relay/Admin Exchange URL (private profile)</span>
            <input type="text" name="storageOnedrivePrivateRelayUrl" value="<%= esc(storageOnedrivePrivateRelayUrl) %>" />
          </label>
          <label>
            <span>External Max Path Length (chars)</span>
            <input type="number" min="0" max="8192" name="storageMaxPathLength" value="<%= storageMaxPathLength %>" />
          </label>
          <label>
            <span>External Max Filename Length (chars)</span>
            <input type="number" min="0" max="1024" name="storageMaxFilenameLength" value="<%= storageMaxFilenameLength %>" />
          </label>
          <div class="help">
            When external storage is enabled, the system automatically synchronizes a full <code>data/</code> directory backup once every day. Set path/filename limits to <code>0</code> to disable these constraints. OneDrive can use app credentials only, public callback auth, or private relay/admin auth.
          </div>
          <div class="help">
            TLS defaults to automatic self-signed cert generation. Optional certbot mode can be enabled later from Tenant Settings &rarr; Operations &rarr; TLS/SSL Runtime, or via runtime options: <code>CONTROVERSIES_SSL_MODE=certbot</code> plus either <code>CONTROVERSIES_CERTBOT_DOMAIN</code> (for <code>/etc/letsencrypt/live/&lt;domain&gt;</code>) or <code>CONTROVERSIES_CERTBOT_LIVE_DIR</code>. Optional overrides: <code>CONTROVERSIES_CERTBOT_OPENSSL_CMD</code> and <code>CONTROVERSIES_CERTBOT_FORCE_REBUILD=true</code>. Restart the app after TLS mode changes.
          </div>

          <h2 style="margin:12px 0 0 0;">External Backup / Restore (optional)</h2>
          <label>
            <span>Initialization Action</span>
            <select name="externalDataAction">
              <option value="none" <%= "none".equals(externalDataAction) ? "selected" : "" %>>No immediate action</option>
              <option value="backup_now" <%= "backup_now".equals(externalDataAction) ? "selected" : "" %>>Run a full backup now</option>
              <option value="restore_latest" <%= "restore_latest".equals(externalDataAction) ? "selected" : "" %>>Restore from latest backup</option>
            </select>
          </label>
          <label class="inline">
            <input type="checkbox" name="externalRestoreConfirm" value="1" <%= externalRestoreConfirm ? "checked" : "" %> />
            <span>I understand restore replaces local <code>data/</code> only after validation and keeps external source data unchanged.</span>
          </label>

          <% if (externalLatestSnapshot != null && externalLatestSnapshot.available) { %>
            <div class="meta">
              Latest external snapshot: <strong><%= esc(externalLatestSnapshot.snapshotId) %></strong>
              • Completed at <strong><%= esc(externalLatestSnapshot.completedAt) %></strong>
              • Files: <strong><%= externalLatestSnapshot.fileCount %></strong>
            </div>
          <% } else if (!"local".equals(storageBackend) && !storageEndpoint.isBlank() && !storageAccessKey.isBlank() && !storageSecret.isBlank()) { %>
            <div class="meta">No existing snapshot detected for this external storage source.</div>
          <% } %>

          <h2 style="margin:12px 0 0 0;">Clio Integration</h2>
          <label class="inline">
            <input type="checkbox" name="clioEnabled" value="1" <%= clioEnabled ? "checked" : "" %> />
            <span>Enable Clio integration</span>
          </label>
          <label>
            <span>Clio Base URL</span>
            <input type="text" name="clioBaseUrl" value="<%= esc(clioBaseUrl) %>" />
          </label>
          <label>
            <span>Clio Client ID</span>
            <input type="text" name="clioClientId" value="<%= esc(clioClientId) %>" />
          </label>
          <label>
            <span>Clio Client Secret</span>
            <input type="password" name="clioClientSecret" value="<%= esc(clioClientSecret) %>" />
          </label>
          <label>
            <span>Clio Auth Mode</span>
            <select name="clioAuthMode">
              <option value="public" <%= "public".equals(clioAuthMode) ? "selected" : "" %>>Public</option>
              <option value="private" <%= "private".equals(clioAuthMode) ? "selected" : "" %>>Private</option>
            </select>
          </label>
          <label>
            <span>Clio OAuth Callback URL (public mode)</span>
            <input type="text" name="clioOauthCallbackUrl" value="<%= esc(clioOauthCallbackUrl) %>" />
          </label>
          <label>
            <span>Clio Relay/Admin Exchange URL (private mode)</span>
            <input type="text" name="clioPrivateRelayUrl" value="<%= esc(clioPrivateRelayUrl) %>" />
          </label>

          <h2 style="margin:12px 0 0 0;">Notification Email</h2>
          <label>
            <span>Email Provider</span>
            <select name="emailProvider">
              <option value="disabled" <%= "disabled".equals(emailProvider) ? "selected" : "" %>>Disabled</option>
              <option value="smtp" <%= "smtp".equals(emailProvider) ? "selected" : "" %>>SMTP</option>
              <option value="microsoft_graph" <%= "microsoft_graph".equals(emailProvider) ? "selected" : "" %>>Microsoft Graph</option>
            </select>
          </label>
          <label>
            <span>From Address</span>
            <input type="email" name="emailFromAddress" value="<%= esc(emailFromAddress) %>" />
          </label>
          <label>
            <span>From Name</span>
            <input type="text" name="emailFromName" value="<%= esc(emailFromName) %>" />
          </label>
          <label>
            <span>Reply-To Address</span>
            <input type="email" name="emailReplyTo" value="<%= esc(emailReplyTo) %>" />
          </label>

          <label>
            <span>SMTP Host</span>
            <input type="text" name="emailSmtpHost" value="<%= esc(emailSmtpHost) %>" />
          </label>
          <label>
            <span>SMTP Port</span>
            <input type="number" min="1" max="65535" name="emailSmtpPort" value="<%= esc(emailSmtpPort) %>" />
          </label>
          <label>
            <span>SMTP Username</span>
            <input type="text" name="emailSmtpUsername" value="<%= esc(emailSmtpUsername) %>" />
          </label>
          <label>
            <span>SMTP Password</span>
            <input type="password" name="emailSmtpPassword" value="<%= esc(emailSmtpPassword) %>" />
          </label>
          <label class="inline">
            <input type="checkbox" name="emailSmtpAuth" value="1" <%= emailSmtpAuth ? "checked" : "" %> />
            <span>SMTP authentication required</span>
          </label>
          <label class="inline">
            <input type="checkbox" name="emailSmtpStarttls" value="1" <%= emailSmtpStarttls ? "checked" : "" %> />
            <span>Use STARTTLS</span>
          </label>
          <label class="inline">
            <input type="checkbox" name="emailSmtpSsl" value="1" <%= emailSmtpSsl ? "checked" : "" %> />
            <span>Use SSL</span>
          </label>

          <label>
            <span>Graph Tenant ID</span>
            <input type="text" name="emailGraphTenantId" value="<%= esc(emailGraphTenantId) %>" />
          </label>
          <label>
            <span>Graph Client ID</span>
            <input type="text" name="emailGraphClientId" value="<%= esc(emailGraphClientId) %>" />
          </label>
          <label>
            <span>Graph Client Secret</span>
            <input type="password" name="emailGraphClientSecret" value="<%= esc(emailGraphClientSecret) %>" />
          </label>
          <label>
            <span>Graph Sender User</span>
            <input type="email" name="emailGraphSenderUser" value="<%= esc(emailGraphSenderUser) %>" />
          </label>

          <div class="actions">
            <button class="btn" type="submit" name="action" value="step4_save">Save and Continue</button>
            <button class="btn btn-ghost" type="submit" name="action" value="step4_skip">Skip For Now</button>
          </div>
        </form>
      <% } %>

      <% if (step == 5) { %>
        <div class="card" style="margin:0;">
          <h2 style="margin-top:0;">Review</h2>
          <div class="meta">
            Tenant: <strong><%= esc(tenantLabel.isBlank() ? "(unnamed)" : tenantLabel) %></strong><br/>
            Administrator: <strong><%= esc(configuredAdminEmail.isBlank() ? "(not set)" : configuredAdminEmail) %></strong><br/>
            Security defaults: <strong><%= esc(step3Done ? "saved/skipped" : "pending") %></strong><br/>
            Integrations/storage: <strong><%= esc(step4Done ? "saved/skipped" : "pending") %></strong>
          </div>
          <div class="help" style="margin-top:10px;">
            After completion, <code>install.jsp</code> is locked and can no longer be used.
          </div>
          <form class="form" method="post" action="<%= ctx %>/install.jsp?step=5" style="margin-top:10px;">
            <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
            <input type="hidden" name="action" value="finish_install" />
            <div class="actions">
              <button class="btn" type="submit">Complete Installation</button>
            </div>
          </form>
        </div>
      <% } %>
    <% } %>
  </section>
</div>

<jsp:include page="footer.jsp" />
