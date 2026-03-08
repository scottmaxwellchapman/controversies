package net.familylawandprobate.controversies;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class permission_layers_test {

    @Test
    void user_overrides_group_and_group_overrides_tenant() throws Exception {
        ensurePepper();

        String tenantUuid = "tenant-perm-layers-" + UUID.randomUUID();
        Path tenantRoot = Paths.get("data", "tenants", tenantUuid).toAbsolutePath();
        deleteQuietly(tenantRoot);

        try {
            users_roles users = users_roles.defaultStore();
            permission_layers perms = permission_layers.defaultStore();

            users.ensure(tenantUuid);
            perms.ensure(tenantUuid);

            users_roles.RoleRec role = users.createRole(tenantUuid, "Case Worker", true);
            users_roles.UserRec user = users.createUser(
                    tenantUuid,
                    "layers@example.com",
                    role.uuid,
                    true,
                    "StrongPass#123".toCharArray()
            );

            assertTrue(perms.setTenantPermission(tenantUuid, "cases.access", "false"));

            permission_layers.GroupRec group = perms.createGroup(tenantUuid, "Litigation Team", true);
            assertTrue(perms.setGroupPermission(tenantUuid, group.uuid, "cases.access", "true"));
            assertTrue(perms.addUserToGroup(tenantUuid, group.uuid, user.uuid));

            // Group overrides tenant (tenant=false, group=true -> true)
            LinkedHashMap<String, String> resolvedA = perms.resolveEffectivePermissions(tenantUuid, user.uuid, role.permissions);
            assertEquals("true", resolvedA.get("cases.access"));

            // User overrides group (user=false -> false)
            assertTrue(perms.setUserPermission(tenantUuid, user.uuid, "cases.access", "false"));
            LinkedHashMap<String, String> resolvedB = perms.resolveEffectivePermissions(tenantUuid, user.uuid, role.permissions);
            assertEquals("false", resolvedB.get("cases.access"));

            // Remove user override: back to group=true
            assertTrue(perms.removeUserPermission(tenantUuid, user.uuid, "cases.access"));
            LinkedHashMap<String, String> resolvedC = perms.resolveEffectivePermissions(tenantUuid, user.uuid, role.permissions);
            assertEquals("true", resolvedC.get("cases.access"));

            // Remove group override: back to tenant=false
            assertTrue(perms.removeGroupPermission(tenantUuid, group.uuid, "cases.access"));
            LinkedHashMap<String, String> resolvedD = perms.resolveEffectivePermissions(tenantUuid, user.uuid, role.permissions);
            assertEquals("false", resolvedD.get("cases.access"));
        } finally {
            deleteQuietly(tenantRoot);
        }
    }

    @Test
    void legacy_defaults_apply_when_structured_permissions_absent() throws Exception {
        ensurePepper();

        String tenantUuid = "tenant-perm-legacy-" + UUID.randomUUID();
        Path tenantRoot = Paths.get("data", "tenants", tenantUuid).toAbsolutePath();
        deleteQuietly(tenantRoot);

        try {
            users_roles users = users_roles.defaultStore();
            permission_layers perms = permission_layers.defaultStore();

            users.ensure(tenantUuid);
            perms.ensure(tenantUuid);

            users_roles.RoleRec role = users.createRole(tenantUuid, "Legacy Role", true);
            users_roles.UserRec user = users.createUser(
                    tenantUuid,
                    "legacy@example.com",
                    role.uuid,
                    true,
                    "StrongPass#123".toCharArray()
            );

            LinkedHashMap<String, String> resolved = perms.resolveEffectivePermissions(tenantUuid, user.uuid, role.permissions);
            assertEquals("true", resolved.get("cases.access"));
            assertEquals("true", resolved.get("documents.access"));
            assertEquals("true", resolved.get("forms.access"));

            // Once a structured key is set, fallback no longer auto-grants that key.
            assertTrue(perms.setTenantPermission(tenantUuid, "cases.access", "false"));
            LinkedHashMap<String, String> resolved2 = perms.resolveEffectivePermissions(tenantUuid, user.uuid, role.permissions);
            assertEquals("false", resolved2.get("cases.access"));
        } finally {
            deleteQuietly(tenantRoot);
        }
    }

    @Test
    void tenant_admin_flag_is_preserved_and_custom_object_keys_are_generated() throws Exception {
        ensurePepper();

        String tenantUuid = "tenant-perm-admin-" + UUID.randomUUID();
        Path tenantRoot = Paths.get("data", "tenants", tenantUuid).toAbsolutePath();
        deleteQuietly(tenantRoot);

        try {
            users_roles users = users_roles.defaultStore();
            permission_layers perms = permission_layers.defaultStore();

            users.ensure(tenantUuid);
            perms.ensure(tenantUuid);

            users_roles.RoleRec adminRole = users.createRole(tenantUuid, "Ops Admin", true);
            assertTrue(users.setRolePermission(tenantUuid, adminRole.uuid, "tenant_admin", "true"));

            users_roles.UserRec adminUser = users.createUser(
                    tenantUuid,
                    "admin2@example.com",
                    adminRole.uuid,
                    true,
                    "StrongPass#123".toCharArray()
            );

            LinkedHashMap<String, String> resolved = perms.resolveEffectivePermissions(tenantUuid, adminUser.uuid, Map.of("tenant_admin", "true"));
            assertEquals("true", resolved.get("tenant_admin"));

            String key = permission_layers.customObjectPermissionKey("Billing Entry", "edit");
            assertEquals("custom_object.billing_entry.edit", key);

            List<String> keys = permission_layers.customObjectPermissionKeys("billing_entry", "access");
            assertFalse(keys.isEmpty());
            assertTrue(keys.contains("custom_object.billing_entry.access"));
            assertTrue(keys.contains("custom_objects.records.access"));
        } finally {
            deleteQuietly(tenantRoot);
        }
    }

    private static void ensurePepper() throws Exception {
        Path pepper = Paths.get("data", "sec", "random_pepper.bin").toAbsolutePath();
        Files.createDirectories(pepper.getParent());
        if (!Files.exists(pepper)) {
            Files.writeString(pepper, "test-pepper-material", StandardCharsets.UTF_8);
        }
    }

    private static void deleteQuietly(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {
        }
    }
}
