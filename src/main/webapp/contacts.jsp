<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>

<%@ page import="java.net.URLEncoder" %>
<%@ page import="java.nio.charset.StandardCharsets" %>
<%@ page import="java.time.Instant" %>
<%@ page import="java.util.ArrayList" %>
<%@ page import="java.util.HashMap" %>
<%@ page import="java.util.LinkedHashMap" %>
<%@ page import="java.util.List" %>
<%@ page import="java.util.Locale" %>
<%@ page import="java.util.Map" %>

<%@ page import="net.familylawandprobate.controversies.contacts" %>
<%@ page import="net.familylawandprobate.controversies.contact_vcards" %>
<%@ page import="net.familylawandprobate.controversies.matter_contacts" %>
<%@ page import="net.familylawandprobate.controversies.matters" %>
<%@ page import="net.familylawandprobate.controversies.integrations.clio.ClioIntegrationService" %>
<%@ page import="net.familylawandprobate.controversies.integrations.office365.Office365ContactsSyncService" %>

<%@ include file="security.jspf" %>
<%
  if (!require_login()) return;
%>

<%!
  private static final String S_TENANT_UUID = "tenant.uuid";
  private static final String CSRF_SESSION_KEY = "CSRF_TOKEN";

  private static String safe(String s) { return s == null ? "" : s; }

  private static String esc(String s) {
    if (s == null) return "";
    return s.replace("&","&amp;")
            .replace("<","&lt;")
            .replace(">","&gt;")
            .replace("\"","&quot;")
            .replace("'","&#39;");
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

  private static contacts.ContactInput toInput(jakarta.servlet.http.HttpServletRequest req) {
    contacts.ContactInput in = new contacts.ContactInput();
    in.displayName = safe(req.getParameter("display_name")).trim();
    in.givenName = safe(req.getParameter("given_name")).trim();
    in.middleName = safe(req.getParameter("middle_name")).trim();
    in.surname = safe(req.getParameter("surname")).trim();
    in.companyName = safe(req.getParameter("company_name")).trim();
    in.jobTitle = safe(req.getParameter("job_title")).trim();
    in.emailPrimary = safe(req.getParameter("email_primary")).trim();
    in.emailSecondary = safe(req.getParameter("email_secondary")).trim();
    in.emailTertiary = safe(req.getParameter("email_tertiary")).trim();
    in.businessPhone = safe(req.getParameter("business_phone")).trim();
    in.businessPhone2 = safe(req.getParameter("business_phone_2")).trim();
    in.mobilePhone = safe(req.getParameter("mobile_phone")).trim();
    in.homePhone = safe(req.getParameter("home_phone")).trim();
    in.otherPhone = safe(req.getParameter("other_phone")).trim();
    in.website = safe(req.getParameter("website")).trim();
    in.street = safe(req.getParameter("street")).trim();
    in.city = safe(req.getParameter("city")).trim();
    in.state = safe(req.getParameter("state")).trim();
    in.postalCode = safe(req.getParameter("postal_code")).trim();
    in.country = safe(req.getParameter("country")).trim();
    in.streetSecondary = safe(req.getParameter("street_secondary")).trim();
    in.citySecondary = safe(req.getParameter("city_secondary")).trim();
    in.stateSecondary = safe(req.getParameter("state_secondary")).trim();
    in.postalCodeSecondary = safe(req.getParameter("postal_code_secondary")).trim();
    in.countrySecondary = safe(req.getParameter("country_secondary")).trim();
    in.streetTertiary = safe(req.getParameter("street_tertiary")).trim();
    in.cityTertiary = safe(req.getParameter("city_tertiary")).trim();
    in.stateTertiary = safe(req.getParameter("state_tertiary")).trim();
    in.postalCodeTertiary = safe(req.getParameter("postal_code_tertiary")).trim();
    in.countryTertiary = safe(req.getParameter("country_tertiary")).trim();
    in.notes = safe(req.getParameter("notes"));
    return in;
  }

  private static String nonBlank(String a, String b) {
    String x = safe(a).trim();
    if (!x.isBlank()) return x;
    return safe(b).trim();
  }

  private static String nonBlank(String a, String b, String c) {
    String x = nonBlank(a, b);
    if (!x.isBlank()) return x;
    return safe(c).trim();
  }

  private static String contactSummary(contacts.ContactRec c) {
    if (c == null) return "";
    String email = nonBlank(c.emailPrimary, c.emailSecondary, c.emailTertiary);
    String phone = nonBlank(c.mobilePhone, c.businessPhone, c.homePhone);
    if (!email.isBlank() && !phone.isBlank()) return email + " • " + phone;
    if (!email.isBlank()) return email;
    if (!phone.isBlank()) return phone;
    return "";
  }

  private static void writeVCardDownload(jakarta.servlet.http.HttpServletResponse resp, String fileName, String payload) throws Exception {
    String safeName = safe(fileName).trim();
    if (safeName.isBlank()) safeName = "contacts.vcf";
    if (!safeName.toLowerCase(Locale.ROOT).endsWith(".vcf")) safeName = safeName + ".vcf";
    byte[] bytes = safe(payload).getBytes(StandardCharsets.UTF_8);
    resp.reset();
    resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
    resp.setContentType("text/vcard; charset=UTF-8");
    resp.setHeader("Content-Disposition", "attachment; filename=\"" + safeName.replace("\"", "") + "\"");
    resp.setContentLength(bytes.length);
    resp.getOutputStream().write(bytes);
  }
%>

<%
  String ctx = request.getContextPath();
  if (ctx == null) ctx = "";

  String tenantUuid = safe((String)session.getAttribute(S_TENANT_UUID)).trim();
  if (tenantUuid.isBlank()) {
    response.sendRedirect(ctx + "/tenant_login.jsp");
    return;
  }

  contacts contactStore = contacts.defaultStore();
  matter_contacts linkStore = matter_contacts.defaultStore();
  matters matterStore = matters.defaultStore();

  String csrfToken = csrfForRender(request);
  String message = null;
  String error = null;

  try { contactStore.ensure(tenantUuid); } catch (Exception ignored) {}
  try { linkStore.ensure(tenantUuid); } catch (Exception ignored) {}
  try { matterStore.ensure(tenantUuid); } catch (Exception ignored) {}

  if ("POST".equalsIgnoreCase(request.getMethod())) {
    String action = safe(request.getParameter("action")).trim();

    if ("create_contact".equalsIgnoreCase(action)) {
      try {
        contacts.ContactRec created = contactStore.createNative(tenantUuid, toInput(request));
        String matterUuid = safe(request.getParameter("matter_uuid")).trim();
        if (!matterUuid.isBlank()) {
          linkStore.replaceNativeLinksForContact(tenantUuid, created.uuid, List.of(matterUuid));
        }
        response.sendRedirect(ctx + "/contacts.jsp?created=1");
        return;
      } catch (Exception ex) {
        error = "Unable to create contact: " + safe(ex.getMessage());
        application.log("[contacts] create failed: " + shortErr(ex), ex);
      }
    }

    if ("save_contact".equalsIgnoreCase(action)) {
      String contactUuid = safe(request.getParameter("uuid")).trim();
      try {
        contacts.ContactRec current = contactStore.getByUuid(tenantUuid, contactUuid);
        if (current == null) throw new IllegalStateException("Contact not found.");
        if (contacts.isExternalReadOnly(current)) {
          throw new IllegalStateException(contacts.readOnlyMessage(current));
        }
        contactStore.updateNative(tenantUuid, contactUuid, toInput(request));
        String matterUuid = safe(request.getParameter("matter_uuid")).trim();
        if (matterUuid.isBlank()) linkStore.replaceNativeLinksForContact(tenantUuid, contactUuid, List.of());
        else linkStore.replaceNativeLinksForContact(tenantUuid, contactUuid, List.of(matterUuid));
        response.sendRedirect(ctx + "/contacts.jsp?saved=1");
        return;
      } catch (Exception ex) {
        error = "Unable to save contact: " + safe(ex.getMessage());
        application.log("[contacts] save failed: " + shortErr(ex), ex);
      }
    }

    if ("archive_contact".equalsIgnoreCase(action)) {
      String contactUuid = safe(request.getParameter("uuid")).trim();
      try {
        contactStore.trash(tenantUuid, contactUuid);
        response.sendRedirect(ctx + "/contacts.jsp?archived=1");
        return;
      } catch (Exception ex) {
        error = "Unable to archive contact: " + safe(ex.getMessage());
      }
    }

    if ("restore_contact".equalsIgnoreCase(action)) {
      String contactUuid = safe(request.getParameter("uuid")).trim();
      try {
        contactStore.restore(tenantUuid, contactUuid);
        response.sendRedirect(ctx + "/contacts.jsp?restored=1");
        return;
      } catch (Exception ex) {
        error = "Unable to restore contact: " + safe(ex.getMessage());
      }
    }

    if ("sync_clio_contacts_now".equalsIgnoreCase(action)) {
      try {
        ClioIntegrationService.ContactSyncResult result = new ClioIntegrationService().syncContactsToMatters(tenantUuid, true);
        if (result.ok) {
          message = "Clio contact sync completed. Upserted " + result.contactsUpserted
                  + " contact(s); applied " + result.linksApplied + " matter-contact link(s).";
        } else {
          error = "Clio contact sync finished with issues: " + safe(result.error);
        }
      } catch (Exception ex) {
        error = "Unable to sync Clio contacts: " + safe(ex.getMessage());
      }
    }

    if ("sync_office365_contacts_now".equalsIgnoreCase(action)) {
      try {
        Office365ContactsSyncService.SyncResult result = new Office365ContactsSyncService().syncContacts(tenantUuid, true);
        if (result.ok) {
          message = "Office 365 contact sync completed. Upserted " + result.contactsUpserted
                  + " contact(s) from " + result.sourcesProcessed + " source(s).";
        } else {
          error = "Office 365 contact sync finished with issues: " + safe(result.error);
        }
      } catch (Exception ex) {
        error = "Unable to sync Office 365 contacts: " + safe(ex.getMessage());
      }
    }

    if ("import_vcards".equalsIgnoreCase(action)) {
      String payload = safe(request.getParameter("vcard_payload"));
      String matterUuid = safe(request.getParameter("import_matter_uuid")).trim();
      try {
        List<contacts.ContactInput> imports = contact_vcards.parseMany(payload);
        if (imports.isEmpty()) throw new IllegalArgumentException("No valid vCard entries were found.");
        int imported = 0;
        for (int i = 0; i < imports.size(); i++) {
          contacts.ContactInput in = imports.get(i);
          if (in == null) continue;
          contacts.ContactRec created = contactStore.createNative(tenantUuid, in);
          if (!matterUuid.isBlank() && created != null && !safe(created.uuid).trim().isBlank()) {
            linkStore.replaceNativeLinksForContact(tenantUuid, created.uuid, List.of(matterUuid));
          }
          imported++;
        }
        response.sendRedirect(ctx + "/contacts.jsp?imported=" + imported);
        return;
      } catch (Exception ex) {
        error = "Unable to import vCards: " + safe(ex.getMessage());
      }
    }
  }

  if ("1".equals(request.getParameter("created"))) message = "Contact created.";
  if ("1".equals(request.getParameter("saved"))) message = "Contact saved.";
  if ("1".equals(request.getParameter("archived"))) message = "Contact archived.";
  if ("1".equals(request.getParameter("restored"))) message = "Contact restored.";
  String importedCount = safe(request.getParameter("imported")).trim();
  if (!importedCount.isBlank()) message = "Imported " + importedCount + " contact(s) from vCard.";

  String q = safe(request.getParameter("q")).trim();
  String show = safe(request.getParameter("show")).trim().toLowerCase(Locale.ROOT);
  if (show.isBlank()) show = "active";
  String reqAction = safe(request.getParameter("action")).trim().toLowerCase(Locale.ROOT);
  String sourceFilter = safe(request.getParameter("source")).trim().toLowerCase(Locale.ROOT);
  if (!"native".equals(sourceFilter) && !"clio".equals(sourceFilter)
      && !"office365".equals(sourceFilter) && !"external".equals(sourceFilter)) sourceFilter = "all";
  String matterFilter = safe(request.getParameter("matter")).trim();

  List<matters.MatterRec> mattersAll = new ArrayList<matters.MatterRec>();
  try { mattersAll = matterStore.listAll(tenantUuid); } catch (Exception ignored) {}

  LinkedHashMap<String, String> matterLabelByUuid = new LinkedHashMap<String, String>();
  for (matters.MatterRec m : mattersAll) {
    if (m == null) continue;
    matterLabelByUuid.put(safe(m.uuid), safe(m.label));
  }

  List<contacts.ContactRec> contactsAll = new ArrayList<contacts.ContactRec>();
  try { contactsAll = contactStore.listAll(tenantUuid); } catch (Exception ignored) {}

  List<matter_contacts.LinkRec> linksAll = new ArrayList<matter_contacts.LinkRec>();
  try { linksAll = linkStore.listAll(tenantUuid); } catch (Exception ignored) {}

  Map<String, List<matter_contacts.LinkRec>> linksByContact = new HashMap<String, List<matter_contacts.LinkRec>>();
  for (matter_contacts.LinkRec link : linksAll) {
    if (link == null) continue;
    String contactUuid = safe(link.contactUuid).trim();
    if (contactUuid.isBlank()) continue;
    linksByContact.computeIfAbsent(contactUuid, k -> new ArrayList<matter_contacts.LinkRec>()).add(link);
  }

  String ql = q.toLowerCase(Locale.ROOT);
  List<contacts.ContactRec> filtered = new ArrayList<contacts.ContactRec>();
  for (contacts.ContactRec c : contactsAll) {
    if (c == null) continue;
    if ("active".equals(show) && c.trashed) continue;
    if ("archived".equals(show) && !c.trashed) continue;

    boolean externalReadOnly = contacts.isExternalReadOnly(c);
    String source = contacts.sourceType(c);
    if ("native".equals(sourceFilter) && !"native".equals(source)) continue;
    if ("clio".equals(sourceFilter) && !"clio".equals(source)) continue;
    if ("office365".equals(sourceFilter) && !source.startsWith("office365")) continue;
    if ("external".equals(sourceFilter) && !externalReadOnly) continue;

    List<matter_contacts.LinkRec> contactLinks = linksByContact.getOrDefault(safe(c.uuid), List.of());
    if (!matterFilter.isBlank()) {
      boolean hasMatter = false;
      for (matter_contacts.LinkRec link : contactLinks) {
        if (matterFilter.equals(safe(link.matterUuid))) { hasMatter = true; break; }
      }
      if (!hasMatter) continue;
    }

    if (!q.isBlank()) {
      StringBuilder hay = new StringBuilder(512);
      hay.append(safe(c.displayName)).append(" ")
         .append(safe(c.givenName)).append(" ")
         .append(safe(c.middleName)).append(" ")
         .append(safe(c.surname)).append(" ")
         .append(safe(c.companyName)).append(" ")
         .append(safe(c.emailPrimary)).append(" ")
         .append(safe(c.emailSecondary)).append(" ")
         .append(safe(c.emailTertiary)).append(" ")
         .append(safe(c.mobilePhone)).append(" ")
         .append(safe(c.businessPhone)).append(" ")
         .append(safe(c.businessPhone2)).append(" ")
         .append(safe(c.homePhone)).append(" ")
         .append(safe(c.otherPhone)).append(" ")
         .append(safe(c.street)).append(" ")
         .append(safe(c.city)).append(" ")
         .append(safe(c.state)).append(" ")
         .append(safe(c.postalCode)).append(" ")
         .append(safe(c.country)).append(" ")
         .append(safe(c.streetSecondary)).append(" ")
         .append(safe(c.citySecondary)).append(" ")
         .append(safe(c.stateSecondary)).append(" ")
         .append(safe(c.postalCodeSecondary)).append(" ")
         .append(safe(c.countrySecondary)).append(" ")
         .append(safe(c.streetTertiary)).append(" ")
         .append(safe(c.cityTertiary)).append(" ")
         .append(safe(c.stateTertiary)).append(" ")
         .append(safe(c.postalCodeTertiary)).append(" ")
         .append(safe(c.countryTertiary)).append(" ");
      for (matter_contacts.LinkRec link : contactLinks) {
        if (link == null) continue;
        hay.append(safe(matterLabelByUuid.get(safe(link.matterUuid)))).append(" ");
      }
      if (!hay.toString().toLowerCase(Locale.ROOT).contains(ql)) continue;
    }
    filtered.add(c);
  }

  if ("GET".equalsIgnoreCase(request.getMethod())) {
    if ("export_vcard".equals(reqAction)) {
      String contactUuid = safe(request.getParameter("uuid")).trim();
      contacts.ContactRec rec = contactStore.getByUuid(tenantUuid, contactUuid);
      if (rec == null) {
        response.sendError(404, "Contact not found.");
        return;
      }
      String baseName = safe(rec.displayName).trim().replaceAll("[^A-Za-z0-9._-]+", "_");
      if (baseName.isBlank()) baseName = "contact";
      writeVCardDownload(response, baseName + ".vcf", contact_vcards.exportOne(rec));
      return;
    }
    if ("export_vcard_filtered".equals(reqAction) || "export_vcard_all".equals(reqAction)) {
      List<contacts.ContactRec> rows = "export_vcard_all".equals(reqAction) ? contactsAll : filtered;
      if (rows == null || rows.isEmpty()) {
        response.sendError(400, "No contacts available to export.");
        return;
      }
      String stamp = String.valueOf(Instant.now().toEpochMilli());
      String name = "export_vcard_all".equals(reqAction) ? ("contacts-all-" + stamp + ".vcf") : ("contacts-filtered-" + stamp + ".vcf");
      writeVCardDownload(response, name, contact_vcards.exportMany(rows));
      return;
    }
  }

  String baseQs =
    "q=" + URLEncoder.encode(q, StandardCharsets.UTF_8) +
    "&show=" + URLEncoder.encode(show, StandardCharsets.UTF_8) +
    "&source=" + URLEncoder.encode(sourceFilter, StandardCharsets.UTF_8) +
    "&matter=" + URLEncoder.encode(matterFilter, StandardCharsets.UTF_8);
%>

<jsp:include page="header.jsp" />

<section class="card">
  <div class="section-head">
    <div>
      <h1 style="margin:0;">Contacts</h1>
      <div class="meta">Manage native contacts and externally synced contacts linked to matters. Externally synced contacts are read-only in Controversies.</div>
      <div class="meta" style="margin-top:4px;">Field names align with Microsoft Graph contact conventions (`displayName`, `givenName`, `surname`, email/phone/address slots) and vCard interoperability.</div>
    </div>
    <div style="display:flex; gap:8px; flex-wrap:wrap;">
      <a class="btn btn-ghost" href="<%= ctx %>/contacts.jsp?action=export_vcard_all">Export All vCards</a>
      <form method="post" action="<%= ctx %>/contacts.jsp" style="margin:0;">
        <input type="hidden" name="action" value="sync_office365_contacts_now" />
        <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
        <button class="btn btn-ghost" type="submit">Sync Office 365 Contacts Now</button>
      </form>
      <form method="post" action="<%= ctx %>/contacts.jsp" style="margin:0;">
        <input type="hidden" name="action" value="sync_clio_contacts_now" />
        <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
        <button class="btn btn-ghost" type="submit">Sync Clio Contacts Now</button>
      </form>
    </div>
  </div>

  <% if (message != null) { %>
    <div class="alert alert-ok" style="margin-top:12px;"><%= esc(message) %></div>
  <% } %>
  <% if (error != null) { %>
    <div class="alert alert-error" style="margin-top:12px;"><%= esc(error) %></div>
  <% } %>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Create Native Contact</h2>
  <form class="form" method="post" action="<%= ctx %>/contacts.jsp">
    <input type="hidden" name="action" value="create_contact" />
    <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />

    <div class="grid grid-2">
      <label>Display Name (Graph: displayName)
        <input type="text" name="display_name" placeholder="Alex Taylor" />
      </label>
      <label>Given Name (Graph: givenName)
        <input type="text" name="given_name" />
      </label>
      <label>Middle Name (Graph: middleName)
        <input type="text" name="middle_name" />
      </label>
      <label>Surname (Graph: surname)
        <input type="text" name="surname" />
      </label>
      <label>Company (Graph: companyName)
        <input type="text" name="company_name" />
      </label>
      <label>Job Title (Graph: jobTitle)
        <input type="text" name="job_title" />
      </label>
      <label>Email (primary)
        <input type="email" name="email_primary" />
      </label>
      <label>Email (secondary)
        <input type="email" name="email_secondary" />
      </label>
      <label>Email (tertiary)
        <input type="email" name="email_tertiary" />
      </label>
      <label>Business Phone 1
        <input type="text" name="business_phone" />
      </label>
      <label>Business Phone 2
        <input type="text" name="business_phone_2" />
      </label>
      <label>Mobile Phone
        <input type="text" name="mobile_phone" />
      </label>
      <label>Home Phone
        <input type="text" name="home_phone" />
      </label>
      <label>Other Phone
        <input type="text" name="other_phone" />
      </label>
      <label>Website
        <input type="url" name="website" />
      </label>
      <label>Street
        <input type="text" name="street" />
      </label>
      <label>City
        <input type="text" name="city" />
      </label>
      <label>State/Province
        <input type="text" name="state" />
      </label>
      <label>Postal Code
        <input type="text" name="postal_code" />
      </label>
      <label>Country/Region
        <input type="text" name="country" />
      </label>
      <label>Street (address 2)
        <input type="text" name="street_secondary" />
      </label>
      <label>City (address 2)
        <input type="text" name="city_secondary" />
      </label>
      <label>State/Province (address 2)
        <input type="text" name="state_secondary" />
      </label>
      <label>Postal Code (address 2)
        <input type="text" name="postal_code_secondary" />
      </label>
      <label>Country/Region (address 2)
        <input type="text" name="country_secondary" />
      </label>
      <label>Street (address 3)
        <input type="text" name="street_tertiary" />
      </label>
      <label>City (address 3)
        <input type="text" name="city_tertiary" />
      </label>
      <label>State/Province (address 3)
        <input type="text" name="state_tertiary" />
      </label>
      <label>Postal Code (address 3)
        <input type="text" name="postal_code_tertiary" />
      </label>
      <label>Country/Region (address 3)
        <input type="text" name="country_tertiary" />
      </label>
      <label>Linked Case
        <select name="matter_uuid">
          <option value=""></option>
          <% for (matters.MatterRec m : mattersAll) {
               if (m == null || m.trashed) continue;
          %>
            <option value="<%= esc(safe(m.uuid)) %>"><%= esc(safe(m.label)) %></option>
          <% } %>
        </select>
      </label>
    </div>

    <label>Notes
      <textarea name="notes" rows="3"></textarea>
    </label>

    <div class="actions" style="display:flex; gap:10px; margin-top:10px;">
      <button class="btn" type="submit">Create Contact</button>
    </div>
  </form>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">vCard Import / Export</h2>
  <div class="meta" style="margin-bottom:10px;">Bulk import supports one or many cards in one payload (`BEGIN:VCARD` ... `END:VCARD`).</div>
  <form class="form" method="post" action="<%= ctx %>/contacts.jsp">
    <input type="hidden" name="action" value="import_vcards" />
    <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
    <label>Load `.vcf` file (single or bulk)
      <input type="file" id="vcard_file_input" accept=".vcf,text/vcard,text/x-vcard" />
    </label>
    <label>Link imported contacts to case (optional)
      <select name="import_matter_uuid">
        <option value=""></option>
        <% for (matters.MatterRec m : mattersAll) {
             if (m == null || m.trashed) continue;
        %>
          <option value="<%= esc(safe(m.uuid)) %>"><%= esc(safe(m.label)) %></option>
        <% } %>
      </select>
    </label>
    <label>vCard payload (single or bulk)
      <textarea id="vcard_payload_textarea" name="vcard_payload" rows="8" placeholder="BEGIN:VCARD&#10;VERSION:3.0&#10;FN:Alex Taylor&#10;EMAIL:alex@example.com&#10;END:VCARD"></textarea>
    </label>
    <div class="actions" style="display:flex; gap:10px; margin-top:10px; flex-wrap:wrap;">
      <button class="btn" type="submit">Import vCards</button>
      <a class="btn btn-ghost" href="<%= ctx %>/contacts.jsp?action=export_vcard_filtered&q=<%= URLEncoder.encode(q, StandardCharsets.UTF_8) %>&show=<%= URLEncoder.encode(show, StandardCharsets.UTF_8) %>&source=<%= URLEncoder.encode(sourceFilter, StandardCharsets.UTF_8) %>&matter=<%= URLEncoder.encode(matterFilter, StandardCharsets.UTF_8) %>">Export Filtered vCards</a>
    </div>
  </form>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Find Contacts</h2>
  <form class="form" method="get" action="<%= ctx %>/contacts.jsp">
    <div class="grid" style="display:grid; grid-template-columns: 3fr 1fr 1fr 1fr auto; gap:12px;">
      <label>Search
        <input type="text" name="q" value="<%= esc(q) %>" placeholder="Name, email, phone, or matter" />
      </label>
      <label>Status
        <select name="show">
          <option value="active" <%= "active".equals(show) ? "selected" : "" %>>Active</option>
          <option value="archived" <%= "archived".equals(show) ? "selected" : "" %>>Archived</option>
          <option value="all" <%= "all".equals(show) ? "selected" : "" %>>All</option>
        </select>
      </label>
      <label>Source
        <select name="source">
          <option value="all" <%= "all".equals(sourceFilter) ? "selected" : "" %>>All</option>
          <option value="native" <%= "native".equals(sourceFilter) ? "selected" : "" %>>Native</option>
          <option value="external" <%= "external".equals(sourceFilter) ? "selected" : "" %>>External</option>
          <option value="office365" <%= "office365".equals(sourceFilter) ? "selected" : "" %>>Office 365</option>
          <option value="clio" <%= "clio".equals(sourceFilter) ? "selected" : "" %>>Clio</option>
        </select>
      </label>
      <label>Matter
        <select name="matter">
          <option value=""></option>
          <% for (matters.MatterRec m : mattersAll) { if (m == null) continue; %>
            <option value="<%= esc(safe(m.uuid)) %>" <%= safe(m.uuid).equals(matterFilter) ? "selected" : "" %>><%= esc(safe(m.label)) %></option>
          <% } %>
        </select>
      </label>
      <label>
        <span>&nbsp;</span>
        <button class="btn" type="submit">Apply</button>
      </label>
    </div>
  </form>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Contact List</h2>
  <div class="table-wrap">
    <table class="table">
      <thead>
        <tr>
          <th>Contact</th>
          <th>Source</th>
          <th>Linked Matters</th>
          <th style="width:380px;">Actions</th>
        </tr>
      </thead>
      <tbody>
      <%
        if (filtered.isEmpty()) {
      %>
        <tr><td colspan="4" class="muted">No contacts match your filter.</td></tr>
      <%
        } else {
          for (int i = 0; i < filtered.size(); i++) {
            contacts.ContactRec c = filtered.get(i);
            if (c == null) continue;
            String contactUuid = safe(c.uuid);
            boolean externalReadOnly = contacts.isExternalReadOnly(c);
            String sourceLabel = contacts.sourceDisplayName(c);
            String rowId = "edit_contact_" + i;
            List<matter_contacts.LinkRec> cLinks = linksByContact.getOrDefault(contactUuid, List.of());
            String selectedNativeMatter = "";
            if (!externalReadOnly) {
              for (matter_contacts.LinkRec link : cLinks) {
                if (link == null) continue;
                if ("native".equalsIgnoreCase(safe(link.source))) {
                  selectedNativeMatter = safe(link.matterUuid);
                  break;
                }
              }
            }
      %>
        <tr>
          <td>
            <strong><%= esc(safe(c.displayName)) %></strong>
            <div class="muted" style="margin-top:4px;"><%= esc(contactSummary(c)) %></div>
            <% if (c.trashed) { %>
              <div class="muted" style="margin-top:4px;">Archived</div>
            <% } %>
          </td>
          <td>
            <% if (externalReadOnly) { %>
              <span class="badge badge-info"><%= esc(sourceLabel) %></span>
              <div class="muted" style="margin-top:6px;">Read-only. Edit in <%= esc(sourceLabel) %>.</div>
            <% } else { %>
              <span class="badge">Native</span>
            <% } %>
          </td>
          <td>
            <% if (cLinks.isEmpty()) { %>
              <span class="muted">None</span>
            <% } else {
                 for (matter_contacts.LinkRec link : cLinks) {
                   if (link == null) continue;
                   String label = safe(matterLabelByUuid.get(safe(link.matterUuid)));
                   if (label.isBlank()) label = safe(link.matterUuid);
            %>
              <div><%= esc(label) %></div>
            <%   }
               } %>
          </td>
          <td>
            <% if (!externalReadOnly) { %>
              <button class="btn btn-ghost" type="button" onclick="toggleEdit('<%= rowId %>')">Edit</button>
            <% } else { %>
              <button class="btn btn-ghost" type="button" disabled title="Edit in source system">Edit (read-only)</button>
            <% } %>
            <a class="btn btn-ghost" href="<%= ctx %>/contacts.jsp?action=export_vcard&uuid=<%= URLEncoder.encode(contactUuid, StandardCharsets.UTF_8) %>">Export vCard</a>
            <% if (!c.trashed) { %>
              <% if (!externalReadOnly) { %>
                <form method="post" action="<%= ctx %>/contacts.jsp?<%= baseQs %>" style="display:inline;">
                  <input type="hidden" name="action" value="archive_contact" />
                  <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
                  <input type="hidden" name="uuid" value="<%= esc(contactUuid) %>" />
                  <button class="btn btn-ghost" type="submit" onclick="return confirm('Archive this contact?');">Archive</button>
                </form>
              <% } else { %>
                <button class="btn btn-ghost" type="button" disabled title="Read-only contact">Archive</button>
              <% } %>
            <% } else { %>
              <% if (!externalReadOnly) { %>
                <form method="post" action="<%= ctx %>/contacts.jsp?<%= baseQs %>" style="display:inline;">
                  <input type="hidden" name="action" value="restore_contact" />
                  <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
                  <input type="hidden" name="uuid" value="<%= esc(contactUuid) %>" />
                  <button class="btn" type="submit">Restore</button>
                </form>
              <% } else { %>
                <button class="btn btn-ghost" type="button" disabled title="Read-only contact">Restore</button>
              <% } %>
            <% } %>
          </td>
        </tr>

        <tr id="<%= rowId %>" style="display:none;">
          <td colspan="4">
            <% if (externalReadOnly) { %>
              <div class="alert alert-error" style="margin:8px 0;"><%= esc(contacts.readOnlyMessage(c)) %> Run sync again after edits in the source system.</div>
            <% } else { %>
              <div class="card" style="margin:8px 0; padding:14px; background:rgba(0,0,0,0.02);">
                <form class="form" method="post" action="<%= ctx %>/contacts.jsp?<%= baseQs %>">
                  <input type="hidden" name="action" value="save_contact" />
                  <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
                  <input type="hidden" name="uuid" value="<%= esc(contactUuid) %>" />

                  <div class="grid grid-2">
                    <label>Display Name
                      <input type="text" name="display_name" value="<%= esc(safe(c.displayName)) %>" />
                    </label>
                    <label>Given Name
                      <input type="text" name="given_name" value="<%= esc(safe(c.givenName)) %>" />
                    </label>
                    <label>Middle Name
                      <input type="text" name="middle_name" value="<%= esc(safe(c.middleName)) %>" />
                    </label>
                    <label>Surname
                      <input type="text" name="surname" value="<%= esc(safe(c.surname)) %>" />
                    </label>
                    <label>Company
                      <input type="text" name="company_name" value="<%= esc(safe(c.companyName)) %>" />
                    </label>
                    <label>Job Title
                      <input type="text" name="job_title" value="<%= esc(safe(c.jobTitle)) %>" />
                    </label>
                    <label>Email (primary)
                      <input type="email" name="email_primary" value="<%= esc(safe(c.emailPrimary)) %>" />
                    </label>
                    <label>Email (secondary)
                      <input type="email" name="email_secondary" value="<%= esc(safe(c.emailSecondary)) %>" />
                    </label>
                    <label>Email (tertiary)
                      <input type="email" name="email_tertiary" value="<%= esc(safe(c.emailTertiary)) %>" />
                    </label>
                    <label>Business Phone 1
                      <input type="text" name="business_phone" value="<%= esc(safe(c.businessPhone)) %>" />
                    </label>
                    <label>Business Phone 2
                      <input type="text" name="business_phone_2" value="<%= esc(safe(c.businessPhone2)) %>" />
                    </label>
                    <label>Mobile Phone
                      <input type="text" name="mobile_phone" value="<%= esc(safe(c.mobilePhone)) %>" />
                    </label>
                    <label>Home Phone
                      <input type="text" name="home_phone" value="<%= esc(safe(c.homePhone)) %>" />
                    </label>
                    <label>Other Phone
                      <input type="text" name="other_phone" value="<%= esc(safe(c.otherPhone)) %>" />
                    </label>
                    <label>Website
                      <input type="url" name="website" value="<%= esc(safe(c.website)) %>" />
                    </label>
                    <label>Street
                      <input type="text" name="street" value="<%= esc(safe(c.street)) %>" />
                    </label>
                    <label>City
                      <input type="text" name="city" value="<%= esc(safe(c.city)) %>" />
                    </label>
                    <label>State/Province
                      <input type="text" name="state" value="<%= esc(safe(c.state)) %>" />
                    </label>
                    <label>Postal Code
                      <input type="text" name="postal_code" value="<%= esc(safe(c.postalCode)) %>" />
                    </label>
                    <label>Country/Region
                      <input type="text" name="country" value="<%= esc(safe(c.country)) %>" />
                    </label>
                    <label>Street (address 2)
                      <input type="text" name="street_secondary" value="<%= esc(safe(c.streetSecondary)) %>" />
                    </label>
                    <label>City (address 2)
                      <input type="text" name="city_secondary" value="<%= esc(safe(c.citySecondary)) %>" />
                    </label>
                    <label>State/Province (address 2)
                      <input type="text" name="state_secondary" value="<%= esc(safe(c.stateSecondary)) %>" />
                    </label>
                    <label>Postal Code (address 2)
                      <input type="text" name="postal_code_secondary" value="<%= esc(safe(c.postalCodeSecondary)) %>" />
                    </label>
                    <label>Country/Region (address 2)
                      <input type="text" name="country_secondary" value="<%= esc(safe(c.countrySecondary)) %>" />
                    </label>
                    <label>Street (address 3)
                      <input type="text" name="street_tertiary" value="<%= esc(safe(c.streetTertiary)) %>" />
                    </label>
                    <label>City (address 3)
                      <input type="text" name="city_tertiary" value="<%= esc(safe(c.cityTertiary)) %>" />
                    </label>
                    <label>State/Province (address 3)
                      <input type="text" name="state_tertiary" value="<%= esc(safe(c.stateTertiary)) %>" />
                    </label>
                    <label>Postal Code (address 3)
                      <input type="text" name="postal_code_tertiary" value="<%= esc(safe(c.postalCodeTertiary)) %>" />
                    </label>
                    <label>Country/Region (address 3)
                      <input type="text" name="country_tertiary" value="<%= esc(safe(c.countryTertiary)) %>" />
                    </label>
                    <label>Linked Case
                      <select name="matter_uuid">
                        <option value=""></option>
                        <% for (matters.MatterRec m : mattersAll) {
                             if (m == null || m.trashed) continue;
                        %>
                          <option value="<%= esc(safe(m.uuid)) %>" <%= safe(m.uuid).equals(selectedNativeMatter) ? "selected" : "" %>><%= esc(safe(m.label)) %></option>
                        <% } %>
                      </select>
                    </label>
                  </div>

                  <label>Notes
                    <textarea name="notes" rows="3"><%= esc(safe(c.notes)) %></textarea>
                  </label>

                  <div class="actions" style="display:flex; gap:10px; margin-top:10px;">
                    <button class="btn" type="submit">Save Contact</button>
                    <button type="button" class="btn btn-ghost" onclick="toggleEdit('<%= rowId %>')">Cancel</button>
                  </div>
                </form>
              </div>
            <% } %>
          </td>
        </tr>
      <%
          }
        }
      %>
      </tbody>
    </table>
  </div>
</section>

<script>
  function toggleEdit(id) {
    var row = document.getElementById(id);
    if (!row) return;
    row.style.display = (row.style.display === "none" || row.style.display === "") ? "table-row" : "none";
  }

  (function () {
    var fileInput = document.getElementById("vcard_file_input");
    var payload = document.getElementById("vcard_payload_textarea");
    if (!fileInput || !payload) return;
    fileInput.addEventListener("change", function () {
      if (!fileInput.files || fileInput.files.length === 0) return;
      var f = fileInput.files[0];
      var reader = new FileReader();
      reader.onload = function () {
        payload.value = String(reader.result || "");
      };
      reader.readAsText(f);
    });
  })();
</script>

<jsp:include page="footer.jsp" />
