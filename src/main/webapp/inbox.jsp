<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" isELIgnored="true" %>

<%@ page import="java.net.URLEncoder" %>
<%@ page import="java.nio.charset.StandardCharsets" %>
<%@ page import="java.time.Instant" %>
<%@ page import="java.time.LocalDateTime" %>
<%@ page import="java.time.OffsetDateTime" %>
<%@ page import="java.time.ZoneId" %>
<%@ page import="java.time.Duration" %>
<%@ page import="java.time.format.DateTimeFormatter" %>
<%@ page import="java.util.ArrayList" %>
<%@ page import="java.util.LinkedHashMap" %>
<%@ page import="java.util.List" %>
<%@ page import="java.util.Locale" %>

<%@ page import="net.familylawandprobate.controversies.unified_inbox" %>
<%@ page import="net.familylawandprobate.controversies.users_roles" %>

<%@ include file="security.jspf" %>
<%
  if (!require_login()) return;
%>

<%!
  private static final String S_TENANT_UUID = "tenant.uuid";
  private static final String S_USER_UUID = "user.uuid";
  private static final DateTimeFormatter DT_LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

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

  private static long parseMillis(String raw) {
    String v = safe(raw).trim();
    if (v.isBlank()) return -1L;
    try { return Instant.parse(v).toEpochMilli(); } catch (Exception ignored) {}
    try { return OffsetDateTime.parse(v).toInstant().toEpochMilli(); } catch (Exception ignored) {}
    try { return LocalDateTime.parse(v).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(); } catch (Exception ignored) {}
    if (v.length() >= 16 && v.length() < 19 && v.contains("T")) {
      try { return LocalDateTime.parse(v + ":00").atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(); } catch (Exception ignored) {}
    }
    return -1L;
  }

  private static String prettyTime(String raw) {
    long ms = parseMillis(raw);
    if (ms <= 0L) return safe(raw);
    try { return DT_LOCAL.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())); } catch (Exception ignored) {}
    return safe(raw);
  }

  private static String dueLabel(String raw) {
    long dueMs = parseMillis(raw);
    if (dueMs <= 0L) return "No deadline";
    long now = System.currentTimeMillis();
    Duration d = Duration.ofMillis(Math.abs(dueMs - now));
    long hours = d.toHours();
    long days = hours / 24L;
    long remHours = hours % 24L;
    String base = prettyTime(raw);
    if (dueMs <= now) {
      if (days > 0L) return "Overdue by " + days + "d " + remHours + "h (" + base + ")";
      return "Overdue by " + Math.max(1L, hours) + "h (" + base + ")";
    }
    if (days > 0L) return "Due in " + days + "d " + remHours + "h (" + base + ")";
    return "Due in " + Math.max(1L, hours) + "h (" + base + ")";
  }

  private static String priorityClass(String priority) {
    String p = safe(priority).trim().toLowerCase(Locale.ROOT);
    if ("urgent".equals(p)) return "inbox-priority-urgent";
    if ("high".equals(p)) return "inbox-priority-high";
    if ("low".equals(p)) return "inbox-priority-low";
    return "inbox-priority-normal";
  }

  private static String typeLabel(String itemType) {
    String t = safe(itemType).trim().toLowerCase(Locale.ROOT);
    if ("task".equals(t)) return "Task";
    if ("thread".equals(t)) return "Thread";
    if ("process_review".equals(t)) return "Process Review";
    if ("mention".equals(t)) return "Mention";
    if ("activity".equals(t)) return "Activity";
    return "Item";
  }

  private static String typeClass(String itemType) {
    String t = safe(itemType).trim().toLowerCase(Locale.ROOT);
    if ("task".equals(t)) return "inbox-type-task";
    if ("thread".equals(t)) return "inbox-type-thread";
    if ("process_review".equals(t)) return "inbox-type-review";
    if ("mention".equals(t)) return "inbox-type-mention";
    if ("activity".equals(t)) return "inbox-type-activity";
    return "";
  }

  private static boolean matchesType(unified_inbox.ItemRec item, String filterType) {
    String wanted = safe(filterType).trim().toLowerCase(Locale.ROOT);
    if (wanted.isBlank() || "all".equals(wanted)) return true;
    return wanted.equals(safe(item == null ? "" : item.itemType).trim().toLowerCase(Locale.ROOT));
  }
