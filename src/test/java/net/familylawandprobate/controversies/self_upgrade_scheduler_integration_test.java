package net.familylawandprobate.controversies;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class self_upgrade_scheduler_integration_test {

    @Test
    void updater_only_pulls_when_remote_has_new_commit() throws Exception {
        Path root = Files.createTempDirectory("self-upgrade-it-");
        Path remote = root.resolve("remote.git");
        Path seed = root.resolve("seed");
        Path app = root.resolve("app");
        Path wrapperDir = root.resolve("bin");
        Path gitCallsLog = root.resolve("git-calls.log");
        Path restartScript = root.resolve("restart-probe.sh");
        Path restartMarker = root.resolve("restart.marker");
        Path workspaceRepo = Path.of("").toAbsolutePath().normalize();

        runAndRequireExit(0, root, List.of("git", "init", "--bare", remote.toString()), Map.of(), Duration.ofSeconds(30));
        runAndRequireExit(0, root, List.of("git", "clone", "--no-local", workspaceRepo.toString(), seed.toString()), Map.of(), Duration.ofMinutes(2));

        runAndRequireExit(0, seed, List.of("git", "config", "user.email", "test@example.com"), Map.of(), Duration.ofSeconds(30));
        runAndRequireExit(0, seed, List.of("git", "config", "user.name", "Self Upgrade Test"), Map.of(), Duration.ofSeconds(30));
        runAndRequireExit(0, seed, List.of("git", "remote", "set-url", "origin", remote.toString()), Map.of(), Duration.ofSeconds(30));
        runAndRequireExit(0, seed, List.of("git", "push", "origin", "HEAD:refs/heads/main"), Map.of(), Duration.ofSeconds(30));

        runAndRequireExit(0, root, List.of("git", "clone", "--branch", "main", remote.toString(), app.toString()), Map.of(), Duration.ofSeconds(30));
        String branch = firstLine(runAndRequireExit(0, app, List.of("git", "rev-parse", "--abbrev-ref", "HEAD"), Map.of(), Duration.ofSeconds(30)).output);
        assertFalse(branch.isBlank());

        String realGit = firstLine(runAndRequireExit(
                0,
                root,
                List.of("/bin/sh", "-lc", "command -v git"),
                Map.of(),
                Duration.ofSeconds(30)
        ).output);
        assertFalse(realGit.isBlank());

        createGitWrapper(wrapperDir, realGit);
        Files.writeString(gitCallsLog, "", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        createRestartProbeScript(restartScript);
        writeSelfUpgradeConfig(
                app,
                branch,
                "mvn -q -DskipTests compile",
                "/bin/sh " + shellQuote(restartScript.toAbsolutePath().toString()) + " " + shellQuote(restartMarker.toAbsolutePath().toString())
        );

        // Push one update to remote so updater has work to do.
        Path readme = seed.resolve("README.md");
        assertTrue(Files.exists(readme), "Expected README.md in seeded app repo.");
        Files.writeString(readme, "\nself-upgrade integration test update\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        runAndRequireExit(0, seed, List.of("git", "add", "README.md"), Map.of(), Duration.ofSeconds(30));
        runAndRequireExit(0, seed, List.of("git", "commit", "-m", "update-1"), Map.of(), Duration.ofSeconds(30));
        String remoteHeadAfterUpdate = firstLine(runAndRequireExit(0, seed, List.of("git", "rev-parse", "HEAD"), Map.of(), Duration.ofSeconds(30)).output);
        runAndRequireExit(0, seed, List.of("git", "push", "origin", "HEAD:refs/heads/main"), Map.of(), Duration.ofSeconds(30));

        // First updater run: should pull and apply update.
        ProcessResult firstRun = runHarness(app, wrapperDir, gitCallsLog, realGit);
        assertEquals(0, firstRun.exitCode, "First updater run failed:\n" + firstRun.output);

        String appHeadAfterFirstRun = firstLine(runAndRequireExit(0, app, List.of("git", "rev-parse", "HEAD"), Map.of(), Duration.ofSeconds(30)).output);
        assertEquals(remoteHeadAfterUpdate, appHeadAfterFirstRun);

        List<String> gitCallsFirstRun = readLinesSafe(gitCallsLog);
        assertTrue(gitCallsFirstRun.stream().anyMatch(line -> line.contains(" pull --ff-only ") || line.startsWith("pull --ff-only ")),
                "Expected updater to run git pull when update exists. Calls: " + gitCallsFirstRun);

        Properties statusAfterFirstRun = readProperties(app.resolve("data/shared/self_upgrade/self_upgrade_status.properties"));
        assertEquals("restarting", safe(statusAfterFirstRun.getProperty("last_status")));
        assertEquals(branch, safe(statusAfterFirstRun.getProperty("last_branch")));
        assertEquals(remoteHeadAfterUpdate, safe(statusAfterFirstRun.getProperty("last_commit_after")));
        assertEquals("0", safe(statusAfterFirstRun.getProperty("last_build_exit_code")));
        assertTrue(Files.isDirectory(app.resolve("target/classes")), "Expected compile output in target/classes.");
        assertTrue(waitForFile(restartMarker, Duration.ofSeconds(10)), "Expected restart marker file from restart probe.");
        String markerAfterFirstRun = Files.readString(restartMarker, StandardCharsets.UTF_8);
        assertTrue(markerAfterFirstRun.contains("restart-probe-started"), "Restart marker content missing.");

        // Second updater run without new commits: should skip pull.
        Files.writeString(gitCallsLog, "", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        ProcessResult secondRun = runHarness(app, wrapperDir, gitCallsLog, realGit);
        assertEquals(0, secondRun.exitCode, "Second updater run failed:\n" + secondRun.output);

        List<String> gitCallsSecondRun = readLinesSafe(gitCallsLog);
        assertFalse(gitCallsSecondRun.stream().anyMatch(line -> line.contains(" pull --ff-only ") || line.startsWith("pull --ff-only ")),
                "Did not expect git pull when no update exists. Calls: " + gitCallsSecondRun);

        Properties statusAfterSecondRun = readProperties(app.resolve("data/shared/self_upgrade/self_upgrade_status.properties"));
        assertEquals("no_updates", safe(statusAfterSecondRun.getProperty("last_status")));
        assertEquals(remoteHeadAfterUpdate, safe(statusAfterSecondRun.getProperty("last_commit_before")));
        String markerAfterSecondRun = Files.readString(restartMarker, StandardCharsets.UTF_8);
        assertEquals(markerAfterFirstRun, markerAfterSecondRun, "Restart probe should not run when no update exists.");
    }

    @Test
    void updater_rolls_back_to_previous_commit_when_build_fails() throws Exception {
        Path root = Files.createTempDirectory("self-upgrade-rollback-it-");
        Path remote = root.resolve("remote.git");
        Path seed = root.resolve("seed");
        Path app = root.resolve("app");
        Path wrapperDir = root.resolve("bin");
        Path gitCallsLog = root.resolve("git-calls.log");
        Path restartScript = root.resolve("restart-probe.sh");
        Path restartMarker = root.resolve("restart.marker");
        Path workspaceRepo = Path.of("").toAbsolutePath().normalize();

        runAndRequireExit(0, root, List.of("git", "init", "--bare", remote.toString()), Map.of(), Duration.ofSeconds(30));
        runAndRequireExit(0, root, List.of("git", "clone", "--no-local", workspaceRepo.toString(), seed.toString()), Map.of(), Duration.ofMinutes(2));

        runAndRequireExit(0, seed, List.of("git", "config", "user.email", "test@example.com"), Map.of(), Duration.ofSeconds(30));
        runAndRequireExit(0, seed, List.of("git", "config", "user.name", "Self Upgrade Test"), Map.of(), Duration.ofSeconds(30));
        runAndRequireExit(0, seed, List.of("git", "remote", "set-url", "origin", remote.toString()), Map.of(), Duration.ofSeconds(30));
        runAndRequireExit(0, seed, List.of("git", "push", "origin", "HEAD:refs/heads/main"), Map.of(), Duration.ofSeconds(30));

        runAndRequireExit(0, root, List.of("git", "clone", "--branch", "main", remote.toString(), app.toString()), Map.of(), Duration.ofSeconds(30));
        String branch = firstLine(runAndRequireExit(0, app, List.of("git", "rev-parse", "--abbrev-ref", "HEAD"), Map.of(), Duration.ofSeconds(30)).output);
        assertFalse(branch.isBlank());

        String realGit = firstLine(runAndRequireExit(
                0,
                root,
                List.of("/bin/sh", "-lc", "command -v git"),
                Map.of(),
                Duration.ofSeconds(30)
        ).output);
        assertFalse(realGit.isBlank());

        createGitWrapper(wrapperDir, realGit);
        Files.writeString(gitCallsLog, "", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        createRestartProbeScript(restartScript);
        writeSelfUpgradeConfig(
                app,
                branch,
                "mvn -q -DskipTests compile",
                "/bin/sh " + shellQuote(restartScript.toAbsolutePath().toString()) + " " + shellQuote(restartMarker.toAbsolutePath().toString())
        );

        String beforeHead = firstLine(runAndRequireExit(0, app, List.of("git", "rev-parse", "HEAD"), Map.of(), Duration.ofSeconds(30)).output);
        assertFalse(beforeHead.isBlank());

        Path brokenSource = seed.resolve("src/main/java/net/familylawandprobate/controversies/self_upgrade_compile_breaker.java");
        String brokenJava = "package net.familylawandprobate.controversies;\n"
                + "\n"
                + "public final class self_upgrade_compile_breaker {\n"
                + "    public static String value() {\n"
                + "        return \"compile-broken;\n"
                + "    }\n"
                + "}\n";
        Files.writeString(brokenSource, brokenJava, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        runAndRequireExit(0, seed, List.of("git", "add", brokenSource.toString()), Map.of(), Duration.ofSeconds(30));
        runAndRequireExit(0, seed, List.of("git", "commit", "-m", "introduce compile failure"), Map.of(), Duration.ofSeconds(30));
        String brokenHead = firstLine(runAndRequireExit(0, seed, List.of("git", "rev-parse", "HEAD"), Map.of(), Duration.ofSeconds(30)).output);
        runAndRequireExit(0, seed, List.of("git", "push", "origin", "HEAD:refs/heads/main"), Map.of(), Duration.ofSeconds(30));

        ProcessResult run = runHarness(app, wrapperDir, gitCallsLog, realGit);
        assertEquals(0, run.exitCode, "Updater run failed unexpectedly:\n" + run.output);

        String afterHead = firstLine(runAndRequireExit(0, app, List.of("git", "rev-parse", "HEAD"), Map.of(), Duration.ofSeconds(30)).output);
        assertEquals(beforeHead, afterHead, "Expected updater to rollback checkout after compile failure.");
        assertFalse(afterHead.equals(brokenHead), "Checkout should not remain on the broken commit.");
        assertFalse(waitForFile(restartMarker, Duration.ofSeconds(2)), "Restart command should not run after compile failure.");

        Properties status = readProperties(app.resolve("data/shared/self_upgrade/self_upgrade_status.properties"));
        assertEquals("error", safe(status.getProperty("last_status")));
        assertEquals(beforeHead, safe(status.getProperty("last_commit_before")));
        assertEquals(beforeHead, safe(status.getProperty("last_commit_after")));
        assertFalse("0".equals(safe(status.getProperty("last_build_exit_code"))), "Build exit code should indicate failure.");
        assertTrue(safe(status.getProperty("last_error")).contains("rolled back to " + beforeHead),
                "Expected rollback marker in status error: " + safe(status.getProperty("last_error")));
    }

    private static ProcessResult runHarness(Path appDir, Path wrapperDir, Path gitCallsLog, String realGit) throws Exception {
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toAbsolutePath().toString();
        String classpath = System.getProperty("java.class.path");
        return runAndRequireExit(
                0,
                appDir,
                List.of(javaBin, "-cp", classpath, "net.familylawandprobate.controversies.self_upgrade_scheduler_harness"),
                Map.of(
                        "PATH", wrapperDir + System.getProperty("path.separator") + System.getenv().getOrDefault("PATH", ""),
                        "GIT_CALLS_LOG", gitCallsLog.toAbsolutePath().toString(),
                        "REAL_GIT_BIN", realGit
                ),
                Duration.ofMinutes(5)
        );
    }

    private static void createRestartProbeScript(Path scriptPath) throws Exception {
        String script = "#!/bin/sh\n"
                + "marker=\"$1\"\n"
                + "echo \"restart-probe-started\" >> \"$marker\"\n"
                + "sleep 4\n";
        Files.writeString(scriptPath, script, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        scriptPath.toFile().setExecutable(true, true);
    }

    private static void createGitWrapper(Path wrapperDir, String realGit) throws Exception {
        Files.createDirectories(wrapperDir);
        Path wrapper = wrapperDir.resolve("git");
        String script = "#!/bin/sh\n"
                + "if [ -n \"$GIT_CALLS_LOG\" ]; then\n"
                + "  echo \"$@\" >> \"$GIT_CALLS_LOG\"\n"
                + "fi\n"
                + "exec \"$REAL_GIT_BIN\" \"$@\"\n";
        Files.writeString(wrapper, script, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        wrapper.toFile().setExecutable(true, true);
        assertNotNull(realGit);
        assertFalse(realGit.isBlank());
    }

    private static void writeSelfUpgradeConfig(Path appDir, String branch, String buildCommand, String restartCommand) throws Exception {
        Path configPath = appDir.resolve("data/shared/self_upgrade/self_upgrade.properties");
        Files.createDirectories(configPath.getParent());
        Properties p = new Properties();
        p.setProperty("enabled", "true");
        p.setProperty("schedule_day_of_week", "SATURDAY");
        p.setProperty("schedule_time_local", "04:00");
        p.setProperty("schedule_zone", "America/Chicago");
        p.setProperty("git_remote", "origin");
        p.setProperty("git_branch", safe(branch));
        p.setProperty("build_command", safe(buildCommand));
        p.setProperty("restart_command", safe(restartCommand));
        // Test harness writes config under data/, so the worktree is intentionally dirty.
        p.setProperty("allow_dirty_worktree", "true");
        p.setProperty("command_timeout_minutes", "2");
        try (OutputStream out = Files.newOutputStream(
                configPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            p.store(out, "integration_test_self_upgrade_config");
        }
    }

    private static Properties readProperties(Path path) throws Exception {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(path, StandardOpenOption.READ)) {
            p.load(in);
        }
        return p;
    }

    private static String shellQuote(String value) {
        String s = value == null ? "" : value;
        if (s.isEmpty()) return "''";
        return "'" + s.replace("'", "'\"'\"'") + "'";
    }

    private static boolean waitForFile(Path path, Duration timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + Math.max(1000L, timeout.toMillis());
        while (System.currentTimeMillis() < deadline) {
            if (Files.isRegularFile(path)) return true;
            Thread.sleep(100L);
        }
        return Files.isRegularFile(path);
    }

    private static List<String> readLinesSafe(Path path) throws Exception {
        if (!Files.exists(path)) return new ArrayList<String>();
        return Files.readAllLines(path, StandardCharsets.UTF_8);
    }

    private static String firstLine(String text) {
        String s = safe(text);
        int nl = s.indexOf('\n');
        return (nl < 0 ? s : s.substring(0, nl)).trim();
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }

    private static ProcessResult runAndRequireExit(int expectedExit,
                                                   Path cwd,
                                                   List<String> command,
                                                   Map<String, String> envOverrides,
                                                   Duration timeout) throws Exception {
        ProcessResult result = run(cwd, command, envOverrides, timeout);
        assertEquals(expectedExit, result.exitCode,
                "Unexpected exit for command " + command + " in " + cwd + "\nOutput:\n" + result.output);
        return result;
    }

    private static ProcessResult run(Path cwd,
                                     List<String> command,
                                     Map<String, String> envOverrides,
                                     Duration timeout) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(cwd.toFile());
        pb.redirectErrorStream(true);
        if (envOverrides != null) {
            pb.environment().putAll(envOverrides);
        }
        Process process = pb.start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = process.getInputStream()) {
            in.transferTo(out);
        }
        boolean finished = process.waitFor(Math.max(1000L, timeout.toMillis()), java.util.concurrent.TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            return new ProcessResult(-1, out.toString(StandardCharsets.UTF_8));
        }
        return new ProcessResult(process.exitValue(), out.toString(StandardCharsets.UTF_8));
    }

    private static final class ProcessResult {
        final int exitCode;
        final String output;

        ProcessResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output == null ? "" : output;
        }
    }
}
