package net.familylawandprobate.controversies.integrations.clio;

import net.familylawandprobate.controversies.billing_accounting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * ClioBillingMapper
 *
 * Lightweight adapter for mapping Clio billing payloads into billing_accounting
 * records while preserving Clio external IDs.
 *
 * Parsing is intentionally defensive because Clio payloads can vary by endpoint
 * and selected field list.
 */
public final class ClioBillingMapper {

    private ClioBillingMapper() {}

    public static final class ClioActivityRec {
        public final String id;
        public final String matterId;
        public final String userId;
        public final String description;
        public final String note;
        public final String date;
        public final long rateCentsPerHour;
        public final int minutes;
        public final String currency;
        public final String rawJson;

        public ClioActivityRec(String id,
                               String matterId,
                               String userId,
                               String description,
                               String note,
                               String date,
                               long rateCentsPerHour,
                               int minutes,
                               String currency,
                               String rawJson) {
            this.id = safe(id);
            this.matterId = safe(matterId);
            this.userId = safe(userId);
            this.description = safe(description);
            this.note = safe(note);
            this.date = safe(date);
            this.rateCentsPerHour = Math.max(0L, rateCentsPerHour);
            this.minutes = Math.max(0, minutes);
            this.currency = normalizeCurrency(currency);
            this.rawJson = safe(rawJson);
        }
    }

    public static final class ClioExpenseRec {
        public final String id;
        public final String matterId;
        public final String description;
        public final String date;
        public final long amountCents;
        public final long taxCents;
        public final String currency;
        public final String rawJson;

        public ClioExpenseRec(String id,
                              String matterId,
                              String description,
                              String date,
                              long amountCents,
                              long taxCents,
                              String currency,
                              String rawJson) {
            this.id = safe(id);
            this.matterId = safe(matterId);
            this.description = safe(description);
            this.date = safe(date);
            this.amountCents = Math.max(0L, amountCents);
            this.taxCents = Math.max(0L, taxCents);
            this.currency = normalizeCurrency(currency);
            this.rawJson = safe(rawJson);
        }
    }

    public static final class ClioBillRec {
        public final String id;
        public final String matterId;
        public final String state;
        public final String issuedAt;
        public final String dueAt;
        public final long subtotalCents;
        public final long taxCents;
        public final long totalCents;
        public final long paidCents;
        public final String currency;
        public final String rawJson;

        public ClioBillRec(String id,
                           String matterId,
                           String state,
                           String issuedAt,
                           String dueAt,
                           long subtotalCents,
                           long taxCents,
                           long totalCents,
                           long paidCents,
                           String currency,
                           String rawJson) {
            this.id = safe(id);
            this.matterId = safe(matterId);
            this.state = safe(state);
            this.issuedAt = safe(issuedAt);
            this.dueAt = safe(dueAt);
            this.subtotalCents = Math.max(0L, subtotalCents);
            this.taxCents = Math.max(0L, taxCents);
            this.totalCents = Math.max(0L, totalCents);
            this.paidCents = Math.max(0L, paidCents);
            this.currency = normalizeCurrency(currency);
            this.rawJson = safe(rawJson);
        }
    }

    public static final class ClioTrustTransactionRec {
        public final String id;
        public final String matterId;
        public final String billId;
        public final String kind;
        public final long amountCents;
        public final String date;
        public final String currency;
        public final String reference;
        public final String rawJson;

        public ClioTrustTransactionRec(String id,
                                       String matterId,
                                       String billId,
                                       String kind,
                                       long amountCents,
                                       String date,
                                       String currency,
                                       String reference,
                                       String rawJson) {
            this.id = safe(id);
            this.matterId = safe(matterId);
            this.billId = safe(billId);
            this.kind = safe(kind);
            this.amountCents = Math.max(0L, amountCents);
            this.date = safe(date);
            this.currency = normalizeCurrency(currency);
            this.reference = safe(reference);
            this.rawJson = safe(rawJson);
        }
    }

    public static ClioActivityRec activityFromJson(String json) {
        String matterId = firstNonBlank(
                JsonHelper.stringValue(json, "matter_id"),
                nestedId(json, "matter")
        );
        String userId = firstNonBlank(
                JsonHelper.stringValue(json, "user_id"),
                nestedId(json, "user")
        );
        int minutes = hoursToMinutes(firstNonBlank(
                JsonHelper.stringValue(json, "quantity_in_hours"),
                JsonHelper.stringValue(json, "quantity")
        ));
        if (minutes <= 0) {
            minutes = (int) Math.max(0L, JsonHelper.longValue(json, "quantity_in_minutes"));
        }

        long rateCents = firstMoneyCents(json,
                "rate_cents",
                "rate",
                "unit_price",
                "price");
        String description = firstNonBlank(
                JsonHelper.stringValue(json, "description"),
                JsonHelper.stringValue(json, "activity_description"),
                "Clio activity"
        );
        return new ClioActivityRec(
                JsonHelper.stringValue(json, "id"),
                matterId,
                userId,
                description,
                JsonHelper.stringValue(json, "note"),
                firstNonBlank(JsonHelper.stringValue(json, "date"), JsonHelper.stringValue(json, "created_at")),
                rateCents,
                minutes,
                firstNonBlank(JsonHelper.stringValue(json, "currency"), "USD"),
                json
        );
    }

