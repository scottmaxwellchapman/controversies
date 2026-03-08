<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" isELIgnored="true" %>
<%@ page import="java.io.InputStream" %>
<%@ page import="java.net.URLEncoder" %>
<%@ page import="java.nio.charset.StandardCharsets" %>
<%@ page import="java.util.ArrayList" %>
<%@ page import="java.util.Collections" %>
<%@ page import="java.util.Comparator" %>
<%@ page import="java.util.LinkedHashMap" %>
<%@ page import="java.util.List" %>
<%@ page import="java.util.Locale" %>
<%@ page import="javax.xml.XMLConstants" %>
<%@ page import="javax.xml.parsers.DocumentBuilderFactory" %>
<%@ page import="org.w3c.dom.Document" %>
<%@ page import="org.w3c.dom.Element" %>
<%@ page import="org.w3c.dom.Node" %>
<%@ page import="org.w3c.dom.NodeList" %>

<%@ include file="security.jspf" %>
<% if (!require_login()) return; %>

<%!
  private static final class HelpTopic {
    String id = "";
    String title = "";
    String category = "General";
    String audience = "All";
    String summary = "";
    String keywords = "";
    String contentHtml = "";
    String searchIndex = "";
    int order = 0;
  }

  private static String hbSafe(String s) { return s == null ? "" : s; }

  private static String hbEsc(String s) {
    if (s == null) return "";
    return s.replace("&","&amp;")
            .replace("<","&lt;")
            .replace(">","&gt;")
            .replace("\"","&quot;")
            .replace("'","&#39;");
  }

  private static String hbEscAttr(String s) {
    return hbEsc(s);
  }

  private static String hbUrl(String s) {
    return URLEncoder.encode(hbSafe(s), StandardCharsets.UTF_8);
  }

  private static int hbInt(String raw, int fallback) {
    try { return Integer.parseInt(hbSafe(raw).trim()); } catch (Exception ignored) { return fallback; }
  }

  private static void hbTrySetFeature(DocumentBuilderFactory dbf, String feature, boolean value) {
    try { dbf.setFeature(feature, value); } catch (Exception ignored) {}
  }

  private static String hbChildText(Element parent, String tagName) {
    if (parent == null) return "";
    NodeList nodes = parent.getElementsByTagName(tagName);
    if (nodes == null || nodes.getLength() == 0) return "";
    Node node = nodes.item(0);
    if (node == null) return "";
    return hbSafe(node.getTextContent()).trim();
  }

  private static String hbStripHtml(String html) {
    return hbSafe(html).replaceAll("(?s)<script.*?>.*?</script>", " ")
                       .replaceAll("(?s)<style.*?>.*?</style>", " ")
                       .replaceAll("<[^>]+>", " ")
                       .replaceAll("\\s+", " ")
                       .trim();
  }

  private static List<HelpTopic> hbLoadTopics(jakarta.servlet.ServletContext app) throws Exception {
    List<HelpTopic> out = new ArrayList<HelpTopic>();
    if (app == null) return out;

    InputStream in = app.getResourceAsStream("/WEB-INF/help/help_topics.xml");
    if (in == null) return out;

    try (InputStream xml = in) {
      DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
      dbf.setNamespaceAware(false);
      dbf.setXIncludeAware(false);
      dbf.setExpandEntityReferences(false);
      hbTrySetFeature(dbf, XMLConstants.FEATURE_SECURE_PROCESSING, true);
      hbTrySetFeature(dbf, "http://apache.org/xml/features/disallow-doctype-decl", true);
      hbTrySetFeature(dbf, "http://xml.org/sax/features/external-general-entities", false);
      hbTrySetFeature(dbf, "http://xml.org/sax/features/external-parameter-entities", false);
      hbTrySetFeature(dbf, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

      Document doc = dbf.newDocumentBuilder().parse(xml);
      NodeList topics = doc.getElementsByTagName("topic");
      for (int i = 0; i < topics.getLength(); i++) {
        Node node = topics.item(i);
        if (!(node instanceof Element)) continue;
        Element e = (Element) node;

        HelpTopic t = new HelpTopic();
        t.id = hbSafe(e.getAttribute("id")).trim();
        t.title = hbSafe(e.getAttribute("title")).trim();
        t.category = hbSafe(e.getAttribute("category")).trim();
        t.audience = hbSafe(e.getAttribute("audience")).trim();
        t.order = hbInt(e.getAttribute("order"), i + 1);
        t.summary = hbChildText(e, "summary");
        t.keywords = hbChildText(e, "keywords");
        t.contentHtml = hbChildText(e, "content");

        if (t.id.isBlank()) t.id = "topic_" + (i + 1);
        if (t.title.isBlank()) t.title = t.id;
        if (t.category.isBlank()) t.category = "General";
        if (t.audience.isBlank()) t.audience = "All";

        String idx = (t.title + " " + t.summary + " " + t.keywords + " " + hbStripHtml(t.contentHtml)).toLowerCase(Locale.ROOT);
        t.searchIndex = idx;
        out.add(t);
      }
    }

    Collections.sort(out, Comparator
      .comparingInt((HelpTopic t) -> t.order)
      .thenComparing(t -> hbSafe(t.title).toLowerCase(Locale.ROOT)));

    return out;
  }

  private static boolean hbMatchesQuery(HelpTopic t, String rawQuery) {
    String q = hbSafe(rawQuery).trim().toLowerCase(Locale.ROOT);
    if (q.isBlank()) return true;
    String hay = t == null ? "" : hbSafe(t.searchIndex);
    String[] terms = q.split("\\s+");
    for (String term : terms) {
      if (term == null || term.isBlank()) continue;
      if (!hay.contains(term)) return false;
    }
    return true;
  }

  private static HelpTopic hbFindTopic(List<HelpTopic> topics, String id) {
    String target = hbSafe(id).trim();
    if (target.isBlank() || topics == null) return null;
    for (HelpTopic t : topics) {
      if (t == null) continue;
      if (target.equalsIgnoreCase(hbSafe(t.id).trim())) return t;
    }
    return null;
  }
%>

<%
  String ctx = hbSafe(request.getContextPath());
  String query = hbSafe(request.getParameter("q")).trim();
  String requestedTopic = hbSafe(request.getParameter("topic")).trim();

  List<HelpTopic> allTopics = new ArrayList<HelpTopic>();
  String loadError = "";
  try {
    allTopics = hbLoadTopics(application);
  } catch (Exception ex) {
    loadError = "Unable to load help topics XML: " + hbSafe(ex.getMessage());
  }

  List<HelpTopic> visibleTopics = new ArrayList<HelpTopic>();
  for (HelpTopic t : allTopics) {
    if (hbMatchesQuery(t, query)) visibleTopics.add(t);
  }

  boolean fallbackToAll = false;
  if (!query.isBlank() && visibleTopics.isEmpty()) {
    fallbackToAll = true;
    visibleTopics.addAll(allTopics);
  }

  HelpTopic selected = hbFindTopic(visibleTopics, requestedTopic);
  if (selected == null) selected = hbFindTopic(allTopics, requestedTopic);
  if (selected == null && !visibleTopics.isEmpty()) selected = visibleTopics.get(0);
  if (selected == null && !allTopics.isEmpty()) selected = allTopics.get(0);

  LinkedHashMap<String, List<HelpTopic>> grouped = new LinkedHashMap<String, List<HelpTopic>>();
  for (HelpTopic t : visibleTopics) {
    String cat = hbSafe(t.category).trim();
    if (cat.isBlank()) cat = "General";
    List<HelpTopic> bucket = grouped.get(cat);
    if (bucket == null) {
      bucket = new ArrayList<HelpTopic>();
      grouped.put(cat, bucket);
    }
    bucket.add(t);
  }

  int selectedIndex = -1;
  for (int i = 0; i < visibleTopics.size(); i++) {
    HelpTopic t = visibleTopics.get(i);
    if (selected != null && hbSafe(t.id).equalsIgnoreCase(hbSafe(selected.id))) {
      selectedIndex = i;
      break;
    }
  }

  HelpTopic prevTopic = selectedIndex > 0 ? visibleTopics.get(selectedIndex - 1) : null;
  HelpTopic nextTopic = (selectedIndex >= 0 && selectedIndex < visibleTopics.size() - 1) ? visibleTopics.get(selectedIndex + 1) : null;

  String selectedContent = selected == null ? "" : hbSafe(selected.contentHtml).replace("{{ctx}}", hbEscAttr(ctx));
%>

<jsp:include page="header.jsp" />

<section class="card">
  <h1 style="margin:0;">Help Browser</h1>
  <p class="meta" style="margin-top:8px;">
    Centralized, searchable help topics for legal teams. Use the search bar to filter by workflow, screen, or operation name.
  </p>
  <% if (!loadError.isBlank()) { %>
    <div class="alert alert-error" style="margin-top:10px;"><%= hbEsc(loadError) %></div>
  <% } %>
</section>

<section class="card" style="margin-top:12px;">
  <form method="get" action="<%= hbEscAttr(ctx) %>/help_browser.jsp" style="display:flex; gap:10px; align-items:center; flex-wrap:wrap;">
    <% if (selected != null) { %>
      <input type="hidden" name="topic" value="<%= hbEscAttr(selected.id) %>" />
    <% } %>
    <label style="display:flex; flex-direction:column; gap:4px; min-width:320px; flex:1 1 420px;">
      <span class="meta">Search Topics</span>
      <input type="text" name="q" value="<%= hbEscAttr(query) %>" placeholder="Search by topic, screen, API operation, or keyword" />
    </label>
    <button type="submit" class="btn">Search</button>
    <a class="btn btn-ghost" href="<%= hbEscAttr(ctx) %>/help_browser.jsp">Clear</a>
  </form>
  <div class="meta" style="margin-top:10px;">
    Showing <strong><%= visibleTopics.size() %></strong> of <strong><%= allTopics.size() %></strong> topics.
    <% if (fallbackToAll) { %>
      No direct matches for <code><%= hbEsc(query) %></code>; showing all topics.
    <% } %>
  </div>
</section>

<style>
  .help-shell {
    display: grid;
    grid-template-columns: minmax(260px, 330px) minmax(0, 1fr);
    gap: 12px;
  }
  .help-topic-category {
    margin: 0 0 8px 0;
    font-size: 13px;
    letter-spacing: 0.02em;
    text-transform: uppercase;
    color: var(--muted-text, #57606a);
  }
  .help-topic-list {
    list-style: none;
    margin: 0 0 14px 0;
    padding: 0;
    display: grid;
    gap: 6px;
  }
  .help-topic-link {
    display: block;
    text-decoration: none;
    border: 1px solid var(--border, #d0d7de);
    border-radius: 8px;
    padding: 8px 10px;
    color: inherit;
    background: var(--surface, #fff);
  }
  .help-topic-link.active {
    border-color: #0b5fff;
    box-shadow: inset 0 0 0 1px #0b5fff;
  }
  .help-topic-link .meta {
    display: block;
    margin-top: 2px;
    font-size: 12px;
  }
  .help-topic-content h3 {
    margin-top: 18px;
  }
  .help-topic-content table {
    width: 100%;
  }
  .help-topic-content code {
    white-space: pre-wrap;
    word-break: break-word;
  }
  @media (max-width: 980px) {
    .help-shell {
      grid-template-columns: 1fr;
    }
  }
</style>

<section class="help-shell" style="margin-top:12px;">
  <section class="card" style="margin:0;">
    <h2 style="margin-top:0;">Topics</h2>
    <% if (grouped.isEmpty()) { %>
      <div class="meta">No topics available.</div>
    <% } else { %>
      <% for (String cat : grouped.keySet()) { %>
        <h3 class="help-topic-category"><%= hbEsc(cat) %></h3>
        <ul class="help-topic-list">
          <% for (HelpTopic t : grouped.get(cat)) {
               String href = ctx + "/help_browser.jsp?topic=" + hbUrl(t.id);
               if (!query.isBlank()) href = href + "&q=" + hbUrl(query);
               boolean active = selected != null && hbSafe(selected.id).equalsIgnoreCase(hbSafe(t.id));
          %>
            <li>
              <a class="help-topic-link <%= active ? "active" : "" %>" href="<%= hbEscAttr(href) %>">
                <strong><%= hbEsc(t.title) %></strong>
                <span class="meta"><%= hbEsc(t.audience) %></span>
              </a>
            </li>
          <% } %>
        </ul>
      <% } %>
    <% } %>
  </section>

  <section class="card" style="margin:0;">
    <% if (selected == null) { %>
      <h2 style="margin-top:0;">No Topic Selected</h2>
      <p class="meta">Choose a topic from the browser or clear your search.</p>
    <% } else { %>
      <div style="display:flex; align-items:flex-start; justify-content:space-between; gap:10px; flex-wrap:wrap;">
        <div>
          <h2 style="margin:0;"><%= hbEsc(selected.title) %></h2>
          <div class="meta" style="margin-top:4px;">
            Category: <strong><%= hbEsc(selected.category) %></strong>
            • Audience: <strong><%= hbEsc(selected.audience) %></strong>
          </div>
        </div>
        <div style="display:flex; gap:8px; flex-wrap:wrap;">
          <% if (prevTopic != null) {
               String prevHref = ctx + "/help_browser.jsp?topic=" + hbUrl(prevTopic.id);
               if (!query.isBlank()) prevHref = prevHref + "&q=" + hbUrl(query);
          %>
            <a class="btn btn-ghost" href="<%= hbEscAttr(prevHref) %>">Previous Topic</a>
          <% } %>
          <% if (nextTopic != null) {
               String nextHref = ctx + "/help_browser.jsp?topic=" + hbUrl(nextTopic.id);
               if (!query.isBlank()) nextHref = nextHref + "&q=" + hbUrl(query);
          %>
            <a class="btn btn-ghost" href="<%= hbEscAttr(nextHref) %>">Next Topic</a>
          <% } %>
        </div>
      </div>

      <% if (!hbSafe(selected.summary).isBlank()) { %>
        <p class="meta" style="margin-top:10px;"><%= hbEsc(selected.summary) %></p>
      <% } %>

      <div class="help-topic-content" style="margin-top:12px;">
        <%= selectedContent %>
      </div>
    <% } %>
  </section>
</section>

<jsp:include page="footer.jsp" />
