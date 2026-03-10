package net.familylawandprobate.controversies;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * leads_crm
 *
 * Tenant-scoped intake + lead pipeline storage.
 */
public final class leads_crm {

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final ConcurrentHashMap<String, ReentrantReadWriteLock> LOCKS = new ConcurrentHashMap<String, ReentrantReadWriteLock>();

    public static final String STATUS_NEW = "new";
    public static final String STATUS_QUALIFIED = "qualified";
    public static final String STATUS_CONSULT_SCHEDULED = "consult_scheduled";
    public static final String STATUS_RETAINED = "retained";
    public static final String STATUS_CLOSED_LOST = "closed_lost";

    public static leads_crm defaultStore() {
        return new leads_crm();
    }

    public static final class LeadInput {
        public String status = STATUS_NEW;
        public String source = "";
        public String intakeChannel = "";
        public String referredBy = "";
        public String firstName = "";
        public String lastName = "";
        public String displayName = "";
        public String company = "";
        public String email = "";
        public String phone = "";
        public String notes = "";
        public String tagsCsv = "";
        public String assignedUserUuid = "";
        public String matterUuid = "";
        public boolean archived = false;
    }

    public static final class LeadRec {
        public final String leadUuid;
        public final String status;
        public final String source;
        public final String intakeChannel;
        public final String referredBy;
        public final String firstName;
        public final String lastName;
        public final String displayName;
        public final String company;
        public final String email;
        public final String phone;
        public final String notes;
        public final String tagsCsv;
        public final String assignedUserUuid;
        public final String matterUuid;
        public final boolean archived;
        public final String convertedAt;
        public final String createdAt;
        public final String updatedAt;

        public LeadRec(String leadUuid,
                       String status,
                       String source,
                       String intakeChannel,
                       String referredBy,
                       String firstName,
                       String lastName,
                       String displayName,
                       String company,
                       String email,
                       String phone,
                       String notes,
                       String tagsCsv,
                       String assignedUserUuid,
                       String matterUuid,
                       boolean archived,
                       String convertedAt,
                       String createdAt,
                       String updatedAt) {
            this.leadUuid = safe(leadUuid);
            this.status = normalizeStatus(status);
            this.source = safe(source).trim();
            this.intakeChannel = safe(intakeChannel).trim();
            this.referredBy = safe(referredBy).trim();
            this.firstName = safe(firstName).trim();
            this.lastName = safe(lastName).trim();
            this.displayName = safe(displayName).trim();
            this.company = safe(company).trim();
            this.email = safe(email).trim();
            this.phone = safe(phone).trim();
            this.notes = safe(notes);
            this.tagsCsv = normalizeCsv(tagsCsv);
            this.assignedUserUuid = safe(assignedUserUuid).trim();
            this.matterUuid = safe(matterUuid).trim();
            this.archived = archived;
            this.convertedAt = safe(convertedAt).trim();
            this.createdAt = safe(createdAt).trim();
            this.updatedAt = safe(updatedAt).trim();
        }
    }

    public static final class LeadNoteRec {
        public final String noteUuid;
        public final String leadUuid;
        public final String body;
        public final String authorUserUuid;
        public final String createdAt;

        public LeadNoteRec(String noteUuid, String leadUuid, String body, String authorUserUuid, String createdAt) {
            this.noteUuid = safe(noteUuid);
            this.leadUuid = safe(leadUuid);
            this.body = safe(body);
            this.authorUserUuid = safe(authorUserUuid);
            this.createdAt = safe(createdAt);
        }
    }

    private static final class FileRec {
        public String updated_at = "";
        public ArrayList<StoredLeadRec> leads = new ArrayList<StoredLeadRec>();
        public ArrayList<StoredLeadNoteRec> notes = new ArrayList<StoredLeadNoteRec>();
    }