    public static ClioExpenseRec expenseFromJson(String json) {
        String matterId = firstNonBlank(
                JsonHelper.stringValue(json, "matter_id"),
                nestedId(json, "matter")
        );
        long amount = firstMoneyCents(json,
                "amount_cents",
                "amount",
                "price",
                "total");
        long tax = firstMoneyCents(json, "tax_cents", "tax", "tax_total");
        String description = firstNonBlank(
                JsonHelper.stringValue(json, "description"),
                JsonHelper.stringValue(json, "name"),
                "Clio expense"
        );

        return new ClioExpenseRec(
                JsonHelper.stringValue(json, "id"),
                matterId,
                description,
                firstNonBlank(JsonHelper.stringValue(json, "date"), JsonHelper.stringValue(json, "created_at")),
                amount,
                tax,
                firstNonBlank(JsonHelper.stringValue(json, "currency"), "USD"),
                json
        );
    }

    public static ClioBillRec billFromJson(String json) {
        String matterId = firstNonBlank(
                JsonHelper.stringValue(json, "matter_id"),
                nestedId(json, "matter")
        );
        long subtotal = firstMoneyCents(json, "subtotal_cents", "subtotal", "amount_before_tax");
        long tax = firstMoneyCents(json, "tax_cents", "tax", "tax_total");
        long total = firstMoneyCents(json, "total_cents", "total", "amount");
        if (total <= 0L) total = subtotal + tax;
        long paid = firstMoneyCents(json, "paid_cents", "paid", "payments_total");
        String state = firstNonBlank(
                JsonHelper.stringValue(json, "state"),
                JsonHelper.stringValue(json, "status"),
                JsonHelper.stringValue(json, "bill_state"),
                "issued"
        );
        return new ClioBillRec(
                JsonHelper.stringValue(json, "id"),
                matterId,
                state,
                firstNonBlank(JsonHelper.stringValue(json, "issued_at"), JsonHelper.stringValue(json, "date")),
                firstNonBlank(JsonHelper.stringValue(json, "due_at"), JsonHelper.stringValue(json, "due_date")),
                subtotal,
                tax,
                total,
                paid,
                firstNonBlank(JsonHelper.stringValue(json, "currency"), "USD"),
                json
        );
    }

    public static ClioTrustTransactionRec trustTransactionFromJson(String json) {
        String matterId = firstNonBlank(
                JsonHelper.stringValue(json, "matter_id"),
                nestedId(json, "matter")
        );
        String billId = firstNonBlank(
                JsonHelper.stringValue(json, "bill_id"),
                nestedId(json, "bill")
        );
        long amount = firstMoneyCents(json, "amount_cents", "amount", "total");
        String kind = firstNonBlank(
                JsonHelper.stringValue(json, "type"),
                JsonHelper.stringValue(json, "kind"),
                "deposit"
        );

        return new ClioTrustTransactionRec(
                JsonHelper.stringValue(json, "id"),
                matterId,
                billId,
                kind,
                amount,
                firstNonBlank(JsonHelper.stringValue(json, "date"), JsonHelper.stringValue(json, "created_at")),
                firstNonBlank(JsonHelper.stringValue(json, "currency"), "USD"),
                firstNonBlank(JsonHelper.stringValue(json, "reference"), JsonHelper.stringValue(json, "description")),
                json
        );
    }

    public static billing_accounting.TimeEntryRec upsertTimeEntryFromActivity(billing_accounting engine,
                                                                              String localMatterUuid,
                                                                              ClioActivityRec activity) {
        if (engine == null) throw new IllegalArgumentException("engine is required");
        if (activity == null) throw new IllegalArgumentException("activity is required");
        String matter = !safe(localMatterUuid).trim().isBlank() ? safe(localMatterUuid).trim() : safe(activity.matterId).trim();
        return engine.importOrUpdateClioTimeEntry(
                activity.id,
                matter,
                activity.userId,
                activity.description,
                activity.note,
                activity.minutes <= 0 ? 1 : activity.minutes,
                activity.rateCentsPerHour,
                activity.currency,
                true,
                activity.date
        );
    }

