package net.familylawandprobate.controversies.plugins;

import java.nio.file.Path;

/**
 * Runtime context provided to plugins.
 */
public final class PluginContext {
    private final Path appRootDir;
    private final Path dataDir;
    private final Path pluginsDir;
    private final Path pluginDataDir;
    private final String source;

    PluginContext(Path appRootDir,
                  Path dataDir,
                  Path pluginsDir,
                  Path pluginDataDir,
                  String source) {
        this.appRootDir = appRootDir;
        this.dataDir = dataDir;
        this.pluginsDir = pluginsDir;
        this.pluginDataDir = pluginDataDir;
        this.source = source == null ? "" : source;
    }

    public Path appRootDir() {
        return appRootDir;
    }

    public Path dataDir() {
        return dataDir;
    }

    public Path pluginsDir() {
        return pluginsDir;
    }

    public Path pluginDataDir() {
        return pluginDataDir;
    }

    public String source() {
        return source;
    }
}

