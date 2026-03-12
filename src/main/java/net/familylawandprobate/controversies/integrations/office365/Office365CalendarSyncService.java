package net.familylawandprobate.controversies.integrations.office365;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.familylawandprobate.controversies.activity_log;
import net.familylawandprobate.controversies.app_clock;
import net.familylawandprobate.controversies.calendar_system;
import net.familylawandprobate.controversies.tenant_settings;
import net.familylawandprobate.controversies.users_roles;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One-way Office 365 / Microsoft Graph calendar sync.
 *
 * Sources are configured in tenant setting key: office365_calendar_sync_sources_json
 */
public final class Office365CalendarSyncService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String SETTINGS_ENABLED = "office365_calendar_sync_enabled";
    private static final String SETTINGS_INTERVAL_MINUTES = "office365_calendar_sync_interval_minutes";
    private static final String SETTINGS_SOURCES_JSON = "office365_calendar_sync_sources_json";
    private static final String SETTINGS_LAST_SYNC_AT = "office365_calendar_last_sync_at";
    private static final String SETTINGS_LAST_SYNC_STATUS = "office365_calendar_last_sync_status";
    private static final String SETTINGS_LAST_SYNC_ERROR = "office365_calendar_last_sync_error";

    private static final String GRAPH_SCOPE_DEFAULT = "https://graph.microsoft.com/.default";
    private static final String DEFAULT_IDENTITY_BASE_URL = "https://login.microsoftonline.com";
    private static final String DEFAULT_GRAPH_BASE_URL = "https://graph.microsoft.com";

    private static final int PAGE_SIZE = 200;
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    private static final ConcurrentHashMap<String, Object> SYNC_LOCKS = new ConcurrentHashMap<String, Object>();

    private final tenant_settings settingsStore;
    private final calendar_system calendarStore;
    private final users_roles usersStore;
    private final String identityBaseUrl;
    private final String graphBaseUrl;

    public Office365CalendarSyncService() {
        this(
                tenant_settings.defaultStore(),
                calendar_system.defaultStore(),
                users_roles.defaultStore(),
                DEFAULT_IDENTITY_BASE_URL,
                DEFAULT_GRAPH_BASE_URL
        );
    }

    public Office365CalendarSyncService(String identityBaseUrl, String graphBaseUrl) {
        this(
                tenant_settings.defaultStore(),
                calendar_system.defaultStore(),
                users_roles.defaultStore(),
                identityBaseUrl,
                graphBaseUrl
        );
    }

    Office365CalendarSyncService(tenant_settings settingsStore,
                                 calendar_system calendarStore,
                                 users_roles usersStore,
                                 String identityBaseUrl,
                                 String graphBaseUrl) {
        this.settingsStore = settingsStore == null ? tenant_settings.defaultStore() : settingsStore;
        this.calendarStore = calendarStore == null ? calendar_system.defaultStore() : calendarStore;
        this.usersStore = usersStore == null ? users_roles.defaultStore() : usersStore;
        this.identityBaseUrl = normalizeBaseUrl(identityBaseUrl, DEFAULT_IDENTITY_BASE_URL);
        this.graphBaseUrl = normalizeBaseUrl(graphBaseUrl, DEFAULT_GRAPH_BASE_URL);
    }

    public static final class SourceConfig {
        public final String sourceId;
        public final String tenantId;
        public final String clientId;
        public final String clientSecret;
        public final String userPrincipal;
        public final String calendarId;
        public final String ownerUserUuid;
        public final String scope;
        public final boolean enabled;

        public SourceConfig(String sourceId,
                            String tenantId,
                            String clientId,
                            String clientSecret,
                            String userPrincipal,
                            String calendarId,
                            String ownerUserUuid,
                            String scope,
                            boolean enabled) {
            this.sourceId = safe(sourceId).trim().toLowerCase(Locale.ROOT);
            this.tenantId = safe(tenantId).trim();
            this.clientId = safe(clientId).trim();
            this.clientSecret = safe(clientSecret);
            this.userPrincipal = safe(userPrincipal).trim();
            this.calendarId = safe(calendarId).trim();
            this.ownerUserUuid = safe(ownerUserUuid).trim();
            this.scope = safe(scope).trim().isBlank() ? GRAPH_SCOPE_DEFAULT : safe(scope).trim();
            this.enabled = enabled;
        }
    }

    public static final class SyncResult {
        public final int sourcesConfigured;
        public final int sourcesProcessed;
        public final int calendarsProcessed;
        public final int eventsUpserted;
        public final boolean ok;
        public final String error;
        public final LinkedHashMap<String, String> perSourceUpserts;

        public SyncResult(int sourcesConfigured,
                          int sourcesProcessed,
                          int calendarsProcessed,
                          int eventsUpserted,
                          boolean ok,
                          String error,
                          LinkedHashMap<String, String> perSourceUpserts) {
            this.sourcesConfigured = Math.max(0, sourcesConfigured);
            this.sourcesProcessed = Math.max(0, sourcesProcessed);
            this.calendarsProcessed = Math.max(0, calendarsProcessed);
            this.eventsUpserted = Math.max(0, eventsUpserted);
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

    public SyncResult syncCalendars(String tenantUuid, boolean manualRun) throws Exception {
        String tu = safe(tenantUuid).trim();
        if (tu.isBlank()) {
            return new SyncResult(0, 0, 0, 0, false, "tenantUuid required", new LinkedHashMap<String, String>());
        }

        Object lock = SYNC_LOCKS.computeIfAbsent(tu, k -> new Object());
        synchronized (lock) {
            LinkedHashMap<String, String> cfg = settingsStore.read(tu);
            if (!"true".equalsIgnoreCase(safe(cfg.get(SETTINGS_ENABLED)))) {
                return new SyncResult(0, 0, 0, 0, false, "office365 calendar sync disabled", new LinkedHashMap<String, String>());
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
                String error = "No enabled Office 365 calendar sync sources are configured.";
                writeSyncStatus(tu, cfg, false, error);
                logSyncResult(tu, manualRun, 0, 0, 0, 0, false, error, perSource);
                return new SyncResult(configured.size(), 0, 0, 0, false, error, perSource);
            }

            int processedSources = 0;
            int calendarsProcessed = 0;
            int eventsUpserted = 0;
            ArrayList<String> failures = new ArrayList<String>();

            for (SourceConfig src : activeSources) {
                if (src == null) continue;
                try {
                    SyncSourceCounts counts = syncSource(tu, src);
                    processedSources++;
                    calendarsProcessed += counts.calendarsProcessed;
                    eventsUpserted += counts.eventsUpserted;
                    perSource.put(src.sourceId, String.valueOf(counts.eventsUpserted));
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
            logSyncResult(tu, manualRun, configured.size(), processedSources, calendarsProcessed, eventsUpserted, ok, error, perSource);
            return new SyncResult(configured.size(), processedSources, calendarsProcessed, eventsUpserted, ok, error, perSource);
        }
    }

    private SyncSourceCounts syncSource(String tenantUuid, SourceConfig source) throws Exception {
        String accessToken = acquireAccessToken(source);
        if (accessToken.isBlank()) {
            throw new IllegalStateException("Missing access token from Microsoft identity endpoint.");
        }

        String ownerUserUuid = resolveOwnerUserUuid(tenantUuid, source);
        if (ownerUserUuid.isBlank()) {
            throw new IllegalStateException("Unable to resolve source owner user for calendar sync.");
        }

        List<GraphCalendarRef> graphCalendars = loadGraphCalendars(source, accessToken);
        if (graphCalendars.isEmpty()) {
            throw new IllegalStateException("No Graph calendars found for source user_principal=" + source.userPrincipal);
        }

        int calendarCount = 0;
        int upserts = 0;

        for (GraphCalendarRef graphCal : graphCalendars) {
            if (graphCal == null || graphCal.calendarId.isBlank()) continue;

            String localCalendarLabel = graphCal.name;
            if (localCalendarLabel.isBlank()) {
                localCalendarLabel = "Office 365 Calendar";
            }

            calendar_system.CalendarRec localCal = calendarStore.findOrCreateExternalCalendar(
                    tenantUuid,
                    ownerUserUuid,
                    "office365:" + source.sourceId,
                    graphCal.calendarId,
                    localCalendarLabel,
                    graphCal.timeZone
            );

            int eventRows = syncGraphCalendarEvents(tenantUuid, source, graphCal, localCal, accessToken, ownerUserUuid);
            upserts += eventRows;
            calendarCount++;
        }

        return new SyncSourceCounts(calendarCount, upserts);
    }

    private int syncGraphCalendarEvents(String tenantUuid,
                                        SourceConfig source,
                                        GraphCalendarRef graphCal,
                                        calendar_system.CalendarRec localCal,
                                        String accessToken,
                                        String ownerUserUuid) throws Exception {
        String selectFields = String.join(",",
                "id",
                "subject",
                "bodyPreview",
                "location",
                "start",
                "end",
                "isAllDay",
                "lastModifiedDateTime",
                "iCalUId",
                "changeKey",
                "attendees",
                "organizer",
                "showAs"
        );

        StringBuilder initial = new StringBuilder();
        initial.append(graphBaseUrl).append("/v1.0/users/")
                .append(encodePathSegment(source.userPrincipal))
                .append("/calendars/")
                .append(encodePathSegment(graphCal.calendarId))
                .append("/events")
                .append("?$top=").append(PAGE_SIZE)
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

                    String sourceEventId = text(row, "id");
                    if (sourceEventId.isBlank()) continue;

                    calendar_system.EventInput in = new calendar_system.EventInput();
                    in.summary = text(row, "subject");
                    in.description = text(row, "bodyPreview");
                    in.location = text(row.path("location"), "displayName");
                    in.allDay = bool(row, "isAllDay", false);
                    in.startAt = graphDateTimeToIso(row.path("start"), in.allDay, true);
                    in.endAt = graphDateTimeToIso(row.path("end"), in.allDay, false);
                    in.status = graphStatusToLocal(text(row, "showAs"));
                    in.organizerUserUuid = ownerUserUuid;
                    in.attendeesCsv = parseAttendees(row.path("attendees"));
                    in.uid = text(row, "iCalUId");

                    calendarStore.upsertExternalEvent(
                            tenantUuid,
                            localCal.uuid,
                            in,
                            "office365:" + source.sourceId,
                            sourceEventId,
                            text(row, "changeKey"),
                            text(row, "iCalUId")
                    );
                    upserted++;
                }
            }

            nextUrl = text(root, "@odata.nextLink");
        }

        return upserted;
    }

    private List<GraphCalendarRef> loadGraphCalendars(SourceConfig source, String accessToken) throws Exception {
        ArrayList<GraphCalendarRef> out = new ArrayList<GraphCalendarRef>();

        if (!source.calendarId.isBlank()) {
            String endpoint = graphBaseUrl + "/v1.0/users/"
                    + encodePathSegment(source.userPrincipal)
                    + "/calendars/"
                    + encodePathSegment(source.calendarId)
                    + "?$select=id,name,color,timeZone";
            JsonNode row = JSON.readTree(getJson(endpoint, accessToken));
            String id = text(row, "id");
            if (!id.isBlank()) {
                out.add(new GraphCalendarRef(
                        id,
                        text(row, "name"),
                        text(row, "timeZone")
                ));
            }
            return out;
        }

        String endpoint = graphBaseUrl + "/v1.0/users/"
                + encodePathSegment(source.userPrincipal)
                + "/calendars?$top=100&$select=id,name,color,timeZone";

        String next = endpoint;
        LinkedHashSet<String> seen = new LinkedHashSet<String>();
        while (!next.isBlank()) {
            if (!seen.add(next)) break;
            JsonNode root = JSON.readTree(getJson(next, accessToken));
            JsonNode rows = root == null ? null : root.path("value");
            if (rows != null && rows.isArray()) {
                for (int i = 0; i < rows.size(); i++) {
                    JsonNode row = rows.get(i);
                    if (row == null || row.isMissingNode()) continue;
                    String id = text(row, "id");
                    if (id.isBlank()) continue;
                    out.add(new GraphCalendarRef(
                            id,
                            text(row, "name"),
                            text(row, "timeZone")
                    ));
                }
            }
            next = text(root, "@odata.nextLink");
        }

        return out;
    }

    private String resolveOwnerUserUuid(String tenantUuid, SourceConfig source) {
        try {
            if (!safe(source.ownerUserUuid).trim().isBlank()) {
                users_roles.UserRec byUuid = usersStore.getUserByUuid(tenantUuid, source.ownerUserUuid);
                if (byUuid != null && byUuid.enabled) return byUuid.uuid;
            }

            if (!safe(source.userPrincipal).trim().isBlank()) {
                users_roles.UserRec byEmail = usersStore.getUserByEmail(tenantUuid, source.userPrincipal);
                if (byEmail != null && byEmail.enabled) return byEmail.uuid;
            }

            for (users_roles.UserRec user : usersStore.listUsers(tenantUuid)) {
                if (user == null || !user.enabled) continue;
                return user.uuid;
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private String graphDateTimeToIso(JsonNode dateTimeTimeZone,
                                      boolean allDay,
                                      boolean startValue) {
        if (dateTimeTimeZone == null || dateTimeTimeZone.isMissingNode()) return "";
        String dateTime = text(dateTimeTimeZone, "dateTime");
        String zone = text(dateTimeTimeZone, "timeZone");
        if (dateTime.isBlank()) return "";

        if (allDay) {
            try {
                LocalDate d;
                if (dateTime.matches("\\d{4}-\\d{2}-\\d{2}")) d = LocalDate.parse(dateTime);
                else d = LocalDateTime.parse(dateTime).toLocalDate();
                if (!startValue) d = d.plusDays(1);
                return d.atStartOfDay().toInstant(ZoneOffset.UTC).toString();
            } catch (Exception ignored) {
                return "";
            }
        }

        try {
            return Instant.parse(dateTime).toString();
        } catch (DateTimeParseException ignored) {
        }

        try {
            return java.time.OffsetDateTime.parse(dateTime).toInstant().toString();
        } catch (DateTimeParseException ignored) {
        }

        try {
            LocalDateTime ldt = LocalDateTime.parse(dateTime);
            ZoneId tz = resolveGraphTimeZone(zone);
            return ldt.atZone(tz).toInstant().toString();
        } catch (DateTimeParseException ignored) {
            return "";
        }
    }

    private static ZoneId resolveGraphTimeZone(String raw) {
        String value = safe(raw).trim();
        if (value.isBlank()) return ZoneOffset.UTC;
        try {
            return ZoneId.of(value);
        } catch (Exception ignored) {
        }

        String key = value.toLowerCase(Locale.ROOT);
        if ("utc".equals(key)) return ZoneOffset.UTC;
        if ("eastern standard time".equals(key)) return ZoneId.of("America/New_York");
        if ("central standard time".equals(key)) return ZoneId.of("America/Chicago");
        if ("mountain standard time".equals(key)) return ZoneId.of("America/Denver");
        if ("pacific standard time".equals(key)) return ZoneId.of("America/Los_Angeles");

        return ZoneOffset.UTC;
    }

    private static String graphStatusToLocal(String graphShowAs) {
        String v = safe(graphShowAs).trim().toLowerCase(Locale.ROOT);
        if ("free".equals(v)) return "free";
        if ("tentative".equals(v)) return "tentative";
        if ("oof".equals(v)) return "busy";
        if ("workingelsewhere".equals(v)) return "busy";
        return "confirmed";
    }

    private static String parseAttendees(JsonNode attendees) {
        if (attendees == null || !attendees.isArray()) return "";
        LinkedHashSet<String> out = new LinkedHashSet<String>();
        for (int i = 0; i < attendees.size(); i++) {
            JsonNode row = attendees.get(i);
            if (row == null || row.isMissingNode()) continue;
            String email = text(row.path("emailAddress"), "address").toLowerCase(Locale.ROOT).trim();
            if (email.isBlank()) continue;
            out.add(email);
        }
        return String.join(",", out);
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
            throw new IllegalStateException("Microsoft Graph calendars request failed status=" + status + " body=" + safeErrorBody(body));
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

    private static List<SourceConfig> parseSources(String rawJson) {
        String raw = safe(rawJson).trim();
        if (raw.isBlank()) return List.of();

        try {
            JsonNode root = JSON.readTree(raw);
            if (root == null || !root.isArray()) return List.of();

            ArrayList<SourceConfig> out = new ArrayList<SourceConfig>();
            for (int i = 0; i < root.size(); i++) {
                JsonNode row = root.get(i);
                if (row == null || row.isMissingNode() || !row.isObject()) continue;

                SourceConfig src = new SourceConfig(
                        text(row, "source_id"),
                        text(row, "tenant_id"),
                        text(row, "client_id"),
                        text(row, "client_secret"),
                        text(row, "user_principal"),
                        text(row, "calendar_id"),
                        text(row, "owner_user_uuid"),
                        text(row, "scope"),
                        bool(row, "enabled", true)
                );

                if (src.sourceId.isBlank()) continue;
                if (src.tenantId.isBlank() || src.clientId.isBlank() || src.clientSecret.isBlank() || src.userPrincipal.isBlank()) {
                    continue;
                }
                out.add(src);
            }
            return out;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private void writeSyncStatus(String tenantUuid,
                                 LinkedHashMap<String, String> currentCfg,
                                 boolean ok,
                                 String error) {
        try {
            LinkedHashMap<String, String> next = new LinkedHashMap<String, String>();
            if (currentCfg != null) next.putAll(currentCfg);
            next.put(SETTINGS_LAST_SYNC_AT, app_clock.now().toString());
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
                               int calendarsProcessed,
                               int eventsUpserted,
                               boolean ok,
                               String error,
                               LinkedHashMap<String, String> perSourceUpserts) {
        try {
            LinkedHashMap<String, String> detail = new LinkedHashMap<String, String>();
            detail.put("manual_run", String.valueOf(manualRun));
            detail.put("sources_configured", String.valueOf(Math.max(0, sourcesConfigured)));
            detail.put("sources_processed", String.valueOf(Math.max(0, sourcesProcessed)));
            detail.put("calendars_processed", String.valueOf(Math.max(0, calendarsProcessed)));
            detail.put("events_upserted", String.valueOf(Math.max(0, eventsUpserted)));
            detail.put("ok", String.valueOf(ok));
            detail.put("error", safe(error));
            if (perSourceUpserts != null && !perSourceUpserts.isEmpty()) {
                detail.put("per_source", safe(perSourceUpserts.toString()));
            }

            activity_log.defaultStore().write(
                    ok ? "INFO" : "ERROR",
                    manualRun ? "office365.calendar.sync.manual" : "office365.calendar.sync.scheduled",
                    tenantUuid,
                    "",
                    "",
                    detail
            );
        } catch (Exception ignored) {
        }
    }

    private static String normalizeBaseUrl(String raw, String fallback) {
        String v = safe(raw).trim();
        if (v.isBlank()) v = safe(fallback).trim();
        if (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        if (v.isBlank()) return safe(fallback).trim();
        return v;
    }

    private static String encodePathSegment(String value) {
        return urlEncode(safe(value)).replace("+", "%20");
    }

    private static String urlEncode(String value) {
        try {
            return URLEncoder.encode(safe(value), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String formBody(Map<String, String> fields) {
        if (fields == null || fields.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> e : fields.entrySet()) {
            if (e == null) continue;
            String k = safe(e.getKey());
            if (k.isBlank()) continue;
            if (!first) sb.append('&');
            first = false;
            sb.append(urlEncode(k)).append('=').append(urlEncode(safe(e.getValue())));
        }
        return sb.toString();
    }

    private static String readBody(HttpURLConnection conn, boolean success) throws Exception {
        if (conn == null) return "";
        try (InputStream in = success ? conn.getInputStream() : conn.getErrorStream()) {
            if (in == null) return "";
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String safeErrorBody(String raw) {
        String v = safe(raw).trim();
        if (v.length() > 1200) return v.substring(0, 1200);
        return v;
    }

    private static String text(JsonNode node, String field) {
        if (node == null || field == null || field.isBlank()) return "";
        JsonNode v = node.path(field);
        if (v == null || v.isMissingNode() || v.isNull()) return "";
        return safe(v.asText());
    }

    private static boolean bool(JsonNode node, String field, boolean fallback) {
        if (node == null || field == null || field.isBlank()) return fallback;
        JsonNode v = node.path(field);
        if (v == null || v.isMissingNode() || v.isNull()) return fallback;
        return v.asBoolean(fallback);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static final class GraphCalendarRef {
        final String calendarId;
        final String name;
        final String timeZone;

        GraphCalendarRef(String calendarId, String name, String timeZone) {
            this.calendarId = safe(calendarId).trim();
            this.name = safe(name).trim();
            this.timeZone = safe(timeZone).trim();
        }
    }

    private static final class SyncSourceCounts {
        final int calendarsProcessed;
        final int eventsUpserted;

        SyncSourceCounts(int calendarsProcessed, int eventsUpserted) {
            this.calendarsProcessed = Math.max(0, calendarsProcessed);
            this.eventsUpserted = Math.max(0, eventsUpserted);
        }
    }
}
