<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" isELIgnored="true" %>

<%@ page import="java.net.URLEncoder" %>
<%@ page import="java.nio.charset.StandardCharsets" %>
<%@ page import="java.nio.file.Files" %>
<%@ page import="java.util.ArrayList" %>
<%@ page import="java.util.Base64" %>
<%@ page import="java.util.HashMap" %>
<%@ page import="java.util.LinkedHashMap" %>
<%@ page import="java.util.List" %>
<%@ page import="java.util.Locale" %>
<%@ page import="java.util.Map" %>

<%@ page import="net.familylawandprobate.controversies.activity_log" %>
<%@ page import="net.familylawandprobate.controversies.matters" %>
<%@ page import="net.familylawandprobate.controversies.omnichannel_tickets" %>
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

  private static void logWarn(jakarta.servlet.ServletContext app, String message, Throwable ex) {
    if (app == null) return;
    if (ex == null) app.log("[omnichannel] " + safe(message));
    else app.log("[omnichannel] " + safe(message), ex);
  }

  private static String shortErr(Throwable ex) {
    if (ex == null) return "";
    String m = safe(ex.getMessage()).trim();
    return m.isBlank() ? ex.getClass().getSimpleName() : m;
  }

  private static String userLabel(String userUuid, Map<String, String> userEmailByUuid) {
    String csv = safe(userUuid).trim();
    if (csv.isBlank()) return "";
    String[] parts = csv.split(",");
    ArrayList<String> out = new ArrayList<String>();
    for (int i = 0; i < parts.length; i++) {
      String id = safe(parts[i]).trim();
      if (id.isBlank()) continue;
      if (userEmailByUuid == null) { out.add("(assigned user)"); continue; }
      String email = safe(userEmailByUuid.get(id)).trim();
      out.add(email.isBlank() ? "(assigned user)" : email);
    }
    return String.join(", ", out);
  }

  private static String matterLabel(String matterUuid, Map<String, String> matterLabelByUuid) {
    String id = safe(matterUuid).trim();
    if (id.isBlank()) return "(none)";
    if (matterLabelByUuid == null) return "(linked matter)";
    String label = safe(matterLabelByUuid.get(id)).trim();
    return label.isBlank() ? "(linked matter)" : label;
  }

  private static String userRefLabel(String raw, Map<String, String> userEmailByUuid) {
    String v = safe(raw).trim();
    if (v.isBlank()) return "";
    if (v.contains("@")) return v;
    String email = userEmailByUuid == null ? "" : safe(userEmailByUuid.get(v)).trim();
    return email.isBlank() ? "(account user)" : email;
  }

  private static String joinCsvIds(String[] values) {
    if (values == null || values.length == 0) return "";
    java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<String>();
    for (int i = 0; i < values.length; i++) {
      String id = safe(values[i]).trim();
      if (id.isBlank()) continue;
      out.add(id);
    }
    return String.join(",", out);
  }

  private static boolean hasCsvId(String csv, String id) {
    String target = safe(id).trim();
    if (target.isBlank()) return false;
    String[] parts = safe(csv).split(",");
    for (int i = 0; i < parts.length; i++) {
      if (target.equals(safe(parts[i]).trim())) return true;
    }
    return false;
  }
%>

