# Law Practice Management Feature Benchmark and Component Plan

As of March 10, 2026.

## 1) Objective

Plan the next components/features for Controversies by evaluating what major law practice management products currently advertise, then prioritizing gaps with the highest business and product impact.

## 2) Current Product Baseline (Controversies)

Based on this repo's implementation and docs:

1. Strong today:
   - Case/matter management (`matters`, `cases.jsp`, `case_fields.jsp`)
   - Tasking, assignment, and custom fields (`tasks.jsp`, `task_attributes.jsp`)
   - Document management, versioning, and form assembly (`documents.jsp`, `forms.jsp`, `template_library.jsp`)
   - Omnichannel communications (SMS/email/Graph + ticketing) (`omnichannel.jsp`, `omnichannel_tickets`)
   - BPM automation and review queues (`business_processes.jsp`, `business_process_manager`)
   - Billing/trust core engine + UI (`billing_accounting`, `billing.jsp`)
   - Deadline rules calculator and Texas-law tooling (`class_dealine_calculator`, `texas_law.jsp`)
2. Partial today:
   - E-sign workflow: signature input + signature notice flow, but not full provider lifecycle orchestration (`forms.jsp`, `send_for_signature` BPM action)
   - Integrations: strong Clio/O365 footprint, limited broader marketplace coverage
3. Missing or weak today:
   - Dedicated lead intake/CRM pipeline
   - Client-facing portal/mobile experience
   - Direct online payment processor integration (card/ACH rails)
   - Billing/trust API surface parity with the UI/runtime engine
   - Firm-level KPI dashboards and advanced analytics
   - Matter-aware legal AI assistant

## 3) Market Evaluation: Advertised Components

The components below are repeatedly advertised across major products (Clio, MyCase, PracticePanther, Smokeball, Filevine, Rocket Matter):

1. Client intake and CRM pipelines
   - Online intake forms, lead stages, consultation booking, automated follow-up, referral/marketing attribution.
2. Secure client portals
   - Client messaging, document exchange, case updates, invoice visibility, and online payment inside portal.
3. Online payments + trust-aware accounting
   - Card/ACH/eCheck, payment links, payment plans/recurring payments, trust/operating separation, three-way reconciliation messaging.
4. Workflow automation
   - Triggered tasks, templates, status-driven automations, auto-reminders, and no-code process controls.
5. E-signature as a first-class feature
   - Native or deeply integrated e-sign workflows with audit trails and reusable templates.
6. Rules-based calendaring/deadline management
   - Court rulesets, automated deadline calculation, and Google/Outlook sync.
7. Embedded legal AI
   - Matter/document summarization, search/insights, drafting support, and context-aware recommendations.
8. Financial and operational analytics
   - Reporting dashboards for collections, cash flow, trust, AR aging, and firm performance.
9. Mobile-first operations
   - Strong attorney and client mobile access for matter updates, communication, and billing.
10. Integration ecosystems
   - Accounting integrations (e.g., QuickBooks), payment rails, and broader app ecosystems.

## 4) Gap/Priority Scoring

Scoring model:

1. Market pressure (1-5): how consistently competitors advertise it.
2. Revenue leverage (1-5): expected collections/growth impact.
3. Fit with current architecture (1-5): how naturally this fits current modules.
4. Delivery effort (1-5): 5 = hardest.
5. Priority score = `market + revenue + fit - effort`.

| ID | Component | Market | Revenue | Fit | Effort | Priority |
| --- | --- | --- | --- | --- | --- | --- |
| CMP-01 | Intake + Lead CRM pipeline | 5 | 5 | 4 | 3 | 11 |
| CMP-02 | Payments rail integration (card/ACH) + trust-safe posting | 5 | 5 | 4 | 4 | 10 |
| CMP-03 | Client portal (messages/docs/invoices/payments) | 5 | 4 | 3 | 4 | 8 |
| CMP-04 | Billing/trust API parity + automation endpoints | 4 | 4 | 5 | 3 | 10 |
| CMP-05 | E-sign provider orchestration | 4 | 3 | 4 | 3 | 8 |
| CMP-06 | Rules calendar sync (Google/O365) + court rules bundles | 4 | 3 | 4 | 3 | 8 |
| CMP-07 | Financial analytics cockpit | 4 | 4 | 3 | 3 | 8 |
| CMP-08 | Matter-aware AI assistant | 4 | 3 | 3 | 4 | 6 |
| CMP-09 | Integration marketplace + outbound webhooks | 4 | 3 | 3 | 4 | 6 |

## 5) Recommended New Components (Phased)

### Phase 1 (0-90 days): Revenue and Pipeline Foundation

1. CMP-01 Intake + Lead CRM
   - Add `leads` domain store with statuses (`new`, `qualified`, `consult_scheduled`, `retained`, `closed_lost`).
   - Add public intake form endpoints with configurable fields per tenant.
   - Add conflict precheck before converting lead to matter.
   - Add referral/source tracking and conversion metrics.
2. CMP-02 Payments Rails + Trust-Safe Posting
   - Integrate one processor path first (LawPay preferred for legal-specific trust handling).
   - Add invoice payment links, ACH/card collection, payment plans, and reminders.
   - Enforce fee/chargeback routing to operating account, not trust.
3. CMP-04 Billing/Trust API Parity
   - Expose `billing.*`, `invoices.*`, `payments.*`, `trust.*`, `reconciliation.*` operations.
   - Add idempotency keys for external sync and payment callbacks.
   - Add webhook/event emission for posted payment, failed payment, trust alert, and reconciliation exception.

### Phase 2 (90-180 days): Client Experience and Automation Completion

