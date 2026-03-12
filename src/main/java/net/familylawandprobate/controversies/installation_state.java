package net.familylawandprobate.controversies;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * Persists one-time installer completion state.
 *
 * Storage:
 *   data/sec/install_state.properties
 */
public final class installation_state {

    public static final String KEY_COMPLETED = "completed";
    public static final String KEY_COMPLETED_AT = "completed_at";
    public static final String KEY_TENANT_UUID = "tenant_uuid";
    public static final String KEY_ADMIN_EMAIL = "admin_email";

    private final Path statePath;
    private final Object lock = new Object();

    public installation_state(Path statePath) {
        this.statePath = Objects.requireNonNull(statePath, "statePath").toAbsolutePath();
    }

    public static installation_state defaultStore() {
        return new installation_state(Paths.get("data", "sec", "install_state.properties"));
    }

    public boolean isCompleted() {
        Map<String, String> state = read();
        return "true".equalsIgnoreCase(safe(state.get(KEY_COMPLETED)).trim());
    }

    public LinkedHashMap<String, String> read() {
        synchronized (lock) {
            Properties p = loadLocked();
            LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
            out.put(KEY_COMPLETED, safe(p.getProperty(KEY_COMPLETED)));
            out.put(KEY_COMPLETED_AT, safe(p.getProperty(KEY_COMPLETED_AT)));
            out.put(KEY_TENANT_UUID, safe(p.getProperty(KEY_TENANT_UUID)));
            out.put(KEY_ADMIN_EMAIL, safe(p.getProperty(KEY_ADMIN_EMAIL)));
            return out;
        }
    }

    public void markCompleted(String tenantUuid, String adminEmail) throws Exception {
        String tu = safe(tenantUuid).trim();
        String em = safe(adminEmail).trim().toLowerCase(Locale.ROOT);
        if (tu.isBlank()) throw new IllegalArgumentException("tenantUuid required");
        if (!looksLikeEmail(em)) throw new IllegalArgumentException("adminEmail must be a valid email address");

        synchronized (lock) {
            Properties p = loadLocked();
            p.setProperty(KEY_COMPLETED, "true");
            p.setProperty(KEY_COMPLETED_AT, app_clock.now().toString());
            p.setProperty(KEY_TENANT_UUID, tu);
            p.setProperty(KEY_ADMIN_EMAIL, em);
            saveLocked(p);
        }
    }

    public void clear() throws Exception {
        synchronized (lock) {
            Files.deleteIfExists(statePath);
        }
    }

    private Properties loadLocked() {
        Properties p = new Properties();
        if (!Files.exists(statePath)) return p;
        try (InputStream in = Files.newInputStream(statePath, StandardOpenOption.READ)) {
            p.load(in);
        } catch (Exception ignored) {
            return new Properties();
        }
        return p;
    }

    private void saveLocked(Properties p) throws Exception {
        Files.createDirectories(statePath.getParent());

        Path tmp = statePath.resolveSibling(statePath.getFileName().toString() + ".tmp");
        try (OutputStream out = Files.newOutputStream(
                tmp,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            p.store(out, "Controversies installer state");
        }

        try {
            Files.move(tmp, statePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(tmp, statePath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static boolean looksLikeEmail(String email) {
        String e = safe(email).trim();
        int at = e.indexOf('@');
        if (at <= 0 || at >= e.length() - 1) return false;
        return e.indexOf('@', at + 1) < 0;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
