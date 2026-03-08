package net.familylawandprobate.controversies;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Shared self-upgrade scheduler for deployments that run from a git checkout.
 *
 * Workflow:
 * 1) fetch + fast-forward pull from configured GitHub remote/branch
 * 2) build/recompile using configured command
 * 3) restart with configured command (or inferred default)
 *
 * Config file (auto-created if missing):
 *   data/shared/self_upgrade/self_upgrade.properties
 */
public final class self_upgrade_scheduler {

    private static final Logger LOG = Logger.getLogger(self_upgrade_scheduler.class.getName());

    private static final boolean DEFAULT_ENABLED = true;
    private static final DayOfWeek DEFAULT_DAY = DayOfWeek.SATURDAY;
    private static final LocalTime DEFAULT_TIME = LocalTime.of(4, 0);
    private static final String DEFAULT_REMOTE = "origin";
    private static final String DEFAULT_BUILD_COMMAND = "mvn -q -DskipTests package";
    private static final Duration DEFAULT_COMMAND_TIMEOUT = Duration.ofMinutes(30);
    private static final Duration DISABLED_RECHECK = Duration.ofHours(1);
    private static final Duration LOCK_STALE_AFTER = Duration.ofHours(6);
    private static final int OUTPUT_LIMIT = 10_000;

    private static final Path SHARED_DIR = Paths.get("data", "shared", "self_upgrade").toAbsolutePath().normalize();
    private static final Path CONFIG_FILE = SHARED_DIR.resolve("self_upgrade.properties");
    private static final Path STATUS_FILE = SHARED_DIR.resolve("self_upgrade_status.properties");
    private static final Path LOCK_FILE = SHARED_DIR.resolve("self_upgrade.lock");
    private static final Path RESTART_LOG_FILE = SHARED_DIR.resolve("restart.log");

    private static final String CFG_ENABLED = "self_upgrade_enabled";
    private static final String CFG_DAY = "self_upgrade_day_of_week";
    private static final String CFG_TIME = "self_upgrade_time_local";
    private static final String CFG_ZONE = "self_upgrade_schedule_zone";
    private static final String CFG_REMOTE = "self_upgrade_git_remote";
    private static final String CFG_BRANCH = "self_upgrade_git_branch";
    private static final String CFG_BUILD_COMMAND = "self_upgrade_build_command";
    private static final String CFG_RESTART_COMMAND = "self_upgrade_restart_command";
    private static final String CFG_ALLOW_DIRTY = "self_upgrade_allow_dirty_worktree";
    private static final String CFG_TIMEOUT_MIN = "self_upgrade_command_timeout_minutes";

    private static final class Holder {
        private static final self_upgrade_scheduler INSTANCE = new self_upgrade_scheduler();
    }

    public static self_upgrade_scheduler defaultService() {
        return Holder.INSTANCE;
    }

    private final Timer timer = new Timer("self-upgrade-scheduler-timer", true);
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean runInFlight = new AtomicBoolean(false);
    private final Object scheduleLock = new Object();

    private volatile Instant nextScheduledAt = null;

    private self_upgrade_scheduler() {
    }

    public void startIfNeeded() {
        if (!started.compareAndSet(false, true)) return;
        try {
            Files.createDirectories(SHARED_DIR);
            ensureDefaultConfigExists();
        } catch (Exception ex) {
            LOG.log(Level.WARNING, "Unable to initialize self-upgrade scheduler storage: " + safe(ex.getMessage()), ex);
        }
        scheduleNextRun("startup");
        LOG.info("Self-upgrade scheduler started.");
    }

    static final class Config {
        boolean enabled = DEFAULT_ENABLED;
        DayOfWeek scheduleDay = DEFAULT_DAY;
        LocalTime scheduleTime = DEFAULT_TIME;
        ZoneId scheduleZone = ZoneId.systemDefault();
        String gitRemote = DEFAULT_REMOTE;
        String gitBranch = "";
        String buildCommand = DEFAULT_BUILD_COMMAND;
        String restartCommand = "";
        boolean allowDirtyWorktree = false;
        Duration commandTimeout = DEFAULT_COMMAND_TIMEOUT;
        String controlTenantUuid = "";
    }

    private static final class CommandResult {
        final int exitCode;
        final String output;
        final boolean timedOut;

        CommandResult(int exitCode, String output, boolean timedOut) {
            this.exitCode = exitCode;
            this.output = safe(output);
            this.timedOut = timedOut;
        }
    }

    private static final class RestartLaunchResult {
        final boolean started;
        final String error;