    private static final class StoredLeadRec {
        public String lead_uuid = "";
        public String status = STATUS_NEW;
        public String source = "";
        public String intake_channel = "";
        public String referred_by = "";
        public String first_name = "";
        public String last_name = "";
        public String display_name = "";
        public String company = "";
        public String email = "";
        public String phone = "";
        public String notes = "";
        public String tags_csv = "";
        public String assigned_user_uuid = "";
        public String matter_uuid = "";
        public boolean archived = false;
        public String converted_at = "";
        public String created_at = "";
        public String updated_at = "";
    }

    private static final class StoredLeadNoteRec {
        public String note_uuid = "";
        public String lead_uuid = "";
        public String body = "";
        public String author_user_uuid = "";
        public String created_at = "";
    }

    public List<LeadRec> listLeads(String tenantUuid, boolean includeArchived) throws Exception {
        String tu = safeToken(tenantUuid);
        if (tu.isBlank()) return List.of();
        ReentrantReadWriteLock lock = lockFor(tu);
        lock.readLock().lock();
        try {
            ensureLocked(tu);
            FileRec file = readLocked(tu);
            ArrayList<LeadRec> out = new ArrayList<LeadRec>();
            for (StoredLeadRec row : file.leads) {
                if (row == null) continue;
                LeadRec rec = toLeadRec(row);
                if (!includeArchived && rec.archived) continue;
                out.add(rec);
            }
            out.sort((a, b) -> compareByIsoThenUuid(a == null ? "" : a.updatedAt, b == null ? "" : b.updatedAt,
                    a == null ? "" : a.leadUuid, b == null ? "" : b.leadUuid));
            return out;
        } finally {
            lock.readLock().unlock();
        }
    }

    public LeadRec getLead(String tenantUuid, String leadUuid) throws Exception {
        String tu = safeToken(tenantUuid);
        String id = safe(leadUuid).trim();
        if (tu.isBlank() || id.isBlank()) return null;
        ReentrantReadWriteLock lock = lockFor(tu);
        lock.readLock().lock();
        try {
            ensureLocked(tu);
            FileRec file = readLocked(tu);
            StoredLeadRec row = findLead(file, id);
            return row == null ? null : toLeadRec(row);
        } finally {
            lock.readLock().unlock();
        }
    }

