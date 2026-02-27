package net.familylawandprobate.controversies.plugins;

import net.familylawandprobate.controversies.activity_log;
import jakarta.servlet.ServletContext;

import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Loads plugins from classpath and data/plugins/*.jar.
 * Safe by default: plugin faults are isolated and never abort app startup.
 */
public final class PluginManager {

    private static final Logger LOG = Logger.getLogger(PluginManager.class.getName());

    private static final PluginManager DEFAULT = new PluginManager(
            Paths.get("data", "plugins").toAbsolutePath().normalize(),
            PluginManager.class.getClassLoader()
    );

    private static final String ATTR_MANAGER = "plugins.manager";
    private static final String ATTR_DESCRIPTORS = "plugins.descriptors";
    private static final String CFG_ENABLED_IDS = "enabled.ids";
    private static final String CFG_DISABLED_IDS = "disabled.ids";

    public static PluginManager defaultManager() {
        return DEFAULT;
    }

    private final Path appRootDir;
    private final Path dataDir;
    private final Path pluginsDir;
    private final Path pluginStateDir;
    private final Path configPath;
    private final ClassLoader appClassLoader;

    private final List<PluginHandle> activePlugins = new ArrayList<>();
    private final List<PluginDescriptor> descriptors = new ArrayList<>();
    private final List<URLClassLoader> pluginClassLoaders = new ArrayList<>();

    private List<MenuContribution> menuContributions = List.of();
    private List<Path> discoveredJarPaths = List.of();
    private Set<String> enabledIds = Set.of();
    private Set<String> disabledIds = Set.of();
    private ServletContext servletContext;
    private boolean started = false;

    public PluginManager(Path pluginsDir, ClassLoader appClassLoader) {
        this.appRootDir = Paths.get(".").toAbsolutePath().normalize();
        this.dataDir = appRootDir.resolve("data").normalize();
        this.pluginsDir = (pluginsDir == null ? dataDir.resolve("plugins") : pluginsDir).toAbsolutePath().normalize();
        this.pluginStateDir = this.pluginsDir.resolve("state").toAbsolutePath().normalize();
        this.configPath = this.pluginsDir.resolve("plugins.properties").toAbsolutePath().normalize();
        this.appClassLoader = (appClassLoader == null ? PluginManager.class.getClassLoader() : appClassLoader);
    }

