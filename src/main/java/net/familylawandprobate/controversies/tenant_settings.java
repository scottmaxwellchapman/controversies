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
            "storage_access_key",
            "storage_secret",
            "storage_encryption_mode",
            "storage_encryption_key",
            "storage_s3_sse_mode",
            "storage_s3_sse_kms_key_id",
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
            "feature_advanced_assembly",
            "feature_async_sync",
            "theme_mode_default",
            "theme_use_location",
            "theme_latitude",
            "theme_longitude",
            "theme_light_start_hour",
            "theme_dark_start_hour",
            "theme_text_size_default",
            "secret_rotation_storage_at",
            "secret_rotation_clio_at",
            "storage_connection_status",
            "storage_connection_checked_at",
            "clio_connection_status",
            "clio_connection_checked_at",
            "clio_auth_health_status",
            "clio_auth_health_checked_at"
    };

    private static final String[] SECRET_KEYS = new String[] {
            "storage_access_key",
            "storage_secret",
            "storage_encryption_key",
            "clio_client_secret",
            "clio_access_token",
            "clio_refresh_token"
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
        return Instant.now().toString();
    }

    private LinkedHashMap<String, String> defaults() {
        LinkedHashMap<String, String> d = new LinkedHashMap<String, String>();
        d.put("storage_backend", "local");
        d.put("storage_endpoint", "");
        d.put("storage_access_key", "");
        d.put("storage_secret", "");
        d.put("storage_encryption_mode", "disabled");
        d.put("storage_encryption_key", "");
        d.put("storage_s3_sse_mode", "none");
        d.put("storage_s3_sse_kms_key_id", "");
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
        d.put("feature_advanced_assembly", "false");
        d.put("feature_async_sync", "false");
        d.put("theme_mode_default", "auto");
        d.put("theme_use_location", "true");
        d.put("theme_latitude", "");
        d.put("theme_longitude", "");
        d.put("theme_light_start_hour", "7");
        d.put("theme_dark_start_hour", "19");
        d.put("theme_text_size_default", "md");
        d.put("secret_rotation_storage_at", "");
        d.put("secret_rotation_clio_at", "");
        d.put("storage_connection_status", "unknown");
        d.put("storage_connection_checked_at", "");
        d.put("clio_connection_status", "unknown");
        d.put("clio_connection_checked_at", "");
        d.put("clio_auth_health_status", "unknown");
        d.put("clio_auth_health_checked_at", "");
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

        if ("feature_advanced_assembly".equals(key) || "feature_async_sync".equals(key)
                || "clio_enabled".equals(key) || "theme_use_location".equals(key)) {
            return truthy(v) ? "true" : "false";
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

        if ("clio_storage_mode".equals(key)) {
            String mode = v.toLowerCase(Locale.ROOT);
            if (!"enabled".equals(mode) && !"disabled".equals(mode)) return "disabled";
            return mode;
        }

        if ("storage_backend".equals(key)) {
            String mode = v.toLowerCase(Locale.ROOT);
            if ("localfs".equals(mode)) return "local";
            if ("filesystem_remote".equals(mode)) return "sftp";
            if (!"local".equals(mode) && !"ftp".equals(mode) && !"ftps".equals(mode) && !"sftp".equals(mode) && !"s3_compatible".equals(mode)) return "local";
            return mode;
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

        if ("storage_connection_status".equals(key) || "clio_connection_status".equals(key) || "clio_auth_health_status".equals(key)) {
            String s = v.toLowerCase(Locale.ROOT);
            if (!"ok".equals(s) && !"failed".equals(s) && !"unknown".equals(s)) return "unknown";
            return s;
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
