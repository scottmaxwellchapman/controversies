package net.familylawandprobate.controversies.plugins;

/**
 * Runtime descriptor for plugin visibility and diagnostics.
 */
public record PluginDescriptor(
        String id,
        String displayName,
        String version,
        String source,
        boolean active,
        String error
) {
}

