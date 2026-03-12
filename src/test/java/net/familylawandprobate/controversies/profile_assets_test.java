package net.familylawandprobate.controversies;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class profile_assets_test {

    private final ArrayList<Path> cleanupRoots = new ArrayList<Path>();

    @AfterEach
    void cleanup() throws Exception {
        for (Path root : cleanupRoots) {
            deleteRecursively(root);
        }
    }

    @Test
    void saves_reads_and_clears_tenant_logo_upload() throws Exception {
        String tenant = "tenant-profile-assets-logo-" + UUID.randomUUID();
        cleanupRoots.add(Paths.get("data", "tenants", tenant).toAbsolutePath());

        profile_assets store = profile_assets.defaultStore();
        byte[] png = samplePng();

        profile_assets.AssetRec rec = store.saveTenantLogoUpload(
                tenant,
                "Firm Logo.png",
                "image/png",
                png,
                "tester@example.test"
        );

        assertNotNull(rec);
        assertEquals("tenant_logo", rec.scope);
        assertEquals("image/png", rec.mimeType);
        assertTrue(rec.fileName.toLowerCase(Locale.ROOT).endsWith(".png"));

        profile_assets.AssetRec fromRead = store.readTenantLogo(tenant);
        assertNotNull(fromRead);
        assertEquals("tenant_logo", fromRead.scope);
        assertArrayEquals(png, store.readAssetBytes(fromRead));

        assertTrue(store.clearTenantLogo(tenant));
        assertNull(store.readTenantLogo(tenant));
    }

    @Test
    void saves_user_photos_per_user_and_clear_is_scoped() throws Exception {
        String tenant = "tenant-profile-assets-users-" + UUID.randomUUID();
        cleanupRoots.add(Paths.get("data", "tenants", tenant).toAbsolutePath());

        profile_assets store = profile_assets.defaultStore();
        byte[] png = samplePng();

        profile_assets.AssetRec first = store.saveUserPhotoUpload(
                tenant,
                "user-a",
                "User A.png",
                "image/png",
                png,
                "tester@example.test"
        );
        profile_assets.AssetRec second = store.saveUserPhotoUpload(
                tenant,
                "user-b",
                "User B.png",
                "image/png",
                png,
                "tester@example.test"
        );

        assertNotNull(first);
        assertNotNull(second);
        assertTrue(first.relativePath.contains("users/user-a."));
        assertTrue(second.relativePath.contains("users/user-b."));

        assertArrayEquals(png, store.readAssetBytes(store.readUserPhoto(tenant, "user-a")));
        assertArrayEquals(png, store.readAssetBytes(store.readUserPhoto(tenant, "user-b")));

        assertTrue(store.clearUserPhoto(tenant, "user-a"));
        assertNull(store.readUserPhoto(tenant, "user-a"));
        assertNotNull(store.readUserPhoto(tenant, "user-b"));
    }

    @Test
    void rejects_invalid_images_and_private_or_non_http_urls() {
        String tenant = "tenant-profile-assets-invalid-" + UUID.randomUUID();
        cleanupRoots.add(Paths.get("data", "tenants", tenant).toAbsolutePath());
        profile_assets store = profile_assets.defaultStore();

        IllegalArgumentException badImage = assertThrows(
                IllegalArgumentException.class,
                () -> store.saveTenantLogoUpload(
                        tenant,
                        "not-an-image.txt",
                        "text/plain",
                        "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        "tester@example.test"
                )
        );
        assertTrue(badImage.getMessage().toLowerCase(Locale.ROOT).contains("unsupported image format"));

        IllegalArgumentException badScheme = assertThrows(
                IllegalArgumentException.class,
                () -> store.saveTenantLogoFromUrl(tenant, "file:///tmp/logo.png", "tester@example.test")
        );
        assertTrue(badScheme.getMessage().toLowerCase(Locale.ROOT).contains("http or https"));

        IllegalArgumentException privateHost = assertThrows(
                IllegalArgumentException.class,
                () -> store.saveTenantLogoFromUrl(tenant, "http://localhost/logo.png", "tester@example.test")
        );
        assertTrue(privateHost.getMessage().toLowerCase(Locale.ROOT).contains("local/private"));
    }

    private static byte[] samplePng() {
        return Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO5F7KsAAAAASUVORK5CYII="
        );
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (root == null || !Files.exists(root)) return;
        Files.walk(root)
                .sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                    }
                });
    }
}