<%
  String ctx = safe(request.getContextPath());
  String tenantUuid = safe((String)session.getAttribute(S_TENANT_UUID)).trim();
  String userUuid = safe((String)session.getAttribute(S_USER_UUID)).trim();
  String userEmail = safe((String)session.getAttribute(users_roles.S_USER_EMAIL)).trim();
  String actor = userEmail.isBlank() ? (userUuid.isBlank() ? "unknown" : userUuid) : userEmail;
  if (tenantUuid.isBlank()) {
    response.sendRedirect(ctx + "/tenant_login.jsp");
    return;
  }

  matters matterStore = matters.defaultStore();
  users_roles usersStore = users_roles.defaultStore();
  omnichannel_tickets ticketStore = omnichannel_tickets.defaultStore();
  activity_log logs = activity_log.defaultStore();

  try { matterStore.ensure(tenantUuid); } catch (Exception ex) { logWarn(application, "Unable to ensure matters: " + shortErr(ex), ex); }
  try { usersStore.ensure(tenantUuid); } catch (Exception ex) { logWarn(application, "Unable to ensure users: " + shortErr(ex), ex); }
  try { ticketStore.ensure(tenantUuid); } catch (Exception ex) { logWarn(application, "Unable to ensure omnichannel store: " + shortErr(ex), ex); }

  String csrfToken = csrfForRender(request);
  String message = null;
  String error = null;

  List<matters.MatterRec> mattersAll = new ArrayList<matters.MatterRec>();
  List<matters.MatterRec> mattersActive = new ArrayList<matters.MatterRec>();
  HashMap<String, String> matterLabelByUuid = new HashMap<String, String>();
  try {
    mattersAll = matterStore.listAll(tenantUuid);
    for (int i = 0; i < mattersAll.size(); i++) {
      matters.MatterRec m = mattersAll.get(i);
      matterLabelByUuid.put(safe(m == null ? "" : m.uuid), safe(m == null ? "" : m.label));
      if (m == null || m.trashed) continue;
      mattersActive.add(m);
    }
  } catch (Exception ex) {
    logWarn(application, "Unable to list matters: " + shortErr(ex), ex);
  }

  List<users_roles.UserRec> allUsers = new ArrayList<users_roles.UserRec>();
  List<users_roles.UserRec> activeUsers = new ArrayList<users_roles.UserRec>();
  HashMap<String, String> userEmailByUuid = new HashMap<String, String>();
  List<String> activeUserUuids = new ArrayList<String>();
  try {
    allUsers = usersStore.listUsers(tenantUuid);
    for (int i = 0; i < allUsers.size(); i++) {
      users_roles.UserRec u = allUsers.get(i);
      if (u == null) continue;
      userEmailByUuid.put(safe(u.uuid), safe(u.emailAddress));
      if (!u.enabled) continue;
      activeUsers.add(u);
      activeUserUuids.add(safe(u.uuid));
    }
  } catch (Exception ex) {
    logWarn(application, "Unable to list users: " + shortErr(ex), ex);
  }

  String show = safe(request.getParameter("show")).trim().toLowerCase(Locale.ROOT);
  if (show.isBlank()) show = "active";
  String q = safe(request.getParameter("q")).trim();
  String matterFilter = safe(request.getParameter("matter_filter")).trim();
  String channelFilter = safe(request.getParameter("channel_filter")).trim().toLowerCase(Locale.ROOT);
  String selectedTicketUuid = safe(request.getParameter("ticket_uuid")).trim();

  String actionGet = safe(request.getParameter("action")).trim().toLowerCase(Locale.ROOT);
  if ("download_attachment".equals(actionGet)) {
    String tId = safe(request.getParameter("ticket_uuid")).trim();
    String aId = safe(request.getParameter("attachment_uuid")).trim();
    try {
      omnichannel_tickets.AttachmentBlob blob = ticketStore.getAttachmentBlob(tenantUuid, tId, aId);
      if (blob == null || blob.attachment == null || blob.path == null || !Files.exists(blob.path)) {
        response.sendError(404);
        return;
      }
      String contentType = safe(blob.attachment.mimeType).trim();
      if (contentType.isBlank()) contentType = "application/octet-stream";
      String fileName = safe(blob.attachment.fileName).trim();
      if (fileName.isBlank()) fileName = "attachment.bin";
      fileName = fileName.replace("\"", "");

      response.setContentType(contentType);
      response.setHeader("Content-Disposition", "attachment; filename=\"" + fileName + "\"");
      try (var in = Files.newInputStream(blob.path)) {
        in.transferTo(response.getOutputStream());
      }
      return;
    } catch (Exception ex) {
      response.sendError(500, safe(ex.getMessage()));
      return;
    }
  }

  if ("POST".equalsIgnoreCase(request.getMethod())) {
    String action = safe(request.getParameter("action")).trim().toLowerCase(Locale.ROOT);
    try {
      if ("create_ticket".equals(action)) {
        String channel = safe(request.getParameter("channel")).trim();
        String matterUuid = safe(request.getParameter("matter_uuid")).trim();
        String assignmentMode = safe(request.getParameter("assignment_mode")).trim();
        String assignedUserUuid = joinCsvIds(request.getParameterValues("assigned_user_uuid_multi"));
        if (assignedUserUuid.isBlank()) assignedUserUuid = safe(request.getParameter("assigned_user_uuid")).trim();
        if ("round_robin".equalsIgnoreCase(assignmentMode)) {
          if (!activeUserUuids.isEmpty()) {
            String queueKey = safe(channel).toLowerCase(Locale.ROOT) + "|" + (matterUuid.isBlank() ? "none" : matterUuid);
            assignedUserUuid = ticketStore.chooseRoundRobinAssignee(tenantUuid, queueKey, activeUserUuids);
          } else {
            assignedUserUuid = "";
          }
        }

        omnichannel_tickets.TicketRec created = ticketStore.createTicket(
          tenantUuid,
          matterUuid,
          channel,
          request.getParameter("subject"),
          request.getParameter("status"),
          request.getParameter("priority"),
          assignmentMode,
          assignedUserUuid,
          request.getParameter("reminder_at"),
          request.getParameter("due_at"),
          request.getParameter("customer_display"),
          request.getParameter("customer_address"),
          request.getParameter("mailbox_address"),
          request.getParameter("thread_key"),
          request.getParameter("external_conversation_id"),
          request.getParameter("initial_direction"),
          request.getParameter("initial_body"),
          request.getParameter("initial_from_address"),
          request.getParameter("initial_to_address"),
          boolLike(request.getParameter("initial_mms")),
          actor,
          request.getParameter("assignment_reason")
        );

        logs.logVerbose("omnichannel.ticket.created", tenantUuid, userUuid, "", "",
          Map.of(
            "ticket_uuid", safe(created == null ? "" : created.uuid),
            "matter_uuid", safe(created == null ? "" : created.matterUuid),
            "channel", safe(created == null ? "" : created.channel)
          )
        );

        String toTicket = safe(created == null ? "" : created.uuid);
        response.sendRedirect(ctx + "/omnichannel.jsp?saved=1&ticket_uuid=" + enc(toTicket));
        return;
      }

      if ("save_ticket".equals(action)) {
        String ticketUuid = safe(request.getParameter("ticket_uuid")).trim();
        omnichannel_tickets.TicketRec current = ticketStore.getTicket(tenantUuid, ticketUuid);
        if (current == null) throw new IllegalStateException("Thread not found.");

        String assignmentMode = safe(request.getParameter("assignment_mode")).trim();
        String assignedUserUuid = joinCsvIds(request.getParameterValues("assigned_user_uuid_multi"));
        if (assignedUserUuid.isBlank()) assignedUserUuid = safe(request.getParameter("assigned_user_uuid")).trim();
        if ("round_robin".equalsIgnoreCase(assignmentMode)) {
          if (!activeUserUuids.isEmpty()) {
            String queueKey = safe(request.getParameter("channel")).toLowerCase(Locale.ROOT)
              + "|" + (safe(request.getParameter("matter_uuid")).trim().isBlank() ? "none" : safe(request.getParameter("matter_uuid")).trim());
            assignedUserUuid = ticketStore.chooseRoundRobinAssignee(tenantUuid, queueKey, activeUserUuids);
          } else {
            assignedUserUuid = "";
          }
        }

        omnichannel_tickets.TicketRec in = new omnichannel_tickets.TicketRec();
        in.uuid = ticketUuid;
        in.matterUuid = safe(request.getParameter("matter_uuid")).trim();
        in.channel = safe(request.getParameter("channel")).trim();
        in.subject = safe(request.getParameter("subject")).trim();
        in.status = safe(request.getParameter("status")).trim();
        in.priority = safe(request.getParameter("priority")).trim();
        in.assignmentMode = assignmentMode;
        in.assignedUserUuid = assignedUserUuid;
        in.reminderAt = safe(request.getParameter("reminder_at")).trim();
        in.dueAt = safe(request.getParameter("due_at")).trim();
        in.customerDisplay = safe(request.getParameter("customer_display")).trim();
        in.customerAddress = safe(request.getParameter("customer_address")).trim();
        in.mailboxAddress = safe(request.getParameter("mailbox_address")).trim();
        in.threadKey = safe(request.getParameter("thread_key")).trim();
        in.externalConversationId = safe(request.getParameter("external_conversation_id")).trim();

        in.createdAt = safe(current.createdAt);
        in.updatedAt = safe(current.updatedAt);
        in.lastInboundAt = safe(current.lastInboundAt);
        in.lastOutboundAt = safe(current.lastOutboundAt);
        in.inboundCount = current.inboundCount;
        in.outboundCount = current.outboundCount;
        in.archived = current.archived;
        in.mmsEnabled = boolLike(request.getParameter("mms_enabled")) || current.mmsEnabled;
        in.reportDocumentUuid = safe(current.reportDocumentUuid);
        in.reportPartUuid = safe(current.reportPartUuid);
        in.lastReportVersionUuid = safe(current.lastReportVersionUuid);

        boolean ok = ticketStore.updateTicket(tenantUuid, in, actor, request.getParameter("assignment_reason"));
        if (!ok) throw new IllegalStateException("Thread not found.");

        logs.logVerbose("omnichannel.ticket.updated", tenantUuid, userUuid, "", "", Map.of("ticket_uuid", safe(ticketUuid)));
        response.sendRedirect(ctx + "/omnichannel.jsp?saved=1&ticket_uuid=" + enc(ticketUuid));
        return;
      }

      if ("add_message".equals(action)) {
        String ticketUuid = safe(request.getParameter("ticket_uuid")).trim();
        ticketStore.addMessage(
          tenantUuid,
          ticketUuid,
          request.getParameter("direction"),
          request.getParameter("body"),
          boolLike(request.getParameter("mms")),
          request.getParameter("from_address"),
          request.getParameter("to_address"),
          request.getParameter("provider_message_id"),
          request.getParameter("email_message_id"),
          request.getParameter("email_in_reply_to"),
          request.getParameter("email_references"),
          actor
        );

        logs.logVerbose("omnichannel.message.added", tenantUuid, userUuid, "", "", Map.of("ticket_uuid", safe(ticketUuid)));
        response.sendRedirect(ctx + "/omnichannel.jsp?message_added=1&ticket_uuid=" + enc(ticketUuid));
        return;
      }

      if ("add_internal_note".equals(action)) {
        String ticketUuid = safe(request.getParameter("ticket_uuid")).trim();
        ticketStore.addMessage(
          tenantUuid,
          ticketUuid,
          "internal",
          request.getParameter("note_body"),
          false,
          "",
          "",
          "",
          "",
          "",
          "",
          actor
        );
        logs.logVerbose("omnichannel.note.added", tenantUuid, userUuid, "", "", Map.of("ticket_uuid", safe(ticketUuid)));
        response.sendRedirect(ctx + "/omnichannel.jsp?note_added=1&ticket_uuid=" + enc(ticketUuid));
        return;
      }

      if ("add_attachment".equals(action)) {
        String ticketUuid = safe(request.getParameter("ticket_uuid")).trim();
        String messageUuid = safe(request.getParameter("message_uuid")).trim();
        String attachmentName = safe(request.getParameter("attachment_name")).trim();
        String attachmentMime = safe(request.getParameter("attachment_mime")).trim();
        String base64 = safe(request.getParameter("attachment_base64")).replaceAll("\\s+", "");

        if (attachmentName.isBlank()) throw new IllegalArgumentException("Attachment name is required.");
        if (base64.isBlank()) throw new IllegalArgumentException("Attachment content is required.");

        byte[] bytes;
        try {
          bytes = Base64.getDecoder().decode(base64);
        } catch (Exception ex) {
          throw new IllegalArgumentException("Attachment payload is not valid base64.");
        }

        ticketStore.saveAttachment(
          tenantUuid,
          ticketUuid,
          messageUuid,
          attachmentName,
          attachmentMime,
          bytes,
          boolLike(request.getParameter("inline_media")),
          actor
        );

        logs.logVerbose("omnichannel.attachment.added", tenantUuid, userUuid, "", "", Map.of("ticket_uuid", safe(ticketUuid)));
        response.sendRedirect(ctx + "/omnichannel.jsp?attachment_added=1&ticket_uuid=" + enc(ticketUuid));
        return;
      }

      if ("archive_ticket".equals(action)) {
        String ticketUuid = safe(request.getParameter("ticket_uuid")).trim();
        boolean ok = ticketStore.setArchived(tenantUuid, ticketUuid, true, actor);
        if (!ok) throw new IllegalStateException("Thread not found.");
        response.sendRedirect(ctx + "/omnichannel.jsp?archived=1&ticket_uuid=" + enc(ticketUuid));
        return;
      }

      if ("restore_ticket".equals(action)) {
        String ticketUuid = safe(request.getParameter("ticket_uuid")).trim();
        boolean ok = ticketStore.setArchived(tenantUuid, ticketUuid, false, actor);
        if (!ok) throw new IllegalStateException("Thread not found.");
        response.sendRedirect(ctx + "/omnichannel.jsp?restored=1&ticket_uuid=" + enc(ticketUuid));
        return;
      }

      if ("regenerate_report".equals(action)) {
        String ticketUuid = safe(request.getParameter("ticket_uuid")).trim();
        ticketStore.refreshMatterReport(tenantUuid, ticketUuid, actor);
        response.sendRedirect(ctx + "/omnichannel.jsp?report=1&ticket_uuid=" + enc(ticketUuid));
        return;
      }
    } catch (Exception ex) {
      error = "Unable to save: " + safe(ex.getMessage());
      logWarn(application, "Action failed (" + action + "): " + shortErr(ex), ex);
      logs.logError("omnichannel.action_failed", tenantUuid, userUuid, "", "",
        Map.of(
          "action", safe(action),
          "message", safe(ex.getMessage())
        )
      );
    }
  }

  if ("1".equals(request.getParameter("saved"))) message = "Thread saved.";
  if ("1".equals(request.getParameter("message_added"))) message = "Message added.";
  if ("1".equals(request.getParameter("note_added"))) message = "Internal note added.";
  if ("1".equals(request.getParameter("attachment_added"))) message = "Attachment added.";
  if ("1".equals(request.getParameter("archived"))) message = "Thread archived.";
  if ("1".equals(request.getParameter("restored"))) message = "Thread restored.";
  if ("1".equals(request.getParameter("report"))) message = "Matter PDF report regenerated.";

  List<omnichannel_tickets.TicketRec> allTickets = new ArrayList<omnichannel_tickets.TicketRec>();
  try {
    allTickets = ticketStore.listTickets(tenantUuid);
  } catch (Exception ex) {
    logWarn(application, "Unable to list threads: " + shortErr(ex), ex);
    error = "Unable to load threads.";
  }

  List<omnichannel_tickets.TicketRec> filteredTickets = new ArrayList<omnichannel_tickets.TicketRec>();
  String ql = q.toLowerCase(Locale.ROOT);
  for (int i = 0; i < allTickets.size(); i++) {
    omnichannel_tickets.TicketRec t = allTickets.get(i);
    if (t == null) continue;

    if ("active".equals(show) && t.archived) continue;
    if ("archived".equals(show) && !t.archived) continue;
    if (!matterFilter.isBlank() && !matterFilter.equals(safe(t.matterUuid))) continue;
    if (!channelFilter.isBlank() && !channelFilter.equalsIgnoreCase(safe(t.channel))) continue;

    if (!q.isBlank()) {
      String hay = (safe(t.subject) + " " + safe(t.customerDisplay) + " " + safe(t.customerAddress)
        + " " + safe(t.mailboxAddress) + " " + safe(t.threadKey) + " " + safe(t.externalConversationId))
        .toLowerCase(Locale.ROOT);
      if (!hay.contains(ql)) continue;
    }

    filteredTickets.add(t);
  }

  int totalCount = allTickets.size();
  int activeCount = 0;
  int archivedCount = 0;
  int flowrouteCount = 0;
  int emailCount = 0;
  int internalMessagesCount = 0;
  for (int i = 0; i < allTickets.size(); i++) {
    omnichannel_tickets.TicketRec t = allTickets.get(i);
    if (t == null) continue;
    if (t.archived) archivedCount++; else activeCount++;
    String ch = safe(t.channel).toLowerCase(Locale.ROOT);
    if ("flowroute_sms".equals(ch)) flowrouteCount++;
    if (ch.startsWith("email_")) emailCount++;
    if ("internal_messages".equals(ch)) internalMessagesCount++;
  }

  if (selectedTicketUuid.isBlank() && !filteredTickets.isEmpty()) {
    selectedTicketUuid = safe(filteredTickets.get(0).uuid);
  }

  omnichannel_tickets.TicketRec selectedTicket = null;
  List<omnichannel_tickets.MessageRec> selectedMessages = new ArrayList<omnichannel_tickets.MessageRec>();
  List<omnichannel_tickets.AttachmentRec> selectedAttachments = new ArrayList<omnichannel_tickets.AttachmentRec>();
  List<omnichannel_tickets.AssignmentRec> selectedAssignments = new ArrayList<omnichannel_tickets.AssignmentRec>();
  HashMap<String, String> messageLabelByUuid = new HashMap<String, String>();

  if (!selectedTicketUuid.isBlank()) {
    try {
      selectedTicket = ticketStore.getTicket(tenantUuid, selectedTicketUuid);
      if (selectedTicket != null) {
        selectedMessages = ticketStore.listMessages(tenantUuid, selectedTicketUuid);
        selectedAttachments = ticketStore.listAttachments(tenantUuid, selectedTicketUuid);
        selectedAssignments = ticketStore.listAssignments(tenantUuid, selectedTicketUuid);
        for (int i = 0; i < selectedMessages.size(); i++) {
          omnichannel_tickets.MessageRec m = selectedMessages.get(i);
          if (m == null) continue;
          String msgId = safe(m.uuid).trim();
          if (msgId.isBlank()) continue;
          String created = safe(m.createdAt).trim();
          String direction = safe(m.direction).trim();
          String label = created;
          if (!direction.isBlank()) label = label.isBlank() ? direction : (created + " (" + direction + ")");
          if (label.isBlank()) label = "Linked message";
          messageLabelByUuid.put(msgId, label);
        }
      }
    } catch (Exception ex) {
      logWarn(application, "Unable to load selected thread details: " + shortErr(ex), ex);
      error = "Unable to load selected thread details.";
    }
  }
