<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" isELIgnored="true" %>

<%@ page import="java.net.URLEncoder" %>
<%@ page import="java.nio.charset.StandardCharsets" %>
<%@ page import="java.time.Instant" %>
<%@ page import="java.time.LocalDateTime" %>
<%@ page import="java.time.OffsetDateTime" %>
<%@ page import="java.time.ZoneId" %>
<%@ page import="java.time.format.DateTimeFormatter" %>
<%@ page import="java.util.ArrayList" %>
<%@ page import="java.util.HashMap" %>
<%@ page import="java.util.LinkedHashMap" %>
<%@ page import="java.util.LinkedHashSet" %>
<%@ page import="java.util.List" %>
<%@ page import="java.util.Locale" %>
<%@ page import="java.util.Map" %>

<%@ page import="net.familylawandprobate.controversies.activity_log" %>
<%@ page import="net.familylawandprobate.controversies.document_parts" %>
<%@ page import="net.familylawandprobate.controversies.documents" %>
<%@ page import="net.familylawandprobate.controversies.matter_facts" %>
<%@ page import="net.familylawandprobate.controversies.matters" %>
<%@ page import="net.familylawandprobate.controversies.omnichannel_tickets" %>
<%@ page import="net.familylawandprobate.controversies.part_versions" %>
<%@ page import="net.familylawandprobate.controversies.task_attributes" %>
<%@ page import="net.familylawandprobate.controversies.task_fields" %>
<%@ page import="net.familylawandprobate.controversies.tasks" %>
<%@ page import="net.familylawandprobate.controversies.users_roles" %>

<%@ include file="security.jspf" %>
<%
  if (!require_login()) return;
%>

