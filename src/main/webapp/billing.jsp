<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" isELIgnored="true" %>

<%@ page import="java.math.BigDecimal" %>
<%@ page import="java.math.RoundingMode" %>
<%@ page import="java.net.URLEncoder" %>
<%@ page import="java.nio.charset.StandardCharsets" %>
<%@ page import="java.time.Instant" %>
<%@ page import="java.time.LocalDate" %>
<%@ page import="java.time.format.DateTimeParseException" %>
<%@ page import="java.util.ArrayList" %>
<%@ page import="java.util.Comparator" %>
<%@ page import="java.util.LinkedHashMap" %>
<%@ page import="java.util.List" %>
<%@ page import="java.util.Locale" %>
<%@ page import="java.util.Map" %>

<%@ page import="net.familylawandprobate.controversies.billing_accounting" %>
<%@ page import="net.familylawandprobate.controversies.billing_output_documents" %>
<%@ page import="net.familylawandprobate.controversies.billing_runtime_registry" %>
<%@ page import="net.familylawandprobate.controversies.form_templates" %>
<%@ page import="net.familylawandprobate.controversies.matters" %>
<%@ page import="net.familylawandprobate.controversies.users_roles" %>

<%@ include file="security.jspf" %>
<%
  if (!require_login()) return;
%>

<%!
  private static final String S_TENANT_UUID = "tenant.uuid";
  private static final String S_USER_UUID = "user.uuid";
  private static final String CSRF_SESSION_KEY = "CSRF_TOKEN";

  private static String safe(String s) { return s == null ? "" : s; }

  private static String esc(String s) {
    return safe(s).replace("&", "&amp;")
                  .replace("<", "&lt;")
                  .replace(">", "&gt;")
                  .replace("\"", "&quot;")
                  .replace("'", "&#39;");
  }

  private static String enc(String s) {
    return URLEncoder.encode(safe(s), StandardCharsets.UTF_8);
  }

  private static int parseIntSafe(String raw, int fallback) {
    try { return Integer.parseInt(safe(raw).trim()); } catch (Exception ignored) { return fallback; }
  }

  private static String csrfForRender(jakarta.servlet.http.HttpServletRequest req) {
    Object a = req.getAttribute("csrfToken");
    if (a instanceof String) {
      String s = (String) a;
      if (s != null && !s.trim().isEmpty()) return s;
    }
    try {
      jakarta.servlet.http.HttpSession sess = req.getSession(false);
      if (sess != null) {
        Object t = sess.getAttribute(CSRF_SESSION_KEY);
        if (t instanceof String) {
          String cs = (String) t;
          if (cs != null && !cs.trim().isEmpty()) return cs;
        }
      }
    } catch (Exception ignored) {}
    return "";
  }

  private static long dollarsToCents(String raw) {
    String v = safe(raw).trim();
    if (v.isBlank()) return 0L;
    v = v.replace("$", "").replace(",", "").trim();
    try {
      BigDecimal bd = new BigDecimal(v);
      return bd.multiply(BigDecimal.valueOf(100L)).setScale(0, RoundingMode.HALF_UP).longValueExact();
    } catch (Exception ignored) {
      return 0L;
    }
  }

  private static String centsToText(long cents) {
    long abs = Math.abs(cents);
    long whole = abs / 100L;
    long rem = abs % 100L;
    String sign = cents < 0L ? "-" : "";
    return sign + whole + "." + String.format(Locale.ROOT, "%02d", rem);
  }

  private static String moneyCell(long cents) {
    String s = centsToText(cents);
    if (cents < 0L) return "(" + s.substring(1) + ")";
    return s;
  }

  private static boolean boolLike(String raw) {
    String v = safe(raw).trim().toLowerCase(Locale.ROOT);
    return "1".equals(v) || "true".equals(v) || "yes".equals(v) || "on".equals(v) || "y".equals(v);
  }

  private static LinkedHashMap<String, String> parseCustomTokens(String text) {
    LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
    String raw = safe(text);
    if (raw.isBlank()) return out;
    String[] lines = raw.replace("\r\n", "\n").replace('\r', '\n').split("\n");
    for (int i = 0; i < lines.length; i++) {
      String line = safe(lines[i]).trim();
      if (line.isBlank()) continue;
      if (line.startsWith("#")) continue;
      int eq = line.indexOf('=');
      if (eq < 0) eq = line.indexOf(':');
      if (eq <= 0) continue;
      String key = safe(line.substring(0, eq)).trim();
      String value = safe(line.substring(eq + 1)).trim();
      if (key.isBlank()) continue;
      out.put(key, value);
    }
    return out;
  }

  private static String asDateOrBlank(String raw) {
    String v = safe(raw).trim();
    if (v.isBlank()) return "";
    try {
      return LocalDate.parse(v).toString();
    } catch (DateTimeParseException ignored) {
      return "";
    }
  }

  private static String cleanFileToken(String in) {
    String v = safe(in).trim().replaceAll("[^A-Za-z0-9._-]", "_");
    if (v.isBlank()) v = "document";
    if (v.length() > 80) v = v.substring(0, 80);
    return v;
  }

  private static String contentDispositionValue(String fileName) {
    String v = cleanFileToken(fileName);
    return "attachment; filename=\"" + v + "\"";
  }
%>

