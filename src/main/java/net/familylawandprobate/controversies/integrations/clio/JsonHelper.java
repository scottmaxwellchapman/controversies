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

    static String firstObjectInDataArray(String json) {
        List<String> xs = dataObjects(json);
        return xs.isEmpty() ? "" : xs.get(0);
    }

    private static String unescape(String s) {
        return safe(s).replace("\\\"", "\"").replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t").replace("\\\\", "\\");
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