<%!
  private static final String S_TENANT_UUID = "tenant.uuid";
  private static final String S_USER_UUID = "user.uuid";
  private static final String CSRF_SESSION_KEY = "CSRF_TOKEN";

  private static final DateTimeFormatter DT_LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

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

  private static void logWarn(jakarta.servlet.ServletContext app, String message, Throwable ex) {
    if (app == null) return;
    if (ex == null) app.log("[tasks] " + safe(message));
    else app.log("[tasks] " + safe(message), ex);
  }

  private static String shortErr(Throwable ex) {
    if (ex == null) return "";
    String m = safe(ex.getMessage()).trim();
    return m.isBlank() ? ex.getClass().getSimpleName() : m;
  }

  private static String joinCsvIds(String[] values) {
    if (values == null || values.length == 0) return "";
    LinkedHashSet<String> out = new LinkedHashSet<String>();
    for (int i = 0; i < values.length; i++) {
      String id = safe(values[i]).trim();
      if (id.isBlank()) continue;
      out.add(id);
    }
    return String.join(",", out);
  }

  private static List<String> csvToList(String csv) {
    ArrayList<String> out = new ArrayList<String>();
    String[] parts = safe(csv).split(",");
    LinkedHashSet<String> seen = new LinkedHashSet<String>();
    for (int i = 0; i < parts.length; i++) {
      String id = safe(parts[i]).trim();
      if (id.isBlank()) continue;
      if (!seen.add(id)) continue;
      out.add(id);
    }
    return out;
  }

  private static boolean csvContains(String csv, String id) {
    String target = safe(id).trim();
    if (target.isBlank()) return false;
    String[] parts = safe(csv).split(",");
    for (int i = 0; i < parts.length; i++) {
      if (target.equals(safe(parts[i]).trim())) return true;
    }
    return false;
  }

  private static String toLocalDateTimeInput(String raw) {
    String v = safe(raw).trim();
    if (v.isBlank()) return "";
    try {
      Instant i = Instant.parse(v);
      return DT_LOCAL.format(i.atZone(ZoneId.systemDefault()));
    } catch (Exception ignored) {}
    try {
      OffsetDateTime odt = OffsetDateTime.parse(v);
      return DT_LOCAL.format(odt.atZoneSameInstant(ZoneId.systemDefault()));
    } catch (Exception ignored) {}
    try {
      LocalDateTime ldt = LocalDateTime.parse(v);
      return DT_LOCAL.format(ldt);
    } catch (Exception ignored) {}
    if (v.length() >= 16 && v.contains("T")) return v.substring(0, 16);
    return "";
  }

  private static final class TaskTreeRow {
    public final tasks.TaskRec task;
    public final int depth;
    TaskTreeRow(tasks.TaskRec task, int depth) {
      this.task = task;
      this.depth = Math.max(0, depth);
    }
  }

  private static void appendTaskRows(tasks.TaskRec parent,
                                     int depth,
                                     Map<String, List<tasks.TaskRec>> byParent,
                                     LinkedHashSet<String> visited,
                                     List<TaskTreeRow> out) {
    if (parent == null || out == null || byParent == null || visited == null) return;
    String id = safe(parent.uuid).trim();
    if (id.isBlank()) return;
    if (!visited.add(id)) return;
    out.add(new TaskTreeRow(parent, depth));

    List<tasks.TaskRec> kids = byParent.get(id);
    if (kids == null || kids.isEmpty()) return;
    for (int i = 0; i < kids.size(); i++) {
      appendTaskRows(kids.get(i), depth + 1, byParent, visited, out);
    }
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

  tasks taskStore = tasks.defaultStore();
  task_fields fieldStore = task_fields.defaultStore();
  task_attributes attrStore = task_attributes.defaultStore();
  matters matterStore = matters.defaultStore();
  users_roles usersStore = users_roles.defaultStore();
  matter_facts factsStore = matter_facts.defaultStore();
  documents docStore = documents.defaultStore();
  document_parts partStore = document_parts.defaultStore();
  part_versions versionStore = part_versions.defaultStore();
  omnichannel_tickets threadStore = omnichannel_tickets.defaultStore();
  activity_log logs = activity_log.defaultStore();

  try { taskStore.ensure(tenantUuid); } catch (Exception ex) { logWarn(application, "Unable to ensure tasks: " + shortErr(ex), ex); }
  try { attrStore.ensure(tenantUuid); } catch (Exception ex) { logWarn(application, "Unable to ensure task attributes: " + shortErr(ex), ex); }
  try { matterStore.ensure(tenantUuid); } catch (Exception ex) { logWarn(application, "Unable to ensure matters: " + shortErr(ex), ex); }
  try { usersStore.ensure(tenantUuid); } catch (Exception ex) { logWarn(application, "Unable to ensure users: " + shortErr(ex), ex); }
  try { threadStore.ensure(tenantUuid); } catch (Exception ex) { logWarn(application, "Unable to ensure threads: " + shortErr(ex), ex); }

  String csrfToken = csrfForRender(request);
  String error = null;
  String message = null;

  List<task_attributes.AttributeRec> enabledAttrs = new ArrayList<task_attributes.AttributeRec>();
  try { enabledAttrs = attrStore.listEnabled(tenantUuid); } catch (Exception ex) { logWarn(application, "Unable to load task attributes: " + shortErr(ex), ex); }

  List<matters.MatterRec> mattersAll = new ArrayList<matters.MatterRec>();
  List<matters.MatterRec> mattersActive = new ArrayList<matters.MatterRec>();
  HashMap<String, String> matterLabelByUuid = new HashMap<String, String>();
  try {
    mattersAll = matterStore.listAll(tenantUuid);
    for (int i = 0; i < mattersAll.size(); i++) {
      matters.MatterRec m = mattersAll.get(i);
      if (m == null) continue;
      matterLabelByUuid.put(safe(m.uuid), safe(m.label));
      if (!m.trashed) mattersActive.add(m);
    }
  } catch (Exception ex) {
    logWarn(application, "Unable to load matters: " + shortErr(ex), ex);
    error = "Unable to load matters.";
  }

  List<users_roles.UserRec> usersAll = new ArrayList<users_roles.UserRec>();
  List<users_roles.UserRec> usersActive = new ArrayList<users_roles.UserRec>();
  HashMap<String, String> userEmailByUuid = new HashMap<String, String>();
  List<String> activeUserUuids = new ArrayList<String>();
  try {
    usersAll = usersStore.listUsers(tenantUuid);
    for (int i = 0; i < usersAll.size(); i++) {
      users_roles.UserRec u = usersAll.get(i);
      if (u == null) continue;
      userEmailByUuid.put(safe(u.uuid), safe(u.emailAddress));
      if (!u.enabled) continue;
      usersActive.add(u);
      activeUserUuids.add(safe(u.uuid));
    }
  } catch (Exception ex) {
    logWarn(application, "Unable to load users: " + shortErr(ex), ex);
  }

  String show = safe(request.getParameter("show")).trim().toLowerCase(Locale.ROOT);
  if (show.isBlank()) show = "active";
  boolean includeArchived = "all".equals(show);

  String matterFilter = safe(request.getParameter("matter_filter")).trim();
  String statusFilter = safe(request.getParameter("status_filter")).trim().toLowerCase(Locale.ROOT);
  String q = safe(request.getParameter("q")).trim();
  String selectedTaskUuid = safe(request.getParameter("task_uuid")).trim();

  if ("POST".equalsIgnoreCase(request.getMethod())) {
    String action = safe(request.getParameter("action")).trim().toLowerCase(Locale.ROOT);
    try {
      if ("create_task".equals(action)) {
        String assignmentMode = safe(request.getParameter("assignment_mode")).trim();
        String assignedUserUuid = joinCsvIds(request.getParameterValues("assigned_user_uuid_multi"));
        if (assignedUserUuid.isBlank()) assignedUserUuid = safe(request.getParameter("assigned_user_uuid")).trim();
        if ("round_robin".equalsIgnoreCase(assignmentMode)) {
          List<String> candidates = csvToList(assignedUserUuid);
          if (candidates.isEmpty()) candidates = new ArrayList<String>(activeUserUuids);
          if (!candidates.isEmpty()) {
            String queueKey = safe(request.getParameter("round_robin_queue_key")).trim();
            if (queueKey.isBlank()) {
              queueKey = safe(request.getParameter("matter_uuid")).trim() + "|"
                       + safe(request.getParameter("thread_uuid")).trim();
            }
            assignedUserUuid = taskStore.chooseRoundRobinAssignee(tenantUuid, queueKey, candidates);
          }
        }

        tasks.TaskRec in = new tasks.TaskRec();
        in.matterUuid = safe(request.getParameter("matter_uuid")).trim();
        in.parentTaskUuid = safe(request.getParameter("parent_task_uuid")).trim();
        in.title = safe(request.getParameter("title"));
        in.description = safe(request.getParameter("description"));
        in.status = safe(request.getParameter("status"));
        in.priority = safe(request.getParameter("priority"));
        in.assignmentMode = assignmentMode;
        in.assignedUserUuid = assignedUserUuid;
        in.dueAt = safe(request.getParameter("due_at")).trim();
        in.reminderAt = safe(request.getParameter("reminder_at")).trim();
        in.estimateMinutes = parseIntSafe(request.getParameter("estimate_minutes"), 0);
        in.claimUuid = safe(request.getParameter("claim_uuid")).trim();
        in.elementUuid = safe(request.getParameter("element_uuid")).trim();
        in.factUuid = safe(request.getParameter("fact_uuid")).trim();
        in.documentUuid = safe(request.getParameter("document_uuid")).trim();
        in.partUuid = safe(request.getParameter("part_uuid")).trim();
        in.versionUuid = safe(request.getParameter("version_uuid")).trim();
        in.pageNumber = parseIntSafe(request.getParameter("page_number"), 0);
        in.threadUuid = safe(request.getParameter("thread_uuid")).trim();

        tasks.TaskRec created = taskStore.createTask(tenantUuid, in, actor, safe(request.getParameter("assignment_reason")));

        if (created != null) {
          LinkedHashMap<String, String> values = new LinkedHashMap<String, String>();
          for (int i = 0; i < enabledAttrs.size(); i++) {
            task_attributes.AttributeRec a = enabledAttrs.get(i);
            if (a == null) continue;
            String key = safe(a.key).trim();
            if (key.isBlank()) continue;
            String val = safe(request.getParameter("new_field_" + key)).trim();
            if (a.required && val.isBlank()) {
              throw new IllegalArgumentException("Required task attribute missing: " + safe(a.label));
            }
            if (!val.isBlank()) values.put(key, val);
          }
          fieldStore.write(tenantUuid, created.uuid, values);
        }

        logs.logVerbose("tasks.task.created", tenantUuid, userUuid, safe(created == null ? "" : created.matterUuid), "",
          Map.of("task_uuid", safe(created == null ? "" : created.uuid), "title", safe(created == null ? "" : created.title)));

        response.sendRedirect(ctx + "/tasks.jsp?saved=created&task_uuid=" + enc(safe(created == null ? "" : created.uuid))
          + "&matter_filter=" + enc(safe(created == null ? "" : created.matterUuid)));
        return;
      }

      if ("save_task".equals(action)) {
        String taskUuid = safe(request.getParameter("task_uuid")).trim();
        tasks.TaskRec current = taskStore.getTask(tenantUuid, taskUuid);
        if (current == null) throw new IllegalStateException("Task not found.");

        String assignmentMode = safe(request.getParameter("assignment_mode")).trim();
        String assignedUserUuid = joinCsvIds(request.getParameterValues("assigned_user_uuid_multi"));
        if (assignedUserUuid.isBlank()) assignedUserUuid = safe(request.getParameter("assigned_user_uuid")).trim();
        if ("round_robin".equalsIgnoreCase(assignmentMode)) {
          List<String> candidates = csvToList(assignedUserUuid);
          if (candidates.isEmpty()) candidates = new ArrayList<String>(activeUserUuids);
          if (!candidates.isEmpty()) {
            String queueKey = safe(request.getParameter("round_robin_queue_key")).trim();
            if (queueKey.isBlank()) queueKey = safe(request.getParameter("matter_uuid")).trim() + "|" + safe(request.getParameter("thread_uuid")).trim();
            assignedUserUuid = taskStore.chooseRoundRobinAssignee(tenantUuid, queueKey, candidates);
          }
        }

        tasks.TaskRec in = new tasks.TaskRec();
        in.uuid = taskUuid;
        in.matterUuid = safe(request.getParameter("matter_uuid")).trim();
        in.parentTaskUuid = safe(request.getParameter("parent_task_uuid")).trim();
        in.title = safe(request.getParameter("title"));
        in.description = safe(request.getParameter("description"));
        in.status = safe(request.getParameter("status"));
        in.priority = safe(request.getParameter("priority"));
        in.assignmentMode = assignmentMode;
        in.assignedUserUuid = assignedUserUuid;
        in.dueAt = safe(request.getParameter("due_at")).trim();
        in.reminderAt = safe(request.getParameter("reminder_at")).trim();
        in.estimateMinutes = parseIntSafe(request.getParameter("estimate_minutes"), 0);

        in.claimUuid = safe(request.getParameter("claim_uuid")).trim();
        in.elementUuid = safe(request.getParameter("element_uuid")).trim();
        in.factUuid = safe(request.getParameter("fact_uuid")).trim();
        in.documentUuid = safe(request.getParameter("document_uuid")).trim();
        in.partUuid = safe(request.getParameter("part_uuid")).trim();
        in.versionUuid = safe(request.getParameter("version_uuid")).trim();
        in.pageNumber = parseIntSafe(request.getParameter("page_number"), 0);
        in.threadUuid = safe(request.getParameter("thread_uuid")).trim();

        in.createdBy = safe(current.createdBy);
        in.createdAt = safe(current.createdAt);
        in.updatedAt = safe(current.updatedAt);
        in.completedAt = safe(current.completedAt);
        in.archived = current.archived;
        in.reportDocumentUuid = safe(current.reportDocumentUuid);
        in.reportPartUuid = safe(current.reportPartUuid);
        in.lastReportVersionUuid = safe(current.lastReportVersionUuid);

        boolean ok = taskStore.updateTask(tenantUuid, in, actor, safe(request.getParameter("assignment_reason")));
        if (!ok) throw new IllegalStateException("Task not found.");

        LinkedHashMap<String, String> values = new LinkedHashMap<String, String>(fieldStore.read(tenantUuid, taskUuid));
        for (int i = 0; i < enabledAttrs.size(); i++) {
          task_attributes.AttributeRec a = enabledAttrs.get(i);
          if (a == null) continue;
          String key = safe(a.key).trim();
          if (key.isBlank()) continue;
          String val = safe(request.getParameter("field_" + key)).trim();
          if (a.required && val.isBlank()) {
            throw new IllegalArgumentException("Required task attribute missing: " + safe(a.label));
          }
          if (val.isBlank()) values.remove(key);
          else values.put(key, val);
        }
        fieldStore.write(tenantUuid, taskUuid, values);

        logs.logVerbose("tasks.task.updated", tenantUuid, userUuid, safe(in.matterUuid), "", Map.of("task_uuid", safe(taskUuid)));
        response.sendRedirect(ctx + "/tasks.jsp?saved=updated&task_uuid=" + enc(taskUuid)
          + "&matter_filter=" + enc(safe(in.matterUuid)) + "&show=" + enc(show));
        return;
      }

      if ("archive_task".equals(action) || "restore_task".equals(action)) {
        String taskUuid = safe(request.getParameter("task_uuid")).trim();
        boolean archive = "archive_task".equals(action);
        taskStore.setArchived(tenantUuid, taskUuid, archive, actor);
        response.sendRedirect(ctx + "/tasks.jsp?saved=" + enc(archive ? "archived" : "restored") + "&task_uuid=" + enc(taskUuid)
          + "&matter_filter=" + enc(safe(request.getParameter("matter_filter"))) + "&show=" + enc(show));
        return;
      }

      if ("add_note".equals(action)) {
        String taskUuid = safe(request.getParameter("task_uuid")).trim();
        taskStore.addNote(tenantUuid, taskUuid, request.getParameter("note_body"), actor);
        response.sendRedirect(ctx + "/tasks.jsp?saved=note_added&task_uuid=" + enc(taskUuid)
          + "&matter_filter=" + enc(safe(request.getParameter("matter_filter"))) + "&show=" + enc(show));
        return;
      }

      if ("refresh_report".equals(action)) {
        String taskUuid = safe(request.getParameter("task_uuid")).trim();
        taskStore.refreshTaskReport(tenantUuid, taskUuid, actor);
        response.sendRedirect(ctx + "/tasks.jsp?saved=report_refreshed&task_uuid=" + enc(taskUuid)
          + "&matter_filter=" + enc(safe(request.getParameter("matter_filter"))) + "&show=" + enc(show));
        return;
      }

    } catch (Exception ex) {
      logWarn(application, "Task action failed (" + action + "): " + shortErr(ex), ex);
      error = "Unable to save: " + safe(ex.getMessage());
      try {
        logs.logError("tasks.action_failed", tenantUuid, userUuid, safe(request.getParameter("matter_uuid")), "",
          Map.of("action", safe(action), "message", safe(ex.getMessage())));
      } catch (Exception ignored) {}
    }
  }

  String saved = safe(request.getParameter("saved")).trim();
  if ("created".equals(saved)) message = "Task created.";
  if ("updated".equals(saved)) message = "Task updated.";
  if ("archived".equals(saved)) message = "Task archived.";
  if ("restored".equals(saved)) message = "Task restored.";
  if ("note_added".equals(saved)) message = "Internal note added.";
  if ("report_refreshed".equals(saved)) message = "Task PDF report regenerated.";

  List<tasks.TaskRec> allTasks = new ArrayList<tasks.TaskRec>();
  try { allTasks = taskStore.listTasks(tenantUuid); } catch (Exception ex) { logWarn(application, "Unable to list tasks: " + shortErr(ex), ex); }

  List<tasks.TaskRec> filtered = new ArrayList<tasks.TaskRec>();
  HashMap<String, tasks.TaskRec> byId = new HashMap<String, tasks.TaskRec>();
  for (int i = 0; i < allTasks.size(); i++) {
    tasks.TaskRec t = allTasks.get(i);
    if (t == null) continue;
    byId.put(safe(t.uuid), t);
  }

  for (int i = 0; i < allTasks.size(); i++) {
    tasks.TaskRec t = allTasks.get(i);
    if (t == null) continue;
    if (!includeArchived && t.archived) continue;
    if (!matterFilter.isBlank() && !matterFilter.equals(safe(t.matterUuid))) continue;
    if (!statusFilter.isBlank() && !statusFilter.equalsIgnoreCase(safe(t.status))) continue;
    if (!q.isBlank()) {
      String hay = (safe(t.title) + " " + safe(t.description)).toLowerCase(Locale.ROOT);
      if (!hay.contains(q.toLowerCase(Locale.ROOT))) continue;
    }
    filtered.add(t);
  }

  LinkedHashMap<String, List<tasks.TaskRec>> childrenByParent = new LinkedHashMap<String, List<tasks.TaskRec>>();
  for (int i = 0; i < filtered.size(); i++) {
    tasks.TaskRec t = filtered.get(i);
    if (t == null) continue;
    String pid = safe(t.parentTaskUuid).trim();
    if (!childrenByParent.containsKey(pid)) childrenByParent.put(pid, new ArrayList<tasks.TaskRec>());
    childrenByParent.get(pid).add(t);
  }
  for (Map.Entry<String, List<tasks.TaskRec>> e : childrenByParent.entrySet()) {
    List<tasks.TaskRec> rows = e.getValue();
    rows.sort((a, b) -> {
      String ad = safe(a == null ? "" : a.dueAt);
      String bd = safe(b == null ? "" : b.dueAt);
      int byDue = ad.compareToIgnoreCase(bd);
      if (byDue != 0) return byDue;
      return safe(a == null ? "" : a.title).compareToIgnoreCase(safe(b == null ? "" : b.title));
    });
  }

  List<TaskTreeRow> treeRows = new ArrayList<TaskTreeRow>();
  LinkedHashSet<String> visited = new LinkedHashSet<String>();

  for (int i = 0; i < filtered.size(); i++) {
    tasks.TaskRec t = filtered.get(i);
    if (t == null) continue;
    String parentId = safe(t.parentTaskUuid).trim();
    if (parentId.isBlank() || !byId.containsKey(parentId)) {
      appendTaskRows(t, 0, childrenByParent, visited, treeRows);
    }
  }
  for (int i = 0; i < filtered.size(); i++) {
    tasks.TaskRec t = filtered.get(i);
    if (t == null) continue;
    if (visited.contains(safe(t.uuid))) continue;
    appendTaskRows(t, 0, childrenByParent, visited, treeRows);
  }

  tasks.TaskRec selectedTask = null;
  if (!selectedTaskUuid.isBlank()) {
    for (int i = 0; i < filtered.size(); i++) {
      tasks.TaskRec t = filtered.get(i);
      if (t == null) continue;
      if (selectedTaskUuid.equals(safe(t.uuid))) { selectedTask = t; break; }
    }
  }
  if (selectedTask == null && !treeRows.isEmpty()) {
    selectedTask = treeRows.get(0).task;
    selectedTaskUuid = safe(selectedTask == null ? "" : selectedTask.uuid);
  }

  String selectedMatterUuid = safe(selectedTask == null ? "" : selectedTask.matterUuid).trim();
  if (selectedMatterUuid.isBlank()) selectedMatterUuid = safe(matterFilter).trim();
  if (selectedMatterUuid.isBlank() && !mattersActive.isEmpty()) selectedMatterUuid = safe(mattersActive.get(0).uuid).trim();

  List<matter_facts.ClaimRec> claims = new ArrayList<matter_facts.ClaimRec>();
  List<matter_facts.ElementRec> elements = new ArrayList<matter_facts.ElementRec>();
  List<matter_facts.FactRec> facts = new ArrayList<matter_facts.FactRec>();
  List<documents.DocumentRec> docs = new ArrayList<documents.DocumentRec>();
  List<document_parts.PartRec> parts = new ArrayList<document_parts.PartRec>();
  List<part_versions.VersionRec> versions = new ArrayList<part_versions.VersionRec>();
  List<omnichannel_tickets.TicketRec> threads = new ArrayList<omnichannel_tickets.TicketRec>();
  HashMap<String, String> docLabelByUuid = new HashMap<String, String>();
  HashMap<String, String> partLabelByUuid = new HashMap<String, String>();
  HashMap<String, String> partDocByUuid = new HashMap<String, String>();
  HashMap<String, String> versionLabelByUuid = new HashMap<String, String>();
  HashMap<String, String> versionPartByUuid = new HashMap<String, String>();
  HashMap<String, String> versionDocByUuid = new HashMap<String, String>();

  if (!selectedMatterUuid.isBlank()) {
    try {
      claims = factsStore.listClaims(tenantUuid, selectedMatterUuid);
      elements = factsStore.listElements(tenantUuid, selectedMatterUuid, "");
      facts = factsStore.listFacts(tenantUuid, selectedMatterUuid, "", "");
    } catch (Exception ex) {
      logWarn(application, "Unable to load facts references: " + shortErr(ex), ex);
    }

    try {
      docs = docStore.listAll(tenantUuid, selectedMatterUuid);
      for (int i = 0; i < docs.size(); i++) {
        documents.DocumentRec d = docs.get(i);
        if (d == null || d.trashed) continue;
        docLabelByUuid.put(safe(d.uuid), safe(d.title));

        List<document_parts.PartRec> pRows = partStore.listAll(tenantUuid, selectedMatterUuid, d.uuid);
        for (int pi = 0; pi < pRows.size(); pi++) {
          document_parts.PartRec p = pRows.get(pi);
          if (p == null || p.trashed) continue;
          parts.add(p);
          partLabelByUuid.put(safe(p.uuid), safe(p.label));
          partDocByUuid.put(safe(p.uuid), safe(d.uuid));

          List<part_versions.VersionRec> vRows = versionStore.listAll(tenantUuid, selectedMatterUuid, d.uuid, p.uuid);
          for (int vi = 0; vi < vRows.size(); vi++) {
            part_versions.VersionRec v = vRows.get(vi);
            if (v == null) continue;
            versions.add(v);
            versionLabelByUuid.put(safe(v.uuid), safe(v.versionLabel));
            versionPartByUuid.put(safe(v.uuid), safe(p.uuid));
            versionDocByUuid.put(safe(v.uuid), safe(d.uuid));
          }
        }
      }
    } catch (Exception ex) {
      logWarn(application, "Unable to load document references: " + shortErr(ex), ex);
    }

    try {
      List<omnichannel_tickets.TicketRec> allThreads = threadStore.listTickets(tenantUuid);
      for (int i = 0; i < allThreads.size(); i++) {
        omnichannel_tickets.TicketRec t = allThreads.get(i);
        if (t == null || t.archived) continue;
        if (!selectedMatterUuid.equals(safe(t.matterUuid))) continue;
        threads.add(t);
      }
    } catch (Exception ex) {
      logWarn(application, "Unable to load thread references: " + shortErr(ex), ex);
    }
  }

  LinkedHashMap<String, String> selectedFields = new LinkedHashMap<String, String>();
  List<tasks.NoteRec> selectedNotes = new ArrayList<tasks.NoteRec>();
  List<tasks.AssignmentRec> selectedAssignments = new ArrayList<tasks.AssignmentRec>();
  if (selectedTask != null) {
    try { selectedFields = new LinkedHashMap<String, String>(fieldStore.read(tenantUuid, selectedTask.uuid)); } catch (Exception ignored) {}
    try { selectedNotes = taskStore.listNotes(tenantUuid, selectedTask.uuid); } catch (Exception ignored) {}
    try { selectedAssignments = taskStore.listAssignments(tenantUuid, selectedTask.uuid); } catch (Exception ignored) {}
  }

  int activeCount = 0;
  int completedCount = 0;
  int blockedCount = 0;
  for (int i = 0; i < allTasks.size(); i++) {
    tasks.TaskRec t = allTasks.get(i);
    if (t == null || t.archived) continue;
    activeCount++;
    if ("completed".equalsIgnoreCase(safe(t.status))) completedCount++;
    if ("blocked".equalsIgnoreCase(safe(t.status))) blockedCount++;
  }
%>

<jsp:include page="header.jsp" />

<style>
  .tasks-shell {
    display: grid;
    grid-template-columns: 360px 1fr;
    gap: 12px;
  }
  .tasks-tree-pane {
    position: sticky;
    top: 82px;
    max-height: calc(100vh - 150px);
    overflow: auto;
  }
  .tasks-tree {
    list-style: none;
    margin: 0;
    padding: 0;
  }
  .tasks-row {
    margin: 6px 0;
  }
  .tasks-node {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 7px 8px;
    border-radius: 8px;
    border: 1px solid transparent;
    background: transparent;
  }
  .tasks-node.is-active {
    background: var(--accent-soft);
    border-color: var(--accent);
  }
  .tasks-node a {
    text-decoration: none;
    color: var(--text);
    display: block;
    flex: 1 1 auto;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .tasks-node .tag {
    font-size: 0.72rem;
    border: 1px solid var(--border);
    border-radius: 999px;
    padding: 2px 6px;
    color: var(--muted);
    flex: 0 0 auto;
  }
  .tasks-node .tag.done {
    color: #065f46;
    border-color: rgba(16, 185, 129, 0.45);
    background: rgba(16, 185, 129, 0.12);
  }
  .tasks-node .tag.blocked {
    color: #92400e;
    border-color: rgba(245, 158, 11, 0.45);
    background: rgba(245, 158, 11, 0.12);
  }
  .tasks-node .tag.archived {
    color: var(--danger);
    border-color: rgba(220, 38, 38, 0.35);
    background: rgba(220, 38, 38, 0.10);
  }
  .tasks-panels > .card {
    margin-top: 12px;
  }
  .tasks-panels > .card:first-child {
    margin-top: 0;
  }
  .tasks-inline {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 10px;
  }
  .tasks-counts {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(170px, 1fr));
    gap: 10px;
    margin-top: 10px;
  }
  .tasks-count-box {
    border: 1px solid var(--border);
    border-radius: 10px;
    padding: 10px;
    background: var(--surface-2);
  }
  .tasks-count-box .k {
    color: var(--muted);
    font-size: .82rem;
  }
  .tasks-count-box .v {
    font-weight: 700;
    font-size: 1.18rem;
  }
  .tasks-meta-grid {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 10px;
  }
  @media (max-width: 1080px) {
    .tasks-shell {
      grid-template-columns: 1fr;
    }
    .tasks-tree-pane {
      position: static;
      max-height: none;
    }
    .tasks-inline,
    .tasks-meta-grid {
      grid-template-columns: 1fr;
    }
  }
</style>

<section class="card">
  <h1 style="margin:0;">Tasks</h1>
  <div class="meta" style="margin-top:6px;">
    Task workspace with sub-tasks, multi-user assignment/reassignment, due date/time, time estimate,
    internal notes, custom attributes, and visual associations to matters, facts, documents, and threads.
  </div>
</section>

<section class="card" style="margin-top:12px;">
  <form method="get" action="<%= ctx %>/tasks.jsp" class="form">
    <div class="grid grid-4">
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
        <span>Status</span>
        <select name="status_filter">
          <option value=""></option>
          <option value="open" <%= "open".equals(statusFilter) ? "selected" : "" %>>Open</option>
          <option value="in_progress" <%= "in_progress".equals(statusFilter) ? "selected" : "" %>>In Progress</option>
          <option value="blocked" <%= "blocked".equals(statusFilter) ? "selected" : "" %>>Blocked</option>
          <option value="waiting_review" <%= "waiting_review".equals(statusFilter) ? "selected" : "" %>>Waiting Review</option>
          <option value="completed" <%= "completed".equals(statusFilter) ? "selected" : "" %>>Completed</option>
          <option value="cancelled" <%= "cancelled".equals(statusFilter) ? "selected" : "" %>>Cancelled</option>
        </select>
      </label>
      <label>
        <span>View</span>
        <select name="show">
          <option value="active" <%= "active".equals(show) ? "selected" : "" %>>Active Only</option>
          <option value="all" <%= "all".equals(show) ? "selected" : "" %>>Active + Archived</option>
        </select>
      </label>
      <label>
        <span>Search</span>
        <input type="text" name="q" value="<%= esc(q) %>" placeholder="Title or description" />
      </label>
    </div>
    <input type="hidden" name="task_uuid" value="<%= esc(selectedTaskUuid) %>" />
    <button type="submit" class="btn" style="margin-top:10px;">Apply Filters</button>
  </form>

  <% if (message != null) { %>
    <div class="alert alert-ok" style="margin-top:12px;"><%= esc(message) %></div>
  <% } %>
  <% if (error != null) { %>
    <div class="alert alert-error" style="margin-top:12px;"><%= esc(error) %></div>
  <% } %>

  <div class="tasks-counts">
    <div class="tasks-count-box"><div class="k">Visible Tasks</div><div class="v"><%= filtered.size() %></div></div>
    <div class="tasks-count-box"><div class="k">Active</div><div class="v"><%= activeCount %></div></div>
    <div class="tasks-count-box"><div class="k">Completed</div><div class="v"><%= completedCount %></div></div>
    <div class="tasks-count-box"><div class="k">Blocked</div><div class="v"><%= blockedCount %></div></div>
  </div>
</section>

<section class="tasks-shell" style="margin-top:12px;">
  <section class="card tasks-tree-pane">
    <h2 style="margin-top:0;">Task Tree</h2>
    <div class="meta">Nested Tasks and Sub-tasks.</div>

    <% if (treeRows.isEmpty()) { %>
      <div class="muted" style="margin-top:10px;">No tasks match your filters.</div>
    <% } else { %>
      <ul class="tasks-tree" style="margin-top:10px;">
        <% for (int i = 0; i < treeRows.size(); i++) {
             TaskTreeRow row = treeRows.get(i);
             tasks.TaskRec t = row == null ? null : row.task;
             if (t == null) continue;
             String tid = safe(t.uuid);
             String tagClass = "";
             if ("completed".equalsIgnoreCase(safe(t.status))) tagClass = "done";
             else if ("blocked".equalsIgnoreCase(safe(t.status))) tagClass = "blocked";
        %>
          <li class="tasks-row" style="margin-left:<%= row.depth * 18 %>px;">
            <div class="tasks-node <%= tid.equals(selectedTaskUuid) ? "is-active" : "" %>">
              <a href="<%= ctx %>/tasks.jsp?task_uuid=<%= enc(tid) %>&matter_filter=<%= enc(matterFilter) %>&status_filter=<%= enc(statusFilter) %>&show=<%= enc(show) %>&q=<%= enc(q) %>">
                <strong><%= esc(safe(t.title)) %></strong>
                <span class="meta" style="display:block; font-size:.8rem;"><%= esc(safe(t.dueAt)) %> | <%= esc(safe(t.priority)) %></span>
              </a>
              <span class="tag <%= tagClass %>"><%= esc(safe(t.status)) %></span>
              <% if (t.archived) { %><span class="tag archived">Archived</span><% } %>
            </div>
          </li>
        <% } %>
      </ul>
    <% } %>
  </section>

  <section class="tasks-panels">
    <section class="card">
      <h2 style="margin-top:0;">Create Task</h2>
      <form method="post" class="form" action="<%= ctx %>/tasks.jsp">
        <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
        <input type="hidden" name="action" value="create_task" />
        <input type="hidden" name="matter_filter" value="<%= esc(matterFilter) %>" />
        <input type="hidden" name="show" value="<%= esc(show) %>" />

        <div class="tasks-meta-grid">
          <label>
            <span>Matter</span>
            <select name="matter_uuid">
              <option value=""></option>
              <% for (int i = 0; i < mattersActive.size(); i++) {
                   matters.MatterRec m = mattersActive.get(i);
                   if (m == null) continue;
              %>
                <option value="<%= esc(safe(m.uuid)) %>" <%= safe(m.uuid).equals(selectedMatterUuid) ? "selected" : "" %>><%= esc(safe(m.label)) %></option>
              <% } %>
            </select>
          </label>
          <label>
            <span>Parent Task (optional)</span>
            <select name="parent_task_uuid">
              <option value=""></option>
              <% for (int i = 0; i < filtered.size(); i++) {
                   tasks.TaskRec t = filtered.get(i);
                   if (t == null) continue;
              %>
                <option value="<%= esc(safe(t.uuid)) %>"><%= esc(safe(t.title)) %></option>
              <% } %>
            </select>
          </label>
          <label>
            <span>Thread (optional)</span>
            <select name="thread_uuid">
              <option value=""></option>
              <% for (int i = 0; i < threads.size(); i++) {
                   omnichannel_tickets.TicketRec th = threads.get(i);
                   if (th == null) continue;
              %>
                <option value="<%= esc(safe(th.uuid)) %>"><%= esc(safe(th.subject)) %> (<%= esc(safe(th.channel)) %>)</option>
              <% } %>
            </select>
          </label>
        </div>

        <label><span>Title</span><input type="text" name="title" required /></label>
        <label><span>Description</span><textarea name="description" rows="3"></textarea></label>

        <div class="tasks-meta-grid">
          <label>
            <span>Due Date + Time</span>
            <input type="datetime-local" name="due_at" required />
          </label>
          <label>
            <span>Time Estimate (minutes)</span>
            <input type="number" name="estimate_minutes" min="1" step="1" value="30" required />
          </label>
          <label>
            <span>Reminder (optional)</span>
            <input type="datetime-local" name="reminder_at" />
          </label>
        </div>

        <div class="tasks-meta-grid">
          <label>
            <span>Status</span>
            <select name="status">
              <option value="open" selected>Open</option>
              <option value="in_progress">In Progress</option>
              <option value="blocked">Blocked</option>
              <option value="waiting_review">Waiting Review</option>
              <option value="completed">Completed</option>
              <option value="cancelled">Cancelled</option>
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
              <option value="round_robin">Round Robin</option>
            </select>
          </label>
        </div>

        <label>
          <span>Assign Users (multi-select)</span>
          <select name="assigned_user_uuid_multi" multiple size="4">
            <% for (int i = 0; i < usersActive.size(); i++) {
                 users_roles.UserRec u = usersActive.get(i);
                 if (u == null) continue;
            %>
              <option value="<%= esc(safe(u.uuid)) %>"><%= esc(safe(u.emailAddress)) %></option>
            <% } %>
          </select>
        </label>
        <label><span>Assignment Reason</span><input type="text" name="assignment_reason" /></label>

        <h4 style="margin:12px 0 8px 0;">Facts Associations (optional)</h4>
        <div class="tasks-meta-grid">
          <label>
            <span>Claim</span>
            <select name="claim_uuid">
              <option value=""></option>
              <% for (int i = 0; i < claims.size(); i++) {
                   matter_facts.ClaimRec c = claims.get(i);
                   if (c == null || c.trashed) continue;
              %>
                <option value="<%= esc(safe(c.uuid)) %>"><%= esc(safe(c.title)) %></option>
              <% } %>
            </select>
          </label>
          <label>
            <span>Element</span>
            <select name="element_uuid">
              <option value=""></option>
              <% for (int i = 0; i < elements.size(); i++) {
                   matter_facts.ElementRec e = elements.get(i);
                   if (e == null || e.trashed) continue;
              %>
                <option value="<%= esc(safe(e.uuid)) %>"><%= esc(safe(e.title)) %></option>
              <% } %>
            </select>
          </label>
          <label>
            <span>Fact</span>
            <select name="fact_uuid">
              <option value=""></option>
              <% for (int i = 0; i < facts.size(); i++) {
                   matter_facts.FactRec f = facts.get(i);
                   if (f == null || f.trashed) continue;
              %>
                <option value="<%= esc(safe(f.uuid)) %>"><%= esc(safe(f.summary)) %></option>
              <% } %>
            </select>
          </label>
        </div>

        <h4 style="margin:12px 0 8px 0;">Document Associations (optional)</h4>
        <div class="tasks-meta-grid">
          <label>
            <span>Document</span>
            <select name="document_uuid">
              <option value=""></option>
              <% for (int i = 0; i < docs.size(); i++) {
                   documents.DocumentRec d = docs.get(i);
                   if (d == null || d.trashed) continue;
              %>
                <option value="<%= esc(safe(d.uuid)) %>"><%= esc(safe(d.title)) %></option>
              <% } %>
            </select>
          </label>
          <label>
            <span>Part</span>
            <select name="part_uuid">
              <option value=""></option>
              <% for (int i = 0; i < parts.size(); i++) {
                   document_parts.PartRec p = parts.get(i);
                   if (p == null || p.trashed) continue;
                   String dId = safe(partDocByUuid.get(safe(p.uuid)));
              %>
                <option value="<%= esc(safe(p.uuid)) %>"><%= esc(safe(docLabelByUuid.get(dId))) %> :: <%= esc(safe(p.label)) %></option>
              <% } %>
            </select>
          </label>
          <label>
            <span>Version</span>
            <select name="version_uuid">
              <option value=""></option>
              <% for (int i = 0; i < versions.size(); i++) {
                   part_versions.VersionRec v = versions.get(i);
                   if (v == null) continue;
                   String dId = safe(versionDocByUuid.get(safe(v.uuid)));
                   String pId = safe(versionPartByUuid.get(safe(v.uuid)));
              %>
                <option value="<%= esc(safe(v.uuid)) %>"><%= esc(safe(docLabelByUuid.get(dId))) %> :: <%= esc(safe(partLabelByUuid.get(pId))) %> :: <%= esc(safe(v.versionLabel)) %></option>
              <% } %>
            </select>
          </label>
        </div>
        <label><span>Page Number</span><input type="number" name="page_number" min="0" value="0" /></label>

        <% if (!enabledAttrs.isEmpty()) { %>
          <h4 style="margin:12px 0 8px 0;">Custom Attributes</h4>
          <div class="tasks-meta-grid">
            <% for (int i = 0; i < enabledAttrs.size(); i++) {
                 task_attributes.AttributeRec a = enabledAttrs.get(i);
                 if (a == null) continue;
                 String k = safe(a.key).trim();
                 if (k.isBlank()) continue;
            %>
              <label>
                <span><%= esc(safe(a.label)) %><%= a.required ? " *" : "" %></span>
                <% if ("textarea".equals(safe(a.dataType))) { %>
                  <textarea name="new_field_<%= esc(k) %>" rows="2"></textarea>
                <% } else if ("select".equals(safe(a.dataType))) { %>
                  <select name="new_field_<%= esc(k) %>">
                    <option value=""></option>
                    <% List<String> opts = attrStore.optionList(safe(a.options));
                       for (int oi = 0; oi < opts.size(); oi++) {
                         String ov = opts.get(oi);
                    %>
                      <option value="<%= esc(ov) %>"><%= esc(ov) %></option>
                    <% } %>
                  </select>
                <% } else if ("number".equals(safe(a.dataType))) { %>
                  <input type="number" name="new_field_<%= esc(k) %>" />
                <% } else if ("date".equals(safe(a.dataType))) { %>
                  <input type="date" name="new_field_<%= esc(k) %>" />
                <% } else { %>
                  <input type="text" name="new_field_<%= esc(k) %>" />
                <% } %>
              </label>
            <% } %>
          </div>
        <% } %>

        <button type="submit" class="btn" style="margin-top:10px;">Create Task</button>
      </form>
    </section>

    <% if (selectedTask != null) { %>
      <section class="card">
        <h2 style="margin-top:0;">Edit Selected Task</h2>
        <div class="meta">Task UUID: <code><%= esc(safe(selectedTask.uuid)) %></code></div>

        <form method="post" class="form" action="<%= ctx %>/tasks.jsp">
          <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
          <input type="hidden" name="action" value="save_task" />
          <input type="hidden" name="task_uuid" value="<%= esc(safe(selectedTask.uuid)) %>" />
          <input type="hidden" name="matter_filter" value="<%= esc(matterFilter) %>" />
          <input type="hidden" name="show" value="<%= esc(show) %>" />

          <div class="tasks-meta-grid">
            <label>
              <span>Matter</span>
              <select name="matter_uuid">
                <option value=""></option>
                <% for (int i = 0; i < mattersActive.size(); i++) {
                     matters.MatterRec m = mattersActive.get(i);
                     if (m == null) continue;
                %>
                  <option value="<%= esc(safe(m.uuid)) %>" <%= safe(m.uuid).equals(safe(selectedTask.matterUuid)) ? "selected" : "" %>><%= esc(safe(m.label)) %></option>
                <% } %>
              </select>
            </label>
            <label>
              <span>Parent Task</span>
              <select name="parent_task_uuid">
                <option value=""></option>
                <% for (int i = 0; i < allTasks.size(); i++) {
                     tasks.TaskRec t = allTasks.get(i);
                     if (t == null) continue;
                     if (safe(t.uuid).equals(safe(selectedTask.uuid))) continue;
                %>
                  <option value="<%= esc(safe(t.uuid)) %>" <%= safe(t.uuid).equals(safe(selectedTask.parentTaskUuid)) ? "selected" : "" %>><%= esc(safe(t.title)) %></option>
                <% } %>
              </select>
            </label>
            <label>
              <span>Thread</span>
              <select name="thread_uuid">
                <option value=""></option>
                <% for (int i = 0; i < threads.size(); i++) {
                     omnichannel_tickets.TicketRec th = threads.get(i);
                     if (th == null) continue;
                %>
                  <option value="<%= esc(safe(th.uuid)) %>" <%= safe(th.uuid).equals(safe(selectedTask.threadUuid)) ? "selected" : "" %>><%= esc(safe(th.subject)) %> (<%= esc(safe(th.channel)) %>)</option>
                <% } %>
              </select>
            </label>
          </div>

          <label><span>Title</span><input type="text" name="title" value="<%= esc(safe(selectedTask.title)) %>" required /></label>
          <label><span>Description</span><textarea name="description" rows="3"><%= esc(safe(selectedTask.description)) %></textarea></label>

          <div class="tasks-meta-grid">
            <label><span>Due Date + Time</span><input type="datetime-local" name="due_at" value="<%= esc(toLocalDateTimeInput(selectedTask.dueAt)) %>" required /></label>
            <label><span>Time Estimate (minutes)</span><input type="number" name="estimate_minutes" min="1" step="1" value="<%= selectedTask.estimateMinutes %>" required /></label>
            <label><span>Reminder</span><input type="datetime-local" name="reminder_at" value="<%= esc(toLocalDateTimeInput(selectedTask.reminderAt)) %>" /></label>
          </div>

          <div class="tasks-meta-grid">
            <label>
              <span>Status</span>
              <select name="status">
                <option value="open" <%= "open".equals(safe(selectedTask.status)) ? "selected" : "" %>>Open</option>
                <option value="in_progress" <%= "in_progress".equals(safe(selectedTask.status)) ? "selected" : "" %>>In Progress</option>
                <option value="blocked" <%= "blocked".equals(safe(selectedTask.status)) ? "selected" : "" %>>Blocked</option>
                <option value="waiting_review" <%= "waiting_review".equals(safe(selectedTask.status)) ? "selected" : "" %>>Waiting Review</option>
                <option value="completed" <%= "completed".equals(safe(selectedTask.status)) ? "selected" : "" %>>Completed</option>
                <option value="cancelled" <%= "cancelled".equals(safe(selectedTask.status)) ? "selected" : "" %>>Cancelled</option>
              </select>
            </label>
            <label>
              <span>Priority</span>
              <select name="priority">
                <option value="low" <%= "low".equals(safe(selectedTask.priority)) ? "selected" : "" %>>Low</option>
                <option value="normal" <%= "normal".equals(safe(selectedTask.priority)) ? "selected" : "" %>>Normal</option>
                <option value="high" <%= "high".equals(safe(selectedTask.priority)) ? "selected" : "" %>>High</option>
                <option value="urgent" <%= "urgent".equals(safe(selectedTask.priority)) ? "selected" : "" %>>Urgent</option>
              </select>
            </label>
            <label>
              <span>Assignment Mode</span>
              <select name="assignment_mode">
                <option value="manual" <%= "manual".equals(safe(selectedTask.assignmentMode)) ? "selected" : "" %>>Manual</option>
                <option value="round_robin" <%= "round_robin".equals(safe(selectedTask.assignmentMode)) ? "selected" : "" %>>Round Robin</option>
              </select>
            </label>
          </div>

          <label>
            <span>Assign Users (multi-select)</span>
            <select name="assigned_user_uuid_multi" multiple size="4">
              <% for (int i = 0; i < usersActive.size(); i++) {
                   users_roles.UserRec u = usersActive.get(i);
                   if (u == null) continue;
              %>
                <option value="<%= esc(safe(u.uuid)) %>" <%= csvContains(safe(selectedTask.assignedUserUuid), safe(u.uuid)) ? "selected" : "" %>><%= esc(safe(u.emailAddress)) %></option>
              <% } %>
            </select>
          </label>
          <label><span>Assignment Reason</span><input type="text" name="assignment_reason" /></label>

          <h4 style="margin:12px 0 8px 0;">Facts Associations</h4>
          <div class="tasks-meta-grid">
            <label>
              <span>Claim</span>
              <select name="claim_uuid">
                <option value=""></option>
                <% for (int i = 0; i < claims.size(); i++) {
                     matter_facts.ClaimRec c = claims.get(i);
                     if (c == null || c.trashed) continue;
                %>
                  <option value="<%= esc(safe(c.uuid)) %>" <%= safe(c.uuid).equals(safe(selectedTask.claimUuid)) ? "selected" : "" %>><%= esc(safe(c.title)) %></option>
                <% } %>
              </select>
            </label>
            <label>
              <span>Element</span>
              <select name="element_uuid">
                <option value=""></option>
                <% for (int i = 0; i < elements.size(); i++) {
                     matter_facts.ElementRec e = elements.get(i);
                     if (e == null || e.trashed) continue;
                %>
                  <option value="<%= esc(safe(e.uuid)) %>" <%= safe(e.uuid).equals(safe(selectedTask.elementUuid)) ? "selected" : "" %>><%= esc(safe(e.title)) %></option>
                <% } %>
              </select>
            </label>
            <label>
              <span>Fact</span>
              <select name="fact_uuid">
                <option value=""></option>
                <% for (int i = 0; i < facts.size(); i++) {
                     matter_facts.FactRec f = facts.get(i);
                     if (f == null || f.trashed) continue;
                %>
                  <option value="<%= esc(safe(f.uuid)) %>" <%= safe(f.uuid).equals(safe(selectedTask.factUuid)) ? "selected" : "" %>><%= esc(safe(f.summary)) %></option>
                <% } %>
              </select>
            </label>
          </div>

          <h4 style="margin:12px 0 8px 0;">Document Associations</h4>
          <div class="tasks-meta-grid">
            <label>
              <span>Document</span>
              <select name="document_uuid">
                <option value=""></option>
                <% for (int i = 0; i < docs.size(); i++) {
                     documents.DocumentRec d = docs.get(i);
                     if (d == null || d.trashed) continue;
                %>
                  <option value="<%= esc(safe(d.uuid)) %>" <%= safe(d.uuid).equals(safe(selectedTask.documentUuid)) ? "selected" : "" %>><%= esc(safe(d.title)) %></option>
                <% } %>
              </select>
            </label>
            <label>
              <span>Part</span>
              <select name="part_uuid">
                <option value=""></option>
                <% for (int i = 0; i < parts.size(); i++) {
                     document_parts.PartRec p = parts.get(i);
                     if (p == null || p.trashed) continue;
                     String dId = safe(partDocByUuid.get(safe(p.uuid)));
                %>
                  <option value="<%= esc(safe(p.uuid)) %>" <%= safe(p.uuid).equals(safe(selectedTask.partUuid)) ? "selected" : "" %>><%= esc(safe(docLabelByUuid.get(dId))) %> :: <%= esc(safe(p.label)) %></option>
                <% } %>
              </select>
            </label>
            <label>
              <span>Version</span>
              <select name="version_uuid">
                <option value=""></option>
                <% for (int i = 0; i < versions.size(); i++) {
                     part_versions.VersionRec v = versions.get(i);
                     if (v == null) continue;
                     String dId = safe(versionDocByUuid.get(safe(v.uuid)));
                     String pId = safe(versionPartByUuid.get(safe(v.uuid)));
                %>
                  <option value="<%= esc(safe(v.uuid)) %>" <%= safe(v.uuid).equals(safe(selectedTask.versionUuid)) ? "selected" : "" %>><%= esc(safe(docLabelByUuid.get(dId))) %> :: <%= esc(safe(partLabelByUuid.get(pId))) %> :: <%= esc(safe(v.versionLabel)) %></option>
                <% } %>
              </select>
            </label>
          </div>
          <label><span>Page Number</span><input type="number" name="page_number" min="0" value="<%= selectedTask.pageNumber %>" /></label>

          <% if (!enabledAttrs.isEmpty()) { %>
            <h4 style="margin:12px 0 8px 0;">Custom Attributes</h4>
            <div class="tasks-meta-grid">
              <% for (int i = 0; i < enabledAttrs.size(); i++) {
                   task_attributes.AttributeRec a = enabledAttrs.get(i);
                   if (a == null) continue;
                   String k = safe(a.key).trim();
                   if (k.isBlank()) continue;
                   String fv = safe(selectedFields.get(k));
              %>
                <label>
                  <span><%= esc(safe(a.label)) %><%= a.required ? " *" : "" %></span>
                  <% if ("textarea".equals(safe(a.dataType))) { %>
                    <textarea name="field_<%= esc(k) %>" rows="2"><%= esc(fv) %></textarea>
                  <% } else if ("select".equals(safe(a.dataType))) { %>
                    <select name="field_<%= esc(k) %>">
                      <option value=""></option>
                      <% List<String> opts = attrStore.optionList(safe(a.options));
                         for (int oi = 0; oi < opts.size(); oi++) {
                           String ov = opts.get(oi);
                      %>
                        <option value="<%= esc(ov) %>" <%= ov.equals(fv) ? "selected" : "" %>><%= esc(ov) %></option>
                      <% } %>
                    </select>
                  <% } else if ("number".equals(safe(a.dataType))) { %>
                    <input type="number" name="field_<%= esc(k) %>" value="<%= esc(fv) %>" />
                  <% } else if ("date".equals(safe(a.dataType))) { %>
                    <input type="date" name="field_<%= esc(k) %>" value="<%= esc(fv) %>" />
                  <% } else { %>
                    <input type="text" name="field_<%= esc(k) %>" value="<%= esc(fv) %>" />
                  <% } %>
                </label>
              <% } %>
            </div>
          <% } %>

          <button type="submit" class="btn" style="margin-top:10px;">Save Task</button>
        </form>

        <div style="display:flex; gap:8px; flex-wrap:wrap; margin-top:10px;">
          <form method="post" action="<%= ctx %>/tasks.jsp" onsubmit="return confirm('<%= selectedTask.archived ? "Restore" : "Archive" %> this task?');">
            <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
            <input type="hidden" name="action" value="<%= selectedTask.archived ? "restore_task" : "archive_task" %>" />
            <input type="hidden" name="task_uuid" value="<%= esc(safe(selectedTask.uuid)) %>" />
            <input type="hidden" name="matter_filter" value="<%= esc(matterFilter) %>" />
            <button type="submit" class="btn btn-ghost"><%= selectedTask.archived ? "Restore Task" : "Archive Task" %></button>
          </form>
          <form method="post" action="<%= ctx %>/tasks.jsp">
            <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
            <input type="hidden" name="action" value="refresh_report" />
            <input type="hidden" name="task_uuid" value="<%= esc(safe(selectedTask.uuid)) %>" />
            <input type="hidden" name="matter_filter" value="<%= esc(matterFilter) %>" />
            <button type="submit" class="btn btn-ghost">Regenerate Task PDF Report</button>
          </form>
        </div>
      </section>

      <section class="card">
        <h2 style="margin-top:0;">Internal Notes</h2>
        <% if (selectedTask == null) { %>
          <div class="muted">Select a task to manage notes.</div>
        <% } else { %>
          <form method="post" class="form" action="<%= ctx %>/tasks.jsp">
            <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
            <input type="hidden" name="action" value="add_note" />
            <input type="hidden" name="task_uuid" value="<%= esc(safe(selectedTask.uuid)) %>" />
            <input type="hidden" name="matter_filter" value="<%= esc(matterFilter) %>" />
            <textarea name="note_body" rows="3" placeholder="Internal note (not visible to non-users)" required></textarea>
            <button type="submit" class="btn" style="margin-top:8px;">Add Internal Note</button>
          </form>

          <div style="margin-top:12px;">
            <% if (selectedNotes.isEmpty()) { %>
              <div class="muted">No notes yet.</div>
            <% } else { %>
              <table class="table">
                <thead><tr><th>Time</th><th>By</th><th>Note</th></tr></thead>
                <tbody>
                  <% for (int i = 0; i < selectedNotes.size(); i++) {
                       tasks.NoteRec n = selectedNotes.get(i);
                       if (n == null) continue;
                  %>
                    <tr>
                      <td><%= esc(safe(n.createdAt)) %></td>
                      <td><%= esc(safe(n.createdBy)) %></td>
                      <td><%= esc(safe(n.body)) %></td>
                    </tr>
                  <% } %>
                </tbody>
              </table>
            <% } %>
          </div>
        <% } %>
      </section>

      <section class="card">
        <h2 style="margin-top:0;">Assignment History</h2>
        <% if (selectedTask == null) { %>
          <div class="muted">Select a task to view assignment history.</div>
        <% } else if (selectedAssignments.isEmpty()) { %>
          <div class="muted">No assignment changes yet.</div>
        <% } else { %>
          <table class="table">
            <thead>
              <tr>
                <th>Time</th>
                <th>Mode</th>
                <th>From</th>
                <th>To</th>
                <th>By</th>
                <th>Reason</th>
              </tr>
            </thead>
            <tbody>
              <% for (int i = 0; i < selectedAssignments.size(); i++) {
                   tasks.AssignmentRec a = selectedAssignments.get(i);
                   if (a == null) continue;
              %>
                <tr>
                  <td><%= esc(safe(a.changedAt)) %></td>
                  <td><%= esc(safe(a.mode)) %></td>
                  <td><%= esc(safe(a.fromUserUuid)) %></td>
                  <td><%= esc(safe(a.toUserUuid)) %></td>
                  <td><%= esc(safe(a.changedBy)) %></td>
                  <td><%= esc(safe(a.reason)) %></td>
                </tr>
              <% } %>
            </tbody>
          </table>
        <% } %>
      </section>
    <% } %>
  </section>
</section>

<jsp:include page="footer.jsp" />
