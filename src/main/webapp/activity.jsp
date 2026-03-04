<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" isELIgnored="true" %>

<%@ page import="java.net.URLEncoder" %>
<%@ page import="java.nio.charset.StandardCharsets" %>
<%@ page import="java.time.Instant" %>
<%@ page import="java.time.LocalDateTime" %>
<%@ page import="java.time.OffsetDateTime" %>
<%@ page import="java.time.ZoneId" %>
<%@ page import="java.time.format.DateTimeFormatter" %>
<%@ page import="java.util.ArrayList" %>
<%@ page import="java.util.List" %>
<%@ page import="java.util.Locale" %>

<%@ page import="net.familylawandprobate.controversies.activity_log" %>
<%@ page import="net.familylawandprobate.controversies.matters" %>

<%@ include file="security.jspf" %>
<%
  if (!require_login()) return;
%>

<%!
  private static final String S_TENANT_UUID = "tenant.uuid";
  private static final DateTimeFormatter DT_LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

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

  private static String prettyTime(String raw) {
    String v = safe(raw).trim();
    if (v.isBlank()) return "";
    try { return DT_LOCAL.format(Instant.parse(v).atZone(ZoneId.systemDefault())); } catch (Exception ignored) {}
    try { return DT_LOCAL.format(OffsetDateTime.parse(v).atZoneSameInstant(ZoneId.systemDefault())); } catch (Exception ignored) {}
    try { return DT_LOCAL.format(LocalDateTime.parse(v)); } catch (Exception ignored) {}
    return v;
  }

  private static String levelClass(String level) {
    String v = safe(level).trim().toLowerCase(Locale.ROOT);
    if ("error".equals(v)) return "activity-level-error";
    if ("warning".equals(v)) return "activity-level-warning";
    return "activity-level-verbose";
  }
%>

<%
  String ctx = safe(request.getContextPath());
  String tenantUuid = safe((String) session.getAttribute(S_TENANT_UUID)).trim();
  if (tenantUuid.isBlank()) {
    response.sendRedirect(ctx + "/tenant_login.jsp");
    return;
  }

  matters matterStore = matters.defaultStore();
  activity_log logStore = activity_log.defaultStore();

  List<matters.MatterRec> allCases = new ArrayList<matters.MatterRec>();
  List<matters.MatterRec> activeCases = new ArrayList<matters.MatterRec>();
  try {
    matterStore.ensure(tenantUuid);
    allCases = matterStore.listAll(tenantUuid);
    for (int i = 0; i < allCases.size(); i++) {
      matters.MatterRec m = allCases.get(i);
      if (m == null || m.trashed) continue;
      activeCases.add(m);
    }
  } catch (Exception ignored) {}

  String caseUuid = safe(request.getParameter("case_uuid")).trim();
  if (caseUuid.isBlank()) caseUuid = safe(request.getParameter("matter_uuid")).trim();
  if (caseUuid.isBlank() && !activeCases.isEmpty()) caseUuid = safe(activeCases.get(0).uuid);
  if (caseUuid.isBlank() && !allCases.isEmpty()) caseUuid = safe(allCases.get(0).uuid);

  int limit = parseIntSafe(request.getParameter("limit"), 200);
  if (limit < 20) limit = 20;
  if (limit > 1000) limit = 1000;

  String format = safe(request.getParameter("format")).trim().toLowerCase(Locale.ROOT);
  if ("xml".equals(format)) {
    response.setContentType("application/xml; charset=UTF-8");
    response.setCharacterEncoding("UTF-8");
    response.setHeader("Cache-Control", "no-store");
    out.print(logStore.caseFeedXml(tenantUuid, caseUuid));
    return;
  }

  List<activity_log.LogEntry> entries = caseUuid.isBlank()
    ? List.of()
    : logStore.recentForCase(tenantUuid, caseUuid, limit);
%>

<jsp:include page="header.jsp" />

<section class="card">
  <div class="section-head">
    <div>
      <h1 style="margin:0;">Case Activity</h1>
      <div class="meta">XML-backed timeline of case events, BPM triggers, and BPM actions with timestamps.</div>
    </div>
  </div>
</section>

