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

public class email_change_tokens_test {

    @Test
    void issue_and_consume_for_matching_user_and_email() throws Exception {
        String tenantUuid = "tenant-email-change-" + UUID.randomUUID();
        Path tenantRoot = Paths.get("data", "tenants", tenantUuid).toAbsolutePath();
        deleteQuietly(tenantRoot);

        try {
            email_change_tokens store = email_change_tokens.defaultStore();
            email_change_tokens.IssueResult issued = store.issue(
                    tenantUuid,
                    "user-1",
                    "new-email@example.com",
                    "127.0.0.1",
                    Duration.ofMinutes(20)
            );

            assertFalse(issued.token.isBlank());
            assertEquals("new-email@example.com", issued.newEmailAddress);

            email_change_tokens.ConsumeResult consumed = store.consume(
                    tenantUuid,
                    "user-1",
                    "new-email@example.com",
                    issued.token,
                    "127.0.0.1"
            );
            assertTrue(consumed.ok);
            assertEquals("user-1", consumed.userUuid);
            assertEquals("new-email@example.com", consumed.newEmailAddress);

            email_change_tokens.ConsumeResult consumedAgain = store.consume(
                    tenantUuid,
                    "user-1",
                    "new-email@example.com",
                    issued.token,
                    "127.0.0.1"
            );
            assertFalse(consumedAgain.ok);
            assertEquals("consumed", consumedAgain.reason);
        } finally {
            deleteQuietly(tenantRoot);
        }
    }

    @Test
    void consume_rejects_wrong_email_or_user() throws Exception {
        String tenantUuid = "tenant-email-change-" + UUID.randomUUID();
        Path tenantRoot = Paths.get("data", "tenants", tenantUuid).toAbsolutePath();
        deleteQuietly(tenantRoot);

        try {
            email_change_tokens store = email_change_tokens.defaultStore();
            email_change_tokens.IssueResult issued = store.issue(
                    tenantUuid,
                    "user-2",
                    "next@example.com",
                    "127.0.0.1",
                    Duration.ofMinutes(20)
            );

            email_change_tokens.ConsumeResult wrongEmail = store.consume(
                    tenantUuid,
                    "user-2",
                    "different@example.com",
                    issued.token,
                    "127.0.0.1"
            );
            assertFalse(wrongEmail.ok);
            assertEquals("invalid", wrongEmail.reason);

            email_change_tokens.ConsumeResult wrongUser = store.consume(
                    tenantUuid,
                    "user-x",
                    "next@example.com",
                    issued.token,
                    "127.0.0.1"
            );
            assertFalse(wrongUser.ok);
            assertEquals("invalid", wrongUser.reason);
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
