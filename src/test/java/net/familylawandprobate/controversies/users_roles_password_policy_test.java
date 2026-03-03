package net.familylawandprobate.controversies;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class users_roles_password_policy_test {

    @Test
    void policy_enforced_by_default_but_admin_can_bypass() throws Exception {
        ensurePepper();

        String tenantUuid = "tenant-policy-" + UUID.randomUUID();
        Path tenantRoot = Paths.get("data", "tenants", tenantUuid).toAbsolutePath();
        deleteQuietly(tenantRoot);

        try {
            tenant_settings.defaultStore().write(tenantUuid, Map.of(
                    "password_policy_enabled", "true",
                    "password_policy_min_length", "12",
                    "password_policy_require_uppercase", "true",
                    "password_policy_require_lowercase", "true",
                    "password_policy_require_number", "true",
                    "password_policy_require_symbol", "true"
            ));

            users_roles users = users_roles.defaultStore();
            users.ensure(tenantUuid);

            List<users_roles.RoleRec> roles = users.listRoles(tenantUuid);
            assertTrue(!roles.isEmpty());
            String roleUuid = roles.get(0).uuid;

            IllegalArgumentException createBlocked = assertThrows(
                    IllegalArgumentException.class,
                    () -> users.createUser(tenantUuid, "blocked@example.com", roleUuid, true, "weakpass".toCharArray())
            );
            assertTrue(createBlocked.getMessage().toLowerCase().contains("password policy"));

            users_roles.UserRec strongUser = users.createUser(
                    tenantUuid,
                    "strong@example.com",
                    roleUuid,
                    true,
                    "StrongPass#123".toCharArray()
            );
            assertNotNull(strongUser);
            assertEquals("strong@example.com", strongUser.emailAddress);

            IllegalArgumentException updateBlocked = assertThrows(
                    IllegalArgumentException.class,
                    () -> users.updateUserPassword(tenantUuid, strongUser.uuid, "weakpass".toCharArray())
            );
            assertTrue(updateBlocked.getMessage().toLowerCase().contains("password policy"));

            boolean bypassUpdated = users.updateUserPassword(
                    tenantUuid,
                    strongUser.uuid,
                    "weakpass".toCharArray(),
                    true
            );
            assertTrue(bypassUpdated);

            users_roles.UserRec bypassUser = users.createUser(
                    tenantUuid,
                    "bypass@example.com",
                    roleUuid,
                    true,
                    "short".toCharArray(),
                    true
            );
            assertNotNull(bypassUser);
            assertEquals("bypass@example.com", bypassUser.emailAddress);
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
