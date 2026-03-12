package net.familylawandprobate.controversies;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Unified user inbox across assigned omnichannel threads, assigned tasks,
 * business-process human reviews, and user-directed activity log events.
 */
public final class unified_inbox {

    public static final class QueryOptions {
        public boolean includeTasks = true;
        public boolean includeThreads = true;
        public boolean includeReviews = true;
        public boolean includeActivities = true;
        public boolean includeMentions = true;
        public boolean includeArchived = false;
        public boolean includeClosed = false;
        public int limit = 100;
        public int activityFetchLimit = 600;
        public int mentionFetchLimit = 600;
    }

    public static final class ItemRec {
        public String itemType = "";
        public String itemUuid = "";
        public String title = "";
        public String summary = "";
        public String status = "";
        public String priority = "";
        public String dueAt = "";
        public String createdAt = "";
        public String updatedAt = "";
        public String matterUuid = "";
        public String linkPath = "";
        public String sourceRef = "";
        public String deadlineBucket = "";
        public long dueEpochMs = -1L;
        public long createdEpochMs = -1L;
        public long updatedEpochMs = -1L;
        public long urgencyScore = 0L;
        public boolean overdue = false;
        public LinkedHashMap<String, String> meta = new LinkedHashMap<String, String>();
    }

    public static unified_inbox defaultService() {
        return new unified_inbox();
    }

    public List<ItemRec> listForUser(String tenantUuid, String userUuid, QueryOptions options) throws Exception {
        String tu = safe(tenantUuid).trim();
        String uu = safe(userUuid).trim();
        if (tu.isBlank() || uu.isBlank()) return List.of();

        QueryOptions opt = options == null ? new QueryOptions() : options;
        int limit = clampInt(opt.limit, 1, 500);
        int activityFetchLimit = clampInt(opt.activityFetchLimit, 50, 2000);
        int mentionFetchLimit = clampInt(opt.mentionFetchLimit, 50, 2000);
        long nowMs = Instant.now().toEpochMilli();

        ArrayList<ItemRec> items = new ArrayList<ItemRec>();

        if (opt.includeTasks) {
            appendTaskItems(items, tu, uu, opt.includeArchived, opt.includeClosed, nowMs);
        }
        if (opt.includeThreads) {
            appendThreadItems(items, tu, uu, opt.includeArchived, opt.includeClosed, nowMs);
        }
        if (opt.includeReviews) {
            appendReviewItems(items, tu, uu, opt.includeClosed, nowMs);
        }
        if (opt.includeMentions) {
            appendMentionItems(items, tu, uu, mentionFetchLimit, nowMs);
        }
        if (opt.includeActivities) {
            appendActivityItems(items, tu, uu, activityFetchLimit, nowMs);
        }

        sortItems(items);
        if (items.size() > limit) return new ArrayList<ItemRec>(items.subList(0, limit));
        return items;
    }

    private static void appendTaskItems(List<ItemRec> out,
                                        String tenantUuid,
                                        String userUuid,
                                        boolean includeArchived,
                                        boolean includeClosed,
                                        long nowMs) throws Exception {
        tasks store = tasks.defaultStore();
        List<tasks.TaskRec> rows = store.listTasks(tenantUuid);
        for (tasks.TaskRec r : rows) {
            if (r == null) continue;
            if (!includeArchived && r.archived) continue;
            if (!csvContains(safe(r.assignedUserUuid), userUuid)) continue;
            if (!includeClosed && isTaskClosedStatus(r.status)) continue;

            ItemRec item = new ItemRec();
            item.itemType = "task";
            item.itemUuid = safe(r.uuid);
            item.title = firstNonBlank(safe(r.title), "Task");
            item.summary = clampText(firstNonBlank(safe(r.description), "Assigned task"), 320);
            item.status = safe(r.status);
            item.priority = normalizePriority(r.priority);
            item.dueAt = safe(r.dueAt);
            item.createdAt = safe(r.createdAt);
            item.updatedAt = firstNonBlank(safe(r.updatedAt), safe(r.createdAt));
            item.matterUuid = safe(r.matterUuid);
            item.linkPath = "/tasks.jsp?task_uuid=" + safe(r.uuid);
            item.sourceRef = safe(r.threadUuid).isBlank() ? "tasks" : "tasks+threads";
            item.meta.put("thread_uuid", safe(r.threadUuid));
            item.meta.put("assignment_mode", safe(r.assignmentMode));
            enrichTimingFields(item, nowMs);
            out.add(item);
        }
    }

