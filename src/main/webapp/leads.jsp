<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" isELIgnored="true" %>
<%@ page import="java.util.ArrayList" %>
<%@ page import="java.util.List" %>
<%@ page import="java.util.Locale" %>

<%@ page import="net.familylawandprobate.controversies.leads_crm" %>
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
%>

<%
  String ctx = safe(request.getContextPath());
  request.setAttribute("activeNav", "/leads.jsp");
  String tenantUuid = safe((String) session.getAttribute(S_TENANT_UUID)).trim();
  String userUuid = safe((String) session.getAttribute(S_USER_UUID)).trim();
  String userEmail = safe((String) session.getAttribute(users_roles.S_USER_EMAIL)).trim();
  if (tenantUuid.isBlank()) { response.sendRedirect(ctx + "/tenant_login.jsp"); return; }

  String actorUser = userUuid.isBlank() ? userEmail : userUuid;
  String csrfToken = csrfForRender(request);
  String message = "";
  String error = "";

  leads_crm store = leads_crm.defaultStore();
  matters matterStore = matters.defaultStore();
  List<matters.MatterRec> allMatters = new ArrayList<matters.MatterRec>();

  try { matterStore.ensure(tenantUuid); allMatters = matterStore.listAll(tenantUuid); }
  catch (Exception ex) { error = "Unable to load matters: " + safe(ex.getMessage()); }

  if ("POST".equalsIgnoreCase(request.getMethod()) && error.isBlank()) {
    String action = safe(request.getParameter("action")).trim();
    try {
      if ("create_lead".equals(action)) {
        leads_crm.LeadInput in = new leads_crm.LeadInput();
        in.status = safe(request.getParameter("status")).trim();
        in.source = safe(request.getParameter("source")).trim();
        in.intakeChannel = safe(request.getParameter("intake_channel")).trim();
        in.referredBy = safe(request.getParameter("referred_by")).trim();
        in.firstName = safe(request.getParameter("first_name")).trim();
        in.lastName = safe(request.getParameter("last_name")).trim();
        in.displayName = safe(request.getParameter("display_name")).trim();
        in.company = safe(request.getParameter("company")).trim();
        in.email = safe(request.getParameter("email")).trim();
        in.phone = safe(request.getParameter("phone")).trim();
        in.notes = safe(request.getParameter("notes"));
        in.tagsCsv = safe(request.getParameter("tags_csv")).trim();
        in.assignedUserUuid = safe(request.getParameter("assigned_user_uuid")).trim();
        leads_crm.LeadRec created = store.createLead(tenantUuid, in, actorUser);
        message = "Lead created: " + safe(created == null ? "" : created.displayName);
      } else if ("update_lead".equals(action)) {
        String leadUuid = safe(request.getParameter("lead_uuid")).trim();
        leads_crm.LeadRec current = store.getLead(tenantUuid, leadUuid);
        if (current == null) throw new IllegalArgumentException("Lead not found.");
        leads_crm.LeadInput in = new leads_crm.LeadInput();
        in.status = safe(request.getParameter("status")).trim();
        in.source = safe(request.getParameter("source")).trim();
        in.intakeChannel = safe(request.getParameter("intake_channel")).trim();
        in.referredBy = safe(request.getParameter("referred_by")).trim();
        in.firstName = safe(request.getParameter("first_name")).trim();
        in.lastName = safe(request.getParameter("last_name")).trim();
        in.displayName = safe(request.getParameter("display_name")).trim();
        in.company = safe(request.getParameter("company")).trim();
        in.email = safe(request.getParameter("email")).trim();
        in.phone = safe(request.getParameter("phone")).trim();
        in.notes = safe(request.getParameter("notes"));
        in.tagsCsv = safe(request.getParameter("tags_csv")).trim();
        in.assignedUserUuid = safe(request.getParameter("assigned_user_uuid")).trim();
        in.matterUuid = safe(request.getParameter("matter_uuid")).trim();
        in.archived = "true".equalsIgnoreCase(safe(request.getParameter("archived")).trim());
        store.updateLead(tenantUuid, leadUuid, in, actorUser);
        message = "Lead updated.";
      } else if ("add_note".equals(action)) {
        String leadUuid = safe(request.getParameter("lead_uuid")).trim();
        String body = safe(request.getParameter("note_body")).trim();
        store.addNote(tenantUuid, leadUuid, body, actorUser);
        message = "Lead note added.";
      } else if ("convert_to_matter".equals(action)) {
        String leadUuid = safe(request.getParameter("lead_uuid")).trim();
        String matterUuid = safe(request.getParameter("matter_uuid")).trim();
        leads_crm.LeadRec lead = store.getLead(tenantUuid, leadUuid);
        if (lead == null) throw new IllegalArgumentException("Lead not found.");
        matters.MatterRec matter = null;
        if (!matterUuid.isBlank()) matter = matterStore.getByUuid(tenantUuid, matterUuid);
        if (matter == null) {
          String label = safe(request.getParameter("matter_label")).trim();
          if (label.isBlank()) label = safe(lead.displayName).trim();
          if (label.isBlank()) label = safe(lead.company).trim();
          if (label.isBlank()) label = "New Matter";
          matter = matterStore.create(tenantUuid, label, "", "", "", "", "");
        }
        store.convertToMatter(tenantUuid, leadUuid, safe(matter.uuid).trim(), actorUser);
        message = "Lead converted to matter.";
      } else if ("set_archived".equals(action)) {
        String leadUuid = safe(request.getParameter("lead_uuid")).trim();
        boolean archived = "true".equalsIgnoreCase(safe(request.getParameter("archived")).trim());
        store.setArchived(tenantUuid, leadUuid, archived);
        message = archived ? "Lead archived." : "Lead restored.";
      }
    } catch (Exception ex) {
      error = safe(ex.getMessage()).isBlank() ? ex.getClass().getSimpleName() : safe(ex.getMessage());
    }
  }

  String selectedLeadUuid = safe(request.getParameter("lead_uuid")).trim();
  boolean includeArchived = "true".equalsIgnoreCase(safe(request.getParameter("include_archived")).trim());
  String filterStatus = safe(request.getParameter("status_filter")).trim().toLowerCase(Locale.ROOT);
  String filterQ = safe(request.getParameter("q")).trim().toLowerCase(Locale.ROOT);

  List<leads_crm.LeadRec> allLeads = new ArrayList<leads_crm.LeadRec>();
  List<leads_crm.LeadRec> rows = new ArrayList<leads_crm.LeadRec>();
  leads_crm.LeadRec selected = null;
  List<leads_crm.LeadNoteRec> selectedNotes = new ArrayList<leads_crm.LeadNoteRec>();
  try {
    allLeads = store.listLeads(tenantUuid, includeArchived);
    for (leads_crm.LeadRec row : allLeads) {
      if (row == null) continue;
      if (!filterStatus.isBlank() && !filterStatus.equalsIgnoreCase(safe(row.status))) continue;
      if (!filterQ.isBlank()) {
        String hay = (safe(row.displayName) + " " + safe(row.company) + " " + safe(row.email) + " " + safe(row.phone)).toLowerCase(Locale.ROOT);
        if (!hay.contains(filterQ)) continue;
      }
      rows.add(row);
    }
    if (selectedLeadUuid.isBlank() && !rows.isEmpty()) selectedLeadUuid = safe(rows.get(0).leadUuid).trim();
    if (!selectedLeadUuid.isBlank()) {
      selected = store.getLead(tenantUuid, selectedLeadUuid);
      selectedNotes = store.listNotes(tenantUuid, selectedLeadUuid);
    }
  } catch (Exception ex) {
    if (error.isBlank()) error = "Unable to load leads: " + safe(ex.getMessage());
  }
