package net.familylawandprobate.controversies;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * billing_accounting
 *
 * Matter-centric legal billing + accounting engine with trust safeguards.
 *
 * Scope:
 * - Time/expense capture
 * - Invoice drafting/finalization
 * - Trust deposits, trust application to invoices, trust refunds
 * - Operating payments
 * - Double-entry journal posting
 * - Trust three-way reconciliation snapshot
 *
 * This is an in-memory engine intentionally designed to be embedded by
 * persistence and API layers.
 */
public final class billing_accounting {

    // Invoice states
    public static final String INVOICE_DRAFT = "draft";
    public static final String INVOICE_ISSUED = "issued";
    public static final String INVOICE_PARTIALLY_PAID = "partially_paid";
    public static final String INVOICE_PAID = "paid";
    public static final String INVOICE_VOID = "void";

    // Trust/payment transaction kinds
    public static final String TRUST_DEPOSIT = "deposit";
    public static final String TRUST_APPLY_TO_INVOICE = "apply_to_invoice";
    public static final String TRUST_REFUND = "refund";
    public static final String PAYMENT_OPERATING = "operating_payment";
    public static final String PAYMENT_TRUST = "trust_payment";

    // Ledger account codes
    public static final String ACCT_TRUST_BANK = "asset:trust_bank";
    public static final String ACCT_OPERATING_BANK = "asset:operating_bank";
    public static final String ACCT_ACCOUNTS_RECEIVABLE = "asset:accounts_receivable";
    public static final String ACCT_TRUST_LIABILITY = "liability:client_trust";
    public static final String ACCT_FEE_REVENUE = "revenue:legal_fees";
    public static final String ACCT_REIMBURSED_EXPENSE_REVENUE = "revenue:reimbursed_expenses";
    public static final String ACCT_SALES_TAX_PAYABLE = "liability:sales_tax_payable";

    private final Object lock = new Object();
    private final Clock clock;

    private final LinkedHashMap<String, TimeEntryRec> timeEntries = new LinkedHashMap<String, TimeEntryRec>();
    private final LinkedHashMap<String, ExpenseEntryRec> expenseEntries = new LinkedHashMap<String, ExpenseEntryRec>();
    private final LinkedHashMap<String, InvoiceRec> invoices = new LinkedHashMap<String, InvoiceRec>();
    private final LinkedHashMap<String, TrustTxnRec> trustTxns = new LinkedHashMap<String, TrustTxnRec>();
    private final LinkedHashMap<String, PaymentRec> payments = new LinkedHashMap<String, PaymentRec>();
    private final ArrayList<JournalEntryRec> journals = new ArrayList<JournalEntryRec>();

    public static billing_accounting inMemory() {
        return new billing_accounting();
    }

    public billing_accounting() {
        this(Clock.systemUTC());
    }

