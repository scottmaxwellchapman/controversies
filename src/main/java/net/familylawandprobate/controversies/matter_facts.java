package net.familylawandprobate.controversies;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
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
 * Matter-scoped case-plan hierarchy store:
 * Claims -> Elements -> Facts.
 *
 * Layout:
 * data/tenants/{tenant}/matters/{matter}/facts/facts.xml
 *
 * Facts changes regenerate a matter-linked landscape PDF report that is stored
 * as a document part version in the matter's document system.
 */
public final class matter_facts {

    private static final Logger LOG = Logger.getLogger(matter_facts.class.getName());
    private static final ConcurrentHashMap<String, ReentrantReadWriteLock> LOCKS = new ConcurrentHashMap<String, ReentrantReadWriteLock>();

    private static final int MAX_TITLE_LEN = 240;
    private static final int MAX_SUMMARY_LEN = 1200;
    private static final int MAX_DETAIL_LEN = 8000;
    private static final int MAX_NOTES_LEN = 8000;
    private static final int MAX_STATUS_LEN = 40;
    private static final int MAX_STRENGTH_LEN = 40;
    private static final int MAX_ACTOR_LEN = 320;

    private static final String STATUS_UNVERIFIED = "unverified";
    private static final String STATUS_CORROBORATED = "corroborated";
    private static final String STATUS_DISPUTED = "disputed";
    private static final String STATUS_ADMITTED = "admitted";
    private static final String STATUS_PROVEN = "proven";

    private static final String STRENGTH_LOW = "low";
    private static final String STRENGTH_MEDIUM = "medium";
    private static final String STRENGTH_HIGH = "high";
    private static final String STRENGTH_CRITICAL = "critical";

    public static final class ClaimRec {
        public String uuid;
        public String title;
        public String summary;
        public int sortOrder;
        public String createdAt;
        public String updatedAt;
        public boolean trashed;
    }

    public static final class ElementRec {
        public String uuid;
        public String claimUuid;
        public String title;
        public String notes;
        public int sortOrder;
        public String createdAt;
        public String updatedAt;
        public boolean trashed;
    }

    public static final class FactRec {
        public String uuid;
        public String claimUuid;
        public String elementUuid;
        public String summary;
        public String detail;
        public String internalNotes;
        public String status;
        public String strength;
        public String documentUuid;
        public String partUuid;
        public String versionUuid;
        public int pageNumber;
        public int sortOrder;
        public String createdAt;
        public String updatedAt;
        public boolean trashed;
    }

    public static final class ReportRec {
        public String reportDocumentUuid;
        public String reportPartUuid;
        public String lastReportVersionUuid;
        public String reportGeneratedAt;
    }

    private static final class MatterFactsData {
        final List<ClaimRec> claims = new ArrayList<ClaimRec>();
        final List<ElementRec> elements = new ArrayList<ElementRec>();
        final List<FactRec> facts = new ArrayList<FactRec>();
        String reportDocumentUuid = "";
        String reportPartUuid = "";
        String lastReportVersionUuid = "";
        String reportGeneratedAt = "";
    }

    private static final class PdfRow {
        final String left;
        final String right;
        final boolean bold;

        PdfRow(String left, String right, boolean bold) {
            this.left = left;
            this.right = right;
            this.bold = bold;
        }
    }

    public static matter_facts defaultStore() {
        return new matter_facts();
    }

