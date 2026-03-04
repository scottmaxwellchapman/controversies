package net.familylawandprobate.controversies;

import net.familylawandprobate.controversies.integrations.clio.ClioIntegrationService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Tenant-aware Clio sync scheduler.
 * Each enabled tenant can configure interval via:
 *   clio_matters_sync_interval_minutes
 *
 * At each interval the scheduler runs:
 * 1) matter sync
 * 2) document + version sync
 * 3) contact-to-matter link sync
 */
public final class clio_matter_sync_scheduler {

    private static final Logger LOG = Logger.getLogger(clio_matter_sync_scheduler.class.getName());
    private static final long TICK_MS = 60_000L;
    private static final int DEFAULT_INTERVAL_MINUTES = 15;

    private static final class Holder {
        private static final clio_matter_sync_scheduler INSTANCE = new clio_matter_sync_scheduler();
    }

    public static clio_matter_sync_scheduler defaultService() {
        return Holder.INSTANCE;
    }

    private final Timer timer = new Timer("clio-matter-sync-timer", true);
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final ConcurrentHashMap<String, Instant> nextRunByTenant = new ConcurrentHashMap<String, Instant>();
    private final Set<String> inFlightTenants = ConcurrentHashMap.newKeySet();
    private final tenant_settings settingsStore = tenant_settings.defaultStore();
    private final ClioIntegrationService integrationService = new ClioIntegrationService();

    private clio_matter_sync_scheduler() {}

    public void startIfNeeded() {
        if (!started.compareAndSet(false, true)) return;
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                tick();
            }
        }, 10_000L, TICK_MS);
        LOG.info("Clio sync scheduler started.");
    }

    private void tick() {
        Set<String> seen = new HashSet<String>();
        try {
            Path tenantsRoot = Paths.get("data", "tenants").toAbsolutePath();
            if (!Files.exists(tenantsRoot) || !Files.isDirectory(tenantsRoot)) return;

            try (java.util.stream.Stream<Path> tenantDirs = Files.list(tenantsRoot)) {
                for (Path p : tenantDirs.toList()) {
                    if (p == null || !Files.isDirectory(p)) continue;
                    String tenantUuid = safe(p.getFileName() == null ? "" : p.getFileName().toString()).trim();
                    if (tenantUuid.isBlank()) continue;
                    seen.add(tenantUuid);
                    handleTenant(tenantUuid);
                }
            }
        } catch (Exception ex) {
            LOG.log(Level.FINE, "Clio scheduler tick failed: " + safe(ex.getMessage()), ex);
        } finally {
            cleanupMissingTenants(seen);
        }
    }

    private void handleTenant(String tenantUuid) {
        LinkedHashMap<String, String> cfg = settingsStore.read(tenantUuid);
        if (!"true".equalsIgnoreCase(safe(cfg.get("clio_enabled")))) {
            nextRunByTenant.remove(tenantUuid);
            inFlightTenants.remove(tenantUuid);
            return;
        }
        if (safe(cfg.get("clio_access_token")).isBlank()) return;

        int intervalMinutes = parseIntervalMinutes(cfg.get("clio_matters_sync_interval_minutes"));
        Instant now = Instant.now();
        Instant due = nextRunByTenant.computeIfAbsent(tenantUuid, t -> now);
        if (now.isBefore(due)) return;
        if (!inFlightTenants.add(tenantUuid)) return;

        Instant nextDue = now.plus(Duration.ofMinutes(intervalMinutes));
        nextRunByTenant.put(tenantUuid, nextDue);

        Thread worker = new Thread(() -> {
            try {
                integrationService.syncMatters(tenantUuid, false);
                integrationService.syncDocuments(tenantUuid, false);
                integrationService.syncContactsToMatters(tenantUuid, false);
            } catch (Exception ex) {
                LOG.log(Level.WARNING, "Scheduled Clio sync failed for tenant=" + tenantUuid + ": " + safe(ex.getMessage()), ex);
            } finally {
                inFlightTenants.remove(tenantUuid);
            }
        }, "clio-matter-sync-" + tenantUuid.replaceAll("[^A-Za-z0-9._-]", "_"));
        worker.setDaemon(true);
        worker.start();
    }

    private void cleanupMissingTenants(Set<String> seen) {
        if (seen == null) seen = Set.of();
        Set<String> safeSeen = seen;
        nextRunByTenant.keySet().removeIf(k -> !safeSeen.contains(k));
        inFlightTenants.removeIf(k -> !safeSeen.contains(k));
    }

    private static int parseIntervalMinutes(String raw) {
        try {
            int n = Integer.parseInt(safe(raw).trim());
            if (n < 1 || n > 1440) return DEFAULT_INTERVAL_MINUTES;
            return n;
        } catch (Exception ignored) {
            return DEFAULT_INTERVAL_MINUTES;
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
