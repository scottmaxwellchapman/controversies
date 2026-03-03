package net.familylawandprobate.controversies;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * task_fields
 *
 * Stores per-task key/value metadata in:
 *   data/tenants/{tenantUuid}/tasks/tasks/{taskUuid}/custom-fields.json
 */
public final class task_fields {

    public static task_fields defaultStore() {
        return new task_fields();
    }

    public Map<String, String> read(String tenantUuid, String taskUuid) {
        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
        try {
            Path p = fieldsPath(tenantUuid, taskUuid);
            if (p == null || !Files.exists(p)) return out;

            String raw = Files.readString(p, StandardCharsets.UTF_8);
            out.putAll(parseSimpleJsonObject(raw));
            return out;
        } catch (Exception ignored) {
            return out;
        }
    }

    public void write(String tenantUuid, String taskUuid, Map<String, String> fields) throws Exception {
        Path p = fieldsPath(tenantUuid, taskUuid);
        if (p == null) throw new IllegalArgumentException("tenantUuid and taskUuid are required");

        LinkedHashMap<String, String> clean = sanitizeFields(fields);
        Files.createDirectories(p.getParent());
        Files.writeString(
                p,
                toSimpleJsonObject(clean),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        );
    }

    public LinkedHashMap<String, String> sanitizeFields(Map<String, String> fields) {
        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
        if (fields == null || fields.isEmpty()) return out;

        ArrayList<Map.Entry<String, String>> rows = new ArrayList<Map.Entry<String, String>>(fields.entrySet());
        rows.sort(new Comparator<Map.Entry<String, String>>() {
            public int compare(Map.Entry<String, String> a, Map.Entry<String, String> b) {
                String ak = normalizeKey(a == null ? "" : a.getKey());
                String bk = normalizeKey(b == null ? "" : b.getKey());
                return ak.compareToIgnoreCase(bk);
            }
        });

        for (int i = 0; i < rows.size(); i++) {
            Map.Entry<String, String> e = rows.get(i);
            if (e == null) continue;

            String key = normalizeKey(e.getKey());
            if (key.isBlank()) continue;
            String value = safe(e.getValue());
            out.put(key, value);
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

    private static Path fieldsPath(String tenantUuid, String taskUuid) {
        String tu = safeFileToken(tenantUuid);
        String uu = safeFileToken(taskUuid);
        if (tu.isBlank() || uu.isBlank()) return null;
        return Paths.get("data", "tenants", tu, "tasks", "tasks", uu, "custom-fields.json").toAbsolutePath();
    }

    private static String safeFileToken(String s) {
        String t = safe(s).trim();
        if (t.isBlank()) return "";
        return t.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

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
        StringBuilder sb = new StringBuilder(v.length() + 8);
        for (int i = 0; i < v.length(); i++) {
            char ch = v.charAt(i);
            switch (ch) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (ch < 0x20) {
                        String hex = Integer.toHexString(ch);
                        sb.append("\\u");
                        for (int p = hex.length(); p < 4; p++) sb.append('0');
                        sb.append(hex);
                    } else {
                        sb.append(ch);
                    }
            }
        }
        return sb.toString();
    }
}
