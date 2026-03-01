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

import java.io.File;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

public class tomcat {
    private Tomcat tomcat;

    private int httpPort = 8080;   // redirect-only (set <=0 to disable)
    private int httpsPort = 8443;  // main port

    // Store SSL files in: data/sec/ssl (relative to working dir)
    private final Path sslDir = Paths.get("data", "sec", "ssl");
    private final Path keystorePath = sslDir.resolve("keystore.p12").toAbsolutePath();

    // Dev keystore settings (auto-generated if missing)
    private final String keystorePassword = "changeit";
    private final String keyAlias = "tomcat";

    // Keep JVM alive until stop() is called
    private CountDownLatch stopLatch = new CountDownLatch(1);

    public synchronized void start() throws Exception {
        start(8080, 8443);
    }

    public synchronized void start(int httpPort, int httpsPort) throws Exception {
        if (tomcat != null) return;

        stopLatch = new CountDownLatch(1);
        this.httpPort = httpPort;
        this.httpsPort = httpsPort;

        // Fail fast if ports are already in use (this is your current crash)
        assertPortFree(this.httpsPort, "HTTPS");
        assertPortFree(this.httpPort, "HTTP");

        // JSP root (common Maven path)
        Path webappDir = Paths.get("src", "main", "webapp").toAbsolutePath();
        Files.createDirectories(webappDir);
        if (!Files.isDirectory(webappDir)) {
            throw new IllegalStateException("Webapp directory not found: " + webappDir);
        }

        ensureDevKeystoreExists(keystorePath, keystorePassword, keyAlias);

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

        // Register your security filter: net.familylawandprobate.controversies.filter
        registerFilter(ctx);
        registerApiServlet(ctx);
        registerVersionUploadServlet(ctx);

        // HTTPS connector (primary)
        Connector https = createHttpsConnector(this.httpsPort, keystorePath, keystorePassword, keyAlias);
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

        System.out.println("Embedded Tomcat started:");
        System.out.println("  HTTPS: https://localhost:" + this.httpsPort + "/");
        if (this.httpPort > 0) {
            System.out.println("  HTTP (redirects): http://localhost:" + this.httpPort + "/");
        }
        System.out.println("  Keystore: " + keystorePath);
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
        System.out.println("Embedded Tomcat stopped.");
    }

    /** Blocks until stop() is called. */
    public void await() throws InterruptedException {
        stopLatch.await();
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

    private static void assertPortFree(int port, String label) {
        if (port <= 0) return;
        try (ServerSocket ss = new ServerSocket()) {
            ss.setReuseAddress(false);
            ss.bind(new InetSocketAddress("0.0.0.0", port));
        } catch (Exception e) {
            throw new IllegalStateException(
                    label + " port " + port + " is already in use. " +
                    "Stop the other process (or change ports).",
                    e
            );
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

    // Dev-only: create a self-signed keystore using JDK keytool if missing
    private static void ensureDevKeystoreExists(Path keystore, String password, String alias) throws Exception {
        if (Files.exists(keystore)) return;

        Files.createDirectories(keystore.getParent()); // creates data/sec/ssl

        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        Path keytool = Paths.get(System.getProperty("java.home"), "bin",
                isWindows ? "keytool.exe" : "keytool");

        if (!Files.exists(keytool)) {
            throw new IllegalStateException("keytool not found at: " + keytool + " (run with a full JDK).");
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(keytool.toString());
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

        Process p = new ProcessBuilder(cmd).inheritIO().start();
        int code = p.waitFor();
        if (code != 0) throw new IllegalStateException("keytool failed (exit " + code + ")");
    }
}