    private static void appendThreadItems(List<ItemRec> out,
                                          String tenantUuid,
                                          String userUuid,
                                          boolean includeArchived,
                                          boolean includeClosed,
                                          long nowMs) throws Exception {
        omnichannel_tickets store = omnichannel_tickets.defaultStore();
        List<omnichannel_tickets.TicketRec> rows = store.listTickets(tenantUuid);
        for (omnichannel_tickets.TicketRec r : rows) {
            if (r == null) continue;
            if (!includeArchived && r.archived) continue;
            if (!csvContains(safe(r.assignedUserUuid), userUuid)) continue;
            if (!includeClosed && isThreadClosedStatus(r.status)) continue;

            ItemRec item = new ItemRec();
            item.itemType = "thread";
            item.itemUuid = safe(r.uuid);
            item.title = firstNonBlank(safe(r.subject), safe(r.customerDisplay), "Omnichannel thread");
            item.summary = clampText(
                    firstNonBlank(
                            safe(r.customerDisplay),
                            safe(r.customerAddress),
                            safe(r.mailboxAddress),
                            safe(r.channel)
                    ),
                    320
            );
            item.status = safe(r.status);
            item.priority = normalizePriority(r.priority);
            item.dueAt = safe(r.dueAt);
            item.createdAt = safe(r.createdAt);
            item.updatedAt = firstNonBlank(safe(r.updatedAt), safe(r.lastInboundAt), safe(r.lastOutboundAt), safe(r.createdAt));
            item.matterUuid = safe(r.matterUuid);
            item.linkPath = "/omnichannel.jsp?ticket_uuid=" + safe(r.uuid);
            item.sourceRef = safe(r.channel);
            item.meta.put("channel", safe(r.channel));
            item.meta.put("mailbox_address", safe(r.mailboxAddress));
            item.meta.put("customer_address", safe(r.customerAddress));
            item.meta.put("inbound_count", String.valueOf(r.inboundCount));
            item.meta.put("outbound_count", String.valueOf(r.outboundCount));
            enrichTimingFields(item, nowMs);
            out.add(item);
        }
    }

    private static void appendReviewItems(List<ItemRec> out,
                                          String tenantUuid,
                                          String userUuid,
                                          boolean includeClosed,
                                          long nowMs) throws Exception {
        business_process_manager store = business_process_manager.defaultService();
        List<business_process_manager.HumanReviewTask> rows = store.listReviewsForUser(
                tenantUuid,
                userUuid,
                !includeClosed,
                500
        );
        for (business_process_manager.HumanReviewTask r : rows) {
            if (r == null) continue;
            if (!includeClosed && !"pending".equalsIgnoreCase(safe(r.status))) continue;

            ItemRec item = new ItemRec();
            item.itemType = "process_review";
            item.itemUuid = safe(r.reviewUuid);
            item.title = firstNonBlank(safe(r.title), safe(r.processName), "Business process review");
            item.summary = clampText(firstNonBlank(safe(r.instructions), safe(r.comment), safe(r.eventType)), 320);
            item.status = safe(r.status);
            item.priority = "high";
            item.dueAt = safe(r.dueAt);
            item.createdAt = safe(r.createdAt);
            item.updatedAt = firstNonBlank(safe(r.resolvedAt), safe(r.createdAt));
            item.matterUuid = safe(r.context.get("matter_uuid"));
            item.linkPath = "/business_process_reviews.jsp?review_uuid=" + safe(r.reviewUuid);
            item.sourceRef = firstNonBlank(safe(r.reviewType), "human_review");
            item.meta.put("process_uuid", safe(r.processUuid));
            item.meta.put("process_name", safe(r.processName));
            item.meta.put("request_run_uuid", safe(r.requestRunUuid));
            item.meta.put("assignee_user_uuid", safe(r.assigneeUserUuid));
            enrichTimingFields(item, nowMs);
            out.add(item);
        }
    }

