package net.familylawandprobate.controversies;

import net.familylawandprobate.controversies.integrations.office365.Office365CalendarSyncService;

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
 * Tenant-aware scheduler for Office 365 calendar sync.
 */
public final class office365_calendar_sync_scheduler {

    private static final Logger LOG = Logger.getLogger(office365_calendar_sync_scheduler.class.getName());
    private static final long TICK_MS = 60_000L;
    private static final int DEFAULT_INTERVAL_MINUTES = 30;

    private static final class Holder {
        private static final office365_calendar_sync_scheduler INSTANCE = new office365_calendar_sync_scheduler();
    }

    public static office365_calendar_sync_scheduler defaultService() {
        return Holder.INSTANCE;
    }

    private final Timer timer = new Timer("office365-calendar-sync-timer", true);
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final ConcurrentHashMap<String, Instant> nextRunByTenant = new ConcurrentHashMap<String, Instant>();
    private final Set<String> inFlightTenants = ConcurrentHashMap.newKeySet();
    private final tenant_settings settingsStore = tenant_settings.defaultStore();
    private final Office365CalendarSyncService syncService = new Office365CalendarSyncService();

    private office365_calendar_sync_scheduler() {
    }

    public void startIfNeeded() {
        if (!started.compareAndSet(false, true)) return;
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                tick();
            }
        }, 20_000L, TICK_MS);
        LOG.info("Office 365 calendar sync scheduler started.");
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
            LOG.log(Level.FINE, "Office 365 calendar scheduler tick failed: " + safe(ex.getMessage()), ex);
        } finally {
            cleanupMissingTenants(seen);
        }
    }

    private void handleTenant(String tenantUuid) {
        LinkedHashMap<String, String> cfg = settingsStore.read(tenantUuid);
        if (!"true".equalsIgnoreCase(safe(cfg.get("office365_calendar_sync_enabled")))) {
            nextRunByTenant.remove(tenantUuid);
            inFlightTenants.remove(tenantUuid);
            return;
        }
        if (safe(cfg.get("office365_calendar_sync_sources_json")).trim().isBlank()) return;

        int intervalMinutes = parseIntervalMinutes(cfg.get("office365_calendar_sync_interval_minutes"));
        Instant now = app_clock.now();
        Instant due = nextRunByTenant.computeIfAbsent(tenantUuid, t -> now);
        if (now.isBefore(due)) return;
        if (!inFlightTenants.add(tenantUuid)) return;

        Instant nextDue = now.plus(Duration.ofMinutes(intervalMinutes));
        nextRunByTenant.put(tenantUuid, nextDue);

        Thread worker = new Thread(() -> {
            try {
                syncService.syncCalendars(tenantUuid, false);
            } catch (Exception ex) {
                LOG.log(Level.WARNING, "Scheduled Office 365 calendar sync failed for tenant=" + tenantUuid + ": " + safe(ex.getMessage()), ex);
            } finally {
                inFlightTenants.remove(tenantUuid);
            }
        }, "office365-calendar-sync-" + tenantUuid.replaceAll("[^A-Za-z0-9._-]", "_"));
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
