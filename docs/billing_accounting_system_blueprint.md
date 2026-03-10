# Comprehensive Billing and Accounting System (Texas + Clio-Compatible)

As of March 10, 2026.

This document defines a practical, implementable billing/accounting system for this codebase, with Texas trust-account constraints and Clio interoperability.

Not legal advice. Validate final workflows with Texas ethics counsel and your malpractice carrier before production rollout.

## 1) Texas IOLTA and Trust-Accounting Baseline

### Core requirements to encode in software

| Requirement | Primary source | System control |
| --- | --- | --- |
| Client/third-party funds must be kept separate from lawyer property in a trust account. | Texas Disciplinary Rules of Professional Conduct, Rule 1.15(a) | No posting path allows client funds into operating bank first; trust transactions are separate journal flows. |
| Keep complete trust-account records for at least 5 years after representation ends. | TDRPC Rule 1.15(a)(2) | Immutable journals + event log + exportable reconciliation archives retained with retention policy >= 5 years. |
| Promptly notify and deliver funds/property the client/third person is entitled to; provide accounting on request. | TDRPC Rule 1.15(c) | Matter ledger with statement export and per-transaction audit history. |
| Disputed funds stay segregated in trust until dispute resolved. | TDRPC Rule 1.15(d) | Dispute hold flag prevents disbursement/transfer for held amounts. |
| Nominal or short-term client funds go to an IOLTA account at an eligible institution. | State Bar Rules Article XI, Section 5; TAJF Rules 4/6/7 | Matter onboarding requires trust classification and institution metadata; policy checks require eligible-bank profile. |
| Account notices: opening/changing/closing IOLTA accounts and annual verification requirements. | TAJF Rules 5, 5A, 5B; TAJF IOLTA Compliance guidance | Compliance checklist tasking + reminders + evidence storage (submitted forms, timestamps, confirmations). |
| Interest remittance/reporting by institution; allowable bank fees from interest only. | TAJF Rule 8 | Reconciliation dashboard tracks posted interest/charges and flags noncompliant fee patterns. |

### Operational controls to enforce

1. Matter-level trust subledger cannot go negative.
2. Trust-to-operating transfer can only occur against an issued/partially paid invoice.
3. Three-way reconciliation must balance:
   - Bank statement ending trust balance
   - Book trust-bank GL balance
   - Sum of matter trust subledgers and trust-liability control balance
4. Daily exception queue:
   - Potential commingling indicators
   - Negative trust attempt
   - Unmatched transfer/payment pair
   - Reconciliation out-of-balance

## 2) Feature Ideas from Major Law-Practice Systems

The following product patterns should be considered baseline parity targets for competitive usability:

1. Legal-billing workflows:
   - Time and expense capture, rate cards, batch billing, split billing, LEDES/UTBMS output.
   - Seen across Clio/MyCase/PracticePanther feature sets.
2. Trust-accounting safety:
   - Matter trust ledgers, overdraft prevention, three-way reconciliation workflows, trust-to-invoice application.
   - Prominent in Clio trust accounting and Rocket Matter trust accounting messaging.
3. Online payments:
   - Card/ACH, invoice links, payment plans, autopay options, and clear fee-handling.
   - Present in Clio/MyCase billing/payment pages.
4. Internal controls:
   - Role-based permissions, approval chains (write-off, trust disbursement), full audit trail.
5. Clio interoperability:
   - Preserve Clio object IDs (activity, expense, bill, payment/trust transaction) for idempotent sync and conflict resolution.

## 3) Patterns from Mature Open-Source Billing/Accounting Projects

| Project | Borrowed pattern |
| --- | --- |
| ERPNext | Double-entry discipline, explicit GL objects, bank reconciliation tooling, immutable-ledger posture. |
| Odoo Accounting | Reconciliation models/automation, receivable follow-up workflows, structured accounting app boundaries. |
| Invoice Ninja | Strong recurring invoice/expense flows and client-payment portal ergonomics. |
| Kill Bill | Event-driven billing architecture, plugin-oriented integration boundaries, robust auditability. |

## 4) Constructed System Architecture for This Repo

### Implemented core engine

Added backend core:

- `src/main/java/net/familylawandprobate/controversies/billing_accounting.java`
- `src/main/java/net/familylawandprobate/controversies/billing_output_documents.java`
- `src/main/java/net/familylawandprobate/controversies/integrations/clio/ClioBillingMapper.java`
- `src/test/java/net/familylawandprobate/controversies/billing_accounting_test.java`
- `src/test/java/net/familylawandprobate/controversies/billing_output_documents_test.java`

### Domain model (current engine)

1. Time entries
2. Expense entries
3. Invoices and invoice lines
4. Trust transactions
5. Payments
6. Journal entries (double-entry lines)
7. Trust reconciliation snapshots
8. Compliance snapshot (violations + reconciliation state)

### Clio-compatible data strategy

Every financial object now carries:

1. `source` (`local`, `clio`, etc.)
2. `sourceId` (external object ID)

This enables deterministic upsert logic and avoids duplicate imports.

### Core financial posting rules (engine)

1. Invoice finalization:
   - Dr `asset:accounts_receivable`
   - Cr `revenue:legal_fees`
   - Cr `liability:sales_tax_payable` (if tax)
