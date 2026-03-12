<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ page import="java.time.Instant" %>
<%@ page import="java.time.Year" %>
<%@ page import="java.time.ZoneId" %>

<%!
  private static String footerEsc(String s) {
    if (s == null) return "";
    return s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
  }
%>

<%
  String footerCtx = request.getContextPath();
  if (footerCtx == null) footerCtx = "";

  long serverEpochMs = net.familylawandprobate.controversies.app_clock.now().toEpochMilli();
  String serverZoneId = ZoneId.systemDefault().getId();

  String footerTenantUuid = String.valueOf(session.getAttribute("tenant.uuid") == null ? "" : session.getAttribute("tenant.uuid")).trim();
  String footerUserUuid = String.valueOf(session.getAttribute("user.uuid") == null ? "" : session.getAttribute("user.uuid")).trim();
  String footerUserEmail = String.valueOf(session.getAttribute("user.email") == null ? "" : session.getAttribute("user.email")).trim();
  String footerChatActor = footerUserEmail.isBlank() ? footerUserUuid : footerUserEmail;
  boolean footerChatEnabled = !footerTenantUuid.isBlank() && !footerUserUuid.isBlank();

  String footerCsrfToken = "";
  Object csrfAttr = request.getAttribute("csrfToken");
  if (csrfAttr instanceof String) {
    footerCsrfToken = (String) csrfAttr;
  }
  if (footerCsrfToken == null || footerCsrfToken.trim().isBlank()) {
    Object csrfSession = session.getAttribute("CSRF_TOKEN");
    if (csrfSession instanceof String) footerCsrfToken = (String) csrfSession;
  }
  if (footerCsrfToken == null) footerCsrfToken = "";
%>

</main>

<footer class="site-footer">
    <div class="container footer-row">
        <div class="footer-left">
            <span class="muted"></span>
            <span class="dot">•</span>
            <span id="footerServerClock" class="muted">Server time loading...</span>
        </div>

        <div class="footer-right muted">
            <span id="footerWeather">Weather: off</span>
            <button type="button" id="footerWeatherToggle" class="btn btn-ghost btn-sm">Enable</button>
        </div>
    </div>
</footer>

<div id="footerRuntimeData"
     style="display:none;"
     data-server-epoch-ms="<%= serverEpochMs %>"
     data-server-zone="<%= footerEsc(serverZoneId) %>"></div>

<div id="footerChatRuntimeData"
     style="display:none;"
     data-enabled="<%= footerChatEnabled ? "1" : "0" %>"
     data-context-path="<%= footerEsc(footerCtx) %>"
     data-csrf-token="<%= footerEsc(footerCsrfToken) %>"
     data-user-label="<%= footerEsc(footerChatActor) %>"></div>

