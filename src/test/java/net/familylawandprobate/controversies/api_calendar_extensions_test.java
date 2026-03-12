package net.familylawandprobate.controversies;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
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

public class api_calendar_extensions_test {

    private final ArrayList<Path> cleanupRoots = new ArrayList<Path>();

    @AfterEach
    void cleanup() throws Exception {
        for (Path root : cleanupRoots) {
            deleteRecursively(root);
        }
    }

    @Test
    void api_supports_multi_user_multi_calendar_workflow() throws Exception {
        String tenant = "tenant-api-calendar-" + UUID.randomUUID();
        cleanupRoots.add(Paths.get("data", "tenants", tenant).toAbsolutePath());

        users_roles users = users_roles.defaultStore();
        users.ensure(tenant);
        users_roles.RoleRec role = users.createRole(tenant, "Calendar API Role", true);
        users.setRolePermission(tenant, role.uuid, "calendar.access", "true");

        users_roles.UserRec userA = users.createUser(tenant, "calendar.a@example.test", role.uuid, true, "StrongPass_2026!".toCharArray(), true);
        users_roles.UserRec userB = users.createUser(tenant, "calendar.b@example.test", role.uuid, true, "StrongPass_2026!".toCharArray(), true);
        users_roles.UserRec userC = users.createUser(tenant, "calendar.c@example.test", role.uuid, true, "StrongPass_2026!".toCharArray(), true);

        LinkedHashMap<String, Object> createdCalendar = invokeApi(
                "calendars.create",
                Map.of(
                        "actor_user_uuid", userA.uuid,
                        "name", "Litigation Calendar",
                        "timezone", "UTC",
                        "color", "#3366AA"
                ),
                tenant
        );

        String calendarUuid = nestedString(createdCalendar, "calendar", "calendar_uuid");
        assertFalse(calendarUuid.isBlank());

        LinkedHashMap<String, Object> shared = invokeApi(
                "calendars.share",
                Map.of(
                        "actor_user_uuid", userA.uuid,
                        "calendar_uuid", calendarUuid,
                        "read_user_uuids", List.of(userB.uuid),
                        "write_user_uuids", List.of(userC.uuid)
                ),
                tenant
        );
        assertTrue(stringList(nestedObject(shared, "calendar", "read_user_uuids")).contains(userB.uuid));
        assertTrue(stringList(nestedObject(shared, "calendar", "write_user_uuids")).contains(userC.uuid));

        LinkedHashMap<String, Object> listForB = invokeApi(
                "calendars.list",
                Map.of("user_uuid", userB.uuid),
                tenant
        );
        assertEquals(1, asInt(listForB.get("count")));

        LinkedHashMap<String, Object> eventCreated = invokeApi(
                "calendar.events.create",
                Map.of(
                        "actor_user_uuid", userC.uuid,
                        "calendar_uuid", calendarUuid,
                        "summary", "Temporary Orders Hearing",
                        "start_at", "2026-07-10T14:00:00Z",
                        "end_at", "2026-07-10T15:00:00Z",
                        "attendees", List.of("client@example.test", "paralegal@example.test")
                ),
                tenant
        );

        String eventUuid = nestedString(eventCreated, "event", "event_uuid");
        assertFalse(eventUuid.isBlank());

        LinkedHashMap<String, Object> gotByB = invokeApi(
                "calendar.events.get",
                Map.of(
                        "user_uuid", userB.uuid,
                        "calendar_uuid", calendarUuid,
                        "event_uuid", eventUuid
                ),
                tenant
        );
        assertEquals("Temporary Orders Hearing", nestedString(gotByB, "event", "summary"));

        LinkedHashMap<String, Object> updated = invokeApi(
                "calendar.events.update",
                Map.of(
                        "actor_user_uuid", userC.uuid,
                        "calendar_uuid", calendarUuid,
                        "event_uuid", eventUuid,
                        "summary", "Temporary Orders Hearing (Updated)",
                        "location", "Courtroom 5"
                ),
                tenant
        );
        assertEquals("Temporary Orders Hearing (Updated)", nestedString(updated, "event", "summary"));

        LinkedHashMap<String, Object> deleted = invokeApi(
                "calendar.events.delete",
                Map.of(
                        "actor_user_uuid", userC.uuid,
                        "calendar_uuid", calendarUuid,
                        "event_uuid", eventUuid
                ),
                tenant
        );
        assertTrue(Boolean.parseBoolean(String.valueOf(deleted.get("deleted"))));
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
    private static List<String> stringList(Object raw) {
        ArrayList<String> out = new ArrayList<String>();
        if (!(raw instanceof List<?> list)) return out;
        for (Object row : list) {
            out.add(safe(String.valueOf(row)));
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
        Files.walk(root).sorted(Comparator.reverseOrder()).forEach(path -> {
            try {
                Files.deleteIfExists(path);
            } catch (Exception ignored) {
            }
        });
    }
}
