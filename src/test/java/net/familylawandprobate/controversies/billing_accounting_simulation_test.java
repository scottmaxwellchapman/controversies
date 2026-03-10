package net.familylawandprobate.controversies;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

public class billing_accounting_simulation_test {

    @Test
    void randomized_financial_flow_preserves_trust_and_ledger_invariants() {
        billing_accounting engine = billing_accounting.inMemory();
        Random random = new Random(42L);

        List<String> matters = List.of(
                "sim-matter-1",
                "sim-matter-2",
                "sim-matter-3",
                "sim-matter-4",
                "sim-matter-5"
        );

        Map<String, Long> expectedTrustBalances = new HashMap<String, Long>();
        for (String matter : matters) expectedTrustBalances.put(matter, 0L);

        ArrayList<String> invoices = new ArrayList<String>();

        for (int i = 0; i < 500; i++) {
            String matter = matters.get(random.nextInt(matters.size()));
            int op = random.nextInt(100);

            if (op < 30) {
                long amount = (random.nextInt(80) + 20) * 100L;
                engine.recordTrustDeposit(matter, amount, "USD", "2026-03-10T10:00:00Z", "sim-deposit-" + i);
                expectedTrustBalances.put(matter, expectedTrustBalances.getOrDefault(matter, 0L) + amount);
                continue;
            }

            if (op < 55) {
                int minutes = 15 * (random.nextInt(8) + 1);
                long rate = (random.nextInt(300) + 150) * 100L;
                engine.createTimeEntry(
                        matter,
                        "sim-user-" + (random.nextInt(4) + 1),
                        "sim-activity",
                        "sim note " + i,
                        minutes,
                        rate,
                        "USD",
                        true,
                        "2026-03-10T10:00:00Z"
                );
                if (random.nextBoolean()) {
                    long expense = (random.nextInt(40) + 10) * 100L;
                    long tax = random.nextBoolean() ? (expense / 10L) : 0L;
                    engine.createExpenseEntry(
                            matter,
                            "sim expense " + i,
                            expense,
                            tax,
                            "USD",
                            true,
                            "2026-03-10T10:00:00Z"
                    );
                }
                continue;
            }

            if (op < 70) {
                try {
                    billing_accounting.InvoiceRec draft = engine.draftInvoiceForMatter(
                            matter,
                            "2026-03-10",
                            "2026-03-25",
                            "USD"
                    );
                    billing_accounting.InvoiceRec issued = engine.finalizeInvoice(draft.uuid);
                    invoices.add(issued.uuid);
                } catch (IllegalStateException ignored) {
                    // No unbilled entries for this matter
                }
                continue;
            }

            if (op < 88) {
                billing_accounting.InvoiceRec target = choosePayableInvoiceForMatter(engine, invoices, matter);
                if (target != null) {
                    long trustBalance = expectedTrustBalances.getOrDefault(matter, 0L);
                    if (trustBalance > 0L && target.outstandingCents > 0L) {
                        long cap = Math.min(trustBalance, target.outstandingCents);
                        long amount = Math.max(100L, Math.min(cap, ((random.nextInt(25) + 1) * 100L)));
                        amount = Math.min(amount, cap);
                        if (amount > 0L) {
                            engine.applyTrustToInvoice(target.uuid, amount, "2026-03-10T12:00:00Z", "sim-apply-" + i);
                            expectedTrustBalances.put(matter, trustBalance - amount);
                        }
                    }
                }
                continue;
            }

            if (op < 97) {
                billing_accounting.InvoiceRec target = choosePayableInvoiceForMatter(engine, invoices, matter);
                if (target != null && target.outstandingCents > 0L) {
                    long amount = Math.max(100L, Math.min(target.outstandingCents, ((random.nextInt(30) + 1) * 100L)));
                    amount = Math.min(amount, target.outstandingCents);
                    engine.recordOperatingPayment(target.uuid, amount, "2026-03-10T13:00:00Z", "sim-op-pay-" + i);
                }
                continue;
            }

            long trustBalance = expectedTrustBalances.getOrDefault(matter, 0L);
            if (trustBalance > 0L) {
                long amount = Math.max(100L, Math.min(trustBalance, ((random.nextInt(20) + 1) * 100L)));
                amount = Math.min(amount, trustBalance);
                engine.recordTrustRefund(matter, amount, "USD", "2026-03-10T14:00:00Z", "sim-refund-" + i);
                expectedTrustBalances.put(matter, trustBalance - amount);
            }
        }

        for (String matter : matters) {
            long expected = expectedTrustBalances.getOrDefault(matter, 0L);
            long actual = engine.matterTrustBalance(matter);
            assertEquals(expected, actual, "trust balance mismatch for " + matter);
            assertTrue(actual >= 0L, "trust balance should never be negative for " + matter);
        }

        billing_accounting.TrustReconciliationRec preview = engine.trustReconciliation("2026-03-31", 0L);
        billing_accounting.TrustReconciliationRec rec = engine.trustReconciliation("2026-03-31", preview.bookTrustBankBalanceCents);
        assertTrue(rec.balanced, "three-way reconciliation must be balanced when statement equals book");
        assertEquals(0L, rec.bankVsBookDeltaCents);
        assertEquals(0L, rec.bookVsClientLedgerDeltaCents);
        assertEquals(0L, rec.bookVsTrustLiabilityDeltaCents);

        billing_accounting.ComplianceSnapshot compliance = engine.complianceSnapshot("2026-03-31", preview.bookTrustBankBalanceCents);
        assertTrue(compliance.violations.isEmpty(), "no compliance violations expected");
    }

    private static billing_accounting.InvoiceRec choosePayableInvoiceForMatter(billing_accounting engine,
                                                                                List<String> invoiceIds,
                                                                                String matterUuid) {
        for (int i = invoiceIds.size() - 1; i >= 0; i--) {
            billing_accounting.InvoiceRec invoice = engine.getInvoice(invoiceIds.get(i));
            if (invoice == null) continue;
            if (!matterUuid.equals(invoice.matterUuid)) continue;
            if (invoice.outstandingCents <= 0L) continue;
            if (!(billing_accounting.INVOICE_ISSUED.equals(invoice.status)
                    || billing_accounting.INVOICE_PARTIALLY_PAID.equals(invoice.status))) {
                continue;
            }
            return invoice;
        }
        return null;
    }
}
