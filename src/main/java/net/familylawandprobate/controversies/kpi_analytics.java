package net.familylawandprobate.controversies;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * kpi_analytics
 *
 * Cross-feature KPI summary and daily series for firm-level analytics.
 */
public final class kpi_analytics {

    public static kpi_analytics defaultService() {
        return new kpi_analytics();
    }

    public static final class SummaryRec {
        public String generatedAt = "";

        public long mattersTotal = 0L;
        public long mattersActive = 0L;

        public long leadsTotal = 0L;
        public long leadsNew = 0L;
        public long leadsQualified = 0L;
        public long leadsConsultScheduled = 0L;
        public long leadsRetained = 0L;
        public long leadsClosedLost = 0L;
        public double leadConversionRate = 0.0d;

        public long tasksTotal = 0L;
        public long tasksOpen = 0L;
        public long tasksCompleted = 0L;
        public long tasksOverdue = 0L;

        public long invoicesTotal = 0L;
        public long invoicesIssued = 0L;
        public long invoicesPaid = 0L;
        public long invoiceOutstandingCents = 0L;
        public long invoicePaidCents = 0L;

        public long trustBalanceTotalCents = 0L;
        public long paymentsReceivedCents = 0L;

        public long paymentTransactionsPending = 0L;
        public long paymentTransactionsPaid = 0L;
        public long paymentTransactionsFailed = 0L;

        public long signaturesTotal = 0L;
        public long signaturesPending = 0L;
        public long signaturesSigned = 0L;
        public long signaturesDeclined = 0L;
    }

    public static final class DailyRec {
        public String date = "";
        public long leadsCreated = 0L;
        public long leadsConverted = 0L;
        public long paymentsCollectedCents = 0L;
        public long signaturesCompleted = 0L;
        public long tasksCompleted = 0L;
    }

