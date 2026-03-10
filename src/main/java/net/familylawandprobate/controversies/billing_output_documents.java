package net.familylawandprobate.controversies;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * billing_output_documents
 *
 * Tenant-configurable rendering for:
 * - invoice
 * - trust_request
 * - statement_of_account
 *
 * Supports:
 * - Inline text templates with document_assembler directives ({{#if}}, {{#each}})
 * - Mapping to uploaded form templates (docx/doc/rtf/odt/txt/pdf) via form_templates
 */
public final class billing_output_documents {

    public static final String DOC_INVOICE = "invoice";
    public static final String DOC_TRUST_REQUEST = "trust_request";
    public static final String DOC_STATEMENT_OF_ACCOUNT = "statement_of_account";

    private static final ConcurrentHashMap<String, ReentrantReadWriteLock> LOCKS = new ConcurrentHashMap<String, ReentrantReadWriteLock>();

    private static final String DEFAULT_INVOICE_TEMPLATE = """
            INVOICE
            Firm: {{tenant.name}}
            Client: {{client.name}}
            Matter: {{matter.label}}
            Invoice #: {{invoice.uuid}}
            Issued: {{invoice.issued_at}}
            Due: {{invoice.due_at}}
            Status: {{invoice.status}}

            Line Items
            {{#each invoice.lines}}- {{item.description}} | Type: {{item.type}} | Qty: {{item.quantity}} | Unit: {{item.unit_amount}} | Tax: {{item.tax}} | Amount: {{item.total}}
            {{/each}}

            Subtotal: {{invoice.subtotal}}
            Tax: {{invoice.tax}}
            Total: {{invoice.total}}
            Paid: {{invoice.paid}}
            Outstanding: {{invoice.outstanding}}
            Trust Applied: {{invoice.trust_applied}}
            Current Trust Balance: {{trust.balance}}

            {{#if invoice.notes}}Notes: {{invoice.notes}}{{/if}}
            Generated: {{document.generated_at}}
            """;

    private static final String DEFAULT_TRUST_REQUEST_TEMPLATE = """
            TRUST REPLENISHMENT REQUEST
            Firm: {{tenant.name}}
            Client: {{client.name}}
            Matter: {{matter.label}}

            Request #: {{trust_request.request_id}}
            Requested Date: {{trust_request.requested_at}}
            Due Date: {{trust_request.due_at}}
            Requested By: {{trust_request.requested_by}}

            Current Trust Balance: {{trust.balance}}
            Requested Replenishment: {{trust_request.amount}}
            Minimum Target Balance: {{trust_request.minimum_target_balance}}

            Reason:
            {{trust_request.reason}}

            {{#if trust_request.notes}}Notes:
            {{trust_request.notes}}
            {{/if}}

            Generated: {{document.generated_at}}
            """;

    private static final String DEFAULT_STATEMENT_TEMPLATE = """
            STATEMENT OF ACCOUNT
            Firm: {{tenant.name}}
            Client: {{client.name}}
            Matter: {{matter.label}}
            Period: {{statement.period_start}} to {{statement.period_end}}

            Accounts Receivable Activity
            {{#each statement.ar_entries}}- {{item.date}} | {{item.type}} | {{item.reference}} | {{item.description}} | Debit {{item.debit}} | Credit {{item.credit}} | Balance {{item.balance}}
            {{/each}}
            Opening AR Balance: {{statement.ar_opening_balance}}
            Period Charges: {{statement.ar_period_charges}}
            Period Payments: {{statement.ar_period_payments}}
            Closing AR Balance: {{statement.ar_closing_balance}}

            Trust Activity
            {{#each statement.trust_entries}}- {{item.date}} | {{item.type}} | {{item.reference}} | {{item.description}} | Deposit {{item.deposit}} | Withdrawal {{item.withdrawal}} | Balance {{item.balance}}
            {{/each}}
            Opening Trust Balance: {{statement.trust_opening_balance}}
            Trust Deposits: {{statement.trust_period_deposits}}
            Trust Applied: {{statement.trust_period_applied}}
            Trust Refunds: {{statement.trust_period_refunds}}
            Closing Trust Balance: {{statement.trust_closing_balance}}

            Generated: {{document.generated_at}}
            """;

    public static billing_output_documents defaultStore() {
        return new billing_output_documents();
    }

    public static final class TemplateConfigRec {
        public final String documentType;
        public final String templateUuid;
        public final String inlineTemplate;
        public final String updatedAt;

        public TemplateConfigRec(String documentType,
                                 String templateUuid,
                                 String inlineTemplate,
                                 String updatedAt) {
            this.documentType = normalizeDocumentType(documentType);
            this.templateUuid = safe(templateUuid).trim();
            this.inlineTemplate = safe(inlineTemplate);
            this.updatedAt = safe(updatedAt).trim();
        }
    }

    public static final class RenderedDocument {
        public final String documentType;
        public final String templateSource; // inline|form_template
        public final String templateUuid;
        public final document_assembler.AssembledFile assembled;
        public final LinkedHashMap<String, String> tokenValues;

        public RenderedDocument(String documentType,
                                String templateSource,
                                String templateUuid,
                                document_assembler.AssembledFile assembled,
                                Map<String, String> tokenValues) {
            this.documentType = normalizeDocumentType(documentType);
            this.templateSource = safe(templateSource).trim();
            this.templateUuid = safe(templateUuid).trim();
            this.assembled = assembled == null
                    ? new document_assembler.AssembledFile(new byte[0], "txt", "text/plain; charset=UTF-8")
                    : assembled;
            this.tokenValues = tokenValues == null ? new LinkedHashMap<String, String>() : new LinkedHashMap<String, String>(tokenValues);
        }
    }

    public static final class TrustRequestInput {
        public final String matterUuid;
        public final String requestId;
        public final long requestedAmountCents;
        public final String requestedAt;
        public final String dueAt;
        public final String requestedBy;
        public final String reason;
        public final long minimumTargetBalanceCents;
        public final String notes;

        public TrustRequestInput(String matterUuid,
                                 String requestId,
                                 long requestedAmountCents,
                                 String requestedAt,
                                 String dueAt,
                                 String requestedBy,
                                 String reason,
                                 long minimumTargetBalanceCents,
                                 String notes) {
            this.matterUuid = safe(matterUuid).trim();
            this.requestId = safe(requestId).trim();
            this.requestedAmountCents = Math.max(0L, requestedAmountCents);
            this.requestedAt = safe(requestedAt).trim();
            this.dueAt = safe(dueAt).trim();
            this.requestedBy = safe(requestedBy).trim();
            this.reason = safe(reason).trim();
            this.minimumTargetBalanceCents = Math.max(0L, minimumTargetBalanceCents);
            this.notes = safe(notes).trim();
        }
    }

    private static final class StatementEvent {
        final String date;
        final String type;
        final String reference;
        final String description;
        final long deltaCents;

        StatementEvent(String date, String type, String reference, String description, long deltaCents) {
            this.date = safe(date).trim();
            this.type = safe(type).trim();
            this.reference = safe(reference).trim();
            this.description = safe(description).trim();
            this.deltaCents = deltaCents;
        }
    }

    private static final class TrustEvent {
        final String date;
        final String type;
        final String reference;
        final String description;
        final long signedAmountCents;

        TrustEvent(String date, String type, String reference, String description, long signedAmountCents) {
            this.date = safe(date).trim();
            this.type = safe(type).trim();
            this.reference = safe(reference).trim();
            this.description = safe(description).trim();
            this.signedAmountCents = signedAmountCents;
        }
    }

    public void ensure(String tenantUuid) throws Exception {
        String tu = safeFileToken(tenantUuid);
        if (tu.isBlank()) throw new IllegalArgumentException("tenantUuid required");

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            Path index = configPath(tu);
            Files.createDirectories(index.getParent());
            if (!Files.exists(index)) {
                writeAllLocked(tu, defaultConfigs());
                return;
            }
            List<TemplateConfigRec> all = readAllLocked(tu);
            if (all.size() < 3) {
                LinkedHashMap<String, TemplateConfigRec> byType = new LinkedHashMap<String, TemplateConfigRec>();
                for (TemplateConfigRec r : all) {
                    if (r == null) continue;
                    byType.put(r.documentType, r);
                }
                for (TemplateConfigRec d : defaultConfigs()) {
                    if (d == null) continue;
                    byType.putIfAbsent(d.documentType, d);
                }
                writeAllLocked(tu, new ArrayList<TemplateConfigRec>(byType.values()));
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<TemplateConfigRec> listTemplateConfigs(String tenantUuid) throws Exception {
        String tu = safeFileToken(tenantUuid);
        if (tu.isBlank()) return List.of();
        ensure(tu);

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.readLock().lock();
        try {
            return readAllLocked(tu);
        } finally {
            lock.readLock().unlock();
        }
    }

    public TemplateConfigRec getTemplateConfig(String tenantUuid, String documentType) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String docType = normalizeDocumentType(documentType);
        if (tu.isBlank() || docType.isBlank()) return null;
        ensure(tu);

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.readLock().lock();
        try {
            for (TemplateConfigRec r : readAllLocked(tu)) {
                if (r == null) continue;
                if (docType.equals(r.documentType)) return r;
            }
            return null;
        } finally {
            lock.readLock().unlock();
        }
    }

    public boolean setInlineTemplate(String tenantUuid, String documentType, String templateBody) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String docType = normalizeDocumentType(documentType);
        String body = safe(templateBody);
        if (tu.isBlank() || docType.isBlank()) throw new IllegalArgumentException("tenantUuid/documentType required");
        if (body.trim().isBlank()) throw new IllegalArgumentException("templateBody required");
        ensure(tu);

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            List<TemplateConfigRec> all = readAllLocked(tu);
            ArrayList<TemplateConfigRec> out = new ArrayList<TemplateConfigRec>(all.size());
            boolean found = false;
            String now = Instant.now().toString();
            for (TemplateConfigRec r : all) {
                if (r == null) continue;
                if (docType.equals(r.documentType)) {
                    out.add(new TemplateConfigRec(docType, "", body, now));
                    found = true;
                } else {
                    out.add(r);
                }
            }
            if (!found) out.add(new TemplateConfigRec(docType, "", body, now));
            sortByDocumentType(out);
            writeAllLocked(tu, out);
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean setFormTemplate(String tenantUuid, String documentType, String templateUuid) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String docType = normalizeDocumentType(documentType);
        String templateId = safe(templateUuid).trim();
        if (tu.isBlank() || docType.isBlank()) throw new IllegalArgumentException("tenantUuid/documentType required");
        if (templateId.isBlank()) throw new IllegalArgumentException("templateUuid required");

        form_templates forms = form_templates.defaultStore();
        form_templates.TemplateRec templateRec = forms.get(tu, templateId);
        if (templateRec == null) throw new IllegalArgumentException("Template not found: " + templateId);
        if (!forms.isSupportedExtension(templateRec.fileExt)) {
            throw new IllegalArgumentException("Unsupported template type for billing output.");
        }
        ensure(tu);

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            List<TemplateConfigRec> all = readAllLocked(tu);
            ArrayList<TemplateConfigRec> out = new ArrayList<TemplateConfigRec>(all.size());
            boolean found = false;
            String now = Instant.now().toString();
            for (TemplateConfigRec r : all) {
                if (r == null) continue;
                if (docType.equals(r.documentType)) {
                    out.add(new TemplateConfigRec(docType, templateId, safe(r.inlineTemplate), now));
                    found = true;
                } else {
                    out.add(r);
                }
            }
            if (!found) out.add(new TemplateConfigRec(docType, templateId, defaultTemplateForType(docType), now));
            sortByDocumentType(out);
            writeAllLocked(tu, out);
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean resetDefaultInlineTemplate(String tenantUuid, String documentType) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String docType = normalizeDocumentType(documentType);
        if (tu.isBlank() || docType.isBlank()) throw new IllegalArgumentException("tenantUuid/documentType required");
        return setInlineTemplate(tu, docType, defaultTemplateForType(docType));
    }

    public List<String> tokenCatalog(String documentType) {
        String docType = normalizeDocumentType(documentType);
        if (DOC_INVOICE.equals(docType)) return invoiceTokenCatalog();
        if (DOC_TRUST_REQUEST.equals(docType)) return trustRequestTokenCatalog();
        if (DOC_STATEMENT_OF_ACCOUNT.equals(docType)) return statementTokenCatalog();
        return List.of();
    }

    public RenderedDocument renderInvoice(String tenantUuid,
                                          billing_accounting ledger,
                                          String matterUuid,
                                          String invoiceUuid,
                                          Map<String, String> profileValues,
                                          Map<String, String> customValues) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String invoiceId = safe(invoiceUuid).trim();
        if (tu.isBlank() || invoiceId.isBlank()) throw new IllegalArgumentException("tenantUuid/invoiceUuid required");
        if (ledger == null) throw new IllegalArgumentException("ledger required");
        ensure(tu);

        billing_accounting.InvoiceRec invoice = ledger.getInvoice(invoiceId);
        if (invoice == null) throw new IllegalArgumentException("invoice not found");
        String matter = safe(matterUuid).trim();
        if (!matter.isBlank() && !matter.equals(safe(invoice.matterUuid).trim())) {
            throw new IllegalArgumentException("invoice does not belong to matter");
        }

        List<billing_accounting.PaymentRec> payments = ledger.listPaymentsForInvoice(invoice.uuid);
        List<billing_accounting.TrustTxnRec> trustTxns = ledger.listTrustTransactionsForInvoice(invoice.uuid);

        long trustApplied = 0L;
        for (billing_accounting.TrustTxnRec t : trustTxns) {
            if (t == null) continue;
            if (billing_accounting.TRUST_APPLY_TO_INVOICE.equals(safe(t.kind).trim())) {
                trustApplied += Math.max(0L, t.amountCents);
            }
        }
        long trustBalance = ledger.matterTrustBalance(invoice.matterUuid);

        LinkedHashMap<String, String> values = baseValues(profileValues, customValues);
        put(values, "document.generated_at", Instant.now().toString());
        put(values, "matter.uuid", invoice.matterUuid);
        put(values, "invoice.uuid", invoice.uuid);
        put(values, "invoice.status", invoice.status);
        put(values, "invoice.currency", invoice.currency);
        put(values, "invoice.issued_at", invoice.issuedAt);
        put(values, "invoice.due_at", invoice.dueAt);
        put(values, "invoice.subtotal", cents(invoice.subtotalCents));
        put(values, "invoice.tax", cents(invoice.taxCents));
        put(values, "invoice.total", cents(invoice.totalCents));
        put(values, "invoice.paid", cents(invoice.paidCents));
        put(values, "invoice.outstanding", cents(invoice.outstandingCents));
        put(values, "invoice.trust_applied", cents(trustApplied));
        putIfBlank(values, "invoice.notes", invoice.voidReason);
        put(values, "invoice.lines_count", String.valueOf(invoice.lines == null ? 0 : invoice.lines.size()));
        put(values, "payments.total_received", cents(sumPayments(payments)));
        put(values, "trust.balance", cents(trustBalance));

        ArrayList<Map<String, String>> lineRows = new ArrayList<Map<String, String>>();
        int idx = 1;
        for (billing_accounting.InvoiceLineRec line : invoice.lines) {
            if (line == null) continue;
            LinkedHashMap<String, String> row = new LinkedHashMap<String, String>();
            row.put("index", String.valueOf(idx++));
            row.put("type", safe(line.type));
            row.put("source_uuid", safe(line.sourceUuid));
            row.put("description", safe(line.description));
            row.put("quantity", String.valueOf(line.quantity));
            row.put("unit_amount", cents(line.unitAmountCents));
            row.put("tax", cents(line.taxCents));
            row.put("total", cents(line.totalCents));
            lineRows.add(row);
        }
        put(values, "invoice.lines", toXmlList(lineRows));

        ArrayList<Map<String, String>> paymentRows = new ArrayList<Map<String, String>>();
        for (billing_accounting.PaymentRec p : payments) {
            if (p == null) continue;
            LinkedHashMap<String, String> row = new LinkedHashMap<String, String>();
            row.put("date", safe(p.postedAt));
            row.put("kind", safe(p.kind));
            row.put("reference", safe(p.reference));
            row.put("amount", cents(p.amountCents));
            paymentRows.add(row);
        }
        put(values, "invoice.payments", toXmlList(paymentRows));

        return renderWithConfig(tu, DOC_INVOICE, values);
    }

    public RenderedDocument renderTrustRequest(String tenantUuid,
                                               billing_accounting ledger,
                                               TrustRequestInput input,
                                               Map<String, String> profileValues,
                                               Map<String, String> customValues) throws Exception {
        String tu = safeFileToken(tenantUuid);
        if (tu.isBlank()) throw new IllegalArgumentException("tenantUuid required");
        if (ledger == null) throw new IllegalArgumentException("ledger required");
        if (input == null) throw new IllegalArgumentException("input required");
        if (safe(input.matterUuid).trim().isBlank()) throw new IllegalArgumentException("matterUuid required");
        if (input.requestedAmountCents <= 0L) throw new IllegalArgumentException("requestedAmountCents must be > 0");
        ensure(tu);

        long trustBalance = ledger.matterTrustBalance(input.matterUuid);
        LinkedHashMap<String, String> values = baseValues(profileValues, customValues);
        put(values, "document.generated_at", Instant.now().toString());
        put(values, "matter.uuid", input.matterUuid);
        put(values, "trust.balance", cents(trustBalance));
        put(values, "trust_request.request_id", safe(input.requestId).isBlank() ? UUID.randomUUID().toString() : input.requestId);
        put(values, "trust_request.requested_at", safe(input.requestedAt).isBlank() ? Instant.now().toString() : input.requestedAt);
        put(values, "trust_request.due_at", input.dueAt);
        put(values, "trust_request.requested_by", input.requestedBy);
        put(values, "trust_request.amount", cents(input.requestedAmountCents));
        put(values, "trust_request.minimum_target_balance", cents(input.minimumTargetBalanceCents));
        put(values, "trust_request.reason", input.reason);
        put(values, "trust_request.notes", input.notes);

        return renderWithConfig(tu, DOC_TRUST_REQUEST, values);
    }

    public RenderedDocument renderStatementOfAccount(String tenantUuid,
                                                     billing_accounting ledger,
                                                     String matterUuid,
                                                     String periodStartIso,
                                                     String periodEndIso,
                                                     Map<String, String> profileValues,
                                                     Map<String, String> customValues) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String matter = safe(matterUuid).trim();
        if (tu.isBlank() || matter.isBlank()) throw new IllegalArgumentException("tenantUuid/matterUuid required");
        if (ledger == null) throw new IllegalArgumentException("ledger required");
        ensure(tu);

        List<billing_accounting.InvoiceRec> invoices = ledger.listInvoicesForMatter(matter);
        List<billing_accounting.PaymentRec> payments = ledger.listPaymentsForMatter(matter);
        List<billing_accounting.TrustTxnRec> trustTxns = ledger.listTrustTransactionsForMatter(matter);

        String start = safe(periodStartIso).trim();
        String end = safe(periodEndIso).trim();

        ArrayList<StatementEvent> arEvents = new ArrayList<StatementEvent>();
        for (billing_accounting.InvoiceRec i : invoices) {
            if (i == null || billing_accounting.INVOICE_VOID.equals(safe(i.status).trim())) continue;
            arEvents.add(new StatementEvent(
                    firstNonBlank(i.issuedAt, i.createdAt),
                    "invoice",
                    i.uuid,
                    "Invoice issued",
                    Math.max(0L, i.totalCents)
            ));
        }
        for (billing_accounting.PaymentRec p : payments) {
            if (p == null) continue;
            arEvents.add(new StatementEvent(
                    firstNonBlank(p.postedAt, p.createdAt),
                    safe(p.kind).trim().isBlank() ? "payment" : p.kind,
                    firstNonBlank(p.reference, p.uuid),
                    "Payment applied to invoice " + safe(p.invoiceUuid),
                    -Math.max(0L, p.amountCents)
            ));
        }
        arEvents.sort(Comparator.comparing((StatementEvent e) -> sortableDateKey(e == null ? "" : e.date))
                .thenComparing(e -> safe(e == null ? "" : e.type))
                .thenComparing(e -> safe(e == null ? "" : e.reference)));

        long arOpening = 0L;
        for (StatementEvent e : arEvents) {
            if (e == null) continue;
            if (isBeforePeriod(e.date, start)) arOpening += e.deltaCents;
        }

        long arPeriodCharges = 0L;
        long arPeriodPayments = 0L;
        long arRunning = arOpening;
        ArrayList<Map<String, String>> arRows = new ArrayList<Map<String, String>>();
        for (StatementEvent e : arEvents) {
            if (e == null) continue;
            if (!isWithinPeriod(e.date, start, end)) continue;
            long debit = e.deltaCents > 0L ? e.deltaCents : 0L;
            long credit = e.deltaCents < 0L ? -e.deltaCents : 0L;
            if (debit > 0L) arPeriodCharges += debit;
            if (credit > 0L) arPeriodPayments += credit;
            arRunning += e.deltaCents;

            LinkedHashMap<String, String> row = new LinkedHashMap<String, String>();
            row.put("date", e.date);
            row.put("type", e.type);
            row.put("reference", e.reference);
            row.put("description", e.description);
            row.put("debit", cents(debit));
            row.put("credit", cents(credit));
            row.put("balance", cents(arRunning));
            arRows.add(row);
        }
        long arClosing = arOpening + arPeriodCharges - arPeriodPayments;

        ArrayList<TrustEvent> trustEvents = new ArrayList<TrustEvent>();
        for (billing_accounting.TrustTxnRec t : trustTxns) {
            if (t == null) continue;
            long signed = signedTrustAmount(t);
            if (signed == 0L) continue;
            String kind = safe(t.kind).trim();
            String description = "Trust transaction";
            if (billing_accounting.TRUST_DEPOSIT.equals(kind)) description = "Trust deposit";
            if (billing_accounting.TRUST_APPLY_TO_INVOICE.equals(kind)) description = "Trust applied to invoice " + safe(t.invoiceUuid);
            if (billing_accounting.TRUST_REFUND.equals(kind)) description = "Trust refund";
            trustEvents.add(new TrustEvent(
                    firstNonBlank(t.postedAt, t.createdAt),
                    kind,
                    firstNonBlank(t.reference, t.uuid),
                    description,
                    signed
            ));
        }
        trustEvents.sort(Comparator.comparing((TrustEvent e) -> sortableDateKey(e == null ? "" : e.date))
                .thenComparing(e -> safe(e == null ? "" : e.type))
                .thenComparing(e -> safe(e == null ? "" : e.reference)));

        long trustOpening = 0L;
        for (TrustEvent e : trustEvents) {
            if (e == null) continue;
            if (isBeforePeriod(e.date, start)) trustOpening += e.signedAmountCents;
        }

        long trustDeposits = 0L;
        long trustApplied = 0L;
        long trustRefunds = 0L;
        long trustRunning = trustOpening;
        ArrayList<Map<String, String>> trustRows = new ArrayList<Map<String, String>>();
        for (TrustEvent e : trustEvents) {
            if (e == null) continue;
            if (!isWithinPeriod(e.date, start, end)) continue;
            long deposit = e.signedAmountCents > 0L ? e.signedAmountCents : 0L;
            long withdrawal = e.signedAmountCents < 0L ? -e.signedAmountCents : 0L;
            if (deposit > 0L) trustDeposits += deposit;
            if (withdrawal > 0L) {
                if (billing_accounting.TRUST_REFUND.equals(safe(e.type).trim())) trustRefunds += withdrawal;
                else trustApplied += withdrawal;
            }
            trustRunning += e.signedAmountCents;

            LinkedHashMap<String, String> row = new LinkedHashMap<String, String>();
            row.put("date", e.date);
            row.put("type", e.type);
            row.put("reference", e.reference);
            row.put("description", e.description);
            row.put("deposit", cents(deposit));
            row.put("withdrawal", cents(withdrawal));
            row.put("balance", cents(trustRunning));
            trustRows.add(row);
        }
        long trustClosing = trustOpening + trustDeposits - trustApplied - trustRefunds;

        LinkedHashMap<String, String> values = baseValues(profileValues, customValues);
        put(values, "document.generated_at", Instant.now().toString());
        put(values, "matter.uuid", matter);
        put(values, "statement.period_start", start.isBlank() ? "(beginning)" : start);
        put(values, "statement.period_end", end.isBlank() ? "(current)" : end);
        put(values, "statement.ar_entries", toXmlList(arRows));
        put(values, "statement.ar_opening_balance", cents(arOpening));
        put(values, "statement.ar_period_charges", cents(arPeriodCharges));
        put(values, "statement.ar_period_payments", cents(arPeriodPayments));
        put(values, "statement.ar_closing_balance", cents(arClosing));
        put(values, "statement.trust_entries", toXmlList(trustRows));
        put(values, "statement.trust_opening_balance", cents(trustOpening));
        put(values, "statement.trust_period_deposits", cents(trustDeposits));
        put(values, "statement.trust_period_applied", cents(trustApplied));
        put(values, "statement.trust_period_refunds", cents(trustRefunds));
        put(values, "statement.trust_closing_balance", cents(trustClosing));

        return renderWithConfig(tu, DOC_STATEMENT_OF_ACCOUNT, values);
    }

    private RenderedDocument renderWithConfig(String tenantUuid, String documentType, LinkedHashMap<String, String> values) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String docType = normalizeDocumentType(documentType);
        if (tu.isBlank() || docType.isBlank()) throw new IllegalArgumentException("tenantUuid/documentType required");

        // Billing outputs explicitly enable advanced templating directives
        // ({{#if}}, {{#each}}, {{format.date ...}}) for high customizability.
        putIfBlank(values, "tenant.advanced_assembly_enabled", "true");
        putIfBlank(values, "advanced_assembly_enabled", "true");
        putIfBlank(values, "tenant.advanced_assembly_strict_mode", "false");

        TemplateConfigRec cfg = getTemplateConfig(tu, docType);
        if (cfg == null) cfg = new TemplateConfigRec(docType, "", defaultTemplateForType(docType), Instant.now().toString());

        document_assembler assembler = new document_assembler();
        if (!safe(cfg.templateUuid).trim().isBlank()) {
            form_templates forms = form_templates.defaultStore();
            form_templates.TemplateRec templateRec = forms.get(tu, cfg.templateUuid);
            if (templateRec == null) throw new IllegalStateException("Configured form template was not found: " + cfg.templateUuid);
            byte[] templateBytes = forms.readBytes(tu, cfg.templateUuid);
            if (templateBytes == null || templateBytes.length == 0) {
                throw new IllegalStateException("Configured form template is empty: " + cfg.templateUuid);
            }
            document_assembler.AssembledFile assembled = assembler.assemble(templateBytes, templateRec.fileExt, values);
            return new RenderedDocument(docType, "form_template", cfg.templateUuid, assembled, values);
        }

        String inlineTemplate = safe(cfg.inlineTemplate);
        if (inlineTemplate.trim().isBlank()) inlineTemplate = defaultTemplateForType(docType);
        byte[] bytes = inlineTemplate.getBytes(StandardCharsets.UTF_8);
        document_assembler.AssembledFile assembled = assembler.assemble(bytes, "txt", values);
        return new RenderedDocument(docType, "inline", "", assembled, values);
    }

    private static LinkedHashMap<String, String> baseValues(Map<String, String> profileValues, Map<String, String> customValues) {
        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
        if (profileValues != null) {
            for (Map.Entry<String, String> e : profileValues.entrySet()) {
                if (e == null) continue;
                String key = safe(e.getKey()).trim();
                if (key.isBlank()) continue;
                out.put(key, safe(e.getValue()));
            }
        }

        putIfBlank(out, "tenant.name", firstNonBlank(out.get("tenant.name"), out.get("tenant_name")));
        putIfBlank(out, "tenant.address", firstNonBlank(out.get("tenant.address"), out.get("tenant_address")));
        putIfBlank(out, "tenant.phone", firstNonBlank(out.get("tenant.phone"), out.get("tenant_phone")));
        putIfBlank(out, "client.name", firstNonBlank(out.get("client.name"), out.get("client_name")));
        putIfBlank(out, "client.address", firstNonBlank(out.get("client.address"), out.get("client_address")));
        putIfBlank(out, "matter.label", firstNonBlank(out.get("matter.label"), out.get("matter_label")));
        putIfBlank(out, "matter.number", firstNonBlank(out.get("matter.number"), out.get("matter_number")));

        if (customValues != null) {
            for (Map.Entry<String, String> e : customValues.entrySet()) {
                if (e == null) continue;
                String key = safe(e.getKey()).trim();
                if (key.isBlank()) continue;
                out.put(key, safe(e.getValue()));
            }
        }
        return out;
    }

    private static long sumPayments(List<billing_accounting.PaymentRec> payments) {
        long out = 0L;
        if (payments == null) return 0L;
        for (billing_accounting.PaymentRec p : payments) {
            if (p == null) continue;
            out += Math.max(0L, p.amountCents);
        }
        return out;
    }

    private static long signedTrustAmount(billing_accounting.TrustTxnRec t) {
        if (t == null) return 0L;
        String kind = safe(t.kind).trim();
        long amount = Math.max(0L, t.amountCents);
        if (billing_accounting.TRUST_DEPOSIT.equals(kind)) return amount;
        if (billing_accounting.TRUST_APPLY_TO_INVOICE.equals(kind)) return -amount;
        if (billing_accounting.TRUST_REFUND.equals(kind)) return -amount;
        return 0L;
    }

    private static boolean isBeforePeriod(String dateValue, String startIso) {
        String start = safe(startIso).trim();
        if (start.isBlank()) return false;
        return compareDateLikeIso(safe(dateValue), start) < 0;
    }

    private static boolean isWithinPeriod(String dateValue, String startIso, String endIso) {
        String date = safe(dateValue).trim();
        if (date.isBlank()) return false;
        String start = safe(startIso).trim();
        String end = safe(endIso).trim();
        if (!start.isBlank() && compareDateLikeIso(date, start) < 0) return false;
        if (!end.isBlank() && compareDateLikeIso(date, end) > 0) return false;
        return true;
    }

    private static int compareDateLikeIso(String a, String b) {
        long ea = toEpochMillis(a);
        long eb = toEpochMillis(b);
        if (ea >= 0L && eb >= 0L) return Long.compare(ea, eb);
        return safe(a).trim().compareTo(safe(b).trim());
    }

    private static String sortableDateKey(String value) {
        String v = safe(value).trim();
        long epoch = toEpochMillis(v);
        if (epoch >= 0L) return String.format(Locale.ROOT, "%020d|%s", epoch, v);
        return "99999999999999999999|" + v;
    }

    private static long toEpochMillis(String value) {
        String v = safe(value).trim();
        if (v.isBlank()) return -1L;
        try { return Instant.parse(v).toEpochMilli(); } catch (Exception ignored) {}
        try { return OffsetDateTime.parse(v).toInstant().toEpochMilli(); } catch (Exception ignored) {}
        try { return LocalDateTime.parse(v).toInstant(ZoneOffset.UTC).toEpochMilli(); } catch (Exception ignored) {}
        try { return LocalDate.parse(v).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(); } catch (Exception ignored) {}
        return -1L;
    }

    private static String toXmlList(List<Map<String, String>> rows) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("<list>");
        if (rows != null) {
            for (Map<String, String> row : rows) {
                if (row == null || row.isEmpty()) continue;
                sb.append("<item>");
                for (Map.Entry<String, String> e : row.entrySet()) {
                    if (e == null) continue;
                    String key = safeXmlTag(e.getKey());
                    if (key.isBlank()) continue;
                    sb.append("<").append(key).append(">")
                            .append(xmlText(e.getValue()))
                            .append("</").append(key).append(">");
                }
                sb.append("</item>");
            }
        }
        sb.append("</list>");
        return sb.toString();
    }

    private static List<TemplateConfigRec> defaultConfigs() {
        String now = Instant.now().toString();
        ArrayList<TemplateConfigRec> out = new ArrayList<TemplateConfigRec>();
        out.add(new TemplateConfigRec(DOC_INVOICE, "", DEFAULT_INVOICE_TEMPLATE, now));
        out.add(new TemplateConfigRec(DOC_TRUST_REQUEST, "", DEFAULT_TRUST_REQUEST_TEMPLATE, now));
        out.add(new TemplateConfigRec(DOC_STATEMENT_OF_ACCOUNT, "", DEFAULT_STATEMENT_TEMPLATE, now));
        sortByDocumentType(out);
        return out;
    }

    private static String defaultTemplateForType(String documentType) {
        String docType = normalizeDocumentType(documentType);
        if (DOC_INVOICE.equals(docType)) return DEFAULT_INVOICE_TEMPLATE;
        if (DOC_TRUST_REQUEST.equals(docType)) return DEFAULT_TRUST_REQUEST_TEMPLATE;
        if (DOC_STATEMENT_OF_ACCOUNT.equals(docType)) return DEFAULT_STATEMENT_TEMPLATE;
        return "";
    }

    private static List<String> invoiceTokenCatalog() {
        return List.of(
                "tenant.name",
                "tenant.address",
                "tenant.phone",
                "client.name",
                "client.address",
                "matter.uuid",
                "matter.label",
                "matter.number",
                "invoice.uuid",
                "invoice.status",
                "invoice.currency",
                "invoice.issued_at",
                "invoice.due_at",
                "invoice.subtotal",
                "invoice.tax",
                "invoice.total",
                "invoice.paid",
                "invoice.outstanding",
                "invoice.trust_applied",
                "invoice.notes",
                "invoice.lines",
                "invoice.lines_count",
                "invoice.payments",
                "payments.total_received",
                "trust.balance",
                "document.generated_at"
        );
    }

    private static List<String> trustRequestTokenCatalog() {
        return List.of(
                "tenant.name",
                "tenant.address",
                "tenant.phone",
                "client.name",
                "client.address",
                "matter.uuid",
                "matter.label",
                "matter.number",
                "trust.balance",
                "trust_request.request_id",
                "trust_request.requested_at",
                "trust_request.due_at",
                "trust_request.requested_by",
                "trust_request.amount",
                "trust_request.minimum_target_balance",
                "trust_request.reason",
                "trust_request.notes",
                "document.generated_at"
        );
    }

    private static List<String> statementTokenCatalog() {
        return List.of(
                "tenant.name",
                "tenant.address",
                "tenant.phone",
                "client.name",
                "client.address",
                "matter.uuid",
                "matter.label",
                "matter.number",
                "statement.period_start",
                "statement.period_end",
                "statement.ar_entries",
                "statement.ar_opening_balance",
                "statement.ar_period_charges",
                "statement.ar_period_payments",
                "statement.ar_closing_balance",
                "statement.trust_entries",
                "statement.trust_opening_balance",
                "statement.trust_period_deposits",
                "statement.trust_period_applied",
                "statement.trust_period_refunds",
                "statement.trust_closing_balance",
                "document.generated_at"
        );
    }

    private static ReentrantReadWriteLock lockFor(String tenantUuid) {
        return LOCKS.computeIfAbsent(tenantUuid, k -> new ReentrantReadWriteLock());
    }

    private static Path configPath(String tenantUuid) {
        return Paths.get("data", "tenants", safeFileToken(tenantUuid), "billing", "output_templates.xml").toAbsolutePath();
    }

    private static List<TemplateConfigRec> readAllLocked(String tenantUuid) throws Exception {
        ArrayList<TemplateConfigRec> out = new ArrayList<TemplateConfigRec>();
        Path p = configPath(tenantUuid);
        if (!Files.exists(p)) return out;

        Document d = parseXml(p);
        Element root = d == null ? null : d.getDocumentElement();
        if (root == null) return out;

        NodeList docs = root.getElementsByTagName("document");
        for (int i = 0; i < docs.getLength(); i++) {
            Node n = docs.item(i);
            if (!(n instanceof Element)) continue;
            Element e = (Element) n;
            String type = normalizeDocumentType(e.getAttribute("type"));
            if (type.isBlank()) type = normalizeDocumentType(text(e, "type"));
            if (type.isBlank()) continue;
            String templateUuid = text(e, "template_uuid");
            String inline = text(e, "inline_template");
            String updated = text(e, "updated_at");
            out.add(new TemplateConfigRec(type, templateUuid, inline, updated));
        }
        sortByDocumentType(out);
        return out;
    }

    private static void writeAllLocked(String tenantUuid, List<TemplateConfigRec> all) throws Exception {
        Path p = configPath(tenantUuid);
        Files.createDirectories(p.getParent());
        String now = Instant.now().toString();

        ArrayList<TemplateConfigRec> rows = new ArrayList<TemplateConfigRec>();
        if (all != null) {
            for (TemplateConfigRec r : all) {
                if (r == null) continue;
                String docType = normalizeDocumentType(r.documentType);
                if (docType.isBlank()) continue;
                rows.add(new TemplateConfigRec(docType, r.templateUuid, r.inlineTemplate,
                        safe(r.updatedAt).isBlank() ? now : r.updatedAt));
            }
        }

        LinkedHashMap<String, TemplateConfigRec> byType = new LinkedHashMap<String, TemplateConfigRec>();
        for (TemplateConfigRec r : rows) byType.put(r.documentType, r);
        for (TemplateConfigRec d : defaultConfigs()) byType.putIfAbsent(d.documentType, d);

        ArrayList<TemplateConfigRec> finalRows = new ArrayList<TemplateConfigRec>(byType.values());
        sortByDocumentType(finalRows);

        StringBuilder sb = new StringBuilder(2048);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<billing_output_templates updated=\"").append(xmlAttr(now)).append("\">\n");
        for (TemplateConfigRec r : finalRows) {
            if (r == null) continue;
            sb.append("  <document type=\"").append(xmlAttr(r.documentType)).append("\">\n");
            sb.append("    <template_uuid>").append(xmlText(r.templateUuid)).append("</template_uuid>\n");
            sb.append("    <inline_template>").append(xmlText(r.inlineTemplate)).append("</inline_template>\n");
            sb.append("    <updated_at>").append(xmlText(r.updatedAt)).append("</updated_at>\n");
            sb.append("  </document>\n");
        }
        sb.append("</billing_output_templates>\n");
        writeAtomic(p, sb.toString());
    }

    private static void sortByDocumentType(List<TemplateConfigRec> rows) {
        if (rows == null) return;
        rows.sort((a, b) -> {
            String ak = safe(a == null ? "" : a.documentType);
            String bk = safe(b == null ? "" : b.documentType);
            return ak.compareToIgnoreCase(bk);
        });
    }

    private static void writeAtomic(Path p, String content) throws Exception {
        Path tmp = p.resolveSibling(p.getFileName().toString() + ".tmp." + UUID.randomUUID());
        Files.writeString(tmp, safe(content), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        try {
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception ignored) {
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Document parseXml(Path p) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        DocumentBuilder b = f.newDocumentBuilder();
        try (var in = Files.newInputStream(p)) {
            return b.parse(in);
        }
    }

    private static String text(Element parent, String tag) {
        if (parent == null || safe(tag).isBlank()) return "";
        NodeList nl = parent.getElementsByTagName(tag);
        if (nl == null || nl.getLength() == 0) return "";
        Node n = nl.item(0);
        return n == null ? "" : safe(n.getTextContent());
    }

    private static String normalizeDocumentType(String value) {
        String v = safe(value).trim().toLowerCase(Locale.ROOT);
        v = v.replace('-', '_').replace(' ', '_');
        if ("invoice".equals(v) || "invoices".equals(v)) return DOC_INVOICE;
        if ("trust_request".equals(v) || "trustrequest".equals(v) || "retainer_request".equals(v)) return DOC_TRUST_REQUEST;
        if ("statement".equals(v) || "statement_of_account".equals(v) || "account_statement".equals(v)) return DOC_STATEMENT_OF_ACCOUNT;
        return "";
    }

    private static String safeXmlTag(String key) {
        String k = safe(key).trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\-]", "_");
        if (k.isBlank()) return "";
        if (!Character.isLetter(k.charAt(0)) && k.charAt(0) != '_') k = "f_" + k;
        return k;
    }

    private static String cents(long amountCents) {
        long abs = Math.abs(amountCents);
        long whole = abs / 100L;
        long cents = abs % 100L;
        String sign = amountCents < 0L ? "-" : "";
        return sign + whole + "." + String.format(Locale.ROOT, "%02d", cents);
    }

    private static void put(LinkedHashMap<String, String> map, String key, String value) {
        if (map == null) return;
        String k = safe(key).trim();
        if (k.isBlank()) return;
        map.put(k, safe(value));
    }

    private static void putIfBlank(LinkedHashMap<String, String> map, String key, String value) {
        if (map == null) return;
        String k = safe(key).trim();
        if (k.isBlank()) return;
        if (!safe(map.get(k)).trim().isBlank()) return;
        map.put(k, safe(value));
    }

    private static String safeFileToken(String s) {
        return safe(s).trim().replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String v : values) {
            String t = safe(v).trim();
            if (!t.isBlank()) return t;
        }
        return "";
    }

    private static String xmlAttr(String s) {
        return xmlText(s).replace("\"", "&quot;");
    }

    private static String xmlText(String s) {
        return safe(s)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
