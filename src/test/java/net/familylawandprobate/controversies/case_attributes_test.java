package net.familylawandprobate.controversies;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

public class case_attributes_test {

    @Test
    void ensure_seeds_default_built_in_case_attributes() throws Exception {
        String tenantUuid = "case-attrs-" + UUID.randomUUID();
        Path tenantDir = Paths.get("data", "tenants", tenantUuid).toAbsolutePath();
        deleteQuietly(tenantDir);

        try {
            case_attributes store = case_attributes.defaultStore();
            store.ensure(tenantUuid);

            List<case_attributes.AttributeRec> all = store.listAll(tenantUuid);
            assertFalse(all.isEmpty());

            boolean foundCause = false;
            boolean foundCounty = false;
            for (int i = 0; i < all.size(); i++) {
                case_attributes.AttributeRec r = all.get(i);
                if (r == null) continue;
                if ("cause_docket_number".equals(r.key)) {
                    foundCause = true;
                    assertTrue(r.builtIn);
                }
                if ("county".equals(r.key)) {
                    foundCounty = true;
                    assertTrue(r.builtIn);
                }
            }
            assertTrue(foundCause);
            assertTrue(foundCounty);
        } finally {
            deleteQuietly(tenantDir);
        }
    }

    @Test
    void save_supports_dropdown_data_type_and_options() throws Exception {
        String tenantUuid = "case-attrs-dropdown-" + UUID.randomUUID();
        Path tenantDir = Paths.get("data", "tenants", tenantUuid).toAbsolutePath();
        deleteQuietly(tenantDir);

        try {
            case_attributes store = case_attributes.defaultStore();
            store.ensure(tenantUuid);

            List<case_attributes.AttributeRec> all = new ArrayList<case_attributes.AttributeRec>(store.listAll(tenantUuid));
            all.add(new case_attributes.AttributeRec(
                    "",
                    "venue",
                    "Venue",
                    "select",
                    "Federal\nState\nCounty",
                    true,
                    true,
                    false,
                    50,
                    ""
            ));
            store.saveAll(tenantUuid, all);

            List<case_attributes.AttributeRec> saved = store.listAll(tenantUuid);
            case_attributes.AttributeRec venue = null;
            for (int i = 0; i < saved.size(); i++) {
                case_attributes.AttributeRec r = saved.get(i);
                if (r != null && "venue".equals(r.key)) {
                    venue = r;
                    break;
                }
            }
            assertTrue(venue != null);
            assertEquals("select", venue.dataType);
            assertEquals("Federal\nState\nCounty", venue.options);
            assertTrue(venue.required);
        } finally {
            deleteQuietly(tenantDir);
        }
    }

    @Test
    void save_supports_additional_data_types_and_aliases() throws Exception {
        String tenantUuid = "case-attrs-extra-types-" + UUID.randomUUID();
        Path tenantDir = Paths.get("data", "tenants", tenantUuid).toAbsolutePath();
        deleteQuietly(tenantDir);

        try {
            case_attributes store = case_attributes.defaultStore();
            store.ensure(tenantUuid);

            List<case_attributes.AttributeRec> all = new ArrayList<case_attributes.AttributeRec>(store.listAll(tenantUuid));
            all.add(new case_attributes.AttributeRec("", "notify_client", "Notify Client", "checkbox", "unused", false, true, false, 60, ""));
            all.add(new case_attributes.AttributeRec("", "hearing_at", "Hearing At", "datetime-local", "", false, true, false, 70, ""));
            all.add(new case_attributes.AttributeRec("", "call_time", "Call Time", "time", "", false, true, false, 80, ""));
            all.add(new case_attributes.AttributeRec("", "contact_email", "Contact Email", "email", "", false, true, false, 90, ""));
            all.add(new case_attributes.AttributeRec("", "contact_phone", "Contact Phone", "tel", "", false, true, false, 100, ""));
            all.add(new case_attributes.AttributeRec("", "portal_url", "Portal Url", "uri", "", false, true, false, 110, ""));
            store.saveAll(tenantUuid, all);

            List<case_attributes.AttributeRec> saved = store.listAll(tenantUuid);
            case_attributes.AttributeRec notify = null;
            case_attributes.AttributeRec hearingAt = null;
            case_attributes.AttributeRec callTime = null;
            case_attributes.AttributeRec email = null;
            case_attributes.AttributeRec phone = null;
            case_attributes.AttributeRec url = null;
            for (int i = 0; i < saved.size(); i++) {
                case_attributes.AttributeRec r = saved.get(i);
                if (r == null) continue;
                if ("notify_client".equals(r.key)) notify = r;
                if ("hearing_at".equals(r.key)) hearingAt = r;
                if ("call_time".equals(r.key)) callTime = r;
                if ("contact_email".equals(r.key)) email = r;
                if ("contact_phone".equals(r.key)) phone = r;
                if ("portal_url".equals(r.key)) url = r;
            }

            assertTrue(notify != null);
            assertEquals("boolean", notify.dataType);
            assertEquals("", notify.options);

            assertTrue(hearingAt != null);
            assertEquals("datetime", hearingAt.dataType);

            assertTrue(callTime != null);
            assertEquals("time", callTime.dataType);

            assertTrue(email != null);
            assertEquals("email", email.dataType);

            assertTrue(phone != null);
            assertEquals("phone", phone.dataType);

            assertTrue(url != null);
            assertEquals("url", url.dataType);
        } finally {
            deleteQuietly(tenantDir);
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