%>

<%
  String ctx = safe(request.getContextPath());
  String tenantUuid = safe((String) session.getAttribute(S_TENANT_UUID)).trim();
  String userUuid = safe((String) session.getAttribute(S_USER_UUID)).trim();
  if (tenantUuid.isBlank() || userUuid.isBlank()) {
    response.sendRedirect(ctx + "/tenant_login.jsp?next=" + enc("/inbox.jsp"));
    return;
  }

  boolean canTasks = users_roles.hasPermissionTrue(session, "tasks.access");
  boolean canThreads = users_roles.hasPermissionTrue(session, "threads.access");
  boolean canReviews = users_roles.hasPermissionTrue(session, "business_process_reviews.manage");
  boolean canActivity = users_roles.hasPermissionTrue(session, "logs.view");
  boolean canMentions = true;

  String typeFilter = safe(request.getParameter("type")).trim().toLowerCase(Locale.ROOT);
  if (typeFilter.isBlank()) typeFilter = "all";

  int limit = parseIntSafe(request.getParameter("limit"), 100);
  if (limit < 20) limit = 20;
  if (limit > 300) limit = 300;
  boolean includeClosed = boolLike(request.getParameter("include_closed"));

  unified_inbox.QueryOptions q = new unified_inbox.QueryOptions();
  q.includeTasks = canTasks;
  q.includeThreads = canThreads;
  q.includeReviews = canReviews;
  q.includeActivities = canActivity;
  q.includeMentions = canMentions;
  q.includeArchived = false;
  q.includeClosed = includeClosed;
  q.limit = limit;
  q.activityFetchLimit = 800;
  q.mentionFetchLimit = 800;

  List<unified_inbox.ItemRec> allItems = new ArrayList<unified_inbox.ItemRec>();
  List<unified_inbox.ItemRec> visibleItems = new ArrayList<unified_inbox.ItemRec>();
  String loadError = "";
  try {
    allItems = unified_inbox.defaultService().listForUser(tenantUuid, userUuid, q);
    for (int i = 0; i < allItems.size(); i++) {
      unified_inbox.ItemRec row = allItems.get(i);
      if (row == null) continue;
      if (!matchesType(row, typeFilter)) continue;
      visibleItems.add(row);
    }
  } catch (Exception ex) {
    loadError = safe(ex.getMessage());
  }

  int taskCount = 0;
  int threadCount = 0;
  int reviewCount = 0;
  int mentionCount = 0;
  int activityCount = 0;
  int criticalCount = 0;
  int dueSoonCount = 0;
  for (int i = 0; i < allItems.size(); i++) {
    unified_inbox.ItemRec row = allItems.get(i);
    if (row == null) continue;
    String t = safe(row.itemType).trim().toLowerCase(Locale.ROOT);
    if ("task".equals(t)) taskCount++;
    else if ("thread".equals(t)) threadCount++;
    else if ("process_review".equals(t)) reviewCount++;
    else if ("mention".equals(t)) mentionCount++;
    else if ("activity".equals(t)) activityCount++;

    String bucket = safe(row.deadlineBucket).trim().toLowerCase(Locale.ROOT);
    String priority = safe(row.priority).trim().toLowerCase(Locale.ROOT);
    boolean highPriority = "urgent".equals(priority) || "high".equals(priority);
    if ("overdue".equals(bucket) || "due_24h".equals(bucket)) dueSoonCount++;
    if (highPriority && ("overdue".equals(bucket) || "due_24h".equals(bucket) || "due_72h".equals(bucket))) criticalCount++;
  }

  LinkedHashMap<String, String> disabledModules = new LinkedHashMap<String, String>();
  if (!canTasks) disabledModules.put("Tasks", "tasks.access");
  if (!canThreads) disabledModules.put("Omnichannel Threads", "threads.access");
  if (!canReviews) disabledModules.put("Business Process Reviews", "business_process_reviews.manage");
  if (!canActivity) disabledModules.put("Activity Log", "logs.view");