%>

<jsp:include page="header.jsp" />

<section class="card">
  <h1 class="u-m-0">Leads CRM</h1>
  <div class="meta u-mt-6">Capture intake leads, track pipeline status, record notes, and convert retained leads into matters.</div>
  <% if (!message.isBlank()) { %><div class="alert alert-success u-mt-12"><%= esc(message) %></div><% } %>
  <% if (!error.isBlank()) { %><div class="alert alert-error u-mt-12"><%= esc(error) %></div><% } %>
</section>

<section class="card section-gap-12">
  <h2 class="u-m-0">New Lead</h2>
  <form method="post" action="<%= ctx %>/leads.jsp" class="form">
    <input type="hidden" name="csrf_token" value="<%= esc(csrfToken) %>" />
    <input type="hidden" name="action" value="create_lead" />
    <div class="grid grid-3">
      <label><span>Status</span>
        <select name="status">
          <option value="new">New</option>
          <option value="qualified">Qualified</option>
          <option value="consult_scheduled">Consult Scheduled</option>
          <option value="retained">Retained</option>
          <option value="closed_lost">Closed Lost</option>
        </select>
      </label>
      <label><span>Source</span><input type="text" name="source" placeholder="website, referral, call" /></label>
      <label><span>Intake Channel</span><input type="text" name="intake_channel" placeholder="web form, phone, walk-in" /></label>
      <label><span>First Name</span><input type="text" name="first_name" /></label>
      <label><span>Last Name</span><input type="text" name="last_name" /></label>
      <label><span>Display Name</span><input type="text" name="display_name" placeholder="Overrides first/last" /></label>
      <label><span>Company</span><input type="text" name="company" /></label>
      <label><span>Email</span><input type="email" name="email" /></label>
      <label><span>Phone</span><input type="text" name="phone" /></label>
      <label><span>Referred By</span><input type="text" name="referred_by" /></label>
      <label><span>Assigned User UUID</span><input type="text" name="assigned_user_uuid" /></label>
      <label><span>Tags (CSV)</span><input type="text" name="tags_csv" placeholder="family-law,urgent" /></label>
    </div>
    <label><span>Notes</span><textarea name="notes" rows="3"></textarea></label>
    <div class="u-mt-12"><button class="btn" type="submit">Create Lead</button></div>
  </form>
