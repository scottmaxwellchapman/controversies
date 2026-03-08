package net.familylawandprobate.controversies;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

public class users_roles_bootstrap_security_test {

    @Test
    void bootstrap_uses_configured_password_for_tenant_admin() throws Exception {
        ensurePepper();

        String previous = System.getProperty("CONTROVERSIES_BOOTSTRAP_PASSWORD");
        String configured = "Bootstrap#Test-" + UUID.randomUUID();
        String tenantUuid = "tenant-bootstrap-secured-" + UUID.randomUUID();
        Path tenantRoot = Paths.get("data", "tenants", tenantUuid).toAbsolutePath();
        deleteQuietly(tenantRoot);

        System.setProperty("CONTROVERSIES_BOOTSTRAP_PASSWORD", configured);
        try {
            users_roles store = users_roles.defaultStore();
            store.ensure(tenantUuid);

            users_roles.AuthResult ok = store.authenticate(tenantUuid, "tenant_admin", configured.toCharArray());
            assertNotNull(ok);

            users_roles.AuthResult bad = store.authenticate(tenantUuid, "tenant_admin", "password".toCharArray());
            assertNull(bad);
        } finally {
            if (previous == null) System.clearProperty("CONTROVERSIES_BOOTSTRAP_PASSWORD");
            else System.setProperty("CONTROVERSIES_BOOTSTRAP_PASSWORD", previous);
            deleteQuietly(tenantRoot);
        }
    }

    @Test
    void bootstrap_no_longer_accepts_legacy_password_default() throws Exception {
        ensurePepper();

        String previous = System.getProperty("CONTROVERSIES_BOOTSTRAP_PASSWORD");
        String tenantUuid = "tenant-bootstrap-random-" + UUID.randomUUID();
        Path tenantRoot = Paths.get("data", "tenants", tenantUuid).toAbsolutePath();
        deleteQuietly(tenantRoot);

        System.clearProperty("CONTROVERSIES_BOOTSTRAP_PASSWORD");
        try {
            users_roles store = users_roles.defaultStore();
            store.ensure(tenantUuid);
            users_roles.AuthResult bad = store.authenticate(tenantUuid, "tenant_admin", "password".toCharArray());
            assertNull(bad);
        } finally {
            if (previous == null) System.clearProperty("CONTROVERSIES_BOOTSTRAP_PASSWORD");
            else System.setProperty("CONTROVERSIES_BOOTSTRAP_PASSWORD", previous);
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
