package net.familylawandprobate.controversies;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

public class billing_output_documents_test {

    @Test
    void default_invoice_output_renders_with_billing_tokens() throws Exception {
        String tenantUuid = "billing-docs-default-" + UUID.randomUUID();
        Path tenantDir = Paths.get("data", "tenants", tenantUuid).toAbsolutePath();
        deleteQuietly(tenantDir);
        try {
            billing_accounting ledger = billing_accounting.inMemory();
            billing_output_documents docs = billing_output_documents.defaultStore();
            String matterUuid = "matter-a";

            ledger.recordTrustDeposit(matterUuid, 50_000L, "USD", "2026-03-01T09:00:00Z", "Initial retainer");
            ledger.createTimeEntry(
                    matterUuid,
                    "user-a",
                    "research",
                    "Draft motion",
                    60,
                    20_000L,
                    "USD",
                    true,
                    "2026-03-02T09:00:00Z"
            );
            billing_accounting.InvoiceRec issued = ledger.finalizeInvoice(
                    ledger.draftInvoiceForMatter(matterUuid, "2026-03-03", "2026-03-17", "USD").uuid
            );
            ledger.applyTrustToInvoice(issued.uuid, 20_000L, "2026-03-04T09:00:00Z", "Apply trust");

            Map<String, String> profile = Map.of(
                    "tenant.name", "Chapman Law Group",
                    "client.name", "Jordan Client",
                    "matter.label", "Client v. Example"
            );
            Map<String, String> custom = Map.of("invoice.notes", "Custom thank-you clause.");

            billing_output_documents.RenderedDocument rendered = docs.renderInvoice(
                    tenantUuid,
                    ledger,
                    matterUuid,
                    issued.uuid,
                    profile,
                    custom
            );

            String text = new String(rendered.assembled.bytes, StandardCharsets.UTF_8);
            assertEquals("invoice", rendered.documentType);
            assertEquals("inline", rendered.templateSource);
            assertTrue(text.contains("INVOICE"));
            assertTrue(text.contains("Chapman Law Group"));
            assertTrue(text.contains("Jordan Client"));
            assertTrue(text.contains("Client v. Example"));
            assertTrue(text.contains("Custom thank-you clause."));
            assertTrue(text.contains("Trust Applied: 200.00"));
            assertTrue(text.contains("Current Trust Balance: 300.00"));
            assertTrue(text.contains("Draft motion"));
        } finally {
            deleteQuietly(tenantDir);
        }
    }

    @Test
    void inline_templates_can_be_overridden_for_invoice_layout() throws Exception {
        String tenantUuid = "billing-docs-inline-" + UUID.randomUUID();
        Path tenantDir = Paths.get("data", "tenants", tenantUuid).toAbsolutePath();
        deleteQuietly(tenantDir);
        try {
            billing_accounting ledger = billing_accounting.inMemory();
            billing_output_documents docs = billing_output_documents.defaultStore();
            String matterUuid = "matter-b";

            ledger.createTimeEntry(
                    matterUuid,
                    "user-b",
                    "review",
                    "Review exhibits",
                    30,
                    18_000L,
                    "USD",
                    true,
                    "2026-03-05T10:00:00Z"
            );
            billing_accounting.InvoiceRec issued = ledger.finalizeInvoice(
                    ledger.draftInvoiceForMatter(matterUuid, "2026-03-06", "2026-03-20", "USD").uuid
            );

            String customTemplate = """
                    CUSTOM INVOICE LAYOUT
                    Client: {{client.name}}
                    Matter: {{matter.label}}
                    Total Due: {{invoice.outstanding}}
                    {{#each invoice.lines}}* {{item.index}} {{item.description}} => {{item.total}}
                    {{/each}}
                    """;
            docs.setInlineTemplate(tenantUuid, billing_output_documents.DOC_INVOICE, customTemplate);

            billing_output_documents.RenderedDocument rendered = docs.renderInvoice(
                    tenantUuid,
                    ledger,
                    matterUuid,
                    issued.uuid,
                    Map.of("client.name", "Taylor Example", "matter.label", "Estate Dispute"),
                    Map.of()
            );
            String text = new String(rendered.assembled.bytes, StandardCharsets.UTF_8);
            assertEquals("inline", rendered.templateSource);
            assertTrue(text.contains("CUSTOM INVOICE LAYOUT"));
            assertTrue(text.contains("Taylor Example"));
            assertTrue(text.contains("Estate Dispute"));
            assertTrue(text.contains("Total Due: 90.00"));
            assertTrue(text.contains("* 1"));
            assertTrue(text.contains("Review exhibits"));
        } finally {
            deleteQuietly(tenantDir);
        }
    }

    @Test
    void statement_can_render_from_form_template_mapping() throws Exception {
        String tenantUuid = "billing-docs-form-" + UUID.randomUUID();
        Path tenantDir = Paths.get("data", "tenants", tenantUuid).toAbsolutePath();
        deleteQuietly(tenantDir);
        try {
            billing_accounting ledger = billing_accounting.inMemory();
            billing_output_documents docs = billing_output_documents.defaultStore();
            form_templates formTemplates = form_templates.defaultStore();
            String matterUuid = "matter-c";

            ledger.createExpenseEntry(
                    matterUuid,
                    "Filing fee",
                    15_000L,
                    0L,
                    "USD",
                    true,
                    "2026-03-01T09:00:00Z"
            );
            billing_accounting.InvoiceRec inv1 = ledger.finalizeInvoice(
                    ledger.draftInvoiceForMatter(matterUuid, "2026-03-02", "2026-03-16", "USD").uuid
            );
            ledger.recordOperatingPayment(inv1.uuid, 5_000L, "2026-03-03T09:00:00Z", "Card payment");

            String templateText = """
                    CUSTOM STATEMENT
                    Client: {{client.name}}
                    {{#each statement.ar_entries}}AR {{item.date}} {{item.type}} {{item.debit}} {{item.credit}} {{item.balance}}
                    {{/each}}
                    Closing AR: {{statement.ar_closing_balance}}
                    """;
            form_templates.TemplateRec template = formTemplates.create(
                    tenantUuid,
                    "Custom statement template",
                    "custom_statement.txt",
                    templateText.getBytes(StandardCharsets.UTF_8)
            );
            docs.setFormTemplate(tenantUuid, billing_output_documents.DOC_STATEMENT_OF_ACCOUNT, template.uuid);

            LinkedHashMap<String, String> profile = new LinkedHashMap<String, String>();
            profile.put("client.name", "Morgan Client");
            profile.put("matter.label", "Contested Probate");

            billing_output_documents.RenderedDocument rendered = docs.renderStatementOfAccount(
                    tenantUuid,
                    ledger,
                    matterUuid,
                    "2026-03-01",
                    "2026-03-31",
                    profile,
                    Map.of()
            );

            String text = new String(rendered.assembled.bytes, StandardCharsets.UTF_8);
            assertEquals("form_template", rendered.templateSource);
            assertEquals(template.uuid, rendered.templateUuid);
            assertTrue(text.contains("CUSTOM STATEMENT"));
            assertTrue(text.contains("Morgan Client"));
            assertTrue(text.contains("AR 2026-03-02"));
            assertTrue(text.contains("Closing AR: 100.00"));
        } finally {
            deleteQuietly(tenantDir);
        }
    }

    private static void deleteQuietly(Path p) {
        try {
            if (p == null || !Files.exists(p)) return;
            try (var walk = Files.walk(p)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {}
                });
            }
        } catch (Exception ignored) {}
    }
}
