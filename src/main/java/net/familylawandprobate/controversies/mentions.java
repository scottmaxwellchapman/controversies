package net.familylawandprobate.controversies;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared mention parsing and logging.
 * Mentions are matched as "@ABC" where ABC is exactly three alphanumeric chars.
 */
public final class mentions {

    private static final Pattern MENTION_PATTERN = Pattern.compile("(?<![A-Za-z0-9])@([A-Za-z0-9]{3})(?![A-Za-z0-9])");
    private static final int SNIPPET_MAX_LEN = 320;

    public static mentions defaultService() {
        return new mentions();
    }

    public LinkedHashSet<String> extractBillingInitials(String text) {
        LinkedHashSet<String> out = new LinkedHashSet<String>();
        Matcher m = MENTION_PATTERN.matcher(safe(text));
        while (m.find()) {
            String initials = safe(m.group(1)).trim().toUpperCase(Locale.ROOT);
            if (initials.length() != 3) continue;
            out.add(initials);
        }
        return out;
    }

    /**
     * Logs one activity event per mentioned user with action "mentions.user".
     * Returns number of emitted mention events.
     */
    public int logMentions(String tenantUuid,
                           String actorUserUuid,
                           String matterUuid,
                           String text,
                           Map<String, String> sourceDetails) {
        String tu = safe(tenantUuid).trim();
        if (tu.isBlank()) return 0;

        LinkedHashSet<String> wantedInitials = extractBillingInitials(text);
        if (wantedInitials.isEmpty()) return 0;

        LinkedHashMap<String, users_roles.UserRec> usersByInitials = new LinkedHashMap<String, users_roles.UserRec>();
        try {
            List<users_roles.UserRec> users = users_roles.defaultStore().listUsers(tu);
            for (users_roles.UserRec u : users) {
                if (u == null) continue;
                String key = safe(u.billingInitials).trim().toUpperCase(Locale.ROOT);
                if (key.length() != 3) continue;
                if (!usersByInitials.containsKey(key)) usersByInitials.put(key, u);
            }
        } catch (Exception ignored) {
            return 0;
        }

        String actor = safe(actorUserUuid).trim();
        String matter = safe(matterUuid).trim();
        LinkedHashMap<String, String> source = new LinkedHashMap<String, String>();
        if (sourceDetails != null) {
            for (Map.Entry<String, String> e : sourceDetails.entrySet()) {
                if (e == null) continue;
                String key = safe(e.getKey()).trim();
                if (key.isBlank()) continue;
                source.put(key, safe(e.getValue()));
            }
        }
        if (matter.isBlank()) matter = safe(source.get("matter_uuid")).trim();

        int emitted = 0;
        for (String initials : wantedInitials) {
            users_roles.UserRec target = usersByInitials.get(safe(initials).trim().toUpperCase(Locale.ROOT));
            if (target == null) continue;
            String targetUserUuid = safe(target.uuid).trim();
            if (targetUserUuid.isBlank()) continue;

            LinkedHashMap<String, String> details = new LinkedHashMap<String, String>();
            details.putAll(source);
            details.put("event_uuid", UUID.randomUUID().toString());
            details.put("mention_uuid", UUID.randomUUID().toString());
            details.put("mentioned_user_uuid", targetUserUuid);
            details.put("to_user_uuid", targetUserUuid);
            details.put("mention_initials", safe(initials).trim().toUpperCase(Locale.ROOT));
            details.put("snippet", mentionSnippet(text, initials));

            activity_log.defaultStore().logVerbose(
                    "mentions.user",
                    tu,
                    actor,
                    matter,
                    "",
                    details
            );
            emitted++;
        }
        return emitted;
    }

    private static String mentionSnippet(String text, String initials) {
        String normalized = safe(text).replaceAll("\\s+", " ").trim();
        if (normalized.isBlank()) return "";
        int max = Math.max(40, SNIPPET_MAX_LEN);
        if (normalized.length() <= max) return normalized;

        String needle = "@" + safe(initials).trim().toUpperCase(Locale.ROOT);
        int at = normalized.toUpperCase(Locale.ROOT).indexOf(needle);
        if (at < 0) return normalized.substring(0, max);

        int start = Math.max(0, at - 96);
        int end = Math.min(normalized.length(), at + 160);
        String slice = normalized.substring(start, end).trim();
        if (slice.length() > max) slice = slice.substring(0, max);
        if (start > 0 && !slice.startsWith("...")) slice = "..." + slice;
        if (end < normalized.length() && !slice.endsWith("...")) slice = slice + "...";
        return slice;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
