package net.familylawandprobate.controversies;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * online_payments
 *
 * Payment processor abstraction with multi-processor support and billing linkage.
 */
public final class online_payments {

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final ConcurrentHashMap<String, ReentrantReadWriteLock> LOCKS = new ConcurrentHashMap<String, ReentrantReadWriteLock>();

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_PAID = "paid";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_CANCELLED = "cancelled";

    private static final String PROCESSOR_MANUAL = "manual";
    private static final String PROCESSOR_LAWPAY_STUB = "lawpay_stub";
    private static final String PROCESSOR_STRIPE_STUB = "stripe_stub";

    public static online_payments defaultStore() {
        return new online_payments();
    }

    public static final class ProcessorInfo {
        public final String processorKey;
        public final String label;
        public final String mode;

        public ProcessorInfo(String processorKey, String label, String mode) {
            this.processorKey = safe(processorKey).trim();
            this.label = safe(label).trim();
            this.mode = safe(mode).trim();
        }
    }

    public static final class CheckoutInput {
        public String invoiceUuid = "";
        public String processorKey = PROCESSOR_MANUAL;
        public String currency = "USD";
        public long amountCents = 0L;
        public String payerName = "";
        public String payerEmail = "";
        public String returnUrl = "";
        public String cancelUrl = "";
        public String metadataJson = "";
    }

    public static final class PaymentTransactionRec {
        public final String transactionUuid;
        public final String processorKey;
        public final String status;
        public final String invoiceUuid;
        public final String matterUuid;
        public final String currency;
        public final long amountCents;
        public final String checkoutUrl;
        public final String providerCheckoutId;
        public final String providerPaymentId;
        public final String payerName;
        public final String payerEmail;
        public final String reference;
        public final String errorMessage;
        public final String metadataJson;
        public final String createdAt;
        public final String updatedAt;
        public final String paidAt;
        public final String failedAt;

        public PaymentTransactionRec(String transactionUuid,
                                     String processorKey,
                                     String status,
                                     String invoiceUuid,
                                     String matterUuid,
                                     String currency,
                                     long amountCents,
                                     String checkoutUrl,
                                     String providerCheckoutId,
                                     String providerPaymentId,
                                     String payerName,
                                     String payerEmail,
                                     String reference,
                                     String errorMessage,
                                     String metadataJson,
                                     String createdAt,
                                     String updatedAt,
                                     String paidAt,
                                     String failedAt) {
            this.transactionUuid = safe(transactionUuid).trim();
            this.processorKey = normalizeProcessor(processorKey);
            this.status = normalizeStatus(status);
            this.invoiceUuid = safe(invoiceUuid).trim();
            this.matterUuid = safe(matterUuid).trim();
            this.currency = normalizeCurrency(currency);
            this.amountCents = Math.max(0L, amountCents);
            this.checkoutUrl = safe(checkoutUrl).trim();
            this.providerCheckoutId = safe(providerCheckoutId).trim();
            this.providerPaymentId = safe(providerPaymentId).trim();
            this.payerName = safe(payerName).trim();
            this.payerEmail = safe(payerEmail).trim();
            this.reference = safe(reference).trim();
            this.errorMessage = safe(errorMessage);
            this.metadataJson = safe(metadataJson);
            this.createdAt = safe(createdAt).trim();
            this.updatedAt = safe(updatedAt).trim();
            this.paidAt = safe(paidAt).trim();
            this.failedAt = safe(failedAt).trim();
        }
    }

    public static final class CheckoutResult {
        public final PaymentTransactionRec transaction;
        public final String checkoutUrl;

        public CheckoutResult(PaymentTransactionRec transaction, String checkoutUrl) {
            this.transaction = transaction;
            this.checkoutUrl = safe(checkoutUrl).trim();
        }
    }

    private interface Processor {
        String key();
        String label();
        String mode();
        ProviderCheckout createCheckout(String tenantUuid, CheckoutInput input, billing_accounting.InvoiceRec invoice, String transactionUuid);
    }

    private static final class ProviderCheckout {
        String checkoutUrl = "";
        String providerCheckoutId = "";
    }

    private static final class ManualProcessor implements Processor {
        @Override public String key() { return PROCESSOR_MANUAL; }
        @Override public String label() { return "Manual/Offline"; }
        @Override public String mode() { return "local"; }

