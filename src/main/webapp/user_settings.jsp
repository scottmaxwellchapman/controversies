<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ page import="net.familylawandprobate.controversies.assembly_identity_tokens" %>
<%@ page import="net.familylawandprobate.controversies.profile_assets" %>
<%@ page import="net.familylawandprobate.controversies.users_roles" %>
<%@ include file="security.jspf" %>

<%
  if (!require_login()) return;
%>

<%!
  private static String usSafe(String s) { return s == null ? "" : s; }

  private static String usEsc(String s) {
    if (s == null) return "";
    return s.replace("&","&amp;")
            .replace("<","&lt;")
            .replace(">","&gt;")
            .replace("\"","&quot;")
            .replace("'","&#39;");
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
        Object t = sess.getAttribute("CSRF_TOKEN");
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
  String ctx = request.getContextPath();
  if (ctx == null) ctx = "";

  String tenantUuid = usSafe((String)session.getAttribute("tenant.uuid")).trim();
  String userUuid = usSafe((String)session.getAttribute(users_roles.S_USER_UUID)).trim();
  String userEmail = usSafe((String)session.getAttribute(users_roles.S_USER_EMAIL)).trim();

  if (tenantUuid.isBlank() || userUuid.isBlank() || userEmail.isBlank()) {
    response.sendRedirect(ctx + "/tenant_login.jsp");
    return;
  }

  String csrfToken = csrfForRender(request);
  String assetStatus = usSafe(request.getParameter("asset_status")).trim().toLowerCase();
  String assetError = usSafe(request.getParameter("asset_error")).trim();
  String photoMessage = "";
  String photoError = "";
  if (!assetError.isBlank()) photoError = assetError;
  else if ("user_photo_saved".equals(assetStatus)) photoMessage = "User photo saved.";
  else if ("user_photo_cleared".equals(assetStatus)) photoMessage = "User photo removed.";

  profile_assets.AssetRec userPhoto = null;
  try { userPhoto = profile_assets.defaultStore().readUserPhoto(tenantUuid, userUuid); } catch (Exception ignored) {}
  String userPhotoUrl = assembly_identity_tokens.userPhotoUrl(ctx, userUuid);

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
        <h2 style="margin:0;">Profile Photo</h2>
        <div class="meta">Available as form token <code>{{user.photo_url}}</code> and downloaded/stored by Controversies.</div>
      </div>
    </div>

    <% if (!photoMessage.isBlank()) { %>
      <div class="alert alert-ok" style="margin-top:10px;"><%= usEsc(photoMessage) %></div>
    <% } %>
    <% if (!photoError.isBlank()) { %>
      <div class="alert alert-error" style="margin-top:10px;"><%= usEsc(photoError) %></div>
    <% } %>

    <div style="display:grid; grid-template-columns:minmax(180px, 240px) minmax(0, 1fr); gap:12px; align-items:start;">
      <div style="border:1px solid var(--line); border-radius:12px; min-height:180px; display:flex; align-items:center; justify-content:center; background:var(--surface-2); padding:10px;">
        <% if (userPhoto != null) { %>
          <img src="<%= usEsc(userPhotoUrl) %>&t=<%= System.currentTimeMillis() %>" alt="Profile photo" style="max-width:100%; max-height:170px; object-fit:contain;" />
        <% } else { %>
          <div class="meta">No profile photo uploaded.</div>
        <% } %>
      </div>

      <div style="display:grid; gap:10px;">
        <% if (userPhoto != null) { %>
          <div class="meta">Current photo: <strong><%= usEsc(usSafe(userPhoto.fileName)) %></strong> (<%= usEsc(usSafe(userPhoto.mimeType)) %>, <%= userPhoto.sizeBytes %> bytes)</div>
        <% } %>

        <form class="form" method="post" action="<%= ctx %>/profile_assets" enctype="multipart/form-data">
          <input type="hidden" name="csrfToken" value="<%= usEsc(csrfToken) %>" />
          <input type="hidden" name="action" value="upload_user_photo" />
          <input type="hidden" name="next" value="/user_settings.jsp" />
          <label>
            <span>Upload photo (PNG/JPG/GIF, max <%= profile_assets.MAX_IMAGE_BYTES %> bytes)</span>
            <input type="file" name="file" accept="image/png,image/jpeg,image/gif" required />
          </label>
          <div class="actions"><button class="btn" type="submit">Upload Photo</button></div>
        </form>

        <form class="form" method="post" action="<%= ctx %>/profile_assets">
          <input type="hidden" name="csrfToken" value="<%= usEsc(csrfToken) %>" />
          <input type="hidden" name="action" value="import_user_photo_url" />
          <input type="hidden" name="next" value="/user_settings.jsp" />
          <label>
            <span>Or import photo from URL</span>
            <input type="url" name="source_url" placeholder="https://example.com/photo.jpg" required />
          </label>
          <div class="actions"><button class="btn btn-ghost" type="submit">Import Photo URL</button></div>
        </form>

        <form class="form" method="post" action="<%= ctx %>/profile_assets">
          <input type="hidden" name="csrfToken" value="<%= usEsc(csrfToken) %>" />
          <input type="hidden" name="action" value="clear_user_photo" />
          <input type="hidden" name="next" value="/user_settings.jsp" />
          <button class="btn btn-ghost" type="submit" <%= userPhoto == null ? "disabled" : "" %>>Remove Photo</button>
        </form>
      </div>
    </div>
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
