package net.familylawandprobate.controversies;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class api_unified_inbox_test {

    private final ArrayList<Path> cleanupRoots = new ArrayList<Path>();

    @AfterEach
    void cleanup() throws Exception {
        for (Path root : cleanupRoots) {
            deleteRecursively(root);
        }
    }

    @Test
    void api_unified_inbox_merges_sources_and_prioritizes_urgent_deadlines() throws Exception {
        String tenant = "tenant-api-inbox-" + UUID.randomUUID();
        cleanupRoots.add(Paths.get("data", "tenants", tenant).toAbsolutePath());
        String userUuid = "inbox-user";

        matters.MatterRec matter = matters.defaultStore().create(
                tenant,
                "Inbox Matter",
                "",
                "",
                "",
                "",
                "",
                "",
                ""
        );

        String urgentDueSoon = Instant.now().plusSeconds(2L * 3600L).toString();
        String threadDueLater = Instant.now().plusSeconds(4L * 24L * 3600L).toString();
        String reviewDueSoon = Instant.now().plusSeconds(3L * 3600L).toString();

        tasks.TaskRec taskIn = new tasks.TaskRec();
        taskIn.matterUuid = matter.uuid;
        taskIn.title = "Urgent filing task";
        taskIn.description = "Needs immediate attention";
        taskIn.status = "open";
        taskIn.priority = "urgent";
        taskIn.assignmentMode = "manual";
        taskIn.assignedUserUuid = userUuid;
        taskIn.dueAt = urgentDueSoon;
        taskIn.estimateMinutes = 30;
        tasks.defaultStore().createTask(tenant, taskIn, "tester", "");

        omnichannel_tickets.defaultStore().createTicket(
                tenant,
                matter.uuid,
                "flowroute_sms",
                "Client text thread",
                "open",
                "normal",
                "manual",
                userUuid,
                "",
                threadDueLater,
                "Client",
                "+15551234567",
                "+15559876543",
                "thread-key-1",
                "",
                "inbound",
                "Need an update",
                "+15551234567",
                "+15559876543",
                false,
                "tester",
                ""
        );

        business_process_manager.ProcessDefinition process = new business_process_manager.ProcessDefinition();
        process.name = "Inbox Review Flow";
        business_process_manager.ProcessTrigger trigger = new business_process_manager.ProcessTrigger();
        trigger.type = "manual";
        process.triggers.add(trigger);

        business_process_manager.ProcessStep reviewStep = new business_process_manager.ProcessStep();
        reviewStep.stepId = "review";
        reviewStep.order = 10;
        reviewStep.action = "human_review";
        reviewStep.settings.put("title", "Approve settlement draft");
        reviewStep.settings.put("instructions", "Review the draft and approve/reject.");
        reviewStep.settings.put("assignee_user_uuid", userUuid);
        reviewStep.settings.put("due_at", reviewDueSoon);
        process.steps.add(reviewStep);

        business_process_manager.ProcessDefinition saved = business_process_manager.defaultService().saveProcess(tenant, process);
        business_process_manager.defaultService().triggerProcess(
                tenant,
                saved.processUuid,
                "manual",
                new LinkedHashMap<String, String>(Map.of("matter_uuid", matter.uuid)),
                "tester",
                "test"
        );

        activity_log.defaultStore().logVerbose(
                "tasks.assignment.updated",
                tenant,
                "assigner-user",
                matter.uuid,
                "",
                Map.of("assigned_user_uuid", userUuid, "note", "Assignment changed")
        );

        LinkedHashMap<String, Object> out = invokeApi(
                "inbox.unified.list",
                Map.of("user_uuid", userUuid, "limit", 50),
                tenant,
                "full_access"
        );
        assertTrue(asInt(out.get("count")) >= 4);

        List<Object> items = asList(out.get("items"));
        assertFalse(items.isEmpty());
        String firstPriority = safe(String.valueOf(nested(items.get(0), "priority")));
        String firstDeadlineBucket = safe(String.valueOf(nested(items.get(0), "deadline_bucket")));
        assertTrue("urgent".equalsIgnoreCase(firstPriority) || "high".equalsIgnoreCase(firstPriority));
        assertTrue("overdue".equalsIgnoreCase(firstDeadlineBucket) || "due_24h".equalsIgnoreCase(firstDeadlineBucket));

        boolean sawTask = false;
        boolean sawThread = false;
        boolean sawReview = false;
        boolean sawActivity = false;
        for (Object row : items) {
            String type = safe(String.valueOf(nested(row, "item_type"))).toLowerCase();
            if ("task".equals(type)) sawTask = true;
            if ("thread".equals(type)) sawThread = true;
            if ("process_review".equals(type)) sawReview = true;
            if ("activity".equals(type)) sawActivity = true;
        }
        assertTrue(sawTask);
        assertTrue(sawThread);
        assertTrue(sawReview);
        assertTrue(sawActivity);
    }

    @Test
    void unified_inbox_api_respects_credential_scope_by_section() throws Exception {
        String tenant = "tenant-api-inbox-scope-" + UUID.randomUUID();
        cleanupRoots.add(Paths.get("data", "tenants", tenant).toAbsolutePath());
        String userUuid = "scoped-user";

        tasks.TaskRec taskIn = new tasks.TaskRec();
        taskIn.title = "Scoped Task";
        taskIn.status = "open";
        taskIn.priority = "high";
        taskIn.assignedUserUuid = userUuid;
        taskIn.assignmentMode = "manual";
        taskIn.dueAt = Instant.now().plusSeconds(2L * 24L * 3600L).toString();
        taskIn.estimateMinutes = 25;
        tasks.defaultStore().createTask(tenant, taskIn, "tester", "");

        omnichannel_tickets.defaultStore().createTicket(
                tenant,
                "",
                "flowroute_sms",
                "Scoped Thread",
                "open",
                "high",
                "manual",
                userUuid,
                "",
                "",
                "Client",
                "+15551110000",
                "+15552220000",
                "scope-thread-1",
                "",
                "inbound",
                "Ping",
                "+15551110000",
                "+15552220000",
                false,
                "tester",
                ""
        );

        LinkedHashMap<String, Object> scoped = invokeApi(
                "inbox.unified.list",
                Map.of("user_uuid", userUuid, "limit", 20),
                tenant,
                "permissions:tasks.access"
        );
        List<Object> items = asList(scoped.get("items"));
        assertFalse(items.isEmpty());
        for (Object row : items) {
            assertEquals("task", safe(String.valueOf(nested(row, "item_type"))));
        }
        Object included = scoped.get("included_sections");
        assertEquals("true", String.valueOf(nested(included, "tasks")));
        assertEquals("false", String.valueOf(nested(included, "threads")));
        assertEquals("false", String.valueOf(nested(included, "process_reviews")));
        assertEquals("false", String.valueOf(nested(included, "activities")));
        assertEquals("true", String.valueOf(nested(included, "mentions")));
    }

    @Test
    void unified_inbox_api_surfaces_mentions_by_billing_initials_without_logs_view_scope() throws Exception {
        String tenant = "tenant-api-inbox-mentions-" + UUID.randomUUID();
        cleanupRoots.add(Paths.get("data", "tenants", tenant).toAbsolutePath());

        users_roles users = users_roles.defaultStore();
        users.ensure(tenant);
        String roleUuid = users.listRoles(tenant).get(0).uuid;
        users_roles.UserRec target = users.createUser(
                tenant,
                "mention.target@example.com",
                roleUuid,
                true,
                "StrongPass#123".toCharArray(),
                "ABC",
                0L,
                true
        );

        tasks.TaskRec taskIn = new tasks.TaskRec();
        taskIn.title = "Need @ABC review";
        taskIn.description = "Please check this before filing, @ABC.";
        taskIn.status = "open";
        taskIn.priority = "normal";
        taskIn.assignmentMode = "manual";
        taskIn.assignedUserUuid = "someone-else";
        taskIn.dueAt = Instant.now().plusSeconds(2L * 24L * 3600L).toString();
        taskIn.estimateMinutes = 15;
        tasks.defaultStore().createTask(tenant, taskIn, "actor-user", "");

        LinkedHashMap<String, Object> out = invokeApi(
                "inbox.unified.list",
                Map.of(
                        "user_uuid", target.uuid,
                        "include_tasks", false,
                        "include_threads", false,
                        "include_reviews", false,
                        "include_activities", false,
                        "include_mentions", true,
                        "limit", 30
                ),
                tenant,
                "permissions:tasks.access"
        );

        List<Object> items = asList(out.get("items"));
        assertFalse(items.isEmpty());
        for (Object row : items) {
            assertEquals("mention", safe(String.valueOf(nested(row, "item_type"))));
        }

        Object included = out.get("included_sections");
        assertEquals("true", String.valueOf(nested(included, "mentions")));
        assertTrue(asInt(nested(out.get("counts"), "mentions")) >= 1);
    }

    @SuppressWarnings("unchecked")
    private static LinkedHashMap<String, Object> invokeApi(String operation,
                                                           Map<String, Object> params,
                                                           String tenantUuid,
                                                           String scope) throws Exception {
        Method m = api_servlet.class.getDeclaredMethod(
                "executeOperation",
                String.class,
                Map.class,
                String.class,
                String.class,
                String.class,
                String.class,
                String.class,
                String.class
        );
        m.setAccessible(true);
        try {
            Object out = m.invoke(null, operation, params, tenantUuid, "cred-1", "Credential", scope, "127.0.0.1", "POST");
            if (out instanceof LinkedHashMap<?, ?> map) return (LinkedHashMap<String, Object>) map;
            return new LinkedHashMap<String, Object>();
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof Exception e) throw e;
            throw ex;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object raw) {
        if (raw instanceof List<?> xs) return (List<Object>) xs;
        return new ArrayList<Object>();
    }

    @SuppressWarnings("unchecked")
    private static Object nested(Object raw, String key) {
        if (!(raw instanceof Map<?, ?> map)) return "";
        return map.containsKey(key) ? map.get(key) : "";
    }

    private static int asInt(Object raw) {
        if (raw instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(raw));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (root == null || !Files.exists(root)) return;
        Files.walk(root).sorted(Comparator.reverseOrder()).forEach(p -> {
            try {
                Files.deleteIfExists(p);
            } catch (Exception ignored) {
            }
        });
    }
}
