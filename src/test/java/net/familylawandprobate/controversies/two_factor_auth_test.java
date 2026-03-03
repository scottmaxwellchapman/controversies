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
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class two_factor_auth_test {

    @Test
    void email_pin_challenge_can_be_started_and_verified() throws Exception {
        ensurePepper();

        String tenantUuid = "tenant-2fa-auth-" + UUID.randomUUID();
        Path tenantRoot = Paths.get("data", "tenants", tenantUuid).toAbsolutePath();
        deleteQuietly(tenantRoot);

        try {
            tenant_settings settingsStore = tenant_settings.defaultStore();
            settingsStore.write(tenantUuid, Map.of(
                    "two_factor_policy", "required",
                    "two_factor_default_engine", "email_pin",
                    "email_provider", "smtp",
                    "email_from_address", "notify@example.com",
                    "email_smtp_host", "smtp.example.test",
                    "email_smtp_port", "587",
                    "email_smtp_auth", "false"
            ));

            users_roles users = users_roles.defaultStore();
            users.ensure(tenantUuid);
            List<users_roles.RoleRec> roles = users.listRoles(tenantUuid);
            String roleUuid = roles.get(0).uuid;
            users_roles.UserRec created = users.createUser(
                    tenantUuid,
                    "user@example.com",
                    roleUuid,
                    true,
                    "StrongPass#123".toCharArray()
            );
            users_roles.UserRec user = users.getUserByUuid(tenantUuid, created.uuid);
            assertNotNull(user);

            AtomicReference<String> sentCode = new AtomicReference<String>("");
            two_factor_auth.EmailSender emailSender = (tu, requestedBy, toAddress, subject, bodyText) -> {
                Matcher m = Pattern.compile("(\\d{6})").matcher(bodyText);
                if (m.find()) sentCode.set(m.group(1));
            };
            two_factor_auth.FlowrouteSender flowrouteSender = (endpoint, accessKey, secretKey, fromNumber, toNumber, body, connectTimeoutMs, readTimeoutMs) -> {
            };

            two_factor_auth tfa = new two_factor_auth(
                    settingsStore,
                    notification_emails.defaultStore(),
                    activity_log.defaultStore(),
                    emailSender,
                    flowrouteSender
            );

            two_factor_auth.Requirement req = tfa.resolveRequirement(tenantUuid, user);
            assertTrue(req.required);
            assertEquals("email_pin", req.engine);
            assertEquals("", req.issue);

            two_factor_auth.ChallengeStartResult start = tfa.startChallenge(tenantUuid, user, user.uuid, "127.0.0.1");
            assertTrue(start.success);
            assertTrue(start.required);
            assertFalse(start.challengeId.isBlank());
            assertEquals("email_pin", start.engine);
            assertFalse(sentCode.get().isBlank());

            two_factor_auth.VerifyResult bad = tfa.verifyChallenge(tenantUuid, user.uuid, start.challengeId, "000000", "127.0.0.1");
            assertFalse(bad.success);
            assertTrue(bad.remainingAttempts > 0);

            two_factor_auth.VerifyResult ok = tfa.verifyChallenge(tenantUuid, user.uuid, start.challengeId, sentCode.get(), "127.0.0.1");
            assertTrue(ok.success);

            two_factor_auth.VerifyResult missing = tfa.verifyChallenge(tenantUuid, user.uuid, start.challengeId, sentCode.get(), "127.0.0.1");
            assertFalse(missing.success);
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
