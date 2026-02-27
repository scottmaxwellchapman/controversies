package net.familylawandprobate.controversies.plugins;

/**
 * Navigation entry contributed by a plugin.
 */
public record MenuContribution(String groupLabel, String label, String href, int order) {

    public MenuContribution {
        groupLabel = normalize(groupLabel);
        label = normalize(label);
        href = normalizeHref(href);
    }

    public static MenuContribution of(String groupLabel, String label, String href) {
        return new MenuContribution(groupLabel, label, href, 1000);
    }

    private static String normalize(String s) {
        return s == null ? "" : s.trim();
    }

    private static String normalizeHref(String s) {
        String v = normalize(s);
        if (v.isBlank()) return "";
        if (v.contains("://") || v.startsWith("//") || v.contains("\r") || v.contains("\n")) return "";
        if (!v.startsWith("/")) v = "/" + v;
        return v;
    }
}

