package net.familylawandprobate.controversies;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Omnichannel ticket storage + communication timeline + attachment ledger.
 *
 * Layout:
 *   data/tenants/{tenant}/communications/tickets/tickets.xml
 *   data/tenants/{tenant}/communications/tickets/round_robin_state.xml
 *   data/tenants/{tenant}/communications/tickets/tickets/{ticket}/messages.xml
 *   data/tenants/{tenant}/communications/tickets/tickets/{ticket}/attachments.xml
 *   data/tenants/{tenant}/communications/tickets/tickets/{ticket}/assignments.xml
 */
public final class omnichannel_tickets {

    private static final Logger LOG = Logger.getLogger(omnichannel_tickets.class.getName());

    public static final long MAX_ATTACHMENT_BYTES = 20L * 1024L * 1024L;
    private static final int MAX_SUBJECT_LEN = 300;
    private static final int MAX_BODY_LEN = 20000;
    private static final int MAX_CONTACT_LEN = 320;
    private static final int MAX_REF_LEN = 500;
    private static final int MAX_ASSIGNMENT_LEN = 2000;
    private static final int MAX_REASON_LEN = 800;

    private static final String CHANNEL_FLOWROUTE_SMS = "flowroute_sms";
    private static final String CHANNEL_EMAIL_IMAP_SMTP = "email_imap_smtp";
    private static final String CHANNEL_EMAIL_GRAPH_USER = "email_graph_user";
    private static final String CHANNEL_EMAIL_GRAPH_SHARED = "email_graph_shared";

    private static final String STATUS_OPEN = "open";
    private static final String STATUS_PENDING_CUSTOMER = "pending_customer";
    private static final String STATUS_PENDING_INTERNAL = "pending_internal";
    private static final String STATUS_ON_HOLD = "on_hold";
    private static final String STATUS_RESOLVED = "resolved";
    private static final String STATUS_CLOSED = "closed";

    private static final String PRIORITY_LOW = "low";
    private static final String PRIORITY_NORMAL = "normal";
    private static final String PRIORITY_HIGH = "high";
    private static final String PRIORITY_URGENT = "urgent";

    private static final String MODE_MANUAL = "manual";
    private static final String MODE_ROUND_ROBIN = "round_robin";

    private static final String DIRECTION_INBOUND = "inbound";
    private static final String DIRECTION_OUTBOUND = "outbound";
    private static final String DIRECTION_INTERNAL = "internal";
    private static final String DIRECTION_SYSTEM = "system";

    private static final ConcurrentHashMap<String, ReentrantReadWriteLock> LOCKS = new ConcurrentHashMap<String, ReentrantReadWriteLock>();

    public static final class TicketRec {
        public String uuid;
        public String matterUuid;
        public String channel;
        public String subject;
        public String status;
        public String priority;

        public String assignmentMode;
        public String assignedUserUuid;

        public String reminderAt;
        public String dueAt;

        public String customerDisplay;
        public String customerAddress;
        public String mailboxAddress;

        public String threadKey;
        public String externalConversationId;

        public String createdAt;
        public String updatedAt;
        public String lastInboundAt;
        public String lastOutboundAt;

        public int inboundCount;
        public int outboundCount;

        public boolean mmsEnabled;
        public boolean archived;

        public String reportDocumentUuid;
        public String reportPartUuid;
        public String lastReportVersionUuid;
    }

    public static final class MessageRec {
        public String uuid;
        public String ticketUuid;

        public String direction;
        public String channel;
        public String body;
        public boolean mms;

        public String fromAddress;
        public String toAddress;

        public String providerMessageId;
        public String emailMessageId;
        public String emailInReplyTo;
        public String emailReferences;

        public String createdBy;
        public String createdAt;
    }

    public static final class AttachmentRec {
        public String uuid;
        public String ticketUuid;
        public String messageUuid;

        public String fileName;
        public String mimeType;
        public String fileSizeBytes;
        public String checksumSha256;
        public String storageFile;

        public boolean inlineMedia;

        public String uploadedBy;
        public String uploadedAt;
    }

    public static final class AssignmentRec {
        public String uuid;
        public String ticketUuid;

        public String mode;
        public String fromUserUuid;
        public String toUserUuid;
        public String reason;

        public String changedBy;
        public String changedAt;
    }

    public static final class AttachmentBlob {
        public final AttachmentRec attachment;
        public final Path path;

        AttachmentBlob(AttachmentRec attachment, Path path) {
            this.attachment = attachment;
            this.path = path;
        }
    }

    public static omnichannel_tickets defaultStore() {
        return new omnichannel_tickets();
    }

    public void ensure(String tenantUuid) throws Exception {
        String tu = safeToken(tenantUuid);
        if (tu.isBlank()) throw new IllegalArgumentException("tenantUuid required");

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            Path root = ticketsRoot(tu);
            Files.createDirectories(root);
            Files.createDirectories(ticketsDir(tu));
            if (!Files.exists(ticketsPath(tu))) {
                writeAtomic(ticketsPath(tu), "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<omnichannelTickets updated=\"" + xmlAttr(nowIso()) + "\"></omnichannelTickets>\n");
            }
            if (!Files.exists(roundRobinStatePath(tu))) {
                writeAtomic(roundRobinStatePath(tu), "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<roundRobinState updated=\"" + xmlAttr(nowIso()) + "\"></roundRobinState>\n");
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<TicketRec> listTickets(String tenantUuid) throws Exception {
        String tu = safeToken(tenantUuid);
        if (tu.isBlank()) return List.of();
        ensure(tu);

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.readLock().lock();
        try {
            return readTicketsLocked(tu);
        } finally {
            lock.readLock().unlock();
        }
    }

    public TicketRec getTicket(String tenantUuid, String ticketUuid) throws Exception {
        String id = safe(ticketUuid).trim();
        if (id.isBlank()) return null;
        List<TicketRec> all = listTickets(tenantUuid);
        for (int i = 0; i < all.size(); i++) {
            TicketRec rec = all.get(i);
            if (rec == null) continue;
            if (id.equals(safe(rec.uuid).trim())) return rec;
        }
        return null;
    }

    public TicketRec createTicket(String tenantUuid,
                                  String matterUuid,
                                  String channel,
                                  String subject,
                                  String status,
                                  String priority,
                                  String assignmentMode,
                                  String assignedUserUuid,
                                  String reminderAt,
                                  String dueAt,
                                  String customerDisplay,
                                  String customerAddress,
                                  String mailboxAddress,
                                  String threadKey,
                                  String externalConversationId,
                                  String initialDirection,
                                  String initialBody,
                                  String initialFromAddress,
                                  String initialToAddress,
                                  boolean initialMms,
                                  String actor,
                                  String assignmentReason) throws Exception {

        String tu = safeToken(tenantUuid);
        if (tu.isBlank()) throw new IllegalArgumentException("tenantUuid required");
        String subj = clampLen(subject, MAX_SUBJECT_LEN).trim();
        if (subj.isBlank()) throw new IllegalArgumentException("subject required");

        TicketRec created;

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensure(tu);
            List<TicketRec> all = readTicketsLocked(tu);

            TicketRec rec = new TicketRec();
            rec.uuid = UUID.randomUUID().toString();
            rec.matterUuid = safe(matterUuid).trim();
            rec.channel = canonicalChannel(channel);
            rec.subject = subj;
            rec.status = canonicalStatus(status);
            rec.priority = canonicalPriority(priority);
            rec.assignmentMode = canonicalAssignmentMode(assignmentMode);
            rec.assignedUserUuid = normalizeAssignmentCsv(assignedUserUuid);
            rec.reminderAt = clampLen(reminderAt, 80).trim();
            rec.dueAt = clampLen(dueAt, 80).trim();
            rec.customerDisplay = clampLen(customerDisplay, MAX_CONTACT_LEN).trim();
            rec.customerAddress = clampLen(customerAddress, MAX_CONTACT_LEN).trim();
            rec.mailboxAddress = clampLen(mailboxAddress, MAX_CONTACT_LEN).trim();
            rec.threadKey = clampLen(threadKey, MAX_REF_LEN).trim();
            rec.externalConversationId = clampLen(externalConversationId, MAX_REF_LEN).trim();
            rec.createdAt = nowIso();
            rec.updatedAt = rec.createdAt;
            rec.lastInboundAt = "";
            rec.lastOutboundAt = "";
            rec.inboundCount = 0;
            rec.outboundCount = 0;
            rec.mmsEnabled = initialMms;
            rec.archived = false;
            rec.reportDocumentUuid = "";
            rec.reportPartUuid = "";
            rec.lastReportVersionUuid = "";

            all.add(rec);
            sortTickets(all);
            writeTicketsLocked(tu, all);

            String assignee = safe(rec.assignedUserUuid).trim();
            if (!assignee.isBlank()) {
                appendAssignmentLocked(tu, rec.uuid, MODE_MANUAL, "", assignee, clampLen(assignmentReason, MAX_REASON_LEN), actor);
            }

            if (!safe(initialBody).trim().isBlank()) {
                addMessageLocked(tu,
                        rec.uuid,
                        initialDirection,
                        initialBody,
                        initialMms,
                        initialFromAddress,
                        initialToAddress,
                        "",
                        "",
                        "",
                        "",
                        actor);
                rec = findTicketByUuid(readTicketsLocked(tu), rec.uuid);
            }

            created = findTicketByUuid(readTicketsLocked(tu), rec.uuid);
        } finally {
            lock.writeLock().unlock();
        }

        TicketRec out = created;
        if (out != null && !safe(out.matterUuid).trim().isBlank()) {
            out = refreshMatterReport(tu, out.uuid, actor);
        }
        if (out != null) {
            publishThreadEvent(
                    tu,
                    "omnichannel.thread.created",
                    out,
                    Map.of("assignment_mode", safe(out.assignmentMode))
            );
            audit("omnichannel.thread.created", tu, actor, safe(out.matterUuid), Map.of("thread_uuid", safe(out.uuid)));
        }
        return out;
    }

    public boolean updateTicket(String tenantUuid,
                                TicketRec in,
                                String actor,
                                String assignmentReason) throws Exception {
        String tu = safeToken(tenantUuid);
        String id = safe(in == null ? "" : in.uuid).trim();
        if (tu.isBlank()) throw new IllegalArgumentException("tenantUuid required");
        if (id.isBlank()) throw new IllegalArgumentException("thread uuid required");

        boolean changed;
        boolean needsReport;

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensure(tu);
            List<TicketRec> all = readTicketsLocked(tu);
            changed = false;
            needsReport = false;

            for (int i = 0; i < all.size(); i++) {
                TicketRec rec = all.get(i);
                if (rec == null) continue;
                if (!id.equals(safe(rec.uuid).trim())) continue;

                String oldAssignee = safe(rec.assignedUserUuid).trim();
                String newAssignee = normalizeAssignmentCsv(in.assignedUserUuid);
                String oldMode = canonicalAssignmentMode(rec.assignmentMode);
                String newMode = canonicalAssignmentMode(in.assignmentMode);

                String newSubject = clampLen(in.subject, MAX_SUBJECT_LEN).trim();
                if (newSubject.isBlank()) throw new IllegalArgumentException("subject required");

                rec.matterUuid = safe(in.matterUuid).trim();
                rec.channel = canonicalChannel(in.channel);
                rec.subject = newSubject;
                rec.status = canonicalStatus(in.status);
                rec.priority = canonicalPriority(in.priority);
                rec.assignmentMode = newMode;
                rec.assignedUserUuid = newAssignee;
                rec.reminderAt = clampLen(in.reminderAt, 80).trim();
                rec.dueAt = clampLen(in.dueAt, 80).trim();
                rec.customerDisplay = clampLen(in.customerDisplay, MAX_CONTACT_LEN).trim();
                rec.customerAddress = clampLen(in.customerAddress, MAX_CONTACT_LEN).trim();
                rec.mailboxAddress = clampLen(in.mailboxAddress, MAX_CONTACT_LEN).trim();
                rec.threadKey = clampLen(in.threadKey, MAX_REF_LEN).trim();
                rec.externalConversationId = clampLen(in.externalConversationId, MAX_REF_LEN).trim();
                rec.mmsEnabled = in.mmsEnabled;
                rec.archived = in.archived;
                rec.updatedAt = nowIso();

                changed = true;
                needsReport = !safe(rec.matterUuid).trim().isBlank();

                if (!oldAssignee.equals(newAssignee) || !oldMode.equals(newMode)) {
                    appendAssignmentLocked(tu,
                            rec.uuid,
                            newMode,
                            oldAssignee,
                            newAssignee,
                            clampLen(assignmentReason, MAX_REASON_LEN),
                            actor);
                }
                break;
            }

            if (changed) {
                sortTickets(all);
                writeTicketsLocked(tu, all);
            }
        } finally {
            lock.writeLock().unlock();
        }

        if (changed && needsReport) {
            refreshMatterReport(tu, id, actor);
        }
        if (changed) {
            TicketRec latest = getTicket(tu, id);
            if (latest != null) {
                publishThreadEvent(
                        tu,
                        "omnichannel.thread.updated",
                        latest,
                        Map.of("assignment_mode", safe(latest.assignmentMode))
                );
                audit("omnichannel.thread.updated", tu, actor, safe(latest.matterUuid), Map.of("thread_uuid", safe(id)));
            }
        }
        return changed;
    }

