package net.familylawandprobate.controversies;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * postal_mail
 *
 * Tenant-scoped intake + outbound postal/courier workflow metadata.
 *
 * Layout:
 *   data/tenants/{tenantUuid}/mail/mail_items.xml
 *   data/tenants/{tenantUuid}/mail/items/{mailUuid}/mail_parts.xml
 *   data/tenants/{tenantUuid}/mail/items/{mailUuid}/mail_recipients.xml
 *   data/tenants/{tenantUuid}/mail/items/{mailUuid}/mail_tracking.xml
 */
public final class postal_mail {

    public static final String DIRECTION_INBOUND = "inbound";
    public static final String DIRECTION_OUTBOUND = "outbound";

    public static final String WORKFLOW_MANUAL = "manual";
    public static final String WORKFLOW_CLICK2MAIL = "click2mail";
    public static final String WORKFLOW_FEDEX = "fedex";
    public static final String WORKFLOW_EMAIL_INBOX = "email_inbox";
    public static final String WORKFLOW_MANUAL_SCAN = "manual_scan";

    public static final String SERVICE_USPS = "usps";
    public static final String SERVICE_FEDEX = "fedex";
    public static final String SERVICE_UPS = "ups";
    public static final String SERVICE_COURIER = "courier";
    public static final String SERVICE_OTHER = "other";

    public static final String STATUS_RECEIVED = "received";
    public static final String STATUS_REVIEW_PENDING = "review_pending";
    public static final String STATUS_REVIEWED = "reviewed";
    public static final String STATUS_UPLOADED = "uploaded";
    public static final String STATUS_READY_TO_SEND = "ready_to_send";
    public static final String STATUS_QUEUED = "queued";
    public static final String STATUS_SENT = "sent";
    public static final String STATUS_IN_TRANSIT = "in_transit";
    public static final String STATUS_DELIVERED = "delivered";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_CANCELLED = "cancelled";

    public static final String PART_ENVELOPE = "envelope";
    public static final String PART_LETTER = "letter";
    public static final String PART_ATTACHMENT = "attachment";
    public static final String PART_RECEIPT = "receipt";
    public static final String PART_PROOF = "proof";
    public static final String PART_TRACKING = "tracking";
    public static final String PART_LABEL = "label";
    public static final String PART_OTHER = "other";

    public static final String ADDRESS_STATUS_UNKNOWN = "unknown";
    public static final String ADDRESS_STATUS_VALID = "valid";
    public static final String ADDRESS_STATUS_INVALID = "invalid";

    private static final ConcurrentHashMap<String, ReentrantReadWriteLock> LOCKS = new ConcurrentHashMap<String, ReentrantReadWriteLock>();

    public static final class MailItemRec {
        public String uuid;
        public String matterUuid;
        public String direction;
        public String workflow;
        public String service;
        public String status;
        public String subject;
        public String notes;

        public String sourceEmailAddress;
        public String sourceDocumentUuid;
        public String sourcePartUuid;
        public String sourceVersionUuid;

        public String filedDocumentUuid;
        public String filedPartUuid;
        public String filedVersionUuid;

        public String trackingCarrier;
        public String trackingNumber;
        public String trackingStatus;

        public String providerReference;
        public String providerMessage;
        public String providerRequestJson;
        public String providerResponseJson;

        public String addressValidationStatus;

        public String createdBy;
        public String createdAt;
        public String updatedAt;
        public String receivedAt;
        public String sentAt;
        public String reviewedBy;
        public String reviewedAt;
        public boolean archived;
    }

    public static final class MailPartRec {
        public String uuid;
        public String mailUuid;
        public String partType;
        public String label;
        public String documentUuid;
        public String partUuid;
        public String versionUuid;
        public String notes;
        public String createdBy;
        public String createdAt;
        public String updatedAt;
        public boolean trashed;
    }

    public static final class RecipientRec {
        public String uuid;
        public String mailUuid;
        public String contactUuid;
        public String displayName;
        public String companyName;
        public String addressLine1;
        public String addressLine2;
        public String city;
        public String state;
        public String postalCode;
        public String country;
        public String emailAddress;
        public String phone;
        public boolean validated;
        public String validationMessage;
        public String createdAt;
        public String updatedAt;
    }

    public static final class TrackingEventRec {
        public String uuid;
        public String mailUuid;
        public String carrier;
        public String trackingNumber;
        public String status;
        public String location;
        public String eventAt;
        public String notes;
        public String source;
        public String createdAt;
    }

    public static final class AddressInput {
        public String displayName = "";
        public String companyName = "";
        public String addressLine1 = "";
        public String addressLine2 = "";
        public String city = "";
        public String state = "";
        public String postalCode = "";
        public String country = "";
        public String emailAddress = "";
        public String phone = "";
    }

    public static final class AddressValidationResult {
        public boolean valid;
        public String message;
        public String normalizedDisplayName;
        public String normalizedCompanyName;
        public String normalizedAddressLine1;
        public String normalizedAddressLine2;
        public String normalizedCity;
        public String normalizedState;
        public String normalizedPostalCode;
        public String normalizedCountry;
        public String normalizedEmailAddress;
        public String normalizedPhone;
        public ArrayList<String> errors = new ArrayList<String>();
        public ArrayList<String> warnings = new ArrayList<String>();
    }

    public static final class TrackingValidationResult {
        public boolean valid;
        public String message;
        public String normalizedTrackingNumber;
        public String carrierHint;
    }

    public static postal_mail defaultStore() {
        return new postal_mail();
    }

