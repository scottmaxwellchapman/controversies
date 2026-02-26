package net.familylawandprobate.controversies;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * tenant_settings
 *
 * Stores tenant settings in:
 *   data/tenants/{tenantUuid}/settings/tenant_settings.json
 */
public final class tenant_settings {

    private static final String[] ALLOWED_KEYS = new String[] {
            "storage_backend",
            "storage_endpoint",
            "storage_access_key",
            "storage_secret",
            "clio_base_url",
            "clio_client_id",
            "clio_client_secret",
            "feature_advanced_assembly",
            "feature_async_sync",
            "secret_rotation_storage_at",
            "secret_rotation_clio_at",
            "storage_connection_status",
            "storage_connection_checked_at",
            "clio_connection_status",
            "clio_connection_checked_at"
    };

    public static tenant_settings defaultStore() {
        return new tenant_settings();
    }

    public LinkedHashMap<String, String> read(String tenantUuid) {
        LinkedHashMap<String, String> out = defaults();
        try {
            Path p = settingsPath(tenantUuid);
            if (p == null || !Files.exists(p)) return out;

            String raw = Files.readString(p, StandardCharsets.UTF_8);
            LinkedHashMap<String, String> parsed = parseSimpleJsonObject(raw);
            for (Map.Entry<String, String> e : sanitizeSettings(parsed).entrySet()) {
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

        Files.createDirectories(p.getParent());
        Files.writeString(
                p,
                toSimpleJsonObject(merged),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        );
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
        d.put("storage_backend", "localfs");
        d.put("storage_endpoint", "");
        d.put("storage_access_key", "");
        d.put("storage_secret", "");
        d.put("clio_base_url", "");
        d.put("clio_client_id", "");
        d.put("clio_client_secret", "");
        d.put("feature_advanced_assembly", "false");
        d.put("feature_async_sync", "false");
        d.put("secret_rotation_storage_at", "");
        d.put("secret_rotation_clio_at", "");
        d.put("storage_connection_status", "unknown");
        d.put("storage_connection_checked_at", "");
        d.put("clio_connection_status", "unknown");
        d.put("clio_connection_checked_at", "");
        return d;
    }

    private boolean isAllowed(String key) {
        for (int i = 0; i < ALLOWED_KEYS.length; i++) {
            if (ALLOWED_KEYS[i].equals(key)) return true;
        }
        return false;
    }

    private String normalizeValue(String key, String value) {
        String v = safe(value).trim();

        if ("feature_advanced_assembly".equals(key) || "feature_async_sync".equals(key)) {
            return truthy(v) ? "true" : "false";
        }

        if ("storage_connection_status".equals(key) || "clio_connection_status".equals(key)) {
            String s = v.toLowerCase(Locale.ROOT);
            if (!"ok".equals(s) && !"failed".equals(s) && !"unknown".equals(s)) return "unknown";
            return s;
        }

        if (v.length() > 2048) v = v.substring(0, 2048);
        return v;
    }

    private boolean truthy(String s) {
        String v = safe(s).trim().toLowerCase(Locale.ROOT);
        return "1".equals(v) || "true".equals(v) || "on".equals(v) || "yes".equals(v);
    }

    private static Path settingsPath(String tenantUuid) {
        String tu = safeFileToken(tenantUuid);
        if (tu.isBlank()) return null;
        return Paths.get("data", "tenants", tu, "settings", "tenant_settings.json").toAbsolutePath();
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
