package net.familylawandprobate.controversies;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.familylawandprobate.controversies.integrations.office365.Office365ContactsSyncService;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class office365_contacts_sync_test {

    @Test
    void syncs_multiple_office365_sources_and_marks_contacts_read_only() throws Exception {
        String tenantUuid = "office365-sync-test-" + UUID.randomUUID();
        HttpServer server = null;
        try {
            ensurePepper();
            tenant_settings settingsStore = tenant_settings.defaultStore();
            LinkedHashMap<String, String> settings = settingsStore.read(tenantUuid);
            settings.put("office365_contacts_sync_enabled", "true");
            settings.put("office365_contacts_sync_interval_minutes", "15");
            settings.put("office365_contacts_sync_sources_json", "["
                    + "{\"source_id\":\"source_a\",\"tenant_id\":\"tenant-a\",\"client_id\":\"cid-a\",\"client_secret\":\"sec-a\",\"user_principal\":\"usera@example.test\",\"enabled\":true},"
                    + "{\"source_id\":\"source_b\",\"tenant_id\":\"tenant-b\",\"client_id\":\"cid-b\",\"client_secret\":\"sec-b\",\"user_principal\":\"userb@example.test\",\"contact_folder_id\":\"shared-folder\",\"enabled\":true}"
                    + "]");
            settingsStore.write(tenantUuid, settings);

            server = HttpServer.create(new InetSocketAddress(0), 0);
            int port = server.getAddress().getPort();

            server.createContext("/id/tenant-a/oauth2/v2.0/token", ex -> {
                respondJson(ex, 200, "{\"access_token\":\"token-a\"}");
            });
            server.createContext("/id/tenant-b/oauth2/v2.0/token", ex -> {
                respondJson(ex, 200, "{\"access_token\":\"token-b\"}");
            });
            server.createContext("/graph/v1.0/users/usera@example.test/contacts", ex -> {
                String query = ex.getRequestURI() == null ? "" : String.valueOf(ex.getRequestURI().getQuery());
                if (query != null && query.contains("page=2")) {
                    respondJson(ex, 200, "{\"value\":[{\"id\":\"A-2\",\"displayName\":\"Alice Two\",\"emailAddresses\":[{\"address\":\"alice2@example.test\"}],\"lastModifiedDateTime\":\"2026-03-07T12:00:00Z\"}]}");
                    return;
                }
                respondJson(ex, 200,
                        "{\"value\":[{\"id\":\"A-1\",\"displayName\":\"Alice One\",\"givenName\":\"Alice\",\"surname\":\"One\",\"emailAddresses\":[{\"address\":\"alice1@example.test\"}],\"businessPhones\":[\"+15550100\"],\"lastModifiedDateTime\":\"2026-03-07T11:00:00Z\"}],"
                                + "\"@odata.nextLink\":\"http://127.0.0.1:" + port + "/graph/v1.0/users/usera@example.test/contacts?page=2\"}");
            });
            server.createContext("/graph/v1.0/users/userb@example.test/contactFolders/shared-folder/contacts", ex -> {
                respondJson(ex, 200, "{\"value\":[{\"id\":\"B-1\",\"displayName\":\"Bob Shared\",\"emailAddresses\":[{\"address\":\"bob@example.test\"}],\"homePhones\":[\"+15550200\"],\"lastModifiedDateTime\":\"2026-03-07T13:00:00Z\"}]}");
            });
            server.start();

            Office365ContactsSyncService svc = new Office365ContactsSyncService(
                    "http://127.0.0.1:" + port + "/id",
                    "http://127.0.0.1:" + port + "/graph"
            );
            Office365ContactsSyncService.SyncResult result = svc.syncContacts(tenantUuid, true);
            assertTrue(result.ok, "sync error=" + result.error + " perSource=" + result.perSourceUpserts);
            assertEquals(2, result.sourcesProcessed);
            assertEquals(3, result.contactsUpserted);

            contacts store = contacts.defaultStore();
            List<contacts.ContactRec> all = store.listAll(tenantUuid);
            assertEquals(3, all.size());
            for (contacts.ContactRec rec : all) {
                assertNotNull(rec);
                assertTrue(contacts.isExternalReadOnly(rec));
                assertTrue(contacts.sourceType(rec).startsWith("office365:"));
            }

            contacts.ContactRec a1 = store.getBySourceContactId(tenantUuid, "office365:source_a", "A-1");
            assertNotNull(a1);
            assertThrows(IllegalStateException.class, () -> store.updateNative(tenantUuid, a1.uuid, new contacts.ContactInput()));
            assertThrows(IllegalStateException.class, () -> store.trash(tenantUuid, a1.uuid));

            LinkedHashMap<String, String> after = settingsStore.read(tenantUuid);
            assertEquals("ok", after.get("office365_contacts_last_sync_status"));
            assertFalse(String.valueOf(after.get("office365_contacts_last_sync_at")).isBlank());
        } finally {
            if (server != null) server.stop(0);
            deleteTenantDirQuiet(tenantUuid);
        }
    }

    @Test
    void fails_cleanly_when_sync_is_enabled_without_sources() throws Exception {
        String tenantUuid = "office365-sync-empty-" + UUID.randomUUID();
        try {
            ensurePepper();
            tenant_settings settingsStore = tenant_settings.defaultStore();
            LinkedHashMap<String, String> settings = settingsStore.read(tenantUuid);
            settings.put("office365_contacts_sync_enabled", "true");
            settings.put("office365_contacts_sync_sources_json", "[]");
            settingsStore.write(tenantUuid, settings);

            Office365ContactsSyncService.SyncResult result = new Office365ContactsSyncService().syncContacts(tenantUuid, true);
            assertFalse(result.ok);
            assertTrue(result.error.toLowerCase().contains("no enabled office 365"));

            LinkedHashMap<String, String> after = settingsStore.read(tenantUuid);
            assertEquals("failed", after.get("office365_contacts_last_sync_status"));
        } finally {
            deleteTenantDirQuiet(tenantUuid);
        }
    }

    private static void respondJson(HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = (body == null ? "" : body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (java.io.OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void ensurePepper() throws Exception {
        Path pepper = Paths.get("data", "sec", "random_pepper.bin").toAbsolutePath();
        Files.createDirectories(pepper.getParent());
        if (!Files.exists(pepper)) {
            Files.writeString(pepper, "test-pepper-material", StandardCharsets.UTF_8);
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
