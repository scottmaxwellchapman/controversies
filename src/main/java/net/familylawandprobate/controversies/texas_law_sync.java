package net.familylawandprobate.controversies;

import group.chapmanlaw.texascodesstatutes.TexasCodesStatutesSync;
import group.chapmanlaw.texasrulesandstandards.TexasRulesAndStandardsSync;

import java.nio.file.DirectoryStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Properties;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Shared (non-tenant) Texas law sync orchestration.
 * Runs both embedded sync tools and schedules a daily run at 6:45 AM local time.
 */
public final class texas_law_sync {

    private static final Logger LOG = Logger.getLogger(texas_law_sync.class.getName());
    private static final ZoneId SCHEDULE_ZONE = ZoneId.systemDefault();
    private static final LocalTime DAILY_RUN_TIME = LocalTime.of(6, 45);

    private static final Path RULES_DATA_DIR = Paths.get("data", "texas_law", "texasrulesandstandards_data").toAbsolutePath().normalize();
    private static final Path RULES_METADATA_FILE = Paths.get("data", "texas_law", "rules-standards-metadata.properties").toAbsolutePath().normalize();
    private static final Path CODES_DATA_DIR = Paths.get("data", "texas_law", "texascodesstatutes_data").toAbsolutePath().normalize();
    private static final Path CODES_LOG_FILE = Paths.get("data", "texas_law", "logs", "texascodesstatutes.log").toAbsolutePath().normalize();
    private static final Path SHARED_STATE_DIR = Paths.get("data", "shared", "texas_law").toAbsolutePath().normalize();
    private static final Path STATUS_FILE = SHARED_STATE_DIR.resolve("texas_law_sync_status.properties");
    private static final Path TENANTS_ROOT = Paths.get("data", "tenants").toAbsolutePath().normalize();

    private static final class Holder {
        private static final texas_law_sync INSTANCE = new texas_law_sync();
    }

    public static texas_law_sync defaultService() {
        return Holder.INSTANCE;
    }

    public static Path rulesDataDir() {
        return RULES_DATA_DIR;
    }

    public static Path rulesMetadataFile() {
        return RULES_METADATA_FILE;
    }

    public static Path codesDataDir() {
        return CODES_DATA_DIR;
    }

    public static Path codesLogFile() {
        return CODES_LOG_FILE;
    }

    public static Path statusFile() {
        return STATUS_FILE;
    }

    static String[] codesSyncArgs(Path dataDir) {
        Path normalized = dataDir == null ? CODES_DATA_DIR : dataDir.toAbsolutePath().normalize();
        return new String[] {
                "--target=local",
                "--data-dir=" + normalized,
                "--allow-partial"
        };
    }

    private final Timer timer = new Timer("texas-law-sync-timer", true);
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean runInFlight = new AtomicBoolean(false);
    private final Object scheduleLock = new Object();

    private volatile Instant nextScheduledAt = null;

    private texas_law_sync() {
        startIfNeeded();
    }

    public void startIfNeeded() {
        if (!started.compareAndSet(false, true)) return;
        try {
            Files.createDirectories(SHARED_STATE_DIR);
            Files.createDirectories(CODES_DATA_DIR);
        } catch (Exception ex) {
            LOG.log(Level.WARNING, "Unable to create Texas law shared directories: " + safe(ex.getMessage()), ex);
        }
        scheduleNextRun("startup");
    }

    public boolean triggerManualRun() {
        startIfNeeded();
        return triggerRunIfIdle("manual", "texas-law-sync-manual");
    }

    public boolean triggerStartupRunIfIdle() {
        startIfNeeded();
        return triggerRunIfIdle("startup-immediate", "texas-law-sync-startup");
    }

    public Properties readStatusSnapshot() {
        Properties p = readStatus();
        if (runInFlight.get()) p.setProperty("running", "true");
        Instant next = nextScheduledAt;
        if (next != null) p.setProperty("next_scheduled_at", next.toString());
        if (!p.containsKey("schedule_zone")) p.setProperty("schedule_zone", SCHEDULE_ZONE.getId());
        if (!p.containsKey("schedule_time_local")) p.setProperty("schedule_time_local", DAILY_RUN_TIME.toString());
        return p;
    }

