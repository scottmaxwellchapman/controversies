<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ page import="java.io.InputStream" %>
<%@ page import="java.io.ByteArrayInputStream" %>
<%@ page import="java.net.URLEncoder" %>
<%@ page import="java.nio.charset.StandardCharsets" %>
<%@ page import="java.nio.file.Files,java.nio.file.Path,java.nio.file.Paths" %>
<%@ page import="java.util.ArrayList,java.util.LinkedHashMap,java.util.List,java.util.Locale" %>
<%@ page import="javax.xml.XMLConstants" %>
<%@ page import="javax.xml.parsers.DocumentBuilder,javax.xml.parsers.DocumentBuilderFactory" %>
<%@ page import="org.w3c.dom.Document,org.w3c.dom.Element,org.w3c.dom.Node,org.w3c.dom.NodeList" %>

<%@ page import="net.familylawandprobate.controversies.users_roles" %>
<%@ page import="net.familylawandprobate.controversies.tenant_settings" %>
<%@ page import="net.familylawandprobate.controversies.custom_objects" %>

<%!
    // -----------------------------
    // Menu XML parsing + safety
    // -----------------------------
    private static final class MenuNode {
        final String label;
        final String href; // optional when acting as a pure submenu label
        final List<MenuNode> children = new ArrayList<>();
        MenuNode(String label, String href) {
            this.label = label;
            this.href = href;
        }
    }

    private static final class MenuGroup {
        final String label; // empty means "ungrouped"
        final List<MenuNode> items = new ArrayList<>();
        MenuGroup(String label) { this.label = label; }
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&","&amp;")
                .replace("<","&lt;")
                .replace(">","&gt;")
                .replace("\"","&quot;")
                .replace("'","&#39;");
    }

    private static String normalizeHref(String href) {
        String h = safe(href).trim();
        if (h.isBlank()) return "";
        if (!h.startsWith("/")) h = "/" + h;
        return h;
    }

    private static DocumentBuilder secureBuilder() throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false);
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);

        // XXE hardening
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

        DocumentBuilder b = f.newDocumentBuilder();
        b.setEntityResolver((publicId, systemId) ->
                new org.xml.sax.InputSource(new java.io.StringReader("")));
        return b;
    }

    private static MenuNode parseMenuNode(Element el) {
        if (el == null) return null;
        String label = safe(el.getAttribute("label")).trim();
        String href = normalizeHref(el.getAttribute("href"));
        MenuNode node = new MenuNode(label, href);

        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (!(n instanceof Element)) continue;
            Element childEl = (Element) n;
            if (!"item".equals(childEl.getTagName())) continue;
            MenuNode child = parseMenuNode(childEl);
            if (child != null) node.children.add(child);
        }

        if (node.label.isBlank()) return null;
        if (node.href.isBlank() && node.children.isEmpty()) return null;
        return node;
    }

    private static void parseDirectItems(Element parent, List<MenuNode> out) {
        if (parent == null || out == null) return;
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (!(n instanceof Element)) continue;
            Element el = (Element) n;
            if (!"item".equals(el.getTagName())) continue;
            MenuNode node = parseMenuNode(el);
            if (node != null) out.add(node);
        }
    }

    private static List<MenuGroup> parseMenuXml(InputStream in) throws Exception {
        DocumentBuilder b = secureBuilder();
        Document d = b.parse(in);
        d.getDocumentElement().normalize();

        Element root = d.getDocumentElement();
        List<MenuGroup> groups = new ArrayList<>();
        MenuGroup ungrouped = new MenuGroup("");

        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (!(n instanceof Element)) continue;
            Element el = (Element) n;

            String tag = el.getTagName();
            if ("item".equals(tag)) {
                MenuNode node = parseMenuNode(el);
                if (node != null) ungrouped.items.add(node);
            } else if ("group".equals(tag)) {
                String glabel = safe(el.getAttribute("label")).trim();
                MenuGroup g = new MenuGroup(glabel);
                parseDirectItems(el, g.items);
                if (!g.items.isEmpty()) groups.add(g);
            }
        }

        if (!ungrouped.items.isEmpty()) groups.add(0, ungrouped);
        return groups;
    }

    private static List<MenuGroup> parseMenuXmlBytes(byte[] bytes) throws Exception {
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            return parseMenuXml(in);
        }
    }

    private static List<MenuGroup> defaultMenu() {
        MenuGroup g = new MenuGroup("");
        g.items.add(new MenuNode("Home", "/index.jsp"));
        g.items.add(new MenuNode("Cases", "/cases.jsp"));
        g.items.add(new MenuNode("Texas Law", "/texas_law.jsp"));
        MenuNode settings = new MenuNode("Settings", "");
        settings.children.add(new MenuNode("Users & Security", "/users_roles.jsp"));
        g.items.add(settings);
        g.items.add(new MenuNode("Tenant Fields", "/tenant_fields.jsp"));
        g.items.add(new MenuNode("Assemble a Form", "/forms.jsp"));
        g.items.add(new MenuNode("View the Assembled Forms", "/assembled_forms.jsp"));
        g.items.add(new MenuNode("Template Library", "/template_library.jsp"));
        g.items.add(new MenuNode("Template Editor", "/template_editor.jsp"));
        MenuNode help = new MenuNode("Help", "");
        help.children.add(new MenuNode("Help Center", "/help_center.jsp"));
        help.children.add(new MenuNode("Getting Started (Legal Team)", "/help_getting_started.jsp"));
        help.children.add(new MenuNode("Case Workflow Guide", "/help_case_workflow.jsp"));
        help.children.add(new MenuNode("Form Assembly Guide", "/help_form_assembly.jsp"));
        help.children.add(new MenuNode("Token Guide", "/token_guide.jsp"));
        help.children.add(new MenuNode("Markup Notation", "/markup_notation.jsp"));
        g.items.add(help);
        return java.util.List.of(g);
    }

    private static boolean isActive(String requestUri, String ctx, String href) {
        if (requestUri == null || href == null) return false;
        String h = normalizeHref(href);
        if (h.isBlank()) return false;

        if (ctx != null && !ctx.isBlank()) {
            String target = ctx + h;
            return requestUri.equals(target) || requestUri.endsWith(h);
        }
        return requestUri.equals(h) || requestUri.endsWith(h);
    }

    private static boolean isHrefActive(String requestUri, String ctx, String activeNav, String href) {
        String h = normalizeHref(href);
        if (h.isBlank()) return false;
        if (activeNav != null && !activeNav.isBlank()) return activeNav.equalsIgnoreCase(h);
        return isActive(requestUri, ctx, h);
    }

    private static boolean isNodeActive(String requestUri, String ctx, String activeNav, MenuNode node) {
        if (node == null) return false;
        if (isHrefActive(requestUri, ctx, activeNav, node.href)) return true;
        for (int i = 0; i < node.children.size(); i++) {
            if (isNodeActive(requestUri, ctx, activeNav, node.children.get(i))) return true;
        }
        return false;
    }

    private static void renderMenuNode(jakarta.servlet.jsp.JspWriter out,
                                       MenuNode node,
                                       String requestUri,
                                       String ctx,
                                       String activeNav,
                                       int depth) throws java.io.IOException {
        if (out == null || node == null) return;

        String label = safe(node.label).trim();
        String href = normalizeHref(node.href);
        if (label.isBlank()) return;

        boolean hasChildren = node.children != null && !node.children.isEmpty();
        boolean selfActive = isHrefActive(requestUri, ctx, activeNav, href);
        boolean branchActive = isNodeActive(requestUri, ctx, activeNav, node);
        String depthClass = "depth-" + Math.max(0, depth);

        if (hasChildren) {
            out.write("<details class=\"dropdown-submenu " + depthClass + "\"" + (branchActive ? " open" : "") + ">");
            out.write("<summary class=\"dropdown-submenu-trigger\">");
            if (!href.isBlank()) {
                String full = safe(ctx) + href;
                out.write("<a class=\"dropdown-submenu-link " + (selfActive ? "is-active" : "") + "\" role=\"menuitem\" href=\"" + esc(full) + "\" onclick=\"event.stopPropagation();\">");
                out.write("<span class=\"dropdown-item-label\">" + esc(label) + "</span>");
                out.write("</a>");
            } else {
                out.write("<span class=\"dropdown-submenu-label\">" + esc(label) + "</span>");
            }
            out.write("<span class=\"dropdown-submenu-caret\" aria-hidden=\"true\">▸</span>");
            out.write("</summary>");
            out.write("<div class=\"dropdown-submenu-items\">");
            for (int i = 0; i < node.children.size(); i++) {
                renderMenuNode(out, node.children.get(i), requestUri, ctx, activeNav, depth + 1);
            }
            out.write("</div>");
            out.write("</details>");
            return;
        }

        if (href.isBlank()) {
            out.write("<div class=\"dropdown-item dropdown-item-label-only " + depthClass + "\">");
            out.write("<span class=\"dropdown-item-label\">" + esc(label) + "</span>");
            out.write("</div>");
            return;
        }

        String full = safe(ctx) + href;
        out.write("<a class=\"dropdown-item " + depthClass + " " + (selfActive ? "is-active" : "") + "\" role=\"menuitem\" href=\"" + esc(full) + "\">");
        out.write("<span class=\"dropdown-item-label\">" + esc(label) + "</span>");
        out.write("</a>");
    }

    private static String uiThemeMode(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (!"light".equals(s) && !"dark".equals(s) && !"auto".equals(s)) return "auto";
        return s;
    }

    private static String uiTextSize(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (!"sm".equals(s) && !"md".equals(s) && !"lg".equals(s) && !"xl".equals(s)) return "md";
        return s;
    }

    private static String uiHour(String raw, int fallback) {
        try {
            int v = Integer.parseInt(raw == null ? "" : raw.trim());
            if (v < 0 || v > 23) return String.valueOf(fallback);
            return String.valueOf(v);
        } catch (Exception ignored) {
            return String.valueOf(fallback);
        }
    }

    private static String uiDecimalOrBlank(String raw, double min, double max) {
        String s = raw == null ? "" : raw.trim();
        if (s.isBlank()) return "";
        try {
            double v = Double.parseDouble(s);
            if (v < min || v > max) return "";
            return String.valueOf(v);
        } catch (Exception ignored) {
            return "";
        }
    }