    public void ensure(String tenantUuid) throws Exception {
        String tu = safeFileToken(tenantUuid);
        if (tu.isBlank()) throw new IllegalArgumentException("tenantUuid required");

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            Path root = mailRoot(tu);
            Files.createDirectories(root);
            Files.createDirectories(itemsDir(tu));
            Path items = itemsPath(tu);
            if (!Files.exists(items)) {
                document_workflow_support.writeAtomic(
                        items,
                        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<mail_items updated=\""
                                + document_workflow_support.xmlText(nowIso()) + "\"></mail_items>\n"
                );
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<MailItemRec> listItems(String tenantUuid) throws Exception {
        String tu = safeFileToken(tenantUuid);
        if (tu.isBlank()) return List.of();
        ensure(tu);

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.readLock().lock();
        try {
            return readItemsLocked(tu);
        } finally {
            lock.readLock().unlock();
        }
    }

    public MailItemRec getItem(String tenantUuid, String mailUuid) throws Exception {
        String id = safe(mailUuid).trim();
        if (id.isBlank()) return null;
        List<MailItemRec> all = listItems(tenantUuid);
        for (MailItemRec rec : all) {
            if (rec == null) continue;
            if (id.equals(safe(rec.uuid).trim())) return rec;
        }
        return null;
    }

    public MailItemRec createItem(String tenantUuid,
                                  String matterUuid,
                                  String direction,
                                  String workflow,
                                  String service,
                                  String status,
                                  String subject,
                                  String notes,
                                  String sourceEmailAddress,
                                  String sourceDocumentUuid,
                                  String sourcePartUuid,
                                  String sourceVersionUuid,
                                  String createdBy) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String mu = safe(matterUuid).trim();
        if (tu.isBlank()) throw new IllegalArgumentException("tenantUuid required");
        if (mu.isBlank()) throw new IllegalArgumentException("matter_uuid is required.");

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensure(tu);
            List<MailItemRec> all = readItemsLocked(tu);

            MailItemRec rec = new MailItemRec();
            rec.uuid = UUID.randomUUID().toString();
            rec.matterUuid = mu;
            rec.direction = canonicalDirection(direction);
            rec.workflow = canonicalWorkflow(workflow);
            rec.service = canonicalService(service, rec.workflow);
            rec.status = canonicalStatus(status, rec.direction);
            rec.subject = clampLen(subject, 500).trim();
            rec.notes = clampLen(notes, 8000).trim();
            rec.sourceEmailAddress = clampLen(sourceEmailAddress, 320).trim();
            rec.sourceDocumentUuid = safe(sourceDocumentUuid).trim();
            rec.sourcePartUuid = safe(sourcePartUuid).trim();
            rec.sourceVersionUuid = safe(sourceVersionUuid).trim();

            rec.filedDocumentUuid = "";
            rec.filedPartUuid = "";
            rec.filedVersionUuid = "";

            rec.trackingCarrier = "";
            rec.trackingNumber = "";
            rec.trackingStatus = "";

            rec.providerReference = "";
            rec.providerMessage = "";
            rec.providerRequestJson = "";
            rec.providerResponseJson = "";

            rec.addressValidationStatus = ADDRESS_STATUS_UNKNOWN;

            rec.createdBy = clampLen(createdBy, 200).trim();
            rec.createdAt = nowIso();
            rec.updatedAt = rec.createdAt;
            rec.receivedAt = DIRECTION_INBOUND.equals(rec.direction) ? rec.createdAt : "";
            rec.sentAt = "";
            rec.reviewedBy = "";
            rec.reviewedAt = "";
            rec.archived = false;

            all.add(rec);
            sortItems(all);
            writeItemsLocked(tu, all);
            ensureItemFilesLocked(tu, rec.uuid);
            return findItem(all, rec.uuid);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean updateItem(String tenantUuid, MailItemRec in) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String id = safe(in == null ? "" : in.uuid).trim();
        if (tu.isBlank() || id.isBlank()) return false;

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensure(tu);
            List<MailItemRec> all = readItemsLocked(tu);
            boolean changed = false;
            for (MailItemRec rec : all) {
                if (rec == null) continue;
                if (!id.equals(safe(rec.uuid).trim())) continue;

                String nextDirection = canonicalDirection(in.direction);
                String nextWorkflow = canonicalWorkflow(in.workflow);
                String nextService = canonicalService(in.service, nextWorkflow);
                String nextStatus = canonicalStatus(in.status, nextDirection);
                String nextTracking = canonicalTracking(in.trackingNumber);
                if (!nextTracking.isBlank()) {
                    TrackingValidationResult tv = validateTrackingNumber(nextTracking);
                    if (!tv.valid) throw new IllegalArgumentException(tv.message);
                }

                rec.matterUuid = safe(in.matterUuid).trim();
                rec.direction = nextDirection;
                rec.workflow = nextWorkflow;
                rec.service = nextService;
                rec.status = nextStatus;
                rec.subject = clampLen(in.subject, 500).trim();
                rec.notes = clampLen(in.notes, 8000).trim();
                rec.sourceEmailAddress = clampLen(in.sourceEmailAddress, 320).trim();
                rec.sourceDocumentUuid = safe(in.sourceDocumentUuid).trim();
                rec.sourcePartUuid = safe(in.sourcePartUuid).trim();
                rec.sourceVersionUuid = safe(in.sourceVersionUuid).trim();
                rec.filedDocumentUuid = safe(in.filedDocumentUuid).trim();
                rec.filedPartUuid = safe(in.filedPartUuid).trim();
                rec.filedVersionUuid = safe(in.filedVersionUuid).trim();
                rec.trackingCarrier = canonicalCarrier(in.trackingCarrier);
                rec.trackingNumber = nextTracking;
                rec.trackingStatus = clampLen(in.trackingStatus, 120).trim();
                rec.providerReference = clampLen(in.providerReference, 500).trim();
                rec.providerMessage = clampLen(in.providerMessage, 5000).trim();
                rec.providerRequestJson = clampLen(in.providerRequestJson, 500000).trim();
                rec.providerResponseJson = clampLen(in.providerResponseJson, 500000).trim();
                rec.addressValidationStatus = canonicalAddressValidationStatus(in.addressValidationStatus);
                rec.receivedAt = safe(in.receivedAt).trim();
                rec.sentAt = safe(in.sentAt).trim();
                rec.reviewedBy = clampLen(in.reviewedBy, 200).trim();
                rec.reviewedAt = safe(in.reviewedAt).trim();
                rec.archived = in.archived;
                rec.updatedAt = nowIso();
                changed = true;
                break;
            }
            if (changed) {
                sortItems(all);
                writeItemsLocked(tu, all);
            }
            return changed;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean setArchived(String tenantUuid, String mailUuid, boolean archived) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String id = safe(mailUuid).trim();
        if (tu.isBlank() || id.isBlank()) return false;

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensure(tu);
            List<MailItemRec> all = readItemsLocked(tu);
            boolean changed = false;
            for (MailItemRec rec : all) {
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
                sortItems(all);
                writeItemsLocked(tu, all);
            }
            return changed;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean markReviewed(String tenantUuid,
                                String mailUuid,
                                String reviewedBy,
                                String reviewNotes) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String id = safe(mailUuid).trim();
        if (tu.isBlank() || id.isBlank()) return false;

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensure(tu);
            List<MailItemRec> all = readItemsLocked(tu);
            boolean changed = false;
            for (MailItemRec rec : all) {
                if (rec == null) continue;
                if (!id.equals(safe(rec.uuid).trim())) continue;
                rec.reviewedBy = clampLen(reviewedBy, 200).trim();
                rec.reviewedAt = nowIso();
                if (DIRECTION_INBOUND.equals(rec.direction)) {
                    rec.status = STATUS_REVIEWED;
                }
                String notes = clampLen(reviewNotes, 8000).trim();
                if (!notes.isBlank()) {
                    rec.notes = rec.notes.isBlank() ? notes : (rec.notes + "\n\n" + notes);
                }
                rec.updatedAt = nowIso();
                changed = true;
                break;
            }
            if (changed) {
                sortItems(all);
                writeItemsLocked(tu, all);
            }
            return changed;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean linkFiledDocument(String tenantUuid,
                                     String mailUuid,
                                     String filedDocumentUuid,
                                     String filedPartUuid,
                                     String filedVersionUuid) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String id = safe(mailUuid).trim();
        if (tu.isBlank() || id.isBlank()) return false;

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensure(tu);
            List<MailItemRec> all = readItemsLocked(tu);
            boolean changed = false;
            for (MailItemRec rec : all) {
                if (rec == null) continue;
                if (!id.equals(safe(rec.uuid).trim())) continue;
                rec.filedDocumentUuid = safe(filedDocumentUuid).trim();
                rec.filedPartUuid = safe(filedPartUuid).trim();
                rec.filedVersionUuid = safe(filedVersionUuid).trim();
                if (DIRECTION_INBOUND.equals(rec.direction) && !rec.filedDocumentUuid.isBlank()) {
                    rec.status = STATUS_UPLOADED;
                }
                rec.updatedAt = nowIso();
                changed = true;
                break;
            }
            if (changed) {
                sortItems(all);
                writeItemsLocked(tu, all);
            }
            return changed;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<MailPartRec> listParts(String tenantUuid, String mailUuid) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String id = safe(mailUuid).trim();
        if (tu.isBlank() || id.isBlank()) return List.of();
        ensure(tu);

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.readLock().lock();
        try {
            return readPartsLocked(tu, id);
        } finally {
            lock.readLock().unlock();
        }
    }

    public MailPartRec addPart(String tenantUuid,
                               String mailUuid,
                               String partType,
                               String label,
                               String documentUuid,
                               String partUuid,
                               String versionUuid,
                               String notes,
                               String actor) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String id = safe(mailUuid).trim();
        if (tu.isBlank()) throw new IllegalArgumentException("tenantUuid required");
        if (id.isBlank()) throw new IllegalArgumentException("mail_uuid is required.");

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensure(tu);
            if (findItem(readItemsLocked(tu), id) == null) throw new IllegalArgumentException("Mail item not found.");
            ensureItemFilesLocked(tu, id);
            List<MailPartRec> all = readPartsLocked(tu, id);
            MailPartRec rec = new MailPartRec();
            rec.uuid = UUID.randomUUID().toString();
            rec.mailUuid = id;
            rec.partType = canonicalPartType(partType);
            rec.label = clampLen(label, 300).trim();
            rec.documentUuid = safe(documentUuid).trim();
            rec.partUuid = safe(partUuid).trim();
            rec.versionUuid = safe(versionUuid).trim();
            rec.notes = clampLen(notes, 4000).trim();
            rec.createdBy = clampLen(actor, 200).trim();
            rec.createdAt = nowIso();
            rec.updatedAt = rec.createdAt;
            rec.trashed = false;
            all.add(rec);
            sortParts(all);
            writePartsLocked(tu, id, all);
            touchItemLocked(tu, id);
            return findPart(all, rec.uuid);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean setPartTrashed(String tenantUuid, String mailUuid, String partUuid, boolean trashed) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String mid = safe(mailUuid).trim();
        String pid = safe(partUuid).trim();
        if (tu.isBlank() || mid.isBlank() || pid.isBlank()) return false;

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensure(tu);
            ensureItemFilesLocked(tu, mid);
            List<MailPartRec> all = readPartsLocked(tu, mid);
            boolean changed = false;
            for (MailPartRec rec : all) {
                if (rec == null) continue;
                if (!pid.equals(safe(rec.uuid).trim())) continue;
                if (rec.trashed != trashed) {
                    rec.trashed = trashed;
                    rec.updatedAt = nowIso();
                    changed = true;
                }
                break;
            }
            if (changed) {
                sortParts(all);
                writePartsLocked(tu, mid, all);
                touchItemLocked(tu, mid);
            }
            return changed;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<RecipientRec> listRecipients(String tenantUuid, String mailUuid) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String id = safe(mailUuid).trim();
        if (tu.isBlank() || id.isBlank()) return List.of();
        ensure(tu);

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.readLock().lock();
        try {
            return readRecipientsLocked(tu, id);
        } finally {
            lock.readLock().unlock();
        }
    }

