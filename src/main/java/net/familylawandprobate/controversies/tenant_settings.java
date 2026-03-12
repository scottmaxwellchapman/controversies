package net.familylawandprobate.controversies;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * tenant_settings
 *
 * Stores tenant settings in:
 *   data/tenants/{tenantUuid}/settings/tenant_settings.json
 * Stores encrypted secrets in:
 *   data/tenants/{tenantUuid}/settings/tenant_secrets.json
 */
public final class tenant_settings {

    private static final String[] ALLOWED_KEYS = new String[] {
            "storage_backend",
            "storage_endpoint",
            "storage_root_folder",
            "storage_access_key",
            "storage_secret",
            "storage_encryption_mode",
            "storage_encryption_key",
            "storage_s3_sse_mode",
            "storage_s3_sse_kms_key_id",
            "storage_onedrive_auth_mode",
            "storage_onedrive_oauth_callback_url",
            "storage_onedrive_private_relay_url",
            "integration_deployment_topology",
            "storage_cache_size_ftp_mb",
            "storage_cache_size_ftps_mb",
            "storage_cache_size_sftp_mb",
            "storage_cache_size_webdav_mb",
            "storage_cache_size_s3_compatible_mb",
            "storage_cache_size_onedrive_business_mb",
            "storage_dedup_links_enabled",
            "storage_max_path_length",
            "storage_max_filename_length",
            "clio_base_url",
            "clio_client_id",
            "clio_client_secret",
            "clio_auth_mode",
            "clio_oauth_callback_url",
            "clio_private_relay_url",
            "clio_enabled",
            "clio_access_token",
            "clio_refresh_token",
            "clio_storage_mode",
            "clio_matters_last_sync_at",
            "clio_matters_sync_interval_minutes",
            "clio_documents_last_sync_at",
            "clio_contacts_last_sync_at",
            "clio_contacts_last_sync_status",
            "clio_contacts_last_sync_error",
            "office365_contacts_sync_enabled",
            "office365_contacts_sync_interval_minutes",
            "office365_contacts_sync_sources_json",
            "office365_contacts_last_sync_at",
            "office365_contacts_last_sync_status",
            "office365_contacts_last_sync_error",
            "office365_calendar_sync_enabled",
            "office365_calendar_sync_interval_minutes",
            "office365_calendar_sync_sources_json",
            "office365_calendar_last_sync_at",
            "office365_calendar_last_sync_status",
            "office365_calendar_last_sync_error",
            "self_upgrade_enabled",
            "self_upgrade_day_of_week",
            "self_upgrade_time_local",
            "self_upgrade_schedule_zone",
            "self_upgrade_git_remote",
            "self_upgrade_git_branch",
            "self_upgrade_build_command",
            "self_upgrade_restart_command",
            "self_upgrade_allow_dirty_worktree",
            "self_upgrade_command_timeout_minutes",
            "feature_advanced_assembly",
            "feature_async_sync",
            "theme_mode_default",
            "theme_use_location",
            "theme_latitude",
            "theme_longitude",
            "theme_light_start_hour",
            "theme_dark_start_hour",
            "theme_text_size_default",
            "password_policy_enabled",
            "password_policy_min_length",
            "password_policy_require_uppercase",
            "password_policy_require_lowercase",
            "password_policy_require_number",
            "password_policy_require_symbol",
            "secret_rotation_storage_at",
            "secret_rotation_clio_at",
            "storage_connection_status",
            "storage_connection_checked_at",
            "clio_connection_status",
            "clio_connection_checked_at",
            "clio_auth_health_status",
            "clio_auth_health_checked_at",
            "email_provider",
            "email_from_address",
            "email_from_name",
            "email_reply_to",
            "email_connect_timeout_ms",
            "email_read_timeout_ms",
            "email_queue_poll_seconds",
            "email_queue_batch_size",
            "email_queue_max_attempts",
            "email_queue_backoff_base_ms",
            "email_queue_backoff_max_ms",
            "email_connection_status",
            "email_connection_checked_at",
            "email_smtp_host",
            "email_smtp_port",
            "email_smtp_username",
            "email_smtp_password",
            "email_smtp_auth",
            "email_smtp_starttls",
            "email_smtp_ssl",
            "email_smtp_helo_domain",
            "email_graph_tenant_id",
            "email_graph_client_id",
            "email_graph_client_secret",
            "email_graph_sender_user",
            "email_graph_scope",
            "two_factor_policy",
            "two_factor_default_engine",
            "flowroute_sms_access_key",
            "flowroute_sms_secret_key",
            "flowroute_sms_from_number",
            "flowroute_sms_api_base_url"
    };

    private static final String[] SECRET_KEYS = new String[] {
            "storage_access_key",
            "storage_secret",
            "storage_encryption_key",
            "clio_client_secret",
            "clio_access_token",
            "clio_refresh_token",
            "office365_contacts_sync_sources_json",
            "office365_calendar_sync_sources_json",
            "email_smtp_password",
            "email_graph_client_secret",
            "flowroute_sms_access_key",
            "flowroute_sms_secret_key"
    };

    public static final class StartupSelfCheckResult {
        public final boolean ok;
        public final List<String> failures;

        public StartupSelfCheckResult(boolean ok, List<String> failures) {
            this.ok = ok;
            this.failures = failures == null ? List.of() : failures;
        }
    }

    public static tenant_settings defaultStore() {
        return new tenant_settings();
    }

    public static final class PasswordPolicy {
        public final boolean enabled;
        public final int minLength;
        public final boolean requireUppercase;
        public final boolean requireLowercase;
        public final boolean requireNumber;
        public final boolean requireSymbol;

        public PasswordPolicy(boolean enabled,
                              int minLength,
                              boolean requireUppercase,
                              boolean requireLowercase,
                              boolean requireNumber,
                              boolean requireSymbol) {
            this.enabled = enabled;
            this.minLength = Math.max(1, minLength);
            this.requireUppercase = requireUppercase;
            this.requireLowercase = requireLowercase;
            this.requireNumber = requireNumber;
            this.requireSymbol = requireSymbol;
        }

