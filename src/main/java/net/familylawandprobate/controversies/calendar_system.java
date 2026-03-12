package net.familylawandprobate.controversies;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Tenant-scoped multi-user, multi-calendar event store.
 *
 * Storage:
 *   data/tenants/{tenantUuid}/calendar/calendar_state.json
 */
public final class calendar_system {

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final ConcurrentHashMap<String, ReentrantReadWriteLock> LOCKS = new ConcurrentHashMap<String, ReentrantReadWriteLock>();

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class StateDoc {
        public ArrayList<CalendarRec> calendars = new ArrayList<CalendarRec>();
        public LinkedHashMap<String, ArrayList<EventRec>> eventsByCalendar = new LinkedHashMap<String, ArrayList<EventRec>>();
    }

    public static final class CalendarInput {
        public String name = "";
        public String color = "";
        public String timezone = "UTC";
        public String ownerUserUuid = "";
        public String readUserUuidsCsv = "";
        public String writeUserUuidsCsv = "";
        public boolean enabled = true;
    }

    public static final class CalendarRec {
        public String uuid = "";
        public boolean enabled = true;
        public boolean trashed = false;
        public String name = "";
        public String color = "";
        public String timezone = "UTC";
        public String ownerUserUuid = "";
        public String readUserUuidsCsv = "";
        public String writeUserUuidsCsv = "";
        public String source = "native";
        public String sourceCalendarId = "";
        public String createdAt = "";
        public String updatedAt = "";
    }

    public static final class EventInput {
        public String eventUuid = "";
        public String uid = "";
        public Integer sequence = null;
        public String summary = "";
        public String description = "";
        public String location = "";
        public String startAt = "";
        public String endAt = "";
        public boolean allDay = false;
        public String status = "confirmed";
        public String organizerUserUuid = "";
        public String attendeesCsv = "";
    }

    public static final class EventRec {
        public String uuid = "";
        public String calendarUuid = "";
        public String uid = "";
        public int sequence = 0;
        public String etag = "";
        public String summary = "";
        public String description = "";
        public String location = "";
        public String startAt = "";
        public String endAt = "";
        public boolean allDay = false;
        public String status = "confirmed";
        public String organizerUserUuid = "";
        public String attendeesCsv = "";
        public String source = "native";
        public String sourceEventId = "";
        public String sourceChangeKey = "";
        public String sourceIcalUid = "";
        public String createdAt = "";
        public String updatedAt = "";
        public boolean trashed = false;
    }

    private static final class IcsTemporal {
        final String iso;
        final boolean allDay;

        IcsTemporal(String iso, boolean allDay) {
            this.iso = safe(iso);
            this.allDay = allDay;
        }
    }

    public static calendar_system defaultStore() {
        return new calendar_system();
    }