%>

<jsp:include page="header.jsp" />

<style>
  .omni-pane {
    overflow: hidden;
    padding: 0;
  }
  .omni-pane > summary {
    list-style: none;
    cursor: pointer;
    padding: 12px 14px;
    border-bottom: 1px solid var(--border);
    background: linear-gradient(180deg, var(--surface-2), color-mix(in srgb, var(--surface-2) 78%, transparent));
    display: flex;
    align-items: center;
    gap: 8px;
    font-weight: 650;
  }
  .omni-pane > summary::-webkit-details-marker {
    display: none;
  }
  .omni-pane > summary::after {
    content: "▸";
    color: var(--muted);
    margin-left: auto;
    transition: transform 0.15s ease;
  }
  .omni-pane[open] > summary::after {
    transform: rotate(90deg);
  }
  .omni-pane[open] > summary {
    background: linear-gradient(180deg, var(--accent-soft), color-mix(in srgb, var(--surface-2) 70%, transparent));
  }
  .omni-pane-body {
    padding: 12px 14px 14px 14px;
  }
  .omni-pane + .omni-pane {
    margin-top: 10px;
  }
  .omni-pane-count {
    color: var(--muted);
    font-size: 0.85rem;
    font-weight: 500;
    margin-left: 6px;
  }
  .omni-stats {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
    gap: 10px;
    margin-top: 12px;
  }
  .omni-actions {
    gap: 10px;
    margin-top: 12px;
  }
  .omni-inline-form {
    display: inline;
  }
  .omni-stat-card {
    margin: 0;
    padding: 12px;
  }
  .omni-stat-value {
    font-size: 1.3rem;
    font-weight: 700;
  }
  .omni-subheading {
    margin: 12px 0 8px 0;
  }
  .omni-check-row {
    display: flex;
    gap: 8px;
    align-items: center;
  }
  .omni-message-body {
    max-width: 460px;
  }
  .omni-message-body pre {
    white-space: pre-wrap;
    margin: 0;
  }
