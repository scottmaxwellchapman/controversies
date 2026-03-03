package net.familylawandprobate.controversies;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class password_reset_tokens_test {

    @Test
    void issue_and_consume_token_once() throws Exception {
        String tenantUuid = "tenant-reset-" + UUID.randomUUID();
        Path tenantRoot = Paths.get("data", "tenants", tenantUuid).toAbsolutePath();
        deleteQuietly(tenantRoot);

        try {
            password_reset_tokens store = password_reset_tokens.defaultStore();

            password_reset_tokens.IssueResult issued = store.issue(
                    tenantUuid,
                    "user-1",
                    "user1@example.com",
                    "127.0.0.1",
                    Duration.ofMinutes(20)
            );

            assertFalse(issued.token.isBlank());
            assertFalse(issued.expiresAt.isBlank());

            password_reset_tokens.ConsumeResult first = store.consume(tenantUuid, issued.token, "127.0.0.1");
            assertTrue(first.ok);
            assertEquals("user-1", first.userUuid);
            assertEquals("user1@example.com", first.emailAddress);

            password_reset_tokens.ConsumeResult second = store.consume(tenantUuid, issued.token, "127.0.0.1");
            assertFalse(second.ok);
            assertEquals("consumed", second.reason);
        } finally {
            deleteQuietly(tenantRoot);
        }
    }

    @Test
    void new_token_supersedes_previous_active_token_for_user() throws Exception {
        String tenantUuid = "tenant-reset-" + UUID.randomUUID();
        Path tenantRoot = Paths.get("data", "tenants", tenantUuid).toAbsolutePath();
        deleteQuietly(tenantRoot);

        try {
            password_reset_tokens store = password_reset_tokens.defaultStore();

            password_reset_tokens.IssueResult first = store.issue(
                    tenantUuid,
                    "user-2",
                    "user2@example.com",
                    "127.0.0.1",
                    Duration.ofMinutes(20)
            );
            password_reset_tokens.IssueResult second = store.issue(
                    tenantUuid,
                    "user-2",
                    "user2@example.com",
                    "127.0.0.1",
                    Duration.ofMinutes(20)
            );

            password_reset_tokens.ConsumeResult superseded = store.consume(tenantUuid, first.token, "127.0.0.1");
            assertFalse(superseded.ok);
            assertEquals("consumed", superseded.reason);

            password_reset_tokens.ConsumeResult latest = store.consume(tenantUuid, second.token, "127.0.0.1");
            assertTrue(latest.ok);
            assertEquals("user-2", latest.userUuid);
        } finally {
            deleteQuietly(tenantRoot);
        }
    }

    @Test
    void expired_token_is_rejected() throws Exception {
        String tenantUuid = "tenant-reset-" + UUID.randomUUID();
        Path tenantRoot = Paths.get("data", "tenants", tenantUuid).toAbsolutePath();
        deleteQuietly(tenantRoot);

        try {
            password_reset_tokens store = password_reset_tokens.defaultStore();

            password_reset_tokens.IssueResult issued = store.issue(
                    tenantUuid,
                    "user-3",
                    "user3@example.com",
                    "127.0.0.1",
                    Duration.ofMillis(1)
            );

            Thread.sleep(15L);

            password_reset_tokens.ConsumeResult consumed = store.consume(tenantUuid, issued.token, "127.0.0.1");
            assertFalse(consumed.ok);
            assertEquals("expired", consumed.reason);
        } finally {
            deleteQuietly(tenantRoot);
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