<%
  String ctx = safe(request.getContextPath());
  request.setAttribute("activeNav", "/billing.jsp");

  String tenantUuid = safe((String) session.getAttribute(S_TENANT_UUID)).trim();
  String userUuid = safe((String) session.getAttribute(S_USER_UUID)).trim();
  String userEmail = safe((String) session.getAttribute(users_roles.S_USER_EMAIL)).trim();
  if (tenantUuid.isBlank()) {
    response.sendRedirect(ctx + "/tenant_login.jsp");
    return;
  }

  String csrfToken = csrfForRender(request);
  String action = "POST".equalsIgnoreCase(request.getMethod()) ? safe(request.getParameter("action")).trim() : "";
  String message = "";
  String error = "";

  matters matterStore = matters.defaultStore();
  form_templates templateStore = form_templates.defaultStore();
  billing_output_documents outputStore = billing_output_documents.defaultStore();
  billing_accounting ledger = billing_runtime_registry.tenantLedger(tenantUuid);

  try { matterStore.ensure(tenantUuid); } catch (Exception ex) { if (error.isBlank()) error = "Unable to load matters: " + safe(ex.getMessage()); }
  try { templateStore.ensure(tenantUuid); } catch (Exception ex) { if (error.isBlank()) error = "Unable to load form templates: " + safe(ex.getMessage()); }
  try { outputStore.ensure(tenantUuid); } catch (Exception ex) { if (error.isBlank()) error = "Unable to load billing output templates: " + safe(ex.getMessage()); }

  List<matters.MatterRec> allMatters = new ArrayList<matters.MatterRec>();
  List<matters.MatterRec> activeMatters = new ArrayList<matters.MatterRec>();
  LinkedHashMap<String, String> matterLabelByUuid = new LinkedHashMap<String, String>();
  try {
    allMatters = matterStore.listAll(tenantUuid);
    for (int i = 0; i < allMatters.size(); i++) {
      matters.MatterRec m = allMatters.get(i);
      if (m == null) continue;
      String id = safe(m.uuid).trim();
      if (id.isBlank()) continue;
      String label = safe(m.label).trim();
      if (label.isBlank()) label = id;
      matterLabelByUuid.put(id, label);
      if (!m.trashed) activeMatters.add(m);
    }
  } catch (Exception ex) {
    if (error.isBlank()) error = "Unable to list matters: " + safe(ex.getMessage());
  }

  String selectedMatterUuid = safe(request.getParameter("matter_uuid")).trim();
  if (selectedMatterUuid.isBlank() && !activeMatters.isEmpty()) selectedMatterUuid = safe(activeMatters.get(0).uuid).trim();
  if (selectedMatterUuid.isBlank() && !allMatters.isEmpty()) selectedMatterUuid = safe(allMatters.get(0).uuid).trim();
  String selectedInvoiceUuid = safe(request.getParameter("invoice_uuid")).trim();
  String selectedTemplateDocType = safe(request.getParameter("template_doc_type")).trim();
  if (selectedTemplateDocType.isBlank()) selectedTemplateDocType = billing_output_documents.DOC_INVOICE;

  String previewTitle = "";
  String previewSource = "";
  String previewContentType = "";
  String previewExtension = "";
  String previewBody = "";
  long previewBytes = 0L;

  if ("POST".equalsIgnoreCase(request.getMethod()) && error.isBlank()) {
    try {
      String actor = userUuid.isBlank() ? userEmail : userUuid;
      if ("create_time_entry".equals(action)) {
        if (selectedMatterUuid.isBlank()) throw new IllegalArgumentException("Select a matter first.");
        int minutes = Math.max(0, parseIntSafe(request.getParameter("time_minutes"), 0));
        long rateCents = Math.max(0L, dollarsToCents(request.getParameter("time_rate")));
        String activityCode = safe(request.getParameter("time_activity_code")).trim();
        String note = safe(request.getParameter("time_note")).trim();
        String workedAt = safe(request.getParameter("time_worked_at")).trim();
        boolean billable = !boolLike(request.getParameter("time_nonbillable"));
        ledger.createTimeEntry(
                selectedMatterUuid,
                actor,
                activityCode,
                note,
                minutes,
                rateCents,
                "USD",
                billable,
                workedAt
        );
        message = "Time entry created.";
      } else if ("create_expense_entry".equals(action)) {
        if (selectedMatterUuid.isBlank()) throw new IllegalArgumentException("Select a matter first.");
        long amountCents = Math.max(0L, dollarsToCents(request.getParameter("expense_amount")));
        long taxCents = Math.max(0L, dollarsToCents(request.getParameter("expense_tax")));
        String description = safe(request.getParameter("expense_description")).trim();
        String incurredAt = safe(request.getParameter("expense_incurred_at")).trim();
        String expenseCode = safe(request.getParameter("expense_utbms_code")).trim();
        boolean billable = !boolLike(request.getParameter("expense_nonbillable"));
        ledger.createExpenseEntry(
                selectedMatterUuid,
                description,
                amountCents,
                taxCents,
                "USD",
                billable,
                incurredAt,
                expenseCode
        );
        message = "Expense entry created.";
      } else if ("set_managed_activity_mode".equals(action)) {
        ledger.setRequireManagedActivities(boolLike(request.getParameter("require_managed_activities")));
        message = "Managed-activity mode updated.";
      } else if ("upsert_billing_activity".equals(action)) {
        String code = safe(request.getParameter("activity_code")).trim();
        if (code.isBlank()) throw new IllegalArgumentException("Activity code is required.");
        long defaultRateCents = Math.max(0L, dollarsToCents(request.getParameter("activity_default_rate")));
        ledger.upsertBillingActivity(
                code,
                safe(request.getParameter("activity_label")).trim(),
                defaultRateCents,
                !boolLike(request.getParameter("activity_default_nonbillable")),
                safe(request.getParameter("activity_utbms_task_code")).trim(),
                safe(request.getParameter("activity_utbms_activity_code")).trim(),
                safe(request.getParameter("activity_utbms_expense_code")).trim(),
                !boolLike(request.getParameter("activity_inactive"))
        );
        message = "Managed billing activity saved.";
      } else if ("deactivate_billing_activity".equals(action)) {
        String code = safe(request.getParameter("activity_code")).trim();
        if (code.isBlank()) throw new IllegalArgumentException("Activity code is required.");
        ledger.deactivateBillingActivity(code);
        message = "Managed billing activity deactivated.";
      } else if ("draft_finalize_invoice".equals(action)) {
        if (selectedMatterUuid.isBlank()) throw new IllegalArgumentException("Select a matter first.");
        String issuedAt = safe(request.getParameter("invoice_issue_date")).trim();
        String dueAt = safe(request.getParameter("invoice_due_date")).trim();
        billing_accounting.InvoiceRec draft = ledger.draftInvoiceForMatter(selectedMatterUuid, issuedAt, dueAt, "USD");
        billing_accounting.InvoiceRec finalized = ledger.finalizeInvoice(draft.uuid);
        selectedInvoiceUuid = finalized.uuid;
        message = "Invoice created and finalized.";
      } else if ("finalize_invoice".equals(action)) {
        if (selectedInvoiceUuid.isBlank()) throw new IllegalArgumentException("Select an invoice first.");
        billing_accounting.InvoiceRec finalized = ledger.finalizeInvoice(selectedInvoiceUuid);
        selectedInvoiceUuid = finalized.uuid;
        message = "Invoice finalized.";
      } else if ("trust_deposit".equals(action)) {
        if (selectedMatterUuid.isBlank()) throw new IllegalArgumentException("Select a matter first.");
        long amountCents = Math.max(0L, dollarsToCents(request.getParameter("trust_deposit_amount")));
        String postedAt = safe(request.getParameter("trust_deposit_posted_at")).trim();
        String reference = safe(request.getParameter("trust_deposit_reference")).trim();
        ledger.recordTrustDeposit(selectedMatterUuid, amountCents, "USD", postedAt, reference);
        message = "Trust deposit posted.";
      } else if ("trust_apply".equals(action)) {
        if (selectedInvoiceUuid.isBlank()) throw new IllegalArgumentException("Select an invoice first.");
        long amountCents = Math.max(0L, dollarsToCents(request.getParameter("trust_apply_amount")));
        String postedAt = safe(request.getParameter("trust_apply_posted_at")).trim();
        String reference = safe(request.getParameter("trust_apply_reference")).trim();
        ledger.applyTrustToInvoice(selectedInvoiceUuid, amountCents, postedAt, reference);
        message = "Trust applied to invoice.";
      } else if ("operating_payment".equals(action)) {
        if (selectedInvoiceUuid.isBlank()) throw new IllegalArgumentException("Select an invoice first.");
        long amountCents = Math.max(0L, dollarsToCents(request.getParameter("operating_payment_amount")));
        String postedAt = safe(request.getParameter("operating_payment_posted_at")).trim();
        String reference = safe(request.getParameter("operating_payment_reference")).trim();
        ledger.recordOperatingPayment(selectedInvoiceUuid, amountCents, postedAt, reference);
        message = "Operating payment posted.";
      } else if ("trust_refund".equals(action)) {
        if (selectedMatterUuid.isBlank()) throw new IllegalArgumentException("Select a matter first.");
        long amountCents = Math.max(0L, dollarsToCents(request.getParameter("trust_refund_amount")));
        String postedAt = safe(request.getParameter("trust_refund_posted_at")).trim();
        String reference = safe(request.getParameter("trust_refund_reference")).trim();
        ledger.recordTrustRefund(selectedMatterUuid, amountCents, "USD", postedAt, reference);
        message = "Trust refund posted.";
      } else if ("set_trust_policy".equals(action)) {
        if (selectedMatterUuid.isBlank()) throw new IllegalArgumentException("Select a matter first.");
        long reserveCents = Math.max(0L, dollarsToCents(request.getParameter("policy_minimum_reserve")));
        ledger.upsertMatterTrustPolicy(
                selectedMatterUuid,
                safe(request.getParameter("policy_iolta_account_name")).trim(),
                safe(request.getParameter("policy_iolta_institution_name")).trim(),
                boolLike(request.getParameter("policy_iolta_institution_eligible")),
                safe(request.getParameter("policy_annual_due_date")).trim(),
                safe(request.getParameter("policy_annual_completed_date")).trim(),
                boolLike(request.getParameter("policy_require_approval")),
                reserveCents,
                actor
        );
        message = "Matter trust policy saved.";
      } else if ("place_trust_hold".equals(action)) {
        if (selectedMatterUuid.isBlank()) throw new IllegalArgumentException("Select a matter first.");
        long holdAmountCents = Math.max(0L, dollarsToCents(request.getParameter("hold_amount")));
        String reason = safe(request.getParameter("hold_reason")).trim();
        String placedAt = safe(request.getParameter("hold_placed_at")).trim();
        ledger.placeTrustDisputeHold(selectedMatterUuid, holdAmountCents, reason, actor, placedAt);
        message = "Trust dispute hold placed.";
      } else if ("release_trust_hold".equals(action)) {
        String holdUuid = safe(request.getParameter("hold_uuid")).trim();
        if (holdUuid.isBlank()) throw new IllegalArgumentException("Select a hold to release.");
        String resolution = safe(request.getParameter("hold_resolution")).trim();
        String releasedAt = safe(request.getParameter("hold_released_at")).trim();
        ledger.releaseTrustDisputeHold(holdUuid, actor, resolution, releasedAt);
        message = "Trust dispute hold released.";
      } else if ("request_disbursement_approval".equals(action)) {
        if (selectedMatterUuid.isBlank()) throw new IllegalArgumentException("Select a matter first.");
        String disbursementType = safe(request.getParameter("approval_disbursement_type")).trim();
        String invoiceUuid = safe(request.getParameter("approval_invoice_uuid")).trim();
        long amountCents = Math.max(0L, dollarsToCents(request.getParameter("approval_amount")));
        String reason = safe(request.getParameter("approval_reason")).trim();
        String requestedAt = safe(request.getParameter("approval_requested_at")).trim();
        billing_accounting.TrustDisbursementApprovalRec approval = ledger.requestTrustDisbursementApproval(
                disbursementType,
                selectedMatterUuid,
                invoiceUuid,
                amountCents,
                "USD",
                actor,
                reason,
                requestedAt
        );
        if (!safe(approval.invoiceUuid).isBlank()) selectedInvoiceUuid = safe(approval.invoiceUuid).trim();
        message = "Disbursement approval requested.";
      } else if ("review_disbursement_approval".equals(action)) {
        String approvalUuid = safe(request.getParameter("approval_uuid")).trim();
        if (approvalUuid.isBlank()) throw new IllegalArgumentException("Select an approval first.");
        String decision = safe(request.getParameter("approval_decision")).trim().toLowerCase(Locale.ROOT);
        boolean approved = "approve".equals(decision) || "approved".equals(decision) || "yes".equals(decision);
        String reviewNotes = safe(request.getParameter("approval_review_notes")).trim();
        String reviewedAt = safe(request.getParameter("approval_reviewed_at")).trim();
        ledger.reviewTrustDisbursementApproval(approvalUuid, approved, actor, reviewNotes, reviewedAt);
        message = approved ? "Approval marked approved." : "Approval marked rejected.";
      } else if ("execute_disbursement_approval".equals(action)) {
        String approvalUuid = safe(request.getParameter("approval_uuid")).trim();
        if (approvalUuid.isBlank()) throw new IllegalArgumentException("Select an approval first.");
        String postedAt = safe(request.getParameter("approval_execute_posted_at")).trim();
        String reference = safe(request.getParameter("approval_execute_reference")).trim();
        billing_accounting.TrustDisbursementApprovalRec approval = ledger.getTrustDisbursementApproval(approvalUuid);
        if (approval == null) throw new IllegalArgumentException("approval not found");
        if (billing_accounting.DISBURSEMENT_TRUST_APPLY.equals(safe(approval.disbursementType))) {
          ledger.applyTrustToInvoiceWithApproval(approvalUuid, postedAt, reference);
          if (!safe(approval.invoiceUuid).isBlank()) selectedInvoiceUuid = safe(approval.invoiceUuid).trim();
        } else if (billing_accounting.DISBURSEMENT_TRUST_REFUND.equals(safe(approval.disbursementType))) {
          ledger.recordTrustRefundWithApproval(approvalUuid, postedAt, reference);
        } else {
          throw new IllegalArgumentException("Unsupported approval disbursement type.");
        }
        message = "Approved trust disbursement executed.";
      } else if ("create_payment_plan".equals(action)) {
        if (selectedMatterUuid.isBlank()) throw new IllegalArgumentException("Select a matter first.");
        String invoiceUuid = safe(request.getParameter("plan_invoice_uuid")).trim();
        int installmentCount = Math.max(1, parseIntSafe(request.getParameter("plan_installment_count"), 3));
        int cadenceDays = Math.max(1, parseIntSafe(request.getParameter("plan_cadence_days"), 30));
        String firstDueAt = safe(request.getParameter("plan_first_due_at")).trim();
        boolean autopay = boolLike(request.getParameter("plan_autopay"));
        String planReference = safe(request.getParameter("plan_reference")).trim();
        billing_accounting.PaymentPlanRec plan = ledger.createPaymentPlan(
                invoiceUuid,
                installmentCount,
                cadenceDays,
                firstDueAt,
                autopay,
                planReference
        );
        selectedInvoiceUuid = safe(plan.invoiceUuid).trim();
        message = "Payment plan created.";
      } else if ("post_payment_plan_installment".equals(action)) {
        String planUuid = safe(request.getParameter("plan_uuid")).trim();
        if (planUuid.isBlank()) throw new IllegalArgumentException("Select a payment plan first.");
        String postedAt = safe(request.getParameter("plan_payment_posted_at")).trim();
        String reference = safe(request.getParameter("plan_payment_reference")).trim();
        billing_accounting.PaymentRec payment = ledger.postPaymentPlanInstallment(planUuid, postedAt, reference);
        if (payment != null && !safe(payment.invoiceUuid).isBlank()) selectedInvoiceUuid = safe(payment.invoiceUuid).trim();
        message = "Payment-plan installment posted.";
      } else if ("run_autopay_sweep".equals(action)) {
        String asOfDate = safe(request.getParameter("autopay_as_of_date")).trim();
        String postedAt = safe(request.getParameter("autopay_posted_at")).trim();
        String refPrefix = safe(request.getParameter("autopay_reference_prefix")).trim();
        List<billing_accounting.PaymentRec> posted = ledger.runAutopayDuePlans(asOfDate, postedAt, refPrefix);
        message = "Autopay sweep complete. Payments posted: " + posted.size() + ".";
      } else if ("cancel_payment_plan".equals(action)) {
        String planUuid = safe(request.getParameter("plan_uuid")).trim();
        if (planUuid.isBlank()) throw new IllegalArgumentException("Select a payment plan first.");
        ledger.cancelPaymentPlan(planUuid);
        message = "Payment plan cancelled.";
      } else if ("set_inline_template".equals(action)) {
        String docType = safe(request.getParameter("template_doc_type")).trim();
        String templateBody = safe(request.getParameter("inline_template")).trim();
        outputStore.setInlineTemplate(tenantUuid, docType, templateBody);
        selectedTemplateDocType = docType;
        message = "Inline template updated.";
      } else if ("set_form_template".equals(action)) {
        String docType = safe(request.getParameter("template_doc_type")).trim();
        String templateUuid = safe(request.getParameter("form_template_uuid")).trim();
        outputStore.setFormTemplate(tenantUuid, docType, templateUuid);
        selectedTemplateDocType = docType;
        message = "Form-template mapping updated.";
      } else if ("reset_default_template".equals(action)) {
        String docType = safe(request.getParameter("template_doc_type")).trim();
        outputStore.resetDefaultInlineTemplate(tenantUuid, docType);
        selectedTemplateDocType = docType;
        message = "Inline template reset to default.";
      } else if ("clear_runtime_ledger".equals(action)) {
        billing_runtime_registry.clearTenant(tenantUuid);
        ledger = billing_runtime_registry.tenantLedger(tenantUuid);
        selectedInvoiceUuid = "";
        message = "Runtime ledger cleared for this tenant.";
      } else if ("render_invoice_preview".equals(action) || "render_invoice_download".equals(action)) {
        if (selectedMatterUuid.isBlank()) throw new IllegalArgumentException("Select a matter first.");
        if (selectedInvoiceUuid.isBlank()) throw new IllegalArgumentException("Select an invoice first.");

        LinkedHashMap<String, String> profile = new LinkedHashMap<String, String>();
        profile.put("tenant.name", safe(request.getParameter("profile_tenant_name")).trim());
        profile.put("client.name", safe(request.getParameter("profile_client_name")).trim());
        profile.put("client.address", safe(request.getParameter("profile_client_address")).trim());
        profile.put("matter.label", safe(request.getParameter("profile_matter_label")).trim());
        profile.put("matter.number", safe(request.getParameter("profile_matter_number")).trim());

        LinkedHashMap<String, String> custom = parseCustomTokens(request.getParameter("custom_tokens"));
        billing_output_documents.RenderedDocument rendered = outputStore.renderInvoice(
                tenantUuid,
                ledger,
                selectedMatterUuid,
                selectedInvoiceUuid,
                profile,
                custom
        );

        if ("render_invoice_download".equals(action)) {
          String ext = safe(rendered.assembled.extension).trim();
          if (ext.isBlank()) ext = "txt";
          String fileName = cleanFileToken("invoice_" + selectedInvoiceUuid + "_" + net.familylawandprobate.controversies.app_clock.now().toEpochMilli()) + "." + ext;
          response.setHeader("Cache-Control", "no-store");
          response.setHeader("Content-Disposition", contentDispositionValue(fileName));
          String ct = safe(rendered.assembled.contentType).trim();
          response.setContentType(ct.isBlank() ? "application/octet-stream" : ct);
          response.getOutputStream().write(rendered.assembled.bytes);
          return;
        }

        previewTitle = "Invoice Output Preview";
        previewSource = safe(rendered.templateSource);
        previewContentType = safe(rendered.assembled.contentType);
        previewExtension = safe(rendered.assembled.extension);
        previewBytes = rendered.assembled.bytes == null ? 0L : rendered.assembled.bytes.length;
        previewBody = new String(rendered.assembled.bytes == null ? new byte[0] : rendered.assembled.bytes, StandardCharsets.UTF_8);
      } else if ("render_trust_request_preview".equals(action) || "render_trust_request_download".equals(action)) {
        if (selectedMatterUuid.isBlank()) throw new IllegalArgumentException("Select a matter first.");

        LinkedHashMap<String, String> profile = new LinkedHashMap<String, String>();
        profile.put("tenant.name", safe(request.getParameter("profile_tenant_name")).trim());
        profile.put("client.name", safe(request.getParameter("profile_client_name")).trim());
        profile.put("client.address", safe(request.getParameter("profile_client_address")).trim());
        profile.put("matter.label", safe(request.getParameter("profile_matter_label")).trim());
        profile.put("matter.number", safe(request.getParameter("profile_matter_number")).trim());
        LinkedHashMap<String, String> custom = parseCustomTokens(request.getParameter("custom_tokens"));

        String requestId = safe(request.getParameter("trust_request_id")).trim();
        long amountCents = Math.max(0L, dollarsToCents(request.getParameter("trust_request_amount")));
        String requestedAt = safe(request.getParameter("trust_request_requested_at")).trim();
        String dueAt = asDateOrBlank(request.getParameter("trust_request_due_at"));
        String requestedBy = safe(request.getParameter("trust_request_requested_by")).trim();
        String reasonText = safe(request.getParameter("trust_request_reason")).trim();
        long minimumTarget = Math.max(0L, dollarsToCents(request.getParameter("trust_request_target_balance")));
        String notes = safe(request.getParameter("trust_request_notes")).trim();

        billing_output_documents.TrustRequestInput in = new billing_output_documents.TrustRequestInput(
                selectedMatterUuid,
                requestId,
                amountCents,
                requestedAt,
                dueAt,
                requestedBy,
                reasonText,
                minimumTarget,
                notes
        );
        billing_output_documents.RenderedDocument rendered = outputStore.renderTrustRequest(
                tenantUuid,
                ledger,
                in,
                profile,
                custom
        );

        if ("render_trust_request_download".equals(action)) {
          String ext = safe(rendered.assembled.extension).trim();
          if (ext.isBlank()) ext = "txt";
          String fileName = cleanFileToken("trust_request_" + selectedMatterUuid + "_" + net.familylawandprobate.controversies.app_clock.now().toEpochMilli()) + "." + ext;
          response.setHeader("Cache-Control", "no-store");
          response.setHeader("Content-Disposition", contentDispositionValue(fileName));
          String ct = safe(rendered.assembled.contentType).trim();
          response.setContentType(ct.isBlank() ? "application/octet-stream" : ct);
          response.getOutputStream().write(rendered.assembled.bytes);
          return;
        }

        previewTitle = "Trust Request Output Preview";
        previewSource = safe(rendered.templateSource);
        previewContentType = safe(rendered.assembled.contentType);
        previewExtension = safe(rendered.assembled.extension);
        previewBytes = rendered.assembled.bytes == null ? 0L : rendered.assembled.bytes.length;
        previewBody = new String(rendered.assembled.bytes == null ? new byte[0] : rendered.assembled.bytes, StandardCharsets.UTF_8);
      } else if ("render_statement_preview".equals(action) || "render_statement_download".equals(action)) {
        if (selectedMatterUuid.isBlank()) throw new IllegalArgumentException("Select a matter first.");

        LinkedHashMap<String, String> profile = new LinkedHashMap<String, String>();
        profile.put("tenant.name", safe(request.getParameter("profile_tenant_name")).trim());
        profile.put("client.name", safe(request.getParameter("profile_client_name")).trim());
        profile.put("client.address", safe(request.getParameter("profile_client_address")).trim());
        profile.put("matter.label", safe(request.getParameter("profile_matter_label")).trim());
        profile.put("matter.number", safe(request.getParameter("profile_matter_number")).trim());
        LinkedHashMap<String, String> custom = parseCustomTokens(request.getParameter("custom_tokens"));
        String periodStart = asDateOrBlank(request.getParameter("statement_period_start"));
        String periodEnd = asDateOrBlank(request.getParameter("statement_period_end"));

        billing_output_documents.RenderedDocument rendered = outputStore.renderStatementOfAccount(
                tenantUuid,
                ledger,
                selectedMatterUuid,
                periodStart,
                periodEnd,
                profile,
                custom
        );

        if ("render_statement_download".equals(action)) {
          String ext = safe(rendered.assembled.extension).trim();
          if (ext.isBlank()) ext = "txt";
          String fileName = cleanFileToken("statement_of_account_" + selectedMatterUuid + "_" + net.familylawandprobate.controversies.app_clock.now().toEpochMilli()) + "." + ext;
          response.setHeader("Cache-Control", "no-store");
          response.setHeader("Content-Disposition", contentDispositionValue(fileName));
          String ct = safe(rendered.assembled.contentType).trim();
          response.setContentType(ct.isBlank() ? "application/octet-stream" : ct);
          response.getOutputStream().write(rendered.assembled.bytes);
          return;
        }

        previewTitle = "Statement of Account Output Preview";
        previewSource = safe(rendered.templateSource);
        previewContentType = safe(rendered.assembled.contentType);
        previewExtension = safe(rendered.assembled.extension);
        previewBytes = rendered.assembled.bytes == null ? 0L : rendered.assembled.bytes.length;
        previewBody = new String(rendered.assembled.bytes == null ? new byte[0] : rendered.assembled.bytes, StandardCharsets.UTF_8);
      } else if ("render_ledes_preview".equals(action) || "render_ledes_download".equals(action)) {
        if (selectedMatterUuid.isBlank()) throw new IllegalArgumentException("Select a matter first.");
        if (selectedInvoiceUuid.isBlank()) throw new IllegalArgumentException("Select an invoice first.");

        LinkedHashMap<String, String> profile = new LinkedHashMap<String, String>();
        profile.put("tenant.name", safe(request.getParameter("profile_tenant_name")).trim());
        profile.put("client.name", safe(request.getParameter("profile_client_name")).trim());
        profile.put("client.address", safe(request.getParameter("profile_client_address")).trim());
        profile.put("matter.label", safe(request.getParameter("profile_matter_label")).trim());
        profile.put("matter.number", safe(request.getParameter("profile_matter_number")).trim());
        profile.put("client.id", safe(request.getParameter("ledes_client_id")).trim());
        profile.put("law_firm.id", safe(request.getParameter("ledes_law_firm_id")).trim());
        profile.put("client.matter_id", safe(request.getParameter("ledes_client_matter_id")).trim());
        LinkedHashMap<String, String> custom = parseCustomTokens(request.getParameter("custom_tokens"));

        billing_output_documents.LedesDocument rendered = outputStore.renderLedes1998B(
                tenantUuid,
                ledger,
                selectedMatterUuid,
                selectedInvoiceUuid,
                profile,
                custom
        );
        if ("render_ledes_download".equals(action)) {
          String ext = safe(rendered.extension).trim();
          if (ext.isBlank()) ext = "txt";
          String fileName = cleanFileToken("ledes1998b_" + selectedInvoiceUuid + "_" + net.familylawandprobate.controversies.app_clock.now().toEpochMilli()) + "." + ext;
          response.setHeader("Cache-Control", "no-store");
          response.setHeader("Content-Disposition", contentDispositionValue(fileName));
          String ct = safe(rendered.contentType).trim();
          response.setContentType(ct.isBlank() ? "text/plain; charset=UTF-8" : ct);
          response.getOutputStream().write(rendered.bytes);
          return;
        }
        previewTitle = "LEDES1998B Preview";
        previewSource = safe(rendered.format);
        previewContentType = safe(rendered.contentType);
        previewExtension = safe(rendered.extension);
        previewBytes = rendered.bytes == null ? 0L : rendered.bytes.length;
        previewBody = new String(rendered.bytes == null ? new byte[0] : rendered.bytes, StandardCharsets.UTF_8);
      }
    } catch (Exception ex) {
      error = safe(ex.getMessage());
      if (error.isBlank()) error = ex.getClass().getSimpleName();
    }
  }

  List<billing_accounting.InvoiceRec> matterInvoices = selectedMatterUuid.isBlank()
          ? List.of()
          : ledger.listInvoicesForMatter(selectedMatterUuid);
  if (selectedInvoiceUuid.isBlank() && !matterInvoices.isEmpty()) {
    selectedInvoiceUuid = safe(matterInvoices.get(matterInvoices.size() - 1).uuid).trim();
  }
  billing_accounting.InvoiceRec selectedInvoice = selectedInvoiceUuid.isBlank() ? null : ledger.getInvoice(selectedInvoiceUuid);

  List<billing_accounting.PaymentRec> matterPayments = selectedMatterUuid.isBlank()
          ? List.of()
          : ledger.listPaymentsForMatter(selectedMatterUuid);
  List<billing_accounting.TrustTxnRec> matterTrustTxns = selectedMatterUuid.isBlank()
          ? List.of()
          : ledger.listTrustTransactionsForMatter(selectedMatterUuid);
  long currentTrustBalance = selectedMatterUuid.isBlank() ? 0L : ledger.matterTrustBalance(selectedMatterUuid);
  long heldTrustBalance = selectedMatterUuid.isBlank() ? 0L : ledger.heldTrustAmount(selectedMatterUuid);
  long availableTrustBalance = selectedMatterUuid.isBlank() ? 0L : ledger.availableTrustBalance(selectedMatterUuid);
  billing_accounting.MatterTrustPolicyRec trustPolicy = selectedMatterUuid.isBlank() ? null : ledger.getMatterTrustPolicy(selectedMatterUuid);
  List<billing_accounting.TrustDisputeHoldRec> matterTrustHolds = selectedMatterUuid.isBlank()
          ? List.of()
          : ledger.listTrustDisputeHoldsForMatter(selectedMatterUuid, true);
  List<billing_accounting.TrustDisbursementApprovalRec> matterDisbursementApprovals = selectedMatterUuid.isBlank()
          ? List.of()
          : ledger.listTrustDisbursementApprovalsForMatter(selectedMatterUuid);
  List<billing_accounting.PaymentPlanRec> matterPaymentPlans = selectedMatterUuid.isBlank()
          ? List.of()
          : ledger.listPaymentPlansForMatter(selectedMatterUuid);
  List<billing_accounting.BillingActivityRec> managedActivities = ledger.listBillingActivities(true);
  boolean requireManagedActivities = ledger.isRequireManagedActivities();

  List<billing_accounting.MatterTrustBalanceRec> trustBalances = ledger.listMatterTrustBalances();
  List<billing_output_documents.TemplateConfigRec> templateConfigs = outputStore.listTemplateConfigs(tenantUuid);
  LinkedHashMap<String, billing_output_documents.TemplateConfigRec> cfgByType = new LinkedHashMap<String, billing_output_documents.TemplateConfigRec>();
  for (int i = 0; i < templateConfigs.size(); i++) {
    billing_output_documents.TemplateConfigRec rec = templateConfigs.get(i);
    if (rec == null) continue;
    cfgByType.put(safe(rec.documentType), rec);
  }
  List<form_templates.TemplateRec> availableTemplates = templateStore.list(tenantUuid);
  availableTemplates.sort(Comparator.comparing((form_templates.TemplateRec t) -> safe(t == null ? "" : t.label)));

  billing_accounting.TrustReconciliationRec previewRecon = ledger.trustReconciliation(net.familylawandprobate.controversies.app_clock.now().toString(), 0L);
  billing_accounting.ComplianceSnapshot compliance = ledger.complianceSnapshot(net.familylawandprobate.controversies.app_clock.now().toString(), previewRecon.bookTrustBankBalanceCents);