    public boolean setArchived(String tenantUuid, String ticketUuid, boolean archived, String actor) throws Exception {
        String tu = safeToken(tenantUuid);
        String id = safe(ticketUuid).trim();
        if (tu.isBlank() || id.isBlank()) return false;

        boolean changed = false;

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensure(tu);
            List<TicketRec> all = readTicketsLocked(tu);
            for (int i = 0; i < all.size(); i++) {
                TicketRec rec = all.get(i);
                if (rec == null) continue;
                if (!id.equals(safe(rec.uuid).trim())) continue;
                if (rec.archived != archived) {
                    rec.archived = archived;
                    rec.updatedAt = nowIso();
                    changed = true;
                }
                break;
            }
            if (changed) {
                sortTickets(all);
                writeTicketsLocked(tu, all);
            }
        } finally {
            lock.writeLock().unlock();
        }

        TicketRec rec = getTicket(tu, id);
        if (changed && rec != null && !safe(rec.matterUuid).trim().isBlank()) {
            refreshMatterReport(tu, id, actor);
        }
        if (changed && rec != null) {
            publishThreadEvent(
                    tu,
                    archived ? "omnichannel.thread.archived" : "omnichannel.thread.restored",
                    rec,
                    Map.of("archived", archived ? "true" : "false")
            );
            audit(
                    archived ? "omnichannel.thread.archived" : "omnichannel.thread.restored",
                    tu,
                    actor,
                    safe(rec.matterUuid),
                    Map.of("thread_uuid", safe(id))
            );
        }
        return changed;
    }

    public MessageRec addMessage(String tenantUuid,
                                 String ticketUuid,
                                 String direction,
                                 String body,
                                 boolean mms,
                                 String fromAddress,
                                 String toAddress,
                                 String providerMessageId,
                                 String emailMessageId,
                                 String emailInReplyTo,
                                 String emailReferences,
                                 String actor) throws Exception {
        String tu = safeToken(tenantUuid);
        String id = safe(ticketUuid).trim();
        if (tu.isBlank()) throw new IllegalArgumentException("tenantUuid required");
        if (id.isBlank()) throw new IllegalArgumentException("thread uuid required");

        MessageRec rec;

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensure(tu);
            rec = addMessageLocked(tu,
                    id,
                    direction,
                    body,
                    mms,
                    fromAddress,
                    toAddress,
                    providerMessageId,
                    emailMessageId,
                    emailInReplyTo,
                    emailReferences,
                    actor);
        } finally {
            lock.writeLock().unlock();
        }

        TicketRec ticket = getTicket(tu, id);
        if (ticket != null && !safe(ticket.matterUuid).trim().isBlank()) {
            refreshMatterReport(tu, id, actor);
        }
        if (ticket != null && rec != null) {
            publishThreadEvent(
                    tu,
                    "omnichannel.message.added",
                    ticket,
                    Map.of(
                            "thread_uuid", safe(ticket.uuid),
                            "message_uuid", safe(rec.uuid),
                            "direction", safe(rec.direction)
                    )
            );
            audit(
                    "omnichannel.message.added",
                    tu,
                    actor,
                    safe(ticket.matterUuid),
                    Map.of("thread_uuid", safe(ticket.uuid), "message_uuid", safe(rec.uuid))
            );
        }
        return rec;
    }

    public AttachmentRec saveAttachment(String tenantUuid,
                                        String ticketUuid,
                                        String messageUuid,
                                        String fileName,
                                        String mimeType,
                                        byte[] bytes,
                                        boolean inlineMedia,
                                        String uploadedBy) throws Exception {
        String tu = safeToken(tenantUuid);
        String id = safe(ticketUuid).trim();
        if (tu.isBlank()) throw new IllegalArgumentException("tenantUuid required");
        if (id.isBlank()) throw new IllegalArgumentException("thread uuid required");
        if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("Attachment bytes required.");
        if (bytes.length > MAX_ATTACHMENT_BYTES) throw new IllegalArgumentException("Attachment exceeds max size of 20MB.");

        AttachmentRec rec;

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensure(tu);
            TicketRec ticket = findTicketByUuid(readTicketsLocked(tu), id);
            if (ticket == null) throw new IllegalArgumentException("Thread not found.");

            String safeName = sanitizeFileName(fileName);
            if (safeName.isBlank()) safeName = "attachment.bin";

            Path folder = attachmentFilesDir(tu, id);
            Files.createDirectories(folder);

            String attachmentUuid = UUID.randomUUID().toString();
            String storageName = attachmentUuid + "__" + safeName;
            Path normalizedFolder = folder.toAbsolutePath().normalize();
            Path p = normalizedFolder.resolve(storageName).normalize();
            if (!p.startsWith(normalizedFolder)) {
                throw new IllegalArgumentException("Invalid attachment storage path.");
            }
            Files.write(p, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);

            AttachmentRec a = new AttachmentRec();
            a.uuid = attachmentUuid;
            a.ticketUuid = id;
            a.messageUuid = safe(messageUuid).trim();
            a.fileName = safeName;
            a.mimeType = safe(mimeType).trim();
            if (a.mimeType.isBlank()) {
                try {
                    a.mimeType = safe(Files.probeContentType(p)).trim();
                } catch (Exception ignored) {
                    a.mimeType = "";
                }
            }
            if (a.mimeType.isBlank()) a.mimeType = "application/octet-stream";
            a.fileSizeBytes = String.valueOf(bytes.length);
            a.checksumSha256 = sha256(bytes);
            a.storageFile = storageName;
            a.inlineMedia = inlineMedia;
            a.uploadedBy = safe(uploadedBy).trim();
            a.uploadedAt = nowIso();

            List<AttachmentRec> all = readAttachmentsLocked(tu, id);
            all.add(a);
            sortAttachments(all);
            writeAttachmentsLocked(tu, id, all);

            updateTicketTimestampLocked(tu, id);
            rec = a;
        } finally {
            lock.writeLock().unlock();
        }

        TicketRec ticket = getTicket(tu, id);
        if (ticket != null && !safe(ticket.matterUuid).trim().isBlank()) {
            refreshMatterReport(tu, id, uploadedBy);
        }
        if (ticket != null && rec != null) {
            publishThreadEvent(
                    tu,
                    "omnichannel.attachment.added",
                    ticket,
                    Map.of(
                            "thread_uuid", safe(ticket.uuid),
                            "attachment_uuid", safe(rec.uuid),
                            "file_name", safe(rec.fileName),
                            "mime_type", safe(rec.mimeType)
                    )
            );
            audit(
                    "omnichannel.attachment.added",
                    tu,
                    uploadedBy,
                    safe(ticket.matterUuid),
                    Map.of("thread_uuid", safe(ticket.uuid), "attachment_uuid", safe(rec.uuid))
            );
        }

        return rec;
    }

    public AttachmentBlob getAttachmentBlob(String tenantUuid,
                                            String ticketUuid,
                                            String attachmentUuid) throws Exception {
        String tu = safeToken(tenantUuid);
        String tid = safe(ticketUuid).trim();
        String aid = safe(attachmentUuid).trim();
        if (tu.isBlank() || tid.isBlank() || aid.isBlank()) return null;
        ensure(tu);

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.readLock().lock();
        try {
            List<AttachmentRec> rows = readAttachmentsLocked(tu, tid);
            for (int i = 0; i < rows.size(); i++) {
                AttachmentRec a = rows.get(i);
                if (a == null) continue;
                if (!aid.equals(safe(a.uuid).trim())) continue;
                Path p = attachmentPath(tu, tid, a);
                if (p == null) return null;
                if (!Files.exists(p) || !Files.isRegularFile(p)) return null;
                return new AttachmentBlob(a, p);
            }
            return null;
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<MessageRec> listMessages(String tenantUuid, String ticketUuid) throws Exception {
        String tu = safeToken(tenantUuid);
        String tid = safe(ticketUuid).trim();
        if (tu.isBlank() || tid.isBlank()) return List.of();
        ensure(tu);

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.readLock().lock();
        try {
            return readMessagesLocked(tu, tid);
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<AttachmentRec> listAttachments(String tenantUuid, String ticketUuid) throws Exception {
        String tu = safeToken(tenantUuid);
        String tid = safe(ticketUuid).trim();
        if (tu.isBlank() || tid.isBlank()) return List.of();
        ensure(tu);

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.readLock().lock();
        try {
            return readAttachmentsLocked(tu, tid);
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<AssignmentRec> listAssignments(String tenantUuid, String ticketUuid) throws Exception {
        String tu = safeToken(tenantUuid);
        String tid = safe(ticketUuid).trim();
        if (tu.isBlank() || tid.isBlank()) return List.of();
        ensure(tu);

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.readLock().lock();
        try {
            return readAssignmentsLocked(tu, tid);
        } finally {
            lock.readLock().unlock();
        }
    }

    public String chooseRoundRobinAssignee(String tenantUuid,
                                           String queueKey,
                                           List<String> candidateUserUuids) throws Exception {
        String tu = safeToken(tenantUuid);
        if (tu.isBlank()) throw new IllegalArgumentException("tenantUuid required");

        LinkedHashSet<String> unique = new LinkedHashSet<String>();
        if (candidateUserUuids != null) {
            for (int i = 0; i < candidateUserUuids.size(); i++) {
                String id = safe(candidateUserUuids.get(i)).trim();
                if (id.isBlank()) continue;
                unique.add(id);
            }
        }
        if (unique.isEmpty()) throw new IllegalArgumentException("No candidates supplied for round robin assignment.");

        List<String> candidates = new ArrayList<String>(unique);
        candidates.sort(String::compareToIgnoreCase);

        String key = safe(queueKey).trim().toLowerCase(Locale.ROOT);
        if (key.isBlank()) key = "default";

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensure(tu);
            Map<String, Integer> state = readRoundRobinStateLocked(tu);
            int previous = state.getOrDefault(key, Integer.valueOf(-1)).intValue();
            int next = previous + 1;
            if (next < 0 || next >= candidates.size()) next = 0;
            state.put(key, Integer.valueOf(next));
            writeRoundRobinStateLocked(tu, state);
            return candidates.get(next);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public TicketRec refreshMatterReport(String tenantUuid, String ticketUuid, String actor) throws Exception {
        String tu = safeToken(tenantUuid);
        String tid = safe(ticketUuid).trim();
        if (tu.isBlank()) throw new IllegalArgumentException("tenantUuid required");
        if (tid.isBlank()) throw new IllegalArgumentException("thread uuid required");

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensure(tu);

            List<TicketRec> tickets = readTicketsLocked(tu);
            TicketRec ticket = findTicketByUuid(tickets, tid);
            if (ticket == null) throw new IllegalArgumentException("Thread not found.");
            String matterUuid = safe(ticket.matterUuid).trim();
            if (matterUuid.isBlank()) return ticket;

            matters.MatterRec matter = matters.defaultStore().getByUuid(tu, matterUuid);
            if (matter == null || matter.trashed) throw new IllegalArgumentException("Linked matter not found.");

            List<MessageRec> messages = readMessagesLocked(tu, tid);
            List<AttachmentRec> attachments = readAttachmentsLocked(tu, tid);
            List<AssignmentRec> assignments = readAssignmentsLocked(tu, tid);

            documents docStore = documents.defaultStore();
            document_parts partStore = document_parts.defaultStore();
            part_versions versionStore = part_versions.defaultStore();

            String docUuid = safe(ticket.reportDocumentUuid).trim();
            documents.DocumentRec docRec = null;
            if (!docUuid.isBlank()) {
                docRec = docStore.get(tu, matterUuid, docUuid);
            }

            String shortId = safe(ticket.uuid);
            if (shortId.length() > 8) shortId = shortId.substring(0, 8);
            String docTitle = "Omnichannel Thread " + shortId + " Report";

            if (docRec == null) {
                docRec = docStore.create(
                        tu,
                        matterUuid,
                        docTitle,
                        "communication",
                        "omnichannel",
                        mapDocumentStatus(ticket.status),
                        safe(ticket.assignedUserUuid).trim(),
                        "work_product",
                        "",
                        "thread:" + safe(ticket.uuid),
                        "Auto-generated omnichannel thread report"
                );
                docUuid = safe(docRec == null ? "" : docRec.uuid).trim();
            } else {
                documents.DocumentRec in = new documents.DocumentRec();
                in.uuid = docRec.uuid;
                in.title = docTitle;
                in.category = safe(docRec.category).trim().isBlank() ? "communication" : safe(docRec.category);
                in.subcategory = safe(docRec.subcategory).trim().isBlank() ? "omnichannel" : safe(docRec.subcategory);
                in.status = mapDocumentStatus(ticket.status);
                in.owner = safe(ticket.assignedUserUuid).trim();
                in.privilegeLevel = safe(docRec.privilegeLevel).trim().isBlank() ? "work_product" : safe(docRec.privilegeLevel);
                in.filedOn = safe(docRec.filedOn);
                in.externalReference = "thread:" + safe(ticket.uuid);
                in.notes = "Auto-generated omnichannel thread report";
                docStore.update(tu, matterUuid, in);
                docUuid = safe(docRec.uuid).trim();
            }

            String partUuid = safe(ticket.reportPartUuid).trim();
            document_parts.PartRec partRec = null;
            if (!partUuid.isBlank()) {
                partRec = partStore.get(tu, matterUuid, docUuid, partUuid);
            }
            if (partRec == null) {
                partRec = partStore.create(
                        tu,
                        matterUuid,
                        docUuid,
                        "Thread Report PDF",
                        "attachment",
                        "1",
                        "internal",
                        safe(actor).trim(),
                        "Auto-generated thread report history"
                );
                partUuid = safe(partRec == null ? "" : partRec.uuid).trim();
            }

            Path versionDir = partStore.partFolder(tu, matterUuid, docUuid, partUuid).resolve("version_files");
            Files.createDirectories(versionDir);
            String fileName = "thread_report_" + safe(ticket.uuid).replaceAll("[^A-Za-z0-9._-]", "_") + "_"
                    + nowIso().replace(':', '-') + ".pdf";
            Path reportPath = versionDir.resolve(UUID.randomUUID().toString() + "__" + fileName).toAbsolutePath().normalize();
            pdf_redaction_service.requirePathWithinTenant(reportPath, tu, "Omnichannel thread report path");

            writeTicketReportPdf(reportPath, tu, tid, ticket, matter, messages, attachments, assignments);
            long bytes = Files.size(reportPath);
            String checksum = pdf_redaction_service.sha256(reportPath);

            String versionLabel = "Thread Report " + nowIso();
            part_versions.VersionRec version = versionStore.create(
                    tu,
                    matterUuid,
                    docUuid,
                    partUuid,
                    versionLabel,
                    "omnichannel_ticketing",
                    "application/pdf",
                    checksum,
                    String.valueOf(bytes),
                    reportPath.toString(),
                    safe(actor).trim(),
                    "Generated from omnichannel thread updates",
                    true
            );

            ticket.reportDocumentUuid = docUuid;
            ticket.reportPartUuid = partUuid;
            ticket.lastReportVersionUuid = safe(version == null ? "" : version.uuid).trim();
            ticket.updatedAt = nowIso();
            writeTicketsLocked(tu, tickets);

            return ticket;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private MessageRec addMessageLocked(String tenantUuid,
                                        String ticketUuid,
                                        String direction,
                                        String body,
                                        boolean mms,
                                        String fromAddress,
                                        String toAddress,
                                        String providerMessageId,
                                        String emailMessageId,
                                        String emailInReplyTo,
                                        String emailReferences,
                                        String actor) throws Exception {
        List<TicketRec> tickets = readTicketsLocked(tenantUuid);
        TicketRec ticket = findTicketByUuid(tickets, ticketUuid);
        if (ticket == null) throw new IllegalArgumentException("Thread not found.");

        String cleanBody = clampLen(body, MAX_BODY_LEN).trim();
        if (cleanBody.isBlank()) throw new IllegalArgumentException("Message body required.");

        List<MessageRec> all = readMessagesLocked(tenantUuid, ticketUuid);

        MessageRec rec = new MessageRec();
        rec.uuid = UUID.randomUUID().toString();
        rec.ticketUuid = ticketUuid;
        rec.direction = canonicalDirection(direction);
        rec.channel = canonicalChannel(ticket.channel);
        rec.body = cleanBody;
        rec.mms = mms;
        rec.fromAddress = clampLen(fromAddress, MAX_CONTACT_LEN).trim();
        rec.toAddress = clampLen(toAddress, MAX_CONTACT_LEN).trim();
        rec.providerMessageId = clampLen(providerMessageId, MAX_REF_LEN).trim();
        rec.emailMessageId = clampLen(emailMessageId, MAX_REF_LEN).trim();
        rec.emailInReplyTo = clampLen(emailInReplyTo, MAX_REF_LEN).trim();
        rec.emailReferences = clampLen(emailReferences, MAX_BODY_LEN).trim();
        rec.createdBy = clampLen(actor, MAX_CONTACT_LEN).trim();
        rec.createdAt = nowIso();

        all.add(rec);
        sortMessages(all);
        writeMessagesLocked(tenantUuid, ticketUuid, all);

        if (DIRECTION_INBOUND.equals(rec.direction)) {
            ticket.inboundCount = ticket.inboundCount + 1;
            ticket.lastInboundAt = rec.createdAt;
        }
        if (DIRECTION_OUTBOUND.equals(rec.direction)) {
            ticket.outboundCount = ticket.outboundCount + 1;
            ticket.lastOutboundAt = rec.createdAt;
        }
        if (mms) ticket.mmsEnabled = true;
        ticket.updatedAt = rec.createdAt;
        writeTicketsLocked(tenantUuid, tickets);

        return rec;
    }

    private void appendAssignmentLocked(String tenantUuid,
                                        String ticketUuid,
                                        String mode,
                                        String fromUserUuid,
                                        String toUserUuid,
                                        String reason,
                                        String actor) throws Exception {
        List<AssignmentRec> all = readAssignmentsLocked(tenantUuid, ticketUuid);

        AssignmentRec rec = new AssignmentRec();
        rec.uuid = UUID.randomUUID().toString();
        rec.ticketUuid = safe(ticketUuid).trim();
        rec.mode = canonicalAssignmentMode(mode);
        rec.fromUserUuid = normalizeAssignmentCsv(fromUserUuid);
        rec.toUserUuid = normalizeAssignmentCsv(toUserUuid);
        rec.reason = clampLen(reason, MAX_REASON_LEN).trim();
        rec.changedBy = clampLen(actor, MAX_CONTACT_LEN).trim();
        rec.changedAt = nowIso();

        all.add(rec);
        sortAssignments(all);
        writeAssignmentsLocked(tenantUuid, ticketUuid, all);
    }

    private void updateTicketTimestampLocked(String tenantUuid, String ticketUuid) throws Exception {
        List<TicketRec> all = readTicketsLocked(tenantUuid);
        for (int i = 0; i < all.size(); i++) {
            TicketRec rec = all.get(i);
            if (rec == null) continue;
            if (!safe(ticketUuid).trim().equals(safe(rec.uuid).trim())) continue;
            rec.updatedAt = nowIso();
            break;
        }
        sortTickets(all);
        writeTicketsLocked(tenantUuid, all);
    }

    private static void writeTicketReportPdf(Path outputPdf,
                                             String tenantUuid,
                                             String ticketUuid,
                                             TicketRec ticket,
                                             matters.MatterRec matter,
                                             List<MessageRec> messages,
                                             List<AttachmentRec> attachments,
                                             List<AssignmentRec> assignments) throws Exception {
        if (outputPdf == null) throw new IllegalArgumentException("Output PDF path required.");
        Files.createDirectories(outputPdf.getParent());

        ArrayList<MessageRec> publicMessages = new ArrayList<MessageRec>();
        LinkedHashSet<String> internalMessageIds = new LinkedHashSet<String>();
        int internalNoteCount = 0;
        List<MessageRec> messageRows = messages == null ? List.of() : messages;
        for (int i = 0; i < messageRows.size(); i++) {
            MessageRec m = messageRows.get(i);
            if (m == null) continue;
            String direction = canonicalDirection(m.direction);
            if (DIRECTION_INTERNAL.equals(direction)) {
                internalNoteCount++;
                String id = safe(m.uuid).trim();
                if (!id.isBlank()) internalMessageIds.add(id);
                continue;
            }
            publicMessages.add(m);
        }

        ArrayList<AttachmentRec> publicAttachments = new ArrayList<AttachmentRec>();
        List<AttachmentRec> attachmentRows = attachments == null ? List.of() : attachments;
        for (int i = 0; i < attachmentRows.size(); i++) {
            AttachmentRec a = attachmentRows.get(i);
            if (a == null) continue;
            String msgId = safe(a.messageUuid).trim();
            if (!msgId.isBlank() && internalMessageIds.contains(msgId)) continue;
            publicAttachments.add(a);
        }

        List<String> lines = new ArrayList<String>(512);

        lines.add("Omnichannel Thread Report");
        lines.add("Generated At: " + nowIso());
        lines.add("");

        lines.add("Thread Summary");
        lines.add("Thread UUID: " + safe(ticket == null ? "" : ticket.uuid));
        lines.add("Matter UUID: " + safe(ticket == null ? "" : ticket.matterUuid));
        lines.add("Matter Label: " + safe(matter == null ? "" : matter.label));
        lines.add("Channel: " + safe(ticket == null ? "" : ticket.channel));
        lines.add("Subject: " + safe(ticket == null ? "" : ticket.subject));
        lines.add("Status: " + safe(ticket == null ? "" : ticket.status));
        lines.add("Priority: " + safe(ticket == null ? "" : ticket.priority));
        lines.add("Assigned User UUID: " + safe(ticket == null ? "" : ticket.assignedUserUuid));
        lines.add("Assignment Mode: " + safe(ticket == null ? "" : ticket.assignmentMode));
        lines.add("Customer: " + safe(ticket == null ? "" : ticket.customerDisplay));
        lines.add("Customer Address: " + safe(ticket == null ? "" : ticket.customerAddress));
        lines.add("Mailbox: " + safe(ticket == null ? "" : ticket.mailboxAddress));
        lines.add("Thread Key: " + safe(ticket == null ? "" : ticket.threadKey));
        lines.add("External Conversation ID: " + safe(ticket == null ? "" : ticket.externalConversationId));
        lines.add("Reminder At: " + safe(ticket == null ? "" : ticket.reminderAt));
        lines.add("Due At: " + safe(ticket == null ? "" : ticket.dueAt));
        lines.add("Inbound Count: " + (ticket == null ? 0 : ticket.inboundCount));
        lines.add("Outbound Count: " + (ticket == null ? 0 : ticket.outboundCount));
        lines.add("Last Inbound At: " + safe(ticket == null ? "" : ticket.lastInboundAt));
        lines.add("Last Outbound At: " + safe(ticket == null ? "" : ticket.lastOutboundAt));
        lines.add("Created At: " + safe(ticket == null ? "" : ticket.createdAt));
        lines.add("Updated At: " + safe(ticket == null ? "" : ticket.updatedAt));
        lines.add("Internal Notes Hidden From External Report: " + internalNoteCount);
        lines.add("");

        lines.add("Assignment History (" + (assignments == null ? 0 : assignments.size()) + ")");
        if (assignments == null || assignments.isEmpty()) {
            lines.add("No assignment changes recorded.");
        } else {
            for (int i = 0; i < assignments.size(); i++) {
                AssignmentRec a = assignments.get(i);
                if (a == null) continue;
                lines.add("[" + safe(a.changedAt) + "] mode=" + safe(a.mode)
                        + " from=" + safe(a.fromUserUuid)
                        + " to=" + safe(a.toUserUuid)
                        + " by=" + safe(a.changedBy));
                if (!safe(a.reason).trim().isBlank()) {
                    appendWrapped(lines, "Reason: " + safe(a.reason), 110);
                }
            }
        }
        lines.add("");

        lines.add("Messages (" + publicMessages.size() + ")");
        if (publicMessages.isEmpty()) {
            lines.add("No messages yet.");
        } else {
            for (int i = 0; i < publicMessages.size(); i++) {
                MessageRec m = publicMessages.get(i);
                if (m == null) continue;
                lines.add("[" + safe(m.createdAt) + "] "
                        + safe(m.direction).toUpperCase(Locale.ROOT)
                        + " channel=" + safe(m.channel)
                        + " mms=" + (m.mms ? "yes" : "no")
                        + " from=" + safe(m.fromAddress)
                        + " to=" + safe(m.toAddress)
                        + " by=" + safe(m.createdBy));
                if (!safe(m.providerMessageId).trim().isBlank()) {
                    lines.add("Provider Message ID: " + safe(m.providerMessageId));
                }
                if (!safe(m.emailMessageId).trim().isBlank()) {
                    lines.add("Email Message ID: " + safe(m.emailMessageId));
                }
                if (!safe(m.emailInReplyTo).trim().isBlank()) {
                    lines.add("Email In-Reply-To: " + safe(m.emailInReplyTo));
                }
                if (!safe(m.emailReferences).trim().isBlank()) {
                    appendWrapped(lines, "Email References: " + safe(m.emailReferences), 110);
                }
                appendWrapped(lines, "Body: " + safe(m.body), 110);
                lines.add("");
            }
        }

        lines.add("Embedded Multimedia Manifest (" + publicAttachments.size() + ")");
        if (publicAttachments.isEmpty()) {
            lines.add("No attachments were embedded.");
        } else {
            for (int i = 0; i < publicAttachments.size(); i++) {
                AttachmentRec a = publicAttachments.get(i);
                if (a == null) continue;
                Path ap = attachmentPath(tenantUuid, ticketUuid, a);
                boolean exists = ap != null && Files.exists(ap) && Files.isRegularFile(ap);
                lines.add("[" + safe(a.uploadedAt) + "] " + safe(a.fileName)
                        + " (" + safe(a.mimeType) + ") size=" + safe(a.fileSizeBytes)
                        + " bytes inline_media=" + (a.inlineMedia ? "yes" : "no")
                        + " embedded=" + (exists ? "yes" : "no")
                        + " message_uuid=" + safe(a.messageUuid)
                        + " uploaded_by=" + safe(a.uploadedBy));
                lines.add("Checksum: " + safe(a.checksumSha256));
            }
        }

        try (PDDocument pdf = new PDDocument()) {
            embedAttachmentsInPdf(pdf, tenantUuid, ticketUuid, publicAttachments);
            writeLinesToPdf(pdf, lines);
            appendInlineImagePages(pdf, tenantUuid, ticketUuid, publicAttachments);
            pdf.save(outputPdf.toFile());
        }
    }

    private static void embedAttachmentsInPdf(PDDocument pdf,
                                              String tenantUuid,
                                              String ticketUuid,
                                              List<AttachmentRec> attachments) throws Exception {
        if (pdf == null || attachments == null || attachments.isEmpty()) return;

        HashMap<String, PDComplexFileSpecification> efMap = new HashMap<String, PDComplexFileSpecification>();
        for (int i = 0; i < attachments.size(); i++) {
            AttachmentRec a = attachments.get(i);
            if (a == null) continue;

            Path p = attachmentPath(tenantUuid, ticketUuid, a);
            if (p == null || !Files.exists(p) || !Files.isRegularFile(p)) continue;

            try (InputStream in = Files.newInputStream(p)) {
                PDEmbeddedFile ef = new PDEmbeddedFile(pdf, in);
                ef.setSubtype(safe(a.mimeType).trim().isBlank() ? "application/octet-stream" : safe(a.mimeType).trim());
                long size = Files.size(p);
                if (size > Integer.MAX_VALUE) size = Integer.MAX_VALUE;
                ef.setSize((int) size);
                ef.setCreationDate(Calendar.getInstance());

                PDComplexFileSpecification fs = new PDComplexFileSpecification();
                String fileName = sanitizeFileName(a.fileName);
                if (fileName.isBlank()) fileName = "attachment.bin";
                fs.setFile(fileName);
                fs.setEmbeddedFile(ef);

                String key = fileName + "_" + safe(a.uuid).trim();
                efMap.put(key, fs);
            }
        }

        if (efMap.isEmpty()) return;

        PDDocumentNameDictionary names = new PDDocumentNameDictionary(pdf.getDocumentCatalog());
        PDEmbeddedFilesNameTreeNode tree = new PDEmbeddedFilesNameTreeNode();
        tree.setNames(efMap);
        names.setEmbeddedFiles(tree);
        pdf.getDocumentCatalog().setNames(names);
    }

    private static void appendInlineImagePages(PDDocument pdf,
                                               String tenantUuid,
                                               String ticketUuid,
                                               List<AttachmentRec> attachments) throws Exception {
        if (pdf == null || attachments == null || attachments.isEmpty()) return;

        for (int i = 0; i < attachments.size(); i++) {
            AttachmentRec a = attachments.get(i);
            if (a == null) continue;
            String mime = safe(a.mimeType).trim().toLowerCase(Locale.ROOT);
            if (!mime.startsWith("image/")) continue;

            Path p = attachmentPath(tenantUuid, ticketUuid, a);
            if (p == null || !Files.exists(p) || !Files.isRegularFile(p)) continue;

            byte[] bytes = Files.readAllBytes(p);
            if (bytes.length == 0) continue;

            PDImageXObject image;
            try {
                image = PDImageXObject.createFromByteArray(pdf, bytes, sanitizeFileName(a.fileName));
            } catch (Exception ex) {
                continue;
            }
            if (image == null) continue;

            PDPage page = new PDPage(PDRectangle.LETTER);
            pdf.addPage(page);

            float pageW = page.getMediaBox().getWidth();
            float pageH = page.getMediaBox().getHeight();
            float margin = 45f;
            float topTextY = pageH - margin;

            float maxW = pageW - (margin * 2f);
            float maxH = pageH - 170f;
            float iw = image.getWidth();
            float ih = image.getHeight();
            if (iw <= 0f || ih <= 0f) continue;
            float scale = Math.min(maxW / iw, maxH / ih);
            if (scale > 1f) scale = 1f;
            float drawW = iw * scale;
            float drawH = ih * scale;
            float x = (pageW - drawW) / 2f;
            float y = Math.max(margin + 10f, (pageH - drawH) / 2f - 20f);

            try (PDPageContentStream cs = new PDPageContentStream(pdf, page)) {
                cs.beginText();
                cs.setFont(PDType1Font.HELVETICA_BOLD, 12f);
                cs.newLineAtOffset(margin, topTextY);
                cs.showText(sanitizePdfText("Embedded Multimedia: " + safe(a.fileName)));
                cs.newLineAtOffset(0, -16f);
                cs.setFont(PDType1Font.HELVETICA, 10f);
                cs.showText(sanitizePdfText("Attachment UUID: " + safe(a.uuid) + " | MIME: " + safe(a.mimeType)
                        + " | Size: " + safe(a.fileSizeBytes) + " bytes"));
                cs.endText();

                cs.drawImage(image, x, y, drawW, drawH);
            }
        }
    }

    private static void writeLinesToPdf(PDDocument pdf, List<String> lines) throws Exception {
        if (pdf == null) throw new IllegalArgumentException("PDF document required.");

        final float margin = 50f;
        final float fontSize = 10f;
        final float leading = 14f;

        PDPage page = new PDPage(PDRectangle.LETTER);
        pdf.addPage(page);

        float y = page.getMediaBox().getHeight() - margin;
        PDPageContentStream cs = new PDPageContentStream(pdf, page);
        cs.beginText();
        cs.setFont(PDType1Font.HELVETICA, fontSize);
        cs.newLineAtOffset(margin, y);

        List<String> src = lines == null ? List.of() : lines;
        for (int i = 0; i < src.size(); i++) {
            String line = sanitizePdfText(src.get(i));

            if (y <= margin) {
                cs.endText();
                cs.close();

                page = new PDPage(PDRectangle.LETTER);
                pdf.addPage(page);
                y = page.getMediaBox().getHeight() - margin;

                cs = new PDPageContentStream(pdf, page);
                cs.beginText();
                cs.setFont(PDType1Font.HELVETICA, fontSize);
                cs.newLineAtOffset(margin, y);
            }

            cs.showText(line);
            cs.newLineAtOffset(0, -leading);
            y -= leading;
        }

        cs.endText();
        cs.close();
    }

    private static void appendWrapped(List<String> lines, String raw, int width) {
        if (lines == null) return;
        String text = safe(raw).replace('\r', ' ').replace('\n', ' ').trim();
        if (text.isBlank()) {
            lines.add("");
            return;
        }

        String[] words = text.split("\\s+");
        StringBuilder row = new StringBuilder(Math.max(width, 40));
        for (int i = 0; i < words.length; i++) {
            String w = safe(words[i]);
            if (w.isBlank()) continue;
            if (row.length() == 0) {
                row.append(w);
                continue;
            }
            if (row.length() + 1 + w.length() > Math.max(20, width)) {
                lines.add(row.toString());
                row.setLength(0);
                row.append(w);
            } else {
                row.append(' ').append(w);
            }
        }
        if (row.length() > 0) lines.add(row.toString());
    }

    private static String sanitizePdfText(String raw) {
        String s = safe(raw);
        StringBuilder out = new StringBuilder(Math.min(180, s.length()));
        int max = 160;
        for (int i = 0; i < s.length() && out.length() < max; i++) {
            char ch = s.charAt(i);
            if (ch == '\n' || ch == '\r' || ch == '\t') {
                out.append(' ');
                continue;
            }
            if (ch < 32 || ch > 126) {
                out.append('?');
            } else {
                out.append(ch);
            }
        }
        return out.toString();
    }

    private static List<TicketRec> readTicketsLocked(String tenantUuid) throws Exception {
        ArrayList<TicketRec> out = new ArrayList<TicketRec>();
        Path p = ticketsPath(tenantUuid);
        if (!Files.exists(p)) return out;

        Document d = parseXml(p);
        Element root = d == null ? null : d.getDocumentElement();
        if (root == null) return out;

        NodeList nl = root.getElementsByTagName("ticket");
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (!(n instanceof Element)) continue;
            Element e = (Element) n;

            TicketRec rec = new TicketRec();
            rec.uuid = text(e, "uuid");
            if (safe(rec.uuid).trim().isBlank()) rec.uuid = UUID.randomUUID().toString();
            rec.matterUuid = text(e, "matter_uuid");
            rec.channel = canonicalChannel(text(e, "channel"));
            rec.subject = text(e, "subject");
            rec.status = canonicalStatus(text(e, "status"));
            rec.priority = canonicalPriority(text(e, "priority"));
            rec.assignmentMode = canonicalAssignmentMode(text(e, "assignment_mode"));
            rec.assignedUserUuid = text(e, "assigned_user_uuid");
            rec.reminderAt = text(e, "reminder_at");
            rec.dueAt = text(e, "due_at");
            rec.customerDisplay = text(e, "customer_display");
            rec.customerAddress = text(e, "customer_address");
            rec.mailboxAddress = text(e, "mailbox_address");
            rec.threadKey = text(e, "thread_key");
            rec.externalConversationId = text(e, "external_conversation_id");
            rec.createdAt = text(e, "created_at");
            rec.updatedAt = text(e, "updated_at");
            rec.lastInboundAt = text(e, "last_inbound_at");
            rec.lastOutboundAt = text(e, "last_outbound_at");
            rec.inboundCount = parseInt(text(e, "inbound_count"), 0);
            rec.outboundCount = parseInt(text(e, "outbound_count"), 0);
            rec.mmsEnabled = parseBool(text(e, "mms_enabled"), false);
            rec.archived = parseBool(text(e, "archived"), false);
            rec.reportDocumentUuid = text(e, "report_document_uuid");
            rec.reportPartUuid = text(e, "report_part_uuid");
            rec.lastReportVersionUuid = text(e, "last_report_version_uuid");

            out.add(rec);
        }

        sortTickets(out);
        return out;
    }

    private static void writeTicketsLocked(String tenantUuid, List<TicketRec> rows) throws Exception {
        Path p = ticketsPath(tenantUuid);
        Files.createDirectories(p.getParent());

        StringBuilder sb = new StringBuilder(4096);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<omnichannelTickets updated=\"").append(xmlAttr(nowIso())).append("\">\n");

        List<TicketRec> src = rows == null ? List.of() : rows;
        for (int i = 0; i < src.size(); i++) {
            TicketRec rec = src.get(i);
            if (rec == null) continue;
            String id = safe(rec.uuid).trim();
            if (id.isBlank()) id = UUID.randomUUID().toString();

            sb.append("  <ticket>\n");
            writeTag(sb, "uuid", id);
            writeTag(sb, "matter_uuid", safe(rec.matterUuid).trim());
            writeTag(sb, "channel", canonicalChannel(rec.channel));
            writeTag(sb, "subject", safe(rec.subject).trim());
            writeTag(sb, "status", canonicalStatus(rec.status));
            writeTag(sb, "priority", canonicalPriority(rec.priority));
            writeTag(sb, "assignment_mode", canonicalAssignmentMode(rec.assignmentMode));
            writeTag(sb, "assigned_user_uuid", safe(rec.assignedUserUuid).trim());
            writeTag(sb, "reminder_at", safe(rec.reminderAt).trim());
            writeTag(sb, "due_at", safe(rec.dueAt).trim());
            writeTag(sb, "customer_display", safe(rec.customerDisplay).trim());
            writeTag(sb, "customer_address", safe(rec.customerAddress).trim());
            writeTag(sb, "mailbox_address", safe(rec.mailboxAddress).trim());
            writeTag(sb, "thread_key", safe(rec.threadKey).trim());
            writeTag(sb, "external_conversation_id", safe(rec.externalConversationId).trim());
            writeTag(sb, "created_at", safe(rec.createdAt).trim());
            writeTag(sb, "updated_at", safe(rec.updatedAt).trim());
            writeTag(sb, "last_inbound_at", safe(rec.lastInboundAt).trim());
            writeTag(sb, "last_outbound_at", safe(rec.lastOutboundAt).trim());
            writeTag(sb, "inbound_count", String.valueOf(Math.max(0, rec.inboundCount)));
            writeTag(sb, "outbound_count", String.valueOf(Math.max(0, rec.outboundCount)));
            writeTag(sb, "mms_enabled", rec.mmsEnabled ? "true" : "false");
            writeTag(sb, "archived", rec.archived ? "true" : "false");
            writeTag(sb, "report_document_uuid", safe(rec.reportDocumentUuid).trim());
            writeTag(sb, "report_part_uuid", safe(rec.reportPartUuid).trim());
            writeTag(sb, "last_report_version_uuid", safe(rec.lastReportVersionUuid).trim());
            sb.append("  </ticket>\n");
        }

        sb.append("</omnichannelTickets>\n");
        writeAtomic(p, sb.toString());
    }

    private static List<MessageRec> readMessagesLocked(String tenantUuid, String ticketUuid) throws Exception {
        ArrayList<MessageRec> out = new ArrayList<MessageRec>();
        Path p = messagesPath(tenantUuid, ticketUuid);
        if (!Files.exists(p)) return out;

        Document d = parseXml(p);
        Element root = d == null ? null : d.getDocumentElement();
        if (root == null) return out;

        NodeList nl = root.getElementsByTagName("message");
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (!(n instanceof Element)) continue;
            Element e = (Element) n;

            MessageRec rec = new MessageRec();
            rec.uuid = text(e, "uuid");
            if (safe(rec.uuid).trim().isBlank()) rec.uuid = UUID.randomUUID().toString();
            rec.ticketUuid = safe(ticketUuid).trim();
            rec.direction = canonicalDirection(text(e, "direction"));
            rec.channel = canonicalChannel(text(e, "channel"));
            rec.body = text(e, "body");
            rec.mms = parseBool(text(e, "mms"), false);
            rec.fromAddress = text(e, "from_address");
            rec.toAddress = text(e, "to_address");
            rec.providerMessageId = text(e, "provider_message_id");
            rec.emailMessageId = text(e, "email_message_id");
            rec.emailInReplyTo = text(e, "email_in_reply_to");
            rec.emailReferences = text(e, "email_references");
            rec.createdBy = text(e, "created_by");
            rec.createdAt = text(e, "created_at");

            out.add(rec);
        }

        sortMessages(out);
        return out;
    }

    private static void writeMessagesLocked(String tenantUuid, String ticketUuid, List<MessageRec> rows) throws Exception {
        Path p = messagesPath(tenantUuid, ticketUuid);
        Files.createDirectories(p.getParent());

        StringBuilder sb = new StringBuilder(2048);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<messages ticket_uuid=\"").append(xmlAttr(ticketUuid)).append("\" updated=\"").append(xmlAttr(nowIso())).append("\">\n");

        List<MessageRec> src = rows == null ? List.of() : rows;
        for (int i = 0; i < src.size(); i++) {
            MessageRec rec = src.get(i);
            if (rec == null) continue;

            String id = safe(rec.uuid).trim();
            if (id.isBlank()) id = UUID.randomUUID().toString();

            sb.append("  <message>\n");
            writeTag(sb, "uuid", id);
            writeTag(sb, "direction", canonicalDirection(rec.direction));
            writeTag(sb, "channel", canonicalChannel(rec.channel));
            writeTag(sb, "body", safe(rec.body));
            writeTag(sb, "mms", rec.mms ? "true" : "false");
            writeTag(sb, "from_address", safe(rec.fromAddress).trim());
            writeTag(sb, "to_address", safe(rec.toAddress).trim());
            writeTag(sb, "provider_message_id", safe(rec.providerMessageId).trim());
            writeTag(sb, "email_message_id", safe(rec.emailMessageId).trim());
            writeTag(sb, "email_in_reply_to", safe(rec.emailInReplyTo).trim());
            writeTag(sb, "email_references", safe(rec.emailReferences).trim());
            writeTag(sb, "created_by", safe(rec.createdBy).trim());
            writeTag(sb, "created_at", safe(rec.createdAt).trim());
            sb.append("  </message>\n");
        }

        sb.append("</messages>\n");
        writeAtomic(p, sb.toString());
    }

    private static List<AttachmentRec> readAttachmentsLocked(String tenantUuid, String ticketUuid) throws Exception {
        ArrayList<AttachmentRec> out = new ArrayList<AttachmentRec>();
        Path p = attachmentsPath(tenantUuid, ticketUuid);
        if (!Files.exists(p)) return out;

        Document d = parseXml(p);
        Element root = d == null ? null : d.getDocumentElement();
        if (root == null) return out;

        NodeList nl = root.getElementsByTagName("attachment");
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (!(n instanceof Element)) continue;
            Element e = (Element) n;

            AttachmentRec rec = new AttachmentRec();
            rec.uuid = text(e, "uuid");
            if (safe(rec.uuid).trim().isBlank()) rec.uuid = UUID.randomUUID().toString();
            rec.ticketUuid = safe(ticketUuid).trim();
            rec.messageUuid = text(e, "message_uuid");
            rec.fileName = text(e, "file_name");
            rec.mimeType = text(e, "mime_type");
            rec.fileSizeBytes = text(e, "file_size_bytes");
            rec.checksumSha256 = text(e, "checksum_sha256");
            rec.storageFile = text(e, "storage_file");
            rec.inlineMedia = parseBool(text(e, "inline_media"), false);
            rec.uploadedBy = text(e, "uploaded_by");
            rec.uploadedAt = text(e, "uploaded_at");
            out.add(rec);
        }

        sortAttachments(out);
        return out;
    }

    private static void writeAttachmentsLocked(String tenantUuid, String ticketUuid, List<AttachmentRec> rows) throws Exception {
        Path p = attachmentsPath(tenantUuid, ticketUuid);
        Files.createDirectories(p.getParent());

        StringBuilder sb = new StringBuilder(2048);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<attachments ticket_uuid=\"").append(xmlAttr(ticketUuid)).append("\" updated=\"").append(xmlAttr(nowIso())).append("\">\n");

        List<AttachmentRec> src = rows == null ? List.of() : rows;
        for (int i = 0; i < src.size(); i++) {
            AttachmentRec rec = src.get(i);
            if (rec == null) continue;
            String id = safe(rec.uuid).trim();
            if (id.isBlank()) id = UUID.randomUUID().toString();

            sb.append("  <attachment>\n");
            writeTag(sb, "uuid", id);
            writeTag(sb, "message_uuid", safe(rec.messageUuid).trim());
            writeTag(sb, "file_name", sanitizeFileName(rec.fileName));
            writeTag(sb, "mime_type", safe(rec.mimeType).trim());
            writeTag(sb, "file_size_bytes", safe(rec.fileSizeBytes).trim());
            writeTag(sb, "checksum_sha256", safe(rec.checksumSha256).trim());
            writeTag(sb, "storage_file", safe(rec.storageFile).trim());
            writeTag(sb, "inline_media", rec.inlineMedia ? "true" : "false");
            writeTag(sb, "uploaded_by", safe(rec.uploadedBy).trim());
            writeTag(sb, "uploaded_at", safe(rec.uploadedAt).trim());
            sb.append("  </attachment>\n");
        }

        sb.append("</attachments>\n");
        writeAtomic(p, sb.toString());
    }

    private static List<AssignmentRec> readAssignmentsLocked(String tenantUuid, String ticketUuid) throws Exception {
        ArrayList<AssignmentRec> out = new ArrayList<AssignmentRec>();
        Path p = assignmentsPath(tenantUuid, ticketUuid);
        if (!Files.exists(p)) return out;

        Document d = parseXml(p);
        Element root = d == null ? null : d.getDocumentElement();
        if (root == null) return out;

        NodeList nl = root.getElementsByTagName("assignment");
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (!(n instanceof Element)) continue;
            Element e = (Element) n;

            AssignmentRec rec = new AssignmentRec();
            rec.uuid = text(e, "uuid");
            if (safe(rec.uuid).trim().isBlank()) rec.uuid = UUID.randomUUID().toString();
            rec.ticketUuid = safe(ticketUuid).trim();
            rec.mode = canonicalAssignmentMode(text(e, "mode"));
            rec.fromUserUuid = text(e, "from_user_uuid");
            rec.toUserUuid = text(e, "to_user_uuid");
            rec.reason = text(e, "reason");
            rec.changedBy = text(e, "changed_by");
            rec.changedAt = text(e, "changed_at");
            out.add(rec);
        }

        sortAssignments(out);
        return out;
    }

    private static void writeAssignmentsLocked(String tenantUuid, String ticketUuid, List<AssignmentRec> rows) throws Exception {
        Path p = assignmentsPath(tenantUuid, ticketUuid);
        Files.createDirectories(p.getParent());

        StringBuilder sb = new StringBuilder(1024);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<assignments ticket_uuid=\"").append(xmlAttr(ticketUuid)).append("\" updated=\"").append(xmlAttr(nowIso())).append("\">\n");

        List<AssignmentRec> src = rows == null ? List.of() : rows;
        for (int i = 0; i < src.size(); i++) {
            AssignmentRec rec = src.get(i);
            if (rec == null) continue;
            String id = safe(rec.uuid).trim();
            if (id.isBlank()) id = UUID.randomUUID().toString();

            sb.append("  <assignment>\n");
            writeTag(sb, "uuid", id);
            writeTag(sb, "mode", canonicalAssignmentMode(rec.mode));
            writeTag(sb, "from_user_uuid", safe(rec.fromUserUuid).trim());
            writeTag(sb, "to_user_uuid", safe(rec.toUserUuid).trim());
            writeTag(sb, "reason", safe(rec.reason).trim());
            writeTag(sb, "changed_by", safe(rec.changedBy).trim());
            writeTag(sb, "changed_at", safe(rec.changedAt).trim());
            sb.append("  </assignment>\n");
        }

        sb.append("</assignments>\n");
        writeAtomic(p, sb.toString());
    }

    private static Map<String, Integer> readRoundRobinStateLocked(String tenantUuid) throws Exception {
        LinkedHashMap<String, Integer> out = new LinkedHashMap<String, Integer>();
        Path p = roundRobinStatePath(tenantUuid);
        if (!Files.exists(p)) return out;

        Document d = parseXml(p);
        Element root = d == null ? null : d.getDocumentElement();
        if (root == null) return out;

        NodeList nl = root.getElementsByTagName("cursor");
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (!(n instanceof Element)) continue;
            Element e = (Element) n;
            String key = safe(e.getAttribute("key")).trim();
            if (key.isBlank()) continue;
            int v = parseInt(safe(e.getTextContent()), -1);
            out.put(key, Integer.valueOf(v));
        }
        return out;
    }

    private static void writeRoundRobinStateLocked(String tenantUuid, Map<String, Integer> state) throws Exception {
        Path p = roundRobinStatePath(tenantUuid);
        Files.createDirectories(p.getParent());

        StringBuilder sb = new StringBuilder(512);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<roundRobinState updated=\"").append(xmlAttr(nowIso())).append("\">\n");

        if (state != null && !state.isEmpty()) {
            List<String> keys = new ArrayList<String>(state.keySet());
            keys.sort(String::compareToIgnoreCase);
            for (int i = 0; i < keys.size(); i++) {
                String key = safe(keys.get(i)).trim();
                if (key.isBlank()) continue;
                Integer v = state.get(key);
                int idx = v == null ? -1 : v.intValue();
                sb.append("  <cursor key=\"").append(xmlAttr(key)).append("\">")
                        .append(idx)
                        .append("</cursor>\n");
            }
        }

        sb.append("</roundRobinState>\n");
        writeAtomic(p, sb.toString());
    }

    private static TicketRec findTicketByUuid(List<TicketRec> rows, String ticketUuid) {
        List<TicketRec> src = rows == null ? List.of() : rows;
        String id = safe(ticketUuid).trim();
        if (id.isBlank()) return null;
        for (int i = 0; i < src.size(); i++) {
            TicketRec rec = src.get(i);
            if (rec == null) continue;
            if (id.equals(safe(rec.uuid).trim())) return rec;
        }
        return null;
    }

    private static void sortTickets(List<TicketRec> rows) {
        if (rows == null) return;
        rows.sort(new Comparator<TicketRec>() {
            @Override
            public int compare(TicketRec a, TicketRec b) {
                String au = safe(a == null ? "" : a.updatedAt);
                String bu = safe(b == null ? "" : b.updatedAt);
                int cmp = bu.compareTo(au);
                if (cmp != 0) return cmp;
                String as = safe(a == null ? "" : a.subject).toLowerCase(Locale.ROOT);
                String bs = safe(b == null ? "" : b.subject).toLowerCase(Locale.ROOT);
                return as.compareTo(bs);
            }
        });
    }

    private static void sortMessages(List<MessageRec> rows) {
        if (rows == null) return;
        rows.sort(new Comparator<MessageRec>() {
            @Override
            public int compare(MessageRec a, MessageRec b) {
                String ad = safe(a == null ? "" : a.createdAt);
                String bd = safe(b == null ? "" : b.createdAt);
                int cmp = bd.compareTo(ad);
                if (cmp != 0) return cmp;
                return safe(a == null ? "" : a.uuid).compareTo(safe(b == null ? "" : b.uuid));
            }
        });
    }

    private static void sortAttachments(List<AttachmentRec> rows) {
        if (rows == null) return;
        rows.sort(new Comparator<AttachmentRec>() {
            @Override
            public int compare(AttachmentRec a, AttachmentRec b) {
                String ad = safe(a == null ? "" : a.uploadedAt);
                String bd = safe(b == null ? "" : b.uploadedAt);
                int cmp = bd.compareTo(ad);
                if (cmp != 0) return cmp;
                return safe(a == null ? "" : a.fileName).compareToIgnoreCase(safe(b == null ? "" : b.fileName));
            }
        });
    }

    private static void sortAssignments(List<AssignmentRec> rows) {
        if (rows == null) return;
        rows.sort(new Comparator<AssignmentRec>() {
            @Override
            public int compare(AssignmentRec a, AssignmentRec b) {
                String ad = safe(a == null ? "" : a.changedAt);
                String bd = safe(b == null ? "" : b.changedAt);
                int cmp = bd.compareTo(ad);
                if (cmp != 0) return cmp;
                return safe(a == null ? "" : a.uuid).compareTo(safe(b == null ? "" : b.uuid));
            }
        });
    }

    private static String mapDocumentStatus(String ticketStatus) {
        String s = canonicalStatus(ticketStatus);
        if (STATUS_CLOSED.equals(s) || STATUS_RESOLVED.equals(s)) return "final";
        if (STATUS_ON_HOLD.equals(s)) return "on_hold";
        if (STATUS_PENDING_CUSTOMER.equals(s) || STATUS_PENDING_INTERNAL.equals(s)) return "pending";
        return "draft";
    }

    private static String canonicalChannel(String raw) {
        String s = document_workflow_support.normalizeToken(raw);
        if (CHANNEL_FLOWROUTE_SMS.equals(s)) return CHANNEL_FLOWROUTE_SMS;
        if (CHANNEL_EMAIL_IMAP_SMTP.equals(s)) return CHANNEL_EMAIL_IMAP_SMTP;
        if (CHANNEL_EMAIL_GRAPH_USER.equals(s)) return CHANNEL_EMAIL_GRAPH_USER;
        if (CHANNEL_EMAIL_GRAPH_SHARED.equals(s)) return CHANNEL_EMAIL_GRAPH_SHARED;
        return CHANNEL_EMAIL_IMAP_SMTP;
    }

    private static String canonicalStatus(String raw) {
        String s = document_workflow_support.normalizeToken(raw);
        if (STATUS_OPEN.equals(s)) return STATUS_OPEN;
        if (STATUS_PENDING_CUSTOMER.equals(s)) return STATUS_PENDING_CUSTOMER;
        if (STATUS_PENDING_INTERNAL.equals(s)) return STATUS_PENDING_INTERNAL;
        if (STATUS_ON_HOLD.equals(s)) return STATUS_ON_HOLD;
        if (STATUS_RESOLVED.equals(s)) return STATUS_RESOLVED;
        if (STATUS_CLOSED.equals(s)) return STATUS_CLOSED;
        return STATUS_OPEN;
    }

    private static String canonicalPriority(String raw) {
        String s = document_workflow_support.normalizeToken(raw);
        if (PRIORITY_LOW.equals(s)) return PRIORITY_LOW;
        if (PRIORITY_NORMAL.equals(s)) return PRIORITY_NORMAL;
        if (PRIORITY_HIGH.equals(s)) return PRIORITY_HIGH;
        if (PRIORITY_URGENT.equals(s)) return PRIORITY_URGENT;
        return PRIORITY_NORMAL;
    }

    private static String canonicalAssignmentMode(String raw) {
        String s = document_workflow_support.normalizeToken(raw);
        if (MODE_ROUND_ROBIN.equals(s)) return MODE_ROUND_ROBIN;
        return MODE_MANUAL;
    }

    private static String canonicalDirection(String raw) {
        String s = document_workflow_support.normalizeToken(raw);
        if (DIRECTION_INBOUND.equals(s)) return DIRECTION_INBOUND;
        if (DIRECTION_OUTBOUND.equals(s)) return DIRECTION_OUTBOUND;
        if (DIRECTION_INTERNAL.equals(s)) return DIRECTION_INTERNAL;
        if (DIRECTION_SYSTEM.equals(s)) return DIRECTION_SYSTEM;
        return DIRECTION_INTERNAL;
    }

    private static Path ticketsRoot(String tenantUuid) {
        return Paths.get("data", "tenants", safeToken(tenantUuid), "communications", "tickets").toAbsolutePath();
    }

    private static Path ticketsDir(String tenantUuid) {
        return ticketsRoot(tenantUuid).resolve("tickets");
    }

    private static Path ticketsPath(String tenantUuid) {
        return ticketsRoot(tenantUuid).resolve("tickets.xml");
    }

    private static Path roundRobinStatePath(String tenantUuid) {
        return ticketsRoot(tenantUuid).resolve("round_robin_state.xml");
    }

    private static Path ticketDir(String tenantUuid, String ticketUuid) {
        return ticketsDir(tenantUuid).resolve(safeToken(ticketUuid));
    }

    private static Path messagesPath(String tenantUuid, String ticketUuid) {
        return ticketDir(tenantUuid, ticketUuid).resolve("messages.xml");
    }

    private static Path attachmentsPath(String tenantUuid, String ticketUuid) {
        return ticketDir(tenantUuid, ticketUuid).resolve("attachments.xml");
    }

    private static Path assignmentsPath(String tenantUuid, String ticketUuid) {
        return ticketDir(tenantUuid, ticketUuid).resolve("assignments.xml");
    }

    private static Path attachmentFilesDir(String tenantUuid, String ticketUuid) {
        return ticketDir(tenantUuid, ticketUuid).resolve("attachment_files");
    }

    private static Path attachmentPath(String tenantUuid, String ticketUuid, AttachmentRec attachment) {
        if (attachment == null) return null;
        String storage = safe(attachment.storageFile).trim();
        if (!storage.matches("[A-Za-z0-9._-]+")) return null;
        if (storage.isBlank()) return null;
        Path root = attachmentFilesDir(tenantUuid, ticketUuid).toAbsolutePath().normalize();
        Path candidate = root.resolve(storage).normalize();
        if (!candidate.startsWith(root)) return null;
        return candidate;
    }

    private static ReentrantReadWriteLock lockFor(String tenantUuid) {
        String key = safeToken(tenantUuid);
        return LOCKS.computeIfAbsent(key, x -> new ReentrantReadWriteLock());
    }

    private static Document parseXml(Path p) throws Exception {
        return document_workflow_support.parseXml(p);
    }

    private static String text(Element parent, String childTag) {
        return document_workflow_support.text(parent, childTag);
    }

    private static void writeAtomic(Path p, String content) throws Exception {
        document_workflow_support.writeAtomic(p, content);
    }

    private static void writeTag(StringBuilder sb, String tag, String value) {
        sb.append("    <").append(tag).append(">")
                .append(xmlText(value))
                .append("</").append(tag).append(">\n");
    }

    private static String xmlText(String s) {
        return document_workflow_support.xmlText(s);
    }

    private static String xmlAttr(String s) {
        return document_workflow_support.xmlText(s);
    }

    private static String nowIso() {
        return Instant.now().toString();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String safeToken(String raw) {
        String s = safe(raw).trim();
        if (s.isBlank()) return "";
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static boolean parseBool(String raw, boolean fallback) {
        String s = safe(raw).trim().toLowerCase(Locale.ROOT);
        if ("true".equals(s) || "1".equals(s) || "yes".equals(s) || "on".equals(s)) return true;
        if ("false".equals(s) || "0".equals(s) || "no".equals(s) || "off".equals(s)) return false;
        return fallback;
    }

    private static int parseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(safe(raw).trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String sanitizeFileName(String raw) {
        String s = safe(raw).trim().replaceAll("[\\r\\n]+", " ");
        s = s.replaceAll("[^A-Za-z0-9._-]", "_");
        while (s.contains("__")) s = s.replace("__", "_");
        while (s.startsWith(".")) s = s.substring(1);
        if (s.length() > 180) s = s.substring(0, 180);
        if (s.isBlank()) return "attachment.bin";
        return s;
    }

    private static String clampLen(String raw, int max) {
        String s = safe(raw);
        int lim = Math.max(1, max);
        if (s.length() <= lim) return s;
        return s.substring(0, lim);
    }

    private static String normalizeAssignmentCsv(String raw) {
        String csv = clampLen(raw, MAX_ASSIGNMENT_LEN);
        if (csv.isBlank()) return "";
        String[] parts = csv.split(",");
        LinkedHashSet<String> out = new LinkedHashSet<String>();
        for (int i = 0; i < parts.length; i++) {
            String id = safe(parts[i]).trim();
            if (id.isBlank()) continue;
            out.add(id);
        }
        return String.join(",", out);
    }

    private static void audit(String action,
                              String tenantUuid,
                              String actor,
                              String matterUuid,
                              Map<String, String> details) {
        try {
            activity_log.defaultStore().logVerbose(
                    safe(action).isBlank() ? "omnichannel.event" : safe(action),
                    safe(tenantUuid),
                    safe(actor),
                    safe(matterUuid),
                    "",
                    details == null ? Map.of() : details
            );
        } catch (Exception ex) {
            LOG.log(Level.FINE, "Omnichannel audit log failure: " + safe(ex.getMessage()), ex);
        }
    }

    private static void publishThreadEvent(String tenantUuid,
                                           String eventType,
                                           TicketRec ticket,
                                           Map<String, String> extraPayload) {
        if (ticket == null) return;
        try {
            LinkedHashMap<String, String> payload = new LinkedHashMap<String, String>();
            payload.put("thread_uuid", safe(ticket.uuid));
            payload.put("matter_uuid", safe(ticket.matterUuid));
            payload.put("channel", safe(ticket.channel));
            payload.put("subject", safe(ticket.subject));
            payload.put("status", safe(ticket.status));
            payload.put("priority", safe(ticket.priority));
            payload.put("assignment_mode", safe(ticket.assignmentMode));
            payload.put("assigned_user_uuid", safe(ticket.assignedUserUuid));
            payload.put("updated_at", safe(ticket.updatedAt));
            payload.put("reminder_at", safe(ticket.reminderAt));
            payload.put("due_at", safe(ticket.dueAt));
            if (extraPayload != null) {
                for (Map.Entry<String, String> e : extraPayload.entrySet()) {
                    if (e == null) continue;
                    String k = safe(e.getKey()).trim();
                    if (k.isBlank()) continue;
                    payload.put(k, safe(e.getValue()));
                }
            }

            business_process_manager.defaultService().triggerEvent(
                    safe(tenantUuid),
                    safe(eventType),
                    payload,
                    "",
                    "omnichannel_tickets.store"
            );
        } catch (Exception ex) {
            LOG.log(Level.FINE, "Omnichannel BPM event publish failure: " + safe(ex.getMessage()), ex);
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(bytes == null ? new byte[0] : bytes);
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (int i = 0; i < hash.length; i++) {
            sb.append(String.format(Locale.ROOT, "%02x", hash[i] & 0xff));
        }
        return sb.toString();
    }
}
