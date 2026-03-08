<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ include file="security.jspf" %>
<% if (!require_login()) return; %>
<%!
private static final String S_TENANT_UUID = "tenant.uuid";
private static final String CSRF_SESSION_KEY = "CSRF_TOKEN";
private static String safe(String s){ return s == null ? "" : s; }
private static String esc(String s){ return safe(s).replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;"); }
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
String tenantUuid = safe((String)session.getAttribute(S_TENANT_UUID)).trim();
if (tenantUuid.isBlank()) { response.sendRedirect(ctx + "/tenant_login.jsp"); return; }
String csrfToken = csrfForRender(request);
%>
<jsp:include page="header.jsp" />

<section class="card">
  <h1 style="margin:0;">Search</h1>
  <div class="meta" style="margin-top:6px;">Queue asynchronous search jobs across document part versions with selectable operators and case sensitivity.</div>
</section>

<section class="card" style="margin-top:12px;">
  <form id="searchForm" class="form" onsubmit="return false;">
    <div class="grid grid-3">
      <div>
        <label>Search Type</label>
        <select id="searchType" name="search_type"></select>
      </div>
      <div>
        <label>Criteria Logic</label>
        <select id="criteriaLogic">
          <option value="or">OR (any criterion)</option>
          <option value="and">AND (all criteria)</option>
        </select>
      </div>
      <div>
        <label>Max Results</label>
        <input id="maxResults" type="number" min="1" max="500" value="200" />
      </div>
    </div>
    <div>
      <label>Criteria</label>
      <div id="criteriaRows" style="display:grid; gap:8px;"></div>
      <div style="margin-top:8px;">
        <button id="btnAddCriterion" class="btn btn-ghost" type="button">Add Criterion</button>
      </div>
    </div>
    <div class="grid grid-3">
      <div>
        <label><input id="includeMetadata" type="checkbox" checked /> Include Metadata</label>
      </div>
      <div>
        <label><input id="includeOcr" type="checkbox" checked /> Include OCR Text</label>
      </div>
      <div>
        <label><input id="caseSensitive" type="checkbox" /> Case Sensitive</label>
      </div>
    </div>
    <div style="display:flex; gap:8px; align-items:center; flex-wrap:wrap;">
      <button id="btnQueueSearch" class="btn" type="button">Queue Search Job</button>
      <span id="searchQueueStatus" class="meta" aria-live="polite"></span>
    </div>
  </form>
</section>

<section class="card" style="margin-top:12px;">
  <div style="display:flex; justify-content:space-between; align-items:center; gap:8px; flex-wrap:wrap;">
    <h2 style="margin:0;">Search Jobs</h2>
    <div class="meta" id="searchEngineStatus">Loading search types...</div>
  </div>
  <div class="table-wrap" style="margin-top:8px;">
    <table class="table">
      <thead>
      <tr>
        <th>Created</th>
        <th>Type</th>
        <th>Status</th>
        <th>Progress</th>
        <th>Results</th>
        <th>Query</th>
        <th></th>
      </tr>
      </thead>
      <tbody id="jobsBody">
      <tr><td colspan="7" class="meta">No jobs yet.</td></tr>
      </tbody>
    </table>
  </div>
</section>

<section class="card" style="margin-top:12px;">
  <h2 style="margin:0;">Job Results</h2>
  <div class="meta" id="jobDetailMeta" style="margin-top:6px;">Select a completed job to inspect matches.</div>
  <div class="table-wrap" style="margin-top:8px;">
    <table class="table">
      <thead>
      <tr>
        <th>Matter</th>
        <th>Document</th>
        <th>Part</th>
        <th>Version</th>
        <th>Matched In</th>
        <th>Snippet</th>
      </tr>
      </thead>
      <tbody id="resultsBody">
      <tr><td colspan="6" class="meta">No results loaded.</td></tr>
      </tbody>
    </table>
  </div>
</section>

<script>
(function(){
  var appContext = "<%= esc(ctx) %>";
  var csrfToken = "<%= esc(csrfToken) %>";
  var form = document.getElementById("searchForm");
  var searchTypeEl = document.getElementById("searchType");
  var criteriaLogicEl = document.getElementById("criteriaLogic");
  var criteriaRowsEl = document.getElementById("criteriaRows");
  var addCriterionBtn = document.getElementById("btnAddCriterion");
  var maxResultsEl = document.getElementById("maxResults");
  var includeMetadataEl = document.getElementById("includeMetadata");
  var includeOcrEl = document.getElementById("includeOcr");
  var caseSensitiveEl = document.getElementById("caseSensitive");
  var queueStatusEl = document.getElementById("searchQueueStatus");
  var jobsBody = document.getElementById("jobsBody");
  var resultsBody = document.getElementById("resultsBody");
  var jobDetailMeta = document.getElementById("jobDetailMeta");
  var searchEngineStatus = document.getElementById("searchEngineStatus");
  var queueBtn = document.getElementById("btnQueueSearch");

  var typeMap = {};
  var selectedJobId = "";
  var pollHandle = null;

  function esc(v){
    return String(v == null ? "" : v)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&#39;");
  }

  function fmtProgress(job){
    var p = Number(job && job.processed_count || 0);
    var t = Number(job && job.total_count || 0);
    if (t > 0) return p + " / " + t;
    return p > 0 ? String(p) : "-";
  }

  function setQueueStatus(message, isError){
    queueStatusEl.textContent = String(message || "");
    queueStatusEl.style.color = isError ? "#b91c1c" : "";
  }

  function operatorOptionsForType(){
    var key = String(searchTypeEl.value || "");
    var info = typeMap[key] || {};
    var ops = Array.isArray(info.operators) && info.operators.length ? info.operators : ["contains"];
    return ops;
  }

  function applyOperatorsToCriteriaRows(){
    var ops = operatorOptionsForType();
    var selects = criteriaRowsEl.querySelectorAll("select[data-criterion-op='1']");
    for (var i = 0; i < selects.length; i++) {
      var sel = selects[i];
      var prior = String(sel.value || "contains");
      sel.innerHTML = "";
      for (var j = 0; j < ops.length; j++) {
        var op = String(ops[j] || "");
        if (!op) continue;
        var opt = document.createElement("option");
        opt.value = op;
        opt.textContent = op;
        sel.appendChild(opt);
      }
      sel.value = ops.indexOf(prior) >= 0 ? prior : ops[0];
    }
  }

  function addCriterionRow(initial){
    var data = initial || {};
    var row = document.createElement("div");
    row.style.display = "grid";
    row.style.gap = "8px";
    row.style.gridTemplateColumns = "minmax(120px, 0.8fr) minmax(140px, 0.9fr) minmax(0, 2fr) auto";
    row.style.alignItems = "end";

    var scopeWrap = document.createElement("div");
    scopeWrap.innerHTML = "<label>Scope</label>";
    var scopeSel = document.createElement("select");
    scopeSel.setAttribute("data-criterion-scope", "1");
    scopeSel.innerHTML = "<option value=\"any\">Any</option><option value=\"metadata\">Metadata</option><option value=\"ocr\">OCR</option>";
    scopeSel.value = data.scope || "any";
    scopeWrap.appendChild(scopeSel);

    var opWrap = document.createElement("div");
    opWrap.innerHTML = "<label>Operator</label>";
    var opSel = document.createElement("select");
    opSel.setAttribute("data-criterion-op", "1");
    opWrap.appendChild(opSel);

    var queryWrap = document.createElement("div");
    queryWrap.innerHTML = "<label>Query</label>";
    var queryInput = document.createElement("input");
    queryInput.type = "text";
    queryInput.placeholder = "Type criterion text";
    queryInput.setAttribute("data-criterion-query", "1");
    queryInput.value = data.query || "";
    queryWrap.appendChild(queryInput);

    var removeWrap = document.createElement("div");
    var removeBtn = document.createElement("button");
    removeBtn.type = "button";
    removeBtn.className = "btn btn-ghost";
    removeBtn.textContent = "Remove";
    removeBtn.addEventListener("click", function(){
      if (criteriaRowsEl.children.length <= 1) {
        setQueueStatus("At least one criterion is required.", true);
        return;
      }
      criteriaRowsEl.removeChild(row);
    });
    removeWrap.appendChild(removeBtn);

    row.appendChild(scopeWrap);
    row.appendChild(opWrap);
    row.appendChild(queryWrap);
    row.appendChild(removeWrap);
    criteriaRowsEl.appendChild(row);
    applyOperatorsToCriteriaRows();
    opSel.value = data.operator || "contains";
  }

  function collectCriteria(){
    var rows = criteriaRowsEl.children;
    var out = [];
    for (var i = 0; i < rows.length; i++) {
      var row = rows[i];
      if (!row) continue;
      var scopeEl = row.querySelector("select[data-criterion-scope='1']");
      var opEl = row.querySelector("select[data-criterion-op='1']");
      var qEl = row.querySelector("input[data-criterion-query='1']");
      var q = String(qEl && qEl.value || "").trim();
      if (!q) continue;
      out.push({
        scope: String(scopeEl && scopeEl.value || "any"),
        operator: String(opEl && opEl.value || "contains"),
        query: q
      });
    }
    return out;
  }

  async function loadTypes(){
    try {
      var res = await fetch(appContext + "/search_jobs?action=types", { credentials: "same-origin" });
      var payload = await res.json();
      if (!payload || payload.ok === false) throw new Error(payload && payload.message ? payload.message : "Failed to load search types.");

      typeMap = {};
      searchTypeEl.innerHTML = "";
      var items = Array.isArray(payload.items) ? payload.items : [];
      for (var i = 0; i < items.length; i++) {
        var row = items[i] || {};
        var key = String(row.key || "").trim();
        if (!key) continue;
        typeMap[key] = row;
        var opt = document.createElement("option");
        opt.value = key;
        opt.textContent = row.label ? String(row.label) : key;
        searchTypeEl.appendChild(opt);
      }
      if (!searchTypeEl.value && searchTypeEl.options.length > 0) searchTypeEl.value = searchTypeEl.options[0].value;
      if (criteriaRowsEl.children.length === 0) addCriterionRow({ scope: "any", operator: "contains", query: "" });
      applyOperatorsToCriteriaRows();

      searchEngineStatus.textContent = payload.tesseract_available
        ? "Tesseract available."
        : "Tesseract unavailable on this server.";
    } catch (err) {
      searchEngineStatus.textContent = "Unable to load search types.";
      setQueueStatus(err && err.message ? err.message : "Type load failed.", true);
    }
  }

  async function queueJob(){
    var criteria = collectCriteria();
    if (!criteria.length) {
      setQueueStatus("At least one criterion with query text is required.", true);
      return;
    }
    if (!includeMetadataEl.checked && !includeOcrEl.checked) {
      setQueueStatus("Select metadata and/or OCR.", true);
      return;
    }
    queueBtn.disabled = true;
    setQueueStatus("Queueing search job...", false);

    try {
      var params = new URLSearchParams();
      params.set("action", "enqueue");
      params.set("search_type", searchTypeEl.value || "");
      params.set("logic", criteriaLogicEl.value || "or");
      params.set("query", criteria[0].query || "");
      params.set("operator", criteria[0].operator || "contains");
      params.set("criteria_json", JSON.stringify(criteria));
      params.set("case_sensitive", caseSensitiveEl.checked ? "1" : "0");
      params.set("include_metadata", includeMetadataEl.checked ? "1" : "0");
      params.set("include_ocr", includeOcrEl.checked ? "1" : "0");
      params.set("max_results", String(maxResultsEl.value || "200"));

      var res = await fetch(appContext + "/search_jobs", {
        method: "POST",
        credentials: "same-origin",
        headers: {
          "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8",
          "X-CSRF-Token": csrfToken
        },
        body: params.toString()
      });

      var payload = await res.json();
      if (!payload || payload.ok === false) {
        throw new Error(payload && payload.message ? payload.message : "Unable to queue search.");
      }

      selectedJobId = String(payload.job_id || "");
      setQueueStatus("Search job queued: " + selectedJobId, false);
      await loadJobs(true);
    } catch (err) {
      setQueueStatus(err && err.message ? err.message : "Queue failed.", true);
    } finally {
      queueBtn.disabled = false;
    }
  }

  async function loadJobs(loadSelected){
    try {
      var res = await fetch(appContext + "/search_jobs?action=list&limit=20", { credentials: "same-origin" });
      var payload = await res.json();
      if (!payload || payload.ok === false) throw new Error(payload && payload.message ? payload.message : "Unable to load jobs.");
      var rows = Array.isArray(payload.items) ? payload.items : [];

      if (!rows.length) {
        jobsBody.innerHTML = "<tr><td colspan=\"7\" class=\"meta\">No jobs yet.</td></tr>";
        if (!selectedJobId) {
          resultsBody.innerHTML = "<tr><td colspan=\"6\" class=\"meta\">No results loaded.</td></tr>";
          jobDetailMeta.textContent = "Select a completed job to inspect matches.";
        }
        return;
      }

      var html = "";
      var firstJobId = "";
      for (var i = 0; i < rows.length; i++) {
        var job = rows[i] || {};
        var jobId = String(job.job_id || "");
        if (!firstJobId && jobId) firstJobId = jobId;
        var status = String(job.status || "");
        var created = String(job.created_at || "").replace("T", " ").replace("Z", "Z");
        var selected = selectedJobId && selectedJobId === jobId;
        html += "<tr" + (selected ? " style=\"background:var(--surface-2);\"" : "") + ">"
          + "<td>" + esc(created) + "</td>"
          + "<td>" + esc(job.search_type || "") + "</td>"
          + "<td>" + esc(status) + "</td>"
          + "<td>" + esc(fmtProgress(job)) + "</td>"
          + "<td>" + esc(String(job.result_count || 0)) + "</td>"
          + "<td>" + esc(job.query || "") + "</td>"
          + "<td><button type=\"button\" class=\"btn btn-ghost\" data-job-id=\"" + esc(jobId) + "\">View</button></td>"
          + "</tr>";
      }
      jobsBody.innerHTML = html;

      var buttons = jobsBody.querySelectorAll("button[data-job-id]");
      for (var b = 0; b < buttons.length; b++) {
        buttons[b].addEventListener("click", function(ev){
          var id = ev.currentTarget ? ev.currentTarget.getAttribute("data-job-id") : "";
          if (!id) return;
          selectedJobId = id;
          loadSelectedJob();
          loadJobs(false);
        });
      }

      if (!selectedJobId) selectedJobId = firstJobId;
      if (loadSelected || selectedJobId) {
        await loadSelectedJob();
      }
    } catch (err) {
      jobsBody.innerHTML = "<tr><td colspan=\"7\" class=\"meta\">Unable to load jobs.</td></tr>";
    }
  }

  async function loadSelectedJob(){
    if (!selectedJobId) return;
    try {
      var res = await fetch(appContext + "/search_jobs?action=get&job_id=" + encodeURIComponent(selectedJobId) + "&include_results=1", {
        credentials: "same-origin"
      });
      var payload = await res.json();
      if (!payload || payload.ok === false || !payload.job) throw new Error(payload && payload.message ? payload.message : "Unable to load job.");
      var job = payload.job || {};
      var status = String(job.status || "");
      var msg = String(job.message || "");
      jobDetailMeta.textContent = "Job " + selectedJobId + " | " + status + " | " + msg;

      var rows = Array.isArray(job.results) ? job.results : [];
      if (!rows.length) {
        resultsBody.innerHTML = "<tr><td colspan=\"6\" class=\"meta\">No results for this job.</td></tr>";
        return;
      }

      var html = "";
      for (var i = 0; i < rows.length; i++) {
        var r = rows[i] || {};
        html += "<tr>"
          + "<td>" + esc(r.matter_label || r.matter_uuid || "") + "</td>"
          + "<td>" + esc(r.document_title || r.document_uuid || "") + "</td>"
          + "<td>" + esc(r.part_label || r.part_uuid || "") + "</td>"
          + "<td>" + esc(r.version_label || r.version_uuid || "") + "</td>"
          + "<td>" + esc(r.matched_in || "") + "</td>"
          + "<td>" + esc(r.snippet || "") + "</td>"
          + "</tr>";
      }
      resultsBody.innerHTML = html;
    } catch (err) {
      resultsBody.innerHTML = "<tr><td colspan=\"6\" class=\"meta\">Unable to load selected job.</td></tr>";
    }
  }

  searchTypeEl.addEventListener("change", function(){
    applyOperatorsToCriteriaRows();
  });
  addCriterionBtn.addEventListener("click", function(){
    addCriterionRow({ scope: "any", operator: "contains", query: "" });
  });
  queueBtn.addEventListener("click", queueJob);

  (async function init(){
    if (criteriaRowsEl.children.length === 0) {
      addCriterionRow({ scope: "any", operator: "contains", query: "" });
    }
    await loadTypes();
    await loadJobs(true);
    if (pollHandle) window.clearInterval(pollHandle);
    pollHandle = window.setInterval(function(){ loadJobs(false); }, 2500);
  })();
})();
</script>

<jsp:include page="footer.jsp" />
