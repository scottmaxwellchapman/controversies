// tomcat.java
package net.familylawandprobate.controversies;

import org.apache.catalina.Context;
import org.apache.catalina.Wrapper;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.webresources.DirResourceSet;
import org.apache.catalina.webresources.StandardRoot;
import org.apache.coyote.http11.AbstractHttp11JsseProtocol;
import org.apache.jasper.servlet.JasperInitializer;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;
import jakarta.servlet.MultipartConfigElement;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Level;
import java.util.logging.Logger;

public class tomcat {
    private static final Logger LOG = Logger.getLogger(tomcat.class.getName());

    private static final int PORT_SCAN_LIMIT = 100;
    private static final SecureRandom RNG = new SecureRandom();
    private static final String SSL_MODE_ENV = "CONTROVERSIES_SSL_MODE";
    private static final String KEYSTORE_PASSWORD_ENV = "CONTROVERSIES_SSL_KEYSTORE_PASSWORD";
    private static final String KEYSTORE_PATH_ENV = "CONTROVERSIES_SSL_KEYSTORE_PATH";
    private static final String KEYSTORE_ALIAS_ENV = "CONTROVERSIES_SSL_KEYSTORE_ALIAS";
    private static final String CERTBOT_DOMAIN_ENV = "CONTROVERSIES_CERTBOT_DOMAIN";
    private static final String CERTBOT_LIVE_DIR_ENV = "CONTROVERSIES_CERTBOT_LIVE_DIR";
    private static final String CERTBOT_OPENSSL_CMD_ENV = "CONTROVERSIES_CERTBOT_OPENSSL_CMD";
    private static final String CERTBOT_FORCE_REBUILD_ENV = "CONTROVERSIES_CERTBOT_FORCE_REBUILD";
    private static final int GENERATED_KEYSTORE_PASSWORD_LEN = 32;
    private static final String KEYSTORE_PASSWORD_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@$%*-_";

    private Tomcat tomcat;

    private int httpPort = 8080;   // redirect-only (set <=0 to disable)
    private int httpsPort = 8443;  // main port

    // Store SSL files in: data/sec/ssl (relative to working dir)
    private final Path sslDir = Paths.get("data", "sec", "ssl");
    private final Path keystorePath = sslDir.resolve("keystore.p12").toAbsolutePath();
    private final Path keystorePasswordPath = sslDir.resolve("keystore.password").toAbsolutePath();
    private final Path runtimeSslConfigPath = sslDir.resolve("runtime_ssl.properties").toAbsolutePath();

    // Dev keystore settings (auto-generated if missing)
    private String keystorePassword = "";
    private final String keyAlias = "tomcat";
    private Properties runtimeSslConfig = new Properties();

    private static final class TlsMaterial {
        final Path keystoreFile;
        final String keystorePassword;
        final String keyAlias;
        final String source;

        TlsMaterial(Path keystoreFile, String keystorePassword, String keyAlias, String source) {
            this.keystoreFile = keystoreFile;
            this.keystorePassword = safe(keystorePassword);
            this.keyAlias = safe(keyAlias).isBlank() ? "tomcat" : safe(keyAlias);
            this.source = safe(source);
        }
    }

    private static final class CommandResult {
        final int exitCode;
        final String output;

        CommandResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = safe(output);
        }
    }

    // Keep JVM alive until stop() is called
    private CountDownLatch stopLatch = new CountDownLatch(1);

    public synchronized void start() throws Exception {
        start(8080, 8443);
    }

    public synchronized void start(int httpPort, int httpsPort) throws Exception {
        if (tomcat != null) return;

        stopLatch = new CountDownLatch(1);
        ResolvedPorts resolvedPorts = resolvePorts(httpPort, httpsPort);
        this.httpPort = resolvedPorts.httpPort;
        this.httpsPort = resolvedPorts.httpsPort;

        // JSP root (common Maven path)
        Path webappDir = Paths.get("src", "main", "webapp").toAbsolutePath();
        Files.createDirectories(webappDir);
        if (!Files.isDirectory(webappDir)) {
            throw new IllegalStateException("Webapp directory not found: " + webappDir);
        }

        loadRuntimeSslConfig();
        this.keystorePassword = resolveKeystorePassword();
        TlsMaterial tlsMaterial = resolveTlsMaterial(this.keystorePassword);

        tomcat = new Tomcat();

        File baseDir = new File("target/tomcat");
        baseDir.mkdirs();
        tomcat.setBaseDir(baseDir.getAbsolutePath());

        Context ctx = tomcat.addWebapp("", webappDir.toString());

        // Make compiled classes visible to JSPs
        Path classesDir = Paths.get("target", "classes").toAbsolutePath();
        StandardRoot resources = new StandardRoot(ctx);
        if (Files.isDirectory(classesDir)) {
            resources.addPreResources(new DirResourceSet(
                    resources,
                    "/WEB-INF/classes",
                    classesDir.toString(),
                    "/"
            ));
        }
        ctx.setResources(resources);

        // Enable JSP engine
        ctx.addServletContainerInitializer(new JasperInitializer(), null);
        configureJspCompilerLevel(ctx);

        // Register your security filter: net.familylawandprobate.controversies.filter
        registerFilter(ctx);
        registerApiServlet(ctx);
        registerVersionUploadServlet(ctx);
        registerVersionEditorServlet(ctx);
        registerSearchJobsServlet(ctx);
        registerTenantChatServlet(ctx);
        registerWikiFileServlet(ctx);
        registerWebDavServlet(ctx);
        registerCalDavServlet(ctx);
        registerProfileAssetsServlet(ctx);

        // HTTPS connector (primary)
        Connector https = createHttpsConnector(this.httpsPort, tlsMaterial.keystoreFile, tlsMaterial.keystorePassword, tlsMaterial.keyAlias);
        tomcat.getService().addConnector(https);
        tomcat.setConnector(https);

        // Optional HTTP connector (redirect support)
        if (this.httpPort > 0) {
            Connector http = createHttpRedirectConnector(this.httpPort, this.httpsPort);
            tomcat.getService().addConnector(http);
        }

        tomcat.start();

        // Fail fast if HTTPS didn't actually come up
        if (!https.getState().isAvailable()) {
            try { stop(); } catch (Exception ignored) {}
            throw new IllegalStateException(
                    "HTTPS connector failed to start. Check earlier logs for the real cause."
            );
        }

        // Stop cleanly on Ctrl+C / SIGTERM
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { stop(); } catch (Exception ignored) {}
        }));

        LOG.info(() -> "Embedded Tomcat started: HTTPS=https://localhost:" + this.httpsPort + "/"
                + (this.httpPort > 0 ? ", HTTP redirect=http://localhost:" + this.httpPort + "/" : ", HTTP redirect=disabled")
                + ", tlsSource=" + tlsMaterial.source + ", keystore=" + tlsMaterial.keystoreFile);
    }

    public synchronized void stop() throws Exception {
        if (tomcat == null) {
            stopLatch.countDown();
            return;
        }
        try {
            tomcat.stop();
        } finally {
            tomcat.destroy();
            tomcat = null;
            stopLatch.countDown();
        }
        LOG.info("Embedded Tomcat stopped.");
    }

    /** Blocks until stop() is called. */
    public void await() throws InterruptedException {
        stopLatch.await();
    }

    public int getHttpPort() {
        return httpPort;
    }

    public int getHttpsPort() {
        return httpsPort;
    }

    private static void registerFilter(Context ctx) {
        // NOTE: Your filter class should be:
        // package net.familylawandprobate.controversies;
        // public class filter implements jakarta.servlet.Filter { ... }
        FilterDef def = new FilterDef();
        def.setFilterName("securityFilter");
        def.setFilterClass("net.familylawandprobate.controversies.filter");
        ctx.addFilterDef(def);

        FilterMap map = new FilterMap();
        map.setFilterName("securityFilter");
        map.addURLPattern("/*");
        ctx.addFilterMap(map);
    }

    private static void configureJspCompilerLevel(Context ctx) {
        String vm = resolveJspCompilerLevel();
        org.apache.catalina.Container jspServlet = ctx.findChild("jsp");
        if (jspServlet instanceof Wrapper) {
            Wrapper jsp = (Wrapper) jspServlet;
            jsp.addInitParameter("compilerSourceVM", vm);
            jsp.addInitParameter("compilerTargetVM", vm);
            return;
        }
        // Fallback for containers where the JSP servlet is initialized later.
        ctx.addParameter("compilerSourceVM", vm);
        ctx.addParameter("compilerTargetVM", vm);
    }

    private static String resolveJspCompilerLevel() {
        int runtimeFeature = Runtime.version().feature();
        // Tomcat Jasper + ECJ can lag the latest JDK release. Cap to a stable level
        // that supports modern language features used in JSP scriptlets.
        int capped = Math.min(runtimeFeature, 17);
        return Integer.toString(capped);
    }

    private static void registerApiServlet(Context ctx) {
        Wrapper wrapper = Tomcat.addServlet(ctx, "apiServlet", new api_servlet());
        wrapper.setLoadOnStartup(1);
        ctx.addServletMappingDecoded("/api/*", "apiServlet");
    }

    private static void registerVersionUploadServlet(Context ctx) {
        Wrapper wrapper = Tomcat.addServlet(ctx, "versionUploadServlet", new version_upload_servlet());
        wrapper.setLoadOnStartup(1);
        ctx.addServletMappingDecoded("/versions_upload", "versionUploadServlet");
    }

    private static void registerVersionEditorServlet(Context ctx) {
        Wrapper wrapper = Tomcat.addServlet(ctx, "versionEditorServlet", new version_editor_servlet());
        wrapper.setLoadOnStartup(1);
        ctx.addServletMappingDecoded("/version_editor/*", "versionEditorServlet");
    }

    private static void registerSearchJobsServlet(Context ctx) {
        Wrapper wrapper = Tomcat.addServlet(ctx, "searchJobsServlet", new search_jobs_servlet());
        wrapper.setLoadOnStartup(1);
        ctx.addServletMappingDecoded("/search_jobs", "searchJobsServlet");
    }

    private static void registerTenantChatServlet(Context ctx) {
        Wrapper wrapper = Tomcat.addServlet(ctx, "tenantChatServlet", new tenant_chat_servlet());
        wrapper.setLoadOnStartup(1);
        wrapper.setMultipartConfigElement(
                new MultipartConfigElement(
                        "",
                        omnichannel_tickets.MAX_ATTACHMENT_BYTES,
                        omnichannel_tickets.MAX_ATTACHMENT_BYTES * 5L,
                        0
                )
        );
        ctx.addServletMappingDecoded("/tenant_chat", "tenantChatServlet");
    }

    private static void registerWikiFileServlet(Context ctx) {
        Wrapper wrapper = Tomcat.addServlet(ctx, "wikiFileServlet", new wiki_file_servlet());
        wrapper.setLoadOnStartup(1);
        wrapper.setMultipartConfigElement(new MultipartConfigElement("", tenant_wikis.MAX_ATTACHMENT_BYTES, tenant_wikis.MAX_ATTACHMENT_BYTES + (5L * 1024L * 1024L), 0));
        ctx.addServletMappingDecoded("/wiki_files", "wikiFileServlet");
    }

    private static void registerWebDavServlet(Context ctx) {
        Wrapper wrapper = Tomcat.addServlet(ctx, "webdavServlet", new webdav_servlet());
        wrapper.setLoadOnStartup(1);
        ctx.addServletMappingDecoded("/webdav/*", "webdavServlet");
    }

    private static void registerCalDavServlet(Context ctx) {
        Wrapper wrapper = Tomcat.addServlet(ctx, "caldavServlet", new caldav_servlet());
        wrapper.setLoadOnStartup(1);
        ctx.addServletMappingDecoded("/caldav/*", "caldavServlet");
    }

    private static void registerProfileAssetsServlet(Context ctx) {
        Wrapper wrapper = Tomcat.addServlet(ctx, "profileAssetsServlet", new profile_assets_servlet());
        wrapper.setLoadOnStartup(1);
        wrapper.setMultipartConfigElement(
                new MultipartConfigElement(
                        "",
                        profile_assets.MAX_IMAGE_BYTES,
                        profile_assets.MAX_IMAGE_BYTES + (1024L * 1024L),
                        0
                )
        );
        ctx.addServletMappingDecoded("/profile_assets", "profileAssetsServlet");
    }

    private static ResolvedPorts resolvePorts(int requestedHttpPort, int requestedHttpsPort) {
        int resolvedHttpsPort = chooseAvailablePort(requestedHttpsPort, "HTTPS", -1);
        int resolvedHttpPort = requestedHttpPort;
        if (requestedHttpPort > 0) {
            resolvedHttpPort = chooseAvailablePort(requestedHttpPort, "HTTP", resolvedHttpsPort);
        }
        return new ResolvedPorts(resolvedHttpPort, resolvedHttpsPort);
    }

    private static int chooseAvailablePort(int basePort, String label, int disallowedPort) {
        if (basePort <= 0) return basePort;
        if (basePort > 65535) {
            throw new IllegalArgumentException(label + " port " + basePort + " is invalid.");
        }

        int maxOffset = Math.min(PORT_SCAN_LIMIT - 1, 65535 - basePort);
        int lastChecked = basePort + maxOffset;
        for (int offset = 0; offset <= maxOffset; offset++) {
            int candidate = basePort + offset;
            if (candidate == disallowedPort) {
                continue;
            }
            if (isPortFree(candidate)) {
                if (candidate != basePort) {
                    LOG.warning(() -> label + " port " + basePort + " is unavailable; using " + candidate + " instead.");
                }
                return candidate;
            }
        }

        throw new IllegalStateException(
                label + " ports " + basePort + "-" + lastChecked + " are unavailable. " +
                "Checked up to " + PORT_SCAN_LIMIT + " ports."
        );
    }

    private static boolean isPortFree(int port) {
        try (ServerSocket ss = new ServerSocket()) {
            ss.setReuseAddress(false);
            ss.bind(new InetSocketAddress(port));
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static final class ResolvedPorts {
        private final int httpPort;
        private final int httpsPort;

        private ResolvedPorts(int httpPort, int httpsPort) {
            this.httpPort = httpPort;
            this.httpsPort = httpsPort;
        }
    }

    private static Connector createHttpRedirectConnector(int httpPort, int httpsPort) {
        Connector c = new Connector("org.apache.coyote.http11.Http11NioProtocol");
        c.setPort(httpPort);
        c.setScheme("http");
        c.setSecure(false);
        c.setRedirectPort(httpsPort);
        tuneConnector(c);
        return c;
    }

    private static Connector createHttpsConnector(int httpsPort, Path keystoreFile, String keystorePass, String keyAlias) {
        Connector c = new Connector("org.apache.coyote.http11.Http11NioProtocol");
        c.setPort(httpsPort);
        c.setScheme("https");
        c.setSecure(true);
        tuneConnector(c);

        @SuppressWarnings("unchecked")
        AbstractHttp11JsseProtocol<?> protocol = (AbstractHttp11JsseProtocol<?>) c.getProtocolHandler();
        protocol.setSSLEnabled(true);

        // IMPORTANT for Tomcat 10.1.x: provide SSLHostConfig named "_default_"
        SSLHostConfig sslHostConfig = new SSLHostConfig();
        sslHostConfig.setHostName("_default_");

        // Prefix with + to avoid Tomcat warning about missing +/- prefixes
        sslHostConfig.setProtocols("+TLSv1.3,+TLSv1.2");

        SSLHostConfigCertificate cert =
                new SSLHostConfigCertificate(sslHostConfig, SSLHostConfigCertificate.Type.RSA);
        cert.setCertificateKeystoreFile(keystoreFile.toString());
        cert.setCertificateKeystorePassword(keystorePass);
        cert.setCertificateKeystoreType("PKCS12");
        cert.setCertificateKeyAlias(keyAlias);

        sslHostConfig.addCertificate(cert);
        protocol.addSslHostConfig(sslHostConfig);

        return c;
    }

    private static void tuneConnector(Connector c) {
        if (c == null) return;
        c.setMaxPostSize(-1);
        c.setProperty("maxHttpFormPostSize", "-1");
        c.setProperty("maxParameterCount", "200000");
        c.setProperty("maxSwallowSize", "-1");
        c.setProperty("maxHttpHeaderSize", "65536");
        c.setProperty("relaxedPathChars", "\"<>[\\]^`{|}");
        c.setProperty("relaxedQueryChars", "\"<>[\\]^`{|}");
    }

    private String resolveKeystorePassword() throws Exception {
        String configured = readSystemOption(KEYSTORE_PASSWORD_ENV).trim();
        if (!configured.isBlank()) return configured;

        Files.createDirectories(sslDir);

        if (Files.exists(keystorePasswordPath)) {
            String stored = safe(Files.readString(keystorePasswordPath, StandardCharsets.UTF_8)).trim();
            if (stored.isBlank()) {
                throw new IllegalStateException("Keystore password file is empty: " + keystorePasswordPath);
            }
            return stored;
        }

        if (Files.exists(keystorePath)) {
            LOG.warning("Existing TLS keystore found without password file; using legacy default password. "
                    + "Set " + KEYSTORE_PASSWORD_ENV + " or create " + keystorePasswordPath + " to rotate.");
            return "changeit";
        }

        String generated = generateSecurePassword();
        Files.writeString(keystorePasswordPath, generated, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        trySetOwnerOnlyPermissions(keystorePasswordPath);
        LOG.warning("Generated TLS keystore password and saved it to " + keystorePasswordPath + ". "
                + "Set " + KEYSTORE_PASSWORD_ENV + " to manage this password explicitly.");
        return generated;
    }

    private TlsMaterial resolveTlsMaterial(String password) throws Exception {
        String sslMode = normalizeSslMode(readRuntimeOption(SSL_MODE_ENV));
        if ("custom".equals(sslMode)) {
            TlsMaterial custom = tryResolveCustomKeystore(password);
            if (custom == null) {
                throw new IllegalStateException(
                        "TLS mode is 'custom' but " + KEYSTORE_PATH_ENV + " is not configured.");
            }
            return custom;
        }
        if ("certbot".equals(sslMode)) {
            TlsMaterial certbot = tryResolveCertbotKeystore(password);
            if (certbot == null) {
                throw new IllegalStateException(
                        "TLS mode is 'certbot' but no valid certbot live directory was found. Set "
                                + CERTBOT_DOMAIN_ENV + " or " + CERTBOT_LIVE_DIR_ENV + ".");
            }
            return certbot;
        }
        if ("self_signed".equals(sslMode)) {
            ensureDevKeystoreExists(keystorePath, password, keyAlias);
            return new TlsMaterial(keystorePath, password, keyAlias, "self_signed");
        }

        TlsMaterial custom = tryResolveCustomKeystore(password);
        if (custom != null) return custom;

        TlsMaterial certbot = tryResolveCertbotKeystore(password);
        if (certbot != null) return certbot;

        ensureDevKeystoreExists(keystorePath, password, keyAlias);
        return new TlsMaterial(keystorePath, password, keyAlias, "self_signed");
    }

    private TlsMaterial tryResolveCustomKeystore(String password) throws Exception {
        String customPathRaw = readRuntimeOption(KEYSTORE_PATH_ENV).trim();
        if (customPathRaw.isBlank()) return null;

        Path customPath = Paths.get(customPathRaw).toAbsolutePath().normalize();
        if (!Files.isRegularFile(customPath)) {
            throw new IllegalStateException("Custom keystore path does not exist or is not a file: " + customPath);
        }
        String alias = readRuntimeOption(KEYSTORE_ALIAS_ENV).trim();
        if (alias.isBlank()) alias = keyAlias;
        return new TlsMaterial(customPath, password, alias, "custom");
    }

    private TlsMaterial tryResolveCertbotKeystore(String password) throws Exception {
        String explicitLiveDir = readRuntimeOption(CERTBOT_LIVE_DIR_ENV).trim();
        String certbotDomain = readRuntimeOption(CERTBOT_DOMAIN_ENV).trim();

        Path liveDir = null;
        if (!explicitLiveDir.isBlank()) {
            liveDir = Paths.get(explicitLiveDir).toAbsolutePath().normalize();
        } else if (!certbotDomain.isBlank()) {
            liveDir = Paths.get("/etc", "letsencrypt", "live", certbotDomain).toAbsolutePath().normalize();
        }
        if (liveDir == null) return null;

        Path fullchain = liveDir.resolve("fullchain.pem");
        Path privkey = liveDir.resolve("privkey.pem");
        if (!Files.isRegularFile(fullchain) || !Files.isRegularFile(privkey)) {
            LOG.warning("Certbot TLS integration requested, but fullchain.pem/privkey.pem are missing in " + liveDir + ".");
            return null;
        }

        Files.createDirectories(sslDir);
        Path certbotPkcs12 = sslDir.resolve("certbot_keystore.p12").toAbsolutePath();
        boolean forceRebuild = "true".equalsIgnoreCase(readRuntimeOption(CERTBOT_FORCE_REBUILD_ENV).trim());
        if (forceRebuild || shouldRebuildCertbotKeystore(certbotPkcs12, fullchain, privkey)) {
            buildPkcs12FromCertbot(certbotPkcs12, fullchain, privkey, password, keyAlias);
        }
        if (!Files.isRegularFile(certbotPkcs12)) return null;

        return new TlsMaterial(certbotPkcs12, password, keyAlias, "certbot");
    }

    private static boolean shouldRebuildCertbotKeystore(Path target, Path fullchain, Path privkey) {
        try {
            if (!Files.isRegularFile(target)) return true;
            long targetMs = Files.getLastModifiedTime(target).toMillis();
            long certMs = Files.getLastModifiedTime(fullchain).toMillis();
            long keyMs = Files.getLastModifiedTime(privkey).toMillis();
            return certMs > targetMs || keyMs > targetMs;
        } catch (Exception ex) {
            return true;
        }
    }

    private void buildPkcs12FromCertbot(Path outputKeystore,
                                        Path fullchainPem,
                                        Path privkeyPem,
                                        String keystorePassword,
                                        String alias) throws Exception {
        String openssl = resolveOpenSslCommand();
        if (openssl.isBlank()) {
            throw new IllegalStateException("OpenSSL was not found. Set " + CERTBOT_OPENSSL_CMD_ENV
                    + " or ensure openssl is on PATH.");
        }

        Path passFile = Files.createTempFile(sslDir, "openssl-pass-", ".txt");
        try {
            Files.writeString(passFile, safe(keystorePassword), StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
            trySetOwnerOnlyPermissions(passFile);

            List<String> cmd = new ArrayList<String>();
            cmd.add(openssl);
            cmd.add("pkcs12");
            cmd.add("-export");
            cmd.add("-in");
            cmd.add(fullchainPem.toString());
            cmd.add("-inkey");
            cmd.add(privkeyPem.toString());
            cmd.add("-name");
            cmd.add(alias);
            cmd.add("-out");
            cmd.add(outputKeystore.toString());
            cmd.add("-passout");
            cmd.add("file:" + passFile.toString());

            CommandResult result = runCommand(cmd);
            if (result.exitCode != 0) {
                throw new IllegalStateException("OpenSSL failed building PKCS12 keystore (exit " + result.exitCode + "): "
                        + truncate(result.output, 2000));
            }
            trySetOwnerOnlyPermissions(outputKeystore);
        } finally {
            try { Files.deleteIfExists(passFile); } catch (Exception ignored) {}
        }
    }

    private String resolveOpenSslCommand() {
        String configured = readRuntimeOption(CERTBOT_OPENSSL_CMD_ENV).trim();
        if (!configured.isBlank()) return configured;

        boolean isWindows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        String[] candidates = isWindows
                ? new String[]{"openssl.exe", "openssl"}
                : new String[]{"openssl"};
        for (String candidate : candidates) {
            try {
                CommandResult result = runCommand(List.of(candidate, "version"));
                if (result.exitCode == 0) return candidate;
            } catch (Exception ignored) {
            }
        }
        return "";
    }

    private static CommandResult runCommand(List<String> cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        byte[] out = p.getInputStream().readAllBytes();
        int code = p.waitFor();
        return new CommandResult(code, new String(out, StandardCharsets.UTF_8));
    }

    private static void trySetOwnerOnlyPermissions(Path p) {
        try {
            Files.setPosixFilePermissions(
                    p,
                    Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
            );
        } catch (Exception ignored) {
            // Non-POSIX filesystems may not support explicit file permissions.
        }
    }

    private static String truncate(String s, int max) {
        String text = safe(s);
        if (text.length() <= max) return text;
        return text.substring(0, Math.max(0, max)) + "...";
    }

    private void loadRuntimeSslConfig() {
        Properties loaded = new Properties();
        if (!Files.isRegularFile(runtimeSslConfigPath)) {
            this.runtimeSslConfig = loaded;
            return;
        }
        try (var in = Files.newInputStream(runtimeSslConfigPath)) {
            loaded.load(in);
        } catch (Exception ex) {
            LOG.log(Level.WARNING, "Unable to read runtime SSL settings from " + runtimeSslConfigPath
                    + ". Continuing with defaults and environment overrides.", ex);
        }
        this.runtimeSslConfig = loaded;
    }

    private String readRuntimeOption(String key) {
        String configured = readSystemOption(key);
        if (!configured.isBlank()) return configured;
        return safe(runtimeSslConfig.getProperty(key)).trim();
    }

    private static String readSystemOption(String key) {
        String configured = safe(System.getProperty(key)).trim();
        if (!configured.isBlank()) return configured;
        return safe(System.getenv(key)).trim();
    }

    private static String normalizeSslMode(String raw) {
        String mode = safe(raw).trim().toLowerCase(Locale.ROOT);
        if ("selfsigned".equals(mode) || "self-signed".equals(mode)) return "self_signed";
        if ("auto".equals(mode) || "self_signed".equals(mode) || "certbot".equals(mode) || "custom".equals(mode)) {
            return mode;
        }
        return "auto";
    }

    private static String generateSecurePassword() {
        int len = Math.max(24, GENERATED_KEYSTORE_PASSWORD_LEN);
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            int idx = RNG.nextInt(KEYSTORE_PASSWORD_ALPHABET.length());
            sb.append(KEYSTORE_PASSWORD_ALPHABET.charAt(idx));
        }
        return sb.toString();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    // Dev-only: create a self-signed keystore using JDK keytool if missing
    private static void ensureDevKeystoreExists(Path keystore, String password, String alias) throws Exception {
        if (Files.exists(keystore)) return;

        Files.createDirectories(keystore.getParent()); // creates data/sec/ssl

        boolean isWindows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        Path javaHomeKeytool = Paths.get(System.getProperty("java.home"), "bin",
                isWindows ? "keytool.exe" : "keytool");
        String keytoolCommand = javaHomeKeytool.toString();
        boolean usingPathLookup = false;
        if (!Files.exists(javaHomeKeytool) || !Files.isExecutable(javaHomeKeytool)) {
            // Some deployments use a JRE-only java.home path while keytool is available on PATH.
            keytoolCommand = isWindows ? "keytool.exe" : "keytool";
            usingPathLookup = true;
        }

        List<String> cmd = new ArrayList<String>();
        cmd.add(keytoolCommand);
        cmd.add("-genkeypair");
        cmd.add("-alias"); cmd.add(alias);
        cmd.add("-keyalg"); cmd.add("RSA");
        cmd.add("-keysize"); cmd.add("2048");
        cmd.add("-storetype"); cmd.add("PKCS12");
        cmd.add("-keystore"); cmd.add(keystore.toString());
        cmd.add("-storepass"); cmd.add(password);
        cmd.add("-keypass"); cmd.add(password);
        cmd.add("-validity"); cmd.add("3650");
        cmd.add("-dname"); cmd.add("CN=localhost, OU=Dev, O=Local, L=Local, S=Local, C=US");
        cmd.add("-ext"); cmd.add("SAN=dns:localhost,ip:127.0.0.1");

        CommandResult result = runCommand(cmd);
        if (result.exitCode != 0) {
            String mode = usingPathLookup ? "PATH lookup" : javaHomeKeytool.toString();
            throw new IllegalStateException("keytool failed (exit " + result.exitCode + ") using " + mode + ": "
                    + truncate(result.output, 2000));
        }
        trySetOwnerOnlyPermissions(keystore);
        LOG.info(() -> "Created self-signed development keystore at " + keystore);
    }
}