<% if (footerChatEnabled) { %>
<div id="tenantChatWidget" class="tenant-chat-widget" hidden>
  <button type="button" id="tenantChatLauncher" class="tenant-chat-launcher" aria-controls="tenantChatPanel" aria-expanded="false">
    <span>Tenant Chat</span>
    <span id="tenantChatUnread" class="tenant-chat-unread" hidden>0</span>
  </button>

  <section id="tenantChatPanel" class="tenant-chat-panel" hidden>
    <header class="tenant-chat-header">
      <strong>Tenant Chat</strong>
      <div class="tenant-chat-header-actions">
        <a class="btn btn-ghost btn-sm" href="<%= footerCtx %>/omnichannel.jsp?channel_filter=internal_messages&q=Tenant+User+Chat&show=all" title="Open full omnichannel view">Popout</a>
        <button type="button" id="tenantChatClose" class="btn btn-ghost btn-sm" title="Close chat">Close</button>
      </div>
    </header>

    <div id="tenantChatTimeline" class="tenant-chat-timeline" aria-live="polite"></div>
    <div id="tenantChatStatus" class="tenant-chat-status">Connecting...</div>

    <form id="tenantChatForm" class="tenant-chat-form" action="<%= footerCtx %>/tenant_chat" method="post" enctype="multipart/form-data" novalidate>
      <div class="tenant-chat-toolbar">
        <button type="button" class="btn btn-ghost btn-sm tenant-chat-tool" data-chat-wrap="**" title="Bold">B</button>
        <button type="button" class="btn btn-ghost btn-sm tenant-chat-tool" data-chat-wrap="*" title="Italic">I</button>
        <button type="button" class="btn btn-ghost btn-sm tenant-chat-tool" data-chat-wrap="`" title="Code">Code</button>
        <button type="button" class="btn btn-ghost btn-sm tenant-chat-tool" data-chat-link="1" title="Insert link">Link</button>
        <button type="button" class="btn btn-ghost btn-sm tenant-chat-tool" id="tenantChatEmojiToggle" title="Emoji">😀</button>
        <span class="tenant-chat-toolbar-hint">Markdown supported: **bold**, *italic*, `code`, [label](https://...)</span>
      </div>

      <div id="tenantChatEmojiPanel" class="tenant-chat-emoji-panel" hidden>
        <button type="button" class="tenant-chat-emoji" data-chat-emoji="😀">😀</button>
        <button type="button" class="tenant-chat-emoji" data-chat-emoji="😂">😂</button>
        <button type="button" class="tenant-chat-emoji" data-chat-emoji="👍">👍</button>
        <button type="button" class="tenant-chat-emoji" data-chat-emoji="🙏">🙏</button>
        <button type="button" class="tenant-chat-emoji" data-chat-emoji="🎉">🎉</button>
        <button type="button" class="tenant-chat-emoji" data-chat-emoji="❤️">❤️</button>
        <button type="button" class="tenant-chat-emoji" data-chat-emoji="✅">✅</button>
        <button type="button" class="tenant-chat-emoji" data-chat-emoji="⚖️">⚖️</button>
        <button type="button" class="tenant-chat-emoji" data-chat-emoji="📎">📎</button>
      </div>

      <textarea id="tenantChatInput" name="body" rows="2" maxlength="20000" placeholder="Type a message..."></textarea>

      <div class="tenant-chat-file-row">
        <input type="file" id="tenantChatFiles" name="files" multiple />
      </div>

      <div class="tenant-chat-compose-actions">
        <span class="muted">Enter to send, Shift+Enter newline, max 10 files (20MB each)</span>
        <button type="submit" id="tenantChatSend" class="btn btn-sm">Send</button>
      </div>
    </form>
  </section>
</div>
<% } %>

<style>
  .tenant-chat-widget {
    position: fixed;
    right: 18px;
    bottom: 82px;
    z-index: 1200;
  }

  .tenant-chat-widget[hidden],
  .tenant-chat-panel[hidden],
  .tenant-chat-emoji-panel[hidden] {
    display: none !important;
  }

  .tenant-chat-launcher {
    border: 1px solid var(--border);
    background: linear-gradient(180deg, var(--accent-soft), var(--surface-2));
    color: var(--fg);
    border-radius: 999px;
    padding: 10px 14px;
    font-size: 0.9rem;
    font-weight: 600;
    cursor: pointer;
    display: inline-flex;
    align-items: center;
    gap: 8px;
    box-shadow: 0 8px 26px rgba(0, 0, 0, 0.18);
  }

  .tenant-chat-launcher.is-open {
    border-color: var(--accent);
  }

  .tenant-chat-unread {
    min-width: 18px;
    height: 18px;
    padding: 0 5px;
    border-radius: 999px;
    background: var(--danger, #d45454);
    color: #fff;
    font-size: 0.73rem;
    line-height: 18px;
    text-align: center;
  }

  .tenant-chat-panel {
    width: min(360px, calc(100vw - 24px));
    max-height: min(520px, calc(100vh - 140px));
    margin-top: 10px;
    border: 1px solid var(--border);
    border-radius: 14px;
    background: color-mix(in srgb, var(--surface) 95%, #ffffff 5%);
    box-shadow: 0 18px 42px rgba(0, 0, 0, 0.24);
    display: grid;
    grid-template-rows: auto minmax(120px, 1fr) auto auto;
    overflow: hidden;
  }

  .tenant-chat-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 10px;
    padding: 10px 12px;
    border-bottom: 1px solid var(--border);
    background: linear-gradient(180deg, var(--surface-2), color-mix(in srgb, var(--surface-2) 78%, transparent));
  }

  .tenant-chat-header-actions {
    display: inline-flex;
    gap: 6px;
    align-items: center;
  }

  .tenant-chat-timeline {
    overflow: auto;
    padding: 10px;
    display: flex;
    flex-direction: column;
    gap: 8px;
    background: color-mix(in srgb, var(--surface) 92%, transparent);
  }

  .tenant-chat-item {
    border: 1px solid var(--border);
    border-radius: 10px;
    padding: 8px 10px;
    background: var(--surface);
    max-width: 92%;
  }

  .tenant-chat-item.is-self {
    margin-left: auto;
    border-color: var(--accent);
    background: color-mix(in srgb, var(--accent-soft) 48%, var(--surface));
  }

  .tenant-chat-meta {
    color: var(--muted);
    font-size: 0.76rem;
    margin-bottom: 4px;
    display: flex;
    justify-content: space-between;
    gap: 10px;
  }

  .tenant-chat-body {
    word-break: break-word;
    font-size: 0.9rem;
    line-height: 1.35;
  }

  .tenant-chat-body p {
    margin: 0 0 6px 0;
  }

  .tenant-chat-body p:last-child {
    margin-bottom: 0;
  }

  .tenant-chat-body ul,
  .tenant-chat-body ol {
    margin: 0 0 6px 18px;
    padding: 0;
  }

  .tenant-chat-body code {
    font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, "Courier New", monospace;
    font-size: 0.83em;
    background: color-mix(in srgb, var(--surface-2) 82%, transparent);
    border: 1px solid var(--border);
    border-radius: 6px;
    padding: 1px 4px;
  }

  .tenant-chat-body a {
    color: var(--accent);
    text-decoration: underline;
  }

  .tenant-chat-attachments {
    margin-top: 8px;
    display: flex;
    flex-direction: column;
    gap: 6px;
  }

  .tenant-chat-attachment {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    font-size: 0.82rem;
    padding: 4px 6px;
    border: 1px solid var(--border);
    border-radius: 8px;
    background: var(--surface-2);
    width: fit-content;
    max-width: 100%;
  }

  .tenant-chat-attachment a {
    color: inherit;
    text-decoration: none;
    border-bottom: 1px solid currentColor;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    max-width: 210px;
  }

  .tenant-chat-status {
    border-top: 1px solid var(--border);
    color: var(--muted);
    font-size: 0.78rem;
    padding: 6px 10px;
    background: var(--surface-2);
  }

  .tenant-chat-status.is-error {
    color: var(--danger, #d45454);
  }

  .tenant-chat-form {
    padding: 8px 10px;
    border-top: 1px solid var(--border);
    background: var(--surface);
  }

  .tenant-chat-toolbar {
    display: flex;
    align-items: center;
    gap: 6px;
    flex-wrap: wrap;
    margin-bottom: 6px;
  }

  .tenant-chat-tool {
    min-width: 34px;
    height: 30px;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    font-weight: 650;
  }

  .tenant-chat-toolbar-hint {
    color: var(--muted);
    font-size: 0.72rem;
    margin-left: 4px;
  }

  .tenant-chat-emoji-panel {
    margin-bottom: 6px;
    display: flex;
    align-items: center;
    gap: 4px;
    flex-wrap: wrap;
    padding: 6px;
    border: 1px solid var(--border);
    border-radius: 10px;
    background: var(--surface-2);
  }

  .tenant-chat-emoji {
    border: 1px solid transparent;
    background: transparent;
    border-radius: 8px;
    cursor: pointer;
    font-size: 1rem;
    line-height: 1;
    padding: 4px 6px;
  }

  .tenant-chat-emoji:hover,
  .tenant-chat-emoji:focus-visible {
    border-color: var(--border);
    background: var(--surface);
    outline: none;
  }

  .tenant-chat-form textarea {
    width: 100%;
    resize: vertical;
    min-height: 56px;
    max-height: 130px;
    border-radius: 10px;
    border: 1px solid var(--border);
    padding: 8px 10px;
    font: inherit;
    background: var(--surface-2);
    color: var(--fg);
  }

  .tenant-chat-file-row {
    margin-top: 6px;
  }

  .tenant-chat-file-row input[type="file"] {
    width: 100%;
    font: inherit;
    font-size: 0.8rem;
    color: var(--muted);
  }

  .tenant-chat-compose-actions {
    margin-top: 6px;
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
  }

  .tenant-chat-placeholder {
    color: var(--muted);
    text-align: center;
    border: 1px dashed var(--border);
    border-radius: 10px;
    padding: 18px 10px;
    font-size: 0.85rem;
  }

  @media (max-width: 760px) {
    .tenant-chat-widget {
      left: 10px;
      right: 10px;
      bottom: 74px;
    }

    .tenant-chat-launcher {
      margin-left: auto;
      display: flex;
    }

    .tenant-chat-panel {
      width: 100%;
      max-height: min(560px, calc(100vh - 120px));
    }
  }
</style>

<script>
(() => {
  const dataEl = document.getElementById("footerRuntimeData");
  const clockEl = document.getElementById("footerServerClock");
  const weatherEl = document.getElementById("footerWeather");
  const weatherToggleEl = document.getElementById("footerWeatherToggle");
  if (!dataEl) return;

  const baseEpochMs = parseInt(dataEl.getAttribute("data-server-epoch-ms") || "0", 10);
  const serverZone = String(dataEl.getAttribute("data-server-zone") || "UTC").trim() || "UTC";
  const startedClientMs = Date.now();

  function formatServerDateTime(ms) {
    const now = new Date(ms);
    try {
      return new Intl.DateTimeFormat(undefined, {
        dateStyle: "full",
        timeStyle: "long",
        timeZone: serverZone
      }).format(now);
    } catch (err) {
      return new Intl.DateTimeFormat(undefined, {
        dateStyle: "full",
        timeStyle: "long"
      }).format(now) + " (" + serverZone + ")";
    }
  }

  function tickClock() {
    if (!clockEl) return;
    const ms = baseEpochMs + (Date.now() - startedClientMs);
    clockEl.textContent = formatServerDateTime(ms) + " (" + serverZone + ")";
  }

  tickClock();
  setInterval(tickClock, 1000);

  if (!weatherEl || !weatherToggleEl) return;

  const cfg = document.getElementById("uiThemeConfig");
  const scope = cfg ? String(cfg.getAttribute("data-pref-scope") || "public") : "public";
  const WEATHER_ENABLED_KEY = "ui.footer.weather.enabled." + scope;

  function readWeatherEnabled() {
    try {
      return String(localStorage.getItem(WEATHER_ENABLED_KEY) || "").trim() === "1";
    } catch (ignored) {
      return false;
    }
  }

  function writeWeatherEnabled(enabled) {
    try {
      localStorage.setItem(WEATHER_ENABLED_KEY, enabled ? "1" : "0");
    } catch (ignored) {}
  }

  function updateWeatherToggle(enabled, isLoading) {
    if (!weatherToggleEl) return;
    weatherToggleEl.textContent = enabled ? "Disable" : "Enable";
    weatherToggleEl.disabled = !!isLoading;
    weatherToggleEl.title = enabled ? "Disable weather fetch" : "Enable weather fetch";
  }

  function weatherCodeLabel(code) {
    const c = Number(code);
    if (c === 0) return "Clear";
    if (c === 1) return "Mostly clear";
    if (c === 2) return "Partly cloudy";
    if (c === 3) return "Overcast";
    if (c === 45 || c === 48) return "Fog";
    if (c === 51 || c === 53 || c === 55) return "Drizzle";
    if (c === 56 || c === 57) return "Freezing drizzle";
    if (c === 61 || c === 63 || c === 65) return "Rain";
    if (c === 66 || c === 67) return "Freezing rain";
    if (c === 71 || c === 73 || c === 75 || c === 77) return "Snow";
    if (c === 80 || c === 81 || c === 82) return "Rain showers";
    if (c === 85 || c === 86) return "Snow showers";
    if (c === 95) return "Thunderstorm";
    if (c === 96 || c === 99) return "Thunderstorm with hail";
    return "Weather " + c;
  }

  function weatherError(message) {
    weatherEl.textContent = "Weather: " + message;
  }

  async function loadWeather() {
    if (!navigator.geolocation) {
      weatherError("location unavailable");
      return;
    }

    weatherEl.textContent = "Weather: locating...";
    updateWeatherToggle(true, true);

    navigator.geolocation.getCurrentPosition(async (position) => {
      const lat = position.coords.latitude;
      const lon = position.coords.longitude;

      const weatherUrl =
        "https://api.open-meteo.com/v1/forecast"
        + "?latitude=" + encodeURIComponent(String(lat))
        + "&longitude=" + encodeURIComponent(String(lon))
        + "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m"
        + "&temperature_unit=fahrenheit"
        + "&wind_speed_unit=mph"
        + "&timezone=auto";

      const geoUrl =
        "https://geocoding-api.open-meteo.com/v1/reverse"
        + "?latitude=" + encodeURIComponent(String(lat))
        + "&longitude=" + encodeURIComponent(String(lon))
        + "&language=en&count=1";

      try {
        const weatherPromise = fetch(weatherUrl, { headers: { "Accept": "application/json" } });
        const geoPromise = fetch(geoUrl, { headers: { "Accept": "application/json" } });
        const responses = await Promise.all([weatherPromise, geoPromise]);

        if (!responses[0].ok) throw new Error("weather service unavailable");
        const forecast = await responses[0].json();
        const geo = responses[1].ok ? await responses[1].json() : null;

        const current = forecast && forecast.current ? forecast.current : null;
        if (!current) {
          weatherError("currently unavailable");
          return;
        }

        let place = "";
        try {
          const first = geo && geo.results && geo.results.length > 0 ? geo.results[0] : null;
          if (first) {
            const parts = [];
            if (first.name) parts.push(String(first.name));
            if (first.admin1) parts.push(String(first.admin1));
            place = parts.join(", ");
          }
        } catch (ignore) {}

        const temp = (typeof current.temperature_2m === "number") ? Math.round(current.temperature_2m) + "°F" : "";
        const feels = (typeof current.apparent_temperature === "number") ? Math.round(current.apparent_temperature) + "°F" : "";
        const wind = (typeof current.wind_speed_10m === "number") ? Math.round(current.wind_speed_10m) + " mph" : "";
        const summary = weatherCodeLabel(current.weather_code);

        const left = place ? ("Weather (" + place + "): ") : "Weather: ";
        const tokens = [];
        if (temp) tokens.push(temp);
        if (summary) tokens.push(summary);
        if (feels) tokens.push("Feels " + feels);
        if (wind) tokens.push("Wind " + wind);

        weatherEl.textContent = left + tokens.join(" | ");
      } catch (err) {
        weatherError("unavailable");
      } finally {
        updateWeatherToggle(true, false);
      }
    }, (error) => {
      if (!error) {
        weatherError("location unavailable");
      } else if (error.code === 1) {
        weatherError("permission denied");
      } else if (error.code === 2) {
        weatherError("location unavailable");
      } else if (error.code === 3) {
        weatherError("location timeout");
      } else {
        weatherError("unavailable");
      }
      updateWeatherToggle(true, false);
    }, {
      enableHighAccuracy: false,
      timeout: 10000,
      maximumAge: 600000
    });
  }

  function applyWeatherState(enabled) {
    writeWeatherEnabled(enabled);
    updateWeatherToggle(enabled, false);
    if (!enabled) {
      weatherEl.textContent = "Weather: off";
      return;
    }
    loadWeather();
  }

  weatherToggleEl.addEventListener("click", function () {
    const currentlyEnabled = readWeatherEnabled();
    applyWeatherState(!currentlyEnabled);
  });

  applyWeatherState(readWeatherEnabled());
})();
</script>

<script>
(() => {
  const runtimeEl = document.getElementById("footerChatRuntimeData");
  const widgetEl = document.getElementById("tenantChatWidget");
  if (!runtimeEl || !widgetEl) return;

  const enabled = String(runtimeEl.getAttribute("data-enabled") || "").trim() === "1";
  if (!enabled) return;

  const contextPath = String(runtimeEl.getAttribute("data-context-path") || "").trim();
  const csrfToken = String(runtimeEl.getAttribute("data-csrf-token") || "").trim();
  const userLabel = String(runtimeEl.getAttribute("data-user-label") || "").trim();

  const launcherEl = document.getElementById("tenantChatLauncher");
  const unreadEl = document.getElementById("tenantChatUnread");
  const panelEl = document.getElementById("tenantChatPanel");
  const closeEl = document.getElementById("tenantChatClose");
  const timelineEl = document.getElementById("tenantChatTimeline");
  const statusEl = document.getElementById("tenantChatStatus");
  const formEl = document.getElementById("tenantChatForm");
  const inputEl = document.getElementById("tenantChatInput");
  const filesEl = document.getElementById("tenantChatFiles");
  const sendEl = document.getElementById("tenantChatSend");
  const emojiToggleEl = document.getElementById("tenantChatEmojiToggle");
  const emojiPanelEl = document.getElementById("tenantChatEmojiPanel");
  const toolEls = Array.prototype.slice.call(document.querySelectorAll(".tenant-chat-tool"));
  const emojiEls = Array.prototype.slice.call(document.querySelectorAll(".tenant-chat-emoji[data-chat-emoji]"));

  if (!launcherEl || !unreadEl || !panelEl || !closeEl || !timelineEl || !statusEl || !formEl || !inputEl || !filesEl || !sendEl || !emojiToggleEl || !emojiPanelEl) {
    return;
  }

  const cfg = document.getElementById("uiThemeConfig");
  const scope = cfg ? String(cfg.getAttribute("data-pref-scope") || "public") : "public";
  const OPEN_KEY = "ui.tenant.chat.open." + scope;

  let open = false;
  let unreadCount = 0;
  let loading = false;
  let sending = false;
  let initialized = false;
  let lastSeenAt = "";
  let pollHandle = 0;

  function hasText(value) {
    return String(value || "").trim().length > 0;
  }

  function fileCount() {
    return filesEl && filesEl.files ? filesEl.files.length : 0;
  }

  function readOpenPreference() {
    try {
      return String(localStorage.getItem(OPEN_KEY) || "").trim() === "1";
    } catch (ignored) {
      return false;
    }
  }

  function writeOpenPreference(value) {
    try {
      localStorage.setItem(OPEN_KEY, value ? "1" : "0");
    } catch (ignored) {}
  }

  function setStatus(message, isError) {
    statusEl.textContent = String(message || "").trim();
    statusEl.classList.toggle("is-error", !!isError);
  }

  function setUnread(count) {
    unreadCount = Math.max(0, Number(count) || 0);
    if (unreadCount > 0) {
      unreadEl.hidden = false;
      unreadEl.textContent = unreadCount > 99 ? "99+" : String(unreadCount);
    } else {
      unreadEl.hidden = true;
      unreadEl.textContent = "0";
    }
  }

  function formatTime(iso) {
    const raw = String(iso || "").trim();
    if (!raw) return "";
    try {
      const d = new Date(raw);
      if (Number.isNaN(d.getTime())) return raw;
      return new Intl.DateTimeFormat(undefined, {
        dateStyle: "short",
        timeStyle: "short"
      }).format(d);
    } catch (ignored) {
      return raw;
    }
  }

  function scrollTimelineToBottom() {
    timelineEl.scrollTop = timelineEl.scrollHeight;
  }

  function escHtml(value) {
    return String(value || "")
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/\"/g, "&quot;")
      .replace(/'/g, "&#39;");
  }

  function sanitizeUrl(url) {
    const raw = String(url || "").trim();
    if (!raw) return "";
    if (/^https?:\/\//i.test(raw)) return raw;
    if (/^mailto:/i.test(raw)) return raw;
    return "";
  }

  function renderInlineMarkup(text) {
    let html = escHtml(text);
    html = html.replace(/`([^`]+)`/g, "<code>$1</code>");
    html = html.replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>");
    html = html.replace(/\*([^*\n]+)\*/g, "<em>$1</em>");
    html = html.replace(/~~([^~]+)~~/g, "<del>$1</del>");
    html = html.replace(/\[([^\]]+)\]\(([^)\s]+)\)/g, function (_, label, href) {
      const safeHref = sanitizeUrl(href);
      if (!safeHref) return label + " (" + href + ")";
      return "<a href=\"" + safeHref.replace(/\"/g, "%22") + "\" target=\"_blank\" rel=\"noopener noreferrer\">" + label + "</a>";
    });
    return html;
  }

  function renderRichMessage(raw) {
    const lines = String(raw || "").replace(/\r\n/g, "\n").split("\n");
    if (lines.length === 0) return "";

    let html = "";
    let inUl = false;
    let inOl = false;

    function closeLists() {
      if (inUl) {
        html += "</ul>";
        inUl = false;
      }
      if (inOl) {
        html += "</ol>";
        inOl = false;
      }
    }

    for (let i = 0; i < lines.length; i += 1) {
      const line = lines[i];
      const trimmed = line.trim();

      if (!trimmed) {
        closeLists();
        continue;
      }

      const ulMatch = /^[-*]\s+(.+)$/.exec(trimmed);
      if (ulMatch) {
        if (inOl) {
          html += "</ol>";
          inOl = false;
        }
        if (!inUl) {
          html += "<ul>";
          inUl = true;
        }
        html += "<li>" + renderInlineMarkup(ulMatch[1]) + "</li>";
        continue;
      }

      const olMatch = /^\d+\.\s+(.+)$/.exec(trimmed);
      if (olMatch) {
        if (inUl) {
          html += "</ul>";
          inUl = false;
        }
        if (!inOl) {
          html += "<ol>";
          inOl = true;
        }
        html += "<li>" + renderInlineMarkup(olMatch[1]) + "</li>";
        continue;
      }

      closeLists();
      html += "<p>" + renderInlineMarkup(trimmed).replace(/\n/g, "<br>") + "</p>";
    }

    closeLists();
    return html || "<p></p>";
  }

  function formatBytes(value) {
    const n = Number(value || 0);
    if (!Number.isFinite(n) || n <= 0) return "";
    if (n < 1024) return n + " B";
    if (n < 1024 * 1024) return (n / 1024).toFixed(1) + " KB";
    return (n / (1024 * 1024)).toFixed(1) + " MB";
  }

  function renderMessages(messages) {
    timelineEl.textContent = "";

    if (!Array.isArray(messages) || messages.length === 0) {
      const emptyEl = document.createElement("div");
      emptyEl.className = "tenant-chat-placeholder";
      emptyEl.textContent = "No messages yet. Start the conversation.";
      timelineEl.appendChild(emptyEl);
      return;
    }

    for (let i = 0; i < messages.length; i += 1) {
      const msg = messages[i] || {};

      const rowEl = document.createElement("article");
      rowEl.className = "tenant-chat-item" + (msg.self ? " is-self" : "");

      const metaEl = document.createElement("div");
      metaEl.className = "tenant-chat-meta";
      const whoEl = document.createElement("span");
      whoEl.textContent = String(msg.created_by || "Unknown user");
      const whenEl = document.createElement("span");
      whenEl.textContent = formatTime(msg.created_at);
      metaEl.appendChild(whoEl);
      metaEl.appendChild(whenEl);

      const bodyEl = document.createElement("div");
      bodyEl.className = "tenant-chat-body";
      bodyEl.innerHTML = renderRichMessage(String(msg.body || ""));

      rowEl.appendChild(metaEl);
      rowEl.appendChild(bodyEl);

      const attachments = Array.isArray(msg.attachments) ? msg.attachments : [];
      if (attachments.length > 0) {
        const filesWrap = document.createElement("div");
        filesWrap.className = "tenant-chat-attachments";
        for (let j = 0; j < attachments.length; j += 1) {
          const file = attachments[j] || {};
          const fileRow = document.createElement("div");
          fileRow.className = "tenant-chat-attachment";

          const icon = document.createElement("span");
          icon.textContent = "📎";
          fileRow.appendChild(icon);

          const link = document.createElement("a");
          const downloadUrl = String(file.download_url || "").trim();
          if (downloadUrl) {
            link.href = contextPath + downloadUrl;
          } else {
            link.href = "#";
          }
          link.textContent = String(file.file_name || "attachment.bin");
          link.setAttribute("target", "_blank");
          link.setAttribute("rel", "noopener noreferrer");
          fileRow.appendChild(link);

          const size = formatBytes(file.file_size_bytes);
          if (size) {
            const sizeEl = document.createElement("span");
            sizeEl.className = "muted";
            sizeEl.textContent = size;
            fileRow.appendChild(sizeEl);
          }

          filesWrap.appendChild(fileRow);
        }
        rowEl.appendChild(filesWrap);
      }

      timelineEl.appendChild(rowEl);
    }
  }

  function updateUnread(messages) {
    if (!Array.isArray(messages) || messages.length === 0) return;

    let incoming = 0;
    for (let i = 0; i < messages.length; i += 1) {
      const msg = messages[i] || {};
      const createdAt = String(msg.created_at || "").trim();
      if (!createdAt) continue;
      if (msg.self) continue;
      if (hasText(lastSeenAt) && createdAt <= lastSeenAt) continue;
      incoming += 1;
    }

    const latest = String((messages[messages.length - 1] || {}).created_at || "").trim();
    if (hasText(latest) && (!hasText(lastSeenAt) || latest > lastSeenAt)) {
      lastSeenAt = latest;
    }

    if (initialized && !open && incoming > 0) {
      setUnread(unreadCount + incoming);
    }
  }

  async function loadMessages(showStatus) {
    if (loading) return;
    loading = true;

    if (showStatus) setStatus("Loading chat...", false);

    try {
      const response = await fetch(contextPath + "/tenant_chat?action=messages&limit=150", {
        method: "GET",
        headers: {
          "Accept": "application/json"
        },
        credentials: "same-origin"
      });

      let payload = null;
      try {
        payload = await response.json();
      } catch (ignored) {
        payload = null;
      }

      if (!response.ok || !payload || payload.ok === false) {
        throw new Error(payload && payload.message ? String(payload.message) : "Request failed");
      }

      const messages = Array.isArray(payload.messages) ? payload.messages : [];
      updateUnread(messages);
      renderMessages(messages);

      if (open) {
        setUnread(0);
        scrollTimelineToBottom();
      }

      const actorLabel = hasText(userLabel) ? userLabel : "tenant user";
      setStatus("Connected as " + actorLabel, false);
      initialized = true;
    } catch (err) {
      setStatus("Chat unavailable.", true);
    } finally {
      loading = false;
    }
  }

  function selectionRange() {
    const start = Number(inputEl.selectionStart || 0);
    const end = Number(inputEl.selectionEnd || 0);
    return { start: Math.min(start, end), end: Math.max(start, end) };
  }

  function insertAtCursor(prefix, suffix, placeholder) {
    const value = String(inputEl.value || "");
    const range = selectionRange();
    const selected = value.slice(range.start, range.end);
    const middle = selected || String(placeholder || "");
    const next = value.slice(0, range.start) + prefix + middle + suffix + value.slice(range.end);
    inputEl.value = next;

    const cursorStart = range.start + prefix.length;
    const cursorEnd = cursorStart + middle.length;
    inputEl.focus();
    inputEl.setSelectionRange(cursorStart, cursorEnd);
  }

  function insertEmoji(emoji) {
    const value = String(inputEl.value || "");
    const range = selectionRange();
    const insert = String(emoji || "");
    if (!insert) return;
    const next = value.slice(0, range.start) + insert + value.slice(range.end);
    inputEl.value = next;
    const cursor = range.start + insert.length;
    inputEl.focus();
    inputEl.setSelectionRange(cursor, cursor);
  }

  function closeEmojiPanel() {
    emojiPanelEl.hidden = true;
  }

  async function sendMessage() {
    if (sending) return;

    const text = String(inputEl.value || "").trim();
    const files = filesEl && filesEl.files ? filesEl.files : null;
    const filesSelected = files && files.length > 0;
    if (!text && !filesSelected) return;

    if (!hasText(csrfToken)) {
      setStatus("Missing CSRF token.", true);
      return;
    }

    if (filesSelected && files.length > 10) {
      setStatus("Max 10 files per message.", true);
      return;
    }
    if (filesSelected) {
      for (let fi = 0; fi < files.length; fi += 1) {
        const file = files[fi];
        if (!file) continue;
        if (Number(file.size || 0) > (20 * 1024 * 1024)) {
          setStatus("File exceeds 20MB: " + String(file.name || "attachment"), true);
          return;
        }
      }
    }

    sending = true;
    sendEl.disabled = true;
    sendEl.textContent = filesSelected ? "Uploading..." : "Sending...";

    try {
      let response = null;
      if (filesSelected) {
        const fd = new FormData();
        fd.append("action", "upload");
        fd.append("body", text);
        for (let i = 0; i < files.length; i += 1) {
          fd.append("files", files[i], files[i].name || "attachment.bin");
        }
        response = await fetch(contextPath + "/tenant_chat?action=upload", {
          method: "POST",
          headers: {
            "Accept": "application/json",
            "X-CSRF-Token": csrfToken
          },
          credentials: "same-origin",
          body: fd
        });
      } else {
        response = await fetch(contextPath + "/tenant_chat?action=send", {
          method: "POST",
          headers: {
            "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
            "Accept": "application/json",
            "X-CSRF-Token": csrfToken
          },
          credentials: "same-origin",
          body: "action=send&body=" + encodeURIComponent(text)
        });
      }

      let payload = null;
      try {
        payload = await response.json();
      } catch (ignored) {
        payload = null;
      }

      if (!response.ok || !payload || payload.ok === false) {
        throw new Error(payload && payload.message ? String(payload.message) : "Unable to send");
      }

      inputEl.value = "";
      filesEl.value = "";
      closeEmojiPanel();
      setStatus("Message sent.", false);
      await loadMessages(false);
    } catch (err) {
      setStatus("Unable to send message.", true);
    } finally {
      sending = false;
      sendEl.disabled = false;
      sendEl.textContent = "Send";
      inputEl.focus();
    }
  }

  function openPanel() {
    open = true;
    panelEl.hidden = false;
    launcherEl.classList.add("is-open");
    launcherEl.setAttribute("aria-expanded", "true");
    setUnread(0);
    writeOpenPreference(true);
    loadMessages(true);
    inputEl.focus();
  }

  function closePanel() {
    open = false;
    panelEl.hidden = true;
    launcherEl.classList.remove("is-open");
    launcherEl.setAttribute("aria-expanded", "false");
    closeEmojiPanel();
    writeOpenPreference(false);
  }

  function togglePanel() {
    if (open) closePanel();
    else openPanel();
  }

  function startPolling() {
    if (pollHandle) {
      clearInterval(pollHandle);
      pollHandle = 0;
    }
    pollHandle = window.setInterval(() => {
      if (document.visibilityState === "hidden") return;
      loadMessages(false);
    }, 8000);
  }

  formEl.addEventListener("submit", function (event) {
    event.preventDefault();
    sendMessage();
  });

  toolEls.forEach(function (toolEl) {
    toolEl.addEventListener("click", function () {
      if (!toolEl) return;
      const wrap = String(toolEl.getAttribute("data-chat-wrap") || "").trim();
      const wantsLink = String(toolEl.getAttribute("data-chat-link") || "").trim() === "1";
      if (wantsLink) {
        insertAtCursor("[", "](https://)", "link");
        return;
      }
      if (wrap) {
        insertAtCursor(wrap, wrap, "text");
      }
    });
  });

  emojiToggleEl.addEventListener("click", function () {
    emojiPanelEl.hidden = !emojiPanelEl.hidden;
    if (!emojiPanelEl.hidden) inputEl.focus();
  });

  emojiEls.forEach(function (emojiEl) {
    emojiEl.addEventListener("click", function () {
      insertEmoji(String(emojiEl.getAttribute("data-chat-emoji") || ""));
    });
  });

  filesEl.addEventListener("change", function () {
    const count = fileCount();
    if (count > 0) setStatus(count + (count === 1 ? " file ready." : " files ready."), false);
    else setStatus("Connected as " + (hasText(userLabel) ? userLabel : "tenant user"), false);
  });

  inputEl.addEventListener("keydown", function (event) {
    if (event.key !== "Enter") return;
    if (event.shiftKey) return;
    event.preventDefault();
    sendMessage();
  });

  launcherEl.addEventListener("click", function () {
    togglePanel();
  });

  closeEl.addEventListener("click", function () {
    closePanel();
  });

  panelEl.addEventListener("click", function (event) {
    const target = event && event.target ? event.target : null;
    if (!target) return;
    if (target === emojiToggleEl) return;
    if (target.closest && target.closest("#tenantChatEmojiPanel")) return;
    if (target.classList && target.classList.contains("tenant-chat-emoji")) return;
    if (!emojiPanelEl.hidden) closeEmojiPanel();
  });

  document.addEventListener("visibilitychange", function () {
    if (document.visibilityState === "hidden") return;
    loadMessages(false);
  });

  const shouldOpen = readOpenPreference();
  widgetEl.hidden = false;
  panelEl.hidden = true;
  launcherEl.classList.remove("is-open");
  launcherEl.setAttribute("aria-expanded", "false");
  open = false;
  setUnread(0);
  startPolling();

  if (shouldOpen) {
    openPanel();
  } else {
    loadMessages(false);
  }
})();
</script>

</body>
</html>
