package net.familylawandprobate.controversies;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
