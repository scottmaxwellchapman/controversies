package net.familylawandprobate.controversies;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * esign_requests
 *
 * Tenant-scoped signature request lifecycle tracking.
 */
public final class esign_requests {

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final ConcurrentHashMap<String, ReentrantReadWriteLock> LOCKS = new ConcurrentHashMap<String, ReentrantReadWriteLock>();

    public static final String STATUS_DRAFT = "draft";
    public static final String STATUS_SENT = "sent";
    public static final String STATUS_DELIVERED = "delivered";
    public static final String STATUS_VIEWED = "viewed";
    public static final String STATUS_SIGNED = "signed";
    public static final String STATUS_DECLINED = "declined";
    public static final String STATUS_EXPIRED = "expired";
    public static final String STATUS_CANCELLED = "cancelled";
    public static final String STATUS_FAILED = "failed";

    public static esign_requests defaultStore() {
        return new esign_requests();
    }

    public static final class CreateInput {
        public String providerKey = "manual_notice";
        public String providerRequestId = "";
        public String matterUuid = "";
        public String documentUuid = "";
        public String partUuid = "";
        public String versionUuid = "";
        public String subject = "";
        public String toCsv = "";
        public String ccCsv = "";
        public String bccCsv = "";
        public String signatureLink = "";
        public String deliveryMode = "";
        public String requestedByUserUuid = "";
        public String status = STATUS_SENT;
    }

    public static final class SignatureRequestRec {
        public final String requestUuid;
        public final String providerKey;
        public final String providerRequestId;
        public final String matterUuid;
        public final String documentUuid;
        public final String partUuid;
        public final String versionUuid;
        public final String subject;
        public final String toCsv;
        public final String ccCsv;
        public final String bccCsv;
        public final String signatureLink;
        public final String deliveryMode;
        public final String status;
        public final String requestedByUserUuid;
        public final String createdAt;
        public final String updatedAt;
        public final String sentAt;
        public final String completedAt;
        public final String lastEventAt;
        public final String lastEventType;
        public final String lastEventNote;

        public SignatureRequestRec(String requestUuid,
                                   String providerKey,
                                   String providerRequestId,
                                   String matterUuid,
                                   String documentUuid,
                                   String partUuid,
                                   String versionUuid,
                                   String subject,
                                   String toCsv,
                                   String ccCsv,
                                   String bccCsv,
                                   String signatureLink,
                                   String deliveryMode,
                                   String status,
                                   String requestedByUserUuid,
                                   String createdAt,
                                   String updatedAt,
                                   String sentAt,
                                   String completedAt,
                                   String lastEventAt,
                                   String lastEventType,
                                   String lastEventNote) {
            this.requestUuid = safe(requestUuid).trim();
            this.providerKey = normalizeProvider(providerKey);
            this.providerRequestId = safe(providerRequestId).trim();
            this.matterUuid = safe(matterUuid).trim();
            this.documentUuid = safe(documentUuid).trim();
            this.partUuid = safe(partUuid).trim();
            this.versionUuid = safe(versionUuid).trim();
            this.subject = safe(subject).trim();
            this.toCsv = normalizeCsv(toCsv);
            this.ccCsv = normalizeCsv(ccCsv);
            this.bccCsv = normalizeCsv(bccCsv);
            this.signatureLink = safe(signatureLink).trim();
            this.deliveryMode = safe(deliveryMode).trim();
            this.status = normalizeStatus(status);
            this.requestedByUserUuid = safe(requestedByUserUuid).trim();
            this.createdAt = safe(createdAt).trim();
            this.updatedAt = safe(updatedAt).trim();
            this.sentAt = safe(sentAt).trim();
            this.completedAt = safe(completedAt).trim();
            this.lastEventAt = safe(lastEventAt).trim();
            this.lastEventType = safe(lastEventType).trim();
            this.lastEventNote = safe(lastEventNote);
        }
    }

    public static final class SignatureEventRec {
        public final String eventUuid;
        public final String requestUuid;
        public final String eventType;
        public final String status;
        public final String note;
        public final String actorUserUuid;
        public final String providerRequestId;
        public final String createdAt;

