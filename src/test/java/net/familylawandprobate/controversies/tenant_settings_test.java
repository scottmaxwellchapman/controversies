package net.familylawandprobate.controversies;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class tenant_settings_test {

    @Test
    void sanitize_supports_clio_auth_modes_and_health_fields() {
        tenant_settings store = tenant_settings.defaultStore();

        LinkedHashMap<String, String> in = new LinkedHashMap<String, String>();
        in.put("clio_auth_mode", "PRIVATE");
        in.put("clio_auth_health_status", "ok");
        in.put("clio_oauth_callback_url", "https://example.test/callback");
        in.put("clio_private_relay_url", "https://relay.example.test/exchange");

        Map<String, String> out = store.sanitizeSettings(in);

        assertEquals("private", out.get("clio_auth_mode"));
        assertEquals("ok", out.get("clio_auth_health_status"));
        assertEquals("https://example.test/callback", out.get("clio_oauth_callback_url"));
        assertEquals("https://relay.example.test/exchange", out.get("clio_private_relay_url"));
    }

    @Test
    void sanitize_supports_clio_contact_sync_status_fields() {
        tenant_settings store = tenant_settings.defaultStore();

        LinkedHashMap<String, String> in = new LinkedHashMap<String, String>();
        in.put("clio_contacts_last_sync_status", "FAILED");
        in.put("clio_contacts_last_sync_at", "2026-03-04T00:00:00Z");

        Map<String, String> out = store.sanitizeSettings(in);
        assertEquals("failed", out.get("clio_contacts_last_sync_status"));
        assertEquals("2026-03-04T00:00:00Z", out.get("clio_contacts_last_sync_at"));
    }

    @Test
    void sanitize_defaults_unknown_clio_auth_mode_and_health_status() {
        tenant_settings store = tenant_settings.defaultStore();

        LinkedHashMap<String, String> in = new LinkedHashMap<String, String>();
        in.put("clio_auth_mode", "relay");
        in.put("clio_auth_health_status", "degraded");

        Map<String, String> out = store.sanitizeSettings(in);

        assertEquals("public", out.get("clio_auth_mode"));
        assertEquals("unknown", out.get("clio_auth_health_status"));
    }

    @Test
    void sanitize_supports_clio_sync_interval_and_document_sync_timestamp() {
        tenant_settings store = tenant_settings.defaultStore();

        LinkedHashMap<String, String> in = new LinkedHashMap<String, String>();
        in.put("clio_matters_sync_interval_minutes", "30");
        in.put("clio_documents_last_sync_at", "2026-03-01T11:22:33Z");

        Map<String, String> out = store.sanitizeSettings(in);

        assertEquals("30", out.get("clio_matters_sync_interval_minutes"));
        assertEquals("2026-03-01T11:22:33Z", out.get("clio_documents_last_sync_at"));
    }

    @Test
    void sanitize_supports_clio_sync_interval_minutes() {
        tenant_settings store = tenant_settings.defaultStore();

        LinkedHashMap<String, String> in = new LinkedHashMap<String, String>();
        in.put("clio_matters_sync_interval_minutes", "45");

        Map<String, String> out = store.sanitizeSettings(in);
        assertEquals("45", out.get("clio_matters_sync_interval_minutes"));
    }

    @Test
    void sanitize_defaults_invalid_clio_sync_interval_minutes() {
        tenant_settings store = tenant_settings.defaultStore();

        LinkedHashMap<String, String> in = new LinkedHashMap<String, String>();
        in.put("clio_matters_sync_interval_minutes", "0");

        Map<String, String> out = store.sanitizeSettings(in);
        assertEquals("15", out.get("clio_matters_sync_interval_minutes"));
    }


    @Test
    void sanitize_supports_storage_encryption_and_s3_sse_modes() {
        tenant_settings store = tenant_settings.defaultStore();

        LinkedHashMap<String, String> in = new LinkedHashMap<String, String>();
        in.put("storage_backend", "s3_compatible");
        in.put("storage_encryption_mode", "TENANT_MANAGED");
        in.put("storage_s3_sse_mode", "AWS_KMS");

        Map<String, String> out = store.sanitizeSettings(in);

        assertEquals("s3_compatible", out.get("storage_backend"));
        assertEquals("tenant_managed", out.get("storage_encryption_mode"));
        assertEquals("aws_kms", out.get("storage_s3_sse_mode"));
    }

    @Test
    void sanitize_supports_storage_cache_sizes_for_external_backends() {
        tenant_settings store = tenant_settings.defaultStore();

        LinkedHashMap<String, String> in = new LinkedHashMap<String, String>();
        in.put("storage_cache_size_ftp_mb", "256");
        in.put("storage_cache_size_ftps_mb", "512");
        in.put("storage_cache_size_sftp_mb", "768");
        in.put("storage_cache_size_s3_compatible_mb", "1024");

        Map<String, String> out = store.sanitizeSettings(in);

        assertEquals("256", out.get("storage_cache_size_ftp_mb"));
        assertEquals("512", out.get("storage_cache_size_ftps_mb"));
        assertEquals("768", out.get("storage_cache_size_sftp_mb"));
        assertEquals("1024", out.get("storage_cache_size_s3_compatible_mb"));
    }

    @Test
    void sanitize_defaults_invalid_storage_cache_sizes() {
        tenant_settings store = tenant_settings.defaultStore();

        LinkedHashMap<String, String> in = new LinkedHashMap<String, String>();
        in.put("storage_cache_size_ftp_mb", "-1");
        in.put("storage_cache_size_ftps_mb", "9999999");
        in.put("storage_cache_size_sftp_mb", "not-a-number");
        in.put("storage_cache_size_s3_compatible_mb", "");

        Map<String, String> out = store.sanitizeSettings(in);

        assertEquals("1024", out.get("storage_cache_size_ftp_mb"));
        assertEquals("1024", out.get("storage_cache_size_ftps_mb"));
        assertEquals("1024", out.get("storage_cache_size_sftp_mb"));
        assertEquals("1024", out.get("storage_cache_size_s3_compatible_mb"));
    }

    @Test
    void sanitize_supports_theme_defaults_and_location_fields() {
        tenant_settings store = tenant_settings.defaultStore();

        LinkedHashMap<String, String> in = new LinkedHashMap<String, String>();
        in.put("theme_mode_default", "DaRk");
        in.put("theme_use_location", "yes");
        in.put("theme_latitude", "35.2271");
        in.put("theme_longitude", "-80.8431");
        in.put("theme_light_start_hour", "6");
        in.put("theme_dark_start_hour", "20");
        in.put("theme_text_size_default", "LG");

        Map<String, String> out = store.sanitizeSettings(in);

        assertEquals("dark", out.get("theme_mode_default"));
        assertEquals("true", out.get("theme_use_location"));
        assertEquals("35.2271", out.get("theme_latitude"));
        assertEquals("-80.8431", out.get("theme_longitude"));
        assertEquals("6", out.get("theme_light_start_hour"));
        assertEquals("20", out.get("theme_dark_start_hour"));
        assertEquals("lg", out.get("theme_text_size_default"));
    }

    @Test
    void sanitize_defaults_invalid_theme_values() {
        tenant_settings store = tenant_settings.defaultStore();

        LinkedHashMap<String, String> in = new LinkedHashMap<String, String>();
        in.put("theme_mode_default", "night");
        in.put("theme_use_location", "off");
        in.put("theme_latitude", "200");
        in.put("theme_longitude", "-300");
        in.put("theme_light_start_hour", "99");
        in.put("theme_dark_start_hour", "-1");
        in.put("theme_text_size_default", "huge");

        Map<String, String> out = store.sanitizeSettings(in);

        assertEquals("auto", out.get("theme_mode_default"));
        assertEquals("false", out.get("theme_use_location"));
        assertEquals("", out.get("theme_latitude"));
        assertEquals("", out.get("theme_longitude"));
        assertEquals("7", out.get("theme_light_start_hour"));
        assertEquals("19", out.get("theme_dark_start_hour"));
        assertEquals("md", out.get("theme_text_size_default"));
    }

    @Test
    void sanitize_supports_password_policy_fields() {
        tenant_settings store = tenant_settings.defaultStore();

        LinkedHashMap<String, String> in = new LinkedHashMap<String, String>();
        in.put("password_policy_enabled", "yes");
        in.put("password_policy_min_length", "18");
        in.put("password_policy_require_uppercase", "true");
        in.put("password_policy_require_lowercase", "on");
        in.put("password_policy_require_number", "1");
        in.put("password_policy_require_symbol", "false");

        Map<String, String> out = store.sanitizeSettings(in);

        assertEquals("true", out.get("password_policy_enabled"));
        assertEquals("18", out.get("password_policy_min_length"));
        assertEquals("true", out.get("password_policy_require_uppercase"));
        assertEquals("true", out.get("password_policy_require_lowercase"));
        assertEquals("true", out.get("password_policy_require_number"));
        assertEquals("false", out.get("password_policy_require_symbol"));
    }

    @Test
    void sanitize_supports_two_factor_and_flowroute_fields() {
        tenant_settings store = tenant_settings.defaultStore();

        LinkedHashMap<String, String> in = new LinkedHashMap<String, String>();
        in.put("two_factor_policy", "REQUIRED");
        in.put("two_factor_default_engine", "FLOWROUTE_SMS");
        in.put("flowroute_sms_from_number", "+1 (206) 555-0100");
        in.put("flowroute_sms_api_base_url", "https://api.flowroute.com/v2.2/messages");

        Map<String, String> out = store.sanitizeSettings(in);

        assertEquals("required", out.get("two_factor_policy"));
        assertEquals("flowroute_sms", out.get("two_factor_default_engine"));
        assertEquals("+12065550100", out.get("flowroute_sms_from_number"));
        assertEquals("https://api.flowroute.com/v2.2/messages", out.get("flowroute_sms_api_base_url"));
    }

    @Test
    void sanitize_defaults_invalid_two_factor_values() {
        tenant_settings store = tenant_settings.defaultStore();

        LinkedHashMap<String, String> in = new LinkedHashMap<String, String>();
        in.put("two_factor_policy", "always");
        in.put("two_factor_default_engine", "totp");
        in.put("flowroute_sms_api_base_url", "ftp://example.test");

        Map<String, String> out = store.sanitizeSettings(in);

        assertEquals("off", out.get("two_factor_policy"));
        assertEquals("email_pin", out.get("two_factor_default_engine"));
        assertEquals("https://api.flowroute.com/v2.2/messages", out.get("flowroute_sms_api_base_url"));
    }

    @Test
    void sanitize_supports_email_provider_and_tunable_settings() {
        tenant_settings store = tenant_settings.defaultStore();

        LinkedHashMap<String, String> in = new LinkedHashMap<String, String>();
        in.put("email_provider", "SMTP");
        in.put("email_smtp_auth", "yes");
        in.put("email_smtp_starttls", "true");
        in.put("email_smtp_ssl", "false");
        in.put("email_smtp_port", "2525");
        in.put("email_connect_timeout_ms", "12000");
        in.put("email_read_timeout_ms", "21000");
        in.put("email_queue_poll_seconds", "6");
        in.put("email_queue_batch_size", "15");
        in.put("email_queue_max_attempts", "12");
        in.put("email_queue_backoff_base_ms", "4000");
        in.put("email_queue_backoff_max_ms", "120000");
        in.put("email_connection_status", "ok");

        Map<String, String> out = store.sanitizeSettings(in);

        assertEquals("smtp", out.get("email_provider"));
        assertEquals("true", out.get("email_smtp_auth"));
        assertEquals("true", out.get("email_smtp_starttls"));
        assertEquals("false", out.get("email_smtp_ssl"));
        assertEquals("2525", out.get("email_smtp_port"));
        assertEquals("12000", out.get("email_connect_timeout_ms"));
        assertEquals("21000", out.get("email_read_timeout_ms"));
        assertEquals("6", out.get("email_queue_poll_seconds"));
        assertEquals("15", out.get("email_queue_batch_size"));
        assertEquals("12", out.get("email_queue_max_attempts"));
        assertEquals("4000", out.get("email_queue_backoff_base_ms"));
        assertEquals("120000", out.get("email_queue_backoff_max_ms"));
        assertEquals("ok", out.get("email_connection_status"));
    }

    @Test
    void writes_secrets_in_encrypted_blob_separate_from_general_settings() throws Exception {
        Path pepper = Paths.get("data", "sec", "random_pepper.bin").toAbsolutePath();
        Files.createDirectories(pepper.getParent());
        if (!Files.exists(pepper)) {
            Files.writeString(pepper, "test-pepper-material", StandardCharsets.UTF_8);
        }

        tenant_settings store = tenant_settings.defaultStore();
        String tenantUuid = "test-" + UUID.randomUUID();
        try {
            LinkedHashMap<String, String> in = new LinkedHashMap<String, String>();
            in.put("storage_backend", "s3_compatible");
            in.put("storage_endpoint", "smb://server/share");
            in.put("storage_access_key", "AKIA_TEST");
            in.put("storage_secret", "storage-secret");
            in.put("storage_encryption_mode", "tenant_managed");
            in.put("storage_encryption_key", "encryption-secret");
            in.put("storage_s3_sse_mode", "aws_kms");
            in.put("storage_s3_sse_kms_key_id", "kms-key-123");
            in.put("clio_client_secret", "clio-secret");
            in.put("email_smtp_password", "smtp-secret");
            in.put("email_graph_client_secret", "graph-secret");
            store.write(tenantUuid, in);

            Path settingsPath = Paths.get("data", "tenants", tenantUuid, "settings", "tenant_settings.json").toAbsolutePath();
            Path secretsPath = Paths.get("data", "tenants", tenantUuid, "settings", "tenant_secrets.json").toAbsolutePath();

            String settingsJson = Files.readString(settingsPath, StandardCharsets.UTF_8);
            String secretsJson = Files.readString(secretsPath, StandardCharsets.UTF_8);

            assertFalse(settingsJson.contains("storage-secret"));
            assertFalse(settingsJson.contains("clio-secret"));
            assertFalse(settingsJson.contains("smtp-secret"));
            assertFalse(settingsJson.contains("graph-secret"));
            assertTrue(secretsJson.contains("ciphertext"));

            Map<String, String> roundtrip = store.read(tenantUuid);
            assertEquals("storage-secret", roundtrip.get("storage_secret"));
            assertEquals("tenant_managed", roundtrip.get("storage_encryption_mode"));
            assertEquals("encryption-secret", roundtrip.get("storage_encryption_key"));
            assertEquals("aws_kms", roundtrip.get("storage_s3_sse_mode"));
            assertEquals("kms-key-123", roundtrip.get("storage_s3_sse_kms_key_id"));
            assertEquals("clio-secret", roundtrip.get("clio_client_secret"));
            assertEquals("smtp-secret", roundtrip.get("email_smtp_password"));
            assertEquals("graph-secret", roundtrip.get("email_graph_client_secret"));
        } finally {
            deleteTenantDirQuiet(tenantUuid);
        }
    }

    @Test
    void startup_self_check_fails_only_for_enabled_integrations_with_invalid_secrets() throws Exception {
        Path pepper = Paths.get("data", "sec", "random_pepper.bin").toAbsolutePath();
        Files.createDirectories(pepper.getParent());
        if (!Files.exists(pepper)) {
            Files.writeString(pepper, "test-pepper-material", StandardCharsets.UTF_8);
        }

        tenant_settings store = tenant_settings.defaultStore();

        String disabledTenant = "test-disabled-" + UUID.randomUUID();
        String enabledTenant = "test-enabled-" + UUID.randomUUID();
        try {
            LinkedHashMap<String, String> disabledCfg = new LinkedHashMap<String, String>();
            disabledCfg.put("clio_enabled", "false");
            disabledCfg.put("clio_base_url", "");
            disabledCfg.put("clio_client_id", "");
            store.write(disabledTenant, disabledCfg);

            LinkedHashMap<String, String> enabledCfg = new LinkedHashMap<String, String>();
            enabledCfg.put("clio_enabled", "true");
            enabledCfg.put("clio_auth_mode", "public");
            enabledCfg.put("clio_base_url", "https://app.clio.com");
            enabledCfg.put("clio_client_id", "client-id");
            // missing clio_client_secret and callback on purpose
            store.write(enabledTenant, enabledCfg);

            tenant_settings.StartupSelfCheckResult result = store.startupSelfCheckAllTenants();
            assertFalse(result.ok);
            String joined = String.join("\n", result.failures);
            assertTrue(joined.contains(enabledTenant));
            assertFalse(joined.contains(disabledTenant));
        } finally {
            deleteTenantDirQuiet(disabledTenant);
            deleteTenantDirQuiet(enabledTenant);
        }
    }

    private static void deleteTenantDirQuiet(String tenantUuid) {
        if (tenantUuid == null || tenantUuid.isBlank()) return;
        Path root = Paths.get("data", "tenants", tenantUuid).toAbsolutePath();
        if (!Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {
        }
    }
}
