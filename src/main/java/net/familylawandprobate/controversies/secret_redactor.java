package net.familylawandprobate.controversies;

import java.util.Locale;

public final class secret_redactor {
    private secret_redactor() {}

    public static boolean isSensitiveKey(String key) {
        String k = safe(key).trim().toLowerCase(Locale.ROOT);
        if (k.isBlank()) return false;
        return k.contains("secret")
                || k.contains("token")
                || k.contains("password")
                || k.contains("access_key")
                || k.endsWith("_key");
    }

    public static String redactValue(String value) {
        String v = safe(value);
        if (v.isBlank()) return "";
        return "[REDACTED]";
    }

    public static String redactIfSensitive(String key, String value) {
        return isSensitiveKey(key) ? redactValue(value) : safe(value);
    }

    private static String safe(String s) { return s == null ? "" : s; }
}