    private static void appendActivityItems(List<ItemRec> out,
                                            String tenantUuid,
                                            String userUuid,
                                            int fetchLimit,
                                            long nowMs) {
        activity_log store = activity_log.defaultStore();
        List<activity_log.LogEntry> rows = store.recent(tenantUuid, fetchLimit);
        for (activity_log.LogEntry r : rows) {
            if (r == null) continue;
            if ("mentions.user".equalsIgnoreCase(safe(r.action).trim())) continue;
            if (!isActivityDirectedToUser(r, userUuid)) continue;

            ItemRec item = new ItemRec();
            item.itemType = "activity";
            item.itemUuid = firstNonBlank(
                    safe(r.detailMap.get("event_uuid")),
                    safe(r.detailMap.get("task_uuid")),
                    safe(r.detailMap.get("thread_uuid")),
                    safe(r.detailMap.get("review_uuid")),
                    safe(r.time)
            );
            item.title = firstNonBlank(safe(r.action), "Activity");
            item.summary = clampText(firstNonBlank(safe(r.details), "Activity event"), 320);
            item.status = safe(r.level);
            item.priority = levelPriority(r.level);
            item.dueAt = firstNonBlank(
                    safe(r.detailMap.get("due_at")),
                    safe(r.detailMap.get("deadline_at")),
                    safe(r.detailMap.get("reminder_at"))
            );
            item.createdAt = safe(r.time);
            item.updatedAt = safe(r.time);
            item.matterUuid = safe(r.caseUuid);
            item.linkPath = safe(r.caseUuid).isBlank() ? "/activity.jsp" : "/activity.jsp?case_uuid=" + safe(r.caseUuid);
            item.sourceRef = firstNonBlank(
                    safe(r.detailMap.get("source")),
                    safe(r.detailMap.get("component")),
                    "activity_log"
            );
            item.meta.put("action", safe(r.action));
            item.meta.put("user_uuid", safe(r.userUuid));
            item.meta.put("document_uuid", safe(r.documentUuid));
            enrichTimingFields(item, nowMs);
            out.add(item);
        }
    }