        public List<String> validate(char[] password) {
            ArrayList<String> issues = new ArrayList<String>();
            if (!enabled) return issues;

            char[] pw = password == null ? new char[0] : password;
            if (pw.length < minLength) {
                issues.add("Password must be at least " + minLength + " characters.");
            }

            boolean hasUpper = false;
            boolean hasLower = false;
            boolean hasDigit = false;
            boolean hasSymbol = false;

            for (int i = 0; i < pw.length; i++) {
                char ch = pw[i];
                if (Character.isUpperCase(ch)) hasUpper = true;
                else if (Character.isLowerCase(ch)) hasLower = true;
                else if (Character.isDigit(ch)) hasDigit = true;
                else if (!Character.isWhitespace(ch)) hasSymbol = true;
            }

            if (requireUppercase && !hasUpper) issues.add("Password must include at least one uppercase letter.");
            if (requireLowercase && !hasLower) issues.add("Password must include at least one lowercase letter.");
            if (requireNumber && !hasDigit) issues.add("Password must include at least one number.");
            if (requireSymbol && !hasSymbol) issues.add("Password must include at least one symbol.");
            return issues;
        }
    }

    public PasswordPolicy readPasswordPolicy(String tenantUuid) {
        return passwordPolicyFromSettings(read(tenantUuid));
    }

    public PasswordPolicy passwordPolicyFromSettings(Map<String, String> cfg) {
        Map<String, String> safeCfg = cfg == null ? Map.of() : cfg;

        boolean enabled = truthy(safeCfg.get("password_policy_enabled"));
        int minLength = parseInt(safeCfg.get("password_policy_min_length"), 12);
        if (minLength < 8 || minLength > 128) minLength = 12;

        boolean requireUppercase = truthy(safeCfg.get("password_policy_require_uppercase"));
        boolean requireLowercase = truthy(safeCfg.get("password_policy_require_lowercase"));
        boolean requireNumber = truthy(safeCfg.get("password_policy_require_number"));
        boolean requireSymbol = truthy(safeCfg.get("password_policy_require_symbol"));

        return new PasswordPolicy(
                enabled,
                minLength,
                requireUppercase,
                requireLowercase,
                requireNumber,
                requireSymbol
        );
    }

    public List<String> validatePasswordAgainstPolicy(String tenantUuid, char[] password) {
        PasswordPolicy policy = readPasswordPolicy(tenantUuid);
        return policy.validate(password);
    }

    public LinkedHashMap<String, String> read(String tenantUuid) {
        LinkedHashMap<String, String> out = defaults();
        try {
            Path p = settingsPath(tenantUuid);
            if (p != null && Files.exists(p)) {
                String raw = Files.readString(p, StandardCharsets.UTF_8);
                LinkedHashMap<String, String> parsed = parseSimpleJsonObject(raw);
                for (Map.Entry<String, String> e : sanitizeSettings(parsed).entrySet()) {
                    out.put(e.getKey(), e.getValue());
                }
            }

            LinkedHashMap<String, String> secrets = readSecrets(tenantUuid);
            for (Map.Entry<String, String> e : secrets.entrySet()) {
                out.put(e.getKey(), e.getValue());
            }
            return out;
        } catch (Exception ignored) {
            return out;
        }
    }

    public void write(String tenantUuid, Map<String, String> settings) throws Exception {
        Path p = settingsPath(tenantUuid);
        if (p == null) throw new IllegalArgumentException("tenantUuid is required");

        LinkedHashMap<String, String> clean = sanitizeSettings(settings);
        LinkedHashMap<String, String> merged = defaults();
        merged.putAll(clean);

        LinkedHashMap<String, String> general = new LinkedHashMap<String, String>();
        LinkedHashMap<String, String> secrets = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> e : merged.entrySet()) {
            if (e == null) continue;
            String k = normalizeKey(e.getKey());
            if (k.isBlank()) continue;
            if (isSecretKey(k)) secrets.put(k, safe(e.getValue()));
            else general.put(k, safe(e.getValue()));
        }

