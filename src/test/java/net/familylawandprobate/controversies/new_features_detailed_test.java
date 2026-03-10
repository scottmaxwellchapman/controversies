package net.familylawandprobate.controversies;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class new_features_detailed_test {

    private final ArrayList<String> tenantIds = new ArrayList<String>();

    @AfterEach
    void cleanup() throws Exception {
        for (String tenant : tenantIds) {
            billing_runtime_registry.clearTenant(tenant);
            deleteRecursively(Paths.get("data", "tenants", tenant).toAbsolutePath());
        }
    }

    @Test
    void online_checkout_handles_null_currency_input() throws Exception {
        String tenant = "tenant-online-payments-null-currency-" + UUID.randomUUID();
        tenantIds.add(tenant);

        billing_accounting ledger = billing_runtime_registry.tenantLedger(tenant);
        String matterUuid = "matter-" + UUID.randomUUID();
        ledger.createTimeEntry(
                matterUuid,
                "user-1",
                "drafting",
                "Draft demand letter",
                30,
                30_000L,
                "USD",
                true,
                "2026-03-10T10:00:00Z"
        );

        billing_accounting.InvoiceRec draft = ledger.draftInvoiceForMatter(
                matterUuid,
                "2026-03-10",
                "2026-03-24",
                "USD"
        );
        billing_accounting.InvoiceRec issued = ledger.finalizeInvoice(draft.uuid);
        assertNotNull(issued);

        online_payments.CheckoutInput input = new online_payments.CheckoutInput();
        input.invoiceUuid = issued.uuid;
        input.currency = null;
        input.processorKey = "manual";
        input.payerEmail = "client@example.com";
        input.payerName = "Client Name";

        online_payments.CheckoutResult checkout = online_payments.defaultStore().createCheckout(tenant, input);
        assertNotNull(checkout);
        assertNotNull(checkout.transaction);
        assertEquals("USD", checkout.transaction.currency);
    }

    @Test
    void webhook_dispatch_failure_is_recorded_with_endpoint_status() throws Exception {
        String tenant = "tenant-webhook-failure-" + UUID.randomUUID();
        tenantIds.add(tenant);

        integration_webhooks webhooks = integration_webhooks.defaultStore();
        integration_webhooks.EndpointInput endpoint = new integration_webhooks.EndpointInput();
        endpoint.label = "Failing endpoint";
        endpoint.url = "http://127.0.0.1:1/webhook";
        endpoint.eventFilterCsv = "payments.*";
        endpoint.signingSecret = "test-secret";
        endpoint.enabled = true;

        integration_webhooks.EndpointRec created = webhooks.createEndpoint(tenant, endpoint);
        assertNotNull(created);
        assertFalse(created.webhookUuid.isBlank());

        integration_webhooks.DispatchResult result = webhooks.dispatchEvent(
                tenant,
                "payments.transaction.created",
                new LinkedHashMap<String, Object>(java.util.Map.of("transaction_uuid", "tx-1"))
        );
        assertNotNull(result);
        assertEquals(1, result.endpointCount);
        assertEquals(1, result.attemptedCount);
        assertEquals(0, result.successCount);
        assertEquals(1, result.failureCount);
        assertEquals(1, result.deliveries.size());
        assertFalse(result.deliveries.get(0).success);

        integration_webhooks.EndpointRec refreshed = webhooks.getEndpoint(tenant, created.webhookUuid);
        assertNotNull(refreshed);
        assertTrue(refreshed.lastAttemptAt != null && !refreshed.lastAttemptAt.isBlank());
        assertTrue(refreshed.lastError != null && !refreshed.lastError.isBlank());
    }

    @Test
    void esign_requests_track_status_transitions_and_events() throws Exception {
        String tenant = "tenant-esign-details-" + UUID.randomUUID();
        tenantIds.add(tenant);

        esign_requests esign = esign_requests.defaultStore();
        esign_requests.CreateInput input = new esign_requests.CreateInput();
        input.providerKey = "manual_notice";
        input.subject = "Engagement Letter";
        input.toCsv = "client@example.com";
        input.status = esign_requests.STATUS_DRAFT;
        input.requestedByUserUuid = "user-1";

        esign_requests.SignatureRequestRec created = esign.createRequest(tenant, input);
        assertNotNull(created);
        assertEquals(esign_requests.STATUS_DRAFT, created.status);
        assertTrue(created.sentAt == null || created.sentAt.isBlank());
        assertTrue(created.completedAt == null || created.completedAt.isBlank());

        esign_requests.SignatureRequestRec sent = esign.updateStatus(
                tenant,
                created.requestUuid,
                esign_requests.STATUS_SENT,
                "sent_notice",
                "Sent to signer",
                "user-1",
                "provider-req-1"
        );
        assertNotNull(sent);
        assertEquals(esign_requests.STATUS_SENT, sent.status);
        assertTrue(sent.sentAt != null && !sent.sentAt.isBlank());

        esign_requests.SignatureRequestRec signed = esign.updateStatus(
                tenant,
                created.requestUuid,
                esign_requests.STATUS_SIGNED,
                "signed",
                "Signer completed document",
                "user-1",
                "provider-req-1"
        );
        assertNotNull(signed);
        assertEquals(esign_requests.STATUS_SIGNED, signed.status);
        assertTrue(signed.completedAt != null && !signed.completedAt.isBlank());

        List<esign_requests.SignatureEventRec> events = esign.listEvents(tenant, created.requestUuid);
        assertEquals(3, events.size());
        long createdEvents = events.stream().filter(e -> "created".equals(e.eventType)).count();
        long sentEvents = events.stream().filter(e -> "sent_notice".equals(e.eventType)).count();
        long signedEvents = events.stream().filter(e -> "signed".equals(e.eventType)).count();
        assertEquals(1L, createdEvents);
        assertEquals(1L, sentEvents);
        assertEquals(1L, signedEvents);
    }

    @Test
    void lead_conversion_and_kpi_rollup_cover_cross_feature_activity() throws Exception {
        String tenant = "tenant-kpi-details-" + UUID.randomUUID();
        tenantIds.add(tenant);

        matters.MatterRec matter = matters.defaultStore().create(
                tenant,
                "KPI Matter",
                "",
                "",
                "",
                "",
                ""
        );
        assertNotNull(matter);

        leads_crm.LeadInput leadInput = new leads_crm.LeadInput();
        leadInput.firstName = "Taylor";
        leadInput.lastName = "Client";
        leadInput.email = "taylor@example.com";
        leads_crm.LeadRec lead = leads_crm.defaultStore().createLead(tenant, leadInput, "user-1");
        assertNotNull(lead);

        leads_crm.LeadRec converted = leads_crm.defaultStore().convertToMatter(tenant, lead.leadUuid, matter.uuid, "user-1");
        assertEquals(leads_crm.STATUS_RETAINED, converted.status);
        List<leads_crm.LeadNoteRec> leadNotes = leads_crm.defaultStore().listNotes(tenant, lead.leadUuid);
        assertEquals(2, leadNotes.size());

        tasks.TaskRec task = new tasks.TaskRec();
        task.matterUuid = matter.uuid;
        task.title = "Complete intake checklist";
        task.description = "Initial checklist completed.";
        task.status = "completed";
        task.priority = "normal";
        task.assignmentMode = "manual";
        task.assignedUserUuid = "user-1";
        task.dueAt = "2026-03-12T17:00";
        task.reminderAt = "";
        task.estimateMinutes = 30;
        tasks.defaultStore().createTask(tenant, task, "user-1", "complete");

        billing_accounting ledger = billing_runtime_registry.tenantLedger(tenant);
        ledger.createTimeEntry(
                matter.uuid,
                "user-1",
                "consult",
                "Client consultation",
                60,
                30_000L,
                "USD",
                true,
                "2026-03-10T09:00:00Z"
        );
        billing_accounting.InvoiceRec draft = ledger.draftInvoiceForMatter(
                matter.uuid,
                "2026-03-10",
                "2026-03-24",
                "USD"
        );
        billing_accounting.InvoiceRec issued = ledger.finalizeInvoice(draft.uuid);
        assertEquals(billing_accounting.INVOICE_ISSUED, issued.status);

        online_payments.CheckoutInput paymentInput = new online_payments.CheckoutInput();
        paymentInput.invoiceUuid = issued.uuid;
        paymentInput.processorKey = "manual";
        paymentInput.amountCents = issued.outstandingCents;
        online_payments.CheckoutResult checkout = online_payments.defaultStore().createCheckout(tenant, paymentInput);
        online_payments.defaultStore().markPaid(
                tenant,
                checkout.transaction.transactionUuid,
                "provider-pay-1",
                "paid-in-full",
                "2026-03-10T12:00:00Z"
        );

        esign_requests.CreateInput signInput = new esign_requests.CreateInput();
        signInput.matterUuid = matter.uuid;
        signInput.subject = "Fee Agreement";
        signInput.toCsv = "taylor@example.com";
        signInput.status = esign_requests.STATUS_SENT;
        signInput.requestedByUserUuid = "user-1";
        esign_requests.SignatureRequestRec signReq = esign_requests.defaultStore().createRequest(tenant, signInput);
        esign_requests.defaultStore().updateStatus(
                tenant,
                signReq.requestUuid,
                esign_requests.STATUS_SIGNED,
                "signed",
                "Executed",
                "user-1",
                "provider-req-2"
        );

        kpi_analytics.SummaryRec summary = kpi_analytics.defaultService().summary(tenant);
        assertNotNull(summary);
        assertEquals(1L, summary.mattersTotal);
        assertEquals(1L, summary.leadsTotal);
        assertEquals(1L, summary.leadsRetained);
        assertEquals(1L, summary.tasksCompleted);
        assertEquals(1L, summary.invoicesPaid);
        assertEquals(1L, summary.paymentTransactionsPaid);
        assertEquals(1L, summary.signaturesSigned);

        List<kpi_analytics.DailyRec> daily = kpi_analytics.defaultService().dailySeries(tenant, 30);
        assertNotNull(daily);
        assertEquals(30, daily.size());
        long leadsCreated = daily.stream().mapToLong(d -> d.leadsCreated).sum();
        long leadsConverted = daily.stream().mapToLong(d -> d.leadsConverted).sum();
        long tasksCompleted = daily.stream().mapToLong(d -> d.tasksCompleted).sum();
        long paymentsCollected = daily.stream().mapToLong(d -> d.paymentsCollectedCents).sum();
        long signaturesCompleted = daily.stream().mapToLong(d -> d.signaturesCompleted).sum();
        assertEquals(1L, leadsCreated);
        assertEquals(1L, leadsConverted);
        assertEquals(1L, tasksCompleted);
        assertEquals(30_000L, paymentsCollected);
        assertEquals(1L, signaturesCompleted);
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (root == null || !Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            List<Path> paths = stream.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        }
    }
}
