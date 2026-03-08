package net.familylawandprobate.controversies;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class installation_state_test {

    @Test
    void defaults_to_incomplete_when_state_file_missing() throws Exception {
        Path root = Files.createTempDirectory("controversies-install-state-");
        try {
            installation_state store = new installation_state(root.resolve("install_state.properties"));
            assertFalse(store.isCompleted());

            LinkedHashMap<String, String> snapshot = store.read();
            assertEquals("", snapshot.get(installation_state.KEY_COMPLETED));
            assertEquals("", snapshot.get(installation_state.KEY_COMPLETED_AT));
            assertEquals("", snapshot.get(installation_state.KEY_TENANT_UUID));
            assertEquals("", snapshot.get(installation_state.KEY_ADMIN_EMAIL));
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void mark_completed_persists_completion_metadata() throws Exception {
        Path root = Files.createTempDirectory("controversies-install-state-");
        try {
            installation_state store = new installation_state(root.resolve("state").resolve("install_state.properties"));
            store.markCompleted("tenant-123", "Admin@Example.com");

            assertTrue(store.isCompleted());

            LinkedHashMap<String, String> snapshot = store.read();
            assertEquals("true", snapshot.get(installation_state.KEY_COMPLETED));
            assertEquals("tenant-123", snapshot.get(installation_state.KEY_TENANT_UUID));
            assertEquals("admin@example.com", snapshot.get(installation_state.KEY_ADMIN_EMAIL));
            assertNotNull(snapshot.get(installation_state.KEY_COMPLETED_AT));
            assertFalse(snapshot.get(installation_state.KEY_COMPLETED_AT).isBlank());
            Instant.parse(snapshot.get(installation_state.KEY_COMPLETED_AT));
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void mark_completed_rejects_invalid_email() throws Exception {
        Path root = Files.createTempDirectory("controversies-install-state-");
        try {
            installation_state store = new installation_state(root.resolve("install_state.properties"));
            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class,
                    () -> store.markCompleted("tenant-123", "not-an-email")
            );
            assertTrue(ex.getMessage().toLowerCase().contains("email"));
            assertFalse(store.isCompleted());
        } finally {
            deleteTree(root);
        }
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {
        }
    }
}
