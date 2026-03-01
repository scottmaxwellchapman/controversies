package net.familylawandprobate.controversies;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.net.URI;
import java.util.logging.Level;
import java.util.logging.Logger;

public class app {

    private static final Logger LOG = Logger.getLogger(app.class.getName());

    public static void main(String[] args) {
        configureHeadlessMode();

        tenant_settings.StartupSelfCheckResult startupCheck = tenant_settings.defaultStore().startupSelfCheckAllTenants();
        if (!startupCheck.ok) {
            for (String failure : startupCheck.failures) {
                LOG.severe("Startup integration self-check failed: " + failure);
            }
            LOG.severe("Aborting startup because enabled integrations have invalid secrets.");
            return;
        }

        // Start background queue workers early in process lifecycle.
        notification_emails.defaultStore();
        texas_law_sync texasLawSync = texas_law_sync.defaultService();
        texasLawSync.triggerStartupRunIfIdle();

        tomcat t = new tomcat();

        int httpPort = 8080;
        int httpsPort = 8443;

        try {
            t.start(httpPort, httpsPort);

            // Launch the browser only when a desktop UI is available
            String url = "https://localhost:" + httpsPort + "/";
            launchBrowserIfDesktop(url);
     
            t.await();
        } catch (Exception ex) {
            LOG.log(Level.SEVERE, null, ex);
        }
    }

    private static void configureHeadlessMode() {
        // Respect an explicitly configured JVM flag.
        String configured = System.getProperty("java.awt.headless");
        if (configured != null && !configured.trim().isBlank()) {
            return;
        }

        // Optional env override for scripts/CI that want predictable behavior.
        String headlessOverride = String.valueOf(System.getenv("CONTROVERSIES_HEADLESS")).trim();
        if ("1".equals(headlessOverride) || "true".equalsIgnoreCase(headlessOverride)) {
            System.setProperty("java.awt.headless", "true");
        } else if ("0".equals(headlessOverride) || "false".equalsIgnoreCase(headlessOverride)) {
            System.setProperty("java.awt.headless", "false");
        }

        // Otherwise, leave the default untouched so desktop environments can auto-open a browser.
    }

    private static void launchBrowserIfDesktop(String url) {
        try {
            if (GraphicsEnvironment.isHeadless()) return;
            if (!Desktop.isDesktopSupported()) return;

            Desktop d = Desktop.getDesktop();
            if (!d.isSupported(Desktop.Action.BROWSE)) return;

            d.browse(URI.create(url));
            LOG.info(() -> "Opened browser: " + url);
        } catch (Exception e) {
            // Never fail startup because browser launch didn't work
            LOG.log(Level.FINE, "Browser launch skipped/failed: " + e.getMessage(), e);
        }
    }
}