        Files.createDirectories(p.getParent());
        Files.writeString(
                p,
                toSimpleJsonObject(general),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        );
        writeSecrets(tenantUuid, secrets);
    }

    public StartupSelfCheckResult startupSelfCheckAllTenants() {
        List<String> failures = new ArrayList<String>();
        try {
            Path tenantsRoot = Paths.get("data", "tenants").toAbsolutePath();
            if (!Files.exists(tenantsRoot)) return new StartupSelfCheckResult(true, failures);
            try (Stream<Path> tenantDirs = Files.list(tenantsRoot)) {
                for (Path p : tenantDirs.toList()) {
                    if (p == null || !Files.isDirectory(p)) continue;
                    String tenantUuid = safe(p.getFileName() == null ? "" : p.getFileName().toString()).trim();
                    if (tenantUuid.isBlank()) continue;
                    LinkedHashMap<String, String> cfg = read(tenantUuid);
                    validateEnabledIntegrationSecrets(tenantUuid, cfg, failures);
                }
            }
        } catch (Exception ex) {
            failures.add("startup_self_check_failed: " + safe(ex.getMessage()));
        }
        return new StartupSelfCheckResult(failures.isEmpty(), failures);
    }

    public LinkedHashMap<String, String> sanitizeSettings(Map<String, String> settings) {
        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
        if (settings == null || settings.isEmpty()) return out;

        for (Map.Entry<String, String> e : settings.entrySet()) {
            if (e == null) continue;
            String rawKey = normalizeKey(e.getKey());
            if (!isAllowed(rawKey)) continue;

            String value = normalizeValue(rawKey, e.getValue());
            out.put(rawKey, value);
        }
        return out;
    }

    public String normalizeKey(String key) {
        String k = safe(key).trim().toLowerCase(Locale.ROOT);
        if (k.isBlank()) return "";

        StringBuilder sb = new StringBuilder(k.length());
        boolean lastUnderscore = false;
        for (int i = 0; i < k.length(); i++) {
            char ch = k.charAt(i);

            boolean ok =
                    (ch >= 'a' && ch <= 'z') ||
                            (ch >= '0' && ch <= '9') ||
                            ch == '_' || ch == '-' || ch == '.';

            if (ok) {
                sb.append(ch);
                lastUnderscore = false;
            } else if (Character.isWhitespace(ch)) {
                if (!lastUnderscore && sb.length() > 0) {
                    sb.append('_');
                    lastUnderscore = true;
                }
            } else {
                if (!lastUnderscore && sb.length() > 0) {
                    sb.append('_');
                    lastUnderscore = true;
                }
            }
        }

        String out = sb.toString();
        while (out.startsWith("_")) out = out.substring(1);
        while (out.endsWith("_")) out = out.substring(0, out.length() - 1);
        if (out.length() > 80) out = out.substring(0, 80);
        return out;
    }

    public String nowIso() {
        return app_clock.now().toString();
    }

    private LinkedHashMap<String, String> defaults() {
        LinkedHashMap<String, String> d = new LinkedHashMap<String, String>();
        d.put("storage_backend", "local");
        d.put("storage_endpoint", "");
        d.put("storage_root_folder", "");
        d.put("storage_access_key", "");
        d.put("storage_secret", "");
        d.put("storage_encryption_mode", "disabled");
        d.put("storage_encryption_key", "");
        d.put("storage_s3_sse_mode", "none");
        d.put("storage_s3_sse_kms_key_id", "");
        d.put("storage_onedrive_auth_mode", "app_credentials");
        d.put("storage_onedrive_oauth_callback_url", "");
        d.put("storage_onedrive_private_relay_url", "");
        d.put("integration_deployment_topology", "public");
        d.put("storage_cache_size_ftp_mb", "1024");
        d.put("storage_cache_size_ftps_mb", "1024");
        d.put("storage_cache_size_sftp_mb", "1024");
        d.put("storage_cache_size_webdav_mb", "1024");
        d.put("storage_cache_size_s3_compatible_mb", "1024");
        d.put("storage_cache_size_onedrive_business_mb", "1024");
        d.put("storage_dedup_links_enabled", "true");
        d.put("storage_max_path_length", "0");
        d.put("storage_max_filename_length", "0");
        d.put("clio_base_url", "");
        d.put("clio_client_id", "");
        d.put("clio_client_secret", "");
        d.put("clio_auth_mode", "public");
        d.put("clio_oauth_callback_url", "");
        d.put("clio_private_relay_url", "");
        d.put("clio_enabled", "false");
        d.put("clio_access_token", "");
        d.put("clio_refresh_token", "");
        d.put("clio_storage_mode", "disabled");
        d.put("clio_matters_last_sync_at", "");
        d.put("clio_matters_sync_interval_minutes", "15");
        d.put("clio_documents_last_sync_at", "");
        d.put("clio_contacts_last_sync_at", "");
        d.put("clio_contacts_last_sync_status", "never");
        d.put("clio_contacts_last_sync_error", "");
        d.put("office365_contacts_sync_enabled", "false");
        d.put("office365_contacts_sync_interval_minutes", "30");
        d.put("office365_contacts_sync_sources_json", "[]");
        d.put("office365_contacts_last_sync_at", "");
        d.put("office365_contacts_last_sync_status", "never");
        d.put("office365_contacts_last_sync_error", "");
        d.put("office365_calendar_sync_enabled", "false");
        d.put("office365_calendar_sync_interval_minutes", "30");
        d.put("office365_calendar_sync_sources_json", "[]");
        d.put("office365_calendar_last_sync_at", "");
        d.put("office365_calendar_last_sync_status", "never");
        d.put("office365_calendar_last_sync_error", "");
        d.put("self_upgrade_enabled", "true");
        d.put("self_upgrade_day_of_week", "SATURDAY");
        d.put("self_upgrade_time_local", "04:00");
        d.put("self_upgrade_schedule_zone", "");
        d.put("self_upgrade_git_remote", "origin");
        d.put("self_upgrade_git_branch", "");
        d.put("self_upgrade_build_command", "mvn -q -DskipTests package");
        d.put("self_upgrade_restart_command", "");
        d.put("self_upgrade_allow_dirty_worktree", "false");
        d.put("self_upgrade_command_timeout_minutes", "30");
        d.put("feature_advanced_assembly", "false");
        d.put("feature_async_sync", "false");
        d.put("bpm_aging_alarm_enabled", "false");
        d.put("bpm_aging_alarm_interval_minutes", "15");
        d.put("bpm_aging_alarm_running_stale_minutes", "60");
        d.put("bpm_aging_alarm_review_stale_minutes", "1440");
        d.put("bpm_aging_alarm_max_issues_per_scan", "200");
        d.put("theme_mode_default", "auto");
        d.put("theme_use_location", "true");
        d.put("theme_latitude", "");
        d.put("theme_longitude", "");
        d.put("theme_light_start_hour", "7");
        d.put("theme_dark_start_hour", "19");
        d.put("theme_text_size_default", "md");
        d.put("password_policy_enabled", "false");
        d.put("password_policy_min_length", "12");
        d.put("password_policy_require_uppercase", "false");
        d.put("password_policy_require_lowercase", "false");
        d.put("password_policy_require_number", "false");
        d.put("password_policy_require_symbol", "false");
        d.put("secret_rotation_storage_at", "");
        d.put("secret_rotation_clio_at", "");
        d.put("storage_connection_status", "unknown");
        d.put("storage_connection_checked_at", "");
        d.put("clio_connection_status", "unknown");
        d.put("clio_connection_checked_at", "");
        d.put("clio_auth_health_status", "unknown");
        d.put("clio_auth_health_checked_at", "");
        d.put("email_provider", "disabled");
        d.put("email_from_address", "");
        d.put("email_from_name", "");
        d.put("email_reply_to", "");
        d.put("email_connect_timeout_ms", "15000");
        d.put("email_read_timeout_ms", "20000");
        d.put("email_queue_poll_seconds", "5");
        d.put("email_queue_batch_size", "10");
        d.put("email_queue_max_attempts", "8");
        d.put("email_queue_backoff_base_ms", "2000");
        d.put("email_queue_backoff_max_ms", "300000");
        d.put("email_connection_status", "unknown");
        d.put("email_connection_checked_at", "");
        d.put("email_smtp_host", "");
        d.put("email_smtp_port", "587");
        d.put("email_smtp_username", "");
        d.put("email_smtp_password", "");
        d.put("email_smtp_auth", "true");
        d.put("email_smtp_starttls", "true");
        d.put("email_smtp_ssl", "false");
        d.put("email_smtp_helo_domain", "");
        d.put("email_graph_tenant_id", "");
        d.put("email_graph_client_id", "");
        d.put("email_graph_client_secret", "");
        d.put("email_graph_sender_user", "");
        d.put("email_graph_scope", "https://graph.microsoft.com/.default");
        d.put("two_factor_policy", "off");
        d.put("two_factor_default_engine", "email_pin");
        d.put("flowroute_sms_access_key", "");
        d.put("flowroute_sms_secret_key", "");
        d.put("flowroute_sms_from_number", "");
        d.put("flowroute_sms_api_base_url", "https://api.flowroute.com/v2.2/messages");
        return d;
    }

    private boolean isAllowed(String key) {
        for (int i = 0; i < ALLOWED_KEYS.length; i++) {
            if (ALLOWED_KEYS[i].equals(key)) return true;
        }
        return false;
    }

    private boolean isSecretKey(String key) {
        for (int i = 0; i < SECRET_KEYS.length; i++) {
            if (SECRET_KEYS[i].equals(key)) return true;
        }
        return false;
    }

    private String normalizeValue(String key, String value) {
        String v = safe(value).trim();

        if ("storage_dedup_links_enabled".equals(key)) {
            if (v.isBlank()) return "true";
            String b = v.toLowerCase(Locale.ROOT);
            if ("1".equals(b) || "true".equals(b) || "on".equals(b) || "yes".equals(b)) return "true";
            if ("0".equals(b) || "false".equals(b) || "off".equals(b) || "no".equals(b)) return "false";
            return "true";
        }

        if ("feature_advanced_assembly".equals(key) || "feature_async_sync".equals(key)
                || "clio_enabled".equals(key) || "office365_contacts_sync_enabled".equals(key)
                || "office365_calendar_sync_enabled".equals(key)
                || "theme_use_location".equals(key)
                || "email_smtp_auth".equals(key) || "email_smtp_starttls".equals(key)
                || "email_smtp_ssl".equals(key) || "password_policy_enabled".equals(key)
                || "password_policy_require_uppercase".equals(key)
                || "password_policy_require_lowercase".equals(key)
                || "password_policy_require_number".equals(key)
                || "password_policy_require_symbol".equals(key)
                || "self_upgrade_enabled".equals(key)
                || "self_upgrade_allow_dirty_worktree".equals(key)) {
            return truthy(v) ? "true" : "false";
        }

        if ("password_policy_min_length".equals(key)) {
            int n = parseInt(v, 12);
            if (n < 8 || n > 128) return "12";
            return String.valueOf(n);
        }

        if ("email_provider".equals(key)) {
            String mode = v.toLowerCase(Locale.ROOT);
            if (!"disabled".equals(mode) && !"smtp".equals(mode) && !"microsoft_graph".equals(mode)) return "disabled";
            return mode;
        }

        if ("two_factor_policy".equals(key)) {
            String mode = v.toLowerCase(Locale.ROOT);
            if (!"off".equals(mode) && !"optional".equals(mode) && !"required".equals(mode)) return "off";
            return mode;
        }

        if ("two_factor_default_engine".equals(key)) {
            String mode = v.toLowerCase(Locale.ROOT);
            if (!"email_pin".equals(mode) && !"flowroute_sms".equals(mode)) return "email_pin";
            return mode;
        }

        if ("flowroute_sms_api_base_url".equals(key)) {
            String lower = v.toLowerCase(Locale.ROOT);
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
                return "https://api.flowroute.com/v2.2/messages";
            }
            return v;
        }

        if ("flowroute_sms_from_number".equals(key)) {
            String s = v.replaceAll("[^0-9+]", "");
            if (s.length() > 20) s = s.substring(0, 20);
            return s;
        }

        if ("theme_mode_default".equals(key)) {
            String mode = v.toLowerCase(Locale.ROOT);
            if (!"light".equals(mode) && !"dark".equals(mode) && !"auto".equals(mode)) return "auto";
            return mode;
        }

        if ("theme_text_size_default".equals(key)) {
            String size = v.toLowerCase(Locale.ROOT);
            if (!"sm".equals(size) && !"md".equals(size) && !"lg".equals(size) && !"xl".equals(size)) return "md";
            return size;
        }

        if ("theme_light_start_hour".equals(key)) {
            int hour = parseInt(v, 7);
            if (hour < 0 || hour > 23) return "7";
            return String.valueOf(hour);
        }

        if ("theme_dark_start_hour".equals(key)) {
            int hour = parseInt(v, 19);
            if (hour < 0 || hour > 23) return "19";
            return String.valueOf(hour);
        }

        if ("theme_latitude".equals(key)) {
            if (v.isBlank()) return "";
            try {
                double lat = Double.parseDouble(v);
                if (lat < -90.0 || lat > 90.0) return "";
                return String.valueOf(lat);
            } catch (Exception ignored) {
                return "";
            }
        }

        if ("theme_longitude".equals(key)) {
            if (v.isBlank()) return "";
            try {
                double lon = Double.parseDouble(v);
                if (lon < -180.0 || lon > 180.0) return "";
                return String.valueOf(lon);
            } catch (Exception ignored) {
                return "";
            }
        }


        if ("clio_auth_mode".equals(key)) {
            String mode = v.toLowerCase(Locale.ROOT);
            if (!"public".equals(mode) && !"private".equals(mode)) return "public";
            return mode;
        }

        if ("storage_onedrive_auth_mode".equals(key)) {
            String mode = v.toLowerCase(Locale.ROOT);
            if ("public".equals(mode) || "private".equals(mode) || "app_credentials".equals(mode)) return mode;
            return "app_credentials";
        }

        if ("integration_deployment_topology".equals(key)) {
            String mode = v.toLowerCase(Locale.ROOT);
            if ("private".equals(mode) || "vpn".equals(mode) || "private_vpn".equals(mode)
                    || "vpn_only".equals(mode) || "behind_vpn".equals(mode)) return "vpn";
            return "public";
        }

        if ("clio_storage_mode".equals(key)) {
            String mode = v.toLowerCase(Locale.ROOT);
            if (!"enabled".equals(mode) && !"disabled".equals(mode)) return "disabled";
            return mode;
        }

        if ("clio_matters_sync_interval_minutes".equals(key)) {
            int n = parseInt(v, 15);
            if (n < 1 || n > 1440) return "15";
            return String.valueOf(n);
        }

        if ("clio_contacts_last_sync_status".equals(key)) {
            String s = v.toLowerCase(Locale.ROOT);
            if (!"ok".equals(s) && !"failed".equals(s) && !"never".equals(s)) return "never";
            return s;
        }

        if ("office365_contacts_sync_interval_minutes".equals(key)) {
            int n = parseInt(v, 30);
            if (n < 1 || n > 1440) return "30";
            return String.valueOf(n);
        }

        if ("office365_calendar_sync_interval_minutes".equals(key)) {
            int n = parseInt(v, 30);
            if (n < 1 || n > 1440) return "30";
            return String.valueOf(n);
        }

        if ("office365_contacts_last_sync_status".equals(key)) {
            String s = v.toLowerCase(Locale.ROOT);
            if (!"ok".equals(s) && !"failed".equals(s) && !"never".equals(s)) return "never";
            return s;
        }

        if ("office365_calendar_last_sync_status".equals(key)) {
            String s = v.toLowerCase(Locale.ROOT);
            if (!"ok".equals(s) && !"failed".equals(s) && !"never".equals(s)) return "never";
            return s;
        }

        if ("self_upgrade_day_of_week".equals(key)) {
            String day = v.toUpperCase(Locale.ROOT);
            if (!"MONDAY".equals(day) && !"TUESDAY".equals(day) && !"WEDNESDAY".equals(day)
                    && !"THURSDAY".equals(day) && !"FRIDAY".equals(day)
                    && !"SATURDAY".equals(day) && !"SUNDAY".equals(day)) {
                return "SATURDAY";
            }
            return day;
        }

        if ("self_upgrade_time_local".equals(key)) {
            if (v.isBlank()) return "04:00";
            try {
                java.time.LocalTime t = java.time.LocalTime.parse(v);
                return t.withSecond(0).withNano(0).toString();
            } catch (Exception ignored) {
                return "04:00";
            }
        }

        if ("self_upgrade_schedule_zone".equals(key)) {
            if (v.isBlank()) return "";
            try {
                java.time.ZoneId.of(v);
                return v;
            } catch (Exception ignored) {
                return "";
            }
        }

        if ("self_upgrade_command_timeout_minutes".equals(key)) {
            int n = parseInt(v, 30);
            if (n < 1 || n > 240) return "30";
            return String.valueOf(n);
        }

        if ("office365_contacts_sync_sources_json".equals(key)) {
            if (v.isBlank()) return "[]";
            if (v.length() > 200000) v = v.substring(0, 200000);
            return v;
        }

        if ("office365_calendar_sync_sources_json".equals(key)) {
            if (v.isBlank()) return "[]";
            if (v.length() > 200000) v = v.substring(0, 200000);
            return v;
        }

        if ("self_upgrade_git_remote".equals(key)) {
            if (v.isBlank()) return "origin";
            if (v.length() > 120) v = v.substring(0, 120);
            return v;
        }

        if ("self_upgrade_git_branch".equals(key)) {
            if (v.length() > 160) v = v.substring(0, 160);
            return v;
        }

        if ("self_upgrade_build_command".equals(key) || "self_upgrade_restart_command".equals(key)) {
            if (v.length() > 1024) v = v.substring(0, 1024);
            return v;
        }

        if ("storage_backend".equals(key)) {
            String mode = v.toLowerCase(Locale.ROOT);
            if ("localfs".equals(mode)) return "local";
            if ("filesystem_remote".equals(mode)) return "sftp";
            if ("onedrive".equals(mode) || "onedrive_for_business".equals(mode) || "onedrive-for-business".equals(mode)) return "onedrive_business";
            if (!"local".equals(mode) && !"ftp".equals(mode) && !"ftps".equals(mode) && !"sftp".equals(mode)
                    && !"webdav".equals(mode) && !"s3_compatible".equals(mode)
                    && !"onedrive_business".equals(mode)) return "local";
            return mode;
        }

        if ("storage_root_folder".equals(key)) {
            return normalizeStorageRootFolder(v);
        }

        if ("storage_encryption_mode".equals(key)) {
            String mode = v.toLowerCase(Locale.ROOT);
            if (!"tenant_managed".equals(mode) && !"disabled".equals(mode)) return "disabled";
            return mode;
        }

        if ("storage_s3_sse_mode".equals(key)) {
            String mode = v.toLowerCase(Locale.ROOT);
            if (!"aes256".equals(mode) && !"aws_kms".equals(mode) && !"none".equals(mode)) return "none";
            return mode;
        }

        if ("storage_cache_size_ftp_mb".equals(key)
                || "storage_cache_size_ftps_mb".equals(key)
                || "storage_cache_size_sftp_mb".equals(key)
                || "storage_cache_size_webdav_mb".equals(key)
                || "storage_cache_size_s3_compatible_mb".equals(key)
                || "storage_cache_size_onedrive_business_mb".equals(key)) {
            int sizeMb = parseInt(v, 1024);
            if (sizeMb < 0 || sizeMb > 1048576) return "1024";
            return String.valueOf(sizeMb);
        }

        if ("storage_max_path_length".equals(key)) {
            int n = parseInt(v, 0);
            if (n < 0 || n > 8192) return "0";
            return String.valueOf(n);
        }

        if ("storage_max_filename_length".equals(key)) {
            int n = parseInt(v, 0);
            if (n < 0 || n > 1024) return "0";
            return String.valueOf(n);
        }

        if ("storage_connection_status".equals(key) || "clio_connection_status".equals(key) || "clio_auth_health_status".equals(key)) {
            String s = v.toLowerCase(Locale.ROOT);
            if (!"ok".equals(s) && !"failed".equals(s) && !"unknown".equals(s)) return "unknown";
            return s;
        }

        if ("email_connection_status".equals(key)) {
            String s = v.toLowerCase(Locale.ROOT);
            if (!"ok".equals(s) && !"failed".equals(s) && !"unknown".equals(s)) return "unknown";
            return s;
        }

        if ("email_smtp_port".equals(key)) {
            int port = parseInt(v, 587);
            if (port < 1 || port > 65535) return "587";
            return String.valueOf(port);
        }

        if ("email_connect_timeout_ms".equals(key)) {
            int ms = parseInt(v, 15000);
            if (ms < 1000 || ms > 120000) return "15000";
            return String.valueOf(ms);
        }

        if ("email_read_timeout_ms".equals(key)) {
            int ms = parseInt(v, 20000);
            if (ms < 1000 || ms > 180000) return "20000";
            return String.valueOf(ms);
        }

        if ("email_queue_poll_seconds".equals(key)) {
            int sec = parseInt(v, 5);
            if (sec < 1 || sec > 300) return "5";
            return String.valueOf(sec);
        }

        if ("email_queue_batch_size".equals(key)) {
            int n = parseInt(v, 10);
            if (n < 1 || n > 200) return "10";
            return String.valueOf(n);
        }

        if ("email_queue_max_attempts".equals(key)) {
            int n = parseInt(v, 8);
            if (n < 1 || n > 50) return "8";
            return String.valueOf(n);
        }

        if ("email_queue_backoff_base_ms".equals(key)) {
            int ms = parseInt(v, 2000);
            if (ms < 100 || ms > 600000) return "2000";
            return String.valueOf(ms);
        }

        if ("email_queue_backoff_max_ms".equals(key)) {
            int ms = parseInt(v, 300000);
            if (ms < 500 || ms > 3600000) return "300000";
            return String.valueOf(ms);
        }

        if (v.length() > 2048) v = v.substring(0, 2048);
        return v;
    }

    private void validateEnabledIntegrationSecrets(String tenantUuid, Map<String, String> cfg, List<String> failures) {
        String storageBackend = safe(cfg.get("storage_backend")).trim().toLowerCase(Locale.ROOT);
        if (!"local".equals(storageBackend)) {
            if (safe(cfg.get("storage_endpoint")).isBlank()
                    || safe(cfg.get("storage_access_key")).isBlank()
                    || safe(cfg.get("storage_secret")).isBlank()) {
                failures.add("tenant=" + safeFileToken(tenantUuid) + " storage backend enabled with invalid credentials");
            }
        }

        String appEncMode = safe(cfg.get("storage_encryption_mode")).trim().toLowerCase(Locale.ROOT);
        if ("tenant_managed".equals(appEncMode) && safe(cfg.get("storage_encryption_key")).isBlank()) {
            failures.add("tenant=" + safeFileToken(tenantUuid) + " tenant-managed encryption enabled without key");
        }

        if ("s3_compatible".equals(storageBackend)) {
            String sseMode = safe(cfg.get("storage_s3_sse_mode")).trim().toLowerCase(Locale.ROOT);
            if ("aws_kms".equals(sseMode) && safe(cfg.get("storage_s3_sse_kms_key_id")).isBlank()) {
                failures.add("tenant=" + safeFileToken(tenantUuid) + " s3 sse aws_kms enabled without key id");
            }
        }

        if ("onedrive_business".equals(storageBackend)) {
            String oneDriveMode = safe(cfg.get("storage_onedrive_auth_mode")).trim().toLowerCase(Locale.ROOT);
            if (!"public".equals(oneDriveMode) && !"private".equals(oneDriveMode) && !"app_credentials".equals(oneDriveMode)) {
                oneDriveMode = "app_credentials";
            }
            boolean modeReady = "app_credentials".equals(oneDriveMode)
                    || ("public".equals(oneDriveMode)
                    ? safe(cfg.get("storage_onedrive_oauth_callback_url")).startsWith("http")
                    : !safe(cfg.get("storage_onedrive_private_relay_url")).isBlank());
            if (!modeReady) {
                failures.add("tenant=" + safeFileToken(tenantUuid) + " onedrive_business enabled with invalid auth mode settings");
            }
        }

        boolean clioEnabled = "true".equalsIgnoreCase(safe(cfg.get("clio_enabled")));
        if (clioEnabled) {
            String clioMode = safe(cfg.get("clio_auth_mode")).trim().toLowerCase(Locale.ROOT);
            if (!"private".equals(clioMode)) clioMode = "public";
            boolean baseReady = safe(cfg.get("clio_base_url")).startsWith("http") && !safe(cfg.get("clio_client_id")).isBlank();
            boolean secretReady = !safe(cfg.get("clio_client_secret")).isBlank();
            boolean modeReady = "public".equals(clioMode)
                    ? safe(cfg.get("clio_oauth_callback_url")).startsWith("http")
                    : !safe(cfg.get("clio_private_relay_url")).isBlank();
            if (!(baseReady && secretReady && modeReady)) {
                failures.add("tenant=" + safeFileToken(tenantUuid) + " clio enabled with invalid credentials");
            }
        }

        boolean office365Enabled = "true".equalsIgnoreCase(safe(cfg.get("office365_contacts_sync_enabled")));
        if (office365Enabled) {
            String sourcesRaw = safe(cfg.get("office365_contacts_sync_sources_json")).trim();
            if (sourcesRaw.isBlank() || "[]".equals(sourcesRaw)) {
                failures.add("tenant=" + safeFileToken(tenantUuid) + " office365 contacts sync enabled without configured sources");
            }
        }

        boolean office365CalendarEnabled = "true".equalsIgnoreCase(safe(cfg.get("office365_calendar_sync_enabled")));
        if (office365CalendarEnabled) {
            String sourcesRaw = safe(cfg.get("office365_calendar_sync_sources_json")).trim();
            if (sourcesRaw.isBlank() || "[]".equals(sourcesRaw)) {
                failures.add("tenant=" + safeFileToken(tenantUuid) + " office365 calendar sync enabled without configured sources");
            }
        }

        String emailProvider = safe(cfg.get("email_provider")).trim().toLowerCase(Locale.ROOT);
        if ("smtp".equals(emailProvider)) {
            boolean authEnabled = "true".equalsIgnoreCase(safe(cfg.get("email_smtp_auth")));
            boolean hasHost = !safe(cfg.get("email_smtp_host")).isBlank();
            int smtpPort = parseInt(safe(cfg.get("email_smtp_port")), 587);
            boolean hasAuth = !authEnabled || (!safe(cfg.get("email_smtp_username")).isBlank() && !safe(cfg.get("email_smtp_password")).isBlank());
            if (!(hasHost && smtpPort >= 1 && smtpPort <= 65535 && hasAuth)) {
                failures.add("tenant=" + safeFileToken(tenantUuid) + " smtp email provider enabled with invalid credentials");
            }
        } else if ("microsoft_graph".equals(emailProvider)) {
            boolean ready = !safe(cfg.get("email_graph_tenant_id")).isBlank()
                    && !safe(cfg.get("email_graph_client_id")).isBlank()
                    && !safe(cfg.get("email_graph_client_secret")).isBlank()
                    && !safe(cfg.get("email_graph_sender_user")).isBlank();
            if (!ready) {
                failures.add("tenant=" + safeFileToken(tenantUuid) + " microsoft graph email provider enabled with invalid credentials");
            }
        }

        String twoFactorPolicy = safe(cfg.get("two_factor_policy")).trim().toLowerCase(Locale.ROOT);
        String twoFactorEngine = safe(cfg.get("two_factor_default_engine")).trim().toLowerCase(Locale.ROOT);
        if (!"required".equals(twoFactorPolicy)) return;
        if ("flowroute_sms".equals(twoFactorEngine)) {
            if (safe(cfg.get("flowroute_sms_access_key")).isBlank()
                    || safe(cfg.get("flowroute_sms_secret_key")).isBlank()
                    || safe(cfg.get("flowroute_sms_from_number")).isBlank()) {
                failures.add("tenant=" + safeFileToken(tenantUuid) + " required two-factor flowroute sms enabled with invalid credentials");
            }
        } else {
            boolean emailReady = "smtp".equals(emailProvider) || "microsoft_graph".equals(emailProvider);
            if (!emailReady) {
                failures.add("tenant=" + safeFileToken(tenantUuid) + " required two-factor email pin enabled without notification email provider");
            }
        }
    }

    private boolean truthy(String s) {
        String v = safe(s).trim().toLowerCase(Locale.ROOT);
        return "1".equals(v) || "true".equals(v) || "on".equals(v) || "yes".equals(v);
    }

    private int parseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(safe(raw).trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String normalizeStorageRootFolder(String raw) {
        String src = safe(raw).replace('\\', '/').trim();
        if (src.isBlank()) return "";

        String[] rawParts = src.split("/");
        ArrayList<String> cleanParts = new ArrayList<String>();
        for (int i = 0; i < rawParts.length; i++) {
            String part = safe(rawParts[i]).trim();
            if (part.isBlank() || ".".equals(part)) continue;
            if ("..".equals(part)) return "";
            part = part.replaceAll("[^A-Za-z0-9._-]", "_");
            if (part.length() > 128) part = part.substring(0, 128);
            if (!part.isBlank()) cleanParts.add(part);
        }
        if (cleanParts.isEmpty()) return "";
        String joined = String.join("/", cleanParts);
        if (joined.length() > 1024) joined = joined.substring(0, 1024);
        return joined;
    }

    private static Path settingsPath(String tenantUuid) {
        String tu = safeFileToken(tenantUuid);
        if (tu.isBlank()) return null;
        return Paths.get("data", "tenants", tu, "settings", "tenant_settings.json").toAbsolutePath();
    }

    private static Path secretsPath(String tenantUuid) {
        String tu = safeFileToken(tenantUuid);
        if (tu.isBlank()) return null;
        return Paths.get("data", "tenants", tu, "settings", "tenant_secrets.json").toAbsolutePath();
    }

    private void writeSecrets(String tenantUuid, Map<String, String> secrets) throws Exception {
        Path p = secretsPath(tenantUuid);
        if (p == null) return;
        Files.createDirectories(p.getParent());

        String plaintext = toSimpleJsonObject(secrets);
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);

        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(deriveTenantKey(tenantUuid), "AES"), new GCMParameterSpec(128, iv));
        byte[] encrypted = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

        LinkedHashMap<String, String> envelope = new LinkedHashMap<String, String>();
        envelope.put("alg", "AES/GCM/NoPadding");
        envelope.put("iv", Base64.getEncoder().encodeToString(iv));
        envelope.put("ciphertext", Base64.getEncoder().encodeToString(encrypted));

        Files.writeString(
                p,
                toSimpleJsonObject(envelope),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        );
    }

    private LinkedHashMap<String, String> readSecrets(String tenantUuid) {
        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
        try {
            Path p = secretsPath(tenantUuid);
            if (p == null || !Files.exists(p)) return out;
            String raw = Files.readString(p, StandardCharsets.UTF_8);
            Map<String, String> env = parseSimpleJsonObject(raw);

            byte[] iv = Base64.getDecoder().decode(safe(env.get("iv")));
            byte[] ciphertext = Base64.getDecoder().decode(safe(env.get("ciphertext")));

            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(deriveTenantKey(tenantUuid), "AES"), new GCMParameterSpec(128, iv));
            byte[] plaintext = c.doFinal(ciphertext);
            Map<String, String> parsed = parseSimpleJsonObject(new String(plaintext, StandardCharsets.UTF_8));
            for (Map.Entry<String, String> e : sanitizeSettings(parsed).entrySet()) {
                if (isSecretKey(e.getKey())) out.put(e.getKey(), safe(e.getValue()));
            }
        } catch (Exception ignored) {
            return new LinkedHashMap<String, String>();
        }
        return out;
    }

    private byte[] deriveTenantKey(String tenantUuid) throws Exception {
        byte[] seed = readSecuritySeedMaterial();
        MessageDigest d = MessageDigest.getInstance("SHA-256");
        d.update(seed);
        d.update((byte) ':');
        d.update(safe(tenantUuid).getBytes(StandardCharsets.UTF_8));
        return d.digest();
    }

    private byte[] readSecuritySeedMaterial() throws Exception {
        Path sec = Paths.get("data", "sec").toAbsolutePath();
        Path pepper = sec.resolve("random_pepper.bin");
        if (!Files.exists(pepper)) {
            throw new IllegalStateException("Missing data/sec/random_pepper.bin");
        }
        return Files.readAllBytes(pepper);
    }

    private static String safeFileToken(String s) {
        String t = safe(s).trim();
        if (t.isBlank()) return "";
        return t.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    // -----------------------------
    // Small JSON helpers (string map only)
    // -----------------------------
    private static LinkedHashMap<String, String> parseSimpleJsonObject(String raw) {
        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
        String s = safe(raw).trim();
        if (s.isBlank()) return out;

        int[] i = new int[] {0};
        skipWs(s, i);
        if (!consume(s, i, '{')) return out;

        skipWs(s, i);
        if (peek(s, i) == '}') {
            i[0]++;
            return out;
        }

        while (i[0] < s.length()) {
            skipWs(s, i);
            String key = parseJsonString(s, i);
            if (key == null) return out;

            skipWs(s, i);
            if (!consume(s, i, ':')) return out;
            skipWs(s, i);

            String value;
            if (peek(s, i) == '"') {
                value = parseJsonString(s, i);
                if (value == null) return out;
            } else {
                value = parseJsonBareValue(s, i);
            }
            out.put(safe(key), safe(value));

            skipWs(s, i);
            char ch = peek(s, i);
            if (ch == ',') {
                i[0]++;
                continue;
            }
            if (ch == '}') {
                i[0]++;
                break;
            }
            break;
        }
        return out;
    }

    private static String toSimpleJsonObject(Map<String, String> fields) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        if (fields != null) {
            int n = 0;
            for (Map.Entry<String, String> e : fields.entrySet()) {
                if (e == null) continue;
                String k = safe(e.getKey());
                if (k.isBlank()) continue;
                String v = safe(e.getValue());

                if (n > 0) sb.append(",");
                sb.append("\"").append(jsonEscape(k)).append("\":");
                sb.append("\"").append(jsonEscape(v)).append("\"");
                n++;
            }
        }
        sb.append("}");
        return sb.toString();
    }

    private static char peek(String s, int[] i) {
        if (s == null || i == null || i[0] < 0 || i[0] >= s.length()) return '\0';
        return s.charAt(i[0]);
    }

    private static void skipWs(String s, int[] i) {
        while (i[0] < s.length()) {
            char ch = s.charAt(i[0]);
            if (ch == ' ' || ch == '\n' || ch == '\r' || ch == '\t') i[0]++;
            else break;
        }
    }

    private static boolean consume(String s, int[] i, char expected) {
        if (peek(s, i) != expected) return false;
        i[0]++;
        return true;
    }

    private static String parseJsonString(String s, int[] i) {
        if (!consume(s, i, '"')) return null;
        StringBuilder sb = new StringBuilder();
        while (i[0] < s.length()) {
            char ch = s.charAt(i[0]++);
            if (ch == '"') return sb.toString();
            if (ch != '\\') {
                sb.append(ch);
                continue;
            }

            if (i[0] >= s.length()) return null;
            char esc = s.charAt(i[0]++);
            switch (esc) {
                case '"': sb.append('"'); break;
                case '\\': sb.append('\\'); break;
                case '/': sb.append('/'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'n': sb.append('\n'); break;
                case 'r': sb.append('\r'); break;
                case 't': sb.append('\t'); break;
                case 'u':
                    if (i[0] + 3 >= s.length()) return null;
                    String hex = s.substring(i[0], i[0] + 4);
                    i[0] += 4;
                    try {
                        int code = Integer.parseInt(hex, 16);
                        sb.append((char) code);
                    } catch (Exception ex) {
                        return null;
                    }
                    break;
                default:
                    sb.append(esc);
            }
        }
        return null;
    }

    private static String parseJsonBareValue(String s, int[] i) {
        int start = i[0];
        while (i[0] < s.length()) {
            char ch = s.charAt(i[0]);
            if (ch == ',' || ch == '}') break;
            i[0]++;
        }
        String raw = s.substring(start, i[0]).trim();
        if ("null".equalsIgnoreCase(raw)) return "";
        return raw;
    }

    private static String jsonEscape(String s) {
        String v = safe(s);
        StringBuilder out = new StringBuilder(v.length() + 16);
        for (int i = 0; i < v.length(); i++) {
            char ch = v.charAt(i);
            switch (ch) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (ch < 0x20) {
                        String hex = Integer.toHexString(ch);
                        out.append("\\u");
                        for (int p = hex.length(); p < 4; p++) out.append('0');
                        out.append(hex);
                    } else {
                        out.append(ch);
                    }
            }
        }
        return out.toString();
    }
}
