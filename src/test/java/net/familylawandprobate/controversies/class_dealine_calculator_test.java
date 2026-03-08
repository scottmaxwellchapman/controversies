package net.familylawandprobate.controversies;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.HashSet;
import java.util.UUID;

import org.junit.jupiter.api.Test;

public class class_dealine_calculator_test {

    @Test
    void business_days_use_tenant_holiday_xml() throws Exception {
        String tenantUuid = "tenant-deadline-holiday-" + UUID.randomUUID();
        Path tenantRoot = Paths.get("data", "tenants", tenantUuid).toAbsolutePath();
        deleteQuietly(tenantRoot);

        String holidayXml =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<holidaySet id=\"custom\" label=\"Custom\">" +
                "  <holiday id=\"closed_day\" type=\"Tenant\" label=\"Closed Day\" computation=\"FIXED_DATE\" month=\"3\" day=\"16\" court=\"NO\" />" +
                "</holidaySet>";

        String deadlineXml =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<deadlineSet id=\"test\" label=\"Test\">" +
                "  <triggers><trigger id=\"start\" label=\"Start\" date=\"2026-03-13\"/></triggers>" +
                "  <actions>" +
                "    <action uuid=\"due\" label=\"Due\" operator=\"OFFSET\" baseRef=\"start\" offsetDays=\"1\" countMethod=\"BUSINESS_DAYS\" adjust=\"NONE\"/>" +
                "  </actions>" +
                "</deadlineSet>";

        try {
            class_holiday_calculator.writeTenantHolidayXml(tenantUuid, holidayXml);
            class_dealine_calculator.EngineOptions options = new class_dealine_calculator.EngineOptions(
                    class_dealine_calculator.Jurisdiction.TEXAS_CIVIL,
                    ZoneId.of("America/Chicago"),
                    LocalTime.of(23, 59, 59),
                    new HashSet<LocalDate>(),
                    false,
                    tenantUuid
            );

            class_dealine_calculator.ActionDateInfo[] out = class_dealine_calculator.calculateDeadlines(
                    new ByteArrayInputStream(deadlineXml.getBytes(StandardCharsets.UTF_8)),
                    options
            );

            assertEquals(1, out.length);
            assertEquals("2026-03-17", out[0].getDate());
        } finally {
            deleteQuietly(tenantRoot);
        }
    }

    @Test
    void tenant_deadline_xml_default_is_created() throws Exception {
        String tenantUuid = "tenant-deadline-default-" + UUID.randomUUID();
        Path tenantRoot = Paths.get("data", "tenants", tenantUuid).toAbsolutePath();
        deleteQuietly(tenantRoot);

        try {
            Path xml = class_dealine_calculator.ensureTenantDeadlineXml(tenantUuid);
            assertTrue(xml != null && Files.exists(xml));
            class_dealine_calculator.ActionDateInfo[] out = class_dealine_calculator.calculateDeadlinesForTenant(tenantUuid);
            assertTrue(out.length > 0);
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