%>

<jsp:include page="header.jsp" />

<section class="card">
  <div class="section-head">
    <div>
      <h1 style="margin:0;">Billing and Accounting</h1>
      <div class="meta">Time/expense capture, invoicing, trust accounting controls, and customizable billing document output.</div>
    </div>
  </div>
  <% if (!message.isBlank()) { %>
    <div class="notice success"><%= esc(message) %></div>
  <% } %>
  <% if (!error.isBlank()) { %>
    <div class="notice error"><%= esc(error) %></div>
  <% } %>
</section>

<section class="card" style="margin-top:12px;">
  <form method="get" action="<%= ctx %>/billing.jsp" class="form">
    <div class="grid grid-4">
      <label>
        <span>Matter</span>
        <select name="matter_uuid">
          <% if (allMatters.isEmpty()) { %>
            <option value="">No matters available</option>
          <% } else { for (int i = 0; i < allMatters.size(); i++) {
               matters.MatterRec m = allMatters.get(i);
               if (m == null) continue;
               String id = safe(m.uuid);
               String label = safe(m.label);
          %>
            <option value="<%= esc(id) %>" <%= id.equals(selectedMatterUuid) ? "selected" : "" %>>
              <%= esc(label) %><%= m.trashed ? " (archived)" : "" %>
            </option>
          <% }} %>
        </select>
      </label>
      <label>
        <span>Invoice Focus</span>
        <select name="invoice_uuid">
          <option value="">(Auto-select latest)</option>
          <% for (int i = 0; i < matterInvoices.size(); i++) {
               billing_accounting.InvoiceRec inv = matterInvoices.get(i);
               if (inv == null) continue;
               String iid = safe(inv.uuid);
          %>
            <option value="<%= esc(iid) %>" <%= iid.equals(selectedInvoiceUuid) ? "selected" : "" %>>
              <%= esc(iid) %> | <%= esc(inv.status) %> | due <%= esc(inv.dueAt) %> | out <%= esc(centsToText(inv.outstandingCents)) %>
            </option>
          <% } %>
        </select>
      </label>
      <label>
        <span>Trust (Ledger / Held / Available)</span>
        <input type="text" value="<%= esc(centsToText(currentTrustBalance) + " / " + centsToText(heldTrustBalance) + " / " + centsToText(availableTrustBalance)) %>" readonly />
      </label>
      <label>
        <span>&nbsp;</span>
        <button class="btn" type="submit">Apply Selection</button>
      </label>
    </div>
  </form>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Capture Time and Expense</h2>
  <div class="split-grid">
    <form method="post" action="<%= ctx %>/billing.jsp" class="subcard">
      <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
      <input type="hidden" name="action" value="create_time_entry" />
      <input type="hidden" name="matter_uuid" value="<%= esc(selectedMatterUuid) %>" />
      <input type="hidden" name="invoice_uuid" value="<%= esc(selectedInvoiceUuid) %>" />
      <h3>Time Entry</h3>
      <div class="grid grid-2">
        <label><span>Activity Code</span><input type="text" name="time_activity_code" placeholder="L110 / A101 / RESEARCH" /></label>
        <label><span>Minutes</span><input type="number" min="1" name="time_minutes" value="60" /></label>
      </div>
      <div class="grid grid-2">
        <label><span>Hourly Rate (USD)</span><input type="text" name="time_rate" value="0.00" /></label>
        <label><span>Worked At (ISO)</span><input type="text" name="time_worked_at" placeholder="2026-03-10T10:00:00Z" /></label>
      </div>
      <label><span>Note</span><textarea name="time_note" rows="3" placeholder="Narrative for billing line item"></textarea></label>
      <label class="inline-check"><input type="checkbox" name="time_nonbillable" value="1" /> Mark as non-billable</label>
      <button class="btn" type="submit">Create Time Entry</button>
    </form>

    <form method="post" action="<%= ctx %>/billing.jsp" class="subcard">
      <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
      <input type="hidden" name="action" value="create_expense_entry" />
      <input type="hidden" name="matter_uuid" value="<%= esc(selectedMatterUuid) %>" />
      <input type="hidden" name="invoice_uuid" value="<%= esc(selectedInvoiceUuid) %>" />
      <h3>Expense Entry</h3>
      <label><span>Description</span><input type="text" name="expense_description" placeholder="Filing fee / service charge" /></label>
      <div class="grid grid-3">
        <label><span>Amount (USD)</span><input type="text" name="expense_amount" value="100.00" /></label>
        <label><span>Tax (USD)</span><input type="text" name="expense_tax" value="0.00" /></label>
        <label><span>Incurred At (ISO)</span><input type="text" name="expense_incurred_at" placeholder="2026-03-10T10:00:00Z" /></label>
      </div>
      <div class="grid grid-2">
        <label><span>UTBMS Expense Code</span><input type="text" name="expense_utbms_code" placeholder="E101" /></label>
        <label><span>&nbsp;</span><div class="meta">Optional. If blank, code may be inferred from description prefix like <code>E101</code>.</div></label>
      </div>
      <label class="inline-check"><input type="checkbox" name="expense_nonbillable" value="1" /> Mark as non-billable</label>
      <button class="btn" type="submit">Create Expense Entry</button>
    </form>
  </div>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Managed Activities and UTBMS Mapping</h2>
  <p class="meta">Maintain a controlled activity catalog, enforce managed mode, and store UTBMS task/activity/expense codes for consistent billing output.</p>
  <div class="split-grid">
    <form method="post" action="<%= ctx %>/billing.jsp" class="subcard">
      <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
      <input type="hidden" name="action" value="set_managed_activity_mode" />
      <input type="hidden" name="matter_uuid" value="<%= esc(selectedMatterUuid) %>" />
      <input type="hidden" name="invoice_uuid" value="<%= esc(selectedInvoiceUuid) %>" />
      <h3>Managed Mode</h3>
      <label class="inline-check"><input type="checkbox" name="require_managed_activities" value="1" <%= requireManagedActivities ? "checked" : "" %> /> Require activity codes to exist and be active in catalog</label>
      <button class="btn" type="submit">Save Managed Mode</button>
      <div class="meta" style="margin-top:8px;">Current mode: <strong><%= requireManagedActivities ? "required" : "advisory" %></strong></div>
    </form>

    <form method="post" action="<%= ctx %>/billing.jsp" class="subcard">
      <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
      <input type="hidden" name="action" value="upsert_billing_activity" />
      <input type="hidden" name="matter_uuid" value="<%= esc(selectedMatterUuid) %>" />
      <input type="hidden" name="invoice_uuid" value="<%= esc(selectedInvoiceUuid) %>" />
      <h3>Activity Catalog Entry</h3>
      <div class="grid grid-2">
        <label><span>Activity Code</span><input type="text" name="activity_code" placeholder="L110" /></label>
        <label><span>Label</span><input type="text" name="activity_label" placeholder="Case assessment" /></label>
      </div>
      <div class="grid grid-2">
        <label><span>Default Hourly Rate (USD)</span><input type="text" name="activity_default_rate" value="250.00" /></label>
        <label><span>Flags</span>
          <div class="inline-check"><input type="checkbox" name="activity_default_nonbillable" value="1" /> Default non-billable</div>
          <div class="inline-check"><input type="checkbox" name="activity_inactive" value="1" /> Mark inactive</div>
        </label>
      </div>
      <div class="grid grid-3">
        <label><span>UTBMS Task</span><input type="text" name="activity_utbms_task_code" placeholder="L110" /></label>
        <label><span>UTBMS Activity</span><input type="text" name="activity_utbms_activity_code" placeholder="A101" /></label>
        <label><span>UTBMS Expense</span><input type="text" name="activity_utbms_expense_code" placeholder="E101" /></label>
      </div>
      <button class="btn btn-ghost" type="submit">Save Activity</button>
    </form>
  </div>

  <div class="table-wrap" style="margin-top:10px;">
    <table class="table">
      <thead><tr><th>Code</th><th>Label</th><th>Defaults</th><th>UTBMS</th><th>Status</th><th>Action</th></tr></thead>
      <tbody>
      <% if (managedActivities.isEmpty()) { %>
        <tr><td colspan="6" class="muted">No managed activities configured.</td></tr>
      <% } else { for (billing_accounting.BillingActivityRec act : managedActivities) {
           if (act == null) continue;
      %>
        <tr>
          <td><code><%= esc(act.code) %></code></td>
          <td><%= esc(act.label) %></td>
          <td>rate <code><%= esc(centsToText(act.defaultRateCents)) %></code> | <%= act.defaultBillable ? "billable" : "non-billable" %></td>
          <td>task=<code><%= esc(act.utbmsTaskCode) %></code> activity=<code><%= esc(act.utbmsActivityCode) %></code> expense=<code><%= esc(act.utbmsExpenseCode) %></code></td>
          <td><%= act.active ? "active" : "inactive" %></td>
          <td>
            <% if (act.active) { %>
              <form method="post" action="<%= ctx %>/billing.jsp" class="mini-action">
                <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
                <input type="hidden" name="action" value="deactivate_billing_activity" />
                <input type="hidden" name="matter_uuid" value="<%= esc(selectedMatterUuid) %>" />
                <input type="hidden" name="invoice_uuid" value="<%= esc(selectedInvoiceUuid) %>" />
                <input type="hidden" name="activity_code" value="<%= esc(act.code) %>" />
                <button class="btn btn-ghost danger" type="submit">Deactivate</button>
              </form>
            <% } else { %>
              <span class="muted">-</span>
            <% } %>
          </td>
        </tr>
      <% }} %>
      </tbody>
    </table>
  </div>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Invoice and Trust Operations</h2>
  <div class="split-grid">
    <form method="post" action="<%= ctx %>/billing.jsp" class="subcard">
      <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
      <input type="hidden" name="action" value="draft_finalize_invoice" />
      <input type="hidden" name="matter_uuid" value="<%= esc(selectedMatterUuid) %>" />
      <input type="hidden" name="invoice_uuid" value="<%= esc(selectedInvoiceUuid) %>" />
      <h3>Create Invoice</h3>
      <div class="grid grid-2">
        <label><span>Issue Date</span><input type="date" name="invoice_issue_date" /></label>
        <label><span>Due Date</span><input type="date" name="invoice_due_date" /></label>
      </div>
      <button class="btn" type="submit">Draft + Finalize from Unbilled Entries</button>
      <button class="btn btn-ghost" style="margin-top:8px;" type="submit" name="action" value="finalize_invoice">Finalize Selected Draft</button>
    </form>

    <form method="post" action="<%= ctx %>/billing.jsp" class="subcard">
      <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
      <input type="hidden" name="matter_uuid" value="<%= esc(selectedMatterUuid) %>" />
      <input type="hidden" name="invoice_uuid" value="<%= esc(selectedInvoiceUuid) %>" />
      <h3>Trust and Payment Posting</h3>
      <div class="grid grid-3">
        <label><span>Trust Deposit (USD)</span><input type="text" name="trust_deposit_amount" value="1000.00" /></label>
        <label><span>Posted At</span><input type="text" name="trust_deposit_posted_at" placeholder="2026-03-10T10:00:00Z" /></label>
        <label><span>Reference</span><input type="text" name="trust_deposit_reference" value="Initial retainer" /></label>
      </div>
      <button class="btn btn-ghost" type="submit" name="action" value="trust_deposit">Post Trust Deposit</button>

      <hr />

      <div class="grid grid-3">
        <label><span>Apply Trust to Invoice (USD)</span><input type="text" name="trust_apply_amount" value="250.00" /></label>
        <label><span>Posted At</span><input type="text" name="trust_apply_posted_at" placeholder="2026-03-10T10:00:00Z" /></label>
        <label><span>Reference</span><input type="text" name="trust_apply_reference" value="Earned fee transfer" /></label>
      </div>
      <button class="btn btn-ghost" type="submit" name="action" value="trust_apply">Apply Trust</button>

      <hr />

      <div class="grid grid-3">
        <label><span>Operating Payment (USD)</span><input type="text" name="operating_payment_amount" value="100.00" /></label>
        <label><span>Posted At</span><input type="text" name="operating_payment_posted_at" placeholder="2026-03-10T10:00:00Z" /></label>
        <label><span>Reference</span><input type="text" name="operating_payment_reference" value="Card payment" /></label>
      </div>
      <button class="btn btn-ghost" type="submit" name="action" value="operating_payment">Post Operating Payment</button>

      <hr />

      <div class="grid grid-3">
        <label><span>Trust Refund (USD)</span><input type="text" name="trust_refund_amount" value="100.00" /></label>
        <label><span>Posted At</span><input type="text" name="trust_refund_posted_at" placeholder="2026-03-10T10:00:00Z" /></label>
        <label><span>Reference</span><input type="text" name="trust_refund_reference" value="Matter closeout" /></label>
      </div>
      <button class="btn btn-ghost" type="submit" name="action" value="trust_refund">Post Trust Refund</button>
    </form>
  </div>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Texas IOLTA Controls and Commercial Billing Features</h2>
  <p class="meta">Adds trust-policy guardrails, disputed-fund holds, approval workflow, and payment plans/autopay inspired by common legal-practice billing systems.</p>

  <div class="split-grid">
    <form method="post" action="<%= ctx %>/billing.jsp" class="subcard">
      <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
      <input type="hidden" name="action" value="set_trust_policy" />
      <input type="hidden" name="matter_uuid" value="<%= esc(selectedMatterUuid) %>" />
      <input type="hidden" name="invoice_uuid" value="<%= esc(selectedInvoiceUuid) %>" />
      <h3>Matter Trust Policy</h3>
      <div class="grid grid-2">
        <label><span>IOLTA Account Name</span><input type="text" name="policy_iolta_account_name" value="<%= esc(trustPolicy == null ? "" : trustPolicy.ioltaAccountName) %>" placeholder="Primary IOLTA" /></label>
        <label><span>Institution Name</span><input type="text" name="policy_iolta_institution_name" value="<%= esc(trustPolicy == null ? "" : trustPolicy.ioltaInstitutionName) %>" placeholder="Eligible financial institution" /></label>
      </div>
      <div class="grid grid-2">
        <label><span>Annual Certification Due</span><input type="date" name="policy_annual_due_date" value="<%= esc(asDateOrBlank(trustPolicy == null ? "" : trustPolicy.annualCertificationDueDate)) %>" /></label>
        <label><span>Certification Completed</span><input type="date" name="policy_annual_completed_date" value="<%= esc(asDateOrBlank(trustPolicy == null ? "" : trustPolicy.annualCertificationCompletedAt)) %>" /></label>
      </div>
      <div class="grid grid-2">
        <label><span>Minimum Trust Reserve (USD)</span><input type="text" name="policy_minimum_reserve" value="<%= esc(centsToText(trustPolicy == null ? 0L : trustPolicy.minimumTrustReserveCents)) %>" /></label>
        <label><span>Policy Flags</span>
          <div class="inline-check"><input type="checkbox" name="policy_iolta_institution_eligible" value="1" <%= trustPolicy != null && trustPolicy.ioltaInstitutionEligible ? "checked" : "" %> /> Institution eligibility confirmed</div>
          <div class="inline-check"><input type="checkbox" name="policy_require_approval" value="1" <%= trustPolicy != null && trustPolicy.requireDisbursementApproval ? "checked" : "" %> /> Require approval for trust disbursements</div>
        </label>
      </div>
      <button class="btn" type="submit">Save Matter Trust Policy</button>
      <div class="meta" style="margin-top:8px;">
        Ledger trust=<code><%= esc(centsToText(currentTrustBalance)) %></code>,
        held=<code><%= esc(centsToText(heldTrustBalance)) %></code>,
        available=<code><%= esc(centsToText(availableTrustBalance)) %></code>
      </div>
    </form>

    <div class="subcard">
      <h3>Disputed Funds Hold Queue</h3>
      <form method="post" action="<%= ctx %>/billing.jsp">
        <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
        <input type="hidden" name="action" value="place_trust_hold" />
        <input type="hidden" name="matter_uuid" value="<%= esc(selectedMatterUuid) %>" />
        <input type="hidden" name="invoice_uuid" value="<%= esc(selectedInvoiceUuid) %>" />
        <div class="grid grid-2">
          <label><span>Hold Amount (USD)</span><input type="text" name="hold_amount" value="100.00" /></label>
          <label><span>Placed At (ISO)</span><input type="text" name="hold_placed_at" placeholder="2026-03-10T10:00:00Z" /></label>
        </div>
        <label><span>Reason</span><textarea name="hold_reason" rows="2" placeholder="Disputed fee amount pending resolution"></textarea></label>
        <button class="btn btn-ghost" type="submit">Place Dispute Hold</button>
      </form>

      <div class="table-wrap" style="margin-top:10px;">
        <table class="table">
          <thead><tr><th>Placed</th><th>Amount</th><th>Status</th><th>Reason</th><th>Action</th></tr></thead>
          <tbody>
          <% if (matterTrustHolds.isEmpty()) { %>
            <tr><td colspan="5" class="muted">No trust dispute holds for this matter.</td></tr>
          <% } else { for (billing_accounting.TrustDisputeHoldRec hold : matterTrustHolds) {
               if (hold == null) continue;
          %>
            <tr>
              <td><%= esc(hold.placedAt) %></td>
              <td><%= esc(centsToText(hold.amountCents)) %></td>
              <td><%= hold.active ? "active" : "released" %></td>
              <td><%= esc(hold.reason) %><% if (!hold.active) { %><div class="meta"><%= esc(hold.resolution) %></div><% } %></td>
              <td>
                <% if (hold.active) { %>
                  <form method="post" action="<%= ctx %>/billing.jsp" class="mini-action">
                    <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
                    <input type="hidden" name="action" value="release_trust_hold" />
                    <input type="hidden" name="matter_uuid" value="<%= esc(selectedMatterUuid) %>" />
                    <input type="hidden" name="invoice_uuid" value="<%= esc(selectedInvoiceUuid) %>" />
                    <input type="hidden" name="hold_uuid" value="<%= esc(hold.uuid) %>" />
                    <input type="hidden" name="hold_released_at" value="" />
                    <input type="hidden" name="hold_resolution" value="Released from billing hold." />
                    <button class="btn btn-ghost" type="submit">Release</button>
                  </form>
                <% } else { %>
                  <span class="muted">-</span>
                <% } %>
              </td>
            </tr>
          <% }} %>
          </tbody>
        </table>
      </div>
    </div>
  </div>

  <div class="split-grid" style="margin-top:12px;">
    <div class="subcard">
      <h3>Trust Disbursement Approval Workflow</h3>
      <form method="post" action="<%= ctx %>/billing.jsp">
        <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
        <input type="hidden" name="action" value="request_disbursement_approval" />
        <input type="hidden" name="matter_uuid" value="<%= esc(selectedMatterUuid) %>" />
        <input type="hidden" name="invoice_uuid" value="<%= esc(selectedInvoiceUuid) %>" />
        <div class="grid grid-2">
          <label>
            <span>Disbursement Type</span>
            <select name="approval_disbursement_type">
              <option value="trust_apply">trust_apply</option>
              <option value="trust_refund">trust_refund</option>
            </select>
          </label>
          <label>
            <span>Invoice (for trust_apply)</span>
            <select name="approval_invoice_uuid">
              <option value="">(none)</option>
              <% for (billing_accounting.InvoiceRec inv : matterInvoices) {
                   if (inv == null) continue;
              %>
                <option value="<%= esc(inv.uuid) %>" <%= safe(inv.uuid).equals(selectedInvoiceUuid) ? "selected" : "" %>><%= esc(inv.uuid) %> | out <%= esc(centsToText(inv.outstandingCents)) %></option>
              <% } %>
            </select>
          </label>
        </div>
        <div class="grid grid-2">
          <label><span>Amount (USD)</span><input type="text" name="approval_amount" value="100.00" /></label>
          <label><span>Requested At (ISO)</span><input type="text" name="approval_requested_at" placeholder="2026-03-10T10:00:00Z" /></label>
        </div>
        <label><span>Reason</span><textarea name="approval_reason" rows="2" placeholder="Client requested release of disputed funds."></textarea></label>
        <button class="btn btn-ghost" type="submit">Request Approval</button>
      </form>

      <div class="table-wrap" style="margin-top:10px;">
        <table class="table">
          <thead><tr><th>Requested</th><th>Type</th><th>Amount</th><th>Status</th><th>Action</th></tr></thead>
          <tbody>
          <% if (matterDisbursementApprovals.isEmpty()) { %>
            <tr><td colspan="5" class="muted">No disbursement approvals for this matter.</td></tr>
          <% } else { for (billing_accounting.TrustDisbursementApprovalRec approval : matterDisbursementApprovals) {
               if (approval == null) continue;
          %>
            <tr>
              <td><%= esc(approval.requestedAt) %></td>
              <td><code><%= esc(approval.disbursementType) %></code></td>
              <td><%= esc(centsToText(approval.amountCents)) %></td>
              <td><%= esc(approval.status) %></td>
              <td>
                <% if ("requested".equals(safe(approval.status))) { %>
                  <form method="post" action="<%= ctx %>/billing.jsp" class="mini-action">
                    <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
                    <input type="hidden" name="action" value="review_disbursement_approval" />
                    <input type="hidden" name="matter_uuid" value="<%= esc(selectedMatterUuid) %>" />
                    <input type="hidden" name="invoice_uuid" value="<%= esc(selectedInvoiceUuid) %>" />
                    <input type="hidden" name="approval_uuid" value="<%= esc(approval.uuid) %>" />
                    <input type="hidden" name="approval_decision" value="approve" />
                    <input type="hidden" name="approval_review_notes" value="Approved from billing screen." />
                    <input type="hidden" name="approval_reviewed_at" value="" />
                    <button class="btn btn-ghost" type="submit">Approve</button>
                  </form>
                  <form method="post" action="<%= ctx %>/billing.jsp" class="mini-action" style="margin-top:6px;">
                    <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
                    <input type="hidden" name="action" value="review_disbursement_approval" />
                    <input type="hidden" name="matter_uuid" value="<%= esc(selectedMatterUuid) %>" />
                    <input type="hidden" name="invoice_uuid" value="<%= esc(selectedInvoiceUuid) %>" />
                    <input type="hidden" name="approval_uuid" value="<%= esc(approval.uuid) %>" />
                    <input type="hidden" name="approval_decision" value="reject" />
                    <input type="hidden" name="approval_review_notes" value="Rejected from billing screen." />
                    <input type="hidden" name="approval_reviewed_at" value="" />
                    <button class="btn btn-ghost danger" type="submit">Reject</button>
                  </form>
                <% } else if ("approved".equals(safe(approval.status))) { %>
                  <form method="post" action="<%= ctx %>/billing.jsp" class="mini-action">
                    <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
                    <input type="hidden" name="action" value="execute_disbursement_approval" />
                    <input type="hidden" name="matter_uuid" value="<%= esc(selectedMatterUuid) %>" />
                    <input type="hidden" name="invoice_uuid" value="<%= esc(selectedInvoiceUuid) %>" />
                    <input type="hidden" name="approval_uuid" value="<%= esc(approval.uuid) %>" />
                    <input type="hidden" name="approval_execute_posted_at" value="" />
                    <input type="hidden" name="approval_execute_reference" value="Approved trust disbursement" />
                    <button class="btn" type="submit">Execute</button>
                  </form>
                <% } else { %>
                  <span class="muted">-</span>
                <% } %>
              </td>
            </tr>
          <% }} %>
          </tbody>
        </table>
      </div>
    </div>

    <div class="subcard">
      <h3>Payment Plans and Autopay</h3>
      <form method="post" action="<%= ctx %>/billing.jsp">
        <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
        <input type="hidden" name="action" value="create_payment_plan" />
        <input type="hidden" name="matter_uuid" value="<%= esc(selectedMatterUuid) %>" />
        <input type="hidden" name="invoice_uuid" value="<%= esc(selectedInvoiceUuid) %>" />
        <label>
          <span>Invoice</span>
          <select name="plan_invoice_uuid">
            <% for (billing_accounting.InvoiceRec inv : matterInvoices) {
                 if (inv == null) continue;
            %>
              <option value="<%= esc(inv.uuid) %>" <%= safe(inv.uuid).equals(selectedInvoiceUuid) ? "selected" : "" %>><%= esc(inv.uuid) %> | out <%= esc(centsToText(inv.outstandingCents)) %></option>
            <% } %>
          </select>
        </label>
        <div class="grid grid-3">
          <label><span>Installments</span><input type="number" min="1" name="plan_installment_count" value="3" /></label>
          <label><span>Cadence Days</span><input type="number" min="1" name="plan_cadence_days" value="30" /></label>
          <label><span>First Due Date</span><input type="date" name="plan_first_due_at" /></label>
        </div>
        <div class="grid grid-2">
          <label><span>Reference</span><input type="text" name="plan_reference" value="Payment plan" /></label>
          <label><span>Autopay</span><div class="inline-check"><input type="checkbox" name="plan_autopay" value="1" /> Enable autopay sweep</div></label>
        </div>
        <button class="btn btn-ghost" type="submit">Create Payment Plan</button>
      </form>

      <form method="post" action="<%= ctx %>/billing.jsp" style="margin-top:10px;">
        <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
        <input type="hidden" name="action" value="run_autopay_sweep" />
        <input type="hidden" name="matter_uuid" value="<%= esc(selectedMatterUuid) %>" />
        <input type="hidden" name="invoice_uuid" value="<%= esc(selectedInvoiceUuid) %>" />
        <div class="grid grid-3">
          <label><span>Autopay As-Of Date</span><input type="date" name="autopay_as_of_date" value="<%= esc(asDateOrBlank(request.getParameter("autopay_as_of_date"))) %>" /></label>
          <label><span>Posted At (ISO)</span><input type="text" name="autopay_posted_at" placeholder="2026-03-10T10:00:00Z" /></label>
          <label><span>Reference Prefix</span><input type="text" name="autopay_reference_prefix" value="Autopay sweep" /></label>
        </div>
        <button class="btn btn-ghost" type="submit">Run Autopay Sweep</button>
      </form>

      <div class="table-wrap" style="margin-top:10px;">
        <table class="table">
          <thead><tr><th>Plan</th><th>Invoice</th><th>Status</th><th>Next Due</th><th>Progress</th><th>Action</th></tr></thead>
          <tbody>
          <% if (matterPaymentPlans.isEmpty()) { %>
            <tr><td colspan="6" class="muted">No payment plans for this matter.</td></tr>
          <% } else { for (billing_accounting.PaymentPlanRec plan : matterPaymentPlans) {
               if (plan == null) continue;
          %>
            <tr>
              <td><code><%= esc(plan.uuid) %></code><div class="meta">autopay=<%= plan.autopay ? "yes" : "no" %></div></td>
              <td><code><%= esc(plan.invoiceUuid) %></code></td>
              <td><%= esc(plan.status) %></td>
              <td><%= esc(plan.nextDueAt) %></td>
              <td><%= plan.paymentsPostedCount %>/<%= plan.installmentCount %></td>
              <td>
                <% if ("active".equals(safe(plan.status))) { %>
                  <form method="post" action="<%= ctx %>/billing.jsp" class="mini-action">
                    <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
                    <input type="hidden" name="action" value="post_payment_plan_installment" />
                    <input type="hidden" name="matter_uuid" value="<%= esc(selectedMatterUuid) %>" />
                    <input type="hidden" name="invoice_uuid" value="<%= esc(selectedInvoiceUuid) %>" />
                    <input type="hidden" name="plan_uuid" value="<%= esc(plan.uuid) %>" />
                    <input type="hidden" name="plan_payment_posted_at" value="" />
                    <input type="hidden" name="plan_payment_reference" value="" />
                    <button class="btn btn-ghost" type="submit">Post Installment</button>
                  </form>
                  <form method="post" action="<%= ctx %>/billing.jsp" class="mini-action" style="margin-top:6px;">
                    <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
                    <input type="hidden" name="action" value="cancel_payment_plan" />
                    <input type="hidden" name="matter_uuid" value="<%= esc(selectedMatterUuid) %>" />
                    <input type="hidden" name="invoice_uuid" value="<%= esc(selectedInvoiceUuid) %>" />
                    <input type="hidden" name="plan_uuid" value="<%= esc(plan.uuid) %>" />
                    <button class="btn btn-ghost danger" type="submit">Cancel</button>
                  </form>
                <% } else { %>
                  <span class="muted">-</span>
                <% } %>
              </td>
            </tr>
          <% }} %>
          </tbody>
        </table>
      </div>
    </div>
  </div>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Customizable Output Templates</h2>
  <p class="meta">Configure invoice, trust-request, and statement-of-account outputs with inline templates or mapped form templates.</p>

  <div class="table-wrap">
    <table class="table">
      <thead>
        <tr>
          <th>Document Type</th>
          <th>Template Source</th>
          <th>Mapped Template UUID</th>
          <th>Updated</th>
        </tr>
      </thead>
      <tbody>
      <% for (billing_output_documents.TemplateConfigRec rec : templateConfigs) {
           if (rec == null) continue;
           String source = safe(rec.templateUuid).isBlank() ? "Inline template" : "Form template";
      %>
        <tr>
          <td><code><%= esc(rec.documentType) %></code></td>
          <td><%= esc(source) %></td>
          <td><code><%= esc(rec.templateUuid) %></code></td>
          <td><%= esc(rec.updatedAt) %></td>
        </tr>
      <% } %>
      </tbody>
    </table>
  </div>

  <div class="split-grid" style="margin-top:12px;">
    <form method="post" action="<%= ctx %>/billing.jsp" class="subcard">
      <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
      <input type="hidden" name="action" value="set_inline_template" />
      <input type="hidden" name="matter_uuid" value="<%= esc(selectedMatterUuid) %>" />
      <input type="hidden" name="invoice_uuid" value="<%= esc(selectedInvoiceUuid) %>" />
      <h3>Inline Template Editor</h3>
      <label>
        <span>Document Type</span>
        <select name="template_doc_type">
          <option value="invoice" <%= "invoice".equals(selectedTemplateDocType) ? "selected" : "" %>>invoice</option>
          <option value="trust_request" <%= "trust_request".equals(selectedTemplateDocType) ? "selected" : "" %>>trust_request</option>
          <option value="statement_of_account" <%= "statement_of_account".equals(selectedTemplateDocType) ? "selected" : "" %>>statement_of_account</option>
        </select>
      </label>
      <textarea name="inline_template" rows="12"><%
        billing_output_documents.TemplateConfigRec selectedCfg = cfgByType.get(selectedTemplateDocType);
        out.print(esc(selectedCfg == null ? "" : selectedCfg.inlineTemplate));
      %></textarea>
      <div class="btn-row">
        <button class="btn" type="submit">Save Inline Template</button>
        <button class="btn btn-ghost" type="submit" name="action" value="reset_default_template">Reset to Default</button>
      </div>
    </form>

    <form method="post" action="<%= ctx %>/billing.jsp" class="subcard">
      <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
      <input type="hidden" name="action" value="set_form_template" />
      <input type="hidden" name="matter_uuid" value="<%= esc(selectedMatterUuid) %>" />
      <input type="hidden" name="invoice_uuid" value="<%= esc(selectedInvoiceUuid) %>" />
      <h3>Map Uploaded Form Template</h3>
      <label>
        <span>Document Type</span>
        <select name="template_doc_type">
          <option value="invoice">invoice</option>
          <option value="trust_request">trust_request</option>
          <option value="statement_of_account">statement_of_account</option>
        </select>
      </label>
      <label>
        <span>Form Template</span>
        <select name="form_template_uuid">
          <% if (availableTemplates.isEmpty()) { %>
            <option value="">No templates available</option>
          <% } else { for (form_templates.TemplateRec t : availableTemplates) {
               if (t == null) continue;
          %>
            <option value="<%= esc(t.uuid) %>"><%= esc(t.label) %> (<%= esc(t.fileExt) %>)</option>
          <% }} %>
        </select>
      </label>
      <button class="btn" type="submit">Map Form Template</button>

      <h4 style="margin-top:14px;">Token Catalog</h4>
      <div class="token-grid">
        <div>
          <strong>invoice</strong>
          <ul>
            <% for (String token : outputStore.tokenCatalog("invoice")) { %><li><code><%= esc(token) %></code></li><% } %>
          </ul>
        </div>
        <div>
          <strong>trust_request</strong>
          <ul>
            <% for (String token : outputStore.tokenCatalog("trust_request")) { %><li><code><%= esc(token) %></code></li><% } %>
          </ul>
        </div>
        <div>
          <strong>statement_of_account</strong>
          <ul>
            <% for (String token : outputStore.tokenCatalog("statement_of_account")) { %><li><code><%= esc(token) %></code></li><% } %>
          </ul>
        </div>
      </div>
    </form>
  </div>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Generate Outputs</h2>
  <form method="post" action="<%= ctx %>/billing.jsp" class="form">
    <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
    <input type="hidden" name="matter_uuid" value="<%= esc(selectedMatterUuid) %>" />
    <input type="hidden" name="invoice_uuid" value="<%= esc(selectedInvoiceUuid) %>" />

    <div class="subcard">
      <h3>Profile and Custom Token Inputs (shared)</h3>
      <div class="grid grid-2">
        <label><span>Firm Name</span><input type="text" name="profile_tenant_name" value="<%= esc(safe(request.getParameter("profile_tenant_name")).isBlank() ? "Chapman Law Group" : request.getParameter("profile_tenant_name")) %>" /></label>
        <label><span>Client Name</span><input type="text" name="profile_client_name" value="<%= esc(safe(request.getParameter("profile_client_name")).isBlank() ? "Client Name" : request.getParameter("profile_client_name")) %>" /></label>
      </div>
      <div class="grid grid-2">
        <label><span>Client Address</span><input type="text" name="profile_client_address" value="<%= esc(safe(request.getParameter("profile_client_address"))) %>" /></label>
        <label><span>Matter Label</span><input type="text" name="profile_matter_label" value="<%= esc(safe(request.getParameter("profile_matter_label")).isBlank() ? safe(matterLabelByUuid.get(selectedMatterUuid)) : request.getParameter("profile_matter_label")) %>" /></label>
      </div>
      <div class="grid grid-2">
        <label><span>Matter Number</span><input type="text" name="profile_matter_number" value="<%= esc(safe(request.getParameter("profile_matter_number"))) %>" /></label>
        <label><span>Custom Tokens (<code>key=value</code>)</span><textarea name="custom_tokens" rows="4" placeholder="invoice.notes=Thank you for your business&#10;tenant.phone=555-123-4567"><%= esc(safe(request.getParameter("custom_tokens"))) %></textarea></label>
      </div>
    </div>

    <div class="split-grid" style="margin-top:12px;">
      <div class="subcard">
        <h3>Invoice</h3>
        <p class="meta">Uses selected matter and invoice.</p>
        <div class="btn-row">
          <button class="btn" type="submit" name="action" value="render_invoice_preview">Preview Invoice Output</button>
          <button class="btn btn-ghost" type="submit" name="action" value="render_invoice_download">Download Invoice Output</button>
        </div>
      </div>

      <div class="subcard">
        <h3>Trust Request</h3>
        <div class="grid grid-2">
          <label><span>Request ID</span><input type="text" name="trust_request_id" value="<%= esc(safe(request.getParameter("trust_request_id"))) %>" /></label>
          <label><span>Requested Amount (USD)</span><input type="text" name="trust_request_amount" value="<%= esc(safe(request.getParameter("trust_request_amount")).isBlank() ? "500.00" : request.getParameter("trust_request_amount")) %>" /></label>
        </div>
        <div class="grid grid-2">
          <label><span>Requested At (ISO)</span><input type="text" name="trust_request_requested_at" value="<%= esc(safe(request.getParameter("trust_request_requested_at")).isBlank() ? net.familylawandprobate.controversies.app_clock.now().toString() : request.getParameter("trust_request_requested_at")) %>" /></label>
          <label><span>Due Date</span><input type="date" name="trust_request_due_at" value="<%= esc(asDateOrBlank(request.getParameter("trust_request_due_at"))) %>" /></label>
        </div>
        <div class="grid grid-2">
          <label><span>Requested By</span><input type="text" name="trust_request_requested_by" value="<%= esc(safe(request.getParameter("trust_request_requested_by")).isBlank() ? (userEmail.isBlank() ? userUuid : userEmail) : request.getParameter("trust_request_requested_by")) %>" /></label>
          <label><span>Minimum Target Balance (USD)</span><input type="text" name="trust_request_target_balance" value="<%= esc(safe(request.getParameter("trust_request_target_balance")).isBlank() ? "1000.00" : request.getParameter("trust_request_target_balance")) %>" /></label>
        </div>
        <label><span>Reason</span><textarea name="trust_request_reason" rows="2"><%= esc(safe(request.getParameter("trust_request_reason")).isBlank() ? "Trust balance below target due to invoice activity." : request.getParameter("trust_request_reason")) %></textarea></label>
        <label><span>Notes</span><textarea name="trust_request_notes" rows="2"><%= esc(safe(request.getParameter("trust_request_notes"))) %></textarea></label>
        <div class="btn-row">
          <button class="btn" type="submit" name="action" value="render_trust_request_preview">Preview Trust Request</button>
          <button class="btn btn-ghost" type="submit" name="action" value="render_trust_request_download">Download Trust Request</button>
        </div>
      </div>
    </div>

    <div class="subcard" style="margin-top:12px;">
      <h3>Statement of Account</h3>
      <div class="grid grid-3">
        <label><span>Period Start</span><input type="date" name="statement_period_start" value="<%= esc(asDateOrBlank(request.getParameter("statement_period_start"))) %>" /></label>
        <label><span>Period End</span><input type="date" name="statement_period_end" value="<%= esc(asDateOrBlank(request.getParameter("statement_period_end"))) %>" /></label>
        <label><span>&nbsp;</span>
          <button class="btn" style="width:100%;" type="submit" name="action" value="render_statement_preview">Preview Statement</button>
        </label>
      </div>
      <div class="btn-row">
        <button class="btn btn-ghost" type="submit" name="action" value="render_statement_download">Download Statement</button>
      </div>
    </div>

    <div class="subcard" style="margin-top:12px;">
      <h3>LEDES1998B (UTBMS e-billing)</h3>
      <div class="grid grid-3">
        <label><span>Client ID</span><input type="text" name="ledes_client_id" value="<%= esc(safe(request.getParameter("ledes_client_id"))) %>" placeholder="CLIENT-001" /></label>
        <label><span>Law Firm ID</span><input type="text" name="ledes_law_firm_id" value="<%= esc(safe(request.getParameter("ledes_law_firm_id"))) %>" placeholder="FIRM-001" /></label>
        <label><span>Client Matter ID</span><input type="text" name="ledes_client_matter_id" value="<%= esc(safe(request.getParameter("ledes_client_matter_id"))) %>" placeholder="MATTER-REF-001" /></label>
      </div>
      <div class="meta" style="margin-top:6px;">Exports the selected invoice as LEDES1998B text with UTBMS task/activity/expense codes from managed activities and entry metadata.</div>
      <div class="btn-row" style="margin-top:8px;">
        <button class="btn" type="submit" name="action" value="render_ledes_preview">Preview LEDES</button>
        <button class="btn btn-ghost" type="submit" name="action" value="render_ledes_download">Download LEDES</button>
      </div>
    </div>
  </form>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Ledger Snapshot</h2>
  <div class="grid grid-4">
    <div class="kv"><span>Selected Matter</span><strong><%= esc(safe(matterLabelByUuid.get(selectedMatterUuid))) %></strong></div>
    <div class="kv"><span>Invoices</span><strong><%= matterInvoices.size() %></strong></div>
    <div class="kv"><span>Matter Payments</span><strong><%= matterPayments.size() %></strong></div>
    <div class="kv"><span>Matter Trust Txns</span><strong><%= matterTrustTxns.size() %></strong></div>
  </div>
  <div class="meta" style="margin-top:8px;">
    trust ledger=<code><%= esc(centsToText(currentTrustBalance)) %></code> |
    held=<code><%= esc(centsToText(heldTrustBalance)) %></code> |
    available=<code><%= esc(centsToText(availableTrustBalance)) %></code> |
    approvals=<code><%= matterDisbursementApprovals.size() %></code> |
    payment_plans=<code><%= matterPaymentPlans.size() %></code> |
    managed_activities=<code><%= managedActivities.size() %></code>
  </div>

  <h3 style="margin-top:14px;">Invoice List</h3>
  <div class="table-wrap">
    <table class="table">
      <thead>
        <tr>
          <th>UUID</th>
          <th>Status</th>
          <th>Issued</th>
          <th>Due</th>
          <th>Total</th>
          <th>Paid</th>
          <th>Outstanding</th>
        </tr>
      </thead>
      <tbody>
      <% if (matterInvoices.isEmpty()) { %>
        <tr><td colspan="7" class="muted">No invoices for this matter yet.</td></tr>
      <% } else { for (billing_accounting.InvoiceRec inv : matterInvoices) {
           if (inv == null) continue;
      %>
        <tr <%= safe(inv.uuid).equals(selectedInvoiceUuid) ? "class=\"row-focus\"" : "" %>>
          <td><code><%= esc(inv.uuid) %></code></td>
          <td><%= esc(inv.status) %></td>
          <td><%= esc(inv.issuedAt) %></td>
          <td><%= esc(inv.dueAt) %></td>
          <td><%= esc(centsToText(inv.totalCents)) %></td>
          <td><%= esc(centsToText(inv.paidCents)) %></td>
          <td><%= esc(centsToText(inv.outstandingCents)) %></td>
        </tr>
      <% }} %>
      </tbody>
    </table>
  </div>

  <div class="split-grid" style="margin-top:12px;">
    <div>
      <h3>Trust Transactions</h3>
      <div class="table-wrap">
        <table class="table">
          <thead><tr><th>Posted</th><th>Kind</th><th>Reference</th><th>Invoice</th><th>Amount</th></tr></thead>
          <tbody>
          <% if (matterTrustTxns.isEmpty()) { %>
            <tr><td colspan="5" class="muted">No trust transactions.</td></tr>
          <% } else { for (billing_accounting.TrustTxnRec t : matterTrustTxns) {
               if (t == null) continue;
          %>
            <tr>
              <td><%= esc(t.postedAt) %></td>
              <td><%= esc(t.kind) %></td>
              <td><%= esc(t.reference) %></td>
              <td><code><%= esc(t.invoiceUuid) %></code></td>
              <td><%= esc(centsToText(t.amountCents)) %></td>
            </tr>
          <% }} %>
          </tbody>
        </table>
      </div>
    </div>
    <div>
      <h3>Payments</h3>
      <div class="table-wrap">
        <table class="table">
          <thead><tr><th>Posted</th><th>Kind</th><th>Reference</th><th>Invoice</th><th>Amount</th></tr></thead>
          <tbody>
          <% if (matterPayments.isEmpty()) { %>
            <tr><td colspan="5" class="muted">No payments.</td></tr>
          <% } else { for (billing_accounting.PaymentRec p : matterPayments) {
               if (p == null) continue;
          %>
            <tr>
              <td><%= esc(p.postedAt) %></td>
              <td><%= esc(p.kind) %></td>
              <td><%= esc(p.reference) %></td>
              <td><code><%= esc(p.invoiceUuid) %></code></td>
              <td><%= esc(centsToText(p.amountCents)) %></td>
            </tr>
          <% }} %>
          </tbody>
        </table>
      </div>
    </div>
  </div>

  <h3 style="margin-top:14px;">Trust Balances by Matter</h3>
  <div class="table-wrap">
    <table class="table">
      <thead><tr><th>Matter</th><th>Balance</th></tr></thead>
      <tbody>
      <% if (trustBalances.isEmpty()) { %>
        <tr><td colspan="2" class="muted">No trust balances yet.</td></tr>
      <% } else { for (billing_accounting.MatterTrustBalanceRec b : trustBalances) {
           if (b == null) continue;
           String label = safe(matterLabelByUuid.get(safe(b.matterUuid)));
           if (label.isBlank()) label = safe(b.matterUuid);
      %>
        <tr>
          <td><%= esc(label) %> <code><%= esc(b.matterUuid) %></code></td>
          <td><%= esc(centsToText(b.balanceCents)) %></td>
        </tr>
      <% }} %>
      </tbody>
    </table>
  </div>

  <h3 style="margin-top:14px;">Compliance Snapshot</h3>
  <div class="meta">
    Trust bank (book): <code><%= esc(centsToText(previewRecon.bookTrustBankBalanceCents)) %></code> |
    Client subledger total: <code><%= esc(centsToText(previewRecon.clientLedgerTotalCents)) %></code> |
    Trust liability: <code><%= esc(centsToText(previewRecon.trustLiabilityBalanceCents)) %></code> |
    Balanced: <strong><%= compliance.trustReconciliation.balanced ? "yes" : "no" %></strong>
  </div>
  <% if (compliance.violations.isEmpty()) { %>
    <div class="notice success" style="margin-top:8px;">No compliance violations detected in current in-memory ledger snapshot.</div>
  <% } else { %>
    <div class="notice error" style="margin-top:8px;">
      <strong>Violations:</strong>
      <ul>
        <% for (String v : compliance.violations) { %><li><%= esc(v) %></li><% } %>
      </ul>
    </div>
  <% } %>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Rendered Output Preview</h2>
  <% if (previewTitle.isBlank()) { %>
    <div class="muted">Generate a preview from the output section above.</div>
  <% } else { %>
    <div class="meta">
      <strong><%= esc(previewTitle) %></strong> |
      source=<code><%= esc(previewSource) %></code> |
      type=<code><%= esc(previewContentType) %></code> |
      ext=<code><%= esc(previewExtension) %></code> |
      bytes=<code><%= previewBytes %></code>
    </div>
    <pre class="preview-pane"><%= esc(previewBody) %></pre>
  <% } %>
