<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" isELIgnored="true" %>

<%@ page import="java.io.ByteArrayInputStream" %>
<%@ page import="java.io.StringReader" %>
<%@ page import="java.io.StringWriter" %>
<%@ page import="java.net.URLEncoder" %>
<%@ page import="java.nio.charset.StandardCharsets" %>
<%@ page import="java.nio.file.Path" %>
<%@ page import="java.time.LocalDate" %>
<%@ page import="java.time.LocalTime" %>
<%@ page import="java.time.ZoneId" %>
<%@ page import="java.util.ArrayList" %>
<%@ page import="java.util.HashSet" %>
<%@ page import="java.util.LinkedHashMap" %>
<%@ page import="java.util.List" %>
<%@ page import="java.util.Locale" %>
<%@ page import="java.util.Map" %>

<%@ page import="javax.xml.XMLConstants" %>
<%@ page import="javax.xml.parsers.DocumentBuilder" %>
<%@ page import="javax.xml.parsers.DocumentBuilderFactory" %>
<%@ page import="javax.xml.transform.OutputKeys" %>
<%@ page import="javax.xml.transform.Transformer" %>
<%@ page import="javax.xml.transform.TransformerFactory" %>
<%@ page import="javax.xml.transform.dom.DOMSource" %>
<%@ page import="javax.xml.transform.stream.StreamResult" %>

<%@ page import="org.w3c.dom.Document" %>
<%@ page import="org.w3c.dom.Element" %>
<%@ page import="org.w3c.dom.Node" %>
<%@ page import="org.w3c.dom.NodeList" %>
<%@ page import="org.xml.sax.InputSource" %>

<%@ page import="net.familylawandprobate.controversies.class_dealine_calculator" %>
<%@ page import="net.familylawandprobate.controversies.class_holiday_calculator" %>

<%@ include file="security.jspf" %>
<%
  if (!require_login()) return;
%>

<%!
  private static final String S_TENANT_UUID = "tenant.uuid";
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

  private static ZoneId parseZone(String raw, String fallback) {
    String v = safe(raw).trim();
    if (v.isBlank()) v = safe(fallback).trim();
    try {
      return ZoneId.of(v);
    } catch (Exception ignored) {
      return ZoneId.of("America/Chicago");
    }
  }

  private static class_dealine_calculator.Jurisdiction parseJurisdiction(String raw) {
    String v = safe(raw).trim().toUpperCase(Locale.ROOT);
    if (v.isBlank()) return class_dealine_calculator.Jurisdiction.TEXAS_CIVIL;
    try {
      return class_dealine_calculator.Jurisdiction.valueOf(v);
    } catch (Exception ignored) {
      return class_dealine_calculator.Jurisdiction.TEXAS_CIVIL;
    }
  }

  private static DocumentBuilder secureBuilder() throws Exception {
    DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
    f.setNamespaceAware(false);
    f.setXIncludeAware(false);
    f.setExpandEntityReferences(false);
    f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    f.setFeature("http://xml.org/sax/features/external-general-entities", false);
    f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
    DocumentBuilder b = f.newDocumentBuilder();
    b.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
    return b;
  }

  private static String applyTriggerDates(String xml, Map<String, String> overrides) throws Exception {
    String text = safe(xml);
    if (text.isBlank() || overrides == null || overrides.isEmpty()) return text;

    DocumentBuilder b = secureBuilder();
    Document d = b.parse(new InputSource(new StringReader(text)));
    d.getDocumentElement().normalize();

    Element root = d.getDocumentElement();
    Element triggersEl = null;
    NodeList rootKids = root.getChildNodes();
    for (int i = 0; i < rootKids.getLength(); i++) {
      Node n = rootKids.item(i);
      if (!(n instanceof Element)) continue;
      Element el = (Element) n;
      if ("triggers".equals(el.getTagName())) {
        triggersEl = el;
        break;
      }
    }

    if (triggersEl != null) {
      NodeList triggerNodes = triggersEl.getChildNodes();
      for (int i = 0; i < triggerNodes.getLength(); i++) {
        Node n = triggerNodes.item(i);
        if (!(n instanceof Element)) continue;
        Element tr = (Element) n;
        if (!"trigger".equals(tr.getTagName())) continue;
        String id = safe(tr.getAttribute("id")).trim();
        if (id.isBlank()) continue;
        String updated = safe(overrides.get(id)).trim();
        if (updated.isBlank()) continue;
        tr.setAttribute("date", updated);
      }
    }

    NodeList legacyNodes = root.getChildNodes();
    for (int i = 0; i < legacyNodes.getLength(); i++) {
      Node n = legacyNodes.item(i);
      if (!(n instanceof Element)) continue;
      Element tr = (Element) n;
      if (!"trigger".equals(tr.getTagName())) continue;
      String id = safe(tr.getAttribute("id")).trim();
      if (id.isBlank()) id = "trigger";
      String updated = safe(overrides.get(id)).trim();
      if (updated.isBlank()) continue;
      tr.setAttribute("date", updated);
    }

    TransformerFactory tf = TransformerFactory.newInstance();
    try { tf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true); } catch (Exception ignored) {}
    Transformer t = tf.newTransformer();
    t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
    t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
    t.setOutputProperty(OutputKeys.INDENT, "yes");
    try { t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2"); } catch (Exception ignored) {}
    StringWriter sw = new StringWriter();
    t.transform(new DOMSource(d), new StreamResult(sw));
    return sw.toString();
  }
