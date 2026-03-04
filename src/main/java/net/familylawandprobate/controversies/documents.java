package net.familylawandprobate.controversies;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public final class documents {
    public static final long CHECKOUT_TTL_HOURS = 72L;
    private static final Duration CHECKOUT_TTL = Duration.ofHours(CHECKOUT_TTL_HOURS);
    private static final ThreadLocal<String> ACTOR_CONTEXT = new ThreadLocal<String>();

    public static final class DocumentRec {
        public String uuid;
        public String title;
        public String category;
        public String subcategory;
        public String status;
        public String owner;
        public String privilegeLevel;
        public String filedOn;
        public String externalReference;
        public String notes;
        public String createdAt;
        public String updatedAt;
        public boolean trashed;
        public String source;
        public String sourceDocumentId;
        public String sourceUpdatedAt;
        public boolean readOnly;
        public String checkedOutBy;
        public String checkedOutAt;
    }

    public static documents defaultStore() { return new documents(); }

    public static void bindActorContext(String actor) {
        String normalized = normalizeActor(actor);
        if (normalized.isBlank()) ACTOR_CONTEXT.remove();
        else ACTOR_CONTEXT.set(normalized);
    }

    public static void clearActorContext() {
        ACTOR_CONTEXT.remove();
    }

    public void ensure(String tenantUuid, String matterUuid) throws Exception {
        Path p = docsPath(tenantUuid, matterUuid);
        if (p == null) throw new IllegalArgumentException("tenantUuid and matterUuid required");
        Files.createDirectories(p.getParent());
        if (!Files.exists(p)) {
            String now = Instant.now().toString();
            document_workflow_support.writeAtomic(p,
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<documents updated=\""
                + document_workflow_support.xmlText(now) + "\"></documents>\n");
        }
    }

    public List<DocumentRec> listAll(String tenantUuid, String matterUuid) throws Exception {
        List<DocumentRec> out = new ArrayList<DocumentRec>();
        Path p = docsPath(tenantUuid, matterUuid);
        if (p == null || !Files.exists(p)) return out;
        Document d = document_workflow_support.parseXml(p);
        Element root = d == null ? null : d.getDocumentElement();
        if (root == null) return out;
        NodeList nl = root.getElementsByTagName("document");
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (!(n instanceof Element)) continue;
            out.add(readRec((Element) n));
        }
        out.sort(Comparator.comparing(a -> document_workflow_support.safe(a.title).toLowerCase()));
        return out;
    }

    public DocumentRec create(String tenantUuid, String matterUuid, String title, String category, String subcategory, String status,
                              String owner, String privilegeLevel, String filedOn, String externalReference, String notes) throws Exception {
        if (document_workflow_support.safe(title).trim().isBlank()) throw new IllegalArgumentException("title required");
        ensure(tenantUuid, matterUuid);
        List<DocumentRec> all = listAll(tenantUuid, matterUuid);
        DocumentRec rec = new DocumentRec();
        rec.uuid = UUID.randomUUID().toString();
        rec.title = document_workflow_support.safe(title).trim();
        rec.category = document_workflow_support.normalizeToken(category);
        rec.subcategory = document_workflow_support.normalizeToken(subcategory);
        rec.status = document_workflow_support.normalizeToken(status);
        rec.owner = document_workflow_support.safe(owner).trim();
        rec.privilegeLevel = document_workflow_support.safe(privilegeLevel).trim();
        rec.filedOn = document_workflow_support.safe(filedOn).trim();
        rec.externalReference = document_workflow_support.safe(externalReference).trim();
        rec.notes = document_workflow_support.safe(notes).trim();
        rec.createdAt = document_workflow_support.nowIso();
        rec.updatedAt = rec.createdAt;
        rec.trashed = false;
        rec.source = "";
        rec.sourceDocumentId = "";
        rec.sourceUpdatedAt = "";
        rec.readOnly = false;
        rec.checkedOutBy = "";
        rec.checkedOutAt = "";
        all.add(rec);
        writeAll(tenantUuid, matterUuid, all);
        Files.createDirectories(documentFolder(tenantUuid, matterUuid, rec.uuid));
        publishDocumentEvent(tenantUuid, matterUuid, "document.created", rec);
        return rec;
    }

    public boolean update(String tenantUuid, String matterUuid, DocumentRec recIn) throws Exception {
        return update(tenantUuid, matterUuid, recIn, currentActor());
    }

    public boolean update(String tenantUuid, String matterUuid, DocumentRec recIn, String actor) throws Exception {
        String id = document_workflow_support.safe(recIn == null ? "" : recIn.uuid).trim();
        if (id.isBlank()) return false;
        List<DocumentRec> all = listAll(tenantUuid, matterUuid);
        boolean changed = false;
        DocumentRec updatedRec = null;
        for (DocumentRec rec : all) {
            if (!id.equals(document_workflow_support.safe(rec.uuid).trim())) continue;
            requireEditable(rec, actor);
            rec.title = document_workflow_support.safe(recIn.title).trim();
            rec.category = document_workflow_support.normalizeToken(recIn.category);
            rec.subcategory = document_workflow_support.normalizeToken(recIn.subcategory);
            rec.status = document_workflow_support.normalizeToken(recIn.status);
            rec.owner = document_workflow_support.safe(recIn.owner).trim();
            rec.privilegeLevel = document_workflow_support.safe(recIn.privilegeLevel).trim();
            rec.filedOn = document_workflow_support.safe(recIn.filedOn).trim();
            rec.externalReference = document_workflow_support.safe(recIn.externalReference).trim();
            rec.notes = document_workflow_support.safe(recIn.notes).trim();
            rec.updatedAt = document_workflow_support.nowIso();
            changed = true;
            updatedRec = rec;
        }
        if (changed) {
            writeAll(tenantUuid, matterUuid, all);
            publishDocumentEvent(tenantUuid, matterUuid, "document.updated", updatedRec);
        }
        return changed;
    }

    public boolean updateForExternalSync(String tenantUuid, String matterUuid, DocumentRec recIn) throws Exception {
        String id = document_workflow_support.safe(recIn == null ? "" : recIn.uuid).trim();
        if (id.isBlank()) return false;
        List<DocumentRec> all = listAll(tenantUuid, matterUuid);
        boolean changed = false;
        DocumentRec updatedRec = null;
        for (DocumentRec rec : all) {
            if (!id.equals(document_workflow_support.safe(rec.uuid).trim())) continue;
            rec.title = document_workflow_support.safe(recIn.title).trim();
            rec.category = document_workflow_support.normalizeToken(recIn.category);
            rec.subcategory = document_workflow_support.normalizeToken(recIn.subcategory);
            rec.status = document_workflow_support.normalizeToken(recIn.status);
            rec.owner = document_workflow_support.safe(recIn.owner).trim();
            rec.privilegeLevel = document_workflow_support.safe(recIn.privilegeLevel).trim();
            rec.filedOn = document_workflow_support.safe(recIn.filedOn).trim();
            rec.externalReference = document_workflow_support.safe(recIn.externalReference).trim();
            rec.notes = document_workflow_support.safe(recIn.notes).trim();
            rec.updatedAt = document_workflow_support.nowIso();
            changed = true;
            updatedRec = rec;
        }
        if (changed) {
            writeAll(tenantUuid, matterUuid, all);
            publishDocumentEvent(tenantUuid, matterUuid, "document.updated_from_external_sync", updatedRec);
        }
        return changed;
    }

    public boolean setTrashed(String tenantUuid, String matterUuid, String docUuid, boolean trashed) throws Exception {
        return setTrashed(tenantUuid, matterUuid, docUuid, trashed, currentActor());
    }

    public boolean setTrashed(String tenantUuid, String matterUuid, String docUuid, boolean trashed, String actor) throws Exception {
        List<DocumentRec> all = listAll(tenantUuid, matterUuid);
        boolean changed = false;
        DocumentRec changedRec = null;
        for (DocumentRec rec : all) {
            if (!document_workflow_support.safe(docUuid).trim().equals(document_workflow_support.safe(rec.uuid).trim())) continue;
            requireEditable(rec, actor);
            rec.trashed = trashed;
            rec.updatedAt = document_workflow_support.nowIso();
            changed = true;
            changedRec = rec;
        }
        if (changed) {
            writeAll(tenantUuid, matterUuid, all);
            publishDocumentEvent(tenantUuid, matterUuid, trashed ? "document.trashed" : "document.restored", changedRec);
        }
        return changed;
    }

    public DocumentRec get(String tenantUuid, String matterUuid, String docUuid) throws Exception {
        for (DocumentRec rec : listAll(tenantUuid, matterUuid)) {
            if (document_workflow_support.safe(docUuid).trim().equals(document_workflow_support.safe(rec.uuid).trim())) return rec;
        }
        return null;
    }

    public DocumentRec requireEditable(String tenantUuid, String matterUuid, String docUuid) throws Exception {
        return requireEditable(tenantUuid, matterUuid, docUuid, currentActor());
    }

    public DocumentRec requireEditable(String tenantUuid, String matterUuid, String docUuid, String actor) throws Exception {
        DocumentRec rec = get(tenantUuid, matterUuid, docUuid);
        if (rec == null) throw new IllegalStateException("Document not found.");
        requireEditable(rec, actor);
        return rec;
    }

    public boolean updateSourceMetadata(String tenantUuid,
                                        String matterUuid,
                                        String docUuid,
                                        String source,
                                        String sourceDocumentId,
                                        String sourceUpdatedAt,
                                        boolean readOnly) throws Exception {
        String id = document_workflow_support.safe(docUuid).trim();
        if (id.isBlank()) return false;
        List<DocumentRec> all = listAll(tenantUuid, matterUuid);
        boolean changed = false;
        DocumentRec updatedRec = null;
        for (DocumentRec rec : all) {
            if (!id.equals(document_workflow_support.safe(rec.uuid).trim())) continue;
            rec.source = document_workflow_support.safe(source).trim();
            rec.sourceDocumentId = document_workflow_support.safe(sourceDocumentId).trim();
            rec.sourceUpdatedAt = document_workflow_support.safe(sourceUpdatedAt).trim();
            rec.readOnly = readOnly;
            rec.updatedAt = document_workflow_support.nowIso();
            changed = true;
            updatedRec = rec;
        }
        if (changed) {
            writeAll(tenantUuid, matterUuid, all);
            publishDocumentEvent(tenantUuid, matterUuid, "document.source_metadata_updated", updatedRec);
        }
        return changed;
    }

    public static boolean isReadOnly(DocumentRec rec) {
        if (rec == null) return false;
        if (rec.readOnly) return true;
        return "clio".equalsIgnoreCase(document_workflow_support.safe(rec.source).trim());
    }

    public static String readOnlyMessage(DocumentRec rec) {
        String src = document_workflow_support.safe(rec == null ? "" : rec.source).trim().toLowerCase();
        if ("clio".equals(src)) {
            return "This document is synced from Clio and is read-only. Edit it in Clio.";
        }
        return "This document is read-only and cannot be edited here.";
    }

    public static boolean isCheckoutActive(DocumentRec rec) {
        return isCheckoutActive(rec, Instant.now());
    }

    public static boolean isCheckoutActive(DocumentRec rec, Instant now) {
        if (rec == null) return false;
        if (normalizeActor(rec.checkedOutBy).isBlank()) return false;
        Instant checkedOutAt = parseInstant(rec.checkedOutAt);
        if (checkedOutAt == null) return false;
        Instant clock = now == null ? Instant.now() : now;
        return checkedOutAt.plus(CHECKOUT_TTL).isAfter(clock);
    }

    public static boolean isCheckedOutBy(DocumentRec rec, String actor) {
        return isCheckedOutBy(rec, actor, Instant.now());
    }

    public static boolean isCheckedOutBy(DocumentRec rec, String actor, Instant now) {
        if (!isCheckoutActive(rec, now)) return false;
        return normalizeActor(rec.checkedOutBy).equals(normalizeActor(actor));
    }

    public static boolean isCheckedOutByOther(DocumentRec rec, String actor) {
        return isCheckedOutByOther(rec, actor, Instant.now());
    }

    public static boolean isCheckedOutByOther(DocumentRec rec, String actor, Instant now) {
        if (!isCheckoutActive(rec, now)) return false;
        String lockOwner = normalizeActor(rec == null ? "" : rec.checkedOutBy);
        String normalizedActor = normalizeActor(actor);
        if (normalizedActor.isBlank()) return !lockOwner.isBlank();
        return !lockOwner.equals(normalizedActor);
    }

    public static String checkoutBy(DocumentRec rec) {
        return document_workflow_support.safe(rec == null ? "" : rec.checkedOutBy).trim();
    }

    public static String checkoutAt(DocumentRec rec) {
        return document_workflow_support.safe(rec == null ? "" : rec.checkedOutAt).trim();
    }

    public static String checkoutExpiresAt(DocumentRec rec) {
        Instant expiresAt = checkoutExpiresAtInstant(rec);
        return expiresAt == null ? "" : expiresAt.toString();
    }

    public static String checkoutMessage(DocumentRec rec) {
        return checkoutMessage(rec, currentActor());
    }

    public static String checkoutMessage(DocumentRec rec, String actor) {
        if (!isCheckedOutByOther(rec, actor, Instant.now())) return "";
        String lockOwner = checkoutBy(rec);
        if (lockOwner.isBlank()) lockOwner = "another user";
        String expiresAt = checkoutExpiresAt(rec);
        if (!expiresAt.isBlank()) {
            return "This document is checked out by " + lockOwner + " until " + expiresAt + ".";
        }
        return "This document is checked out by " + lockOwner + ".";
    }

    public static void requireEditable(DocumentRec rec) {
        requireEditable(rec, currentActor());
    }

    public static void requireEditable(DocumentRec rec, String actor) {
        if (isReadOnly(rec)) throw new IllegalStateException(readOnlyMessage(rec));
        if (isCheckedOutByOther(rec, actor, Instant.now())) {
            throw new IllegalStateException(checkoutMessage(rec, actor));
        }
    }

    public boolean checkOut(String tenantUuid, String matterUuid, String docUuid) throws Exception {
        return checkOut(tenantUuid, matterUuid, docUuid, currentActor());
    }

    public boolean checkOut(String tenantUuid, String matterUuid, String docUuid, String actor) throws Exception {
        String normalizedActor = normalizeActor(actor);
        if (normalizedActor.isBlank()) throw new IllegalStateException("Unable to determine current user for checkout.");
        List<DocumentRec> all = listAll(tenantUuid, matterUuid);
        boolean changed = false;
        DocumentRec changedRec = null;
        Instant now = Instant.now();
        String nowIso = now.toString();
        for (DocumentRec rec : all) {
            if (!document_workflow_support.safe(docUuid).trim().equals(document_workflow_support.safe(rec.uuid).trim())) continue;
            requireEditable(rec, normalizedActor);
            if (isCheckedOutBy(rec, normalizedActor, now)) return false;
            rec.checkedOutBy = normalizedActor;
            rec.checkedOutAt = nowIso;
            rec.updatedAt = nowIso;
            changed = true;
            changedRec = rec;
        }
        if (changed) {
            writeAll(tenantUuid, matterUuid, all);
            publishDocumentEvent(tenantUuid, matterUuid, "document.checked_out", changedRec);
        }
        return changed;
    }

    public boolean checkIn(String tenantUuid, String matterUuid, String docUuid) throws Exception {
        return checkIn(tenantUuid, matterUuid, docUuid, currentActor());
    }

    public boolean checkIn(String tenantUuid, String matterUuid, String docUuid, String actor) throws Exception {
        String normalizedActor = normalizeActor(actor);
        if (normalizedActor.isBlank()) throw new IllegalStateException("Unable to determine current user for check-in.");
        List<DocumentRec> all = listAll(tenantUuid, matterUuid);
        boolean changed = false;
        DocumentRec changedRec = null;
        Instant now = Instant.now();
        String nowIso = now.toString();
        for (DocumentRec rec : all) {
            if (!document_workflow_support.safe(docUuid).trim().equals(document_workflow_support.safe(rec.uuid).trim())) continue;
            if (isReadOnly(rec)) throw new IllegalStateException(readOnlyMessage(rec));
            if (isCheckedOutByOther(rec, normalizedActor, now)) {
                throw new IllegalStateException(checkoutMessage(rec, normalizedActor));
            }
            if (!document_workflow_support.safe(rec.checkedOutBy).isBlank()
                    || !document_workflow_support.safe(rec.checkedOutAt).isBlank()) {
                rec.checkedOutBy = "";
                rec.checkedOutAt = "";
                rec.updatedAt = nowIso;
                changed = true;
                changedRec = rec;
            }
        }
        if (changed) {
            writeAll(tenantUuid, matterUuid, all);
            publishDocumentEvent(tenantUuid, matterUuid, "document.checked_in", changedRec);
        }
        return changed;
    }

    public Path documentFolder(String tenantUuid, String matterUuid, String docUuid) {
        String tu = safeFileToken(tenantUuid); String mu = safeFileToken(matterUuid); String du = safeFileToken(docUuid);
        if (tu.isBlank() || mu.isBlank() || du.isBlank()) return null;
        return Paths.get("data", "tenants", tu, "matters", mu, "documents", du).toAbsolutePath();
    }

    private static DocumentRec readRec(Element e) {
        DocumentRec rec = new DocumentRec();
        rec.uuid = document_workflow_support.text(e, "uuid");
        rec.title = document_workflow_support.text(e, "title");
        rec.category = document_workflow_support.text(e, "category");
        rec.subcategory = document_workflow_support.text(e, "subcategory");
        rec.status = document_workflow_support.text(e, "status");
        rec.owner = document_workflow_support.text(e, "owner");
        rec.privilegeLevel = document_workflow_support.text(e, "privilege_level");
        rec.filedOn = document_workflow_support.text(e, "filed_on");
        rec.externalReference = document_workflow_support.text(e, "external_reference");
        rec.notes = document_workflow_support.text(e, "notes");
        rec.createdAt = document_workflow_support.text(e, "created_at");
        rec.updatedAt = document_workflow_support.text(e, "updated_at");
        rec.trashed = "true".equalsIgnoreCase(document_workflow_support.text(e, "trashed"));
        rec.source = document_workflow_support.text(e, "source");
        rec.sourceDocumentId = document_workflow_support.text(e, "source_document_id");
        rec.sourceUpdatedAt = document_workflow_support.text(e, "source_updated_at");
        rec.readOnly = "true".equalsIgnoreCase(document_workflow_support.text(e, "read_only"));
        rec.checkedOutBy = document_workflow_support.text(e, "checked_out_by");
        rec.checkedOutAt = document_workflow_support.text(e, "checked_out_at");
        return rec;
    }

    private void writeAll(String tenantUuid, String matterUuid, List<DocumentRec> all) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<documents updated=\"")
          .append(document_workflow_support.xmlText(document_workflow_support.nowIso())).append("\">\n");
        for (DocumentRec rec : all) {
            if (rec == null || document_workflow_support.safe(rec.uuid).isBlank()) continue;
            sb.append("  <document>\n");
            writeTag(sb, "uuid", rec.uuid);
            writeTag(sb, "title", rec.title);
            writeTag(sb, "category", rec.category);
            writeTag(sb, "subcategory", rec.subcategory);
            writeTag(sb, "status", rec.status);
            writeTag(sb, "owner", rec.owner);
            writeTag(sb, "privilege_level", rec.privilegeLevel);
            writeTag(sb, "filed_on", rec.filedOn);
            writeTag(sb, "external_reference", rec.externalReference);
            writeTag(sb, "notes", rec.notes);
            writeTag(sb, "created_at", rec.createdAt);
            writeTag(sb, "updated_at", rec.updatedAt);
            writeTag(sb, "trashed", rec.trashed ? "true" : "false");
            writeTag(sb, "source", rec.source);
            writeTag(sb, "source_document_id", rec.sourceDocumentId);
            writeTag(sb, "source_updated_at", rec.sourceUpdatedAt);
            writeTag(sb, "read_only", rec.readOnly ? "true" : "false");
            writeTag(sb, "checked_out_by", rec.checkedOutBy);
            writeTag(sb, "checked_out_at", rec.checkedOutAt);
            sb.append("  </document>\n");
        }
        sb.append("</documents>\n");
        document_workflow_support.writeAtomic(docsPath(tenantUuid, matterUuid), sb.toString());
    }

    private static void writeTag(StringBuilder sb, String tag, String value) {
        sb.append("    <").append(tag).append(">")
          .append(document_workflow_support.xmlText(value))
          .append("</").append(tag).append(">\n");
    }

    private static Path docsPath(String tenantUuid, String matterUuid) {
        String tu = safeFileToken(tenantUuid); String mu = safeFileToken(matterUuid);
        if (tu.isBlank() || mu.isBlank()) return null;
        return Paths.get("data", "tenants", tu, "matters", mu, "documents.xml").toAbsolutePath();
    }

    private static void publishDocumentEvent(String tenantUuid, String matterUuid, String eventType, DocumentRec rec) {
        if (rec == null) return;
        try {
            LinkedHashMap<String, String> payload = new LinkedHashMap<String, String>();
            payload.put("matter_uuid", document_workflow_support.safe(matterUuid));
            payload.put("doc_uuid", document_workflow_support.safe(rec.uuid));
            payload.put("title", document_workflow_support.safe(rec.title));
            payload.put("category", document_workflow_support.safe(rec.category));
            payload.put("subcategory", document_workflow_support.safe(rec.subcategory));
            payload.put("status", document_workflow_support.safe(rec.status));
            payload.put("owner", document_workflow_support.safe(rec.owner));
            payload.put("privilege_level", document_workflow_support.safe(rec.privilegeLevel));
            payload.put("filed_on", document_workflow_support.safe(rec.filedOn));
            payload.put("external_reference", document_workflow_support.safe(rec.externalReference));
            payload.put("notes", document_workflow_support.safe(rec.notes));
            payload.put("created_at", document_workflow_support.safe(rec.createdAt));
            payload.put("updated_at", document_workflow_support.safe(rec.updatedAt));
            payload.put("trashed", rec.trashed ? "true" : "false");
            payload.put("source", document_workflow_support.safe(rec.source));
            payload.put("source_document_id", document_workflow_support.safe(rec.sourceDocumentId));
            payload.put("source_updated_at", document_workflow_support.safe(rec.sourceUpdatedAt));
            payload.put("read_only", rec.readOnly ? "true" : "false");
            payload.put("checked_out_by", document_workflow_support.safe(rec.checkedOutBy));
            payload.put("checked_out_at", document_workflow_support.safe(rec.checkedOutAt));
            business_process_manager.defaultService().triggerEvent(
                    document_workflow_support.safe(tenantUuid),
                    document_workflow_support.safe(eventType),
                    payload,
                    "",
                    "documents.store"
            );
        } catch (Exception ignored) {
        }
    }

    private static String safeFileToken(String s) {
        return document_workflow_support.safe(s).trim().replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String currentActor() {
        return normalizeActor(ACTOR_CONTEXT.get());
    }

    private static String normalizeActor(String actor) {
        return document_workflow_support.safe(actor).trim().toLowerCase(Locale.ROOT);
    }

    private static Instant parseInstant(String raw) {
        String v = document_workflow_support.safe(raw).trim();
        if (v.isBlank()) return null;
        try {
            return Instant.parse(v);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Instant checkoutExpiresAtInstant(DocumentRec rec) {
        Instant checkedOutAt = parseInstant(rec == null ? "" : rec.checkedOutAt);
        if (checkedOutAt == null) return null;
        return checkedOutAt.plus(CHECKOUT_TTL);
    }
}
