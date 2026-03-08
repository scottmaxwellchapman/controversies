package net.familylawandprobate.controversies.integrations.office365;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.familylawandprobate.controversies.activity_log;
import net.familylawandprobate.controversies.contacts;
import net.familylawandprobate.controversies.tenant_settings;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One-way Office 365 / Microsoft Graph contacts sync.
 * Imported contacts are upserted as external read-only records in Controversies.
 */
public final class Office365ContactsSyncService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String GRAPH_SCOPE_DEFAULT = "https://graph.microsoft.com/.default";
    private static final String SETTINGS_ENABLED = "office365_contacts_sync_enabled";
    private static final String SETTINGS_INTERVAL_MINUTES = "office365_contacts_sync_interval_minutes";
    private static final String SETTINGS_SOURCES_JSON = "office365_contacts_sync_sources_json";
    private static final String SETTINGS_LAST_SYNC_AT = "office365_contacts_last_sync_at";
    private static final String SETTINGS_LAST_SYNC_STATUS = "office365_contacts_last_sync_status";
    private static final String SETTINGS_LAST_SYNC_ERROR = "office365_contacts_last_sync_error";
    private static final String DEFAULT_IDENTITY_BASE_URL = "https://login.microsoftonline.com";
    private static final String DEFAULT_GRAPH_BASE_URL = "https://graph.microsoft.com";
    private static final int PAGE_SIZE = 200;
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final ConcurrentHashMap<String, Object> SYNC_LOCKS = new ConcurrentHashMap<String, Object>();

    private final tenant_settings settingsStore;
    private final contacts contactStore;
    private final String identityBaseUrl;
    private final String graphBaseUrl;

    public Office365ContactsSyncService() {
        this(tenant_settings.defaultStore(), contacts.defaultStore(), DEFAULT_IDENTITY_BASE_URL, DEFAULT_GRAPH_BASE_URL);
    }

    public Office365ContactsSyncService(String identityBaseUrl, String graphBaseUrl) {
        this(tenant_settings.defaultStore(), contacts.defaultStore(), identityBaseUrl, graphBaseUrl);
    }

    Office365ContactsSyncService(tenant_settings settingsStore,
                                 contacts contactStore,
                                 String identityBaseUrl,
                                 String graphBaseUrl) {
        this.settingsStore = settingsStore == null ? tenant_settings.defaultStore() : settingsStore;
        this.contactStore = contactStore == null ? contacts.defaultStore() : contactStore;
        this.identityBaseUrl = normalizeBaseUrl(identityBaseUrl, DEFAULT_IDENTITY_BASE_URL);
        this.graphBaseUrl = normalizeBaseUrl(graphBaseUrl, DEFAULT_GRAPH_BASE_URL);
    }

    public static final class SourceConfig {
        public final String sourceId;
        public final String tenantId;
        public final String clientId;
        public final String clientSecret;
        public final String userPrincipal;
        public final String contactFolderId;
        public final String scope;
        public final boolean enabled;

        public SourceConfig(String sourceId,
                            String tenantId,
                            String clientId,
                            String clientSecret,
                            String userPrincipal,
                            String contactFolderId,
                            String scope,
                            boolean enabled) {
            this.sourceId = safe(sourceId).trim().toLowerCase(Locale.ROOT);
            this.tenantId = safe(tenantId).trim();
            this.clientId = safe(clientId).trim();
            this.clientSecret = safe(clientSecret);
            this.userPrincipal = safe(userPrincipal).trim();
            this.contactFolderId = safe(contactFolderId).trim();
            this.scope = safe(scope).trim().isBlank() ? GRAPH_SCOPE_DEFAULT : safe(scope).trim();
            this.enabled = enabled;
        }
    }

    public static final class SyncResult {
        public final int sourcesConfigured;
        public final int sourcesProcessed;
        public final int contactsUpserted;
        public final boolean ok;
        public final String error;
        public final LinkedHashMap<String, String> perSourceUpserts;

        public SyncResult(int sourcesConfigured,
                          int sourcesProcessed,
                          int contactsUpserted,
                          boolean ok,
                          String error,
                          LinkedHashMap<String, String> perSourceUpserts) {
            this.sourcesConfigured = Math.max(0, sourcesConfigured);
            this.sourcesProcessed = Math.max(0, sourcesProcessed);
            this.contactsUpserted = Math.max(0, contactsUpserted);
            this.ok = ok;
            this.error = safe(error);
            this.perSourceUpserts = perSourceUpserts == null
                    ? new LinkedHashMap<String, String>()
                    : new LinkedHashMap<String, String>(perSourceUpserts);
        }
    }

    public boolean isEnabled(String tenantUuid) {
        LinkedHashMap<String, String> cfg = settingsStore.read(tenantUuid);
        return "true".equalsIgnoreCase(safe(cfg.get(SETTINGS_ENABLED)));
    }

    public SyncResult syncContacts(String tenantUuid, boolean manualRun) throws Exception {
        String tu = safe(tenantUuid).trim();
        if (tu.isBlank()) {
            return new SyncResult(0, 0, 0, false, "tenantUuid required", new LinkedHashMap<String, String>());
        }

        Object lock = SYNC_LOCKS.computeIfAbsent(tu, k -> new Object());
        synchronized (lock) {
            LinkedHashMap<String, String> cfg = settingsStore.read(tu);
            if (!"true".equalsIgnoreCase(safe(cfg.get(SETTINGS_ENABLED)))) {
                return new SyncResult(0, 0, 0, false, "office365 contacts sync disabled", new LinkedHashMap<String, String>());
            }

            List<SourceConfig> configured = parseSources(safe(cfg.get(SETTINGS_SOURCES_JSON)));
            ArrayList<SourceConfig> activeSources = new ArrayList<SourceConfig>();
            for (SourceConfig src : configured) {
                if (src == null || !src.enabled) continue;
                if (src.sourceId.isBlank()) continue;
                activeSources.add(src);
            }

            LinkedHashMap<String, String> perSource = new LinkedHashMap<String, String>();
            if (activeSources.isEmpty()) {
                String error = "No enabled Office 365 contact sync sources are configured.";
                writeSyncStatus(tu, cfg, false, error);
                logSyncResult(tu, manualRun, 0, 0, 0, false, error, perSource);
                return new SyncResult(configured.size(), 0, 0, false, error, perSource);
            }

            int processed = 0;
            int upserted = 0;
            ArrayList<String> failures = new ArrayList<String>();
            for (SourceConfig src : activeSources) {
                if (src == null) continue;
                try {
                    int sourceUpserted = syncSource(tu, src);
                    processed++;
                    upserted += sourceUpserted;
                    perSource.put(src.sourceId, String.valueOf(sourceUpserted));
                } catch (Exception ex) {
                    String err = safe(ex.getMessage()).trim();
                    if (err.isBlank()) err = ex.getClass().getSimpleName();
                    failures.add(src.sourceId + ": " + err);
                    perSource.put(src.sourceId, "error");
                }
            }

            boolean ok = failures.isEmpty();
            String error = ok ? "" : String.join(" | ", failures);
            writeSyncStatus(tu, cfg, ok, error);
            logSyncResult(tu, manualRun, configured.size(), processed, upserted, ok, error, perSource);
            return new SyncResult(configured.size(), processed, upserted, ok, error, perSource);
        }
    }

    private int syncSource(String tenantUuid, SourceConfig source) throws Exception {
        String accessToken = acquireAccessToken(source);
        if (accessToken.isBlank()) {
            throw new IllegalStateException("Missing access token from Microsoft identity endpoint.");
        }

        String selectFields = String.join(",",
                "id",
                "displayName",
                "givenName",
                "middleName",
                "surname",
                "companyName",
                "jobTitle",
                "emailAddresses",
                "businessPhones",
                "mobilePhone",
                "homePhones",
                "businessAddress",
                "homeAddress",
                "otherAddress",
                "personalNotes",
                "lastModifiedDateTime"
        );

        StringBuilder initial = new StringBuilder();
        initial.append(graphBaseUrl).append("/v1.0/users/")
                .append(encodePathSegment(source.userPrincipal));
        if (source.contactFolderId.isBlank()) {
            initial.append("/contacts");
        } else {
            initial.append("/contactFolders/")
                    .append(encodePathSegment(source.contactFolderId))
                    .append("/contacts");
        }
        initial.append("?$top=").append(PAGE_SIZE)
                .append("&$select=").append(urlEncode(selectFields));

        int upserted = 0;
        String nextUrl = initial.toString();
        LinkedHashSet<String> seenUrls = new LinkedHashSet<String>();
        while (!nextUrl.isBlank()) {
            if (!seenUrls.add(nextUrl)) break;
            JsonNode root = JSON.readTree(getJson(nextUrl, accessToken));
            JsonNode rows = root == null ? null : root.path("value");
            if (rows != null && rows.isArray()) {
                for (int i = 0; i < rows.size(); i++) {
                    JsonNode row = rows.get(i);
                    if (row == null || row.isMissingNode()) continue;
                    String sourceContactId = text(row, "id");
                    if (sourceContactId.isBlank()) continue;
                    contacts.ContactInput in = toContactInput(row);
                    contactStore.upsertExternal(
                            tenantUuid,
                            in,
                            "office365:" + source.sourceId,
                            sourceContactId,
                            text(row, "lastModifiedDateTime")
                    );
                    upserted++;
                }
            }
            nextUrl = text(root, "@odata.nextLink");
        }
        return upserted;
    }

    private String acquireAccessToken(SourceConfig source) throws Exception {
        if (source == null) throw new IllegalArgumentException("source required");
        if (source.tenantId.isBlank() || source.clientId.isBlank() || source.clientSecret.isBlank()) {
            throw new IllegalArgumentException("source credentials are incomplete");
        }

        String endpoint = identityBaseUrl + "/"
                + encodePathSegment(source.tenantId)
                + "/oauth2/v2.0/token";
        LinkedHashMap<String, String> fields = new LinkedHashMap<String, String>();
        fields.put("grant_type", "client_credentials");
        fields.put("client_id", source.clientId);
        fields.put("client_secret", source.clientSecret);
        fields.put("scope", source.scope);
        String raw = postForm(endpoint, fields);
        JsonNode root = JSON.readTree(safe(raw));
        return text(root, "access_token");
    }

    private String getJson(String url, String accessToken) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create(safe(url).trim()).toURL().openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + safe(accessToken).trim());
        int status = conn.getResponseCode();
        String body = readBody(conn, status >= 200 && status < 300);
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("Microsoft Graph contacts request failed status=" + status + " body=" + safeErrorBody(body));
        }
        return body;
    }

    private String postForm(String url, Map<String, String> fields) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create(safe(url).trim()).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setDoOutput(true);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        byte[] payload = formBody(fields).getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = conn.getOutputStream()) {
            out.write(payload);
        }
        int status = conn.getResponseCode();
        String body = readBody(conn, status >= 200 && status < 300);
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("Microsoft identity token request failed status=" + status + " body=" + safeErrorBody(body));
        }
        return body;
    }

    private void writeSyncStatus(String tenantUuid, LinkedHashMap<String, String> currentCfg, boolean ok, String error) {
        try {
            LinkedHashMap<String, String> next = new LinkedHashMap<String, String>();
            if (currentCfg != null) next.putAll(currentCfg);
            next.put(SETTINGS_LAST_SYNC_AT, Instant.now().toString());
            next.put(SETTINGS_LAST_SYNC_STATUS, ok ? "ok" : "failed");
            next.put(SETTINGS_LAST_SYNC_ERROR, safe(error).trim());
            settingsStore.write(tenantUuid, next);
        } catch (Exception ignored) {
        }
    }

    private void logSyncResult(String tenantUuid,
                               boolean manualRun,
                               int sourcesConfigured,
                               int sourcesProcessed,
                               int contactsUpserted,
                               boolean ok,
                               String error,
                               Map<String, String> perSource) {
        try {
            LinkedHashMap<String, String> details = new LinkedHashMap<String, String>();
            details.put("status", ok ? "ok" : "failed");
            details.put("sources_configured", String.valueOf(Math.max(0, sourcesConfigured)));
            details.put("sources_processed", String.valueOf(Math.max(0, sourcesProcessed)));
            details.put("contacts_upserted", String.valueOf(Math.max(0, contactsUpserted)));
            details.put("sync_interval_minutes", safe(settingsStore.read(tenantUuid).get(SETTINGS_INTERVAL_MINUTES)));
            if (!safe(error).isBlank()) details.put("error", safe(error));
            if (perSource != null && !perSource.isEmpty()) {
                details.put("per_source", toCompactMap(perSource));
            }
            activity_log.defaultStore().logVerbose(
                    manualRun ? "office365.contacts.sync.manual" : "office365.contacts.sync.scheduled",
                    tenantUuid,
                    "system",
                    "",
                    "",
                    details
            );
        } catch (Exception ignored) {
        }
    }

    private static List<SourceConfig> parseSources(String rawJson) {
        String raw = safe(rawJson).trim();
        if (raw.isBlank()) return List.of();
        try {
            JsonNode parsed = JSON.readTree(raw);
            JsonNode rows = parsed;
            if (parsed != null && parsed.isObject() && parsed.has("sources")) rows = parsed.path("sources");
            if (rows == null || !rows.isArray()) return List.of();

            ArrayList<SourceConfig> out = new ArrayList<SourceConfig>();
            for (int i = 0; i < rows.size(); i++) {
                JsonNode row = rows.get(i);
                SourceConfig src = parseSource(row, i);
                if (src == null) continue;
                out.add(src);
            }
            return out;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static SourceConfig parseSource(JsonNode row, int index) {
        if (row == null || !row.isObject()) return null;
        String sourceId = firstNonBlank(text(row, "source_id"), text(row, "id"), text(row, "name"));
        String tenantId = firstNonBlank(text(row, "tenant_id"), text(row, "azure_tenant_id"));
        String clientId = firstNonBlank(text(row, "client_id"), text(row, "app_client_id"));
        String clientSecret = firstNonBlank(text(row, "client_secret"), text(row, "app_client_secret"));
        String userPrincipal = firstNonBlank(text(row, "user_principal"), text(row, "mailbox"), text(row, "user_id"));
        String folderId = firstNonBlank(text(row, "contact_folder_id"), text(row, "address_book_id"), text(row, "folder_id"));
        String scope = firstNonBlank(text(row, "scope"), GRAPH_SCOPE_DEFAULT);
        boolean enabled = bool(row, "enabled", true);
        if (sourceId.isBlank()) sourceId = deriveSourceId(userPrincipal, folderId, index);
        sourceId = normalizeSourceId(sourceId);
        if (sourceId.isBlank()) return null;
        if (tenantId.isBlank() || clientId.isBlank() || clientSecret.isBlank() || userPrincipal.isBlank()) return null;
        return new SourceConfig(sourceId, tenantId, clientId, clientSecret, userPrincipal, folderId, scope, enabled);
    }

    private contacts.ContactInput toContactInput(JsonNode row) {
        contacts.ContactInput in = new contacts.ContactInput();
        if (row == null || row.isMissingNode()) return in;

        in.displayName = text(row, "displayName");
        in.givenName = text(row, "givenName");
        in.middleName = text(row, "middleName");
        in.surname = text(row, "surname");
        in.companyName = text(row, "companyName");
        in.jobTitle = text(row, "jobTitle");
        in.notes = text(row, "personalNotes");

        List<String> emails = readEmailAddresses(row.path("emailAddresses"));
        in.emailPrimary = valueAt(emails, 0);
        in.emailSecondary = valueAt(emails, 1);
        in.emailTertiary = valueAt(emails, 2);

        List<String> businessPhones = readStringArray(row.path("businessPhones"));
        in.businessPhone = valueAt(businessPhones, 0);
        in.businessPhone2 = valueAt(businessPhones, 1);
        in.mobilePhone = text(row, "mobilePhone");

        List<String> homePhones = readStringArray(row.path("homePhones"));
        in.homePhone = valueAt(homePhones, 0);

        copyAddress(row.path("businessAddress"), in, 1);
        copyAddress(row.path("homeAddress"), in, 2);
        copyAddress(row.path("otherAddress"), in, 3);
        return in;
    }

    private static void copyAddress(JsonNode addressNode, contacts.ContactInput in, int slot) {
        if (in == null || addressNode == null || addressNode.isMissingNode() || addressNode.isNull()) return;
        String street = firstNonBlank(text(addressNode, "street"), text(addressNode, "streetAddress"));
        String city = text(addressNode, "city");
        String state = firstNonBlank(text(addressNode, "state"), text(addressNode, "stateOrProvince"));
        String postalCode = firstNonBlank(text(addressNode, "postalCode"), text(addressNode, "zip"));
        String country = firstNonBlank(text(addressNode, "countryOrRegion"), text(addressNode, "country"));
        if (slot == 1) {
            in.street = street;
            in.city = city;
            in.state = state;
            in.postalCode = postalCode;
            in.country = country;
            return;
        }
        if (slot == 2) {
            in.streetSecondary = street;
            in.citySecondary = city;
            in.stateSecondary = state;
            in.postalCodeSecondary = postalCode;
            in.countrySecondary = country;
            return;
        }
        in.streetTertiary = street;
        in.cityTertiary = city;
        in.stateTertiary = state;
        in.postalCodeTertiary = postalCode;
        in.countryTertiary = country;
    }

    private static List<String> readEmailAddresses(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        ArrayList<String> out = new ArrayList<String>();
        for (int i = 0; i < node.size(); i++) {
            JsonNode row = node.get(i);
            if (row == null || row.isMissingNode()) continue;
            String address = firstNonBlank(text(row, "address"), text(row, "email"));
            if (!address.isBlank()) out.add(address);
        }
        return out;
    }

    private static List<String> readStringArray(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        ArrayList<String> out = new ArrayList<String>();
        for (int i = 0; i < node.size(); i++) {
            JsonNode value = node.get(i);
            String v = value == null ? "" : safe(value.asText()).trim();
            if (!v.isBlank()) out.add(v);
        }
        return out;
    }

    private static String valueAt(List<String> list, int idx) {
        if (list == null || idx < 0 || idx >= list.size()) return "";
        return safe(list.get(idx)).trim();
    }

    private static String toCompactMap(Map<String, String> fields) {
        if (fields == null || fields.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Map.Entry<String, String> e : fields.entrySet()) {
            if (e == null) continue;
            String k = safe(e.getKey()).trim();
            if (k.isBlank()) continue;
            if (n++ > 0) sb.append(", ");
            sb.append(k).append('=').append(safe(e.getValue()).trim());
        }
        return sb.toString();
    }

    private static String text(JsonNode node, String key) {
        if (node == null || key == null || key.isBlank()) return "";
        JsonNode value = node.path(key);
        if (value == null || value.isMissingNode() || value.isNull()) return "";
        if (value.isTextual() || value.isNumber() || value.isBoolean()) return safe(value.asText()).trim();
        return "";
    }

    private static boolean bool(JsonNode node, String key, boolean fallback) {
        if (node == null || key == null || key.isBlank()) return fallback;
        JsonNode value = node.path(key);
        if (value == null || value.isMissingNode() || value.isNull()) return fallback;
        if (value.isBoolean()) return value.asBoolean();
        String raw = safe(value.asText()).trim().toLowerCase(Locale.ROOT);
        if (raw.isBlank()) return fallback;
        return "1".equals(raw) || "true".equals(raw) || "yes".equals(raw) || "on".equals(raw);
    }

    private static String deriveSourceId(String userPrincipal, String folderId, int index) {
        String user = safe(userPrincipal).trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]", "_");
        String folder = safe(folderId).trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]", "_");
        if (user.isBlank()) user = "source_" + (index + 1);
        if (folder.isBlank()) return user;
        return user + "__" + folder;
    }

    private static String normalizeSourceId(String raw) {
        String sourceId = safe(raw).trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._:-]", "_");
        if (sourceId.length() > 100) sourceId = sourceId.substring(0, 100);
        return sourceId;
    }

    private static String formBody(Map<String, String> fields) {
        if (fields == null || fields.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Map.Entry<String, String> e : fields.entrySet()) {
            if (e == null) continue;
            String key = safe(e.getKey()).trim();
            String val = safe(e.getValue()).trim();
            if (key.isBlank()) continue;
            if (n++ > 0) sb.append('&');
            sb.append(urlEncode(key)).append('=').append(urlEncode(val));
        }
        return sb.toString();
    }

    private static String readBody(HttpURLConnection conn, boolean successStream) throws Exception {
        String body = "";
        InputStream stream = successStream ? conn.getInputStream() : conn.getErrorStream();
        if (stream != null) {
            try (InputStream in = stream) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        return body;
    }

    private static String safeErrorBody(String body) {
        String v = safe(body).replaceAll("[\r\n\t]+", " ").trim();
        if (v.length() > 300) return v.substring(0, 300) + "...";
        return v;
    }

    private static String encodePathSegment(String value) {
        String v = safe(value).trim();
        if (v.isBlank()) return "";
        return urlEncode(v).replace("+", "%20");
    }

    private static String normalizeBaseUrl(String raw, String fallback) {
        String v = safe(raw).trim();
        if (v.isBlank()) v = safe(fallback).trim();
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        return v;
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(safe(s), StandardCharsets.UTF_8);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) {
            String v = safe(value).trim();
            if (!v.isBlank()) return v;
        }
        return "";
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