</style>

<section class="card">
  <h1 class="u-m-0">Omnichannel Threads</h1>
  <div class="meta u-mt-6">
    Unified thread workflow for Flowroute SMS/MMS, IMAP/SMTP email, Microsoft Graph user/shared mailbox email,
    and internal user-to-user messages.
    Matter-linked threads automatically produce versioned PDF reports with embedded multimedia.
  </div>
  <div class="actions omni-actions">
    <a class="btn btn-ghost" href="<%= ctx %>/omnichannel_manifest.jsp<%= selectedTicket != null ? "?ticket_uuid=" + enc(safe(selectedTicket.uuid)) : "" %>">Open Embedded Content Manifest</a>
    <% if (selectedTicket != null && !safe(selectedTicket.matterUuid).trim().isBlank()) { %>
      <form method="post" action="<%= ctx %>/omnichannel.jsp" class="omni-inline-form">
        <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
        <input type="hidden" name="action" value="regenerate_report" />
        <input type="hidden" name="ticket_uuid" value="<%= esc(safe(selectedTicket.uuid)) %>" />
        <button class="btn" type="submit">Regenerate Matter PDF Report</button>
      </form>
    <% } %>
  </div>

  <% if (message != null) { %><div class="alert alert-ok u-mt-12"><%= esc(message) %></div><% } %>
  <% if (error != null) { %><div class="alert alert-error u-mt-12"><%= esc(error) %></div><% } %>
</section>

<details class="card omni-pane section-gap-12" open>
  <summary>Filters and Metrics</summary>
  <div class="omni-pane-body">
  <form class="form" method="get" action="<%= ctx %>/omnichannel.jsp" id="omniFilterForm">
    <div class="grid grid-3">
      <label>
        <span>Search</span>
        <input type="search" name="q" value="<%= esc(q) %>" placeholder="Subject, mailbox, thread id, customer" />
      </label>
      <label>
        <span>Show</span>
        <select name="show">
          <option value="active" <%= "active".equals(show) ? "selected" : "" %>>Active</option>
          <option value="archived" <%= "archived".equals(show) ? "selected" : "" %>>Archived</option>
          <option value="all" <%= "all".equals(show) ? "selected" : "" %>>All</option>
        </select>
      </label>
      <label>
        <span>Channel</span>
        <select name="channel_filter">
          <option value=""></option>
          <option value="flowroute_sms" <%= "flowroute_sms".equals(channelFilter) ? "selected" : "" %>>Flowroute SMS/MMS</option>
          <option value="email_imap_smtp" <%= "email_imap_smtp".equals(channelFilter) ? "selected" : "" %>>Email (IMAP/SMTP)</option>
          <option value="email_graph_user" <%= "email_graph_user".equals(channelFilter) ? "selected" : "" %>>Email (Graph User Mailbox)</option>
          <option value="email_graph_shared" <%= "email_graph_shared".equals(channelFilter) ? "selected" : "" %>>Email (Graph Shared Mailbox)</option>
          <option value="internal_messages" <%= "internal_messages".equals(channelFilter) ? "selected" : "" %>>Internal Messages (User-to-User)</option>
        </select>
      </label>
    </div>

    <div class="grid grid-3">
      <label>
        <span>Matter</span>
        <select name="matter_filter">
          <option value=""></option>
          <% for (int i = 0; i < mattersActive.size(); i++) {
               matters.MatterRec m = mattersActive.get(i);
               if (m == null) continue;
          %>
            <option value="<%= esc(safe(m.uuid)) %>" <%= safe(m.uuid).equals(matterFilter) ? "selected" : "" %>><%= esc(safe(m.label)) %></option>
          <% } %>
        </select>
      </label>
      <label>
        <span>Selected Thread</span>
        <select name="ticket_uuid">
          <option value=""></option>
          <% for (int i = 0; i < filteredTickets.size(); i++) {
               omnichannel_tickets.TicketRec t = filteredTickets.get(i);
               if (t == null) continue;
          %>
            <option value="<%= esc(safe(t.uuid)) %>" <%= safe(t.uuid).equals(selectedTicketUuid) ? "selected" : "" %>><%= esc(safe(t.subject)) %></option>
          <% } %>
        </select>
      </label>
      <label>
        <span>&nbsp;</span>
        <button class="btn" type="submit">Apply Filters</button>
      </label>
    </div>
  </form>

  <div class="omni-stats">
    <article class="card omni-stat-card"><div class="meta">Total</div><div class="omni-stat-value"><%= totalCount %></div></article>
    <article class="card omni-stat-card"><div class="meta">Active</div><div class="omni-stat-value"><%= activeCount %></div></article>
    <article class="card omni-stat-card"><div class="meta">Archived</div><div class="omni-stat-value"><%= archivedCount %></div></article>
    <article class="card omni-stat-card"><div class="meta">Flowroute</div><div class="omni-stat-value"><%= flowrouteCount %></div></article>
    <article class="card omni-stat-card"><div class="meta">Email</div><div class="omni-stat-value"><%= emailCount %></div></article>
    <article class="card omni-stat-card"><div class="meta">Internal Messages</div><div class="omni-stat-value"><%= internalMessagesCount %></div></article>
    <article class="card omni-stat-card"><div class="meta">Filtered</div><div class="omni-stat-value"><%= filteredTickets.size() %></div></article>
  </div>
  </div>
