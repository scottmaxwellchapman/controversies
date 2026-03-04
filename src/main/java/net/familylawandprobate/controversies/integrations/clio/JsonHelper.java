package net.familylawandprobate.controversies.integrations.clio;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class JsonHelper {
    private JsonHelper() {}

    static String stringValue(String json, String key) {
        String pattern = "\"" + Pattern.quote(safe(key)) + "\"\\s*:\\s*\"([^\"]*)\"";
        Matcher m = Pattern.compile(pattern).matcher(safe(json));
        if (!m.find()) return "";
        return unescape(m.group(1));
    }

    static long longValue(String json, String key) {
        String pattern = "\"" + Pattern.quote(safe(key)) + "\"\\s*:\\s*([0-9]+)";
        Matcher m = Pattern.compile(pattern).matcher(safe(json));
        if (!m.find()) return 0L;
        try {
            return Long.parseLong(safe(m.group(1)).trim());
        } catch (Exception ignored) {
            return 0L;
        }
    }

    static boolean boolValue(String json, String key) {
        String pattern = "\"" + Pattern.quote(safe(key)) + "\"\\s*:\\s*(true|false)";
        Matcher m = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(safe(json));
        if (!m.find()) return false;
        return "true".equalsIgnoreCase(safe(m.group(1)));
    }

    static String objectValue(String json, String key) {
        String s = safe(json);
        String marker = "\"" + safe(key) + "\"";
        int i = s.indexOf(marker);
        if (i < 0) return "";
        int colon = s.indexOf(':', i + marker.length());
        if (colon < 0) return "";
        int start = -1;
        for (int p = colon + 1; p < s.length(); p++) {
            char ch = s.charAt(p);
            if (Character.isWhitespace(ch)) continue;
            if (ch == '{') {
                start = p;
                break;
            }
            return "";
        }
        if (start < 0) return "";

        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int p = start; p < s.length(); p++) {
            char ch = s.charAt(p);
            if (inString) {
                if (escaped) {
                    escaped = false;
                    continue;
                }
                if (ch == '\\') {
                    escaped = true;
                    continue;
                }
                if (ch == '"') {
                    inString = false;
                }
                continue;
            }
            if (ch == '"') {
                inString = true;
                continue;
            }
            if (ch == '{') depth++;
            else if (ch == '}') {
                depth--;
                if (depth == 0) return s.substring(start, p + 1);
            }
        }
        return "";
    }

    static List<String> dataObjects(String json) {
        List<String> out = new ArrayList<String>();
        String s = safe(json);
        int data = s.indexOf("\"data\"");
        if (data < 0) return out;
        int start = s.indexOf('[', data);
        if (start < 0) return out;
        int depth = 0;
        int objStart = -1;
        for (int i = start; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '{') {
                if (depth == 0) objStart = i;
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0 && objStart >= 0) {
                    out.add(s.substring(objStart, i + 1));
                    objStart = -1;
                }
            } else if (ch == ']' && depth == 0) {
                break;
            }
        }
        return out;
    }

    static String pagingNextUrl(String json) {
        String s = safe(json);
        Pattern p = Pattern.compile("\"meta\"\\s*:\\s*\\{.*?\"paging\"\\s*:\\s*\\{.*?\"next\"\\s*:\\s*\"([^\"]*)\"",
                Pattern.DOTALL);
        Matcher m = p.matcher(s);
        if (!m.find()) return "";
        return unescape(m.group(1));
    }

    static String firstObjectInDataArray(String json) {
        List<String> xs = dataObjects(json);
        return xs.isEmpty() ? "" : xs.get(0);
    }

    private static String unescape(String s) {
        return safe(s)
                .replace("\\/", "/")
                .replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\\\", "\\");
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
