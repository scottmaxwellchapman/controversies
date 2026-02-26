package net.familylawandprobate.controversies;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.net.URI;
import java.util.logging.Level;
import java.util.logging.Logger;

public class app {

    private static final Logger LOG = Logger.getLogger(app.class.getName());

    public static void main(String[] args) {
        configureHeadlessDefault();

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

    private static void configureHeadlessDefault() {
        String configured = System.getProperty("java.awt.headless");
        if (configured != null && !configured.trim().isBlank()) return;

        String allowDesktop = String.valueOf(System.getenv("CONTROVERSIES_ALLOW_DESKTOP")).trim();
        if ("1".equals(allowDesktop) || "true".equalsIgnoreCase(allowDesktop)) {
            System.setProperty("java.awt.headless", "false");
        } else {
            System.setProperty("java.awt.headless", "true");
        }
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
