<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ page import="java.net.URLEncoder" %>
<%@ page import="java.nio.charset.StandardCharsets" %>
<%@ page import="java.util.ArrayList" %>
<%@ page import="java.util.LinkedHashMap" %>
<%@ page import="java.util.List" %>
<%@ page import="java.util.Map" %>
<%@ page import="net.familylawandprobate.controversies.leads_crm" %>
<%@ page import="net.familylawandprobate.controversies.users_roles" %>
<%@ include file="security.jspf" %>
<% if (!require_login()) return; %>
<% if (!require_permission("tenant_settings.manage")) return; %>
<%!
  private static String safe(String s) { return s == null ? "" : s; }
  private static String esc(String s) {
    return safe(s)
      .replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")
      .replace("'", "&#39;");
  }
  private static String enc(String s) { return URLEncoder.encode(safe(s), StandardCharsets.UTF_8); }
  private static String csrfForRender(jakarta.servlet.http.HttpServletRequest req) {
    Object a = req == null ? null : req.getAttribute("csrfToken");
    if (a instanceof String s && !safe(s).trim().isBlank()) return s;
    try {
      jakarta.servlet.http.HttpSession sess = req == null ? null : req.getSession(false);
      Object t = sess == null ? null : sess.getAttribute("CSRF_TOKEN");
      if (t instanceof String cs && !safe(cs).trim().isBlank()) return cs;
    } catch (Exception ignored) {}
    return "";
  }
%>
<%
String ctx = safe(request.getContextPath());
request.setAttribute("activeNav", "/leads_crm_lab.jsp");
String tenantUuid = safe((String) session.getAttribute("tenant.uuid")).trim();
if (tenantUuid.isBlank()) {
  response.sendRedirect(ctx + "/tenant_login.jsp");
  return;
}
String actorUserUuid = safe((String) session.getAttribute(users_roles.S_USER_UUID)).trim();
String csrfToken = csrfForRender(request);

String action = safe(request.getParameter("action")).trim();
String selectedLeadUuid = safe(request.getParameter("lead_uuid")).trim();
boolean includeArchived = "1".equals(safe(request.getParameter("include_archived")));

String message = "";
String error = "";
leads_crm store = leads_crm.defaultStore();

if (!action.isBlank()) {
  try {
    if ("create_lead".equals(action)) {
      leads_crm.LeadInput in = new leads_crm.LeadInput();
      in.status = safe(request.getParameter("status"));
      in.source = safe(request.getParameter("source"));
      in.intakeChannel = safe(request.getParameter("intake_channel"));
      in.referredBy = safe(request.getParameter("referred_by"));
      in.firstName = safe(request.getParameter("first_name"));
      in.lastName = safe(request.getParameter("last_name"));
      in.displayName = safe(request.getParameter("display_name"));
      in.company = safe(request.getParameter("company"));
      in.email = safe(request.getParameter("email"));
      in.phone = safe(request.getParameter("phone"));
      in.notes = safe(request.getParameter("notes"));
      in.tagsCsv = safe(request.getParameter("tags_csv"));
      in.assignedUserUuid = safe(request.getParameter("assigned_user_uuid"));
      leads_crm.LeadRec created = store.createLead(tenantUuid, in, actorUserUuid);
      selectedLeadUuid = safe(created == null ? "" : created.leadUuid).trim();
      message = "Lead created.";
    } else if ("add_note".equals(action)) {
      String leadUuid = safe(request.getParameter("lead_uuid")).trim();
      String body = safe(request.getParameter("note_body"));
      if (leadUuid.isBlank()) throw new IllegalArgumentException("lead_uuid is required.");
      store.addNote(tenantUuid, leadUuid, body, actorUserUuid);
      selectedLeadUuid = leadUuid;
      message = "Lead note added.";
    } else if ("toggle_archive".equals(action)) {
      String leadUuid = safe(request.getParameter("lead_uuid")).trim();
      boolean archive = "1".equals(safe(request.getParameter("archive")));
      if (leadUuid.isBlank()) throw new IllegalArgumentException("lead_uuid is required.");
      boolean ok = store.setArchived(tenantUuid, leadUuid, archive);
      selectedLeadUuid = leadUuid;
      message = ok ? (archive ? "Lead archived." : "Lead restored.") : "No change applied.";
    }
  } catch (Exception ex) {
    error = safe(ex.getMessage());
    if (error.isBlank()) error = "Lead action failed.";
  }
}