    public static billing_accounting.ExpenseEntryRec upsertExpenseFromExpense(billing_accounting engine,
                                                                               String localMatterUuid,
                                                                               ClioExpenseRec expense) {
        if (engine == null) throw new IllegalArgumentException("engine is required");
        if (expense == null) throw new IllegalArgumentException("expense is required");
        String matter = !safe(localMatterUuid).trim().isBlank() ? safe(localMatterUuid).trim() : safe(expense.matterId).trim();
        return engine.importOrUpdateClioExpenseEntry(
                expense.id,
                matter,
                expense.description,
                Math.max(1L, expense.amountCents),
                expense.taxCents,
                expense.currency,
                true,
                expense.date
        );
    }

    public static billing_accounting.InvoiceRec upsertInvoiceFromBill(billing_accounting engine,
                                                                      String localMatterUuid,
                                                                      ClioBillRec bill) {
        if (engine == null) throw new IllegalArgumentException("engine is required");
        if (bill == null) throw new IllegalArgumentException("bill is required");
        String matter = !safe(localMatterUuid).trim().isBlank() ? safe(localMatterUuid).trim() : safe(bill.matterId).trim();
        long subtotal = bill.subtotalCents;
        long tax = bill.taxCents;
        long total = bill.totalCents;
        if (total > 0L && subtotal + tax <= 0L) subtotal = Math.max(0L, total - tax);

        return engine.importOrUpdateClioInvoiceSummary(
                bill.id,
                matter,
                bill.issuedAt,
                bill.dueAt,
                bill.currency,
                subtotal,
                tax,
                bill.paidCents,
                bill.state
        );
    }

    /**
     * Imports trust deposits and trust-funded bill payments.
     * Any non-deposit kind with a bill id is treated as a trust payment.
     */
    public static void importTrustTransaction(billing_accounting engine,
                                              String localMatterUuid,
                                              String localInvoiceUuid,
                                              ClioTrustTransactionRec txn) {
        if (engine == null) throw new IllegalArgumentException("engine is required");
        if (txn == null) throw new IllegalArgumentException("txn is required");
        String kind = safe(txn.kind).trim().toLowerCase(Locale.ROOT);
        String matter = !safe(localMatterUuid).trim().isBlank() ? safe(localMatterUuid).trim() : safe(txn.matterId).trim();
        if (kind.contains("deposit") || safe(txn.billId).trim().isBlank()) {
            engine.importClioTrustDeposit(txn.id, matter, Math.max(1L, txn.amountCents), txn.currency, txn.date, txn.reference);
            return;
        }
        String invoiceUuid = safe(localInvoiceUuid).trim();
        if (invoiceUuid.isBlank()) {
            throw new IllegalArgumentException("localInvoiceUuid is required for Clio trust payment imports");
        }
        engine.importClioTrustApplication(txn.id, invoiceUuid, Math.max(1L, txn.amountCents), txn.date, txn.reference);
    }

    private static String nestedId(String json, String objectKey) {
        String obj = JsonHelper.objectValue(json, objectKey);
        if (obj.isBlank()) return "";
        return JsonHelper.stringValue(obj, "id");
    }

    private static long firstMoneyCents(String json, String... keys) {
        if (keys == null) return 0L;
        for (String key : keys) {
            String k = safe(key).trim();
            if (k.isBlank()) continue;

            long direct = JsonHelper.longValue(json, k);
            if (direct > 0L) return direct;

            String raw = JsonHelper.stringValue(json, k);
            long fromString = moneyStringToCents(raw);
            if (fromString > 0L) return fromString;

            String obj = JsonHelper.objectValue(json, k);
            if (!obj.isBlank()) {
                long cents = JsonHelper.longValue(obj, "cents");
                if (cents > 0L) return cents;
                long amount = moneyStringToCents(JsonHelper.stringValue(obj, "amount"));
                if (amount > 0L) return amount;
            }
        }
        return 0L;
    }

    private static int hoursToMinutes(String hoursText) {
        String s = safe(hoursText).trim();
        if (s.isBlank()) return 0;
        try {
            BigDecimal hours = new BigDecimal(s);
            BigDecimal minutes = hours.multiply(BigDecimal.valueOf(60L)).setScale(0, RoundingMode.HALF_UP);
            return Math.max(0, minutes.intValue());
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static long moneyStringToCents(String amountText) {
        String s = safe(amountText).trim();
        if (s.isBlank()) return 0L;
        String normalized = s.replace("$", "").replace(",", "").trim();
        try {
            return new BigDecimal(normalized)
                    .multiply(BigDecimal.valueOf(100L))
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValueExact();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String v : values) {
            String t = safe(v).trim();
            if (!t.isBlank()) return t;
        }
        return "";
    }

    private static String normalizeCurrency(String currency) {
        String c = safe(currency).trim().toUpperCase(Locale.ROOT);
        return c.isBlank() ? "USD" : c;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
