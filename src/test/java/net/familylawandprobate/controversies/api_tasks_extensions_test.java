package net.familylawandprobate.controversies;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class api_tasks_extensions_test {

    private final ArrayList<Path> cleanupRoots = new ArrayList<Path>();

    @AfterEach
    void cleanup() throws Exception {
        for (Path root : cleanupRoots) {
            deleteRecursively(root);
        }
    }

    @Test
    void api_supports_tasks_custom_attributes_fields_notes_assignments_round_robin_and_reports() throws Exception {
        String tenant = "tenant-api-tasks-" + UUID.randomUUID();
        cleanupRoots.add(Paths.get("data", "tenants", tenant).toAbsolutePath());

        matters.MatterRec matter = matters.defaultStore().create(
                tenant,
                "API Tasks Matter",
                "",
                "",
                "",
                "",
                ""
        );

        documents docs = documents.defaultStore();
        document_parts parts = document_parts.defaultStore();
        part_versions versions = part_versions.defaultStore();

        documents.DocumentRec doc = docs.create(
                tenant,
                matter.uuid,
                "API Task Doc",
                "evidence",
                "exhibit",
                "draft",
                "owner",
                "work_product",
                "",
                "",
                ""
        );
        document_parts.PartRec part = parts.create(
                tenant,
                matter.uuid,
                doc.uuid,
                "Part A",
                "lead",
                "1",
                "internal",
                "owner",
                ""
        );
        Path sourcePath = parts.partFolder(tenant, matter.uuid, doc.uuid, part.uuid)
                .resolve("version_files")
                .resolve("api_task_source.pdf")
                .toAbsolutePath()
                .normalize();
        Files.createDirectories(sourcePath.getParent());
        Files.write(sourcePath, "api-task-source".getBytes(StandardCharsets.UTF_8));
        part_versions.VersionRec version = versions.create(
                tenant,
                matter.uuid,
                doc.uuid,
                part.uuid,
                "v1",
                "uploaded",
                "application/pdf",
                "abc123",
                String.valueOf(Files.size(sourcePath)),
                sourcePath.toString(),
                "owner",
                "",
                true
        );

        matter_facts factsStore = matter_facts.defaultStore();
        factsStore.ensure(tenant, matter.uuid);
        matter_facts.ClaimRec claim = factsStore.createClaim(tenant, matter.uuid, "Claim", "", 10, "tester");
        matter_facts.ElementRec element = factsStore.createElement(tenant, matter.uuid, claim.uuid, "Element", "", 10, "tester");
        matter_facts.FactRec fact = factsStore.createFact(
                tenant,
                matter.uuid,
                claim.uuid,
                element.uuid,
                "Fact",
                "",
                "",
                "corroborated",
                "medium",
                doc.uuid,
                part.uuid,
                version.uuid,
                1,
                10,
                "tester"
        );

        omnichannel_tickets threadStore = omnichannel_tickets.defaultStore();
        threadStore.ensure(tenant);
        omnichannel_tickets.TicketRec thread = threadStore.createTicket(
                tenant,
                matter.uuid,
                "flowroute_sms",
                "API Task Thread",
                "open",
                "normal",
                "manual",
                "user-a",
                "",
                "",
                "Client",
                "+15551230000",
                "+15557654321",
                "api-thread-1",
                "",
                "inbound",
                "Initial",
                "+15551230000",
                "+15557654321",
                false,
                "tester",
                ""
        );

        LinkedHashMap<String, Object> saveAttrs = invokeApi(
                "task.attributes.save",
                Map.of(
                        "rows", List.of(
                                Map.of(
                                        "key", "phase",
                                        "label", "Phase",
                                        "data_type", "select",
                                        "options", "Intake\nDiscovery",
                                        "required", true,
                                        "enabled", true,
                                        "sort_order", 10
                                )
                        )
                ),
                tenant
        );
        assertTrue(Boolean.parseBoolean(String.valueOf(saveAttrs.get("saved"))));

        LinkedHashMap<String, Object> attrs = invokeApi("task.attributes.list", Map.of("enabled_only", true), tenant);
        assertTrue(asInt(attrs.get("count")) >= 1);

        LinkedHashMap<String, Object> createParams = new LinkedHashMap<String, Object>();
        createParams.put("matter_uuid", matter.uuid);
        createParams.put("title", "API Task");
        createParams.put("description", "API-generated task");
        createParams.put("status", "open");
        createParams.put("priority", "high");
        createParams.put("assignment_mode", "manual");
        createParams.put("assigned_user_uuid", "user-a,user-b");
        createParams.put("due_at", "2026-05-01T17:00");
        createParams.put("reminder_at", "2026-04-30T09:00");
        createParams.put("estimate_minutes", 90);
        createParams.put("claim_uuid", claim.uuid);
        createParams.put("element_uuid", element.uuid);
        createParams.put("fact_uuid", fact.uuid);
        createParams.put("document_uuid", doc.uuid);
        createParams.put("part_uuid", part.uuid);
        createParams.put("version_uuid", version.uuid);
        createParams.put("page_number", 1);
        createParams.put("thread_uuid", thread.uuid);
        createParams.put("actor_user_uuid", "tester");
        createParams.put("fields", Map.of("phase", "Discovery"));

        LinkedHashMap<String, Object> created = invokeApi("tasks.create", createParams, tenant);
        String taskUuid = nestedString(created, "task", "task_uuid");
        assertFalse(taskUuid.isBlank());
        assertEquals("Discovery", stringMap(created.get("fields")).get("phase"));

        LinkedHashMap<String, Object> fieldsUpdated = invokeApi(
                "task.fields.update",
                Map.of("task_uuid", taskUuid, "fields", Map.of("phase", "Intake")),
                tenant
        );
        assertTrue(Boolean.parseBoolean(String.valueOf(fieldsUpdated.get("saved"))));

        LinkedHashMap<String, Object> fields = invokeApi("task.fields.get", Map.of("task_uuid", taskUuid), tenant);
        assertEquals("Intake", stringMap(fields.get("fields")).get("phase"));

        LinkedHashMap<String, Object> note = invokeApi(
                "tasks.notes.add",
                Map.of("task_uuid", taskUuid, "body", "Internal API note", "actor_user_uuid", "tester"),
                tenant
        );
        assertFalse(nestedString(note, "note", "note_uuid").isBlank());

        LinkedHashMap<String, Object> notes = invokeApi("tasks.notes.list", Map.of("task_uuid", taskUuid), tenant);
        assertTrue(asInt(notes.get("count")) >= 1);

        LinkedHashMap<String, Object> updated = invokeApi(
                "tasks.update",
                Map.of(
                        "task_uuid", taskUuid,
                        "status", "in_progress",
                        "assignment_mode", "round_robin",
                        "candidate_user_uuids", List.of("user-a", "user-b", "user-c"),
                        "actor_user_uuid", "tester",
                        "assignment_reason", "Queue balancing"
                ),
                tenant
        );
        assertTrue(Boolean.parseBoolean(String.valueOf(updated.get("updated"))));
        assertEquals("in_progress", nestedString(updated, "task", "status"));

        LinkedHashMap<String, Object> assignments = invokeApi("tasks.assignments.list", Map.of("task_uuid", taskUuid), tenant);
        assertTrue(asInt(assignments.get("count")) >= 1);

        LinkedHashMap<String, Object> list = invokeApi("tasks.list", Map.of("matter_uuid", matter.uuid, "include_archived", false), tenant);
        assertTrue(asInt(list.get("count")) >= 1);

        LinkedHashMap<String, Object> rr = invokeApi(
                "tasks.round_robin.next_assignee",
                Map.of("queue_key", "matter|" + matter.uuid, "candidate_user_uuids", List.of("user-a", "user-b")),
                tenant
        );
        assertFalse(safe(String.valueOf(rr.get("assigned_user_uuid"))).isBlank());

        LinkedHashMap<String, Object> report = invokeApi(
                "tasks.report.refresh",
                Map.of("task_uuid", taskUuid, "actor_user_uuid", "tester"),
                tenant
        );
        assertFalse(nestedString(report, "task", "report_document_uuid").isBlank());
        assertFalse(nestedString(report, "task", "report_part_uuid").isBlank());

        LinkedHashMap<String, Object> archived = invokeApi(
                "tasks.set_archived",
                Map.of("task_uuid", taskUuid, "archived", true, "actor_user_uuid", "tester"),
                tenant
        );
        assertTrue(Boolean.parseBoolean(String.valueOf(nestedObject(archived, "task", "archived"))));

        LinkedHashMap<String, Object> restored = invokeApi(
                "tasks.set_archived",
                Map.of("task_uuid", taskUuid, "archived", false, "actor_user_uuid", "tester"),
                tenant
        );
        assertFalse(Boolean.parseBoolean(String.valueOf(nestedObject(restored, "task", "archived"))));
    }

    @SuppressWarnings("unchecked")
    private static LinkedHashMap<String, Object> invokeApi(String operation, Map<String, Object> params, String tenantUuid) throws Exception {
        Method m = api_servlet.class.getDeclaredMethod(
                "executeOperation",
                String.class,
                Map.class,
                String.class,
                String.class,
                String.class,
                String.class,
                String.class
        );
        m.setAccessible(true);
        try {
            Object out = m.invoke(null, operation, params, tenantUuid, "cred-1", "Credential", "127.0.0.1", "POST");
            if (out instanceof LinkedHashMap<?, ?> map) return (LinkedHashMap<String, Object>) map;
            return new LinkedHashMap<String, Object>();
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof Exception e) throw e;
            throw ex;
        }
    }

    @SuppressWarnings("unchecked")
    private static Object nestedObject(Map<String, Object> root, String key, String nestedKey) {
        if (root == null) return "";
        Object v = root.get(key);
        if (!(v instanceof Map<?, ?> map)) return "";
        return map.containsKey(nestedKey) ? map.get(nestedKey) : "";
    }

    private static String nestedString(Map<String, Object> root, String key, String nestedKey) {
        return safe(String.valueOf(nestedObject(root, key, nestedKey)));
    }

    @SuppressWarnings("unchecked")
    private static LinkedHashMap<String, String> stringMap(Object raw) {
        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
        if (!(raw instanceof Map<?, ?> map)) return out;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (e == null) continue;
            String k = safe(String.valueOf(e.getKey())).trim();
            if (k.isBlank()) continue;
            out.put(k, safe(String.valueOf(e.getValue())));
        }
        return out;
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