    public SummaryRec summary(String tenantUuid) throws Exception {
        String tu = safe(tenantUuid).trim();
        if (tu.isBlank()) throw new IllegalArgumentException("tenantUuid required.");

        SummaryRec out = new SummaryRec();
        out.generatedAt = app_clock.now().toString();

        matters matterStore = matters.defaultStore();
        tasks taskStore = tasks.defaultStore();
        billing_accounting ledger = billing_runtime_registry.tenantLedger(tu);
        leads_crm leadStore = leads_crm.defaultStore();
        esign_requests signStore = esign_requests.defaultStore();
        online_payments paymentStore = online_payments.defaultStore();

        List<matters.MatterRec> mattersAll = matterStore.listAll(tu);
        out.mattersTotal = mattersAll.size();
        for (matters.MatterRec m : mattersAll) {
            if (m == null) continue;
            if (!m.trashed) out.mattersActive++;
        }

        List<leads_crm.LeadRec> leads = leadStore.listLeads(tu, true);
        out.leadsTotal = leads.size();
        for (leads_crm.LeadRec lead : leads) {
            if (lead == null) continue;
            String status = safe(lead.status).trim().toLowerCase(Locale.ROOT);
            if (leads_crm.STATUS_NEW.equals(status)) out.leadsNew++;
            else if (leads_crm.STATUS_QUALIFIED.equals(status)) out.leadsQualified++;
            else if (leads_crm.STATUS_CONSULT_SCHEDULED.equals(status)) out.leadsConsultScheduled++;
            else if (leads_crm.STATUS_RETAINED.equals(status)) out.leadsRetained++;
            else if (leads_crm.STATUS_CLOSED_LOST.equals(status)) out.leadsClosedLost++;
        }
        if (out.leadsTotal > 0L) {
            out.leadConversionRate = ((double) out.leadsRetained / (double) out.leadsTotal) * 100.0d;
        }

        List<tasks.TaskRec> tasksAll = taskStore.listTasks(tu);
        out.tasksTotal = tasksAll.size();
        LocalDate today = app_clock.todayUtc();
        for (tasks.TaskRec task : tasksAll) {
            if (task == null || task.archived) continue;
            String status = safe(task.status).trim().toLowerCase(Locale.ROOT);
            boolean done = "completed".equals(status) || "done".equals(status) || !safe(task.completedAt).trim().isBlank();
            if (done) out.tasksCompleted++;
            else out.tasksOpen++;

            if (!done) {
                String due = safe(task.dueAt).trim();
                if (!due.isBlank()) {
                    try {
                        LocalDate d = LocalDate.parse(due.substring(0, 10));
                        if (d.isBefore(today)) out.tasksOverdue++;
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        for (matters.MatterRec m : mattersAll) {
            if (m == null) continue;
            String matterUuid = safe(m.uuid).trim();
            if (matterUuid.isBlank()) continue;

            List<billing_accounting.InvoiceRec> invoices = ledger.listInvoicesForMatter(matterUuid);
            out.invoicesTotal += invoices.size();
            for (billing_accounting.InvoiceRec inv : invoices) {
                if (inv == null) continue;
                String st = safe(inv.status).trim().toLowerCase(Locale.ROOT);
                if (billing_accounting.INVOICE_ISSUED.equals(st) || billing_accounting.INVOICE_PARTIALLY_PAID.equals(st)) out.invoicesIssued++;
                if (billing_accounting.INVOICE_PAID.equals(st)) out.invoicesPaid++;
                out.invoiceOutstandingCents += Math.max(0L, inv.outstandingCents);
                out.invoicePaidCents += Math.max(0L, inv.paidCents);
            }

            List<billing_accounting.PaymentRec> payments = ledger.listPaymentsForMatter(matterUuid);
            for (billing_accounting.PaymentRec p : payments) {
                if (p == null) continue;
                out.paymentsReceivedCents += Math.max(0L, p.amountCents);
            }
            out.trustBalanceTotalCents += Math.max(0L, ledger.matterTrustBalance(matterUuid));
        }

        List<online_payments.PaymentTransactionRec> txns = paymentStore.listTransactions(tu, "", "", "");
        for (online_payments.PaymentTransactionRec tx : txns) {
            if (tx == null) continue;
            String status = safe(tx.status).trim().toLowerCase(Locale.ROOT);
            if (online_payments.STATUS_PAID.equals(status)) out.paymentTransactionsPaid++;
            else if (online_payments.STATUS_FAILED.equals(status)) out.paymentTransactionsFailed++;
            else out.paymentTransactionsPending++;
        }

        List<esign_requests.SignatureRequestRec> signatures = signStore.listRequests(tu);
        out.signaturesTotal = signatures.size();
        for (esign_requests.SignatureRequestRec s : signatures) {
            if (s == null) continue;
            String st = safe(s.status).trim().toLowerCase(Locale.ROOT);
            if (esign_requests.STATUS_SIGNED.equals(st)) out.signaturesSigned++;
            else if (esign_requests.STATUS_DECLINED.equals(st)) out.signaturesDeclined++;
            else if (!esign_requests.STATUS_CANCELLED.equals(st)
                    && !esign_requests.STATUS_FAILED.equals(st)
                    && !esign_requests.STATUS_EXPIRED.equals(st)) {
                out.signaturesPending++;
            }
        }

        return out;
    }

    public List<DailyRec> dailySeries(String tenantUuid, int days) throws Exception {
        String tu = safe(tenantUuid).trim();
        if (tu.isBlank()) throw new IllegalArgumentException("tenantUuid required.");
        int span = Math.max(1, Math.min(366, days <= 0 ? 30 : days));

        LinkedHashMap<String, DailyRec> byDay = new LinkedHashMap<String, DailyRec>();
        LocalDate today = app_clock.todayUtc();
        for (int i = span - 1; i >= 0; i--) {
            LocalDate d = today.minusDays(i);
            DailyRec row = new DailyRec();
            row.date = d.toString();
            byDay.put(row.date, row);
        }

        for (leads_crm.LeadRec lead : leads_crm.defaultStore().listLeads(tu, true)) {
            if (lead == null) continue;
            String createdDay = dayFromIso(lead.createdAt);
            DailyRec created = byDay.get(createdDay);
            if (created != null) created.leadsCreated++;

            String convertedDay = dayFromIso(lead.convertedAt);
            DailyRec converted = byDay.get(convertedDay);
            if (converted != null) converted.leadsConverted++;
        }

        for (online_payments.PaymentTransactionRec tx : online_payments.defaultStore().listTransactions(tu, "", "", "")) {
            if (tx == null) continue;
            if (!online_payments.STATUS_PAID.equalsIgnoreCase(safe(tx.status))) continue;
            String paidDay = dayFromIso(tx.paidAt);
            DailyRec day = byDay.get(paidDay);
            if (day != null) day.paymentsCollectedCents += Math.max(0L, tx.amountCents);
        }

        for (esign_requests.SignatureRequestRec sig : esign_requests.defaultStore().listRequests(tu)) {
            if (sig == null) continue;
            if (!esign_requests.STATUS_SIGNED.equalsIgnoreCase(safe(sig.status))
                    && !esign_requests.STATUS_DECLINED.equalsIgnoreCase(safe(sig.status))) continue;
            String dayKey = dayFromIso(sig.completedAt);
            DailyRec day = byDay.get(dayKey);
            if (day != null) day.signaturesCompleted++;
        }

        for (tasks.TaskRec task : tasks.defaultStore().listTasks(tu)) {
            if (task == null) continue;
            String status = safe(task.status).trim().toLowerCase(Locale.ROOT);
            boolean done = "completed".equals(status) || "done".equals(status) || !safe(task.completedAt).trim().isBlank();
            if (!done) continue;
            String dayKey = dayFromIso(task.completedAt);
            DailyRec day = byDay.get(dayKey);
            if (day != null) day.tasksCompleted++;
        }

        return new ArrayList<DailyRec>(byDay.values());
    }

    private static String dayFromIso(String iso) {
        String s = safe(iso).trim();
        if (s.isBlank()) return "";
        if (s.length() >= 10) {
            String candidate = s.substring(0, 10);
            try {
                LocalDate.parse(candidate);
                return candidate;
            } catch (Exception ignored) {
            }
        }
        try {
            return Instant.parse(s).atZone(ZoneOffset.UTC).toLocalDate().toString();
        } catch (Exception ex) {
            return "";
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
