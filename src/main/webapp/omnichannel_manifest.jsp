<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" isELIgnored="true" %>

<%@ page import="java.net.URLEncoder" %>
<%@ page import="java.nio.charset.StandardCharsets" %>
<%@ page import="java.nio.file.Files" %>
<%@ page import="java.util.ArrayList" %>
<%@ page import="java.util.HashMap" %>
<%@ page import="java.util.HashSet" %>
<%@ page import="java.util.List" %>
<%@ page import="java.util.Locale" %>
<%@ page import="java.util.Map" %>

<%@ page import="net.familylawandprobate.controversies.omnichannel_tickets" %>
<%@ page import="net.familylawandprobate.controversies.part_versions" %>

<%@ include file="security.jspf" %>
<%
  if (!require_login()) return;
%>

<%!
  private static final String S_TENANT_UUID = "tenant.uuid";

  private static String safe(String s) { return s == null ? "" : s; }

  private static String esc(String s) {
    return safe(s).replace("&", "&amp;")
                  .replace("<", "&lt;")
                  .replace(">", "&gt;")
                  .replace("\"", "&quot;")
                  .replace("'", "&#39;");
  }

  private static String enc(String s) { return URLEncoder.encode(safe(s), StandardCharsets.UTF_8); }

  private static String shortErr(Throwable ex) {
    if (ex == null) return "";
    String m = safe(ex.getMessage()).trim();
    return m.isBlank() ? ex.getClass().getSimpleName() : m;
  }

  private static void logWarn(jakarta.servlet.ServletContext app, String message, Throwable ex) {
    if (app == null) return;
    if (ex == null) app.log("[omnichannel_manifest] " + safe(message));
    else app.log("[omnichannel_manifest] " + safe(message), ex);
  }
%>

<%
  String ctx = safe(request.getContextPath());
  String tenantUuid = safe((String)session.getAttribute(S_TENANT_UUID)).trim();
  if (tenantUuid.isBlank()) {
    response.sendRedirect(ctx + "/tenant_login.jsp");
    return;
  }

  omnichannel_tickets store = omnichannel_tickets.defaultStore();
  try { store.ensure(tenantUuid); } catch (Exception ex) { logWarn(application, "Unable to ensure store: " + shortErr(ex), ex); }

  String error = null;

  List<omnichannel_tickets.TicketRec> allThreads = new ArrayList<omnichannel_tickets.TicketRec>();
  try {
    allThreads = store.listTickets(tenantUuid);
  } catch (Exception ex) {
    error = "Unable to load threads.";
    logWarn(application, "Unable to list threads: " + shortErr(ex), ex);
  }

  String selectedThreadUuid = safe(request.getParameter("ticket_uuid")).trim();
  if (selectedThreadUuid.isBlank() && !allThreads.isEmpty()) {
    selectedThreadUuid = safe(allThreads.get(0).uuid);
  }

  omnichannel_tickets.TicketRec selected = null;
  List<omnichannel_tickets.MessageRec> messages = new ArrayList<omnichannel_tickets.MessageRec>();
  List<omnichannel_tickets.AttachmentRec> attachments = new ArrayList<omnichannel_tickets.AttachmentRec>();
  HashMap<String, String> directionByMessageId = new HashMap<String, String>();
  HashSet<String> internalMessageIds = new HashSet<String>();

  part_versions.VersionRec latestReportVersion = null;

  if (!selectedThreadUuid.isBlank()) {
    try {
      selected = store.getTicket(tenantUuid, selectedThreadUuid);
      if (selected != null) {
        messages = store.listMessages(tenantUuid, selectedThreadUuid);
        attachments = store.listAttachments(tenantUuid, selectedThreadUuid);

        for (int i = 0; i < messages.size(); i++) {
          omnichannel_tickets.MessageRec m = messages.get(i);
          if (m == null) continue;
          String id = safe(m.uuid).trim();
          if (id.isBlank()) continue;
          String d = safe(m.direction).trim().toLowerCase(Locale.ROOT);
          directionByMessageId.put(id, d);
          if ("internal".equals(d)) internalMessageIds.add(id);
        }

        String matterUuid = safe(selected.matterUuid).trim();
        String docUuid = safe(selected.reportDocumentUuid).trim();
        String partUuid = safe(selected.reportPartUuid).trim();
        if (!matterUuid.isBlank() && !docUuid.isBlank() && !partUuid.isBlank()) {
          List<part_versions.VersionRec> versions = part_versions.defaultStore().listAll(tenantUuid, matterUuid, docUuid, partUuid);
          if (!versions.isEmpty()) latestReportVersion = versions.get(0);
        }
      }
    } catch (Exception ex) {
      error = "Unable to load selected thread manifest.";
      logWarn(application, "Unable to load selected manifest: " + shortErr(ex), ex);
    }
  }