    private static void appendMentionItems(List<ItemRec> out,
                                           String tenantUuid,
                                           String userUuid,
                                           int fetchLimit,
                                           long nowMs) {
        activity_log store = activity_log.defaultStore();
        List<activity_log.LogEntry> rows = store.recent(tenantUuid, fetchLimit);
        for (activity_log.LogEntry r : rows) {
            if (r == null) continue;
            if (!"mentions.user".equalsIgnoreCase(safe(r.action).trim())) continue;
            if (!isMentionDirectedToUser(r, userUuid)) continue;

            String sourcePath = safe(r.detailMap.get("source_path")).trim();
            String sourceType = safe(r.detailMap.get("source_type")).trim();
            String sourceTitle = firstNonBlank(safe(r.detailMap.get("source_title")), safe(r.action), "Mention");
            String snippet = firstNonBlank(safe(r.detailMap.get("snippet")), safe(r.details), "You were mentioned.");
            String mentionInitials = safe(r.detailMap.get("mention_initials")).trim().toUpperCase(Locale.ROOT);
            String actor = safe(r.userUuid).trim();

            ItemRec item = new ItemRec();
            item.itemType = "mention";
            item.itemUuid = firstNonBlank(
                    safe(r.detailMap.get("event_uuid")),
                    safe(r.detailMap.get("mention_uuid")),
                    safe(r.detailMap.get("note_uuid")),
                    safe(r.detailMap.get("message_uuid")),
                    safe(r.detailMap.get("task_uuid")),
                    safe(r.detailMap.get("thread_uuid")),
                    safe(r.detailMap.get("lead_uuid")),
                    safe(r.time)
            );
            item.title = firstNonBlank(
                    safe(r.detailMap.get("title")),
                    sourceTitle,
                    mentionInitials.isBlank() ? "New mention" : ("Mention @" + mentionInitials)
            );
            if (!actor.isBlank()) {
                item.summary = clampText("Mentioned by " + actor + ": " + snippet, 320);
            } else {
                item.summary = clampText(snippet, 320);
            }
            item.status = safe(r.level);
            item.priority = "high";
            item.dueAt = firstNonBlank(
                    safe(r.detailMap.get("due_at")),
                    safe(r.detailMap.get("deadline_at")),
                    safe(r.detailMap.get("reminder_at"))
            );
            item.createdAt = safe(r.time);
            item.updatedAt = safe(r.time);
            item.matterUuid = firstNonBlank(
                    safe(r.detailMap.get("matter_uuid")),
                    safe(r.caseUuid)
            );
            item.linkPath = sourcePath.isBlank() ? mentionLinkPath(sourceType, r.detailMap) : sourcePath;
            item.sourceRef = firstNonBlank(sourceType, "mention");
            item.meta.put("mention_initials", mentionInitials);
            item.meta.put("mentioned_user_uuid", firstNonBlank(
                    safe(r.detailMap.get("mentioned_user_uuid")),
                    safe(r.detailMap.get("to_user_uuid"))
            ));
            item.meta.put("actor_user_uuid", actor);
            item.meta.put("source_type", sourceType);
            item.meta.put("source_uuid", firstNonBlank(
                    safe(r.detailMap.get("source_uuid")),
                    safe(r.detailMap.get("task_uuid")),
                    safe(r.detailMap.get("thread_uuid")),
                    safe(r.detailMap.get("lead_uuid"))
            ));
            enrichTimingFields(item, nowMs);
            out.add(item);
        }
    }

    private static boolean isActivityDirectedToUser(activity_log.LogEntry row, String userUuid) {
        String target = safe(userUuid).trim();
        if (target.isBlank() || row == null) return false;

        if (target.equalsIgnoreCase(safe(row.userUuid).trim())) return true;

        Map<String, String> d = row.detailMap;
        if (d == null || d.isEmpty()) return false;

        String[] candidateKeys = new String[] {
                "user_uuid",
                "actor_user_uuid",
                "assigned_user_uuid",
                "assignee_user_uuid",
                "requested_by_user_uuid",
                "reviewed_by_user_uuid",
                "to_user_uuid",
                "to_user_uuids",
                "from_user_uuid",
                "from_user_uuids"
        };
        for (int i = 0; i < candidateKeys.length; i++) {
            String key = candidateKeys[i];
            if (csvContains(safe(d.get(key)), target)) return true;
        }

        for (Map.Entry<String, String> e : d.entrySet()) {
            if (e == null) continue;
            String k = safe(e.getKey()).trim().toLowerCase(Locale.ROOT);
            if (!k.contains("user_uuid")) continue;
            if (csvContains(safe(e.getValue()), target)) return true;
        }
        return false;
    }

    private static boolean isMentionDirectedToUser(activity_log.LogEntry row, String userUuid) {
        String target = safe(userUuid).trim();
        if (target.isBlank() || row == null) return false;
        Map<String, String> d = row.detailMap;
        if (d == null || d.isEmpty()) return false;
        if (csvContains(safe(d.get("mentioned_user_uuid")), target)) return true;
        if (csvContains(safe(d.get("to_user_uuid")), target)) return true;
        if (csvContains(safe(d.get("to_user_uuids")), target)) return true;
        return false;
    }

