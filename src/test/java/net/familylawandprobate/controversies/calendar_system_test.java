package net.familylawandprobate.controversies;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class calendar_system_test {

    private final ArrayList<Path> cleanupRoots = new ArrayList<Path>();

    @AfterEach
    void cleanup() throws Exception {
        for (Path root : cleanupRoots) {
            deleteRecursively(root);
        }
    }

    @Test
    void supports_multi_user_multi_calendar_access_and_event_crud() throws Exception {
        String tenantUuid = "calendar-system-" + UUID.randomUUID();
        cleanupRoots.add(Paths.get("data", "tenants", tenantUuid).toAbsolutePath());

        calendar_system store = calendar_system.defaultStore();

        calendar_system.CalendarInput calIn = new calendar_system.CalendarInput();
        calIn.name = "Litigation";
        calIn.ownerUserUuid = "user-a";
        calIn.readUserUuidsCsv = "user-b";
        calIn.writeUserUuidsCsv = "user-c";

        calendar_system.CalendarRec calendar = store.createCalendar(tenantUuid, calIn, "user-a");
        assertFalse(safe(calendar.uuid).isBlank());

        List<calendar_system.CalendarRec> aCals = store.listCalendarsForUser(tenantUuid, "user-a", false, false);
        List<calendar_system.CalendarRec> bCals = store.listCalendarsForUser(tenantUuid, "user-b", false, false);
        List<calendar_system.CalendarRec> cCals = store.listCalendarsForUser(tenantUuid, "user-c", false, false);

        assertEquals(1, aCals.size());
        assertEquals(1, bCals.size());
        assertEquals(1, cCals.size());

        calendar_system.EventInput in = new calendar_system.EventInput();
        in.summary = "Temporary Orders Hearing";
        in.startAt = "2026-04-20T14:00:00Z";
        in.endAt = "2026-04-20T15:00:00Z";
        in.organizerUserUuid = "user-a";

        assertThrows(SecurityException.class, () -> store.putEventForUser(tenantUuid, calendar.uuid, in, "user-b"));

        calendar_system.EventRec created = store.putEventForUser(tenantUuid, calendar.uuid, in, "user-c");
        assertFalse(safe(created.uuid).isBlank());
        assertFalse(safe(created.etag).isBlank());

        List<calendar_system.EventRec> bEvents = store.listEventsForUser(
                tenantUuid,
                calendar.uuid,
                "user-b",
                "2026-04-01T00:00:00Z",
                "2026-05-01T00:00:00Z",
                false
        );
        assertEquals(1, bEvents.size());
        assertEquals("Temporary Orders Hearing", safe(bEvents.get(0).summary));

        boolean deleted = store.deleteEventByUuidForUser(tenantUuid, calendar.uuid, created.uuid, "user-c");
        assertTrue(deleted);

        List<calendar_system.EventRec> afterDelete = store.listEventsForUser(
                tenantUuid,
                calendar.uuid,
                "user-a",
                "",
                "",
                false
        );
        assertEquals(0, afterDelete.size());
    }

    @Test
    void parses_and_renders_ics_event_fields() throws Exception {
        String tenantUuid = "calendar-ics-" + UUID.randomUUID();
        cleanupRoots.add(Paths.get("data", "tenants", tenantUuid).toAbsolutePath());

        calendar_system store = calendar_system.defaultStore();

        calendar_system.CalendarInput calIn = new calendar_system.CalendarInput();
        calIn.name = "Court Calendar";
        calIn.ownerUserUuid = "owner-user";
        calendar_system.CalendarRec calendar = store.createCalendar(tenantUuid, calIn, "owner-user");

        String ics = "BEGIN:VCALENDAR\r\n"
                + "VERSION:2.0\r\n"
                + "BEGIN:VEVENT\r\n"
                + "UID:abc-123@example.test\r\n"
                + "SUMMARY:Final Trial\r\n"
                + "DESCRIPTION:Bench trial\r\n"
                + "LOCATION:County Court\r\n"
                + "DTSTART:20260510T150000Z\r\n"
                + "DTEND:20260510T170000Z\r\n"
                + "END:VEVENT\r\n"
                + "END:VCALENDAR\r\n";

        calendar_system.EventInput in = store.parseIcsEvent(ics, "fallback@example.test");
        assertEquals("abc-123@example.test", safe(in.uid));
        assertEquals("Final Trial", safe(in.summary));

        calendar_system.EventRec saved = store.putEventForUser(tenantUuid, calendar.uuid, in, "owner-user");
        String outIcs = store.toIcs(saved);

        assertTrue(outIcs.contains("UID:abc-123@example.test"));
        assertTrue(outIcs.contains("SUMMARY:Final Trial"));
        assertTrue(outIcs.contains("LOCATION:County Court"));
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (root == null || !Files.exists(root)) return;
        Files.walk(root)
                .sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                    }
                });
    }
}
