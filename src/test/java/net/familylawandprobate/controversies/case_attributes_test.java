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