    public void ensure(String tenantUuid) throws Exception {
        String tu = safeFileToken(tenantUuid);
        if (tu.isBlank()) throw new IllegalArgumentException("tenantUuid required");

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            Path p = statePath(tu);
            Files.createDirectories(p.getParent());
            if (!Files.exists(p)) {
                writeStateLocked(tu, new StateDoc());
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public CalendarRec ensurePrimaryCalendarForUser(String tenantUuid,
                                                    String userUuid,
                                                    String fallbackDisplayLabel) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String uu = safe(userUuid).trim();
        if (tu.isBlank() || uu.isBlank()) throw new IllegalArgumentException("tenantUuid and userUuid required");

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensure(tu);
            StateDoc state = readStateLocked(tu);

            CalendarRec owned = null;
            for (CalendarRec row : state.calendars) {
                if (row == null) continue;
                if (!uu.equals(safe(row.ownerUserUuid).trim())) continue;
                if (row.trashed) continue;
                if (!row.enabled) continue;
                owned = normalizeCalendar(row);
                break;
            }
            if (owned != null) return copyCalendar(owned);

            CalendarInput in = new CalendarInput();
            String label = safe(fallbackDisplayLabel).trim();
            if (label.isBlank()) label = "Primary";
            in.name = label + " Calendar";
            in.ownerUserUuid = uu;
            in.enabled = true;
            CalendarRec created = createCalendarLocked(tu, state, in, uu, "native", "");
            writeStateLocked(tu, state);
            return copyCalendar(created);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<CalendarRec> listAllCalendars(String tenantUuid) throws Exception {
        String tu = safeFileToken(tenantUuid);
        if (tu.isBlank()) return List.of();
        ensure(tu);

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.readLock().lock();
        try {
            StateDoc state = readStateLocked(tu);
            ArrayList<CalendarRec> out = new ArrayList<CalendarRec>();
            for (CalendarRec row : state.calendars) {
                if (row == null) continue;
                out.add(copyCalendar(normalizeCalendar(row)));
            }
            out.sort(Comparator.comparing(a -> safe(a.name).toLowerCase(Locale.ROOT)));
            return out;
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<CalendarRec> listCalendarsForUser(String tenantUuid,
                                                  String userUuid,
                                                  boolean includeTrashed,
                                                  boolean includeDisabled) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String uu = safe(userUuid).trim();
        if (tu.isBlank() || uu.isBlank()) return List.of();
        ensure(tu);

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.readLock().lock();
        try {
            StateDoc state = readStateLocked(tu);
            ArrayList<CalendarRec> out = new ArrayList<CalendarRec>();
            for (CalendarRec row : state.calendars) {
                if (row == null) continue;
                CalendarRec rec = normalizeCalendar(row);
                if (!includeTrashed && rec.trashed) continue;
                if (!includeDisabled && !rec.enabled) continue;
                if (!canReadCalendar(rec, uu)) continue;
                out.add(copyCalendar(rec));
            }
            out.sort(Comparator.comparing(a -> safe(a.name).toLowerCase(Locale.ROOT)));
            return out;
        } finally {
            lock.readLock().unlock();
        }
    }

    public CalendarRec getCalendar(String tenantUuid, String calendarUuid) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String cu = safe(calendarUuid).trim();
        if (tu.isBlank() || cu.isBlank()) return null;
        ensure(tu);

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.readLock().lock();
        try {
            StateDoc state = readStateLocked(tu);
            CalendarRec rec = findCalendarByUuid(state.calendars, cu);
            if (rec == null) return null;
            return copyCalendar(normalizeCalendar(rec));
        } finally {
            lock.readLock().unlock();
        }
    }

    public CalendarRec getCalendarForUser(String tenantUuid,
                                          String calendarUuid,
                                          String userUuid,
                                          boolean requireWrite) throws Exception {
        CalendarRec rec = getCalendar(tenantUuid, calendarUuid);
        String uu = safe(userUuid).trim();
        if (rec == null || uu.isBlank()) return null;
        if (rec.trashed || !rec.enabled) return null;
        if (requireWrite) {
            if (!canWriteCalendar(rec, uu)) return null;
        } else {
            if (!canReadCalendar(rec, uu)) return null;
        }
        return rec;
    }

    public CalendarRec getCalendarBySource(String tenantUuid,
                                           String source,
                                           String sourceCalendarId) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String src = normalizeSource(source);
        String sid = safe(sourceCalendarId).trim();
        if (tu.isBlank() || src.isBlank() || sid.isBlank()) return null;
        ensure(tu);

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.readLock().lock();
        try {
            StateDoc state = readStateLocked(tu);
            for (CalendarRec row : state.calendars) {
                if (row == null) continue;
                CalendarRec rec = normalizeCalendar(row);
                if (src.equals(rec.source) && sid.equals(rec.sourceCalendarId)) {
                    return copyCalendar(rec);
                }
            }
            return null;
        } finally {
            lock.readLock().unlock();
        }
    }

    public CalendarRec findOrCreateExternalCalendar(String tenantUuid,
                                                    String ownerUserUuid,
                                                    String source,
                                                    String sourceCalendarId,
                                                    String suggestedName,
                                                    String timezone) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String uu = safe(ownerUserUuid).trim();
        String src = normalizeSource(source);
        String sid = safe(sourceCalendarId).trim();
        if (tu.isBlank() || uu.isBlank() || src.isBlank() || sid.isBlank()) {
            throw new IllegalArgumentException("tenantUuid, ownerUserUuid, source, and sourceCalendarId required");
        }

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensure(tu);
            StateDoc state = readStateLocked(tu);

            CalendarRec existing = null;
            for (CalendarRec row : state.calendars) {
                if (row == null) continue;
                CalendarRec rec = normalizeCalendar(row);
                if (src.equals(rec.source) && sid.equals(rec.sourceCalendarId)) {
                    existing = rec;
                    break;
                }
            }

            if (existing != null) {
                boolean changed = false;
                String wantedName = normalizeCalendarName(suggestedName);
                if (wantedName.isBlank()) wantedName = existing.name;
                String wantedTz = normalizeTimeZone(timezone);
                if (!wantedName.equals(existing.name)) {
                    existing.name = wantedName;
                    changed = true;
                }
                if (!wantedTz.equals(existing.timezone)) {
                    existing.timezone = wantedTz;
                    changed = true;
                }
                if (!uu.equals(existing.ownerUserUuid)) {
                    existing.ownerUserUuid = uu;
                    changed = true;
                }
                if (existing.trashed) {
                    existing.trashed = false;
                    changed = true;
                }
                if (!existing.enabled) {
                    existing.enabled = true;
                    changed = true;
                }
                if (changed) {
                    existing.updatedAt = app_clock.now().toString();
                    replaceCalendarByUuid(state.calendars, existing);
                    sortCalendars(state.calendars);
                    writeStateLocked(tu, state);
                }
                return copyCalendar(existing);
            }

            CalendarInput in = new CalendarInput();
            in.name = normalizeCalendarName(suggestedName);
            if (in.name.isBlank()) in.name = "External Calendar";
            in.ownerUserUuid = uu;
            in.timezone = normalizeTimeZone(timezone);
            in.enabled = true;

            CalendarRec created = createCalendarLocked(tu, state, in, uu, src, sid);
            writeStateLocked(tu, state);
            return copyCalendar(created);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public CalendarRec createCalendar(String tenantUuid,
                                      CalendarInput input,
                                      String actorUserUuid) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String actor = safe(actorUserUuid).trim();
        if (tu.isBlank()) throw new IllegalArgumentException("tenantUuid required");
        if (actor.isBlank()) throw new IllegalArgumentException("actorUserUuid required");

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensure(tu);
            StateDoc state = readStateLocked(tu);
            CalendarRec created = createCalendarLocked(tu, state, input, actor, "native", "");
            writeStateLocked(tu, state);
            return copyCalendar(created);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public CalendarRec updateCalendar(String tenantUuid,
                                      String calendarUuid,
                                      CalendarInput patch,
                                      String actorUserUuid) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String cu = safe(calendarUuid).trim();
        String actor = safe(actorUserUuid).trim();
        if (tu.isBlank() || cu.isBlank() || actor.isBlank()) throw new IllegalArgumentException("tenantUuid, calendarUuid, actorUserUuid required");

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensure(tu);
            StateDoc state = readStateLocked(tu);
            CalendarRec current = findCalendarByUuid(state.calendars, cu);
            if (current == null) throw new IllegalArgumentException("Calendar not found.");
            CalendarRec rec = normalizeCalendar(current);
            if (!canWriteCalendar(rec, actor)) throw new SecurityException("Calendar write access denied.");

            boolean changed = false;
            if (patch != null) {
                if (hasText(patch.name)) {
                    String nv = normalizeCalendarName(patch.name);
                    if (!nv.equals(rec.name)) {
                        rec.name = nv;
                        changed = true;
                    }
                }
                if (hasText(patch.color)) {
                    String nv = normalizeColor(patch.color);
                    if (!nv.equals(rec.color)) {
                        rec.color = nv;
                        changed = true;
                    }
                }
                if (hasText(patch.timezone)) {
                    String nv = normalizeTimeZone(patch.timezone);
                    if (!nv.equals(rec.timezone)) {
                        rec.timezone = nv;
                        changed = true;
                    }
                }
                if (!patch.ownerUserUuid.isBlank()) {
                    String nv = safe(patch.ownerUserUuid).trim();
                    if (!nv.equals(rec.ownerUserUuid)) {
                        rec.ownerUserUuid = nv;
                        changed = true;
                    }
                }
                if (patch.enabled != rec.enabled) {
                    rec.enabled = patch.enabled;
                    changed = true;
                }
            }

            if (changed) {
                rec.updatedAt = app_clock.now().toString();
                replaceCalendarByUuid(state.calendars, rec);
                sortCalendars(state.calendars);
                writeStateLocked(tu, state);
            }
            return copyCalendar(rec);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public CalendarRec setCalendarAccess(String tenantUuid,
                                         String calendarUuid,
                                         String readUserUuidsCsv,
                                         String writeUserUuidsCsv,
                                         String actorUserUuid) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String cu = safe(calendarUuid).trim();
        String actor = safe(actorUserUuid).trim();
        if (tu.isBlank() || cu.isBlank() || actor.isBlank()) throw new IllegalArgumentException("tenantUuid, calendarUuid, actorUserUuid required");

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensure(tu);
            StateDoc state = readStateLocked(tu);
            CalendarRec current = findCalendarByUuid(state.calendars, cu);
            if (current == null) throw new IllegalArgumentException("Calendar not found.");
            CalendarRec rec = normalizeCalendar(current);
            if (!canWriteCalendar(rec, actor)) throw new SecurityException("Calendar write access denied.");

            String writeCsv = normalizeUsersCsv(writeUserUuidsCsv, rec.ownerUserUuid);
            String readCsv = normalizeUsersCsv(readUserUuidsCsv, rec.ownerUserUuid);
            readCsv = removeUsersCsv(readCsv, csvToSet(writeCsv));

            boolean changed = false;
            if (!writeCsv.equals(rec.writeUserUuidsCsv)) {
                rec.writeUserUuidsCsv = writeCsv;
                changed = true;
            }
            if (!readCsv.equals(rec.readUserUuidsCsv)) {
                rec.readUserUuidsCsv = readCsv;
                changed = true;
            }

            if (changed) {
                rec.updatedAt = app_clock.now().toString();
                replaceCalendarByUuid(state.calendars, rec);
                sortCalendars(state.calendars);
                writeStateLocked(tu, state);
            }
            return copyCalendar(rec);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<EventRec> listEventsForUser(String tenantUuid,
                                            String calendarUuid,
                                            String userUuid,
                                            String startInclusive,
                                            String endExclusive,
                                            boolean includeTrashed) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String cu = safe(calendarUuid).trim();
        String uu = safe(userUuid).trim();
        if (tu.isBlank() || cu.isBlank() || uu.isBlank()) return List.of();
        ensure(tu);

        Instant rangeStart = parseInstantLoose(startInclusive);
        Instant rangeEnd = parseInstantLoose(endExclusive);

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.readLock().lock();
        try {
            StateDoc state = readStateLocked(tu);
            CalendarRec calendar = findCalendarByUuid(state.calendars, cu);
            if (calendar == null) throw new IllegalArgumentException("Calendar not found.");
            CalendarRec recCal = normalizeCalendar(calendar);
            if (!canReadCalendar(recCal, uu)) throw new SecurityException("Calendar read access denied.");

            ArrayList<EventRec> out = new ArrayList<EventRec>();
            List<EventRec> rows = eventsList(state, cu);
            for (EventRec row : rows) {
                if (row == null) continue;
                EventRec rec = normalizeEvent(row, cu);
                if (!includeTrashed && rec.trashed) continue;
                if (!overlapsRange(rec, rangeStart, rangeEnd)) continue;
                out.add(copyEvent(rec));
            }
            sortEvents(out);
            return out;
        } finally {
            lock.readLock().unlock();
        }
    }