%>

<%
    // -----------------------------------------
    // Menu cache (application-scope) — SAFE
    // Cache RAW BYTES only (never JSP classes)
    // -----------------------------------------
    final String MENU_CACHE_BYTES = "header.menu.xml.bytes";
    final String MENU_CACHE_MTIME = "header.menu.xml.mtime";

    List<MenuGroup> menuGroups = null;

    // New location: data/menu.xml (relative to working directory)
    Path menuFile = Paths.get("src","main","webapp", "menu.xml").toAbsolutePath();

    try {
        if (Files.exists(menuFile)) {
            long mtime = Files.getLastModifiedTime(menuFile).toMillis();

            Long cachedMtime = (Long) application.getAttribute(MENU_CACHE_MTIME);
            byte[] cachedBytes = (byte[]) application.getAttribute(MENU_CACHE_BYTES);

            byte[] bytes;
            if (cachedBytes != null && cachedMtime != null && cachedMtime.longValue() == mtime) {
                bytes = cachedBytes;
            } else {
                bytes = Files.readAllBytes(menuFile);
                application.setAttribute(MENU_CACHE_BYTES, bytes);
                application.setAttribute(MENU_CACHE_MTIME, Long.valueOf(mtime));
            }

            menuGroups = parseMenuXmlBytes(bytes);
        }

        if (menuGroups == null || menuGroups.isEmpty()) {
            menuGroups = defaultMenu();
        }

    } catch (Exception e) {
        menuGroups = defaultMenu();
    }

    String ctx = request.getContextPath();
    String uri = request.getRequestURI();

    // Optional: page can set request.setAttribute("activeNav", "/index.jsp") etc.
    String activeNav = null;
    Object _activeObj = request.getAttribute("activeNav");
    if (_activeObj instanceof String) activeNav = (String) _activeObj;

    // --------------------------------------------------------------------
    // Login state (tenant login + user login)
    // NOTE: security.jspf enforces bindings; header only renders UI state.
    // --------------------------------------------------------------------
    String tenantUuid  = (String) session.getAttribute("tenant.uuid");
    String tenantLabel = (String) session.getAttribute("tenant.label");

    String userUuid  = (String) session.getAttribute(users_roles.S_USER_UUID);
    String userEmail = (String) session.getAttribute(users_roles.S_USER_EMAIL);
    String roleLabel = (String) session.getAttribute(users_roles.S_ROLE_LABEL); // if you store it

    // Back-compat: some older pages might still set session "username"
    Object _hdrUserObj = session.getAttribute("username");
    if ((userEmail == null || userEmail.isBlank()) && (_hdrUserObj instanceof String)) {
        userEmail = (String) _hdrUserObj;
    }

    boolean tenantLoggedIn =
            tenantUuid != null && !tenantUuid.isBlank() &&
            tenantLabel != null && !tenantLabel.isBlank();

    boolean userLoggedIn =
            userUuid != null && !userUuid.isBlank() &&
            userEmail != null && !userEmail.isBlank();
    boolean navVisible = tenantLoggedIn && userLoggedIn;

    if (navVisible && tenantUuid != null && !tenantUuid.isBlank()) {
        try {
            List<custom_objects.ObjectRec> publishedObjects = custom_objects.defaultStore().listPublished(tenantUuid);
            if (publishedObjects != null && !publishedObjects.isEmpty()) {
                MenuNode customRoot = new MenuNode("Custom Objects", "");
                for (int i = 0; i < publishedObjects.size(); i++) {
                    custom_objects.ObjectRec r = publishedObjects.get(i);
                    if (r == null) continue;
                    String objectUuid = safe(r.uuid).trim();
                    if (objectUuid.isBlank()) continue;
                    String label = safe(r.pluralLabel).trim();
                    if (label.isBlank()) label = safe(r.label).trim();
                    if (label.isBlank()) label = "Object";
                    String href = "/custom_object_records.jsp?object_uuid=" + URLEncoder.encode(objectUuid, StandardCharsets.UTF_8);
                    customRoot.children.add(new MenuNode(label, href));
                }

                if (!customRoot.children.isEmpty()) {
                    if (menuGroups == null) menuGroups = new ArrayList<MenuGroup>();
                    MenuGroup topGroup;
                    if (menuGroups.isEmpty()) {
                        topGroup = new MenuGroup("");
                        menuGroups.add(topGroup);
                    } else {
                        topGroup = menuGroups.get(0);
                    }
                    topGroup.items.add(customRoot);
                }
            }
        } catch (Exception ignored) {}
    }

    String uiThemeDefaultMode = "auto";
    String uiThemeUseLocation = "false";
    String uiThemeLatitude = "";
    String uiThemeLongitude = "";
    String uiThemeLightHour = "7";
    String uiThemeDarkHour = "19";
    String uiThemeTextSizeDefault = "md";
    if (tenantLoggedIn) {
        try {
            LinkedHashMap<String, String> uiCfg = tenant_settings.defaultStore().read(tenantUuid);
            uiThemeDefaultMode = uiThemeMode(uiCfg.get("theme_mode_default"));
            uiThemeUseLocation = "true".equalsIgnoreCase((uiCfg.get("theme_use_location") == null ? "" : uiCfg.get("theme_use_location").trim())) ? "true" : "false";
            uiThemeLatitude = uiDecimalOrBlank(uiCfg.get("theme_latitude"), -90.0, 90.0);
            uiThemeLongitude = uiDecimalOrBlank(uiCfg.get("theme_longitude"), -180.0, 180.0);
            uiThemeLightHour = uiHour(uiCfg.get("theme_light_start_hour"), 7);
            uiThemeDarkHour = uiHour(uiCfg.get("theme_dark_start_hour"), 19);
            uiThemeTextSizeDefault = uiTextSize(uiCfg.get("theme_text_size_default"));
        } catch (Exception ignored) {}
    }

    // Build safe `next` (path-only, no scheme/host) for login pages
    String currentPath = uri;
    if (currentPath == null || currentPath.isBlank()) currentPath = "/index.jsp";
    if (ctx != null && !ctx.isBlank() && currentPath.startsWith(ctx)) {
        currentPath = currentPath.substring(ctx.length());
        if (currentPath == null || currentPath.isBlank()) currentPath = "/index.jsp";
    }
    if (!currentPath.startsWith("/")) currentPath = "/index.jsp";

    String nextEnc = URLEncoder.encode(currentPath, StandardCharsets.UTF_8);

    String loginHref = ctx + "/tenant_login.jsp?next=" + nextEnc;

    // Keep one logout endpoint (recommended: make /logout.jsp clear BOTH tenant+user)
    String logoutHref = ctx + "/logout.jsp";