%>

<jsp:include page="header.jsp" />

<section class="card">
  <h1 style="margin:0;">Embedded Content Manifest</h1>
  <div class="meta" style="margin-top:6px;">Per-thread manifest of multimedia/attachments and their inclusion status in generated matter PDF reports.</div>
  <% if (error != null) { %><div class="alert alert-error" style="margin-top:10px;"><%= esc(error) %></div><% } %>
</section>

<section class="card" style="margin-top:12px;">
  <form class="form" method="get" action="<%= ctx %>/omnichannel_manifest.jsp">
    <label>
      <span>Thread</span>
      <select name="ticket_uuid" onchange="this.form.submit()">
        <% for (int i = 0; i < allThreads.size(); i++) {
             omnichannel_tickets.TicketRec t = allThreads.get(i);
             if (t == null) continue;
        %>
          <option value="<%= esc(safe(t.uuid)) %>" <%= safe(t.uuid).equals(selectedThreadUuid) ? "selected" : "" %>><%= esc(safe(t.subject)) %></option>
        <% } %>
      </select>
    </label>
  </form>

  <% if (selected != null) { %>
    <div class="meta" style="margin-top:8px;">
      Channel: <code><%= esc(safe(selected.channel)) %></code>
      | Status: <code><%= esc(safe(selected.status)) %></code>
      | Updated: <code><%= esc(safe(selected.updatedAt)) %></code>
    </div>
    <% if (latestReportVersion != null) { %>
      <div class="meta" style="margin-top:6px;">
        Latest report version:
        <code><%= esc(safe(latestReportVersion.versionLabel)) %></code>
        | Created: <code><%= esc(safe(latestReportVersion.createdAt)) %></code>
      </div>
    <% } %>

    <div class="actions" style="display:flex; gap:10px; margin-top:10px; flex-wrap:wrap;">
      <a class="btn btn-ghost" href="<%= ctx %>/omnichannel.jsp?ticket_uuid=<%= enc(safe(selected.uuid)) %>">Back To Thread</a>
    </div>
  <% } %>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Attachment Manifest</h2>
  <div class="table-wrap">
    <table class="table">
      <thead>
        <tr>
          <th>File</th>
          <th>MIME</th>
          <th>Size</th>
          <th>Message Direction</th>
          <th>Inline Media</th>
          <th>Included In Report</th>
          <th>Uploaded</th>
          <th>Checksum</th>
          <th>Actions</th>
        </tr>
      </thead>
      <tbody>
      <% if (attachments.isEmpty()) { %>
        <tr><td colspan="9" class="muted">No attachments for this thread.</td></tr>
      <% } else {
           for (int i = 0; i < attachments.size(); i++) {
             omnichannel_tickets.AttachmentRec a = attachments.get(i);
             if (a == null) continue;
             String messageUuid = safe(a.messageUuid).trim();
             String direction = safe(directionByMessageId.get(messageUuid)).trim();
             if (direction.isBlank()) direction = "(none)";
             boolean isInternal = internalMessageIds.contains(messageUuid);
             boolean included = !isInternal;
      %>
        <tr>
          <td><%= esc(safe(a.fileName)) %></td>
          <td><%= esc(safe(a.mimeType)) %></td>
          <td><%= esc(safe(a.fileSizeBytes)) %></td>
          <td><%= esc(direction) %></td>
          <td><%= a.inlineMedia ? "Yes" : "No" %></td>
          <td><%= included ? "Yes" : "No (Internal Note)" %></td>
          <td>
            <%= esc(safe(a.uploadedAt)) %>
            <div class="muted">By: <%= esc(safe(a.uploadedBy)) %></div>
          </td>
          <td><code><%= esc(safe(a.checksumSha256)) %></code></td>
          <td>
            <a class="btn btn-ghost" href="<%= ctx %>/omnichannel.jsp?action=download_attachment&ticket_uuid=<%= enc(safe(selectedThreadUuid)) %>&attachment_uuid=<%= enc(safe(a.uuid)) %>">Download</a>
          </td>
        </tr>
      <%   }
         } %>
      </tbody>
    </table>
  </div>
  <div class="meta" style="margin-top:10px;">
    Internal-note attachments are visible to authenticated users in this app but excluded from generated external-facing report content.
  </div>
</section>

<jsp:include page="footer.jsp" />
