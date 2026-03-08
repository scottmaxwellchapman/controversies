package net.familylawandprobate.controversies;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class api_permissions_scope_test {

    private final ArrayList<Path> cleanupRoots = new ArrayList<Path>();

    @AfterEach
    void cleanup() throws Exception {
        for (Path root : cleanupRoots) {
            deleteRecursively(root);
        }
    }

    @Test
    void credential_scope_enforces_operation_permissions() throws Exception {
        String tenant = "tenant-api-scope-" + UUID.randomUUID();
        cleanupRoots.add(Paths.get("data", "tenants", tenant).toAbsolutePath());

        LinkedHashMap<String, Object> matters = invokeApiWithScope(
                "matters.list",
                Map.of("include_trashed", false),
                tenant,
                "permissions:cases.access"
        );
        assertTrue(matters.containsKey("count"));

        assertThrows(SecurityException.class, () -> invokeApiWithScope(
                "api.credentials.list",
                Map.of(),
                tenant,
                "permissions:cases.access"
        ));

        LinkedHashMap<String, Object> allowedByProfile = invokeApiWithScope(
                "api.credentials.list",
                Map.of(),
                tenant,
                "profile:security-manager"
        );
        assertTrue(allowedByProfile.containsKey("count"));

        assertThrows(SecurityException.class, () -> invokeApiWithScope(
                "matters.list",
                Map.of(),
                tenant,
                "profile:security-manager"
        ));
    }

    @Test
    void api_credentials_endpoints_support_get_update_and_secret_rotation() throws Exception {
        String tenant = "tenant-api-cred-endpoints-" + UUID.randomUUID();
        cleanupRoots.add(Paths.get("data", "tenants", tenant).toAbsolutePath());

        LinkedHashMap<String, Object> created = invokeApiWithScope(
                "api.credentials.create",
                Map.of(
                        "label", "Case Bot",
                        "scope", "permissions:cases.access,contacts.access",
                        "created_by_user_uuid", "user-123"
                ),
                tenant,
                "permissions:api.credentials.manage"
        );
        String credentialId = nestedString(created, "credential", "credential_id");
        assertFalse(credentialId.isBlank());
        assertEquals("permissions:cases.access,contacts.access", nestedString(created, "credential", "scope"));
        assertFalse(safe(String.valueOf(created.get("api_key"))).isBlank());
        assertFalse(safe(String.valueOf(created.get("api_secret"))).isBlank());

        LinkedHashMap<String, Object> fetched = invokeApiWithScope(
                "api.credentials.get",
                Map.of("credential_id", credentialId),
                tenant,
                "permissions:api.credentials.manage"
        );
        assertEquals(credentialId, nestedString(fetched, "credential", "credential_id"));

        LinkedHashMap<String, Object> updated = invokeApiWithScope(
                "api.credentials.update",
                Map.of(
                        "credential_id", credentialId,
                        "label", "Security Bot",
                        "scope", "profile:security-manager"
                ),
                tenant,
                "permissions:api.credentials.manage"
        );
        assertTrue(Boolean.parseBoolean(String.valueOf(updated.get("updated"))));
        assertEquals("Security Bot", nestedString(updated, "credential", "label"));
        assertEquals("profile:security-manager", nestedString(updated, "credential", "scope"));

        LinkedHashMap<String, Object> rotated = invokeApiWithScope(
                "api.credentials.rotate_secret",
                Map.of("credential_id", credentialId),
                tenant,
                "permissions:api.credentials.manage"
        );
        assertEquals(credentialId, nestedString(rotated, "credential", "credential_id"));
        assertFalse(safe(String.valueOf(rotated.get("api_key"))).isBlank());
        assertFalse(safe(String.valueOf(rotated.get("api_secret"))).isBlank());
    }

    @Test
    void permissions_catalog_and_legacy_endpoint_resolution_are_supported() throws Exception {
        String tenant = "tenant-api-perms-catalog-" + UUID.randomUUID();
        cleanupRoots.add(Paths.get("data", "tenants", tenant).toAbsolutePath());

        LinkedHashMap<String, Object> catalog = invokeApiWithScope(
                "permissions.catalog",
                Map.of(),
                tenant,
                "permissions:permissions.manage"
        );
        assertTrue(asInt(catalog.get("permission_count")) > 0);
        assertTrue(asInt(catalog.get("profile_count")) > 0);
        boolean foundApiCredentialsManage = false;
        for (Object row : asList(catalog.get("permissions"))) {
            if (!(row instanceof Map<?, ?> map)) continue;
            String key = safe(String.valueOf(map.get("key")));
            if ("api.credentials.manage".equals(key)) {
                foundApiCredentialsManage = true;
                break;
            }
        }
        assertTrue(foundApiCredentialsManage);

        assertEquals("matters.list", resolveOperation("/v1/op/matters/list", null, new LinkedHashMap<String, Object>()));
        assertEquals("matters.list", resolveOperation("/v1/matters/list", null, new LinkedHashMap<String, Object>()));
        assertEquals("tasks.list", resolveOperation("/v1/execute", requestWithParams(Map.of("operation", "tasks.list")), new LinkedHashMap<String, Object>()));
        assertEquals(
                "facts.tree.get",
                resolveOperation(
                        "/v1/execute",
                        requestWithParams(Map.of()),
                        new LinkedHashMap<String, Object>(Map.of("operation", "facts.tree.get"))
                )
        );
    }

    @Test
    void conflicts_operations_require_conflict_permissions() throws Exception {
        String tenant = "tenant-api-conflicts-" + UUID.randomUUID();
        cleanupRoots.add(Paths.get("data", "tenants", tenant).toAbsolutePath());
        matters.MatterRec matter = matters.defaultStore().create(tenant, "Conflict API Matter", "", "", "", "", "");

        assertThrows(SecurityException.class, () -> invokeApiWithScope(
                "conflicts.list",
                Map.of("matter_uuid", matter.uuid),
                tenant,
                "permissions:cases.access"
        ));

        LinkedHashMap<String, Object> listed = invokeApiWithScope(
                "conflicts.list",
                Map.of("matter_uuid", matter.uuid),
                tenant,
                "permissions:conflicts.access"
        );
        assertTrue(listed.containsKey("count"));

        assertThrows(SecurityException.class, () -> invokeApiWithScope(
                "conflicts.list",
                Map.of(
                        "matter_uuid", matter.uuid,
                        "refresh", true
                ),
                tenant,
                "permissions:conflicts.access"
        ));

        assertThrows(SecurityException.class, () -> invokeApiWithScope(
                "conflicts.upsert",
                Map.of(
                        "matter_uuid", matter.uuid,
                        "entity_type", "person",
                        "display_name", "Manual Conflict Name"
                ),
                tenant,
                "permissions:conflicts.access"
        ));

        LinkedHashMap<String, Object> upserted = invokeApiWithScope(
                "conflicts.upsert",
                Map.of(
                        "matter_uuid", matter.uuid,
                        "entity_type", "person",
                        "display_name", "Manual Conflict Name"
                ),
                tenant,
                "permissions:conflicts.manage"
        );
        assertFalse(nestedString(upserted, "entry", "entry_uuid").isBlank());
    }

    @Test
    void search_enqueue_enforces_selected_search_type_permission() throws Exception {
        String tenant = "tenant-api-search-type-scope-" + UUID.randomUUID();
        cleanupRoots.add(Paths.get("data", "tenants", tenant).toAbsolutePath());

        assertThrows(SecurityException.class, () -> invokeApiWithScope(
                "search.jobs.enqueue",
                Map.of(
                        "search_type", "case_conflicts",
                        "query", "acme",
                        "operator", "contains",
                        "include_metadata", true,
                        "include_ocr", true
                ),
                tenant,
                "permissions:documents.access"
        ));

        LinkedHashMap<String, Object> queued = invokeApiWithScope(
                "search.jobs.enqueue",
                Map.of(
                        "search_type", "case_conflicts",
                        "query", "acme",
                        "operator", "contains",
                        "include_metadata", true,
                        "include_ocr", true
                ),
                tenant,
                "permissions:conflicts.access"
        );
        assertNotNull(queued.get("job_id"));
        assertFalse(safe(String.valueOf(queued.get("job_id"))).isBlank());
    }

    @SuppressWarnings("unchecked")
    private static LinkedHashMap<String, Object> invokeApiWithScope(String operation,
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

    private static String resolveOperation(String path, HttpServletRequest req, Map<String, Object> payload) throws Exception {
        Method m = api_servlet.class.getDeclaredMethod(
                "resolveOperation",
                String.class,
                HttpServletRequest.class,
                Map.class
        );
        m.setAccessible(true);
        Object out = m.invoke(null, path, req, payload);
        return safe(String.valueOf(out));
    }

    private static HttpServletRequest requestWithParams(Map<String, String> params) {
        final LinkedHashMap<String, String> safeParams = new LinkedHashMap<String, String>();
        if (params != null) safeParams.putAll(params);

        return (HttpServletRequest) Proxy.newProxyInstance(
                api_permissions_scope_test.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class},
                (proxy, method, args) -> {
                    String name = method == null ? "" : safe(method.getName());
                    if ("getParameter".equals(name)) {
                        String key = (args == null || args.length == 0) ? "" : safe(String.valueOf(args[0]));
                        return safeParams.get(key);
                    }
                    if ("getParameterMap".equals(name)) {
                        LinkedHashMap<String, String[]> out = new LinkedHashMap<String, String[]>();
                        for (Map.Entry<String, String> e : safeParams.entrySet()) {
                            if (e == null) continue;
                            String k = safe(e.getKey());
                            if (k.isBlank()) continue;
                            out.put(k, new String[]{safe(e.getValue())});
                        }
                        return out;
                    }

                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    if (rt == long.class) return 0L;
                    if (rt == short.class) return (short) 0;
                    if (rt == byte.class) return (byte) 0;
                    if (rt == float.class) return 0f;
                    if (rt == double.class) return 0d;
                    if (rt == char.class) return (char) 0;
                    return null;
                }
        );
    }

    private static String nestedString(Map<String, Object> root, String key, String nestedKey) {
        if (root == null) return "";
        Object obj = root.get(key);
        if (!(obj instanceof Map<?, ?> map)) return "";
        return safe(String.valueOf(map.get(nestedKey)));
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object raw) {
        if (raw instanceof List<?> xs) return (List<Object>) xs;
        return new ArrayList<Object>();
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
