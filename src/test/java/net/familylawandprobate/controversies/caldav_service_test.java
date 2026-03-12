package net.familylawandprobate.controversies;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class caldav_service_test {

    private static final String PASSWORD = "CalDavPass_2026!Strong";

    private final ArrayList<Path> cleanupRoots = new ArrayList<Path>();

    @AfterEach
    void cleanup() throws Exception {
        for (Path root : cleanupRoots) {
            deleteRecursively(root);
        }
    }

    @Test
    void supports_caldav_propfind_report_put_get_and_delete() throws Exception {
        TestCtx ctx = createContext();

        String homePath = "/calendars/" + ctx.principal.tenantSlug + "/" + ctx.principal.email + "/";
        List<caldav_service.Resource> homeRows = ctx.service.propfind(ctx.principal, homePath, 1);

        caldav_service.Resource calendar = homeRows.stream()
                .filter(r -> r != null && r.kind == caldav_service.Kind.CALENDAR)
                .findFirst()
                .orElseThrow();
        assertNotNull(calendar.calendar);

        String calendarPath = "/" + String.join("/", calendar.canonicalSegments) + "/";

        String ics = "BEGIN:VCALENDAR\r\n"
                + "VERSION:2.0\r\n"
                + "BEGIN:VEVENT\r\n"
                + "UID:caldav-test-event@example.test\r\n"
                + "SUMMARY:Status Conference\r\n"
                + "DTSTART:20260601T150000Z\r\n"
                + "DTEND:20260601T160000Z\r\n"
                + "END:VEVENT\r\n"
                + "END:VCALENDAR\r\n";

        caldav_service.PutResult saved = ctx.service.putCalendarObject(
                ctx.principal,
                calendarPath + "status-conference.ics",
                new ByteArrayInputStream(ics.getBytes(StandardCharsets.UTF_8))
        );
        assertTrue(saved.created);
        assertNotNull(saved.resource);
        assertNotNull(saved.resource.event);

        List<caldav_service.Resource> reportRows = ctx.service.reportCalendarQuery(
                ctx.principal,
                calendarPath,
                "2026-06-01T00:00:00Z",
                "2026-06-02T00:00:00Z"
        );
        assertEquals(1, reportRows.size());

        caldav_service.Resource loaded = ctx.service.eventForGet(ctx.principal, "/" + String.join("/", saved.resource.canonicalSegments));
        String roundTrip = ctx.service.eventAsIcs(loaded);
        assertTrue(roundTrip.contains("SUMMARY:Status Conference"));
        assertTrue(roundTrip.contains("UID:caldav-test-event@example.test"));

        ctx.service.deleteCalendarObject(ctx.principal, "/" + String.join("/", saved.resource.canonicalSegments));

        List<caldav_service.Resource> afterDelete = ctx.service.reportCalendarQuery(
                ctx.principal,
                calendarPath,
                "",
                ""
        );
        assertEquals(0, afterDelete.size());
    }

    @Test
    void honors_shared_calendar_read_without_write() throws Exception {
        TestCtx ownerCtx = createContext();
        TestCtx readerCtx = createContext(ownerCtx.tenantUuid, ownerCtx.tenantLabel, "reader@example.test");

        calendar_system store = calendar_system.defaultStore();
        calendar_system.CalendarInput in = new calendar_system.CalendarInput();
        in.name = "Shared Calendar";
        in.ownerUserUuid = ownerCtx.principal.userUuid;
        in.readUserUuidsCsv = readerCtx.principal.userUuid;
        calendar_system.CalendarRec shared = store.createCalendar(ownerCtx.tenantUuid, in, ownerCtx.principal.userUuid);

        List<caldav_service.Resource> readerHome = readerCtx.service.propfind(
                readerCtx.principal,
                "/calendars/" + readerCtx.principal.tenantSlug + "/" + readerCtx.principal.email + "/",
                1
        );
        boolean sawShared = false;
        for (caldav_service.Resource row : readerHome) {
            if (row == null || row.kind != caldav_service.Kind.CALENDAR || row.calendar == null) continue;
            if (shared.uuid.equals(safe(row.calendar.uuid))) {
                sawShared = true;
                break;
            }
        }
        assertTrue(sawShared);

        String putPath = "/calendars/" + readerCtx.principal.tenantSlug + "/" + readerCtx.principal.email
                + "/" + calendar_system.calendarSegment(shared) + "/reader-write.ics";

        caldav_service.CalDavException ex = assertThrows(
                caldav_service.CalDavException.class,
                () -> readerCtx.service.putCalendarObject(
                        readerCtx.principal,
                        putPath,
                        new ByteArrayInputStream("BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n".getBytes(StandardCharsets.UTF_8))
                )
        );
        assertTrue(ex.status == 404 || ex.status == 403);
    }

    private TestCtx createContext() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String tenantLabel = "CalDAV Tenant " + suffix;
        String email = "caldav_" + suffix + "@example.com";

        String tenantUuid = tenants.defaultStore().create(tenantLabel, PASSWORD.toCharArray());
        cleanupRoots.add(Paths.get("data", "tenants", tenantUuid).toAbsolutePath());

        users_roles users = users_roles.defaultStore();
        users.ensure(tenantUuid);
        users_roles.RoleRec role = users.createRole(tenantUuid, "CalDAV Role " + suffix, true);
        users.setRolePermission(tenantUuid, role.uuid, "calendar.access", "true");
        users.createUser(tenantUuid, email, role.uuid, true, PASSWORD.toCharArray(), true);

        caldav_service service = caldav_service.defaultService();
        String realmUser = webdav_service.tenantSlug(tenantLabel) + "\\" + email;
        caldav_service.Principal principal = service.authenticate(basicHeader(realmUser, PASSWORD));

        return new TestCtx(tenantUuid, tenantLabel, service, principal);
    }

    private TestCtx createContext(String tenantUuid, String tenantLabel, String email) throws Exception {
        cleanupRoots.add(Paths.get("data", "tenants", tenantUuid).toAbsolutePath());

        users_roles users = users_roles.defaultStore();
        users.ensure(tenantUuid);
        users_roles.RoleRec role = users.createRole(tenantUuid, "CalDAV Shared Role " + UUID.randomUUID().toString().substring(0, 6), true);
        users.setRolePermission(tenantUuid, role.uuid, "calendar.access", "true");
        users.createUser(tenantUuid, email, role.uuid, true, PASSWORD.toCharArray(), true);

        caldav_service service = caldav_service.defaultService();
        String realmUser = webdav_service.tenantSlug(tenantLabel) + "\\" + email;
        caldav_service.Principal principal = service.authenticate(basicHeader(realmUser, PASSWORD));

        return new TestCtx(tenantUuid, tenantLabel, service, principal);
    }

    private static String basicHeader(String username, String password) {
        String pair = safe(username) + ":" + safe(password);
        String token = Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8));
        return "Basic " + token;
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

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static final class TestCtx {
        final String tenantUuid;
        final String tenantLabel;
        final caldav_service service;
        final caldav_service.Principal principal;

        TestCtx(String tenantUuid,
                String tenantLabel,
                caldav_service service,
                caldav_service.Principal principal) {
            this.tenantUuid = tenantUuid;
            this.tenantLabel = tenantLabel;
            this.service = service;
            this.principal = principal;
        }
    }
}