2. Trust deposit:
   - Dr `asset:trust_bank`
   - Cr `liability:client_trust`
3. Trust applied to invoice:
   - Dr `liability:client_trust`
   - Cr `asset:trust_bank`
   - Dr `asset:operating_bank`
   - Cr `asset:accounts_receivable`
4. Operating payment:
   - Dr `asset:operating_bank`
   - Cr `asset:accounts_receivable`

## 4A) Highly Customizable Billing Documents (Implemented)

This repo now supports tenant-level customization for:

1. `invoice`
2. `trust_request`
3. `statement_of_account`

Customization modes:

1. Inline templates (stored per tenant) with token directives.
2. Mapped form templates (`docx/doc/rtf/odt/txt/pdf`) from `form_templates`.

Template directives supported (via `document_assembler`):

1. `{{token.name}}`
2. `{{#if token.name}}...{{/if}}`
3. `{{#each token.list}}...{{item.field}}...{{/each}}`
4. `{{format.date token.name "MMMM d, yyyy"}}`

Billing renderer auto-enables advanced template directives for these outputs so custom sections and repeat blocks work without extra tenant toggles.

## 5) Clio Integration Contract (Implemented)

### Idempotent import methods

1. `importOrUpdateClioTimeEntry(...)`
2. `importOrUpdateClioExpenseEntry(...)`
3. `importOrUpdateClioInvoiceSummary(...)`
4. `importClioTrustDeposit(...)`
5. `importClioOperatingPayment(...)`
6. `importClioTrustApplication(...)`

### Invoice linking

1. `linkInvoiceToClioBill(localInvoiceUuid, clioBillId)` for local-origin invoices later synchronized to Clio.

### Mapper utility

`ClioBillingMapper` parses variable Clio payload shapes and normalizes them into engine imports:

1. `activityFromJson(...)` + `upsertTimeEntryFromActivity(...)`
2. `expenseFromJson(...)` + `upsertExpenseFromExpense(...)`
3. `billFromJson(...)` + `upsertInvoiceFromBill(...)`
4. `trustTransactionFromJson(...)` + `importTrustTransaction(...)`

## 6) API/UI Buildout Plan (Next)

1. Add storage-backed service layer (tenant/matter scoped, file-backed like existing modules).
2. Add API endpoints for:
   - Time/expense CRUD
   - Invoice lifecycle
   - Trust deposit/apply/refund
   - Reconciliation snapshots and exception queue
3. Add UI pages:
   - `billing.jsp`
   - `invoices.jsp`
   - `trust_accounting.jsp`
   - `reconciliation.jsp`
4. Add approval/permissions:
   - trust disbursement approval
   - write-off approval
   - void/refund approval
5. Add Clio sync scheduler stages for billing objects after matter mapping.

## 7) Testing and Audit Baseline

Current automated tests validate:

1. Trust-funded invoice flow and three-way reconciliation balance.
2. Trust overdraft prevention.
3. Clio idempotent import/link behavior and external-ID mapping.

Additional test layers to add:

1. Multi-matter reconciliation stress tests.
2. Concurrent import/idempotency race tests.
3. Reversal/void/write-off accounting entries.
4. Permission and approval gate tests.

## Sources

### Texas rules and compliance

1. [Texas Disciplinary Rules of Professional Conduct (Rule 1.15)](https://www.txcourts.gov/media/1457495/texas-disciplinary-rules-professional-conduct.pdf)
2. [Texas Access to Justice Foundation - Governing Rules hub](https://www.tajf.org/governing-rules/)
3. [Rules Governing the Operation of the Texas Access to Justice Foundation (current PDF)](https://www.tajf.org/wp-content/uploads/2024/07/Rules-Governing-the-Operation-of-the-TAJF-CURRENT.pdf)
4. [State Bar Rules Article XI (IOLTA)](https://www.tajf.org/wp-content/uploads/2024/07/ARTICLE-XI-CURRENT.pdf)
5. [TAJF IOLTA Compliance page](https://www.tajf.org/iolta-compliance/)

### Law practice management feature references

1. [Clio legal billing software](https://www.clio.com/features/legal-billing-software/)
2. [Clio trust accounting software](https://www.clio.com/features/trust-accounting-software/)
3. [MyCase legal billing software](https://www.mycase.com/legal-billing-software/)
4. [PracticePanther legal trust accounting](https://www.practicepanther.com/legal-software/trust-accounting-software/)
5. [Rocket Matter trust accounting](https://www.rocketmatter.com/features/trust-accounting-software/)

### Mature open-source accounting/billing references

1. [ERPNext Chart of Accounts docs](https://docs.frappe.io/erpnext/user/manual/en/chart-of-accounts)
2. [ERPNext Journal Entry docs](https://docs.frappe.io/erpnext/user/manual/en/journal-entry)
3. [ERPNext Bank Reconciliation docs](https://docs.frappe.io/erpnext/user/manual/en/bank-reconciliation-statement)
4. [Odoo Accounting reconciliation docs](https://www.odoo.com/documentation/master/applications/finance/accounting/bank/reconciliation.html)
5. [Invoice Ninja recurring invoices](https://invoiceninja.github.io/en/invoices/recurring-invoices/)
6. [Kill Bill documentation home](https://docs.killbill.io/latest/home)
