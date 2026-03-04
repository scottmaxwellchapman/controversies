// filter.java (NO CORS)
package net.familylawandprobate.controversies;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * filter.java (NO CORS)
 * --------------------
 * Goals:
 * - Stable (no redirect loops, no CORS/origin headaches)
 * - Rotating XML access log: data/logs/access_log.xml
 * - Block non-browser clients (automated tools + spiders) via strict-ish UA heuristics
 * - IP filtering using ip_lists (never blocks localhost/loopback)
 * - CSRF token validation for state-changing methods (POST/PUT/PATCH/DELETE)
 * - Minimal hardening headers + safe method allowlist
 *
 * Access log:
 * - Includes timestamp with timezone offset + zone id
 * - Includes full URL (scheme://host[:port]/path?query)
 * - Includes IP data
 * - Includes location data if available from common proxy/CDN headers (Cloudflare, CloudFront, Vercel, etc.)
 * - Maintains a well-formed XML document by rewriting only the closing tag per write.
 * - Rotates when file exceeds max bytes (default 5MB). Renames to access_log-<timestamp>.xml
 * - Keeps last N rotated logs (default 10).
 *
 * CSRF:
 * - Session key: CSRF_TOKEN (configurable)
 * - Accepts token via request header "X-CSRF-Token" or form param "csrfToken"
 * - For GET/HEAD/OPTIONS: ensures token exists in session and sets request attribute "csrfToken"
 */
public final class filter implements Filter {

    private static final Logger LOG = Logger.getLogger(filter.class.getName());
    private static final SecureRandom RNG = new SecureRandom();

    // ---- init params (optional) ----
    private boolean trustProxyHeaders = false;

    private boolean enableCsrf = true;
    private String csrfSessionKey = "CSRF_TOKEN";
    private String csrfHeaderName = "X-CSRF-Token";
    private String csrfParamName  = "csrfToken";

    private boolean enableHsts = false;          // default OFF to avoid dev surprises
    private long hstsMaxAgeSeconds = 31536000L;  // 1 year

    // Block non-browser clients (default ON)
    private boolean blockNonBrowserClients = true;

    // Method allowlist
    private static final Set<String> ALLOWED_METHODS =
            Set.of("GET", "POST", "HEAD", "OPTIONS", "PUT", "PATCH", "DELETE");

    // Context attribute names
    private static final String CTX_IPLISTS = "ipLists";
    private static final String API_TENANT_HEADER = "X-Tenant-UUID";
    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String API_SECRET_HEADER = "X-API-Secret";

    // --- Access log (rotating XML) ---
    private boolean enableAccessLog = true;
    private long accessLogMaxBytes = 10L * 1024L * 1024L; // 5MB
    private int accessLogMaxFiles = 10;

    private final Path logsDir = Paths.get("data","sec" ,"logs").toAbsolutePath();
    private final Path accessLogPath = logsDir.resolve("access_log.xml");

    private final Object logLock = new Object();

    private static final DateTimeFormatter ROT_TS =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

    // Timestamp format with offset (e.g. 2026-01-03T19:00:35-06:00)
    private static final DateTimeFormatter LOG_TS =
            DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    // strict-ish bot / tool keywords (lowercased check)
    private static final String[] BOT_KEYWORDS = new String[] {
            "bot", "spider", "crawl", "slurp", "bingpreview", "bingbot", "googlebot",
            "duckduckbot", "baiduspider", "yandex", "ahrefs", "semrush", "mj12bot",
            "facebookexternalhit", "twitterbot", "pinterest", "petalbot", "sogou",
            "crawler", "scrapy", "python-requests", "java/", "apache-httpclient",
            "okhttp", "go-http-client", "wget", "curl", "powershell", "postmanruntime",
            "insomnia", "httpclient", "libwww", "headless"
    };

    @Override
    public void init(FilterConfig cfg) {
        trustProxyHeaders = boolParam(cfg, "trustProxyHeaders", trustProxyHeaders);

        enableCsrf = boolParam(cfg, "enableCsrf", enableCsrf);
        csrfSessionKey = strParam(cfg, "csrfSessionKey", csrfSessionKey);
        csrfHeaderName = strParam(cfg, "csrfHeaderName", csrfHeaderName);
        csrfParamName  = strParam(cfg, "csrfParamName", csrfParamName);

        enableHsts = boolParam(cfg, "enableHsts", enableHsts);
        hstsMaxAgeSeconds = longParam(cfg, "hstsMaxAgeSeconds", hstsMaxAgeSeconds);

        blockNonBrowserClients = boolParam(cfg, "blockNonBrowserClients", blockNonBrowserClients);

        enableAccessLog = boolParam(cfg, "enableAccessLog", enableAccessLog);
        accessLogMaxBytes = longParam(cfg, "accessLogMaxBytes", accessLogMaxBytes);
        accessLogMaxFiles = intParam(cfg, "accessLogMaxFiles", accessLogMaxFiles);

        // Ensure ip_lists exists and store it in servlet context once
        try {
            ip_lists lists = (ip_lists) cfg.getServletContext().getAttribute(CTX_IPLISTS);
            if (lists == null) {
                lists = ip_lists.defaultStore();
                cfg.getServletContext().setAttribute(CTX_IPLISTS, lists);
            } else {
                lists.ensure();
            }
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Failed initializing ip_lists: " + e.getMessage(), e);
        }

        // Ensure access log exists
        if (enableAccessLog) {
            try {
                Files.createDirectories(logsDir);
                ensureWellFormedAccessLogFile();
                rotateIfNeeded();
            } catch (Exception e) {
                enableAccessLog = false; // protect availability
                LOG.log(Level.SEVERE, "Disabling access log due to init failure: " + e.getMessage(), e);
            }
        }

        LOG.info(() -> "filter initialized. trustProxyHeaders=" + trustProxyHeaders +
                ", enableCsrf=" + enableCsrf +
                ", enableHsts=" + enableHsts +
                ", blockNonBrowserClients=" + blockNonBrowserClients +
                ", enableAccessLog=" + enableAccessLog);
    }

    @Override
    public void doFilter(ServletRequest rawReq, ServletResponse rawRes, FilterChain chain)
            throws IOException, ServletException {

        if (!(rawReq instanceof HttpServletRequest req) || !(rawRes instanceof HttpServletResponse res)) {
            chain.doFilter(rawReq, rawRes);
            return;
        }

        // Avoid double-running on forwards/includes/async/error (prevents weird loops)
        if (req.getDispatcherType() != DispatcherType.REQUEST) {
            chain.doFilter(req, res);
            return;
        }

        long startNs = System.nanoTime();
        StatusCaptureResponse wrapped = new StatusCaptureResponse(res);

        String clientIp = getClientIp(req);
        String method = safe(req.getMethod());
        String uri = safe(req.getRequestURI());
        String qs  = safe(req.getQueryString());
        String ua  = safe(req.getHeader("User-Agent"));
        String ref = safe(req.getHeader("Referer"));

        String scheme = safe(req.getScheme());
        String host = safe(req.getHeader("Host"));
        String fullUrl = buildFullUrl(req);

        Location loc = extractLocation(req);

        // timestamp with timezone offset + zone id (also embedded)
        ZoneId zone = ZoneId.systemDefault();
        ZonedDateTime zdt = ZonedDateTime.now(zone);
        String tsWithOffset = LOG_TS.format(zdt) + "[" + zone.getId() + "]";
        String tzId = zone.getId();

        try {
            // 1) Baseline headers
            applySecurityHeaders(req, wrapped);

            // 2) Method allowlist
            if (!methodAllowed(req, wrapped)) return;

            // 3) Very light path sanity checks (no body reads; avoids hangs)
            if (!pathLooksSafe(req, wrapped)) return;

            boolean apiRequest = isApiRequest(req);

            // 4) IP allow/deny via ip_lists (never blocks localhost)
            if (!ipAllowed(req, wrapped, clientIp)) return;

            if (apiRequest) {
                // API requests are automation-first: no browser heuristic and no CSRF.
                if (apiAuthRequired(req)) {
                    if (!apiAuthOk(req, wrapped, clientIp)) return;
                }
                documents.bindActorContext(resolveApiActor(req));
                chain.doFilter(req, wrapped);
                return;
            }

            // 5) Block non-browser clients (tools + spiders) for interactive web UI.
            if (blockNonBrowserClients && !browserClientAllowed(req, wrapped, clientIp)) return;

            // 6) CSRF (token-only; no origin/referer checks) for web UI state changes.
            if (enableCsrf) {
                if (!csrfOk(req, wrapped)) return;
            }

            documents.bindActorContext(resolveWebActor(req));
            chain.doFilter(req, wrapped);

        } finally {
            documents.clearActorContext();
            if (enableAccessLog) {
                long ms = (System.nanoTime() - startNs) / 1_000_000L;
                try {
                    writeAccessLogEntry(
                            tsWithOffset,
                            tzId,
                            clientIp,
                            method,
                            uri,
                            qs,
                            scheme,
                            host,
                            fullUrl,
                            wrapped.getStatusCode(),
                            ms,
                            ua,
                            ref,
                            loc
                    );
                } catch (Exception e) {
                    // never break the app because logging failed
                    LOG.log(Level.FINE, "access log write failed: " + e.getMessage(), e);
                }
            }
        }
    }

    @Override
    public void destroy() {
        // no-op
    }

    // -----------------------------
    // Security headers (minimal)
    // -----------------------------

    private void applySecurityHeaders(HttpServletRequest req, HttpServletResponse res) {
        res.setHeader("X-Content-Type-Options", "nosniff");
        res.setHeader("Referrer-Policy", "no-referrer");
        res.setHeader("X-Frame-Options", "SAMEORIGIN");
        res.setHeader("Permissions-Policy", "geolocation=(self), microphone=(), camera=()");

        // CSP: conservative to avoid breaking JSP UI
        res.setHeader("Content-Security-Policy",
                "default-src 'self'; " +
                        "img-src 'self' data:; " +
                        "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; " +
                        "font-src 'self' https://fonts.gstatic.com data:; " +
                        "script-src 'self' 'unsafe-inline'; " +
                        "connect-src 'self' https://api.open-meteo.com https://geocoding-api.open-meteo.com; " +
                        "base-uri 'self'; " +
                        "object-src 'none'; " +
                        "frame-ancestors 'self'");

        if (enableHsts && req.isSecure()) {
            res.setHeader("Strict-Transport-Security", "max-age=" + hstsMaxAgeSeconds);
        }
    }

    // -----------------------------
    // Method & path checks
    // -----------------------------

    private boolean methodAllowed(HttpServletRequest req, HttpServletResponse res) throws IOException {
        String m = req.getMethod().toUpperCase(Locale.ROOT);

        if ("TRACE".equals(m) || "TRACK".equals(m)) {
            res.sendError(405);
            return false;
        }

        if (!ALLOWED_METHODS.contains(m)) {
            res.sendError(405);
            return false;
        }
        return true;
    }

    private boolean pathLooksSafe(HttpServletRequest req, HttpServletResponse res) throws IOException {
        String uri = req.getRequestURI();
        if (uri == null) return true;

        String lower = uri.toLowerCase(Locale.ROOT);
        String slashNorm = lower.replace('\\', '/');

        if (lower.contains("\u0000") || lower.contains("%00")) {
            res.sendError(400);
            return false;
        }

        if (slashNorm.contains("/../") || slashNorm.endsWith("/..") ||
                lower.contains("%2e%2e") || lower.contains("..%2f") || lower.contains("%2f..") ||
                lower.contains("%5c..") || lower.contains("..%5c") || lower.contains("%5c%2e%2e") || lower.contains("%2e%2e%5c")) {
            res.sendError(400);
            return false;
        }

        return true;
    }

    // -----------------------------
    // Block non-browser clients
    // -----------------------------

    private boolean browserClientAllowed(HttpServletRequest req, HttpServletResponse res, String clientIp) throws IOException {
        // Never interfere with localhost development
        if (isLocalhostIp(clientIp)) return true;

        String ua = req.getHeader("User-Agent");
        if (ua == null || ua.isBlank()) {
            res.sendError(403, "Non-browser client denied.");
            return false;
        }

        String u = ua.toLowerCase(Locale.ROOT);
        for (String k : BOT_KEYWORDS) {
            if (u.contains(k)) {
                res.sendError(403, "Automated client/spider denied.");
                return false;
            }
        }

        boolean looksBrowser =
                u.contains("mozilla/") &&
                        (u.contains("applewebkit") || u.contains("gecko") || u.contains("chrome/") || u.contains("safari/") || u.contains("firefox/") || u.contains("edg/"));

        boolean hasBrowserHeaders =
                notBlank(req.getHeader("sec-fetch-site")) ||
                        notBlank(req.getHeader("sec-fetch-mode")) ||
                        notBlank(req.getHeader("sec-ch-ua")) ||
                        notBlank(req.getHeader("upgrade-insecure-requests")) ||
                        notBlank(req.getHeader("accept-language"));

        if (!looksBrowser && !hasBrowserHeaders) {
            res.sendError(403, "Non-browser client denied.");
            return false;
        }

        return true;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    // -----------------------------
    // IP allow/deny via ip_lists
    // -----------------------------

    private boolean ipAllowed(HttpServletRequest req, HttpServletResponse res, String clientIp) throws IOException {
        if (isLocalhostIp(clientIp)) return true;

        ip_lists lists = (ip_lists) req.getServletContext().getAttribute(CTX_IPLISTS);
        if (lists == null) return true; // fail-open

        ip_lists.Decision d = lists.check(clientIp);
        if (d == ip_lists.Decision.ALLOW) return true;

        res.setStatus(403);
        res.setContentType("text/plain; charset=UTF-8");
        res.getWriter().write("Access denied (" + d + ") for IP: " + clientIp);
        return false;
    }

    private String getClientIp(HttpServletRequest req) {
        if (trustProxyHeaders) {
            String xff = req.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                int comma = xff.indexOf(',');
                return (comma > 0 ? xff.substring(0, comma) : xff).trim();
            }
            String xrip = req.getHeader("X-Real-IP");
            if (xrip != null && !xrip.isBlank()) return xrip.trim();
        }
        return req.getRemoteAddr();
    }

    private static boolean isLocalhostIp(String ip) {
        if (ip == null) return false;
        String s = ip.trim();
        return "127.0.0.1".equals(s)
                || "::1".equals(s)
                || "0:0:0:0:0:0:0:1".equalsIgnoreCase(s)
                || s.startsWith("127.");
    }

    private static String normalizedPath(HttpServletRequest req) {
        if (req == null) return "";
        String uri = safe(req.getRequestURI());
        String ctx = safe(req.getContextPath());
        String path = uri;
        if (!ctx.isBlank() && path.startsWith(ctx)) {
            path = path.substring(ctx.length());
        }
        if (path.isBlank()) path = "/";
        if (!path.startsWith("/")) path = "/" + path;
        return path;
    }

    private static boolean isApiRequest(HttpServletRequest req) {
        return normalizedPath(req).startsWith("/api/");
    }

    private static boolean apiAuthRequired(HttpServletRequest req) {
        String path = normalizedPath(req);
        if (!path.startsWith("/api/")) return false;
        if ("/api/v1/help".equals(path)) return false;
        if ("/api/v1/help/readme".equals(path)) return false;
        if ("/api/v1/ping".equals(path)) return false;
        if ("/api/v1/capabilities".equals(path)) return false;
        return true;
    }

    private static String resolveApiActor(HttpServletRequest req) {
        String credentialId = safe((String) req.getAttribute(api_servlet.REQ_CREDENTIAL_ID)).trim();
        if (credentialId.isBlank()) return "api";
        return "api:" + credentialId.toLowerCase(Locale.ROOT);
    }

    private static String resolveWebActor(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        if (session == null) return "";
        String email = safe((String) session.getAttribute(users_roles.S_USER_EMAIL)).trim();
        if (!email.isBlank()) return email.toLowerCase(Locale.ROOT);
        String userUuid = safe((String) session.getAttribute(users_roles.S_USER_UUID)).trim();
        if (!userUuid.isBlank()) return userUuid.toLowerCase(Locale.ROOT);
        return "";
    }

    private boolean apiAuthOk(HttpServletRequest req, HttpServletResponse res, String clientIp) throws IOException {
        String tenantUuid = safe(req.getHeader(API_TENANT_HEADER)).trim();
        if (tenantUuid.isBlank()) tenantUuid = safe(req.getParameter("tenant_uuid")).trim();
        if (tenantUuid.isBlank()) {
            res.sendError(401, "Missing tenant identifier.");
            return false;
        }

        String apiKey = safe(req.getHeader(API_KEY_HEADER)).trim();
        String apiSecret = safe(req.getHeader(API_SECRET_HEADER)).trim();

        if (apiKey.isBlank() || apiSecret.isBlank()) {
            String auth = safe(req.getHeader("Authorization")).trim();
            if (!auth.isBlank()) {
                String lower = auth.toLowerCase(Locale.ROOT);
                if (lower.startsWith("bearer ")) {
                    String token = auth.substring(7).trim();
                    int idx = token.indexOf(':');
                    if (idx <= 0) idx = token.indexOf('.');
                    if (idx > 0 && idx + 1 < token.length()) {
                        apiKey = token.substring(0, idx).trim();
                        apiSecret = token.substring(idx + 1).trim();
                    }
                } else if (lower.startsWith("basic ")) {
                    try {
                        String token = auth.substring(6).trim();
                        byte[] decoded = Base64.getDecoder().decode(token);
                        String pair = new String(decoded, StandardCharsets.UTF_8);
                        int idx = pair.indexOf(':');
                        if (idx > 0 && idx + 1 < pair.length()) {
                            apiKey = pair.substring(0, idx).trim();
                            apiSecret = pair.substring(idx + 1).trim();
                        }
                    } catch (Exception ignored) {
                        // fall through to auth failure
                    }
                }
            }
        }

        if (apiKey.isBlank() || apiSecret.isBlank()) {
            res.sendError(401, "Missing API credentials.");
            return false;
        }

        api_credentials.VerificationResult verified = api_credentials.defaultStore()
                .verify(tenantUuid, apiKey, apiSecret, clientIp);
        if (!verified.ok) {
            res.sendError(401, "Invalid API credentials.");
            return false;
        }

        req.setAttribute(api_servlet.REQ_TENANT_UUID, verified.tenantUuid);
        req.setAttribute(api_servlet.REQ_CREDENTIAL_ID, verified.credentialId);
        req.setAttribute(api_servlet.REQ_CREDENTIAL_LABEL, verified.credentialLabel);
        req.setAttribute(api_servlet.REQ_CREDENTIAL_SCOPE, verified.scope);
        return true;
    }

    // -----------------------------
    // CSRF (token-only; no origin/referer)
    // -----------------------------

    private boolean csrfOk(HttpServletRequest req, HttpServletResponse res) throws IOException {
        String m = req.getMethod().toUpperCase(Locale.ROOT);

        if ("GET".equals(m) || "HEAD".equals(m) || "OPTIONS".equals(m)) {
            HttpSession s = req.getSession(true);
            String token = ensureCsrfToken(s);
            req.setAttribute("csrfToken", token);
            return true;
        }

        HttpSession s = req.getSession(false);
        if (s == null) {
            res.sendError(403, "CSRF session missing.");
            return false;
        }

        String expected = (String) s.getAttribute(csrfSessionKey);
        if (expected == null || expected.isBlank()) {
            ensureCsrfToken(s);
            res.sendError(403, "CSRF token missing/invalid.");
            return false;
        }

        String provided = req.getHeader(csrfHeaderName);
        if (provided == null || provided.isBlank()) provided = req.getParameter(csrfParamName);

        if (provided == null || provided.isBlank() || !constantTimeEquals(expected, provided)) {
            res.sendError(403, "CSRF token missing/invalid.");
            return false;
        }

        return true;
    }

    private String ensureCsrfToken(HttpSession s) {
        String token = (String) s.getAttribute(csrfSessionKey);
        if (token != null && !token.isBlank()) return token;

        byte[] buf = new byte[32];
        RNG.nextBytes(buf);
        token = Base64.getUrlEncoder().withoutPadding().encodeToString(buf);

        s.setAttribute(csrfSessionKey, token);
        return token;
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        byte[] x = a.getBytes(StandardCharsets.UTF_8);
        byte[] y = b.getBytes(StandardCharsets.UTF_8);
        if (x.length != y.length) return false;

        int r = 0;
        for (int i = 0; i < x.length; i++) r |= (x[i] ^ y[i]);
        return r == 0;
    }

    // -----------------------------
    // Access log (rotating XML)
    // -----------------------------

    private void ensureWellFormedAccessLogFile() throws IOException {
        if (Files.exists(accessLogPath) && Files.size(accessLogPath) > 0) return;

        Files.createDirectories(accessLogPath.getParent());
        String header =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                        "<accessLog created=\"" + xmlAttr(Instant.now().toString()) + "\">\n" +
                        "</accessLog>\n";
        Files.writeString(accessLogPath, header, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private void rotateIfNeeded() throws IOException {
        if (accessLogMaxBytes <= 0) return;
        if (!Files.exists(accessLogPath)) return;

        long size = Files.size(accessLogPath);
        if (size <= accessLogMaxBytes) return;

        String ts = ROT_TS.format(Instant.now());
        Path rotated = logsDir.resolve("access_log-" + ts + ".xml");

        ensureEndsWithClosingTag();
        Files.move(accessLogPath, rotated, StandardCopyOption.REPLACE_EXISTING);

        ensureWellFormedAccessLogFile();
        pruneOldRotations();
    }

    private void pruneOldRotations() throws IOException {
        if (accessLogMaxFiles <= 0) return;

        List<Path> rotations = new ArrayList<>();

        // Using DirectoryStream avoids any type-inference weirdness and keeps Path strongly typed.
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(logsDir, "access_log-*.xml")) {
            for (Path p : ds) {
                rotations.add(p);
            }
        }

        rotations.sort(Comparator.comparingLong(filter::safeLastModifiedMillis).reversed());

        for (int i = accessLogMaxFiles; i < rotations.size(); i++) {
            try { Files.deleteIfExists(rotations.get(i)); } catch (Exception ignored) {}
        }
    }

    private static long safeLastModifiedMillis(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (Exception e) {
            return Long.MIN_VALUE;
        }
    }

    private void ensureEndsWithClosingTag() throws IOException {
        String close = "</accessLog>\n";
        if (!Files.exists(accessLogPath)) return;

        long size = Files.size(accessLogPath);
        if (size <= close.length()) return;

        // Small app: simplest correctness check
        String s = Files.readString(accessLogPath, StandardCharsets.UTF_8);
        if (!s.endsWith(close) && !s.endsWith("</accessLog>")) {
            Files.writeString(accessLogPath, "\n</accessLog>\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        }
    }

    private void writeAccessLogEntry(
            String tsWithOffset,
            String tzId,
            String ip,
            String method,
            String uri,
            String queryString,
            String scheme,
            String host,
            String fullUrl,
            int status,
            long ms,
            String userAgent,
            String referer,
            Location loc
    ) throws IOException {

        synchronized (logLock) {
            rotateIfNeeded();
            ensureWellFormedAccessLogFile();

            String closeTag = "</accessLog>\n";

            String locAttrs = "";
            if (loc != null && loc.hasAny()) {
                locAttrs =
                        " locSrc=\"" + xmlAttr(loc.source) + "\"" +
                                " locCountry=\"" + xmlAttr(loc.country) + "\"" +
                                " locRegion=\"" + xmlAttr(loc.region) + "\"" +
                                " locCity=\"" + xmlAttr(loc.city) + "\"" +
                                " locTz=\"" + xmlAttr(loc.timezone) + "\"";
            }

            String entry = "  <r"
                    + " t=\"" + xmlAttr(tsWithOffset) + "\""
                    + " tz=\"" + xmlAttr(tzId) + "\""
                    + " ip=\"" + xmlAttr(ip) + "\""
                    + " m=\"" + xmlAttr(method) + "\""
                    + " u=\"" + xmlAttr(uri) + "\""
                    + " qs=\"" + xmlAttr(queryString) + "\""
                    + " scheme=\"" + xmlAttr(scheme) + "\""
                    + " host=\"" + xmlAttr(host) + "\""
                    + " url=\"" + xmlAttr(fullUrl) + "\""
                    + " s=\"" + status + "\""
                    + " ms=\"" + ms + "\""
                    + " ua=\"" + xmlAttr(userAgent) + "\""
                    + " ref=\"" + xmlAttr(referer) + "\""
                    + locAttrs
                    + " />\n";

            // Insert right before the closing tag (keeps file always well-formed).
            try (RandomAccessFile raf = new RandomAccessFile(accessLogPath.toFile(), "rw")) {
                long len = raf.length();
                if (len < closeTag.length()) {
                    raf.seek(len);
                    raf.write(entry.getBytes(StandardCharsets.UTF_8));
                    raf.write(closeTag.getBytes(StandardCharsets.UTF_8));
                    return;
                }

                long insertPos = len - closeTag.length();
                if (insertPos < 0) insertPos = len;

                raf.seek(insertPos);
                raf.write(entry.getBytes(StandardCharsets.UTF_8));
                raf.write(closeTag.getBytes(StandardCharsets.UTF_8));
                raf.setLength(raf.getFilePointer());
            }
        }
    }

    private static String xmlAttr(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("'", "&apos;");
    }

    // -----------------------------
    // URL + Location helpers
    // -----------------------------

    private static String buildFullUrl(HttpServletRequest req) {
        StringBuffer sb = req.getRequestURL(); // scheme://host[:port]/path
        if (sb == null) return "";
        String qs = req.getQueryString();
        if (qs != null && !qs.isBlank()) sb.append('?').append(qs);
        return sb.toString();
    }

    private static final class Location {
        final String source;
        final String country;
        final String region;
        final String city;
        final String timezone;

        Location(String source, String country, String region, String city, String timezone) {
            this.source = nz(source);
            this.country = nz(country);
            this.region = nz(region);
            this.city = nz(city);
            this.timezone = nz(timezone);
        }

        boolean hasAny() {
            return !(country.isBlank() && region.isBlank() && city.isBlank() && timezone.isBlank());
        }
    }

    private static Location extractLocation(HttpServletRequest req) {
        // Cloudflare
        String cfCountry = hdr(req, "CF-IPCountry");
        String cfRegion  = hdr(req, "CF-Region");
        String cfCity    = hdr(req, "CF-IPCity");
        String cfTz      = hdr(req, "CF-Timezone");

        if (any(cfCountry, cfRegion, cfCity, cfTz)) {
            return new Location("cloudflare", cfCountry, cfRegion, cfCity, cfTz);
        }

        // CloudFront
        String cfc = hdr(req, "CloudFront-Viewer-Country");
        if (any(cfc)) {
            return new Location("cloudfront", cfc, "", "", "");
        }

        // Vercel
        String vzCountry = hdr(req, "x-vercel-ip-country");
        String vzRegion  = hdr(req, "x-vercel-ip-country-region");
        String vzCity    = hdr(req, "x-vercel-ip-city");
        if (any(vzCountry, vzRegion, vzCity)) {
            return new Location("vercel", vzCountry, vzRegion, vzCity, "");
        }

        // Generic headers (if you later add a reverse proxy that sets these)
        String gCountry = firstNonBlank(
                hdr(req, "X-Geo-Country"),
                hdr(req, "X-Client-Country"),
                hdr(req, "X-Country")
        );
        String gRegion = firstNonBlank(
                hdr(req, "X-Geo-Region"),
                hdr(req, "X-Client-Region"),
                hdr(req, "X-Region")
        );
        String gCity = firstNonBlank(
                hdr(req, "X-Geo-City"),
                hdr(req, "X-Client-City"),
                hdr(req, "X-City")
        );
        String gTz = firstNonBlank(
                hdr(req, "X-Geo-Timezone"),
                hdr(req, "X-Timezone")
        );

        if (any(gCountry, gRegion, gCity, gTz)) {
            return new Location("headers", gCountry, gRegion, gCity, gTz);
        }

        return new Location("", "", "", "", "");
    }

    private static String hdr(HttpServletRequest req, String name) {
        String v = req.getHeader(name);
        return (v == null) ? "" : v.trim();
    }

    private static boolean any(String... vals) {
        if (vals == null) return false;
        for (String v : vals) {
            if (v != null && !v.isBlank()) return true;
        }
        return false;
    }

    private static String firstNonBlank(String... vals) {
        if (vals == null) return "";
        for (String v : vals) {
            if (v != null && !v.isBlank()) return v.trim();
        }
        return "";
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    // -----------------------------
    // Response status capture
    // -----------------------------

    private static final class StatusCaptureResponse extends HttpServletResponseWrapper {
        private int status = 200;

        StatusCaptureResponse(HttpServletResponse response) {
            super(response);
        }

        int getStatusCode() {
            return status;
        }

        @Override
        public void setStatus(int sc) {
            this.status = sc;
            super.setStatus(sc);
        }

        @Override
        public void sendError(int sc) throws IOException {
            this.status = sc;
            super.sendError(sc);
        }

        @Override
        public void sendError(int sc, String msg) throws IOException {
            this.status = sc;
            super.sendError(sc, msg);
        }

        @Override
        public void sendRedirect(String location) throws IOException {
            this.status = 302;
            super.sendRedirect(location);
        }
    }

    // -----------------------------
    // init-param helpers
    // -----------------------------

    private static boolean boolParam(FilterConfig cfg, String name, boolean def) {
        String v = cfg.getInitParameter(name);
        return v == null ? def : Boolean.parseBoolean(v.trim());
    }

    private static int intParam(FilterConfig cfg, String name, int def) {
        String v = cfg.getInitParameter(name);
        if (v == null || v.isBlank()) return def;
        try { return Integer.parseInt(v.trim()); } catch (Exception ignored) { return def; }
    }

    private static long longParam(FilterConfig cfg, String name, long def) {
        String v = cfg.getInitParameter(name);
        if (v == null || v.isBlank()) return def;
        try { return Long.parseLong(v.trim()); } catch (Exception ignored) { return def; }
    }

    private static String strParam(FilterConfig cfg, String name, String def) {
        String v = cfg.getInitParameter(name);
        return (v == null) ? def : v.trim();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
