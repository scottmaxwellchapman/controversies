<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ page import="java.io.InputStream" %>
<%@ page import="java.io.ByteArrayInputStream" %>
<%@ page import="java.net.URLEncoder" %>
<%@ page import="java.nio.charset.StandardCharsets" %>
<%@ page import="java.nio.file.Files,java.nio.file.Path,java.nio.file.Paths" %>
<%@ page import="java.util.ArrayList,java.util.List" %>
<%@ page import="javax.xml.XMLConstants" %>
<%@ page import="javax.xml.parsers.DocumentBuilder,javax.xml.parsers.DocumentBuilderFactory" %>
<%@ page import="org.w3c.dom.Document,org.w3c.dom.Element,org.w3c.dom.Node,org.w3c.dom.NodeList" %>

<%@ page import="net.familylawandprobate.controversies.users_roles" %>
<%@ page import="net.familylawandprobate.controversies.plugins.MenuContribution" %>
<%@ page import="net.familylawandprobate.controversies.plugins.PluginManager" %>

<%!
    // -----------------------------
    // Menu XML parsing + safety
    // -----------------------------
    private static final class MenuItem {
        final String label;
        final String href;
        MenuItem(String label, String href) { this.label = label; this.href = href; }
    }

    private static final class MenuGroup {
        final String label; // empty means "ungrouped"
        final List<MenuItem> items = new ArrayList<>();
        MenuGroup(String label) { this.label = label; }
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&","&amp;")
                .replace("<","&lt;")
                .replace(">","&gt;")
                .replace("\"","&quot;")
                .replace("'","&#39;");
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
                String label = el.getAttribute("label");
                String href  = el.getAttribute("href");
                if (!label.isBlank() && !href.isBlank()) {
                    ungrouped.items.add(new MenuItem(label.trim(), href.trim()));
                }
            } else if ("group".equals(tag)) {
                String glabel = el.getAttribute("label");
                MenuGroup g = new MenuGroup(glabel == null ? "" : glabel.trim());

                NodeList gi = el.getElementsByTagName("item");
                for (int j = 0; j < gi.getLength(); j++) {
                    Element it = (Element) gi.item(j);
                    String label = it.getAttribute("label");
                    String href  = it.getAttribute("href");
                    if (!label.isBlank() && !href.isBlank()) {
                        g.items.add(new MenuItem(label.trim(), href.trim()));
                    }
                }
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
        g.items.add(new MenuItem("Home", "/index.jsp"));
        g.items.add(new MenuItem("Users & Security", "/users_roles.jsp"));
        g.items.add(new MenuItem("Cases", "/cases.jsp"));
        g.items.add(new MenuItem("Tenant Fields", "/tenant_fields.jsp"));
        g.items.add(new MenuItem("Form Assembly", "/forms.jsp"));
        g.items.add(new MenuItem("Assembled Forms", "/assembled_forms.jsp"));
        g.items.add(new MenuItem("Plugin Manager", "/plugin_manager.jsp"));
        g.items.add(new MenuItem("Logs", "/log_viewer.jsp"));
        return java.util.List.of(g);
    }

    private static String normalizeHref(String href) {
        String h = (href == null) ? "" : href.trim();
        if (h.isBlank()) return "";
        if (h.contains("://") || h.startsWith("//") || h.contains("\r") || h.contains("\n")) return "";
        if (!h.startsWith("/")) h = "/" + h;
        return h;
    }

    private static MenuGroup findGroup(List<MenuGroup> groups, String label) {
        String target = (label == null) ? "" : label.trim();
        for (MenuGroup g : groups) {
            if (g == null) continue;
            String gl = (g.label == null) ? "" : g.label.trim();
            if (gl.equalsIgnoreCase(target)) return g;
        }
        return null;
    }

    private static boolean hasMenuItem(MenuGroup group, String label, String href) {
        if (group == null) return false;
        String lbl = (label == null) ? "" : label.trim();
        String h = normalizeHref(href);
        if (lbl.isBlank() || h.isBlank()) return false;
        for (MenuItem existing : group.items) {
            if (existing == null) continue;
            String el = (existing.label == null) ? "" : existing.label.trim();
            String eh = normalizeHref(existing.href);
            if (el.equalsIgnoreCase(lbl) && eh.equalsIgnoreCase(h)) return true;
        }
        return false;
    }

    private static void mergePluginMenu(List<MenuGroup> groups, List<MenuContribution> contributions) {
        if (groups == null || contributions == null || contributions.isEmpty()) return;

        for (MenuContribution c : contributions) {
            if (c == null) continue;
            String label = (c.label() == null) ? "" : c.label().trim();
            String href = normalizeHref(c.href());
            if (label.isBlank() || href.isBlank()) continue;

            String groupLabel = (c.groupLabel() == null) ? "" : c.groupLabel().trim();
            MenuGroup target = findGroup(groups, groupLabel);
            if (target == null) {
                target = new MenuGroup(groupLabel);
                groups.add(target);
            }

            if (!hasMenuItem(target, label, href)) {
                target.items.add(new MenuItem(label, href));
            }
        }
    }

    private static boolean isActive(String requestUri, String ctx, String href) {
        if (requestUri == null || href == null) return false;
        String h = href.startsWith("/") ? href : ("/" + href);

        if (ctx != null && !ctx.isBlank()) {
            String target = ctx + h;
            return requestUri.equals(target) || requestUri.endsWith(h);
        }
        return requestUri.equals(h) || requestUri.endsWith(h);
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

    try {
        List<MenuContribution> pluginMenu = PluginManager.defaultManager().menuContributions();
        mergePluginMenu(menuGroups, pluginMenu);
    } catch (Exception ignored) {}

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

    // Build safe `next` (path-only, no scheme/host) for login pages
    String currentPath = uri;
    if (currentPath == null || currentPath.isBlank()) currentPath = "/index.jsp";
    if (ctx != null && !ctx.isBlank() && currentPath.startsWith(ctx)) {
        currentPath = currentPath.substring(ctx.length());
        if (currentPath == null || currentPath.isBlank()) currentPath = "/index.jsp";
    }
    if (!currentPath.startsWith("/")) currentPath = "/index.jsp";

    String nextEnc = URLEncoder.encode(currentPath, StandardCharsets.UTF_8);

    String tenantLoginHref = ctx + "/tenant_login.jsp?next=" + nextEnc;

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
                                    for (MenuItem it : g.items) {
                                        String href = (it.href == null) ? "" : it.href.trim();
                                        if (!href.startsWith("/")) href = "/" + href;
                                        String full = ctx + href;

                                        boolean active =
                                                (activeNav != null && !activeNav.isBlank())
                                                        ? activeNav.equalsIgnoreCase(href)
                                                        : isActive(uri, ctx, href);
                                %>
                                    <a class="dropdown-item <%= active ? "is-active" : "" %>"
                                       role="menuitem"
                                       href="<%= full %>">
                                        <span class="dropdown-item-label"><%= esc(it.label) %></span>
                                    </a>
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

            <% } else if (tenantLoggedIn) { %>
                <div class="user-chip" title="Authentication required">
                    <span class="user-chip-label">Auth</span>
                    <span class="user-chip-name">Sign in required</span>
                </div>
                <a class="btn btn-sm" href="<%= tenantLoginHref %>">Sign in</a>
                <a class="btn btn-ghost btn-sm" href="<%= logoutHref %>">Logout</a>

            <% } else { %>
                <a class="btn btn-sm" href="<%= tenantLoginHref %>">Tenant Login</a>
            <% } %>

        </div>
    </div>
</header>

<main class="container main">
