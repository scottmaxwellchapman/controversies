package net.familylawandprobate.controversies;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.familylawandprobate.controversies.integrations.office365.Office365CalendarSyncService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class office365_calendar_sync_test {

    @Test
    void syncs_office365_calendars_and_events_into_local_calendar_store() throws Exception {
        Assumptions.assumeTrue(canBindLoopbackSocket(), "Loopback socket binding is not permitted in this environment.");

        String tenantUuid = "office365-calendar-sync-" + UUID.randomUUID();
        HttpServer server = null;
        try {
            ensurePepper();

            users_roles users = users_roles.defaultStore();
            users.ensure(tenantUuid);
            users_roles.RoleRec role = users.createRole(tenantUuid, "Calendar Sync Role", true);
            users.setRolePermission(tenantUuid, role.uuid, "calendar.access", "true");
            users_roles.UserRec user = users.createUser(tenantUuid, "usera@example.test", role.uuid, true, "StrongPass_2026!".toCharArray(), true);
            assertNotNull(user);

            tenant_settings settingsStore = tenant_settings.defaultStore();
            LinkedHashMap<String, String> settings = settingsStore.read(tenantUuid);
            settings.put("office365_calendar_sync_enabled", "true");
            settings.put("office365_calendar_sync_interval_minutes", "15");
            settings.put("office365_calendar_sync_sources_json", "["
                    + "{\"source_id\":\"source_a\",\"tenant_id\":\"tenant-a\",\"client_id\":\"cid-a\",\"client_secret\":\"sec-a\",\"user_principal\":\"usera@example.test\",\"enabled\":true}"
                    + "]");
            settingsStore.write(tenantUuid, settings);

            server = HttpServer.create(new InetSocketAddress(0), 0);
            int port = server.getAddress().getPort();

            server.createContext("/id/tenant-a/oauth2/v2.0/token", ex -> respondJson(ex, 200, "{\"access_token\":\"token-a\"}"));
            server.createContext("/graph/v1.0/users/usera@example.test/calendars", ex -> {
                respondJson(ex, 200,
                        "{\"value\":[{\"id\":\"cal-1\",\"name\":\"Firm Calendar\",\"timeZone\":\"UTC\"}]}"
                );
            });
            server.createContext("/graph/v1.0/users/usera@example.test/calendars/cal-1/events", ex -> {
                respondJson(ex, 200,
                        "{\"value\":["
                                + "{\"id\":\"evt-1\",\"subject\":\"Court Appearance\",\"bodyPreview\":\"Appear in court\",\"location\":{\"displayName\":\"Courtroom 3\"},\"start\":{\"dateTime\":\"2026-05-05T14:00:00Z\",\"timeZone\":\"UTC\"},\"end\":{\"dateTime\":\"2026-05-05T15:00:00Z\",\"timeZone\":\"UTC\"},\"isAllDay\":false,\"iCalUId\":\"ical-evt-1\",\"changeKey\":\"ck1\"},"
                                + "{\"id\":\"evt-2\",\"subject\":\"Mediation\",\"bodyPreview\":\"Remote mediation\",\"location\":{\"displayName\":\"Zoom\"},\"start\":{\"dateTime\":\"2026-05-06T16:00:00Z\",\"timeZone\":\"UTC\"},\"end\":{\"dateTime\":\"2026-05-06T17:00:00Z\",\"timeZone\":\"UTC\"},\"isAllDay\":false,\"iCalUId\":\"ical-evt-2\",\"changeKey\":\"ck2\"}"
                                + "]}"
                );
            });
            server.start();

            Office365CalendarSyncService sync = new Office365CalendarSyncService(
                    "http://127.0.0.1:" + port + "/id",
                    "http://127.0.0.1:" + port + "/graph"
            );

            Office365CalendarSyncService.SyncResult result = sync.syncCalendars(tenantUuid, true);
            assertTrue(result.ok, "sync error=" + result.error + " perSource=" + result.perSourceUpserts);
            assertEquals(1, result.sourcesProcessed);
            assertEquals(1, result.calendarsProcessed);
            assertEquals(2, result.eventsUpserted);

            calendar_system store = calendar_system.defaultStore();
            List<calendar_system.CalendarRec> calendars = store.listCalendarsForUser(tenantUuid, user.uuid, false, false);
            assertEquals(1, calendars.size());
            calendar_system.CalendarRec calendar = calendars.get(0);
            assertEquals("office365:source_a", safe(calendar.source));

            List<calendar_system.EventRec> events = store.listEventsForUser(tenantUuid, calendar.uuid, user.uuid, "", "", false);
            assertEquals(2, events.size());
            assertFalse(safe(events.get(0).sourceEventId).isBlank());

            LinkedHashMap<String, String> after = settingsStore.read(tenantUuid);
            assertEquals("ok", after.get("office365_calendar_last_sync_status"));
            assertFalse(String.valueOf(after.get("office365_calendar_last_sync_at")).isBlank());
        } finally {
            if (server != null) server.stop(0);
            deleteTenantDirQuiet(tenantUuid);
        }
    }

    private static void respondJson(HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = (body == null ? "" : body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (java.io.OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void ensurePepper() throws Exception {
        Path pepper = Paths.get("data", "sec", "random_pepper.bin").toAbsolutePath();
        Files.createDirectories(pepper.getParent());
        if (!Files.exists(pepper)) {
            Files.writeString(pepper, "test-pepper-material", StandardCharsets.UTF_8);
        }
    }

    private static void deleteTenantDirQuiet(String tenantUuid) {
        if (tenantUuid == null || tenantUuid.isBlank()) return;
        Path root = Paths.get("data", "tenants", tenantUuid).toAbsolutePath();
        if (!Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {
        }
    }

    private static boolean canBindLoopbackSocket() {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
