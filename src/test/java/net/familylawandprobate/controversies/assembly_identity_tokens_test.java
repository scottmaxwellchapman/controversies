package net.familylawandprobate.controversies;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class assembly_identity_tokens_test {

    private final ArrayList<Path> cleanupRoots = new ArrayList<Path>();

    @AfterEach
    void cleanup() throws Exception {
        for (Path root : cleanupRoots) {
            deleteRecursively(root);
        }
    }

    @Test
    void adds_tenant_contact_logo_and_user_photo_tokens() throws Exception {
        String tenant = "tenant-identity-tokens-" + UUID.randomUUID();
        String user = "user.tokens@example.test";
        cleanupRoots.add(Paths.get("data", "tenants", tenant).toAbsolutePath());

        profile_assets store = profile_assets.defaultStore();
        byte[] png = samplePng();
        store.saveTenantLogoUpload(tenant, "FirmLogo.png", "image/png", png, "tester@example.test");
        store.saveUserPhotoUpload(tenant, user, "UserPhoto.png", "image/png", png, "tester@example.test");

        LinkedHashMap<String, String> tenantKv = new LinkedHashMap<String, String>();
        tenantKv.put("tenant mailing address", "P.O. Box 123");
        tenantKv.put("Physical Address", "100 Main Street");
        tenantKv.put("Phone", "(555) 123-0000");
        tenantKv.put("fax", "(555) 123-0001");
        tenantKv.put("Email", "intake@example.test");

        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
        out.put("kv.phone", "CASE-WINS");
        out.put("phone", "CASE-WINS");

        assembly_identity_tokens.addTenantIdentityTokens(out, tenant, "/ctx", tenantKv);
        assembly_identity_tokens.addUserIdentityTokens(out, tenant, user, "LAWYER@EXAMPLE.TEST", "/ctx");

        assertEquals("P.O. Box 123", out.get("tenant.mailing_address"));
        assertEquals("100 Main Street", out.get("tenant.physical_address"));
        assertEquals("(555) 123-0000", out.get("tenant.phone"));
        assertEquals("(555) 123-0001", out.get("tenant.fax"));
        assertEquals("intake@example.test", out.get("tenant.email"));

        assertEquals("CASE-WINS", out.get("kv.phone"));
        assertEquals("CASE-WINS", out.get("phone"));

        String tenantLogoUrl = "/ctx/profile_assets?action=tenant_logo";
        assertEquals(tenantLogoUrl, out.get("tenant.logo_url"));
        assertEquals(tenantLogoUrl, out.get("tenant.logo"));
        assertEquals(tenantLogoUrl, out.get("kv.logo"));
        assertEquals(tenantLogoUrl, out.get("logo"));
        assertTrue(out.get("tenant.logo_file_name").toLowerCase(Locale.ROOT).endsWith(".png"));
        assertEquals("image/png", out.get("tenant.logo_content_type"));

        String userPhotoUrl = "/ctx/profile_assets?action=user_photo&user_uuid="
                + URLEncoder.encode(user, StandardCharsets.UTF_8);
        assertEquals(user, out.get("user.uuid"));
        assertEquals("lawyer@example.test", out.get("user.email"));
        assertEquals(userPhotoUrl, out.get("user.photo_url"));
        assertEquals(userPhotoUrl, out.get("user.photo"));
        assertTrue(out.get("user.photo_file_name").toLowerCase(Locale.ROOT).endsWith(".png"));
        assertEquals("image/png", out.get("user.photo_content_type"));
    }

    @Test
    void token_url_helpers_handle_root_context_path() {
        assertEquals("/profile_assets?action=tenant_logo", assembly_identity_tokens.tenantLogoUrl("/"));
        assertEquals(
                "/profile_assets?action=user_photo&user_uuid=user1",
                assembly_identity_tokens.userPhotoUrl("/", "user1")
        );
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