</details>

<details class="card omni-pane section-gap-12">
  <summary>Create Thread</summary>
  <div class="omni-pane-body">
  <form method="post" class="form" action="<%= ctx %>/omnichannel.jsp">
    <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
    <input type="hidden" name="action" value="create_ticket" />

    <div class="grid grid-3">
      <label>
        <span>Subject</span>
        <input type="text" name="subject" required placeholder="New inbound email from opposing counsel" />
      </label>
      <label>
        <span>Channel</span>
        <select name="channel" required>
          <option value="flowroute_sms">Flowroute SMS/MMS</option>
          <option value="email_imap_smtp" selected>Email (IMAP/SMTP)</option>
          <option value="email_graph_user">Email (Graph User Mailbox)</option>
          <option value="email_graph_shared">Email (Graph Shared Mailbox)</option>
          <option value="internal_messages">Internal Messages (User-to-User)</option>
        </select>
      </label>
      <label>
        <span>Linked Matter</span>
        <select name="matter_uuid">
          <option value=""></option>
          <% for (int i = 0; i < mattersActive.size(); i++) {
               matters.MatterRec m = mattersActive.get(i);
               if (m == null) continue;
          %>
            <option value="<%= esc(safe(m.uuid)) %>"><%= esc(safe(m.label)) %></option>
          <% } %>
        </select>
      </label>
    </div>

    <div class="grid grid-3">
      <label>
        <span>Status</span>
        <select name="status">
          <option value="open">Open</option>
          <option value="pending_customer">Pending Customer</option>
          <option value="pending_internal">Pending Internal</option>
          <option value="on_hold">On Hold</option>
          <option value="resolved">Resolved</option>
          <option value="closed">Closed</option>
        </select>
      </label>
      <label>
        <span>Priority</span>
        <select name="priority">
          <option value="low">Low</option>
          <option value="normal" selected>Normal</option>
          <option value="high">High</option>
          <option value="urgent">Urgent</option>
        </select>
      </label>
      <label>
        <span>Assignment Mode</span>
        <select name="assignment_mode">
          <option value="manual" selected>Manual</option>
          <option value="round_robin">Round Robin (Load Balanced)</option>
        </select>
      </label>
    </div>

    <div class="grid grid-3">
      <label>
        <span>Assigned Users (Manual)</span>
        <select name="assigned_user_uuid_multi" multiple size="4">
          <% for (int i = 0; i < activeUsers.size(); i++) {
               users_roles.UserRec u = activeUsers.get(i);
               if (u == null) continue;
          %>
            <option value="<%= esc(safe(u.uuid)) %>"><%= esc(safe(u.emailAddress)) %></option>
          <% } %>
        </select>
      </label>
      <label>
        <span>Reminder At</span>
        <input type="datetime-local" name="reminder_at" />
      </label>
      <label>
        <span>Due At</span>
        <input type="datetime-local" name="due_at" />
      </label>
    </div>

    <div class="grid grid-3">
      <label>
        <span>Customer Name</span>
        <input type="text" name="customer_display" placeholder="John Client" />
      </label>
      <label>
        <span>Customer Address (Email/Phone)</span>
        <input type="text" name="customer_address" placeholder="client@example.com or +1..." />
      </label>
      <label>
        <span>Mailbox Address (Email Channels)</span>
        <input type="text" name="mailbox_address" placeholder="inbox@firm.com" />
      </label>
    </div>

    <div class="grid grid-3">
      <label>
        <span>Email Thread Key</span>
        <input type="text" name="thread_key" placeholder="imap UID / graph conversation id / thread key" />
      </label>
      <label>
        <span>External Conversation ID</span>
        <input type="text" name="external_conversation_id" placeholder="provider-specific conversation id" />
      </label>
      <label>
        <span>Assignment Note</span>
        <input type="text" name="assignment_reason" placeholder="Reason for initial assignment/reassignment" />
      </label>
    </div>

    <h4 class="omni-subheading">Initial Message (Optional)</h4>
    <div class="grid grid-3">
      <label>
        <span>Direction</span>
        <select name="initial_direction">
          <option value="inbound" selected>Inbound</option>
          <option value="outbound">Outbound</option>
          <option value="internal">Internal</option>
        </select>
      </label>
      <label>
        <span>From</span>
        <input type="text" name="initial_from_address" placeholder="sender@example.com or +1..." />
      </label>
      <label>
        <span>To</span>
        <input type="text" name="initial_to_address" placeholder="destination@example.com or +1..." />
      </label>
    </div>
    <label>
      <span>Message Body</span>
      <textarea name="initial_body" rows="3" placeholder="Paste first message summary/content"></textarea>
    </label>
    <label class="omni-check-row u-mt-8">
      <input type="checkbox" name="initial_mms" value="1" />
      <span>Initial message includes MMS/multimedia context</span>
    </label>

    <div class="meta u-mt-10">
      Future development plan: plug inbound/outbound transport workers into Flowroute webhook polling, IMAP/SMTP mailbox sync, and Graph user/shared mailbox graph subscriptions while preserving this thread/report schema.
    </div>

    <button class="btn u-mt-10" type="submit">Create Thread</button>
  </form>
  </div>
</details>