    public EventRec getEventByUuidForUser(String tenantUuid,
                                          String calendarUuid,
                                          String eventUuid,
                                          String userUuid,
                                          boolean requireWrite) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String cu = safe(calendarUuid).trim();
        String eu = safe(eventUuid).trim();
        String uu = safe(userUuid).trim();
        if (tu.isBlank() || cu.isBlank() || eu.isBlank() || uu.isBlank()) return null;
        ensure(tu);

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.readLock().lock();
        try {
            StateDoc state = readStateLocked(tu);
            CalendarRec calendar = findCalendarByUuid(state.calendars, cu);
            if (calendar == null) return null;
            CalendarRec recCal = normalizeCalendar(calendar);
            boolean allowed = requireWrite ? canWriteCalendar(recCal, uu) : canReadCalendar(recCal, uu);
            if (!allowed) return null;

            EventRec event = findEventByUuid(eventsList(state, cu), eu);
            if (event == null) return null;
            EventRec rec = normalizeEvent(event, cu);
            if (rec.trashed) return null;
            return copyEvent(rec);
        } finally {
            lock.readLock().unlock();
        }
    }

    public EventRec getEventByUidForUser(String tenantUuid,
                                         String calendarUuid,
                                         String uid,
                                         String userUuid,
                                         boolean requireWrite) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String cu = safe(calendarUuid).trim();
        String uq = safe(uid).trim();
        String uu = safe(userUuid).trim();
        if (tu.isBlank() || cu.isBlank() || uq.isBlank() || uu.isBlank()) return null;
        ensure(tu);

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.readLock().lock();
        try {
            StateDoc state = readStateLocked(tu);
            CalendarRec calendar = findCalendarByUuid(state.calendars, cu);
            if (calendar == null) return null;
            CalendarRec recCal = normalizeCalendar(calendar);
            boolean allowed = requireWrite ? canWriteCalendar(recCal, uu) : canReadCalendar(recCal, uu);
            if (!allowed) return null;

            EventRec event = findEventByUid(eventsList(state, cu), uq);
            if (event == null) return null;
            EventRec rec = normalizeEvent(event, cu);
            if (rec.trashed) return null;
            return copyEvent(rec);
        } finally {
            lock.readLock().unlock();
        }
    }

    public EventRec getEventBySource(String tenantUuid,
                                     String calendarUuid,
                                     String source,
                                     String sourceEventId) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String cu = safe(calendarUuid).trim();
        String src = normalizeSource(source);
        String sid = safe(sourceEventId).trim();
        if (tu.isBlank() || cu.isBlank() || src.isBlank() || sid.isBlank()) return null;
        ensure(tu);

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.readLock().lock();
        try {
            StateDoc state = readStateLocked(tu);
            for (EventRec row : eventsList(state, cu)) {
                if (row == null) continue;
                EventRec rec = normalizeEvent(row, cu);
                if (src.equals(rec.source) && sid.equals(rec.sourceEventId)) return copyEvent(rec);
            }
            return null;
        } finally {
            lock.readLock().unlock();
        }
    }

    public EventRec putEventForUser(String tenantUuid,
                                    String calendarUuid,
                                    EventInput input,
                                    String actorUserUuid) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String cu = safe(calendarUuid).trim();
        String actor = safe(actorUserUuid).trim();
        if (tu.isBlank() || cu.isBlank() || actor.isBlank()) {
            throw new IllegalArgumentException("tenantUuid, calendarUuid, actorUserUuid required");
        }

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensure(tu);
            StateDoc state = readStateLocked(tu);
            CalendarRec calendar = findCalendarByUuid(state.calendars, cu);
            if (calendar == null) throw new IllegalArgumentException("Calendar not found.");
            CalendarRec recCal = normalizeCalendar(calendar);
            if (!canWriteCalendar(recCal, actor)) throw new SecurityException("Calendar write access denied.");

            EventRec out = putEventLocked(
                    state,
                    recCal,
                    input,
                    actor,
                    "native",
                    "",
                    "",
                    ""
            );
            writeStateLocked(tu, state);
            return copyEvent(out);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public EventRec upsertExternalEvent(String tenantUuid,
                                        String calendarUuid,
                                        EventInput input,
                                        String source,
                                        String sourceEventId,
                                        String sourceChangeKey,
                                        String sourceIcalUid) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String cu = safe(calendarUuid).trim();
        String src = normalizeSource(source);
        String sid = safe(sourceEventId).trim();
        if (tu.isBlank() || cu.isBlank() || src.isBlank() || sid.isBlank()) {
            throw new IllegalArgumentException("tenantUuid, calendarUuid, source, sourceEventId required");
        }

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensure(tu);
            StateDoc state = readStateLocked(tu);
            CalendarRec calendar = findCalendarByUuid(state.calendars, cu);
            if (calendar == null) throw new IllegalArgumentException("Calendar not found.");
            CalendarRec recCal = normalizeCalendar(calendar);

            EventRec out = putEventLocked(
                    state,
                    recCal,
                    input,
                    safe(input == null ? "" : input.organizerUserUuid),
                    src,
                    sid,
                    safe(sourceChangeKey),
                    safe(sourceIcalUid)
            );
            writeStateLocked(tu, state);
            return copyEvent(out);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean deleteEventByUuidForUser(String tenantUuid,
                                            String calendarUuid,
                                            String eventUuid,
                                            String actorUserUuid) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String cu = safe(calendarUuid).trim();
        String eu = safe(eventUuid).trim();
        String actor = safe(actorUserUuid).trim();
        if (tu.isBlank() || cu.isBlank() || eu.isBlank() || actor.isBlank()) {
            throw new IllegalArgumentException("tenantUuid, calendarUuid, eventUuid, actorUserUuid required");
        }

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensure(tu);
            StateDoc state = readStateLocked(tu);
            CalendarRec calendar = findCalendarByUuid(state.calendars, cu);
            if (calendar == null) throw new IllegalArgumentException("Calendar not found.");
            CalendarRec recCal = normalizeCalendar(calendar);
            if (!canWriteCalendar(recCal, actor)) throw new SecurityException("Calendar write access denied.");

            ArrayList<EventRec> list = new ArrayList<EventRec>(eventsList(state, cu));
            boolean changed = false;
            ArrayList<EventRec> out = new ArrayList<EventRec>(list.size());
            for (EventRec row : list) {
                if (row == null) continue;
                EventRec rec = normalizeEvent(row, cu);
                if (eu.equals(rec.uuid)) {
                    changed = true;
                    continue;
                }
                out.add(rec);
            }
            if (!changed) return false;

            sortEvents(out);
            state.eventsByCalendar.put(cu, out);
            writeStateLocked(tu, state);
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean canReadCalendar(CalendarRec rec, String userUuid) {
        String uu = safe(userUuid).trim();
        CalendarRec cal = normalizeCalendar(rec);
        if (uu.isBlank()) return false;
        if (uu.equals(cal.ownerUserUuid)) return true;
        if (csvContains(cal.writeUserUuidsCsv, uu)) return true;
        return csvContains(cal.readUserUuidsCsv, uu);
    }

    public boolean canWriteCalendar(CalendarRec rec, String userUuid) {
        String uu = safe(userUuid).trim();
        CalendarRec cal = normalizeCalendar(rec);
        if (uu.isBlank()) return false;
        if (uu.equals(cal.ownerUserUuid)) return true;
        return csvContains(cal.writeUserUuidsCsv, uu);
    }

    public EventInput parseIcsEvent(String icsBody, String fallbackUid) {
        EventInput out = new EventInput();
        out.uid = safe(fallbackUid).trim();

        String normalized = safe(icsBody)
                .replace("\r\n", "\n")
                .replace('\r', '\n');

        String[] rawLines = normalized.split("\n", -1);
        ArrayList<String> lines = new ArrayList<String>();
        for (String raw : rawLines) {
            String line = safe(raw);
            if ((line.startsWith(" ") || line.startsWith("\t")) && !lines.isEmpty()) {
                int last = lines.size() - 1;
                lines.set(last, lines.get(last) + line.substring(1));
            } else {
                lines.add(line);
            }
        }

        boolean inEvent = false;
        ArrayList<String> attendees = new ArrayList<String>();
        for (String line : lines) {
            String row = safe(line).trim();
            if (row.isBlank()) continue;
            if ("BEGIN:VEVENT".equalsIgnoreCase(row)) {
                inEvent = true;
                continue;
            }
            if ("END:VEVENT".equalsIgnoreCase(row)) {
                inEvent = false;
                break;
            }
            if (!inEvent) continue;

            int colon = row.indexOf(':');
            if (colon <= 0) continue;
            String nameAndParams = row.substring(0, colon).trim();
            String value = row.substring(colon + 1);

            String prop = nameAndParams;
            int semi = prop.indexOf(';');
            if (semi >= 0) prop = prop.substring(0, semi);
            prop = prop.trim().toUpperCase(Locale.ROOT);

            if ("UID".equals(prop)) {
                String uid = safe(unescapeIcsText(value)).trim();
                if (!uid.isBlank()) out.uid = uid;
                continue;
            }
            if ("SUMMARY".equals(prop)) {
                out.summary = unescapeIcsText(value);
                continue;
            }
            if ("DESCRIPTION".equals(prop)) {
                out.description = unescapeIcsText(value);
                continue;
            }
            if ("LOCATION".equals(prop)) {
                out.location = unescapeIcsText(value);
                continue;
            }
            if ("STATUS".equals(prop)) {
                out.status = normalizeStatus(value);
                continue;
            }
            if ("SEQUENCE".equals(prop)) {
                try {
                    out.sequence = Integer.valueOf(Math.max(0, Integer.parseInt(safe(value).trim())));
                } catch (Exception ignored) {
                    out.sequence = Integer.valueOf(0);
                }
                continue;
            }
            if ("DTSTART".equals(prop)) {
                IcsTemporal t = parseIcsTemporal(nameAndParams, value);
                out.startAt = t.iso;
                out.allDay = t.allDay;
                continue;
            }
            if ("DTEND".equals(prop)) {
                IcsTemporal t = parseIcsTemporal(nameAndParams, value);
                out.endAt = t.iso;
                if (t.allDay) out.allDay = true;
                continue;
            }
            if ("ORGANIZER".equals(prop)) {
                String email = extractMailto(value);
                if (!email.isBlank()) out.organizerUserUuid = email;
                continue;
            }
            if ("ATTENDEE".equals(prop)) {
                String email = extractMailto(value);
                if (!email.isBlank()) attendees.add(email.toLowerCase(Locale.ROOT));
            }
        }

        if (!attendees.isEmpty()) {
            LinkedHashSet<String> uniq = new LinkedHashSet<String>(attendees);
            out.attendeesCsv = String.join(",", uniq);
        }

        if (safe(out.endAt).isBlank()) {
            out.endAt = defaultEndForStart(out.startAt, out.allDay);
        }

        if (safe(out.uid).isBlank()) {
            out.uid = generatedUid();
        }

        return out;
    }

    public String toIcs(EventRec rawEvent) {
        EventRec rec = normalizeEvent(rawEvent, safe(rawEvent == null ? "" : rawEvent.calendarUuid));

        String uid = safe(rec.uid).trim();
        if (uid.isBlank()) uid = generatedUid();

        String dtstamp = formatUtcDateTime(parseInstantLoose(rec.updatedAt));
        if (dtstamp.isBlank()) dtstamp = formatUtcDateTime(app_clock.now());

        String start = safe(rec.startAt).trim();
        String end = safe(rec.endAt).trim();
        if (end.isBlank()) end = defaultEndForStart(start, rec.allDay);

        StringBuilder sb = new StringBuilder();
        appendIcsLine(sb, "BEGIN:VCALENDAR");
        appendIcsLine(sb, "VERSION:2.0");
        appendIcsLine(sb, "PRODID:-//Controversies//Calendar System//EN");
        appendIcsLine(sb, "CALSCALE:GREGORIAN");
        appendIcsLine(sb, "BEGIN:VEVENT");
        appendIcsLine(sb, "UID:" + escapeIcsText(uid));
        appendIcsLine(sb, "DTSTAMP:" + dtstamp);
        appendIcsLine(sb, "SEQUENCE:" + Math.max(0, rec.sequence));

        String summary = safe(rec.summary).trim();
        if (!summary.isBlank()) appendIcsLine(sb, "SUMMARY:" + escapeIcsText(summary));
        String description = safe(rec.description);
        if (!description.isBlank()) appendIcsLine(sb, "DESCRIPTION:" + escapeIcsText(description));
        String location = safe(rec.location);
        if (!location.isBlank()) appendIcsLine(sb, "LOCATION:" + escapeIcsText(location));

        if (rec.allDay) {
            LocalDate startDate = toLocalDate(start);
            LocalDate endDate = toLocalDate(end);
            if (startDate != null) {
                appendIcsLine(sb, "DTSTART;VALUE=DATE:" + startDate.format(DateTimeFormatter.BASIC_ISO_DATE));
            }
            if (endDate != null) {
                if (!endDate.isAfter(startDate == null ? endDate.minusDays(1) : startDate)) {
                    endDate = (startDate == null ? endDate : startDate).plusDays(1);
                }
                appendIcsLine(sb, "DTEND;VALUE=DATE:" + endDate.format(DateTimeFormatter.BASIC_ISO_DATE));
            }
        } else {
            String dtStart = formatUtcDateTime(parseInstantLoose(start));
            String dtEnd = formatUtcDateTime(parseInstantLoose(end));
            if (!dtStart.isBlank()) appendIcsLine(sb, "DTSTART:" + dtStart);
            if (!dtEnd.isBlank()) appendIcsLine(sb, "DTEND:" + dtEnd);
        }

        String status = normalizeStatus(rec.status);
        if (!status.isBlank()) appendIcsLine(sb, "STATUS:" + status.toUpperCase(Locale.ROOT));

        for (String attendee : csvToSet(rec.attendeesCsv)) {
            String em = safe(attendee).trim().toLowerCase(Locale.ROOT);
            if (em.isBlank()) continue;
            appendIcsLine(sb, "ATTENDEE:mailto:" + em);
        }

        appendIcsLine(sb, "END:VEVENT");
        appendIcsLine(sb, "END:VCALENDAR");
        return sb.toString();
    }

    public static String calendarSegment(CalendarRec rec) {
        CalendarRec row = normalizeCalendar(rec);
        return safeSegmentLabel(row.name, "Calendar") + "__" + safe(row.uuid).trim();
    }

    public static String eventFileName(EventRec rec) {
        EventRec row = normalizeEvent(rec, safe(rec == null ? "" : rec.calendarUuid));
        String label = safe(row.summary).trim();
        if (label.isBlank()) label = safe(row.uid).trim();
        if (label.isBlank()) label = "Event";
        return safeSegmentLabel(label, "Event") + "__" + safe(row.uuid).trim() + ".ics";
    }

    public static String extractUuidSuffix(String segment) {
        String s = safe(segment).trim();
        if (s.toLowerCase(Locale.ROOT).endsWith(".ics")) {
            s = s.substring(0, s.length() - 4);
        }
        int idx = s.lastIndexOf("__");
        if (idx <= 0 || idx + 2 >= s.length()) return "";
        String maybe = s.substring(idx + 2).trim();
        if (!looksUuid(maybe)) return "";
        return maybe;
    }

    private static CalendarRec createCalendarLocked(String tenantUuid,
                                                    StateDoc state,
                                                    CalendarInput input,
                                                    String actorUserUuid,
                                                    String source,
                                                    String sourceCalendarId) {
        CalendarInput in = input == null ? new CalendarInput() : input;
        String actor = safe(actorUserUuid).trim();

        CalendarRec rec = new CalendarRec();
        rec.uuid = UUID.randomUUID().toString();
        rec.enabled = in.enabled;
        rec.trashed = false;
        rec.name = normalizeCalendarName(in.name);
        if (rec.name.isBlank()) rec.name = "Calendar";
        rec.color = normalizeColor(in.color);
        rec.timezone = normalizeTimeZone(in.timezone);
        rec.ownerUserUuid = safe(in.ownerUserUuid).trim();
        if (rec.ownerUserUuid.isBlank()) rec.ownerUserUuid = actor;
        if (rec.ownerUserUuid.isBlank()) {
            throw new IllegalArgumentException("calendar owner user is required");
        }

        String writeCsv = normalizeUsersCsv(in.writeUserUuidsCsv, rec.ownerUserUuid);
        String readCsv = normalizeUsersCsv(in.readUserUuidsCsv, rec.ownerUserUuid);
        readCsv = removeUsersCsv(readCsv, csvToSet(writeCsv));

        rec.writeUserUuidsCsv = writeCsv;
        rec.readUserUuidsCsv = readCsv;
        rec.source = normalizeSource(source);
        if (rec.source.isBlank()) rec.source = "native";
        rec.sourceCalendarId = safe(sourceCalendarId).trim();

        String now = app_clock.now().toString();
        rec.createdAt = now;
        rec.updatedAt = now;

        state.calendars.add(rec);
        sortCalendars(state.calendars);
        state.eventsByCalendar.putIfAbsent(rec.uuid, new ArrayList<EventRec>());

        return copyCalendar(rec);
    }

    private static EventRec putEventLocked(StateDoc state,
                                           CalendarRec calendar,
                                           EventInput input,
                                           String actorUserUuid,
                                           String source,
                                           String sourceEventId,
                                           String sourceChangeKey,
                                           String sourceIcalUid) {
        CalendarRec cal = normalizeCalendar(calendar);
        EventInput in = input == null ? new EventInput() : input;

        ArrayList<EventRec> all = new ArrayList<EventRec>(eventsList(state, cal.uuid));
        String eventUuid = safe(in.eventUuid).trim();
        String uid = safe(in.uid).trim();

        EventRec existing = null;
        if (!eventUuid.isBlank()) {
            existing = findEventByUuid(all, eventUuid);
        }
        if (existing == null && !safe(source).isBlank() && !safe(sourceEventId).isBlank()) {
            existing = findEventBySource(all, source, sourceEventId);
        }
        if (existing == null && !uid.isBlank()) {
            existing = findEventByUid(all, uid);
        }

        String now = app_clock.now().toString();
        EventRec rec;
        boolean created;
        if (existing == null) {
            rec = new EventRec();
            rec.uuid = UUID.randomUUID().toString();
            rec.calendarUuid = cal.uuid;
            rec.createdAt = now;
            rec.sequence = Math.max(0, in.sequence == null ? 0 : in.sequence.intValue());
            created = true;
        } else {
            rec = normalizeEvent(existing, cal.uuid);
            rec.sequence = Math.max(0, rec.sequence + 1);
            if (in.sequence != null) {
                rec.sequence = Math.max(rec.sequence, Math.max(0, in.sequence.intValue()));
            }
            created = false;
        }

        rec.uid = safe(uid).trim();
        if (rec.uid.isBlank()) {
            if (!safe(sourceIcalUid).trim().isBlank()) rec.uid = safe(sourceIcalUid).trim();
            else rec.uid = generatedUid();
        }

        rec.summary = clampLen(in.summary, 500);
        rec.description = clampLen(in.description, 32000);
        rec.location = clampLen(in.location, 1000);

        rec.allDay = in.allDay;
        rec.startAt = normalizeIsoDateTime(in.startAt, rec.allDay, true);
        rec.endAt = normalizeIsoDateTime(in.endAt, rec.allDay, false);
        if (rec.startAt.isBlank()) {
            rec.startAt = app_clock.now().toString();
            rec.allDay = false;
        }
        if (rec.endAt.isBlank()) {
            rec.endAt = defaultEndForStart(rec.startAt, rec.allDay);
        }

        rec.status = normalizeStatus(in.status);
        rec.organizerUserUuid = safe(in.organizerUserUuid).trim();
        if (rec.organizerUserUuid.isBlank()) rec.organizerUserUuid = safe(actorUserUuid).trim();
        rec.attendeesCsv = normalizeEmailCsv(in.attendeesCsv);

        rec.source = normalizeSource(source);
        if (rec.source.isBlank()) rec.source = "native";
        rec.sourceEventId = safe(sourceEventId).trim();
        rec.sourceChangeKey = safe(sourceChangeKey).trim();
        rec.sourceIcalUid = safe(sourceIcalUid).trim();

        rec.trashed = false;
        rec.updatedAt = now;
        rec.etag = makeStrongEtag(rec.uuid, rec.sequence, rec.updatedAt, created);

        replaceOrAppendEvent(all, rec);
        sortEvents(all);
        state.eventsByCalendar.put(cal.uuid, all);
        return copyEvent(rec);
    }

    private static boolean overlapsRange(EventRec rec, Instant rangeStart, Instant rangeEnd) {
        if (rec == null) return false;
        Instant evStart = parseInstantLoose(rec.startAt);
        Instant evEnd = parseInstantLoose(rec.endAt);
        if (evStart == null && evEnd == null) return true;
        if (evStart == null) evStart = evEnd;
        if (evEnd == null) evEnd = evStart;

        if (rangeStart != null && evEnd != null && evEnd.isBefore(rangeStart)) return false;
        if (rangeEnd != null && evStart != null && !evStart.isBefore(rangeEnd)) return false;
        return true;
    }

    private static CalendarRec findCalendarByUuid(List<CalendarRec> rows, String calendarUuid) {
        String cu = safe(calendarUuid).trim();
        if (rows == null || cu.isBlank()) return null;
        for (CalendarRec row : rows) {
            if (row == null) continue;
            if (cu.equals(safe(row.uuid).trim())) return row;
        }
        return null;
    }

    private static EventRec findEventByUuid(List<EventRec> rows, String eventUuid) {
        String eu = safe(eventUuid).trim();
        if (rows == null || eu.isBlank()) return null;
        for (EventRec row : rows) {
            if (row == null) continue;
            if (eu.equals(safe(row.uuid).trim())) return row;
        }
        return null;
    }

    private static EventRec findEventByUid(List<EventRec> rows, String uid) {
        String uq = safe(uid).trim();
        if (rows == null || uq.isBlank()) return null;
        for (EventRec row : rows) {
            if (row == null) continue;
            if (uq.equals(safe(row.uid).trim())) return row;
        }
        return null;
    }

    private static EventRec findEventBySource(List<EventRec> rows, String source, String sourceEventId) {
        String src = normalizeSource(source);
        String sid = safe(sourceEventId).trim();
        if (rows == null || src.isBlank() || sid.isBlank()) return null;
        for (EventRec row : rows) {
            if (row == null) continue;
            EventRec rec = normalizeEvent(row, safe(row.calendarUuid));
            if (src.equals(rec.source) && sid.equals(rec.sourceEventId)) return rec;
        }
        return null;
    }

    private static void replaceOrAppendEvent(List<EventRec> rows, EventRec next) {
        if (rows == null || next == null) return;
        String id = safe(next.uuid).trim();
        if (id.isBlank()) {
            rows.add(next);
            return;
        }
        for (int i = 0; i < rows.size(); i++) {
            EventRec row = rows.get(i);
            if (row == null) continue;
            if (!id.equals(safe(row.uuid).trim())) continue;
            rows.set(i, next);
            return;
        }
        rows.add(next);
    }

    private static void replaceCalendarByUuid(List<CalendarRec> rows, CalendarRec next) {
        if (rows == null || next == null) return;
        String id = safe(next.uuid).trim();
        if (id.isBlank()) return;
        for (int i = 0; i < rows.size(); i++) {
            CalendarRec row = rows.get(i);
            if (row == null) continue;
            if (!id.equals(safe(row.uuid).trim())) continue;
            rows.set(i, next);
            return;
        }
    }

    private static void sortCalendars(List<CalendarRec> rows) {
        if (rows == null) return;
        rows.sort(Comparator
                .comparing((CalendarRec c) -> safe(c == null ? "" : c.name).toLowerCase(Locale.ROOT))
                .thenComparing(c -> safe(c == null ? "" : c.uuid)));
    }

    private static void sortEvents(List<EventRec> rows) {
        if (rows == null) return;
        rows.sort(Comparator
                .comparing((EventRec e) -> safe(e == null ? "" : normalizeIsoDateTime(e.startAt, e.allDay, true)))
                .thenComparing(e -> safe(e == null ? "" : e.uid))
                .thenComparing(e -> safe(e == null ? "" : e.uuid)));
    }

    private static ReentrantReadWriteLock lockFor(String tenantUuid) {
        String tu = safeFileToken(tenantUuid);
        return LOCKS.computeIfAbsent(tu, k -> new ReentrantReadWriteLock());
    }

    private static Path statePath(String tenantUuid) {
        String tu = safeFileToken(tenantUuid);
        if (tu.isBlank()) return null;
        return Paths.get("data", "tenants", tu, "calendar", "calendar_state.json").toAbsolutePath();
    }

    private static StateDoc readStateLocked(String tenantUuid) throws Exception {
        Path p = statePath(tenantUuid);
        if (p == null) throw new IllegalArgumentException("tenantUuid required");
        if (!Files.exists(p)) return new StateDoc();

        String raw = Files.readString(p, StandardCharsets.UTF_8);
        if (safe(raw).trim().isBlank()) return new StateDoc();

        StateDoc parsed = JSON.readValue(raw, StateDoc.class);
        if (parsed == null) parsed = new StateDoc();
        if (parsed.calendars == null) parsed.calendars = new ArrayList<CalendarRec>();
        if (parsed.eventsByCalendar == null) parsed.eventsByCalendar = new LinkedHashMap<String, ArrayList<EventRec>>();

        ArrayList<CalendarRec> cleanCalendars = new ArrayList<CalendarRec>();
        for (CalendarRec row : parsed.calendars) {
            if (row == null) continue;
            cleanCalendars.add(normalizeCalendar(row));
        }
        sortCalendars(cleanCalendars);
        parsed.calendars = cleanCalendars;

        LinkedHashMap<String, ArrayList<EventRec>> cleanEvents = new LinkedHashMap<String, ArrayList<EventRec>>();
        for (Map.Entry<String, ArrayList<EventRec>> e : parsed.eventsByCalendar.entrySet()) {
            String calendarUuid = safe(e == null ? "" : e.getKey()).trim();
            if (calendarUuid.isBlank()) continue;
            ArrayList<EventRec> out = new ArrayList<EventRec>();
            List<EventRec> rows = e == null ? List.of() : e.getValue();
            if (rows != null) {
                for (EventRec row : rows) {
                    if (row == null) continue;
                    out.add(normalizeEvent(row, calendarUuid));
                }
            }
            sortEvents(out);
            cleanEvents.put(calendarUuid, out);
        }

        for (CalendarRec cal : parsed.calendars) {
            if (cal == null) continue;
            String cu = safe(cal.uuid).trim();
            if (cu.isBlank()) continue;
            cleanEvents.putIfAbsent(cu, new ArrayList<EventRec>());
        }

        parsed.eventsByCalendar = cleanEvents;
        return parsed;
    }

    private static void writeStateLocked(String tenantUuid, StateDoc state) throws Exception {
        Path p = statePath(tenantUuid);
        if (p == null) throw new IllegalArgumentException("tenantUuid required");

        Files.createDirectories(p.getParent());

        StateDoc out = state == null ? new StateDoc() : state;
        if (out.calendars == null) out.calendars = new ArrayList<CalendarRec>();
        if (out.eventsByCalendar == null) out.eventsByCalendar = new LinkedHashMap<String, ArrayList<EventRec>>();

        sortCalendars(out.calendars);
        for (Map.Entry<String, ArrayList<EventRec>> e : out.eventsByCalendar.entrySet()) {
            if (e == null || e.getValue() == null) continue;
            sortEvents(e.getValue());
        }

        String json = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(out);
        Path tmp = p.resolveSibling(p.getFileName().toString() + ".tmp");
        Files.writeString(tmp, json, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        try {
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception atomicNotSupported) {
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static List<EventRec> eventsList(StateDoc state, String calendarUuid) {
        if (state == null) return List.of();
        String cu = safe(calendarUuid).trim();
        if (cu.isBlank()) return List.of();
        ArrayList<EventRec> rows = state.eventsByCalendar.get(cu);
        if (rows == null) {
            rows = new ArrayList<EventRec>();
            state.eventsByCalendar.put(cu, rows);
        }
        return rows;
    }

    private static CalendarRec copyCalendar(CalendarRec rec) {
        CalendarRec row = normalizeCalendar(rec);
        CalendarRec out = new CalendarRec();
        out.uuid = row.uuid;
        out.enabled = row.enabled;
        out.trashed = row.trashed;
        out.name = row.name;
        out.color = row.color;
        out.timezone = row.timezone;
        out.ownerUserUuid = row.ownerUserUuid;
        out.readUserUuidsCsv = row.readUserUuidsCsv;
        out.writeUserUuidsCsv = row.writeUserUuidsCsv;
        out.source = row.source;
        out.sourceCalendarId = row.sourceCalendarId;
        out.createdAt = row.createdAt;
        out.updatedAt = row.updatedAt;
        return out;
    }

    private static EventRec copyEvent(EventRec rec) {
        EventRec row = normalizeEvent(rec, safe(rec == null ? "" : rec.calendarUuid));
        EventRec out = new EventRec();
        out.uuid = row.uuid;
        out.calendarUuid = row.calendarUuid;
        out.uid = row.uid;
        out.sequence = row.sequence;
        out.etag = row.etag;
        out.summary = row.summary;
        out.description = row.description;
        out.location = row.location;
        out.startAt = row.startAt;
        out.endAt = row.endAt;
        out.allDay = row.allDay;
        out.status = row.status;
        out.organizerUserUuid = row.organizerUserUuid;
        out.attendeesCsv = row.attendeesCsv;
        out.source = row.source;
        out.sourceEventId = row.sourceEventId;
        out.sourceChangeKey = row.sourceChangeKey;
        out.sourceIcalUid = row.sourceIcalUid;
        out.createdAt = row.createdAt;
        out.updatedAt = row.updatedAt;
        out.trashed = row.trashed;
        return out;
    }

    private static CalendarRec normalizeCalendar(CalendarRec in) {
        CalendarRec out = new CalendarRec();
        if (in == null) return out;

        out.uuid = safe(in.uuid).trim();
        out.enabled = in.enabled;
        out.trashed = in.trashed;
        out.name = normalizeCalendarName(in.name);
        if (out.name.isBlank()) out.name = "Calendar";
        out.color = normalizeColor(in.color);
        out.timezone = normalizeTimeZone(in.timezone);
        out.ownerUserUuid = safe(in.ownerUserUuid).trim();
        out.writeUserUuidsCsv = normalizeUsersCsv(in.writeUserUuidsCsv, out.ownerUserUuid);
        out.readUserUuidsCsv = removeUsersCsv(
                normalizeUsersCsv(in.readUserUuidsCsv, out.ownerUserUuid),
                csvToSet(out.writeUserUuidsCsv)
        );
        out.source = normalizeSource(in.source);
        if (out.source.isBlank()) out.source = "native";
        out.sourceCalendarId = safe(in.sourceCalendarId).trim();
        out.createdAt = normalizeIsoDateTime(in.createdAt, false, true);
        out.updatedAt = normalizeIsoDateTime(in.updatedAt, false, true);
        if (out.createdAt.isBlank()) out.createdAt = app_clock.now().toString();
        if (out.updatedAt.isBlank()) out.updatedAt = out.createdAt;

        return out;
    }

    private static EventRec normalizeEvent(EventRec in, String fallbackCalendarUuid) {
        EventRec out = new EventRec();
        if (in == null) return out;

        out.uuid = safe(in.uuid).trim();
        out.calendarUuid = safe(in.calendarUuid).trim();
        if (out.calendarUuid.isBlank()) out.calendarUuid = safe(fallbackCalendarUuid).trim();
        out.uid = safe(in.uid).trim();
        if (out.uid.isBlank()) out.uid = generatedUid();
        out.sequence = Math.max(0, in.sequence);
        out.etag = safe(in.etag).trim();
        out.summary = clampLen(in.summary, 500);
        out.description = clampLen(in.description, 32000);
        out.location = clampLen(in.location, 1000);
        out.allDay = in.allDay;
        out.startAt = normalizeIsoDateTime(in.startAt, out.allDay, true);
        out.endAt = normalizeIsoDateTime(in.endAt, out.allDay, false);
        if (out.startAt.isBlank()) {
            out.startAt = app_clock.now().toString();
            out.allDay = false;
        }
        if (out.endAt.isBlank()) out.endAt = defaultEndForStart(out.startAt, out.allDay);
        out.status = normalizeStatus(in.status);
        out.organizerUserUuid = safe(in.organizerUserUuid).trim();
        out.attendeesCsv = normalizeEmailCsv(in.attendeesCsv);
        out.source = normalizeSource(in.source);
        if (out.source.isBlank()) out.source = "native";
        out.sourceEventId = safe(in.sourceEventId).trim();
        out.sourceChangeKey = safe(in.sourceChangeKey).trim();
        out.sourceIcalUid = safe(in.sourceIcalUid).trim();
        out.createdAt = normalizeIsoDateTime(in.createdAt, false, true);
        out.updatedAt = normalizeIsoDateTime(in.updatedAt, false, true);
        if (out.createdAt.isBlank()) out.createdAt = app_clock.now().toString();
        if (out.updatedAt.isBlank()) out.updatedAt = out.createdAt;
        out.trashed = in.trashed;

        if (out.etag.isBlank()) {
            out.etag = makeStrongEtag(out.uuid, out.sequence, out.updatedAt, false);
        }

        return out;
    }

    private static String normalizeCalendarName(String raw) {
        String v = safe(raw).trim().replaceAll("\\s+", " ");
        if (v.length() > 200) v = v.substring(0, 200).trim();
        return v;
    }

    private static String normalizeColor(String raw) {
        String v = safe(raw).trim();
        if (v.isBlank()) return "";
        if (!v.startsWith("#")) v = "#" + v;
        if (!v.matches("#[0-9A-Fa-f]{6}")) return "";
        return v.toUpperCase(Locale.ROOT);
    }

    private static String normalizeTimeZone(String raw) {
        String v = safe(raw).trim();
        if (v.isBlank()) return "UTC";
        try {
            java.time.ZoneId.of(v);
            return v;
        } catch (Exception ignored) {
            return "UTC";
        }
    }

    private static String normalizeSource(String raw) {
        String v = safe(raw).trim().toLowerCase(Locale.ROOT);
        if (v.isBlank()) return "";
        if (v.length() > 160) v = v.substring(0, 160);
        return v;
    }

    private static String normalizeStatus(String raw) {
        String v = safe(raw).trim().toLowerCase(Locale.ROOT);
        if ("cancelled".equals(v)) return "cancelled";
        if ("tentative".equals(v)) return "tentative";
        if ("confirmed".equals(v)) return "confirmed";
        if ("free".equals(v)) return "free";
        if ("busy".equals(v)) return "busy";
        return "confirmed";
    }

    private static String normalizeIsoDateTime(String raw, boolean allDay, boolean startValue) {
        String v = safe(raw).trim();
        if (v.isBlank()) return "";

        if (looksIsoDate(v)) {
            LocalDate d = parseLocalDate(v);
            if (d == null) return "";
            return (startValue ? d : d.plusDays(1)).atStartOfDay().toInstant(ZoneOffset.UTC).toString();
        }

        Instant instant = parseInstantLoose(v);
        if (instant == null) return "";

        if (allDay) {
            LocalDate d = instant.atZone(ZoneOffset.UTC).toLocalDate();
            if (!startValue) d = d.plusDays(1);
            return d.atStartOfDay().toInstant(ZoneOffset.UTC).toString();
        }

        return instant.toString();
    }

    private static String defaultEndForStart(String startAt, boolean allDay) {
        Instant start = parseInstantLoose(startAt);
        if (start == null) start = app_clock.now();
        if (allDay) {
            LocalDate d = start.atZone(ZoneOffset.UTC).toLocalDate().plusDays(1);
            return d.atStartOfDay().toInstant(ZoneOffset.UTC).toString();
        }
        return start.plusSeconds(3600L).toString();
    }

    private static Instant parseInstantLoose(String raw) {
        String v = safe(raw).trim();
        if (v.isBlank()) return null;

        try { return Instant.parse(v); } catch (Exception ignored) {}
        try { return java.time.OffsetDateTime.parse(v).toInstant(); } catch (Exception ignored) {}
        try { return LocalDateTime.parse(v).toInstant(ZoneOffset.UTC); } catch (Exception ignored) {}

        LocalDate d = parseLocalDate(v);
        if (d != null) return d.atStartOfDay().toInstant(ZoneOffset.UTC);

        try {
            DateTimeFormatter basicUtc = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
            LocalDateTime dt = LocalDateTime.parse(v, basicUtc);
            return dt.toInstant(ZoneOffset.UTC);
        } catch (Exception ignored) {}

        try {
            DateTimeFormatter basicLocal = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
            LocalDateTime dt = LocalDateTime.parse(v, basicLocal);
            return dt.toInstant(ZoneOffset.UTC);
        } catch (Exception ignored) {}

        return null;
    }

    private static LocalDate parseLocalDate(String raw) {
        String v = safe(raw).trim();
        if (v.isBlank()) return null;
        try {
            if (v.matches("\\d{8}")) {
                return LocalDate.parse(v, DateTimeFormatter.BASIC_ISO_DATE);
            }
            return LocalDate.parse(v);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static boolean looksIsoDate(String raw) {
        String v = safe(raw).trim();
        return v.matches("\\d{4}-\\d{2}-\\d{2}") || v.matches("\\d{8}");
    }

    private static LocalDate toLocalDate(String raw) {
        Instant instant = parseInstantLoose(raw);
        if (instant != null) return instant.atZone(ZoneOffset.UTC).toLocalDate();
        return parseLocalDate(raw);
    }

    private static String makeStrongEtag(String uuid, int sequence, String updatedAt, boolean created) {
        String seed = safe(uuid).trim() + "|" + Math.max(0, sequence) + "|" + safe(updatedAt).trim() + "|" + (created ? "c" : "u");
        String hex = Integer.toHexString(seed.hashCode()).replace('-', '0');
        return "\"" + hex + "-" + Math.max(0, sequence) + "\"";
    }

    private static String generatedUid() {
        return UUID.randomUUID() + "@controversies.local";
    }

    private static String formatUtcDateTime(Instant instant) {
        Instant ts = instant == null ? null : instant;
        if (ts == null) return "";
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
        return fmt.format(ts);
    }

    private static IcsTemporal parseIcsTemporal(String nameAndParams, String value) {
        String meta = safe(nameAndParams).toUpperCase(Locale.ROOT);
        String val = safe(value).trim();
        if (val.isBlank()) return new IcsTemporal("", false);

        boolean valueDate = meta.contains(";VALUE=DATE");
        if (valueDate) {
            LocalDate d = parseLocalDate(val);
            if (d == null) return new IcsTemporal("", true);
            String iso = d.atStartOfDay().toInstant(ZoneOffset.UTC).toString();
            return new IcsTemporal(iso, true);
        }

        // Basic iCalendar UTC or floating date-time.
        Instant instant = parseInstantLoose(val);
        if (instant != null) return new IcsTemporal(instant.toString(), false);

        return new IcsTemporal("", false);
    }

    private static void appendIcsLine(StringBuilder sb, String line) {
        if (sb == null) return;
        sb.append(safe(line)).append("\r\n");
    }

    private static String escapeIcsText(String raw) {
        return safe(raw)
                .replace("\\", "\\\\")
                .replace(";", "\\;")
                .replace(",", "\\,")
                .replace("\r\n", "\\n")
                .replace("\n", "\\n")
                .replace("\r", "\\n");
    }

    private static String unescapeIcsText(String raw) {
        String v = safe(raw);
        v = v.replace("\\n", "\n").replace("\\N", "\n");
        v = v.replace("\\,", ",");
        v = v.replace("\\;", ";");
        v = v.replace("\\\\", "\\");
        return v;
    }

    private static String extractMailto(String raw) {
        String v = safe(raw).trim();
        if (v.isBlank()) return "";
        int idx = v.toLowerCase(Locale.ROOT).indexOf("mailto:");
        if (idx >= 0) v = v.substring(idx + "mailto:".length());
        int semi = v.indexOf(';');
        if (semi >= 0) v = v.substring(0, semi);
        return v.trim();
    }

    private static String normalizeUsersCsv(String csv, String ownerUserUuid) {
        LinkedHashSet<String> out = csvToSet(csv);
        String owner = safe(ownerUserUuid).trim();
        if (!owner.isBlank()) out.remove(owner);
        return String.join(",", out);
    }

    private static String removeUsersCsv(String csv, LinkedHashSet<String> remove) {
        LinkedHashSet<String> set = csvToSet(csv);
        if (remove != null) set.removeAll(remove);
        return String.join(",", set);
    }

    private static LinkedHashSet<String> csvToSet(String csv) {
        LinkedHashSet<String> out = new LinkedHashSet<String>();
        String raw = safe(csv).trim();
        if (raw.isBlank()) return out;

        String[] parts = raw.split("[,;\\s]+");
        for (String part : parts) {
            String token = safe(part).trim();
            if (token.isBlank()) continue;
            if (token.length() > 120) token = token.substring(0, 120);
            out.add(token);
        }
        return out;
    }

    private static boolean csvContains(String csv, String token) {
        String want = safe(token).trim();
        if (want.isBlank()) return false;
        return csvToSet(csv).contains(want);
    }

    private static String normalizeEmailCsv(String csv) {
        LinkedHashSet<String> out = new LinkedHashSet<String>();
        for (String token : csvToSet(csv)) {
            String em = safe(token).trim().toLowerCase(Locale.ROOT);
            if (em.isBlank()) continue;
            out.add(em);
        }
        return String.join(",", out);
    }

    private static String clampLen(String raw, int maxLen) {
        String v = safe(raw);
        if (maxLen > 0 && v.length() > maxLen) return v.substring(0, maxLen);
        return v;
    }

    private static boolean hasText(String value) {
        return !safe(value).trim().isBlank();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String safeSegmentLabel(String raw, String fallback) {
        String v = safe(raw);
        v = v.replaceAll("[\\u0000-\\u001F\\u007F]", " ");
        v = v.replace('\\', ' ');
        v = v.replace('/', ' ');
        v = v.replace(':', ' ');
        v = v.replace('*', ' ');
        v = v.replace('?', ' ');
        v = v.replace('"', ' ');
        v = v.replace('<', ' ');
        v = v.replace('>', ' ');
        v = v.replace('|', ' ');
        v = v.replaceAll("\\s+", " ").trim();
        while (v.endsWith(".")) v = v.substring(0, v.length() - 1).trim();
        if (v.isBlank()) v = safe(fallback).trim();
        if (v.isBlank()) v = "item";
        if (v.length() > 140) v = v.substring(0, 140).trim();
        return v;
    }

    private static boolean looksUuid(String raw) {
        return safe(raw).trim().matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    }

    private static String safeFileToken(String raw) {
        String v = safe(raw).trim();
        if (v.isBlank()) return "";
        return v.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
