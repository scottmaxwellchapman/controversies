package net.familylawandprobate.controversies;

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
 * Optional tenant-aware scheduler for BPM stale/incomplete aging alarms.
 *
 * Tenant settings:
 * - bpm_aging_alarm_enabled=true|false
 * - bpm_aging_alarm_interval_minutes (default 15)
 * - bpm_aging_alarm_running_stale_minutes (default 60)
 * - bpm_aging_alarm_review_stale_minutes (default 1440)
 * - bpm_aging_alarm_max_issues_per_scan (default 200)
 */
public final class bpm_aging_alarm_scheduler {

    private static final Logger LOG = Logger.getLogger(bpm_aging_alarm_scheduler.class.getName());
    private static final long TICK_MS = 60_000L;
    private static final int DEFAULT_INTERVAL_MINUTES = 15;
    private static final int DEFAULT_RUNNING_STALE_MINUTES = 60;
    private static final int DEFAULT_REVIEW_STALE_MINUTES = 1440;
    private static final int DEFAULT_MAX_ISSUES = 200;

    private static final class Holder {
        private static final bpm_aging_alarm_scheduler INSTANCE = new bpm_aging_alarm_scheduler();
    }

    public static bpm_aging_alarm_scheduler defaultService() {
        return Holder.INSTANCE;
    }

    private final Timer timer = new Timer("bpm-aging-alarm-timer", true);
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final ConcurrentHashMap<String, Instant> nextRunByTenant = new ConcurrentHashMap<String, Instant>();
    private final Set<String> inFlightTenants = ConcurrentHashMap.newKeySet();
    private final tenant_settings settingsStore = tenant_settings.defaultStore();

    private bpm_aging_alarm_scheduler() {
    }

    public void startIfNeeded() {
        if (!started.compareAndSet(false, true)) return;
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                tick();
            }
        }, 20_000L, TICK_MS);
        LOG.info("BPM aging alarm scheduler started.");
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
            LOG.log(Level.FINE, "BPM aging alarm scheduler tick failed: " + safe(ex.getMessage()), ex);
        } finally {
            cleanupMissingTenants(seen);
        }
    }

    private void handleTenant(String tenantUuid) {
        LinkedHashMap<String, String> cfg = settingsStore.read(tenantUuid);
        if (!truthy(cfg.get("bpm_aging_alarm_enabled"))) {
            nextRunByTenant.remove(tenantUuid);
            inFlightTenants.remove(tenantUuid);
            return;
        }

        int intervalMinutes = clampInt(cfg.get("bpm_aging_alarm_interval_minutes"), DEFAULT_INTERVAL_MINUTES, 1, 1440);
        int runningStaleMinutes = clampInt(cfg.get("bpm_aging_alarm_running_stale_minutes"), DEFAULT_RUNNING_STALE_MINUTES, 1, 43200);
        int reviewStaleMinutes = clampInt(cfg.get("bpm_aging_alarm_review_stale_minutes"), DEFAULT_REVIEW_STALE_MINUTES, 1, 43200);
        int maxIssues = clampInt(cfg.get("bpm_aging_alarm_max_issues_per_scan"), DEFAULT_MAX_ISSUES, 1, 2000);

        Instant now = Instant.now();
        Instant due = nextRunByTenant.computeIfAbsent(tenantUuid, t -> now);
        if (now.isBefore(due)) return;
        if (!inFlightTenants.add(tenantUuid)) return;

        Instant nextDue = now.plus(Duration.ofMinutes(intervalMinutes));
        nextRunByTenant.put(tenantUuid, nextDue);

        Thread worker = new Thread(() -> {
            try {
                business_process_manager.AgingAlarmEmailResult result = business_process_manager.defaultService()
                        .sendAgingRunAlarmEmails(
                                tenantUuid,
                                runningStaleMinutes,
                                reviewStaleMinutes,
                                maxIssues,
                                "system.bpm_aging_alarm"
                        );
                if (result != null && result.emailsQueued > 0) {
                    LOG.warning("BPM aging alarm email queued for tenant=" + tenantUuid
                            + " issues=" + result.newlyAlarmedIssues
                            + " recipients=" + result.recipients.size()
                            + " email_uuid=" + safe(result.emailUuid) + ".");
                } else if (result != null && !safe(result.emailError).isBlank()) {
                    LOG.warning("BPM aging alarm email failed for tenant=" + tenantUuid + ": " + safe(result.emailError));
                }
            } catch (Exception ex) {
                LOG.log(Level.WARNING, "BPM aging alarm scan failed for tenant=" + tenantUuid + ": " + safe(ex.getMessage()), ex);
            } finally {
                inFlightTenants.remove(tenantUuid);
            }
        }, "bpm-aging-alarm-" + tenantUuid.replaceAll("[^A-Za-z0-9._-]", "_"));
        worker.setDaemon(true);
        worker.start();
    }

    private void cleanupMissingTenants(Set<String> seen) {
        if (seen == null) seen = Set.of();
        Set<String> safeSeen = seen;
        nextRunByTenant.keySet().removeIf(k -> !safeSeen.contains(k));
        inFlightTenants.removeIf(k -> !safeSeen.contains(k));
    }

    private static int clampInt(String raw, int fallback, int min, int max) {
        int n = fallback;
        try {
            n = Integer.parseInt(safe(raw).trim());
        } catch (Exception ignored) {
        }
        if (n < min) return min;
        if (n > max) return max;
        return n;
    }

    private static boolean truthy(String raw) {
        String v = safe(raw).trim().toLowerCase(java.util.Locale.ROOT);
        return "true".equals(v) || "1".equals(v) || "yes".equals(v) || "on".equals(v) || "y".equals(v);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
