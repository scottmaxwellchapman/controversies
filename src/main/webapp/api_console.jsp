<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ include file="security.jspf" %>
<% if (!require_login()) return; %>
<% if (!require_permission("api.credentials.manage")) return; %>
<%!
  private static String safe(String s) { return s == null ? "" : s; }
  private static String esc(String s) {
    return safe(s)
      .replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")
      .replace("'", "&#39;");
  }
%>
<%
String ctx = safe(request.getContextPath());
String tenantUuid = safe((String) session.getAttribute("tenant.uuid")).trim();
request.setAttribute("activeNav", "/api_console.jsp");
%>
<jsp:include page="header.jsp" />

<section class="card">
  <h1 class="u-m-0">API Console</h1>
  <div class="meta u-mt-6">Run discovery and operation requests against <code>api_servlet</code> from the UI.</div>
</section>

<section class="card section-gap-12">
  <form id="apiConsoleForm" class="form" onsubmit="return false;">
    <div class="grid grid-2">
      <div>
        <label for="endpointMode">Request Mode</label>
        <select id="endpointMode">
          <option value="help">GET /api/v1/help</option>
          <option value="readme">GET /api/v1/help/readme</option>
          <option value="ping">GET /api/v1/ping</option>
          <option value="capabilities">GET /api/v1/capabilities</option>
          <option value="execute" selected>POST /api/v1/execute</option>
        </select>
      </div>
      <div>
        <label for="operation">Operation (execute mode)</label>
        <input id="operation" type="text" value="auth.whoami" />
      </div>
      <div>
        <label for="tenantUuid">Tenant UUID</label>
        <input id="tenantUuid" type="text" value="<%= esc(tenantUuid) %>" />
      </div>
      <div>
        <label for="apiKey">API Key</label>
        <input id="apiKey" type="text" autocomplete="off" />
      </div>
      <div>
        <label for="apiSecret">API Secret</label>
        <input id="apiSecret" type="password" autocomplete="off" />
      </div>
    </div>
    <label for="paramsJson">Params JSON (execute mode)</label>
    <textarea id="paramsJson" rows="10">{
  "limit": 20
}</textarea>

    <div class="u-flex u-gap-8 u-items-center u-wrap">
      <button id="runApiCallBtn" class="btn" type="button">Run Request</button>
      <span id="apiRunStatus" class="meta" aria-live="polite"></span>
    </div>
  </form>
</section>

<section class="card section-gap-12">
  <h2 class="u-m-0">Response</h2>
  <div class="meta u-mt-6" id="apiResponseMeta">No request executed yet.</div>
  <pre id="apiResponseBody" style="white-space:pre-wrap;"></pre>
</section>

<script>
(function(){
  var ctx = "<%= esc(ctx) %>";
  var modeEl = document.getElementById("endpointMode");
  var opEl = document.getElementById("operation");
  var tenantEl = document.getElementById("tenantUuid");
  var keyEl = document.getElementById("apiKey");
  var secretEl = document.getElementById("apiSecret");
  var paramsEl = document.getElementById("paramsJson");
  var runBtn = document.getElementById("runApiCallBtn");
  var statusEl = document.getElementById("apiRunStatus");
  var metaEl = document.getElementById("apiResponseMeta");
  var bodyEl = document.getElementById("apiResponseBody");

  function setStatus(msg, isError){
    statusEl.textContent = String(msg || "");
    statusEl.classList.toggle("is-error", !!isError);
  }

  function pretty(value){
    try {
      if (typeof value === "string") return value;
      return JSON.stringify(value, null, 2);
    } catch (ignored) {
      return String(value == null ? "" : value);
    }
  }

  function parseParamsJson(){
    var raw = String(paramsEl.value || "").trim();
    if (!raw) return {};
    try {
      var parsed = JSON.parse(raw);
      if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) return parsed;
      throw new Error("Params JSON must be an object.");
    } catch (err) {
      throw new Error("Invalid params JSON: " + (err && err.message ? err.message : "parse error"));
    }
  }

  async function runRequest(){
    setStatus("Running request...", false);
    metaEl.textContent = "Running...";
    bodyEl.textContent = "";

    var mode = String(modeEl.value || "");
    var url = ctx + "/api/v1/execute";
    var method = "POST";
    var headers = {};
    var body = null;
    var startedAt = Date.now();

    if (mode === "help") {
      url = ctx + "/api/v1/help";
      method = "GET";
    } else if (mode === "readme") {
      url = ctx + "/api/v1/help/readme";
      method = "GET";
    } else if (mode === "ping") {
      url = ctx + "/api/v1/ping";
      method = "GET";
    } else if (mode === "capabilities") {
      url = ctx + "/api/v1/capabilities";
      method = "GET";
    } else {
      var tenantUuid = String(tenantEl.value || "").trim();
      var apiKey = String(keyEl.value || "").trim();
      var apiSecret = String(secretEl.value || "").trim();
      var operation = String(opEl.value || "").trim();
      if (!tenantUuid) throw new Error("Tenant UUID is required for execute mode.");
      if (!apiKey) throw new Error("API Key is required for execute mode.");
      if (!apiSecret) throw new Error("API Secret is required for execute mode.");
      if (!operation) throw new Error("Operation is required for execute mode.");
      headers["X-Tenant-UUID"] = tenantUuid;
      headers["X-API-Key"] = apiKey;
      headers["X-API-Secret"] = apiSecret;
      headers["Content-Type"] = "application/json";
      body = JSON.stringify({
        operation: operation,
        params: parseParamsJson()
      });
    }

    var response = await fetch(url, {
      method: method,
      headers: headers,
      body: body,
      credentials: "same-origin"
    });

    var elapsed = Date.now() - startedAt;
    var contentType = String(response.headers.get("content-type") || "").toLowerCase();
    var payloadText = "";
    var payloadValue = "";

    if (contentType.indexOf("application/json") >= 0) {
      try {
        payloadValue = await response.json();
      } catch (err) {
        payloadText = await response.text();
      }
    } else {
      payloadText = await response.text();
    }

    var responseBody = payloadText || pretty(payloadValue);
    bodyEl.textContent = responseBody;
    metaEl.textContent = method + " " + url + " -> HTTP " + response.status + " (" + elapsed + " ms)";
    setStatus(response.ok ? "Request complete." : "Request returned error status.", !response.ok);
  }

  runBtn.addEventListener("click", async function(){
    try {
      await runRequest();
    } catch (err) {
      var msg = err && err.message ? err.message : "Request failed.";
      metaEl.textContent = msg;
      bodyEl.textContent = "";
      setStatus(msg, true);
    }
  });
})();
</script>

<jsp:include page="footer.jsp" />