%>

<!doctype html>
<html lang="en">
<head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1" />
    <title>Controversies</title>

    <link rel="preconnect" href="https://fonts.googleapis.com">
    <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
    <link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&display=swap" rel="stylesheet">

    <link rel="stylesheet" href="<%= ctx %>/style.css" />
</head>
<body>

<header class="site-header">
    <div class="container header-row" style="padding:0.45rem 0; gap:0.75rem;">
        <div class="header-left" style="display:flex; align-items:center; gap:0.6rem; flex-wrap:wrap;">

            <% if (navVisible) { %>
                <details class="dropdown nav-dropdown">
                    <summary class="dropdown-trigger btn-sm" aria-label="Open navigation menu">
                        <span class="dropdown-trigger-label">
                            <span class="brand-link">Controversies</span>
                        </span>
                        <span class="chevron" aria-hidden="true">▾</span>
                    </summary>

                    <div class="dropdown-panel" role="menu" aria-label="Site navigation">
                        <%
                            for (MenuGroup g : menuGroups) {
                                boolean showTitle = (g.label != null && !g.label.isBlank());
                        %>
                            <div class="dropdown-group">
                                <% if (showTitle) { %>
                                    <div class="dropdown-title"><%= esc(g.label) %></div>
                                <% } %>

                                <div class="dropdown-items">
                                    <%
                                        for (MenuNode it : g.items) {
                                            renderMenuNode(out, it, uri, ctx, activeNav, 0);
                                    %>
                                    <%
                                        }
                                    %>
                                </div>
                            </div>
                        <%
                            }
                        %>
                    </div>
                </details>
            <% } else { %>
                <span class="brand-link" aria-label="Controversies">Controversies</span>
            <% } %>

            <div class="header-ui-controls" aria-label="Display controls">
                <button type="button" id="uiThemeToggle" class="btn btn-ghost btn-sm header-ui-btn" title="Cycle theme mode: Auto, Dark, Light">
                    Theme: Auto
                </button>
                <button type="button" id="uiTextSmaller" class="btn btn-ghost btn-sm header-ui-btn" title="Text smaller">A-</button>
                <button type="button" id="uiTextBigger" class="btn btn-ghost btn-sm header-ui-btn" title="Text bigger">A+</button>
            </div>

            <!-- =========================
                 Login / Logout UI
                 ========================= -->
            <% if (tenantLoggedIn) { %>
                <div class="user-chip" title="Tenant">
                    <span class="user-chip-label">Tenant</span>
                    <span class="user-chip-name"><%= esc(tenantLabel) %></span>
                </div>
            <% } %>

            <% if (userLoggedIn) { %>
                <div class="user-chip" title="Signed in user">
                    <span class="user-chip-label">User</span>
                    <span class="user-chip-name"><%= esc(userEmail) %></span>
                </div>
                <% if (roleLabel != null && !roleLabel.isBlank()) { %>
                    <div class="user-chip" title="Role">
                        <span class="user-chip-label">Role</span>
                        <span class="user-chip-name"><%= esc(roleLabel) %></span>
                    </div>
                <% } %>
                <a class="btn btn-ghost btn-sm" href="<%= logoutHref %>">Logout</a>

            <% } else { %>
                <a class="btn btn-sm" href="<%= loginHref %>">Sign In</a>
            <% } %>

        </div>
    </div>