<section class="card" style="margin-top:12px;">
  <form class="form" method="get" action="<%= ctx %>/activity.jsp">
    <div class="grid" style="display:grid; grid-template-columns: 3fr 1fr auto auto; gap:12px;">
      <label>
        <span>Case</span>
        <select name="case_uuid">
          <% if (allCases.isEmpty()) { %>
            <option value="">No cases found</option>
          <% } else { for (int i = 0; i < allCases.size(); i++) {
               matters.MatterRec c = allCases.get(i);
               if (c == null) continue;
               String id = safe(c.uuid);
               String label = safe(c.label);
          %>
            <option value="<%= esc(id) %>" <%= id.equals(caseUuid) ? "selected" : "" %>>
              <%= esc(label) %><%= c.trashed ? " (archived)" : "" %>
            </option>
          <% }} %>
        </select>
      </label>
      <label>
        <span>Rows</span>
        <select name="limit">
          <option value="100" <%= limit == 100 ? "selected" : "" %>>100</option>
          <option value="200" <%= limit == 200 ? "selected" : "" %>>200</option>
          <option value="500" <%= limit == 500 ? "selected" : "" %>>500</option>
          <option value="1000" <%= limit == 1000 ? "selected" : "" %>>1000</option>
        </select>
      </label>
      <label>
        <span>&nbsp;</span>
        <button class="btn" type="submit">Apply</button>
      </label>
      <label>
        <span>&nbsp;</span>
        <a class="btn btn-ghost" href="<%= ctx %>/activity.jsp?case_uuid=<%= enc(caseUuid) %>&limit=<%= limit %>&format=xml">View XML Feed</a>
      </label>
    </div>
  </form>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Activity Timeline</h2>

  <% if (caseUuid.isBlank()) { %>
    <div class="muted">Select a case to load activity.</div>
  <% } else if (entries.isEmpty()) { %>
    <div class="muted">No activity events for this case yet.</div>
  <% } else { %>
    <div class="activity-feed">
      <% for (int i = 0; i < entries.size(); i++) {
           activity_log.LogEntry e = entries.get(i);
           if (e == null) continue;
      %>
        <article class="activity-item">
          <div class="activity-time">
            <div><strong><%= esc(prettyTime(e.time)) %></strong></div>
            <div class="meta"><%= esc(e.time) %></div>
          </div>
          <div class="activity-main">
            <div class="activity-head">
              <span class="badge <%= levelClass(e.level) %>"><%= esc(safe(e.level).toUpperCase(Locale.ROOT)) %></span>
              <code><%= esc(e.action) %></code>
            </div>
            <div class="meta">
              user=<%= esc(e.userUuid.isBlank() ? "system" : e.userUuid) %>
              <% if (!safe(e.documentUuid).isBlank()) { %> | doc=<%= esc(e.documentUuid) %><% } %>
            </div>
            <% if (!safe(e.details).isBlank()) { %>
              <div class="activity-details"><%= esc(e.details) %></div>
            <% } %>
          </div>
        </article>
      <% } %>
    </div>
  <% } %>
</section>

<style>
  .activity-feed { display:grid; gap:10px; }
  .activity-item {
    display:grid;
    grid-template-columns: 220px 1fr;
    gap:12px;
    border:1px solid var(--border);
    border-radius: 12px;
    padding: 12px;
    background: var(--surface-2);
  }
  .activity-time { font-size:0.92rem; }
  .activity-head { display:flex; align-items:center; gap:8px; margin-bottom:4px; flex-wrap:wrap; }
  .activity-details {
    margin-top:8px;
    padding:8px 10px;
    border-radius:10px;
    border:1px solid var(--border);
    background: var(--surface);
    font-size:0.92rem;
    line-height:1.45;
  }
  .activity-level-verbose {
    background: rgba(37,99,235,0.12);
    border-color: rgba(37,99,235,0.28);
    color: #1D4ED8;
  }
  .activity-level-warning {
    background: var(--warn-fill);
    border-color: rgba(217,119,6,0.3);
    color: #92400E;
  }
  .activity-level-error {
    background: var(--danger-fill);
    border-color: rgba(220,38,38,0.3);
    color: #991B1B;
  }
  @media (max-width: 880px) {
    .activity-item { grid-template-columns: 1fr; }
  }
</style>

<jsp:include page="footer.jsp" />
