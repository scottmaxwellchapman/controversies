package net.familylawandprobate.controversies;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

public class billing_accounting_exchange_test {

    @Test
    void gaap_financial_reports_balance_for_general_business_journals() {
        billing_accounting engine = billing_accounting.inMemory();

        engine.recordGeneralJournalEntry(
                "journal.manual",
                "JE-1",
                "Owner capital",
                List.of(
                        new billing_accounting.JournalLineRec(billing_accounting.ACCT_OPERATING_BANK, "", "", 100_000L, 0L, "Capital in"),
                        new billing_accounting.JournalLineRec(billing_accounting.ACCT_OWNER_EQUITY, "", "", 0L, 100_000L, "Owner equity")
                ),
                "2026-03-01"
        );
        engine.recordGeneralJournalEntry(
                "journal.manual",
                "JE-2",
                "Service revenue on account",
                List.of(
                        new billing_accounting.JournalLineRec(billing_accounting.ACCT_ACCOUNTS_RECEIVABLE, "", "", 30_000L, 0L, "Invoice customer"),
                        new billing_accounting.JournalLineRec(billing_accounting.ACCT_SERVICE_REVENUE, "", "", 0L, 30_000L, "Earned revenue")
                ),
                "2026-03-05"
        );
        engine.recordGeneralJournalEntry(
                "journal.manual",
                "JE-3",
                "Customer payment",
                List.of(
                        new billing_accounting.JournalLineRec(billing_accounting.ACCT_OPERATING_BANK, "", "", 30_000L, 0L, "Cash received"),
                        new billing_accounting.JournalLineRec(billing_accounting.ACCT_ACCOUNTS_RECEIVABLE, "", "", 0L, 30_000L, "Clear AR")
                ),
                "2026-03-10"
        );
        engine.recordGeneralJournalEntry(
                "journal.manual",
                "JE-4",
                "Rent expense paid",
                List.of(
                        new billing_accounting.JournalLineRec("expense:rent", "", "", 12_000L, 0L, "Rent"),
                        new billing_accounting.JournalLineRec(billing_accounting.ACCT_OPERATING_BANK, "", "", 0L, 12_000L, "Cash paid")
                ),
                "2026-03-15"
        );
        engine.recordGeneralJournalEntry(
                "journal.manual",
                "JE-5",
                "Office expense accrued",
                List.of(
                        new billing_accounting.JournalLineRec(billing_accounting.ACCT_OFFICE_EXPENSE, "", "", 3_000L, 0L, "Supplies"),
                        new billing_accounting.JournalLineRec(billing_accounting.ACCT_ACCOUNTS_PAYABLE, "", "", 0L, 3_000L, "Vendor payable")
                ),
                "2026-03-20"
        );

        billing_accounting.TrialBalanceRec trial = engine.trialBalance("2026-03-31");
        assertTrue(trial.balanced);
        assertEquals(trial.totalDebitCents, trial.totalCreditCents);

        billing_accounting.IncomeStatementRec income = engine.incomeStatement("2026-03-01", "2026-03-31");
        assertEquals(30_000L, income.totalRevenueCents);
        assertEquals(15_000L, income.totalExpenseCents);
        assertEquals(15_000L, income.netIncomeCents);

        billing_accounting.BalanceSheetRec sheet = engine.balanceSheet("2026-03-31");
        assertEquals(118_000L, sheet.totalAssetsCents);
        assertEquals(3_000L, sheet.totalLiabilitiesCents);
        assertEquals(115_000L, sheet.totalEquityCents);
        assertEquals(15_000L, sheet.currentEarningsCents);
        assertTrue(sheet.balanced);
    }