    public RecipientRec addRecipient(String tenantUuid,
                                     String mailUuid,
                                     RecipientRec input,
                                     boolean allowInvalidAddress) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String mid = safe(mailUuid).trim();
        if (tu.isBlank()) throw new IllegalArgumentException("tenantUuid required");
        if (mid.isBlank()) throw new IllegalArgumentException("mail_uuid is required.");

        RecipientRec in = input == null ? new RecipientRec() : input;
        AddressValidationResult validated = validateAddress(addressFromRecipient(in));
        if (!allowInvalidAddress && !validated.valid) {
            throw new IllegalArgumentException(validated.message);
        }

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensure(tu);
            if (findItem(readItemsLocked(tu), mid) == null) throw new IllegalArgumentException("Mail item not found.");
            ensureItemFilesLocked(tu, mid);

            List<RecipientRec> all = readRecipientsLocked(tu, mid);
            RecipientRec rec = new RecipientRec();
            rec.uuid = UUID.randomUUID().toString();
            rec.mailUuid = mid;
            rec.contactUuid = safe(in.contactUuid).trim();
            rec.displayName = validated.normalizedDisplayName;
            rec.companyName = validated.normalizedCompanyName;
            rec.addressLine1 = validated.normalizedAddressLine1;
            rec.addressLine2 = validated.normalizedAddressLine2;
            rec.city = validated.normalizedCity;
            rec.state = validated.normalizedState;
            rec.postalCode = validated.normalizedPostalCode;
            rec.country = validated.normalizedCountry;
            rec.emailAddress = validated.normalizedEmailAddress;
            rec.phone = validated.normalizedPhone;
            rec.validated = validated.valid;
            rec.validationMessage = clampLen(validated.message, 600).trim();
            rec.createdAt = nowIso();
            rec.updatedAt = rec.createdAt;
            all.add(rec);
            sortRecipients(all);
            writeRecipientsLocked(tu, mid, all);
            touchItemLocked(tu, mid);
            updateItemAddressValidationStatusLocked(tu, mid, all);
            return findRecipient(all, rec.uuid);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean replaceRecipients(String tenantUuid,
                                     String mailUuid,
                                     List<RecipientRec> replacements,
                                     boolean allowInvalidAddress) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String mid = safe(mailUuid).trim();
        if (tu.isBlank() || mid.isBlank()) return false;

        ArrayList<RecipientRec> clean = new ArrayList<RecipientRec>();
        List<RecipientRec> source = replacements == null ? List.of() : replacements;
        for (RecipientRec in : source) {
            if (in == null) continue;
            AddressValidationResult validated = validateAddress(addressFromRecipient(in));
            if (!allowInvalidAddress && !validated.valid) {
                throw new IllegalArgumentException(validated.message);
            }
            RecipientRec rec = new RecipientRec();
            rec.uuid = safe(in.uuid).trim();
            if (rec.uuid.isBlank()) rec.uuid = UUID.randomUUID().toString();
            rec.mailUuid = mid;
            rec.contactUuid = safe(in.contactUuid).trim();
            rec.displayName = validated.normalizedDisplayName;
            rec.companyName = validated.normalizedCompanyName;
            rec.addressLine1 = validated.normalizedAddressLine1;
            rec.addressLine2 = validated.normalizedAddressLine2;
            rec.city = validated.normalizedCity;
            rec.state = validated.normalizedState;
            rec.postalCode = validated.normalizedPostalCode;
            rec.country = validated.normalizedCountry;
            rec.emailAddress = validated.normalizedEmailAddress;
            rec.phone = validated.normalizedPhone;
            rec.validated = validated.valid;
            rec.validationMessage = clampLen(validated.message, 600).trim();
            rec.createdAt = nowIso();
            rec.updatedAt = rec.createdAt;
            clean.add(rec);
        }
        sortRecipients(clean);

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensure(tu);
            if (findItem(readItemsLocked(tu), mid) == null) throw new IllegalArgumentException("Mail item not found.");
            ensureItemFilesLocked(tu, mid);

            List<RecipientRec> existing = readRecipientsLocked(tu, mid);
            boolean changed = !sameRecipients(existing, clean);
            if (!changed) return false;

