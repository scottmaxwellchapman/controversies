package net.familylawandprobate.controversies;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public enum search_query_operator {
    CONTAINS("contains", "Contains"),
    NOT_CONTAINS("not_contains", "Does Not Contain"),
    EQUALS("equals", "Equals"),
    NOT_EQUALS("not_equals", "Does Not Equal"),
    ENDS_WITH("ends_with", "Ends With"),
    ALL_TERMS("all_terms", "All Terms (AND)"),
    ANY_TERMS("any_terms", "Any Terms (OR)"),
    STARTS_WITH("starts_with", "Starts With"),
    REGEX("regex", "Regex");

    private final String key;
    private final String label;

    search_query_operator(String key, String label) {
        this.key = safe(key).trim().toLowerCase(Locale.ROOT);
        this.label = safe(label).trim();
    }

    public String key() {
        return key;
    }

    public String label() {
        return label;
    }

    public static search_query_operator fromKey(String raw) {
        String k = safe(raw).trim().toLowerCase(Locale.ROOT);
        if ("exact".equals(k)) return EQUALS;
        for (search_query_operator op : values()) {
            if (op.key.equals(k)) return op;
        }
        return CONTAINS;
    }

    public boolean matches(String haystack, String query, boolean caseSensitive) {
        String h = safe(haystack);
        String q = safe(query).trim();
        if (q.isBlank()) return false;

        if (this == REGEX) {
            try {
                java.util.regex.Pattern p = caseSensitive
                        ? java.util.regex.Pattern.compile(q)
                        : java.util.regex.Pattern.compile(q, java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE);
                return p.matcher(h).find();
            } catch (Exception ignored) {
                return false;
            }
        }

        if (!caseSensitive) {
            h = h.toLowerCase(Locale.ROOT);
            q = q.toLowerCase(Locale.ROOT);
        }

        switch (this) {
            case EQUALS:
                return h.equals(q);
            case NOT_EQUALS:
                return !h.equals(q);
            case STARTS_WITH:
                return h.startsWith(q);
            case ENDS_WITH:
                return h.endsWith(q);
            case NOT_CONTAINS:
                return !h.contains(q);
            case ALL_TERMS: {
                List<String> terms = terms(q);
                if (terms.isEmpty()) return false;
                for (String term : terms) {
                    if (!h.contains(term)) return false;
                }
                return true;
            }
            case ANY_TERMS: {
                List<String> terms = terms(q);
                if (terms.isEmpty()) return false;
                for (String term : terms) {
                    if (h.contains(term)) return true;
                }
                return false;
            }
            case CONTAINS:
            default:
                return h.contains(q);
        }
    }

    private static List<String> terms(String raw) {
        String[] chunks = safe(raw).trim().split("\\s+");
        ArrayList<String> out = new ArrayList<String>();
        for (String chunk : chunks) {
            String v = safe(chunk).trim();
            if (!v.isBlank()) out.add(v);
        }
        return out;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