%>

<%
  String ctx = safe(request.getContextPath());
  request.setAttribute("activeNav", "/deadline_calculator.jsp");

  String tenantUuid = safe((String) session.getAttribute(S_TENANT_UUID)).trim();
  if (tenantUuid.isBlank()) {
    response.sendRedirect(ctx + "/tenant_login.jsp");
    return;
  }

  String csrfToken = csrfForRender(request);
  String action = "POST".equalsIgnoreCase(request.getMethod()) ? safe(request.getParameter("action")).trim() : "";

  String message = "";
  String error = "";

  Path deadlinePath = null;
  Path holidayPath = null;
  String deadlineXml = "";
  String holidayXml = "";
  class_dealine_calculator.DeadlineDefinition definition = null;

  int holidayYear = parseIntSafe(request.getParameter("holiday_year"), net.familylawandprobate.controversies.app_clock.today().getYear());
  if (holidayYear < 1900 || holidayYear > 2200) holidayYear = net.familylawandprobate.controversies.app_clock.today().getYear();

  String zoneInput = safe(request.getParameter("zone_id")).trim();
  if (zoneInput.isBlank()) zoneInput = "America/Chicago";
  ZoneId zoneId = parseZone(zoneInput, "America/Chicago");

  class_dealine_calculator.Jurisdiction jurisdiction = parseJurisdiction(request.getParameter("jurisdiction"));
  boolean includeImaginary = boolLike(request.getParameter("include_imaginary"));

  if ("save_deadline_xml".equals(action)) {
    try {
      class_dealine_calculator.writeTenantDeadlineXml(tenantUuid, request.getParameter("deadline_xml"));
      message = "Tenant deadline XML saved.";
    } catch (Exception ex) {
      error = "Unable to save tenant deadline XML: " + safe(ex.getMessage());
    }
  } else if ("save_holiday_xml".equals(action)) {
    try {
      class_holiday_calculator.writeTenantHolidayXml(tenantUuid, request.getParameter("holiday_xml"));
      message = "Tenant holiday XML saved.";
    } catch (Exception ex) {
      error = "Unable to save tenant holiday XML: " + safe(ex.getMessage());
    }
  }

  try {
    deadlinePath = class_dealine_calculator.ensureTenantDeadlineXml(tenantUuid);
    holidayPath = class_holiday_calculator.ensureTenantHolidayXml(tenantUuid);
    deadlineXml = class_dealine_calculator.readTenantDeadlineXml(tenantUuid);
    holidayXml = class_holiday_calculator.readTenantHolidayXml(tenantUuid);
    definition = class_dealine_calculator.parseDefinition(new ByteArrayInputStream(deadlineXml.getBytes(StandardCharsets.UTF_8)));
  } catch (Exception ex) {
    if (error.isBlank()) error = "Unable to load tenant XML files: " + safe(ex.getMessage());
  }

  LinkedHashMap<String, String> triggerValues = new LinkedHashMap<String, String>();
  if (definition != null) {
    for (Map.Entry<String, class_dealine_calculator.TriggerEvent> e : definition.getTriggers().entrySet()) {
      if (e == null || e.getValue() == null) continue;
      triggerValues.put(safe(e.getKey()), e.getValue().getDate().toString());
    }
  }

  ArrayList<class_dealine_calculator.ActionDateInfo> results = new ArrayList<class_dealine_calculator.ActionDateInfo>();
  if ("calculate".equals(action) && definition != null && error.isBlank()) {
    boolean triggersOk = true;
    for (Map.Entry<String, class_dealine_calculator.TriggerEvent> e : definition.getTriggers().entrySet()) {
      if (e == null) continue;
      String id = safe(e.getKey()).trim();
      if (id.isBlank()) continue;
      String posted = safe(request.getParameter("trigger_" + id)).trim();
      if (posted.isBlank()) continue;
      try {
        LocalDate.parse(posted);
        triggerValues.put(id, posted);
      } catch (Exception ex) {
        triggersOk = false;
        error = "Invalid trigger date for " + id + ": " + posted;
        break;
      }
    }

    if (triggersOk) {
      try {
        String effectiveXml = applyTriggerDates(deadlineXml, triggerValues);
        class_dealine_calculator.EngineOptions options = new class_dealine_calculator.EngineOptions(
                jurisdiction,
                zoneId,
                LocalTime.of(23, 59, 59),
                new HashSet<LocalDate>(),
                includeImaginary,
                tenantUuid
        );
        class_dealine_calculator.ActionDateInfo[] calculatedRows = class_dealine_calculator.calculateDeadlines(
                new ByteArrayInputStream(effectiveXml.getBytes(StandardCharsets.UTF_8)),
                options
        );
        for (int i = 0; i < calculatedRows.length; i++) results.add(calculatedRows[i]);
        message = "Calculated " + calculatedRows.length + " deadline date(s).";
      } catch (Exception ex) {
        error = "Unable to calculate deadlines: " + safe(ex.getMessage());
      }
    }
  }

  class_holiday_calculator.HolidayInfo[] holidayRows = new class_holiday_calculator.HolidayInfo[0];
  try {
    holidayRows = class_holiday_calculator.calculateHolidays(tenantUuid, holidayYear);
  } catch (Exception ex) {
    if (error.isBlank()) error = "Unable to load holiday preview: " + safe(ex.getMessage());
  }