        public SignatureEventRec(String eventUuid,
                                 String requestUuid,
                                 String eventType,
                                 String status,
                                 String note,
                                 String actorUserUuid,
                                 String providerRequestId,
                                 String createdAt) {
            this.eventUuid = safe(eventUuid).trim();
            this.requestUuid = safe(requestUuid).trim();
            this.eventType = safe(eventType).trim();
            this.status = normalizeStatus(status);
            this.note = safe(note);
            this.actorUserUuid = safe(actorUserUuid).trim();
            this.providerRequestId = safe(providerRequestId).trim();
            this.createdAt = safe(createdAt).trim();
        }
    }

    private static final class FileRec {
        public String updated_at = "";
        public ArrayList<StoredRequestRec> requests = new ArrayList<StoredRequestRec>();
        public ArrayList<StoredEventRec> events = new ArrayList<StoredEventRec>();
    }

    private static final class StoredRequestRec {
        public String request_uuid = "";
        public String provider_key = "manual_notice";
        public String provider_request_id = "";
        public String matter_uuid = "";
        public String document_uuid = "";
        public String part_uuid = "";
        public String version_uuid = "";
        public String subject = "";
        public String to_csv = "";
        public String cc_csv = "";
        public String bcc_csv = "";
        public String signature_link = "";
        public String delivery_mode = "";
        public String status = STATUS_SENT;
        public String requested_by_user_uuid = "";
        public String created_at = "";
        public String updated_at = "";
        public String sent_at = "";
        public String completed_at = "";
        public String last_event_at = "";
        public String last_event_type = "";
        public String last_event_note = "";
    }

    private static final class StoredEventRec {
        public String event_uuid = "";
        public String request_uuid = "";
        public String event_type = "";
        public String status = STATUS_SENT;
        public String note = "";
        public String actor_user_uuid = "";
        public String provider_request_id = "";
        public String created_at = "";
    }