    public LeadRec createLead(String tenantUuid, LeadInput in, String actorUserUuid) throws Exception {
        String tu = safeToken(tenantUuid);
        if (tu.isBlank()) throw new IllegalArgumentException("tenantUuid required");
        LeadInput input = in == null ? new LeadInput() : in;

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensureLocked(tu);
            FileRec file = readLocked(tu);
            if (file.leads == null) file.leads = new ArrayList<StoredLeadRec>();
            if (file.notes == null) file.notes = new ArrayList<StoredLeadNoteRec>();

            String now = Instant.now().toString();
            StoredLeadRec row = new StoredLeadRec();
            row.lead_uuid = "lead_" + UUID.randomUUID().toString().replace("-", "");
            row.status = normalizeStatus(input.status);
            row.source = safe(input.source).trim();
            row.intake_channel = safe(input.intakeChannel).trim();
            row.referred_by = safe(input.referredBy).trim();
            row.first_name = safe(input.firstName).trim();
            row.last_name = safe(input.lastName).trim();
            row.display_name = resolvedDisplayName(input);
            row.company = safe(input.company).trim();
            row.email = safe(input.email).trim();
            row.phone = safe(input.phone).trim();
            row.notes = safe(input.notes);
            row.tags_csv = normalizeCsv(input.tagsCsv);
            row.assigned_user_uuid = safe(input.assignedUserUuid).trim();
            row.matter_uuid = safe(input.matterUuid).trim();
            row.archived = input.archived;
            row.converted_at = row.matter_uuid.isBlank() ? "" : now;
            row.created_at = now;
            row.updated_at = now;

            file.leads.add(row);
            file.updated_at = now;
            writeLocked(tu, file);

            if (!safe(actorUserUuid).trim().isBlank()) {
                addNote(tu, row.lead_uuid, "Lead created.", actorUserUuid);
            }
            return toLeadRec(row);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public LeadRec updateLead(String tenantUuid, String leadUuid, LeadInput in, String actorUserUuid) throws Exception {
        String tu = safeToken(tenantUuid);
        String id = safe(leadUuid).trim();
        if (tu.isBlank() || id.isBlank()) throw new IllegalArgumentException("tenantUuid and leadUuid required");
        LeadInput input = in == null ? new LeadInput() : in;

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensureLocked(tu);
            FileRec file = readLocked(tu);
            StoredLeadRec row = findLead(file, id);
            if (row == null) throw new IllegalArgumentException("Lead not found.");

            String beforeStatus = normalizeStatus(row.status);
            row.status = normalizeStatus(input.status);
            row.source = safe(input.source).trim();
            row.intake_channel = safe(input.intakeChannel).trim();
            row.referred_by = safe(input.referredBy).trim();
            row.first_name = safe(input.firstName).trim();
            row.last_name = safe(input.lastName).trim();
            row.display_name = resolvedDisplayName(input);
            row.company = safe(input.company).trim();
            row.email = safe(input.email).trim();
            row.phone = safe(input.phone).trim();
            row.notes = safe(input.notes);
            row.tags_csv = normalizeCsv(input.tagsCsv);
            row.assigned_user_uuid = safe(input.assignedUserUuid).trim();
            row.matter_uuid = safe(input.matterUuid).trim();
            row.archived = input.archived;
            if (!row.matter_uuid.isBlank() && safe(row.converted_at).trim().isBlank()) {
                row.converted_at = Instant.now().toString();
            }
            row.updated_at = Instant.now().toString();
            file.updated_at = row.updated_at;
            writeLocked(tu, file);

            if (!safe(actorUserUuid).trim().isBlank() && !beforeStatus.equals(row.status)) {
                addNote(tu, row.lead_uuid, "Status changed: " + beforeStatus + " -> " + row.status, actorUserUuid);
            }
            return toLeadRec(row);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean setArchived(String tenantUuid, String leadUuid, boolean archived) throws Exception {
        String tu = safeToken(tenantUuid);
        String id = safe(leadUuid).trim();
        if (tu.isBlank() || id.isBlank()) return false;
        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensureLocked(tu);
            FileRec file = readLocked(tu);
            StoredLeadRec row = findLead(file, id);
            if (row == null) return false;
            if (row.archived == archived) return false;
            row.archived = archived;
            row.updated_at = Instant.now().toString();
            file.updated_at = row.updated_at;
            writeLocked(tu, file);
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public LeadRec convertToMatter(String tenantUuid, String leadUuid, String matterUuid, String actorUserUuid) throws Exception {
        String tu = safeToken(tenantUuid);
        String id = safe(leadUuid).trim();
        String mu = safe(matterUuid).trim();
        if (tu.isBlank() || id.isBlank() || mu.isBlank()) throw new IllegalArgumentException("tenantUuid, leadUuid, and matterUuid are required.");
        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensureLocked(tu);
            FileRec file = readLocked(tu);
            StoredLeadRec row = findLead(file, id);
            if (row == null) throw new IllegalArgumentException("Lead not found.");
            row.matter_uuid = mu;
            row.status = STATUS_RETAINED;
            if (safe(row.converted_at).trim().isBlank()) row.converted_at = Instant.now().toString();
            row.updated_at = Instant.now().toString();
            file.updated_at = row.updated_at;
            writeLocked(tu, file);
            if (!safe(actorUserUuid).trim().isBlank()) {
                addNote(tu, row.lead_uuid, "Converted to matter " + mu + ".", actorUserUuid);
            }
            return toLeadRec(row);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public LeadNoteRec addNote(String tenantUuid, String leadUuid, String body, String authorUserUuid) throws Exception {
        String tu = safeToken(tenantUuid);
        String lu = safe(leadUuid).trim();
        if (tu.isBlank() || lu.isBlank()) throw new IllegalArgumentException("tenantUuid and leadUuid are required.");
        String text = safe(body).trim();
        if (text.isBlank()) throw new IllegalArgumentException("body is required.");

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensureLocked(tu);
            FileRec file = readLocked(tu);
            if (findLead(file, lu) == null) throw new IllegalArgumentException("Lead not found.");
            if (file.notes == null) file.notes = new ArrayList<StoredLeadNoteRec>();

            StoredLeadNoteRec row = new StoredLeadNoteRec();
            row.note_uuid = "lead_note_" + UUID.randomUUID().toString().replace("-", "");
            row.lead_uuid = lu;
            row.body = safe(body);
            row.author_user_uuid = safe(authorUserUuid).trim();
            row.created_at = Instant.now().toString();
            file.notes.add(row);
            file.updated_at = row.created_at;
            writeLocked(tu, file);
            return toLeadNoteRec(row);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<LeadNoteRec> listNotes(String tenantUuid, String leadUuid) throws Exception {
        String tu = safeToken(tenantUuid);
        String lu = safe(leadUuid).trim();
        if (tu.isBlank() || lu.isBlank()) return List.of();
        ReentrantReadWriteLock lock = lockFor(tu);
        lock.readLock().lock();
        try {
            ensureLocked(tu);
            FileRec file = readLocked(tu);
            ArrayList<LeadNoteRec> out = new ArrayList<LeadNoteRec>();
            for (StoredLeadNoteRec row : file.notes) {
                if (row == null) continue;
                if (!lu.equals(safe(row.lead_uuid).trim())) continue;
                out.add(toLeadNoteRec(row));
            }
            out.sort((a, b) -> compareByIsoThenUuid(a == null ? "" : a.createdAt, b == null ? "" : b.createdAt,
                    a == null ? "" : a.noteUuid, b == null ? "" : b.noteUuid));
            return out;
        } finally {
            lock.readLock().unlock();
        }
    }

    public LinkedHashMap<String, Long> statusCounts(String tenantUuid, boolean includeArchived) throws Exception {
        LinkedHashMap<String, Long> out = new LinkedHashMap<String, Long>();
        out.put(STATUS_NEW, 0L);
        out.put(STATUS_QUALIFIED, 0L);
        out.put(STATUS_CONSULT_SCHEDULED, 0L);
        out.put(STATUS_RETAINED, 0L);
        out.put(STATUS_CLOSED_LOST, 0L);

        for (LeadRec r : listLeads(tenantUuid, includeArchived)) {
            String key = normalizeStatus(r == null ? "" : r.status);
            out.put(key, out.getOrDefault(key, 0L) + 1L);
        }
        return out;
    }

    private static String resolvedDisplayName(LeadInput in) {
        if (in == null) return "";
        String explicit = safe(in.displayName).trim();
        if (!explicit.isBlank()) return explicit;
        String full = (safe(in.firstName).trim() + " " + safe(in.lastName).trim()).trim();
        if (!full.isBlank()) return full;
        if (!safe(in.company).trim().isBlank()) return safe(in.company).trim();
        if (!safe(in.email).trim().isBlank()) return safe(in.email).trim();
        return "";
    }

    private static StoredLeadRec findLead(FileRec file, String leadUuid) {
        if (file == null || file.leads == null) return null;
        String target = safe(leadUuid).trim();
        if (target.isBlank()) return null;
        for (StoredLeadRec row : file.leads) {
            if (row == null) continue;
            if (target.equals(safe(row.lead_uuid).trim())) return row;
        }
        return null;
    }

    private static LeadRec toLeadRec(StoredLeadRec row) {
        if (row == null) return null;
        return new LeadRec(
                safe(row.lead_uuid).trim(),
                normalizeStatus(row.status),
                safe(row.source),
                safe(row.intake_channel),
                safe(row.referred_by),
                safe(row.first_name),
                safe(row.last_name),
                safe(row.display_name),
                safe(row.company),
                safe(row.email),
                safe(row.phone),
                safe(row.notes),
                safe(row.tags_csv),
                safe(row.assigned_user_uuid),
                safe(row.matter_uuid),
                row.archived,
                safe(row.converted_at),
                safe(row.created_at),
                safe(row.updated_at)
        );
    }

    private static LeadNoteRec toLeadNoteRec(StoredLeadNoteRec row) {
        if (row == null) return null;
        return new LeadNoteRec(
                safe(row.note_uuid),
                safe(row.lead_uuid),
                safe(row.body),
                safe(row.author_user_uuid),
                safe(row.created_at)
        );
    }

    private static String normalizeStatus(String raw) {
        String v = safe(raw).trim().toLowerCase(Locale.ROOT);
        if (STATUS_QUALIFIED.equals(v)) return STATUS_QUALIFIED;
        if (STATUS_CONSULT_SCHEDULED.equals(v) || "consult".equals(v) || "consultation".equals(v)) return STATUS_CONSULT_SCHEDULED;
        if (STATUS_RETAINED.equals(v) || "converted".equals(v)) return STATUS_RETAINED;
        if (STATUS_CLOSED_LOST.equals(v) || "lost".equals(v)) return STATUS_CLOSED_LOST;
        return STATUS_NEW;
    }

    private static String normalizeCsv(String raw) {
        LinkedHashSet<String> out = new LinkedHashSet<String>();
        String text = safe(raw).trim();
        if (text.isBlank()) return "";
        for (String part : text.split(",")) {
            String token = safe(part).trim();
            if (token.isBlank()) continue;
            out.add(token);
        }
        return String.join(",", out);
    }

    private static String safeToken(String tenantUuid) {
        String tu = safe(tenantUuid).trim();
        if (tu.isBlank()) return "";
        return tu.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static ReentrantReadWriteLock lockFor(String tenantToken) {
        return LOCKS.computeIfAbsent(safeToken(tenantToken), k -> new ReentrantReadWriteLock());
    }

    private static Path storePath(String tenantToken) {
        return Paths.get("data", "tenants", safeToken(tenantToken), "crm", "leads.json").toAbsolutePath();
    }

    private static void ensureLocked(String tenantToken) throws Exception {
        Path file = storePath(tenantToken);
        Files.createDirectories(file.getParent());
        if (Files.exists(file)) return;
        FileRec empty = new FileRec();
        empty.updated_at = Instant.now().toString();
        writeJsonAtomic(file, empty);
    }

    private static FileRec readLocked(String tenantToken) throws Exception {
        Path file = storePath(tenantToken);
        if (!Files.exists(file)) return new FileRec();
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length == 0) return new FileRec();
        try {
            FileRec rec = JSON.readValue(bytes, FileRec.class);
            if (rec == null) rec = new FileRec();
            if (rec.leads == null) rec.leads = new ArrayList<StoredLeadRec>();
            if (rec.notes == null) rec.notes = new ArrayList<StoredLeadNoteRec>();
            return rec;
        } catch (Exception ex) {
            return new FileRec();
        }
    }

    private static void writeLocked(String tenantToken, FileRec file) throws Exception {
        if (file == null) file = new FileRec();
        if (file.leads == null) file.leads = new ArrayList<StoredLeadRec>();
        if (file.notes == null) file.notes = new ArrayList<StoredLeadNoteRec>();
        if (safe(file.updated_at).trim().isBlank()) file.updated_at = Instant.now().toString();
        writeJsonAtomic(storePath(tenantToken), file);
    }

    private static void writeJsonAtomic(Path file, FileRec rec) throws Exception {
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
        byte[] json = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(rec == null ? new FileRec() : rec);
        Files.write(tmp, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception ex) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static int compareByIsoThenUuid(String isoA, String isoB, String uuidA, String uuidB) {
        String a = safe(isoA).trim();
        String b = safe(isoB).trim();
        int cmp = b.compareTo(a);
        if (cmp != 0) return cmp;
        return safe(uuidB).trim().compareTo(safe(uuidA).trim());
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