<details class="card omni-pane section-gap-12" open>
  <summary>Threads <span class="omni-pane-count"><%= filteredTickets.size() %> shown</span></summary>
  <div class="omni-pane-body">
  <div class="table-wrap">
    <table class="table">
      <thead>
        <tr>
          <th>Subject</th>
          <th>Channel</th>
          <th>Status</th>
          <th>Assigned</th>
          <th>Matter</th>
          <th>Updated</th>
          <th>Actions</th>
        </tr>
      </thead>
      <tbody>
      <% if (filteredTickets.isEmpty()) { %>
        <tr><td colspan="7" class="muted">No threads match the current filters.</td></tr>
      <% } else {
           for (int i = 0; i < filteredTickets.size(); i++) {
             omnichannel_tickets.TicketRec t = filteredTickets.get(i);
             if (t == null) continue;
             String tId = safe(t.uuid);
      %>
        <tr>
          <td>
            <strong><%= esc(safe(t.subject)) %></strong>
            <div class="muted"><%= t.archived ? "Archived" : "Active" %> | <%= t.inboundCount %> in / <%= t.outboundCount %> out</div>
          </td>
          <td><%= esc(safe(t.channel)) %></td>
          <td><%= esc(safe(t.status)) %> (<%= esc(safe(t.priority)) %>)</td>
          <td><%= esc(userLabel(safe(t.assignedUserUuid), userEmailByUuid)) %></td>
          <td><%= esc(matterLabel(safe(t.matterUuid), matterLabelByUuid)) %></td>
          <td><%= esc(safe(t.updatedAt)) %></td>
          <td>
            <a class="btn btn-ghost" href="<%= ctx %>/omnichannel.jsp?show=<%= enc(show) %>&q=<%= enc(q) %>&matter_filter=<%= enc(matterFilter) %>&channel_filter=<%= enc(channelFilter) %>&ticket_uuid=<%= enc(tId) %>">Open</a>
            <a class="btn btn-ghost" href="<%= ctx %>/omnichannel_manifest.jsp?ticket_uuid=<%= enc(tId) %>">Manifest</a>
            <% if (!t.archived) { %>
              <form method="post" action="<%= ctx %>/omnichannel.jsp" class="omni-inline-form">
                <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
                <input type="hidden" name="action" value="archive_ticket" />
                <input type="hidden" name="ticket_uuid" value="<%= esc(tId) %>" />
                <button class="btn btn-ghost" type="submit">Archive</button>
              </form>
            <% } else { %>
              <form method="post" action="<%= ctx %>/omnichannel.jsp" class="omni-inline-form">
                <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
                <input type="hidden" name="action" value="restore_ticket" />
                <input type="hidden" name="ticket_uuid" value="<%= esc(tId) %>" />
                <button class="btn" type="submit">Restore</button>
              </form>
            <% } %>
          </td>
        </tr>
      <%   }
         } %>
      </tbody>
    </table>
  </div>
  </div>
</details>

