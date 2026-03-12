package net.familylawandprobate.controversies;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * assembly_identity_tokens
 *
 * Adds canonical tenant identity + user profile tokens used by form assembly.
 */
public final class assembly_identity_tokens {

    private assembly_identity_tokens() {}

    public static void addTenantIdentityTokens(Map<String, String> out,
                                               String tenantUuid,
                                               String contextPath,
                                               Map<String, String> tenantFields) {
        if (out == null) return;

        LinkedHashMap<String, String> normalizedFields = normalizeTenantFields(tenantFields);
        String mailingAddress = firstNonBlank(
                normalizedFields.get("mailing_address"),
                normalizedFields.get("tenant_mailing_address"),
                normalizedFields.get("firm_mailing_address")
        );
        String physicalAddress = firstNonBlank(
                normalizedFields.get("physical_address"),
                normalizedFields.get("tenant_physical_address"),
                normalizedFields.get("firm_physical_address"),
                normalizedFields.get("address")
        );
        String phone = firstNonBlank(
                normalizedFields.get("phone"),
                normalizedFields.get("tenant_phone"),
                normalizedFields.get("firm_phone")
        );
        String fax = firstNonBlank(
                normalizedFields.get("fax"),
                normalizedFields.get("tenant_fax"),
                normalizedFields.get("firm_fax")
        );
        String email = firstNonBlank(
                normalizedFields.get("email"),
                normalizedFields.get("tenant_email"),
                normalizedFields.get("firm_email")
        );

        putCanonicalTenantToken(out, "mailing_address", mailingAddress);
        putCanonicalTenantToken(out, "physical_address", physicalAddress);
        putCanonicalTenantToken(out, "phone", phone);
        putCanonicalTenantToken(out, "fax", fax);
        putCanonicalTenantToken(out, "email", email);

        putTokenIfAbsent(out, "tenant.logo_url", "");
        putTokenIfAbsent(out, "tenant.logo", "");
        putTokenIfAbsent(out, "tenant.logo_file_name", "");
        putTokenIfAbsent(out, "tenant.logo_content_type", "");
        putTokenIfAbsent(out, "tenant.logo_source_url", "");
        putTokenIfAbsent(out, "tenant.logo_updated_at", "");

        profile_assets.AssetRec logo = profile_assets.defaultStore().readTenantLogo(tenantUuid);
        if (logo != null) {
            String logoUrl = tenantLogoUrl(contextPath);
            putToken(out, "tenant.logo_url", logoUrl);
            putTokenIfAbsent(out, "kv.logo_url", logoUrl);
            putTokenIfAbsent(out, "logo_url", logoUrl);
            putToken(out, "tenant.logo", logoUrl);
            putTokenIfAbsent(out, "kv.logo", logoUrl);
            putTokenIfAbsent(out, "logo", logoUrl);
            putToken(out, "tenant.logo_file_name", safe(logo.fileName));
            putToken(out, "tenant.logo_content_type", safe(logo.mimeType));
            putToken(out, "tenant.logo_source_url", safe(logo.sourceUrl));
            putToken(out, "tenant.logo_updated_at", safe(logo.updatedAt));
        }
    }

    public static void addUserIdentityTokens(Map<String, String> out,
                                             String tenantUuid,
                                             String userUuid,
                                             String userEmail,
                                             String contextPath) {
        if (out == null) return;
        String tu = safe(tenantUuid).trim();
        String uu = safe(userUuid).trim();
        String email = safe(userEmail).trim().toLowerCase(Locale.ROOT);

        if (!uu.isBlank()) putToken(out, "user.uuid", uu);
        if (!email.isBlank()) putToken(out, "user.email", email);

        putTokenIfAbsent(out, "user.photo_url", "");
        putTokenIfAbsent(out, "user.photo", "");
        putTokenIfAbsent(out, "user.photo_file_name", "");
        putTokenIfAbsent(out, "user.photo_content_type", "");
        putTokenIfAbsent(out, "user.photo_source_url", "");
        putTokenIfAbsent(out, "user.photo_updated_at", "");

        if (tu.isBlank() || uu.isBlank()) return;

        if (email.isBlank()) {
            try {
                users_roles.UserRec user = users_roles.defaultStore().getUserByUuid(tu, uu);
                if (user != null) {
                    email = safe(user.emailAddress).trim().toLowerCase(Locale.ROOT);
                    if (!email.isBlank()) putToken(out, "user.email", email);
                }
            } catch (Exception ignored) {}
        }

        profile_assets.AssetRec photo = profile_assets.defaultStore().readUserPhoto(tu, uu);
        if (photo == null) return;

        String photoUrl = userPhotoUrl(contextPath, uu);
        putToken(out, "user.photo_url", photoUrl);
        putToken(out, "user.photo", photoUrl);
        putToken(out, "user.photo_file_name", safe(photo.fileName));
        putToken(out, "user.photo_content_type", safe(photo.mimeType));
        putToken(out, "user.photo_source_url", safe(photo.sourceUrl));
        putToken(out, "user.photo_updated_at", safe(photo.updatedAt));
    }

    public static String tenantLogoUrl(String contextPath) {
        return safeContextPath(contextPath) + "/profile_assets?action=tenant_logo";
    }

    public static String userPhotoUrl(String contextPath, String userUuid) {
        return safeContextPath(contextPath) + "/profile_assets?action=user_photo&user_uuid=" + enc(userUuid);
    }

    private static void putCanonicalTenantToken(Map<String, String> out, String keyTail, String value) {
        String v = safe(value);
        putToken(out, "tenant." + keyTail, v);
        putTokenIfAbsent(out, "kv." + keyTail, v);
        putTokenIfAbsent(out, keyTail, v);
    }

    private static LinkedHashMap<String, String> normalizeTenantFields(Map<String, String> fields) {
        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
        if (fields == null || fields.isEmpty()) return out;
        tenant_fields normalizer = tenant_fields.defaultStore();
        for (Map.Entry<String, String> e : fields.entrySet()) {
            if (e == null) continue;
            String k = normalizer.normalizeKey(e.getKey());
            if (k.isBlank()) continue;
            out.put(k, safe(e.getValue()));
        }
        return out;
    }

    private static void putToken(Map<String, String> out, String key, String value) {
        if (out == null) return;
        String k = safe(key).trim();
        if (k.isBlank()) return;
        out.put(k, safe(value));
    }

    private static void putTokenIfAbsent(Map<String, String> out, String key, String value) {
        if (out == null) return;
        String k = safe(key).trim();
        if (k.isBlank()) return;
        if (out.containsKey(k)) return;
        out.put(k, safe(value));
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String v : values) {
            String s = safe(v).trim();
            if (!s.isBlank()) return s;
        }
        return "";
    }

    private static String safeContextPath(String ctx) {
        String c = safe(ctx).trim();
        if (c.isBlank() || "/".equals(c)) return "";
        if (c.endsWith("/")) c = c.substring(0, c.length() - 1);
        return c;
    }

    private static String enc(String v) {
        return URLEncoder.encode(safe(v), StandardCharsets.UTF_8);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