List<leads_crm.LeadRec> leads = new ArrayList<leads_crm.LeadRec>();
LinkedHashMap<String, Long> statusCounts = new LinkedHashMap<String, Long>();
leads_crm.LeadRec selectedLead = null;
List<leads_crm.LeadNoteRec> selectedNotes = new ArrayList<leads_crm.LeadNoteRec>();

try {
  leads = store.listLeads(tenantUuid, includeArchived);
  statusCounts = store.statusCounts(tenantUuid, includeArchived);
  if (selectedLeadUuid.isBlank() && !leads.isEmpty()) {
    selectedLeadUuid = safe(leads.get(0).leadUuid).trim();
  }
  for (leads_crm.LeadRec row : leads) {
    if (row == null) continue;
    if (safe(row.leadUuid).trim().equals(selectedLeadUuid)) {
      selectedLead = row;
      break;
    }
  }
  if (selectedLead != null) {
    selectedNotes = store.listNotes(tenantUuid, selectedLead.leadUuid);
  }
} catch (Exception ex) {
  if (error.isBlank()) {
    error = safe(ex.getMessage());
    if (error.isBlank()) error = "Unable to load leads.";
  }
}
%>
<jsp:include page="header.jsp" />

<section class="card">
  <h1 class="u-m-0">Leads CRM Lab</h1>
  <div class="meta u-mt-6">Manage tenant leads via <code>leads_crm</code>.</div>
</section>

<section class="card section-gap-12">
  <form method="get" action="<%= ctx %>/leads_crm_lab.jsp" class="u-flex u-gap-8 u-items-center u-wrap">
    <label><input type="checkbox" name="include_archived" value="1" <%= includeArchived ? "checked" : "" %> /> Include Archived Leads</label>
    <button class="btn btn-ghost" type="submit">Refresh</button>
  </form>
  <% if (!message.isBlank()) { %><div class="alert alert-ok u-mt-8"><%= esc(message) %></div><% } %>
  <% if (!error.isBlank()) { %><div class="alert alert-error u-mt-8"><%= esc(error) %></div><% } %>
</section>

<section class="card section-gap-12">
  <h2 class="u-m-0">Status Counts</h2>
  <div class="table-wrap table-wrap-tight u-mt-8">
    <table class="table">
      <thead><tr><th>Status</th><th>Count</th></tr></thead>
      <tbody>
      <% if (statusCounts.isEmpty()) { %>
        <tr><td colspan="2" class="meta">No counts available.</td></tr>
      <% } else {
           for (Map.Entry<String, Long> e : statusCounts.entrySet()) { %>
        <tr><td><%= esc(safe(e.getKey())) %></td><td><%= e.getValue() == null ? 0 : e.getValue() %></td></tr>
      <%   }
         } %>
      </tbody>
    </table>
  </div>
</section>

<section class="card section-gap-12">
  <h2 class="u-m-0">Create Lead</h2>
  <form method="post" action="<%= ctx %>/leads_crm_lab.jsp" class="form">
    <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
    <input type="hidden" name="action" value="create_lead" />
    <input type="hidden" name="include_archived" value="<%= includeArchived ? "1" : "" %>" />
    <div class="grid grid-3">
      <div>
        <label>Status</label>
        <select name="status">
          <option value="<%= leads_crm.STATUS_NEW %>"><%= leads_crm.STATUS_NEW %></option>
          <option value="<%= leads_crm.STATUS_QUALIFIED %>"><%= leads_crm.STATUS_QUALIFIED %></option>
          <option value="<%= leads_crm.STATUS_CONSULT_SCHEDULED %>"><%= leads_crm.STATUS_CONSULT_SCHEDULED %></option>
          <option value="<%= leads_crm.STATUS_RETAINED %>"><%= leads_crm.STATUS_RETAINED %></option>
          <option value="<%= leads_crm.STATUS_CLOSED_LOST %>"><%= leads_crm.STATUS_CLOSED_LOST %></option>
        </select>
      </div>
      <div><label>Display Name</label><input name="display_name" type="text" /></div>
      <div><label>Company</label><input name="company" type="text" /></div>
      <div><label>First Name</label><input name="first_name" type="text" /></div>
      <div><label>Last Name</label><input name="last_name" type="text" /></div>
      <div><label>Email</label><input name="email" type="text" /></div>
      <div><label>Phone</label><input name="phone" type="text" /></div>
      <div><label>Source</label><input name="source" type="text" /></div>
      <div><label>Intake Channel</label><input name="intake_channel" type="text" /></div>
      <div><label>Referred By</label><input name="referred_by" type="text" /></div>
      <div><label>Tags CSV</label><input name="tags_csv" type="text" /></div>
      <div><label>Assigned User UUID</label><input name="assigned_user_uuid" type="text" /></div>
    </div>
    <label>Notes</label>
    <textarea name="notes" rows="4"></textarea>
    <button class="btn" type="submit">Create Lead</button>
  </form>