</header>

<div id="uiThemeConfig"
     style="display:none;"
     data-tenant-scope="<%= esc((tenantUuid == null || tenantUuid.isBlank()) ? "public" : tenantUuid) %>"
     data-theme-default="<%= esc(uiThemeDefaultMode) %>"
     data-theme-use-location="<%= esc(uiThemeUseLocation) %>"
     data-theme-latitude="<%= esc(uiThemeLatitude) %>"
     data-theme-longitude="<%= esc(uiThemeLongitude) %>"
     data-theme-light-hour="<%= esc(uiThemeLightHour) %>"
     data-theme-dark-hour="<%= esc(uiThemeDarkHour) %>"
     data-text-size-default="<%= esc(uiThemeTextSizeDefault) %>"></div>

<script>
(() => {
    const root = document.documentElement;
    const configEl = document.getElementById("uiThemeConfig");
    const btnTheme = document.getElementById("uiThemeToggle");
    const btnSmaller = document.getElementById("uiTextSmaller");
    const btnBigger = document.getElementById("uiTextBigger");
    if (!root || !configEl) return;

    const scope = String(configEl.getAttribute("data-tenant-scope") || "public");
    const modeDefaultRaw = String(configEl.getAttribute("data-theme-default") || "auto").toLowerCase();
    const useLocationDefault = String(configEl.getAttribute("data-theme-use-location") || "true").toLowerCase() === "true";
    const latDefaultRaw = String(configEl.getAttribute("data-theme-latitude") || "").trim();
    const lonDefaultRaw = String(configEl.getAttribute("data-theme-longitude") || "").trim();
    const lightHourDefault = parseHour(configEl.getAttribute("data-theme-light-hour"), 7);
    const darkHourDefault = parseHour(configEl.getAttribute("data-theme-dark-hour"), 19);
    const textDefaultRaw = String(configEl.getAttribute("data-text-size-default") || "md").toLowerCase();

    const MODE_KEY = "ui.theme.mode." + scope;
    const ACTIVE_THEME_KEY = "ui.theme.active." + scope;
    const GEO_CACHE_KEY = "ui.theme.geo.coords." + scope;
    const GEO_BLOCKED_KEY = "ui.theme.geo.blocked." + scope;
    const GEO_BLOCKED_TTL_MS = 6 * 60 * 60 * 1000;
    const TEXT_SIZE_KEY = "ui.text.size." + scope;
    const SIZE_STEPS = ["sm", "md", "lg", "xl"];
    const MODE_STEPS = ["auto", "dark", "light"];

    let activeMode = normalizeMode(readStorage(MODE_KEY) || modeDefaultRaw || "auto");
    let activeTextSize = normalizeTextSize(readStorage(TEXT_SIZE_KEY) || textDefaultRaw || "md");
    let autoTimer = null;
    let pendingGeo = false;
    let fullAutoResolvedOnce = false;

    applyTextSize(activeTextSize);
    updateTextButtons();

    if (activeMode === "auto") applyAutoTheme();
    else applyTheme(activeMode);

    updateThemeButton();

    if (btnTheme) {
        btnTheme.addEventListener("click", function () {
            const next = nextThemeMode(activeMode);
            activeMode = next;
            safeWriteStorage(MODE_KEY, next);
            if (next === "auto") {
                applyAutoTheme(true);
            } else {
                clearAutoTimer();
                applyTheme(next);
            }
            updateThemeButton();
        });
    }

    if (btnSmaller) {
        btnSmaller.addEventListener("click", function () {
            changeTextSize(-1);
        });
    }
    if (btnBigger) {
        btnBigger.addEventListener("click", function () {
            changeTextSize(1);
        });
    }

    document.addEventListener("visibilitychange", function () {
        if (activeMode !== "auto") return;
        if (document.hidden) return;
        resolveAutoTheme(false).then((theme) => {
            applyTheme(theme);
        });
    });

    window.addEventListener("focus", function () {
        if (activeMode !== "auto") return;
        resolveAutoTheme(false).then((theme) => {
            applyTheme(theme);
        });
    });

    function normalizeMode(raw) {
        const v = String(raw || "").trim().toLowerCase();
        return (v === "light" || v === "dark" || v === "auto") ? v : "auto";
    }

    function normalizeTextSize(raw) {
        const v = String(raw || "").trim().toLowerCase();
        return SIZE_STEPS.indexOf(v) >= 0 ? v : "md";
    }

    function parseHour(raw, fallback) {
        const n = Number(String(raw || "").trim());
        if (!isFinite(n)) return fallback;
        const i = Math.round(n);
        if (i < 0 || i > 23) return fallback;
        return i;
    }

    function nextThemeMode(current) {
        const idx = MODE_STEPS.indexOf(current);
        if (idx < 0) return "auto";
        return MODE_STEPS[(idx + 1) % MODE_STEPS.length];
    }

    function changeTextSize(direction) {
        const idx = SIZE_STEPS.indexOf(activeTextSize);
        const current = idx < 0 ? 1 : idx;
        const next = Math.max(0, Math.min(SIZE_STEPS.length - 1, current + direction));
        const size = SIZE_STEPS[next];
        activeTextSize = size;
        applyTextSize(size);
        safeWriteStorage(TEXT_SIZE_KEY, size);
        updateTextButtons();
    }

    function applyTheme(theme) {
        const t = theme === "dark" ? "dark" : "light";
        root.setAttribute("data-theme", t);
        safeWriteStorage(ACTIVE_THEME_KEY, t);
        updateThemeButton();
    }

    function applyTextSize(size) {
        const s = normalizeTextSize(size);
        root.setAttribute("data-text-size", s);
    }

    function updateThemeButton() {
        if (!btnTheme) return;
        const activeTheme = String(root.getAttribute("data-theme") || readStorage(ACTIVE_THEME_KEY) || "light");
        let label = "";
        if (activeMode === "auto") {
            label = "Theme: Auto (" + (activeTheme === "dark" ? "Dark" : "Light") + ")";
            btnTheme.classList.add("is-auto");
        } else if (activeMode === "dark") {
            label = "Theme: Dark";
            btnTheme.classList.remove("is-auto");
        } else {
            label = "Theme: Light";
            btnTheme.classList.remove("is-auto");
        }
        btnTheme.textContent = label;
    }

    function updateTextButtons() {
        const idx = SIZE_STEPS.indexOf(activeTextSize);
        if (btnSmaller) btnSmaller.disabled = idx <= 0;
        if (btnBigger) btnBigger.disabled = idx >= SIZE_STEPS.length - 1;
    }

    function clearAutoTimer() {
        if (autoTimer) {
            clearInterval(autoTimer);
            autoTimer = null;
        }
    }

    function applyAutoTheme(forceGeoRefresh) {
        if (activeMode !== "auto") return;
        const now = new Date();
        const fallbackTheme = isDayByHours(now, lightHourDefault, darkHourDefault) ? "light" : "dark";
        applyTheme(fallbackTheme);

        clearAutoTimer();
        autoTimer = setInterval(() => {
            if (activeMode !== "auto") return;
            resolveAutoTheme(false).then((theme) => {
                applyTheme(theme);
            });
        }, 60 * 1000);

        resolveAutoTheme(!!forceGeoRefresh).then((theme) => {
            applyTheme(theme);
        });
    }

    function resolveAutoTheme(forceGeoRefresh) {
        const now = new Date();
        const fallbackTheme = isDayByHours(now, lightHourDefault, darkHourDefault) ? "light" : "dark";
        const configured = readConfiguredCoords();
        if (configured) {
            const bySun = themeFromSun(now, configured.lat, configured.lon);
            if (bySun) {
                fullAutoResolvedOnce = true;
                return Promise.resolve(bySun);
            }
        }

        const cached = readCachedCoords();
        if (cached && !forceGeoRefresh) {
            const bySun = themeFromSun(now, cached.lat, cached.lon);
            if (bySun) {
                fullAutoResolvedOnce = true;
                return Promise.resolve(bySun);
            }
        }

        if (!useLocationDefault) return Promise.resolve(fallbackTheme);
        if (pendingGeo) return Promise.resolve(fallbackTheme);
        if (isGeoBlockedRecently(forceGeoRefresh)) return Promise.resolve(fallbackTheme);
        if (!navigator.geolocation) return Promise.resolve(fallbackTheme);
        if (fullAutoResolvedOnce && !forceGeoRefresh) return Promise.resolve(fallbackTheme);

        pendingGeo = true;
        return requestBrowserCoords()
            .then((coords) => {
                pendingGeo = false;
                if (!coords) return fallbackTheme;
                writeCachedCoords(coords.lat, coords.lon);
                const bySun = themeFromSun(now, coords.lat, coords.lon);
                if (bySun) {
                    fullAutoResolvedOnce = true;
                    return bySun;
                }
                return fallbackTheme;
            })
            .catch(() => {
                pendingGeo = false;
                return fallbackTheme;
            });
    }

    function readConfiguredCoords() {
        const lat = Number(latDefaultRaw);
        const lon = Number(lonDefaultRaw);
        if (!isFinite(lat) || !isFinite(lon)) return null;
        if (lat < -90 || lat > 90 || lon < -180 || lon > 180) return null;
        return { lat: lat, lon: lon };
    }

    function readCachedCoords() {
        const raw = readStorage(GEO_CACHE_KEY);
        if (!raw) return null;
        try {
            const data = JSON.parse(raw);
            if (!data) return null;
            const lat = Number(data.lat);
            const lon = Number(data.lon);
            const ts = Number(data.ts || 0);
            if (!isFinite(lat) || !isFinite(lon)) return null;
            if (lat < -90 || lat > 90 || lon < -180 || lon > 180) return null;
            if (!isFinite(ts) || ts <= 0) return null;
            if ((Date.now() - ts) > (12 * 60 * 60 * 1000)) return null;
            return { lat: lat, lon: lon };
        } catch (ignored) {
            return null;
        }
    }

    function writeCachedCoords(lat, lon) {
        safeWriteStorage(GEO_CACHE_KEY, JSON.stringify({
            lat: lat,
            lon: lon,
            ts: Date.now()
        }));
    }

    function isGeoBlockedRecently(forceGeoRefresh) {
        if (forceGeoRefresh) return false;
        const raw = String(readStorage(GEO_BLOCKED_KEY) || "").trim();
        if (!raw) return false;
        const ts = Number(raw);
        if (!isFinite(ts) || ts <= 0) {
            safeWriteStorage(GEO_BLOCKED_KEY, "");
            return false;
        }
        if ((Date.now() - ts) > GEO_BLOCKED_TTL_MS) {
            safeWriteStorage(GEO_BLOCKED_KEY, "");
            return false;
        }
        return true;
    }

    function markGeoBlocked(isBlocked) {
        if (isBlocked) safeWriteStorage(GEO_BLOCKED_KEY, String(Date.now()));
        else safeWriteStorage(GEO_BLOCKED_KEY, "");
    }

    async function requestBrowserCoords() {
        try {
            if (navigator.permissions && navigator.permissions.query) {
                const perm = await navigator.permissions.query({ name: "geolocation" });
                if (perm && perm.state === "denied") {
                    markGeoBlocked(true);
                    return null;
                }
            }
        } catch (ignored) {}

        return new Promise((resolve) => {
            try {
                navigator.geolocation.getCurrentPosition(
                    (pos) => {
                        markGeoBlocked(false);
                        if (!pos || !pos.coords) {
                            resolve(null);
                            return;
                        }
                        resolve({ lat: Number(pos.coords.latitude), lon: Number(pos.coords.longitude) });
                    },
                    (err) => {
                        if (err && (err.code === 1 || String(err.message || "").toLowerCase().indexOf("denied") >= 0)) {
                            markGeoBlocked(true);
                        }
                        resolve(null);
                    },
                    { enableHighAccuracy: false, timeout: 5000, maximumAge: 4 * 60 * 60 * 1000 }
                );
            } catch (ignored) {
                resolve(null);
            }
        });
    }

    function isDayByHours(now, lightHour, darkHour) {
        const h = now.getHours() + (now.getMinutes() / 60) + (now.getSeconds() / 3600);
        if (lightHour === darkHour) return true;
        if (lightHour < darkHour) return h >= lightHour && h < darkHour;
        return !(h >= darkHour && h < lightHour);
    }

    function themeFromSun(now, lat, lon) {
        const sun = sunriseSunsetLocalHours(now, lat, lon);
        if (!sun) return null;
        const h = now.getHours() + (now.getMinutes() / 60) + (now.getSeconds() / 3600);
        if (sun.sunrise <= sun.sunset) return (h >= sun.sunrise && h < sun.sunset) ? "light" : "dark";
        return (h >= sun.sunrise || h < sun.sunset) ? "light" : "dark";
    }

    function sunriseSunsetLocalHours(date, lat, lon) {
        const sunrise = calcSunTime(date, lat, lon, true);
        const sunset = calcSunTime(date, lat, lon, false);
        if (sunrise == null || sunset == null) return null;
        return { sunrise: sunrise, sunset: sunset };
    }

    function calcSunTime(date, lat, lon, isSunrise) {
        const zenith = 90.833;
        const n = dayOfYear(date);
        const lngHour = lon / 15;
        const t = n + (((isSunrise ? 6 : 18) - lngHour) / 24);
        const m = (0.9856 * t) - 3.289;
        let l = m + (1.916 * sinDeg(m)) + (0.020 * sinDeg(2 * m)) + 282.634;
        l = normalizeDeg(l);

        let ra = radToDeg(Math.atan(0.91764 * Math.tan(degToRad(l))));
        ra = normalizeDeg(ra);
        const lQuadrant = Math.floor(l / 90) * 90;
        const raQuadrant = Math.floor(ra / 90) * 90;
        ra = (ra + (lQuadrant - raQuadrant)) / 15;

        const sinDec = 0.39782 * sinDeg(l);
        const cosDec = Math.cos(Math.asin(sinDec));
        const cosH = (cosDeg(zenith) - (sinDec * sinDeg(lat))) / (cosDec * cosDeg(lat));
        if (cosH > 1 || cosH < -1) return null;

        let h;
        if (isSunrise) h = 360 - radToDeg(Math.acos(cosH));
        else h = radToDeg(Math.acos(cosH));
        h = h / 15;

        const localMeanTime = h + ra - (0.06571 * t) - 6.622;
        let utc = localMeanTime - lngHour;
        utc = normalizeHour(utc);
        let local = utc + (-date.getTimezoneOffset() / 60);
        local = normalizeHour(local);
        return local;
    }

    function dayOfYear(date) {
        const start = new Date(date.getFullYear(), 0, 1);
        const diff = date - start;
        return Math.floor(diff / 86400000) + 1;
    }

    function degToRad(v) { return v * Math.PI / 180; }
    function radToDeg(v) { return v * 180 / Math.PI; }
    function sinDeg(v) { return Math.sin(degToRad(v)); }
    function cosDeg(v) { return Math.cos(degToRad(v)); }
    function normalizeDeg(v) {
        let out = v % 360;
        if (out < 0) out += 360;
        return out;
    }
    function normalizeHour(v) {
        let out = v % 24;
        if (out < 0) out += 24;
        return out;
    }

    function readStorage(key) {
        try { return window.localStorage.getItem(key); } catch (ignored) { return null; }
    }

    function safeWriteStorage(key, value) {
        try { window.localStorage.setItem(key, String(value == null ? "" : value)); } catch (ignored) {}
    }
})();
</script>

<main class="container main">
