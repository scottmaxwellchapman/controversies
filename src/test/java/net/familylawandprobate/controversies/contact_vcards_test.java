package net.familylawandprobate.controversies;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class contact_vcards_test {

    @Test
    void parse_many_supports_bulk_cards_and_multiple_addresses() {
        String payload =
                "BEGIN:VCARD\r\n" +
                "VERSION:3.0\r\n" +
                "FN:Alex Taylor\r\n" +
                "N:Taylor;Alex;;;\r\n" +
                "EMAIL;TYPE=WORK;TYPE=PREF:alex@work.test\r\n" +
                "EMAIL;TYPE=HOME:alex@home.test\r\n" +
                "TEL;TYPE=CELL:+1-214-555-0100\r\n" +
                "TEL;TYPE=WORK:+1-214-555-0101\r\n" +
                "ADR;TYPE=WORK:;;100 Main St;Dallas;TX;75001;US\r\n" +
                "ADR;TYPE=HOME:;;200 Oak Ave;Austin;TX;73301;US\r\n" +
                "END:VCARD\r\n" +
                "BEGIN:VCARD\r\n" +
                "VERSION:3.0\r\n" +
                "FN:Jordan Lane\r\n" +
                "EMAIL:jordan@test.example\r\n" +
                "TEL;TYPE=HOME:+1-713-555-0200\r\n" +
                "END:VCARD\r\n";

        List<contacts.ContactInput> rows = contact_vcards.parseMany(payload);
        assertEquals(2, rows.size());

        contacts.ContactInput first = rows.get(0);
        assertEquals("Alex Taylor", first.displayName);
        assertEquals("alex@work.test", first.emailPrimary);
        assertEquals("alex@home.test", first.emailSecondary);
        assertEquals("+1-214-555-0100", first.mobilePhone);
        assertEquals("+1-214-555-0101", first.businessPhone);
        assertEquals("100 Main St", first.street);
        assertEquals("200 Oak Ave", first.streetSecondary);

        contacts.ContactInput second = rows.get(1);
        assertEquals("Jordan Lane", second.displayName);
        assertEquals("jordan@test.example", second.emailPrimary);
        assertEquals("+1-713-555-0200", second.homePhone);
    }

    @Test
    void export_and_parse_round_trip_keeps_multivalue_fields() throws Exception {
        String tenantUuid = "vcard-test-" + UUID.randomUUID();
        try {
            contacts store = contacts.defaultStore();
            contacts.ContactInput in = new contacts.ContactInput();
            in.displayName = "Round Trip";
            in.givenName = "Round";
            in.surname = "Trip";
            in.emailPrimary = "round.trip@example.test";
            in.emailSecondary = "round.trip.home@example.test";
            in.businessPhone = "+1-469-555-0300";
            in.mobilePhone = "+1-469-555-0301";
            in.street = "100 Main St";
            in.city = "Dallas";
            in.state = "TX";
            in.postalCode = "75001";
            in.country = "US";
            in.streetSecondary = "200 Oak Ave";
            in.citySecondary = "Austin";
            in.stateSecondary = "TX";
            in.postalCodeSecondary = "73301";
            in.countrySecondary = "US";
            in.notes = "line one\nline two";

            contacts.ContactRec rec = store.createNative(tenantUuid, in);
            String vcf = contact_vcards.exportOne(rec);
            assertTrue(vcf.contains("BEGIN:VCARD"));
            assertTrue(vcf.contains("EMAIL;TYPE=INTERNET;TYPE=WORK;TYPE=PREF:round.trip@example.test"));
            assertTrue(vcf.contains("ADR;TYPE=HOME:;;200 Oak Ave;Austin;TX;73301;US"));

            List<contacts.ContactInput> parsed = contact_vcards.parseMany(vcf);
            assertEquals(1, parsed.size());
            contacts.ContactInput out = parsed.get(0);
            assertEquals("Round Trip", out.displayName);
            assertEquals("round.trip@example.test", out.emailPrimary);
            assertEquals("round.trip.home@example.test", out.emailSecondary);
            assertEquals("+1-469-555-0300", out.businessPhone);
            assertEquals("+1-469-555-0301", out.mobilePhone);
            assertEquals("100 Main St", out.street);
            assertEquals("200 Oak Ave", out.streetSecondary);
            assertFalse(out.notes.isBlank());
        } finally {
            deleteTenantDirQuiet(tenantUuid);
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
}

