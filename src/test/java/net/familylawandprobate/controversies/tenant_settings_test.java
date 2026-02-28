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
            store.write(tenantUuid, in);

            Path settingsPath = Paths.get("data", "tenants", tenantUuid, "settings", "tenant_settings.json").toAbsolutePath();
            Path secretsPath = Paths.get("data", "tenants", tenantUuid, "settings", "tenant_secrets.json").toAbsolutePath();

            String settingsJson = Files.readString(settingsPath, StandardCharsets.UTF_8);
            String secretsJson = Files.readString(secretsPath, StandardCharsets.UTF_8);

            assertFalse(settingsJson.contains("storage-secret"));
            assertFalse(settingsJson.contains("clio-secret"));
            assertTrue(secretsJson.contains("ciphertext"));

            Map<String, String> roundtrip = store.read(tenantUuid);
            assertEquals("storage-secret", roundtrip.get("storage_secret"));
            assertEquals("tenant_managed", roundtrip.get("storage_encryption_mode"));
            assertEquals("encryption-secret", roundtrip.get("storage_encryption_key"));
            assertEquals("aws_kms", roundtrip.get("storage_s3_sse_mode"));
            assertEquals("kms-key-123", roundtrip.get("storage_s3_sse_kms_key_id"));
            assertEquals("clio-secret", roundtrip.get("clio_client_secret"));
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
