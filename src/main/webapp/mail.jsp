<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" isELIgnored="true" %>

<%@ page import="java.net.URLEncoder" %>
<%@ page import="java.nio.charset.StandardCharsets" %>
<%@ page import="java.util.ArrayList" %>
<%@ page import="java.util.HashMap" %>
<%@ page import="java.util.LinkedHashMap" %>
<%@ page import="java.util.List" %>
<%@ page import="java.util.Locale" %>
<%@ page import="java.util.Map" %>

<%@ page import="net.familylawandprobate.controversies.contacts" %>
<%@ page import="net.familylawandprobate.controversies.document_parts" %>
<%@ page import="net.familylawandprobate.controversies.documents" %>
<%@ page import="net.familylawandprobate.controversies.matters" %>
<%@ page import="net.familylawandprobate.controversies.part_versions" %>
<%@ page import="net.familylawandprobate.controversies.postal_mail" %>
<%@ page import="net.familylawandprobate.controversies.users_roles" %>

<%@ include file="security.jspf" %>
<%
  if (!require_login()) return;
%>

<%!
  private static final String S_TENANT_UUID = "tenant.uuid";
  private static final String S_USER_UUID = "user.uuid";
  private static final String CSRF_SESSION_KEY = "CSRF_TOKEN";

  private static final String[] WORKFLOW_OPTIONS = new String[] {
    postal_mail.WORKFLOW_MANUAL,
    postal_mail.WORKFLOW_CLICK2MAIL,
    postal_mail.WORKFLOW_FEDEX,
    postal_mail.WORKFLOW_EMAIL_INBOX,
    postal_mail.WORKFLOW_MANUAL_SCAN
  };

  private static final String[] SERVICE_OPTIONS = new String[] {
    postal_mail.SERVICE_USPS,
    postal_mail.SERVICE_FEDEX,
    postal_mail.SERVICE_UPS,
    postal_mail.SERVICE_COURIER,
    postal_mail.SERVICE_OTHER
  };

  private static final String[] STATUS_OPTIONS = new String[] {
    postal_mail.STATUS_RECEIVED,
    postal_mail.STATUS_REVIEW_PENDING,
    postal_mail.STATUS_REVIEWED,
    postal_mail.STATUS_UPLOADED,
    postal_mail.STATUS_READY_TO_SEND,
    postal_mail.STATUS_QUEUED,
    postal_mail.STATUS_SENT,
    postal_mail.STATUS_IN_TRANSIT,
    postal_mail.STATUS_DELIVERED,
    postal_mail.STATUS_FAILED,
    postal_mail.STATUS_CANCELLED
  };

  private static final String[] PART_TYPE_OPTIONS = new String[] {
    postal_mail.PART_ENVELOPE,
    postal_mail.PART_LETTER,
    postal_mail.PART_ATTACHMENT,
    postal_mail.PART_RECEIPT,
    postal_mail.PART_PROOF,
    postal_mail.PART_TRACKING,
    postal_mail.PART_LABEL,
    postal_mail.PART_OTHER
  };

  private static final String[] CARRIER_OPTIONS = new String[] {
    postal_mail.SERVICE_USPS,
    postal_mail.SERVICE_FEDEX,
    postal_mail.SERVICE_UPS,
    postal_mail.SERVICE_COURIER
  };

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

  private static boolean boolLike(String raw) {
    String v = safe(raw).trim().toLowerCase(Locale.ROOT);
    return "true".equals(v) || "1".equals(v) || "yes".equals(v) || "on".equals(v) || "y".equals(v);
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

  private static String shortErr(Throwable ex) {
    if (ex == null) return "";
    String m = safe(ex.getMessage()).trim();
    return m.isBlank() ? ex.getClass().getSimpleName() : m;
  }

  private static void logWarn(jakarta.servlet.ServletContext app, String message, Throwable ex) {
    if (app == null) return;
    if (ex == null) app.log("[mail] " + safe(message));
    else app.log("[mail] " + safe(message), ex);
  }

  private static String normalizeShowFilter(String raw) {
    String v = safe(raw).trim().toLowerCase(Locale.ROOT);
    if ("all".equals(v) || "archived".equals(v)) return v;
    return "active";
  }

  private static String normalizeDirectionFilter(String raw) {
    String v = safe(raw).trim().toLowerCase(Locale.ROOT);
    if (postal_mail.DIRECTION_INBOUND.equals(v) || postal_mail.DIRECTION_OUTBOUND.equals(v)) return v;
    return "all";
  }

  private static boolean tokenInList(String value, String[] options) {
    String target = safe(value).trim().toLowerCase(Locale.ROOT);
    if (target.isBlank() || options == null) return false;
    for (int i = 0; i < options.length; i++) {
      if (target.equals(safe(options[i]).trim().toLowerCase(Locale.ROOT))) return true;
    }
    return false;
  }

  private static String tokenForSelect(String rawValue, String rawCustom) {
    String value = safe(rawValue).trim();
    String custom = safe(rawCustom).trim();
    if ("__custom__".equals(value)) return custom;
    if (value.isBlank() && !custom.isBlank()) return custom;
    return value;
  }

  private static String selectValueOrCustom(String current, String[] options) {
    String v = safe(current).trim().toLowerCase(Locale.ROOT);
    if (v.isBlank()) return "";
    return tokenInList(v, options) ? v : "__custom__";
  }

  private static String customValueIfNeeded(String current, String[] options) {
    String v = safe(current).trim().toLowerCase(Locale.ROOT);
    if (v.isBlank()) return "";
    return tokenInList(v, options) ? "" : v;
  }

  private static String humanToken(String raw) {
    String v = safe(raw).trim();
    if (v.isBlank()) return "";
    String spaced = v.replace('_', ' ').replace('-', ' ').trim();
    String[] parts = spaced.split("\\s+");
    StringBuilder out = new StringBuilder(spaced.length() + 4);
    for (int i = 0; i < parts.length; i++) {
      String p = safe(parts[i]).trim();
      if (p.isBlank()) continue;
      if (out.length() > 0) out.append(' ');
      if (p.length() == 1) {
        out.append(p.toUpperCase(Locale.ROOT));
      } else {
        out.append(p.substring(0, 1).toUpperCase(Locale.ROOT));
        out.append(p.substring(1).toLowerCase(Locale.ROOT));
      }
    }
    return out.toString();
  }

  private static String firstNonBlank(String... values) {
    if (values == null) return "";
    for (int i = 0; i < values.length; i++) {
      String v = safe(values[i]).trim();
      if (!v.isBlank()) return v;
    }
    return "";
  }

  private static String contactDisplayName(contacts.ContactRec c) {
    if (c == null) return "";
    String fullName = (safe(c.givenName).trim() + " " + safe(c.surname).trim()).trim();
    return firstNonBlank(c.displayName, fullName, c.companyName);
  }

  private static String contactSummary(contacts.ContactRec c) {
    if (c == null) return "";
    String email = firstNonBlank(c.emailPrimary, c.emailSecondary, c.emailTertiary);
    String phone = firstNonBlank(c.mobilePhone, c.businessPhone, c.businessPhone2, c.homePhone, c.otherPhone);
    if (!email.isBlank() && !phone.isBlank()) return email + " | " + phone;
    if (!email.isBlank()) return email;
    if (!phone.isBlank()) return phone;
    return "";
  }

  private static postal_mail.RecipientRec recipientFromRequest(jakarta.servlet.http.HttpServletRequest req) {
    postal_mail.RecipientRec out = new postal_mail.RecipientRec();
    out.contactUuid = safe(req.getParameter("recipient_contact_uuid")).trim();
    out.displayName = safe(req.getParameter("recipient_display_name")).trim();
    out.companyName = safe(req.getParameter("recipient_company_name")).trim();
    out.addressLine1 = safe(req.getParameter("recipient_address_line_1")).trim();
    out.addressLine2 = safe(req.getParameter("recipient_address_line_2")).trim();
    out.city = safe(req.getParameter("recipient_city")).trim();
    out.state = safe(req.getParameter("recipient_state")).trim();
    out.postalCode = safe(req.getParameter("recipient_postal_code")).trim();
    out.country = safe(req.getParameter("recipient_country")).trim();
    out.emailAddress = safe(req.getParameter("recipient_email_address")).trim();
    out.phone = safe(req.getParameter("recipient_phone")).trim();
    return out;
  }

  private static void applyContactDefaults(postal_mail.RecipientRec recipient, contacts.ContactRec c) {
    if (recipient == null || c == null) return;
    String display = contactDisplayName(c);
    if (safe(recipient.displayName).trim().isBlank()) recipient.displayName = display;
    if (safe(recipient.companyName).trim().isBlank()) recipient.companyName = safe(c.companyName).trim();
    if (safe(recipient.addressLine1).trim().isBlank()) recipient.addressLine1 = safe(c.street).trim();
    if (safe(recipient.addressLine2).trim().isBlank()) recipient.addressLine2 = safe(c.streetSecondary).trim();
    if (safe(recipient.city).trim().isBlank()) recipient.city = safe(c.city).trim();
    if (safe(recipient.state).trim().isBlank()) recipient.state = safe(c.state).trim();
    if (safe(recipient.postalCode).trim().isBlank()) recipient.postalCode = safe(c.postalCode).trim();
    if (safe(recipient.country).trim().isBlank()) recipient.country = safe(c.country).trim();
    if (safe(recipient.emailAddress).trim().isBlank()) {
      recipient.emailAddress = firstNonBlank(c.emailPrimary, c.emailSecondary, c.emailTertiary);
    }
    if (safe(recipient.phone).trim().isBlank()) {
      recipient.phone = firstNonBlank(c.mobilePhone, c.businessPhone, c.businessPhone2, c.homePhone, c.otherPhone);
    }
  }

  private static String hiddenInput(String name, String value) {
    return "<input type=\"hidden\" name=\"" + esc(name) + "\" value=\"" + esc(value) + "\" />";
  }

  private static String hiddenStateFields(String matterUuid,
                                          String directionFilter,
                                          String statusFilter,
                                          String show,
                                          String q,
                                          String mailUuid) {
    StringBuilder sb = new StringBuilder(256);
    sb.append(hiddenInput("matter_uuid", matterUuid));
    sb.append(hiddenInput("direction_filter", directionFilter));
    sb.append(hiddenInput("status_filter", statusFilter));
    sb.append(hiddenInput("show", show));
    sb.append(hiddenInput("q", q));
    sb.append(hiddenInput("mail_uuid", mailUuid));
    return sb.toString();
  }

  private static String buildStateQuery(String matterUuid,
                                        String directionFilter,
                                        String statusFilter,
                                        String show,
                                        String q,
                                        String mailUuid) {
    ArrayList<String> parts = new ArrayList<String>();
    if (!safe(matterUuid).trim().isBlank()) parts.add("matter_uuid=" + enc(matterUuid));
    if (!safe(directionFilter).trim().isBlank() && !"all".equalsIgnoreCase(directionFilter)) {
      parts.add("direction_filter=" + enc(directionFilter));
    }
    if (!safe(statusFilter).trim().isBlank() && !"all".equalsIgnoreCase(statusFilter)) {
      parts.add("status_filter=" + enc(statusFilter));
    }
    if (!safe(show).trim().isBlank() && !"active".equalsIgnoreCase(show)) {
      parts.add("show=" + enc(show));
    }
    if (!safe(q).trim().isBlank()) parts.add("q=" + enc(q));
    if (!safe(mailUuid).trim().isBlank()) parts.add("mail_uuid=" + enc(mailUuid));
    return String.join("&", parts);
  }

  private static String appendQueryParam(String query, String key, String value) {
    String q = safe(query).trim();
    String k = safe(key).trim();
    if (k.isBlank()) return q;
    String pair = enc(k) + "=" + enc(value);
    return q.isBlank() ? pair : (q + "&" + pair);
  }

  private static String matterLabel(String matterUuid, Map<String, String> matterLabelByUuid) {
    String id = safe(matterUuid).trim();
    String label = matterLabelByUuid == null ? "" : safe(matterLabelByUuid.get(id)).trim();
    return label.isBlank() ? id : label;
  }

  private static String refLabel(String documentUuid,
                                 String partUuid,
                                 String versionUuid,
                                 Map<String, String> docLabelByUuid,
                                 Map<String, String> partLabelByUuid,
                                 Map<String, String> versionLabelByUuid) {
    String d = safe(documentUuid).trim();
    String p = safe(partUuid).trim();
    String v = safe(versionUuid).trim();
    String dl = d.isBlank() ? "" : safe(docLabelByUuid == null ? "" : docLabelByUuid.get(d)).trim();
    String pl = p.isBlank() ? "" : safe(partLabelByUuid == null ? "" : partLabelByUuid.get(p)).trim();
    String vl = v.isBlank() ? "" : safe(versionLabelByUuid == null ? "" : versionLabelByUuid.get(v)).trim();

    ArrayList<String> bits = new ArrayList<String>();
    if (!d.isBlank()) bits.add(dl.isBlank() ? d : dl);
    if (!p.isBlank()) bits.add(pl.isBlank() ? p : pl);
    if (!v.isBlank()) bits.add(vl.isBlank() ? v : vl);
    return bits.isEmpty() ? "" : String.join(" :: ", bits);
  }

  private static String nonBlank(String value, String fallback) {
    String v = safe(value).trim();
    return v.isBlank() ? safe(fallback).trim() : v;
  }

  private static boolean equalsToken(String a, String b) {
    return safe(a).trim().equalsIgnoreCase(safe(b).trim());
  }
%>

<%
  String ctx = safe(request.getContextPath());
  String tenantUuid = safe((String) session.getAttribute(S_TENANT_UUID)).trim();
  if (tenantUuid.isBlank()) {
    response.sendRedirect(ctx + "/tenant_login.jsp");
    return;
  }

  String userUuid = safe((String) session.getAttribute(S_USER_UUID)).trim();
  String userEmail = safe((String) session.getAttribute(users_roles.S_USER_EMAIL)).trim();
  String actor = firstNonBlank(userEmail, userUuid, "unknown");

  postal_mail mailStore = postal_mail.defaultStore();
  matters matterStore = matters.defaultStore();
  contacts contactStore = contacts.defaultStore();
  documents docStore = documents.defaultStore();
  document_parts partStore = document_parts.defaultStore();
  part_versions versionStore = part_versions.defaultStore();

  try { matterStore.ensure(tenantUuid); } catch (Exception ex) { logWarn(application, "Unable to ensure matters: " + shortErr(ex), ex); }
  try { contactStore.ensure(tenantUuid); } catch (Exception ex) { logWarn(application, "Unable to ensure contacts: " + shortErr(ex), ex); }
  try { mailStore.ensure(tenantUuid); } catch (Exception ex) { logWarn(application, "Unable to ensure mail store: " + shortErr(ex), ex); }

  String csrfToken = csrfForRender(request);
  String message = null;
  String error = null;

  postal_mail.AddressValidationResult addressPreview = null;
  postal_mail.TrackingValidationResult trackingPreview = null;

  String matterFilter = safe(request.getParameter("matter_uuid")).trim();
  String directionFilter = normalizeDirectionFilter(request.getParameter("direction_filter"));
  String statusFilter = safe(request.getParameter("status_filter")).trim().toLowerCase(Locale.ROOT);
  if (statusFilter.isBlank()) statusFilter = "all";
  String show = normalizeShowFilter(request.getParameter("show"));
  String q = safe(request.getParameter("q")).trim();
  String selectedMailUuid = safe(request.getParameter("mail_uuid")).trim();

  if ("POST".equalsIgnoreCase(request.getMethod())) {
    String action = safe(request.getParameter("action")).trim();
    try {
      if ("create_mail".equals(action)) {
        String createMatterUuid = safe(request.getParameter("create_matter_uuid")).trim();
        String createDirection = safe(request.getParameter("create_direction")).trim();
        String createWorkflow = tokenForSelect(request.getParameter("create_workflow"), request.getParameter("create_workflow_custom"));
        String createService = tokenForSelect(request.getParameter("create_service"), request.getParameter("create_service_custom"));
        String createStatus = tokenForSelect(request.getParameter("create_status"), request.getParameter("create_status_custom"));

        postal_mail.MailItemRec created = mailStore.createItem(
            tenantUuid,
            createMatterUuid,
            createDirection,
            createWorkflow,
            createService,
            createStatus,
            request.getParameter("create_subject"),
            request.getParameter("create_notes"),
            request.getParameter("create_source_email_address"),
            request.getParameter("create_source_document_uuid"),
            request.getParameter("create_source_part_uuid"),
            request.getParameter("create_source_version_uuid"),
            actor
        );
        matterFilter = nonBlank(matterFilter, createMatterUuid);
        selectedMailUuid = safe(created == null ? "" : created.uuid).trim();
        String qs = buildStateQuery(matterFilter, directionFilter, statusFilter, show, q, selectedMailUuid);
        String qsWithMsg = appendQueryParam(qs, "msg", "created");
        response.sendRedirect(ctx + "/mail.jsp" + (qsWithMsg.isBlank() ? "" : ("?" + qsWithMsg)));
        return;
      }

      if ("save_mail".equals(action)) {
        String mailUuid = safe(request.getParameter("mail_uuid")).trim();
        postal_mail.MailItemRec current = mailStore.getItem(tenantUuid, mailUuid);
        if (current == null) throw new IllegalArgumentException("Mail item not found.");

        current.matterUuid = safe(request.getParameter("edit_matter_uuid")).trim();
        current.direction = safe(request.getParameter("edit_direction")).trim();
        current.workflow = tokenForSelect(request.getParameter("edit_workflow"), request.getParameter("edit_workflow_custom"));
        current.service = tokenForSelect(request.getParameter("edit_service"), request.getParameter("edit_service_custom"));
        current.status = tokenForSelect(request.getParameter("edit_status"), request.getParameter("edit_status_custom"));
        current.subject = safe(request.getParameter("edit_subject"));
        current.notes = safe(request.getParameter("edit_notes"));

        current.sourceEmailAddress = safe(request.getParameter("edit_source_email_address"));
        current.sourceDocumentUuid = safe(request.getParameter("edit_source_document_uuid"));
        current.sourcePartUuid = safe(request.getParameter("edit_source_part_uuid"));
        current.sourceVersionUuid = safe(request.getParameter("edit_source_version_uuid"));

        current.filedDocumentUuid = safe(request.getParameter("edit_filed_document_uuid"));
        current.filedPartUuid = safe(request.getParameter("edit_filed_part_uuid"));
        current.filedVersionUuid = safe(request.getParameter("edit_filed_version_uuid"));

        current.trackingCarrier = tokenForSelect(request.getParameter("edit_tracking_carrier"), request.getParameter("edit_tracking_carrier_custom"));
        current.trackingNumber = safe(request.getParameter("edit_tracking_number"));
        current.trackingStatus = safe(request.getParameter("edit_tracking_status"));

        current.providerReference = safe(request.getParameter("edit_provider_reference"));
        current.providerMessage = safe(request.getParameter("edit_provider_message"));
        current.providerRequestJson = safe(request.getParameter("edit_provider_request_json"));
        current.providerResponseJson = safe(request.getParameter("edit_provider_response_json"));

        current.receivedAt = safe(request.getParameter("edit_received_at"));
        current.sentAt = safe(request.getParameter("edit_sent_at"));

        mailStore.updateItem(tenantUuid, current);
        selectedMailUuid = mailUuid;
        matterFilter = nonBlank(matterFilter, current.matterUuid);
        String qs = buildStateQuery(matterFilter, directionFilter, statusFilter, show, q, selectedMailUuid);
        String qsWithMsg = appendQueryParam(qs, "msg", "saved");
        response.sendRedirect(ctx + "/mail.jsp" + (qsWithMsg.isBlank() ? "" : ("?" + qsWithMsg)));
        return;
      }

      if ("set_archived".equals(action)) {
        String mailUuid = safe(request.getParameter("mail_uuid")).trim();
        boolean archived = boolLike(request.getParameter("archived"));
        mailStore.setArchived(tenantUuid, mailUuid, archived);
        selectedMailUuid = mailUuid;
        String qs = buildStateQuery(matterFilter, directionFilter, statusFilter, show, q, selectedMailUuid);
        String qsWithMsg = appendQueryParam(qs, "msg", archived ? "archived" : "restored");
        response.sendRedirect(ctx + "/mail.jsp" + (qsWithMsg.isBlank() ? "" : ("?" + qsWithMsg)));
        return;
      }

      if ("mark_reviewed".equals(action)) {
        String mailUuid = safe(request.getParameter("mail_uuid")).trim();
        mailStore.markReviewed(tenantUuid, mailUuid, actor, request.getParameter("review_notes"));
        selectedMailUuid = mailUuid;
        String qs = buildStateQuery(matterFilter, directionFilter, statusFilter, show, q, selectedMailUuid);
        String qsWithMsg = appendQueryParam(qs, "msg", "reviewed");
        response.sendRedirect(ctx + "/mail.jsp" + (qsWithMsg.isBlank() ? "" : ("?" + qsWithMsg)));
        return;
      }

      if ("link_filed_document".equals(action)) {
        String mailUuid = safe(request.getParameter("mail_uuid")).trim();
        mailStore.linkFiledDocument(
            tenantUuid,
            mailUuid,
            request.getParameter("filed_document_uuid"),
            request.getParameter("filed_part_uuid"),
            request.getParameter("filed_version_uuid")
        );
        selectedMailUuid = mailUuid;
        String qs = buildStateQuery(matterFilter, directionFilter, statusFilter, show, q, selectedMailUuid);
        String qsWithMsg = appendQueryParam(qs, "msg", "filed_linked");
        response.sendRedirect(ctx + "/mail.jsp" + (qsWithMsg.isBlank() ? "" : ("?" + qsWithMsg)));
        return;
      }

      if ("add_part".equals(action)) {
        String mailUuid = safe(request.getParameter("mail_uuid")).trim();
        String partType = tokenForSelect(request.getParameter("part_type"), request.getParameter("part_type_custom"));
        mailStore.addPart(
            tenantUuid,
            mailUuid,
            partType,
            request.getParameter("part_label"),
            request.getParameter("part_document_uuid"),
            request.getParameter("part_part_uuid"),
            request.getParameter("part_version_uuid"),
            request.getParameter("part_notes"),
            actor
        );
        selectedMailUuid = mailUuid;
        String qs = buildStateQuery(matterFilter, directionFilter, statusFilter, show, q, selectedMailUuid);
        String qsWithMsg = appendQueryParam(qs, "msg", "part_added");
        response.sendRedirect(ctx + "/mail.jsp" + (qsWithMsg.isBlank() ? "" : ("?" + qsWithMsg)));
        return;
      }

      if ("set_part_trashed".equals(action)) {
        String mailUuid = safe(request.getParameter("mail_uuid")).trim();
        String partUuid = safe(request.getParameter("part_uuid")).trim();
        boolean trashed = boolLike(request.getParameter("trashed"));
        mailStore.setPartTrashed(tenantUuid, mailUuid, partUuid, trashed);
        selectedMailUuid = mailUuid;
        String qs = buildStateQuery(matterFilter, directionFilter, statusFilter, show, q, selectedMailUuid);
        String qsWithMsg = appendQueryParam(qs, "msg", trashed ? "part_trashed" : "part_restored");
        response.sendRedirect(ctx + "/mail.jsp" + (qsWithMsg.isBlank() ? "" : ("?" + qsWithMsg)));
        return;
      }

      if ("add_recipient".equals(action)) {
        String mailUuid = safe(request.getParameter("mail_uuid")).trim();
        postal_mail.RecipientRec recipientInput = recipientFromRequest(request);
        String contactUuid = safe(recipientInput.contactUuid).trim();
        if (!contactUuid.isBlank()) {
          contacts.ContactRec contact = contactStore.getByUuid(tenantUuid, contactUuid);
          if (contact != null) applyContactDefaults(recipientInput, contact);
        }

        boolean allowInvalidAddress = boolLike(request.getParameter("allow_invalid_address"));
        mailStore.addRecipient(tenantUuid, mailUuid, recipientInput, allowInvalidAddress);
        selectedMailUuid = mailUuid;
        String qs = buildStateQuery(matterFilter, directionFilter, statusFilter, show, q, selectedMailUuid);
        String qsWithMsg = appendQueryParam(qs, "msg", "recipient_added");
        response.sendRedirect(ctx + "/mail.jsp" + (qsWithMsg.isBlank() ? "" : ("?" + qsWithMsg)));
        return;
      }

      if ("validate_address".equals(action)) {
        postal_mail.RecipientRec recipientInput = recipientFromRequest(request);
        String contactUuid = safe(recipientInput.contactUuid).trim();
        if (!contactUuid.isBlank()) {
          contacts.ContactRec contact = contactStore.getByUuid(tenantUuid, contactUuid);
          if (contact != null) applyContactDefaults(recipientInput, contact);
        }

        postal_mail.AddressInput input = new postal_mail.AddressInput();
        input.displayName = safe(recipientInput.displayName);
        input.companyName = safe(recipientInput.companyName);
        input.addressLine1 = safe(recipientInput.addressLine1);
        input.addressLine2 = safe(recipientInput.addressLine2);
        input.city = safe(recipientInput.city);
        input.state = safe(recipientInput.state);
        input.postalCode = safe(recipientInput.postalCode);
        input.country = safe(recipientInput.country);
        input.emailAddress = safe(recipientInput.emailAddress);
        input.phone = safe(recipientInput.phone);

        addressPreview = mailStore.validateAddress(input);
        message = safe(addressPreview.message);
      }

      if ("update_tracking_summary".equals(action)) {
        String mailUuid = safe(request.getParameter("mail_uuid")).trim();
        String carrier = tokenForSelect(request.getParameter("summary_carrier"), request.getParameter("summary_carrier_custom"));
        boolean markSentIfMissing = boolLike(request.getParameter("mark_sent_if_missing"));
        mailStore.updateTrackingSummary(
            tenantUuid,
            mailUuid,
            carrier,
            request.getParameter("summary_tracking_number"),
            request.getParameter("summary_tracking_status"),
            markSentIfMissing
        );
        selectedMailUuid = mailUuid;
        String qs = buildStateQuery(matterFilter, directionFilter, statusFilter, show, q, selectedMailUuid);
        String qsWithMsg = appendQueryParam(qs, "msg", "tracking_updated");
        response.sendRedirect(ctx + "/mail.jsp" + (qsWithMsg.isBlank() ? "" : ("?" + qsWithMsg)));
        return;
      }

      if ("add_tracking_event".equals(action)) {
        String mailUuid = safe(request.getParameter("mail_uuid")).trim();
        String carrier = tokenForSelect(request.getParameter("event_carrier"), request.getParameter("event_carrier_custom"));
        mailStore.addTrackingEvent(
            tenantUuid,
            mailUuid,
            carrier,
            request.getParameter("event_tracking_number"),
            request.getParameter("event_status"),
            request.getParameter("event_location"),
            request.getParameter("event_at"),
            request.getParameter("event_notes"),
            request.getParameter("event_source")
        );
        selectedMailUuid = mailUuid;
        String qs = buildStateQuery(matterFilter, directionFilter, statusFilter, show, q, selectedMailUuid);
        String qsWithMsg = appendQueryParam(qs, "msg", "tracking_event_added");
        response.sendRedirect(ctx + "/mail.jsp" + (qsWithMsg.isBlank() ? "" : ("?" + qsWithMsg)));
        return;
      }

      if ("validate_tracking".equals(action)) {
        trackingPreview = postal_mail.validateTrackingNumber(request.getParameter("tracking_number_to_validate"));
        message = safe(trackingPreview.message);
      }
    } catch (Exception ex) {
      error = "Unable to complete action: " + safe(ex.getMessage());
      logWarn(application, "Action failed (" + action + "): " + shortErr(ex), ex);
    }
  }

  if (message == null) {
    String msg = safe(request.getParameter("msg")).trim().toLowerCase(Locale.ROOT);
    if ("created".equals(msg)) message = "Mail item created.";
    else if ("saved".equals(msg)) message = "Mail item updated.";
    else if ("archived".equals(msg)) message = "Mail item archived.";
    else if ("restored".equals(msg)) message = "Mail item restored.";
    else if ("reviewed".equals(msg)) message = "Inbound mail marked as reviewed.";
    else if ("filed_linked".equals(msg)) message = "Filed document link saved.";
    else if ("part_added".equals(msg)) message = "Mail part added.";
    else if ("part_trashed".equals(msg)) message = "Mail part moved to trash.";
    else if ("part_restored".equals(msg)) message = "Mail part restored.";
    else if ("recipient_added".equals(msg)) message = "Recipient added.";
    else if ("tracking_updated".equals(msg)) message = "Tracking summary updated.";
    else if ("tracking_event_added".equals(msg)) message = "Tracking event added.";
  }

  List<matters.MatterRec> mattersAll = new ArrayList<matters.MatterRec>();
  List<matters.MatterRec> mattersActive = new ArrayList<matters.MatterRec>();
  LinkedHashMap<String, String> matterLabelByUuid = new LinkedHashMap<String, String>();
  try {
    mattersAll = matterStore.listAll(tenantUuid);
    for (int i = 0; i < mattersAll.size(); i++) {
      matters.MatterRec m = mattersAll.get(i);
      if (m == null) continue;
      matterLabelByUuid.put(safe(m.uuid), safe(m.label));
      if (!m.trashed) mattersActive.add(m);
    }
  } catch (Exception ex) {
    logWarn(application, "Unable to load matters: " + shortErr(ex), ex);
    if (error == null) error = "Unable to load matters.";
  }

  String defaultMatterUuid = matterFilter;
  if (defaultMatterUuid.isBlank() && !mattersActive.isEmpty()) {
    defaultMatterUuid = safe(mattersActive.get(0).uuid).trim();
  }

  List<contacts.ContactRec> contactsAll = new ArrayList<contacts.ContactRec>();
  List<contacts.ContactRec> contactsActive = new ArrayList<contacts.ContactRec>();
  LinkedHashMap<String, contacts.ContactRec> contactByUuid = new LinkedHashMap<String, contacts.ContactRec>();
  try {
    contactsAll = contactStore.listAll(tenantUuid);
    for (int i = 0; i < contactsAll.size(); i++) {
      contacts.ContactRec c = contactsAll.get(i);
      if (c == null) continue;
      String id = safe(c.uuid).trim();
      if (id.isBlank()) continue;
      contactByUuid.put(id, c);
      if (!c.trashed) contactsActive.add(c);
    }
  } catch (Exception ex) {
    logWarn(application, "Unable to load contacts: " + shortErr(ex), ex);
    if (error == null) error = "Unable to load contacts.";
  }

  List<postal_mail.MailItemRec> allMail = new ArrayList<postal_mail.MailItemRec>();
  List<postal_mail.MailItemRec> filteredMail = new ArrayList<postal_mail.MailItemRec>();
  try {
    allMail = mailStore.listItems(tenantUuid);
  } catch (Exception ex) {
    logWarn(application, "Unable to list mail: " + shortErr(ex), ex);
    if (error == null) error = "Unable to list mail items.";
  }

  String ql = q.toLowerCase(Locale.ROOT);
  for (int i = 0; i < allMail.size(); i++) {
    postal_mail.MailItemRec row = allMail.get(i);
    if (row == null) continue;

    if (!matterFilter.isBlank() && !safe(row.matterUuid).trim().equals(matterFilter)) continue;
    if (!"all".equals(directionFilter) && !equalsToken(directionFilter, row.direction)) continue;
    if (!"all".equals(statusFilter) && !equalsToken(statusFilter, row.status)) continue;

    if ("active".equals(show) && row.archived) continue;
    if ("archived".equals(show) && !row.archived) continue;

    if (!ql.isBlank()) {
      String hay = (
          safe(row.subject) + " " +
          safe(row.notes) + " " +
          safe(row.direction) + " " +
          safe(row.workflow) + " " +
          safe(row.service) + " " +
          safe(row.status) + " " +
          safe(row.sourceEmailAddress) + " " +
          safe(row.providerReference) + " " +
          safe(row.providerMessage) + " " +
          safe(row.trackingCarrier) + " " +
          safe(row.trackingNumber) + " " +
          safe(row.trackingStatus)
      ).toLowerCase(Locale.ROOT);
      if (!hay.contains(ql)) continue;
    }

    filteredMail.add(row);
  }

  postal_mail.MailItemRec selectedMail = null;
  if (!selectedMailUuid.isBlank()) {
    for (int i = 0; i < allMail.size(); i++) {
      postal_mail.MailItemRec row = allMail.get(i);
      if (row == null) continue;
      if (selectedMailUuid.equals(safe(row.uuid).trim())) {
        selectedMail = row;
        break;
      }
    }
  }
  if (selectedMail == null && !filteredMail.isEmpty()) {
    selectedMail = filteredMail.get(0);
    selectedMailUuid = safe(selectedMail.uuid).trim();
  }

  List<postal_mail.MailPartRec> selectedParts = new ArrayList<postal_mail.MailPartRec>();
  List<postal_mail.RecipientRec> selectedRecipients = new ArrayList<postal_mail.RecipientRec>();
  List<postal_mail.TrackingEventRec> selectedTrackingEvents = new ArrayList<postal_mail.TrackingEventRec>();

  if (selectedMail != null) {
    try { selectedParts = mailStore.listParts(tenantUuid, selectedMail.uuid); } catch (Exception ex) { logWarn(application, "Unable to list parts: " + shortErr(ex), ex); }
    try { selectedRecipients = mailStore.listRecipients(tenantUuid, selectedMail.uuid); } catch (Exception ex) { logWarn(application, "Unable to list recipients: " + shortErr(ex), ex); }
    try { selectedTrackingEvents = mailStore.listTrackingEvents(tenantUuid, selectedMail.uuid); } catch (Exception ex) { logWarn(application, "Unable to list tracking: " + shortErr(ex), ex); }
  }

  String linksMatterUuid = selectedMail != null ? safe(selectedMail.matterUuid).trim() : defaultMatterUuid;
  List<documents.DocumentRec> linkDocs = new ArrayList<documents.DocumentRec>();
  List<document_parts.PartRec> linkParts = new ArrayList<document_parts.PartRec>();
  List<part_versions.VersionRec> linkVersions = new ArrayList<part_versions.VersionRec>();

  LinkedHashMap<String, String> docLabelByUuid = new LinkedHashMap<String, String>();
  LinkedHashMap<String, String> partLabelByUuid = new LinkedHashMap<String, String>();
  LinkedHashMap<String, String> partDocByUuid = new LinkedHashMap<String, String>();
  LinkedHashMap<String, String> versionLabelByUuid = new LinkedHashMap<String, String>();
  LinkedHashMap<String, String> versionPartByUuid = new LinkedHashMap<String, String>();
  LinkedHashMap<String, String> versionDocByUuid = new LinkedHashMap<String, String>();

  if (!linksMatterUuid.isBlank()) {
    try {
      docStore.ensure(tenantUuid, linksMatterUuid);
      linkDocs = docStore.listAll(tenantUuid, linksMatterUuid);
      for (int di = 0; di < linkDocs.size(); di++) {
        documents.DocumentRec d = linkDocs.get(di);
        if (d == null) continue;
        String docUuid = safe(d.uuid).trim();
        if (docUuid.isBlank()) continue;
        docLabelByUuid.put(docUuid, safe(d.title));

        List<document_parts.PartRec> parts = partStore.listAll(tenantUuid, linksMatterUuid, docUuid);
        for (int pi = 0; pi < parts.size(); pi++) {
          document_parts.PartRec p = parts.get(pi);
          if (p == null || p.trashed) continue;
          String partUuid = safe(p.uuid).trim();
          if (partUuid.isBlank()) continue;
          linkParts.add(p);
          partLabelByUuid.put(partUuid, safe(p.label));
          partDocByUuid.put(partUuid, docUuid);

          List<part_versions.VersionRec> versions = versionStore.listAll(tenantUuid, linksMatterUuid, docUuid, partUuid);
          for (int vi = 0; vi < versions.size(); vi++) {
            part_versions.VersionRec v = versions.get(vi);
            if (v == null) continue;
            String versionUuid = safe(v.uuid).trim();
            if (versionUuid.isBlank()) continue;
            linkVersions.add(v);
            versionLabelByUuid.put(versionUuid, safe(v.versionLabel));
            versionPartByUuid.put(versionUuid, partUuid);
            versionDocByUuid.put(versionUuid, docUuid);
          }
        }
      }
    } catch (Exception ex) {
      logWarn(application, "Unable to load linked docs/parts/versions: " + shortErr(ex), ex);
    }
  }

  String baseQs = buildStateQuery(matterFilter, directionFilter, statusFilter, show, q, selectedMailUuid);
%>

<jsp:include page="header.jsp" />

<style>
  .mail-grid-2 {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 10px;
  }
  .mail-grid-3 {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 10px;
  }
  .mail-token-field {
    display: grid;
    grid-template-columns: minmax(0, 1fr) minmax(160px, 220px);
    gap: 8px;
    align-items: end;
  }
  .mail-kpi-grid {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
    gap: 10px;
    margin-top: 10px;
  }
  .mail-kpi {
    border: 1px solid var(--border);
    border-radius: 10px;
    padding: 10px;
    background: var(--surface-2);
  }
  .mail-kpi .k {
    color: var(--muted);
    font-size: .82rem;
  }
  .mail-kpi .v {
    font-weight: 700;
    font-size: 1.12rem;
  }
  .mail-tag {
    display: inline-block;
    padding: 2px 8px;
    border-radius: 999px;
    border: 1px solid var(--border);
    background: var(--surface-2);
    font-size: .78rem;
    line-height: 1.35;
    margin-right: 4px;
    margin-bottom: 4px;
  }
  .mail-stack {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
  }
  @media (max-width: 1080px) {
    .mail-grid-2,
    .mail-grid-3,
    .mail-token-field {
      grid-template-columns: 1fr;
    }
  }
</style>

<section class="card">
  <div class="section-head">
    <div>
      <h1 style="margin:0;">Postal Mail Manager</h1>
      <div class="meta">Inbound and outbound postal/courier workflows with review, filing links, recipients, parts, proof, and tracking.</div>
      <div class="meta" style="margin-top:4px;">Supports manual handling, Click2Mail, FedEx API operations, and custom future carrier/API tokens.</div>
    </div>
  </div>

  <% if (message != null) { %>
    <div class="alert alert-ok" style="margin-top:12px;"><%= esc(message) %></div>
  <% } %>
  <% if (error != null) { %>
    <div class="alert alert-error" style="margin-top:12px;"><%= esc(error) %></div>
  <% } %>

  <div class="mail-kpi-grid">
    <div class="mail-kpi"><div class="k">Visible Items</div><div class="v"><%= filteredMail.size() %></div></div>
    <div class="mail-kpi"><div class="k">Selected Matter</div><div class="v"><%= esc(matterLabel(defaultMatterUuid, matterLabelByUuid)) %></div></div>
    <div class="mail-kpi"><div class="k">Mail Parts (Selected)</div><div class="v"><%= selectedParts.size() %></div></div>
    <div class="mail-kpi"><div class="k">Recipients (Selected)</div><div class="v"><%= selectedRecipients.size() %></div></div>
    <div class="mail-kpi"><div class="k">Tracking Events (Selected)</div><div class="v"><%= selectedTrackingEvents.size() %></div></div>
  </div>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Filters</h2>
  <form class="form" method="get" action="<%= ctx %>/mail.jsp">
    <div class="mail-grid-3">
      <label>
        <span>Matter</span>
        <select name="matter_uuid">
          <option value=""></option>
          <% for (int i = 0; i < mattersAll.size(); i++) {
               matters.MatterRec m = mattersAll.get(i);
               if (m == null) continue;
          %>
            <option value="<%= esc(safe(m.uuid)) %>" <%= safe(m.uuid).equals(matterFilter) ? "selected" : "" %>><%= esc(safe(m.label)) %></option>
          <% } %>
        </select>
      </label>
      <label>
        <span>Direction</span>
        <select name="direction_filter">
          <option value="all" <%= "all".equals(directionFilter) ? "selected" : "" %>>All</option>
          <option value="inbound" <%= "inbound".equals(directionFilter) ? "selected" : "" %>>Inbound</option>
          <option value="outbound" <%= "outbound".equals(directionFilter) ? "selected" : "" %>>Outbound</option>
        </select>
      </label>
      <label>
        <span>Status</span>
        <select name="status_filter">
          <option value="all" <%= "all".equals(statusFilter) ? "selected" : "" %>>All</option>
          <% for (int i = 0; i < STATUS_OPTIONS.length; i++) {
               String token = STATUS_OPTIONS[i];
          %>
            <option value="<%= esc(token) %>" <%= token.equalsIgnoreCase(statusFilter) ? "selected" : "" %>><%= esc(humanToken(token)) %></option>
          <% } %>
        </select>
      </label>
      <label>
        <span>View</span>
        <select name="show">
          <option value="active" <%= "active".equals(show) ? "selected" : "" %>>Active</option>
          <option value="all" <%= "all".equals(show) ? "selected" : "" %>>Active + Archived</option>
          <option value="archived" <%= "archived".equals(show) ? "selected" : "" %>>Archived Only</option>
        </select>
      </label>
      <label>
        <span>Search</span>
        <input type="text" name="q" value="<%= esc(q) %>" placeholder="Subject, notes, provider, tracking" />
      </label>
      <label>
        <span>&nbsp;</span>
        <button class="btn" type="submit">Apply Filters</button>
      </label>
    </div>
  </form>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Create Mail Item</h2>
  <form class="form" method="post" action="<%= ctx %>/mail.jsp">
    <input type="hidden" name="action" value="create_mail" />
    <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
    <%= hiddenStateFields(matterFilter, directionFilter, statusFilter, show, q, selectedMailUuid) %>

    <div class="mail-grid-3">
      <label>
        <span>Matter</span>
        <select name="create_matter_uuid" required>
          <option value=""></option>
          <% for (int i = 0; i < mattersActive.size(); i++) {
               matters.MatterRec m = mattersActive.get(i);
               if (m == null) continue;
               String id = safe(m.uuid).trim();
          %>
            <option value="<%= esc(id) %>" <%= id.equals(defaultMatterUuid) ? "selected" : "" %>><%= esc(safe(m.label)) %></option>
          <% } %>
        </select>
      </label>

      <label>
        <span>Direction</span>
        <select name="create_direction">
          <option value="inbound">Inbound</option>
          <option value="outbound">Outbound</option>
        </select>
      </label>

      <label>
        <span>Source Email (for inbound email inbox)</span>
        <input type="email" name="create_source_email_address" placeholder="mailroom@firm.example" />
      </label>
    </div>

    <div class="mail-grid-3">
      <label>
        <span>Workflow</span>
        <div class="mail-token-field">
          <select name="create_workflow">
            <option value="manual" selected>Manual</option>
            <option value="click2mail">Click2Mail</option>
            <option value="fedex">FedEx API</option>
            <option value="email_inbox">Email Inbox Intake</option>
            <option value="manual_scan">Manual Scan Intake</option>
            <option value="__custom__">Custom</option>
          </select>
          <input type="text" name="create_workflow_custom" placeholder="custom workflow" />
        </div>
      </label>

      <label>
        <span>Service</span>
        <div class="mail-token-field">
          <select name="create_service">
            <option value="usps">USPS</option>
            <option value="fedex">FedEx</option>
            <option value="ups">UPS</option>
            <option value="courier">Courier</option>
            <option value="other" selected>Other</option>
            <option value="__custom__">Custom</option>
          </select>
          <input type="text" name="create_service_custom" placeholder="custom service" />
        </div>
      </label>

      <label>
        <span>Status</span>
        <div class="mail-token-field">
          <select name="create_status">
            <option value="received">Received</option>
            <option value="review_pending">Review Pending</option>
            <option value="ready_to_send" selected>Ready To Send</option>
            <option value="queued">Queued</option>
            <option value="sent">Sent</option>
            <option value="in_transit">In Transit</option>
            <option value="delivered">Delivered</option>
            <option value="failed">Failed</option>
            <option value="cancelled">Cancelled</option>
            <option value="__custom__">Custom</option>
          </select>
          <input type="text" name="create_status_custom" placeholder="custom status" />
        </div>
      </label>
    </div>

    <div class="mail-grid-2">
      <label>
        <span>Subject</span>
        <input type="text" name="create_subject" placeholder="Certified letter intake" required />
      </label>
      <label>
        <span>Source Document UUID (optional)</span>
        <input type="text" name="create_source_document_uuid" placeholder="document uuid" />
      </label>
      <label>
        <span>Source Part UUID (optional)</span>
        <input type="text" name="create_source_part_uuid" placeholder="part uuid" />
      </label>
      <label>
        <span>Source Version UUID (optional)</span>
        <input type="text" name="create_source_version_uuid" placeholder="version uuid" />
      </label>
    </div>

    <label>
      <span>Notes</span>
      <textarea name="create_notes" rows="3" placeholder="Mailroom notes, provider details, or handling instructions"></textarea>
    </label>

    <button class="btn" type="submit" style="margin-top:10px;">Create Mail Item</button>
  </form>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Mail Items</h2>
  <div class="table-wrap">
    <table class="table">
      <thead>
        <tr>
          <th>Subject</th>
          <th>Matter</th>
          <th>Direction / Workflow / Service</th>
          <th>Status</th>
          <th>Tracking</th>
          <th>Updated</th>
          <th style="width:220px;">Actions</th>
        </tr>
      </thead>
      <tbody>
      <% if (filteredMail.isEmpty()) { %>
        <tr><td colspan="7" class="muted">No mail items match your filters.</td></tr>
      <% } else {
           for (int i = 0; i < filteredMail.size(); i++) {
             postal_mail.MailItemRec row = filteredMail.get(i);
             if (row == null) continue;
             String rowId = safe(row.uuid).trim();
             String rowQs = buildStateQuery(matterFilter, directionFilter, statusFilter, show, q, rowId);
      %>
        <tr>
          <td>
            <strong><%= esc(safe(row.subject)) %></strong>
            <% if (!safe(row.sourceEmailAddress).trim().isBlank()) { %>
              <div class="meta" style="margin-top:4px;">Source email: <%= esc(safe(row.sourceEmailAddress)) %></div>
            <% } %>
            <% if (row.archived) { %>
              <div class="meta" style="margin-top:4px;">Archived</div>
            <% } %>
          </td>
          <td><%= esc(matterLabel(row.matterUuid, matterLabelByUuid)) %></td>
          <td>
            <span class="mail-tag"><%= esc(humanToken(row.direction)) %></span>
            <span class="mail-tag"><%= esc(humanToken(row.workflow)) %></span>
            <span class="mail-tag"><%= esc(humanToken(row.service)) %></span>
          </td>
          <td><span class="mail-tag"><%= esc(humanToken(row.status)) %></span></td>
          <td>
            <div><%= esc(humanToken(row.trackingCarrier)) %></div>
            <div class="meta"><%= esc(safe(row.trackingNumber)) %></div>
            <div class="meta"><%= esc(safe(row.trackingStatus)) %></div>
          </td>
          <td><%= esc(safe(row.updatedAt)) %></td>
          <td>
            <a class="btn btn-ghost" href="<%= ctx %>/mail.jsp?<%= rowQs %>">Open</a>
            <form method="post" action="<%= ctx %>/mail.jsp" style="display:inline;">
              <input type="hidden" name="action" value="set_archived" />
              <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
              <input type="hidden" name="mail_uuid" value="<%= esc(rowId) %>" />
              <input type="hidden" name="archived" value="<%= row.archived ? "false" : "true" %>" />
              <%= hiddenStateFields(matterFilter, directionFilter, statusFilter, show, q, rowId) %>
              <button class="btn btn-ghost" type="submit"><%= row.archived ? "Restore" : "Archive" %></button>
            </form>
          </td>
        </tr>
      <%   }
         }
      %>
      </tbody>
    </table>
  </div>
</section>

<% if (selectedMail != null) {
     String workflowSelect = selectValueOrCustom(selectedMail.workflow, WORKFLOW_OPTIONS);
     String workflowCustom = customValueIfNeeded(selectedMail.workflow, WORKFLOW_OPTIONS);
     String serviceSelect = selectValueOrCustom(selectedMail.service, SERVICE_OPTIONS);
     String serviceCustom = customValueIfNeeded(selectedMail.service, SERVICE_OPTIONS);
     String statusSelect = selectValueOrCustom(selectedMail.status, STATUS_OPTIONS);
     String statusCustom = customValueIfNeeded(selectedMail.status, STATUS_OPTIONS);
     String editCarrierSelect = selectValueOrCustom(selectedMail.trackingCarrier, CARRIER_OPTIONS);
     String editCarrierCustom = customValueIfNeeded(selectedMail.trackingCarrier, CARRIER_OPTIONS);
%>
<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Selected Mail Item</h2>
  <div class="meta" style="margin-bottom:10px;">UUID: <%= esc(safe(selectedMail.uuid)) %></div>

  <form class="form" method="post" action="<%= ctx %>/mail.jsp">
    <input type="hidden" name="action" value="save_mail" />
    <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
    <input type="hidden" name="mail_uuid" value="<%= esc(safe(selectedMail.uuid)) %>" />
    <%= hiddenStateFields(matterFilter, directionFilter, statusFilter, show, q, safe(selectedMail.uuid)) %>

    <div class="mail-grid-3">
      <label>
        <span>Matter</span>
        <select name="edit_matter_uuid">
          <% for (int i = 0; i < mattersAll.size(); i++) {
               matters.MatterRec m = mattersAll.get(i);
               if (m == null) continue;
               String id = safe(m.uuid).trim();
          %>
            <option value="<%= esc(id) %>" <%= id.equals(safe(selectedMail.matterUuid).trim()) ? "selected" : "" %>><%= esc(safe(m.label)) %></option>
          <% } %>
        </select>
      </label>

      <label>
        <span>Direction</span>
        <select name="edit_direction">
          <option value="inbound" <%= "inbound".equalsIgnoreCase(safe(selectedMail.direction)) ? "selected" : "" %>>Inbound</option>
          <option value="outbound" <%= "outbound".equalsIgnoreCase(safe(selectedMail.direction)) ? "selected" : "" %>>Outbound</option>
        </select>
      </label>

      <label>
        <span>Tracking Number</span>
        <input type="text" name="edit_tracking_number" value="<%= esc(safe(selectedMail.trackingNumber)) %>" />
      </label>
    </div>

    <div class="mail-grid-3">
      <label>
        <span>Workflow</span>
        <div class="mail-token-field">
          <select name="edit_workflow">
            <option value="manual" <%= "manual".equals(workflowSelect) ? "selected" : "" %>>Manual</option>
            <option value="click2mail" <%= "click2mail".equals(workflowSelect) ? "selected" : "" %>>Click2Mail</option>
            <option value="fedex" <%= "fedex".equals(workflowSelect) ? "selected" : "" %>>FedEx API</option>
            <option value="email_inbox" <%= "email_inbox".equals(workflowSelect) ? "selected" : "" %>>Email Inbox Intake</option>
            <option value="manual_scan" <%= "manual_scan".equals(workflowSelect) ? "selected" : "" %>>Manual Scan Intake</option>
            <option value="__custom__" <%= "__custom__".equals(workflowSelect) ? "selected" : "" %>>Custom</option>
          </select>
          <input type="text" name="edit_workflow_custom" value="<%= esc(workflowCustom) %>" placeholder="custom workflow" />
        </div>
      </label>

      <label>
        <span>Service</span>
        <div class="mail-token-field">
          <select name="edit_service">
            <option value="usps" <%= "usps".equals(serviceSelect) ? "selected" : "" %>>USPS</option>
            <option value="fedex" <%= "fedex".equals(serviceSelect) ? "selected" : "" %>>FedEx</option>
            <option value="ups" <%= "ups".equals(serviceSelect) ? "selected" : "" %>>UPS</option>
            <option value="courier" <%= "courier".equals(serviceSelect) ? "selected" : "" %>>Courier</option>
            <option value="other" <%= "other".equals(serviceSelect) ? "selected" : "" %>>Other</option>
            <option value="__custom__" <%= "__custom__".equals(serviceSelect) ? "selected" : "" %>>Custom</option>
          </select>
          <input type="text" name="edit_service_custom" value="<%= esc(serviceCustom) %>" placeholder="custom service" />
        </div>
      </label>

      <label>
        <span>Status</span>
        <div class="mail-token-field">
          <select name="edit_status">
            <% for (int si = 0; si < STATUS_OPTIONS.length; si++) {
                 String token = STATUS_OPTIONS[si];
            %>
              <option value="<%= esc(token) %>" <%= token.equals(statusSelect) ? "selected" : "" %>><%= esc(humanToken(token)) %></option>
            <% } %>
            <option value="__custom__" <%= "__custom__".equals(statusSelect) ? "selected" : "" %>>Custom</option>
          </select>
          <input type="text" name="edit_status_custom" value="<%= esc(statusCustom) %>" placeholder="custom status" />
        </div>
      </label>
    </div>

    <div class="mail-grid-2">
      <label>
        <span>Subject</span>
        <input type="text" name="edit_subject" value="<%= esc(safe(selectedMail.subject)) %>" required />
      </label>
      <label>
        <span>Source Email Address</span>
        <input type="email" name="edit_source_email_address" value="<%= esc(safe(selectedMail.sourceEmailAddress)) %>" />
      </label>
      <label>
        <span>Source Document UUID</span>
        <input type="text" name="edit_source_document_uuid" value="<%= esc(safe(selectedMail.sourceDocumentUuid)) %>" list="mail-doc-options" />
      </label>
      <label>
        <span>Source Part UUID</span>
        <input type="text" name="edit_source_part_uuid" value="<%= esc(safe(selectedMail.sourcePartUuid)) %>" list="mail-part-options" />
      </label>
      <label>
        <span>Source Version UUID</span>
        <input type="text" name="edit_source_version_uuid" value="<%= esc(safe(selectedMail.sourceVersionUuid)) %>" list="mail-version-options" />
      </label>
      <label>
        <span>Filed Document UUID</span>
        <input type="text" name="edit_filed_document_uuid" value="<%= esc(safe(selectedMail.filedDocumentUuid)) %>" list="mail-doc-options" />
      </label>
      <label>
        <span>Filed Part UUID</span>
        <input type="text" name="edit_filed_part_uuid" value="<%= esc(safe(selectedMail.filedPartUuid)) %>" list="mail-part-options" />
      </label>
      <label>
        <span>Filed Version UUID</span>
        <input type="text" name="edit_filed_version_uuid" value="<%= esc(safe(selectedMail.filedVersionUuid)) %>" list="mail-version-options" />
      </label>
      <label>
        <span>Tracking Carrier</span>
        <div class="mail-token-field">
          <select name="edit_tracking_carrier">
            <option value=""></option>
            <option value="usps" <%= "usps".equals(editCarrierSelect) ? "selected" : "" %>>USPS</option>
            <option value="fedex" <%= "fedex".equals(editCarrierSelect) ? "selected" : "" %>>FedEx</option>
            <option value="ups" <%= "ups".equals(editCarrierSelect) ? "selected" : "" %>>UPS</option>
            <option value="courier" <%= "courier".equals(editCarrierSelect) ? "selected" : "" %>>Courier</option>
            <option value="__custom__" <%= "__custom__".equals(editCarrierSelect) ? "selected" : "" %>>Custom</option>
          </select>
          <input type="text" name="edit_tracking_carrier_custom" value="<%= esc(editCarrierCustom) %>" placeholder="custom carrier" />
        </div>
      </label>
      <label>
        <span>Tracking Status</span>
        <input type="text" name="edit_tracking_status" value="<%= esc(safe(selectedMail.trackingStatus)) %>" />
      </label>
      <label>
        <span>Received At (ISO-8601)</span>
        <input type="text" name="edit_received_at" value="<%= esc(safe(selectedMail.receivedAt)) %>" />
      </label>
      <label>
        <span>Sent At (ISO-8601)</span>
        <input type="text" name="edit_sent_at" value="<%= esc(safe(selectedMail.sentAt)) %>" />
      </label>
      <label>
        <span>Provider Reference</span>
        <input type="text" name="edit_provider_reference" value="<%= esc(safe(selectedMail.providerReference)) %>" />
      </label>
      <label>
        <span>Provider Message</span>
        <input type="text" name="edit_provider_message" value="<%= esc(safe(selectedMail.providerMessage)) %>" />
      </label>
    </div>

    <label>
      <span>Notes</span>
      <textarea name="edit_notes" rows="3"><%= esc(safe(selectedMail.notes)) %></textarea>
    </label>

    <label>
      <span>Provider Request JSON</span>
      <textarea name="edit_provider_request_json" rows="4"><%= esc(safe(selectedMail.providerRequestJson)) %></textarea>
    </label>

    <label>
      <span>Provider Response JSON</span>
      <textarea name="edit_provider_response_json" rows="4"><%= esc(safe(selectedMail.providerResponseJson)) %></textarea>
    </label>

    <div class="actions" style="display:flex; gap:10px; flex-wrap:wrap; margin-top:10px;">
      <button class="btn" type="submit">Save Mail Item</button>
    </div>
  </form>

  <form method="post" action="<%= ctx %>/mail.jsp" style="display:inline; margin-top:10px;">
    <input type="hidden" name="action" value="set_archived" />
    <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
    <input type="hidden" name="mail_uuid" value="<%= esc(safe(selectedMail.uuid)) %>" />
    <input type="hidden" name="archived" value="<%= selectedMail.archived ? "false" : "true" %>" />
    <%= hiddenStateFields(matterFilter, directionFilter, statusFilter, show, q, safe(selectedMail.uuid)) %>
    <button class="btn btn-ghost" type="submit"><%= selectedMail.archived ? "Restore" : "Archive" %></button>
  </form>

  <datalist id="mail-doc-options">
    <% for (int di = 0; di < linkDocs.size(); di++) {
         documents.DocumentRec d = linkDocs.get(di);
         if (d == null) continue;
    %>
      <option value="<%= esc(safe(d.uuid)) %>"><%= esc(safe(d.title)) %></option>
    <% } %>
  </datalist>
  <datalist id="mail-part-options">
    <% for (int pi = 0; pi < linkParts.size(); pi++) {
         document_parts.PartRec p = linkParts.get(pi);
         if (p == null) continue;
         String partUuid = safe(p.uuid).trim();
         String docUuid = safe(partDocByUuid.get(partUuid));
    %>
      <option value="<%= esc(partUuid) %>"><%= esc(safe(docLabelByUuid.get(docUuid))) %> :: <%= esc(safe(p.label)) %></option>
    <% } %>
  </datalist>
  <datalist id="mail-version-options">
    <% for (int vi = 0; vi < linkVersions.size(); vi++) {
         part_versions.VersionRec v = linkVersions.get(vi);
         if (v == null) continue;
         String versionUuid = safe(v.uuid).trim();
         String partUuid = safe(versionPartByUuid.get(versionUuid));
         String docUuid = safe(versionDocByUuid.get(versionUuid));
    %>
      <option value="<%= esc(versionUuid) %>"><%= esc(safe(docLabelByUuid.get(docUuid))) %> :: <%= esc(safe(partLabelByUuid.get(partUuid))) %> :: <%= esc(safe(v.versionLabel)) %></option>
    <% } %>
  </datalist>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Inbound Review + Filing</h2>
  <div class="mail-grid-2">
    <form class="form" method="post" action="<%= ctx %>/mail.jsp">
      <input type="hidden" name="action" value="mark_reviewed" />
      <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
      <input type="hidden" name="mail_uuid" value="<%= esc(safe(selectedMail.uuid)) %>" />
      <%= hiddenStateFields(matterFilter, directionFilter, statusFilter, show, q, safe(selectedMail.uuid)) %>
      <label>
        <span>Review Notes</span>
        <textarea name="review_notes" rows="3" placeholder="Notes added to mail item on review"></textarea>
      </label>
      <button class="btn" type="submit">Mark Reviewed</button>
    </form>

    <form class="form" method="post" action="<%= ctx %>/mail.jsp">
      <input type="hidden" name="action" value="link_filed_document" />
      <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
      <input type="hidden" name="mail_uuid" value="<%= esc(safe(selectedMail.uuid)) %>" />
      <%= hiddenStateFields(matterFilter, directionFilter, statusFilter, show, q, safe(selectedMail.uuid)) %>
      <label>
        <span>Filed Document UUID</span>
        <input type="text" name="filed_document_uuid" value="<%= esc(safe(selectedMail.filedDocumentUuid)) %>" list="mail-doc-options" />
      </label>
      <label>
        <span>Filed Part UUID</span>
        <input type="text" name="filed_part_uuid" value="<%= esc(safe(selectedMail.filedPartUuid)) %>" list="mail-part-options" />
      </label>
      <label>
        <span>Filed Version UUID</span>
        <input type="text" name="filed_version_uuid" value="<%= esc(safe(selectedMail.filedVersionUuid)) %>" list="mail-version-options" />
      </label>
      <button class="btn" type="submit">Link Filed Document</button>
    </form>
  </div>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Mail Parts</h2>
  <div class="table-wrap" style="margin-bottom:12px;">
    <table class="table">
      <thead>
        <tr>
          <th>Type</th>
          <th>Label</th>
          <th>Document Reference</th>
          <th>Created</th>
          <th style="width:140px;">Actions</th>
        </tr>
      </thead>
      <tbody>
      <% if (selectedParts.isEmpty()) { %>
        <tr><td colspan="5" class="muted">No parts added yet.</td></tr>
      <% } else {
           for (int i = 0; i < selectedParts.size(); i++) {
             postal_mail.MailPartRec p = selectedParts.get(i);
             if (p == null) continue;
             String ref = refLabel(p.documentUuid, p.partUuid, p.versionUuid, docLabelByUuid, partLabelByUuid, versionLabelByUuid);
      %>
        <tr>
          <td><span class="mail-tag"><%= esc(humanToken(p.partType)) %></span></td>
          <td>
            <strong><%= esc(safe(p.label)) %></strong>
            <% if (p.trashed) { %><div class="meta">Trashed</div><% } %>
          </td>
          <td><%= esc(ref) %></td>
          <td><%= esc(safe(p.createdAt)) %></td>
          <td>
            <form method="post" action="<%= ctx %>/mail.jsp" style="display:inline;">
              <input type="hidden" name="action" value="set_part_trashed" />
              <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
              <input type="hidden" name="mail_uuid" value="<%= esc(safe(selectedMail.uuid)) %>" />
              <input type="hidden" name="part_uuid" value="<%= esc(safe(p.uuid)) %>" />
              <input type="hidden" name="trashed" value="<%= p.trashed ? "false" : "true" %>" />
              <%= hiddenStateFields(matterFilter, directionFilter, statusFilter, show, q, safe(selectedMail.uuid)) %>
              <button class="btn btn-ghost" type="submit"><%= p.trashed ? "Restore" : "Trash" %></button>
            </form>
          </td>
        </tr>
      <%   }
         }
      %>
      </tbody>
    </table>
  </div>

  <form class="form" method="post" action="<%= ctx %>/mail.jsp">
    <input type="hidden" name="action" value="add_part" />
    <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
    <input type="hidden" name="mail_uuid" value="<%= esc(safe(selectedMail.uuid)) %>" />
    <%= hiddenStateFields(matterFilter, directionFilter, statusFilter, show, q, safe(selectedMail.uuid)) %>

    <div class="mail-grid-2">
      <label>
        <span>Part Type</span>
        <div class="mail-token-field">
          <select name="part_type">
            <% for (int i = 0; i < PART_TYPE_OPTIONS.length; i++) {
                 String token = PART_TYPE_OPTIONS[i];
            %>
              <option value="<%= esc(token) %>"><%= esc(humanToken(token)) %></option>
            <% } %>
            <option value="__custom__">Custom</option>
          </select>
          <input type="text" name="part_type_custom" placeholder="custom type" />
        </div>
      </label>
      <label>
        <span>Label</span>
        <input type="text" name="part_label" placeholder="Scanned envelope" required />
      </label>
      <label>
        <span>Document UUID (optional)</span>
        <input type="text" name="part_document_uuid" list="mail-doc-options" />
      </label>
      <label>
        <span>Part UUID (optional)</span>
        <input type="text" name="part_part_uuid" list="mail-part-options" />
      </label>
      <label>
        <span>Version UUID (optional)</span>
        <input type="text" name="part_version_uuid" list="mail-version-options" />
      </label>
    </div>

    <label>
      <span>Notes</span>
      <textarea name="part_notes" rows="3" placeholder="Envelope front/back, proof of mailing, receipt, etc."></textarea>
    </label>

    <button class="btn" type="submit" style="margin-top:10px;">Add Part</button>
  </form>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Recipients + Address Validation</h2>
  <div class="table-wrap" style="margin-bottom:12px;">
    <table class="table">
      <thead>
        <tr>
          <th>Recipient</th>
          <th>Address</th>
          <th>Validation</th>
          <th>Created</th>
        </tr>
      </thead>
      <tbody>
      <% if (selectedRecipients.isEmpty()) { %>
        <tr><td colspan="4" class="muted">No recipients yet.</td></tr>
      <% } else {
           for (int i = 0; i < selectedRecipients.size(); i++) {
             postal_mail.RecipientRec r = selectedRecipients.get(i);
             if (r == null) continue;
      %>
        <tr>
          <td>
            <strong><%= esc(firstNonBlank(r.displayName, r.companyName)) %></strong>
            <div class="meta"><%= esc(safe(r.emailAddress)) %></div>
            <div class="meta"><%= esc(safe(r.phone)) %></div>
          </td>
          <td>
            <div><%= esc(safe(r.addressLine1)) %></div>
            <% if (!safe(r.addressLine2).trim().isBlank()) { %><div><%= esc(safe(r.addressLine2)) %></div><% } %>
            <div><%= esc(safe(r.city)) %>, <%= esc(safe(r.state)) %> <%= esc(safe(r.postalCode)) %> <%= esc(safe(r.country)) %></div>
          </td>
          <td>
            <span class="mail-tag"><%= r.validated ? "Valid" : "Invalid/Needs Review" %></span>
            <div class="meta"><%= esc(safe(r.validationMessage)) %></div>
          </td>
          <td><%= esc(safe(r.createdAt)) %></td>
        </tr>
      <%   }
         }
      %>
      </tbody>
    </table>
  </div>

  <form class="form" method="post" action="<%= ctx %>/mail.jsp">
    <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
    <input type="hidden" name="mail_uuid" value="<%= esc(safe(selectedMail.uuid)) %>" />
    <%= hiddenStateFields(matterFilter, directionFilter, statusFilter, show, q, safe(selectedMail.uuid)) %>

    <div class="mail-grid-3">
      <label>
        <span>Contact (optional)</span>
        <select name="recipient_contact_uuid">
          <option value=""></option>
          <% for (int i = 0; i < contactsActive.size(); i++) {
               contacts.ContactRec c = contactsActive.get(i);
               if (c == null) continue;
               String cid = safe(c.uuid).trim();
          %>
            <option value="<%= esc(cid) %>"><%= esc(contactDisplayName(c)) %> - <%= esc(contactSummary(c)) %></option>
          <% } %>
        </select>
      </label>
      <label>
        <span>Display Name</span>
        <input type="text" name="recipient_display_name" />
      </label>
      <label>
        <span>Company Name</span>
        <input type="text" name="recipient_company_name" />
      </label>
      <label>
        <span>Address Line 1</span>
        <input type="text" name="recipient_address_line_1" />
      </label>
      <label>
        <span>Address Line 2</span>
        <input type="text" name="recipient_address_line_2" />
      </label>
      <label>
        <span>City</span>
        <input type="text" name="recipient_city" />
      </label>
      <label>
        <span>State / Province</span>
        <input type="text" name="recipient_state" />
      </label>
      <label>
        <span>Postal Code</span>
        <input type="text" name="recipient_postal_code" />
      </label>
      <label>
        <span>Country</span>
        <input type="text" name="recipient_country" value="US" />
      </label>
      <label>
        <span>Email</span>
        <input type="email" name="recipient_email_address" />
      </label>
      <label>
        <span>Phone</span>
        <input type="text" name="recipient_phone" />
      </label>
      <label>
        <span>Allow Invalid Address</span>
        <select name="allow_invalid_address">
          <option value="false" selected>No</option>
          <option value="true">Yes</option>
        </select>
      </label>
    </div>

    <div class="actions" style="display:flex; gap:10px; flex-wrap:wrap; margin-top:10px;">
      <button class="btn" type="submit" name="action" value="add_recipient">Add Recipient</button>
      <button class="btn btn-ghost" type="submit" name="action" value="validate_address">Validate Address</button>
    </div>
  </form>

  <% if (addressPreview != null) { %>
    <div class="card" style="margin-top:10px; padding:12px; background:rgba(0,0,0,0.02);">
      <h4 style="margin-top:0;">Address Validation Result</h4>
      <div><strong>Valid:</strong> <%= addressPreview.valid ? "Yes" : "No" %></div>
      <div><strong>Message:</strong> <%= esc(safe(addressPreview.message)) %></div>
      <div><strong>Normalized:</strong> <%= esc(firstNonBlank(addressPreview.normalizedDisplayName, addressPreview.normalizedCompanyName)) %>, <%= esc(safe(addressPreview.normalizedAddressLine1)) %>, <%= esc(safe(addressPreview.normalizedCity)) %> <%= esc(safe(addressPreview.normalizedState)) %> <%= esc(safe(addressPreview.normalizedPostalCode)) %> <%= esc(safe(addressPreview.normalizedCountry)) %></div>
    </div>
  <% } %>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Tracking</h2>
  <div class="meta" style="margin-bottom:8px;">Summary: <%= esc(humanToken(selectedMail.trackingCarrier)) %> / <%= esc(safe(selectedMail.trackingNumber)) %> / <%= esc(safe(selectedMail.trackingStatus)) %></div>

  <div class="table-wrap" style="margin-bottom:12px;">
    <table class="table">
      <thead>
        <tr>
          <th>Carrier</th>
          <th>Tracking #</th>
          <th>Status</th>
          <th>Location</th>
          <th>Event At</th>
          <th>Source</th>
        </tr>
      </thead>
      <tbody>
      <% if (selectedTrackingEvents.isEmpty()) { %>
        <tr><td colspan="6" class="muted">No tracking events yet.</td></tr>
      <% } else {
           for (int i = 0; i < selectedTrackingEvents.size(); i++) {
             postal_mail.TrackingEventRec e = selectedTrackingEvents.get(i);
             if (e == null) continue;
      %>
        <tr>
          <td><%= esc(humanToken(e.carrier)) %></td>
          <td><%= esc(safe(e.trackingNumber)) %></td>
          <td><%= esc(safe(e.status)) %></td>
          <td><%= esc(safe(e.location)) %></td>
          <td><%= esc(safe(e.eventAt)) %></td>
          <td><%= esc(safe(e.source)) %></td>
        </tr>
      <%   }
         }
      %>
      </tbody>
    </table>
  </div>

  <div class="mail-grid-2">
    <form class="form" method="post" action="<%= ctx %>/mail.jsp">
      <input type="hidden" name="action" value="update_tracking_summary" />
      <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
      <input type="hidden" name="mail_uuid" value="<%= esc(safe(selectedMail.uuid)) %>" />
      <%= hiddenStateFields(matterFilter, directionFilter, statusFilter, show, q, safe(selectedMail.uuid)) %>

      <label>
        <span>Carrier</span>
        <div class="mail-token-field">
          <select name="summary_carrier">
            <option value=""></option>
            <option value="usps">USPS</option>
            <option value="fedex">FedEx</option>
            <option value="ups">UPS</option>
            <option value="courier">Courier</option>
            <option value="__custom__">Custom</option>
          </select>
          <input type="text" name="summary_carrier_custom" placeholder="custom carrier" />
        </div>
      </label>
      <label>
        <span>Tracking Number</span>
        <input type="text" name="summary_tracking_number" value="<%= esc(safe(selectedMail.trackingNumber)) %>" />
      </label>
      <label>
        <span>Tracking Status</span>
        <input type="text" name="summary_tracking_status" value="<%= esc(safe(selectedMail.trackingStatus)) %>" />
      </label>
      <label>
        <span>Mark Sent If Missing</span>
        <select name="mark_sent_if_missing">
          <option value="false" selected>No</option>
          <option value="true">Yes</option>
        </select>
      </label>
      <button class="btn" type="submit">Update Tracking Summary</button>
    </form>

    <form class="form" method="post" action="<%= ctx %>/mail.jsp">
      <input type="hidden" name="action" value="add_tracking_event" />
      <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
      <input type="hidden" name="mail_uuid" value="<%= esc(safe(selectedMail.uuid)) %>" />
      <%= hiddenStateFields(matterFilter, directionFilter, statusFilter, show, q, safe(selectedMail.uuid)) %>

      <label>
        <span>Carrier</span>
        <div class="mail-token-field">
          <select name="event_carrier">
            <option value=""></option>
            <option value="usps">USPS</option>
            <option value="fedex">FedEx</option>
            <option value="ups">UPS</option>
            <option value="courier">Courier</option>
            <option value="__custom__">Custom</option>
          </select>
          <input type="text" name="event_carrier_custom" placeholder="custom carrier" />
        </div>
      </label>
      <label>
        <span>Tracking Number</span>
        <input type="text" name="event_tracking_number" value="<%= esc(safe(selectedMail.trackingNumber)) %>" />
      </label>
      <label>
        <span>Status</span>
        <input type="text" name="event_status" placeholder="in_transit" />
      </label>
      <label>
        <span>Location</span>
        <input type="text" name="event_location" placeholder="Dallas, TX" />
      </label>
      <label>
        <span>Event At (ISO-8601, optional)</span>
        <input type="text" name="event_at" />
      </label>
      <label>
        <span>Source</span>
        <input type="text" name="event_source" placeholder="fedex_api / click2mail / manual" />
      </label>
      <label>
        <span>Notes</span>
        <textarea name="event_notes" rows="3"></textarea>
      </label>
      <button class="btn" type="submit">Add Tracking Event</button>
    </form>
  </div>

  <form class="form" method="post" action="<%= ctx %>/mail.jsp" style="margin-top:12px;">
    <input type="hidden" name="action" value="validate_tracking" />
    <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
    <%= hiddenStateFields(matterFilter, directionFilter, statusFilter, show, q, safe(selectedMail.uuid)) %>
    <div class="mail-grid-2">
      <label>
        <span>Validate Tracking Number</span>
        <input type="text" name="tracking_number_to_validate" placeholder="9400100000000000000000" />
      </label>
      <label>
        <span>&nbsp;</span>
        <button class="btn btn-ghost" type="submit">Validate Tracking Number</button>
      </label>
    </div>
  </form>

  <% if (trackingPreview != null) { %>
    <div class="card" style="margin-top:10px; padding:12px; background:rgba(0,0,0,0.02);">
      <h4 style="margin-top:0;">Tracking Validation Result</h4>
      <div><strong>Valid:</strong> <%= trackingPreview.valid ? "Yes" : "No" %></div>
      <div><strong>Message:</strong> <%= esc(safe(trackingPreview.message)) %></div>
      <div><strong>Normalized:</strong> <%= esc(safe(trackingPreview.normalizedTrackingNumber)) %></div>
      <div><strong>Carrier Hint:</strong> <%= esc(safe(trackingPreview.carrierHint)) %></div>
    </div>
  <% } %>
</section>
<% } %>

<jsp:include page="footer.jsp" />
