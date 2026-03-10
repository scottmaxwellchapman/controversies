package net.familylawandprobate.controversies;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class postal_mail_test {

    @Test
    void inbound_mail_flow_supports_review_upload_parts_recipients_and_tracking() throws Exception {
        String tenantUuid = "tenant-mail-inbound-" + UUID.randomUUID();
        Path tenantRoot = Paths.get("data", "tenants", tenantUuid).toAbsolutePath();
        deleteQuietly(tenantRoot);

        try {
            matters.MatterRec matter = matters.defaultStore().create(
                    tenantUuid,
                    "Mail Intake Matter",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    ""
            );

            postal_mail store = postal_mail.defaultStore();
            postal_mail.MailItemRec created = store.createItem(
                    tenantUuid,
                    matter.uuid,
                    "inbound",
                    "manual_scan",
                    "usps",
                    "received",
                    "Certified letter intake",
                    "Mailroom scan",
                    "",
                    "",
                    "",
                    "",
                    "mailroom@example.test"
            );

            assertFalse(created.uuid.isBlank());
            assertEquals(postal_mail.DIRECTION_INBOUND, created.direction);
            assertEquals(postal_mail.WORKFLOW_MANUAL_SCAN, created.workflow);

            postal_mail.RecipientRec recipient = new postal_mail.RecipientRec();
            recipient.displayName = "John Recipient";
            recipient.addressLine1 = "123 Main St";
            recipient.city = "Dallas";
            recipient.state = "TX";
            recipient.postalCode = "75001";
            recipient.country = "US";

            postal_mail.RecipientRec addedRecipient = store.addRecipient(
                    tenantUuid,
                    created.uuid,
                    recipient,
                    false
            );
            assertTrue(addedRecipient.validated);
            assertEquals("75001", addedRecipient.postalCode);

            postal_mail.MailPartRec envelope = store.addPart(
                    tenantUuid,
                    created.uuid,
                    "envelope",
                    "Scanned envelope",
                    "doc-envelope",
                    "part-envelope",
                    "version-envelope",
                    "Front and back envelope scan",
                    "mailroom@example.test"
            );
            assertFalse(envelope.uuid.isBlank());
            assertEquals(postal_mail.PART_ENVELOPE, envelope.partType);

            assertTrue(store.markReviewed(
                    tenantUuid,
                    created.uuid,
                    "reviewer@example.test",
                    "Reviewed and accepted."
            ));

            assertTrue(store.linkFiledDocument(
                    tenantUuid,
                    created.uuid,
                    "doc-filed-1",
                    "part-filed-1",
                    "ver-filed-1"
            ));

            assertTrue(store.updateTrackingSummary(
                    tenantUuid,
                    created.uuid,
                    "usps",
                    "9400100000000000000000",
                    "accepted",
                    true
            ));

            postal_mail.TrackingEventRec event = store.addTrackingEvent(
                    tenantUuid,
                    created.uuid,
                    "usps",
                    "9400100000000000000000",
                    "in_transit",
                    "Dallas, TX",
                    "",
                    "Routing update",
                    "manual"
            );
            assertFalse(event.uuid.isBlank());

            postal_mail.MailItemRec refreshed = store.getItem(tenantUuid, created.uuid);
            assertEquals(postal_mail.STATUS_UPLOADED, refreshed.status);
            assertEquals("9400100000000000000000", refreshed.trackingNumber);
            assertEquals(postal_mail.ADDRESS_STATUS_VALID, refreshed.addressValidationStatus);

            List<postal_mail.TrackingEventRec> tracking = store.listTrackingEvents(tenantUuid, created.uuid);
            assertEquals(1, tracking.size());
            assertEquals("in_transit", tracking.get(0).status);
        } finally {
            deleteQuietly(tenantRoot);
        }
    }

    @Test
    void invalid_recipient_can_be_blocked_or_allowed_and_tracking_validation_is_available() throws Exception {
        String tenantUuid = "tenant-mail-validate-" + UUID.randomUUID();
        Path tenantRoot = Paths.get("data", "tenants", tenantUuid).toAbsolutePath();
        deleteQuietly(tenantRoot);

        try {
            matters.MatterRec matter = matters.defaultStore().create(
                    tenantUuid,
                    "Mail Outbound Matter",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    ""
            );

            postal_mail store = postal_mail.defaultStore();
            postal_mail.MailItemRec created = store.createItem(
                    tenantUuid,
                    matter.uuid,
                    "outbound",
                    "click2mail",
                    "usps",
                    "ready_to_send",
                    "Serve opposing counsel",
                    "",
                    "",
                    "doc-outbound-1",
                    "part-outbound-1",
                    "ver-outbound-1",
                    "assistant@example.test"
            );

            postal_mail.RecipientRec bad = new postal_mail.RecipientRec();
            bad.displayName = "Incomplete Recipient";
            bad.city = "Austin";
            bad.state = "TX";
            bad.postalCode = "78701";
            bad.country = "US";

            assertThrows(
                    IllegalArgumentException.class,
                    () -> store.addRecipient(tenantUuid, created.uuid, bad, false)
            );

            postal_mail.RecipientRec accepted = store.addRecipient(tenantUuid, created.uuid, bad, true);
            assertFalse(accepted.validated);

            postal_mail.MailItemRec refreshed = store.getItem(tenantUuid, created.uuid);
            assertEquals(postal_mail.ADDRESS_STATUS_INVALID, refreshed.addressValidationStatus);

            postal_mail.TrackingValidationResult fedex = postal_mail.validateTrackingNumber("123456789012");
            assertTrue(fedex.valid);
            assertEquals("fedex", fedex.carrierHint);

            postal_mail.TrackingValidationResult custom = postal_mail.validateTrackingNumber("DHL1234");
            assertTrue(custom.valid);
            assertEquals("", custom.carrierHint);

            postal_mail.TrackingValidationResult invalid = postal_mail.validateTrackingNumber("abc");
            assertFalse(invalid.valid);
        } finally {
            deleteQuietly(tenantRoot);
        }
    }

    @Test
    void custom_workflow_service_carrier_and_status_are_preserved_for_future_apis() throws Exception {
        String tenantUuid = "tenant-mail-custom-provider-" + UUID.randomUUID();
        Path tenantRoot = Paths.get("data", "tenants", tenantUuid).toAbsolutePath();
        deleteQuietly(tenantRoot);

        try {
            matters.MatterRec matter = matters.defaultStore().create(
                    tenantUuid,
                    "Custom Carrier Matter",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    ""
            );

            postal_mail store = postal_mail.defaultStore();
            postal_mail.MailItemRec created = store.createItem(
                    tenantUuid,
                    matter.uuid,
                    "outbound",
                    "dhl_api",
                    "dhl_express",
                    "label_created",
                    "Send package via custom API",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "assistant@example.test"
            );

            assertEquals("dhl_api", created.workflow);
            assertEquals("dhl_express", created.service);
            assertEquals("label_created", created.status);

            assertTrue(store.updateTrackingSummary(
                    tenantUuid,
                    created.uuid,
                    "dhl",
                    "DHL1234567890",
                    "out_for_delivery",
                    true
            ));

            postal_mail.MailPartRec customProof = store.addPart(
                    tenantUuid,
                    created.uuid,
                    "manifest_sheet",
                    "Custom manifest",
                    "doc-manifest",
                    "part-manifest",
                    "version-manifest",
                    "",
                    "assistant@example.test"
            );

            assertEquals("manifest_sheet", customProof.partType);

            postal_mail.MailItemRec refreshed = store.getItem(tenantUuid, created.uuid);
            assertEquals("dhl", refreshed.trackingCarrier);
            assertEquals("DHL1234567890", refreshed.trackingNumber);
            assertEquals("out_for_delivery", refreshed.trackingStatus);
            assertEquals("label_created", refreshed.status);
        } finally {
            deleteQuietly(tenantRoot);
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
