package net.familylawandprobate.controversies;

import java.util.concurrent.ConcurrentHashMap;

/**
 * billing_runtime_registry
 *
 * In-process registry for tenant-scoped billing engines used by JSP/UI flows.
 * This keeps runtime billing state stable across requests without forcing
 * immediate persistence design decisions.
 */
public final class billing_runtime_registry {

    private static final ConcurrentHashMap<String, billing_accounting> ENGINES = new ConcurrentHashMap<String, billing_accounting>();

    private billing_runtime_registry() {}

    public static billing_accounting tenantLedger(String tenantUuid) {
        String tu = safeToken(tenantUuid);
        if (tu.isBlank()) throw new IllegalArgumentException("tenantUuid required");
        return ENGINES.computeIfAbsent(tu, k -> billing_accounting.inMemory());
    }

    public static int activeTenantCount() {
        return ENGINES.size();
    }

    public static void clearTenant(String tenantUuid) {
        String tu = safeToken(tenantUuid);
        if (tu.isBlank()) return;
        ENGINES.remove(tu);
    }

    public static void clearAll() {
        ENGINES.clear();
    }

    private static String safeToken(String s) {
        return (s == null ? "" : s).trim();
    }
}