    private static void enrichTimingFields(ItemRec item, long nowMs) {
        if (item == null) return;
        item.dueEpochMs = parseFlexibleEpochMillis(item.dueAt);
        item.createdEpochMs = parseFlexibleEpochMillis(item.createdAt);
        item.updatedEpochMs = parseFlexibleEpochMillis(item.updatedAt);
        item.deadlineBucket = computeDeadlineBucket(item.dueEpochMs, nowMs);
        item.overdue = "overdue".equals(item.deadlineBucket);
        item.urgencyScore = computeUrgencyScore(item.priority, item.dueEpochMs, nowMs);
    }

    private static void sortItems(List<ItemRec> rows) {
        if (rows == null || rows.size() < 2) return;
        rows.sort((a, b) -> {
            long aUrgency = a == null ? Long.MIN_VALUE : a.urgencyScore;
            long bUrgency = b == null ? Long.MIN_VALUE : b.urgencyScore;
            int cmp = Long.compare(bUrgency, aUrgency);
            if (cmp != 0) return cmp;

            cmp = Long.compare(dueOrder(a == null ? -1L : a.dueEpochMs), dueOrder(b == null ? -1L : b.dueEpochMs));
            if (cmp != 0) return cmp;

            cmp = Long.compare(mostRecentEpoch(b), mostRecentEpoch(a));
            if (cmp != 0) return cmp;

            cmp = safe(a == null ? "" : a.itemType).compareToIgnoreCase(safe(b == null ? "" : b.itemType));
            if (cmp != 0) return cmp;

            return safe(a == null ? "" : a.title).compareToIgnoreCase(safe(b == null ? "" : b.title));
        });
    }

    private static long dueOrder(long dueEpochMs) {
        return dueEpochMs > 0L ? dueEpochMs : Long.MAX_VALUE;
    }

    private static String mentionLinkPath(String sourceType, Map<String, String> detail) {
        String type = safe(sourceType).trim().toLowerCase(Locale.ROOT);
        Map<String, String> d = detail == null ? Map.of() : detail;
        if ("task".equals(type)) {
            String taskUuid = safe(d.get("task_uuid")).trim();
            if (!taskUuid.isBlank()) return "/tasks.jsp?task_uuid=" + taskUuid;
            String sourceUuid = safe(d.get("source_uuid")).trim();
            if (!sourceUuid.isBlank()) return "/tasks.jsp?task_uuid=" + sourceUuid;
        }
        if ("thread".equals(type) || "omnichannel".equals(type)) {
            String threadUuid = safe(d.get("thread_uuid")).trim();
            if (!threadUuid.isBlank()) return "/omnichannel.jsp?ticket_uuid=" + threadUuid;
            String sourceUuid = safe(d.get("source_uuid")).trim();
            if (!sourceUuid.isBlank()) return "/omnichannel.jsp?ticket_uuid=" + sourceUuid;
        }
        if ("lead".equals(type)) {
            String leadUuid = safe(d.get("lead_uuid")).trim();
            if (!leadUuid.isBlank()) return "/leads.jsp?lead_uuid=" + leadUuid;
            String sourceUuid = safe(d.get("source_uuid")).trim();
            if (!sourceUuid.isBlank()) return "/leads.jsp?lead_uuid=" + sourceUuid;
        }
        return "/inbox.jsp?type=mention";
    }

    private static long mostRecentEpoch(ItemRec r) {
        if (r == null) return -1L;
        if (r.updatedEpochMs > 0L) return r.updatedEpochMs;
        if (r.createdEpochMs > 0L) return r.createdEpochMs;
        return -1L;
    }

