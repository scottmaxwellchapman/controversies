package net.familylawandprobate.controversies.plugins;

import jakarta.servlet.ServletContext;
import java.util.List;

/**
 * Core plugin contract for Controversies modules.
 *
 * Plugins are discovered through ServiceLoader:
 * META-INF/services/net.familylawandprobate.controversies.plugins.ControversiesPlugin
 */
public interface ControversiesPlugin {

    /**
     * Stable unique id (for enable/disable configuration and state directories).
     */
    String id();

    default String displayName() {
        return id();
    }

    default String version() {
        return "";
    }

    /**
     * Lower values initialize earlier.
     */
    default int startupOrder() {
        return 1000;
    }

    /**
     * Called once at application startup.
     */
    default void onLoad(PluginContext context) throws Exception {
        // no-op
    }

    /**
     * Called when ServletContext is available.
     */
    default void onServletContextReady(ServletContext servletContext, PluginContext context) throws Exception {
        // no-op
    }

    /**
     * Menu entries contributed by this plugin.
     */
    default List<MenuContribution> menuContributions() {
        return List.of();
    }

    /**
     * Called during application shutdown.
     */
    default void onShutdown() throws Exception {
        // no-op
    }
}