</section>

<section class="card section-gap-12">
  <div class="u-flex u-justify-between u-items-center u-gap-8 u-wrap">
    <h2 class="u-m-0">Lead Pipeline</h2>
    <form method="get" action="<%= ctx %>/leads.jsp" class="u-flex u-gap-8 u-wrap">
      <input type="text" name="q" value="<%= esc(filterQ) %>" placeholder="Search leads" />
      <select name="status_filter">
        <option value="">All statuses</option>
        <option value="new" <%= "new".equals(filterStatus) ? "selected" : "" %>>New</option>
        <option value="qualified" <%= "qualified".equals(filterStatus) ? "selected" : "" %>>Qualified</option>
        <option value="consult_scheduled" <%= "consult_scheduled".equals(filterStatus) ? "selected" : "" %>>Consult Scheduled</option>
        <option value="retained" <%= "retained".equals(filterStatus) ? "selected" : "" %>>Retained</option>
        <option value="closed_lost" <%= "closed_lost".equals(filterStatus) ? "selected" : "" %>>Closed Lost</option>
      </select>
      <label><input type="checkbox" name="include_archived" value="true" <%= includeArchived ? "checked" : "" %> /> Include Archived</label>
      <button class="btn btn-ghost" type="submit">Apply</button>
    </form>
  </div>
  <div class="table-wrap table-wrap-tight">
    <table class="table">
      <thead>
      <tr>
        <th>Lead</th>
        <th>Status</th>
        <th>Source</th>
        <th>Contact</th>
        <th>Matter</th>
        <th>Updated</th>
        <th></th>
      </tr>
      </thead>
      <tbody>
      <% if (rows.isEmpty()) { %>
      <tr><td colspan="7" class="meta">No leads found.</td></tr>
      <% } else {
           for (leads_crm.LeadRec row : rows) {
             if (row == null) continue;
      %>
      <tr>
        <td><a href="<%= ctx %>/leads.jsp?lead_uuid=<%= esc(safe(row.leadUuid)) %>"><%= esc(safe(row.displayName).isBlank() ? safe(row.company) : safe(row.displayName)) %></a></td>
        <td><%= esc(safe(row.status)) %><%= row.archived ? " (archived)" : "" %></td>
        <td><%= esc(safe(row.source)) %></td>
        <td><%= esc(safe(row.email)) %><%= safe(row.phone).isBlank() ? "" : " / " + esc(safe(row.phone)) %></td>
        <td><%= esc(safe(row.matterUuid)) %></td>
        <td><%= esc(safe(row.updatedAt)) %></td>
        <td>
          <form method="post" action="<%= ctx %>/leads.jsp?lead_uuid=<%= esc(safe(row.leadUuid)) %>">
            <input type="hidden" name="csrf_token" value="<%= esc(csrfToken) %>" />
            <input type="hidden" name="action" value="set_archived" />
            <input type="hidden" name="lead_uuid" value="<%= esc(safe(row.leadUuid)) %>" />
            <input type="hidden" name="archived" value="<%= row.archived ? "false" : "true" %>" />
            <button class="btn btn-ghost" type="submit"><%= row.archived ? "Restore" : "Archive" %></button>
          </form>
        </td>
      </tr>
      <%   }
         } %>
      </tbody>
    </table>
  </div>