    private static long computeUrgencyScore(String priority, long dueEpochMs, long nowMs) {
        long score = priorityWeight(priority);
        if (dueEpochMs <= 0L) return score;

        long deltaMinutes = (dueEpochMs - nowMs) / 60000L;
        if (deltaMinutes <= 0L) {
            long overdueMinutes = Math.min(72L * 60L, Math.abs(deltaMinutes));
            return score + (900L - ((overdueMinutes / 60L) * 4L));
        }
        if (deltaMinutes <= 24L * 60L) {
            return score + (760L - ((deltaMinutes / 60L) * 10L));
        }
        if (deltaMinutes <= 72L * 60L) {
            long beyond24hHours = (deltaMinutes - (24L * 60L)) / 60L;
            return score + (520L - (beyond24hHours * 4L));
        }
        if (deltaMinutes <= 168L * 60L) {
            long beyond72hHours = (deltaMinutes - (72L * 60L)) / 60L;
            return score + (328L - beyond72hHours);
        }
        if (deltaMinutes <= 336L * 60L) {
            long beyondWeekHours = (deltaMinutes - (168L * 60L)) / 60L;
            return score + (232L - (beyondWeekHours / 3L));
        }
        return score;
    }

    private static String computeDeadlineBucket(long dueEpochMs, long nowMs) {
        if (dueEpochMs <= 0L) return "unscheduled";
        long deltaMinutes = (dueEpochMs - nowMs) / 60000L;
        if (deltaMinutes <= 0L) return "overdue";
        if (deltaMinutes <= 24L * 60L) return "due_24h";
        if (deltaMinutes <= 72L * 60L) return "due_72h";
        if (deltaMinutes <= 168L * 60L) return "due_7d";
        return "upcoming";
    }

    private static long priorityWeight(String priority) {
        String p = normalizePriority(priority);
        if ("urgent".equals(p)) return 400L;
        if ("high".equals(p)) return 300L;
        if ("low".equals(p)) return 80L;
        return 180L;
    }

    private static String normalizePriority(String priority) {
        String p = safe(priority).trim().toLowerCase(Locale.ROOT);
        if ("urgent".equals(p)) return "urgent";
        if ("high".equals(p)) return "high";
        if ("low".equals(p)) return "low";
        return "normal";
    }

    private static String levelPriority(String level) {
        String v = safe(level).trim().toLowerCase(Locale.ROOT);
        if ("error".equals(v)) return "high";
        if ("warning".equals(v)) return "normal";
        return "low";
    }

    private static boolean isTaskClosedStatus(String status) {
        String s = safe(status).trim().toLowerCase(Locale.ROOT);
        return "completed".equals(s) || "cancelled".equals(s);
    }

    private static boolean isThreadClosedStatus(String status) {
        String s = safe(status).trim().toLowerCase(Locale.ROOT);
        return "resolved".equals(s) || "closed".equals(s);
    }

    private static boolean csvContains(String csv, String wanted) {
        String needle = safe(wanted).trim();
        if (needle.isBlank()) return false;
        String[] parts = safe(csv).split(",");
        for (int i = 0; i < parts.length; i++) {
            String id = safe(parts[i]).trim();
            if (id.isBlank()) continue;
            if (needle.equalsIgnoreCase(id)) return true;
        }
        return false;
    }

    private static long parseFlexibleEpochMillis(String raw) {
        String v = safe(raw).trim();
        if (v.isBlank()) return -1L;
        try {
            return Instant.parse(v).toEpochMilli();
        } catch (Exception ignored) {
        }
        try {
            return OffsetDateTime.parse(v).toInstant().toEpochMilli();
        } catch (Exception ignored) {
        }
        try {
            return LocalDateTime.parse(v).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception ignored) {
        }
        if (v.length() >= 16 && v.length() < 19 && v.contains("T")) {
            try {
                return LocalDateTime.parse(v + ":00").atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            } catch (Exception ignored) {
            }
        }
        return -1L;
    }

    private static int clampInt(int v, int lo, int hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }

    private static String clampText(String raw, int max) {
        String s = safe(raw);
        int lim = Math.max(1, max);
        if (s.length() <= lim) return s;
        return s.substring(0, lim);
    }

    private static String firstNonBlank(String... values) {
        if (values == null || values.length == 0) return "";
        for (int i = 0; i < values.length; i++) {
            String v = safe(values[i]).trim();
            if (!v.isBlank()) return v;
        }
        return "";
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