        @Override
        public ProviderCheckout createCheckout(String tenantUuid, CheckoutInput input, billing_accounting.InvoiceRec invoice, String transactionUuid) {
            ProviderCheckout out = new ProviderCheckout();
            out.providerCheckoutId = "manual_" + safe(transactionUuid).trim();
            out.checkoutUrl = "/billing.jsp?invoice_uuid=" + safe(invoice == null ? "" : invoice.uuid).trim()
                    + "&payment_txn_uuid=" + safe(transactionUuid).trim();
            return out;
        }
    }

    private static final class StubRemoteProcessor implements Processor {
        private final String key;
        private final String label;

        StubRemoteProcessor(String key, String label) {
            this.key = normalizeProcessor(key);
            this.label = safe(label).trim();
        }

        @Override public String key() { return key; }
        @Override public String label() { return label; }
        @Override public String mode() { return "stub_remote"; }

        @Override
        public ProviderCheckout createCheckout(String tenantUuid, CheckoutInput input, billing_accounting.InvoiceRec invoice, String transactionUuid) {
            ProviderCheckout out = new ProviderCheckout();
            out.providerCheckoutId = key + "_" + UUID.randomUUID().toString().replace("-", "");
            out.checkoutUrl = "https://payments.example.invalid/" + key + "/checkout/" + out.providerCheckoutId;
            return out;
        }
    }

    private static final class FileRec {
        public String updated_at = "";
        public ArrayList<StoredTransactionRec> transactions = new ArrayList<StoredTransactionRec>();
    }

    private static final class StoredTransactionRec {
        public String transaction_uuid = "";
        public String processor_key = PROCESSOR_MANUAL;
        public String status = STATUS_PENDING;
        public String invoice_uuid = "";
        public String matter_uuid = "";
        public String currency = "USD";
        public long amount_cents = 0L;
        public String checkout_url = "";
        public String provider_checkout_id = "";
        public String provider_payment_id = "";
        public String payer_name = "";
        public String payer_email = "";
        public String reference = "";
        public String error_message = "";
        public String metadata_json = "";
        public String created_at = "";
        public String updated_at = "";
        public String paid_at = "";
        public String failed_at = "";
    }

    public List<ProcessorInfo> listProcessors() {
        ArrayList<ProcessorInfo> out = new ArrayList<ProcessorInfo>();
        for (Processor p : processors()) {
            out.add(new ProcessorInfo(p.key(), p.label(), p.mode()));
        }
        return out;
    }