</section>

<section class="card section-gap-12">
  <h2 class="u-m-0">Leads</h2>
  <div class="table-wrap table-wrap-tight u-mt-8">
    <table class="table">
      <thead>
      <tr>
        <th>Lead</th>
        <th>Status</th>
        <th>Email</th>
        <th>Phone</th>
        <th>Archived</th>
        <th></th>
      </tr>
      </thead>
      <tbody>
      <% if (leads.isEmpty()) { %>
        <tr><td colspan="6" class="meta">No leads found.</td></tr>
      <% } else {
           for (leads_crm.LeadRec row : leads) {
             if (row == null) continue;
             String leadUuid = safe(row.leadUuid).trim();
      %>
        <tr>
          <td><%= esc(safe(row.displayName).isBlank() ? leadUuid : safe(row.displayName)) %></td>
          <td><%= esc(safe(row.status)) %></td>
          <td><%= esc(safe(row.email)) %></td>
          <td><%= esc(safe(row.phone)) %></td>
          <td><%= row.archived ? "Yes" : "No" %></td>
          <td>
            <a class="btn btn-ghost" href="<%= ctx %>/leads_crm_lab.jsp?lead_uuid=<%= enc(leadUuid) %>&include_archived=<%= includeArchived ? "1" : "" %>">Open</a>
          </td>
        </tr>
      <%   }
         } %>
      </tbody>
    </table>
  </div>
</section>

<% if (selectedLead != null) { %>
<section class="card section-gap-12">
  <h2 class="u-m-0">Selected Lead</h2>
  <div class="meta u-mt-6"><code><%= esc(safe(selectedLead.leadUuid)) %></code></div>

  <form method="post" action="<%= ctx %>/leads_crm_lab.jsp" class="u-flex u-gap-8 u-items-center u-wrap u-mt-8">
    <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
    <input type="hidden" name="action" value="toggle_archive" />
    <input type="hidden" name="lead_uuid" value="<%= esc(safe(selectedLead.leadUuid)) %>" />
    <input type="hidden" name="include_archived" value="<%= includeArchived ? "1" : "" %>" />
    <input type="hidden" name="archive" value="<%= selectedLead.archived ? "0" : "1" %>" />
    <button class="btn btn-ghost" type="submit"><%= selectedLead.archived ? "Restore Lead" : "Archive Lead" %></button>
  </form>

  <h3 class="u-mt-12">Notes</h3>
  <div class="table-wrap table-wrap-tight">
    <table class="table">
      <thead><tr><th>When</th><th>Author</th><th>Note</th></tr></thead>
      <tbody>
      <% if (selectedNotes.isEmpty()) { %>
        <tr><td colspan="3" class="meta">No notes yet.</td></tr>
      <% } else {
           for (leads_crm.LeadNoteRec n : selectedNotes) {
             if (n == null) continue;
      %>
        <tr>
          <td><%= esc(safe(n.createdAt)) %></td>
          <td><%= esc(safe(n.authorUserUuid)) %></td>
          <td><%= esc(safe(n.body)) %></td>
        </tr>
      <%   }
         } %>
      </tbody>
    </table>
  </div>

  <form method="post" action="<%= ctx %>/leads_crm_lab.jsp" class="form u-mt-12">
    <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
    <input type="hidden" name="action" value="add_note" />
    <input type="hidden" name="lead_uuid" value="<%= esc(safe(selectedLead.leadUuid)) %>" />
    <input type="hidden" name="include_archived" value="<%= includeArchived ? "1" : "" %>" />
    <label>Add Note</label>
    <textarea name="note_body" rows="4"></textarea>
    <button class="btn" type="submit">Add Note</button>
  </form>
</section>
<% } %>

<jsp:include page="footer.jsp" />