%>

<jsp:include page="header.jsp" />

<section class="card">
  <div class="section-head">
    <div>
      <h1 style="margin:0;">Unified Inbox</h1>
      <div class="meta">One queue for communication, tasks, process reviews, mentions, and directed activity for your user.</div>
    </div>
  </div>
</section>

<section class="card" style="margin-top:12px;">
  <div class="inbox-counts">
    <div class="inbox-count-card"><div class="k">Visible</div><div class="v"><%= visibleItems.size() %></div></div>
    <div class="inbox-count-card"><div class="k">Critical</div><div class="v"><%= criticalCount %></div></div>
    <div class="inbox-count-card"><div class="k">Due Soon</div><div class="v"><%= dueSoonCount %></div></div>
    <div class="inbox-count-card"><div class="k">Tasks</div><div class="v"><%= taskCount %></div></div>
    <div class="inbox-count-card"><div class="k">Threads</div><div class="v"><%= threadCount %></div></div>
    <div class="inbox-count-card"><div class="k">Reviews</div><div class="v"><%= reviewCount %></div></div>
    <div class="inbox-count-card"><div class="k">Mentions</div><div class="v"><%= mentionCount %></div></div>
    <div class="inbox-count-card"><div class="k">Activity</div><div class="v"><%= activityCount %></div></div>
  </div>
</section>

<section class="card" style="margin-top:12px;">
  <form class="form" method="get" action="<%= ctx %>/inbox.jsp">
    <div class="grid" style="display:grid; grid-template-columns: 2fr 1fr 1fr auto; gap:12px;">
      <label>
        <span>Type</span>
        <select name="type">
          <option value="all" <%= "all".equals(typeFilter) ? "selected" : "" %>>All</option>
          <option value="task" <%= "task".equals(typeFilter) ? "selected" : "" %>>Tasks</option>
          <option value="thread" <%= "thread".equals(typeFilter) ? "selected" : "" %>>Threads</option>
          <option value="process_review" <%= "process_review".equals(typeFilter) ? "selected" : "" %>>Process Reviews</option>
          <option value="mention" <%= "mention".equals(typeFilter) ? "selected" : "" %>>Mentions</option>
          <option value="activity" <%= "activity".equals(typeFilter) ? "selected" : "" %>>Activity</option>
        </select>
      </label>
      <label>
        <span>Rows</span>
        <select name="limit">
          <option value="50" <%= limit == 50 ? "selected" : "" %>>50</option>
          <option value="100" <%= limit == 100 ? "selected" : "" %>>100</option>
          <option value="200" <%= limit == 200 ? "selected" : "" %>>200</option>
          <option value="300" <%= limit == 300 ? "selected" : "" %>>300</option>
        </select>
      </label>
      <label class="inbox-inline-check">
        <span>State</span>
        <span class="inbox-inline-check-row">
          <input type="checkbox" name="include_closed" value="1" <%= includeClosed ? "checked" : "" %> />
          Include closed/completed
        </span>
      </label>
      <label>
        <span>&nbsp;</span>
        <button class="btn" type="submit">Apply</button>
      </label>
    </div>
  </form>
  <% if (!disabledModules.isEmpty()) { %>
    <div class="alert alert-warn" style="margin-top:10px;">
      Some modules are hidden by permission:
      <% int idx = 0; for (String k : disabledModules.keySet()) { %>
        <%= idx++ == 0 ? "" : "; " %><code><%= esc(k) %></code> (<%= esc(disabledModules.get(k)) %>)
      <% } %>
    </div>
  <% } %>
  <% if (!loadError.isBlank()) { %>
    <div class="alert alert-danger" style="margin-top:10px;">Unable to load inbox: <%= esc(loadError) %></div>
  <% } %>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin-top:0;">Priority Queue</h2>
  <div class="meta">Sorted by urgency score (priority + nearing deadline), then nearest deadline, then recent updates.</div>

  <% if (visibleItems.isEmpty()) { %>
    <div class="muted" style="margin-top:10px;">No inbox items match the current filter.</div>
  <% } else { %>
    <div class="table-wrap table-wrap-tight" style="margin-top:10px;">
      <table class="table">
        <thead>
          <tr>
            <th>Priority</th>
            <th>Due</th>
            <th>Type</th>
            <th>Item</th>
            <th>Status</th>
            <th>Updated</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <% for (int i = 0; i < visibleItems.size(); i++) {
               unified_inbox.ItemRec row = visibleItems.get(i);
               if (row == null) continue;
               String link = safe(row.linkPath);
               if (link.isBlank()) link = "/index.jsp";
          %>
            <tr>
              <td><span class="badge <%= priorityClass(row.priority) %>"><%= esc(safe(row.priority).toUpperCase(Locale.ROOT)) %></span></td>
              <td>
                <div><%= esc(dueLabel(row.dueAt)) %></div>
                <div class="meta"><%= esc(safe(row.deadlineBucket)) %></div>
              </td>
              <td><span class="badge <%= typeClass(row.itemType) %>"><%= esc(typeLabel(row.itemType)) %></span></td>
              <td>
                <div style="font-weight:600;"><%= esc(row.title) %></div>
                <% if (!safe(row.summary).isBlank()) { %>
                  <div class="meta"><%= esc(row.summary) %></div>
                <% } %>
              </td>
              <td><%= esc(row.status) %></td>
              <td><%= esc(prettyTime(row.updatedAt)) %></td>
              <td><a class="btn btn-ghost" href="<%= esc(ctx + link) %>">Open</a></td>
            </tr>
          <% } %>
        </tbody>
      </table>
    </div>
  <% } %>