</section>

<% if (selected != null) { %>
<section class="card section-gap-12">
  <h2 class="u-m-0">Lead Detail</h2>
  <form method="post" action="<%= ctx %>/leads.jsp?lead_uuid=<%= esc(safe(selected.leadUuid)) %>" class="form">
    <input type="hidden" name="csrf_token" value="<%= esc(csrfToken) %>" />
    <input type="hidden" name="action" value="update_lead" />
    <input type="hidden" name="lead_uuid" value="<%= esc(safe(selected.leadUuid)) %>" />
    <div class="grid grid-3">
      <label><span>Status</span>
        <select name="status">
          <option value="new" <%= "new".equalsIgnoreCase(safe(selected.status)) ? "selected" : "" %>>New</option>
          <option value="qualified" <%= "qualified".equalsIgnoreCase(safe(selected.status)) ? "selected" : "" %>>Qualified</option>
          <option value="consult_scheduled" <%= "consult_scheduled".equalsIgnoreCase(safe(selected.status)) ? "selected" : "" %>>Consult Scheduled</option>
          <option value="retained" <%= "retained".equalsIgnoreCase(safe(selected.status)) ? "selected" : "" %>>Retained</option>
          <option value="closed_lost" <%= "closed_lost".equalsIgnoreCase(safe(selected.status)) ? "selected" : "" %>>Closed Lost</option>
        </select>
      </label>
      <label><span>Source</span><input type="text" name="source" value="<%= esc(safe(selected.source)) %>" /></label>
      <label><span>Intake Channel</span><input type="text" name="intake_channel" value="<%= esc(safe(selected.intakeChannel)) %>" /></label>
      <label><span>First Name</span><input type="text" name="first_name" value="<%= esc(safe(selected.firstName)) %>" /></label>
      <label><span>Last Name</span><input type="text" name="last_name" value="<%= esc(safe(selected.lastName)) %>" /></label>
      <label><span>Display Name</span><input type="text" name="display_name" value="<%= esc(safe(selected.displayName)) %>" /></label>
      <label><span>Company</span><input type="text" name="company" value="<%= esc(safe(selected.company)) %>" /></label>
      <label><span>Email</span><input type="email" name="email" value="<%= esc(safe(selected.email)) %>" /></label>
      <label><span>Phone</span><input type="text" name="phone" value="<%= esc(safe(selected.phone)) %>" /></label>
      <label><span>Referred By</span><input type="text" name="referred_by" value="<%= esc(safe(selected.referredBy)) %>" /></label>
      <label><span>Assigned User UUID</span><input type="text" name="assigned_user_uuid" value="<%= esc(safe(selected.assignedUserUuid)) %>" /></label>
      <label><span>Matter UUID</span><input type="text" name="matter_uuid" value="<%= esc(safe(selected.matterUuid)) %>" /></label>
    </div>
    <label><span>Tags (CSV)</span><input type="text" name="tags_csv" value="<%= esc(safe(selected.tagsCsv)) %>" /></label>
    <label><span>Notes</span><textarea name="notes" rows="3"><%= esc(safe(selected.notes)) %></textarea></label>
    <label><input type="checkbox" name="archived" value="true" <%= selected.archived ? "checked" : "" %> /> Archived</label>
    <div class="u-mt-12"><button class="btn" type="submit">Save Lead</button></div>
  </form>
</section>

<section class="card section-gap-12">
  <h2 class="u-m-0">Convert To Matter</h2>
  <form method="post" action="<%= ctx %>/leads.jsp?lead_uuid=<%= esc(safe(selected.leadUuid)) %>" class="form">
    <input type="hidden" name="csrf_token" value="<%= esc(csrfToken) %>" />
    <input type="hidden" name="action" value="convert_to_matter" />
    <input type="hidden" name="lead_uuid" value="<%= esc(safe(selected.leadUuid)) %>" />
    <div class="grid grid-2">
      <label><span>Use Existing Matter</span>
        <select name="matter_uuid">
          <option value="">Create new matter</option>
          <% for (matters.MatterRec m : allMatters) { if (m == null || m.trashed) continue; %>
          <option value="<%= esc(safe(m.uuid)) %>"><%= esc(safe(m.label)) %></option>
          <% } %>
        </select>
      </label>
      <label><span>New Matter Label (if creating)</span>
        <input type="text" name="matter_label" placeholder="Defaults to lead name/company" />
      </label>
    </div>
    <div class="u-mt-12"><button class="btn" type="submit">Convert Lead</button></div>
  </form>
</section>

<section class="card section-gap-12">
  <h2 class="u-m-0">Lead Notes</h2>
  <form method="post" action="<%= ctx %>/leads.jsp?lead_uuid=<%= esc(safe(selected.leadUuid)) %>" class="form">
    <input type="hidden" name="csrf_token" value="<%= esc(csrfToken) %>" />
    <input type="hidden" name="action" value="add_note" />
    <input type="hidden" name="lead_uuid" value="<%= esc(safe(selected.leadUuid)) %>" />
    <label><span>New Note</span><textarea name="note_body" rows="3" required></textarea></label>
    <div class="u-mt-12"><button class="btn btn-ghost" type="submit">Add Note</button></div>
  </form>
  <div class="table-wrap table-wrap-tight u-mt-12">
    <table class="table">
      <thead><tr><th>Created</th><th>Author</th><th>Note</th></tr></thead>
      <tbody>
      <% if (selectedNotes.isEmpty()) { %>
      <tr><td colspan="3" class="meta">No notes yet.</td></tr>
      <% } else {
           for (leads_crm.LeadNoteRec note : selectedNotes) {
             if (note == null) continue;
      %>
      <tr>
        <td><%= esc(safe(note.createdAt)) %></td>
        <td><%= esc(safe(note.authorUserUuid)) %></td>
        <td><%= esc(safe(note.body)) %></td>
      </tr>
      <%   }
         } %>
      </tbody>
    </table>
  </div>
</section>
<% } %>

<jsp:include page="footer.jsp" />