    @Test
    void csv_export_and_import_round_trip_journals_and_chart_of_accounts() {
        billing_accounting source = billing_accounting.inMemory();
        source.recordGeneralJournalEntry(
                "journal.manual",
                "JE-CSV-1",
                "CSV roundtrip journal 1",
                List.of(
                        new billing_accounting.JournalLineRec(billing_accounting.ACCT_OPERATING_BANK, "", "", 10_000L, 0L, "Cash"),
                        new billing_accounting.JournalLineRec(billing_accounting.ACCT_OWNER_EQUITY, "", "", 0L, 10_000L, "Equity")
                ),
                "2026-03-01"
        );
        source.recordGeneralJournalEntry(
                "journal.manual",
                "JE-CSV-2",
                "CSV roundtrip journal 2",
                List.of(
                        new billing_accounting.JournalLineRec("expense:software", "", "", 2_500L, 0L, "SaaS"),
                        new billing_accounting.JournalLineRec(billing_accounting.ACCT_OPERATING_BANK, "", "", 0L, 2_500L, "Cash")
                ),
                "2026-03-02"
        );

        String journalsCsv = source.exportJournalsCsv("2026-03-01", "2026-03-31");
        assertTrue(journalsCsv.contains("journal_uuid,posted_at,reference_type"));

        billing_accounting target = billing_accounting.inMemory();
        billing_accounting.DataImportResultRec journalImport = target.importJournalsCsv(journalsCsv, true);
        assertEquals(4, journalImport.rowsImported);
        assertEquals(2, target.listJournals().size());
        assertTrue(target.trialBalance("2026-03-31").balanced);

        String coaCsv = source.exportChartOfAccountsCsv();
        assertTrue(coaCsv.contains("account_code,account_name,account_type"));
        billing_accounting.DataImportResultRec coaImport = target.importChartOfAccountsCsv(coaCsv);
        assertTrue(coaImport.rowsImported > 0);
        assertNotNull(target.getAccount("liability:accounts_payable"));
    }

    @Test
    void quickbooks_iif_export_and_import_round_trip_with_duplicate_skip() {
        billing_accounting source = billing_accounting.inMemory();
        source.recordGeneralJournalEntry(
                "journal.manual",
                "QB-1",
                "QuickBooks export 1",
                List.of(
                        new billing_accounting.JournalLineRec(billing_accounting.ACCT_OPERATING_BANK, "", "", 50_000L, 0L, "Cash"),
                        new billing_accounting.JournalLineRec(billing_accounting.ACCT_OWNER_EQUITY, "", "", 0L, 50_000L, "Equity")
                ),
                "2026-03-03"
        );
        source.recordGeneralJournalEntry(
                "journal.manual",
                "QB-2",
                "QuickBooks export 2",
                List.of(
                        new billing_accounting.JournalLineRec("expense:insurance", "", "", 4_000L, 0L, "Insurance"),
                        new billing_accounting.JournalLineRec(billing_accounting.ACCT_OPERATING_BANK, "", "", 0L, 4_000L, "Cash")
                ),
                "2026-03-04"
        );

        String iif = source.exportQuickBooksIif("2026-03-01", "2026-03-31");
        assertTrue(iif.contains("!TRNS\tTRNSID\tTRNSTYPE"));
        assertTrue(iif.contains("ENDTRNS"));

        billing_accounting target = billing_accounting.inMemory();
        billing_accounting.DataImportResultRec first = target.importQuickBooksIif(iif, true);
        assertEquals(4, first.rowsImported);
        assertEquals(2, target.listJournals().size());
        assertTrue(target.trialBalance("2026-03-31").balanced);

        billing_accounting.DataImportResultRec second = target.importQuickBooksIif(iif, true);
        assertTrue(second.rowsSkipped > 0);
        assertEquals(2, target.listJournals().size());
    }

    @Test
    void financial_statement_csv_exports_include_expected_sections() {
        billing_accounting engine = billing_accounting.inMemory();
        engine.recordGeneralJournalEntry(
                "journal.manual",
                "CSV-STMT-1",
                "Seed statement data",
                List.of(
                        new billing_accounting.JournalLineRec(billing_accounting.ACCT_OPERATING_BANK, "", "", 20_000L, 0L, "Cash"),
                        new billing_accounting.JournalLineRec(billing_accounting.ACCT_OWNER_EQUITY, "", "", 0L, 20_000L, "Equity")
                ),
                "2026-03-01"
        );
        engine.recordGeneralJournalEntry(
                "journal.manual",
                "CSV-STMT-2",
                "Expense entry",
                List.of(
                        new billing_accounting.JournalLineRec("expense:travel", "", "", 1_500L, 0L, "Travel"),
                        new billing_accounting.JournalLineRec(billing_accounting.ACCT_OPERATING_BANK, "", "", 0L, 1_500L, "Cash")
                ),
                "2026-03-05"
        );

        String trialCsv = engine.exportTrialBalanceCsv("2026-03-31");
        String incomeCsv = engine.exportIncomeStatementCsv("2026-03-01", "2026-03-31");
        String balanceCsv = engine.exportBalanceSheetCsv("2026-03-31");

        assertTrue(trialCsv.contains("account_code,account_name,account_type"));
        assertTrue(incomeCsv.contains("section,account_code,account_name"));
        assertTrue(incomeCsv.contains("NET_INCOME"));
        assertTrue(balanceCsv.contains("section,account_code,account_name"));
        assertTrue(balanceCsv.contains("Assets = Liabilities + Equity"));
    }
}

