package net.familylawandprobate.controversies;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class omnichannel_tickets_test {

    @Test
    void round_robin_cycles_and_multi_assignment_history_is_tracked() throws Exception {
        String tenantUuid = "omni-round-robin-" + UUID.randomUUID();
        Path tenantDir = Paths.get("data", "tenants", tenantUuid).toAbsolutePath();
        deleteQuietly(tenantDir);

        try {
            omnichannel_tickets store = omnichannel_tickets.defaultStore();
            store.ensure(tenantUuid);

            List<String> candidates = List.of("user-a", "user-b", "user-c");
            String a1 = store.chooseRoundRobinAssignee(tenantUuid, "email_imap_smtp|none", candidates);
            String a2 = store.chooseRoundRobinAssignee(tenantUuid, "email_imap_smtp|none", candidates);
            String a3 = store.chooseRoundRobinAssignee(tenantUuid, "email_imap_smtp|none", candidates);
            String a4 = store.chooseRoundRobinAssignee(tenantUuid, "email_imap_smtp|none", candidates);

            assertEquals("user-a", a1);
            assertEquals("user-b", a2);
            assertEquals("user-c", a3);
            assertEquals("user-a", a4);

            omnichannel_tickets.TicketRec thread = store.createTicket(
                    tenantUuid,
                    "",
                    "email_imap_smtp",
                    "Multi-owner assignment",
                    "open",
                    "normal",
                    "manual",
                    "user-a,user-b",
                    "",
                    "",
                    "Client A",
                    "client.a@example.test",
                    "inbox@example.test",
                    "thread-100",
                    "conv-100",
                    "inbound",
                    "Initial inbound thread",
                    "client.a@example.test",
                    "inbox@example.test",
                    false,
                    "tester@example.test",
                    "Initial triage"
            );

            assertNotNull(thread);
            assertFalse(thread.uuid.isBlank());

            omnichannel_tickets.TicketRec update = new omnichannel_tickets.TicketRec();
            update.uuid = thread.uuid;
            update.matterUuid = thread.matterUuid;
            update.channel = thread.channel;
            update.subject = thread.subject;
            update.status = thread.status;
            update.priority = thread.priority;
            update.assignmentMode = "manual";
            update.assignedUserUuid = "user-b,user-c";
            update.reminderAt = thread.reminderAt;
            update.dueAt = thread.dueAt;
            update.customerDisplay = thread.customerDisplay;
            update.customerAddress = thread.customerAddress;
            update.mailboxAddress = thread.mailboxAddress;
            update.threadKey = thread.threadKey;
            update.externalConversationId = thread.externalConversationId;
            update.createdAt = thread.createdAt;
            update.updatedAt = thread.updatedAt;
            update.lastInboundAt = thread.lastInboundAt;
            update.lastOutboundAt = thread.lastOutboundAt;
            update.inboundCount = thread.inboundCount;
            update.outboundCount = thread.outboundCount;
            update.mmsEnabled = thread.mmsEnabled;
            update.archived = thread.archived;
            update.reportDocumentUuid = thread.reportDocumentUuid;
            update.reportPartUuid = thread.reportPartUuid;
            update.lastReportVersionUuid = thread.lastReportVersionUuid;

            boolean changed = store.updateTicket(tenantUuid, update, "tester@example.test", "Coverage balancing");
            assertTrue(changed);

            List<omnichannel_tickets.AssignmentRec> history = store.listAssignments(tenantUuid, thread.uuid);
            assertTrue(history.size() >= 2);
            boolean foundReassignment = false;
            for (omnichannel_tickets.AssignmentRec rec : history) {
                if (rec == null) continue;
                if ("user-a,user-b".equals(rec.fromUserUuid) && "user-b,user-c".equals(rec.toUserUuid)) {
                    foundReassignment = true;
                    break;
                }
            }
            assertTrue(foundReassignment);
        } finally {
            deleteQuietly(tenantDir);
        }
    }

    @Test
    void internal_messages_channel_is_preserved() throws Exception {
        String tenantUuid = "omni-internal-channel-" + UUID.randomUUID();
        Path tenantDir = Paths.get("data", "tenants", tenantUuid).toAbsolutePath();
        deleteQuietly(tenantDir);

        try {
            omnichannel_tickets store = omnichannel_tickets.defaultStore();
            store.ensure(tenantUuid);

            omnichannel_tickets.TicketRec thread = store.createTicket(
                    tenantUuid,
                    "",
                    "internal_messages",
                    "Internal team thread",
                    "open",
                    "normal",
                    "manual",
                    "user-a,user-b",
                    "",
                    "",
                    "Paralegal Team",
                    "team@internal.local",
                    "",
                    "",
                    "",
                    "internal",
                    "Initial user-to-user handoff",
                    "coordinator@internal.local",
                    "owner@internal.local",
                    false,
                    "tester@example.test",
                    "Internal collaboration assignment"
            );

            assertNotNull(thread);
            assertEquals("internal_messages", safe(thread.channel));

            omnichannel_tickets.TicketRec loaded = store.getTicket(tenantUuid, thread.uuid);
            assertNotNull(loaded);
            assertEquals("internal_messages", safe(loaded.channel));
        } finally {
            deleteQuietly(tenantDir);
        }
    }

    @Test
    void report_embeds_public_multimedia_and_hides_internal_note_content() throws Exception {
        String tenantUuid = "omni-report-" + UUID.randomUUID();
        Path tenantDir = Paths.get("data", "tenants", tenantUuid).toAbsolutePath();
        deleteQuietly(tenantDir);

        try {
            matters matterStore = matters.defaultStore();
            matterStore.ensure(tenantUuid);
            matters.MatterRec matter = matterStore.create(
                    tenantUuid,
                    "Report Matter",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    ""
            );

            omnichannel_tickets store = omnichannel_tickets.defaultStore();
            store.ensure(tenantUuid);

            omnichannel_tickets.TicketRec thread = store.createTicket(
                    tenantUuid,
                    matter.uuid,
                    "flowroute_sms",
                    "MMS thread with evidence",
                    "open",
                    "high",
                    "manual",
                    "owner-1,owner-2",
                    "",
                    "",
                    "Client B",
                    "+15554440000",
                    "+15559990000",
                    "sms-thread-77",
                    "flowroute-conv-77",
                    "inbound",
                    "Public intake message",
                    "+15554440000",
                    "+15559990000",
                    true,
                    "tester@example.test",
                    "Initial assignment"
            );

            assertNotNull(thread);
            String ticketUuid = thread.uuid;

            omnichannel_tickets.MessageRec outbound = store.addMessage(
                    tenantUuid,
                    ticketUuid,
                    "outbound",
                    "Public response body for the client.",
                    false,
                    "+15559990000",
                    "+15554440000",
                    "msg-public-1",
                    "",
                    "",
                    "",
                    "tester@example.test"
            );

            omnichannel_tickets.MessageRec internal = store.addMessage(
                    tenantUuid,
                    ticketUuid,
                    "internal",
                    "PRIVATE STRATEGY NOTE - DO NOT DISCLOSE",
                    false,
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "tester@example.test"
            );

            assertNotNull(outbound);
            assertNotNull(internal);

            byte[] publicBytes = "public-attachment-content".getBytes(StandardCharsets.UTF_8);
            byte[] internalBytes = "internal-attachment-content".getBytes(StandardCharsets.UTF_8);

            omnichannel_tickets.AttachmentRec publicAttachment = store.saveAttachment(
                    tenantUuid,
                    ticketUuid,
                    outbound.uuid,
                    "public_evidence.txt",
                    "text/plain",
                    publicBytes,
                    false,
                    "tester@example.test"
            );

            omnichannel_tickets.AttachmentRec internalAttachment = store.saveAttachment(
                    tenantUuid,
                    ticketUuid,
                    internal.uuid,
                    "internal_strategy.txt",
                    "text/plain",
                    internalBytes,
                    false,
                    "tester@example.test"
            );

            assertNotNull(publicAttachment);
            assertNotNull(internalAttachment);

            omnichannel_tickets.TicketRec refreshed = store.getTicket(tenantUuid, ticketUuid);
            assertNotNull(refreshed);
            assertFalse(safe(refreshed.reportDocumentUuid).isBlank());
            assertFalse(safe(refreshed.reportPartUuid).isBlank());
            assertFalse(safe(refreshed.lastReportVersionUuid).isBlank());

            List<part_versions.VersionRec> versions = part_versions.defaultStore().listAll(
                    tenantUuid,
                    matter.uuid,
                    refreshed.reportDocumentUuid,
                    refreshed.reportPartUuid
            );
            assertFalse(versions.isEmpty());

            Path reportPdf = Paths.get(safe(versions.get(0).storagePath)).toAbsolutePath().normalize();
            assertTrue(Files.exists(reportPdf));
            assertTrue(Files.size(reportPdf) > 0L);

            try (PDDocument pdf = PDDocument.load(reportPdf.toFile())) {
                PDDocumentNameDictionary names = new PDDocumentNameDictionary(pdf.getDocumentCatalog());
                PDEmbeddedFilesNameTreeNode embeddedTree = names.getEmbeddedFiles();
                assertNotNull(embeddedTree);

                Map<String, PDComplexFileSpecification> embeddedNames = embeddedTree.getNames();
                assertNotNull(embeddedNames);
                boolean publicEmbedded = false;
                boolean internalEmbedded = false;
                for (String key : embeddedNames.keySet()) {
                    String k = safe(key);
                    if (k.contains("public_evidence.txt")) publicEmbedded = true;
                    if (k.contains("internal_strategy.txt")) internalEmbedded = true;
                }
                assertTrue(publicEmbedded);
                assertFalse(internalEmbedded);

                PDFTextStripper stripper = new PDFTextStripper();
                String reportText = safe(stripper.getText(pdf));
                assertTrue(reportText.contains("Internal Notes Hidden From External Report: 1"));
                assertTrue(reportText.contains("Public response body for the client."));
                assertFalse(reportText.contains("PRIVATE STRATEGY NOTE - DO NOT DISCLOSE"));
            }
        } finally {
            deleteQuietly(tenantDir);
        }
    }

    private static void deleteQuietly(Path p) {
        try {
            if (p == null || !Files.exists(p)) return;
            try (var walk = Files.walk(p)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) {}
                });
            }
        } catch (Exception ignored) {
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