    public CheckoutResult createCheckout(String tenantUuid, CheckoutInput in) throws Exception {
        String tu = safeToken(tenantUuid);
        if (tu.isBlank()) throw new IllegalArgumentException("tenantUuid required");
        CheckoutInput input = in == null ? new CheckoutInput() : in;
        String invoiceUuid = safe(input.invoiceUuid).trim();
        if (invoiceUuid.isBlank()) throw new IllegalArgumentException("invoice_uuid is required.");

        billing_accounting ledger = billing_runtime_registry.tenantLedger(tu);
        billing_accounting.InvoiceRec invoice = ledger.getInvoice(invoiceUuid);
        if (invoice == null) throw new IllegalArgumentException("Invoice not found.");
        if (!(billing_accounting.INVOICE_ISSUED.equals(invoice.status) || billing_accounting.INVOICE_PARTIALLY_PAID.equals(invoice.status))) {
            throw new IllegalStateException("Invoice is not payable in current status.");
        }

        long amount = input.amountCents > 0L ? input.amountCents : invoice.outstandingCents;
        if (amount <= 0L) throw new IllegalArgumentException("amount must be > 0.");
        if (amount > invoice.outstandingCents) throw new IllegalArgumentException("amount exceeds invoice outstanding.");

        Processor processor = processorByKey(input.processorKey);
        String now = app_clock.now().toString();
        String txUuid = "paytx_" + UUID.randomUUID().toString().replace("-", "");
        ProviderCheckout providerCheckout = processor.createCheckout(tu, input, invoice, txUuid);

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensureLocked(tu);
            FileRec file = readLocked(tu);
            if (file.transactions == null) file.transactions = new ArrayList<StoredTransactionRec>();

            StoredTransactionRec row = new StoredTransactionRec();
            row.transaction_uuid = txUuid;
            row.processor_key = processor.key();
            row.status = STATUS_PENDING;
            row.invoice_uuid = safe(invoice.uuid).trim();
            row.matter_uuid = safe(invoice.matterUuid).trim();
            String requestedCurrency = safe(input.currency).trim();
            row.currency = normalizeCurrency(requestedCurrency.isBlank() ? invoice.currency : requestedCurrency);
            row.amount_cents = amount;
            row.checkout_url = safe(providerCheckout.checkoutUrl).trim();
            row.provider_checkout_id = safe(providerCheckout.providerCheckoutId).trim();
            row.provider_payment_id = "";
            row.payer_name = safe(input.payerName).trim();
            row.payer_email = safe(input.payerEmail).trim();
            row.reference = "";
            row.error_message = "";
            row.metadata_json = safe(input.metadataJson);
            row.created_at = now;
            row.updated_at = now;
            row.paid_at = "";
            row.failed_at = "";
            file.transactions.add(row);
            file.updated_at = now;
            writeLocked(tu, file);

            fireIntegrationEvent(tu, "payments.transaction.created", row);
            return new CheckoutResult(toRec(row), row.checkout_url);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public PaymentTransactionRec getTransaction(String tenantUuid, String transactionUuid) throws Exception {
        String tu = safeToken(tenantUuid);
        String tx = safe(transactionUuid).trim();
        if (tu.isBlank() || tx.isBlank()) return null;
        ReentrantReadWriteLock lock = lockFor(tu);
        lock.readLock().lock();
        try {
            ensureLocked(tu);
            FileRec file = readLocked(tu);
            StoredTransactionRec row = findTxn(file, tx);
            return row == null ? null : toRec(row);
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<PaymentTransactionRec> listTransactions(String tenantUuid,
                                                        String matterUuid,
                                                        String invoiceUuid,
                                                        String status) throws Exception {
        String tu = safeToken(tenantUuid);
        if (tu.isBlank()) return List.of();
        String matter = safe(matterUuid).trim();
        String invoice = safe(invoiceUuid).trim();
        String wantedStatus = normalizeStatus(status);
        boolean statusFilter = !safe(status).trim().isBlank();

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.readLock().lock();
        try {
            ensureLocked(tu);
            FileRec file = readLocked(tu);
            ArrayList<PaymentTransactionRec> out = new ArrayList<PaymentTransactionRec>();
            for (StoredTransactionRec row : file.transactions) {
                if (row == null) continue;
                if (!matter.isBlank() && !matter.equals(safe(row.matter_uuid).trim())) continue;
                if (!invoice.isBlank() && !invoice.equals(safe(row.invoice_uuid).trim())) continue;
                if (statusFilter && !wantedStatus.equals(normalizeStatus(row.status))) continue;
                out.add(toRec(row));
            }
            out.sort((a, b) -> compareByIsoThenUuid(a == null ? "" : a.updatedAt, b == null ? "" : b.updatedAt,
                    a == null ? "" : a.transactionUuid, b == null ? "" : b.transactionUuid));
            return out;
        } finally {
            lock.readLock().unlock();
        }
    }

    public PaymentTransactionRec markPaid(String tenantUuid,
                                          String transactionUuid,
                                          String providerPaymentId,
                                          String reference,
                                          String postedAt) throws Exception {
        String tu = safeToken(tenantUuid);
        String tx = safe(transactionUuid).trim();
        if (tu.isBlank() || tx.isBlank()) throw new IllegalArgumentException("tenantUuid and transactionUuid are required.");
        String when = safe(postedAt).trim().isBlank() ? app_clock.now().toString() : safe(postedAt).trim();

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensureLocked(tu);
            FileRec file = readLocked(tu);
            StoredTransactionRec row = findTxn(file, tx);
            if (row == null) throw new IllegalArgumentException("Payment transaction not found.");
            if (STATUS_PAID.equals(normalizeStatus(row.status))) return toRec(row);
            if (STATUS_CANCELLED.equals(normalizeStatus(row.status))) {
                throw new IllegalStateException("Cancelled transaction cannot be marked paid.");
            }

            billing_accounting ledger = billing_runtime_registry.tenantLedger(tu);
            ledger.recordOperatingPayment(
                    safe(row.invoice_uuid),
                    row.amount_cents,
                    when,
                    safe(reference).trim().isBlank() ? ("payment_txn:" + tx) : safe(reference).trim()
            );

            row.status = STATUS_PAID;
            row.provider_payment_id = safe(providerPaymentId).trim();
            row.reference = safe(reference).trim();
            row.error_message = "";
            row.paid_at = when;
            row.updated_at = app_clock.now().toString();
            file.updated_at = row.updated_at;
            writeLocked(tu, file);
            fireIntegrationEvent(tu, "payments.transaction.paid", row);
            return toRec(row);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public PaymentTransactionRec markFailed(String tenantUuid, String transactionUuid, String errorMessage) throws Exception {
        String tu = safeToken(tenantUuid);
        String tx = safe(transactionUuid).trim();
        if (tu.isBlank() || tx.isBlank()) throw new IllegalArgumentException("tenantUuid and transactionUuid are required.");
        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensureLocked(tu);
            FileRec file = readLocked(tu);
            StoredTransactionRec row = findTxn(file, tx);
            if (row == null) throw new IllegalArgumentException("Payment transaction not found.");
            row.status = STATUS_FAILED;
            row.error_message = safe(errorMessage);
            row.failed_at = app_clock.now().toString();
            row.updated_at = row.failed_at;
            file.updated_at = row.updated_at;
            writeLocked(tu, file);
            fireIntegrationEvent(tu, "payments.transaction.failed", row);
            return toRec(row);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public PaymentTransactionRec setCancelled(String tenantUuid, String transactionUuid, String reason) throws Exception {
        String tu = safeToken(tenantUuid);
        String tx = safe(transactionUuid).trim();
        if (tu.isBlank() || tx.isBlank()) throw new IllegalArgumentException("tenantUuid and transactionUuid are required.");
        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensureLocked(tu);
            FileRec file = readLocked(tu);
            StoredTransactionRec row = findTxn(file, tx);
            if (row == null) throw new IllegalArgumentException("Payment transaction not found.");
            if (STATUS_PAID.equals(normalizeStatus(row.status))) throw new IllegalStateException("Paid transaction cannot be cancelled.");
            row.status = STATUS_CANCELLED;
            row.error_message = safe(reason);
            row.failed_at = safe(row.failed_at).trim().isBlank() ? app_clock.now().toString() : row.failed_at;
            row.updated_at = app_clock.now().toString();
            file.updated_at = row.updated_at;
            writeLocked(tu, file);
            fireIntegrationEvent(tu, "payments.transaction.cancelled", row);
            return toRec(row);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private static void fireIntegrationEvent(String tenantToken, String eventType, StoredTransactionRec row) {
        try {
            LinkedHashMap<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("transaction_uuid", safe(row == null ? "" : row.transaction_uuid));
            payload.put("processor_key", safe(row == null ? "" : row.processor_key));
            payload.put("status", normalizeStatus(row == null ? "" : row.status));
            payload.put("invoice_uuid", safe(row == null ? "" : row.invoice_uuid));
            payload.put("matter_uuid", safe(row == null ? "" : row.matter_uuid));
            payload.put("amount_cents", row == null ? 0L : row.amount_cents);
            payload.put("currency", normalizeCurrency(row == null ? "" : row.currency));
            payload.put("reference", safe(row == null ? "" : row.reference));
            payload.put("paid_at", safe(row == null ? "" : row.paid_at));
            payload.put("failed_at", safe(row == null ? "" : row.failed_at));
            integration_webhooks.defaultStore().dispatchEvent(tenantToken, eventType, payload);
        } catch (Exception ignored) {
        }
    }

    private static Processor processorByKey(String raw) {
        String wanted = normalizeProcessor(raw);
        for (Processor p : processors()) {
            if (wanted.equals(p.key())) return p;
        }
        throw new IllegalArgumentException("Unsupported processor_key: " + wanted);
    }

    private static List<Processor> processors() {
        ArrayList<Processor> out = new ArrayList<Processor>();
        out.add(new ManualProcessor());
        out.add(new StubRemoteProcessor(PROCESSOR_LAWPAY_STUB, "LawPay (Stub)"));
        out.add(new StubRemoteProcessor(PROCESSOR_STRIPE_STUB, "Stripe (Stub)"));
        return out;
    }

    private static StoredTransactionRec findTxn(FileRec file, String transactionUuid) {
        if (file == null || file.transactions == null) return null;
        String target = safe(transactionUuid).trim();
        if (target.isBlank()) return null;
        for (StoredTransactionRec row : file.transactions) {
            if (row == null) continue;
            if (target.equals(safe(row.transaction_uuid).trim())) return row;
        }
        return null;
    }

    private static PaymentTransactionRec toRec(StoredTransactionRec row) {
        if (row == null) return null;
        return new PaymentTransactionRec(
                safe(row.transaction_uuid),
                safe(row.processor_key),
                safe(row.status),
                safe(row.invoice_uuid),
                safe(row.matter_uuid),
                safe(row.currency),
                row.amount_cents,
                safe(row.checkout_url),
                safe(row.provider_checkout_id),
                safe(row.provider_payment_id),
                safe(row.payer_name),
                safe(row.payer_email),
                safe(row.reference),
                safe(row.error_message),
                safe(row.metadata_json),
                safe(row.created_at),
                safe(row.updated_at),
                safe(row.paid_at),
                safe(row.failed_at)
        );
    }

    private static String normalizeProcessor(String raw) {
        String v = safe(raw).trim().toLowerCase(Locale.ROOT);
        if (PROCESSOR_LAWPAY_STUB.equals(v) || "lawpay".equals(v)) return PROCESSOR_LAWPAY_STUB;
        if (PROCESSOR_STRIPE_STUB.equals(v) || "stripe".equals(v)) return PROCESSOR_STRIPE_STUB;
        return PROCESSOR_MANUAL;
    }

    private static String normalizeStatus(String raw) {
        String v = safe(raw).trim().toLowerCase(Locale.ROOT);
        if (STATUS_PAID.equals(v)) return STATUS_PAID;
        if (STATUS_FAILED.equals(v)) return STATUS_FAILED;
        if (STATUS_CANCELLED.equals(v) || "canceled".equals(v)) return STATUS_CANCELLED;
        return STATUS_PENDING;
    }

    private static String normalizeCurrency(String raw) {
        String v = safe(raw).trim().toUpperCase(Locale.ROOT);
        if (v.isBlank()) return "USD";
        if (v.length() > 8) v = v.substring(0, 8);
        return v;
    }

    private static String safeToken(String tenantUuid) {
        String tu = safe(tenantUuid).trim();
        if (tu.isBlank()) return "";
        return tu.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static ReentrantReadWriteLock lockFor(String tenantToken) {
        return LOCKS.computeIfAbsent(safeToken(tenantToken), k -> new ReentrantReadWriteLock());
    }

    private static Path storePath(String tenantToken) {
        return Paths.get("data", "tenants", safeToken(tenantToken), "billing", "payment_transactions.json").toAbsolutePath();
    }

    private static void ensureLocked(String tenantToken) throws Exception {
        Path file = storePath(tenantToken);
        Files.createDirectories(file.getParent());
        if (Files.exists(file)) return;
        FileRec empty = new FileRec();
        empty.updated_at = app_clock.now().toString();
        writeJsonAtomic(file, empty);
    }

    private static FileRec readLocked(String tenantToken) throws Exception {
        Path file = storePath(tenantToken);
        if (!Files.exists(file)) return new FileRec();
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length == 0) return new FileRec();
        try {
            FileRec rec = JSON.readValue(bytes, FileRec.class);
            if (rec == null) rec = new FileRec();
            if (rec.transactions == null) rec.transactions = new ArrayList<StoredTransactionRec>();
            return rec;
        } catch (Exception ignored) {
            return new FileRec();
        }
    }

    private static void writeLocked(String tenantToken, FileRec file) throws Exception {
        if (file == null) file = new FileRec();
        if (file.transactions == null) file.transactions = new ArrayList<StoredTransactionRec>();
        if (safe(file.updated_at).trim().isBlank()) file.updated_at = app_clock.now().toString();
        writeJsonAtomic(storePath(tenantToken), file);
    }

    private static void writeJsonAtomic(Path file, FileRec rec) throws Exception {
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
        byte[] json = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(rec == null ? new FileRec() : rec);
        Files.write(tmp, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception ex) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static int compareByIsoThenUuid(String isoA, String isoB, String uuidA, String uuidB) {
        String a = safe(isoA).trim();
        String b = safe(isoB).trim();
        int cmp = b.compareTo(a);
        if (cmp != 0) return cmp;
        return safe(uuidB).trim().compareTo(safe(uuidA).trim());
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
