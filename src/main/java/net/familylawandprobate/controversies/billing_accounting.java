package net.familylawandprobate.controversies;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
    public static final String PAYMENT_PLAN_INSTALLMENT = "payment_plan_installment";

    // Trust disbursement controls
    public static final String DISBURSEMENT_TRUST_APPLY = "trust_apply";
    public static final String DISBURSEMENT_TRUST_REFUND = "trust_refund";
    public static final String APPROVAL_REQUESTED = "requested";
    public static final String APPROVAL_APPROVED = "approved";
    public static final String APPROVAL_REJECTED = "rejected";
    public static final String APPROVAL_EXECUTED = "executed";

    // Payment plan states
    public static final String PAYMENT_PLAN_ACTIVE = "active";
    public static final String PAYMENT_PLAN_COMPLETED = "completed";
    public static final String PAYMENT_PLAN_CANCELLED = "cancelled";

    // Ledger account codes
    public static final String ACCT_TRUST_BANK = "asset:trust_bank";
    public static final String ACCT_OPERATING_BANK = "asset:operating_bank";
    public static final String ACCT_ACCOUNTS_RECEIVABLE = "asset:accounts_receivable";
    public static final String ACCT_TRUST_LIABILITY = "liability:client_trust";
    public static final String ACCT_ACCOUNTS_PAYABLE = "liability:accounts_payable";
    public static final String ACCT_FEE_REVENUE = "revenue:legal_fees";
    public static final String ACCT_REIMBURSED_EXPENSE_REVENUE = "revenue:reimbursed_expenses";
    public static final String ACCT_SERVICE_REVENUE = "revenue:service_income";
    public static final String ACCT_SALES_TAX_PAYABLE = "liability:sales_tax_payable";
    public static final String ACCT_OWNER_EQUITY = "equity:owner_equity";
    public static final String ACCT_RETAINED_EARNINGS = "equity:retained_earnings";
    public static final String ACCT_OFFICE_EXPENSE = "expense:office_expense";

    // GAAP-oriented account families
    public static final String ACCOUNT_ASSET = "asset";
    public static final String ACCOUNT_LIABILITY = "liability";
    public static final String ACCOUNT_EQUITY = "equity";
    public static final String ACCOUNT_REVENUE = "revenue";
    public static final String ACCOUNT_EXPENSE = "expense";

    private final Object lock = new Object();
    private final Clock clock;

    private final LinkedHashMap<String, AccountRec> chartOfAccounts = new LinkedHashMap<String, AccountRec>();
    private final LinkedHashMap<String, TimeEntryRec> timeEntries = new LinkedHashMap<String, TimeEntryRec>();
    private final LinkedHashMap<String, ExpenseEntryRec> expenseEntries = new LinkedHashMap<String, ExpenseEntryRec>();
    private final LinkedHashMap<String, InvoiceRec> invoices = new LinkedHashMap<String, InvoiceRec>();
    private final LinkedHashMap<String, TrustTxnRec> trustTxns = new LinkedHashMap<String, TrustTxnRec>();
    private final LinkedHashMap<String, PaymentRec> payments = new LinkedHashMap<String, PaymentRec>();
    private final LinkedHashMap<String, BillingActivityRec> activities = new LinkedHashMap<String, BillingActivityRec>();
    private final LinkedHashMap<String, TrustDisputeHoldRec> trustDisputeHolds = new LinkedHashMap<String, TrustDisputeHoldRec>();
    private final LinkedHashMap<String, TrustDisbursementApprovalRec> trustDisbursementApprovals = new LinkedHashMap<String, TrustDisbursementApprovalRec>();
    private final LinkedHashMap<String, PaymentPlanRec> paymentPlans = new LinkedHashMap<String, PaymentPlanRec>();
    private final LinkedHashMap<String, MatterTrustPolicyRec> matterTrustPolicies = new LinkedHashMap<String, MatterTrustPolicyRec>();
    private final ArrayList<JournalEntryRec> journals = new ArrayList<JournalEntryRec>();
    private boolean requireManagedActivities = false;

    public static billing_accounting inMemory() {
        return new billing_accounting();
    }

    public billing_accounting() {
        this(app_clock.utcClock());
    }

    billing_accounting(Clock clock) {
        this.clock = clock == null ? app_clock.utcClock() : clock;
        seedDefaultAccounts();
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
        public final String utbmsTaskCode;
        public final String utbmsActivityCode;
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
                            String utbmsTaskCode,
                            String utbmsActivityCode,
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
            this.utbmsTaskCode = normalizeUtbmsTaskCode(utbmsTaskCode);
            this.utbmsActivityCode = normalizeUtbmsActivityCode(utbmsActivityCode);
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
        public final String utbmsExpenseCode;
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
                               String utbmsExpenseCode,
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
            this.utbmsExpenseCode = normalizeUtbmsExpenseCode(utbmsExpenseCode);
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
            this.accountCode = normalizeAccountCode(accountCode);
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

    public static final class MatterTrustPolicyRec {
        public final String matterUuid;
        public final String ioltaAccountName;
        public final String ioltaInstitutionName;
        public final boolean ioltaInstitutionEligible;
        public final String annualCertificationDueDate;
        public final String annualCertificationCompletedAt;
        public final boolean requireDisbursementApproval;
        public final long minimumTrustReserveCents;
        public final String updatedAt;
        public final String updatedBy;

        public MatterTrustPolicyRec(String matterUuid,
                                    String ioltaAccountName,
                                    String ioltaInstitutionName,
                                    boolean ioltaInstitutionEligible,
                                    String annualCertificationDueDate,
                                    String annualCertificationCompletedAt,
                                    boolean requireDisbursementApproval,
                                    long minimumTrustReserveCents,
                                    String updatedAt,
                                    String updatedBy) {
            this.matterUuid = safe(matterUuid);
            this.ioltaAccountName = safe(ioltaAccountName);
            this.ioltaInstitutionName = safe(ioltaInstitutionName);
            this.ioltaInstitutionEligible = ioltaInstitutionEligible;
            this.annualCertificationDueDate = safe(annualCertificationDueDate);
            this.annualCertificationCompletedAt = safe(annualCertificationCompletedAt);
            this.requireDisbursementApproval = requireDisbursementApproval;
            this.minimumTrustReserveCents = Math.max(0L, minimumTrustReserveCents);
            this.updatedAt = safe(updatedAt);
            this.updatedBy = safe(updatedBy);
        }
    }

    public static final class TrustDisputeHoldRec {
        public final String uuid;
        public final String matterUuid;
        public final long amountCents;
        public final String reason;
        public final String placedAt;
        public final String placedBy;
        public final boolean active;
        public final String releasedAt;
        public final String releasedBy;
        public final String resolution;

        public TrustDisputeHoldRec(String uuid,
                                   String matterUuid,
                                   long amountCents,
                                   String reason,
                                   String placedAt,
                                   String placedBy,
                                   boolean active,
                                   String releasedAt,
                                   String releasedBy,
                                   String resolution) {
            this.uuid = safe(uuid);
            this.matterUuid = safe(matterUuid);
            this.amountCents = Math.max(0L, amountCents);
            this.reason = safe(reason);
            this.placedAt = safe(placedAt);
            this.placedBy = safe(placedBy);
            this.active = active;
            this.releasedAt = safe(releasedAt);
            this.releasedBy = safe(releasedBy);
            this.resolution = safe(resolution);
        }
    }

    public static final class TrustDisbursementApprovalRec {
        public final String uuid;
        public final String disbursementType;
        public final String matterUuid;
        public final String invoiceUuid;
        public final long amountCents;
        public final String currency;
        public final String requestedAt;
        public final String requestedBy;
        public final String reason;
        public final String status;
        public final String reviewedAt;
        public final String reviewedBy;
        public final String reviewNotes;
        public final String executedAt;
        public final String trustTxnUuid;

        public TrustDisbursementApprovalRec(String uuid,
                                            String disbursementType,
                                            String matterUuid,
                                            String invoiceUuid,
                                            long amountCents,
                                            String currency,
                                            String requestedAt,
                                            String requestedBy,
                                            String reason,
                                            String status,
                                            String reviewedAt,
                                            String reviewedBy,
                                            String reviewNotes,
                                            String executedAt,
                                            String trustTxnUuid) {
            this.uuid = safe(uuid);
            this.disbursementType = safe(disbursementType);
            this.matterUuid = safe(matterUuid);
            this.invoiceUuid = safe(invoiceUuid);
            this.amountCents = Math.max(0L, amountCents);
            this.currency = currencyCode(currency);
            this.requestedAt = safe(requestedAt);
            this.requestedBy = safe(requestedBy);
            this.reason = safe(reason);
            this.status = safe(status);
            this.reviewedAt = safe(reviewedAt);
            this.reviewedBy = safe(reviewedBy);
            this.reviewNotes = safe(reviewNotes);
            this.executedAt = safe(executedAt);
            this.trustTxnUuid = safe(trustTxnUuid);
        }
    }

    public static final class PaymentPlanRec {
        public final String uuid;
        public final String matterUuid;
        public final String invoiceUuid;
        public final String currency;
        public final int installmentCount;
        public final int cadenceDays;
        public final long installmentAmountCents;
        public final long firstInstallmentExtraCents;
        public final String nextDueAt;
        public final boolean autopay;
        public final String status;
        public final int paymentsPostedCount;
        public final String reference;
        public final String createdAt;
        public final String updatedAt;

        public PaymentPlanRec(String uuid,
                              String matterUuid,
                              String invoiceUuid,
                              String currency,
                              int installmentCount,
                              int cadenceDays,
                              long installmentAmountCents,
                              long firstInstallmentExtraCents,
                              String nextDueAt,
                              boolean autopay,
                              String status,
                              int paymentsPostedCount,
                              String reference,
                              String createdAt,
                              String updatedAt) {
            this.uuid = safe(uuid);
            this.matterUuid = safe(matterUuid);
            this.invoiceUuid = safe(invoiceUuid);
            this.currency = currencyCode(currency);
            this.installmentCount = Math.max(1, installmentCount);
            this.cadenceDays = Math.max(1, cadenceDays);
            this.installmentAmountCents = Math.max(0L, installmentAmountCents);
            this.firstInstallmentExtraCents = Math.max(0L, firstInstallmentExtraCents);
            this.nextDueAt = safe(nextDueAt);
            this.autopay = autopay;
            this.status = safe(status);
            this.paymentsPostedCount = Math.max(0, paymentsPostedCount);
            this.reference = safe(reference);
            this.createdAt = safe(createdAt);
            this.updatedAt = safe(updatedAt);
        }
    }

    public static final class BillingActivityRec {
        public final String code;
        public final String label;
        public final long defaultRateCents;
        public final boolean defaultBillable;
        public final String utbmsTaskCode;
        public final String utbmsActivityCode;
        public final String utbmsExpenseCode;
        public final boolean active;
        public final String createdAt;
        public final String updatedAt;

        public BillingActivityRec(String code,
                                  String label,
                                  long defaultRateCents,
                                  boolean defaultBillable,
                                  String utbmsTaskCode,
                                  String utbmsActivityCode,
                                  String utbmsExpenseCode,
                                  boolean active,
                                  String createdAt,
                                  String updatedAt) {
            this.code = normalizeActivityCode(code);
            this.label = safe(label).trim();
            this.defaultRateCents = Math.max(0L, defaultRateCents);
            this.defaultBillable = defaultBillable;
            this.utbmsTaskCode = normalizeUtbmsTaskCode(utbmsTaskCode);
            this.utbmsActivityCode = normalizeUtbmsActivityCode(utbmsActivityCode);
            this.utbmsExpenseCode = normalizeUtbmsExpenseCode(utbmsExpenseCode);
            this.active = active;
            this.createdAt = safe(createdAt);
            this.updatedAt = safe(updatedAt);
        }
    }

    public static final class AccountRec {
        public final String code;
        public final String name;
        public final String type;
        public final boolean active;
        public final String updatedAt;

        public AccountRec(String code, String name, String type, boolean active, String updatedAt) {
            this.code = normalizeAccountCode(code);
            this.name = safe(name).trim();
            this.type = normalizeAccountType(type);
            this.active = active;
            this.updatedAt = safe(updatedAt).trim();
        }
    }

    public static final class TrialBalanceLineRec {
        public final String accountCode;
        public final String accountName;
        public final String accountType;
        public final long debitCents;
        public final long creditCents;

        public TrialBalanceLineRec(String accountCode,
                                   String accountName,
                                   String accountType,
                                   long debitCents,
                                   long creditCents) {
            this.accountCode = normalizeAccountCode(accountCode);
            this.accountName = safe(accountName).trim();
            this.accountType = normalizeAccountType(accountType);
            this.debitCents = Math.max(0L, debitCents);
            this.creditCents = Math.max(0L, creditCents);
        }
    }

    public static final class TrialBalanceRec {
        public final String asOfDate;
        public final List<TrialBalanceLineRec> lines;
        public final long totalDebitCents;
        public final long totalCreditCents;
        public final boolean balanced;

        public TrialBalanceRec(String asOfDate,
                               List<TrialBalanceLineRec> lines,
                               long totalDebitCents,
                               long totalCreditCents,
                               boolean balanced) {
            this.asOfDate = safe(asOfDate).trim();
            this.lines = lines == null ? List.of() : List.copyOf(lines);
            this.totalDebitCents = Math.max(0L, totalDebitCents);
            this.totalCreditCents = Math.max(0L, totalCreditCents);
            this.balanced = balanced;
        }
    }

    public static final class IncomeStatementLineRec {
        public final String accountCode;
        public final String accountName;
        public final long amountCents;

        public IncomeStatementLineRec(String accountCode, String accountName, long amountCents) {
            this.accountCode = normalizeAccountCode(accountCode);
            this.accountName = safe(accountName).trim();
            this.amountCents = amountCents;
        }
    }

    public static final class IncomeStatementRec {
        public final String periodStartDate;
        public final String periodEndDate;
        public final List<IncomeStatementLineRec> revenueLines;
        public final List<IncomeStatementLineRec> expenseLines;
        public final long totalRevenueCents;
        public final long totalExpenseCents;
        public final long netIncomeCents;

        public IncomeStatementRec(String periodStartDate,
                                  String periodEndDate,
                                  List<IncomeStatementLineRec> revenueLines,
                                  List<IncomeStatementLineRec> expenseLines,
                                  long totalRevenueCents,
                                  long totalExpenseCents,
                                  long netIncomeCents) {
            this.periodStartDate = safe(periodStartDate).trim();
            this.periodEndDate = safe(periodEndDate).trim();
            this.revenueLines = revenueLines == null ? List.of() : List.copyOf(revenueLines);
            this.expenseLines = expenseLines == null ? List.of() : List.copyOf(expenseLines);
            this.totalRevenueCents = totalRevenueCents;
            this.totalExpenseCents = totalExpenseCents;
            this.netIncomeCents = netIncomeCents;
        }
    }

    public static final class BalanceSheetLineRec {
        public final String accountCode;
        public final String accountName;
        public final long amountCents;

        public BalanceSheetLineRec(String accountCode, String accountName, long amountCents) {
            this.accountCode = normalizeAccountCode(accountCode);
            this.accountName = safe(accountName).trim();
            this.amountCents = amountCents;
        }
    }

    public static final class BalanceSheetRec {
        public final String asOfDate;
        public final List<BalanceSheetLineRec> assetLines;
        public final List<BalanceSheetLineRec> liabilityLines;
        public final List<BalanceSheetLineRec> equityLines;
        public final long totalAssetsCents;
        public final long totalLiabilitiesCents;
        public final long totalEquityCents;
        public final long currentEarningsCents;
        public final boolean balanced;

        public BalanceSheetRec(String asOfDate,
                               List<BalanceSheetLineRec> assetLines,
                               List<BalanceSheetLineRec> liabilityLines,
                               List<BalanceSheetLineRec> equityLines,
                               long totalAssetsCents,
                               long totalLiabilitiesCents,
                               long totalEquityCents,
                               long currentEarningsCents,
                               boolean balanced) {
            this.asOfDate = safe(asOfDate).trim();
            this.assetLines = assetLines == null ? List.of() : List.copyOf(assetLines);
            this.liabilityLines = liabilityLines == null ? List.of() : List.copyOf(liabilityLines);
            this.equityLines = equityLines == null ? List.of() : List.copyOf(equityLines);
            this.totalAssetsCents = totalAssetsCents;
            this.totalLiabilitiesCents = totalLiabilitiesCents;
            this.totalEquityCents = totalEquityCents;
            this.currentEarningsCents = currentEarningsCents;
            this.balanced = balanced;
        }
    }

    public static final class DataImportResultRec {
        public final int rowsRead;
        public final int rowsImported;
        public final int rowsSkipped;
        public final List<String> errors;

        public DataImportResultRec(int rowsRead, int rowsImported, int rowsSkipped, List<String> errors) {
            this.rowsRead = Math.max(0, rowsRead);
            this.rowsImported = Math.max(0, rowsImported);
            this.rowsSkipped = Math.max(0, rowsSkipped);
            this.errors = errors == null ? List.of() : List.copyOf(errors);
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

        synchronized (lock) {
            String now = nowIso();
            String activity = normalizeActivityCode(activityCode);
            BillingActivityRec activityRec = resolveManagedActivityLocked(activity);
            long effectiveRate = rateCents > 0L ? rateCents : (activityRec == null ? 0L : activityRec.defaultRateCents);
            String utbmsTask = activityRec == null ? deriveTaskCodeFromActivity(activity) : activityRec.utbmsTaskCode;
            String utbmsActivity = activityRec == null ? deriveActivityCodeFromActivity(activity) : activityRec.utbmsActivityCode;

            TimeEntryRec rec = new TimeEntryRec(
                    uuid(),
                    "local",
                    "",
                    matter,
                    safe(userUuid).trim(),
                    activity,
                    safe(note).trim(),
                    minutes,
                    effectiveRate,
                    currencyCode(currency),
                    billable,
                    safe(workedAt).isBlank() ? now : safe(workedAt).trim(),
                    utbmsTask,
                    utbmsActivity,
                    now,
                    now,
                    ""
            );
            timeEntries.put(rec.uuid, rec);
            return rec;
        }
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

            String activity = normalizeActivityCode(activityCode);
            BillingActivityRec activityRec = resolveManagedActivityLocked(activity);
            long effectiveRate = rateCents > 0L ? rateCents : (activityRec == null ? 0L : activityRec.defaultRateCents);
            String utbmsTask = activityRec == null ? deriveTaskCodeFromActivity(activity) : activityRec.utbmsTaskCode;
            String utbmsActivity = activityRec == null ? deriveActivityCodeFromActivity(activity) : activityRec.utbmsActivityCode;

            String uuid = existing == null ? uuid() : existing.uuid;
            String createdAt = existing == null ? now : existing.createdAt;
            String billedInvoiceUuid = existing == null ? "" : existing.billedInvoiceUuid;

            TimeEntryRec upserted = new TimeEntryRec(
                    uuid,
                    "clio",
                    externalId,
                    matter,
                    safe(userUuid).trim(),
                    activity,
                    safe(note).trim(),
                    minutes,
                    effectiveRate,
                    currencyCode(currency),
                    billable,
                    safe(workedAt).isBlank() ? now : safe(workedAt).trim(),
                    utbmsTask,
                    utbmsActivity,
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
        return createExpenseEntry(
                matterUuid,
                description,
                amountCents,
                taxCents,
                currency,
                billable,
                incurredAt,
                ""
        );
    }

    public ExpenseEntryRec createExpenseEntry(String matterUuid,
                                              String description,
                                              long amountCents,
                                              long taxCents,
                                              String currency,
                                              boolean billable,
                                              String incurredAt,
                                              String expenseCode) {
        String matter = requireToken(matterUuid, "matterUuid");
        String desc = requireToken(description, "description");
        if (amountCents <= 0L) throw new IllegalArgumentException("amountCents must be > 0");
        if (taxCents < 0L) throw new IllegalArgumentException("taxCents must be >= 0");

        String now = nowIso();
        String normalizedExpenseCode = normalizeUtbmsExpenseCode(expenseCode);
        if (normalizedExpenseCode.isBlank()) {
            normalizedExpenseCode = deriveExpenseCodeFromDescription(desc);
        }
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
                normalizedExpenseCode,
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
        return importOrUpdateClioExpenseEntry(
                clioExpenseId,
                matterUuid,
                description,
                amountCents,
                taxCents,
                currency,
                billable,
                incurredAt,
                ""
        );
    }

    public ExpenseEntryRec importOrUpdateClioExpenseEntry(String clioExpenseId,
                                                          String matterUuid,
                                                          String description,
                                                          long amountCents,
                                                          long taxCents,
                                                          String currency,
                                                          boolean billable,
                                                          String incurredAt,
                                                          String expenseCode) {
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
            String normalizedExpenseCode = normalizeUtbmsExpenseCode(expenseCode);
            if (normalizedExpenseCode.isBlank()) {
                normalizedExpenseCode = deriveExpenseCodeFromDescription(desc);
            }

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
                    normalizedExpenseCode,
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
                subtotal = checkedAdd(subtotal, lineAmount, "invoice subtotal accumulation (time)");
                timeIds.add(t.uuid);
            }

            for (ExpenseEntryRec e : expenseEntries.values()) {
                if (e == null) continue;
                if (!matter.equals(e.matterUuid)) continue;
                if (!e.billable) continue;
                if (!safe(e.billedInvoiceUuid).isBlank()) continue;

                long lineTotal = checkedAdd(e.amountCents, e.taxCents, "expense line total");
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
                subtotal = checkedAdd(subtotal, e.amountCents, "invoice subtotal accumulation (expense)");
                tax = checkedAdd(tax, e.taxCents, "invoice tax accumulation");
                expenseIds.add(e.uuid);
            }

            if (lines.isEmpty()) {
                throw new IllegalStateException("No unbilled billable entries for matter.");
            }

            long total = checkedAdd(subtotal, tax, "invoice total");
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
                        t.workedAt, t.utbmsTaskCode, t.utbmsActivityCode, t.createdAt, now, cur.uuid
                ));
            }
            for (String expenseId : cur.sourceExpenseEntryUuids) {
                ExpenseEntryRec e = expenseEntries.get(safe(expenseId));
                if (e == null) continue;
                expenseEntries.put(e.uuid, new ExpenseEntryRec(
                        e.uuid, e.source, e.sourceId, e.matterUuid, e.description, e.amountCents, e.taxCents, e.currency, e.billable,
                        e.incurredAt, e.utbmsExpenseCode, e.createdAt, now, cur.uuid
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
        long total = checkedAdd(subtotal, tax, "imported invoice total");
        long paid = Math.max(0L, Math.min(Math.max(0L, paidCents), total));
        long outstanding = checkedSub(total, paid, "imported invoice outstanding");
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
        return applyTrustToInvoiceInternal(
                invoiceUuid,
                amountCents,
                postedAt,
                reference,
                "local",
                "",
                "",
                false
        );
    }

    public TrustTxnRec applyTrustToInvoiceWithApproval(String approvalUuid,
                                                       String postedAt,
                                                       String reference) {
        String id = requireToken(approvalUuid, "approvalUuid");
        synchronized (lock) {
            TrustDisbursementApprovalRec approval = trustDisbursementApprovals.get(id);
            if (approval == null) throw new IllegalArgumentException("approval not found");
            if (!DISBURSEMENT_TRUST_APPLY.equals(safe(approval.disbursementType).trim())) {
                throw new IllegalArgumentException("approval is not for trust-apply disbursement");
            }
            if (!safe(approval.trustTxnUuid).trim().isBlank()) {
                TrustTxnRec existing = trustTxns.get(safe(approval.trustTxnUuid).trim());
                if (existing != null) return existing;
            }
            if (!APPROVAL_APPROVED.equals(safe(approval.status).trim())) {
                throw new IllegalStateException("approval is not in approved status");
            }
            TrustTxnRec txn = applyTrustToInvoiceInternal(
                    approval.invoiceUuid,
                    approval.amountCents,
                    postedAt,
                    safe(reference).trim().isBlank() ? approval.reason : reference,
                    "local",
                    "",
                    approval.uuid,
                    true
            );
            String now = nowIso();
            trustDisbursementApprovals.put(approval.uuid, new TrustDisbursementApprovalRec(
                    approval.uuid,
                    approval.disbursementType,
                    approval.matterUuid,
                    approval.invoiceUuid,
                    approval.amountCents,
                    approval.currency,
                    approval.requestedAt,
                    approval.requestedBy,
                    approval.reason,
                    APPROVAL_EXECUTED,
                    safe(approval.reviewedAt).isBlank() ? now : approval.reviewedAt,
                    approval.reviewedBy,
                    approval.reviewNotes,
                    now,
                    txn.uuid
            ));
            return txn;
        }
    }

    private TrustTxnRec applyTrustToInvoiceInternal(String invoiceUuid,
                                                    long amountCents,
                                                    String postedAt,
                                                    String reference,
                                                    String source,
                                                    String sourceId,
                                                    String approvalUuid,
                                                    boolean bypassApprovalGate) {
        String id = requireToken(invoiceUuid, "invoiceUuid");
        if (amountCents <= 0L) throw new IllegalArgumentException("amountCents must be > 0");
        String now = nowIso();
        String when = safe(postedAt).isBlank() ? now : safe(postedAt).trim();
        String src = safe(source).trim().toLowerCase(Locale.ROOT);
        if (src.isBlank()) src = "local";
        String srcId = safe(sourceId).trim();
        String approvalId = safe(approvalUuid).trim();

        synchronized (lock) {
            InvoiceRec inv = invoices.get(id);
            if (inv == null) throw new IllegalArgumentException("invoice not found");
            if (!(INVOICE_ISSUED.equals(inv.status) || INVOICE_PARTIALLY_PAID.equals(inv.status))) {
                throw new IllegalStateException("Invoice is not payable from trust in current status.");
            }
            if (amountCents > inv.outstandingCents) {
                throw new IllegalArgumentException("amount exceeds invoice outstanding balance");
            }

            enforceTrustDisbursementRulesLocked(
                    inv.matterUuid,
                    amountCents,
                    DISBURSEMENT_TRUST_APPLY,
                    approvalId,
                    bypassApprovalGate
            );

            TrustTxnRec trust = new TrustTxnRec(
                    uuid(),
                    src,
                    srcId,
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
                    src,
                    srcId,
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

        return applyTrustToInvoiceInternal(
                invoiceUuid,
                amountCents,
                postedAt,
                reference,
                "clio",
                externalId,
                "",
                true
        );
    }

    public TrustTxnRec recordTrustRefund(String matterUuid,
                                         long amountCents,
                                         String currency,
                                         String postedAt,
                                         String reference) {
        return recordTrustRefundInternal(
                matterUuid,
                amountCents,
                currency,
                postedAt,
                reference,
                "",
                false
        );
    }

    public TrustTxnRec recordTrustRefundWithApproval(String approvalUuid,
                                                     String postedAt,
                                                     String reference) {
        String id = requireToken(approvalUuid, "approvalUuid");
        synchronized (lock) {
            TrustDisbursementApprovalRec approval = trustDisbursementApprovals.get(id);
            if (approval == null) throw new IllegalArgumentException("approval not found");
            if (!DISBURSEMENT_TRUST_REFUND.equals(safe(approval.disbursementType).trim())) {
                throw new IllegalArgumentException("approval is not for trust-refund disbursement");
            }
            if (!safe(approval.trustTxnUuid).trim().isBlank()) {
                TrustTxnRec existing = trustTxns.get(safe(approval.trustTxnUuid).trim());
                if (existing != null) return existing;
            }
            if (!APPROVAL_APPROVED.equals(safe(approval.status).trim())) {
                throw new IllegalStateException("approval is not in approved status");
            }
            TrustTxnRec txn = recordTrustRefundInternal(
                    approval.matterUuid,
                    approval.amountCents,
                    approval.currency,
                    postedAt,
                    safe(reference).trim().isBlank() ? approval.reason : reference,
                    approval.uuid,
                    true
            );
            String now = nowIso();
            trustDisbursementApprovals.put(approval.uuid, new TrustDisbursementApprovalRec(
                    approval.uuid,
                    approval.disbursementType,
                    approval.matterUuid,
                    approval.invoiceUuid,
                    approval.amountCents,
                    approval.currency,
                    approval.requestedAt,
                    approval.requestedBy,
                    approval.reason,
                    APPROVAL_EXECUTED,
                    safe(approval.reviewedAt).isBlank() ? now : approval.reviewedAt,
                    approval.reviewedBy,
                    approval.reviewNotes,
                    now,
                    txn.uuid
            ));
            return txn;
        }
    }

    private TrustTxnRec recordTrustRefundInternal(String matterUuid,
                                                  long amountCents,
                                                  String currency,
                                                  String postedAt,
                                                  String reference,
                                                  String approvalUuid,
                                                  boolean bypassApprovalGate) {
        String matter = requireToken(matterUuid, "matterUuid");
        if (amountCents <= 0L) throw new IllegalArgumentException("amountCents must be > 0");
        String now = nowIso();
        String when = safe(postedAt).isBlank() ? now : safe(postedAt).trim();
        String approvalId = safe(approvalUuid).trim();

        synchronized (lock) {
            enforceTrustDisbursementRulesLocked(
                    matter,
                    amountCents,
                    DISBURSEMENT_TRUST_REFUND,
                    approvalId,
                    bypassApprovalGate
            );

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

    public void setRequireManagedActivities(boolean requireManaged) {
        synchronized (lock) {
            this.requireManagedActivities = requireManaged;
        }
    }

    public boolean isRequireManagedActivities() {
        synchronized (lock) {
            return requireManagedActivities;
        }
    }

    public BillingActivityRec upsertBillingActivity(String code,
                                                    String label,
                                                    long defaultRateCents,
                                                    boolean defaultBillable,
                                                    String utbmsTaskCode,
                                                    String utbmsActivityCode,
                                                    String utbmsExpenseCode,
                                                    boolean active) {
        String normalizedCode = requireToken(normalizeActivityCode(code), "code");
        String now = nowIso();
        synchronized (lock) {
            BillingActivityRec existing = activities.get(normalizedCode);
            BillingActivityRec rec = new BillingActivityRec(
                    normalizedCode,
                    safe(label).trim(),
                    Math.max(0L, defaultRateCents),
                    defaultBillable,
                    utbmsTaskCode,
                    utbmsActivityCode,
                    utbmsExpenseCode,
                    active,
                    existing == null ? now : existing.createdAt,
                    now
            );
            activities.put(rec.code, rec);
            return rec;
        }
    }

    public BillingActivityRec getBillingActivity(String code) {
        String normalizedCode = normalizeActivityCode(code);
        if (normalizedCode.isBlank()) return null;
        synchronized (lock) {
            return activities.get(normalizedCode);
        }
    }

    public BillingActivityRec deactivateBillingActivity(String code) {
        String normalizedCode = requireToken(normalizeActivityCode(code), "code");
        synchronized (lock) {
            BillingActivityRec existing = activities.get(normalizedCode);
            if (existing == null) return null;
            BillingActivityRec rec = new BillingActivityRec(
                    existing.code,
                    existing.label,
                    existing.defaultRateCents,
                    existing.defaultBillable,
                    existing.utbmsTaskCode,
                    existing.utbmsActivityCode,
                    existing.utbmsExpenseCode,
                    false,
                    existing.createdAt,
                    nowIso()
            );
            activities.put(rec.code, rec);
            return rec;
        }
    }

    public List<BillingActivityRec> listBillingActivities(boolean includeInactive) {
        synchronized (lock) {
            ArrayList<BillingActivityRec> out = new ArrayList<BillingActivityRec>();
            for (BillingActivityRec rec : activities.values()) {
                if (rec == null) continue;
                if (!includeInactive && !rec.active) continue;
                out.add(rec);
            }
            out.sort(Comparator.comparing((BillingActivityRec r) -> safe(r == null ? "" : r.code)));
            return out;
        }
    }

    public MatterTrustPolicyRec upsertMatterTrustPolicy(String matterUuid,
                                                        String ioltaAccountName,
                                                        String ioltaInstitutionName,
                                                        boolean ioltaInstitutionEligible,
                                                        String annualCertificationDueDate,
                                                        String annualCertificationCompletedAt,
                                                        boolean requireDisbursementApproval,
                                                        long minimumTrustReserveCents,
                                                        String updatedBy) {
        String matter = requireToken(matterUuid, "matterUuid");
        String now = nowIso();
        synchronized (lock) {
            MatterTrustPolicyRec rec = new MatterTrustPolicyRec(
                    matter,
                    safe(ioltaAccountName).trim(),
                    safe(ioltaInstitutionName).trim(),
                    ioltaInstitutionEligible,
                    normalizeIsoDateOrBlank(annualCertificationDueDate),
                    normalizeIsoDateOrBlank(annualCertificationCompletedAt),
                    requireDisbursementApproval,
                    Math.max(0L, minimumTrustReserveCents),
                    now,
                    safe(updatedBy).trim()
            );
            matterTrustPolicies.put(matter, rec);
            return rec;
        }
    }

    public MatterTrustPolicyRec getMatterTrustPolicy(String matterUuid) {
        String matter = safe(matterUuid).trim();
        if (matter.isBlank()) return null;
        synchronized (lock) {
            return matterTrustPolicies.get(matter);
        }
    }

    public List<MatterTrustPolicyRec> listMatterTrustPolicies() {
        synchronized (lock) {
            ArrayList<MatterTrustPolicyRec> out = new ArrayList<MatterTrustPolicyRec>(matterTrustPolicies.values());
            out.sort(Comparator.comparing((MatterTrustPolicyRec p) -> safe(p == null ? "" : p.matterUuid)));
            return out;
        }
    }

    public TrustDisputeHoldRec placeTrustDisputeHold(String matterUuid,
                                                     long amountCents,
                                                     String reason,
                                                     String placedBy,
                                                     String placedAt) {
        String matter = requireToken(matterUuid, "matterUuid");
        String why = requireToken(reason, "reason");
        if (amountCents <= 0L) throw new IllegalArgumentException("amountCents must be > 0");
        String now = nowIso();
        String when = safe(placedAt).isBlank() ? now : safe(placedAt).trim();
        synchronized (lock) {
            long balance = matterTrustBalanceLocked(matter);
            if (amountCents > balance) {
                throw new IllegalArgumentException("hold amount exceeds matter trust balance");
            }
            TrustDisputeHoldRec rec = new TrustDisputeHoldRec(
                    uuid(),
                    matter,
                    amountCents,
                    why,
                    when,
                    safe(placedBy).trim(),
                    true,
                    "",
                    "",
                    ""
            );
            trustDisputeHolds.put(rec.uuid, rec);
            return rec;
        }
    }

    public TrustDisputeHoldRec releaseTrustDisputeHold(String holdUuid,
                                                       String releasedBy,
                                                       String resolution,
                                                       String releasedAt) {
        String id = requireToken(holdUuid, "holdUuid");
        String now = nowIso();
        String when = safe(releasedAt).isBlank() ? now : safe(releasedAt).trim();
        synchronized (lock) {
            TrustDisputeHoldRec cur = trustDisputeHolds.get(id);
            if (cur == null) throw new IllegalArgumentException("hold not found");
            if (!cur.active) return cur;
            TrustDisputeHoldRec rec = new TrustDisputeHoldRec(
                    cur.uuid,
                    cur.matterUuid,
                    cur.amountCents,
                    cur.reason,
                    cur.placedAt,
                    cur.placedBy,
                    false,
                    when,
                    safe(releasedBy).trim(),
                    safe(resolution).trim()
            );
            trustDisputeHolds.put(rec.uuid, rec);
            return rec;
        }
    }

    public List<TrustDisputeHoldRec> listTrustDisputeHoldsForMatter(String matterUuid, boolean includeReleased) {
        String matter = safe(matterUuid).trim();
        if (matter.isBlank()) return List.of();
        synchronized (lock) {
            ArrayList<TrustDisputeHoldRec> out = new ArrayList<TrustDisputeHoldRec>();
            for (TrustDisputeHoldRec hold : trustDisputeHolds.values()) {
                if (hold == null) continue;
                if (!matter.equals(safe(hold.matterUuid).trim())) continue;
                if (!includeReleased && !hold.active) continue;
                out.add(hold);
            }
            out.sort((a, b) -> compareByIsoThenUuid(a == null ? "" : a.placedAt, b == null ? "" : b.placedAt,
                    a == null ? "" : a.uuid, b == null ? "" : b.uuid));
            return out;
        }
    }

    public TrustDisputeHoldRec getTrustDisputeHold(String holdUuid) {
        String id = safe(holdUuid).trim();
        if (id.isBlank()) return null;
        synchronized (lock) {
            return trustDisputeHolds.get(id);
        }
    }

    public long heldTrustAmount(String matterUuid) {
        String matter = safe(matterUuid).trim();
        if (matter.isBlank()) return 0L;
        synchronized (lock) {
            return activeTrustHoldAmountLocked(matter);
        }
    }

    public long availableTrustBalance(String matterUuid) {
        String matter = safe(matterUuid).trim();
        if (matter.isBlank()) return 0L;
        synchronized (lock) {
            return availableTrustBalanceLocked(matter);
        }
    }

    public TrustDisbursementApprovalRec requestTrustDisbursementApproval(String disbursementType,
                                                                         String matterUuid,
                                                                         String invoiceUuid,
                                                                         long amountCents,
                                                                         String currency,
                                                                         String requestedBy,
                                                                         String reason,
                                                                         String requestedAt) {
        String type = normalizeDisbursementType(disbursementType);
        if (amountCents <= 0L) throw new IllegalArgumentException("amountCents must be > 0");
        String now = nowIso();
        String when = safe(requestedAt).isBlank() ? now : safe(requestedAt).trim();

        synchronized (lock) {
            String matter = safe(matterUuid).trim();
            String invoice = safe(invoiceUuid).trim();
            if (DISBURSEMENT_TRUST_APPLY.equals(type)) {
                if (invoice.isBlank()) throw new IllegalArgumentException("invoiceUuid is required for trust_apply approvals");
                InvoiceRec inv = invoices.get(invoice);
                if (inv == null) throw new IllegalArgumentException("invoice not found");
                if (!(INVOICE_ISSUED.equals(inv.status) || INVOICE_PARTIALLY_PAID.equals(inv.status))) {
                    throw new IllegalStateException("Invoice is not payable in current status.");
                }
                if (amountCents > inv.outstandingCents) {
                    throw new IllegalArgumentException("amount exceeds invoice outstanding balance");
                }
                matter = inv.matterUuid;
            } else {
                matter = requireToken(matter, "matterUuid");
                invoice = "";
            }
            long available = availableTrustBalanceLocked(matter);
            if (amountCents > available) {
                throw new IllegalArgumentException("amount exceeds available trust balance after holds/reserve");
            }

            TrustDisbursementApprovalRec rec = new TrustDisbursementApprovalRec(
                    uuid(),
                    type,
                    matter,
                    invoice,
                    amountCents,
                    currencyCode(currency),
                    when,
                    safe(requestedBy).trim(),
                    safe(reason).trim(),
                    APPROVAL_REQUESTED,
                    "",
                    "",
                    "",
                    "",
                    ""
            );
            trustDisbursementApprovals.put(rec.uuid, rec);
            return rec;
        }
    }

    public TrustDisbursementApprovalRec reviewTrustDisbursementApproval(String approvalUuid,
                                                                        boolean approved,
                                                                        String reviewedBy,
                                                                        String reviewNotes,
                                                                        String reviewedAt) {
        String id = requireToken(approvalUuid, "approvalUuid");
        String now = nowIso();
        String when = safe(reviewedAt).isBlank() ? now : safe(reviewedAt).trim();
        synchronized (lock) {
            TrustDisbursementApprovalRec cur = trustDisbursementApprovals.get(id);
            if (cur == null) throw new IllegalArgumentException("approval not found");
            String status = safe(cur.status).trim();
            if (!(APPROVAL_REQUESTED.equals(status) || APPROVAL_APPROVED.equals(status) || APPROVAL_REJECTED.equals(status))) {
                throw new IllegalStateException("approval cannot be reviewed in current status");
            }

            String next = approved ? APPROVAL_APPROVED : APPROVAL_REJECTED;
            TrustDisbursementApprovalRec updated = new TrustDisbursementApprovalRec(
                    cur.uuid,
                    cur.disbursementType,
                    cur.matterUuid,
                    cur.invoiceUuid,
                    cur.amountCents,
                    cur.currency,
                    cur.requestedAt,
                    cur.requestedBy,
                    cur.reason,
                    next,
                    when,
                    safe(reviewedBy).trim(),
                    safe(reviewNotes).trim(),
                    "",
                    cur.trustTxnUuid
            );
            trustDisbursementApprovals.put(updated.uuid, updated);
            return updated;
        }
    }

    public List<TrustDisbursementApprovalRec> listTrustDisbursementApprovalsForMatter(String matterUuid) {
        String matter = safe(matterUuid).trim();
        if (matter.isBlank()) return List.of();
        synchronized (lock) {
            ArrayList<TrustDisbursementApprovalRec> out = new ArrayList<TrustDisbursementApprovalRec>();
            for (TrustDisbursementApprovalRec rec : trustDisbursementApprovals.values()) {
                if (rec == null) continue;
                if (!matter.equals(safe(rec.matterUuid).trim())) continue;
                out.add(rec);
            }
            out.sort((a, b) -> compareByIsoThenUuid(a == null ? "" : a.requestedAt, b == null ? "" : b.requestedAt,
                    a == null ? "" : a.uuid, b == null ? "" : b.uuid));
            return out;
        }
    }

    public TrustDisbursementApprovalRec getTrustDisbursementApproval(String approvalUuid) {
        String id = safe(approvalUuid).trim();
        if (id.isBlank()) return null;
        synchronized (lock) {
            return trustDisbursementApprovals.get(id);
        }
    }

    public PaymentPlanRec createPaymentPlan(String invoiceUuid,
                                            int installmentCount,
                                            int cadenceDays,
                                            String firstDueAt,
                                            boolean autopay,
                                            String reference) {
        String invoiceId = requireToken(invoiceUuid, "invoiceUuid");
        int count = Math.max(1, installmentCount);
        int cadence = Math.max(1, cadenceDays);
        synchronized (lock) {
            InvoiceRec inv = invoices.get(invoiceId);
            if (inv == null) throw new IllegalArgumentException("invoice not found");
            if (!(INVOICE_ISSUED.equals(inv.status) || INVOICE_PARTIALLY_PAID.equals(inv.status))) {
                throw new IllegalStateException("Invoice is not payable in current status.");
            }
            if (inv.outstandingCents <= 0L) throw new IllegalStateException("Invoice has no outstanding balance.");

            long perInstallment = inv.outstandingCents / count;
            long remainder = inv.outstandingCents % count;
            if (perInstallment <= 0L) {
                perInstallment = 1L;
                long scheduledBase = checkedMultiply(count, perInstallment, "payment plan scheduled base");
                remainder = Math.max(0L, checkedSub(inv.outstandingCents, scheduledBase, "payment plan first-installment remainder"));
            }

            String now = nowIso();
            String firstDue = normalizeIsoDateOrBlank(firstDueAt);
            if (firstDue.isBlank()) firstDue = normalizeIsoDateOrBlank(inv.dueAt);
            if (firstDue.isBlank()) firstDue = normalizeIsoDateOrBlank(now);

            PaymentPlanRec rec = new PaymentPlanRec(
                    uuid(),
                    inv.matterUuid,
                    inv.uuid,
                    inv.currency,
                    count,
                    cadence,
                    perInstallment,
                    remainder,
                    firstDue,
                    autopay,
                    PAYMENT_PLAN_ACTIVE,
                    0,
                    safe(reference).trim(),
                    now,
                    now
            );
            paymentPlans.put(rec.uuid, rec);
            return rec;
        }
    }

    public PaymentPlanRec cancelPaymentPlan(String paymentPlanUuid) {
        String planId = requireToken(paymentPlanUuid, "paymentPlanUuid");
        synchronized (lock) {
            PaymentPlanRec cur = paymentPlans.get(planId);
            if (cur == null) throw new IllegalArgumentException("payment plan not found");
            if (PAYMENT_PLAN_CANCELLED.equals(cur.status) || PAYMENT_PLAN_COMPLETED.equals(cur.status)) return cur;
            PaymentPlanRec updated = new PaymentPlanRec(
                    cur.uuid,
                    cur.matterUuid,
                    cur.invoiceUuid,
                    cur.currency,
                    cur.installmentCount,
                    cur.cadenceDays,
                    cur.installmentAmountCents,
                    cur.firstInstallmentExtraCents,
                    cur.nextDueAt,
                    cur.autopay,
                    PAYMENT_PLAN_CANCELLED,
                    cur.paymentsPostedCount,
                    cur.reference,
                    cur.createdAt,
                    nowIso()
            );
            paymentPlans.put(updated.uuid, updated);
            return updated;
        }
    }

    public PaymentRec postPaymentPlanInstallment(String paymentPlanUuid,
                                                 String postedAt,
                                                 String reference) {
        String planId = requireToken(paymentPlanUuid, "paymentPlanUuid");
        synchronized (lock) {
            PaymentPlanRec plan = paymentPlans.get(planId);
            if (plan == null) throw new IllegalArgumentException("payment plan not found");
            if (!PAYMENT_PLAN_ACTIVE.equals(safe(plan.status).trim())) {
                throw new IllegalStateException("payment plan is not active");
            }
            InvoiceRec invoice = invoices.get(plan.invoiceUuid);
            if (invoice == null) throw new IllegalStateException("invoice for payment plan not found");
            if (invoice.outstandingCents <= 0L) {
                paymentPlans.put(plan.uuid, new PaymentPlanRec(
                        plan.uuid,
                        plan.matterUuid,
                        plan.invoiceUuid,
                        plan.currency,
                        plan.installmentCount,
                        plan.cadenceDays,
                        plan.installmentAmountCents,
                        plan.firstInstallmentExtraCents,
                        plan.nextDueAt,
                        plan.autopay,
                        PAYMENT_PLAN_COMPLETED,
                        plan.paymentsPostedCount,
                        plan.reference,
                        plan.createdAt,
                        nowIso()
                ));
                throw new IllegalStateException("payment plan invoice is already paid");
            }

            long amount = plan.installmentAmountCents;
            if (plan.paymentsPostedCount == 0) {
                amount = checkedAdd(amount, plan.firstInstallmentExtraCents, "payment plan first installment amount");
            }
            if (amount <= 0L) amount = invoice.outstandingCents;
            amount = Math.min(amount, invoice.outstandingCents);

            String ref = safe(reference).trim();
            if (ref.isBlank()) {
                String suffix = "installment " + (plan.paymentsPostedCount + 1) + "/" + plan.installmentCount;
                ref = safe(plan.reference).trim().isBlank()
                        ? suffix
                        : (safe(plan.reference).trim() + " | " + suffix);
            }

            PaymentRec payment = recordOperatingPayment(plan.invoiceUuid, amount, postedAt, ref);
            InvoiceRec updatedInvoice = invoices.get(plan.invoiceUuid);
            int newCount = plan.paymentsPostedCount + 1;
            boolean done = updatedInvoice == null
                    || updatedInvoice.outstandingCents <= 0L
                    || newCount >= plan.installmentCount;
            String nextStatus = done ? PAYMENT_PLAN_COMPLETED : PAYMENT_PLAN_ACTIVE;
            String nextDue = done ? plan.nextDueAt : shiftIsoDateByDays(plan.nextDueAt, plan.cadenceDays);

            PaymentPlanRec updatedPlan = new PaymentPlanRec(
                    plan.uuid,
                    plan.matterUuid,
                    plan.invoiceUuid,
                    plan.currency,
                    plan.installmentCount,
                    plan.cadenceDays,
                    plan.installmentAmountCents,
                    plan.firstInstallmentExtraCents,
                    nextDue,
                    plan.autopay,
                    nextStatus,
                    newCount,
                    plan.reference,
                    plan.createdAt,
                    nowIso()
            );
            paymentPlans.put(updatedPlan.uuid, updatedPlan);
            return payment;
        }
    }

    public List<PaymentRec> runAutopayDuePlans(String asOfDate, String postedAt, String referencePrefix) {
        LocalDate asOf = parseIsoDateOrNull(asOfDate);
        if (asOf == null) asOf = parseIsoDateOrNull(nowIso());
        if (asOf == null) return List.of();

        ArrayList<String> duePlanIds = new ArrayList<String>();
        synchronized (lock) {
            for (PaymentPlanRec plan : paymentPlans.values()) {
                if (plan == null) continue;
                if (!plan.autopay) continue;
                if (!PAYMENT_PLAN_ACTIVE.equals(safe(plan.status).trim())) continue;
                LocalDate due = parseIsoDateOrNull(plan.nextDueAt);
                if (due == null) continue;
                if (!due.isAfter(asOf)) duePlanIds.add(plan.uuid);
            }
        }

        ArrayList<PaymentRec> posted = new ArrayList<PaymentRec>();
        for (String planId : duePlanIds) {
            try {
                String ref = safe(referencePrefix).trim();
                if (!ref.isBlank()) ref = ref + " | " + planId;
                posted.add(postPaymentPlanInstallment(planId, postedAt, ref));
            } catch (Exception ignored) {
                // Best-effort autopay sweep; failures remain visible through plan status/compliance snapshots.
            }
        }
        return posted;
    }

    public List<PaymentPlanRec> listPaymentPlansForMatter(String matterUuid) {
        String matter = safe(matterUuid).trim();
        if (matter.isBlank()) return List.of();
        synchronized (lock) {
            ArrayList<PaymentPlanRec> out = new ArrayList<PaymentPlanRec>();
            for (PaymentPlanRec rec : paymentPlans.values()) {
                if (rec == null) continue;
                if (!matter.equals(safe(rec.matterUuid).trim())) continue;
                out.add(rec);
            }
            out.sort((a, b) -> compareByIsoThenUuid(a == null ? "" : a.createdAt, b == null ? "" : b.createdAt,
                    a == null ? "" : a.uuid, b == null ? "" : b.uuid));
            return out;
        }
    }

    public List<PaymentPlanRec> listPaymentPlansForInvoice(String invoiceUuid) {
        String invoice = safe(invoiceUuid).trim();
        if (invoice.isBlank()) return List.of();
        synchronized (lock) {
            ArrayList<PaymentPlanRec> out = new ArrayList<PaymentPlanRec>();
            for (PaymentPlanRec rec : paymentPlans.values()) {
                if (rec == null) continue;
                if (!invoice.equals(safe(rec.invoiceUuid).trim())) continue;
                out.add(rec);
            }
            out.sort((a, b) -> compareByIsoThenUuid(a == null ? "" : a.createdAt, b == null ? "" : b.createdAt,
                    a == null ? "" : a.uuid, b == null ? "" : b.uuid));
            return out;
        }
    }

    public PaymentPlanRec getPaymentPlan(String paymentPlanUuid) {
        String id = safe(paymentPlanUuid).trim();
        if (id.isBlank()) return null;
        synchronized (lock) {
            return paymentPlans.get(id);
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

    public List<TimeEntryRec> listTimeEntriesForInvoice(String invoiceUuid) {
        String invoice = safe(invoiceUuid).trim();
        if (invoice.isBlank()) return List.of();
        synchronized (lock) {
            ArrayList<TimeEntryRec> out = new ArrayList<TimeEntryRec>();
            for (TimeEntryRec t : timeEntries.values()) {
                if (t == null) continue;
                if (!invoice.equals(safe(t.billedInvoiceUuid).trim())) continue;
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

    public List<ExpenseEntryRec> listExpenseEntriesForInvoice(String invoiceUuid) {
        String invoice = safe(invoiceUuid).trim();
        if (invoice.isBlank()) return List.of();
        synchronized (lock) {
            ArrayList<ExpenseEntryRec> out = new ArrayList<ExpenseEntryRec>();
            for (ExpenseEntryRec e : expenseEntries.values()) {
                if (e == null) continue;
                if (!invoice.equals(safe(e.billedInvoiceUuid).trim())) continue;
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

    public AccountRec upsertAccount(String accountCode, String accountName, String accountType, boolean active) {
        String code = requireToken(normalizeAccountCode(accountCode), "accountCode");
        String type = normalizeAccountType(accountType);
        if (type.isBlank()) {
            type = inferAccountType(code);
        }
        if (type.isBlank()) {
            throw new IllegalArgumentException("accountType is required (asset/liability/equity/revenue/expense)");
        }

        synchronized (lock) {
            AccountRec existing = chartOfAccounts.get(code);
            String name = safe(accountName).trim();
            if (name.isBlank()) name = existing == null ? code : firstNonBlank(existing.name, code);
            AccountRec rec = new AccountRec(code, name, type, active, nowIso());
            chartOfAccounts.put(code, rec);
            return rec;
        }
    }

    public AccountRec getAccount(String accountCode) {
        String code = normalizeAccountCode(accountCode);
        if (code.isBlank()) return null;
        synchronized (lock) {
            return chartOfAccounts.get(code);
        }
    }

    public List<AccountRec> listAccounts(boolean includeInactive) {
        synchronized (lock) {
            ArrayList<AccountRec> out = new ArrayList<AccountRec>();
            for (AccountRec rec : chartOfAccounts.values()) {
                if (rec == null) continue;
                if (!includeInactive && !rec.active) continue;
                out.add(rec);
            }
            out.sort(Comparator.comparing((AccountRec a) -> safe(a == null ? "" : a.code)));
            return out;
        }
    }

    /**
     * Manual journals enable GAAP-style general business accounting (AR/AP/accrual/equity entries)
     * while preserving the same double-entry controls used by billing/trust journals.
     */
    public JournalEntryRec recordGeneralJournalEntry(String referenceType,
                                                     String referenceUuid,
                                                     String description,
                                                     List<JournalLineRec> lines,
                                                     String postedAt) {
        synchronized (lock) {
            String refType = safe(referenceType).trim();
            if (refType.isBlank()) refType = "journal.manual";
            return postJournalLocked(refType, safe(referenceUuid).trim(), safe(description).trim(), lines, postedAt);
        }
    }

    public TrialBalanceRec trialBalance(String asOfDate) {
        LocalDate asOf = parseIsoDateOrNull(asOfDate);
        if (asOf == null) asOf = parseIsoDateOrNull(nowIso());
        String asOfOut = asOf == null ? "" : asOf.toString();

        synchronized (lock) {
            LinkedHashMap<String, AccountRollup> rollups = journalAccountRollupsLocked(null, asOf);
            ArrayList<String> accountCodes = new ArrayList<String>(rollups.keySet());
            accountCodes.sort(String::compareTo);

            ArrayList<TrialBalanceLineRec> lines = new ArrayList<TrialBalanceLineRec>(accountCodes.size());
            long totalDebit = 0L;
            long totalCredit = 0L;

            for (String code : accountCodes) {
                AccountRollup r = rollups.get(code);
                if (r == null) continue;
                long net = checkedSub(r.debitCents, r.creditCents, "trial balance net");
                long debit = 0L;
                long credit = 0L;
                if (net >= 0L) debit = net;
                else credit = checkedSub(0L, net, "trial balance net credit");
                if (debit == 0L && credit == 0L) continue;

                AccountRec meta = chartOfAccounts.get(code);
                lines.add(new TrialBalanceLineRec(
                        code,
                        meta == null ? code : firstNonBlank(meta.name, code),
                        meta == null ? inferAccountType(code) : meta.type,
                        debit,
                        credit
                ));
                totalDebit = checkedAdd(totalDebit, debit, "trial balance debit total");
                totalCredit = checkedAdd(totalCredit, credit, "trial balance credit total");
            }

            return new TrialBalanceRec(asOfOut, lines, totalDebit, totalCredit, totalDebit == totalCredit);
        }
    }

    public IncomeStatementRec incomeStatement(String periodStartDate, String periodEndDate) {
        LocalDate start = parseIsoDateOrNull(periodStartDate);
        LocalDate end = parseIsoDateOrNull(periodEndDate);
        if (end == null) end = parseIsoDateOrNull(nowIso());
        String outStart = start == null ? "" : start.toString();
        String outEnd = end == null ? "" : end.toString();

        synchronized (lock) {
            LinkedHashMap<String, Long> revenue = new LinkedHashMap<String, Long>();
            LinkedHashMap<String, Long> expense = new LinkedHashMap<String, Long>();

            for (JournalEntryRec j : journals) {
                if (j == null) continue;
                if (!isWithinDateRange(j.postedAt, start, end)) continue;
                for (JournalLineRec l : j.lines) {
                    if (l == null) continue;
                    String code = normalizeAccountCode(l.accountCode);
                    if (code.isBlank()) continue;
                    String type = accountTypeForCodeLocked(code);
                    long debit = Math.max(0L, l.debitCents);
                    long credit = Math.max(0L, l.creditCents);
                    if (ACCOUNT_REVENUE.equals(type)) {
                        long amount = checkedSub(credit, debit, "income statement revenue line");
                        long prior = revenue.getOrDefault(code, 0L);
                        revenue.put(code, checkedAdd(prior, amount, "income statement revenue accumulation"));
                    } else if (ACCOUNT_EXPENSE.equals(type)) {
                        long amount = checkedSub(debit, credit, "income statement expense line");
                        long prior = expense.getOrDefault(code, 0L);
                        expense.put(code, checkedAdd(prior, amount, "income statement expense accumulation"));
                    }
                }
            }

            ArrayList<IncomeStatementLineRec> revenueLines = new ArrayList<IncomeStatementLineRec>();
            ArrayList<String> revenueCodes = new ArrayList<String>(revenue.keySet());
            revenueCodes.sort(String::compareTo);
            long totalRevenue = 0L;
            for (String code : revenueCodes) {
                long amount = revenue.getOrDefault(code, 0L);
                if (amount == 0L) continue;
                AccountRec meta = chartOfAccounts.get(code);
                revenueLines.add(new IncomeStatementLineRec(code, meta == null ? code : firstNonBlank(meta.name, code), amount));
                totalRevenue = checkedAdd(totalRevenue, amount, "income statement total revenue");
            }

            ArrayList<IncomeStatementLineRec> expenseLines = new ArrayList<IncomeStatementLineRec>();
            ArrayList<String> expenseCodes = new ArrayList<String>(expense.keySet());
            expenseCodes.sort(String::compareTo);
            long totalExpense = 0L;
            for (String code : expenseCodes) {
                long amount = expense.getOrDefault(code, 0L);
                if (amount == 0L) continue;
                AccountRec meta = chartOfAccounts.get(code);
                expenseLines.add(new IncomeStatementLineRec(code, meta == null ? code : firstNonBlank(meta.name, code), amount));
                totalExpense = checkedAdd(totalExpense, amount, "income statement total expense");
            }

            long netIncome = checkedSub(totalRevenue, totalExpense, "income statement net income");
            return new IncomeStatementRec(outStart, outEnd, revenueLines, expenseLines, totalRevenue, totalExpense, netIncome);
        }
    }

    public BalanceSheetRec balanceSheet(String asOfDate) {
        LocalDate asOf = parseIsoDateOrNull(asOfDate);
        if (asOf == null) asOf = parseIsoDateOrNull(nowIso());
        String outDate = asOf == null ? "" : asOf.toString();

        synchronized (lock) {
            LinkedHashMap<String, Long> assets = new LinkedHashMap<String, Long>();
            LinkedHashMap<String, Long> liabilities = new LinkedHashMap<String, Long>();
            LinkedHashMap<String, Long> equity = new LinkedHashMap<String, Long>();
            long revenueTotal = 0L;
            long expenseTotal = 0L;

            for (JournalEntryRec j : journals) {
                if (j == null) continue;
                if (!isWithinDateRange(j.postedAt, null, asOf)) continue;
                for (JournalLineRec l : j.lines) {
                    if (l == null) continue;
                    String code = normalizeAccountCode(l.accountCode);
                    if (code.isBlank()) continue;
                    String type = accountTypeForCodeLocked(code);
                    long debit = Math.max(0L, l.debitCents);
                    long credit = Math.max(0L, l.creditCents);

                    if (ACCOUNT_ASSET.equals(type)) {
                        long amount = checkedSub(debit, credit, "balance sheet asset line");
                        long prior = assets.getOrDefault(code, 0L);
                        assets.put(code, checkedAdd(prior, amount, "balance sheet asset accumulation"));
                    } else if (ACCOUNT_LIABILITY.equals(type)) {
                        long amount = checkedSub(credit, debit, "balance sheet liability line");
                        long prior = liabilities.getOrDefault(code, 0L);
                        liabilities.put(code, checkedAdd(prior, amount, "balance sheet liability accumulation"));
                    } else if (ACCOUNT_EQUITY.equals(type)) {
                        long amount = checkedSub(credit, debit, "balance sheet equity line");
                        long prior = equity.getOrDefault(code, 0L);
                        equity.put(code, checkedAdd(prior, amount, "balance sheet equity accumulation"));
                    } else if (ACCOUNT_REVENUE.equals(type)) {
                        long amount = checkedSub(credit, debit, "balance sheet current earnings revenue");
                        revenueTotal = checkedAdd(revenueTotal, amount, "balance sheet revenue accumulation");
                    } else if (ACCOUNT_EXPENSE.equals(type)) {
                        long amount = checkedSub(debit, credit, "balance sheet current earnings expense");
                        expenseTotal = checkedAdd(expenseTotal, amount, "balance sheet expense accumulation");
                    }
                }
            }

            long currentEarnings = checkedSub(revenueTotal, expenseTotal, "balance sheet current earnings");
            long postedEquityTotal = 0L;
            long totalAssets = 0L;
            long totalLiabilities = 0L;

            ArrayList<BalanceSheetLineRec> assetLines = toBalanceSheetLinesLocked(assets, ACCOUNT_ASSET);
            for (BalanceSheetLineRec row : assetLines) {
                totalAssets = checkedAdd(totalAssets, row.amountCents, "balance sheet assets total");
            }

            ArrayList<BalanceSheetLineRec> liabilityLines = toBalanceSheetLinesLocked(liabilities, ACCOUNT_LIABILITY);
            for (BalanceSheetLineRec row : liabilityLines) {
                totalLiabilities = checkedAdd(totalLiabilities, row.amountCents, "balance sheet liabilities total");
            }

            ArrayList<BalanceSheetLineRec> equityLines = toBalanceSheetLinesLocked(equity, ACCOUNT_EQUITY);
            for (BalanceSheetLineRec row : equityLines) {
                postedEquityTotal = checkedAdd(postedEquityTotal, row.amountCents, "balance sheet posted equity total");
            }
            if (currentEarnings != 0L) {
                equityLines.add(new BalanceSheetLineRec("equity:current_earnings", "Current Earnings", currentEarnings));
            }
            long totalEquity = checkedAdd(postedEquityTotal, currentEarnings, "balance sheet total equity");

            long rightSide = checkedAdd(totalLiabilities, totalEquity, "balance sheet liabilities+equity");
            boolean balanced = totalAssets == rightSide;
            return new BalanceSheetRec(
                    outDate,
                    assetLines,
                    liabilityLines,
                    equityLines,
                    totalAssets,
                    totalLiabilities,
                    totalEquity,
                    currentEarnings,
                    balanced
            );
        }
    }

    public String exportChartOfAccountsCsv() {
        synchronized (lock) {
            StringBuilder sb = new StringBuilder(2048);
            sb.append("account_code,account_name,account_type,active,updated_at\n");
            ArrayList<AccountRec> accounts = new ArrayList<AccountRec>(chartOfAccounts.values());
            accounts.sort(Comparator.comparing((AccountRec a) -> safe(a == null ? "" : a.code)));
            for (AccountRec rec : accounts) {
                if (rec == null) continue;
                sb.append(csvCell(rec.code)).append(',')
                        .append(csvCell(rec.name)).append(',')
                        .append(csvCell(rec.type)).append(',')
                        .append(csvCell(String.valueOf(rec.active))).append(',')
                        .append(csvCell(rec.updatedAt))
                        .append('\n');
            }
            return sb.toString();
        }
    }

    public DataImportResultRec importChartOfAccountsCsv(String csvContent) {
        ArrayList<String> errors = new ArrayList<String>();
        String body = safe(csvContent);
        if (body.trim().isBlank()) {
            errors.add("CSV content is empty.");
            return new DataImportResultRec(0, 0, 0, errors);
        }

        List<LinkedHashMap<String, String>> rows = parseCsvToRows(body);
        int rowsRead = rows.size();
        int imported = 0;
        int skipped = 0;

        for (int i = 0; i < rows.size(); i++) {
            LinkedHashMap<String, String> row = rows.get(i);
            if (row == null) continue;
            try {
                String code = normalizeAccountCode(row.get("account_code"));
                if (code.isBlank()) code = normalizeAccountCode(row.get("code"));
                if (code.isBlank()) {
                    skipped++;
                    errors.add("Row " + (i + 2) + ": account_code is required.");
                    continue;
                }
                String name = firstNonBlank(row.get("account_name"), row.get("name"), code);
                String type = firstNonBlank(row.get("account_type"), row.get("type"), inferAccountType(code));
                boolean active = parseBoolean(row.get("active"), true);
                upsertAccount(code, name, type, active);
                imported++;
            } catch (RuntimeException ex) {
                skipped++;
                errors.add("Row " + (i + 2) + ": " + safe(ex.getMessage()));
            }
        }
        return new DataImportResultRec(rowsRead, imported, skipped, errors);
    }

    public String exportJournalsCsv(String periodStartDate, String periodEndDate) {
        LocalDate start = parseIsoDateOrNull(periodStartDate);
        LocalDate end = parseIsoDateOrNull(periodEndDate);
        if (end == null && start == null) end = parseIsoDateOrNull(nowIso());

        synchronized (lock) {
            StringBuilder sb = new StringBuilder(8192);
            sb.append("journal_uuid,posted_at,reference_type,reference_uuid,description,line_index,account_code,matter_uuid,invoice_uuid,debit_cents,credit_cents,memo\n");
            for (JournalEntryRec j : journals) {
                if (j == null) continue;
                if (!isWithinDateRange(j.postedAt, start, end)) continue;
                int idx = 1;
                for (JournalLineRec l : j.lines) {
                    if (l == null) continue;
                    sb.append(csvCell(j.uuid)).append(',')
                            .append(csvCell(j.postedAt)).append(',')
                            .append(csvCell(j.referenceType)).append(',')
                            .append(csvCell(j.referenceUuid)).append(',')
                            .append(csvCell(j.description)).append(',')
                            .append(csvCell(String.valueOf(idx++))).append(',')
                            .append(csvCell(l.accountCode)).append(',')
                            .append(csvCell(l.matterUuid)).append(',')
                            .append(csvCell(l.invoiceUuid)).append(',')
                            .append(csvCell(String.valueOf(Math.max(0L, l.debitCents)))).append(',')
                            .append(csvCell(String.valueOf(Math.max(0L, l.creditCents)))).append(',')
                            .append(csvCell(l.memo))
                            .append('\n');
                }
            }
            return sb.toString();
        }
    }

    public DataImportResultRec importJournalsCsv(String csvContent, boolean skipExisting) {
        ArrayList<String> errors = new ArrayList<String>();
        String body = safe(csvContent);
        if (body.trim().isBlank()) {
            errors.add("CSV content is empty.");
            return new DataImportResultRec(0, 0, 0, errors);
        }

        List<LinkedHashMap<String, String>> rows = parseCsvToRows(body);
        int rowsRead = rows.size();
        int rowsImported = 0;
        int rowsSkipped = 0;

        LinkedHashMap<String, ArrayList<LinkedHashMap<String, String>>> groups =
                new LinkedHashMap<String, ArrayList<LinkedHashMap<String, String>>>();

        for (int i = 0; i < rows.size(); i++) {
            LinkedHashMap<String, String> row = rows.get(i);
            if (row == null) continue;
            String key = safe(row.get("journal_uuid")).trim();
            if (key.isBlank()) key = "row-" + (i + 1);
            groups.computeIfAbsent(key, k -> new ArrayList<LinkedHashMap<String, String>>()).add(row);
        }

        synchronized (lock) {
            for (Map.Entry<String, ArrayList<LinkedHashMap<String, String>>> group : groups.entrySet()) {
                String groupKey = safe(group == null ? "" : group.getKey()).trim();
                ArrayList<LinkedHashMap<String, String>> groupRows = group == null ? null : group.getValue();
                if (groupRows == null || groupRows.isEmpty()) continue;
                try {
                    LinkedHashMap<String, String> first = groupRows.get(0);
                    String refType = firstNonBlank(first.get("reference_type"), "import.csv");
                    String refUuid = firstNonBlank(first.get("reference_uuid"), groupKey);
                    if (skipExisting && findJournalByReferenceLocked(refType, refUuid) != null) {
                        rowsSkipped += groupRows.size();
                        continue;
                    }

                    String postedAt = firstNonBlank(first.get("posted_at"), nowIso());
                    String description = firstNonBlank(first.get("description"), "Imported CSV journal " + groupKey);
                    ArrayList<JournalLineRec> lines = new ArrayList<JournalLineRec>(groupRows.size());
                    for (LinkedHashMap<String, String> row : groupRows) {
                        String account = requireToken(normalizeAccountCode(row.get("account_code")), "account_code");
                        long debit = parseCents(row.get("debit_cents"), "debit_cents");
                        long credit = parseCents(row.get("credit_cents"), "credit_cents");
                        lines.add(new JournalLineRec(
                                account,
                                safe(row.get("matter_uuid")).trim(),
                                safe(row.get("invoice_uuid")).trim(),
                                debit,
                                credit,
                                safe(row.get("memo")).trim()
                        ));
                    }
                    postJournalLocked(refType, refUuid, description, lines, postedAt);
                    rowsImported += groupRows.size();
                } catch (RuntimeException ex) {
                    rowsSkipped += groupRows.size();
                    errors.add("Journal group " + groupKey + ": " + safe(ex.getMessage()));
                }
            }
        }

        return new DataImportResultRec(rowsRead, rowsImported, rowsSkipped, errors);
    }

    public String exportQuickBooksIif(String periodStartDate, String periodEndDate) {
        LocalDate start = parseIsoDateOrNull(periodStartDate);
        LocalDate end = parseIsoDateOrNull(periodEndDate);
        if (end == null && start == null) end = parseIsoDateOrNull(nowIso());

        synchronized (lock) {
            StringBuilder sb = new StringBuilder(8192);
            sb.append("!TRNS\tTRNSID\tTRNSTYPE\tDATE\tACCNT\tNAME\tCLASS\tAMOUNT\tDOCNUM\tMEMO\n");
            sb.append("!SPL\tTRNSID\tTRNSTYPE\tDATE\tACCNT\tNAME\tCLASS\tAMOUNT\tDOCNUM\tMEMO\n");
            sb.append("!ENDTRNS\n");

            for (JournalEntryRec j : journals) {
                if (j == null) continue;
                if (!isWithinDateRange(j.postedAt, start, end)) continue;
                if (j.lines == null || j.lines.isEmpty()) continue;

                String trnsId = safe(j.uuid).trim();
                String trnsType = "GENERAL JOURNAL";
                String date = toQuickBooksDate(j.postedAt);
                String docNum = firstNonBlank(j.referenceUuid, trnsId);
                String memo = firstNonBlank(j.description, "Journal " + trnsId);

                JournalLineRec firstLine = j.lines.get(0);
                sb.append("TRNS\t")
                        .append(iifCell(trnsId)).append('\t')
                        .append(iifCell(trnsType)).append('\t')
                        .append(iifCell(date)).append('\t')
                        .append(iifCell(firstLine.accountCode)).append('\t')
                        .append(iifCell(firstLine.matterUuid)).append('\t')
                        .append(iifCell(firstLine.invoiceUuid)).append('\t')
                        .append(iifCell(centsDecimal(journalLineSignedAmount(firstLine)))).append('\t')
                        .append(iifCell(docNum)).append('\t')
                        .append(iifCell(firstNonBlank(firstLine.memo, memo)))
                        .append('\n');

                for (int i = 1; i < j.lines.size(); i++) {
                    JournalLineRec l = j.lines.get(i);
                    if (l == null) continue;
                    sb.append("SPL\t")
                            .append(iifCell(trnsId)).append('\t')
                            .append(iifCell(trnsType)).append('\t')
                            .append(iifCell(date)).append('\t')
                            .append(iifCell(l.accountCode)).append('\t')
                            .append(iifCell(l.matterUuid)).append('\t')
                            .append(iifCell(l.invoiceUuid)).append('\t')
                            .append(iifCell(centsDecimal(journalLineSignedAmount(l)))).append('\t')
                            .append(iifCell(docNum)).append('\t')
                            .append(iifCell(firstNonBlank(l.memo, memo)))
                            .append('\n');
                }
                sb.append("ENDTRNS\n");
            }
            return sb.toString();
        }
    }

    public DataImportResultRec importQuickBooksIif(String iifContent, boolean skipExisting) {
        ArrayList<String> errors = new ArrayList<String>();
        String body = safe(iifContent);
        if (body.trim().isBlank()) {
            errors.add("IIF content is empty.");
            return new DataImportResultRec(0, 0, 0, errors);
        }

        String normalized = body.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        int rowsRead = 0;
        int rowsImported = 0;
        int rowsSkipped = 0;

        ArrayList<String> trnsHeader = new ArrayList<String>();
        ArrayList<String> splHeader = new ArrayList<String>();
        PendingIifTxn pending = null;

        synchronized (lock) {
            for (String raw : lines) {
                String line = safe(raw);
                String trimmed = line.trim();
                if (trimmed.isBlank()) continue;

                if (trimmed.startsWith("!TRNS")) {
                    trnsHeader = parseIifHeader(trimmed);
                    continue;
                }
                if (trimmed.startsWith("!SPL")) {
                    splHeader = parseIifHeader(trimmed);
                    continue;
                }
                if (trimmed.equals("!ENDTRNS")) continue;
                if (trimmed.equals("ENDTRNS")) {
                    if (pending == null) continue;
                    try {
                        int importedRows = importPendingIifTxnLocked(pending, skipExisting);
                        if (importedRows > 0) rowsImported += importedRows;
                        else rowsSkipped += pending.rowCount;
                    } catch (RuntimeException ex) {
                        rowsSkipped += pending.rowCount;
                        errors.add("IIF transaction " + pending.transactionId + ": " + safe(ex.getMessage()));
                    }
                    pending = null;
                    continue;
                }

                if (trimmed.startsWith("TRNS")) {
                    rowsRead++;
                    if (pending != null) {
                        try {
                            int importedRows = importPendingIifTxnLocked(pending, skipExisting);
                            if (importedRows > 0) rowsImported += importedRows;
                            else rowsSkipped += pending.rowCount;
                        } catch (RuntimeException ex) {
                            rowsSkipped += pending.rowCount;
                            errors.add("IIF transaction " + pending.transactionId + ": " + safe(ex.getMessage()));
                        }
                    }

                    LinkedHashMap<String, String> row = parseIifRow(trimmed, trnsHeader);
                    PendingIifTxn next = new PendingIifTxn();
                    next.transactionId = firstNonBlank(row.get("TRNSID"), row.get("DOCNUM"), uuid());
                    next.referenceType = "import.iif";
                    next.referenceUuid = firstNonBlank(row.get("DOCNUM"), row.get("TRNSID"), next.transactionId);
                    next.postedAt = normalizeQuickBooksDate(firstNonBlank(row.get("DATE"), nowIso()));
                    next.description = firstNonBlank(row.get("MEMO"), "Imported IIF transaction " + next.referenceUuid);
                    next.rowCount = 1;
                    next.lines.add(iifRowToJournalLine(row));
                    pending = next;
                    continue;
                }

                if (trimmed.startsWith("SPL")) {
                    rowsRead++;
                    if (pending == null) {
                        rowsSkipped++;
                        errors.add("Encountered SPL row before TRNS.");
                        continue;
                    }
                    LinkedHashMap<String, String> row = parseIifRow(trimmed, splHeader);
                    pending.lines.add(iifRowToJournalLine(row));
                    pending.rowCount++;
                    continue;
                }
            }

            if (pending != null) {
                try {
                    int importedRows = importPendingIifTxnLocked(pending, skipExisting);
                    if (importedRows > 0) rowsImported += importedRows;
                    else rowsSkipped += pending.rowCount;
                } catch (RuntimeException ex) {
                    rowsSkipped += pending.rowCount;
                    errors.add("IIF transaction " + pending.transactionId + ": " + safe(ex.getMessage()));
                }
            }
        }

        return new DataImportResultRec(rowsRead, rowsImported, rowsSkipped, errors);
    }

    public String exportTrialBalanceCsv(String asOfDate) {
        TrialBalanceRec trial = trialBalance(asOfDate);
        StringBuilder sb = new StringBuilder(4096);
        sb.append("as_of_date,account_code,account_name,account_type,debit_cents,credit_cents,debit,credit\n");
        for (TrialBalanceLineRec row : trial.lines) {
            if (row == null) continue;
            sb.append(csvCell(trial.asOfDate)).append(',')
                    .append(csvCell(row.accountCode)).append(',')
                    .append(csvCell(row.accountName)).append(',')
                    .append(csvCell(row.accountType)).append(',')
                    .append(csvCell(String.valueOf(row.debitCents))).append(',')
                    .append(csvCell(String.valueOf(row.creditCents))).append(',')
                    .append(csvCell(centsDecimal(row.debitCents))).append(',')
                    .append(csvCell(centsDecimal(row.creditCents)))
                    .append('\n');
        }
        sb.append(csvCell(trial.asOfDate)).append(',')
                .append(csvCell("TOTAL")).append(',')
                .append(csvCell("Totals")).append(',')
                .append(csvCell("")).append(',')
                .append(csvCell(String.valueOf(trial.totalDebitCents))).append(',')
                .append(csvCell(String.valueOf(trial.totalCreditCents))).append(',')
                .append(csvCell(centsDecimal(trial.totalDebitCents))).append(',')
                .append(csvCell(centsDecimal(trial.totalCreditCents)))
                .append('\n');
        return sb.toString();
    }

    public String exportIncomeStatementCsv(String periodStartDate, String periodEndDate) {
        IncomeStatementRec stmt = incomeStatement(periodStartDate, periodEndDate);
        StringBuilder sb = new StringBuilder(4096);
        sb.append("period_start_date,period_end_date,section,account_code,account_name,amount_cents,amount\n");
        for (IncomeStatementLineRec row : stmt.revenueLines) {
            if (row == null) continue;
            sb.append(csvCell(stmt.periodStartDate)).append(',')
                    .append(csvCell(stmt.periodEndDate)).append(',')
                    .append(csvCell("revenue")).append(',')
                    .append(csvCell(row.accountCode)).append(',')
                    .append(csvCell(row.accountName)).append(',')
                    .append(csvCell(String.valueOf(row.amountCents))).append(',')
                    .append(csvCell(centsDecimal(row.amountCents)))
                    .append('\n');
        }
        for (IncomeStatementLineRec row : stmt.expenseLines) {
            if (row == null) continue;
            sb.append(csvCell(stmt.periodStartDate)).append(',')
                    .append(csvCell(stmt.periodEndDate)).append(',')
                    .append(csvCell("expense")).append(',')
                    .append(csvCell(row.accountCode)).append(',')
                    .append(csvCell(row.accountName)).append(',')
                    .append(csvCell(String.valueOf(row.amountCents))).append(',')
                    .append(csvCell(centsDecimal(row.amountCents)))
                    .append('\n');
        }
        sb.append(csvCell(stmt.periodStartDate)).append(',')
                .append(csvCell(stmt.periodEndDate)).append(',')
                .append(csvCell("total")).append(',')
                .append(csvCell("NET_INCOME")).append(',')
                .append(csvCell("Net Income")).append(',')
                .append(csvCell(String.valueOf(stmt.netIncomeCents))).append(',')
                .append(csvCell(centsDecimal(stmt.netIncomeCents)))
                .append('\n');
        return sb.toString();
    }

    public String exportBalanceSheetCsv(String asOfDate) {
        BalanceSheetRec sheet = balanceSheet(asOfDate);
        StringBuilder sb = new StringBuilder(4096);
        sb.append("as_of_date,section,account_code,account_name,amount_cents,amount\n");
        for (BalanceSheetLineRec row : sheet.assetLines) {
            if (row == null) continue;
            sb.append(csvCell(sheet.asOfDate)).append(',')
                    .append(csvCell("asset")).append(',')
                    .append(csvCell(row.accountCode)).append(',')
                    .append(csvCell(row.accountName)).append(',')
                    .append(csvCell(String.valueOf(row.amountCents))).append(',')
                    .append(csvCell(centsDecimal(row.amountCents)))
                    .append('\n');
        }
        for (BalanceSheetLineRec row : sheet.liabilityLines) {
            if (row == null) continue;
            sb.append(csvCell(sheet.asOfDate)).append(',')
                    .append(csvCell("liability")).append(',')
                    .append(csvCell(row.accountCode)).append(',')
                    .append(csvCell(row.accountName)).append(',')
                    .append(csvCell(String.valueOf(row.amountCents))).append(',')
                    .append(csvCell(centsDecimal(row.amountCents)))
                    .append('\n');
        }
        for (BalanceSheetLineRec row : sheet.equityLines) {
            if (row == null) continue;
            sb.append(csvCell(sheet.asOfDate)).append(',')
                    .append(csvCell("equity")).append(',')
                    .append(csvCell(row.accountCode)).append(',')
                    .append(csvCell(row.accountName)).append(',')
                    .append(csvCell(String.valueOf(row.amountCents))).append(',')
                    .append(csvCell(centsDecimal(row.amountCents)))
                    .append('\n');
        }
        sb.append(csvCell(sheet.asOfDate)).append(',')
                .append(csvCell("total")).append(',')
                .append(csvCell("BALANCE")).append(',')
                .append(csvCell("Assets = Liabilities + Equity")).append(',')
                .append(csvCell(String.valueOf(sheet.totalAssetsCents))).append(',')
                .append(csvCell(centsDecimal(sheet.totalAssetsCents)))
                .append('\n');
        return sb.toString();
    }

    public TrustReconciliationRec trustReconciliation(String statementDate, long statementEndingBalanceCents) {
        synchronized (lock) {
            long bookTrustBank = balanceAssetAccountLocked(ACCT_TRUST_BANK);
            long clientLedgerTotal = 0L;
            LinkedHashMap<String, Long> balances = trustBalancesByMatterLocked();
            for (Map.Entry<String, Long> e : balances.entrySet()) {
                if (e == null || e.getValue() == null) continue;
                clientLedgerTotal = checkedAdd(clientLedgerTotal, e.getValue().longValue(), "trust reconciliation client subledger total");
            }
            long trustLiability = balanceLiabilityAccountLocked(ACCT_TRUST_LIABILITY);

            long bankVsBook = checkedSub(statementEndingBalanceCents, bookTrustBank, "trust reconciliation bank-vs-book delta");
            long bookVsClient = checkedSub(bookTrustBank, clientLedgerTotal, "trust reconciliation book-vs-client delta");
            long bookVsLiability = checkedSub(bookTrustBank, trustLiability, "trust reconciliation book-vs-liability delta");
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
            LocalDate today = parseIsoDateOrNull(nowIso());

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

            for (Map.Entry<String, Long> e : balances.entrySet()) {
                if (e == null) continue;
                String matter = safe(e.getKey()).trim();
                if (matter.isBlank()) continue;
                long held = activeTrustHoldAmountLocked(matter);
                long balance = e.getValue() == null ? 0L : e.getValue().longValue();
                if (held > balance) {
                    violations.add("Active dispute holds exceed trust balance for matter: " + matter);
                }
                if (availableTrustBalanceLocked(matter) < 0L) {
                    violations.add("Available trust is negative after holds/reserve for matter: " + matter);
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

            HashSet<String> mattersRequiringApproval = new HashSet<String>();
            for (MatterTrustPolicyRec p : matterTrustPolicies.values()) {
                if (p == null) continue;
                String matter = safe(p.matterUuid).trim();
                if (matter.isBlank()) continue;

                long trustBalance = balances.getOrDefault(matter, 0L);
                if (trustBalance > 0L && !p.ioltaInstitutionEligible) {
                    violations.add("Matter with trust balance lacks confirmed eligible IOLTA institution: " + matter);
                }

                LocalDate due = parseIsoDateOrNull(p.annualCertificationDueDate);
                LocalDate completed = parseIsoDateOrNull(p.annualCertificationCompletedAt);
                if (due != null && today != null && !due.isAfter(today)) {
                    if (completed == null || completed.isBefore(due)) {
                        violations.add("IOLTA annual certification appears overdue for matter: " + matter);
                    }
                }

                if (p.requireDisbursementApproval) mattersRequiringApproval.add(matter);
            }

            HashSet<String> executedApprovalTxnIds = new HashSet<String>();
            for (TrustDisbursementApprovalRec approval : trustDisbursementApprovals.values()) {
                if (approval == null) continue;
                if (APPROVAL_EXECUTED.equals(safe(approval.status).trim())) {
                    String txnId = safe(approval.trustTxnUuid).trim();
                    if (txnId.isBlank()) {
                        violations.add("Executed trust disbursement approval missing trust transaction link: " + approval.uuid);
                    } else {
                        executedApprovalTxnIds.add(txnId);
                        if (!trustTxns.containsKey(txnId)) {
                            violations.add("Executed trust disbursement approval references missing transaction: " + approval.uuid);
                        }
                    }
                }
            }

            if (!mattersRequiringApproval.isEmpty()) {
                for (TrustTxnRec t : trustTxns.values()) {
                    if (t == null) continue;
                    String kind = safe(t.kind).trim();
                    if (!(TRUST_APPLY_TO_INVOICE.equals(kind) || TRUST_REFUND.equals(kind))) continue;
                    String matter = safe(t.matterUuid).trim();
                    if (!mattersRequiringApproval.contains(matter)) continue;
                    if (!"local".equals(safe(t.source).trim().toLowerCase(Locale.ROOT))) continue;
                    if (!executedApprovalTxnIds.contains(safe(t.uuid).trim())) {
                        violations.add("Trust disbursement missing required approval: " + t.uuid);
                    }
                }
            }

            if (!rec.balanced) {
                violations.add("Trust three-way reconciliation is out of balance.");
            }

            return new ComplianceSnapshot(rec, violations);
        }
    }

    private InvoiceRec applyPaymentLocked(InvoiceRec inv, long amountCents, String updatedAt) {
        long paid = checkedAdd(inv.paidCents, amountCents, "invoice paid total");
        long outstanding = checkedSub(inv.totalCents, paid, "invoice outstanding after payment");
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

    private void enforceTrustDisbursementRulesLocked(String matterUuid,
                                                     long amountCents,
                                                     String disbursementType,
                                                     String approvalUuid,
                                                     boolean bypassApprovalGate) {
        String matter = requireToken(matterUuid, "matterUuid");
        String type = normalizeDisbursementType(disbursementType);
        long amount = Math.max(0L, amountCents);
        if (amount <= 0L) throw new IllegalArgumentException("amountCents must be > 0");

        long available = availableTrustBalanceLocked(matter);
        if (amount > available) {
            throw new IllegalArgumentException("amount exceeds available trust balance after holds and reserve requirements");
        }

        MatterTrustPolicyRec policy = matterTrustPolicies.get(matter);
        boolean requiresApproval = policy != null && policy.requireDisbursementApproval;
        String approvalId = safe(approvalUuid).trim();

        if (!requiresApproval) return;

        if (approvalId.isBlank()) {
            if (bypassApprovalGate) return;
            throw new IllegalStateException("Matter trust policy requires disbursement approval before posting.");
        }

        TrustDisbursementApprovalRec approval = trustDisbursementApprovals.get(approvalId);
        if (approval == null) throw new IllegalArgumentException("approval not found");
        if (!matter.equals(safe(approval.matterUuid).trim())) {
            throw new IllegalArgumentException("approval does not match matter");
        }
        if (!type.equals(safe(approval.disbursementType).trim())) {
            throw new IllegalArgumentException("approval type does not match disbursement");
        }
        if (approval.amountCents != amount) {
            throw new IllegalArgumentException("approval amount does not match disbursement amount");
        }
        String status = safe(approval.status).trim();
        if (!(APPROVAL_APPROVED.equals(status) || APPROVAL_EXECUTED.equals(status))) {
            throw new IllegalStateException("approval is not approved");
        }
        if (APPROVAL_EXECUTED.equals(status) && !safe(approval.trustTxnUuid).trim().isBlank()) {
            throw new IllegalStateException("approval already executed");
        }
    }

    private long activeTrustHoldAmountLocked(String matterUuid) {
        String matter = safe(matterUuid).trim();
        if (matter.isBlank()) return 0L;
        long held = 0L;
        for (TrustDisputeHoldRec hold : trustDisputeHolds.values()) {
            if (hold == null) continue;
            if (!hold.active) continue;
            if (!matter.equals(safe(hold.matterUuid).trim())) continue;
            held = checkedAdd(held, Math.max(0L, hold.amountCents), "active trust holds total");
        }
        return held;
    }

    private long minimumTrustReserveLocked(String matterUuid) {
        MatterTrustPolicyRec policy = matterTrustPolicies.get(safe(matterUuid).trim());
        if (policy == null) return 0L;
        return Math.max(0L, policy.minimumTrustReserveCents);
    }

    private long availableTrustBalanceLocked(String matterUuid) {
        String matter = safe(matterUuid).trim();
        if (matter.isBlank()) return 0L;
        long balance = matterTrustBalanceLocked(matter);
        long held = activeTrustHoldAmountLocked(matter);
        long reserve = minimumTrustReserveLocked(matter);
        long afterHolds = checkedSub(balance, held, "available trust after holds");
        return checkedSub(afterHolds, reserve, "available trust after reserve");
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
            out.put(matter, checkedAdd(prior, signed, "matter trust balance accumulation"));
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

    private BillingActivityRec resolveManagedActivityLocked(String activityCode) {
        String code = normalizeActivityCode(activityCode);
        if (code.isBlank()) {
            if (requireManagedActivities) {
                throw new IllegalArgumentException("activityCode is required when managed activity mode is enabled");
            }
            return null;
        }
        BillingActivityRec rec = activities.get(code);
        if (rec == null || !rec.active) {
            if (requireManagedActivities) {
                throw new IllegalArgumentException("Unknown or inactive managed activity: " + code);
            }
            return null;
        }
        return rec;
    }

    private static String normalizeActivityCode(String code) {
        return safe(code).trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeUtbmsTaskCode(String code) {
        String v = safe(code).trim().toUpperCase(Locale.ROOT);
        if (v.isBlank()) return "";
        if (!isAlphaNumUtbmsCode(v)) {
            throw new IllegalArgumentException("UTBMS task code must match X999 format.");
        }
        return v;
    }

    private static String normalizeUtbmsActivityCode(String code) {
        String v = safe(code).trim().toUpperCase(Locale.ROOT);
        if (v.isBlank()) return "";
        if (!isAlphaNumUtbmsCode(v) || v.charAt(0) != 'A') {
            throw new IllegalArgumentException("UTBMS activity code must match A999 format.");
        }
        return v;
    }

    private static String normalizeUtbmsExpenseCode(String code) {
        String v = safe(code).trim().toUpperCase(Locale.ROOT);
        if (v.isBlank()) return "";
        if (!isAlphaNumUtbmsCode(v) || v.charAt(0) != 'E') {
            throw new IllegalArgumentException("UTBMS expense code must match E999 format.");
        }
        return v;
    }

    private static String deriveTaskCodeFromActivity(String activityCode) {
        String v = normalizeActivityCode(activityCode);
        if (v.length() != 4 || !isAlphaNumUtbmsCode(v)) return "";
        char lead = v.charAt(0);
        if (lead == 'A' || lead == 'E') return "";
        return v;
    }

    private static String deriveActivityCodeFromActivity(String activityCode) {
        String v = normalizeActivityCode(activityCode);
        if (v.length() != 4 || !isAlphaNumUtbmsCode(v)) return "";
        return v.charAt(0) == 'A' ? v : "";
    }

    private static String deriveExpenseCodeFromDescription(String description) {
        String raw = safe(description).trim();
        if (raw.isBlank()) return "";
        String first = raw.split("\\s+", 2)[0].replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
        if (first.length() == 4 && isAlphaNumUtbmsCode(first) && first.charAt(0) == 'E') return first;
        return "";
    }

    private static boolean isAlphaNumUtbmsCode(String value) {
        String v = safe(value).trim().toUpperCase(Locale.ROOT);
        if (v.length() != 4) return false;
        if (!Character.isLetter(v.charAt(0))) return false;
        return Character.isDigit(v.charAt(1))
                && Character.isDigit(v.charAt(2))
                && Character.isDigit(v.charAt(3));
    }

    private static String normalizeDisbursementType(String disbursementType) {
        String type = safe(disbursementType).trim().toLowerCase(Locale.ROOT);
        if (!DISBURSEMENT_TRUST_APPLY.equals(type) && !DISBURSEMENT_TRUST_REFUND.equals(type)) {
            throw new IllegalArgumentException("disbursementType must be trust_apply or trust_refund");
        }
        return type;
    }

    private static LocalDate parseIsoDateOrNull(String raw) {
        String v = safe(raw).trim();
        if (v.isBlank()) return null;
        if (v.length() >= 10) {
            String prefix = v.substring(0, 10);
            try {
                return LocalDate.parse(prefix);
            } catch (DateTimeParseException ignored) {
            }
        }
        try {
            return LocalDate.parse(v);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static String normalizeIsoDateOrBlank(String raw) {
        LocalDate d = parseIsoDateOrNull(raw);
        return d == null ? "" : d.toString();
    }

    private String shiftIsoDateByDays(String rawDate, int days) {
        LocalDate base = parseIsoDateOrNull(rawDate);
        if (base == null) base = parseIsoDateOrNull(nowIso());
        if (base == null) return "";
        return base.plusDays(Math.max(1, days)).toString();
    }

    private static int compareByIsoThenUuid(String isoA, String isoB, String uuidA, String uuidB) {
        int c = safe(isoA).trim().compareTo(safe(isoB).trim());
        if (c != 0) return c;
        return safe(uuidA).compareTo(safe(uuidB));
    }

    private void seedDefaultAccounts() {
        String now = nowIso();
        ensureAccountRegisteredLocked(ACCT_TRUST_BANK, "Trust Bank", true, ACCOUNT_ASSET, now);
        ensureAccountRegisteredLocked(ACCT_OPERATING_BANK, "Operating Bank", true, ACCOUNT_ASSET, now);
        ensureAccountRegisteredLocked(ACCT_ACCOUNTS_RECEIVABLE, "Accounts Receivable", true, ACCOUNT_ASSET, now);
        ensureAccountRegisteredLocked(ACCT_TRUST_LIABILITY, "Client Trust Liability", true, ACCOUNT_LIABILITY, now);
        ensureAccountRegisteredLocked(ACCT_ACCOUNTS_PAYABLE, "Accounts Payable", true, ACCOUNT_LIABILITY, now);
        ensureAccountRegisteredLocked(ACCT_SALES_TAX_PAYABLE, "Sales Tax Payable", true, ACCOUNT_LIABILITY, now);
        ensureAccountRegisteredLocked(ACCT_OWNER_EQUITY, "Owner Equity", true, ACCOUNT_EQUITY, now);
        ensureAccountRegisteredLocked(ACCT_RETAINED_EARNINGS, "Retained Earnings", true, ACCOUNT_EQUITY, now);
        ensureAccountRegisteredLocked(ACCT_FEE_REVENUE, "Legal Fee Revenue", true, ACCOUNT_REVENUE, now);
        ensureAccountRegisteredLocked(ACCT_REIMBURSED_EXPENSE_REVENUE, "Reimbursed Expense Revenue", true, ACCOUNT_REVENUE, now);
        ensureAccountRegisteredLocked(ACCT_SERVICE_REVENUE, "Service Revenue", true, ACCOUNT_REVENUE, now);
        ensureAccountRegisteredLocked(ACCT_OFFICE_EXPENSE, "Office Expense", true, ACCOUNT_EXPENSE, now);
    }

    private void ensureAccountRegisteredLocked(String accountCode, String accountName, boolean active) {
        ensureAccountRegisteredLocked(accountCode, accountName, active, inferAccountType(accountCode), nowIso());
    }

    private void ensureAccountRegisteredLocked(String accountCode,
                                               String accountName,
                                               boolean active,
                                               String accountType,
                                               String updatedAt) {
        String code = normalizeAccountCode(accountCode);
        if (code.isBlank()) return;
        AccountRec existing = chartOfAccounts.get(code);
        if (existing != null) return;
        String type = normalizeAccountType(accountType);
        if (type.isBlank()) type = inferAccountType(code);
        if (type.isBlank()) return;
        chartOfAccounts.put(code, new AccountRec(
                code,
                firstNonBlank(accountName, code),
                type,
                active,
                safe(updatedAt).isBlank() ? nowIso() : safe(updatedAt).trim()
        ));
    }

    private String accountTypeForCodeLocked(String accountCode) {
        String code = normalizeAccountCode(accountCode);
        if (code.isBlank()) return "";
        AccountRec rec = chartOfAccounts.get(code);
        if (rec != null) return normalizeAccountType(rec.type);
        String inferred = inferAccountType(code);
        if (!inferred.isBlank()) {
            ensureAccountRegisteredLocked(code, code, true, inferred, nowIso());
        }
        return inferred;
    }

    private static String normalizeAccountCode(String accountCode) {
        return safe(accountCode).trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeAccountType(String accountType) {
        String type = safe(accountType).trim().toLowerCase(Locale.ROOT);
        if (ACCOUNT_ASSET.equals(type)) return ACCOUNT_ASSET;
        if (ACCOUNT_LIABILITY.equals(type)) return ACCOUNT_LIABILITY;
        if (ACCOUNT_EQUITY.equals(type)) return ACCOUNT_EQUITY;
        if (ACCOUNT_REVENUE.equals(type)) return ACCOUNT_REVENUE;
        if (ACCOUNT_EXPENSE.equals(type)) return ACCOUNT_EXPENSE;
        return "";
    }

    private static String inferAccountType(String accountCode) {
        String code = normalizeAccountCode(accountCode);
        int colon = code.indexOf(':');
        String prefix = colon <= 0 ? code : code.substring(0, colon);
        return normalizeAccountType(prefix);
    }

    private LinkedHashMap<String, AccountRollup> journalAccountRollupsLocked(LocalDate start, LocalDate end) {
        LinkedHashMap<String, AccountRollup> out = new LinkedHashMap<String, AccountRollup>();
        for (JournalEntryRec j : journals) {
            if (j == null) continue;
            if (!isWithinDateRange(j.postedAt, start, end)) continue;
            for (JournalLineRec l : j.lines) {
                if (l == null) continue;
                String code = normalizeAccountCode(l.accountCode);
                if (code.isBlank()) continue;
                ensureAccountRegisteredLocked(code, code, true);

                AccountRollup roll = out.get(code);
                if (roll == null) {
                    roll = new AccountRollup();
                    out.put(code, roll);
                }
                roll.debitCents = checkedAdd(roll.debitCents, Math.max(0L, l.debitCents), "journal rollup debit");
                roll.creditCents = checkedAdd(roll.creditCents, Math.max(0L, l.creditCents), "journal rollup credit");
            }
        }
        return out;
    }

    private ArrayList<BalanceSheetLineRec> toBalanceSheetLinesLocked(Map<String, Long> amountsByAccount, String accountType) {
        ArrayList<BalanceSheetLineRec> out = new ArrayList<BalanceSheetLineRec>();
        if (amountsByAccount == null || amountsByAccount.isEmpty()) return out;
        ArrayList<String> codes = new ArrayList<String>(amountsByAccount.keySet());
        codes.sort(String::compareTo);
        for (String code : codes) {
            if (safe(code).isBlank()) continue;
            long amount = amountsByAccount.getOrDefault(code, 0L);
            if (amount == 0L) continue;
            String type = accountTypeForCodeLocked(code);
            if (!safe(accountType).trim().equals(type)) continue;
            AccountRec meta = chartOfAccounts.get(code);
            out.add(new BalanceSheetLineRec(code, meta == null ? code : firstNonBlank(meta.name, code), amount));
        }
        return out;
    }

    private static boolean isWithinDateRange(String postedAt, LocalDate startInclusive, LocalDate endInclusive) {
        LocalDate date = toLocalDateOrNull(postedAt);
        if (date == null) return false;
        if (startInclusive != null && date.isBefore(startInclusive)) return false;
        if (endInclusive != null && date.isAfter(endInclusive)) return false;
        return true;
    }

    private static LocalDate toLocalDateOrNull(String value) {
        String raw = safe(value).trim();
        if (raw.isBlank()) return null;
        LocalDate iso = parseIsoDateOrNull(raw);
        if (iso != null) return iso;
        return parseQuickBooksDate(raw);
    }

    private static LocalDate parseQuickBooksDate(String value) {
        String raw = safe(value).trim();
        if (raw.isBlank()) return null;
        String[] tokens = raw.split("/");
        if (tokens.length != 3) return null;
        try {
            int month = Integer.parseInt(tokens[0].trim());
            int day = Integer.parseInt(tokens[1].trim());
            int year = Integer.parseInt(tokens[2].trim());
            if (year < 100) year += 2000;
            return LocalDate.of(year, month, day);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String normalizeQuickBooksDate(String value) {
        LocalDate date = toLocalDateOrNull(value);
        if (date == null) return safe(value).trim();
        return date.toString();
    }

    private static String toQuickBooksDate(String value) {
        LocalDate date = toLocalDateOrNull(value);
        if (date == null) {
            date = parseIsoDateOrNull(value);
        }
        if (date == null) {
            date = parseIsoDateOrNull(Instant.now().toString());
        }
        if (date == null) return "";
        return date.format(DateTimeFormatter.ofPattern("M/d/yyyy", Locale.ROOT));
    }

    private static String csvCell(String value) {
        String v = safe(value);
        boolean needsQuotes = v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r");
        if (!needsQuotes) return v;
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }

    private static List<LinkedHashMap<String, String>> parseCsvToRows(String csvContent) {
        List<List<String>> matrix = parseCsvMatrix(csvContent);
        ArrayList<LinkedHashMap<String, String>> out = new ArrayList<LinkedHashMap<String, String>>();
        if (matrix.isEmpty()) return out;

        List<String> header = matrix.get(0);
        if (header == null || header.isEmpty()) return out;
        ArrayList<String> keys = new ArrayList<String>(header.size());
        for (String h : header) {
            keys.add(safe(h).trim().toLowerCase(Locale.ROOT));
        }

        for (int r = 1; r < matrix.size(); r++) {
            List<String> row = matrix.get(r);
            if (row == null) continue;
            LinkedHashMap<String, String> mapped = new LinkedHashMap<String, String>();
            boolean hasAny = false;
            for (int c = 0; c < keys.size(); c++) {
                String key = keys.get(c);
                if (key.isBlank()) continue;
                String val = c < row.size() ? safe(row.get(c)) : "";
                if (!val.trim().isBlank()) hasAny = true;
                mapped.put(key, val);
            }
            if (hasAny) out.add(mapped);
        }
        return out;
    }

    private static List<List<String>> parseCsvMatrix(String csvContent) {
        ArrayList<List<String>> out = new ArrayList<List<String>>();
        String src = safe(csvContent);
        if (src.isEmpty()) return out;

        ArrayList<String> row = new ArrayList<String>();
        StringBuilder cell = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < src.length(); i++) {
            char ch = src.charAt(i);
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < src.length() && src.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cell.append(ch);
                }
                continue;
            }

            if (ch == '"') {
                inQuotes = true;
                continue;
            }
            if (ch == ',') {
                row.add(cell.toString());
                cell.setLength(0);
                continue;
            }
            if (ch == '\n') {
                row.add(cell.toString());
                out.add(row);
                row = new ArrayList<String>();
                cell.setLength(0);
                continue;
            }
            if (ch == '\r') {
                continue;
            }
            cell.append(ch);
        }

        row.add(cell.toString());
        out.add(row);
        return out;
    }

    private static long parseCents(String raw, String fieldName) {
        String v = safe(raw).trim();
        if (v.isBlank()) return 0L;
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException ignored) {
        }
        try {
            return BigDecimal.valueOf(Double.parseDouble(v))
                    .movePointRight(2)
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValueExact();
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException(fieldName + " must be numeric cents or decimal currency.");
        }
    }

    private static boolean parseBoolean(String raw, boolean fallback) {
        String v = safe(raw).trim().toLowerCase(Locale.ROOT);
        if (v.isBlank()) return fallback;
        if ("true".equals(v) || "1".equals(v) || "yes".equals(v) || "y".equals(v)) return true;
        if ("false".equals(v) || "0".equals(v) || "no".equals(v) || "n".equals(v)) return false;
        return fallback;
    }

    private static long journalLineSignedAmount(JournalLineRec line) {
        if (line == null) return 0L;
        long debit = Math.max(0L, line.debitCents);
        long credit = Math.max(0L, line.creditCents);
        return checkedSub(debit, credit, "journal line signed amount");
    }

    private static String iifCell(String value) {
        return safe(value).replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static String centsDecimal(long cents) {
        return BigDecimal.valueOf(cents)
                .movePointLeft(2)
                .setScale(2, RoundingMode.HALF_UP)
                .toPlainString();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String v : values) {
            String s = safe(v).trim();
            if (!s.isBlank()) return s;
        }
        return "";
    }

    private static ArrayList<String> parseIifHeader(String line) {
        String normalized = safe(line).trim();
        if (normalized.startsWith("!")) normalized = normalized.substring(1);
        String[] raw = normalized.split("\\t", -1);
        ArrayList<String> out = new ArrayList<String>(raw.length);
        for (String col : raw) out.add(safe(col).trim().toUpperCase(Locale.ROOT));
        return out;
    }

    private static LinkedHashMap<String, String> parseIifRow(String line, List<String> header) {
        String[] raw = safe(line).split("\\t", -1);
        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
        if (header != null && !header.isEmpty()) {
            for (int i = 1; i < header.size(); i++) {
                String key = safe(header.get(i)).trim().toUpperCase(Locale.ROOT);
                if (key.isBlank()) continue;
                String value = i < raw.length ? safe(raw[i]).trim() : "";
                out.put(key, value);
            }
            return out;
        }

        if (raw.length > 1) out.put("TRNSID", safe(raw[1]).trim());
        if (raw.length > 2) out.put("TRNSTYPE", safe(raw[2]).trim());
        if (raw.length > 3) out.put("DATE", safe(raw[3]).trim());
        if (raw.length > 4) out.put("ACCNT", safe(raw[4]).trim());
        if (raw.length > 5) out.put("NAME", safe(raw[5]).trim());
        if (raw.length > 6) out.put("CLASS", safe(raw[6]).trim());
        if (raw.length > 7) out.put("AMOUNT", safe(raw[7]).trim());
        if (raw.length > 8) out.put("DOCNUM", safe(raw[8]).trim());
        if (raw.length > 9) out.put("MEMO", safe(raw[9]).trim());
        return out;
    }

    private static JournalLineRec iifRowToJournalLine(Map<String, String> row) {
        String account = requireToken(normalizeAccountCode(safe(row == null ? "" : row.get("ACCNT"))), "IIF account");
        String matter = safe(row == null ? "" : row.get("NAME")).trim();
        String invoice = safe(row == null ? "" : row.get("CLASS")).trim();
        String memo = safe(row == null ? "" : row.get("MEMO")).trim();
        long amount = parseCents(safe(row == null ? "" : row.get("AMOUNT")), "IIF AMOUNT");
        long debit = amount >= 0L ? amount : 0L;
        long credit = amount >= 0L ? 0L : checkedSub(0L, amount, "IIF credit amount");
        return new JournalLineRec(account, matter, invoice, debit, credit, memo);
    }

    private int importPendingIifTxnLocked(PendingIifTxn pending, boolean skipExisting) {
        if (pending == null) return 0;
        if (pending.lines == null || pending.lines.size() < 2) {
            throw new IllegalArgumentException("transaction requires at least 2 journal lines");
        }
        if (skipExisting && findJournalByReferenceLocked(pending.referenceType, pending.referenceUuid) != null) {
            return 0;
        }
        postJournalLocked(
                firstNonBlank(pending.referenceType, "import.iif"),
                firstNonBlank(pending.referenceUuid, pending.transactionId),
                firstNonBlank(pending.description, "Imported IIF transaction"),
                pending.lines,
                normalizeQuickBooksDate(pending.postedAt)
        );
        return pending.rowCount;
    }

    private JournalEntryRec findJournalByReferenceLocked(String referenceType, String referenceUuid) {
        String type = safe(referenceType).trim();
        String id = safe(referenceUuid).trim();
        if (type.isBlank() || id.isBlank()) return null;
        for (JournalEntryRec j : journals) {
            if (j == null) continue;
            if (type.equals(safe(j.referenceType).trim()) && id.equals(safe(j.referenceUuid).trim())) return j;
        }
        return null;
    }

    private static final class AccountRollup {
        long debitCents = 0L;
        long creditCents = 0L;
    }

    private static final class PendingIifTxn {
        String transactionId = "";
        String referenceType = "";
        String referenceUuid = "";
        String description = "";
        String postedAt = "";
        int rowCount = 0;
        ArrayList<JournalLineRec> lines = new ArrayList<JournalLineRec>();
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
            String account = requireToken(normalizeAccountCode(l.accountCode), "journal line accountCode");
            if (accountTypeForCodeLocked(account).isBlank()) {
                throw new IllegalArgumentException("Unsupported account type for code: " + account);
            }
            ensureAccountRegisteredLocked(account, account, true);
            if (l.debitCents > 0L && l.creditCents > 0L) {
                throw new IllegalArgumentException("journal line cannot have both debit and credit");
            }
            if (l.debitCents == 0L && l.creditCents == 0L) {
                throw new IllegalArgumentException("journal line requires debit or credit amount");
            }
            dr = checkedAdd(dr, Math.max(0L, l.debitCents), "journal debit total");
            cr = checkedAdd(cr, Math.max(0L, l.creditCents), "journal credit total");
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
                dr = checkedAdd(dr, Math.max(0L, l.debitCents), "asset account debits");
                cr = checkedAdd(cr, Math.max(0L, l.creditCents), "asset account credits");
            }
        }
        return checkedSub(dr, cr, "asset account balance");
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
                dr = checkedAdd(dr, Math.max(0L, l.debitCents), "liability account debits");
                cr = checkedAdd(cr, Math.max(0L, l.creditCents), "liability account credits");
            }
        }
        return checkedSub(cr, dr, "liability account balance");
    }

    private static long proratedTimeAmountCents(long hourlyRateCents, int minutes) {
        if (hourlyRateCents <= 0L || minutes <= 0) return 0L;
        try {
            return BigDecimal.valueOf(hourlyRateCents)
                    .multiply(BigDecimal.valueOf(minutes))
                    .divide(BigDecimal.valueOf(60L), 0, RoundingMode.HALF_UP)
                    .longValueExact();
        } catch (ArithmeticException ex) {
            throw new IllegalStateException("Arithmetic overflow (prorated time amount)");
        }
    }

    private static long checkedAdd(long a, long b, String context) {
        try {
            return Math.addExact(a, b);
        } catch (ArithmeticException ex) {
            throw new IllegalStateException("Arithmetic overflow (" + safe(context) + ")");
        }
    }

    private static long checkedSub(long a, long b, String context) {
        try {
            return Math.subtractExact(a, b);
        } catch (ArithmeticException ex) {
            throw new IllegalStateException("Arithmetic overflow (" + safe(context) + ")");
        }
    }

    private static long checkedMultiply(long a, long b, String context) {
        try {
            return Math.multiplyExact(a, b);
        } catch (ArithmeticException ex) {
            throw new IllegalStateException("Arithmetic overflow (" + safe(context) + ")");
        }
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