            writeRecipientsLocked(tu, mid, clean);
            touchItemLocked(tu, mid);
            updateItemAddressValidationStatusLocked(tu, mid, clean);
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<TrackingEventRec> listTrackingEvents(String tenantUuid, String mailUuid) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String id = safe(mailUuid).trim();
        if (tu.isBlank() || id.isBlank()) return List.of();
        ensure(tu);

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.readLock().lock();
        try {
            return readTrackingEventsLocked(tu, id);
        } finally {
            lock.readLock().unlock();
        }
    }

    public TrackingEventRec addTrackingEvent(String tenantUuid,
                                             String mailUuid,
                                             String carrier,
                                             String trackingNumber,
                                             String status,
                                             String location,
                                             String eventAt,
                                             String notes,
                                             String source) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String mid = safe(mailUuid).trim();
        if (tu.isBlank()) throw new IllegalArgumentException("tenantUuid required");
        if (mid.isBlank()) throw new IllegalArgumentException("mail_uuid is required.");

        String normalizedTracking = canonicalTracking(trackingNumber);
        if (!normalizedTracking.isBlank()) {
            TrackingValidationResult tv = validateTrackingNumber(normalizedTracking);
            if (!tv.valid) throw new IllegalArgumentException(tv.message);
            normalizedTracking = tv.normalizedTrackingNumber;
        }

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensure(tu);
            MailItemRec item = findItem(readItemsLocked(tu), mid);
            if (item == null) throw new IllegalArgumentException("Mail item not found.");
            ensureItemFilesLocked(tu, mid);

            List<TrackingEventRec> all = readTrackingEventsLocked(tu, mid);
            TrackingEventRec rec = new TrackingEventRec();
            rec.uuid = UUID.randomUUID().toString();
            rec.mailUuid = mid;
            rec.carrier = canonicalCarrier(carrier);
            rec.trackingNumber = normalizedTracking;
            rec.status = clampLen(status, 120).trim();
            rec.location = clampLen(location, 200).trim();
            rec.eventAt = safe(eventAt).trim();
            if (rec.eventAt.isBlank()) rec.eventAt = nowIso();
            rec.notes = clampLen(notes, 3000).trim();
            rec.source = clampLen(source, 120).trim();
            rec.createdAt = nowIso();
            all.add(rec);
            sortTrackingEvents(all);
            writeTrackingEventsLocked(tu, mid, all);

            updateTrackingSummaryLocked(
                    tu,
                    mid,
                    rec.carrier.isBlank() ? item.trackingCarrier : rec.carrier,
                    rec.trackingNumber.isBlank() ? item.trackingNumber : rec.trackingNumber,
                    rec.status.isBlank() ? item.trackingStatus : rec.status,
                    false
            );
            return findTrackingEvent(all, rec.uuid);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean updateTrackingSummary(String tenantUuid,
                                         String mailUuid,
                                         String carrier,
                                         String trackingNumber,
                                         String trackingStatus,
                                         boolean markSentIfMissing) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String mid = safe(mailUuid).trim();
        if (tu.isBlank() || mid.isBlank()) return false;

        String normalizedTracking = canonicalTracking(trackingNumber);
        if (!normalizedTracking.isBlank()) {
            TrackingValidationResult tv = validateTrackingNumber(normalizedTracking);
            if (!tv.valid) throw new IllegalArgumentException(tv.message);
            normalizedTracking = tv.normalizedTrackingNumber;
        }

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensure(tu);
            return updateTrackingSummaryLocked(
                    tu,
                    mid,
                    canonicalCarrier(carrier),
                    normalizedTracking,
                    clampLen(trackingStatus, 120).trim(),
                    markSentIfMissing
            );
        } finally {
            lock.writeLock().unlock();
        }
    }

    public AddressValidationResult validateAddress(AddressInput input) {
        AddressInput in = input == null ? new AddressInput() : input;
        AddressValidationResult out = new AddressValidationResult();
        out.normalizedDisplayName = clampLen(in.displayName, 200).trim();
        out.normalizedCompanyName = clampLen(in.companyName, 200).trim();
        out.normalizedAddressLine1 = clampLen(in.addressLine1, 200).trim();
        out.normalizedAddressLine2 = clampLen(in.addressLine2, 200).trim();
        out.normalizedCity = clampLen(in.city, 120).trim();
        out.normalizedState = clampLen(in.state, 80).trim().toUpperCase(Locale.ROOT);
        out.normalizedPostalCode = clampLen(in.postalCode, 32).trim();
        out.normalizedCountry = clampLen(in.country, 80).trim().toUpperCase(Locale.ROOT);
        out.normalizedEmailAddress = clampLen(in.emailAddress, 320).trim().toLowerCase(Locale.ROOT);
        out.normalizedPhone = clampLen(in.phone, 60).trim();

        if (out.normalizedCountry.isBlank()) out.normalizedCountry = "US";

        if (out.normalizedDisplayName.isBlank() && out.normalizedCompanyName.isBlank()) {
            out.errors.add("Recipient display_name or company_name is required.");
        }
        if (out.normalizedAddressLine1.isBlank()) out.errors.add("address_line_1 is required.");
        if (out.normalizedCity.isBlank()) out.errors.add("city is required.");
        if (out.normalizedPostalCode.isBlank()) out.errors.add("postal_code is required.");

        if ("US".equals(out.normalizedCountry) || "USA".equals(out.normalizedCountry) || "UNITED STATES".equals(out.normalizedCountry)) {
            out.normalizedCountry = "US";
            if (out.normalizedState.isBlank()) out.errors.add("state is required.");
            if (!out.normalizedState.matches("^[A-Z]{2}$")) {
                out.errors.add("US addresses require a 2-letter state code.");
            }

            String zip = out.normalizedPostalCode.replace(" ", "");
            if (zip.matches("^\\d{9}$")) {
                zip = zip.substring(0, 5) + "-" + zip.substring(5);
            }
            if (!zip.matches("^\\d{5}(-\\d{4})?$")) {
                out.errors.add("US postal_code must be 5 digits or ZIP+4.");
            }
            out.normalizedPostalCode = zip;
        } else {
            if (out.normalizedState.isBlank()) {
                out.warnings.add("Non-US addresses should include region/province when available.");
            }
        }

        if (!out.normalizedEmailAddress.isBlank()
                && !out.normalizedEmailAddress.matches("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")) {
            out.warnings.add("email_address is not in a standard format.");
        }

        out.valid = out.errors.isEmpty();
        if (out.valid) {
            if (out.warnings.isEmpty()) out.message = "Address appears valid.";
            else out.message = "Address valid with warnings: " + String.join(" ", out.warnings);
        } else {
            out.message = "Invalid address: " + String.join(" ", out.errors);
        }
        return out;
    }

    public static TrackingValidationResult validateTrackingNumber(String raw) {
        TrackingValidationResult out = new TrackingValidationResult();
        String candidate = canonicalTracking(raw);
        out.normalizedTrackingNumber = candidate;
        out.carrierHint = "";

        if (candidate.isBlank()) {
            out.valid = false;
            out.message = "tracking_number is required.";
            return out;
        }

        if (candidate.matches("^\\d{12}$")
                || candidate.matches("^\\d{15}$")
                || candidate.matches("^\\d{20}$")
                || candidate.matches("^\\d{22}$")) {
            out.valid = true;
            out.carrierHint = SERVICE_FEDEX;
            out.message = "Tracking number format is valid.";
            return out;
        }

        if (candidate.matches("^92\\d{20,24}$")
                || candidate.matches("^94\\d{20,24}$")
                || candidate.matches("^93\\d{20,24}$")) {
            out.valid = true;
            out.carrierHint = SERVICE_USPS;
            out.message = "Tracking number format is valid.";
            return out;
        }

        // Future carrier APIs can have non-USPS/FedEx formats; allow a broad
        // alphanumeric range so unknown carriers can still be tracked.
        if (candidate.matches("^[A-Z0-9]{6,60}$")) {
            out.valid = true;
            out.carrierHint = "";
            out.message = "Tracking number format is valid for a custom carrier.";
            return out;
        }

        out.valid = false;
        out.message = "Tracking number format is invalid.";
        return out;
    }

    private static AddressInput addressFromRecipient(RecipientRec in) {
        AddressInput out = new AddressInput();
        out.displayName = safe(in == null ? "" : in.displayName);
        out.companyName = safe(in == null ? "" : in.companyName);
        out.addressLine1 = safe(in == null ? "" : in.addressLine1);
        out.addressLine2 = safe(in == null ? "" : in.addressLine2);
        out.city = safe(in == null ? "" : in.city);
        out.state = safe(in == null ? "" : in.state);
        out.postalCode = safe(in == null ? "" : in.postalCode);
        out.country = safe(in == null ? "" : in.country);
        out.emailAddress = safe(in == null ? "" : in.emailAddress);
        out.phone = safe(in == null ? "" : in.phone);
        return out;
    }

    private static boolean updateTrackingSummaryLocked(String tenantUuid,
                                                       String mailUuid,
                                                       String carrier,
                                                       String trackingNumber,
                                                       String trackingStatus,
                                                       boolean markSentIfMissing) throws Exception {
        List<MailItemRec> all = readItemsLocked(tenantUuid);
        boolean changed = false;
        for (MailItemRec rec : all) {
            if (rec == null) continue;
            if (!safe(mailUuid).trim().equals(safe(rec.uuid).trim())) continue;

            String nextCarrier = canonicalCarrier(carrier);
            String nextTracking = canonicalTracking(trackingNumber);
            String nextStatus = clampLen(trackingStatus, 120).trim();

            if (!safe(rec.trackingCarrier).equals(nextCarrier)) {
                rec.trackingCarrier = nextCarrier;
                changed = true;
            }
            if (!safe(rec.trackingNumber).equals(nextTracking)) {
                rec.trackingNumber = nextTracking;
                changed = true;
            }
            if (!safe(rec.trackingStatus).equals(nextStatus)) {
                rec.trackingStatus = nextStatus;
                changed = true;
            }

            if (markSentIfMissing && rec.sentAt.isBlank() && !nextTracking.isBlank()) {
                rec.sentAt = nowIso();
                if (STATUS_READY_TO_SEND.equals(rec.status) || STATUS_QUEUED.equals(rec.status)) {
                    rec.status = STATUS_SENT;
                }
                changed = true;
            }

            if (changed) rec.updatedAt = nowIso();
            break;
        }
        if (changed) {
            sortItems(all);
            writeItemsLocked(tenantUuid, all);
        }
        return changed;
    }

    private static void updateItemAddressValidationStatusLocked(String tenantUuid,
                                                                String mailUuid,
                                                                List<RecipientRec> recipients) throws Exception {
        List<MailItemRec> all = readItemsLocked(tenantUuid);
        String status = ADDRESS_STATUS_UNKNOWN;
        if (recipients != null && !recipients.isEmpty()) {
            boolean anyInvalid = false;
            boolean anyValid = false;
            for (RecipientRec r : recipients) {
                if (r == null) continue;
                if (r.validated) anyValid = true;
                else anyInvalid = true;
            }
            if (anyInvalid) status = ADDRESS_STATUS_INVALID;
            else if (anyValid) status = ADDRESS_STATUS_VALID;
        }

        boolean changed = false;
        for (MailItemRec item : all) {
            if (item == null) continue;
            if (!safe(mailUuid).trim().equals(safe(item.uuid).trim())) continue;
            if (!status.equals(item.addressValidationStatus)) {
                item.addressValidationStatus = status;
                item.updatedAt = nowIso();
                changed = true;
            }
            break;
        }
        if (changed) {
            sortItems(all);
            writeItemsLocked(tenantUuid, all);
        }
    }

    private static void touchItemLocked(String tenantUuid, String mailUuid) throws Exception {
        List<MailItemRec> all = readItemsLocked(tenantUuid);
        boolean changed = false;
        for (MailItemRec rec : all) {
            if (rec == null) continue;
            if (!safe(mailUuid).trim().equals(safe(rec.uuid).trim())) continue;
            rec.updatedAt = nowIso();
            changed = true;
            break;
        }
        if (changed) {
            sortItems(all);
            writeItemsLocked(tenantUuid, all);
        }
    }

    private static List<MailItemRec> readItemsLocked(String tenantUuid) throws Exception {
        ArrayList<MailItemRec> out = new ArrayList<MailItemRec>();
        Path p = itemsPath(tenantUuid);
        if (p == null || !Files.exists(p)) return out;
        Document d = document_workflow_support.parseXml(p);
        Element root = d == null ? null : d.getDocumentElement();
        if (root == null) return out;
        NodeList nl = root.getElementsByTagName("item");
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (!(n instanceof Element e)) continue;
            out.add(readItemRec(e));
        }
        sortItems(out);
        return out;
    }

    private static void writeItemsLocked(String tenantUuid, List<MailItemRec> rows) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<mail_items updated=\"")
                .append(document_workflow_support.xmlText(nowIso())).append("\">\n");
        List<MailItemRec> source = rows == null ? List.of() : rows;
        for (MailItemRec rec : source) {
            if (rec == null || safe(rec.uuid).trim().isBlank()) continue;
            sb.append("  <item>\n");
            writeTag(sb, "uuid", rec.uuid);
            writeTag(sb, "matter_uuid", rec.matterUuid);
            writeTag(sb, "direction", rec.direction);
            writeTag(sb, "workflow", rec.workflow);
            writeTag(sb, "service", rec.service);
            writeTag(sb, "status", rec.status);
            writeTag(sb, "subject", rec.subject);
            writeTag(sb, "notes", rec.notes);
            writeTag(sb, "source_email_address", rec.sourceEmailAddress);
            writeTag(sb, "source_document_uuid", rec.sourceDocumentUuid);
            writeTag(sb, "source_part_uuid", rec.sourcePartUuid);
            writeTag(sb, "source_version_uuid", rec.sourceVersionUuid);
            writeTag(sb, "filed_document_uuid", rec.filedDocumentUuid);
            writeTag(sb, "filed_part_uuid", rec.filedPartUuid);
            writeTag(sb, "filed_version_uuid", rec.filedVersionUuid);
            writeTag(sb, "tracking_carrier", rec.trackingCarrier);
            writeTag(sb, "tracking_number", rec.trackingNumber);
            writeTag(sb, "tracking_status", rec.trackingStatus);
            writeTag(sb, "provider_reference", rec.providerReference);
            writeTag(sb, "provider_message", rec.providerMessage);
            writeTag(sb, "provider_request_json", rec.providerRequestJson);
            writeTag(sb, "provider_response_json", rec.providerResponseJson);
            writeTag(sb, "address_validation_status", rec.addressValidationStatus);
            writeTag(sb, "created_by", rec.createdBy);
            writeTag(sb, "created_at", rec.createdAt);
            writeTag(sb, "updated_at", rec.updatedAt);
            writeTag(sb, "received_at", rec.receivedAt);
            writeTag(sb, "sent_at", rec.sentAt);
            writeTag(sb, "reviewed_by", rec.reviewedBy);
            writeTag(sb, "reviewed_at", rec.reviewedAt);
            writeTag(sb, "archived", rec.archived ? "true" : "false");
            sb.append("  </item>\n");
        }
        sb.append("</mail_items>\n");
        document_workflow_support.writeAtomic(itemsPath(tenantUuid), sb.toString());
    }

    private static List<MailPartRec> readPartsLocked(String tenantUuid, String mailUuid) throws Exception {
        ArrayList<MailPartRec> out = new ArrayList<MailPartRec>();
        Path p = partsPath(tenantUuid, mailUuid);
        if (p == null || !Files.exists(p)) return out;
        Document d = document_workflow_support.parseXml(p);
        Element root = d == null ? null : d.getDocumentElement();
        if (root == null) return out;
        NodeList nl = root.getElementsByTagName("part");
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (!(n instanceof Element e)) continue;
            out.add(readPartRec(e));
        }
        sortParts(out);
        return out;
    }

    private static void writePartsLocked(String tenantUuid, String mailUuid, List<MailPartRec> rows) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<mail_parts updated=\"")
                .append(document_workflow_support.xmlText(nowIso())).append("\">\n");
        List<MailPartRec> source = rows == null ? List.of() : rows;
        for (MailPartRec rec : source) {
            if (rec == null || safe(rec.uuid).trim().isBlank()) continue;
            sb.append("  <part>\n");
            writeTag(sb, "uuid", rec.uuid);
            writeTag(sb, "mail_uuid", rec.mailUuid);
            writeTag(sb, "part_type", rec.partType);
            writeTag(sb, "label", rec.label);
            writeTag(sb, "document_uuid", rec.documentUuid);
            writeTag(sb, "part_uuid", rec.partUuid);
            writeTag(sb, "version_uuid", rec.versionUuid);
            writeTag(sb, "notes", rec.notes);
            writeTag(sb, "created_by", rec.createdBy);
            writeTag(sb, "created_at", rec.createdAt);
            writeTag(sb, "updated_at", rec.updatedAt);
            writeTag(sb, "trashed", rec.trashed ? "true" : "false");
            sb.append("  </part>\n");
        }
        sb.append("</mail_parts>\n");
        document_workflow_support.writeAtomic(partsPath(tenantUuid, mailUuid), sb.toString());
    }

    private static List<RecipientRec> readRecipientsLocked(String tenantUuid, String mailUuid) throws Exception {
        ArrayList<RecipientRec> out = new ArrayList<RecipientRec>();
        Path p = recipientsPath(tenantUuid, mailUuid);
        if (p == null || !Files.exists(p)) return out;
        Document d = document_workflow_support.parseXml(p);
        Element root = d == null ? null : d.getDocumentElement();
        if (root == null) return out;
        NodeList nl = root.getElementsByTagName("recipient");
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (!(n instanceof Element e)) continue;
            out.add(readRecipientRec(e));
        }
        sortRecipients(out);
        return out;
    }

    private static void writeRecipientsLocked(String tenantUuid, String mailUuid, List<RecipientRec> rows) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<mail_recipients updated=\"")
                .append(document_workflow_support.xmlText(nowIso())).append("\">\n");
        List<RecipientRec> source = rows == null ? List.of() : rows;
        for (RecipientRec rec : source) {
            if (rec == null || safe(rec.uuid).trim().isBlank()) continue;
            sb.append("  <recipient>\n");
            writeTag(sb, "uuid", rec.uuid);
            writeTag(sb, "mail_uuid", rec.mailUuid);
            writeTag(sb, "contact_uuid", rec.contactUuid);
            writeTag(sb, "display_name", rec.displayName);
            writeTag(sb, "company_name", rec.companyName);
            writeTag(sb, "address_line_1", rec.addressLine1);
            writeTag(sb, "address_line_2", rec.addressLine2);
            writeTag(sb, "city", rec.city);
            writeTag(sb, "state", rec.state);
            writeTag(sb, "postal_code", rec.postalCode);
            writeTag(sb, "country", rec.country);
            writeTag(sb, "email_address", rec.emailAddress);
            writeTag(sb, "phone", rec.phone);
            writeTag(sb, "validated", rec.validated ? "true" : "false");
            writeTag(sb, "validation_message", rec.validationMessage);
            writeTag(sb, "created_at", rec.createdAt);
            writeTag(sb, "updated_at", rec.updatedAt);
            sb.append("  </recipient>\n");
        }
        sb.append("</mail_recipients>\n");
        document_workflow_support.writeAtomic(recipientsPath(tenantUuid, mailUuid), sb.toString());
    }

    private static List<TrackingEventRec> readTrackingEventsLocked(String tenantUuid, String mailUuid) throws Exception {
        ArrayList<TrackingEventRec> out = new ArrayList<TrackingEventRec>();
        Path p = trackingPath(tenantUuid, mailUuid);
        if (p == null || !Files.exists(p)) return out;
        Document d = document_workflow_support.parseXml(p);
        Element root = d == null ? null : d.getDocumentElement();
        if (root == null) return out;
        NodeList nl = root.getElementsByTagName("event");
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (!(n instanceof Element e)) continue;
            out.add(readTrackingEventRec(e));
        }
        sortTrackingEvents(out);
        return out;
    }

    private static void writeTrackingEventsLocked(String tenantUuid, String mailUuid, List<TrackingEventRec> rows) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<mail_tracking updated=\"")
                .append(document_workflow_support.xmlText(nowIso())).append("\">\n");
        List<TrackingEventRec> source = rows == null ? List.of() : rows;
        for (TrackingEventRec rec : source) {
            if (rec == null || safe(rec.uuid).trim().isBlank()) continue;
            sb.append("  <event>\n");
            writeTag(sb, "uuid", rec.uuid);
            writeTag(sb, "mail_uuid", rec.mailUuid);
            writeTag(sb, "carrier", rec.carrier);
            writeTag(sb, "tracking_number", rec.trackingNumber);
            writeTag(sb, "status", rec.status);
            writeTag(sb, "location", rec.location);
            writeTag(sb, "event_at", rec.eventAt);
            writeTag(sb, "notes", rec.notes);
            writeTag(sb, "source", rec.source);
            writeTag(sb, "created_at", rec.createdAt);
            sb.append("  </event>\n");
        }
        sb.append("</mail_tracking>\n");
        document_workflow_support.writeAtomic(trackingPath(tenantUuid, mailUuid), sb.toString());
    }

    private static MailItemRec readItemRec(Element e) {
        MailItemRec rec = new MailItemRec();
        rec.uuid = document_workflow_support.text(e, "uuid");
        rec.matterUuid = document_workflow_support.text(e, "matter_uuid");
        rec.direction = canonicalDirection(document_workflow_support.text(e, "direction"));
        rec.workflow = canonicalWorkflow(document_workflow_support.text(e, "workflow"));
        rec.service = canonicalService(document_workflow_support.text(e, "service"), rec.workflow);
        rec.status = canonicalStatus(document_workflow_support.text(e, "status"), rec.direction);
        rec.subject = document_workflow_support.text(e, "subject");
        rec.notes = document_workflow_support.text(e, "notes");
        rec.sourceEmailAddress = document_workflow_support.text(e, "source_email_address");
        rec.sourceDocumentUuid = document_workflow_support.text(e, "source_document_uuid");
        rec.sourcePartUuid = document_workflow_support.text(e, "source_part_uuid");
        rec.sourceVersionUuid = document_workflow_support.text(e, "source_version_uuid");
        rec.filedDocumentUuid = document_workflow_support.text(e, "filed_document_uuid");
        rec.filedPartUuid = document_workflow_support.text(e, "filed_part_uuid");
        rec.filedVersionUuid = document_workflow_support.text(e, "filed_version_uuid");
        rec.trackingCarrier = canonicalCarrier(document_workflow_support.text(e, "tracking_carrier"));
        rec.trackingNumber = canonicalTracking(document_workflow_support.text(e, "tracking_number"));
        rec.trackingStatus = document_workflow_support.text(e, "tracking_status");
        rec.providerReference = document_workflow_support.text(e, "provider_reference");
        rec.providerMessage = document_workflow_support.text(e, "provider_message");
        rec.providerRequestJson = document_workflow_support.text(e, "provider_request_json");
        rec.providerResponseJson = document_workflow_support.text(e, "provider_response_json");
        rec.addressValidationStatus = canonicalAddressValidationStatus(document_workflow_support.text(e, "address_validation_status"));
        rec.createdBy = document_workflow_support.text(e, "created_by");
        rec.createdAt = document_workflow_support.text(e, "created_at");
        rec.updatedAt = document_workflow_support.text(e, "updated_at");
        rec.receivedAt = document_workflow_support.text(e, "received_at");
        rec.sentAt = document_workflow_support.text(e, "sent_at");
        rec.reviewedBy = document_workflow_support.text(e, "reviewed_by");
        rec.reviewedAt = document_workflow_support.text(e, "reviewed_at");
        rec.archived = "true".equalsIgnoreCase(document_workflow_support.text(e, "archived"));
        return rec;
    }

    private static MailPartRec readPartRec(Element e) {
        MailPartRec rec = new MailPartRec();
        rec.uuid = document_workflow_support.text(e, "uuid");
        rec.mailUuid = document_workflow_support.text(e, "mail_uuid");
        rec.partType = canonicalPartType(document_workflow_support.text(e, "part_type"));
        rec.label = document_workflow_support.text(e, "label");
        rec.documentUuid = document_workflow_support.text(e, "document_uuid");
        rec.partUuid = document_workflow_support.text(e, "part_uuid");
        rec.versionUuid = document_workflow_support.text(e, "version_uuid");
        rec.notes = document_workflow_support.text(e, "notes");
        rec.createdBy = document_workflow_support.text(e, "created_by");
        rec.createdAt = document_workflow_support.text(e, "created_at");
        rec.updatedAt = document_workflow_support.text(e, "updated_at");
        rec.trashed = "true".equalsIgnoreCase(document_workflow_support.text(e, "trashed"));
        return rec;
    }

    private static RecipientRec readRecipientRec(Element e) {
        RecipientRec rec = new RecipientRec();
        rec.uuid = document_workflow_support.text(e, "uuid");
        rec.mailUuid = document_workflow_support.text(e, "mail_uuid");
        rec.contactUuid = document_workflow_support.text(e, "contact_uuid");
        rec.displayName = document_workflow_support.text(e, "display_name");
        rec.companyName = document_workflow_support.text(e, "company_name");
        rec.addressLine1 = document_workflow_support.text(e, "address_line_1");
        rec.addressLine2 = document_workflow_support.text(e, "address_line_2");
        rec.city = document_workflow_support.text(e, "city");
        rec.state = document_workflow_support.text(e, "state");
        rec.postalCode = document_workflow_support.text(e, "postal_code");
        rec.country = document_workflow_support.text(e, "country");
        rec.emailAddress = document_workflow_support.text(e, "email_address");
        rec.phone = document_workflow_support.text(e, "phone");
        rec.validated = "true".equalsIgnoreCase(document_workflow_support.text(e, "validated"));
        rec.validationMessage = document_workflow_support.text(e, "validation_message");
        rec.createdAt = document_workflow_support.text(e, "created_at");
        rec.updatedAt = document_workflow_support.text(e, "updated_at");
        return rec;
    }

    private static TrackingEventRec readTrackingEventRec(Element e) {
        TrackingEventRec rec = new TrackingEventRec();
        rec.uuid = document_workflow_support.text(e, "uuid");
        rec.mailUuid = document_workflow_support.text(e, "mail_uuid");
        rec.carrier = canonicalCarrier(document_workflow_support.text(e, "carrier"));
        rec.trackingNumber = canonicalTracking(document_workflow_support.text(e, "tracking_number"));
        rec.status = document_workflow_support.text(e, "status");
        rec.location = document_workflow_support.text(e, "location");
        rec.eventAt = document_workflow_support.text(e, "event_at");
        rec.notes = document_workflow_support.text(e, "notes");
        rec.source = document_workflow_support.text(e, "source");
        rec.createdAt = document_workflow_support.text(e, "created_at");
        return rec;
    }

    private static MailItemRec findItem(List<MailItemRec> rows, String itemUuid) {
        if (rows == null) return null;
        String id = safe(itemUuid).trim();
        if (id.isBlank()) return null;
        for (MailItemRec rec : rows) {
            if (rec == null) continue;
            if (id.equals(safe(rec.uuid).trim())) return rec;
        }
        return null;
    }

    private static MailPartRec findPart(List<MailPartRec> rows, String partUuid) {
        if (rows == null) return null;
        String id = safe(partUuid).trim();
        if (id.isBlank()) return null;
        for (MailPartRec rec : rows) {
            if (rec == null) continue;
            if (id.equals(safe(rec.uuid).trim())) return rec;
        }
        return null;
    }

    private static RecipientRec findRecipient(List<RecipientRec> rows, String recipientUuid) {
        if (rows == null) return null;
        String id = safe(recipientUuid).trim();
        if (id.isBlank()) return null;
        for (RecipientRec rec : rows) {
            if (rec == null) continue;
            if (id.equals(safe(rec.uuid).trim())) return rec;
        }
        return null;
    }

    private static TrackingEventRec findTrackingEvent(List<TrackingEventRec> rows, String eventUuid) {
        if (rows == null) return null;
        String id = safe(eventUuid).trim();
        if (id.isBlank()) return null;
        for (TrackingEventRec rec : rows) {
            if (rec == null) continue;
            if (id.equals(safe(rec.uuid).trim())) return rec;
        }
        return null;
    }

    private static boolean sameRecipients(List<RecipientRec> a, List<RecipientRec> b) {
        List<RecipientRec> left = a == null ? List.of() : a;
        List<RecipientRec> right = b == null ? List.of() : b;
        if (left.size() != right.size()) return false;
        for (int i = 0; i < left.size(); i++) {
            RecipientRec x = left.get(i);
            RecipientRec y = right.get(i);
            if (!safe(x == null ? "" : x.uuid).equals(safe(y == null ? "" : y.uuid))) return false;
            if (!safe(x == null ? "" : x.contactUuid).equals(safe(y == null ? "" : y.contactUuid))) return false;
            if (!safe(x == null ? "" : x.displayName).equals(safe(y == null ? "" : y.displayName))) return false;
            if (!safe(x == null ? "" : x.companyName).equals(safe(y == null ? "" : y.companyName))) return false;
            if (!safe(x == null ? "" : x.addressLine1).equals(safe(y == null ? "" : y.addressLine1))) return false;
            if (!safe(x == null ? "" : x.addressLine2).equals(safe(y == null ? "" : y.addressLine2))) return false;
            if (!safe(x == null ? "" : x.city).equals(safe(y == null ? "" : y.city))) return false;
            if (!safe(x == null ? "" : x.state).equals(safe(y == null ? "" : y.state))) return false;
            if (!safe(x == null ? "" : x.postalCode).equals(safe(y == null ? "" : y.postalCode))) return false;
            if (!safe(x == null ? "" : x.country).equals(safe(y == null ? "" : y.country))) return false;
            if (!safe(x == null ? "" : x.emailAddress).equals(safe(y == null ? "" : y.emailAddress))) return false;
            if (!safe(x == null ? "" : x.phone).equals(safe(y == null ? "" : y.phone))) return false;
            if ((x != null && x.validated) != (y != null && y.validated)) return false;
            if (!safe(x == null ? "" : x.validationMessage).equals(safe(y == null ? "" : y.validationMessage))) return false;
        }
        return true;
    }

    private static void ensureItemFilesLocked(String tenantUuid, String mailUuid) throws Exception {
        Path folder = itemDir(tenantUuid, mailUuid);
        if (folder == null) return;
        Files.createDirectories(folder);
        Path parts = partsPath(tenantUuid, mailUuid);
        Path recipients = recipientsPath(tenantUuid, mailUuid);
        Path tracking = trackingPath(tenantUuid, mailUuid);
        if (parts != null && !Files.exists(parts)) {
            document_workflow_support.writeAtomic(
                    parts,
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<mail_parts updated=\""
                            + document_workflow_support.xmlText(nowIso()) + "\"></mail_parts>\n"
            );
        }
        if (recipients != null && !Files.exists(recipients)) {
            document_workflow_support.writeAtomic(
                    recipients,
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<mail_recipients updated=\""
                            + document_workflow_support.xmlText(nowIso()) + "\"></mail_recipients>\n"
            );
        }
        if (tracking != null && !Files.exists(tracking)) {
            document_workflow_support.writeAtomic(
                    tracking,
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<mail_tracking updated=\""
                            + document_workflow_support.xmlText(nowIso()) + "\"></mail_tracking>\n"
            );
        }
    }

    private static void sortItems(List<MailItemRec> rows) {
        if (rows == null) return;
        rows.sort(
                Comparator
                        .comparing((MailItemRec r) -> safe(r == null ? "" : r.updatedAt))
                        .thenComparing(r -> safe(r == null ? "" : r.createdAt))
                        .thenComparing(r -> safe(r == null ? "" : r.subject).toLowerCase(Locale.ROOT))
                        .reversed()
        );
    }

    private static void sortParts(List<MailPartRec> rows) {
        if (rows == null) return;
        rows.sort(
                Comparator
                        .comparing((MailPartRec r) -> safe(r == null ? "" : r.createdAt))
                        .thenComparing(r -> safe(r == null ? "" : r.label).toLowerCase(Locale.ROOT))
        );
    }

    private static void sortRecipients(List<RecipientRec> rows) {
        if (rows == null) return;
        rows.sort(
                Comparator
                        .comparing((RecipientRec r) -> safe(r == null ? "" : r.displayName).toLowerCase(Locale.ROOT))
                        .thenComparing(r -> safe(r == null ? "" : r.companyName).toLowerCase(Locale.ROOT))
                        .thenComparing(r -> safe(r == null ? "" : r.postalCode))
        );
    }

    private static void sortTrackingEvents(List<TrackingEventRec> rows) {
        if (rows == null) return;
        rows.sort(
                Comparator
                        .comparing((TrackingEventRec r) -> safe(r == null ? "" : r.eventAt))
                        .thenComparing(r -> safe(r == null ? "" : r.createdAt))
        );
    }

    private static String canonicalDirection(String raw) {
        String v = safe(raw).trim().toLowerCase(Locale.ROOT);
        if (DIRECTION_OUTBOUND.equals(v)) return DIRECTION_OUTBOUND;
        return DIRECTION_INBOUND;
    }

    private static String canonicalWorkflow(String raw) {
        String v = safe(raw).trim().toLowerCase(Locale.ROOT);
        if (WORKFLOW_CLICK2MAIL.equals(v)) return WORKFLOW_CLICK2MAIL;
        if (WORKFLOW_FEDEX.equals(v)) return WORKFLOW_FEDEX;
        if (WORKFLOW_EMAIL_INBOX.equals(v)) return WORKFLOW_EMAIL_INBOX;
        if (WORKFLOW_MANUAL_SCAN.equals(v)) return WORKFLOW_MANUAL_SCAN;
        String custom = normalizeProviderToken(v);
        if (!custom.isBlank()) return custom;
        return WORKFLOW_MANUAL;
    }

    private static String canonicalService(String raw, String workflow) {
        String v = safe(raw).trim().toLowerCase(Locale.ROOT);
        if (SERVICE_USPS.equals(v)) return SERVICE_USPS;
        if (SERVICE_FEDEX.equals(v)) return SERVICE_FEDEX;
        if (SERVICE_UPS.equals(v)) return SERVICE_UPS;
        if (SERVICE_COURIER.equals(v)) return SERVICE_COURIER;
        if (SERVICE_OTHER.equals(v)) return SERVICE_OTHER;
        String custom = normalizeProviderToken(v);
        if (!custom.isBlank()) return custom;
        if (WORKFLOW_FEDEX.equals(workflow)) return SERVICE_FEDEX;
        if (WORKFLOW_CLICK2MAIL.equals(workflow)) return SERVICE_USPS;
        return SERVICE_OTHER;
    }

    private static String canonicalStatus(String raw, String direction) {
        String v = safe(raw).trim().toLowerCase(Locale.ROOT);
        if (STATUS_RECEIVED.equals(v)) return STATUS_RECEIVED;
        if (STATUS_REVIEW_PENDING.equals(v)) return STATUS_REVIEW_PENDING;
        if (STATUS_REVIEWED.equals(v)) return STATUS_REVIEWED;
        if (STATUS_UPLOADED.equals(v)) return STATUS_UPLOADED;
        if (STATUS_READY_TO_SEND.equals(v)) return STATUS_READY_TO_SEND;
        if (STATUS_QUEUED.equals(v)) return STATUS_QUEUED;
        if (STATUS_SENT.equals(v)) return STATUS_SENT;
        if (STATUS_IN_TRANSIT.equals(v)) return STATUS_IN_TRANSIT;
        if (STATUS_DELIVERED.equals(v)) return STATUS_DELIVERED;
        if (STATUS_FAILED.equals(v)) return STATUS_FAILED;
        if (STATUS_CANCELLED.equals(v)) return STATUS_CANCELLED;
        String custom = normalizeProviderToken(v);
        if (!custom.isBlank()) return custom;
        return DIRECTION_OUTBOUND.equals(canonicalDirection(direction)) ? STATUS_READY_TO_SEND : STATUS_RECEIVED;
    }

    private static String canonicalPartType(String raw) {
        String v = safe(raw).trim().toLowerCase(Locale.ROOT);
        if (PART_ENVELOPE.equals(v)) return PART_ENVELOPE;
        if (PART_LETTER.equals(v)) return PART_LETTER;
        if (PART_ATTACHMENT.equals(v)) return PART_ATTACHMENT;
        if (PART_RECEIPT.equals(v)) return PART_RECEIPT;
        if (PART_PROOF.equals(v)) return PART_PROOF;
        if (PART_TRACKING.equals(v)) return PART_TRACKING;
        if (PART_LABEL.equals(v)) return PART_LABEL;
        String custom = normalizeProviderToken(v);
        if (!custom.isBlank()) return custom;
        return PART_OTHER;
    }

    private static String canonicalCarrier(String raw) {
        String v = safe(raw).trim().toLowerCase(Locale.ROOT);
        if (v.contains("fedex")) return SERVICE_FEDEX;
        if (v.contains("usps") || v.contains("postal")) return SERVICE_USPS;
        if (v.contains("ups")) return SERVICE_UPS;
        if (v.contains("courier") || v.contains("messenger")) return SERVICE_COURIER;
        if (v.isBlank()) return "";
        if (SERVICE_FEDEX.equals(v)) return SERVICE_FEDEX;
        if (SERVICE_USPS.equals(v)) return SERVICE_USPS;
        if (SERVICE_UPS.equals(v)) return SERVICE_UPS;
        if (SERVICE_COURIER.equals(v)) return SERVICE_COURIER;
        String custom = normalizeProviderToken(v);
        if (!custom.isBlank()) return custom;
        return SERVICE_OTHER;
    }

    private static String canonicalAddressValidationStatus(String raw) {
        String v = safe(raw).trim().toLowerCase(Locale.ROOT);
        if (ADDRESS_STATUS_VALID.equals(v)) return ADDRESS_STATUS_VALID;
        if (ADDRESS_STATUS_INVALID.equals(v)) return ADDRESS_STATUS_INVALID;
        return ADDRESS_STATUS_UNKNOWN;
    }

    private static String canonicalTracking(String raw) {
        String v = safe(raw).trim().toUpperCase(Locale.ROOT);
        if (v.isBlank()) return "";
        return v.replaceAll("[\\s-]", "");
    }

    private static String clampLen(String raw, int max) {
        String v = safe(raw);
        if (max > 0 && v.length() > max) return v.substring(0, max);
        return v;
    }

    private static String normalizeProviderToken(String raw) {
        String v = safe(raw).trim().toLowerCase(Locale.ROOT);
        if (v.isBlank()) return "";
        StringBuilder sb = new StringBuilder(v.length());
        boolean lastUnderscore = false;
        for (int i = 0; i < v.length(); i++) {
            char ch = v.charAt(i);
            boolean ok = (ch >= 'a' && ch <= 'z')
                    || (ch >= '0' && ch <= '9')
                    || ch == '_' || ch == '-' || ch == '.';
            if (ok) {
                sb.append(ch);
                lastUnderscore = false;
            } else if (!lastUnderscore && sb.length() > 0) {
                sb.append('_');
                lastUnderscore = true;
            }
        }
        String out = sb.toString();
        while (out.startsWith("_")) out = out.substring(1);
        while (out.endsWith("_")) out = out.substring(0, out.length() - 1);
        if (out.length() > 80) out = out.substring(0, 80);
        return out;
    }

    private static Path mailRoot(String tenantUuid) {
        String tu = safeFileToken(tenantUuid);
        if (tu.isBlank()) return null;
        return Paths.get("data", "tenants", tu, "mail").toAbsolutePath();
    }

    private static Path itemsDir(String tenantUuid) {
        Path root = mailRoot(tenantUuid);
        if (root == null) return null;
        return root.resolve("items");
    }

    private static Path itemDir(String tenantUuid, String mailUuid) {
        Path dir = itemsDir(tenantUuid);
        String mid = safeFileToken(mailUuid);
        if (dir == null || mid.isBlank()) return null;
        return dir.resolve(mid);
    }

    private static Path itemsPath(String tenantUuid) {
        Path root = mailRoot(tenantUuid);
        if (root == null) return null;
        return root.resolve("mail_items.xml");
    }

    private static Path partsPath(String tenantUuid, String mailUuid) {
        Path folder = itemDir(tenantUuid, mailUuid);
        if (folder == null) return null;
        return folder.resolve("mail_parts.xml");
    }

    private static Path recipientsPath(String tenantUuid, String mailUuid) {
        Path folder = itemDir(tenantUuid, mailUuid);
        if (folder == null) return null;
        return folder.resolve("mail_recipients.xml");
    }

    private static Path trackingPath(String tenantUuid, String mailUuid) {
        Path folder = itemDir(tenantUuid, mailUuid);
        if (folder == null) return null;
        return folder.resolve("mail_tracking.xml");
    }

    private static ReentrantReadWriteLock lockFor(String tenantUuid) {
        String tu = safeFileToken(tenantUuid);
        return LOCKS.computeIfAbsent(tu, ignored -> new ReentrantReadWriteLock());
    }

    private static String safeFileToken(String s) {
        return safe(s).trim().replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String nowIso() {
        return Instant.now().toString();
    }

    private static void writeTag(StringBuilder sb, String tag, String value) {
        sb.append("    <").append(tag).append(">")
                .append(document_workflow_support.xmlText(value))
                .append("</").append(tag).append(">\n");
    }
}