    public SignatureRequestRec createRequest(String tenantUuid, CreateInput in) throws Exception {
        String tu = safeToken(tenantUuid);
        if (tu.isBlank()) throw new IllegalArgumentException("tenantUuid required");
        CreateInput input = in == null ? new CreateInput() : in;

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensureLocked(tu);
            FileRec file = readLocked(tu);
            if (file.requests == null) file.requests = new ArrayList<StoredRequestRec>();
            if (file.events == null) file.events = new ArrayList<StoredEventRec>();

            String now = Instant.now().toString();
            StoredRequestRec row = new StoredRequestRec();
            row.request_uuid = "sig_" + UUID.randomUUID().toString().replace("-", "");
            row.provider_key = normalizeProvider(input.providerKey);
            row.provider_request_id = safe(input.providerRequestId).trim();
            row.matter_uuid = safe(input.matterUuid).trim();
            row.document_uuid = safe(input.documentUuid).trim();
            row.part_uuid = safe(input.partUuid).trim();
            row.version_uuid = safe(input.versionUuid).trim();
            row.subject = safe(input.subject).trim();
            row.to_csv = normalizeCsv(input.toCsv);
            row.cc_csv = normalizeCsv(input.ccCsv);
            row.bcc_csv = normalizeCsv(input.bccCsv);
            row.signature_link = safe(input.signatureLink).trim();
            row.delivery_mode = safe(input.deliveryMode).trim();
            row.status = normalizeStatus(input.status);
            row.requested_by_user_uuid = safe(input.requestedByUserUuid).trim();
            row.created_at = now;
            row.updated_at = now;
            row.sent_at = isSentState(row.status) ? now : "";
            row.completed_at = isTerminalStatus(row.status) ? now : "";
            row.last_event_at = now;
            row.last_event_type = "created";
            row.last_event_note = "Signature request created.";

            file.requests.add(row);
            file.updated_at = now;
            appendEvent(file, row.request_uuid, "created", row.status, row.last_event_note, row.requested_by_user_uuid, row.provider_request_id, now);
            writeLocked(tu, file);
            return toRequest(row);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public SignatureRequestRec getRequest(String tenantUuid, String requestUuid) throws Exception {
        String tu = safeToken(tenantUuid);
        String id = safe(requestUuid).trim();
        if (tu.isBlank() || id.isBlank()) return null;

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.readLock().lock();
        try {
            ensureLocked(tu);
            FileRec file = readLocked(tu);
            StoredRequestRec row = findRequest(file, id);
            return row == null ? null : toRequest(row);
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<SignatureRequestRec> listRequests(String tenantUuid) throws Exception {
        String tu = safeToken(tenantUuid);
        if (tu.isBlank()) return List.of();
        ReentrantReadWriteLock lock = lockFor(tu);
        lock.readLock().lock();
        try {
            ensureLocked(tu);
            FileRec file = readLocked(tu);
            ArrayList<SignatureRequestRec> out = new ArrayList<SignatureRequestRec>();
            for (StoredRequestRec row : file.requests) {
                if (row == null) continue;
                out.add(toRequest(row));
            }
            out.sort((a, b) -> compareByIsoThenUuid(a == null ? "" : a.updatedAt, b == null ? "" : b.updatedAt,
                    a == null ? "" : a.requestUuid, b == null ? "" : b.requestUuid));
            return out;
        } finally {
            lock.readLock().unlock();
        }
    }

    public SignatureRequestRec updateStatus(String tenantUuid,
                                            String requestUuid,
                                            String status,
                                            String eventType,
                                            String note,
                                            String actorUserUuid,
                                            String providerRequestId) throws Exception {
        String tu = safeToken(tenantUuid);
        String id = safe(requestUuid).trim();
        if (tu.isBlank() || id.isBlank()) throw new IllegalArgumentException("tenantUuid and requestUuid are required.");

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensureLocked(tu);
            FileRec file = readLocked(tu);
            StoredRequestRec row = findRequest(file, id);
            if (row == null) throw new IllegalArgumentException("Signature request not found.");

            String now = Instant.now().toString();
            String nextStatus = normalizeStatus(status);
            row.status = nextStatus;
            if (!safe(providerRequestId).trim().isBlank()) row.provider_request_id = safe(providerRequestId).trim();
            if (isSentState(nextStatus) && safe(row.sent_at).trim().isBlank()) row.sent_at = now;
            if (isTerminalStatus(nextStatus) && safe(row.completed_at).trim().isBlank()) row.completed_at = now;
            row.last_event_at = now;
            row.last_event_type = safe(eventType).trim().isBlank() ? "status_update" : safe(eventType).trim();
            row.last_event_note = safe(note);
            row.updated_at = now;
            file.updated_at = now;
            appendEvent(file, row.request_uuid, row.last_event_type, nextStatus, row.last_event_note, actorUserUuid, row.provider_request_id, now);
            writeLocked(tu, file);
            return toRequest(row);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<SignatureEventRec> listEvents(String tenantUuid, String requestUuid) throws Exception {
        String tu = safeToken(tenantUuid);
        String id = safe(requestUuid).trim();
        if (tu.isBlank() || id.isBlank()) return List.of();
        ReentrantReadWriteLock lock = lockFor(tu);
        lock.readLock().lock();
        try {
            ensureLocked(tu);
            FileRec file = readLocked(tu);
            ArrayList<SignatureEventRec> out = new ArrayList<SignatureEventRec>();
            for (StoredEventRec row : file.events) {
                if (row == null) continue;
                if (!id.equals(safe(row.request_uuid).trim())) continue;
                out.add(toEvent(row));
            }
            out.sort((a, b) -> compareByIsoThenUuid(a == null ? "" : a.createdAt, b == null ? "" : b.createdAt,
                    a == null ? "" : a.eventUuid, b == null ? "" : b.eventUuid));
            return out;
        } finally {
            lock.readLock().unlock();
        }
    }

    private static void appendEvent(FileRec file,
                                    String requestUuid,
                                    String eventType,
                                    String status,
                                    String note,
                                    String actorUserUuid,
                                    String providerRequestId,
                                    String createdAt) {
        if (file == null) return;
        if (file.events == null) file.events = new ArrayList<StoredEventRec>();
        StoredEventRec row = new StoredEventRec();
        row.event_uuid = "sig_evt_" + UUID.randomUUID().toString().replace("-", "");
        row.request_uuid = safe(requestUuid).trim();
        row.event_type = safe(eventType).trim();
        row.status = normalizeStatus(status);
        row.note = safe(note);
        row.actor_user_uuid = safe(actorUserUuid).trim();
        row.provider_request_id = safe(providerRequestId).trim();
        row.created_at = safe(createdAt).trim();
        file.events.add(row);
        if (file.events.size() > 2000) {
            file.events = new ArrayList<StoredEventRec>(file.events.subList(file.events.size() - 2000, file.events.size()));
        }
    }

    private static StoredRequestRec findRequest(FileRec file, String requestUuid) {
        if (file == null || file.requests == null) return null;
        String id = safe(requestUuid).trim();
        if (id.isBlank()) return null;
        for (StoredRequestRec row : file.requests) {
            if (row == null) continue;
            if (id.equals(safe(row.request_uuid).trim())) return row;
        }
        return null;
    }

    private static SignatureRequestRec toRequest(StoredRequestRec row) {
        if (row == null) return null;
        return new SignatureRequestRec(
                safe(row.request_uuid),
                safe(row.provider_key),
                safe(row.provider_request_id),
                safe(row.matter_uuid),
                safe(row.document_uuid),
                safe(row.part_uuid),
                safe(row.version_uuid),
                safe(row.subject),
                safe(row.to_csv),
                safe(row.cc_csv),
                safe(row.bcc_csv),
                safe(row.signature_link),
                safe(row.delivery_mode),
                safe(row.status),
                safe(row.requested_by_user_uuid),
                safe(row.created_at),
                safe(row.updated_at),
                safe(row.sent_at),
                safe(row.completed_at),
                safe(row.last_event_at),
                safe(row.last_event_type),
                safe(row.last_event_note)
        );
    }

    private static SignatureEventRec toEvent(StoredEventRec row) {
        if (row == null) return null;
        return new SignatureEventRec(
                safe(row.event_uuid),
                safe(row.request_uuid),
                safe(row.event_type),
                safe(row.status),
                safe(row.note),
                safe(row.actor_user_uuid),
                safe(row.provider_request_id),
                safe(row.created_at)
        );
    }

    private static String normalizeProvider(String raw) {
        String v = safe(raw).trim().toLowerCase(Locale.ROOT);
        if (v.isBlank()) return "manual_notice";
        return v;
    }

    private static String normalizeStatus(String raw) {
        String v = safe(raw).trim().toLowerCase(Locale.ROOT);
        if (STATUS_DRAFT.equals(v)) return STATUS_DRAFT;
        if (STATUS_SENT.equals(v)) return STATUS_SENT;
        if (STATUS_DELIVERED.equals(v)) return STATUS_DELIVERED;
        if (STATUS_VIEWED.equals(v)) return STATUS_VIEWED;
        if (STATUS_SIGNED.equals(v)) return STATUS_SIGNED;
        if (STATUS_DECLINED.equals(v)) return STATUS_DECLINED;
        if (STATUS_EXPIRED.equals(v)) return STATUS_EXPIRED;
        if (STATUS_CANCELLED.equals(v) || "canceled".equals(v)) return STATUS_CANCELLED;
        if (STATUS_FAILED.equals(v)) return STATUS_FAILED;
        return STATUS_SENT;
    }

    private static boolean isSentState(String status) {
        String s = normalizeStatus(status);
        return STATUS_SENT.equals(s)
                || STATUS_DELIVERED.equals(s)
                || STATUS_VIEWED.equals(s)
                || STATUS_SIGNED.equals(s)
                || STATUS_DECLINED.equals(s)
                || STATUS_EXPIRED.equals(s);
    }

    private static boolean isTerminalStatus(String status) {
        String s = normalizeStatus(status);
        return STATUS_SIGNED.equals(s)
                || STATUS_DECLINED.equals(s)
                || STATUS_EXPIRED.equals(s)
                || STATUS_CANCELLED.equals(s)
                || STATUS_FAILED.equals(s);
    }

    private static String normalizeCsv(String raw) {
        String text = safe(raw).trim();
        if (text.isBlank()) return "";
        ArrayList<String> out = new ArrayList<String>();
        for (String part : text.split(",")) {
            String token = safe(part).trim();
            if (token.isBlank()) continue;
            if (!out.contains(token)) out.add(token);
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
        return Paths.get("data", "tenants", safeToken(tenantToken), "esign", "signature_requests.json").toAbsolutePath();
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
            if (rec.requests == null) rec.requests = new ArrayList<StoredRequestRec>();
            if (rec.events == null) rec.events = new ArrayList<StoredEventRec>();
            return rec;
        } catch (Exception ignored) {
            return new FileRec();
        }
    }

    private static void writeLocked(String tenantToken, FileRec file) throws Exception {
        if (file == null) file = new FileRec();
        if (file.requests == null) file.requests = new ArrayList<StoredRequestRec>();
        if (file.events == null) file.events = new ArrayList<StoredEventRec>();
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
