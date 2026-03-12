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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class users_roles_billing_profile_test {

    @Test
    void billing_initials_and_default_hourly_rate_roundtrip_and_enforce_uniqueness() throws Exception {
        ensurePepper();

        String tenantUuid = "tenant-users-billing-" + UUID.randomUUID();
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
                    "timekeeper@example.com",
                    roleUuid,
                    true,
                    "StrongPass#123".toCharArray(),
                    "a1b",
                    27500L,
                    false
            );
            assertNotNull(user);
            assertEquals("A1B", user.billingInitials);
            assertEquals(27500L, user.defaultHourlyRateCents);

            users_roles.UserRec loaded = store.getUserByUuid(tenantUuid, user.uuid);
            assertNotNull(loaded);
            assertEquals("A1B", loaded.billingInitials);
            assertEquals(27500L, loaded.defaultHourlyRateCents);

            assertTrue(store.updateUserBillingInitials(tenantUuid, user.uuid, "xy9"));
            assertTrue(store.updateUserDefaultHourlyRateCents(tenantUuid, user.uuid, 31000L));

            users_roles.UserRec updated = store.getUserByUuid(tenantUuid, user.uuid);
            assertNotNull(updated);
            assertEquals("XY9", updated.billingInitials);
            assertEquals(31000L, updated.defaultHourlyRateCents);

            Path usersXml = Paths.get("data", "tenants", tenantUuid, "users.xml").toAbsolutePath();
            String xml = Files.readString(usersXml, StandardCharsets.UTF_8);
            assertTrue(xml.contains("<billing_initials>XY9</billing_initials>"));
            assertTrue(xml.contains("<default_hourly_rate_cents>31000</default_hourly_rate_cents>"));

            assertThrows(IllegalStateException.class, () -> store.createUser(
                    tenantUuid,
                    "duplicate@example.com",
                    roleUuid,
                    true,
                    "AnotherStrong#123".toCharArray(),
                    "xy9",
                    15000L,
                    false
            ));
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
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }
}