%>

<jsp:include page="header.jsp" />

<section class="card">
  <h1 style="margin:0;">Deadline Calculator</h1>
  <div class="meta" style="margin-top:6px;">
    Uses tenant-level XML definitions for both deadline rules and holiday computation.
  </div>
  <div class="meta" style="margin-top:8px;">
    Deadline XML:
    <code><%= esc(deadlinePath == null ? "" : deadlinePath.toString()) %></code><br/>
    Holiday XML:
    <code><%= esc(holidayPath == null ? "" : holidayPath.toString()) %></code>
  </div>

  <% if (!message.isBlank()) { %>
    <div class="alert alert-ok" style="margin-top:12px;"><%= esc(message) %></div>
  <% } %>
  <% if (!error.isBlank()) { %>
    <div class="alert alert-error" style="margin-top:12px;"><%= esc(error) %></div>
  <% } %>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin:0 0 8px 0;">Run Calculation</h2>
  <form method="post" action="<%= ctx %>/deadline_calculator.jsp">
    <input type="hidden" name="action" value="calculate" />
    <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />

    <div class="grid" style="display:grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap:12px;">
      <label>
        <span>Jurisdiction</span>
        <select name="jurisdiction">
          <% for (class_dealine_calculator.Jurisdiction j : class_dealine_calculator.Jurisdiction.values()) { %>
            <option value="<%= esc(j.name()) %>" <%= j == jurisdiction ? "selected" : "" %>><%= esc(j.name()) %></option>
          <% } %>
        </select>
      </label>
      <label>
        <span>Zone ID</span>
        <input type="text" name="zone_id" value="<%= esc(zoneId.getId()) %>" placeholder="America/Chicago" />
      </label>
      <label>
        <span>Holiday Preview Year</span>
        <input type="number" min="1900" max="2200" name="holiday_year" value="<%= holidayYear %>" />
      </label>
      <label>
        <span>Include Imaginary Dates</span><br/>
        <input type="checkbox" name="include_imaginary" value="true" <%= includeImaginary ? "checked" : "" %> />
      </label>
    </div>

    <div style="margin-top:12px; display:grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap:12px;">
      <% if (definition == null || definition.getTriggers().isEmpty()) { %>
        <div class="alert alert-warn">No triggers were found in the deadline XML.</div>
      <% } else {
           for (Map.Entry<String, class_dealine_calculator.TriggerEvent> e : definition.getTriggers().entrySet()) {
             if (e == null || e.getValue() == null) continue;
             String id = safe(e.getKey());
             class_dealine_calculator.TriggerEvent tr = e.getValue();
             String v = safe(triggerValues.get(id));
      %>
        <label>
          <span><%= esc(tr.getLabel()) %> (<code><%= esc(id) %></code>)</span>
          <input type="date" name="trigger_<%= esc(id) %>" value="<%= esc(v) %>" />
        </label>
      <%   }
         } %>
    </div>

    <div style="margin-top:12px;">
      <button class="btn" type="submit">Calculate Deadlines</button>
    </div>
  </form>

  <% if (!results.isEmpty()) { %>
    <div class="table-wrap" style="margin-top:12px;">
      <table class="table">
        <thead>
          <tr>
            <th>Date</th>
            <th>Label</th>
            <th>Date-Time</th>
            <th>Imaginary</th>
            <th>Explanation</th>
          </tr>
        </thead>
        <tbody>
          <% for (int i = 0; i < results.size(); i++) {
               class_dealine_calculator.ActionDateInfo row = results.get(i);
               if (row == null) continue;
          %>
            <tr>
              <td><code><%= esc(row.getDate()) %></code></td>
              <td><%= esc(row.getLabel()) %></td>
              <td><%= esc(safe(row.getDateTimeIso())) %></td>
              <td><%= row.isImaginary() ? "Yes" : "No" %></td>
              <td><%= esc(row.getExplanation()) %></td>
            </tr>
          <% } %>
        </tbody>
      </table>
    </div>
  <% } %>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin:0 0 8px 0;">Holiday Preview (<%= holidayYear %>)</h2>
  <div class="table-wrap">
    <table class="table">
      <thead>
        <tr>
          <th>Date</th>
          <th>Type</th>
          <th>Name</th>
          <th>Court Status</th>
        </tr>
      </thead>
      <tbody>
        <% if (holidayRows == null || holidayRows.length == 0) { %>
          <tr><td colspan="4" class="muted">No holiday rows found.</td></tr>
        <% } else {
             for (int i = 0; i < holidayRows.length; i++) {
               class_holiday_calculator.HolidayInfo h = holidayRows[i];
               if (h == null) continue;
        %>
          <tr>
            <td><code><%= esc(h.getHolidayDate()) %></code></td>
            <td><%= esc(h.getHolidayType()) %></td>
            <td><%= esc(h.getHolidayName()) %></td>
            <td><%= esc(h.getCourtSessionStatus().name()) %></td>
          </tr>
        <%   }
           } %>
      </tbody>
    </table>
  </div>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin:0 0 8px 0;">Tenant Deadline XML</h2>
  <form method="post" action="<%= ctx %>/deadline_calculator.jsp">
    <input type="hidden" name="action" value="save_deadline_xml" />
    <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
    <textarea name="deadline_xml" rows="16" style="width:100%; font-family: ui-monospace, SFMono-Regular, Menlo, monospace;"><%= esc(deadlineXml) %></textarea>
    <div style="margin-top:10px;">
      <button class="btn" type="submit">Save Deadline XML</button>
    </div>
  </form>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin:0 0 8px 0;">Tenant Holiday XML</h2>
  <form method="post" action="<%= ctx %>/deadline_calculator.jsp">
    <input type="hidden" name="action" value="save_holiday_xml" />
    <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
    <textarea name="holiday_xml" rows="16" style="width:100%; font-family: ui-monospace, SFMono-Regular, Menlo, monospace;"><%= esc(holidayXml) %></textarea>
    <div style="margin-top:10px;">
      <button class="btn" type="submit">Save Holiday XML</button>
    </div>
  </form>
</section>

<jsp:include page="footer.jsp" />