    public synchronized void start() {
        if (started) return;
        started = true;

        activePlugins.clear();
        descriptors.clear();
        menuContributions = List.of();
        discoveredJarPaths = List.of();
        enabledIds = Set.of();
        disabledIds = Set.of();

        LinkedHashMap<String, String> startDetails = new LinkedHashMap<>();
        startDetails.put("plugins_dir", pluginsDir.toString());
        startDetails.put("config_path", configPath.toString());
        logActivity("info", "plugin.manager.starting", startDetails);

        try {
            Files.createDirectories(dataDir);
            Files.createDirectories(pluginsDir);
            Files.createDirectories(pluginStateDir);
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Plugin manager directories are unavailable: " + e.getMessage(), e);
            LinkedHashMap<String, String> details = new LinkedHashMap<>();
            details.put("error", safeError(e));
            details.put("plugins_dir", pluginsDir.toString());
            logActivity("error", "plugin.manager.start.failed", details);
            started = false;
            return;
        }

        loadGateConfig();

        List<PluginHandle> discovered = new ArrayList<>();
        Set<String> seenIds = new LinkedHashSet<>();

        discoverFromClassLoader(appClassLoader, "classpath", discovered, seenIds);
        discoverFromPluginJars(discovered, seenIds);

        discovered.sort(Comparator
                .comparingInt((PluginHandle h) -> h.startupOrder)
                .thenComparing(h -> h.id.toLowerCase(Locale.ROOT)));

        List<MenuContribution> menu = new ArrayList<>();
        for (PluginHandle h : discovered) {
            initializeHandle(h, menu);
        }

        menu.sort(Comparator
                .comparingInt(MenuContribution::order)
                .thenComparing(MenuContribution::groupLabel, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(MenuContribution::label, String.CASE_INSENSITIVE_ORDER));
        menuContributions = List.copyOf(menu);

        if (servletContext != null) {
            bindServletContextLocked(servletContext);
            publishContextAttributes(servletContext);
        }

        LinkedHashMap<String, String> details = new LinkedHashMap<>();
        details.put("active_plugins", String.valueOf(activePlugins.size()));
        details.put("discovered_descriptors", String.valueOf(descriptors.size()));
        details.put("jar_count", String.valueOf(discoveredJarPaths.size()));
        if (!enabledIds.isEmpty()) details.put("enabled_ids", String.join(",", enabledIds));
        if (!disabledIds.isEmpty()) details.put("disabled_ids", String.join(",", disabledIds));
        logActivity("info", "plugin.manager.started", details);

        LOG.info(() -> "Plugin manager started. active=" + activePlugins.size() + ", discovered=" + descriptors.size());
    }

    public synchronized void bindServletContext(ServletContext servletContext) {
        if (servletContext == null) return;
        this.servletContext = servletContext;
        if (!started) start();
        bindServletContextLocked(servletContext);
        publishContextAttributes(servletContext);
    }

    public synchronized void stop() {
        if (!started) return;

        for (int i = activePlugins.size() - 1; i >= 0; i--) {
            PluginHandle h = activePlugins.get(i);
            try {
                h.plugin.onShutdown();
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Plugin shutdown failed for " + h.id + ": " + e.getMessage(), e);
                LinkedHashMap<String, String> details = new LinkedHashMap<>();
                details.put("plugin_id", h.id);
                details.put("error", safeError(e));
                logActivity("warn", "plugin.shutdown.failed", details);
            }
        }

        int activeCount = activePlugins.size();
        activePlugins.clear();
        menuContributions = List.of();
        discoveredJarPaths = List.of();

        for (int i = pluginClassLoaders.size() - 1; i >= 0; i--) {
            URLClassLoader cl = pluginClassLoaders.get(i);
            try {
                cl.close();
            } catch (Exception e) {
                LOG.log(Level.FINE, "Plugin classloader close failed: " + e.getMessage(), e);
            }
        }
        pluginClassLoaders.clear();
        descriptors.clear();
        enabledIds = Set.of();
        disabledIds = Set.of();
        servletContext = null;
        started = false;

        LinkedHashMap<String, String> details = new LinkedHashMap<>();
        details.put("active_plugins_before_stop", String.valueOf(activeCount));
        logActivity("info", "plugin.manager.stopped", details);
    }

    public synchronized void reload() {
        ServletContext ctx = this.servletContext;
        stop();
        if (ctx != null) this.servletContext = ctx;
        start();
        LinkedHashMap<String, String> details = new LinkedHashMap<>();
        details.put("active_plugins", String.valueOf(activePlugins.size()));
        details.put("discovered_descriptors", String.valueOf(descriptors.size()));
        logActivity("info", "plugin.manager.reloaded", details);
    }

    public synchronized List<MenuContribution> menuContributions() {
        if (!started) start();
        return menuContributions;
    }

    public synchronized List<PluginDescriptor> descriptors() {
        if (!started) start();
        return List.copyOf(descriptors);
    }

    public synchronized boolean isStarted() {
        return started;
    }

    public synchronized int activePluginCount() {
        return activePlugins.size();
    }

    public synchronized Path pluginsDir() {
        return pluginsDir;
    }

    public synchronized Path pluginStateDir() {
        return pluginStateDir;
    }

    public synchronized Path configPath() {
        return configPath;
    }

    public synchronized List<Path> pluginJarPaths() {
        return List.copyOf(discoveredJarPaths);
    }

    public synchronized Set<String> enabledIdsConfigured() {
        return Set.copyOf(enabledIds);
    }

    public synchronized Set<String> disabledIdsConfigured() {
        return Set.copyOf(disabledIds);
    }

    private void publishContextAttributes(ServletContext servletContext) {
        try {
            servletContext.setAttribute(ATTR_MANAGER, this);
            servletContext.setAttribute(ATTR_DESCRIPTORS, descriptors());
        } catch (Exception ignored) {
        }
    }

    private void discoverFromPluginJars(List<PluginHandle> discovered, Set<String> seenIds) {
        if (!Files.isDirectory(pluginsDir)) return;

        List<Path> jars = new ArrayList<>();
        try (Stream<Path> s = Files.list(pluginsDir)) {
            s.filter(p -> Files.isRegularFile(p)
                            && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted()
                    .forEach(jars::add);
            discoveredJarPaths = List.copyOf(jars);
        } catch (Exception e) {
            descriptors.add(new PluginDescriptor(
                    "plugins.dir",
                    "Plugin Directory Scan",
                    "",
                    pluginsDir.toString(),
                    false,
                    safeError(e)
            ));
            LOG.log(Level.WARNING, "Plugin directory scan failed: " + e.getMessage(), e);
            LinkedHashMap<String, String> details = new LinkedHashMap<>();
            details.put("plugins_dir", pluginsDir.toString());
            details.put("error", safeError(e));
            logActivity("warn", "plugin.discovery.directory.failed", details);
            return;
        }

        for (Path jar : jars) {
            try {
                URLClassLoader loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, appClassLoader);
                pluginClassLoaders.add(loader);
                discoverFromClassLoader(loader, jar.toAbsolutePath().toString(), discovered, seenIds);
            } catch (Exception e) {
                descriptors.add(new PluginDescriptor(
                        jar.getFileName().toString(),
                        "Plugin Jar",
                        "",
                        jar.toAbsolutePath().toString(),
                        false,
                        safeError(e)
                ));
                LOG.log(Level.WARNING, "Plugin jar load failed: " + jar + " - " + e.getMessage(), e);
                LinkedHashMap<String, String> details = new LinkedHashMap<>();
                details.put("jar", jar.toAbsolutePath().toString());
                details.put("error", safeError(e));
                logActivity("warn", "plugin.discovery.jar.failed", details);
            }
        }
    }

    private void discoverFromClassLoader(ClassLoader loader,
                                         String source,
                                         List<PluginHandle> discovered,
                                         Set<String> seenIds) {
        ServiceLoader<ControversiesPlugin> sl = ServiceLoader.load(ControversiesPlugin.class, loader);
        Iterator<ControversiesPlugin> it = sl.iterator();

        while (true) {
            ControversiesPlugin plugin;
            try {
                if (!it.hasNext()) break;
                plugin = it.next();
            } catch (ServiceConfigurationError e) {
                descriptors.add(new PluginDescriptor(
                        "service.configuration",
                        "Plugin Service Configuration",
                        "",
                        source,
                        false,
                        safeError(e)
                ));
                LOG.log(Level.WARNING, "Plugin service loader error from " + source + ": " + e.getMessage(), e);
                LinkedHashMap<String, String> details = new LinkedHashMap<>();
                details.put("source", source);
                details.put("error", safeError(e));
                logActivity("warn", "plugin.discovery.service_loader.failed", details);
                continue;
            }

            if (plugin == null) continue;

            String id;
            String displayName;
            String version;
            int startupOrder;
            try {
                id = safe(plugin.id()).trim();
                displayName = safe(plugin.displayName()).trim();
                version = safe(plugin.version()).trim();
                startupOrder = plugin.startupOrder();
            } catch (Throwable t) {
                String className = plugin.getClass().getName();
                descriptors.add(new PluginDescriptor(
                        className,
                        className,
                        "",
                        source,
                        false,
                        "Plugin metadata failed: " + safeError(t)
                ));
                LOG.log(Level.WARNING, "Plugin metadata failed for " + className + ": " + t.getMessage(), t);
                LinkedHashMap<String, String> details = new LinkedHashMap<>();
                details.put("source", source);
                details.put("plugin_class", className);
                details.put("error", safeError(t));
                logActivity("warn", "plugin.metadata.failed", details);
                continue;
            }
            if (displayName.isBlank()) displayName = id;

            if (id.isBlank()) {
                descriptors.add(new PluginDescriptor(
                        "plugin.id.blank",
                        displayName,
                        version,
                        source,
                        false,
                        "Plugin id() returned blank."
                ));
                LinkedHashMap<String, String> details = new LinkedHashMap<>();
                details.put("source", source);
                details.put("display_name", displayName);
                logActivity("warn", "plugin.id.blank", details);
                continue;
            }

            String idKey = id.toLowerCase(Locale.ROOT);
            if (!isEnabledByConfig(idKey)) {
                descriptors.add(new PluginDescriptor(
                        id,
                        displayName,
                        version,
                        source,
                        false,
                        "Disabled by plugins.properties."
                ));
                LinkedHashMap<String, String> details = new LinkedHashMap<>();
                details.put("plugin_id", id);
                details.put("source", source);
                logActivity("info", "plugin.disabled.by_config", details);
                continue;
            }

            if (!seenIds.add(idKey)) {
                descriptors.add(new PluginDescriptor(
                        id,
                        displayName,
                        version,
                        source,
                        false,
                        "Duplicate plugin id."
                ));
                LinkedHashMap<String, String> details = new LinkedHashMap<>();
                details.put("plugin_id", id);
                details.put("source", source);
                logActivity("warn", "plugin.id.duplicate", details);
                continue;
            }

            discovered.add(new PluginHandle(plugin, id, displayName, version, source, startupOrder));
        }
    }

    private void initializeHandle(PluginHandle h, List<MenuContribution> menu) {
        Path pluginData = pluginStateDir.resolve(safeFileToken(h.id));
        try {
            Files.createDirectories(pluginData);
        } catch (Exception e) {
            descriptors.add(new PluginDescriptor(
                    h.id,
                    h.displayName,
                    h.version,
                    h.source,
                    false,
                    "Plugin state directory failed: " + safeError(e)
            ));
            LOG.log(Level.WARNING, "Plugin state directory failed for " + h.id + ": " + e.getMessage(), e);
            return;
        }

        h.context = new PluginContext(appRootDir, dataDir, pluginsDir, pluginData, h.source);

        try {
            h.plugin.onLoad(h.context);
            h.active = true;
            activePlugins.add(h);
            descriptors.add(new PluginDescriptor(h.id, h.displayName, h.version, h.source, true, ""));
            collectMenuContributions(h, menu);
            LinkedHashMap<String, String> details = new LinkedHashMap<>();
            details.put("plugin_id", h.id);
            details.put("display_name", h.displayName);
            details.put("version", h.version);
            details.put("source", h.source);
            logActivity("info", "plugin.loaded", details);
        } catch (Exception e) {
            descriptors.add(new PluginDescriptor(h.id, h.displayName, h.version, h.source, false, safeError(e)));
            LOG.log(Level.WARNING, "Plugin init failed for " + h.id + ": " + e.getMessage(), e);
            LinkedHashMap<String, String> details = new LinkedHashMap<>();
            details.put("plugin_id", h.id);
            details.put("display_name", h.displayName);
            details.put("source", h.source);
            details.put("error", safeError(e));
            logActivity("warn", "plugin.load.failed", details);
        }
    }

    private void collectMenuContributions(PluginHandle h, List<MenuContribution> out) {
        try {
            List<MenuContribution> items = h.plugin.menuContributions();
            if (items == null || items.isEmpty()) return;
            for (MenuContribution mc : items) {
                if (mc == null) continue;
                if (safe(mc.label()).isBlank()) continue;
                if (safe(mc.href()).isBlank()) continue;
                out.add(mc);
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Plugin menu contributions failed for " + h.id + ": " + e.getMessage(), e);
            LinkedHashMap<String, String> details = new LinkedHashMap<>();
            details.put("plugin_id", h.id);
            details.put("error", safeError(e));
            logActivity("warn", "plugin.menu.failed", details);
        }
    }

    private void bindServletContextLocked(ServletContext servletContext) {
        for (PluginHandle h : activePlugins) {
            if (h.servletContextBound) continue;
            try {
                h.plugin.onServletContextReady(servletContext, h.context);
                h.servletContextBound = true;
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Plugin servlet-context hook failed for " + h.id + ": " + e.getMessage(), e);
                LinkedHashMap<String, String> details = new LinkedHashMap<>();
                details.put("plugin_id", h.id);
                details.put("error", safeError(e));
                logActivity("warn", "plugin.servlet_context.failed", details);
            }
        }
    }

    private void loadGateConfig() {
        Properties p = new Properties();
        if (Files.isRegularFile(configPath)) {
            try (InputStream in = Files.newInputStream(configPath)) {
                p.load(in);
            } catch (Exception e) {
                descriptors.add(new PluginDescriptor(
                        "plugins.config",
                        "Plugin Configuration",
                        "",
                        configPath.toString(),
                        false,
                        safeError(e)
                ));
                LOG.log(Level.WARNING, "Plugin config read failed: " + e.getMessage(), e);
                LinkedHashMap<String, String> details = new LinkedHashMap<>();
                details.put("config_path", configPath.toString());
                details.put("error", safeError(e));
                logActivity("warn", "plugin.config.read.failed", details);
            }
        }
        enabledIds = parseIdSet(p.getProperty(CFG_ENABLED_IDS, ""));
        disabledIds = parseIdSet(p.getProperty(CFG_DISABLED_IDS, ""));
    }

    private boolean isEnabledByConfig(String idKey) {
        if (idKey == null || idKey.isBlank()) return false;
        if (!enabledIds.isEmpty() && !enabledIds.contains(idKey)) return false;
        return !disabledIds.contains(idKey);
    }

    private static Set<String> parseIdSet(String input) {
        String src = safe(input);
        if (src.isBlank()) return Set.of();
        Set<String> out = new HashSet<>();
        String[] parts = src.split("[,;\\s]+");
        for (String part : parts) {
            String id = safe(part).trim().toLowerCase(Locale.ROOT);
            if (!id.isBlank()) out.add(id);
        }
        return Set.copyOf(out);
    }

    private static String safeFileToken(String s) {
        String t = safe(s).trim();
        if (t.isBlank()) return "_plugin";
        return t.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String safeError(Throwable t) {
        if (t == null) return "";
        String msg = safe(t.getMessage()).trim();
        if (msg.isBlank()) msg = t.getClass().getSimpleName();
        return msg;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static void logActivity(String level, String action, Map<String, String> details) {
        try {
            activity_log.defaultStore().logSystem(level, action, details);
        } catch (Exception ignored) {
        }
    }

    private static final class PluginHandle {
        final ControversiesPlugin plugin;
        final String id;
        final String displayName;
        final String version;
        final String source;
        final int startupOrder;
        boolean active = false;
        boolean servletContextBound = false;
        PluginContext context = null;

        PluginHandle(ControversiesPlugin plugin,
                     String id,
                     String displayName,
                     String version,
                     String source,
                     int startupOrder) {
            this.plugin = plugin;
            this.id = id;
            this.displayName = displayName;
            this.version = version;
            this.source = source;
            this.startupOrder = startupOrder;
        }
    }
}
