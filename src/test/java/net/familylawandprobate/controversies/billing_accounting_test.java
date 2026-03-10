package net.familylawandprobate.controversies;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class billing_accounting_test {

    @Test
    void trust_payment_flow_balances_with_three_way_reconciliation() {
        billing_accounting engine = billing_accounting.inMemory();
        String matterUuid = "matter-100";

        engine.recordTrustDeposit(matterUuid, 100_000L, "USD", "2026-03-01T10:00:00Z", "Initial retainer");
        engine.createTimeEntry(
                matterUuid,
                "user-1",
                "research",
                "Research and memo drafting",
                120,
                30_000L,
                "USD",
                true,
                "2026-03-02T09:00:00Z"
        );

        billing_accounting.InvoiceRec draft = engine.draftInvoiceForMatter(
                matterUuid,
                "2026-03-03",
                "2026-03-17",
                "USD"
        );
        billing_accounting.InvoiceRec issued = engine.finalizeInvoice(draft.uuid);
        assertEquals(60_000L, issued.totalCents);
        assertEquals(billing_accounting.INVOICE_ISSUED, issued.status);

        engine.applyTrustToInvoice(issued.uuid, 60_000L, "2026-03-04T10:00:00Z", "Apply trust");
        billing_accounting.InvoiceRec paid = engine.getInvoice(issued.uuid);
        assertNotNull(paid);
        assertEquals(billing_accounting.INVOICE_PAID, paid.status);
        assertEquals(0L, paid.outstandingCents);
        assertEquals(60_000L, paid.paidCents);

        assertEquals(40_000L, engine.matterTrustBalance(matterUuid));

        billing_accounting.TrustReconciliationRec reconciliation = engine.trustReconciliation("2026-03-31", 40_000L);
        assertTrue(reconciliation.balanced);
        assertEquals(0L, reconciliation.bankVsBookDeltaCents);
        assertEquals(0L, reconciliation.bookVsClientLedgerDeltaCents);
        assertEquals(0L, reconciliation.bookVsTrustLiabilityDeltaCents);

        billing_accounting.ComplianceSnapshot compliance = engine.complianceSnapshot("2026-03-31", 40_000L);
        assertTrue(compliance.violations.isEmpty());
    }

    @Test
    void cannot_overdraw_client_trust_balance() {
        billing_accounting engine = billing_accounting.inMemory();
        String matterUuid = "matter-200";

        engine.recordTrustDeposit(matterUuid, 10_000L, "USD", "2026-03-01T08:00:00Z", "Small retainer");
        engine.createExpenseEntry(
                matterUuid,
                "Filing fee",
                15_000L,
                0L,
                "USD",
                true,
                "2026-03-02T08:00:00Z"
        );

        billing_accounting.InvoiceRec draft = engine.draftInvoiceForMatter(
                matterUuid,
                "2026-03-02",
                "2026-03-16",
                "USD"
        );
        billing_accounting.InvoiceRec issued = engine.finalizeInvoice(draft.uuid);
        assertEquals(15_000L, issued.totalCents);

        assertThrows(IllegalArgumentException.class,
                () -> engine.applyTrustToInvoice(issued.uuid, 15_000L, "2026-03-03T08:00:00Z", "Attempt overdraw"));
    }

    @Test
    void clio_imports_are_idempotent_and_linked_by_external_ids() {
        billing_accounting engine = billing_accounting.inMemory();
        String matterUuid = "matter-clio-300";

        billing_accounting.TimeEntryRec firstTime = engine.importOrUpdateClioTimeEntry(
                "clio-activity-1",
                matterUuid,
                "clio-user-1",
                "Draft pleading",
                "Initial draft",
                30,
                24_000L,
                "USD",
                true,
                "2026-03-05"
        );
        billing_accounting.TimeEntryRec updatedTime = engine.importOrUpdateClioTimeEntry(
                "clio-activity-1",
                matterUuid,
                "clio-user-1",
                "Draft pleading",
                "Updated draft",
                45,
                24_000L,
                "USD",
                true,
                "2026-03-05"
        );
        assertEquals(firstTime.uuid, updatedTime.uuid);
        assertEquals("clio", updatedTime.source);
        assertEquals(45, updatedTime.minutes);
        assertEquals("clio-activity-1", updatedTime.sourceId);
        assertNotNull(engine.findTimeEntryBySource("clio", "clio-activity-1"));

        billing_accounting.ExpenseEntryRec firstExpense = engine.importOrUpdateClioExpenseEntry(
                "clio-expense-1",
                matterUuid,
                "Courier service",
                8_500L,
                500L,
                "USD",
                true,
                "2026-03-05"
        );
        billing_accounting.ExpenseEntryRec updatedExpense = engine.importOrUpdateClioExpenseEntry(
                "clio-expense-1",
                matterUuid,
                "Courier service updated",
                9_000L,
                500L,
                "USD",
                true,
                "2026-03-05"
        );
        assertEquals(firstExpense.uuid, updatedExpense.uuid);
        assertEquals("clio", updatedExpense.source);
        assertEquals("clio-expense-1", updatedExpense.sourceId);
        assertEquals(9_000L, updatedExpense.amountCents);
        assertNotNull(engine.findExpenseEntryBySource("clio", "clio-expense-1"));

        billing_accounting.InvoiceRec clioSummary = engine.importOrUpdateClioInvoiceSummary(
                "clio-bill-1",
                matterUuid,
                "2026-03-06",
                "2026-03-20",
                "USD",
                30_000L,
                0L,
                0L,
                "issued"
        );
        assertEquals("clio", clioSummary.source);
        assertEquals("clio-bill-1", clioSummary.sourceId);
        assertNotNull(engine.findInvoiceBySource("clio", "clio-bill-1"));

        engine.recordTrustDeposit(matterUuid, 30_000L, "USD", "2026-03-06T11:00:00Z", "Retainer");
        billing_accounting.InvoiceRec localDraft = engine.draftInvoiceForMatter(
                matterUuid,
                "2026-03-06",
                "2026-03-20",
                "USD"
        );
        billing_accounting.InvoiceRec localIssued = engine.finalizeInvoice(localDraft.uuid);

        billing_accounting.InvoiceRec linked = engine.linkInvoiceToClioBill(localIssued.uuid, "clio-bill-2");
        assertEquals("clio", linked.source);
        assertEquals("clio-bill-2", linked.sourceId);

        billing_accounting.TrustTxnRec importedDepositA = engine.importClioTrustDeposit(
                "clio-trust-deposit-1",
                matterUuid,
                5_000L,
                "USD",
                "2026-03-07T11:00:00Z",
                "Imported trust deposit"
        );
        billing_accounting.TrustTxnRec importedDepositB = engine.importClioTrustDeposit(
                "clio-trust-deposit-1",
                matterUuid,
                5_000L,
                "USD",
                "2026-03-07T11:00:00Z",
                "Imported trust deposit"
        );
        assertEquals(importedDepositA.uuid, importedDepositB.uuid);

        billing_accounting.TrustTxnRec importedTrustPayA = engine.importClioTrustApplication(
                "clio-trust-apply-1",
                linked.uuid,
                Math.min(10_000L, linked.outstandingCents),
                "2026-03-08T10:00:00Z",
                "Imported trust apply"
        );
        billing_accounting.TrustTxnRec importedTrustPayB = engine.importClioTrustApplication(
                "clio-trust-apply-1",
                linked.uuid,
                Math.min(10_000L, linked.outstandingCents),
                "2026-03-08T10:00:00Z",
                "Imported trust apply"
        );
        assertEquals(importedTrustPayA.uuid, importedTrustPayB.uuid);
        assertNotNull(engine.findTrustTxnBySource("clio", "clio-trust-apply-1"));
        assertNotNull(engine.findPaymentBySource("clio", "clio-trust-apply-1"));
    }
}