1. CMP-03 Client Portal
   - Add external client identity layer with 2FA and scoped access.
   - Enable secure messaging, file sharing, task/event visibility, invoices, and online payments.
   - Add portal activity logging to existing audit/event pipeline.
2. CMP-05 E-Sign Provider Orchestration
   - Convert `send_for_signature` from email-notice only to provider-backed lifecycle (`sent`, `viewed`, `signed`, `declined`, `expired`).
   - Persist signed artifacts and certificate/audit metadata into document version history.
3. CMP-06 Rules Calendar Sync
   - Add calendar event store and sync adapters (Google + O365 first).
   - Connect existing deadline engine outputs to event creation and reminder automation.

### Phase 3 (180-270 days): Intelligence and Platform Expansion

1. CMP-07 Financial Analytics Cockpit
   - Dashboards: AR aging, realization, collections velocity, trust exception queue, utilization.
   - Drill-down from dashboard metrics to matter/invoice-level records.
2. CMP-08 Matter-Aware AI Assistant
   - Start with constrained tasks: case timeline summary, document abstract, billing narrative draft, risk checklist.
   - Require citation/backlink to source records for every generated output.
3. CMP-09 Integration Marketplace + Webhooks
   - Publish integration contract and self-service connector model.
   - Add outbound webhook registry with retry, signing secret, and replay protection.

## 6) Component-Level Acceptance Criteria

1. CMP-01 Intake + Lead CRM
   - New lead can be created from a public form and assigned within 60 seconds.
   - Lead-to-matter conversion avoids duplicate contact/matter creation.
   - Pipeline and source attribution reports are available per tenant.
2. CMP-02 Payments + Trust-Safe Posting
   - Clients can pay by card and ACH from invoice links.
   - Processor fees and chargebacks never debit trust ledgers.
   - Payment-plan schedules post automatically and reconcile to ledger entries.
3. CMP-03 Client Portal
   - Client can view matter messages, shared documents, invoices, and due items.
   - Client uploads/messages create auditable records in matter history.
   - Portal sessions enforce 2FA and tenant/user scoping.
4. CMP-04 Billing/Trust API
   - Core billing actions are fully available over `/api/v1/execute`.
   - API and UI produce equivalent ledger outcomes for identical workflows.
5. CMP-05 E-Sign Orchestration
   - Signing status syncs back without manual polling.
   - Signed outputs and signature certificates are versioned and immutable.
6. CMP-06 Calendar Sync
   - Deadline rule calculations can auto-create calendar events.
   - Event edits remain consistent across Controversies and external calendars.
7. CMP-07 Analytics
   - Dashboard values are reproducible from underlying ledger/case data.
   - Users can export monthly KPI snapshots.
8. CMP-08 AI Assistant
   - Every AI response includes source attribution.
   - Sensitive actions stay human-approved before write operations.
9. CMP-09 Marketplace/Webhooks
   - Webhooks are signed, retried, and observable in delivery logs.
   - Connector onboarding documentation supports partner implementation without internal code changes.

## 7) Recommended Immediate Next Steps (Next 2 Sprints)

1. Define data contracts and storage schemas for `leads`, `portal_accounts`, and `payment_transactions`.
2. Implement CMP-04 first slice (billing/trust API parity) to unlock integrations and portal/payment reuse.
3. Implement CMP-01 MVP intake pipeline with tenant-configurable intake forms.
4. Select and prototype payment processor integration with trust-safe posting verification tests.
5. Add KPI instrumentation from day one (lead conversion, time-to-payment, collection rate, trust exceptions).

## 8) Sources (Advertised Feature References)

1. Clio Features: https://www.clio.com/features/
2. Clio Online Intake Forms: https://www.clio.com/features/online-intake-forms/
3. Clio Client Portal: https://www.clio.com/features/legal-client-portal-software/
4. Clio Legal Workflow Automation: https://www.clio.com/features/legal-workflow-automation-software/
5. Clio Trust Account Management: https://www.clio.com/features/trust-account-management-software/
6. Clio Mobile App: https://www.clio.com/features/mobile-app/
7. Clio Work (AI): https://www.clio.com/work/
8. MyCase Features: https://www.mycase.com/features/
9. MyCase Practice Management: https://www.mycase.com/legal-practice-management-software/
10. MyCase Client Portal: https://www.mycase.com/features/client-portal/
11. MyCase Workflow Automation: https://www.mycase.com/features/workflow-automation/
12. MyCase eSignature: https://www.mycase.com/features/e-signature/
13. MyCase Legal AI: https://www.mycase.com/products/legal-ai-software/
14. PracticePanther Practice Management: https://www.practicepanther.com/practice-management-software/
15. PracticePanther Trust Accounting: https://www.practicepanther.com/trust-accounting-software/
16. Smokeball Features: https://www.smokeball.com/features
17. Smokeball Legal Billing: https://www.smokeball.com/feature/legal-billing-software/
18. Smokeball Client Portal: https://www.smokeball.com/client-portal-software/
19. Filevine Legal Workflow Software: https://www.filevine.com/platform/case-management-software/legal-workflow-software/
20. Filevine Intake and Lead Tracking: https://www.filevine.com/platform/intake-lead-management-and-marketing/intake-and-lead-tracking/
21. Filevine Pricing (feature packaging): https://www.filevine.com/pricing/
22. Rocket Matter Practice Management: https://www.rocketmatter.com/law-practice-management-software
23. Rocket Matter Trust Accounting: https://www.rocketmatter.com/trust-accounting-software
24. Rocket Matter Online Payments: https://www.rocketmatter.com/online-payments