    private void scheduleNextRun(String reason) {
        synchronized (scheduleLock) {
            ZonedDateTime now = app_clock.now(SCHEDULE_ZONE);
            ZonedDateTime next = now
                    .withHour(DAILY_RUN_TIME.getHour())
                    .withMinute(DAILY_RUN_TIME.getMinute())
                    .withSecond(0)
                    .withNano(0);
            if (!next.isAfter(now)) next = next.plusDays(1);
            long delayMs = Math.max(1000L, Duration.between(now, next).toMillis());
            nextScheduledAt = next.toInstant();

            updateStatusForSchedule();
            LOG.info("Texas law sync next run scheduled for " + next + " (" + reason + ").");

            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    if (!runInFlight.compareAndSet(false, true)) {
                        scheduleNextRun("previous-run-still-active");
                        return;
                    }
                    runSyncCycle("scheduled");
                    scheduleNextRun("scheduled-complete");
                }
            }, delayMs);
        }
    }

    private void runSyncCycle(String trigger) {
        Instant startedAt = app_clock.now();
        int rulesExit = -1;
        int codesExit = -1;
        String error = "";
        try {
            updateStatusForStart(trigger, startedAt);

            rulesExit = TexasRulesAndStandardsSync.runSync();
            String[] codeArgs = codesSyncArgs(CODES_DATA_DIR);
            codesExit = TexasCodesStatutesSync.run(codeArgs);
            if (rulesExit != 0 || codesExit != 0) {
                error = "rules_exit=" + rulesExit + ", codes_exit=" + codesExit;
            }
        } catch (Exception ex) {
            error = safe(ex.getMessage());
            LOG.log(Level.SEVERE, "Texas law sync cycle failed (" + trigger + "): " + error, ex);
        } finally {
            Instant completedAt = app_clock.now();
            long durationMs = Math.max(0L, Duration.between(startedAt, completedAt).toMillis());
            updateStatusForCompletion(trigger, startedAt, completedAt, rulesExit, codesExit, error);
            logSyncCycleToActivityLogs(trigger, rulesExit, codesExit, error, durationMs);
            runInFlight.set(false);
        }
    }

    private boolean triggerRunIfIdle(String trigger, String threadName) {
        if (!runInFlight.compareAndSet(false, true)) return false;
        Thread worker = new Thread(() -> runSyncCycle(trigger), safe(threadName).isBlank() ? "texas-law-sync" : threadName);
        worker.setDaemon(true);
        worker.start();
        return true;
    }

    private void updateStatusForSchedule() {
        Properties p = readStatus();
        Instant next = nextScheduledAt;
        if (next != null) p.setProperty("next_scheduled_at", next.toString());
        p.setProperty("schedule_zone", SCHEDULE_ZONE.getId());
        p.setProperty("schedule_time_local", DAILY_RUN_TIME.toString());
        writeStatus(p);
    }

    private void updateStatusForStart(String trigger, Instant startedAt) {
        Properties p = readStatus();
        p.setProperty("running", "true");
        p.setProperty("last_trigger", safe(trigger));
        p.setProperty("last_started_at", startedAt.toString());
        p.setProperty("last_status", "running");
        p.setProperty("last_error", "");
        p.setProperty("rules_exit_code", "");
        p.setProperty("codes_exit_code", "");
        writeStatus(p);
    }

    private void updateStatusForCompletion(String trigger,
                                           Instant startedAt,
                                           Instant completedAt,
                                           int rulesExit,
                                           int codesExit,
                                           String error) {
        Properties p = readStatus();
        long durationMs = Math.max(0L, Duration.between(startedAt, completedAt).toMillis());
        boolean ok = safe(error).isBlank() && rulesExit == 0 && codesExit == 0;
        p.setProperty("running", "false");
        p.setProperty("last_trigger", safe(trigger));
        p.setProperty("last_started_at", startedAt.toString());
        p.setProperty("last_completed_at", completedAt.toString());
        p.setProperty("last_duration_ms", String.valueOf(durationMs));
        p.setProperty("rules_exit_code", rulesExit < 0 ? "" : String.valueOf(rulesExit));
        p.setProperty("codes_exit_code", codesExit < 0 ? "" : String.valueOf(codesExit));
        p.setProperty("last_status", ok ? "ok" : "error");
        p.setProperty("last_error", safe(error));
        writeStatus(p);
        if (ok) {
            LOG.info("Texas law sync cycle complete (" + trigger + "), durationMs=" + durationMs + ".");
        } else {
            LOG.warning("Texas law sync cycle completed with errors (" + trigger + "): " + safe(error));
        }
    }

    private void logSyncCycleToActivityLogs(String trigger,
                                            int rulesExit,
                                            int codesExit,
                                            String error,
                                            long durationMs) {
        String failure = safe(error);
        boolean ok = failure.isBlank() && rulesExit == 0 && codesExit == 0;
        List<String> tenants = discoverTenantUuids();
        if (tenants.isEmpty()) return;

        activity_log logs = activity_log.defaultStore();
        for (String tenantUuid : tenants) {
            if (safe(tenantUuid).isBlank()) continue;
            LinkedHashMap<String, String> details = new LinkedHashMap<String, String>();
            details.put("trigger", safe(trigger));
            details.put("duration_ms", String.valueOf(Math.max(0L, durationMs)));
            details.put("rules_exit_code", rulesExit < 0 ? "" : String.valueOf(rulesExit));
            details.put("codes_exit_code", codesExit < 0 ? "" : String.valueOf(codesExit));
            if (!failure.isBlank()) details.put("error", failure);
            try {
                if (ok) {
                    logs.logVerbose("texas_law.sync.completed", tenantUuid, "system", "", "", details);
                } else {
                    logs.logError("texas_law.sync.failed", tenantUuid, "system", "", "", details);
                }
            } catch (Exception ex) {
                LOG.log(Level.FINE, "Unable to write Texas law sync activity log for tenant=" + tenantUuid, ex);
            }
        }
    }

    private static List<String> discoverTenantUuids() {
        ArrayList<String> out = new ArrayList<String>();
        if (!Files.isDirectory(TENANTS_ROOT)) return out;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(TENANTS_ROOT)) {
            for (Path p : ds) {
                if (p == null || !Files.isDirectory(p)) continue;
                if (p.getFileName() == null) continue;
                String tenantUuid = safe(p.getFileName().toString()).trim();
                if (!tenantUuid.isBlank()) out.add(tenantUuid);
            }
        } catch (Exception ex) {
            LOG.log(Level.FINE, "Unable to list tenant directories for texas law activity logging: " + safe(ex.getMessage()), ex);
        }
        return out;
    }

    private static Properties readStatus() {
        Properties p = new Properties();
        if (!Files.exists(STATUS_FILE)) return p;
        try (InputStream in = Files.newInputStream(STATUS_FILE)) {
            p.load(in);
        } catch (Exception ex) {
            LOG.log(Level.WARNING, "Unable to read Texas law sync status: " + safe(ex.getMessage()), ex);
        }
        return p;
    }

    private static void writeStatus(Properties p) {
        try {
            Files.createDirectories(STATUS_FILE.getParent());
            Path tmp = STATUS_FILE.resolveSibling(STATUS_FILE.getFileName().toString() + ".tmp");
            try (OutputStream out = Files.newOutputStream(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                p.store(out, "texas_law_sync_status");
            }
            try {
                Files.move(tmp, STATUS_FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception moveEx) {
                Files.move(tmp, STATUS_FILE, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception ex) {
            LOG.log(Level.WARNING, "Unable to write Texas law sync status: " + safe(ex.getMessage()), ex);
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }
}
