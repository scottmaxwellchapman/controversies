package net.familylawandprobate.controversies;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.UUID;

import org.junit.jupiter.api.Test;

public class class_holiday_calculator_test {

    @Test
    void creates_tenant_holiday_xml_with_defaults() throws Exception {
        String tenantUuid = "tenant-holiday-default-" + UUID.randomUUID();
        Path tenantRoot = Paths.get("data", "tenants", tenantUuid).toAbsolutePath();
        deleteQuietly(tenantRoot);

        try {
            Path xml = class_holiday_calculator.ensureTenantHolidayXml(tenantUuid);
            assertNotNull(xml);
            assertTrue(Files.exists(xml));

            class_holiday_calculator.HolidayInfo[] rows = class_holiday_calculator.calculateHolidays(tenantUuid, 2026);
            assertTrue(rows.length > 0);
            assertTrue(class_holiday_calculator.isHoliday(tenantUuid, LocalDate.of(2026, 11, 26)));
            assertTrue(class_holiday_calculator.isHoliday(tenantUuid, LocalDate.of(2026, 11, 27)));
        } finally {
            deleteQuietly(tenantRoot);
        }
    }

    @Test
    void custom_tenant_holiday_xml_is_used() throws Exception {
        String tenantUuid = "tenant-holiday-custom-" + UUID.randomUUID();
        Path tenantRoot = Paths.get("data", "tenants", tenantUuid).toAbsolutePath();
        deleteQuietly(tenantRoot);

        String xml =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<holidaySet id=\"custom\" label=\"Custom\">" +
                "  <holiday id=\"custom_day\" type=\"Tenant\" label=\"Custom Day\" computation=\"FIXED_DATE\" month=\"3\" day=\"16\" court=\"NO\" />" +
                "</holidaySet>";

        try {
            class_holiday_calculator.writeTenantHolidayXml(tenantUuid, xml);
            assertTrue(class_holiday_calculator.isHoliday(tenantUuid, LocalDate.of(2026, 3, 16)));
            assertFalse(class_holiday_calculator.isHoliday(tenantUuid, LocalDate.of(2026, 11, 26)));
        } finally {
            deleteQuietly(tenantRoot);
        }
    }

    private static void deleteQuietly(Path p) {
        try {
            if (p == null || !Files.exists(p)) return;
            try (var walk = Files.walk(p)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) {}
                });
            }
        } catch (Exception ignored) {}
    }
}