        RestartLaunchResult(boolean started, String error) {
            this.started = started;
            this.error = safe(error);
        }
    }

    private void scheduleNextRun(String reason) {
        synchronized (scheduleLock) {
            Config cfg = loadConfig();
            ZonedDateTime now = ZonedDateTime.now(cfg.scheduleZone);
            ZonedDateTime next = cfg.enabled
                    ? nextScheduledRun(now, cfg.scheduleDay, cfg.scheduleTime)
                    : now.plus(DISABLED_RECHECK);

            long delayMs = Math.max(1000L, Duration.between(now, next).toMillis());
            nextScheduledAt = next.toInstant();
            updateStatusForSchedule(cfg, next);

            LOG.info("Self-upgrade next run scheduled for " + next + " (" + reason + "), enabled=" + cfg.enabled + ".");

            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    if (!runInFlight.compareAndSet(false, true)) {
                        scheduleNextRun("previous-run-still-active");
                        return;
                    }
                    try {
                        runUpgradeCycle("scheduled");
                    } finally {
                        runInFlight.set(false);
                        scheduleNextRun("scheduled-complete");
                    }
                }
            }, delayMs);
        }
    }

    static ZonedDateTime nextScheduledRun(ZonedDateTime now, DayOfWeek day, LocalTime time) {
        ZonedDateTime candidate = now
                .with(TemporalAdjusters.nextOrSame(day))
                .withHour(time.getHour())
                .withMinute(time.getMinute())
                .withSecond(0)
                .withNano(0);
        if (!candidate.isAfter(now)) candidate = candidate.plusWeeks(1);
        return candidate;
    }

    private void runUpgradeCycle(String trigger) {
        Config cfg = loadConfig();
        Instant startedAt = Instant.now();
        String status = "error";
        String error = "";
        String branch = safe(cfg.gitBranch).trim();
        String beforeHead = "";
        String afterHead = "";
        int gitPullExit = -1;
        int buildExit = -1;
        String restartCommandUsed = "";
        boolean lockHeld = false;
        boolean statusWritten = false;

        updateStatusForStart(cfg, trigger, startedAt);

        if (!cfg.enabled) {
            status = "disabled";
            updateStatusForCompletion(cfg, trigger, startedAt, status, "", "", "", branch, -1, -1, "");
            return;
        }

        try {
            if (!acquireLock()) {
                status = "skipped_locked";
                error = "Another self-upgrade run is already active.";
                LOG.info("Self-upgrade skipped because lock is held by another process.");
                return;
            }
            lockHeld = true;

            Path repoRoot = Paths.get("").toAbsolutePath().normalize();
            verifyGitRepository(repoRoot, cfg.commandTimeout);

            if (branch.isBlank()) {
                branch = requireValue(
                        runCommand(List.of("git", "rev-parse", "--abbrev-ref", "HEAD"), cfg.commandTimeout, repoRoot),
                        "Unable to determine git branch."
                );
            }
            if ("HEAD".equalsIgnoreCase(branch)) {
                throw new IllegalStateException("Detached HEAD detected. Set self_upgrade_git_branch in tenant settings or self_upgrade.properties.");
            }

            ensureRemoteExists(cfg.gitRemote, repoRoot, cfg.commandTimeout);

            if (!cfg.allowDirtyWorktree) {
                CommandResult dirty = runCommand(List.of("git", "status", "--porcelain"), cfg.commandTimeout, repoRoot);
                if (dirty.timedOut || dirty.exitCode != 0) {
                    throw new IllegalStateException("git status failed: " + summarizeCommandResult(dirty));
                }
                if (!safe(dirty.output).trim().isBlank()) {
                    status = "skipped_dirty_worktree";
                    error = "Working tree contains uncommitted changes.";
                    LOG.warning("Self-upgrade skipped because working tree is dirty.");
                    return;
                }
            }

            beforeHead = requireValue(
                    runCommand(List.of("git", "rev-parse", "HEAD"), cfg.commandTimeout, repoRoot),
                    "Unable to read current commit hash."
            );
            LOG.info("Self-upgrade check started for remote=" + cfg.gitRemote + ", branch=" + branch + ", commit=" + beforeHead + ".");

            CommandResult fetch = runCommand(List.of("git", "fetch", "--prune", cfg.gitRemote, branch), cfg.commandTimeout, repoRoot);
            if (fetch.timedOut || fetch.exitCode != 0) {
                throw new IllegalStateException("git fetch failed: " + summarizeCommandResult(fetch));
            }

            String remoteRef = cfg.gitRemote + "/" + branch;
            String remoteHead = requireValue(
                    runCommand(List.of("git", "rev-parse", remoteRef), cfg.commandTimeout, repoRoot),
                    "Unable to resolve remote branch '" + remoteRef + "'."
            );
            if (beforeHead.equals(remoteHead)) {
                status = "no_updates";
                LOG.info("Self-upgrade check complete: local commit " + beforeHead + " is already at latest remote commit.");
                return;
            }
            LOG.info("Self-upgrade update detected: local=" + beforeHead + ", remote=" + remoteHead + ". Pulling fast-forward update.");

            CommandResult pull = runCommand(List.of("git", "pull", "--ff-only", cfg.gitRemote, branch), cfg.commandTimeout, repoRoot);
            gitPullExit = pull.exitCode;
            if (pull.timedOut || pull.exitCode != 0) {
                throw new IllegalStateException("git pull --ff-only failed: " + summarizeCommandResult(pull));
            }

            afterHead = requireValue(
                    runCommand(List.of("git", "rev-parse", "HEAD"), cfg.commandTimeout, repoRoot),
                    "Unable to read post-pull commit hash."
            );
            if (beforeHead.equals(afterHead)) {
                status = "no_updates";
                LOG.info("Self-upgrade pull completed but commit did not change; no restart required.");
                return;
            }

            CommandResult build = runShellCommand(cfg.buildCommand, cfg.commandTimeout, repoRoot);
            buildExit = build.exitCode;
            if (build.timedOut || build.exitCode != 0) {
                String buildFailure = "Build command failed: " + summarizeCommandResult(build);
                if (!cfg.allowDirtyWorktree) {
                    String rollbackError = rollbackToCommit(repoRoot, beforeHead, cfg.commandTimeout);
                    if (!rollbackError.isBlank()) {
                        throw new IllegalStateException(buildFailure + " | rollback failed: " + rollbackError);
                    }
                    throw new IllegalStateException(buildFailure + " | rolled back to " + beforeHead + ".");
                }
                throw new IllegalStateException(buildFailure + " | rollback skipped because allow_dirty_worktree=true.");
            }

            restartCommandUsed = resolveRestartCommand(cfg.restartCommand);
            if (restartCommandUsed.isBlank()) {
                status = "updated_restart_not_configured";
                error = "Build succeeded, but restart command is unavailable. Configure restart_command.";
                LOG.warning("Self-upgrade succeeded but restart was skipped due to missing restart command.");
                return;
            }

            RestartLaunchResult restart = launchRestart(restartCommandUsed, repoRoot);
            if (!restart.started) {
                status = "updated_restart_failed";
                error = restart.error;
                LOG.severe("Self-upgrade restart failed: " + error);
                return;
            }

            status = "restarting";
            updateStatusForCompletion(cfg, trigger, startedAt, status, "", beforeHead, afterHead, branch, gitPullExit, buildExit, restartCommandUsed);
            statusWritten = true;

            if (lockHeld) {
                releaseLock();
                lockHeld = false;
            }
            LOG.info("Self-upgrade applied commit " + afterHead + ". Restart command launched; exiting current process.");
            System.exit(0);
        } catch (Exception ex) {
            error = safe(ex.getMessage());
            LOG.log(Level.SEVERE, "Self-upgrade cycle failed (" + trigger + "): " + error, ex);
        } finally {
            if (lockHeld) releaseLock();
            if (!statusWritten) {
                updateStatusForCompletion(cfg, trigger, startedAt, status, error, beforeHead, afterHead, branch, gitPullExit, buildExit, restartCommandUsed);
            }
        }
    }

    private static void verifyGitRepository(Path repoRoot, Duration timeout) {
        CommandResult inRepo = runCommand(List.of("git", "rev-parse", "--is-inside-work-tree"), timeout, repoRoot);
        String value = safe(inRepo.output).trim().toLowerCase(Locale.ROOT);
        if (inRepo.timedOut || inRepo.exitCode != 0 || !"true".equals(value)) {
            throw new IllegalStateException("Current directory is not a git repository.");
        }
    }

    private static void ensureRemoteExists(String remote, Path repoRoot, Duration timeout) {
        String r = safe(remote).trim();
        if (r.isBlank()) throw new IllegalArgumentException("git_remote is required.");
        CommandResult result = runCommand(List.of("git", "remote", "get-url", r), timeout, repoRoot);
        if (result.timedOut || result.exitCode != 0) {
            throw new IllegalStateException("Git remote '" + r + "' is not configured.");
        }
    }

    private static String rollbackToCommit(Path repoRoot, String commit, Duration timeout) {
        String hash = safe(commit).trim();
        if (hash.isBlank()) return "missing previous commit hash";
        CommandResult result = runCommand(List.of("git", "reset", "--hard", hash), timeout, repoRoot);
        if (result.timedOut || result.exitCode != 0) {
            return summarizeCommandResult(result);
        }
        return "";
    }

    private static String requireValue(CommandResult result, String message) {
        if (result.timedOut || result.exitCode != 0) {
            throw new IllegalStateException(message + " " + summarizeCommandResult(result));
        }
        String value = firstLine(result.output);
        if (value.isBlank()) throw new IllegalStateException(message);
        return value;
    }

    private static String summarizeCommandResult(CommandResult result) {
        if (result == null) return "no command result";
        if (result.timedOut) {
            return "timed out" + (safe(result.output).isBlank() ? "" : " output=" + truncate(result.output, 500));
        }
        return "exit=" + result.exitCode + (safe(result.output).isBlank() ? "" : " output=" + truncate(result.output, 500));
    }

    private static CommandResult runShellCommand(String command, Duration timeout, Path workdir) {
        List<String> cmd = shellCommand(command);
        return runCommand(cmd, timeout, workdir);
    }

    static List<String> shellCommand(String command) {
        String cmd = safe(command);
        if (isWindows()) {
            String comspec = safe(System.getenv("COMSPEC")).trim();
            if (comspec.isBlank()) comspec = "cmd.exe";
            return List.of(comspec, "/c", cmd);
        }
        return List.of("/bin/sh", "-lc", cmd);
    }

    private static CommandResult runCommand(List<String> cmd, Duration timeout, Path workdir) {
        Duration effectiveTimeout = timeout == null || timeout.isNegative() || timeout.isZero() ? DEFAULT_COMMAND_TIMEOUT : timeout;
        Process process = null;
        StringBuilder output = new StringBuilder();
        Thread reader = null;
        boolean timedOut = false;
        int exit = -1;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            if (workdir != null) pb.directory(workdir.toFile());
            pb.redirectErrorStream(true);
            process = pb.start();

            Process started = process;
            reader = new Thread(() -> streamOutput(started.getInputStream(), output), "self-upgrade-output-reader");
            reader.setDaemon(true);
            reader.start();

            boolean finished = process.waitFor(Math.max(1L, effectiveTimeout.toMillis()), TimeUnit.MILLISECONDS);
            if (!finished) {
                timedOut = true;
                process.destroy();
                if (!process.waitFor(3L, TimeUnit.SECONDS)) process.destroyForcibly();
            }
            exit = process.exitValue();
        } catch (Exception ex) {
            return new CommandResult(-1, "command failed: " + safe(ex.getMessage()), false);
        } finally {
            if (reader != null) {
                try {
                    reader.join(1500L);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            if (process != null) {
                try {
                    process.getInputStream().close();
                } catch (Exception ignored) {
                }
            }
        }
        return new CommandResult(exit, output.toString(), timedOut);
    }

    private static void streamOutput(InputStream in, StringBuilder output) {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                appendLimited(output, line);
                appendLimited(output, "\n");
            }
        } catch (Exception ignored) {
        }
    }

    private static synchronized void appendLimited(StringBuilder sb, String chunk) {
        if (sb == null || chunk == null || chunk.isEmpty()) return;
        int remaining = OUTPUT_LIMIT - sb.length();
        if (remaining <= 0) return;
        if (chunk.length() <= remaining) {
            sb.append(chunk);
            return;
        }
        sb.append(chunk, 0, remaining);
    }

    private static String resolveRestartCommand(String configuredRestartCommand) {
        String configured = safe(configuredRestartCommand).trim();
        if (!configured.isBlank()) return configured;

        String inferred = inferJarRestartCommand();
        if (!inferred.isBlank()) return inferred;

        Path pom = Paths.get("pom.xml").toAbsolutePath();
        if (Files.isRegularFile(pom)) return "mvn exec:java";
        return "";
    }

    static String inferJarRestartCommand() {
        try {
            java.net.URL location = self_upgrade_scheduler.class.getProtectionDomain().getCodeSource().getLocation();
            if (location == null) return "";
            Path source = Paths.get(location.toURI()).toAbsolutePath().normalize();
            String lower = source.toString().toLowerCase(Locale.ROOT);
            if (!lower.endsWith(".jar")) return "";
            String javaExec = defaultJavaExecutable();
            return shellQuote(javaExec) + " -jar " + shellQuote(source.toString());
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String defaultJavaExecutable() {
        boolean win = isWindows();
        Path javaBin = Paths.get(System.getProperty("java.home"), "bin", win ? "java.exe" : "java").toAbsolutePath().normalize();
        if (Files.isRegularFile(javaBin)) return javaBin.toString();
        return win ? "java.exe" : "java";
    }

    static String shellQuote(String value) {
        String v = safe(value);
        if (v.isBlank()) return "''";
        if (isWindows()) {
            return "\"" + v.replace("\"", "\\\"") + "\"";
        }
        return "'" + v.replace("'", "'\"'\"'") + "'";
    }

    private static RestartLaunchResult launchRestart(String command, Path repoRoot) {
        try {
            Files.createDirectories(SHARED_DIR);
            ProcessBuilder pb = new ProcessBuilder(shellCommand(command));
            pb.directory(repoRoot.toFile());
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(RESTART_LOG_FILE.toFile()));

            Process p = pb.start();
            if (p.waitFor(1500L, TimeUnit.MILLISECONDS)) {
                int exit = p.exitValue();
                if (exit != 0) {
                    return new RestartLaunchResult(false, "Restart command exited immediately with code " + exit + ". See " + RESTART_LOG_FILE);
                }
            }
            return new RestartLaunchResult(true, "");
        } catch (Exception ex) {
            return new RestartLaunchResult(false, "Unable to launch restart command: " + safe(ex.getMessage()));
        }
    }

    private boolean acquireLock() {
        try {
            Files.createDirectories(SHARED_DIR);
        } catch (Exception ignored) {
        }

        String body = "pid=" + ProcessHandle.current().pid() + "\nstarted_at=" + Instant.now() + "\n";
        try {
            Files.writeString(LOCK_FILE, body, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return true;
        } catch (FileAlreadyExistsException ex) {
            if (!isLockStale()) return false;
            try {
                Files.deleteIfExists(LOCK_FILE);
                Files.writeString(LOCK_FILE, body, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                return true;
            } catch (Exception ignored) {
                return false;
            }
        } catch (Exception ex) {
            LOG.log(Level.WARNING, "Unable to acquire self-upgrade lock: " + safe(ex.getMessage()), ex);
            return false;
        }
    }

    private static boolean isLockStale() {
        try {
            if (!Files.isRegularFile(LOCK_FILE)) return false;
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(LOCK_FILE, StandardOpenOption.READ)) {
                p.load(in);
            }
            String started = safe(p.getProperty("started_at")).trim();
            Instant startedAt = started.isBlank() ? null : Instant.parse(started);
            if (startedAt == null) return true;
            return startedAt.plus(LOCK_STALE_AFTER).isBefore(Instant.now());
        } catch (Exception ignored) {
            return true;
        }
    }

    private static void releaseLock() {
        try {
            Files.deleteIfExists(LOCK_FILE);
        } catch (Exception ex) {
            LOG.log(Level.FINE, "Unable to release self-upgrade lock: " + safe(ex.getMessage()), ex);
        }
    }

    private void ensureDefaultConfigExists() {
        if (Files.exists(CONFIG_FILE)) return;
        Config defaults = new Config();
        Properties p = new Properties();
        applyConfigToProperties(defaults, p);
        try {
            savePropertiesAtomically(CONFIG_FILE, p, "controversies_self_upgrade_config");
        } catch (Exception ex) {
            LOG.log(Level.WARNING, "Unable to write default self-upgrade config: " + safe(ex.getMessage()), ex);
        }
    }

    private Config loadConfig() {
        Config cfg = new Config();

        Properties p = new Properties();
        if (Files.isRegularFile(CONFIG_FILE)) {
            try (InputStream in = Files.newInputStream(CONFIG_FILE, StandardOpenOption.READ)) {
                p.load(in);
            } catch (Exception ex) {
                LOG.log(Level.WARNING, "Unable to read self-upgrade config; using defaults: " + safe(ex.getMessage()), ex);
            }
        }

        cfg.enabled = parseBoolean(p.getProperty("enabled"), cfg.enabled);
        cfg.scheduleDay = parseDayOfWeek(p.getProperty("schedule_day_of_week"), cfg.scheduleDay);
        cfg.scheduleTime = parseLocalTime(p.getProperty("schedule_time_local"), cfg.scheduleTime);
        cfg.scheduleZone = parseZoneId(p.getProperty("schedule_zone"), cfg.scheduleZone);
        cfg.gitRemote = safe(p.getProperty("git_remote")).trim();
        if (cfg.gitRemote.isBlank()) cfg.gitRemote = DEFAULT_REMOTE;
        cfg.gitBranch = safe(p.getProperty("git_branch")).trim();
        cfg.buildCommand = safe(p.getProperty("build_command")).trim();
        if (cfg.buildCommand.isBlank()) cfg.buildCommand = DEFAULT_BUILD_COMMAND;
        cfg.restartCommand = safe(p.getProperty("restart_command")).trim();
        cfg.allowDirtyWorktree = parseBoolean(p.getProperty("allow_dirty_worktree"), cfg.allowDirtyWorktree);
        int timeoutMinutes = parseInt(p.getProperty("command_timeout_minutes"), (int) DEFAULT_COMMAND_TIMEOUT.toMinutes(), 1, 240);
        cfg.commandTimeout = Duration.ofMinutes(timeoutMinutes);

        applyTenantOverrides(cfg);
        applyEnvOverrides(cfg);
        if (cfg.gitRemote.isBlank()) cfg.gitRemote = DEFAULT_REMOTE;
        if (cfg.buildCommand.isBlank()) cfg.buildCommand = DEFAULT_BUILD_COMMAND;
        if (cfg.commandTimeout == null || cfg.commandTimeout.isNegative() || cfg.commandTimeout.isZero()) {
            cfg.commandTimeout = DEFAULT_COMMAND_TIMEOUT;
        }
        return cfg;
    }

    private void applyTenantOverrides(Config cfg) {
        String controlTenantUuid = resolveControlTenantUuid();
        cfg.controlTenantUuid = controlTenantUuid;
        if (controlTenantUuid.isBlank()) return;
        try {
            LinkedHashMap<String, String> tenantCfg = tenant_settings.defaultStore().read(controlTenantUuid);
            applyMapOverrides(cfg, tenantCfg);
        } catch (Exception ex) {
            LOG.log(Level.FINE, "Unable to apply self-upgrade tenant overrides for tenant=" + controlTenantUuid
                    + ": " + safe(ex.getMessage()), ex);
        }
    }

    private static void applyEnvOverrides(Config cfg) {
        LinkedHashMap<String, String> env = new LinkedHashMap<String, String>();
        env.put(CFG_ENABLED, System.getenv("CONTROVERSIES_SELF_UPDATE_ENABLED"));
        env.put(CFG_DAY, System.getenv("CONTROVERSIES_SELF_UPDATE_SCHEDULE_DAY"));
        env.put(CFG_TIME, System.getenv("CONTROVERSIES_SELF_UPDATE_SCHEDULE_TIME"));
        env.put(CFG_ZONE, System.getenv("CONTROVERSIES_SELF_UPDATE_SCHEDULE_ZONE"));
        env.put(CFG_REMOTE, System.getenv("CONTROVERSIES_SELF_UPDATE_GIT_REMOTE"));
        env.put(CFG_BRANCH, System.getenv("CONTROVERSIES_SELF_UPDATE_GIT_BRANCH"));
        env.put(CFG_BUILD_COMMAND, System.getenv("CONTROVERSIES_SELF_UPDATE_BUILD_COMMAND"));
        env.put(CFG_RESTART_COMMAND, System.getenv("CONTROVERSIES_SELF_UPDATE_RESTART_COMMAND"));
        env.put(CFG_ALLOW_DIRTY, System.getenv("CONTROVERSIES_SELF_UPDATE_ALLOW_DIRTY_WORKTREE"));
        env.put(CFG_TIMEOUT_MIN, System.getenv("CONTROVERSIES_SELF_UPDATE_COMMAND_TIMEOUT_MINUTES"));
        applyMapOverrides(cfg, env);
    }

    private static void applyMapOverrides(Config cfg, Map<String, String> raw) {
        if (cfg == null || raw == null || raw.isEmpty()) return;
        cfg.enabled = parseBoolean(raw.get(CFG_ENABLED), cfg.enabled);
        cfg.scheduleDay = parseDayOfWeek(raw.get(CFG_DAY), cfg.scheduleDay);
        cfg.scheduleTime = parseLocalTime(raw.get(CFG_TIME), cfg.scheduleTime);
        cfg.scheduleZone = parseZoneId(raw.get(CFG_ZONE), cfg.scheduleZone);
        String remote = safe(raw.get(CFG_REMOTE)).trim();
        if (!remote.isBlank()) cfg.gitRemote = remote;
        String branch = safe(raw.get(CFG_BRANCH)).trim();
        if (!branch.isBlank()) cfg.gitBranch = branch;
        String buildCommand = safe(raw.get(CFG_BUILD_COMMAND)).trim();
        if (!buildCommand.isBlank()) cfg.buildCommand = buildCommand;
        String restartCommand = safe(raw.get(CFG_RESTART_COMMAND)).trim();
        if (!restartCommand.isBlank()) cfg.restartCommand = restartCommand;
        cfg.allowDirtyWorktree = parseBoolean(raw.get(CFG_ALLOW_DIRTY), cfg.allowDirtyWorktree);
        int timeoutMinutes = parseInt(raw.get(CFG_TIMEOUT_MIN), (int) cfg.commandTimeout.toMinutes(), 1, 240);
        cfg.commandTimeout = Duration.ofMinutes(timeoutMinutes);
    }

    private String resolveControlTenantUuid() {
        try {
            String installTenant = safe(installation_state.defaultStore().read().get(installation_state.KEY_TENANT_UUID)).trim();
            if (!installTenant.isBlank()) return installTenant;
        } catch (Exception ignored) {
        }

        Path tenantsRoot = Paths.get("data", "tenants").toAbsolutePath().normalize();
        if (!Files.isDirectory(tenantsRoot)) return "";
        try (var stream = Files.list(tenantsRoot)) {
            return stream
                    .filter(Files::isDirectory)
                    .map(path -> safe(path.getFileName() == null ? "" : path.getFileName().toString()).trim())
                    .filter(token -> !token.isBlank())
                    .sorted()
                    .findFirst()
                    .orElse("");
        } catch (Exception ignored) {
            return "";
        }
    }

    static DayOfWeek parseDayOfWeek(String raw, DayOfWeek fallback) {
        String normalized = safe(raw).trim().toUpperCase(Locale.ROOT);
        if (normalized.isBlank()) return fallback == null ? DEFAULT_DAY : fallback;
        try {
            return DayOfWeek.valueOf(normalized);
        } catch (Exception ignored) {
            return fallback == null ? DEFAULT_DAY : fallback;
        }
    }

    static LocalTime parseLocalTime(String raw, LocalTime fallback) {
        String normalized = safe(raw).trim();
        if (normalized.isBlank()) return fallback == null ? DEFAULT_TIME : fallback;
        try {
            return LocalTime.parse(normalized);
        } catch (Exception ignored) {
            return fallback == null ? DEFAULT_TIME : fallback;
        }
    }

    static ZoneId parseZoneId(String raw, ZoneId fallback) {
        String normalized = safe(raw).trim();
        if (normalized.isBlank()) return fallback == null ? ZoneId.systemDefault() : fallback;
        try {
            return ZoneId.of(normalized);
        } catch (Exception ignored) {
            return fallback == null ? ZoneId.systemDefault() : fallback;
        }
    }

    static boolean parseBoolean(String raw, boolean fallback) {
        String normalized = safe(raw).trim();
        if (normalized.isBlank()) return fallback;
        if ("1".equals(normalized) || "true".equalsIgnoreCase(normalized) || "yes".equalsIgnoreCase(normalized) || "on".equalsIgnoreCase(normalized)) {
            return true;
        }
        if ("0".equals(normalized) || "false".equalsIgnoreCase(normalized) || "no".equalsIgnoreCase(normalized) || "off".equalsIgnoreCase(normalized)) {
            return false;
        }
        return fallback;
    }

    static int parseInt(String raw, int fallback, int min, int max) {
        try {
            int n = Integer.parseInt(safe(raw).trim());
            if (n < min || n > max) return fallback;
            return n;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private void updateStatusForSchedule(Config cfg, ZonedDateTime next) {
        Properties p = readStatus();
        p.setProperty("control_tenant_uuid", safe(cfg.controlTenantUuid));
        p.setProperty("enabled", String.valueOf(cfg.enabled));
        p.setProperty("schedule_day_of_week", cfg.scheduleDay.name());
        p.setProperty("schedule_time_local", cfg.scheduleTime.toString());
        p.setProperty("schedule_zone", cfg.scheduleZone.getId());
        p.setProperty("next_scheduled_at", next.toInstant().toString());
        if (!p.containsKey("running")) p.setProperty("running", "false");
        writeStatus(p);
    }

    private void updateStatusForStart(Config cfg, String trigger, Instant startedAt) {
        Properties p = readStatus();
        p.setProperty("control_tenant_uuid", safe(cfg.controlTenantUuid));
        p.setProperty("running", "true");
        p.setProperty("enabled", String.valueOf(cfg.enabled));
        p.setProperty("schedule_day_of_week", cfg.scheduleDay.name());
        p.setProperty("schedule_time_local", cfg.scheduleTime.toString());
        p.setProperty("schedule_zone", cfg.scheduleZone.getId());
        p.setProperty("last_trigger", safe(trigger));
        p.setProperty("last_started_at", startedAt.toString());
        p.setProperty("last_status", "running");
        p.setProperty("last_error", "");
        writeStatus(p);
    }

    private void updateStatusForCompletion(Config cfg,
                                           String trigger,
                                           Instant startedAt,
                                           String status,
                                           String error,
                                           String beforeHead,
                                           String afterHead,
                                           String branch,
                                           int gitPullExit,
                                           int buildExit,
                                           String restartCommand) {
        Instant completedAt = Instant.now();
        long durationMs = Math.max(0L, Duration.between(startedAt, completedAt).toMillis());
        Properties p = readStatus();
        p.setProperty("control_tenant_uuid", safe(cfg.controlTenantUuid));
        p.setProperty("running", "false");
        p.setProperty("enabled", String.valueOf(cfg.enabled));
        p.setProperty("schedule_day_of_week", cfg.scheduleDay.name());
        p.setProperty("schedule_time_local", cfg.scheduleTime.toString());
        p.setProperty("schedule_zone", cfg.scheduleZone.getId());
        p.setProperty("last_trigger", safe(trigger));
        p.setProperty("last_started_at", startedAt.toString());
        p.setProperty("last_completed_at", completedAt.toString());
        p.setProperty("last_duration_ms", String.valueOf(durationMs));
        p.setProperty("last_status", safe(status));
        p.setProperty("last_error", safe(error));
        p.setProperty("last_branch", safe(branch));
        p.setProperty("last_commit_before", safe(beforeHead));
        p.setProperty("last_commit_after", safe(afterHead));
        p.setProperty("last_git_pull_exit_code", gitPullExit < 0 ? "" : String.valueOf(gitPullExit));
        p.setProperty("last_build_exit_code", buildExit < 0 ? "" : String.valueOf(buildExit));
        p.setProperty("last_restart_command", safe(restartCommand));
        Instant next = nextScheduledAt;
        if (next != null) p.setProperty("next_scheduled_at", next.toString());
        writeStatus(p);
    }

    private static Properties readStatus() {
        Properties p = new Properties();
        if (!Files.isRegularFile(STATUS_FILE)) return p;
        try (InputStream in = Files.newInputStream(STATUS_FILE, StandardOpenOption.READ)) {
            p.load(in);
        } catch (Exception ex) {
            LOG.log(Level.WARNING, "Unable to read self-upgrade status: " + safe(ex.getMessage()), ex);
        }
        return p;
    }

    private static void writeStatus(Properties p) {
        try {
            Files.createDirectories(STATUS_FILE.getParent());
            savePropertiesAtomically(STATUS_FILE, p, "controversies_self_upgrade_status");
        } catch (Exception ex) {
            LOG.log(Level.WARNING, "Unable to write self-upgrade status: " + safe(ex.getMessage()), ex);
        }
    }

    private static void savePropertiesAtomically(Path target, Properties p, String comment) throws Exception {
        Path tmp = target.resolveSibling(target.getFileName().toString() + ".tmp");
        try (OutputStream out = Files.newOutputStream(
                tmp,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            p.store(out, safe(comment));
        }

        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void applyConfigToProperties(Config cfg, Properties p) {
        p.setProperty("enabled", String.valueOf(cfg.enabled));
        p.setProperty("schedule_day_of_week", cfg.scheduleDay.name());
        p.setProperty("schedule_time_local", cfg.scheduleTime.toString());
        p.setProperty("schedule_zone", cfg.scheduleZone.getId());
        p.setProperty("git_remote", cfg.gitRemote);
        p.setProperty("git_branch", safe(cfg.gitBranch));
        p.setProperty("build_command", safe(cfg.buildCommand));
        p.setProperty("restart_command", safe(cfg.restartCommand));
        p.setProperty("allow_dirty_worktree", String.valueOf(cfg.allowDirtyWorktree));
        p.setProperty("command_timeout_minutes", String.valueOf(Math.max(1L, cfg.commandTimeout.toMinutes())));
    }

    private static boolean isWindows() {
        return safe(System.getProperty("os.name")).toLowerCase(Locale.ROOT).contains("win");
    }

    private static String firstLine(String output) {
        String text = safe(output);
        if (text.isBlank()) return "";
        int nl = text.indexOf('\n');
        if (nl < 0) return text.trim();
        return text.substring(0, nl).trim();
    }

    private static String truncate(String text, int max) {
        String s = safe(text);
        if (s.length() <= max) return s;
        return s.substring(0, Math.max(0, max)) + "...";
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
