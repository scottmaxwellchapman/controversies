package net.familylawandprobate.controversies;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class caldav_service {

    public static final String REALM = "Controversies CalDAV";

    private final tenants tenantStore;
    private final users_roles usersStore;
    private final calendar_system calendarStore;

    public enum Kind {
        ROOT,
        PRINCIPAL,
        HOME,
        CALENDAR,
        EVENT
    }

    public static final class CalDavException extends Exception {
        public final int status;
        public final boolean challenge;

        CalDavException(int status, String message, boolean challenge) {
            super(safe(message));
            this.status = status;
            this.challenge = challenge;
        }
    }

    public static final class Principal {
        public final String tenantUuid;
        public final String tenantSlug;
        public final String tenantLabel;
        public final String userUuid;
        public final String email;

        Principal(String tenantUuid,
                  String tenantSlug,
                  String tenantLabel,
                  String userUuid,
                  String email) {
            this.tenantUuid = safe(tenantUuid).trim();
            this.tenantSlug = safe(tenantSlug).trim();
            this.tenantLabel = safe(tenantLabel).trim();
            this.userUuid = safe(userUuid).trim();
            this.email = safe(email).trim().toLowerCase(Locale.ROOT);
        }

        public String actor() {
            if (!email.isBlank()) return email;
            return userUuid;
        }
    }

    public static final class Resource {
        public final Kind kind;
        public final boolean collection;
        public final List<String> canonicalSegments;
        public final String displayName;
        public final calendar_system.CalendarRec calendar;
        public final calendar_system.EventRec event;
        public final String etag;
        public final Instant modifiedAt;

        Resource(Kind kind,
                 boolean collection,
                 List<String> canonicalSegments,
                 String displayName,
                 calendar_system.CalendarRec calendar,
                 calendar_system.EventRec event,
                 String etag,
                 Instant modifiedAt) {
            this.kind = kind;
            this.collection = collection;
            this.canonicalSegments = canonicalSegments == null ? List.of() : List.copyOf(canonicalSegments);
            this.displayName = safe(displayName);
            this.calendar = calendar;
            this.event = event;
            this.etag = safe(etag);
            this.modifiedAt = modifiedAt;
        }
    }

    public static final class PutResult {
        public final boolean created;
        public final Resource resource;

        PutResult(boolean created, Resource resource) {
            this.created = created;
            this.resource = resource;
        }
    }

    private static final class RealmUser {
        final String tenantToken;
        final String email;

        RealmUser(String tenantToken, String email) {
            this.tenantToken = safe(tenantToken).trim();
            this.email = safe(email).trim().toLowerCase(Locale.ROOT);
        }
    }

    public static caldav_service defaultService() {
        return new caldav_service(
                tenants.defaultStore(),
                users_roles.defaultStore(),
                calendar_system.defaultStore()
        );
    }

    caldav_service(tenants tenantStore,
                   users_roles usersStore,
                   calendar_system calendarStore) {
        this.tenantStore = tenantStore == null ? tenants.defaultStore() : tenantStore;
        this.usersStore = usersStore == null ? users_roles.defaultStore() : usersStore;
        this.calendarStore = calendarStore == null ? calendar_system.defaultStore() : calendarStore;
    }

    public Principal authenticate(String authorizationHeader) throws Exception {
        String pair = decodeBasicPair(authorizationHeader);
        int idx = pair.indexOf(':');
        if (idx <= 0) {
            throw unauthorized("Basic authentication credentials are required.");
        }

        String username = pair.substring(0, idx);
        String passwordRaw = pair.substring(idx + 1);

        RealmUser realmUser = parseRealmUser(username);
        tenants.Tenant tenant = resolveTenantByToken(realmUser.tenantToken);
        if (tenant == null) {
            throw unauthorized("Unknown tenant slug in username.");
        }

        users_roles.AuthResult auth = usersStore.authenticate(
                safe(tenant.uuid).trim(),
                realmUser.email,
                passwordRaw.toCharArray()
        );
        if (auth == null || auth.user == null) {
            throw unauthorized("Invalid CalDAV username or password.");
        }

        if (!hasCalendarAccess(auth.permissions)) {
            throw forbidden("This user does not have calendar.access permission.");
        }

        return new Principal(
                safe(tenant.uuid).trim(),
                webdav_service.tenantSlug(tenant.label),
                tenant.label,
                safe(auth.user.uuid).trim(),
                safe(auth.user.emailAddress).trim().toLowerCase(Locale.ROOT)
        );
    }

    public List<Resource> propfind(Principal principal, String rawPath, int depth) throws Exception {
        Resource target = resolveExisting(principal, rawPath);
        if (target == null) throw notFound("CalDAV path not found.");

        ArrayList<Resource> out = new ArrayList<Resource>();
        out.add(target);
        if (depth > 0 && target.collection) {
            out.addAll(children(principal, target));
        }
        return out;
    }

    public List<Resource> reportCalendarQuery(Principal principal,
                                              String rawPath,
                                              String timeRangeStart,
                                              String timeRangeEnd) throws Exception {
        Resource calendarResource = resolveExisting(principal, rawPath);
        if (calendarResource == null || calendarResource.kind != Kind.CALENDAR) {
            throw notFound("Calendar collection not found.");
        }

        String calendarUuid = safe(calendarResource.calendar == null ? "" : calendarResource.calendar.uuid).trim();
        List<calendar_system.EventRec> events = calendarStore.listEventsForUser(
                principal.tenantUuid,
                calendarUuid,
                principal.userUuid,
                safe(timeRangeStart),
                safe(timeRangeEnd),
                false
        );

        ArrayList<Resource> out = new ArrayList<Resource>();
        for (calendar_system.EventRec event : events) {
            if (event == null) continue;
            out.add(eventResource(principal, calendarResource.calendar, event));
        }
        out.sort(Comparator.comparing(r -> safe(r.displayName).toLowerCase(Locale.ROOT)));
        return out;
    }

    public List<Resource> reportCalendarMultiGet(Principal principal,
                                                 String calendarPath,
                                                 List<String> hrefPaths) throws Exception {
        Resource calendarResource = resolveExisting(principal, calendarPath);
        if (calendarResource == null || calendarResource.kind != Kind.CALENDAR) {
            throw notFound("Calendar collection not found.");
        }

        String calendarUuid = safe(calendarResource.calendar == null ? "" : calendarResource.calendar.uuid).trim();
        if (calendarUuid.isBlank()) return List.of();

        List<String> hrefs = hrefPaths == null ? List.of() : hrefPaths;
        LinkedHashMap<String, Resource> dedup = new LinkedHashMap<String, Resource>();

        for (String href : hrefs) {
            String p = normalizePath(href);
            if (p.isBlank()) continue;
            Resource row = resolveExisting(principal, p);
            if (row == null || row.kind != Kind.EVENT || row.event == null) continue;
            if (!calendarUuid.equals(safe(row.event.calendarUuid).trim())) continue;

            String eventUuid = safe(row.event.uuid).trim();
            if (eventUuid.isBlank()) continue;
            dedup.putIfAbsent(eventUuid, row);
        }

        if (dedup.isEmpty()) {
            List<calendar_system.EventRec> all = calendarStore.listEventsForUser(
                    principal.tenantUuid,
                    calendarUuid,
                    principal.userUuid,
                    "",
                    "",
                    false
            );
            for (calendar_system.EventRec event : all) {
                if (event == null) continue;
                Resource row = eventResource(principal, calendarResource.calendar, event);
                dedup.putIfAbsent(safe(event.uuid), row);
            }
        }

        return new ArrayList<Resource>(dedup.values());
    }

    public Resource eventForGet(Principal principal, String rawPath) throws Exception {
        Resource target = resolveExisting(principal, rawPath);
        if (target == null || target.kind != Kind.EVENT || target.event == null) {
            throw notFound("Calendar object not found.");
        }
        return target;
    }

    public String eventAsIcs(Resource eventResource) {
        if (eventResource == null || eventResource.event == null) return "";
        return calendarStore.toIcs(eventResource.event);
    }

    public PutResult putCalendarObject(Principal principal, String rawPath, InputStream body) throws Exception {
        if (body == null) throw badRequest("Missing calendar object body.");

        List<String> segments = parseSegments(rawPath);
        if (segments.size() != 5) {
            throw status(409, "PUT target must be a calendar object resource.", false);
        }

        // /calendars/{tenant}/{user}/{calendar}/{event}.ics
        if (!"calendars".equalsIgnoreCase(segments.get(0))) {
            throw notFound("Calendar path not found.");
        }
        requireOwnerPathMatch(principal, segments.get(1), segments.get(2));

        calendar_system.CalendarRec calendar = resolveCalendarForPrincipal(principal, segments.get(3), true);
        if (calendar == null) throw notFound("Calendar collection not found.");

        String eventSegment = safe(segments.get(4)).trim();
        if (eventSegment.isBlank()) throw badRequest("Calendar object file name is required.");

        String eventUuidFromPath = calendar_system.extractUuidSuffix(eventSegment);
        String fallbackUid = fallbackUidFromSegment(eventSegment);

        calendar_system.EventRec before = null;
        if (!eventUuidFromPath.isBlank()) {
            before = calendarStore.getEventByUuidForUser(
                    principal.tenantUuid,
                    calendar.uuid,
                    eventUuidFromPath,
                    principal.userUuid,
                    true
            );
        }

        if (before == null && !fallbackUid.isBlank()) {
            before = calendarStore.getEventByUidForUser(
                    principal.tenantUuid,
                    calendar.uuid,
                    fallbackUid,
                    principal.userUuid,
                    true
            );
        }

        byte[] payload = readBody(body, 2_500_000);
        String ics = new String(payload, StandardCharsets.UTF_8);

        calendar_system.EventInput in = calendarStore.parseIcsEvent(ics, fallbackUid);
        if (!eventUuidFromPath.isBlank()) in.eventUuid = eventUuidFromPath;

        calendar_system.EventRec saved = calendarStore.putEventForUser(
                principal.tenantUuid,
                calendar.uuid,
                in,
                principal.userUuid
        );

        Resource eventResource = eventResource(principal, calendar, saved);
        boolean created = before == null;
        return new PutResult(created, eventResource);
    }

    public void deleteCalendarObject(Principal principal, String rawPath) throws Exception {
        List<String> segments = parseSegments(rawPath);
        if (segments.size() != 5) {
            throw status(409, "DELETE target must be a calendar object resource.", false);
        }

        if (!"calendars".equalsIgnoreCase(segments.get(0))) {
            throw notFound("Calendar path not found.");
        }
        requireOwnerPathMatch(principal, segments.get(1), segments.get(2));

        calendar_system.CalendarRec calendar = resolveCalendarForPrincipal(principal, segments.get(3), true);
        if (calendar == null) throw notFound("Calendar collection not found.");

        String eventSegment = safe(segments.get(4)).trim();
        String eventUuid = calendar_system.extractUuidSuffix(eventSegment);
        if (eventUuid.isBlank()) {
            String uid = fallbackUidFromSegment(eventSegment);
            calendar_system.EventRec byUid = calendarStore.getEventByUidForUser(
                    principal.tenantUuid,
                    calendar.uuid,
                    uid,
                    principal.userUuid,
                    true
            );
            eventUuid = safe(byUid == null ? "" : byUid.uuid).trim();
        }
        if (eventUuid.isBlank()) throw notFound("Calendar object not found.");

        boolean changed = calendarStore.deleteEventByUuidForUser(
                principal.tenantUuid,
                calendar.uuid,
                eventUuid,
                principal.userUuid
        );
        if (!changed) throw notFound("Calendar object not found.");
    }

    public Resource mkcalendar(Principal principal,
                               String rawPath,
                               String displayName,
                               String timezone,
                               String color) throws Exception {
        List<String> segments = parseSegments(rawPath);
        if (segments.size() != 4) {
            throw status(409, "MKCALENDAR target must be under calendar home collection.", false);
        }

        if (!"calendars".equalsIgnoreCase(segments.get(0))) {
            throw notFound("Calendar path not found.");
        }
        requireOwnerPathMatch(principal, segments.get(1), segments.get(2));

        String label = safe(displayName).trim();
        if (label.isBlank()) {
            String fromPath = stripUuidSuffix(segments.get(3)).replace('_', ' ').replace('-', ' ').trim();
            label = fromPath.isBlank() ? "Calendar" : fromPath;
        }

        calendar_system.CalendarInput in = new calendar_system.CalendarInput();
        in.name = label;
        in.ownerUserUuid = principal.userUuid;
        in.timezone = safe(timezone).trim();
        in.color = safe(color).trim();
        in.enabled = true;

        calendar_system.CalendarRec created = calendarStore.createCalendar(
                principal.tenantUuid,
                in,
                principal.userUuid
        );
        return calendarResource(principal, created);
    }

    private List<Resource> children(Principal principal, Resource parent) throws Exception {
        if (parent == null) return List.of();

        if (parent.kind == Kind.ROOT) {
            return List.of(principalResource(principal));
        }

        if (parent.kind == Kind.PRINCIPAL) {
            return List.of(homeResource(principal));
        }

        if (parent.kind == Kind.HOME) {
            ensurePrimaryCalendar(principal);
            List<calendar_system.CalendarRec> calendars = calendarStore.listCalendarsForUser(
                    principal.tenantUuid,
                    principal.userUuid,
                    false,
                    false
            );
            ArrayList<Resource> out = new ArrayList<Resource>();
            for (calendar_system.CalendarRec cal : calendars) {
                if (cal == null) continue;
                out.add(calendarResource(principal, cal));
            }
            out.sort(Comparator.comparing(r -> safe(r.displayName).toLowerCase(Locale.ROOT)));
            return out;
        }

        if (parent.kind == Kind.CALENDAR && parent.calendar != null) {
            List<calendar_system.EventRec> events = calendarStore.listEventsForUser(
                    principal.tenantUuid,
                    parent.calendar.uuid,
                    principal.userUuid,
                    "",
                    "",
                    false
            );
            ArrayList<Resource> out = new ArrayList<Resource>();
            for (calendar_system.EventRec event : events) {
                if (event == null) continue;
                out.add(eventResource(principal, parent.calendar, event));
            }
            out.sort(Comparator.comparing(r -> safe(r.displayName).toLowerCase(Locale.ROOT)));
            return out;
        }

        return List.of();
    }

    private Resource resolveExisting(Principal principal, String rawPath) throws Exception {
        List<String> segments = parseSegments(rawPath);
        if (segments.isEmpty()) {
            return rootResource();
        }

        String first = safe(segments.get(0)).trim().toLowerCase(Locale.ROOT);
        if ("principals".equals(first)) {
            if (segments.size() != 3) return null;
            if (!matchesTenantToken(principal, segments.get(1))) return null;
            if (!matchesUserToken(principal, segments.get(2))) return null;
            return principalResource(principal);
        }

        if ("calendars".equals(first)) {
            if (segments.size() < 3 || segments.size() > 5) return null;
            if (!matchesTenantToken(principal, segments.get(1))) return null;
            if (!matchesUserToken(principal, segments.get(2))) return null;

            if (segments.size() == 3) {
                ensurePrimaryCalendar(principal);
                return homeResource(principal);
            }

            calendar_system.CalendarRec calendar = resolveCalendarForPrincipal(principal, segments.get(3), false);
            if (calendar == null) return null;

            if (segments.size() == 4) {
                return calendarResource(principal, calendar);
            }

            String eventSegment = safe(segments.get(4)).trim();
            String eventUuid = calendar_system.extractUuidSuffix(eventSegment);

            calendar_system.EventRec event = null;
            if (!eventUuid.isBlank()) {
                event = calendarStore.getEventByUuidForUser(
                        principal.tenantUuid,
                        calendar.uuid,
                        eventUuid,
                        principal.userUuid,
                        false
                );
            }

            if (event == null) {
                String uid = fallbackUidFromSegment(eventSegment);
                if (!uid.isBlank()) {
                    event = calendarStore.getEventByUidForUser(
                            principal.tenantUuid,
                            calendar.uuid,
                            uid,
                            principal.userUuid,
                            false
                    );
                }
            }

            if (event == null) return null;
            return eventResource(principal, calendar, event);
        }

        return null;
    }

    private calendar_system.CalendarRec resolveCalendarForPrincipal(Principal principal,
                                                                    String segment,
                                                                    boolean requireWrite) throws Exception {
        String wantedUuid = calendar_system.extractUuidSuffix(segment);
        List<calendar_system.CalendarRec> calendars = calendarStore.listCalendarsForUser(
                principal.tenantUuid,
                principal.userUuid,
                false,
                false
        );

        if (!wantedUuid.isBlank()) {
            for (calendar_system.CalendarRec row : calendars) {
                if (row == null) continue;
                if (wantedUuid.equalsIgnoreCase(safe(row.uuid).trim())) {
                    if (requireWrite && !calendarStore.canWriteCalendar(row, principal.userUuid)) return null;
                    return row;
                }
            }
        }

        String cmp = normalizeCompare(stripUuidSuffix(segment));
        for (calendar_system.CalendarRec row : calendars) {
            if (row == null) continue;
            if (cmp.equals(normalizeCompare(calendar_system.calendarSegment(row)))) {
                if (requireWrite && !calendarStore.canWriteCalendar(row, principal.userUuid)) return null;
                return row;
            }
        }
        for (calendar_system.CalendarRec row : calendars) {
            if (row == null) continue;
            if (cmp.equals(normalizeCompare(safe(row.name)))) {
                if (requireWrite && !calendarStore.canWriteCalendar(row, principal.userUuid)) return null;
                return row;
            }
        }

        return null;
    }

    private void ensurePrimaryCalendar(Principal principal) throws Exception {
        if (principal == null) return;
        users_roles.UserRec user = usersStore.getUserByUuid(principal.tenantUuid, principal.userUuid);
        String label = safe(user == null ? "" : user.emailAddress).trim();
        if (label.isBlank()) label = "Primary";
        calendarStore.ensurePrimaryCalendarForUser(principal.tenantUuid, principal.userUuid, label);
    }

    private Resource rootResource() {
        return new Resource(
                Kind.ROOT,
                true,
                List.of(),
                "CalDAV",
                null,
                null,
                "",
                null
        );
    }

    private Resource principalResource(Principal principal) {
        return new Resource(
                Kind.PRINCIPAL,
                true,
                List.of("principals", principal.tenantSlug, principalToken(principal)),
                principal.email,
                null,
                null,
                "",
                null
        );
    }

    private Resource homeResource(Principal principal) {
        return new Resource(
                Kind.HOME,
                true,
                List.of("calendars", principal.tenantSlug, principalToken(principal)),
                "Calendars",
                null,
                null,
                "",
                null
        );
    }

    private Resource calendarResource(Principal principal, calendar_system.CalendarRec calendar) {
        return new Resource(
                Kind.CALENDAR,
                true,
                List.of("calendars", principal.tenantSlug, principalToken(principal), calendar_system.calendarSegment(calendar)),
                safe(calendar == null ? "" : calendar.name),
                calendar,
                null,
                "",
                parseInstant(safe(calendar == null ? "" : calendar.updatedAt))
        );
    }

    private Resource eventResource(Principal principal,
                                   calendar_system.CalendarRec calendar,
                                   calendar_system.EventRec event) {
        return new Resource(
                Kind.EVENT,
                false,
                List.of(
                        "calendars",
                        principal.tenantSlug,
                        principalToken(principal),
                        calendar_system.calendarSegment(calendar),
                        calendar_system.eventFileName(event)
                ),
                safe(event == null ? "" : event.summary),
                calendar,
                event,
                safe(event == null ? "" : event.etag),
                parseInstant(safe(event == null ? "" : event.updatedAt))
        );
    }

    private static String decodeBasicPair(String authHeader) throws CalDavException {
        String raw = safe(authHeader).trim();
        if (raw.isBlank()) throw unauthorized("Basic authentication is required.");
        if (!raw.regionMatches(true, 0, "basic ", 0, 6)) {
            throw unauthorized("Basic authentication is required.");
        }

        String token = raw.substring(6).trim();
        if (token.isBlank()) throw unauthorized("Basic authentication is required.");

        try {
            byte[] decoded = Base64.getDecoder().decode(token);
            return new String(decoded, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw unauthorized("Invalid basic authentication header.");
        }
    }

    private RealmUser parseRealmUser(String username) throws CalDavException {
        String raw = safe(username).trim();
        int idx = raw.indexOf('\\');
        if (idx <= 0 || idx + 1 >= raw.length()) {
            throw unauthorized("Username must use tenant_slug\\email_address format.");
        }

        String tenantToken = raw.substring(0, idx).trim();
        String email = raw.substring(idx + 1).trim();
        if (tenantToken.isBlank() || email.isBlank()) {
            throw unauthorized("Username must use tenant_slug\\email_address format.");
        }

        return new RealmUser(tenantToken, email);
    }

    private tenants.Tenant resolveTenantByToken(String tenantToken) throws Exception {
        String token = safe(tenantToken).trim().toLowerCase(Locale.ROOT);
        if (token.isBlank()) return null;

        List<tenants.Tenant> all = tenantStore.list();
        tenants.Tenant uuidMatch = null;
        ArrayList<tenants.Tenant> slugMatches = new ArrayList<tenants.Tenant>();

        for (tenants.Tenant row : all) {
            if (row == null || !row.enabled) continue;
            String uuid = safe(row.uuid).trim().toLowerCase(Locale.ROOT);
            if (token.equals(uuid)) {
                uuidMatch = row;
                break;
            }
            String slug = webdav_service.tenantSlug(row.label);
            if (token.equals(slug)) slugMatches.add(row);
        }

        if (uuidMatch != null) return uuidMatch;
        if (slugMatches.isEmpty()) return null;
        if (slugMatches.size() == 1) return slugMatches.get(0);
        throw unauthorized("Tenant slug is ambiguous. Use a unique slug.");
    }

    private static void requireOwnerPathMatch(Principal principal,
                                              String tenantToken,
                                              String userToken) throws CalDavException {
        if (principal == null) throw unauthorized("Authentication required.");
        if (!matchesTenantToken(principal, tenantToken)) throw notFound("Calendar path not found.");
        if (!matchesUserToken(principal, userToken)) throw notFound("Calendar path not found.");
    }

    private static boolean matchesTenantToken(Principal principal, String token) {
        String wanted = safe(token).trim().toLowerCase(Locale.ROOT);
        if (wanted.isBlank() || principal == null) return false;
        if (wanted.equals(safe(principal.tenantUuid).trim().toLowerCase(Locale.ROOT))) return true;
        return wanted.equals(safe(principal.tenantSlug).trim().toLowerCase(Locale.ROOT));
    }

    private static boolean matchesUserToken(Principal principal, String token) {
        String wanted = safe(token).trim().toLowerCase(Locale.ROOT);
        if (wanted.isBlank() || principal == null) return false;
        if (wanted.equals(safe(principal.userUuid).trim().toLowerCase(Locale.ROOT))) return true;
        return wanted.equals(principalToken(principal).toLowerCase(Locale.ROOT));
    }

    private static String principalToken(Principal principal) {
        if (principal == null) return "user";
        String email = safe(principal.email).trim().toLowerCase(Locale.ROOT);
        if (!email.isBlank()) return email;
        String userUuid = safe(principal.userUuid).trim();
        if (!userUuid.isBlank()) return userUuid;
        return "user";
    }

    private static String fallbackUidFromSegment(String segment) {
        String s = safe(segment).trim();
        if (s.toLowerCase(Locale.ROOT).endsWith(".ics")) {
            s = s.substring(0, s.length() - 4);
        }
        int idx = s.lastIndexOf("__");
        if (idx > 0) s = s.substring(0, idx);
        s = s.trim();
        if (s.isBlank()) return "";
        return s.replace(' ', '_') + "@controversies.local";
    }

    private static List<String> parseSegments(String rawPath) throws CalDavException {
        String p = normalizePath(rawPath);
        if (p.isBlank() || "/".equals(p)) return List.of();

        while (p.startsWith("/")) p = p.substring(1);
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        if (p.isBlank()) return List.of();

        String[] parts = p.split("/");
        ArrayList<String> out = new ArrayList<String>();
        for (String raw : parts) {
            String seg = decodeSegment(raw);
            if (seg.isBlank()) continue;
            if (".".equals(seg) || "..".equals(seg)) throw badRequest("Invalid CalDAV path segment.");
            if (seg.contains("/") || seg.contains("\\")) throw badRequest("Invalid CalDAV path segment.");
            out.add(seg);
        }
        return out;
    }

    private static String decodeSegment(String raw) throws CalDavException {
        try {
            return URLDecoder.decode(safe(raw).replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw badRequest("Invalid URL encoding in path.");
        }
    }

    private static String normalizePath(String rawPath) {
        String p = safe(rawPath).trim().replace('\\', '/');
        if (p.isBlank()) return "/";

        int schemeIdx = p.indexOf("://");
        if (schemeIdx > 0) {
            int slash = p.indexOf('/', schemeIdx + 3);
            p = slash >= 0 ? p.substring(slash) : "/";
        }

        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);

        int caldavIdx = p.toLowerCase(Locale.ROOT).indexOf("/caldav/");
        if (caldavIdx >= 0) {
            p = p.substring(caldavIdx + "/caldav".length());
        } else if ("/caldav".equalsIgnoreCase(p)) {
            p = "/";
        }

        if (!p.startsWith("/")) p = "/" + p;
        return p;
    }

    private static String stripUuidSuffix(String segment) {
        String s = safe(segment).trim();
        int idx = s.lastIndexOf("__");
        if (idx <= 0 || idx + 2 >= s.length()) return s;
        String maybe = s.substring(idx + 2).trim();
        if (!looksUuid(maybe)) return s;
        return s.substring(0, idx).trim();
    }

    private static boolean looksUuid(String raw) {
        return safe(raw).trim().matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    }

    private static String normalizeCompare(String value) {
        String v = safe(value).trim().toLowerCase(Locale.ROOT);
        return v.replaceAll("[^a-z0-9]+", "");
    }

    private static Instant parseInstant(String raw) {
        try {
            return Instant.parse(safe(raw).trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static byte[] readBody(InputStream body, int maxBytes) throws Exception {
        int cap = Math.max(1024, maxBytes);
        try (InputStream in = body; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int total = 0;
            while (true) {
                int n = in.read(buf);
                if (n < 0) break;
                if (n == 0) continue;
                total += n;
                if (total > cap) throw badRequest("Calendar object is too large.");
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static boolean hasCalendarAccess(Map<String, String> permissions) {
        if (permissions == null || permissions.isEmpty()) return false;
        if (isTrue(permissions.get("tenant_admin"))) return true;
        if (isTrue(permissions.get("calendar.access"))) return true;
        return isTrue(permissions.get("tasks.access"));
    }

    private static boolean isTrue(String raw) {
        String v = safe(raw).trim().toLowerCase(Locale.ROOT);
        return "1".equals(v) || "true".equals(v) || "yes".equals(v) || "on".equals(v);
    }

    private static CalDavException status(int status, String message, boolean challenge) {
        return new CalDavException(status, message, challenge);
    }

    private static CalDavException badRequest(String message) {
        return status(400, message, false);
    }

    private static CalDavException unauthorized(String message) {
        return status(401, message, true);
    }

    private static CalDavException forbidden(String message) {
        return status(403, message, false);
    }

    private static CalDavException notFound(String message) {
        return status(404, message, false);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
