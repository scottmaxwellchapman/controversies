package net.familylawandprobate.controversies;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class users_roles_two_factor_test {

    @Test
    void update_user_two_factor_settings_roundtrip_persists_to_xml() throws Exception {
        ensurePepper();

        String tenantUuid = "tenant-2fa-users-" + UUID.randomUUID();
        Path tenantRoot = Paths.get("data", "tenants", tenantUuid).toAbsolutePath();
        deleteQuietly(tenantRoot);

        try {
            users_roles store = users_roles.defaultStore();
            store.ensure(tenantUuid);

            List<users_roles.RoleRec> roles = store.listRoles(tenantUuid);
            assertTrue(!roles.isEmpty());
            String roleUuid = roles.get(0).uuid;

            users_roles.UserRec user = store.createUser(
                    tenantUuid,
                    "person@example.com",
                    roleUuid,
                    true,
                    "StrongPass#123".toCharArray()
            );
            assertNotNull(user);

            boolean changed = store.updateUserTwoFactorSettings(
                    tenantUuid,
                    user.uuid,
                    true,
                    "flowroute_sms",
                    "+1 (206) 555-0199"
            );
            assertTrue(changed);

            users_roles.UserRec updated = store.getUserByUuid(tenantUuid, user.uuid);
            assertNotNull(updated);
            assertTrue(updated.twoFactorEnabled);
            assertEquals("flowroute_sms", updated.twoFactorEngine);
            assertEquals("+12065550199", updated.twoFactorPhone);

            Path usersPath = Paths.get("data", "tenants", tenantUuid, "users.xml").toAbsolutePath();
            String xml = Files.readString(usersPath, StandardCharsets.UTF_8);
            assertTrue(xml.contains("<two_factor_enabled>true</two_factor_enabled>"));
            assertTrue(xml.contains("<two_factor_engine>flowroute_sms</two_factor_engine>"));
            assertTrue(xml.contains("<two_factor_phone>+12065550199</two_factor_phone>"));
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
