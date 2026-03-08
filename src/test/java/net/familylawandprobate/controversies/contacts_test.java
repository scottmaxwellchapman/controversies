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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class contacts_test {

    @Test
    void clio_contacts_are_read_only_but_native_contacts_are_editable() throws Exception {
        String tenantUuid = "contacts-test-" + UUID.randomUUID();
        try {
            contacts store = contacts.defaultStore();

            contacts.ContactInput nativeInput = new contacts.ContactInput();
            nativeInput.displayName = "Native Contact";
            nativeInput.emailPrimary = "native@example.test";
            nativeInput.street = "100 Main St";
            nativeInput.city = "Dallas";
            nativeInput.state = "TX";
            nativeInput.postalCode = "75001";
            nativeInput.country = "US";
            nativeInput.streetSecondary = "200 Oak Ave";
            nativeInput.citySecondary = "Austin";
            nativeInput.stateSecondary = "TX";
            nativeInput.postalCodeSecondary = "73301";
            nativeInput.countrySecondary = "US";
            contacts.ContactRec nativeRec = store.createNative(tenantUuid, nativeInput);
            assertFalse(contacts.isClioLocked(nativeRec));
            assertFalse(contacts.isExternalReadOnly(nativeRec));
            assertEquals("200 Oak Ave", nativeRec.streetSecondary);

            contacts.ContactInput update = new contacts.ContactInput();
            update.displayName = "Native Contact Updated";
            update.streetTertiary = "300 Pine Rd";
            update.cityTertiary = "Houston";
            update.stateTertiary = "TX";
            update.postalCodeTertiary = "77001";
            update.countryTertiary = "US";
            assertTrue(store.updateNative(tenantUuid, nativeRec.uuid, update));
            contacts.ContactRec updated = store.getByUuid(tenantUuid, nativeRec.uuid);
            assertEquals("Native Contact Updated", updated.displayName);
            assertEquals("300 Pine Rd", updated.streetTertiary);
            assertEquals("Houston", updated.cityTertiary);

            contacts.ContactInput clioInput = new contacts.ContactInput();
            clioInput.displayName = "Clio Contact";
            clioInput.emailPrimary = "clio@example.test";
            contacts.ContactRec clioRec = store.upsertClio(tenantUuid, clioInput, "clio-123", "2026-03-04T00:00:00Z");
            assertTrue(contacts.isClioLocked(clioRec));
            assertTrue(contacts.isExternalReadOnly(clioRec));

            contacts.ContactInput attemptUpdate = new contacts.ContactInput();
            attemptUpdate.displayName = "Should Fail";
            assertThrows(IllegalStateException.class, () -> store.updateNative(tenantUuid, clioRec.uuid, attemptUpdate));
            assertThrows(IllegalStateException.class, () -> store.trash(tenantUuid, clioRec.uuid));
        } finally {
            deleteTenantDirQuiet(tenantUuid);
        }
    }

    @Test
    void contact_change_events_trigger_bpm_runs_and_external_sources_are_read_only() throws Exception {
        String tenantUuid = "contacts-events-test-" + UUID.randomUUID();
        try {
            contacts store = contacts.defaultStore();
            business_process_manager bpm = business_process_manager.defaultService();

            business_process_manager.ProcessDefinition process = new business_process_manager.ProcessDefinition();
            process.name = "Contact Change Watcher";
            business_process_manager.ProcessTrigger trigger = new business_process_manager.ProcessTrigger();
            trigger.type = "contact.changed";
            process.triggers.add(trigger);
            business_process_manager.ProcessStep step = new business_process_manager.ProcessStep();
            step.stepId = "log";
            step.order = 10;
            step.action = "log_note";
            step.settings.put("message", "Contact changed: {{event.contact_uuid}}");
            process.steps.add(step);
            bpm.saveProcess(tenantUuid, process);

            contacts.ContactInput in = new contacts.ContactInput();
            in.displayName = "Native Event Contact";
            contacts.ContactRec nativeRec = store.createNative(tenantUuid, in);

            contacts.ContactInput update = new contacts.ContactInput();
            update.displayName = "Native Event Contact Updated";
            assertTrue(store.updateNative(tenantUuid, nativeRec.uuid, update));
            assertTrue(store.trash(tenantUuid, nativeRec.uuid));
            assertTrue(store.restore(tenantUuid, nativeRec.uuid));

            contacts.ContactInput externalIn = new contacts.ContactInput();
            externalIn.displayName = "Office Contact";
            contacts.ContactRec externalRec = store.upsertExternal(
                    tenantUuid,
                    externalIn,
                    "office365:directory",
                    "o365-contact-1",
                    "2026-03-05T00:00:00Z"
            );
            assertNotNull(externalRec);
            assertTrue(contacts.isExternalReadOnly(externalRec));
            assertThrows(IllegalStateException.class, () -> store.updateNative(tenantUuid, externalRec.uuid, new contacts.ContactInput()));
            assertThrows(IllegalStateException.class, () -> store.trash(tenantUuid, externalRec.uuid));
            assertThrows(IllegalStateException.class, () -> store.restore(tenantUuid, externalRec.uuid));

            List<business_process_manager.RunResult> runs = bpm.listRuns(tenantUuid, 100);
            assertEquals(5, runs.size());
        } finally {
            deleteTenantDirQuiet(tenantUuid);
        }
    }

    @Test
    void matter_contact_links_replace_source_scoped_rows() throws Exception {
        String tenantUuid = "matter-contacts-test-" + UUID.randomUUID();
        try {
            matter_contacts links = matter_contacts.defaultStore();

            links.replaceClioLinksForMatter(
                    tenantUuid,
                    "matter-1",
                    "clio-matter-1",
                    List.of(
                            new matter_contacts.LinkRec("matter-1", "contact-a", "clio", "clio-matter-1", "clio-contact-a", ""),
                            new matter_contacts.LinkRec("matter-1", "contact-b", "clio", "clio-matter-1", "clio-contact-b", "")
                    )
            );
            assertEquals(2, links.listByMatter(tenantUuid, "matter-1").size());

            links.replaceClioLinksForMatter(
                    tenantUuid,
                    "matter-1",
                    "clio-matter-1",
                    List.of(new matter_contacts.LinkRec("matter-1", "contact-b", "clio", "clio-matter-1", "clio-contact-b", ""))
            );
            List<matter_contacts.LinkRec> clioRows = links.listByMatter(tenantUuid, "matter-1");
            assertEquals(1, clioRows.size());
            assertEquals("contact-b", clioRows.get(0).contactUuid);

            links.replaceNativeLinksForContact(tenantUuid, "contact-native-1", List.of("matter-1", "matter-2"));
            List<matter_contacts.LinkRec> nativeRows = links.listByContact(tenantUuid, "contact-native-1");
            assertEquals(2, nativeRows.size());
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