    billing_accounting(Clock clock) {
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public static final class TimeEntryRec {
        public final String uuid;
        public final String source; // local|clio|...
        public final String sourceId;
        public final String matterUuid;
        public final String userUuid;
        public final String activityCode;
        public final String note;
        public final int minutes;
        public final long rateCents;
        public final String currency;
        public final boolean billable;
        public final String workedAt;
        public final String createdAt;
        public final String updatedAt;
        public final String billedInvoiceUuid;

        public TimeEntryRec(String uuid,
                            String source,
                            String sourceId,
                            String matterUuid,
                            String userUuid,
                            String activityCode,
                            String note,
                            int minutes,
                            long rateCents,
                            String currency,
                            boolean billable,
                            String workedAt,
                            String createdAt,
                            String updatedAt,
                            String billedInvoiceUuid) {
            this.uuid = safe(uuid);
            this.source = safe(source).trim().isBlank() ? "local" : safe(source).trim().toLowerCase(Locale.ROOT);
            this.sourceId = safe(sourceId).trim();
            this.matterUuid = safe(matterUuid);
            this.userUuid = safe(userUuid);
            this.activityCode = safe(activityCode);
            this.note = safe(note);
            this.minutes = Math.max(0, minutes);
            this.rateCents = Math.max(0L, rateCents);
            this.currency = currencyCode(currency);
            this.billable = billable;
            this.workedAt = safe(workedAt);
            this.createdAt = safe(createdAt);
            this.updatedAt = safe(updatedAt);
            this.billedInvoiceUuid = safe(billedInvoiceUuid);
        }
    }

    public static final class ExpenseEntryRec {
        public final String uuid;
        public final String source; // local|clio|...
        public final String sourceId;
        public final String matterUuid;
        public final String description;
        public final long amountCents;
        public final long taxCents;
        public final String currency;
        public final boolean billable;
        public final String incurredAt;
        public final String createdAt;
        public final String updatedAt;
        public final String billedInvoiceUuid;

        public ExpenseEntryRec(String uuid,
                               String source,
                               String sourceId,
                               String matterUuid,
                               String description,
                               long amountCents,
                               long taxCents,
                               String currency,
                               boolean billable,
                               String incurredAt,
                               String createdAt,
                               String updatedAt,
                               String billedInvoiceUuid) {
            this.uuid = safe(uuid);
            this.source = safe(source).trim().isBlank() ? "local" : safe(source).trim().toLowerCase(Locale.ROOT);
            this.sourceId = safe(sourceId).trim();
            this.matterUuid = safe(matterUuid);
            this.description = safe(description);
            this.amountCents = Math.max(0L, amountCents);
            this.taxCents = Math.max(0L, taxCents);
            this.currency = currencyCode(currency);
            this.billable = billable;
            this.incurredAt = safe(incurredAt);
            this.createdAt = safe(createdAt);
            this.updatedAt = safe(updatedAt);
            this.billedInvoiceUuid = safe(billedInvoiceUuid);
        }
    }

    public static final class InvoiceLineRec {
        public final String uuid;
        public final String type; // time|expense|adjustment
        public final String sourceUuid;
        public final String description;
        public final long quantity;
        public final long unitAmountCents;
        public final long taxCents;
        public final long totalCents;

        public InvoiceLineRec(String uuid,
                              String type,
                              String sourceUuid,
                              String description,
                              long quantity,
                              long unitAmountCents,
                              long taxCents,
                              long totalCents) {
            this.uuid = safe(uuid);
            this.type = safe(type);
            this.sourceUuid = safe(sourceUuid);
            this.description = safe(description);
            this.quantity = Math.max(0L, quantity);
            this.unitAmountCents = Math.max(0L, unitAmountCents);
            this.taxCents = Math.max(0L, taxCents);
            this.totalCents = Math.max(0L, totalCents);
        }
    }

    public static final class InvoiceRec {
        public final String uuid;
        public final String source; // local|clio|...
        public final String sourceId;
        public final String matterUuid;
        public final String status;
        public final String currency;
        public final String issuedAt;
        public final String dueAt;
        public final String createdAt;
        public final String updatedAt;
        public final List<InvoiceLineRec> lines;
        public final long subtotalCents;
        public final long taxCents;
        public final long totalCents;
        public final long paidCents;
        public final long outstandingCents;
        public final List<String> sourceTimeEntryUuids;
        public final List<String> sourceExpenseEntryUuids;
        public final String voidReason;

        public InvoiceRec(String uuid,
                          String source,
                          String sourceId,
                          String matterUuid,
                          String status,
                          String currency,
                          String issuedAt,
                          String dueAt,
                          String createdAt,
                          String updatedAt,
                          List<InvoiceLineRec> lines,
                          long subtotalCents,
                          long taxCents,
                          long totalCents,
                          long paidCents,
                          long outstandingCents,
                          List<String> sourceTimeEntryUuids,
                          List<String> sourceExpenseEntryUuids,
                          String voidReason) {
            this.uuid = safe(uuid);
            this.source = safe(source).trim().isBlank() ? "local" : safe(source).trim().toLowerCase(Locale.ROOT);
            this.sourceId = safe(sourceId).trim();
            this.matterUuid = safe(matterUuid);
            this.status = safe(status);
            this.currency = currencyCode(currency);
            this.issuedAt = safe(issuedAt);
            this.dueAt = safe(dueAt);
            this.createdAt = safe(createdAt);
            this.updatedAt = safe(updatedAt);
            this.lines = lines == null ? List.of() : List.copyOf(lines);
            this.subtotalCents = Math.max(0L, subtotalCents);
            this.taxCents = Math.max(0L, taxCents);
            this.totalCents = Math.max(0L, totalCents);
            this.paidCents = Math.max(0L, paidCents);
            this.outstandingCents = Math.max(0L, outstandingCents);
            this.sourceTimeEntryUuids = sourceTimeEntryUuids == null ? List.of() : List.copyOf(sourceTimeEntryUuids);
            this.sourceExpenseEntryUuids = sourceExpenseEntryUuids == null ? List.of() : List.copyOf(sourceExpenseEntryUuids);
            this.voidReason = safe(voidReason);
        }
    }

    public static final class TrustTxnRec {
        public final String uuid;
        public final String source; // local|clio|...
        public final String sourceId;
        public final String matterUuid;
        public final String invoiceUuid;
        public final String kind;
        public final long amountCents;
        public final String currency;
        public final String postedAt;
        public final String reference;
        public final String createdAt;

        public TrustTxnRec(String uuid,
                           String source,
                           String sourceId,
                           String matterUuid,
                           String invoiceUuid,
                           String kind,
                           long amountCents,
                           String currency,
                           String postedAt,
                           String reference,
                           String createdAt) {
            this.uuid = safe(uuid);
            this.source = safe(source).trim().isBlank() ? "local" : safe(source).trim().toLowerCase(Locale.ROOT);
            this.sourceId = safe(sourceId).trim();
            this.matterUuid = safe(matterUuid);
            this.invoiceUuid = safe(invoiceUuid);
            this.kind = safe(kind);
            this.amountCents = Math.max(0L, amountCents);
            this.currency = currencyCode(currency);
            this.postedAt = safe(postedAt);
            this.reference = safe(reference);
            this.createdAt = safe(createdAt);
        }
    }

    public static final class PaymentRec {
        public final String uuid;
        public final String source; // local|clio|...
        public final String sourceId;
        public final String matterUuid;
        public final String invoiceUuid;
        public final String kind;
        public final long amountCents;
        public final String currency;
        public final String postedAt;
        public final String reference;
        public final String createdAt;

        public PaymentRec(String uuid,
                          String source,
                          String sourceId,
                          String matterUuid,
                          String invoiceUuid,
                          String kind,
                          long amountCents,
                          String currency,
                          String postedAt,
                          String reference,
                          String createdAt) {
            this.uuid = safe(uuid);
            this.source = safe(source).trim().isBlank() ? "local" : safe(source).trim().toLowerCase(Locale.ROOT);
            this.sourceId = safe(sourceId).trim();
            this.matterUuid = safe(matterUuid);
            this.invoiceUuid = safe(invoiceUuid);
            this.kind = safe(kind);
            this.amountCents = Math.max(0L, amountCents);
            this.currency = currencyCode(currency);
            this.postedAt = safe(postedAt);
            this.reference = safe(reference);
            this.createdAt = safe(createdAt);
        }
    }

    public static final class JournalLineRec {
        public final String accountCode;
        public final String matterUuid;
        public final String invoiceUuid;
        public final long debitCents;
        public final long creditCents;
        public final String memo;

        public JournalLineRec(String accountCode,
                              String matterUuid,
                              String invoiceUuid,
                              long debitCents,
                              long creditCents,
                              String memo) {
            this.accountCode = safe(accountCode);
            this.matterUuid = safe(matterUuid);
            this.invoiceUuid = safe(invoiceUuid);
            this.debitCents = Math.max(0L, debitCents);
            this.creditCents = Math.max(0L, creditCents);
            this.memo = safe(memo);
        }
    }

    public static final class JournalEntryRec {
        public final String uuid;
        public final String postedAt;
        public final String referenceType;
        public final String referenceUuid;
        public final String description;
        public final List<JournalLineRec> lines;
        public final long totalDebitCents;
        public final long totalCreditCents;

        public JournalEntryRec(String uuid,
                               String postedAt,
                               String referenceType,
                               String referenceUuid,
                               String description,
                               List<JournalLineRec> lines,
                               long totalDebitCents,
                               long totalCreditCents) {
            this.uuid = safe(uuid);
            this.postedAt = safe(postedAt);
            this.referenceType = safe(referenceType);
            this.referenceUuid = safe(referenceUuid);
            this.description = safe(description);
            this.lines = lines == null ? List.of() : List.copyOf(lines);
            this.totalDebitCents = Math.max(0L, totalDebitCents);
            this.totalCreditCents = Math.max(0L, totalCreditCents);
        }
    }

    public static final class MatterTrustBalanceRec {
        public final String matterUuid;
        public final long balanceCents;

        public MatterTrustBalanceRec(String matterUuid, long balanceCents) {
            this.matterUuid = safe(matterUuid);
            this.balanceCents = balanceCents;
        }
    }

    public static final class TrustReconciliationRec {
        public final String statementDate;
        public final long statementEndingBalanceCents;
        public final long bookTrustBankBalanceCents;
        public final long clientLedgerTotalCents;
        public final long trustLiabilityBalanceCents;
        public final long bankVsBookDeltaCents;
        public final long bookVsClientLedgerDeltaCents;
        public final long bookVsTrustLiabilityDeltaCents;
        public final boolean balanced;

        public TrustReconciliationRec(String statementDate,
                                      long statementEndingBalanceCents,
                                      long bookTrustBankBalanceCents,
                                      long clientLedgerTotalCents,
                                      long trustLiabilityBalanceCents,
                                      long bankVsBookDeltaCents,
                                      long bookVsClientLedgerDeltaCents,
                                      long bookVsTrustLiabilityDeltaCents,
                                      boolean balanced) {
            this.statementDate = safe(statementDate);
            this.statementEndingBalanceCents = statementEndingBalanceCents;
            this.bookTrustBankBalanceCents = bookTrustBankBalanceCents;
            this.clientLedgerTotalCents = clientLedgerTotalCents;
            this.trustLiabilityBalanceCents = trustLiabilityBalanceCents;
            this.bankVsBookDeltaCents = bankVsBookDeltaCents;
            this.bookVsClientLedgerDeltaCents = bookVsClientLedgerDeltaCents;
            this.bookVsTrustLiabilityDeltaCents = bookVsTrustLiabilityDeltaCents;
            this.balanced = balanced;
        }
    }

    public static final class ComplianceSnapshot {
        public final TrustReconciliationRec trustReconciliation;
        public final List<String> violations;

        public ComplianceSnapshot(TrustReconciliationRec trustReconciliation, List<String> violations) {
            this.trustReconciliation = trustReconciliation;
            this.violations = violations == null ? List.of() : List.copyOf(violations);
        }
    }

    public TimeEntryRec createTimeEntry(String matterUuid,
                                        String userUuid,
                                        String activityCode,
                                        String note,
                                        int minutes,
                                        long rateCents,
                                        String currency,
                                        boolean billable,
                                        String workedAt) {
        String matter = requireToken(matterUuid, "matterUuid");
        if (minutes <= 0) throw new IllegalArgumentException("minutes must be > 0");
        if (rateCents < 0) throw new IllegalArgumentException("rateCents must be >= 0");

        String now = nowIso();
        TimeEntryRec rec = new TimeEntryRec(
                uuid(),
                "local",
                "",
                matter,
                safe(userUuid).trim(),
                safe(activityCode).trim(),
                safe(note).trim(),
                minutes,
                rateCents,
                currencyCode(currency),
                billable,
                safe(workedAt).isBlank() ? now : safe(workedAt).trim(),
                now,
                now,
                ""
        );

        synchronized (lock) {
            timeEntries.put(rec.uuid, rec);
        }
        return rec;
    }

    /**
     * Upsert a time entry sourced from Clio activity data.
     * Existing billed entries are left untouched to avoid invoice drift.
     */
    public TimeEntryRec importOrUpdateClioTimeEntry(String clioActivityId,
                                                    String matterUuid,
                                                    String userUuid,
                                                    String activityCode,
                                                    String note,
                                                    int minutes,
                                                    long rateCents,
                                                    String currency,
                                                    boolean billable,
                                                    String workedAt) {
        String externalId = requireToken(clioActivityId, "clioActivityId");
        String matter = requireToken(matterUuid, "matterUuid");
        if (minutes <= 0) throw new IllegalArgumentException("minutes must be > 0");
        if (rateCents < 0L) throw new IllegalArgumentException("rateCents must be >= 0");

        synchronized (lock) {
            TimeEntryRec existing = findTimeEntryBySourceLocked("clio", externalId);
            String now = nowIso();
            if (existing != null && !safe(existing.billedInvoiceUuid).isBlank()) {
                return existing;
            }

            String uuid = existing == null ? uuid() : existing.uuid;
            String createdAt = existing == null ? now : existing.createdAt;
            String billedInvoiceUuid = existing == null ? "" : existing.billedInvoiceUuid;

            TimeEntryRec upserted = new TimeEntryRec(
                    uuid,
                    "clio",
                    externalId,
                    matter,
                    safe(userUuid).trim(),
                    safe(activityCode).trim(),
                    safe(note).trim(),
                    minutes,
                    rateCents,
                    currencyCode(currency),
                    billable,
                    safe(workedAt).isBlank() ? now : safe(workedAt).trim(),
                    createdAt,
                    now,
                    billedInvoiceUuid
            );
            timeEntries.put(upserted.uuid, upserted);
            return upserted;
        }
    }

    public ExpenseEntryRec createExpenseEntry(String matterUuid,
                                              String description,
                                              long amountCents,
                                              long taxCents,
                                              String currency,
                                              boolean billable,
                                              String incurredAt) {
        String matter = requireToken(matterUuid, "matterUuid");
        String desc = requireToken(description, "description");
        if (amountCents <= 0L) throw new IllegalArgumentException("amountCents must be > 0");
        if (taxCents < 0L) throw new IllegalArgumentException("taxCents must be >= 0");

        String now = nowIso();
        ExpenseEntryRec rec = new ExpenseEntryRec(
                uuid(),
                "local",
                "",
                matter,
                desc,
                amountCents,
                taxCents,
                currencyCode(currency),
                billable,
                safe(incurredAt).isBlank() ? now : safe(incurredAt).trim(),
                now,
                now,
                ""
        );

        synchronized (lock) {
            expenseEntries.put(rec.uuid, rec);
        }
        return rec;
    }

    /**
     * Upsert a billable expense sourced from Clio expense data.
     * Existing billed entries are left untouched to avoid invoice drift.
     */
    public ExpenseEntryRec importOrUpdateClioExpenseEntry(String clioExpenseId,
                                                          String matterUuid,
                                                          String description,
                                                          long amountCents,
                                                          long taxCents,
                                                          String currency,
                                                          boolean billable,
                                                          String incurredAt) {
        String externalId = requireToken(clioExpenseId, "clioExpenseId");
        String matter = requireToken(matterUuid, "matterUuid");
        String desc = requireToken(description, "description");
        if (amountCents <= 0L) throw new IllegalArgumentException("amountCents must be > 0");
        if (taxCents < 0L) throw new IllegalArgumentException("taxCents must be >= 0");

        synchronized (lock) {
            ExpenseEntryRec existing = findExpenseEntryBySourceLocked("clio", externalId);
            String now = nowIso();
            if (existing != null && !safe(existing.billedInvoiceUuid).isBlank()) {
                return existing;
            }

            String uuid = existing == null ? uuid() : existing.uuid;
            String createdAt = existing == null ? now : existing.createdAt;
            String billedInvoiceUuid = existing == null ? "" : existing.billedInvoiceUuid;

            ExpenseEntryRec upserted = new ExpenseEntryRec(
                    uuid,
                    "clio",
                    externalId,
                    matter,
                    desc,
                    amountCents,
                    taxCents,
                    currencyCode(currency),
                    billable,
                    safe(incurredAt).isBlank() ? now : safe(incurredAt).trim(),
                    createdAt,
                    now,
                    billedInvoiceUuid
            );
            expenseEntries.put(upserted.uuid, upserted);
            return upserted;
        }
    }

    public InvoiceRec draftInvoiceForMatter(String matterUuid,
                                            String issueDate,
                                            String dueDate,
                                            String currency) {
        String matter = requireToken(matterUuid, "matterUuid");
        String ccy = currencyCode(currency);
        String now = nowIso();

        ArrayList<InvoiceLineRec> lines = new ArrayList<InvoiceLineRec>();
        ArrayList<String> timeIds = new ArrayList<String>();
        ArrayList<String> expenseIds = new ArrayList<String>();
        long subtotal = 0L;
        long tax = 0L;

        synchronized (lock) {
            for (TimeEntryRec t : timeEntries.values()) {
                if (t == null) continue;
                if (!matter.equals(t.matterUuid)) continue;
                if (!t.billable) continue;
                if (!safe(t.billedInvoiceUuid).isBlank()) continue;

                long lineAmount = proratedTimeAmountCents(t.rateCents, t.minutes);
                String detail = safe(t.activityCode).isBlank() ? "Billable time" : t.activityCode;
                if (!safe(t.note).isBlank()) detail += " | " + t.note;

                lines.add(new InvoiceLineRec(
                        uuid(),
                        "time",
                        t.uuid,
                        detail,
                        t.minutes,
                        t.rateCents,
                        0L,
                        lineAmount
                ));
                subtotal += lineAmount;
                timeIds.add(t.uuid);
            }

            for (ExpenseEntryRec e : expenseEntries.values()) {
                if (e == null) continue;
                if (!matter.equals(e.matterUuid)) continue;
                if (!e.billable) continue;
                if (!safe(e.billedInvoiceUuid).isBlank()) continue;

                long lineTotal = e.amountCents + e.taxCents;
                lines.add(new InvoiceLineRec(
                        uuid(),
                        "expense",
                        e.uuid,
                        e.description,
                        1L,
                        e.amountCents,
                        e.taxCents,
                        lineTotal
                ));
                subtotal += e.amountCents;
                tax += e.taxCents;
                expenseIds.add(e.uuid);
            }

            if (lines.isEmpty()) {
                throw new IllegalStateException("No unbilled billable entries for matter.");
            }

            long total = subtotal + tax;
            InvoiceRec draft = new InvoiceRec(
                    uuid(),
                    "local",
                    "",
                    matter,
                    INVOICE_DRAFT,
                    ccy,
                    safe(issueDate).isBlank() ? now : safe(issueDate).trim(),
                    safe(dueDate).isBlank() ? now : safe(dueDate).trim(),
                    now,
                    now,
                    lines,
                    subtotal,
                    tax,
                    total,
                    0L,
                    total,
                    timeIds,
                    expenseIds,
                    ""
            );
            invoices.put(draft.uuid, draft);
            return draft;
        }
    }

    public InvoiceRec finalizeInvoice(String invoiceUuid) {
        String id = requireToken(invoiceUuid, "invoiceUuid");
        synchronized (lock) {
            InvoiceRec cur = invoices.get(id);
            if (cur == null) throw new IllegalArgumentException("invoice not found");
            if (!INVOICE_DRAFT.equals(cur.status)) {
                throw new IllegalStateException("Only draft invoices can be finalized.");
            }

            for (String timeId : cur.sourceTimeEntryUuids) {
                TimeEntryRec t = timeEntries.get(safe(timeId));
                if (t == null) continue;
                String linked = safe(t.billedInvoiceUuid).trim();
                if (!linked.isBlank() && !cur.uuid.equals(linked)) {
                    throw new IllegalStateException("Time entry already billed by another invoice: " + t.uuid);
                }
            }
            for (String expenseId : cur.sourceExpenseEntryUuids) {
                ExpenseEntryRec e = expenseEntries.get(safe(expenseId));
                if (e == null) continue;
                String linked = safe(e.billedInvoiceUuid).trim();
                if (!linked.isBlank() && !cur.uuid.equals(linked)) {
                    throw new IllegalStateException("Expense already billed by another invoice: " + e.uuid);
                }
            }

            String now = nowIso();

            for (String timeId : cur.sourceTimeEntryUuids) {
                TimeEntryRec t = timeEntries.get(safe(timeId));
                if (t == null) continue;
                timeEntries.put(t.uuid, new TimeEntryRec(
                        t.uuid, t.source, t.sourceId, t.matterUuid, t.userUuid, t.activityCode, t.note, t.minutes, t.rateCents, t.currency, t.billable,
                        t.workedAt, t.createdAt, now, cur.uuid
                ));
            }
            for (String expenseId : cur.sourceExpenseEntryUuids) {
                ExpenseEntryRec e = expenseEntries.get(safe(expenseId));
                if (e == null) continue;
                expenseEntries.put(e.uuid, new ExpenseEntryRec(
                        e.uuid, e.source, e.sourceId, e.matterUuid, e.description, e.amountCents, e.taxCents, e.currency, e.billable,
                        e.incurredAt, e.createdAt, now, cur.uuid
                ));
            }

            ArrayList<JournalLineRec> lines = new ArrayList<JournalLineRec>();
            lines.add(new JournalLineRec(
                    ACCT_ACCOUNTS_RECEIVABLE, cur.matterUuid, cur.uuid, cur.totalCents, 0L, "Invoice finalized"
            ));
            lines.add(new JournalLineRec(
                    ACCT_FEE_REVENUE, cur.matterUuid, cur.uuid, 0L, cur.subtotalCents, "Earned fees and reimbursable expenses"
            ));
            if (cur.taxCents > 0L) {
                lines.add(new JournalLineRec(
                        ACCT_SALES_TAX_PAYABLE, cur.matterUuid, cur.uuid, 0L, cur.taxCents, "Collected/collectible sales tax"
                ));
            }
            postJournalLocked("invoice.finalized", cur.uuid, "Finalize invoice " + cur.uuid, lines, now);

            InvoiceRec next = new InvoiceRec(
                    cur.uuid,
                    cur.source,
                    cur.sourceId,
                    cur.matterUuid,
                    INVOICE_ISSUED,
                    cur.currency,
                    cur.issuedAt,
                    cur.dueAt,
                    cur.createdAt,
                    now,
                    cur.lines,
                    cur.subtotalCents,
                    cur.taxCents,
                    cur.totalCents,
                    cur.paidCents,
                    cur.outstandingCents,
                    cur.sourceTimeEntryUuids,
                    cur.sourceExpenseEntryUuids,
                    cur.voidReason
            );
            invoices.put(next.uuid, next);
            return next;
        }
    }

    public InvoiceRec linkInvoiceToClioBill(String invoiceUuid, String clioBillId) {
        String id = requireToken(invoiceUuid, "invoiceUuid");
        String externalId = requireToken(clioBillId, "clioBillId");
        synchronized (lock) {
            InvoiceRec cur = invoices.get(id);
            if (cur == null) throw new IllegalArgumentException("invoice not found");
            InvoiceRec linked = new InvoiceRec(
                    cur.uuid,
                    "clio",
                    externalId,
                    cur.matterUuid,
                    cur.status,
                    cur.currency,
                    cur.issuedAt,
                    cur.dueAt,
                    cur.createdAt,
                    nowIso(),
                    cur.lines,
                    cur.subtotalCents,
                    cur.taxCents,
                    cur.totalCents,
                    cur.paidCents,
                    cur.outstandingCents,
                    cur.sourceTimeEntryUuids,
                    cur.sourceExpenseEntryUuids,
                    cur.voidReason
            );
            invoices.put(linked.uuid, linked);
            return linked;
        }
    }

    /**
     * Upsert a Clio bill summary into local invoice state.
     * This mirrors external bill state and intentionally does not post journals.
     */
    public InvoiceRec importOrUpdateClioInvoiceSummary(String clioBillId,
                                                       String matterUuid,
                                                       String issueDate,
                                                       String dueDate,
                                                       String currency,
                                                       long subtotalCents,
                                                       long taxCents,
                                                       long paidCents,
                                                       String clioState) {
        String externalId = requireToken(clioBillId, "clioBillId");
        String matter = requireToken(matterUuid, "matterUuid");
        long subtotal = Math.max(0L, subtotalCents);
        long tax = Math.max(0L, taxCents);
        long total = subtotal + tax;
        long paid = Math.max(0L, Math.min(Math.max(0L, paidCents), total));
        long outstanding = total - paid;
        String now = nowIso();
        String status = invoiceStatusFromExternalState(clioState, paid, outstanding);

        synchronized (lock) {
            InvoiceRec existing = findInvoiceBySourceLocked("clio", externalId);
            String uuid = existing == null ? uuid() : existing.uuid;
            String createdAt = existing == null ? now : existing.createdAt;
            String issuedAt = safe(issueDate).isBlank() ? now : safe(issueDate).trim();
            String dueAt = safe(dueDate).isBlank() ? issuedAt : safe(dueDate).trim();

            ArrayList<InvoiceLineRec> lines = new ArrayList<InvoiceLineRec>();
            lines.add(new InvoiceLineRec(
                    uuid(),
                    "adjustment",
                    externalId,
                    "Imported Clio bill " + externalId,
                    1L,
                    subtotal,
                    tax,
                    total
            ));

            InvoiceRec upserted = new InvoiceRec(
                    uuid,
                    "clio",
                    externalId,
                    matter,
                    status,
                    currencyCode(currency),
                    issuedAt,
                    dueAt,
                    createdAt,
                    now,
                    lines,
                    subtotal,
                    tax,
                    total,
                    paid,
                    outstanding,
                    List.of(),
                    List.of(),
                    INVOICE_VOID.equals(status) ? "Imported void bill state from Clio" : ""
            );
            invoices.put(upserted.uuid, upserted);
            return upserted;
        }
    }

    public TrustTxnRec recordTrustDeposit(String matterUuid,
                                          long amountCents,
                                          String currency,
                                          String postedAt,
                                          String reference) {
        String matter = requireToken(matterUuid, "matterUuid");
        if (amountCents <= 0L) throw new IllegalArgumentException("amountCents must be > 0");
        String now = nowIso();
        String when = safe(postedAt).isBlank() ? now : safe(postedAt).trim();

        synchronized (lock) {
            TrustTxnRec txn = new TrustTxnRec(
                    uuid(),
                    "local",
                    "",
                    matter,
                    "",
                    TRUST_DEPOSIT,
                    amountCents,
                    currencyCode(currency),
                    when,
                    safe(reference).trim(),
                    now
            );
            trustTxns.put(txn.uuid, txn);

            ArrayList<JournalLineRec> lines = new ArrayList<JournalLineRec>();
            lines.add(new JournalLineRec(
                    ACCT_TRUST_BANK, matter, "", amountCents, 0L, "Trust deposit"
            ));
            lines.add(new JournalLineRec(
                    ACCT_TRUST_LIABILITY, matter, "", 0L, amountCents, "Client trust liability increase"
            ));
            postJournalLocked("trust.deposit", txn.uuid, "Trust deposit", lines, when);
            return txn;
        }
    }

    public TrustTxnRec importClioTrustDeposit(String clioTrustTransactionId,
                                              String matterUuid,
                                              long amountCents,
                                              String currency,
                                              String postedAt,
                                              String reference) {
        String externalId = requireToken(clioTrustTransactionId, "clioTrustTransactionId");
        synchronized (lock) {
            TrustTxnRec existing = findTrustTxnBySourceLocked("clio", externalId);
            if (existing != null) return existing;
        }
        TrustTxnRec created = recordTrustDeposit(matterUuid, amountCents, currency, postedAt, reference);
        synchronized (lock) {
            TrustTxnRec cur = trustTxns.get(created.uuid);
            TrustTxnRec linked = new TrustTxnRec(
                    cur.uuid,
                    "clio",
                    externalId,
                    cur.matterUuid,
                    cur.invoiceUuid,
                    cur.kind,
                    cur.amountCents,
                    cur.currency,
                    cur.postedAt,
                    cur.reference,
                    cur.createdAt
            );
            trustTxns.put(linked.uuid, linked);
            return linked;
        }
    }

    public TrustTxnRec applyTrustToInvoice(String invoiceUuid,
                                           long amountCents,
                                           String postedAt,
                                           String reference) {
        String id = requireToken(invoiceUuid, "invoiceUuid");
        if (amountCents <= 0L) throw new IllegalArgumentException("amountCents must be > 0");
        String now = nowIso();
        String when = safe(postedAt).isBlank() ? now : safe(postedAt).trim();

        synchronized (lock) {
            InvoiceRec inv = invoices.get(id);
            if (inv == null) throw new IllegalArgumentException("invoice not found");
            if (!(INVOICE_ISSUED.equals(inv.status) || INVOICE_PARTIALLY_PAID.equals(inv.status))) {
                throw new IllegalStateException("Invoice is not payable from trust in current status.");
            }
            if (amountCents > inv.outstandingCents) {
                throw new IllegalArgumentException("amount exceeds invoice outstanding balance");
            }

            long trustBalance = matterTrustBalanceLocked(inv.matterUuid);
            if (amountCents > trustBalance) {
                throw new IllegalArgumentException("amount exceeds matter trust balance");
            }

            TrustTxnRec trust = new TrustTxnRec(
                    uuid(),
                    "local",
                    "",
                    inv.matterUuid,
                    inv.uuid,
                    TRUST_APPLY_TO_INVOICE,
                    amountCents,
                    inv.currency,
                    when,
                    safe(reference).trim(),
                    now
            );
            trustTxns.put(trust.uuid, trust);

            PaymentRec payment = new PaymentRec(
                    uuid(),
                    "local",
                    "",
                    inv.matterUuid,
                    inv.uuid,
                    PAYMENT_TRUST,
                    amountCents,
                    inv.currency,
                    when,
                    safe(reference).trim(),
                    now
            );
            payments.put(payment.uuid, payment);

            ArrayList<JournalLineRec> trustTransfer = new ArrayList<JournalLineRec>();
            trustTransfer.add(new JournalLineRec(
                    ACCT_TRUST_LIABILITY, inv.matterUuid, inv.uuid, amountCents, 0L, "Trust liability reduction for earned fee transfer"
            ));
            trustTransfer.add(new JournalLineRec(
                    ACCT_TRUST_BANK, inv.matterUuid, inv.uuid, 0L, amountCents, "Trust bank outflow"
            ));
            postJournalLocked("trust.apply", trust.uuid, "Apply trust to invoice", trustTransfer, when);

            ArrayList<JournalLineRec> paymentLines = new ArrayList<JournalLineRec>();
            paymentLines.add(new JournalLineRec(
                    ACCT_OPERATING_BANK, inv.matterUuid, inv.uuid, amountCents, 0L, "Operating bank inflow from earned trust funds"
            ));
            paymentLines.add(new JournalLineRec(
                    ACCT_ACCOUNTS_RECEIVABLE, inv.matterUuid, inv.uuid, 0L, amountCents, "Accounts receivable reduction"
            ));
            postJournalLocked("payment.trust", payment.uuid, "Trust-funded invoice payment", paymentLines, when);

            InvoiceRec updated = applyPaymentLocked(inv, amountCents, when);
            invoices.put(updated.uuid, updated);
            return trust;
        }
    }

    public PaymentRec recordOperatingPayment(String invoiceUuid,
                                             long amountCents,
                                             String postedAt,
                                             String reference) {
        String id = requireToken(invoiceUuid, "invoiceUuid");
        if (amountCents <= 0L) throw new IllegalArgumentException("amountCents must be > 0");
        String now = nowIso();
        String when = safe(postedAt).isBlank() ? now : safe(postedAt).trim();

        synchronized (lock) {
            InvoiceRec inv = invoices.get(id);
            if (inv == null) throw new IllegalArgumentException("invoice not found");
            if (!(INVOICE_ISSUED.equals(inv.status) || INVOICE_PARTIALLY_PAID.equals(inv.status))) {
                throw new IllegalStateException("Invoice is not payable in current status.");
            }
            if (amountCents > inv.outstandingCents) {
                throw new IllegalArgumentException("amount exceeds invoice outstanding balance");
            }

            PaymentRec payment = new PaymentRec(
                    uuid(),
                    "local",
                    "",
                    inv.matterUuid,
                    inv.uuid,
                    PAYMENT_OPERATING,
                    amountCents,
                    inv.currency,
                    when,
                    safe(reference).trim(),
                    now
            );
            payments.put(payment.uuid, payment);

            ArrayList<JournalLineRec> lines = new ArrayList<JournalLineRec>();
            lines.add(new JournalLineRec(
                    ACCT_OPERATING_BANK, inv.matterUuid, inv.uuid, amountCents, 0L, "Operating payment received"
            ));
            lines.add(new JournalLineRec(
                    ACCT_ACCOUNTS_RECEIVABLE, inv.matterUuid, inv.uuid, 0L, amountCents, "Accounts receivable reduction"
            ));
            postJournalLocked("payment.operating", payment.uuid, "Operating invoice payment", lines, when);

            InvoiceRec updated = applyPaymentLocked(inv, amountCents, when);
            invoices.put(updated.uuid, updated);
            return payment;
        }
    }

    public PaymentRec importClioOperatingPayment(String clioPaymentId,
                                                 String invoiceUuid,
                                                 long amountCents,
                                                 String postedAt,
                                                 String reference) {
        String externalId = requireToken(clioPaymentId, "clioPaymentId");
        synchronized (lock) {
            PaymentRec existing = findPaymentBySourceLocked("clio", externalId);
            if (existing != null) return existing;
        }
        PaymentRec created = recordOperatingPayment(invoiceUuid, amountCents, postedAt, reference);
        synchronized (lock) {
            PaymentRec cur = payments.get(created.uuid);
            PaymentRec linked = new PaymentRec(
                    cur.uuid,
                    "clio",
                    externalId,
                    cur.matterUuid,
                    cur.invoiceUuid,
                    cur.kind,
                    cur.amountCents,
                    cur.currency,
                    cur.postedAt,
                    cur.reference,
                    cur.createdAt
            );
            payments.put(linked.uuid, linked);
            return linked;
        }
    }

    public TrustTxnRec importClioTrustApplication(String clioTrustTransactionId,
                                                  String invoiceUuid,
                                                  long amountCents,
                                                  String postedAt,
                                                  String reference) {
        String externalId = requireToken(clioTrustTransactionId, "clioTrustTransactionId");
        synchronized (lock) {
            TrustTxnRec existing = findTrustTxnBySourceLocked("clio", externalId);
            if (existing != null) return existing;
        }

        TrustTxnRec created = applyTrustToInvoice(invoiceUuid, amountCents, postedAt, reference);
        synchronized (lock) {
            TrustTxnRec cur = trustTxns.get(created.uuid);
            TrustTxnRec linked = new TrustTxnRec(
                    cur.uuid,
                    "clio",
                    externalId,
                    cur.matterUuid,
                    cur.invoiceUuid,
                    cur.kind,
                    cur.amountCents,
                    cur.currency,
                    cur.postedAt,
                    cur.reference,
                    cur.createdAt
            );
            trustTxns.put(linked.uuid, linked);

            PaymentRec payment = findLatestTrustPaymentForInvoiceLocked(cur.invoiceUuid, cur.amountCents);
            if (payment != null) {
                PaymentRec taggedPayment = new PaymentRec(
                        payment.uuid,
                        "clio",
                        externalId,
                        payment.matterUuid,
                        payment.invoiceUuid,
                        payment.kind,
                        payment.amountCents,
                        payment.currency,
                        payment.postedAt,
                        payment.reference,
                        payment.createdAt
                );
                payments.put(taggedPayment.uuid, taggedPayment);
            }
            return linked;
        }
    }

    public TrustTxnRec recordTrustRefund(String matterUuid,
                                         long amountCents,
                                         String currency,
                                         String postedAt,
                                         String reference) {
        String matter = requireToken(matterUuid, "matterUuid");
        if (amountCents <= 0L) throw new IllegalArgumentException("amountCents must be > 0");
        String now = nowIso();
        String when = safe(postedAt).isBlank() ? now : safe(postedAt).trim();

        synchronized (lock) {
            long balance = matterTrustBalanceLocked(matter);
            if (amountCents > balance) {
                throw new IllegalArgumentException("refund exceeds matter trust balance");
            }

            TrustTxnRec txn = new TrustTxnRec(
                    uuid(),
                    "local",
                    "",
                    matter,
                    "",
                    TRUST_REFUND,
                    amountCents,
                    currencyCode(currency),
                    when,
                    safe(reference).trim(),
                    now
            );
            trustTxns.put(txn.uuid, txn);

            ArrayList<JournalLineRec> lines = new ArrayList<JournalLineRec>();
            lines.add(new JournalLineRec(
                    ACCT_TRUST_LIABILITY, matter, "", amountCents, 0L, "Trust liability reduction (refund)"
            ));
            lines.add(new JournalLineRec(
                    ACCT_TRUST_BANK, matter, "", 0L, amountCents, "Trust bank outflow (refund)"
            ));
            postJournalLocked("trust.refund", txn.uuid, "Trust refund", lines, when);
            return txn;
        }
    }

    public InvoiceRec getInvoice(String invoiceUuid) {
        String id = safe(invoiceUuid).trim();
        if (id.isBlank()) return null;
        synchronized (lock) {
            return invoices.get(id);
        }
    }

    public List<TimeEntryRec> listTimeEntriesForMatter(String matterUuid) {
        String matter = safe(matterUuid).trim();
        if (matter.isBlank()) return List.of();
        synchronized (lock) {
            ArrayList<TimeEntryRec> out = new ArrayList<TimeEntryRec>();
            for (TimeEntryRec t : timeEntries.values()) {
                if (t == null) continue;
                if (!matter.equals(safe(t.matterUuid).trim())) continue;
                out.add(t);
            }
            out.sort((a, b) -> compareByIsoThenUuid(a == null ? "" : a.workedAt, b == null ? "" : b.workedAt,
                    a == null ? "" : a.uuid, b == null ? "" : b.uuid));
            return out;
        }
    }

    public List<ExpenseEntryRec> listExpenseEntriesForMatter(String matterUuid) {
        String matter = safe(matterUuid).trim();
        if (matter.isBlank()) return List.of();
        synchronized (lock) {
            ArrayList<ExpenseEntryRec> out = new ArrayList<ExpenseEntryRec>();
            for (ExpenseEntryRec e : expenseEntries.values()) {
                if (e == null) continue;
                if (!matter.equals(safe(e.matterUuid).trim())) continue;
                out.add(e);
            }
            out.sort((a, b) -> compareByIsoThenUuid(a == null ? "" : a.incurredAt, b == null ? "" : b.incurredAt,
                    a == null ? "" : a.uuid, b == null ? "" : b.uuid));
            return out;
        }
    }

    public List<InvoiceRec> listInvoicesForMatter(String matterUuid) {
        String matter = safe(matterUuid).trim();
        if (matter.isBlank()) return List.of();
        synchronized (lock) {
            ArrayList<InvoiceRec> out = new ArrayList<InvoiceRec>();
            for (InvoiceRec i : invoices.values()) {
                if (i == null) continue;
                if (!matter.equals(safe(i.matterUuid).trim())) continue;
                out.add(i);
            }
            out.sort((a, b) -> compareByIsoThenUuid(a == null ? "" : a.issuedAt, b == null ? "" : b.issuedAt,
                    a == null ? "" : a.uuid, b == null ? "" : b.uuid));
            return out;
        }
    }

    public List<PaymentRec> listPaymentsForMatter(String matterUuid) {
        String matter = safe(matterUuid).trim();
        if (matter.isBlank()) return List.of();
        synchronized (lock) {
            ArrayList<PaymentRec> out = new ArrayList<PaymentRec>();
            for (PaymentRec p : payments.values()) {
                if (p == null) continue;
                if (!matter.equals(safe(p.matterUuid).trim())) continue;
                out.add(p);
            }
            out.sort((a, b) -> compareByIsoThenUuid(a == null ? "" : a.postedAt, b == null ? "" : b.postedAt,
                    a == null ? "" : a.uuid, b == null ? "" : b.uuid));
            return out;
        }
    }

    public List<PaymentRec> listPaymentsForInvoice(String invoiceUuid) {
        String invoice = safe(invoiceUuid).trim();
        if (invoice.isBlank()) return List.of();
        synchronized (lock) {
            ArrayList<PaymentRec> out = new ArrayList<PaymentRec>();
            for (PaymentRec p : payments.values()) {
                if (p == null) continue;
                if (!invoice.equals(safe(p.invoiceUuid).trim())) continue;
                out.add(p);
            }
            out.sort((a, b) -> compareByIsoThenUuid(a == null ? "" : a.postedAt, b == null ? "" : b.postedAt,
                    a == null ? "" : a.uuid, b == null ? "" : b.uuid));
            return out;
        }
    }

    public List<TrustTxnRec> listTrustTransactionsForMatter(String matterUuid) {
        String matter = safe(matterUuid).trim();
        if (matter.isBlank()) return List.of();
        synchronized (lock) {
            ArrayList<TrustTxnRec> out = new ArrayList<TrustTxnRec>();
            for (TrustTxnRec t : trustTxns.values()) {
                if (t == null) continue;
                if (!matter.equals(safe(t.matterUuid).trim())) continue;
                out.add(t);
            }
            out.sort((a, b) -> compareByIsoThenUuid(a == null ? "" : a.postedAt, b == null ? "" : b.postedAt,
                    a == null ? "" : a.uuid, b == null ? "" : b.uuid));
            return out;
        }
    }

    public List<TrustTxnRec> listTrustTransactionsForInvoice(String invoiceUuid) {
        String invoice = safe(invoiceUuid).trim();
        if (invoice.isBlank()) return List.of();
        synchronized (lock) {
            ArrayList<TrustTxnRec> out = new ArrayList<TrustTxnRec>();
            for (TrustTxnRec t : trustTxns.values()) {
                if (t == null) continue;
                if (!invoice.equals(safe(t.invoiceUuid).trim())) continue;
                out.add(t);
            }
            out.sort((a, b) -> compareByIsoThenUuid(a == null ? "" : a.postedAt, b == null ? "" : b.postedAt,
                    a == null ? "" : a.uuid, b == null ? "" : b.uuid));
            return out;
        }
    }

    public TimeEntryRec findTimeEntryBySource(String source, String sourceId) {
        String src = safe(source).trim().toLowerCase(Locale.ROOT);
        String id = safe(sourceId).trim();
        if (src.isBlank() || id.isBlank()) return null;
        synchronized (lock) {
            return findTimeEntryBySourceLocked(src, id);
        }
    }

    public ExpenseEntryRec findExpenseEntryBySource(String source, String sourceId) {
        String src = safe(source).trim().toLowerCase(Locale.ROOT);
        String id = safe(sourceId).trim();
        if (src.isBlank() || id.isBlank()) return null;
        synchronized (lock) {
            return findExpenseEntryBySourceLocked(src, id);
        }
    }

    public InvoiceRec findInvoiceBySource(String source, String sourceId) {
        String src = safe(source).trim().toLowerCase(Locale.ROOT);
        String id = safe(sourceId).trim();
        if (src.isBlank() || id.isBlank()) return null;
        synchronized (lock) {
            return findInvoiceBySourceLocked(src, id);
        }
    }

    public TrustTxnRec findTrustTxnBySource(String source, String sourceId) {
        String src = safe(source).trim().toLowerCase(Locale.ROOT);
        String id = safe(sourceId).trim();
        if (src.isBlank() || id.isBlank()) return null;
        synchronized (lock) {
            return findTrustTxnBySourceLocked(src, id);
        }
    }

    public PaymentRec findPaymentBySource(String source, String sourceId) {
        String src = safe(source).trim().toLowerCase(Locale.ROOT);
        String id = safe(sourceId).trim();
        if (src.isBlank() || id.isBlank()) return null;
        synchronized (lock) {
            return findPaymentBySourceLocked(src, id);
        }
    }

    public long matterTrustBalance(String matterUuid) {
        String matter = safe(matterUuid).trim();
        if (matter.isBlank()) return 0L;
        synchronized (lock) {
            return matterTrustBalanceLocked(matter);
        }
    }

    public List<MatterTrustBalanceRec> listMatterTrustBalances() {
        synchronized (lock) {
            LinkedHashMap<String, Long> balances = trustBalancesByMatterLocked();
            ArrayList<MatterTrustBalanceRec> out = new ArrayList<MatterTrustBalanceRec>(balances.size());
            for (Map.Entry<String, Long> e : balances.entrySet()) {
                if (e == null) continue;
                String matter = safe(e.getKey()).trim();
                if (matter.isBlank()) continue;
                out.add(new MatterTrustBalanceRec(matter, e.getValue() == null ? 0L : e.getValue().longValue()));
            }
            out.sort(Comparator.comparing((MatterTrustBalanceRec b) -> safe(b.matterUuid)));
            return out;
        }
    }

    public List<JournalEntryRec> listJournals() {
        synchronized (lock) {
            return List.copyOf(journals);
        }
    }

    public TrustReconciliationRec trustReconciliation(String statementDate, long statementEndingBalanceCents) {
        synchronized (lock) {
            long bookTrustBank = balanceAssetAccountLocked(ACCT_TRUST_BANK);
            long clientLedgerTotal = 0L;
            LinkedHashMap<String, Long> balances = trustBalancesByMatterLocked();
            for (Map.Entry<String, Long> e : balances.entrySet()) {
                if (e == null || e.getValue() == null) continue;
                clientLedgerTotal += e.getValue().longValue();
            }
            long trustLiability = balanceLiabilityAccountLocked(ACCT_TRUST_LIABILITY);

            long bankVsBook = statementEndingBalanceCents - bookTrustBank;
            long bookVsClient = bookTrustBank - clientLedgerTotal;
            long bookVsLiability = bookTrustBank - trustLiability;
            boolean ok = bankVsBook == 0L && bookVsClient == 0L && bookVsLiability == 0L;

            return new TrustReconciliationRec(
                    safe(statementDate).isBlank() ? nowIso() : safe(statementDate).trim(),
                    statementEndingBalanceCents,
                    bookTrustBank,
                    clientLedgerTotal,
                    trustLiability,
                    bankVsBook,
                    bookVsClient,
                    bookVsLiability,
                    ok
            );
        }
    }

    public ComplianceSnapshot complianceSnapshot(String statementDate, long statementEndingBalanceCents) {
        synchronized (lock) {
            TrustReconciliationRec rec = trustReconciliation(statementDate, statementEndingBalanceCents);
            ArrayList<String> violations = new ArrayList<String>();

            for (JournalEntryRec j : journals) {
                if (j == null) continue;
                if (j.totalDebitCents != j.totalCreditCents) {
                    violations.add("Unbalanced journal entry: " + j.uuid);
                }
            }

            LinkedHashMap<String, Long> balances = trustBalancesByMatterLocked();
            for (Map.Entry<String, Long> e : balances.entrySet()) {
                if (e == null || e.getValue() == null) continue;
                if (e.getValue().longValue() < 0L) {
                    violations.add("Negative trust balance for matter: " + safe(e.getKey()));
                }
            }

            for (InvoiceRec i : invoices.values()) {
                if (i == null) continue;
                if (i.outstandingCents < 0L) violations.add("Invoice outstanding is negative: " + i.uuid);
                if (i.paidCents > i.totalCents) violations.add("Invoice paid exceeds total: " + i.uuid);
                if (INVOICE_DRAFT.equals(i.status) && i.paidCents > 0L) {
                    violations.add("Draft invoice has posted payments: " + i.uuid);
                }
            }

            if (!rec.balanced) {
                violations.add("Trust three-way reconciliation is out of balance.");
            }

            return new ComplianceSnapshot(rec, violations);
        }
    }

    private InvoiceRec applyPaymentLocked(InvoiceRec inv, long amountCents, String updatedAt) {
        long paid = inv.paidCents + amountCents;
        long outstanding = inv.totalCents - paid;
        if (outstanding < 0L) throw new IllegalStateException("Invoice overpaid");

        String nextStatus = outstanding == 0L ? INVOICE_PAID : INVOICE_PARTIALLY_PAID;
        if (INVOICE_VOID.equals(inv.status)) throw new IllegalStateException("Cannot apply payment to void invoice.");

        return new InvoiceRec(
                inv.uuid,
                inv.source,
                inv.sourceId,
                inv.matterUuid,
                nextStatus,
                inv.currency,
                inv.issuedAt,
                inv.dueAt,
                inv.createdAt,
                safe(updatedAt).isBlank() ? nowIso() : safe(updatedAt).trim(),
                inv.lines,
                inv.subtotalCents,
                inv.taxCents,
                inv.totalCents,
                paid,
                outstanding,
                inv.sourceTimeEntryUuids,
                inv.sourceExpenseEntryUuids,
                inv.voidReason
        );
    }

    private long matterTrustBalanceLocked(String matterUuid) {
        return trustBalancesByMatterLocked().getOrDefault(safe(matterUuid).trim(), 0L);
    }

    private LinkedHashMap<String, Long> trustBalancesByMatterLocked() {
        LinkedHashMap<String, Long> out = new LinkedHashMap<String, Long>();
        for (TrustTxnRec t : trustTxns.values()) {
            if (t == null) continue;
            String matter = safe(t.matterUuid).trim();
            if (matter.isBlank()) continue;

            long signed = signedTrustAmount(t);
            long prior = out.getOrDefault(matter, 0L);
            out.put(matter, prior + signed);
        }
        return out;
    }

    private long signedTrustAmount(TrustTxnRec t) {
        if (t == null) return 0L;
        String kind = safe(t.kind).trim();
        long amt = Math.max(0L, t.amountCents);
        if (TRUST_DEPOSIT.equals(kind)) return amt;
        if (TRUST_APPLY_TO_INVOICE.equals(kind)) return -amt;
        if (TRUST_REFUND.equals(kind)) return -amt;
        return 0L;
    }

    private TimeEntryRec findTimeEntryBySourceLocked(String source, String sourceId) {
        String src = safe(source).trim().toLowerCase(Locale.ROOT);
        String id = safe(sourceId).trim();
        if (src.isBlank() || id.isBlank()) return null;
        for (TimeEntryRec t : timeEntries.values()) {
            if (t == null) continue;
            if (src.equals(safe(t.source).trim().toLowerCase(Locale.ROOT))
                    && id.equals(safe(t.sourceId).trim())) {
                return t;
            }
        }
        return null;
    }

    private ExpenseEntryRec findExpenseEntryBySourceLocked(String source, String sourceId) {
        String src = safe(source).trim().toLowerCase(Locale.ROOT);
        String id = safe(sourceId).trim();
        if (src.isBlank() || id.isBlank()) return null;
        for (ExpenseEntryRec e : expenseEntries.values()) {
            if (e == null) continue;
            if (src.equals(safe(e.source).trim().toLowerCase(Locale.ROOT))
                    && id.equals(safe(e.sourceId).trim())) {
                return e;
            }
        }
        return null;
    }

    private InvoiceRec findInvoiceBySourceLocked(String source, String sourceId) {
        String src = safe(source).trim().toLowerCase(Locale.ROOT);
        String id = safe(sourceId).trim();
        if (src.isBlank() || id.isBlank()) return null;
        for (InvoiceRec i : invoices.values()) {
            if (i == null) continue;
            if (src.equals(safe(i.source).trim().toLowerCase(Locale.ROOT))
                    && id.equals(safe(i.sourceId).trim())) {
                return i;
            }
        }
        return null;
    }

    private TrustTxnRec findTrustTxnBySourceLocked(String source, String sourceId) {
        String src = safe(source).trim().toLowerCase(Locale.ROOT);
        String id = safe(sourceId).trim();
        if (src.isBlank() || id.isBlank()) return null;
        for (TrustTxnRec t : trustTxns.values()) {
            if (t == null) continue;
            if (src.equals(safe(t.source).trim().toLowerCase(Locale.ROOT))
                    && id.equals(safe(t.sourceId).trim())) {
                return t;
            }
        }
        return null;
    }

    private PaymentRec findPaymentBySourceLocked(String source, String sourceId) {
        String src = safe(source).trim().toLowerCase(Locale.ROOT);
        String id = safe(sourceId).trim();
        if (src.isBlank() || id.isBlank()) return null;
        for (PaymentRec p : payments.values()) {
            if (p == null) continue;
            if (src.equals(safe(p.source).trim().toLowerCase(Locale.ROOT))
                    && id.equals(safe(p.sourceId).trim())) {
                return p;
            }
        }
        return null;
    }

    private PaymentRec findLatestTrustPaymentForInvoiceLocked(String invoiceUuid, long amountCents) {
        String invoice = safe(invoiceUuid).trim();
        if (invoice.isBlank()) return null;
        PaymentRec match = null;
        for (PaymentRec p : payments.values()) {
            if (p == null) continue;
            if (!invoice.equals(safe(p.invoiceUuid).trim())) continue;
            if (!PAYMENT_TRUST.equals(safe(p.kind).trim())) continue;
            if (amountCents > 0L && p.amountCents != amountCents) continue;
            match = p;
        }
        return match;
    }

    private static String invoiceStatusFromExternalState(String externalState, long paidCents, long outstandingCents) {
        String s = safe(externalState).trim().toLowerCase(Locale.ROOT);
        if (s.equals("void") || s.equals("canceled") || s.equals("cancelled")) return INVOICE_VOID;
        if (s.equals("draft")) return INVOICE_DRAFT;
        if (s.equals("paid")) return INVOICE_PAID;
        if (paidCents <= 0L) return INVOICE_ISSUED;
        if (outstandingCents <= 0L) return INVOICE_PAID;
        return INVOICE_PARTIALLY_PAID;
    }

    private static int compareByIsoThenUuid(String isoA, String isoB, String uuidA, String uuidB) {
        int c = safe(isoA).trim().compareTo(safe(isoB).trim());
        if (c != 0) return c;
        return safe(uuidA).compareTo(safe(uuidB));
    }

    private JournalEntryRec postJournalLocked(String referenceType,
                                              String referenceUuid,
                                              String description,
                                              List<JournalLineRec> lines,
                                              String postedAt) {
        List<JournalLineRec> safeLines = lines == null ? List.of() : List.copyOf(lines);
        if (safeLines.isEmpty()) throw new IllegalArgumentException("journal lines are required");

        long dr = 0L;
        long cr = 0L;
        for (JournalLineRec l : safeLines) {
            if (l == null) continue;
            if (l.debitCents > 0L && l.creditCents > 0L) {
                throw new IllegalArgumentException("journal line cannot have both debit and credit");
            }
            if (l.debitCents == 0L && l.creditCents == 0L) {
                throw new IllegalArgumentException("journal line requires debit or credit amount");
            }
            dr += Math.max(0L, l.debitCents);
            cr += Math.max(0L, l.creditCents);
        }
        if (dr != cr) throw new IllegalArgumentException("journal entry is unbalanced");

        JournalEntryRec j = new JournalEntryRec(
                uuid(),
                safe(postedAt).isBlank() ? nowIso() : safe(postedAt).trim(),
                safe(referenceType).trim(),
                safe(referenceUuid).trim(),
                safe(description).trim(),
                safeLines,
                dr,
                cr
        );
        journals.add(j);
        return j;
    }

    private long balanceAssetAccountLocked(String accountCode) {
        long dr = 0L;
        long cr = 0L;
        String account = safe(accountCode).trim();
        for (JournalEntryRec j : journals) {
            if (j == null) continue;
            for (JournalLineRec l : j.lines) {
                if (l == null) continue;
                if (!account.equals(safe(l.accountCode).trim())) continue;
                dr += Math.max(0L, l.debitCents);
                cr += Math.max(0L, l.creditCents);
            }
        }
        return dr - cr;
    }

    private long balanceLiabilityAccountLocked(String accountCode) {
        long dr = 0L;
        long cr = 0L;
        String account = safe(accountCode).trim();
        for (JournalEntryRec j : journals) {
            if (j == null) continue;
            for (JournalLineRec l : j.lines) {
                if (l == null) continue;
                if (!account.equals(safe(l.accountCode).trim())) continue;
                dr += Math.max(0L, l.debitCents);
                cr += Math.max(0L, l.creditCents);
            }
        }
        return cr - dr;
    }

    private static long proratedTimeAmountCents(long hourlyRateCents, int minutes) {
        if (hourlyRateCents <= 0L || minutes <= 0) return 0L;
        return BigDecimal.valueOf(hourlyRateCents)
                .multiply(BigDecimal.valueOf(minutes))
                .divide(BigDecimal.valueOf(60L), 0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    private String nowIso() {
        return Instant.now(clock).toString();
    }

    private static String requireToken(String v, String name) {
        String out = safe(v).trim();
        if (out.isBlank()) throw new IllegalArgumentException(name + " is required");
        return out;
    }

    private static String currencyCode(String code) {
        String c = safe(code).trim().toUpperCase(Locale.ROOT);
        return c.isBlank() ? "USD" : c;
    }

    private static String uuid() {
        return UUID.randomUUID().toString();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