</section>

<section class="card" style="margin-top:12px;">
  <form method="post" action="<%= ctx %>/billing.jsp" class="form-inline">
    <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
    <input type="hidden" name="action" value="clear_runtime_ledger" />
    <input type="hidden" name="matter_uuid" value="<%= esc(selectedMatterUuid) %>" />
    <input type="hidden" name="invoice_uuid" value="" />
    <button class="btn btn-ghost danger" type="submit">Clear Runtime Ledger (Tenant)</button>
    <span class="meta">Removes only in-memory ledger data for this tenant. Template configurations remain on disk.</span>
  </form>
</section>

<style>
  .split-grid {
    display:grid;
    grid-template-columns: 1fr 1fr;
    gap: 12px;
  }
  .subcard {
    border:1px solid var(--border);
    border-radius: 12px;
    padding: 12px;
    background: var(--surface-2);
  }
  .btn-row {
    display:flex;
    gap:8px;
    flex-wrap:wrap;
    margin-top:8px;
  }
  .inline-check {
    display:flex;
    align-items:center;
    gap:8px;
    margin: 6px 0 10px;
  }
  .notice {
    margin-top:10px;
    border:1px solid var(--border);
    border-radius:10px;
    padding:8px 10px;
  }
  .notice.success {
    background: rgba(16, 185, 129, 0.08);
    border-color: rgba(16, 185, 129, 0.28);
  }
  .notice.error {
    background: rgba(239, 68, 68, 0.10);
    border-color: rgba(239, 68, 68, 0.35);
  }
  .token-grid {
    display:grid;
    grid-template-columns: 1fr 1fr 1fr;
    gap:10px;
    max-height: 320px;
    overflow:auto;
    border:1px solid var(--border);
    border-radius:10px;
    padding:8px;
    background: var(--surface);
  }
  .token-grid ul {
    margin: 8px 0 0;
    padding-left: 18px;
  }
  .preview-pane {
    margin-top:10px;
    border:1px solid var(--border);
    border-radius:10px;
    padding:10px;
    background: var(--surface-2);
    max-height: 440px;
    overflow:auto;
    white-space: pre-wrap;
    word-break: break-word;
    font-size: 0.92rem;
    line-height: 1.4;
  }
  .kv {
    border:1px solid var(--border);
    border-radius:10px;
    padding:8px;
    background: var(--surface-2);
  }
  .kv span {
    display:block;
    font-size: 0.82rem;
    color: var(--text-muted);
  }
  .row-focus td {
    background: rgba(37,99,235,0.08);
  }
  .form-inline {
    display:flex;
    gap:10px;
    align-items:center;
    flex-wrap:wrap;
  }
  .mini-action {
    margin: 0;
  }
  .danger {
    border-color: rgba(239,68,68,0.42);
    color: #991B1B;
  }
  @media (max-width: 980px) {
    .split-grid { grid-template-columns: 1fr; }
    .token-grid { grid-template-columns: 1fr; max-height: none; }
  }
</style>

<jsp:include page="footer.jsp" />