</section>

<style>
  .inbox-counts {
    display:grid;
    grid-template-columns: repeat(8, minmax(110px, 1fr));
    gap:10px;
  }
  .inbox-count-card {
    border:1px solid var(--border);
    border-radius:12px;
    padding:10px 12px;
    background: var(--surface-2);
  }
  .inbox-count-card .k {
    color: var(--muted);
    font-size: 0.82rem;
    text-transform: uppercase;
    letter-spacing: 0.04em;
  }
  .inbox-count-card .v {
    margin-top:4px;
    font-size: 1.35rem;
    font-weight: 700;
  }
  .inbox-inline-check-row {
    display:flex;
    align-items:center;
    gap:8px;
    margin-top:8px;
  }
  .inbox-priority-urgent {
    background: #7F1D1D1F;
    border-color: #B91C1C66;
    color: #991B1B;
  }
  .inbox-priority-high {
    background: #D977061C;
    border-color: #D9770652;
    color: #92400E;
  }
  .inbox-priority-normal {
    background: #0F766E1A;
    border-color: #0F766E4D;
    color: #0F766E;
  }
  .inbox-priority-low {
    background: #3341551A;
    border-color: #3341554D;
    color: #334155;
  }
  .inbox-type-task {
    background: #1D4ED81A;
    border-color: #1D4ED84D;
    color: #1D4ED8;
  }
  .inbox-type-thread {
    background: #7E22CE1A;
    border-color: #7E22CE4D;
    color: #7E22CE;
  }
  .inbox-type-review {
    background: #B453091A;
    border-color: #B453094D;
    color: #B45309;
  }
  .inbox-type-mention {
    background: #1665341A;
    border-color: #1665344D;
    color: #166534;
  }
  .inbox-type-activity {
    background: #4755691A;
    border-color: #4755694D;
    color: #334155;
  }
  @media (max-width: 980px) {
    .inbox-counts {
      grid-template-columns: repeat(2, minmax(120px, 1fr));
    }
  }
</style>

<jsp:include page="footer.jsp" />