<% if (selectedTicket != null) { %>
<details class="card omni-pane section-gap-12" open>
  <summary>Selected Thread Detail</summary>
  <div class="omni-pane-body">
  <div class="meta u-mb-12">
    Channel: <code><%= esc(safe(selectedTicket.channel)) %></code>
    | Status: <code><%= esc(safe(selectedTicket.status)) %></code>
    | Updated: <code><%= esc(safe(selectedTicket.updatedAt)) %></code>
    <% if (!safe(selectedTicket.reportDocumentUuid).trim().isBlank()) { %>
      | External Report: <code>Available</code>
    <% } %>
  </div>

  <form method="post" class="form" action="<%= ctx %>/omnichannel.jsp">
    <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
    <input type="hidden" name="action" value="save_ticket" />
    <input type="hidden" name="ticket_uuid" value="<%= esc(safe(selectedTicket.uuid)) %>" />

    <div class="grid grid-3">
      <label><span>Subject</span><input type="text" name="subject" value="<%= esc(safe(selectedTicket.subject)) %>" required /></label>
      <label>
        <span>Channel</span>
        <select name="channel">
          <option value="flowroute_sms" <%= "flowroute_sms".equals(safe(selectedTicket.channel)) ? "selected" : "" %>>Flowroute SMS/MMS</option>
          <option value="email_imap_smtp" <%= "email_imap_smtp".equals(safe(selectedTicket.channel)) ? "selected" : "" %>>Email (IMAP/SMTP)</option>
          <option value="email_graph_user" <%= "email_graph_user".equals(safe(selectedTicket.channel)) ? "selected" : "" %>>Email (Graph User Mailbox)</option>
          <option value="email_graph_shared" <%= "email_graph_shared".equals(safe(selectedTicket.channel)) ? "selected" : "" %>>Email (Graph Shared Mailbox)</option>
          <option value="internal_messages" <%= "internal_messages".equals(safe(selectedTicket.channel)) ? "selected" : "" %>>Internal Messages (User-to-User)</option>
        </select>
      </label>
      <label>
        <span>Linked Matter</span>
        <select name="matter_uuid">
          <option value=""></option>
          <% for (int i = 0; i < mattersActive.size(); i++) {
               matters.MatterRec m = mattersActive.get(i);
               if (m == null) continue;
          %>
            <option value="<%= esc(safe(m.uuid)) %>" <%= safe(m.uuid).equals(safe(selectedTicket.matterUuid)) ? "selected" : "" %>><%= esc(safe(m.label)) %></option>
          <% } %>
        </select>
      </label>
    </div>

    <div class="grid grid-3">
      <label>
        <span>Status</span>
        <select name="status">
          <option value="open" <%= "open".equals(safe(selectedTicket.status)) ? "selected" : "" %>>Open</option>
          <option value="pending_customer" <%= "pending_customer".equals(safe(selectedTicket.status)) ? "selected" : "" %>>Pending Customer</option>
          <option value="pending_internal" <%= "pending_internal".equals(safe(selectedTicket.status)) ? "selected" : "" %>>Pending Internal</option>
          <option value="on_hold" <%= "on_hold".equals(safe(selectedTicket.status)) ? "selected" : "" %>>On Hold</option>
          <option value="resolved" <%= "resolved".equals(safe(selectedTicket.status)) ? "selected" : "" %>>Resolved</option>
          <option value="closed" <%= "closed".equals(safe(selectedTicket.status)) ? "selected" : "" %>>Closed</option>
        </select>
      </label>
      <label>
        <span>Priority</span>
        <select name="priority">
          <option value="low" <%= "low".equals(safe(selectedTicket.priority)) ? "selected" : "" %>>Low</option>
          <option value="normal" <%= "normal".equals(safe(selectedTicket.priority)) ? "selected" : "" %>>Normal</option>
          <option value="high" <%= "high".equals(safe(selectedTicket.priority)) ? "selected" : "" %>>High</option>
          <option value="urgent" <%= "urgent".equals(safe(selectedTicket.priority)) ? "selected" : "" %>>Urgent</option>
        </select>
      </label>
      <label>
        <span>Assignment Mode</span>
        <select name="assignment_mode">
          <option value="manual" <%= "manual".equals(safe(selectedTicket.assignmentMode)) ? "selected" : "" %>>Manual</option>
          <option value="round_robin" <%= "round_robin".equals(safe(selectedTicket.assignmentMode)) ? "selected" : "" %>>Round Robin</option>
        </select>
      </label>
    </div>

    <div class="grid grid-3">
      <label>
        <span>Assigned Users (Manual)</span>
        <select name="assigned_user_uuid_multi" multiple size="4">
          <% for (int i = 0; i < activeUsers.size(); i++) {
               users_roles.UserRec u = activeUsers.get(i);
               if (u == null) continue;
          %>
            <option value="<%= esc(safe(u.uuid)) %>" <%= hasCsvId(safe(selectedTicket.assignedUserUuid), safe(u.uuid)) ? "selected" : "" %>><%= esc(safe(u.emailAddress)) %></option>
          <% } %>
        </select>
      </label>
      <label><span>Reminder At</span><input type="datetime-local" name="reminder_at" value="<%= esc(safe(selectedTicket.reminderAt)) %>" /></label>
      <label><span>Due At</span><input type="datetime-local" name="due_at" value="<%= esc(safe(selectedTicket.dueAt)) %>" /></label>
    </div>

    <div class="grid grid-3">
      <label><span>Customer Name</span><input type="text" name="customer_display" value="<%= esc(safe(selectedTicket.customerDisplay)) %>" /></label>
      <label><span>Customer Address</span><input type="text" name="customer_address" value="<%= esc(safe(selectedTicket.customerAddress)) %>" /></label>
      <label><span>Mailbox Address</span><input type="text" name="mailbox_address" value="<%= esc(safe(selectedTicket.mailboxAddress)) %>" /></label>
    </div>

    <div class="grid grid-3">
      <label><span>Email Thread Key</span><input type="text" name="thread_key" value="<%= esc(safe(selectedTicket.threadKey)) %>" /></label>
      <label><span>External Conversation ID</span><input type="text" name="external_conversation_id" value="<%= esc(safe(selectedTicket.externalConversationId)) %>" /></label>
      <label><span>Assignment Note</span><input type="text" name="assignment_reason" placeholder="Reason for reassignment" /></label>
    </div>

    <label class="omni-check-row">
      <input type="checkbox" name="mms_enabled" value="1" <%= selectedTicket.mmsEnabled ? "checked" : "" %> />
      <span>Thread has MMS/multimedia context</span>
    </label>

    <button class="btn" type="submit">Save Thread</button>
  </form>
  </div>
</details>

<details class="card omni-pane section-gap-12">
  <summary>Add External Message</summary>
  <div class="omni-pane-body">
  <form method="post" class="form" action="<%= ctx %>/omnichannel.jsp">
    <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
    <input type="hidden" name="action" value="add_message" />
    <input type="hidden" name="ticket_uuid" value="<%= esc(safe(selectedTicket.uuid)) %>" />

    <div class="grid grid-3">
      <label>
        <span>Direction</span>
        <select name="direction">
          <option value="inbound">Inbound</option>
          <option value="outbound">Outbound</option>
        </select>
      </label>
      <label><span>From</span><input type="text" name="from_address" placeholder="sender@domain.com or +1..." /></label>
      <label><span>To</span><input type="text" name="to_address" placeholder="receiver@domain.com or +1..." /></label>
    </div>

    <div class="grid grid-3">
      <label><span>Provider Message ID</span><input type="text" name="provider_message_id" placeholder="Flowroute/SMTP provider id" /></label>
      <label><span>Email Message-ID</span><input type="text" name="email_message_id" placeholder="&lt;message-id@example&gt;" /></label>
      <label><span>Email In-Reply-To</span><input type="text" name="email_in_reply_to" /></label>
    </div>

    <label>
      <span>Email References</span>
      <input type="text" name="email_references" placeholder="thread reference chain" />
    </label>

    <label>
      <span>Body</span>
      <textarea name="body" rows="4" required placeholder="Message content"></textarea>
    </label>

    <label class="omni-check-row">
      <input type="checkbox" name="mms" value="1" />
      <span>This message includes MMS/multimedia context</span>
    </label>

    <button class="btn" type="submit">Add Message</button>
  </form>
  </div>
</details>

<details class="card omni-pane section-gap-12">
  <summary>Add Internal Note</summary>
  <div class="omni-pane-body">
  <div class="meta u-mb-8">
    Internal notes are visible to authenticated users in this app but are excluded from embedded matter PDF reports.
  </div>
  <form method="post" class="form" action="<%= ctx %>/omnichannel.jsp">
    <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
    <input type="hidden" name="action" value="add_internal_note" />
    <input type="hidden" name="ticket_uuid" value="<%= esc(safe(selectedTicket.uuid)) %>" />
    <label>
      <span>Note</span>
      <textarea name="note_body" rows="4" required placeholder="Internal strategy/commentary not intended for external recipients"></textarea>
    </label>
    <button class="btn" type="submit">Save Internal Note</button>
  </form>
  </div>
</details>

<details class="card omni-pane section-gap-12">
  <summary>Add Attachment / MMS Multimedia</summary>
  <div class="omni-pane-body">
  <form method="post" class="form js-attachment-form" action="<%= ctx %>/omnichannel.jsp" data-max-bytes="20971520">
    <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
    <input type="hidden" name="action" value="add_attachment" />
    <input type="hidden" name="ticket_uuid" value="<%= esc(safe(selectedTicket.uuid)) %>" />

    <div class="grid grid-3">
      <label>
        <span>Attach To Message</span>
        <select name="message_uuid">
          <option value=""></option>
          <% for (int i = 0; i < selectedMessages.size(); i++) {
               omnichannel_tickets.MessageRec m = selectedMessages.get(i);
               if (m == null) continue;
          %>
            <option value="<%= esc(safe(m.uuid)) %>"><%= esc("[" + safe(m.createdAt) + "] " + safe(m.direction)) %></option>
          <% } %>
        </select>
      </label>
      <label>
        <span>File</span>
        <input type="file" name="attachment_file" required />
      </label>
      <label class="omni-check-row">
        <input type="checkbox" name="inline_media" value="1" checked />
        <span>Inline media (embed as multimedia)</span>
      </label>
    </div>

    <input type="hidden" name="attachment_name" value="" />
    <input type="hidden" name="attachment_mime" value="" />
    <textarea name="attachment_base64" class="u-hidden"></textarea>

    <div class="meta">Attachments are base64-encoded client-side, stored with thread history, and embedded into linked matter PDF reports.</div>
    <button class="btn" type="submit">Upload Attachment</button>
  </form>
  </div>
</details>

<details class="card omni-pane section-gap-12">
  <summary>Assignment History <span class="omni-pane-count"><%= selectedAssignments.size() %> records</span></summary>
  <div class="omni-pane-body">
  <div class="table-wrap">
    <table class="table">
      <thead>
        <tr>
          <th>Changed At</th>
          <th>Mode</th>
          <th>From</th>
          <th>To</th>
          <th>Reason</th>
          <th>Changed By</th>
        </tr>
      </thead>
      <tbody>
      <% if (selectedAssignments.isEmpty()) { %>
        <tr><td colspan="6" class="muted">No assignment changes recorded.</td></tr>
      <% } else {
           for (int i = 0; i < selectedAssignments.size(); i++) {
             omnichannel_tickets.AssignmentRec a = selectedAssignments.get(i);
             if (a == null) continue;
      %>
        <tr>
          <td><%= esc(safe(a.changedAt)) %></td>
          <td><%= esc(safe(a.mode)) %></td>
          <td><%= esc(userLabel(safe(a.fromUserUuid), userEmailByUuid)) %></td>
          <td><%= esc(userLabel(safe(a.toUserUuid), userEmailByUuid)) %></td>
          <td><%= esc(safe(a.reason)) %></td>
          <td><%= esc(userRefLabel(safe(a.changedBy), userEmailByUuid)) %></td>
        </tr>
      <%   }
         } %>
      </tbody>
    </table>
  </div>
  </div>
</details>

<details class="card omni-pane section-gap-12" open>
  <summary>Message Timeline <span class="omni-pane-count"><%= selectedMessages.size() %> messages</span></summary>
  <div class="omni-pane-body">
  <div class="table-wrap">
    <table class="table">
      <thead>
        <tr>
          <th>Created</th>
          <th>Direction</th>
          <th>Route</th>
          <th>Tracking</th>
          <th>Body</th>
        </tr>
      </thead>
      <tbody>
      <% if (selectedMessages.isEmpty()) { %>
        <tr><td colspan="5" class="muted">No messages yet.</td></tr>
      <% } else {
           for (int i = 0; i < selectedMessages.size(); i++) {
             omnichannel_tickets.MessageRec m = selectedMessages.get(i);
             if (m == null) continue;
      %>
        <tr>
          <td>
            <%= esc(safe(m.createdAt)) %>
            <div class="muted">By: <%= esc(userRefLabel(safe(m.createdBy), userEmailByUuid)) %></div>
          </td>
          <td><%= esc(safe(m.direction)) %> <%= m.mms ? "(MMS)" : "" %></td>
          <td>
            <div>From: <%= esc(safe(m.fromAddress)) %></div>
            <div>To: <%= esc(safe(m.toAddress)) %></div>
          </td>
          <td>
            <div>Provider ID: <%= esc(safe(m.providerMessageId)) %></div>
            <div>Email Message-ID: <%= esc(safe(m.emailMessageId)) %></div>
            <div>In-Reply-To: <%= esc(safe(m.emailInReplyTo)) %></div>
          </td>
          <td class="omni-message-body"><pre><%= esc(safe(m.body)) %></pre></td>
        </tr>
      <%   }
         } %>
      </tbody>
    </table>
  </div>
  </div>
</details>

<details class="card omni-pane section-gap-12">
  <summary>Attachment / Multimedia Ledger <span class="omni-pane-count"><%= selectedAttachments.size() %> files</span></summary>
  <div class="omni-pane-body">
  <div class="table-wrap">
    <table class="table">
      <thead>
        <tr>
          <th>File</th>
          <th>MIME</th>
          <th>Size</th>
          <th>Message</th>
          <th>Inline Media</th>
          <th>Uploaded</th>
          <th>Checksum</th>
          <th>Actions</th>
        </tr>
      </thead>
      <tbody>
      <% if (selectedAttachments.isEmpty()) { %>
        <tr><td colspan="8" class="muted">No attachments yet.</td></tr>
      <% } else {
           for (int i = 0; i < selectedAttachments.size(); i++) {
             omnichannel_tickets.AttachmentRec a = selectedAttachments.get(i);
             if (a == null) continue;
             String messageRef = safe(a.messageUuid).trim();
             String messageLabel = safe(messageLabelByUuid.get(messageRef)).trim();
             if (messageLabel.isBlank()) messageLabel = messageRef.isBlank() ? "(not linked)" : "Linked message";
      %>
        <tr>
          <td><%= esc(safe(a.fileName)) %></td>
          <td><%= esc(safe(a.mimeType)) %></td>
          <td><%= esc(safe(a.fileSizeBytes)) %></td>
          <td><%= esc(messageLabel) %></td>
          <td><%= a.inlineMedia ? "Yes" : "No" %></td>
          <td>
            <%= esc(safe(a.uploadedAt)) %>
            <div class="muted">By: <%= esc(userRefLabel(safe(a.uploadedBy), userEmailByUuid)) %></div>
          </td>
          <td><code><%= esc(safe(a.checksumSha256)) %></code></td>
          <td>
            <a class="btn btn-ghost" href="<%= ctx %>/omnichannel.jsp?action=download_attachment&ticket_uuid=<%= enc(safe(selectedTicket.uuid)) %>&attachment_uuid=<%= enc(safe(a.uuid)) %>">Download</a>
            <a class="btn btn-ghost" href="<%= ctx %>/omnichannel_manifest.jsp?ticket_uuid=<%= enc(safe(selectedTicket.uuid)) %>">Manifest</a>
          </td>
        </tr>
      <%   }
         } %>
      </tbody>
    </table>
  </div>
  </div>
</details>
<% } %>