    public void ensure(String tenantUuid, String matterUuid) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String mu = safeFileToken(matterUuid);
        if (tu.isBlank() || mu.isBlank()) {
            throw new IllegalArgumentException("tenantUuid and matterUuid are required.");
        }
        ReentrantReadWriteLock lock = lockFor(tu, mu);
        lock.writeLock().lock();
        try {
            Path root = factsRoot(tu, mu);
            Files.createDirectories(root);
            Path p = factsPath(tu, mu);
            if (!Files.exists(p)) {
                writeAtomic(p, emptyFactsXml());
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<ClaimRec> listClaims(String tenantUuid, String matterUuid) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String mu = safeFileToken(matterUuid);
        if (tu.isBlank() || mu.isBlank()) return List.of();
        ensure(tu, mu);
        ReentrantReadWriteLock lock = lockFor(tu, mu);
        lock.readLock().lock();
        try {
            MatterFactsData data = readDataLocked(tu, mu);
            sortClaims(data.claims);
            return new ArrayList<ClaimRec>(data.claims);
        } finally {
            lock.readLock().unlock();
        }
    }

    public ClaimRec getClaim(String tenantUuid, String matterUuid, String claimUuid) throws Exception {
        String id = safe(claimUuid).trim();
        if (id.isBlank()) return null;
        List<ClaimRec> rows = listClaims(tenantUuid, matterUuid);
        for (int i = 0; i < rows.size(); i++) {
            ClaimRec row = rows.get(i);
            if (row == null) continue;
            if (id.equals(safe(row.uuid).trim())) return row;
        }
        return null;
    }

    public ClaimRec createClaim(String tenantUuid,
                                String matterUuid,
                                String title,
                                String summary,
                                int sortOrder,
                                String actor) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String mu = safeFileToken(matterUuid);
        String cleanTitle = clampLen(title, MAX_TITLE_LEN).trim();
        if (tu.isBlank() || mu.isBlank()) throw new IllegalArgumentException("tenantUuid and matterUuid are required.");
        if (cleanTitle.isBlank()) throw new IllegalArgumentException("Claim title is required.");

        ReentrantReadWriteLock lock = lockFor(tu, mu);
        lock.writeLock().lock();
        try {
            ensure(tu, mu);
            ensureMatterExists(tu, mu);
            MatterFactsData data = readDataLocked(tu, mu);

            ClaimRec rec = new ClaimRec();
            rec.uuid = UUID.randomUUID().toString();
            rec.title = cleanTitle;
            rec.summary = clampLen(summary, MAX_SUMMARY_LEN).trim();
            rec.sortOrder = sanitizeSortOrder(sortOrder, data.claims.size() + 1);
            rec.createdAt = nowIso();
            rec.updatedAt = rec.createdAt;
            rec.trashed = false;
            data.claims.add(rec);
            sortClaims(data.claims);

            refreshReportLocked(tu, mu, data, actor);
            writeDataLocked(tu, mu, data);

            LinkedHashMap<String, String> details = new LinkedHashMap<String, String>();
            details.put("claim_uuid", safe(rec.uuid));
            details.put("claim_title", safe(rec.title));
            audit("facts.claim.created", tu, actor, mu, details);
            publishFactsEvent(
                    tu,
                    "facts.claim.created",
                    Map.of(
                            "matter_uuid", mu,
                            "claim_uuid", safe(rec.uuid),
                            "claim_title", safe(rec.title)
                    )
            );
            return rec;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean updateClaim(String tenantUuid,
                               String matterUuid,
                               ClaimRec in,
                               String actor) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String mu = safeFileToken(matterUuid);
        String id = safe(in == null ? "" : in.uuid).trim();
        if (tu.isBlank() || mu.isBlank()) throw new IllegalArgumentException("tenantUuid and matterUuid are required.");
        if (id.isBlank()) throw new IllegalArgumentException("claim uuid is required.");

        ReentrantReadWriteLock lock = lockFor(tu, mu);
        lock.writeLock().lock();
        try {
            ensure(tu, mu);
            MatterFactsData data = readDataLocked(tu, mu);
            boolean changed = false;
            ClaimRec changedRec = null;
            for (int i = 0; i < data.claims.size(); i++) {
                ClaimRec row = data.claims.get(i);
                if (row == null) continue;
                if (!id.equals(safe(row.uuid).trim())) continue;

                String title = clampLen(in.title, MAX_TITLE_LEN).trim();
                if (title.isBlank()) throw new IllegalArgumentException("Claim title is required.");
                row.title = title;
                row.summary = clampLen(in.summary, MAX_SUMMARY_LEN).trim();
                row.sortOrder = sanitizeSortOrder(in.sortOrder, row.sortOrder <= 0 ? i + 1 : row.sortOrder);
                row.updatedAt = nowIso();
                changed = true;
                changedRec = row;
                break;
            }
            if (!changed) return false;
            sortClaims(data.claims);
            refreshReportLocked(tu, mu, data, actor);
            writeDataLocked(tu, mu, data);

            LinkedHashMap<String, String> details = new LinkedHashMap<String, String>();
            details.put("claim_uuid", safe(changedRec == null ? "" : changedRec.uuid));
            details.put("claim_title", safe(changedRec == null ? "" : changedRec.title));
            audit("facts.claim.updated", tu, actor, mu, details);
            publishFactsEvent(
                    tu,
                    "facts.claim.updated",
                    Map.of(
                            "matter_uuid", mu,
                            "claim_uuid", safe(changedRec == null ? "" : changedRec.uuid),
                            "claim_title", safe(changedRec == null ? "" : changedRec.title)
                    )
            );
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean setClaimTrashed(String tenantUuid,
                                   String matterUuid,
                                   String claimUuid,
                                   boolean trashed,
                                   String actor) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String mu = safeFileToken(matterUuid);
        String id = safe(claimUuid).trim();
        if (tu.isBlank() || mu.isBlank()) throw new IllegalArgumentException("tenantUuid and matterUuid are required.");
        if (id.isBlank()) throw new IllegalArgumentException("claim uuid is required.");

        ReentrantReadWriteLock lock = lockFor(tu, mu);
        lock.writeLock().lock();
        try {
            ensure(tu, mu);
            MatterFactsData data = readDataLocked(tu, mu);
            boolean changed = false;
            String title = "";
            for (int i = 0; i < data.claims.size(); i++) {
                ClaimRec row = data.claims.get(i);
                if (row == null) continue;
                if (!id.equals(safe(row.uuid).trim())) continue;
                if (row.trashed != trashed) changed = true;
                row.trashed = trashed;
                row.updatedAt = nowIso();
                title = safe(row.title);
            }
            if (!changed) return false;

            for (int i = 0; i < data.elements.size(); i++) {
                ElementRec row = data.elements.get(i);
                if (row == null) continue;
                if (!id.equals(safe(row.claimUuid).trim())) continue;
                row.trashed = trashed;
                row.updatedAt = nowIso();
            }
            for (int i = 0; i < data.facts.size(); i++) {
                FactRec row = data.facts.get(i);
                if (row == null) continue;
                if (!id.equals(safe(row.claimUuid).trim())) continue;
                row.trashed = trashed;
                row.updatedAt = nowIso();
            }

            refreshReportLocked(tu, mu, data, actor);
            writeDataLocked(tu, mu, data);
            audit(
                    trashed ? "facts.claim.trashed" : "facts.claim.restored",
                    tu,
                    actor,
                    mu,
                    Map.of("claim_uuid", id, "claim_title", title)
            );
            publishFactsEvent(
                    tu,
                    trashed ? "facts.claim.trashed" : "facts.claim.restored",
                    Map.of("matter_uuid", mu, "claim_uuid", id, "claim_title", title)
            );
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<ElementRec> listElements(String tenantUuid, String matterUuid, String claimUuidFilter) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String mu = safeFileToken(matterUuid);
        if (tu.isBlank() || mu.isBlank()) return List.of();
        String claimFilter = safe(claimUuidFilter).trim();
        ensure(tu, mu);
        ReentrantReadWriteLock lock = lockFor(tu, mu);
        lock.readLock().lock();
        try {
            MatterFactsData data = readDataLocked(tu, mu);
            sortElements(data.elements);
            ArrayList<ElementRec> out = new ArrayList<ElementRec>();
            for (int i = 0; i < data.elements.size(); i++) {
                ElementRec row = data.elements.get(i);
                if (row == null) continue;
                if (!claimFilter.isBlank() && !claimFilter.equals(safe(row.claimUuid).trim())) continue;
                out.add(row);
            }
            return out;
        } finally {
            lock.readLock().unlock();
        }
    }

    public ElementRec getElement(String tenantUuid, String matterUuid, String elementUuid) throws Exception {
        String id = safe(elementUuid).trim();
        if (id.isBlank()) return null;
        List<ElementRec> rows = listElements(tenantUuid, matterUuid, "");
        for (int i = 0; i < rows.size(); i++) {
            ElementRec row = rows.get(i);
            if (row == null) continue;
            if (id.equals(safe(row.uuid).trim())) return row;
        }
        return null;
    }

    public ElementRec createElement(String tenantUuid,
                                    String matterUuid,
                                    String claimUuid,
                                    String title,
                                    String notes,
                                    int sortOrder,
                                    String actor) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String mu = safeFileToken(matterUuid);
        String claimId = safe(claimUuid).trim();
        String cleanTitle = clampLen(title, MAX_TITLE_LEN).trim();
        if (tu.isBlank() || mu.isBlank()) throw new IllegalArgumentException("tenantUuid and matterUuid are required.");
        if (claimId.isBlank()) throw new IllegalArgumentException("claim_uuid is required.");
        if (cleanTitle.isBlank()) throw new IllegalArgumentException("Element title is required.");

        ReentrantReadWriteLock lock = lockFor(tu, mu);
        lock.writeLock().lock();
        try {
            ensure(tu, mu);
            MatterFactsData data = readDataLocked(tu, mu);
            ClaimRec claim = findClaim(data.claims, claimId);
            if (claim == null || claim.trashed) throw new IllegalArgumentException("Claim not found.");

            ElementRec rec = new ElementRec();
            rec.uuid = UUID.randomUUID().toString();
            rec.claimUuid = claimId;
            rec.title = cleanTitle;
            rec.notes = clampLen(notes, MAX_NOTES_LEN).trim();
            rec.sortOrder = sanitizeSortOrder(sortOrder, countElementsForClaim(data.elements, claimId) + 1);
            rec.createdAt = nowIso();
            rec.updatedAt = rec.createdAt;
            rec.trashed = false;
            data.elements.add(rec);
            sortElements(data.elements);

            refreshReportLocked(tu, mu, data, actor);
            writeDataLocked(tu, mu, data);

            audit(
                    "facts.element.created",
                    tu,
                    actor,
                    mu,
                    Map.of("element_uuid", safe(rec.uuid), "claim_uuid", claimId, "element_title", safe(rec.title))
            );
            publishFactsEvent(
                    tu,
                    "facts.element.created",
                    Map.of(
                            "matter_uuid", mu,
                            "claim_uuid", claimId,
                            "element_uuid", safe(rec.uuid),
                            "element_title", safe(rec.title)
                    )
            );
            return rec;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean updateElement(String tenantUuid,
                                 String matterUuid,
                                 ElementRec in,
                                 String actor) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String mu = safeFileToken(matterUuid);
        String id = safe(in == null ? "" : in.uuid).trim();
        if (tu.isBlank() || mu.isBlank()) throw new IllegalArgumentException("tenantUuid and matterUuid are required.");
        if (id.isBlank()) throw new IllegalArgumentException("element uuid is required.");

        ReentrantReadWriteLock lock = lockFor(tu, mu);
        lock.writeLock().lock();
        try {
            ensure(tu, mu);
            MatterFactsData data = readDataLocked(tu, mu);
            ElementRec changedRec = null;
            String oldClaim = "";
            String newClaim = safe(in.claimUuid).trim();
            if (newClaim.isBlank()) throw new IllegalArgumentException("claim_uuid is required.");

            ClaimRec claim = findClaim(data.claims, newClaim);
            if (claim == null || claim.trashed) throw new IllegalArgumentException("Claim not found.");

            boolean changed = false;
            for (int i = 0; i < data.elements.size(); i++) {
                ElementRec row = data.elements.get(i);
                if (row == null) continue;
                if (!id.equals(safe(row.uuid).trim())) continue;
                oldClaim = safe(row.claimUuid).trim();
                row.claimUuid = newClaim;
                String title = clampLen(in.title, MAX_TITLE_LEN).trim();
                if (title.isBlank()) throw new IllegalArgumentException("Element title is required.");
                row.title = title;
                row.notes = clampLen(in.notes, MAX_NOTES_LEN).trim();
                row.sortOrder = sanitizeSortOrder(in.sortOrder, row.sortOrder <= 0 ? i + 1 : row.sortOrder);
                row.updatedAt = nowIso();
                changed = true;
                changedRec = row;
                break;
            }
            if (!changed) return false;

            if (!oldClaim.equals(newClaim)) {
                for (int i = 0; i < data.facts.size(); i++) {
                    FactRec fact = data.facts.get(i);
                    if (fact == null) continue;
                    if (!id.equals(safe(fact.elementUuid).trim())) continue;
                    fact.claimUuid = newClaim;
                    fact.updatedAt = nowIso();
                }
            }

            sortElements(data.elements);
            sortFacts(data.facts);
            refreshReportLocked(tu, mu, data, actor);
            writeDataLocked(tu, mu, data);

            audit(
                    "facts.element.updated",
                    tu,
                    actor,
                    mu,
                    Map.of(
                            "element_uuid", safe(changedRec == null ? "" : changedRec.uuid),
                            "claim_uuid", safe(changedRec == null ? "" : changedRec.claimUuid),
                            "element_title", safe(changedRec == null ? "" : changedRec.title)
                    )
            );
            publishFactsEvent(
                    tu,
                    "facts.element.updated",
                    Map.of(
                            "matter_uuid", mu,
                            "claim_uuid", safe(changedRec == null ? "" : changedRec.claimUuid),
                            "element_uuid", safe(changedRec == null ? "" : changedRec.uuid),
                            "element_title", safe(changedRec == null ? "" : changedRec.title)
                    )
            );
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean setElementTrashed(String tenantUuid,
                                     String matterUuid,
                                     String elementUuid,
                                     boolean trashed,
                                     String actor) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String mu = safeFileToken(matterUuid);
        String id = safe(elementUuid).trim();
        if (tu.isBlank() || mu.isBlank()) throw new IllegalArgumentException("tenantUuid and matterUuid are required.");
        if (id.isBlank()) throw new IllegalArgumentException("element uuid is required.");

        ReentrantReadWriteLock lock = lockFor(tu, mu);
        lock.writeLock().lock();
        try {
            ensure(tu, mu);
            MatterFactsData data = readDataLocked(tu, mu);
            boolean changed = false;
            String claimUuid = "";
            String title = "";
            for (int i = 0; i < data.elements.size(); i++) {
                ElementRec row = data.elements.get(i);
                if (row == null) continue;
                if (!id.equals(safe(row.uuid).trim())) continue;
                if (row.trashed != trashed) changed = true;
                row.trashed = trashed;
                row.updatedAt = nowIso();
                claimUuid = safe(row.claimUuid);
                title = safe(row.title);
            }
            if (!changed) return false;
            for (int i = 0; i < data.facts.size(); i++) {
                FactRec row = data.facts.get(i);
                if (row == null) continue;
                if (!id.equals(safe(row.elementUuid).trim())) continue;
                row.trashed = trashed;
                row.updatedAt = nowIso();
            }

            refreshReportLocked(tu, mu, data, actor);
            writeDataLocked(tu, mu, data);

            audit(
                    trashed ? "facts.element.trashed" : "facts.element.restored",
                    tu,
                    actor,
                    mu,
                    Map.of("element_uuid", id, "claim_uuid", claimUuid, "element_title", title)
            );
            publishFactsEvent(
                    tu,
                    trashed ? "facts.element.trashed" : "facts.element.restored",
                    Map.of("matter_uuid", mu, "claim_uuid", claimUuid, "element_uuid", id, "element_title", title)
            );
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<FactRec> listFacts(String tenantUuid,
                                   String matterUuid,
                                   String claimUuidFilter,
                                   String elementUuidFilter) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String mu = safeFileToken(matterUuid);
        if (tu.isBlank() || mu.isBlank()) return List.of();
        String claimFilter = safe(claimUuidFilter).trim();
        String elementFilter = safe(elementUuidFilter).trim();
        ensure(tu, mu);
        ReentrantReadWriteLock lock = lockFor(tu, mu);
        lock.readLock().lock();
        try {
            MatterFactsData data = readDataLocked(tu, mu);
            sortFacts(data.facts);
            ArrayList<FactRec> out = new ArrayList<FactRec>();
            for (int i = 0; i < data.facts.size(); i++) {
                FactRec row = data.facts.get(i);
                if (row == null) continue;
                if (!claimFilter.isBlank() && !claimFilter.equals(safe(row.claimUuid).trim())) continue;
                if (!elementFilter.isBlank() && !elementFilter.equals(safe(row.elementUuid).trim())) continue;
                out.add(row);
            }
            return out;
        } finally {
            lock.readLock().unlock();
        }
    }

    public FactRec getFact(String tenantUuid, String matterUuid, String factUuid) throws Exception {
        String id = safe(factUuid).trim();
        if (id.isBlank()) return null;
        List<FactRec> rows = listFacts(tenantUuid, matterUuid, "", "");
        for (int i = 0; i < rows.size(); i++) {
            FactRec row = rows.get(i);
            if (row == null) continue;
            if (id.equals(safe(row.uuid).trim())) return row;
        }
        return null;
    }

    public FactRec createFact(String tenantUuid,
                              String matterUuid,
                              String claimUuid,
                              String elementUuid,
                              String summary,
                              String detail,
                              String internalNotes,
                              String status,
                              String strength,
                              String documentUuid,
                              String partUuid,
                              String versionUuid,
                              int pageNumber,
                              int sortOrder,
                              String actor) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String mu = safeFileToken(matterUuid);
        String elementId = safe(elementUuid).trim();
        String claimId = safe(claimUuid).trim();
        String cleanSummary = clampLen(summary, MAX_SUMMARY_LEN).trim();
        if (tu.isBlank() || mu.isBlank()) throw new IllegalArgumentException("tenantUuid and matterUuid are required.");
        if (elementId.isBlank()) throw new IllegalArgumentException("element_uuid is required.");
        if (cleanSummary.isBlank()) throw new IllegalArgumentException("Fact summary is required.");

        ReentrantReadWriteLock lock = lockFor(tu, mu);
        lock.writeLock().lock();
        try {
            ensure(tu, mu);
            MatterFactsData data = readDataLocked(tu, mu);

            ElementRec element = findElement(data.elements, elementId);
            if (element == null || element.trashed) throw new IllegalArgumentException("Element not found.");
            if (claimId.isBlank()) claimId = safe(element.claimUuid).trim();
            if (!claimId.equals(safe(element.claimUuid).trim())) {
                throw new IllegalArgumentException("claim_uuid must match element claim.");
            }
            ClaimRec claim = findClaim(data.claims, claimId);
            if (claim == null || claim.trashed) throw new IllegalArgumentException("Claim not found.");

            FactRec rec = new FactRec();
            rec.uuid = UUID.randomUUID().toString();
            rec.claimUuid = claimId;
            rec.elementUuid = elementId;
            rec.summary = cleanSummary;
            rec.detail = clampLen(detail, MAX_DETAIL_LEN).trim();
            rec.internalNotes = clampLen(internalNotes, MAX_NOTES_LEN).trim();
            rec.status = canonicalFactStatus(status);
            rec.strength = canonicalFactStrength(strength);
            rec.documentUuid = safe(documentUuid).trim();
            rec.partUuid = safe(partUuid).trim();
            rec.versionUuid = safe(versionUuid).trim();
            rec.pageNumber = sanitizePageNumber(pageNumber);
            rec.sortOrder = sanitizeSortOrder(sortOrder, countFactsForElement(data.facts, elementId) + 1);
            rec.createdAt = nowIso();
            rec.updatedAt = rec.createdAt;
            rec.trashed = false;

            validateFactLink(tu, mu, rec);
            data.facts.add(rec);
            sortFacts(data.facts);

            refreshReportLocked(tu, mu, data, actor);
            writeDataLocked(tu, mu, data);

            audit(
                    "facts.fact.created",
                    tu,
                    actor,
                    mu,
                    Map.of(
                            "fact_uuid", safe(rec.uuid),
                            "claim_uuid", safe(rec.claimUuid),
                            "element_uuid", safe(rec.elementUuid),
                            "document_uuid", safe(rec.documentUuid)
                    )
            );
            publishFactsEvent(
                    tu,
                    "facts.fact.created",
                    Map.of(
                            "matter_uuid", mu,
                            "claim_uuid", safe(rec.claimUuid),
                            "element_uuid", safe(rec.elementUuid),
                            "fact_uuid", safe(rec.uuid),
                            "status", safe(rec.status)
                    )
            );
            return rec;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean updateFact(String tenantUuid,
                              String matterUuid,
                              FactRec in,
                              String actor) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String mu = safeFileToken(matterUuid);
        String id = safe(in == null ? "" : in.uuid).trim();
        if (tu.isBlank() || mu.isBlank()) throw new IllegalArgumentException("tenantUuid and matterUuid are required.");
        if (id.isBlank()) throw new IllegalArgumentException("fact uuid is required.");

        ReentrantReadWriteLock lock = lockFor(tu, mu);
        lock.writeLock().lock();
        try {
            ensure(tu, mu);
            MatterFactsData data = readDataLocked(tu, mu);
            FactRec changedRec = null;
            boolean changed = false;
            for (int i = 0; i < data.facts.size(); i++) {
                FactRec row = data.facts.get(i);
                if (row == null) continue;
                if (!id.equals(safe(row.uuid).trim())) continue;

                String elementId = safe(in.elementUuid).trim();
                String claimId = safe(in.claimUuid).trim();
                if (elementId.isBlank()) elementId = safe(row.elementUuid).trim();
                if (claimId.isBlank()) claimId = safe(row.claimUuid).trim();
                if (elementId.isBlank() || claimId.isBlank()) {
                    throw new IllegalArgumentException("claim_uuid and element_uuid are required.");
                }

                ElementRec element = findElement(data.elements, elementId);
                if (element == null || element.trashed) throw new IllegalArgumentException("Element not found.");
                if (!claimId.equals(safe(element.claimUuid).trim())) {
                    throw new IllegalArgumentException("claim_uuid must match element claim.");
                }
                ClaimRec claim = findClaim(data.claims, claimId);
                if (claim == null || claim.trashed) throw new IllegalArgumentException("Claim not found.");

                String summary = clampLen(in.summary, MAX_SUMMARY_LEN).trim();
                if (summary.isBlank()) throw new IllegalArgumentException("Fact summary is required.");

                row.claimUuid = claimId;
                row.elementUuid = elementId;
                row.summary = summary;
                row.detail = clampLen(in.detail, MAX_DETAIL_LEN).trim();
                row.internalNotes = clampLen(in.internalNotes, MAX_NOTES_LEN).trim();
                row.status = canonicalFactStatus(in.status);
                row.strength = canonicalFactStrength(in.strength);
                row.documentUuid = safe(in.documentUuid).trim();
                row.partUuid = safe(in.partUuid).trim();
                row.versionUuid = safe(in.versionUuid).trim();
                row.pageNumber = sanitizePageNumber(in.pageNumber);
                row.sortOrder = sanitizeSortOrder(in.sortOrder, row.sortOrder <= 0 ? i + 1 : row.sortOrder);
                row.updatedAt = nowIso();
                validateFactLink(tu, mu, row);
                changedRec = row;
                changed = true;
                break;
            }
            if (!changed) return false;
            sortFacts(data.facts);
            refreshReportLocked(tu, mu, data, actor);
            writeDataLocked(tu, mu, data);

            audit(
                    "facts.fact.updated",
                    tu,
                    actor,
                    mu,
                    Map.of(
                            "fact_uuid", safe(changedRec == null ? "" : changedRec.uuid),
                            "claim_uuid", safe(changedRec == null ? "" : changedRec.claimUuid),
                            "element_uuid", safe(changedRec == null ? "" : changedRec.elementUuid),
                            "document_uuid", safe(changedRec == null ? "" : changedRec.documentUuid)
                    )
            );
            publishFactsEvent(
                    tu,
                    "facts.fact.updated",
                    Map.of(
                            "matter_uuid", mu,
                            "claim_uuid", safe(changedRec == null ? "" : changedRec.claimUuid),
                            "element_uuid", safe(changedRec == null ? "" : changedRec.elementUuid),
                            "fact_uuid", safe(changedRec == null ? "" : changedRec.uuid),
                            "status", safe(changedRec == null ? "" : changedRec.status)
                    )
            );
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean setFactTrashed(String tenantUuid,
                                  String matterUuid,
                                  String factUuid,
                                  boolean trashed,
                                  String actor) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String mu = safeFileToken(matterUuid);
        String id = safe(factUuid).trim();
        if (tu.isBlank() || mu.isBlank()) throw new IllegalArgumentException("tenantUuid and matterUuid are required.");
        if (id.isBlank()) throw new IllegalArgumentException("fact uuid is required.");

        ReentrantReadWriteLock lock = lockFor(tu, mu);
        lock.writeLock().lock();
        try {
            ensure(tu, mu);
            MatterFactsData data = readDataLocked(tu, mu);
            boolean changed = false;
            FactRec rowOut = null;
            for (int i = 0; i < data.facts.size(); i++) {
                FactRec row = data.facts.get(i);
                if (row == null) continue;
                if (!id.equals(safe(row.uuid).trim())) continue;
                if (row.trashed != trashed) changed = true;
                row.trashed = trashed;
                row.updatedAt = nowIso();
                rowOut = row;
            }
            if (!changed) return false;
            refreshReportLocked(tu, mu, data, actor);
            writeDataLocked(tu, mu, data);
            audit(
                    trashed ? "facts.fact.trashed" : "facts.fact.restored",
                    tu,
                    actor,
                    mu,
                    Map.of(
                            "fact_uuid", id,
                            "claim_uuid", safe(rowOut == null ? "" : rowOut.claimUuid),
                            "element_uuid", safe(rowOut == null ? "" : rowOut.elementUuid)
                    )
            );
            publishFactsEvent(
                    tu,
                    trashed ? "facts.fact.trashed" : "facts.fact.restored",
                    Map.of(
                            "matter_uuid", mu,
                            "claim_uuid", safe(rowOut == null ? "" : rowOut.claimUuid),
                            "element_uuid", safe(rowOut == null ? "" : rowOut.elementUuid),
                            "fact_uuid", id
                    )
            );
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public ReportRec reportRefs(String tenantUuid, String matterUuid) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String mu = safeFileToken(matterUuid);
        if (tu.isBlank() || mu.isBlank()) return emptyReport();
        ensure(tu, mu);
        ReentrantReadWriteLock lock = lockFor(tu, mu);
        lock.readLock().lock();
        try {
            MatterFactsData data = readDataLocked(tu, mu);
            return reportFromData(data);
        } finally {
            lock.readLock().unlock();
        }
    }

    public ReportRec refreshMatterReport(String tenantUuid, String matterUuid, String actor) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String mu = safeFileToken(matterUuid);
        if (tu.isBlank() || mu.isBlank()) throw new IllegalArgumentException("tenantUuid and matterUuid are required.");

        ReentrantReadWriteLock lock = lockFor(tu, mu);
        lock.writeLock().lock();
        try {
            ensure(tu, mu);
            MatterFactsData data = readDataLocked(tu, mu);
            ReportRec rec = refreshReportLocked(tu, mu, data, actor);
            writeDataLocked(tu, mu, data);
            audit(
                    "facts.report.refreshed",
                    tu,
                    actor,
                    mu,
                    Map.of(
                            "report_document_uuid", safe(rec.reportDocumentUuid),
                            "report_part_uuid", safe(rec.reportPartUuid),
                            "report_version_uuid", safe(rec.lastReportVersionUuid)
                    )
            );
            publishFactsEvent(
                    tu,
                    "facts.report.refreshed",
                    Map.of(
                            "matter_uuid", mu,
                            "report_document_uuid", safe(rec.reportDocumentUuid),
                            "report_part_uuid", safe(rec.reportPartUuid),
                            "report_version_uuid", safe(rec.lastReportVersionUuid)
                    )
            );
            return rec;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private static ReportRec refreshReportLocked(String tenantUuid,
                                                 String matterUuid,
                                                 MatterFactsData data,
                                                 String actor) throws Exception {
        if (data == null) throw new IllegalArgumentException("Facts data is required.");
        matters.MatterRec matter = matters.defaultStore().getByUuid(tenantUuid, matterUuid);
        if (matter == null || matter.trashed) throw new IllegalArgumentException("Matter not found.");

        documents docs = documents.defaultStore();
        document_parts parts = document_parts.defaultStore();
        part_versions versions = part_versions.defaultStore();

        String docUuid = safe(data.reportDocumentUuid).trim();
        String partUuid = safe(data.reportPartUuid).trim();

        String reportTitle = "Facts Case Plan Report";
        documents.DocumentRec docRec = null;
        if (!docUuid.isBlank()) {
            docRec = docs.get(tenantUuid, matterUuid, docUuid);
        }
        if (docRec == null) {
            docRec = docs.create(
                    tenantUuid,
                    matterUuid,
                    reportTitle,
                    "case_plan",
                    "facts",
                    "draft",
                    clampLen(actor, MAX_ACTOR_LEN).trim(),
                    "work_product",
                    "",
                    "facts:case_plan",
                    "Auto-generated claim/element/fact landscape report"
            );
            docUuid = safe(docRec == null ? "" : docRec.uuid).trim();
        } else {
            documents.DocumentRec in = new documents.DocumentRec();
            in.uuid = docRec.uuid;
            in.title = reportTitle;
            in.category = safe(docRec.category).trim().isBlank() ? "case_plan" : safe(docRec.category);
            in.subcategory = safe(docRec.subcategory).trim().isBlank() ? "facts" : safe(docRec.subcategory);
            in.status = safe(docRec.status).trim().isBlank() ? "draft" : safe(docRec.status);
            in.owner = clampLen(actor, MAX_ACTOR_LEN).trim();
            in.privilegeLevel = safe(docRec.privilegeLevel).trim().isBlank() ? "work_product" : safe(docRec.privilegeLevel);
            in.filedOn = safe(docRec.filedOn);
            in.externalReference = "facts:case_plan";
            in.notes = "Auto-generated claim/element/fact landscape report";
            docs.update(tenantUuid, matterUuid, in);
            docUuid = safe(docRec.uuid).trim();
        }

        document_parts.PartRec partRec = null;
        if (!partUuid.isBlank()) {
            partRec = parts.get(tenantUuid, matterUuid, docUuid, partUuid);
        }
        if (partRec == null) {
            partRec = parts.create(
                    tenantUuid,
                    matterUuid,
                    docUuid,
                    "Facts Report PDF",
                    "attachment",
                    "1",
                    "internal",
                    clampLen(actor, MAX_ACTOR_LEN).trim(),
                    "Auto-generated facts report history"
            );
            partUuid = safe(partRec == null ? "" : partRec.uuid).trim();
        }

        Path versionDir = parts.partFolder(tenantUuid, matterUuid, docUuid, partUuid).resolve("version_files");
        Files.createDirectories(versionDir);
        String fileName = ("facts_case_plan_" + nowIso() + ".pdf").replaceAll("[^A-Za-z0-9.]", "_");
        Path reportPath = versionDir.resolve(UUID.randomUUID().toString().replace("-", "_") + "__" + fileName).toAbsolutePath().normalize();
        pdf_redaction_service.requirePathWithinTenant(reportPath, tenantUuid, "Facts report path");

        List<ClaimRec> claims = activeClaims(data.claims);
        List<ElementRec> elements = activeElements(data.elements);
        List<FactRec> facts = activeFacts(data.facts);

        writeFactsReportPdf(reportPath, tenantUuid, matterUuid, matter, claims, elements, facts, actor);
        long bytes = Files.size(reportPath);
        String checksum = pdf_redaction_service.sha256(reportPath);
        part_versions.VersionRec version = versions.create(
                tenantUuid,
                matterUuid,
                docUuid,
                partUuid,
                "Facts Report " + nowIso(),
                "facts_case_plan",
                "application/pdf",
                checksum,
                String.valueOf(bytes),
                reportPath.toString(),
                clampLen(actor, MAX_ACTOR_LEN).trim(),
                "Generated from Claims->Elements->Facts updates",
                true
        );

        data.reportDocumentUuid = docUuid;
        data.reportPartUuid = partUuid;
        data.lastReportVersionUuid = safe(version == null ? "" : version.uuid).trim();
        data.reportGeneratedAt = nowIso();
        return reportFromData(data);
    }

    private static void writeFactsReportPdf(Path outputPdf,
                                            String tenantUuid,
                                            String matterUuid,
                                            matters.MatterRec matter,
                                            List<ClaimRec> claims,
                                            List<ElementRec> elements,
                                            List<FactRec> facts,
                                            String actor) throws Exception {
        if (outputPdf == null) throw new IllegalArgumentException("Output PDF path required.");
        Files.createDirectories(outputPdf.getParent());

        List<ClaimRec> claimRows = claims == null ? List.of() : claims;
        List<ElementRec> elementRows = elements == null ? List.of() : elements;
        List<FactRec> factRows = facts == null ? List.of() : facts;

        LinkedHashMap<String, ClaimRec> claimById = new LinkedHashMap<String, ClaimRec>();
        for (int i = 0; i < claimRows.size(); i++) {
            ClaimRec c = claimRows.get(i);
            if (c == null) continue;
            claimById.put(safe(c.uuid).trim(), c);
        }

        LinkedHashMap<String, List<ElementRec>> elementsByClaim = new LinkedHashMap<String, List<ElementRec>>();
        for (int i = 0; i < elementRows.size(); i++) {
            ElementRec e = elementRows.get(i);
            if (e == null) continue;
            String claimUuid = safe(e.claimUuid).trim();
            if (claimUuid.isBlank()) continue;
            elementsByClaim.computeIfAbsent(claimUuid, x -> new ArrayList<ElementRec>()).add(e);
        }
        for (List<ElementRec> rows : elementsByClaim.values()) {
            rows.sort(Comparator.comparingInt((ElementRec x) -> x == null ? Integer.MAX_VALUE : x.sortOrder)
                    .thenComparing(x -> safe(x == null ? "" : x.title).toLowerCase(Locale.ROOT)));
        }

        LinkedHashMap<String, List<FactRec>> factsByElement = new LinkedHashMap<String, List<FactRec>>();
        for (int i = 0; i < factRows.size(); i++) {
            FactRec f = factRows.get(i);
            if (f == null) continue;
            String elementUuid = safe(f.elementUuid).trim();
            if (elementUuid.isBlank()) continue;
            factsByElement.computeIfAbsent(elementUuid, x -> new ArrayList<FactRec>()).add(f);
        }
        for (List<FactRec> rows : factsByElement.values()) {
            rows.sort(Comparator.comparingInt((FactRec x) -> x == null ? Integer.MAX_VALUE : x.sortOrder)
                    .thenComparing(x -> safe(x == null ? "" : x.summary).toLowerCase(Locale.ROOT)));
        }

        LinkedHashMap<String, documents.DocumentRec> docByUuid = new LinkedHashMap<String, documents.DocumentRec>();
        LinkedHashMap<String, document_parts.PartRec> partByUuid = new LinkedHashMap<String, document_parts.PartRec>();
        LinkedHashMap<String, part_versions.VersionRec> versionByUuid = new LinkedHashMap<String, part_versions.VersionRec>();
        preloadReferenceMaps(tenantUuid, matterUuid, facts, docByUuid, partByUuid, versionByUuid);

        ArrayList<PdfRow> rows = new ArrayList<PdfRow>(1024);
        rows.add(new PdfRow("Case Plan Tree (Claims -> Elements -> Facts)", "Fact Detail / Document Reference", true));
        rows.add(new PdfRow("", "", false));

        int claimIdx = 0;
        for (int i = 0; i < claimRows.size(); i++) {
            ClaimRec claim = claimRows.get(i);
            if (claim == null) continue;
            claimIdx++;
            String claimId = safe(claim.uuid).trim();
            String claimLabel = "C" + claimIdx + "  " + safe(claim.title);
            String claimRight = safe(claim.summary);
            if (claimRight.isBlank()) claimRight = "No claim summary.";
            rows.add(new PdfRow(claimLabel, claimRight, true));

            List<ElementRec> elementList = elementsByClaim.getOrDefault(claimId, List.of());
            if (elementList.isEmpty()) {
                rows.add(new PdfRow("  (No elements)", "Add at least one element under this claim.", false));
                continue;
            }

            int elemIdx = 0;
            for (int ei = 0; ei < elementList.size(); ei++) {
                ElementRec element = elementList.get(ei);
                if (element == null) continue;
                elemIdx++;
                String elementId = safe(element.uuid).trim();
                String elementLabel = "  E" + claimIdx + "." + elemIdx + "  " + safe(element.title);
                String elementRight = safe(element.notes);
                if (elementRight.isBlank()) elementRight = "No element notes.";
                rows.add(new PdfRow(elementLabel, elementRight, false));

                List<FactRec> factList = factsByElement.getOrDefault(elementId, List.of());
                if (factList.isEmpty()) {
                    rows.add(new PdfRow("    (No facts)", "Add fact entries with linked source documents.", false));
                    continue;
                }

                int factIdx = 0;
                for (int fi = 0; fi < factList.size(); fi++) {
                    FactRec fact = factList.get(fi);
                    if (fact == null) continue;
                    factIdx++;
                    String factLabel = "    F" + claimIdx + "." + elemIdx + "." + factIdx + "  " + safe(fact.summary);
                    String detail = safe(fact.detail);
                    if (detail.isBlank()) detail = "(No detail)";
                    StringBuilder right = new StringBuilder(512);
                    right.append("Status=").append(safe(fact.status))
                            .append(" | Strength=").append(safe(fact.strength))
                            .append(" | Sort=").append(fact.sortOrder <= 0 ? 0 : fact.sortOrder);
                    right.append(" | Page=").append(fact.pageNumber <= 0 ? "n/a" : String.valueOf(fact.pageNumber));
                    right.append("\n");
                    right.append("Detail: ").append(detail);
                    if (!safe(fact.internalNotes).trim().isBlank()) {
                        right.append("\nInternal Notes: ").append(safe(fact.internalNotes));
                    }
                    right.append("\n");
                    right.append("Link: ").append(factLinkLabel(fact, docByUuid, partByUuid, versionByUuid));
                    rows.add(new PdfRow(factLabel, right.toString(), false));
                }
            }
        }

        if (claimIdx == 0) {
            rows.add(new PdfRow("(No claims)", "No active claims are defined for this matter.", false));
        }

        try (PDDocument pdf = new PDDocument()) {
            drawLandscapeSideTreeReport(pdf, rows, matter, actor, claimRows.size(), elementRows.size(), factRows.size());
            pdf.save(outputPdf.toFile());
        }
    }

    private static void preloadReferenceMaps(String tenantUuid,
                                             String matterUuid,
                                             List<FactRec> facts,
                                             Map<String, documents.DocumentRec> docByUuid,
                                             Map<String, document_parts.PartRec> partByUuid,
                                             Map<String, part_versions.VersionRec> versionByUuid) {
        if (facts == null || facts.isEmpty()) return;
        documents docs = documents.defaultStore();
        document_parts parts = document_parts.defaultStore();
        part_versions versions = part_versions.defaultStore();

        LinkedHashSet<String> docUuids = new LinkedHashSet<String>();
        for (int i = 0; i < facts.size(); i++) {
            FactRec fact = facts.get(i);
            if (fact == null) continue;
            String docId = safe(fact.documentUuid).trim();
            if (!docId.isBlank()) docUuids.add(docId);
        }

        for (String docUuid : docUuids) {
            try {
                documents.DocumentRec doc = docs.get(tenantUuid, matterUuid, docUuid);
                if (doc == null) continue;
                docByUuid.put(docUuid, doc);
                List<document_parts.PartRec> partRows = parts.listAll(tenantUuid, matterUuid, docUuid);
                for (int i = 0; i < partRows.size(); i++) {
                    document_parts.PartRec part = partRows.get(i);
                    if (part == null) continue;
                    partByUuid.put(safe(part.uuid).trim(), part);
                    List<part_versions.VersionRec> versionRows = versions.listAll(tenantUuid, matterUuid, docUuid, part.uuid);
                    for (int vi = 0; vi < versionRows.size(); vi++) {
                        part_versions.VersionRec ver = versionRows.get(vi);
                        if (ver == null) continue;
                        versionByUuid.put(safe(ver.uuid).trim(), ver);
                    }
                }
            } catch (Exception ex) {
                LOG.log(Level.FINE, "Facts PDF reference preload failed doc=" + docUuid + ": " + safe(ex.getMessage()), ex);
            }
        }
    }

    private static String factLinkLabel(FactRec fact,
                                        Map<String, documents.DocumentRec> docs,
                                        Map<String, document_parts.PartRec> parts,
                                        Map<String, part_versions.VersionRec> versions) {
        if (fact == null) return "No linked document.";
        String docUuid = safe(fact.documentUuid).trim();
        String partUuid = safe(fact.partUuid).trim();
        String versionUuid = safe(fact.versionUuid).trim();
        if (docUuid.isBlank()) return "No linked document.";

        StringBuilder sb = new StringBuilder(256);
        documents.DocumentRec doc = docs == null ? null : docs.get(docUuid);
        document_parts.PartRec part = parts == null ? null : parts.get(partUuid);
        part_versions.VersionRec version = versions == null ? null : versions.get(versionUuid);

        sb.append("Doc=");
        if (doc != null) sb.append(safe(doc.title)).append(" (").append(docUuid).append(")");
        else sb.append(docUuid);

        if (!partUuid.isBlank()) {
            sb.append(" | Part=");
            if (part != null) sb.append(safe(part.label)).append(" (").append(partUuid).append(")");
            else sb.append(partUuid);
        }
        if (!versionUuid.isBlank()) {
            sb.append(" | Version=");
            if (version != null) sb.append(safe(version.versionLabel)).append(" (").append(versionUuid).append(")");
            else sb.append(versionUuid);
        }
        if (fact.pageNumber > 0) {
            sb.append(" | Page=").append(fact.pageNumber);
        }
        return sb.toString();
    }

    private static void drawLandscapeSideTreeReport(PDDocument pdf,
                                                    List<PdfRow> rows,
                                                    matters.MatterRec matter,
                                                    String actor,
                                                    int claimCount,
                                                    int elementCount,
                                                    int factCount) throws Exception {
        if (pdf == null) throw new IllegalArgumentException("PDF document required.");

        final PDRectangle LANDSCAPE_LETTER = new PDRectangle(PDRectangle.LETTER.getHeight(), PDRectangle.LETTER.getWidth());
        final float margin = 24f;
        final float headerHeight = 50f;
        final float footerHeight = 18f;
        final float dividerGap = 16f;
        final float leftWidth = 260f;
        final float bodyFontSize = 9f;
        final float bodyLeading = 11.5f;
        final float leftChars = 45f;
        final float rightChars = 92f;

        PDPage page = new PDPage(LANDSCAPE_LETTER);
        pdf.addPage(page);
        PDPageContentStream cs = new PDPageContentStream(pdf, page);

        float pageW = LANDSCAPE_LETTER.getWidth();
        float pageH = LANDSCAPE_LETTER.getHeight();
        float rightX = margin + leftWidth + dividerGap;
        float y = pageH - margin;

        y = renderReportHeader(cs, margin, y, pageW, headerHeight, matter, actor, claimCount, elementCount, factCount);

        List<PdfRow> src = rows == null ? List.of() : rows;
        for (int i = 0; i < src.size(); i++) {
            PdfRow row = src.get(i);
            if (row == null) continue;

            List<String> leftLines = wrapForPdf(row.left, (int) leftChars);
            List<String> rightLines = wrapForPdf(row.right, (int) rightChars);
            int lines = Math.max(leftLines.size(), rightLines.size());
            if (lines <= 0) lines = 1;
            float blockHeight = (lines * bodyLeading) + 2f;

            if (y - blockHeight <= margin + footerHeight) {
                drawFooter(cs, margin, margin + 2f, pageW - (margin * 2f), i + 1);
                cs.close();
                page = new PDPage(LANDSCAPE_LETTER);
                pdf.addPage(page);
                cs = new PDPageContentStream(pdf, page);
                y = pageH - margin;
                y = renderReportHeader(cs, margin, y, pageW, headerHeight, matter, actor, claimCount, elementCount, factCount);
            }

            drawVerticalDivider(cs, margin + leftWidth + (dividerGap / 2f), y + 3f, y - blockHeight + 2f);
            for (int line = 0; line < lines; line++) {
                String left = line < leftLines.size() ? leftLines.get(line) : "";
                String right = line < rightLines.size() ? rightLines.get(line) : "";
                drawTextLine(cs, left, row.bold ? PDType1Font.HELVETICA_BOLD : PDType1Font.HELVETICA, bodyFontSize, margin, y - (line * bodyLeading));
                drawTextLine(cs, right, PDType1Font.HELVETICA, bodyFontSize, rightX, y - (line * bodyLeading));
            }
            y -= blockHeight;
        }

        drawFooter(cs, margin, margin + 2f, pageW - (margin * 2f), pdf.getNumberOfPages());
        cs.close();
    }

    private static float renderReportHeader(PDPageContentStream cs,
                                            float x,
                                            float y,
                                            float pageW,
                                            float headerHeight,
                                            matters.MatterRec matter,
                                            String actor,
                                            int claimCount,
                                            int elementCount,
                                            int factCount) throws Exception {
        String title = "Facts Case Plan Report (Landscape)";
        String meta1 = "Generated: " + nowIso() + " | By: " + safe(actor).trim();
        String meta2 = "Matter: " + safe(matter == null ? "" : matter.label);
        String counts = "Counts -> Claims: " + claimCount + ", Elements: " + elementCount + ", Facts: " + factCount;

        drawTextLine(cs, title, PDType1Font.HELVETICA_BOLD, 13f, x, y);
        drawTextLine(cs, meta1, PDType1Font.HELVETICA, 9f, x, y - 14f);
        drawTextLine(cs, meta2, PDType1Font.HELVETICA, 9f, x, y - 25f);
        drawTextLine(cs, counts, PDType1Font.HELVETICA_BOLD, 9f, x, y - 36f);
        drawHorizontalDivider(cs, x, y - headerHeight + 4f, x + pageW - (x * 2f));
        return y - headerHeight;
    }

    private static void drawFooter(PDPageContentStream cs, float x, float y, float width, int pageNumber) throws Exception {
        drawHorizontalDivider(cs, x, y + 10f, x + width);
        drawTextLine(cs, "Case Plan Tree: Claims -> Elements -> Facts", PDType1Font.HELVETICA, 8f, x, y);
        drawTextLine(cs, "Page " + pageNumber, PDType1Font.HELVETICA, 8f, x + width - 44f, y);
    }

    private static void drawHorizontalDivider(PDPageContentStream cs, float x1, float y, float x2) throws Exception {
        cs.moveTo(x1, y);
        cs.lineTo(x2, y);
        cs.stroke();
    }

    private static void drawVerticalDivider(PDPageContentStream cs, float x, float y1, float y2) throws Exception {
        cs.moveTo(x, y1);
        cs.lineTo(x, y2);
        cs.stroke();
    }

    private static void drawTextLine(PDPageContentStream cs,
                                     String text,
                                     PDType1Font font,
                                     float size,
                                     float x,
                                     float y) throws Exception {
        String line = sanitizePdfText(text);
        if (line.isBlank()) return;
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(line);
        cs.endText();
    }

    private static List<String> wrapForPdf(String raw, int width) {
        int max = Math.max(16, width);
        String text = safe(raw).replace('\r', ' ').trim();
        if (text.isBlank()) return List.of("");
        String[] baseLines = text.split("\n");
        ArrayList<String> out = new ArrayList<String>();
        for (int li = 0; li < baseLines.length; li++) {
            String src = safe(baseLines[li]).trim();
            if (src.isBlank()) {
                out.add("");
                continue;
            }
            String[] words = src.split("\\s+");
            StringBuilder row = new StringBuilder(Math.max(32, max));
            for (int i = 0; i < words.length; i++) {
                String w = safe(words[i]);
                if (w.isBlank()) continue;
                if (row.length() == 0) {
                    row.append(w);
                    continue;
                }
                if (row.length() + 1 + w.length() > max) {
                    out.add(row.toString());
                    row.setLength(0);
                    row.append(w);
                } else {
                    row.append(' ').append(w);
                }
            }
            if (row.length() > 0) out.add(row.toString());
        }
        if (out.isEmpty()) out.add("");
        return out;
    }

    private static String sanitizePdfText(String raw) {
        String s = safe(raw);
        StringBuilder out = new StringBuilder(Math.min(200, s.length()));
        int max = 180;
        for (int i = 0; i < s.length() && out.length() < max; i++) {
            char ch = s.charAt(i);
            if (ch == '\n' || ch == '\r' || ch == '\t') {
                out.append(' ');
            } else if (ch < 32 || ch > 126) {
                out.append('?');
            } else {
                out.append(ch);
            }
        }
        return out.toString();
    }

    private static void validateFactLink(String tenantUuid, String matterUuid, FactRec fact) throws Exception {
        if (fact == null) return;
        String docUuid = safe(fact.documentUuid).trim();
        String partUuid = safe(fact.partUuid).trim();
        String versionUuid = safe(fact.versionUuid).trim();

        if (docUuid.isBlank()) {
            if (!partUuid.isBlank() || !versionUuid.isBlank()) {
                throw new IllegalArgumentException("document_uuid is required when part/version linkage is supplied.");
            }
            fact.partUuid = "";
            fact.versionUuid = "";
            fact.pageNumber = sanitizePageNumber(fact.pageNumber);
            return;
        }

        documents.DocumentRec doc = documents.defaultStore().get(tenantUuid, matterUuid, docUuid);
        if (doc == null || doc.trashed) throw new IllegalArgumentException("Linked document not found.");

        if (partUuid.isBlank() && !versionUuid.isBlank()) {
            throw new IllegalArgumentException("part_uuid is required when version_uuid is supplied.");
        }
        if (!partUuid.isBlank()) {
            document_parts.PartRec part = document_parts.defaultStore().get(tenantUuid, matterUuid, docUuid, partUuid);
            if (part == null || part.trashed) throw new IllegalArgumentException("Linked part not found.");
            if (!versionUuid.isBlank()) {
                List<part_versions.VersionRec> versions = part_versions.defaultStore().listAll(tenantUuid, matterUuid, docUuid, partUuid);
                boolean found = false;
                for (int i = 0; i < versions.size(); i++) {
                    part_versions.VersionRec ver = versions.get(i);
                    if (ver == null) continue;
                    if (versionUuid.equals(safe(ver.uuid).trim())) {
                        found = true;
                        break;
                    }
                }
                if (!found) throw new IllegalArgumentException("Linked version not found.");
            }
        }

        fact.pageNumber = sanitizePageNumber(fact.pageNumber);
    }

    private static void ensureMatterExists(String tenantUuid, String matterUuid) throws Exception {
        matters.MatterRec m = matters.defaultStore().getByUuid(tenantUuid, matterUuid);
        if (m == null || m.trashed) throw new IllegalArgumentException("Matter not found.");
    }

    private static MatterFactsData readDataLocked(String tenantUuid, String matterUuid) throws Exception {
        MatterFactsData data = new MatterFactsData();
        Path p = factsPath(tenantUuid, matterUuid);
        if (!Files.exists(p)) return data;

        Document d = parseXml(p);
        Element root = d == null ? null : d.getDocumentElement();
        if (root == null) return data;

        data.reportDocumentUuid = safe(root.getAttribute("report_document_uuid")).trim();
        data.reportPartUuid = safe(root.getAttribute("report_part_uuid")).trim();
        data.lastReportVersionUuid = safe(root.getAttribute("last_report_version_uuid")).trim();
        data.reportGeneratedAt = safe(root.getAttribute("report_generated_at")).trim();

        NodeList claimNodes = root.getElementsByTagName("claim");
        for (int i = 0; i < claimNodes.getLength(); i++) {
            Node n = claimNodes.item(i);
            if (!(n instanceof Element)) continue;
            data.claims.add(readClaim((Element) n));
        }

        NodeList elementNodes = root.getElementsByTagName("element");
        for (int i = 0; i < elementNodes.getLength(); i++) {
            Node n = elementNodes.item(i);
            if (!(n instanceof Element)) continue;
            data.elements.add(readElement((Element) n));
        }

        NodeList factNodes = root.getElementsByTagName("fact");
        for (int i = 0; i < factNodes.getLength(); i++) {
            Node n = factNodes.item(i);
            if (!(n instanceof Element)) continue;
            data.facts.add(readFact((Element) n));
        }

        sortClaims(data.claims);
        sortElements(data.elements);
        sortFacts(data.facts);
        return data;
    }

    private static void writeDataLocked(String tenantUuid, String matterUuid, MatterFactsData data) throws Exception {
        Path p = factsPath(tenantUuid, matterUuid);
        Files.createDirectories(p.getParent());

        StringBuilder sb = new StringBuilder(16384);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<caseFacts updated=\"").append(xmlAttr(nowIso())).append("\"");
        sb.append(" report_document_uuid=\"").append(xmlAttr(safe(data == null ? "" : data.reportDocumentUuid))).append("\"");
        sb.append(" report_part_uuid=\"").append(xmlAttr(safe(data == null ? "" : data.reportPartUuid))).append("\"");
        sb.append(" last_report_version_uuid=\"").append(xmlAttr(safe(data == null ? "" : data.lastReportVersionUuid))).append("\"");
        sb.append(" report_generated_at=\"").append(xmlAttr(safe(data == null ? "" : data.reportGeneratedAt))).append("\">\n");

        List<ClaimRec> claims = data == null ? List.of() : data.claims;
        for (int i = 0; i < claims.size(); i++) {
            ClaimRec row = claims.get(i);
            if (row == null) continue;
            sb.append("  <claim>\n");
            writeTag(sb, "uuid", safe(row.uuid).trim().isBlank() ? UUID.randomUUID().toString() : safe(row.uuid).trim());
            writeTag(sb, "title", clampLen(row.title, MAX_TITLE_LEN).trim());
            writeTag(sb, "summary", clampLen(row.summary, MAX_SUMMARY_LEN).trim());
            writeTag(sb, "sort_order", String.valueOf(sanitizeSortOrder(row.sortOrder, i + 1)));
            writeTag(sb, "created_at", safe(row.createdAt).trim());
            writeTag(sb, "updated_at", safe(row.updatedAt).trim());
            writeTag(sb, "trashed", row.trashed ? "true" : "false");
            sb.append("  </claim>\n");
        }

        List<ElementRec> elements = data == null ? List.of() : data.elements;
        for (int i = 0; i < elements.size(); i++) {
            ElementRec row = elements.get(i);
            if (row == null) continue;
            sb.append("  <element>\n");
            writeTag(sb, "uuid", safe(row.uuid).trim().isBlank() ? UUID.randomUUID().toString() : safe(row.uuid).trim());
            writeTag(sb, "claim_uuid", safe(row.claimUuid).trim());
            writeTag(sb, "title", clampLen(row.title, MAX_TITLE_LEN).trim());
            writeTag(sb, "notes", clampLen(row.notes, MAX_NOTES_LEN).trim());
            writeTag(sb, "sort_order", String.valueOf(sanitizeSortOrder(row.sortOrder, i + 1)));
            writeTag(sb, "created_at", safe(row.createdAt).trim());
            writeTag(sb, "updated_at", safe(row.updatedAt).trim());
            writeTag(sb, "trashed", row.trashed ? "true" : "false");
            sb.append("  </element>\n");
        }

        List<FactRec> facts = data == null ? List.of() : data.facts;
        for (int i = 0; i < facts.size(); i++) {
            FactRec row = facts.get(i);
            if (row == null) continue;
            sb.append("  <fact>\n");
            writeTag(sb, "uuid", safe(row.uuid).trim().isBlank() ? UUID.randomUUID().toString() : safe(row.uuid).trim());
            writeTag(sb, "claim_uuid", safe(row.claimUuid).trim());
            writeTag(sb, "element_uuid", safe(row.elementUuid).trim());
            writeTag(sb, "summary", clampLen(row.summary, MAX_SUMMARY_LEN).trim());
            writeTag(sb, "detail", clampLen(row.detail, MAX_DETAIL_LEN).trim());
            writeTag(sb, "internal_notes", clampLen(row.internalNotes, MAX_NOTES_LEN).trim());
            writeTag(sb, "status", canonicalFactStatus(row.status));
            writeTag(sb, "strength", canonicalFactStrength(row.strength));
            writeTag(sb, "document_uuid", safe(row.documentUuid).trim());
            writeTag(sb, "part_uuid", safe(row.partUuid).trim());
            writeTag(sb, "version_uuid", safe(row.versionUuid).trim());
            writeTag(sb, "page_number", String.valueOf(sanitizePageNumber(row.pageNumber)));
            writeTag(sb, "sort_order", String.valueOf(sanitizeSortOrder(row.sortOrder, i + 1)));
            writeTag(sb, "created_at", safe(row.createdAt).trim());
            writeTag(sb, "updated_at", safe(row.updatedAt).trim());
            writeTag(sb, "trashed", row.trashed ? "true" : "false");
            sb.append("  </fact>\n");
        }

        sb.append("</caseFacts>\n");
        writeAtomic(p, sb.toString());
    }

    private static ClaimRec readClaim(Element e) {
        ClaimRec rec = new ClaimRec();
        rec.uuid = text(e, "uuid");
        if (safe(rec.uuid).trim().isBlank()) rec.uuid = UUID.randomUUID().toString();
        rec.title = clampLen(text(e, "title"), MAX_TITLE_LEN).trim();
        rec.summary = clampLen(text(e, "summary"), MAX_SUMMARY_LEN).trim();
        rec.sortOrder = sanitizeSortOrder(parseInt(text(e, "sort_order"), 0), 1);
        rec.createdAt = text(e, "created_at");
        rec.updatedAt = text(e, "updated_at");
        rec.trashed = parseBool(text(e, "trashed"), false);
        return rec;
    }

    private static ElementRec readElement(Element e) {
        ElementRec rec = new ElementRec();
        rec.uuid = text(e, "uuid");
        if (safe(rec.uuid).trim().isBlank()) rec.uuid = UUID.randomUUID().toString();
        rec.claimUuid = text(e, "claim_uuid");
        rec.title = clampLen(text(e, "title"), MAX_TITLE_LEN).trim();
        rec.notes = clampLen(text(e, "notes"), MAX_NOTES_LEN).trim();
        rec.sortOrder = sanitizeSortOrder(parseInt(text(e, "sort_order"), 0), 1);
        rec.createdAt = text(e, "created_at");
        rec.updatedAt = text(e, "updated_at");
        rec.trashed = parseBool(text(e, "trashed"), false);
        return rec;
    }

    private static FactRec readFact(Element e) {
        FactRec rec = new FactRec();
        rec.uuid = text(e, "uuid");
        if (safe(rec.uuid).trim().isBlank()) rec.uuid = UUID.randomUUID().toString();
        rec.claimUuid = text(e, "claim_uuid");
        rec.elementUuid = text(e, "element_uuid");
        rec.summary = clampLen(text(e, "summary"), MAX_SUMMARY_LEN).trim();
        rec.detail = clampLen(text(e, "detail"), MAX_DETAIL_LEN).trim();
        rec.internalNotes = clampLen(text(e, "internal_notes"), MAX_NOTES_LEN).trim();
        rec.status = canonicalFactStatus(text(e, "status"));
        rec.strength = canonicalFactStrength(text(e, "strength"));
        rec.documentUuid = text(e, "document_uuid");
        rec.partUuid = text(e, "part_uuid");
        rec.versionUuid = text(e, "version_uuid");
        rec.pageNumber = sanitizePageNumber(parseInt(text(e, "page_number"), 0));
        rec.sortOrder = sanitizeSortOrder(parseInt(text(e, "sort_order"), 0), 1);
        rec.createdAt = text(e, "created_at");
        rec.updatedAt = text(e, "updated_at");
        rec.trashed = parseBool(text(e, "trashed"), false);
        return rec;
    }

    private static ClaimRec findClaim(List<ClaimRec> rows, String claimUuid) {
        String id = safe(claimUuid).trim();
        if (id.isBlank() || rows == null) return null;
        for (int i = 0; i < rows.size(); i++) {
            ClaimRec row = rows.get(i);
            if (row == null) continue;
            if (id.equals(safe(row.uuid).trim())) return row;
        }
        return null;
    }

    private static ElementRec findElement(List<ElementRec> rows, String elementUuid) {
        String id = safe(elementUuid).trim();
        if (id.isBlank() || rows == null) return null;
        for (int i = 0; i < rows.size(); i++) {
            ElementRec row = rows.get(i);
            if (row == null) continue;
            if (id.equals(safe(row.uuid).trim())) return row;
        }
        return null;
    }

    private static int countElementsForClaim(List<ElementRec> rows, String claimUuid) {
        String id = safe(claimUuid).trim();
        int count = 0;
        if (id.isBlank() || rows == null) return count;
        for (int i = 0; i < rows.size(); i++) {
            ElementRec row = rows.get(i);
            if (row == null) continue;
            if (id.equals(safe(row.claimUuid).trim())) count++;
        }
        return count;
    }

    private static int countFactsForElement(List<FactRec> rows, String elementUuid) {
        String id = safe(elementUuid).trim();
        int count = 0;
        if (id.isBlank() || rows == null) return count;
        for (int i = 0; i < rows.size(); i++) {
            FactRec row = rows.get(i);
            if (row == null) continue;
            if (id.equals(safe(row.elementUuid).trim())) count++;
        }
        return count;
    }

    private static List<ClaimRec> activeClaims(List<ClaimRec> rows) {
        ArrayList<ClaimRec> out = new ArrayList<ClaimRec>();
        if (rows == null) return out;
        for (int i = 0; i < rows.size(); i++) {
            ClaimRec row = rows.get(i);
            if (row == null || row.trashed) continue;
            out.add(row);
        }
        sortClaims(out);
        return out;
    }

    private static List<ElementRec> activeElements(List<ElementRec> rows) {
        ArrayList<ElementRec> out = new ArrayList<ElementRec>();
        if (rows == null) return out;
        for (int i = 0; i < rows.size(); i++) {
            ElementRec row = rows.get(i);
            if (row == null || row.trashed) continue;
            out.add(row);
        }
        sortElements(out);
        return out;
    }

    private static List<FactRec> activeFacts(List<FactRec> rows) {
        ArrayList<FactRec> out = new ArrayList<FactRec>();
        if (rows == null) return out;
        for (int i = 0; i < rows.size(); i++) {
            FactRec row = rows.get(i);
            if (row == null || row.trashed) continue;
            out.add(row);
        }
        sortFacts(out);
        return out;
    }

    private static void sortClaims(List<ClaimRec> rows) {
        if (rows == null) return;
        rows.sort(
                Comparator.comparingInt((ClaimRec r) -> r == null ? Integer.MAX_VALUE : sanitizeSortOrder(r.sortOrder, Integer.MAX_VALUE))
                        .thenComparing(r -> safe(r == null ? "" : r.title).toLowerCase(Locale.ROOT))
                        .thenComparing(r -> safe(r == null ? "" : r.uuid))
        );
    }

    private static void sortElements(List<ElementRec> rows) {
        if (rows == null) return;
        rows.sort(
                Comparator.comparing((ElementRec r) -> safe(r == null ? "" : r.claimUuid))
                        .thenComparingInt(r -> r == null ? Integer.MAX_VALUE : sanitizeSortOrder(r.sortOrder, Integer.MAX_VALUE))
                        .thenComparing(r -> safe(r == null ? "" : r.title).toLowerCase(Locale.ROOT))
                        .thenComparing(r -> safe(r == null ? "" : r.uuid))
        );
    }

    private static void sortFacts(List<FactRec> rows) {
        if (rows == null) return;
        rows.sort(
                Comparator.comparing((FactRec r) -> safe(r == null ? "" : r.claimUuid))
                        .thenComparing(r -> safe(r == null ? "" : r.elementUuid))
                        .thenComparingInt(r -> r == null ? Integer.MAX_VALUE : sanitizeSortOrder(r.sortOrder, Integer.MAX_VALUE))
                        .thenComparing(r -> safe(r == null ? "" : r.summary).toLowerCase(Locale.ROOT))
                        .thenComparing(r -> safe(r == null ? "" : r.uuid))
        );
    }

    private static void writeTag(StringBuilder sb, String tag, String value) {
        sb.append("    <").append(tag).append(">")
                .append(xmlText(value))
                .append("</").append(tag).append(">\n");
    }

    private static ReportRec reportFromData(MatterFactsData data) {
        ReportRec out = new ReportRec();
        if (data == null) return out;
        out.reportDocumentUuid = safe(data.reportDocumentUuid);
        out.reportPartUuid = safe(data.reportPartUuid);
        out.lastReportVersionUuid = safe(data.lastReportVersionUuid);
        out.reportGeneratedAt = safe(data.reportGeneratedAt);
        return out;
    }

    private static ReportRec emptyReport() {
        return new ReportRec();
    }

    private static ReentrantReadWriteLock lockFor(String tenantUuid, String matterUuid) {
        String key = safeFileToken(tenantUuid) + "|" + safeFileToken(matterUuid);
        return LOCKS.computeIfAbsent(key, x -> new ReentrantReadWriteLock());
    }

    private static Path factsRoot(String tenantUuid, String matterUuid) {
        return Paths.get("data", "tenants", safeFileToken(tenantUuid), "matters", safeFileToken(matterUuid), "facts").toAbsolutePath();
    }

    private static Path factsPath(String tenantUuid, String matterUuid) {
        return factsRoot(tenantUuid, matterUuid).resolve("facts.xml");
    }

    private static String emptyFactsXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<caseFacts updated=\"" + xmlAttr(nowIso()) + "\""
                + " report_document_uuid=\"\" report_part_uuid=\"\" last_report_version_uuid=\"\" report_generated_at=\"\">"
                + "</caseFacts>\n";
    }

    private static String canonicalFactStatus(String raw) {
        String v = normalizeToken(raw, MAX_STATUS_LEN);
        if (STATUS_CORROBORATED.equals(v)) return STATUS_CORROBORATED;
        if (STATUS_DISPUTED.equals(v)) return STATUS_DISPUTED;
        if (STATUS_ADMITTED.equals(v)) return STATUS_ADMITTED;
        if (STATUS_PROVEN.equals(v)) return STATUS_PROVEN;
        return STATUS_UNVERIFIED;
    }

    private static String canonicalFactStrength(String raw) {
        String v = normalizeToken(raw, MAX_STRENGTH_LEN);
        if (STRENGTH_LOW.equals(v)) return STRENGTH_LOW;
        if (STRENGTH_HIGH.equals(v)) return STRENGTH_HIGH;
        if (STRENGTH_CRITICAL.equals(v)) return STRENGTH_CRITICAL;
        return STRENGTH_MEDIUM;
    }

    private static String normalizeToken(String raw, int maxLen) {
        String s = safe(raw).trim().toLowerCase(Locale.ROOT);
        if (s.isBlank()) return "";
        StringBuilder out = new StringBuilder(Math.min(maxLen, s.length()));
        for (int i = 0; i < s.length() && out.length() < Math.max(1, maxLen); i++) {
            char ch = s.charAt(i);
            boolean ok = (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '_' || ch == '-' || ch == '.';
            if (ok) out.append(ch);
        }
        String v = out.toString();
        if (v.length() > maxLen) v = v.substring(0, maxLen);
        return v;
    }

    private static int sanitizeSortOrder(int requested, int fallback) {
        int def = fallback <= 0 ? 1 : fallback;
        if (requested <= 0) return def;
        if (requested > 999999) return 999999;
        return requested;
    }

    private static int sanitizePageNumber(int page) {
        if (page <= 0) return 0;
        if (page > 200000) return 200000;
        return page;
    }

    private static int parseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(safe(raw).trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static boolean parseBool(String raw, boolean fallback) {
        String v = safe(raw).trim().toLowerCase(Locale.ROOT);
        if ("true".equals(v) || "1".equals(v) || "yes".equals(v) || "on".equals(v)) return true;
        if ("false".equals(v) || "0".equals(v) || "no".equals(v) || "off".equals(v)) return false;
        return fallback;
    }

    private static String clampLen(String raw, int maxLen) {
        String s = safe(raw);
        int lim = Math.max(1, maxLen);
        if (s.length() <= lim) return s;
        return s.substring(0, lim);
    }

    private static String nowIso() {
        return app_clock.now().toString();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String safeFileToken(String raw) {
        String s = safe(raw).trim();
        if (s.isBlank()) return "";
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
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

    private static String xmlText(String raw) {
        return document_workflow_support.xmlText(raw);
    }

    private static String xmlAttr(String raw) {
        return document_workflow_support.xmlText(raw);
    }

    private static void audit(String action,
                              String tenantUuid,
                              String actor,
                              String matterUuid,
                              Map<String, String> details) {
        try {
            activity_log.defaultStore().logVerbose(
                    safe(action).isBlank() ? "facts.event" : safe(action),
                    safe(tenantUuid),
                    clampLen(actor, MAX_ACTOR_LEN).trim(),
                    safe(matterUuid),
                    "",
                    details == null ? Map.of() : details
            );
        } catch (Exception ex) {
            LOG.log(Level.FINE, "Facts audit failure: " + safe(ex.getMessage()), ex);
        }
    }

    private static void publishFactsEvent(String tenantUuid, String eventType, Map<String, String> payload) {
        try {
            LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
            if (payload != null) {
                for (Map.Entry<String, String> e : payload.entrySet()) {
                    if (e == null) continue;
                    String k = safe(e.getKey()).trim();
                    if (k.isBlank()) continue;
                    out.put(k, safe(e.getValue()));
                }
            }
            business_process_manager.defaultService().triggerEvent(
                    safe(tenantUuid),
                    safe(eventType),
                    out,
                    "",
                    "matter_facts.store"
            );
        } catch (Exception ex) {
            LOG.log(Level.FINE, "Facts BPM event publish failure: " + safe(ex.getMessage()), ex);
        }
    }
}
