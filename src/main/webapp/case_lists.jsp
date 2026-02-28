<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" isELIgnored="true" %>

<%@ page import="java.net.URLEncoder" %>
<%@ page import="java.nio.charset.StandardCharsets" %>
<%@ page import="java.util.ArrayList" %>
<%@ page import="java.util.LinkedHashMap" %>
<%@ page import="java.util.List" %>
<%@ page import="java.util.Locale" %>
<%@ page import="java.util.Map" %>
<%@ page import="java.util.regex.Pattern" %>

<%@ page import="javax.xml.XMLConstants" %>
<%@ page import="javax.xml.parsers.DocumentBuilder" %>
<%@ page import="javax.xml.parsers.DocumentBuilderFactory" %>

<%@ page import="org.w3c.dom.Document" %>
<%@ page import="org.w3c.dom.Element" %>
<%@ page import="org.w3c.dom.Node" %>
<%@ page import="org.w3c.dom.NodeList" %>
<%@ page import="org.xml.sax.InputSource" %>

<%@ page import="net.familylawandprobate.controversies.case_list_items" %>
<%@ page import="net.familylawandprobate.controversies.form_templates" %>
<%@ page import="net.familylawandprobate.controversies.matters" %>

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

  private static String enc(String s) {
    return URLEncoder.encode(safe(s), StandardCharsets.UTF_8);
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

  private static String xmlEsc(String s) {
    return esc(safe(s));
  }

  private static ArrayList<String> splitNonEmptyLines(String raw) {
    ArrayList<String> out = new ArrayList<String>();
    String[] lines = safe(raw).split("\\r?\\n");
    for (int i = 0; i < lines.length; i++) {
      String line = safe(lines[i]).trim();
      if (line.isBlank()) continue;
      out.add(line);
    }
    return out;
  }

  private static ArrayList<String> splitColumns(String line, char delim) {
    ArrayList<String> out = new ArrayList<String>();
    String d = Pattern.quote(String.valueOf(delim));
    String[] parts = safe(line).split(d, -1);
    for (int i = 0; i < parts.length; i++) out.add(safe(parts[i]).trim());
    return out;
  }

  private static String normalizeElementName(String raw, int ordinal) {
    String k = safe(raw).trim().toLowerCase(Locale.ROOT);
    if (k.isBlank()) k = "column_" + ordinal;

    StringBuilder sb = new StringBuilder(k.length());
    boolean lastUnderscore = false;
    for (int i = 0; i < k.length(); i++) {
      char ch = k.charAt(i);
      boolean ok = (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '_' || ch == '-';
      if (ok) {
        sb.append(ch);
        lastUnderscore = false;
      } else if (!lastUnderscore && sb.length() > 0) {
        sb.append('_');
        lastUnderscore = true;
      }
    }
    String out = sb.toString();
    while (out.startsWith("_")) out = out.substring(1);
    while (out.endsWith("_")) out = out.substring(0, out.length() - 1);
    if (out.isBlank()) out = "column_" + ordinal;
    return out;
  }

  private static String buildListXmlFromPayload(String payload) {
    ArrayList<String> lines = splitNonEmptyLines(payload);
    StringBuilder sb = new StringBuilder(Math.max(64, safe(payload).length() + 32));
    sb.append("<list>");
    for (int i = 0; i < lines.size(); i++) {
      sb.append("<item>").append(xmlEsc(lines.get(i))).append("</item>");
    }
    sb.append("</list>");
    return sb.toString();
  }

  private static String buildGridXmlFromPayload(String payload) {
    ArrayList<String> lines = splitNonEmptyLines(payload);
    if (lines.isEmpty()) return "<list></list>";

    String headerLine = lines.get(0);
    char delim = headerLine.indexOf('|') >= 0 ? '|' : ',';
    ArrayList<String> rawCols = splitColumns(headerLine, delim);
    ArrayList<String> cols = new ArrayList<String>();
    for (int i = 0; i < rawCols.size(); i++) cols.add(normalizeElementName(rawCols.get(i), i + 1));
    if (cols.isEmpty()) cols.add("column_1");

    StringBuilder sb = new StringBuilder(Math.max(96, safe(payload).length() + 64));
    sb.append("<list>");
    for (int i = 1; i < lines.size(); i++) {
      ArrayList<String> vals = splitColumns(lines.get(i), delim);
      sb.append("<row>");
      for (int ci = 0; ci < cols.size(); ci++) {
        String col = cols.get(ci);
        String val = ci < vals.size() ? vals.get(ci) : "";
        sb.append("<").append(col).append(">")
          .append(xmlEsc(val))
          .append("</").append(col).append(">");
      }
      sb.append("</row>");
    }
    sb.append("</list>");
    return sb.toString();
  }

  private static Document parseXmlSafe(String xml) {
    try {
      DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
      f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      f.setFeature("http://xml.org/sax/features/external-general-entities", false);
      f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      f.setXIncludeAware(false);
      f.setExpandEntityReferences(false);
      DocumentBuilder b = f.newDocumentBuilder();
      return b.parse(new InputSource(new java.io.StringReader(safe(xml))));
    } catch (Exception ignored) {
      return null;
    }
  }

  private static String localName(Element el) {
    if (el == null) return "";
    String t = safe(el.getTagName());
    int c = t.indexOf(':');
    if (c >= 0 && c + 1 < t.length()) t = t.substring(c + 1);
    return t.toLowerCase(Locale.ROOT);
  }

  private static ArrayList<Element> childElements(Element parent) {
    ArrayList<Element> out = new ArrayList<Element>();
    if (parent == null) return out;
    NodeList nodes = parent.getChildNodes();
    for (int i = 0; i < nodes.getLength(); i++) {
      Node n = nodes.item(i);
      if (n instanceof Element) out.add((Element) n);
    }
    return out;
  }

  private static boolean hasElementChildren(Element el) {
    if (el == null) return false;
    NodeList nodes = el.getChildNodes();
    for (int i = 0; i < nodes.getLength(); i++) if (nodes.item(i) instanceof Element) return true;
    return false;
  }

  private static ArrayList<Element> descendantElements(Element root, String wantedLocalName) {
    ArrayList<Element> out = new ArrayList<Element>();
    if (root == null) return out;
    String wanted = safe(wantedLocalName).trim().toLowerCase(Locale.ROOT);
    if (wanted.isBlank()) return out;

    if (wanted.equals(localName(root))) out.add(root);
    NodeList all = root.getElementsByTagName("*");
    for (int i = 0; i < all.getLength(); i++) {
      Node n = all.item(i);
      if (!(n instanceof Element)) continue;
      Element e = (Element) n;
      if (wanted.equals(localName(e))) out.add(e);
    }
    return out;
  }

  private static final class DatasetView {
    final String kind;
    final String payload;
    DatasetView(String kind, String payload) {
      this.kind = safe(kind).isBlank() ? "xml" : safe(kind);
      this.payload = safe(payload);
    }
  }

  private static DatasetView viewFromStoredXml(String rawXml) {
    String raw = safe(rawXml).trim();
    if (raw.isBlank()) return new DatasetView("list", "");

    Document doc = parseXmlSafe(raw);
    if (doc == null || doc.getDocumentElement() == null) return new DatasetView("xml", raw);

    Element root = doc.getDocumentElement();
    Element dataRoot = root;
    if ("list".equals(localName(root))) {
      ArrayList<Element> kids = childElements(root);
      for (int i = 0; i < kids.size(); i++) {
        Element k = kids.get(i);
        if (k == null) continue;
        if ("list".equals(localName(k))) {
          dataRoot = k;
          break;
        }
      }
    }

    ArrayList<Element> rows = descendantElements(dataRoot, "row");
    if (!rows.isEmpty()) {
      ArrayList<String> cols = new ArrayList<String>();
      ArrayList<Element> firstChildren = childElements(rows.get(0));
      for (int i = 0; i < firstChildren.size(); i++) {
        cols.add(localName(firstChildren.get(i)));
      }
      if (!cols.isEmpty()) {
        StringBuilder p = new StringBuilder(256);
        p.append(String.join(" | ", cols));
        for (int ri = 0; ri < rows.size(); ri++) {
          Element row = rows.get(ri);
          p.append("\n");
          ArrayList<String> vals = new ArrayList<String>();
          for (int ci = 0; ci < cols.size(); ci++) {
            String wanted = cols.get(ci);
            String v = "";
            ArrayList<Element> fields = childElements(row);
            for (int fi = 0; fi < fields.size(); fi++) {
              Element field = fields.get(fi);
              if (wanted.equals(localName(field))) {
                v = safe(field.getTextContent()).trim();
                break;
              }
            }
            vals.add(v);
          }
          p.append(String.join(" | ", vals));
        }
        return new DatasetView("grid", p.toString().trim());
      }
    }

    ArrayList<Element> items = descendantElements(dataRoot, "item");
    ArrayList<String> lines = new ArrayList<String>();
    for (int i = 0; i < items.size(); i++) {
      Element item = items.get(i);
      if (item == null) continue;
      if (hasElementChildren(item)) continue;
      String txt = safe(item.getTextContent()).trim();
      if (!txt.isBlank()) lines.add(txt);
    }
    if (!lines.isEmpty()) return new DatasetView("list", String.join("\n", lines));

    return new DatasetView("xml", raw);
  }

  private static String guessKindFromKey(String key) {
    String k = safe(key).trim().toLowerCase(Locale.ROOT);
    if (k.endsWith("_rows") || k.endsWith(".rows") || k.endsWith("_grid") || k.endsWith("_table")) return "grid";
    return "list";
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

  matters matterStore = matters.defaultStore();
  case_list_items caseListStore = case_list_items.defaultStore();
  form_templates templateStore = form_templates.defaultStore();

  String csrfToken = csrfForRender(request);

  String message = null;
  String error = null;

  String selectedMatterUuid = safe(request.getParameter("matter_uuid")).trim();
  String selectedTemplateUuid = safe(request.getParameter("template_uuid")).trim();
  String selectedAssemblyUuid = safe(request.getParameter("assembly_uuid")).trim();
  String focusListKey = caseListStore.normalizeKey(safe(request.getParameter("focus_list_key")));
  String returnTo = safe(request.getParameter("return_to")).trim().toLowerCase(Locale.ROOT);
  if (returnTo.isBlank()) returnTo = "forms";
  boolean focusMode = "1".equals(safe(request.getParameter("focus")).trim());
  String renderPreview = "0".equals(safe(request.getParameter("render_preview")).trim()) ? "0" : "1";
  String focusQs = focusMode ? "&focus=1" : "";
  String renderPreviewQs = "&render_preview=" + renderPreview;
  boolean assembleAfter = "1".equals(safe(request.getParameter("assemble_after")).trim());

  try { matterStore.ensure(tenantUuid); } catch (Exception ignored) {}

  if ("POST".equalsIgnoreCase(request.getMethod()) && "save_case_lists".equalsIgnoreCase(safe(request.getParameter("action")).trim())) {
    selectedMatterUuid = safe(request.getParameter("matter_uuid")).trim();
    selectedTemplateUuid = safe(request.getParameter("template_uuid")).trim();
    selectedAssemblyUuid = safe(request.getParameter("assembly_uuid")).trim();
    focusListKey = caseListStore.normalizeKey(safe(request.getParameter("focus_list_key")));
    returnTo = safe(request.getParameter("return_to")).trim().toLowerCase(Locale.ROOT);
    if (returnTo.isBlank()) returnTo = "forms";
    focusMode = "1".equals(safe(request.getParameter("focus")).trim());
    renderPreview = "0".equals(safe(request.getParameter("render_preview")).trim()) ? "0" : "1";
    focusQs = focusMode ? "&focus=1" : "";
    renderPreviewQs = "&render_preview=" + renderPreview;
    assembleAfter = "1".equals(safe(request.getParameter("assemble_after")).trim());

    try {
      String[] keys = request.getParameterValues("dataset_key");
      String[] kinds = request.getParameterValues("dataset_kind");
      String[] payloads = request.getParameterValues("dataset_payload");

      LinkedHashMap<String,String> datasetMap = new LinkedHashMap<String,String>();
      int n = Math.max(
          keys == null ? 0 : keys.length,
          Math.max(kinds == null ? 0 : kinds.length, payloads == null ? 0 : payloads.length)
      );
      for (int i = 0; i < n; i++) {
        String keyRaw = (keys != null && i < keys.length) ? safe(keys[i]) : "";
        String kindRaw = (kinds != null && i < kinds.length) ? safe(kinds[i]) : "list";
        String payloadRaw = (payloads != null && i < payloads.length) ? safe(payloads[i]) : "";

        String key = caseListStore.normalizeKey(keyRaw);
        String kind = safe(kindRaw).trim().toLowerCase(Locale.ROOT);
        String payload = safe(payloadRaw);
        if (key.isBlank()) continue;
        if (payload.trim().isBlank()) continue;

        String xml;
        if ("grid".equals(kind)) xml = buildGridXmlFromPayload(payload);
        else if ("list".equals(kind)) xml = buildListXmlFromPayload(payload);
        else xml = payload;

        datasetMap.put(key, xml);
      }

      caseListStore.write(tenantUuid, selectedMatterUuid, datasetMap);
      String redirect;
      if ("forms".equals(returnTo)) {
        redirect = ctx + "/forms.jsp?matter_uuid=" + enc(selectedMatterUuid) + "&template_uuid=" + enc(selectedTemplateUuid) + "&lists_saved=1" + focusQs + renderPreviewQs;
        if (!selectedAssemblyUuid.isBlank()) redirect += "&assembly_uuid=" + enc(selectedAssemblyUuid);
        if (!focusListKey.isBlank()) redirect += "&focus_token=" + enc("{{case." + focusListKey + "}}");
        if (assembleAfter) redirect += "&resume_prompt=1&assemble_after=1";
      } else {
        redirect = ctx + "/case_lists.jsp?matter_uuid=" + enc(selectedMatterUuid) + "&template_uuid=" + enc(selectedTemplateUuid) + focusQs + renderPreviewQs;
        if (!selectedAssemblyUuid.isBlank()) redirect += "&assembly_uuid=" + enc(selectedAssemblyUuid);
        if (!focusListKey.isBlank()) redirect += "&focus_list_key=" + enc(focusListKey);
        redirect += "&return_to=" + enc(returnTo);
        if (assembleAfter) redirect += "&assemble_after=1";
        redirect += "&lists_saved=1";
      }
      response.sendRedirect(redirect);
      return;
    } catch (Exception ex) {
      error = "Unable to save case lists/grids: " + safe(ex.getMessage());
    }
  }

  if ("1".equals(safe(request.getParameter("lists_saved")))) {
    message = "Case list/grid datasets saved.";
  }

  List<matters.MatterRec> allCases = new ArrayList<matters.MatterRec>();
  try { allCases = matterStore.listAll(tenantUuid); } catch (Exception ignored) {}

  List<matters.MatterRec> activeCases = new ArrayList<matters.MatterRec>();
  for (int i = 0; i < allCases.size(); i++) {
    matters.MatterRec c = allCases.get(i);
    if (c == null) continue;
    if (!c.trashed) activeCases.add(c);
  }

  if (selectedMatterUuid.isBlank() && !activeCases.isEmpty()) selectedMatterUuid = safe(activeCases.get(0).uuid);

  List<form_templates.TemplateRec> templates = new ArrayList<form_templates.TemplateRec>();
  try { templates = templateStore.list(tenantUuid); } catch (Exception ignored) {}
  if (selectedTemplateUuid.isBlank() && !templates.isEmpty()) selectedTemplateUuid = safe(templates.get(0).uuid);

  matters.MatterRec selectedCase = null;
  try { selectedCase = matterStore.getByUuid(tenantUuid, selectedMatterUuid); } catch (Exception ignored) {}
  if (selectedCase == null && !activeCases.isEmpty()) selectedCase = activeCases.get(0);
  if (selectedCase != null) selectedMatterUuid = safe(selectedCase.uuid);

  LinkedHashMap<String,String> caseListKv = new LinkedHashMap<String,String>();
  if (selectedCase != null) {
    try { caseListKv.putAll(caseListStore.read(tenantUuid, selectedCase.uuid)); } catch (Exception ignored) {}
  }

  ArrayList<String> rowKeys = new ArrayList<String>();
  ArrayList<String> rowKinds = new ArrayList<String>();
  ArrayList<String> rowPayloads = new ArrayList<String>();
  for (Map.Entry<String,String> e : caseListKv.entrySet()) {
    if (e == null) continue;
    String key = caseListStore.normalizeKey(e.getKey());
    if (key.isBlank()) continue;
    DatasetView v = viewFromStoredXml(e.getValue());
    rowKeys.add(key);
    rowKinds.add(v.kind);
    rowPayloads.add(v.payload);
  }

  if (!focusListKey.isBlank() && !rowKeys.contains(focusListKey)) {
    rowKeys.add(0, focusListKey);
    rowKinds.add(0, guessKindFromKey(focusListKey));
    rowPayloads.add(0, "");
  }

  int rowCount = Math.max(3, rowKeys.size() + 1);
  while (rowKeys.size() < rowCount) {
    rowKeys.add("");
    rowKinds.add("list");
    rowPayloads.add("");
  }

  String formsHref = ctx + "/forms.jsp?matter_uuid=" + enc(selectedMatterUuid) + "&template_uuid=" + enc(selectedTemplateUuid) + focusQs + renderPreviewQs;
  if (!selectedAssemblyUuid.isBlank()) formsHref += "&assembly_uuid=" + enc(selectedAssemblyUuid);
%>

<jsp:include page="header.jsp" />

<section class="card">
  <h1 style="margin:0;">Case Lists/Grids</h1>
  <div class="meta" style="margin-top:4px;">Store case-specific list and table datasets used by <code>{{#each ...}}</code> directives in form templates.</div>
  <% if (assembleAfter) { %>
    <div class="meta" style="margin-top:4px;">After saving, return to form assembly and run download/assemble.</div>
  <% } %>

  <% if (message != null) { %>
    <div class="alert alert-ok" style="margin-top:12px;"><%= esc(message) %></div>
  <% } %>
  <% if (error != null) { %>
    <div class="alert alert-error" style="margin-top:12px;"><%= esc(error) %></div>
  <% } %>
</section>

<section class="card" style="margin-top:12px;">
  <form class="form" method="get" action="<%= ctx %>/case_lists.jsp">
    <input type="hidden" name="return_to" value="<%= esc(returnTo) %>" />
    <input type="hidden" name="assembly_uuid" value="<%= esc(selectedAssemblyUuid) %>" />
    <input type="hidden" name="focus" value="<%= focusMode ? "1" : "" %>" />
    <input type="hidden" name="render_preview" value="<%= esc(renderPreview) %>" />
    <% if (assembleAfter) { %><input type="hidden" name="assemble_after" value="1" /><% } %>
    <% if (!focusListKey.isBlank()) { %><input type="hidden" name="focus_list_key" value="<%= esc(focusListKey) %>" /><% } %>

    <div class="grid" style="display:grid; grid-template-columns: 2fr 2fr auto; gap:12px;">
      <label>
        <span>Case</span>
        <select name="matter_uuid">
          <% if (activeCases.isEmpty()) { %>
            <option value="">No active cases</option>
          <% } else {
               for (int i = 0; i < activeCases.size(); i++) {
                 matters.MatterRec c = activeCases.get(i);
                 if (c == null) continue;
                 String id = safe(c.uuid);
                 boolean sel = id.equals(safe(selectedMatterUuid));
          %>
            <option value="<%= esc(id) %>" <%= sel ? "selected" : "" %>><%= esc(safe(c.label)) %></option>
          <%   }
             } %>
        </select>
      </label>

      <label>
        <span>Template Context</span>
        <select name="template_uuid">
          <% if (templates.isEmpty()) { %>
            <option value="">No templates</option>
          <% } else {
               for (int i = 0; i < templates.size(); i++) {
                 form_templates.TemplateRec t = templates.get(i);
                 if (t == null) continue;
                 String id = safe(t.uuid);
                 boolean sel = id.equals(safe(selectedTemplateUuid));
          %>
            <option value="<%= esc(id) %>" <%= sel ? "selected" : "" %>>
              <%= esc(safe(t.label)) %> (<%= esc(safe(t.fileExt).toUpperCase(Locale.ROOT)) %>)
            </option>
          <%   }
             } %>
        </select>
      </label>

      <label>
        <span>&nbsp;</span>
        <button class="btn" type="submit">Load</button>
      </label>
    </div>
  </form>

  <div class="actions" style="display:flex; gap:10px; margin-top:10px; flex-wrap:wrap;">
    <a class="btn btn-ghost" href="<%= formsHref %>">Back To Form Assembly</a>
    <a class="btn btn-ghost" href="<%= ctx %>/case_fields.jsp?matter_uuid=<%= enc(selectedMatterUuid) %>&template_uuid=<%= enc(selectedTemplateUuid) %>">Case Fields</a>
    <a class="btn btn-ghost" href="<%= ctx %>/token_guide.jsp?matter_uuid=<%= enc(selectedMatterUuid) %>&template_uuid=<%= enc(selectedTemplateUuid) %>">Token Guide</a>
    <a class="btn btn-ghost" href="<%= ctx %>/markup_notation.jsp">Markup Notation</a>
  </div>
</section>

<section class="card" style="margin-top:12px;">
  <% if (selectedCase == null) { %>
    <div class="muted">Select or create a case first.</div>
  <% } else { %>
    <div class="meta" style="margin-bottom:8px;">
      Case: <strong><%= esc(safe(selectedCase.label)) %></strong>
      <% if (!safe(selectedCase.causeDocketNumber).isBlank()) { %>
        • <%= esc(safe(selectedCase.causeDocketNumber)) %>
      <% } %>
    </div>

    <div class="meta" style="margin-bottom:10px;">
      <strong>Type guide:</strong> <code>list</code> = one value per line.
      <code>grid</code> = first line headers with <code>|</code>, then one row per line.
      <code>xml</code> = raw XML dataset.
    </div>

    <form class="form" method="post" action="<%= ctx %>/case_lists.jsp">
      <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
      <input type="hidden" name="action" value="save_case_lists" />
      <input type="hidden" name="matter_uuid" value="<%= esc(selectedMatterUuid) %>" />
      <input type="hidden" name="template_uuid" value="<%= esc(selectedTemplateUuid) %>" />
      <input type="hidden" name="assembly_uuid" value="<%= esc(selectedAssemblyUuid) %>" />
      <input type="hidden" name="return_to" value="<%= esc(returnTo) %>" />
      <input type="hidden" name="focus_list_key" value="<%= esc(focusListKey) %>" />
      <input type="hidden" name="focus" value="<%= focusMode ? "1" : "" %>" />
      <input type="hidden" name="render_preview" value="<%= esc(renderPreview) %>" />
      <% if (assembleAfter) { %><input type="hidden" name="assemble_after" value="1" /><% } %>

      <div class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th style="width:22%;">Dataset Key</th>
              <th style="width:14%;">Type</th>
              <th>Dataset Value</th>
              <th style="width:96px;">&nbsp;</th>
            </tr>
          </thead>
          <tbody id="datasetRows">
          <% for (int i = 0; i < rowKeys.size(); i++) {
               String k = safe(rowKeys.get(i));
               String kind = safe(rowKinds.get(i));
               String payload = safe(rowPayloads.get(i));
          %>
            <tr>
              <td><input type="text" name="dataset_key" value="<%= esc(k) %>" placeholder="service_rows" /></td>
              <td>
                <select name="dataset_kind">
                  <option value="list" <%= "list".equals(kind) ? "selected" : "" %>>list</option>
                  <option value="grid" <%= "grid".equals(kind) ? "selected" : "" %>>grid</option>
                  <option value="xml" <%= "xml".equals(kind) ? "selected" : "" %>>xml</option>
                </select>
              </td>
              <td><textarea name="dataset_payload" rows="4" placeholder="list: one item per line&#10;grid: col1 | col2&#10;row1v1 | row1v2"><%= esc(payload) %></textarea></td>
              <td><button type="button" class="btn btn-ghost" onclick="removeDatasetRow(this)">Remove</button></td>
            </tr>
          <% } %>
          </tbody>
        </table>
      </div>

      <div class="actions" style="display:flex; gap:10px; margin-top:10px; flex-wrap:wrap;">
        <button type="button" class="btn btn-ghost" onclick="addDatasetRow()">Add Dataset</button>
        <button type="submit" class="btn">Save Lists/Grids</button>
      </div>
    </form>
  <% } %>
</section>

<script>
  function addDatasetRow() {
    var tbody = document.getElementById("datasetRows");
    if (!tbody) return;
    var tr = document.createElement("tr");
    tr.innerHTML =
      '<td><input type="text" name="dataset_key" value="" placeholder="service_rows" /></td>' +
      '<td><select name="dataset_kind">' +
        '<option value="list" selected>list</option>' +
        '<option value="grid">grid</option>' +
        '<option value="xml">xml</option>' +
      '</select></td>' +
      '<td><textarea name="dataset_payload" rows="4" placeholder="list: one item per line&#10;grid: col1 | col2&#10;row1v1 | row1v2"></textarea></td>' +
      '<td><button type="button" class="btn btn-ghost" onclick="removeDatasetRow(this)">Remove</button></td>';
    tbody.appendChild(tr);
  }

  function removeDatasetRow(btn) {
    var b = btn;
    if (!b) return;
    var tr = b.closest ? b.closest("tr") : null;
    if (!tr || !tr.parentNode) return;
    tr.parentNode.removeChild(tr);
  }
</script>

<jsp:include page="footer.jsp" />
