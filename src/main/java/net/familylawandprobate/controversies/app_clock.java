package net.familylawandprobate.controversies;

import org.apache.commons.net.ntp.NTPUDPClient;
import org.apache.commons.net.ntp.TimeInfo;

import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Centralized runtime clock with NTP synchronization and safe system-clock fallback.
 */
public final class app_clock {

    private static final Logger LOG = Logger.getLogger(app_clock.class.getName());

    private static final String ENABLED_ENV = "CONTROVERSIES_NTP_ENABLED";
    private static final String ENABLED_PROP = "controversies.ntp.enabled";
    private static final String SERVERS_ENV = "CONTROVERSIES_NTP_SERVERS";
    private static final String SERVERS_PROP = "controversies.ntp.servers";
    private static final String TIMEOUT_ENV = "CONTROVERSIES_NTP_TIMEOUT_MS";
    private static final String TIMEOUT_PROP = "controversies.ntp.timeout.ms";
    private static final String INTERVAL_ENV = "CONTROVERSIES_NTP_SYNC_INTERVAL_SECONDS";
    private static final String INTERVAL_PROP = "controversies.ntp.sync.interval.seconds";

    private static final String[] DEFAULT_NTP_SERVERS = new String[] {
            "time.cloudflare.com",
            "time.google.com",
            "pool.ntp.org"
    };
    private static final int DEFAULT_TIMEOUT_MS = 1500;
    private static final long DEFAULT_SYNC_INTERVAL_SECONDS = 900L;
    private static final long MIN_SYNC_INTERVAL_SECONDS = 30L;

    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static volatile ScheduledExecutorService scheduler;
    private static volatile long offsetMillis = 0L;
    private static volatile String source = "system";

    private app_clock() {}

    public static Instant now() {
        ensureStarted();
        return Instant.ofEpochMilli(currentTimeMillis());
    }

    public static long currentTimeMillis() {
        ensureStarted();
        return System.currentTimeMillis() + offsetMillis;
    }

    public static ZonedDateTime now(ZoneId zone) {
        ZoneId useZone = zone == null ? ZoneOffset.UTC : zone;
        return ZonedDateTime.ofInstant(now(), useZone);
    }

    public static LocalDate todayUtc() {
        return now(ZoneOffset.UTC).toLocalDate();
    }

    public static LocalDate today() {
        return now(ZoneId.systemDefault()).toLocalDate();
    }

    public static Clock utcClock() {
        ensureStarted();
        return Clock.offset(Clock.systemUTC(), Duration.ofMillis(offsetMillis));
    }

    public static void startIfEnabled() {
        ensureStarted();
    }

    public static void syncNow() {
        ensureStarted();
        synchronizeClockOnce();
    }

    public static String source() {
        return source;
    }

    private static void ensureStarted() {
        if (STARTED.get()) return;
        synchronized (app_clock.class) {
            if (STARTED.get()) return;
            STARTED.set(true);
            if (!isEnabled()) {
                source = "system (ntp disabled)";
                LOG.info("NTP synchronization disabled; using system clock.");
                return;
            }
            long intervalSeconds = syncIntervalSeconds();
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "controversies-ntp-clock");
                t.setDaemon(true);
                return t;
            });
            // Initial sync immediately, then periodic refresh.
            scheduler.scheduleWithFixedDelay(app_clock::synchronizeClockOnce, 0L, intervalSeconds, TimeUnit.SECONDS);
            LOG.info(() -> "NTP clock synchronization enabled. servers=" + String.join(",", ntpServers())
                    + ", timeoutMs=" + timeoutMs() + ", intervalSeconds=" + intervalSeconds);
        }
    }

    private static void synchronizeClockOnce() {
        List<String> servers = ntpServers();
        if (servers.isEmpty()) return;
        int timeout = timeoutMs();
        for (String server : servers) {
            String host = safe(server).trim();
            if (host.isBlank()) continue;
            NTPUDPClient client = new NTPUDPClient();
            client.setDefaultTimeout(timeout);
            try {
                TimeInfo info = client.getTime(InetAddress.getByName(host));
                info.computeDetails();
                Long measuredOffset = info.getOffset();
                if (measuredOffset == null) continue;
                offsetMillis = measuredOffset.longValue();
                source = "ntp:" + host;
                LOG.fine(() -> "NTP clock synchronized from " + host + " (offsetMillis=" + offsetMillis + ").");
                return;
            } catch (Exception ex) {
                LOG.log(Level.FINE, "NTP sync attempt failed for " + host + ": " + ex.getMessage(), ex);
            } finally {
                try {
                    client.close();
                } catch (Exception ignored) {}
            }
        }
        LOG.warning("Unable to synchronize NTP clock from configured servers; retaining existing clock offset.");
    }

    private static boolean isEnabled() {
        String value = readConfig(ENABLED_PROP, ENABLED_ENV);
        if (value == null || value.trim().isBlank()) return true;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if ("0".equals(normalized) || "false".equals(normalized) || "no".equals(normalized) || "off".equals(normalized)) {
            return false;
        }
        return true;
    }

    private static int timeoutMs() {
        long parsed = parseLong(readConfig(TIMEOUT_PROP, TIMEOUT_ENV), DEFAULT_TIMEOUT_MS);
        if (parsed < 250L) return 250;
        if (parsed > 30000L) return 30000;
        return (int) parsed;
    }

    private static long syncIntervalSeconds() {
        long parsed = parseLong(readConfig(INTERVAL_PROP, INTERVAL_ENV), DEFAULT_SYNC_INTERVAL_SECONDS);
        return Math.max(MIN_SYNC_INTERVAL_SECONDS, parsed);
    }

    private static List<String> ntpServers() {
        String configured = readConfig(SERVERS_PROP, SERVERS_ENV);
        ArrayList<String> out = new ArrayList<String>();
        if (configured == null || configured.trim().isBlank()) {
            for (String def : DEFAULT_NTP_SERVERS) out.add(def);
            return out;
        }
        String[] parts = configured.split(",");
        for (String part : parts) {
            String host = safe(part).trim();
            if (!host.isBlank()) out.add(host);
        }
        if (out.isEmpty()) {
            for (String def : DEFAULT_NTP_SERVERS) out.add(def);
        }
        return out;
    }

    private static String readConfig(String propName, String envName) {
        String propValue = System.getProperty(propName);
        if (propValue != null && !propValue.trim().isBlank()) return propValue;
        return System.getenv(envName);
    }

    private static long parseLong(String raw, long fallback) {
        String value = safe(raw).trim();
        if (value.isBlank()) return fallback;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
