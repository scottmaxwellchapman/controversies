package net.familylawandprobate.controversies;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;

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

    @Test
    void disputed_holds_block_disbursement_until_released() {
        billing_accounting engine = billing_accounting.inMemory();
        String matterUuid = "matter-hold-400";

        engine.recordTrustDeposit(matterUuid, 50_000L, "USD", "2026-03-01T09:00:00Z", "Retainer");
        engine.createTimeEntry(
                matterUuid,
                "user-hold",
                "court_appearance",
                "Initial hearing",
                60,
                20_000L,
                "USD",
                true,
                "2026-03-02T10:00:00Z"
        );
        billing_accounting.InvoiceRec issued = engine.finalizeInvoice(engine.draftInvoiceForMatter(
                matterUuid,
                "2026-03-03",
                "2026-03-17",
                "USD"
        ).uuid);

        billing_accounting.TrustDisputeHoldRec hold = engine.placeTrustDisputeHold(
                matterUuid,
                40_000L,
                "Disputed allocation",
                "user-hold",
                "2026-03-03T15:00:00Z"
        );
        assertTrue(hold.active);
        assertEquals(10_000L, engine.availableTrustBalance(matterUuid));

        assertThrows(IllegalArgumentException.class,
                () -> engine.applyTrustToInvoice(issued.uuid, 20_000L, "2026-03-04T09:00:00Z", "Attempt while held"));

        engine.releaseTrustDisputeHold(hold.uuid, "user-hold", "Dispute resolved", "2026-03-05T09:00:00Z");
        engine.applyTrustToInvoice(issued.uuid, 20_000L, "2026-03-05T10:00:00Z", "Post-resolution transfer");

        billing_accounting.InvoiceRec paid = engine.getInvoice(issued.uuid);
        assertNotNull(paid);
        assertEquals(billing_accounting.INVOICE_PAID, paid.status);
        assertEquals(30_000L, engine.matterTrustBalance(matterUuid));
        assertEquals(0L, engine.heldTrustAmount(matterUuid));
    }

    @Test
    void trust_disbursement_approval_workflow_enforces_policy_and_executes() {
        billing_accounting engine = billing_accounting.inMemory();
        String matterUuid = "matter-approval-500";

        engine.recordTrustDeposit(matterUuid, 60_000L, "USD", "2026-03-01T09:00:00Z", "Retainer");
        engine.createExpenseEntry(
                matterUuid,
                "Expert consult",
                25_000L,
                0L,
                "USD",
                true,
                "2026-03-02T10:00:00Z"
        );
        billing_accounting.InvoiceRec issued = engine.finalizeInvoice(engine.draftInvoiceForMatter(
                matterUuid,
                "2026-03-03",
                "2026-03-17",
                "USD"
        ).uuid);

        engine.upsertMatterTrustPolicy(
                matterUuid,
                "Primary IOLTA",
                "Approved Bank",
                true,
                "2026-12-31",
                "",
                true,
                5_000L,
                "admin"
        );

        assertThrows(IllegalStateException.class,
                () -> engine.applyTrustToInvoice(issued.uuid, 20_000L, "2026-03-04T09:00:00Z", "No approval"));

        billing_accounting.TrustDisbursementApprovalRec requested = engine.requestTrustDisbursementApproval(
                billing_accounting.DISBURSEMENT_TRUST_APPLY,
                matterUuid,
                issued.uuid,
                20_000L,
                "USD",
                "requestor",
                "Transfer earned fee",
                "2026-03-04T10:00:00Z"
        );
        billing_accounting.TrustDisbursementApprovalRec approved = engine.reviewTrustDisbursementApproval(
                requested.uuid,
                true,
                "approver",
                "Approved",
                "2026-03-04T11:00:00Z"
        );
        assertEquals(billing_accounting.APPROVAL_APPROVED, approved.status);

        billing_accounting.TrustTxnRec executed = engine.applyTrustToInvoiceWithApproval(
                approved.uuid,
                "2026-03-04T12:00:00Z",
                "Approved transfer"
        );
        assertNotNull(executed);

        billing_accounting.TrustDisbursementApprovalRec done = engine.getTrustDisbursementApproval(approved.uuid);
        assertNotNull(done);
        assertEquals(billing_accounting.APPROVAL_EXECUTED, done.status);
        assertEquals(executed.uuid, done.trustTxnUuid);

        billing_accounting.TrustReconciliationRec rec = engine.trustReconciliation("2026-03-31", 40_000L);
        billing_accounting.ComplianceSnapshot compliance = engine.complianceSnapshot("2026-03-31", rec.bookTrustBankBalanceCents);
        assertTrue(compliance.violations.isEmpty());
    }

    @Test
    void payment_plans_and_autopay_post_installments_until_invoice_paid() {
        billing_accounting engine = billing_accounting.inMemory();
        String matterUuid = "matter-plan-600";

        engine.createExpenseEntry(
                matterUuid,
                "Flat fee filing package",
                30_000L,
                0L,
                "USD",
                true,
                "2026-03-01T09:00:00Z"
        );
        billing_accounting.InvoiceRec issued = engine.finalizeInvoice(engine.draftInvoiceForMatter(
                matterUuid,
                "2026-03-01",
                "2026-03-31",
                "USD"
        ).uuid);

        billing_accounting.PaymentPlanRec plan = engine.createPaymentPlan(
                issued.uuid,
                3,
                30,
                "2026-03-01",
                true,
                "Matter installment plan"
        );
        assertEquals(billing_accounting.PAYMENT_PLAN_ACTIVE, plan.status);

        engine.postPaymentPlanInstallment(plan.uuid, "2026-03-01T10:00:00Z", "Manual installment #1");
        billing_accounting.InvoiceRec afterFirst = engine.getInvoice(issued.uuid);
        assertNotNull(afterFirst);
        assertEquals(20_000L, afterFirst.outstandingCents);

        List<billing_accounting.PaymentRec> firstSweep = engine.runAutopayDuePlans("2026-03-31", "2026-03-31T10:00:00Z", "Autopay");
        assertEquals(1, firstSweep.size());
        List<billing_accounting.PaymentRec> secondSweep = engine.runAutopayDuePlans("2026-04-30", "2026-04-30T10:00:00Z", "Autopay");
        assertEquals(1, secondSweep.size());

        billing_accounting.InvoiceRec paid = engine.getInvoice(issued.uuid);
        assertNotNull(paid);
        assertEquals(billing_accounting.INVOICE_PAID, paid.status);
        assertEquals(0L, paid.outstandingCents);

        billing_accounting.PaymentPlanRec completed = engine.getPaymentPlan(plan.uuid);
        assertNotNull(completed);
        assertEquals(billing_accounting.PAYMENT_PLAN_COMPLETED, completed.status);
        assertEquals(3, completed.paymentsPostedCount);
    }

    @Test
    void managed_activities_enforce_catalog_and_apply_utbms_defaults() {
        billing_accounting engine = billing_accounting.inMemory();
        String matterUuid = "matter-activity-800";

        engine.setRequireManagedActivities(true);
        assertThrows(IllegalArgumentException.class, () -> engine.createTimeEntry(
                matterUuid,
                "user-a",
                "l110",
                "Should fail before activity exists",
                30,
                0L,
                "USD",
                true,
                "2026-03-01T09:00:00Z"
        ));

        engine.upsertBillingActivity(
                "l110",
                "Case assessment",
                25_000L,
                true,
                "L110",
                "A101",
                "",
                true
        );
        billing_accounting.TimeEntryRec managed = engine.createTimeEntry(
                matterUuid,
                "user-a",
                "l110",
                "Managed activity entry",
                60,
                0L,
                "USD",
                true,
                "2026-03-01T10:00:00Z"
        );
        assertEquals("L110", managed.activityCode);
        assertEquals(25_000L, managed.rateCents);
        assertEquals("L110", managed.utbmsTaskCode);
        assertEquals("A101", managed.utbmsActivityCode);

        engine.deactivateBillingActivity("l110");
        assertThrows(IllegalArgumentException.class, () -> engine.createTimeEntry(
                matterUuid,
                "user-a",
                "L110",
                "Inactive activity should fail",
                30,
                25_000L,
                "USD",
                true,
                "2026-03-01T11:00:00Z"
        ));
    }

    @Test
    void expense_entries_accept_and_preserve_utbms_expense_codes() {
        billing_accounting engine = billing_accounting.inMemory();
        String matterUuid = "matter-expense-801";

        billing_accounting.ExpenseEntryRec localExpense = engine.createExpenseEntry(
                matterUuid,
                "Court filing",
                12_500L,
                0L,
                "USD",
                true,
                "2026-03-01T09:00:00Z",
                "E101"
        );
        assertEquals("E101", localExpense.utbmsExpenseCode);

        billing_accounting.ExpenseEntryRec importedExpense = engine.importOrUpdateClioExpenseEntry(
                "clio-expense-utbms-1",
                matterUuid,
                "Messenger service",
                7_500L,
                0L,
                "USD",
                true,
                "2026-03-02T09:00:00Z",
                "E111"
        );
        assertEquals("E111", importedExpense.utbmsExpenseCode);
    }

    @Test
    void compliance_flags_missing_iolta_eligibility_and_overdue_certification() {
        billing_accounting engine = billing_accounting.inMemory();
        String matterUuid = "matter-policy-700";

        engine.recordTrustDeposit(matterUuid, 5_000L, "USD", "2026-03-01T09:00:00Z", "Retainer");
        engine.upsertMatterTrustPolicy(
                matterUuid,
                "Fallback IOLTA",
                "Unknown Bank",
                false,
                "2025-01-15",
                "",
                false,
                0L,
                "admin"
        );

        billing_accounting.TrustReconciliationRec rec = engine.trustReconciliation("2026-03-31", 5_000L);
        billing_accounting.ComplianceSnapshot compliance = engine.complianceSnapshot("2026-03-31", rec.bookTrustBankBalanceCents);

        assertTrue(containsViolation(compliance.violations, "lacks confirmed eligible IOLTA institution"));
        assertTrue(containsViolation(compliance.violations, "annual certification appears overdue"));
    }

    @Test
    void extreme_invoice_overflow_is_rejected_instead_of_wrapping() {
        billing_accounting engine = billing_accounting.inMemory();
        String matterUuid = "matter-overflow-invoice-900";

        engine.createExpenseEntry(
                matterUuid,
                "Major disbursement A",
                Long.MAX_VALUE,
                0L,
                "USD",
                true,
                "2026-03-01T09:00:00Z"
        );
        engine.createExpenseEntry(
                matterUuid,
                "Major disbursement B",
                1L,
                0L,
                "USD",
                true,
                "2026-03-01T09:05:00Z"
        );

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                engine.draftInvoiceForMatter(matterUuid, "2026-03-02", "2026-03-16", "USD"));
        assertTrue(ex.getMessage().toLowerCase().contains("overflow"));
    }

    @Test
    void extreme_trust_ledger_overflow_is_rejected_instead_of_wrapping() {
        billing_accounting engine = billing_accounting.inMemory();
        String matterUuid = "matter-overflow-trust-901";

        engine.recordTrustDeposit(matterUuid, Long.MAX_VALUE, "USD", "2026-03-01T09:00:00Z", "Large trust deposit");
        engine.recordTrustDeposit(matterUuid, 1L, "USD", "2026-03-01T09:10:00Z", "Overflow trigger");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> engine.matterTrustBalance(matterUuid));
        assertTrue(ex.getMessage().toLowerCase().contains("overflow"));
    }

    @Test
    void minute_rounding_and_micro_installment_plan_do_not_overcharge() {
        billing_accounting engine = billing_accounting.inMemory();
        String matterUuid = "matter-rounding-902";

        engine.createTimeEntry(
                matterUuid,
                "user-round",
                "L110",
                "One-minute call",
                1,
                100L,
                "USD",
                true,
                "2026-03-01T10:00:00Z"
        );
        billing_accounting.InvoiceRec issued = engine.finalizeInvoice(engine.draftInvoiceForMatter(
                matterUuid,
                "2026-03-01",
                "2026-03-31",
                "USD"
        ).uuid);
        assertEquals(2L, issued.totalCents);

        billing_accounting.PaymentPlanRec plan = engine.createPaymentPlan(
                issued.uuid,
                10,
                30,
                "2026-03-01",
                false,
                "Micro plan"
        );
        billing_accounting.PaymentRec p1 = engine.postPaymentPlanInstallment(plan.uuid, "2026-03-01T12:00:00Z", "Installment #1");
        billing_accounting.PaymentRec p2 = engine.postPaymentPlanInstallment(plan.uuid, "2026-03-02T12:00:00Z", "Installment #2");
        assertEquals(1L, p1.amountCents);
        assertEquals(1L, p2.amountCents);

        billing_accounting.InvoiceRec paid = engine.getInvoice(issued.uuid);
        assertNotNull(paid);
        assertEquals(0L, paid.outstandingCents);
        assertEquals(billing_accounting.INVOICE_PAID, paid.status);
        assertThrows(IllegalStateException.class, () ->
                engine.postPaymentPlanInstallment(plan.uuid, "2026-03-03T12:00:00Z", "Should not post"));
    }

    @Test
    void hold_and_reserve_boundary_allows_exact_available_and_blocks_excess() {
        billing_accounting engine = billing_accounting.inMemory();
        String matterUuid = "matter-boundary-903";

        engine.recordTrustDeposit(matterUuid, 10_000L, "USD", "2026-03-01T09:00:00Z", "Retainer");
        engine.upsertMatterTrustPolicy(
                matterUuid,
                "Primary IOLTA",
                "Approved Bank",
                true,
                "2026-12-31",
                "",
                false,
                3_000L,
                "admin"
        );
        engine.placeTrustDisputeHold(
                matterUuid,
                2_000L,
                "Disputed fee segment",
                "user-boundary",
                "2026-03-01T10:00:00Z"
        );
        assertEquals(5_000L, engine.availableTrustBalance(matterUuid));

        engine.createExpenseEntry(
                matterUuid,
                "Flat fee tranche",
                5_000L,
                0L,
                "USD",
                true,
                "2026-03-02T09:00:00Z"
        );
        billing_accounting.InvoiceRec issued = engine.finalizeInvoice(engine.draftInvoiceForMatter(
                matterUuid,
                "2026-03-03",
                "2026-03-31",
                "USD"
        ).uuid);

        engine.applyTrustToInvoice(issued.uuid, 5_000L, "2026-03-04T10:00:00Z", "Exact available transfer");
        assertThrows(IllegalArgumentException.class, () ->
                engine.recordTrustRefund(matterUuid, 1L, "USD", "2026-03-04T11:00:00Z", "Exceeds available by 1 cent"));
    }

    @Test
    void extreme_time_proration_overflow_is_rejected_instead_of_arithmetic_exception() {
        billing_accounting engine = billing_accounting.inMemory();
        String matterUuid = "matter-overflow-time-904";

        engine.createTimeEntry(
                matterUuid,
                "user-overflow",
                "L110",
                "Extreme overflow proration",
                Integer.MAX_VALUE,
                Long.MAX_VALUE,
                "USD",
                true,
                "2026-03-01T09:00:00Z"
        );

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                engine.draftInvoiceForMatter(matterUuid, "2026-03-02", "2026-03-16", "USD"));
        assertTrue(ex.getMessage().toLowerCase().contains("overflow"));
    }

    @Test
    void extreme_available_trust_underflow_is_rejected_instead_of_wrapping_positive() throws Exception {
        billing_accounting engine = billing_accounting.inMemory();
        String matterUuid = "matter-overflow-available-905";

        putTrustTxn(engine, new billing_accounting.TrustTxnRec(
                "txn-r1",
                "local",
                "",
                matterUuid,
                "",
                billing_accounting.TRUST_REFUND,
                Long.MAX_VALUE,
                "USD",
                "2026-03-01T09:00:00Z",
                "Synthetic refund 1",
                "2026-03-01T09:00:00Z"
        ));
        putTrustTxn(engine, new billing_accounting.TrustTxnRec(
                "txn-r2",
                "local",
                "",
                matterUuid,
                "",
                billing_accounting.TRUST_REFUND,
                1L,
                "USD",
                "2026-03-01T09:01:00Z",
                "Synthetic refund 2",
                "2026-03-01T09:01:00Z"
        ));
        engine.upsertMatterTrustPolicy(
                matterUuid,
                "Primary IOLTA",
                "Approved Bank",
                true,
                "",
                "",
                false,
                1L,
                "admin"
        );

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> engine.availableTrustBalance(matterUuid));
        assertTrue(ex.getMessage().toLowerCase().contains("overflow"));
    }

    private static boolean containsViolation(List<String> violations, String text) {
        if (violations == null || text == null) return false;
        String needle = text.toLowerCase();
        for (String v : violations) {
            if (v == null) continue;
            if (v.toLowerCase().contains(needle)) return true;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static void putTrustTxn(billing_accounting engine, billing_accounting.TrustTxnRec txn) throws Exception {
        Field f = billing_accounting.class.getDeclaredField("trustTxns");
        f.setAccessible(true);
        LinkedHashMap<String, billing_accounting.TrustTxnRec> txns =
                (LinkedHashMap<String, billing_accounting.TrustTxnRec>) f.get(engine);
        txns.put(txn.uuid, txn);
    }
}
