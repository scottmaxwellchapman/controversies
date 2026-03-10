<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" isELIgnored="true" %>

<%@ page import="java.net.URLEncoder" %>
<%@ page import="java.nio.charset.StandardCharsets" %>
<%@ page import="java.util.ArrayList" %>
<%@ page import="java.util.HashMap" %>
<%@ page import="java.util.LinkedHashMap" %>
<%@ page import="java.util.List" %>
<%@ page import="java.util.Locale" %>
<%@ page import="java.util.Map" %>

<%@ page import="net.familylawandprobate.controversies.activity_log" %>
<%@ page import="net.familylawandprobate.controversies.document_parts" %>
<%@ page import="net.familylawandprobate.controversies.documents" %>
<%@ page import="net.familylawandprobate.controversies.matter_facts" %>
<%@ page import="net.familylawandprobate.controversies.matters" %>
<%@ page import="net.familylawandprobate.controversies.part_versions" %>
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

  private static String enc(String s) {
    return URLEncoder.encode(safe(s), StandardCharsets.UTF_8);
  }

  private static int parseIntSafe(String raw, int fallback) {
    try { return Integer.parseInt(safe(raw).trim()); } catch (Exception ignored) { return fallback; }
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
    if (ex == null) app.log("[facts] " + safe(message));
    else app.log("[facts] " + safe(message), ex);
  }

  private static String shortErr(Throwable ex) {
    if (ex == null) return "";
    String m = safe(ex.getMessage()).trim();
    return m.isBlank() ? ex.getClass().getSimpleName() : m;
  }

  private static boolean isSelected(String a, String b) {
    return safe(a).trim().equals(safe(b).trim());
  }

  private static String linkedLabel(String uuid, Map<String, String> labelByUuid, String noun) {
    String id = safe(uuid).trim();
    if (id.isBlank()) return "(none)";
    String label = labelByUuid == null ? "" : safe(labelByUuid.get(id)).trim();
    if (!label.isBlank()) return label;
    return "(linked " + safe(noun).trim() + ")";
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

  matters matterStore = matters.defaultStore();
  matter_facts factsStore = matter_facts.defaultStore();
  documents docStore = documents.defaultStore();
  document_parts partStore = document_parts.defaultStore();
  part_versions versionStore = part_versions.defaultStore();
  activity_log logs = activity_log.defaultStore();

  String csrfToken = csrfForRender(request);
  String error = null;
  String message = null;

  try { matterStore.ensure(tenantUuid); } catch (Exception ex) { logWarn(application, "Unable to ensure matters: " + shortErr(ex), ex); }

  List<matters.MatterRec> allCases = new ArrayList<matters.MatterRec>();
  List<matters.MatterRec> activeCases = new ArrayList<matters.MatterRec>();
  try {
    allCases = matterStore.listAll(tenantUuid);
    for (int i = 0; i < allCases.size(); i++) {
      matters.MatterRec c = allCases.get(i);
      if (c == null || c.trashed) continue;
      activeCases.add(c);
    }
  } catch (Exception ex) {
    logWarn(application, "Unable to list matters: " + shortErr(ex), ex);
    error = "Unable to load matters.";
  }

  String caseUuid = safe(request.getParameter("case_uuid")).trim();
  if (caseUuid.isBlank()) caseUuid = safe(request.getParameter("matter_uuid")).trim();
  if (caseUuid.isBlank() && !activeCases.isEmpty()) caseUuid = safe(activeCases.get(0).uuid);

  String show = safe(request.getParameter("show")).trim().toLowerCase(Locale.ROOT);
  if (show.isBlank()) show = "active";
  boolean includeTrashed = "all".equals(show);

  if ("POST".equalsIgnoreCase(request.getMethod())) {
    String action = safe(request.getParameter("action")).trim().toLowerCase(Locale.ROOT);
    caseUuid = safe(request.getParameter("case_uuid")).trim();
    if (caseUuid.isBlank()) caseUuid = safe(request.getParameter("matter_uuid")).trim();

    String claimUuid = safe(request.getParameter("claim_uuid")).trim();
    String elementUuid = safe(request.getParameter("element_uuid")).trim();
    String factUuid = safe(request.getParameter("fact_uuid")).trim();

    try {
      if (caseUuid.isBlank()) throw new IllegalArgumentException("Select a matter first.");
      factsStore.ensure(tenantUuid, caseUuid);

      if ("create_claim".equals(action)) {
        matter_facts.ClaimRec rec = factsStore.createClaim(
          tenantUuid,
          caseUuid,
          request.getParameter("claim_title"),
          request.getParameter("claim_summary"),
          parseIntSafe(request.getParameter("claim_sort_order"), 0),
          actor
        );
        response.sendRedirect(ctx + "/facts.jsp?case_uuid=" + enc(caseUuid)
          + "&show=" + enc(show)
          + "&claim_uuid=" + enc(safe(rec == null ? "" : rec.uuid))
          + "&saved=claim_created");
        return;
      }

      if ("save_claim".equals(action)) {
        matter_facts.ClaimRec in = new matter_facts.ClaimRec();
        in.uuid = claimUuid;
        in.title = safe(request.getParameter("claim_title"));
        in.summary = safe(request.getParameter("claim_summary"));
        in.sortOrder = parseIntSafe(request.getParameter("claim_sort_order"), 0);
        factsStore.updateClaim(tenantUuid, caseUuid, in, actor);
        response.sendRedirect(ctx + "/facts.jsp?case_uuid=" + enc(caseUuid)
          + "&show=" + enc(show)
          + "&claim_uuid=" + enc(claimUuid)
          + "&saved=claim_saved");
        return;
      }

      if ("archive_claim".equals(action) || "restore_claim".equals(action)) {
        boolean trashed = "archive_claim".equals(action);
        factsStore.setClaimTrashed(tenantUuid, caseUuid, claimUuid, trashed, actor);
        response.sendRedirect(ctx + "/facts.jsp?case_uuid=" + enc(caseUuid)
          + "&show=" + enc(show)
          + "&saved=" + enc(trashed ? "claim_archived" : "claim_restored"));
        return;
      }

      if ("create_element".equals(action)) {
        matter_facts.ElementRec rec = factsStore.createElement(
          tenantUuid,
          caseUuid,
          request.getParameter("claim_uuid"),
          request.getParameter("element_title"),
          request.getParameter("element_notes"),
          parseIntSafe(request.getParameter("element_sort_order"), 0),
          actor
        );
        response.sendRedirect(ctx + "/facts.jsp?case_uuid=" + enc(caseUuid)
          + "&show=" + enc(show)
          + "&claim_uuid=" + enc(safe(rec == null ? claimUuid : rec.claimUuid))
          + "&element_uuid=" + enc(safe(rec == null ? "" : rec.uuid))
          + "&saved=element_created");
        return;
      }

      if ("save_element".equals(action)) {
        matter_facts.ElementRec in = new matter_facts.ElementRec();
        in.uuid = elementUuid;
        in.claimUuid = safe(request.getParameter("claim_uuid"));
        in.title = safe(request.getParameter("element_title"));
        in.notes = safe(request.getParameter("element_notes"));
        in.sortOrder = parseIntSafe(request.getParameter("element_sort_order"), 0);
        factsStore.updateElement(tenantUuid, caseUuid, in, actor);
        response.sendRedirect(ctx + "/facts.jsp?case_uuid=" + enc(caseUuid)
          + "&show=" + enc(show)
          + "&claim_uuid=" + enc(in.claimUuid)
          + "&element_uuid=" + enc(elementUuid)
          + "&saved=element_saved");
        return;
      }

      if ("archive_element".equals(action) || "restore_element".equals(action)) {
        boolean trashed = "archive_element".equals(action);
        factsStore.setElementTrashed(tenantUuid, caseUuid, elementUuid, trashed, actor);
        response.sendRedirect(ctx + "/facts.jsp?case_uuid=" + enc(caseUuid)
          + "&show=" + enc(show)
          + "&claim_uuid=" + enc(claimUuid)
          + "&saved=" + enc(trashed ? "element_archived" : "element_restored"));
        return;
      }

      if ("create_fact".equals(action)) {
        matter_facts.FactRec rec = factsStore.createFact(
          tenantUuid,
          caseUuid,
          request.getParameter("claim_uuid"),
          request.getParameter("element_uuid"),
          request.getParameter("fact_summary"),
          request.getParameter("fact_detail"),
          request.getParameter("fact_internal_notes"),
          request.getParameter("fact_status"),
          request.getParameter("fact_strength"),
          request.getParameter("document_uuid"),
          request.getParameter("part_uuid"),
          request.getParameter("version_uuid"),
          parseIntSafe(request.getParameter("page_number"), 0),
          parseIntSafe(request.getParameter("fact_sort_order"), 0),
          actor
        );
        response.sendRedirect(ctx + "/facts.jsp?case_uuid=" + enc(caseUuid)
          + "&show=" + enc(show)
          + "&claim_uuid=" + enc(safe(rec == null ? "" : rec.claimUuid))
          + "&element_uuid=" + enc(safe(rec == null ? "" : rec.elementUuid))
          + "&fact_uuid=" + enc(safe(rec == null ? "" : rec.uuid))
          + "&saved=fact_created");
        return;
      }

      if ("save_fact".equals(action)) {
        matter_facts.FactRec in = new matter_facts.FactRec();
        in.uuid = factUuid;
        in.claimUuid = safe(request.getParameter("claim_uuid"));
        in.elementUuid = safe(request.getParameter("element_uuid"));
        in.summary = safe(request.getParameter("fact_summary"));
        in.detail = safe(request.getParameter("fact_detail"));
        in.internalNotes = safe(request.getParameter("fact_internal_notes"));
        in.status = safe(request.getParameter("fact_status"));
        in.strength = safe(request.getParameter("fact_strength"));
        in.documentUuid = safe(request.getParameter("document_uuid"));
        in.partUuid = safe(request.getParameter("part_uuid"));
        in.versionUuid = safe(request.getParameter("version_uuid"));
        in.pageNumber = parseIntSafe(request.getParameter("page_number"), 0);
        in.sortOrder = parseIntSafe(request.getParameter("fact_sort_order"), 0);
        factsStore.updateFact(tenantUuid, caseUuid, in, actor);
        response.sendRedirect(ctx + "/facts.jsp?case_uuid=" + enc(caseUuid)
          + "&show=" + enc(show)
          + "&claim_uuid=" + enc(in.claimUuid)
          + "&element_uuid=" + enc(in.elementUuid)
          + "&fact_uuid=" + enc(factUuid)
          + "&saved=fact_saved");
        return;
      }

      if ("archive_fact".equals(action) || "restore_fact".equals(action)) {
        boolean trashed = "archive_fact".equals(action);
        factsStore.setFactTrashed(tenantUuid, caseUuid, factUuid, trashed, actor);
        response.sendRedirect(ctx + "/facts.jsp?case_uuid=" + enc(caseUuid)
          + "&show=" + enc(show)
          + "&claim_uuid=" + enc(claimUuid)
          + "&element_uuid=" + enc(elementUuid)
          + "&saved=" + enc(trashed ? "fact_archived" : "fact_restored"));
        return;
      }

      if ("refresh_report".equals(action)) {
        factsStore.refreshMatterReport(tenantUuid, caseUuid, actor);
        response.sendRedirect(ctx + "/facts.jsp?case_uuid=" + enc(caseUuid)
          + "&show=" + enc(show)
          + "&claim_uuid=" + enc(claimUuid)
          + "&element_uuid=" + enc(elementUuid)
          + "&fact_uuid=" + enc(factUuid)
          + "&saved=report_refreshed");
        return;
      }
    } catch (Exception ex) {
      logWarn(application, "Facts action failed (" + action + "): " + shortErr(ex), ex);
      error = "Unable to save: " + safe(ex.getMessage());
      try {
        logs.logError(
          "facts.action_failed",
          tenantUuid,
          userUuid,
          caseUuid,
          "",
          Map.of(
            "action", safe(action),
            "message", safe(ex.getMessage())
          )
        );
      } catch (Exception ignored) {}
    }
  }

  String saved = safe(request.getParameter("saved")).trim();
  if ("claim_created".equals(saved)) message = "Claim added and report regenerated.";
  if ("claim_saved".equals(saved)) message = "Claim updated and report regenerated.";
  if ("claim_archived".equals(saved)) message = "Claim archived with descendant entries.";
  if ("claim_restored".equals(saved)) message = "Claim restored with descendant entries.";
  if ("element_created".equals(saved)) message = "Element added and report regenerated.";
  if ("element_saved".equals(saved)) message = "Element updated and report regenerated.";
  if ("element_archived".equals(saved)) message = "Element archived with descendant facts.";
  if ("element_restored".equals(saved)) message = "Element restored with descendant facts.";
  if ("fact_created".equals(saved)) message = "Fact added and report regenerated.";
  if ("fact_saved".equals(saved)) message = "Fact updated and report regenerated.";
  if ("fact_archived".equals(saved)) message = "Fact archived and report regenerated.";
  if ("fact_restored".equals(saved)) message = "Fact restored and report regenerated.";
  if ("report_refreshed".equals(saved)) message = "Landscape facts report regenerated.";

  List<matter_facts.ClaimRec> allClaims = new ArrayList<matter_facts.ClaimRec>();
  List<matter_facts.ElementRec> allElements = new ArrayList<matter_facts.ElementRec>();
  List<matter_facts.FactRec> allFacts = new ArrayList<matter_facts.FactRec>();
  matter_facts.ReportRec reportRefs = new matter_facts.ReportRec();

  if (!caseUuid.isBlank()) {
    try {
      factsStore.ensure(tenantUuid, caseUuid);
      allClaims = factsStore.listClaims(tenantUuid, caseUuid);
      allElements = factsStore.listElements(tenantUuid, caseUuid, "");
      allFacts = factsStore.listFacts(tenantUuid, caseUuid, "", "");
      reportRefs = factsStore.reportRefs(tenantUuid, caseUuid);
    } catch (Exception ex) {
      logWarn(application, "Unable to load facts hierarchy: " + shortErr(ex), ex);
      error = error == null ? "Unable to load facts hierarchy." : error;
    }
  }

  List<matter_facts.ClaimRec> claims = new ArrayList<matter_facts.ClaimRec>();
  List<matter_facts.ElementRec> elements = new ArrayList<matter_facts.ElementRec>();
  List<matter_facts.FactRec> facts = new ArrayList<matter_facts.FactRec>();

  for (int i = 0; i < allClaims.size(); i++) {
    matter_facts.ClaimRec c = allClaims.get(i);
    if (c == null) continue;
    if (!includeTrashed && c.trashed) continue;
    claims.add(c);
  }
  for (int i = 0; i < allElements.size(); i++) {
    matter_facts.ElementRec e = allElements.get(i);
    if (e == null) continue;
    if (!includeTrashed && e.trashed) continue;
    elements.add(e);
  }
  for (int i = 0; i < allFacts.size(); i++) {
    matter_facts.FactRec f = allFacts.get(i);
    if (f == null) continue;
    if (!includeTrashed && f.trashed) continue;
    facts.add(f);
  }

  String selectedClaimUuid = safe(request.getParameter("claim_uuid")).trim();
  String selectedElementUuid = safe(request.getParameter("element_uuid")).trim();
  String selectedFactUuid = safe(request.getParameter("fact_uuid")).trim();

  matter_facts.FactRec selectedFact = null;
  for (int i = 0; i < facts.size(); i++) {
    matter_facts.FactRec f = facts.get(i);
    if (f == null) continue;
    if (safe(f.uuid).equals(selectedFactUuid)) { selectedFact = f; break; }
  }
  if (selectedFact != null) {
    if (selectedClaimUuid.isBlank()) selectedClaimUuid = safe(selectedFact.claimUuid);
    if (selectedElementUuid.isBlank()) selectedElementUuid = safe(selectedFact.elementUuid);
  }

  matter_facts.ElementRec selectedElement = null;
  for (int i = 0; i < elements.size(); i++) {
    matter_facts.ElementRec e = elements.get(i);
    if (e == null) continue;
    if (safe(e.uuid).equals(selectedElementUuid)) { selectedElement = e; break; }
  }
  if (selectedElement != null && selectedClaimUuid.isBlank()) {
    selectedClaimUuid = safe(selectedElement.claimUuid);
  }

  if (selectedClaimUuid.isBlank() && !claims.isEmpty()) selectedClaimUuid = safe(claims.get(0).uuid);

  if (selectedElementUuid.isBlank()) {
    for (int i = 0; i < elements.size(); i++) {
      matter_facts.ElementRec e = elements.get(i);
      if (e == null) continue;
      if (safe(e.claimUuid).equals(selectedClaimUuid)) {
        selectedElementUuid = safe(e.uuid);
        selectedElement = e;
        break;
      }
    }
  }

  if (selectedFactUuid.isBlank()) {
    for (int i = 0; i < facts.size(); i++) {
      matter_facts.FactRec f = facts.get(i);
      if (f == null) continue;
      if (safe(f.elementUuid).equals(selectedElementUuid)) {
        selectedFactUuid = safe(f.uuid);
        selectedFact = f;
        break;
      }
    }
  }

  matter_facts.ClaimRec selectedClaim = null;
  for (int i = 0; i < claims.size(); i++) {
    matter_facts.ClaimRec c = claims.get(i);
    if (c == null) continue;
    if (safe(c.uuid).equals(selectedClaimUuid)) { selectedClaim = c; break; }
  }
  if (selectedElement == null && !selectedElementUuid.isBlank()) {
    for (int i = 0; i < elements.size(); i++) {
      matter_facts.ElementRec e = elements.get(i);
      if (e == null) continue;
      if (safe(e.uuid).equals(selectedElementUuid)) { selectedElement = e; break; }
    }
  }
  if (selectedFact == null && !selectedFactUuid.isBlank()) {
    for (int i = 0; i < facts.size(); i++) {
      matter_facts.FactRec f = facts.get(i);
      if (f == null) continue;
      if (safe(f.uuid).equals(selectedFactUuid)) { selectedFact = f; break; }
    }
  }

  LinkedHashMap<String, List<matter_facts.ElementRec>> elementsByClaim = new LinkedHashMap<String, List<matter_facts.ElementRec>>();
  for (int i = 0; i < elements.size(); i++) {
    matter_facts.ElementRec e = elements.get(i);
    if (e == null) continue;
    String key = safe(e.claimUuid).trim();
    if (!elementsByClaim.containsKey(key)) elementsByClaim.put(key, new ArrayList<matter_facts.ElementRec>());
    elementsByClaim.get(key).add(e);
  }
  for (Map.Entry<String, List<matter_facts.ElementRec>> e : elementsByClaim.entrySet()) {
    List<matter_facts.ElementRec> rows = e.getValue();
    rows.sort((a, b) -> {
      int as = a == null ? Integer.MAX_VALUE : a.sortOrder;
      int bs = b == null ? Integer.MAX_VALUE : b.sortOrder;
      if (as != bs) return Integer.compare(as, bs);
      return safe(a == null ? "" : a.title).compareToIgnoreCase(safe(b == null ? "" : b.title));
    });
  }

  LinkedHashMap<String, List<matter_facts.FactRec>> factsByElement = new LinkedHashMap<String, List<matter_facts.FactRec>>();
  for (int i = 0; i < facts.size(); i++) {
    matter_facts.FactRec f = facts.get(i);
    if (f == null) continue;
    String key = safe(f.elementUuid).trim();
    if (!factsByElement.containsKey(key)) factsByElement.put(key, new ArrayList<matter_facts.FactRec>());
    factsByElement.get(key).add(f);
  }
  for (Map.Entry<String, List<matter_facts.FactRec>> e : factsByElement.entrySet()) {
    List<matter_facts.FactRec> rows = e.getValue();
    rows.sort((a, b) -> {
      int as = a == null ? Integer.MAX_VALUE : a.sortOrder;
      int bs = b == null ? Integer.MAX_VALUE : b.sortOrder;
      if (as != bs) return Integer.compare(as, bs);
      return safe(a == null ? "" : a.summary).compareToIgnoreCase(safe(b == null ? "" : b.summary));
    });
  }

  List<documents.DocumentRec> docs = new ArrayList<documents.DocumentRec>();
  List<document_parts.PartRec> allParts = new ArrayList<document_parts.PartRec>();
  List<part_versions.VersionRec> allVersions = new ArrayList<part_versions.VersionRec>();
  HashMap<String, String> docLabelByUuid = new HashMap<String, String>();
  HashMap<String, String> partDocUuid = new HashMap<String, String>();
  HashMap<String, String> partLabelByUuid = new HashMap<String, String>();
  HashMap<String, String> versionPartUuid = new HashMap<String, String>();
  HashMap<String, String> versionLabelByUuid = new HashMap<String, String>();
  HashMap<String, String> versionDocUuid = new HashMap<String, String>();

  if (!caseUuid.isBlank()) {
    try {
      docs = docStore.listAll(tenantUuid, caseUuid);
      for (int i = 0; i < docs.size(); i++) {
        documents.DocumentRec d = docs.get(i);
        if (d == null || d.trashed) continue;
        docLabelByUuid.put(safe(d.uuid), safe(d.title));

        List<document_parts.PartRec> parts = partStore.listAll(tenantUuid, caseUuid, d.uuid);
        for (int pi = 0; pi < parts.size(); pi++) {
          document_parts.PartRec p = parts.get(pi);
          if (p == null || p.trashed) continue;
          allParts.add(p);
          partDocUuid.put(safe(p.uuid), safe(d.uuid));
          partLabelByUuid.put(safe(p.uuid), safe(p.label));

          List<part_versions.VersionRec> versions = versionStore.listAll(tenantUuid, caseUuid, d.uuid, p.uuid);
          for (int vi = 0; vi < versions.size(); vi++) {
            part_versions.VersionRec v = versions.get(vi);
            if (v == null) continue;
            allVersions.add(v);
            versionPartUuid.put(safe(v.uuid), safe(p.uuid));
            versionLabelByUuid.put(safe(v.uuid), safe(v.versionLabel));
            versionDocUuid.put(safe(v.uuid), safe(d.uuid));
          }
        }
      }
    } catch (Exception ex) {
      logWarn(application, "Unable to load document reference data: " + shortErr(ex), ex);
      error = error == null ? "Unable to load document references." : error;
    }
  }

  int activeClaimCount = 0;
  int activeElementCount = 0;
  int activeFactCount = 0;
  for (int i = 0; i < allClaims.size(); i++) if (allClaims.get(i) != null && !allClaims.get(i).trashed) activeClaimCount++;
  for (int i = 0; i < allElements.size(); i++) if (allElements.get(i) != null && !allElements.get(i).trashed) activeElementCount++;
  for (int i = 0; i < allFacts.size(); i++) if (allFacts.get(i) != null && !allFacts.get(i).trashed) activeFactCount++;
%>

<jsp:include page="header.jsp" />

<style>
  .facts-shell {
    display: grid;
    grid-template-columns: minmax(0, 1.45fr) minmax(340px, 520px);
    gap: 14px;
  }
  .facts-tree-pane {
    position: sticky;
    top: 74px;
    max-height: calc(100vh - 120px);
    overflow: auto;
  }
  .facts-tree-header {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 10px;
    flex-wrap: wrap;
  }
  .facts-tree-stats {
    display: flex;
    gap: 8px;
    flex-wrap: wrap;
  }
  .facts-chip {
    border: 1px solid var(--border);
    border-radius: 999px;
    padding: 2px 8px;
    font-size: .76rem;
    background: var(--surface-2);
    color: var(--muted);
  }
  .facts-tree-root,
  .facts-tree-root ul {
    list-style: none;
    margin: 0;
    padding: 0;
  }
  .facts-tree-root ul {
    padding-left: 16px;
    border-left: 1px dashed var(--border);
    margin-left: 8px;
  }
  .facts-tree-item {
    margin: 6px 0;
  }
  .facts-node {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
    padding: 6px 8px;
    border-radius: 8px;
    border: 1px solid transparent;
    background: transparent;
  }
  .facts-node.is-active {
    background: var(--accent-soft);
    border-color: var(--accent);
  }
  .facts-node a {
    text-decoration: none;
    color: var(--text);
    display: block;
    flex: 1 1 auto;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .facts-node .tag {
    font-size: 0.72rem;
    color: var(--muted);
    border: 1px solid var(--border);
    border-radius: 999px;
    padding: 2px 6px;
    flex: 0 0 auto;
  }
  .facts-node .tag.archived {
    color: var(--danger);
    border-color: rgba(220, 38, 38, 0.35);
    background: rgba(220, 38, 38, 0.10);
  }
  .facts-panels {
    display: flex;
    flex-direction: column;
    gap: 10px;
  }
  .facts-pane {
    overflow: hidden;
    padding: 0;
  }
  .facts-pane > summary {
    list-style: none;
    cursor: pointer;
    padding: 12px 14px;
    border-bottom: 1px solid var(--border);
    background: linear-gradient(180deg, var(--surface-2), #f8fafc);
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
    font-weight: 650;
  }
  .facts-pane > summary::-webkit-details-marker {
    display: none;
  }
  .facts-pane[open] > summary {
    background: linear-gradient(180deg, var(--accent-soft), #f3f8ff);
  }
  .facts-pane .facts-pane-body {
    padding: 12px 14px 14px 14px;
  }
  .facts-pane-label {
    color: var(--muted);
    font-size: .82rem;
    font-weight: 500;
  }
  .facts-inline {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 10px;
  }
  .facts-counts {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
    gap: 10px;
    margin-top: 10px;
  }
  .facts-count-box {
    border: 1px solid var(--border);
    border-radius: 10px;
    padding: 10px;
    background: var(--surface-2);
  }
  .facts-count-box .k {
    color: var(--muted);
    font-size: .82rem;
  }
  .facts-count-box .v {
    font-weight: 700;
    font-size: 1.2rem;
  }
  .facts-subheading {
    margin: 12px 0 8px 0;
  }
  @media (max-width: 980px) {
    .facts-shell {
      grid-template-columns: 1fr;
    }
    .facts-tree-pane {
      position: static;
      max-height: none;
    }
    .facts-panels {
      gap: 12px;
    }
    .facts-inline {
      grid-template-columns: 1fr;
    }
  }
</style>

<section class="card">
  <h1 class="u-m-0">Facts Case Plan</h1>
  <div class="meta u-mt-6">
    Visual case-plan manager using a side-tree structure: <strong>Claims</strong> -> <strong>Elements</strong> -> <strong>Facts</strong>.
    Updates automatically regenerate a landscape PDF report and store it in the matter's documents.
  </div>
</section>

<% if (message != null) { %>
  <div class="alert alert-ok u-mt-12"><%= esc(message) %></div>
<% } %>
<% if (error != null) { %>
  <div class="alert alert-error u-mt-12"><%= esc(error) %></div>
<% } %>

<section class="facts-shell section-gap-12">
  <section class="card facts-tree-pane">
    <div class="facts-tree-header">
      <div>
        <h2 class="u-m-0">Case Plan Tree</h2>
        <div class="meta">Primary workspace for Claims, Elements, and Facts.</div>
      </div>
      <div class="facts-tree-stats">
        <span class="facts-chip">Claims: <strong><%= claims.size() %></strong></span>
        <span class="facts-chip">Elements: <strong><%= elements.size() %></strong></span>
        <span class="facts-chip">Facts: <strong><%= facts.size() %></strong></span>
      </div>
    </div>

    <% if (claims.isEmpty()) { %>
      <div class="muted u-mt-10">No claims yet. Create one in the panel to the right.</div>
    <% } else { %>
      <ul class="facts-tree-root u-mt-10">
        <% for (int ci = 0; ci < claims.size(); ci++) {
             matter_facts.ClaimRec c = claims.get(ci);
             if (c == null) continue;
             String cid = safe(c.uuid);
             List<matter_facts.ElementRec> claimElements = elementsByClaim.containsKey(cid) ? elementsByClaim.get(cid) : new ArrayList<matter_facts.ElementRec>();
        %>
          <li class="facts-tree-item">
            <div class="facts-node <%= isSelected(cid, selectedClaimUuid) ? "is-active" : "" %>">
              <a href="<%= ctx %>/facts.jsp?case_uuid=<%= enc(caseUuid) %>&show=<%= enc(show) %>&claim_uuid=<%= enc(cid) %>">
                C<%= (ci + 1) %> - <%= esc(safe(c.title)) %>
              </a>
              <% if (c.trashed) { %><span class="tag archived">Archived</span><% } %>
            </div>

            <ul>
              <% if (claimElements.isEmpty()) { %>
                <li class="facts-tree-item"><div class="facts-node"><span class="muted">No elements</span></div></li>
              <% } else {
                   for (int ei = 0; ei < claimElements.size(); ei++) {
                     matter_facts.ElementRec e = claimElements.get(ei);
                     if (e == null) continue;
                     String eid = safe(e.uuid);
                     List<matter_facts.FactRec> elementFacts = factsByElement.containsKey(eid) ? factsByElement.get(eid) : new ArrayList<matter_facts.FactRec>();
              %>
                <li class="facts-tree-item">
                  <div class="facts-node <%= isSelected(eid, selectedElementUuid) ? "is-active" : "" %>">
                    <a href="<%= ctx %>/facts.jsp?case_uuid=<%= enc(caseUuid) %>&show=<%= enc(show) %>&claim_uuid=<%= enc(cid) %>&element_uuid=<%= enc(eid) %>">
                      E<%= (ci + 1) %>.<%= (ei + 1) %> - <%= esc(safe(e.title)) %>
                    </a>
                    <% if (e.trashed) { %><span class="tag archived">Archived</span><% } %>
                  </div>
                  <ul>
                    <% if (elementFacts.isEmpty()) { %>
                      <li class="facts-tree-item"><div class="facts-node"><span class="muted">No facts</span></div></li>
                    <% } else {
                         for (int fi = 0; fi < elementFacts.size(); fi++) {
                           matter_facts.FactRec f = elementFacts.get(fi);
                           if (f == null) continue;
                           String fid = safe(f.uuid);
                    %>
                      <li class="facts-tree-item">
                        <div class="facts-node <%= isSelected(fid, selectedFactUuid) ? "is-active" : "" %>">
                          <a href="<%= ctx %>/facts.jsp?case_uuid=<%= enc(caseUuid) %>&show=<%= enc(show) %>&claim_uuid=<%= enc(cid) %>&element_uuid=<%= enc(eid) %>&fact_uuid=<%= enc(fid) %>">
                            F<%= (ci + 1) %>.<%= (ei + 1) %>.<%= (fi + 1) %> - <%= esc(safe(f.summary)) %>
                          </a>
                          <% if (f.trashed) { %><span class="tag archived">Archived</span><% } %>
                        </div>
                      </li>
                    <% } } %>
                  </ul>
                </li>
              <% } } %>
            </ul>
          </li>
        <% } %>
      </ul>
    <% } %>
  </section>

  <section class="facts-panels">
    <details class="card facts-pane" open>
      <summary>
        <span>Workspace Controls</span>
        <span class="facts-pane-label">Matter, view filters, and report metadata</span>
      </summary>
      <div class="facts-pane-body">
        <form method="get" action="<%= ctx %>/facts.jsp" class="form">
          <div class="grid grid-3">
            <label>
              <span>Matter</span>
              <select name="case_uuid" onchange="this.form.submit()">
                <% for (int i = 0; i < activeCases.size(); i++) {
                     matters.MatterRec c = activeCases.get(i);
                     if (c == null) continue;
                     String id = safe(c.uuid);
                %>
                  <option value="<%= esc(id) %>" <%= isSelected(id, caseUuid) ? "selected" : "" %>><%= esc(safe(c.label)) %></option>
                <% } %>
              </select>
            </label>
            <label>
              <span>View</span>
              <select name="show" onchange="this.form.submit()">
                <option value="active" <%= "active".equals(show) ? "selected" : "" %>>Active Only</option>
                <option value="all" <%= "all".equals(show) ? "selected" : "" %>>Active + Archived</option>
              </select>
            </label>
            <label>
              <span>&nbsp;</span>
              <button class="btn" type="submit">Apply</button>
            </label>
          </div>
          <input type="hidden" name="claim_uuid" value="<%= esc(selectedClaimUuid) %>" />
          <input type="hidden" name="element_uuid" value="<%= esc(selectedElementUuid) %>" />
          <input type="hidden" name="fact_uuid" value="<%= esc(selectedFactUuid) %>" />
        </form>

        <div class="facts-counts">
          <div class="facts-count-box">
            <div class="k">Claims</div>
            <div class="v"><%= claims.size() %></div>
            <div class="meta">Active: <%= activeClaimCount %> / Total: <%= allClaims.size() %></div>
          </div>
          <div class="facts-count-box">
            <div class="k">Elements</div>
            <div class="v"><%= elements.size() %></div>
            <div class="meta">Active: <%= activeElementCount %> / Total: <%= allElements.size() %></div>
          </div>
          <div class="facts-count-box">
            <div class="k">Facts</div>
            <div class="v"><%= facts.size() %></div>
            <div class="meta">Active: <%= activeFactCount %> / Total: <%= allFacts.size() %></div>
          </div>
        </div>

        <div class="meta u-mt-10">
          Report Document: <code><%= esc(linkedLabel(reportRefs.reportDocumentUuid, docLabelByUuid, "document")) %></code>
          |
          Report Part: <code><%= esc(linkedLabel(reportRefs.reportPartUuid, partLabelByUuid, "part")) %></code>
          |
          Last Version: <code><%= esc(linkedLabel(reportRefs.lastReportVersionUuid, versionLabelByUuid, "version")) %></code>
          |
          Generated: <code><%= esc(safe(reportRefs.reportGeneratedAt)) %></code>
        </div>

        <form method="post" action="<%= ctx %>/facts.jsp" class="u-mt-10">
          <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
          <input type="hidden" name="action" value="refresh_report" />
          <input type="hidden" name="case_uuid" value="<%= esc(caseUuid) %>" />
          <input type="hidden" name="show" value="<%= esc(show) %>" />
          <input type="hidden" name="claim_uuid" value="<%= esc(selectedClaimUuid) %>" />
          <input type="hidden" name="element_uuid" value="<%= esc(selectedElementUuid) %>" />
          <input type="hidden" name="fact_uuid" value="<%= esc(selectedFactUuid) %>" />
          <button class="btn" type="submit">Regenerate Landscape PDF Report</button>
        </form>
      </div>
    </details>

    <details class="card facts-pane">
      <summary>
        <span>Add Claim</span>
        <span class="facts-pane-label">Create a top-level claim</span>
      </summary>
      <div class="facts-pane-body">
        <form method="post" class="form" action="<%= ctx %>/facts.jsp">
          <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
          <input type="hidden" name="action" value="create_claim" />
          <input type="hidden" name="case_uuid" value="<%= esc(caseUuid) %>" />
          <input type="hidden" name="show" value="<%= esc(show) %>" />
          <label><span>Claim Title</span><input type="text" name="claim_title" required /></label>
          <div class="facts-inline">
            <label><span>Sort Order</span><input type="number" name="claim_sort_order" value="10" min="1" /></label>
            <label><span>&nbsp;</span><button class="btn" type="submit">Add Claim</button></label>
          </div>
          <label><span>Summary</span><textarea name="claim_summary" rows="2" placeholder="Describe the legal claim at a high level."></textarea></label>
        </form>
      </div>
    </details>

    <% if (selectedClaim != null) { %>
      <details class="card facts-pane" open>
        <summary>
          <span>Selected Claim</span>
          <span class="facts-pane-label"><%= esc(safe(selectedClaim.title)) %></span>
        </summary>
        <div class="facts-pane-body">
          <form method="post" class="form" action="<%= ctx %>/facts.jsp">
            <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
            <input type="hidden" name="action" value="save_claim" />
            <input type="hidden" name="case_uuid" value="<%= esc(caseUuid) %>" />
            <input type="hidden" name="show" value="<%= esc(show) %>" />
            <input type="hidden" name="claim_uuid" value="<%= esc(safe(selectedClaim.uuid)) %>" />
            <label><span>Claim Title</span><input type="text" name="claim_title" value="<%= esc(safe(selectedClaim.title)) %>" required /></label>
            <div class="facts-inline">
              <label><span>Sort Order</span><input type="number" name="claim_sort_order" value="<%= selectedClaim.sortOrder %>" min="1" /></label>
              <label><span>Status</span><input type="text" value="<%= selectedClaim.trashed ? "Archived" : "Active" %>" disabled /></label>
            </div>
            <label><span>Summary</span><textarea name="claim_summary" rows="3"><%= esc(safe(selectedClaim.summary)) %></textarea></label>
            <div class="entity-action-bar">
              <button class="btn" type="submit">Save Claim</button>
            </div>
          </form>
          <form method="post" action="<%= ctx %>/facts.jsp" class="u-mt-8">
            <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
            <input type="hidden" name="action" value="<%= selectedClaim.trashed ? "restore_claim" : "archive_claim" %>" />
            <input type="hidden" name="case_uuid" value="<%= esc(caseUuid) %>" />
            <input type="hidden" name="show" value="<%= esc(show) %>" />
            <input type="hidden" name="claim_uuid" value="<%= esc(safe(selectedClaim.uuid)) %>" />
            <button class="btn btn-ghost" type="submit" onclick="return confirm('<%= selectedClaim.trashed ? "Restore" : "Archive" %> this claim and all descendant elements/facts?');">
              <%= selectedClaim.trashed ? "Restore Claim + Descendants" : "Archive Claim + Descendants" %>
            </button>
          </form>
        </div>
      </details>
    <% } %>

    <details class="card facts-pane">
      <summary>
        <span>Add Element</span>
        <span class="facts-pane-label">Create a child element for a claim</span>
      </summary>
      <div class="facts-pane-body">
        <form method="post" class="form" action="<%= ctx %>/facts.jsp">
          <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
          <input type="hidden" name="action" value="create_element" />
          <input type="hidden" name="case_uuid" value="<%= esc(caseUuid) %>" />
          <input type="hidden" name="show" value="<%= esc(show) %>" />
          <label>
            <span>Parent Claim</span>
            <select name="claim_uuid" required>
              <option value=""></option>
              <% for (int i = 0; i < allClaims.size(); i++) {
                   matter_facts.ClaimRec c = allClaims.get(i);
                   if (c == null || c.trashed) continue;
              %>
                <option value="<%= esc(safe(c.uuid)) %>" <%= isSelected(safe(c.uuid), selectedClaimUuid) ? "selected" : "" %>><%= esc(safe(c.title)) %></option>
              <% } %>
            </select>
          </label>
          <label><span>Element Title</span><input type="text" name="element_title" required /></label>
          <div class="facts-inline">
            <label><span>Sort Order</span><input type="number" name="element_sort_order" value="10" min="1" /></label>
            <label><span>&nbsp;</span><button class="btn" type="submit">Add Element</button></label>
          </div>
          <label><span>Notes</span><textarea name="element_notes" rows="2" placeholder="List required legal elements/proofs."></textarea></label>
        </form>
      </div>
    </details>

    <% if (selectedElement != null) { %>
      <details class="card facts-pane" open>
        <summary>
          <span>Selected Element</span>
          <span class="facts-pane-label"><%= esc(safe(selectedElement.title)) %></span>
        </summary>
        <div class="facts-pane-body">
          <form method="post" class="form" action="<%= ctx %>/facts.jsp">
            <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
            <input type="hidden" name="action" value="save_element" />
            <input type="hidden" name="case_uuid" value="<%= esc(caseUuid) %>" />
            <input type="hidden" name="show" value="<%= esc(show) %>" />
            <input type="hidden" name="element_uuid" value="<%= esc(safe(selectedElement.uuid)) %>" />
            <label>
              <span>Parent Claim</span>
              <select name="claim_uuid" required>
                <% for (int i = 0; i < allClaims.size(); i++) {
                     matter_facts.ClaimRec c = allClaims.get(i);
                     if (c == null || c.trashed) continue;
                %>
                  <option value="<%= esc(safe(c.uuid)) %>" <%= isSelected(safe(c.uuid), safe(selectedElement.claimUuid)) ? "selected" : "" %>><%= esc(safe(c.title)) %></option>
                <% } %>
              </select>
            </label>
            <label><span>Element Title</span><input type="text" name="element_title" value="<%= esc(safe(selectedElement.title)) %>" required /></label>
            <div class="facts-inline">
              <label><span>Sort Order</span><input type="number" name="element_sort_order" value="<%= selectedElement.sortOrder %>" min="1" /></label>
              <label><span>Status</span><input type="text" value="<%= selectedElement.trashed ? "Archived" : "Active" %>" disabled /></label>
            </div>
            <label><span>Notes</span><textarea name="element_notes" rows="3"><%= esc(safe(selectedElement.notes)) %></textarea></label>
            <button class="btn" type="submit">Save Element</button>
          </form>
          <form method="post" action="<%= ctx %>/facts.jsp" class="u-mt-8">
            <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
            <input type="hidden" name="action" value="<%= selectedElement.trashed ? "restore_element" : "archive_element" %>" />
            <input type="hidden" name="case_uuid" value="<%= esc(caseUuid) %>" />
            <input type="hidden" name="show" value="<%= esc(show) %>" />
            <input type="hidden" name="claim_uuid" value="<%= esc(selectedClaimUuid) %>" />
            <input type="hidden" name="element_uuid" value="<%= esc(safe(selectedElement.uuid)) %>" />
            <button class="btn btn-ghost" type="submit" onclick="return confirm('<%= selectedElement.trashed ? "Restore" : "Archive" %> this element and all descendant facts?');">
              <%= selectedElement.trashed ? "Restore Element + Facts" : "Archive Element + Facts" %>
            </button>
          </form>
        </div>
      </details>
    <% } %>

    <details class="card facts-pane">
      <summary>
        <span>Add Fact</span>
        <span class="facts-pane-label">Create a fact and link it to evidence</span>
      </summary>
      <div class="facts-pane-body">
        <div class="meta">Facts may link to a specific document, part, version, and page.</div>
        <form method="post" class="form" action="<%= ctx %>/facts.jsp">
          <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
          <input type="hidden" name="action" value="create_fact" />
          <input type="hidden" name="case_uuid" value="<%= esc(caseUuid) %>" />
          <input type="hidden" name="show" value="<%= esc(show) %>" />

          <div class="grid grid-3">
            <label>
              <span>Claim</span>
              <select name="claim_uuid" required>
                <% for (int i = 0; i < allClaims.size(); i++) {
                     matter_facts.ClaimRec c = allClaims.get(i);
                     if (c == null || c.trashed) continue;
                %>
                  <option value="<%= esc(safe(c.uuid)) %>" <%= isSelected(safe(c.uuid), selectedClaimUuid) ? "selected" : "" %>><%= esc(safe(c.title)) %></option>
                <% } %>
              </select>
            </label>
            <label>
              <span>Element</span>
              <select name="element_uuid" required>
                <% for (int i = 0; i < allElements.size(); i++) {
                     matter_facts.ElementRec e = allElements.get(i);
                     if (e == null || e.trashed) continue;
                %>
                  <option value="<%= esc(safe(e.uuid)) %>" <%= isSelected(safe(e.uuid), selectedElementUuid) ? "selected" : "" %>><%= esc(safe(e.title)) %></option>
                <% } %>
              </select>
            </label>
            <label>
              <span>Sort Order</span>
              <input type="number" name="fact_sort_order" value="10" min="1" />
            </label>
          </div>

          <label><span>Fact Summary</span><input type="text" name="fact_summary" required /></label>
          <label><span>Fact Detail</span><textarea name="fact_detail" rows="3"></textarea></label>
          <label><span>Internal Notes (users only)</span><textarea name="fact_internal_notes" rows="2" placeholder="Not intended for non-user audiences."></textarea></label>

          <div class="grid grid-3">
            <label>
              <span>Status</span>
              <select name="fact_status">
                <option value="unverified">Unverified</option>
                <option value="corroborated">Corroborated</option>
                <option value="disputed">Disputed</option>
                <option value="admitted">Admitted</option>
                <option value="proven">Proven</option>
              </select>
            </label>
            <label>
              <span>Strength</span>
              <select name="fact_strength">
                <option value="low">Low</option>
                <option value="medium" selected>Medium</option>
                <option value="high">High</option>
                <option value="critical">Critical</option>
              </select>
            </label>
            <label>
              <span>Page Number</span>
              <input type="number" name="page_number" min="0" value="0" />
            </label>
          </div>

          <h4 class="facts-subheading">Document Association</h4>
          <div class="grid grid-3">
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
                <% for (int i = 0; i < allParts.size(); i++) {
                     document_parts.PartRec p = allParts.get(i);
                     if (p == null) continue;
                     String docId = safe(partDocUuid.get(safe(p.uuid)));
                %>
                  <option value="<%= esc(safe(p.uuid)) %>"><%= esc(safe(docLabelByUuid.get(docId))) %> :: <%= esc(safe(p.label)) %></option>
                <% } %>
              </select>
            </label>
            <label>
              <span>Version</span>
              <select name="version_uuid">
                <option value=""></option>
                <% for (int i = 0; i < allVersions.size(); i++) {
                     part_versions.VersionRec v = allVersions.get(i);
                     if (v == null) continue;
                     String docId = safe(versionDocUuid.get(safe(v.uuid)));
                     String partId = safe(versionPartUuid.get(safe(v.uuid)));
                %>
                  <option value="<%= esc(safe(v.uuid)) %>"><%= esc(safe(docLabelByUuid.get(docId))) %> :: <%= esc(safe(partLabelByUuid.get(partId))) %> :: <%= esc(safe(v.versionLabel)) %></option>
                <% } %>
              </select>
            </label>
          </div>

          <button class="btn u-mt-10" type="submit">Add Fact</button>
        </form>
      </div>
    </details>

    <% if (selectedFact != null) { %>
      <details class="card facts-pane" open>
        <summary>
          <span>Selected Fact</span>
          <span class="facts-pane-label"><%= esc(safe(selectedFact.summary)) %></span>
        </summary>
        <div class="facts-pane-body">
          <form method="post" class="form" action="<%= ctx %>/facts.jsp">
            <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
            <input type="hidden" name="action" value="save_fact" />
            <input type="hidden" name="case_uuid" value="<%= esc(caseUuid) %>" />
            <input type="hidden" name="show" value="<%= esc(show) %>" />
            <input type="hidden" name="fact_uuid" value="<%= esc(safe(selectedFact.uuid)) %>" />

            <div class="grid grid-3">
              <label>
                <span>Claim</span>
                <select name="claim_uuid" required>
                  <% for (int i = 0; i < allClaims.size(); i++) {
                       matter_facts.ClaimRec c = allClaims.get(i);
                       if (c == null || c.trashed) continue;
                  %>
                    <option value="<%= esc(safe(c.uuid)) %>" <%= isSelected(safe(c.uuid), safe(selectedFact.claimUuid)) ? "selected" : "" %>><%= esc(safe(c.title)) %></option>
                  <% } %>
                </select>
              </label>
              <label>
                <span>Element</span>
                <select name="element_uuid" required>
                  <% for (int i = 0; i < allElements.size(); i++) {
                       matter_facts.ElementRec e = allElements.get(i);
                       if (e == null || e.trashed) continue;
                  %>
                    <option value="<%= esc(safe(e.uuid)) %>" <%= isSelected(safe(e.uuid), safe(selectedFact.elementUuid)) ? "selected" : "" %>><%= esc(safe(e.title)) %></option>
                  <% } %>
                </select>
              </label>
              <label><span>Sort Order</span><input type="number" name="fact_sort_order" min="1" value="<%= selectedFact.sortOrder %>" /></label>
            </div>

            <label><span>Fact Summary</span><input type="text" name="fact_summary" value="<%= esc(safe(selectedFact.summary)) %>" required /></label>
            <label><span>Fact Detail</span><textarea name="fact_detail" rows="3"><%= esc(safe(selectedFact.detail)) %></textarea></label>
            <label><span>Internal Notes (users only)</span><textarea name="fact_internal_notes" rows="2"><%= esc(safe(selectedFact.internalNotes)) %></textarea></label>

            <div class="grid grid-3">
              <label>
                <span>Status</span>
                <select name="fact_status">
                  <option value="unverified" <%= isSelected("unverified", safe(selectedFact.status)) ? "selected" : "" %>>Unverified</option>
                  <option value="corroborated" <%= isSelected("corroborated", safe(selectedFact.status)) ? "selected" : "" %>>Corroborated</option>
                  <option value="disputed" <%= isSelected("disputed", safe(selectedFact.status)) ? "selected" : "" %>>Disputed</option>
                  <option value="admitted" <%= isSelected("admitted", safe(selectedFact.status)) ? "selected" : "" %>>Admitted</option>
                  <option value="proven" <%= isSelected("proven", safe(selectedFact.status)) ? "selected" : "" %>>Proven</option>
                </select>
              </label>
              <label>
                <span>Strength</span>
                <select name="fact_strength">
                  <option value="low" <%= isSelected("low", safe(selectedFact.strength)) ? "selected" : "" %>>Low</option>
                  <option value="medium" <%= isSelected("medium", safe(selectedFact.strength)) ? "selected" : "" %>>Medium</option>
                  <option value="high" <%= isSelected("high", safe(selectedFact.strength)) ? "selected" : "" %>>High</option>
                  <option value="critical" <%= isSelected("critical", safe(selectedFact.strength)) ? "selected" : "" %>>Critical</option>
                </select>
              </label>
              <label><span>Page Number</span><input type="number" name="page_number" min="0" value="<%= selectedFact.pageNumber %>" /></label>
            </div>

            <h4 class="facts-subheading">Document Association</h4>
            <div class="grid grid-3">
              <label>
                <span>Document</span>
                <select name="document_uuid">
                  <option value=""></option>
                  <% for (int i = 0; i < docs.size(); i++) {
                       documents.DocumentRec d = docs.get(i);
                       if (d == null || d.trashed) continue;
                  %>
                    <option value="<%= esc(safe(d.uuid)) %>" <%= isSelected(safe(d.uuid), safe(selectedFact.documentUuid)) ? "selected" : "" %>><%= esc(safe(d.title)) %></option>
                  <% } %>
                </select>
              </label>
              <label>
                <span>Part</span>
                <select name="part_uuid">
                  <option value=""></option>
                  <% for (int i = 0; i < allParts.size(); i++) {
                       document_parts.PartRec p = allParts.get(i);
                       if (p == null) continue;
                       String docId = safe(partDocUuid.get(safe(p.uuid)));
                  %>
                    <option value="<%= esc(safe(p.uuid)) %>" <%= isSelected(safe(p.uuid), safe(selectedFact.partUuid)) ? "selected" : "" %>><%= esc(safe(docLabelByUuid.get(docId))) %> :: <%= esc(safe(p.label)) %></option>
                  <% } %>
                </select>
              </label>
              <label>
                <span>Version</span>
                <select name="version_uuid">
                  <option value=""></option>
                  <% for (int i = 0; i < allVersions.size(); i++) {
                       part_versions.VersionRec v = allVersions.get(i);
                       if (v == null) continue;
                       String docId = safe(versionDocUuid.get(safe(v.uuid)));
                       String partId = safe(versionPartUuid.get(safe(v.uuid)));
                  %>
                    <option value="<%= esc(safe(v.uuid)) %>" <%= isSelected(safe(v.uuid), safe(selectedFact.versionUuid)) ? "selected" : "" %>><%= esc(safe(docLabelByUuid.get(docId))) %> :: <%= esc(safe(partLabelByUuid.get(partId))) %> :: <%= esc(safe(v.versionLabel)) %></option>
                  <% } %>
                </select>
              </label>
            </div>

            <div class="entity-action-bar">
              <button class="btn" type="submit">Save Fact</button>
            </div>
          </form>
          <form method="post" action="<%= ctx %>/facts.jsp" class="u-mt-8">
            <input type="hidden" name="csrfToken" value="<%= esc(csrfToken) %>" />
            <input type="hidden" name="action" value="<%= selectedFact.trashed ? "restore_fact" : "archive_fact" %>" />
            <input type="hidden" name="case_uuid" value="<%= esc(caseUuid) %>" />
            <input type="hidden" name="show" value="<%= esc(show) %>" />
            <input type="hidden" name="claim_uuid" value="<%= esc(selectedClaimUuid) %>" />
            <input type="hidden" name="element_uuid" value="<%= esc(selectedElementUuid) %>" />
            <input type="hidden" name="fact_uuid" value="<%= esc(safe(selectedFact.uuid)) %>" />
            <button class="btn btn-ghost" type="submit" onclick="return confirm('<%= selectedFact.trashed ? "Restore" : "Archive" %> this fact?');">
              <%= selectedFact.trashed ? "Restore Fact" : "Archive Fact" %>
            </button>
          </form>
        </div>
      </details>
    <% } %>

    <details class="card facts-pane">
      <summary>
        <span>Reference Catalog</span>
        <span class="facts-pane-label">Document -> Part -> Version mapping</span>
      </summary>
      <div class="facts-pane-body">
        <div class="meta">Use this list to quickly choose valid document -> part -> version links for facts.</div>
        <div class="table-wrap table-wrap-tight">
          <table class="table">
            <thead>
              <tr>
                <th>Document</th>
                <th>Part</th>
                <th>Version</th>
              </tr>
            </thead>
            <tbody>
              <% if (allVersions.isEmpty()) { %>
                <tr><td colspan="3" class="muted">No versions found. Add document parts and versions first.</td></tr>
              <% } else {
                   for (int i = 0; i < allVersions.size(); i++) {
                     part_versions.VersionRec v = allVersions.get(i);
                     if (v == null) continue;
                     String vid = safe(v.uuid);
                     String pid = safe(versionPartUuid.get(vid));
                     String did = safe(versionDocUuid.get(vid));
              %>
                <tr>
                  <td><%= esc(linkedLabel(did, docLabelByUuid, "document")) %></td>
                  <td><%= esc(linkedLabel(pid, partLabelByUuid, "part")) %></td>
                  <td><%= esc(linkedLabel(vid, versionLabelByUuid, "version")) %></td>
                </tr>
              <% } } %>
            </tbody>
          </table>
        </div>
      </div>
    </details>
  </section>
</section>

<jsp:include page="footer.jsp" />
