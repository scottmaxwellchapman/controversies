package net.familylawandprobate.controversies;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class api_omnichannel_bpm_extensions_test {

    private final ArrayList<Path> cleanupRoots = new ArrayList<Path>();

    @AfterEach
    void cleanup() throws Exception {
        for (Path root : cleanupRoots) {
            deleteRecursively(root);
        }
    }

    @Test
    void api_supports_omnichannel_thread_lifecycle_operations() throws Exception {
        String tenant = "tenant-api-omni-" + UUID.randomUUID();
        cleanupRoots.add(Paths.get("data", "tenants", tenant).toAbsolutePath());

        LinkedHashMap<String, Object> createParams = new LinkedHashMap<String, Object>();
        createParams.put("channel", "flowroute_sms");
        createParams.put("subject", "Thread via API");
        createParams.put("status", "open");
        createParams.put("priority", "normal");
        createParams.put("assignment_mode", "manual");
        createParams.put("initial_direction", "inbound");
        createParams.put("initial_body", "Initial inbound payload");
        createParams.put("initial_from_address", "+15551230000");
        createParams.put("initial_to_address", "+15557654321");
        createParams.put("actor_user_uuid", "tester");

        LinkedHashMap<String, Object> created = invokeApi("omnichannel.threads.create", createParams, tenant);
        String threadUuid = nestedString(created, "thread", "thread_uuid");
        assertFalse(threadUuid.isBlank());

        LinkedHashMap<String, Object> list = invokeApi("omnichannel.threads.list", Map.of(), tenant);
        assertTrue(asInt(list.get("count")) >= 1);

        LinkedHashMap<String, Object> noteParams = new LinkedHashMap<String, Object>();
        noteParams.put("thread_uuid", threadUuid);
        noteParams.put("body", "Internal API note");
        noteParams.put("actor_user_uuid", "tester");
        LinkedHashMap<String, Object> noteResult = invokeApi("omnichannel.notes.add", noteParams, tenant);
        assertFalse(nestedString(noteResult, "note", "message_uuid").isBlank());

        byte[] attachmentBytes = "Evidence payload".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        LinkedHashMap<String, Object> attachmentParams = new LinkedHashMap<String, Object>();
        attachmentParams.put("thread_uuid", threadUuid);
        attachmentParams.put("file_name", "evidence.txt");
        attachmentParams.put("mime_type", "text/plain");
        attachmentParams.put("content_base64", Base64.getEncoder().encodeToString(attachmentBytes));
        attachmentParams.put("uploaded_by", "tester");
        LinkedHashMap<String, Object> addedAttachment = invokeApi("omnichannel.attachments.add", attachmentParams, tenant);
        String attachmentUuid = nestedString(addedAttachment, "attachment", "attachment_uuid");
        assertFalse(attachmentUuid.isBlank());

        LinkedHashMap<String, Object> attachmentGet = invokeApi(
                "omnichannel.attachments.get",
                Map.of("thread_uuid", threadUuid, "attachment_uuid", attachmentUuid),
                tenant
        );
        assertEquals(attachmentBytes.length, asInt(attachmentGet.get("content_size_bytes")));
        assertFalse(safe(String.valueOf(attachmentGet.get("content_base64"))).isBlank());

        LinkedHashMap<String, Object> update = invokeApi(
                "omnichannel.threads.update",
                Map.of(
                        "thread_uuid", threadUuid,
                        "status", "resolved",
                        "priority", "high",
                        "assigned_user_uuid", "user-a,user-b",
                        "subject", "Thread via API (updated)",
                        "actor_user_uuid", "tester",
                        "assignment_reason", "Balancing queue"
                ),
                tenant
        );
        assertTrue(Boolean.parseBoolean(String.valueOf(update.get("updated"))));
        assertEquals("resolved", nestedString(update, "thread", "status"));

        LinkedHashMap<String, Object> details = invokeApi(
                "omnichannel.threads.get",
                Map.of("thread_uuid", threadUuid, "include_details", true),
                tenant
        );
        assertEquals(threadUuid, nestedString(details, "thread", "thread_uuid"));
        assertTrue(asList(details.get("messages")).size() >= 2);
        assertTrue(asList(details.get("attachments")).size() >= 1);
        assertTrue(asList(details.get("assignments")).size() >= 1);

        LinkedHashMap<String, Object> archive = invokeApi(
                "omnichannel.threads.set_archived",
                Map.of("thread_uuid", threadUuid, "archived", true, "actor_user_uuid", "tester"),
                tenant
        );
        assertTrue(Boolean.parseBoolean(String.valueOf(archive.get("updated"))));
        assertTrue(Boolean.parseBoolean(String.valueOf(nestedObject(archive, "thread", "archived"))));

        LinkedHashMap<String, Object> unarchive = invokeApi(
                "omnichannel.threads.set_archived",
                Map.of("thread_uuid", threadUuid, "archived", false, "actor_user_uuid", "tester"),
                tenant
        );
        assertTrue(Boolean.parseBoolean(String.valueOf(unarchive.get("updated"))));
        assertFalse(Boolean.parseBoolean(String.valueOf(nestedObject(unarchive, "thread", "archived"))));
    }

    @Test
    void api_exposes_bpm_action_catalog() throws Exception {
        String tenant = "tenant-api-bpm-catalog-" + UUID.randomUUID();
        cleanupRoots.add(Paths.get("data", "tenants", tenant).toAbsolutePath());

        LinkedHashMap<String, Object> out = invokeApi("bpm.actions.catalog", Map.of(), tenant);
        assertTrue(asInt(out.get("count")) >= 1);

        boolean foundUpdateThread = false;
        boolean foundSetDocumentField = false;
        boolean foundUpdateTask = false;
        boolean foundSetCustomObjectRecordField = false;
        boolean foundUpdateCustomObjectRecord = false;
        boolean foundRequestInformation = false;
        boolean foundDeadlineRule = false;
        boolean foundAuditSnapshot = false;
        boolean foundAssemblyToDocumentVersion = false;
        for (Object row : asList(out.get("items"))) {
            if (!(row instanceof Map<?, ?> map)) continue;
            String action = safe(String.valueOf(map.get("action")));
            if ("update_thread".equals(action)) foundUpdateThread = true;
            if ("set_document_field".equals(action)) foundSetDocumentField = true;
            if ("update_task".equals(action)) foundUpdateTask = true;
            if ("set_custom_object_record_field".equals(action)) foundSetCustomObjectRecordField = true;
            if ("update_custom_object_record".equals(action)) foundUpdateCustomObjectRecord = true;
            if ("request_information".equals(action)) foundRequestInformation = true;
            if ("deadline_rule_calculation".equals(action)) foundDeadlineRule = true;
            if ("audit_snapshot".equals(action)) foundAuditSnapshot = true;
            if ("assembly_to_document_version".equals(action)) foundAssemblyToDocumentVersion = true;
        }
        assertTrue(foundUpdateThread);
        assertTrue(foundSetDocumentField);
        assertTrue(foundUpdateTask);
        assertTrue(foundSetCustomObjectRecordField);
        assertTrue(foundUpdateCustomObjectRecord);
        assertTrue(foundRequestInformation);
        assertTrue(foundDeadlineRule);
        assertTrue(foundAuditSnapshot);
        assertTrue(foundAssemblyToDocumentVersion);
    }

    @Test
    void api_supports_custom_object_definition_manipulation() throws Exception {
        String tenant = "tenant-api-custom-objects-" + UUID.randomUUID();
        cleanupRoots.add(Paths.get("data", "tenants", tenant).toAbsolutePath());

        LinkedHashMap<String, Object> created = invokeApi(
                "custom_objects.create",
                Map.of(
                        "key", "time_entry",
                        "label", "Time Entry",
                        "plural_label", "Time Entries",
                        "enabled", true,
                        "published", false,
                        "sort_order", 10
                ),
                tenant
        );
        String objectUuid = nestedString(created, "object", "uuid");
        assertFalse(objectUuid.isBlank());

        LinkedHashMap<String, Object> updated = invokeApi(
                "custom_objects.update",
                Map.of(
                        "object_uuid", objectUuid,
                        "label", "Billing Time Entry",
                        "published", true
                ),
                tenant
        );
        assertTrue(Boolean.parseBoolean(String.valueOf(updated.get("updated"))));
        assertEquals("Billing Time Entry", nestedString(updated, "object", "label"));
        assertTrue(Boolean.parseBoolean(String.valueOf(nestedObject(updated, "object", "published"))));

        LinkedHashMap<String, Object> listed = invokeApi("custom_objects.list", Map.of(), tenant);
        assertEquals(1, asInt(listed.get("count")));

        LinkedHashMap<String, Object> deleted = invokeApi(
                "custom_objects.delete",
                Map.of("object_uuid", objectUuid),
                tenant
        );
        assertTrue(Boolean.parseBoolean(String.valueOf(deleted.get("deleted"))));

        LinkedHashMap<String, Object> afterDelete = invokeApi("custom_objects.list", Map.of(), tenant);
        assertEquals(0, asInt(afterDelete.get("count")));
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
    private static List<Object> asList(Object raw) {
        if (raw instanceof List<?> xs) return (List<Object>) xs;
        return new ArrayList<Object>();
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
