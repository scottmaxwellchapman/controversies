package net.familylawandprobate.controversies;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class self_upgrade_scheduler_test {

    @Test
    void parse_day_of_week_uses_fallback_when_invalid() {
        assertEquals(DayOfWeek.SATURDAY, self_upgrade_scheduler.parseDayOfWeek("not-a-day", DayOfWeek.SATURDAY));
        assertEquals(DayOfWeek.MONDAY, self_upgrade_scheduler.parseDayOfWeek("monday", DayOfWeek.SATURDAY));
    }

    @Test
    void parse_local_time_uses_default_when_invalid() {
        assertEquals(LocalTime.of(4, 0), self_upgrade_scheduler.parseLocalTime("99:99", LocalTime.of(4, 0)));
        assertEquals(LocalTime.of(3, 30), self_upgrade_scheduler.parseLocalTime("03:30", LocalTime.of(4, 0)));
    }

    @Test
    void next_scheduled_run_is_always_in_future() {
        ZonedDateTime now = ZonedDateTime.of(2026, 3, 7, 4, 0, 0, 0, ZoneId.of("America/Chicago"));
        ZonedDateTime next = self_upgrade_scheduler.nextScheduledRun(now, DayOfWeek.SATURDAY, LocalTime.of(4, 0));
        assertTrue(next.isAfter(now));
        assertEquals(DayOfWeek.SATURDAY, next.getDayOfWeek());
        assertEquals(14, next.getDayOfMonth());
    }

    @Test
    void parse_boolean_and_int_support_expected_values() {
        assertTrue(self_upgrade_scheduler.parseBoolean("yes", false));
        assertFalse(self_upgrade_scheduler.parseBoolean("off", true));
        assertEquals(30, self_upgrade_scheduler.parseInt("abc", 30, 1, 240));
        assertEquals(60, self_upgrade_scheduler.parseInt("60", 30, 1, 240));
    }
}
