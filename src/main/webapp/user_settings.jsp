<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ page import="net.familylawandprobate.controversies.users_roles" %>
<%@ include file="security.jspf" %>

<%
  if (!require_login()) return;
%>

<%!
  private static String usSafe(String s) { return s == null ? "" : s; }
%>

<%
  String ctx = request.getContextPath();
  if (ctx == null) ctx = "";

  String tenantUuid = usSafe((String)session.getAttribute("tenant.uuid")).trim();
  String userUuid = usSafe((String)session.getAttribute(users_roles.S_USER_UUID)).trim();
  String userEmail = usSafe((String)session.getAttribute(users_roles.S_USER_EMAIL)).trim();

  if (tenantUuid.isBlank() || userUuid.isBlank() || userEmail.isBlank()) {
    response.sendRedirect(ctx + "/tenant_login.jsp");
    return;
  }

  request.setAttribute("activeNav", "/user_settings.jsp");
%>

<jsp:include page="header.jsp" />

<div class="container main">
  <section class="card narrow">
    <div class="section-head">
      <div>
        <h1>User Settings</h1>
        <div class="meta">Display preferences are saved per user for this tenant.</div>
      </div>
    </div>

    <form id="userThemeSettingsForm" class="form" autocomplete="off">
      <div class="pref-grid">
        <label>
          <span>Theme Mode</span>
          <select id="usThemeMode" name="theme_mode">
            <option value="auto">Auto (time + sunrise/sunset)</option>
            <option value="light">Light</option>
            <option value="dark">Dark</option>
          </select>
        </label>

        <label>
          <span>Theme Palette</span>
          <select id="usThemeVariant" name="theme_variant">
            <option value="default">Default</option>
            <option value="macos">Mac OS Colors (Experimental)</option>
            <option value="sunset">Sunset (Experimental)</option>
            <option value="graphite">Graphite (Experimental)</option>
          </select>
        </label>

        <label>
          <span>Text Size</span>
          <select id="usTextSize" name="text_size">
            <option value="sm">Smaller</option>
            <option value="md">Normal</option>
            <option value="lg">Larger</option>
            <option value="xl">Largest</option>
          </select>
        </label>
      </div>

      <div class="actions">
        <button class="btn" type="submit">Save Preferences</button>
        <button class="btn btn-ghost" type="button" id="usThemeReset">Reset to Tenant Defaults</button>
      </div>
    </form>

    <div id="usThemeMessage" class="alert alert-ok" style="display:none; margin-top:12px;"></div>
  </section>

  <section class="card narrow" style="margin-top:12px;">
    <div class="section-head">
      <div>
        <h2 style="margin:0;">Account Access</h2>
        <div class="meta">Manage your sign-in email and password.</div>
      </div>
    </div>

    <div class="quick-links">
      <a class="quick-link-card" href="<%= ctx %>/change_email.jsp">
        <span class="quick-link-title">Change E-Mail Address</span>
        <span class="quick-link-meta">Verify and update the email used for login.</span>
      </a>
      <a class="quick-link-card" href="<%= ctx %>/change_password.jsp">
        <span class="quick-link-title">Change Password</span>
        <span class="quick-link-meta">Set a new password for your current tenant account.</span>
      </a>
    </div>
  </section>
</div>

<script>
(() => {
  const root = document.documentElement;
  const configEl = document.getElementById("uiThemeConfig");
  const form = document.getElementById("userThemeSettingsForm");
  const modeEl = document.getElementById("usThemeMode");
  const variantEl = document.getElementById("usThemeVariant");
  const sizeEl = document.getElementById("usTextSize");
  const resetBtn = document.getElementById("usThemeReset");
  const messageEl = document.getElementById("usThemeMessage");
  if (!root || !configEl || !form || !modeEl || !variantEl || !sizeEl) return;

  const scope = String(configEl.getAttribute("data-pref-scope") || configEl.getAttribute("data-tenant-scope") || "public");
  const modeDefault = normalizeMode(String(configEl.getAttribute("data-theme-default") || "auto"));
  const textDefault = normalizeSize(String(configEl.getAttribute("data-text-size-default") || "md"));
  const variantDefault = "default";

  const modeKey = "ui.theme.mode." + scope;
  const textSizeKey = "ui.text.size." + scope;
  const variantKey = "ui.theme.variant." + scope;
  const activeThemeKey = "ui.theme.active." + scope;

  hydrate();

  form.addEventListener("submit", (event) => {
    event.preventDefault();
    const mode = normalizeMode(modeEl.value);
    const variant = normalizeVariant(variantEl.value);
    const size = normalizeSize(sizeEl.value);

    writeStorage(modeKey, mode);
    writeStorage(variantKey, variant);
    writeStorage(textSizeKey, size);

    applyVariant(variant);
    applyTextSize(size);
    if (mode === "light" || mode === "dark") {
      applyTheme(mode);
    } else {
      removeStorage(activeThemeKey);
    }

    showMessage("Display preferences saved.");
  });

  if (resetBtn) {
    resetBtn.addEventListener("click", () => {
      writeStorage(modeKey, modeDefault);
      writeStorage(variantKey, variantDefault);
      writeStorage(textSizeKey, textDefault);

      modeEl.value = modeDefault;
      variantEl.value = variantDefault;
      sizeEl.value = textDefault;

      applyVariant(variantDefault);
      applyTextSize(textDefault);
      if (modeDefault === "light" || modeDefault === "dark") {
        applyTheme(modeDefault);
      } else {
        removeStorage(activeThemeKey);
      }

      showMessage("Display preferences reset to tenant defaults.");
    });
  }

  function hydrate() {
    const mode = normalizeMode(readStorage(modeKey) || modeDefault);
    const variant = normalizeVariant(readStorage(variantKey) || variantDefault);
    const size = normalizeSize(readStorage(textSizeKey) || textDefault);

    modeEl.value = mode;
    variantEl.value = variant;
    sizeEl.value = size;
  }

  function applyTheme(mode) {
    const t = mode === "dark" ? "dark" : "light";
    root.setAttribute("data-theme", t);
    writeStorage(activeThemeKey, t);
  }

  function applyVariant(variant) {
    root.setAttribute("data-theme-variant", normalizeVariant(variant));
  }

  function applyTextSize(size) {
    root.setAttribute("data-text-size", normalizeSize(size));
  }

  function showMessage(message) {
    if (!messageEl) return;
    messageEl.textContent = message;
    messageEl.style.display = "block";
  }

  function normalizeMode(raw) {
    const v = String(raw || "").trim().toLowerCase();
    return (v === "auto" || v === "light" || v === "dark") ? v : "auto";
  }

  function normalizeVariant(raw) {
    const v = String(raw || "").trim().toLowerCase();
    return (v === "default" || v === "macos" || v === "sunset" || v === "graphite") ? v : "default";
  }

  function normalizeSize(raw) {
    const v = String(raw || "").trim().toLowerCase();
    return (v === "sm" || v === "md" || v === "lg" || v === "xl") ? v : "md";
  }

  function readStorage(key) {
    try { return window.localStorage.getItem(key); } catch (ignored) { return null; }
  }

  function writeStorage(key, value) {
    try { window.localStorage.setItem(key, String(value == null ? "" : value)); } catch (ignored) {}
  }

  function removeStorage(key) {
    try { window.localStorage.removeItem(key); } catch (ignored) {}
  }
})();
</script>

<jsp:include page="footer.jsp" />
