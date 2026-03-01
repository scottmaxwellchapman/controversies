package net.familylawandprobate.controversies;

import net.familylawandprobate.controversies.storage.DocumentStorageBackend;
import net.familylawandprobate.controversies.storage.StorageBackendResolver;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class notification_emails_test {

    @Test
    void enqueue_persists_local_and_external_storage_attachments() throws Exception {
        String tenantUuid = "tenant-email-" + UUID.randomUUID();
        String matterUuid = "matter-email-" + UUID.randomUUID();
        Path tenantRoot = Paths.get("data", "tenants", tenantUuid).toAbsolutePath();
        deleteQuietly(tenantRoot);

        try {
            ensurePepper();

            tenant_settings settingsStore = tenant_settings.defaultStore();
            settingsStore.write(tenantUuid, Map.of(
                    "email_provider", "smtp",
                    "email_from_address", "notify@example.com",
                    "email_smtp_host", "smtp.example.test",
                    "email_smtp_port", "587",
                    "email_smtp_auth", "false",
                    "email_queue_max_attempts", "3"
            ));

            Path localAttachment = tenantRoot.resolve("tmp/local_note.txt");
            Files.createDirectories(localAttachment.getParent());
            Files.writeString(localAttachment, "local attachment", StandardCharsets.UTF_8);

            StorageBackendResolver resolver = new StorageBackendResolver();
            DocumentStorageBackend externalBackend = resolver.resolve(tenantUuid, matterUuid, "s3_compatible");
            String externalKey = externalBackend.put("matters/" + matterUuid + "/attachments/remote_note.txt", "external attachment".getBytes(StandardCharsets.UTF_8));

            notification_emails.Sender noopSmtp = (job, cfg, atts) -> new notification_emails.SendResult(250, "noop", "250 queued");
            notification_emails.Sender noopGraph = (job, cfg, atts) -> new notification_emails.SendResult(202, "", "202 accepted");
            notification_emails queue = new notification_emails(
                    settingsStore,
                    resolver,
                    activity_log.defaultStore(),
                    noopSmtp,
                    noopGraph,
                    false
            );

            notification_emails.NotificationEmailRequest req = new notification_emails.NotificationEmailRequest(
                    matterUuid,
                    "",
                    "",
                    "",
                    "Attachment Audit",
                    "Queued with attachments",
                    "",
                    List.of("recipient@example.com"),
                    List.of(),
                    List.of(),
                    List.of(
                            notification_emails.AttachmentSpec.localFile(localAttachment.toString(), "local_note.txt", "text/plain"),
                            notification_emails.AttachmentSpec.externalStorage("s3_compatible", externalKey, matterUuid, "remote_note.txt", "text/plain")
                    )
            );

            String emailUuid = queue.enqueue(tenantUuid, "user-1", req);
            Path queuePath = Paths.get("data", "tenants", tenantUuid, "sync", "notification_email_queue.xml").toAbsolutePath();
            assertTrue(Files.exists(queuePath));

            String xml = Files.readString(queuePath, StandardCharsets.UTF_8);
            assertTrue(xml.contains("<uuid>" + emailUuid + "</uuid>"));
            assertTrue(xml.contains("<subject>Attachment Audit</subject>"));
            assertTrue(xml.contains("<source_type>local_file</source_type>"));
            assertTrue(xml.contains("<source_type>external_storage</source_type>"));
            assertTrue(xml.contains("<result>queued</result>"));

            Path attachmentDir = Paths.get("data", "tenants", tenantUuid, "sync", "email_queue_attachments", emailUuid).toAbsolutePath();
            assertTrue(Files.isDirectory(attachmentDir));
            try (Stream<Path> files = Files.list(attachmentDir)) {
                assertEquals(2L, files.count());
            }
        } finally {
            deleteQuietly(tenantRoot);
        }
    }

    @Test
    void retry_now_persists_delivery_result_and_server_response_for_audit() throws Exception {
        String tenantUuid = "tenant-email-send-" + UUID.randomUUID();
        String matterUuid = "matter-email-send-" + UUID.randomUUID();
        Path tenantRoot = Paths.get("data", "tenants", tenantUuid).toAbsolutePath();
        deleteQuietly(tenantRoot);

        try {
            ensurePepper();

            tenant_settings settingsStore = tenant_settings.defaultStore();
            settingsStore.write(tenantUuid, Map.of(
                    "email_provider", "smtp",
                    "email_from_address", "notify@example.com",
                    "email_smtp_host", "smtp.example.test",
                    "email_smtp_port", "587",
                    "email_smtp_auth", "false",
                    "email_queue_max_attempts", "2"
            ));

            notification_emails.Sender successSmtp = (job, cfg, atts) ->
                    new notification_emails.SendResult(250, "smtp-message-id-1", "250 2.0.0 queued");
            notification_emails.Sender noopGraph = (job, cfg, atts) ->
                    new notification_emails.SendResult(202, "", "202 accepted");
            notification_emails queue = new notification_emails(
                    settingsStore,
                    new StorageBackendResolver(),
                    activity_log.defaultStore(),
                    successSmtp,
                    noopGraph,
                    false
            );

            String emailUuid = queue.enqueue(
                    tenantUuid,
                    "user-2",
                    new notification_emails.NotificationEmailRequest(
                            matterUuid,
                            "",
                            "",
                            "",
                            "Send Audit",
                            "Body",
                            "",
                            List.of("recipient@example.com"),
                            List.of(),
                            List.of(),
                            List.of()
                    )
            );

            assertTrue(queue.retryNow(tenantUuid, emailUuid));

            Path queuePath = Paths.get("data", "tenants", tenantUuid, "sync", "notification_email_queue.xml").toAbsolutePath();
            String xml = Files.readString(queuePath, StandardCharsets.UTF_8);
            assertTrue(xml.contains("<uuid>" + emailUuid + "</uuid>"));
            assertTrue(xml.contains("<state>sent</state>"));
            assertTrue(xml.contains("<result>ok</result>"));
            assertTrue(xml.contains("<provider_status_code>250</provider_status_code>"));
            assertTrue(xml.contains("<provider_message_id>smtp-message-id-1</provider_message_id>"));
            assertTrue(xml.contains("<server_response>250 2.0.0 queued</server_response>"));

            String day = Instant.now().toString().substring(0, 10);
            Path activityPath = Paths.get("data", "tenants", tenantUuid, "logs", "activity_" + day + ".xml").toAbsolutePath();
            assertTrue(Files.exists(activityPath));
            String activity = Files.readString(activityPath, StandardCharsets.UTF_8);
            assertTrue(activity.contains("notification_email.sent"));
            assertTrue(activity.contains(emailUuid));
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