<script>
  (function () {
    function uiScope() {
      var cfg = document.getElementById("uiThemeConfig");
      if (!cfg) return "global";
      var value = String(cfg.getAttribute("data-scope") || cfg.getAttribute("data-tenant") || "").trim();
      return value || "global";
    }

    function wireFilterPersistence() {
      var form = document.getElementById("omniFilterForm");
      if (!form || !window.localStorage) return;

      var key = "omnichannel.filters." + uiScope();
      var fields = ["q", "show", "matter_filter", "channel_filter", "ticket_uuid"];

      var params = new URLSearchParams(window.location.search || "");
      var hasExplicit = fields.some(function (name) {
        return params.has(name);
      });

      if (!hasExplicit) {
        try {
          var raw = localStorage.getItem(key);
          if (raw) {
            var saved = JSON.parse(raw);
            fields.forEach(function (name) {
              var input = form.elements.namedItem(name);
              if (!input || !saved || saved[name] == null) return;
              input.value = String(saved[name]);
            });
          }
        } catch (err) {
          // Ignore invalid/blocked local storage.
        }
      }

      form.addEventListener("submit", function () {
        try {
          var payload = {};
          fields.forEach(function (name) {
            var input = form.elements.namedItem(name);
            payload[name] = input ? String(input.value || "") : "";
          });
          localStorage.setItem(key, JSON.stringify(payload));
        } catch (err) {
          // Ignore local storage failures.
        }
      });
    }

    function wireAttachmentForms() {
      var forms = document.querySelectorAll("form.js-attachment-form");
      forms.forEach(function (form) {
        form.addEventListener("submit", function (event) {
          if (form.dataset.ready === "1") {
            form.dataset.ready = "";
            return;
          }

          var input = form.querySelector('input[name="attachment_file"]');
          if (!input || !input.files || input.files.length === 0) {
            event.preventDefault();
            alert("Choose a file to upload.");
            return;
          }

          var file = input.files[0];
          var maxBytes = parseInt(form.dataset.maxBytes || "20971520", 10);
          if (file.size > maxBytes) {
            event.preventDefault();
            alert("Attachment exceeds the 20MB limit.");
            return;
          }

          event.preventDefault();
          var reader = new FileReader();
          reader.onload = function (e) {
            var raw = (e && e.target && e.target.result) ? String(e.target.result) : "";
            var comma = raw.indexOf(",");
            var payload = comma >= 0 ? raw.substring(comma + 1) : raw;

            var nameInput = form.querySelector('input[name="attachment_name"]');
            var mimeInput = form.querySelector('input[name="attachment_mime"]');
            var b64Input = form.querySelector('textarea[name="attachment_base64"]');

            if (nameInput) nameInput.value = file.name || "attachment.bin";
            if (mimeInput) mimeInput.value = file.type || "application/octet-stream";
            if (b64Input) b64Input.value = payload;

            form.dataset.ready = "1";
            form.submit();
          };
          reader.onerror = function () {
            alert("Unable to read selected file.");
          };
          reader.readAsDataURL(file);
        });
      });
    }

    wireFilterPersistence();
    wireAttachmentForms();
  })();
</script>

<jsp:include page="footer.jsp" />
