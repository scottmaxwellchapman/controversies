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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Per-matter conflicts XML store:
 * data/tenants/{tenantUuid}/matters/{matterUuid}/conflicts.xml
 */
public final class matter_conflicts {

    private static final ConcurrentHashMap<String, ReentrantReadWriteLock> LOCKS = new ConcurrentHashMap<String, ReentrantReadWriteLock>();
    private static final activity_log ACTIVITY_LOGS = activity_log.defaultStore();

    public static matter_conflicts defaultStore() {
        return new matter_conflicts();
    }

    public static final class ConflictEntry {
        public String uuid = "";
        public String entityType = "";        // person | organization
        public String displayName = "";
        public String normalizedName = "";
        public String sourceTags = "";
        public String sourceRefs = "";
        public String linkedContactUuids = "";
        public int occurrenceCount = 0;
        public String firstSeenAt = "";
        public String lastSeenAt = "";
        public String notes = "";
    }

    public static final class VersionScanState {
        public String versionUuid = "";
        public String fingerprint = "";
        public String scannedAt = "";
    }

    public static final class FileRec {
        public String updatedAt = "";
        public String lastScannedAt = "";
        public ArrayList<ConflictEntry> entries = new ArrayList<ConflictEntry>();
        public LinkedHashMap<String, VersionScanState> versionScanState = new LinkedHashMap<String, VersionScanState>();
    }

    public void ensure(String tenantUuid, String matterUuid) throws Exception {
        String tu = safe(tenantUuid).trim();
        String mu = safe(matterUuid).trim();
        if (tu.isBlank() || mu.isBlank()) {
            throw new IllegalArgumentException("tenantUuid and matterUuid are required.");
        }

        ReentrantReadWriteLock lock = lockFor(tu, mu);
        lock.writeLock().lock();
        try {
            Path p = conflictsPath(tu, mu);
            Files.createDirectories(p.getParent());
            if (!Files.exists(p)) {
                document_workflow_support.writeAtomic(p, emptyXml());
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void ensureForAllMatters(String tenantUuid) throws Exception {
        String tu = safe(tenantUuid).trim();
        if (tu.isBlank()) throw new IllegalArgumentException("tenantUuid is required.");
        List<matters.MatterRec> rows = matters.defaultStore().listAll(tu);
        for (matters.MatterRec row : rows) {
            if (row == null) continue;
            String mu = safe(row.uuid).trim();
            if (mu.isBlank()) continue;
            ensure(tu, mu);
        }
    }

    public FileRec read(String tenantUuid, String matterUuid) throws Exception {
        String tu = safe(tenantUuid).trim();
        String mu = safe(matterUuid).trim();
        if (tu.isBlank() || mu.isBlank()) return new FileRec();
        ensure(tu, mu);

        ReentrantReadWriteLock lock = lockFor(tu, mu);
        lock.readLock().lock();
        try {
            return readAllLocked(conflictsPath(tu, mu));
        } finally {
            lock.readLock().unlock();
        }
    }

    public void write(String tenantUuid, String matterUuid, FileRec file) throws Exception {
        String tu = safe(tenantUuid).trim();
        String mu = safe(matterUuid).trim();
        if (tu.isBlank() || mu.isBlank()) {
            throw new IllegalArgumentException("tenantUuid and matterUuid are required.");
        }

        ReentrantReadWriteLock lock = lockFor(tu, mu);
        lock.writeLock().lock();
        try {
            ensure(tu, mu);
            FileRec cleaned = normalizeFile(file);
            writeAllLocked(conflictsPath(tu, mu), cleaned);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public ConflictEntry upsertEntry(String tenantUuid, String matterUuid, ConflictEntry input) throws Exception {
        String tu = safe(tenantUuid).trim();
        String mu = safe(matterUuid).trim();
        if (tu.isBlank() || mu.isBlank()) {
            throw new IllegalArgumentException("tenantUuid and matterUuid are required.");
        }
        if (input == null) throw new IllegalArgumentException("Conflict entry is required.");

        ReentrantReadWriteLock lock = lockFor(tu, mu);
        lock.writeLock().lock();
        try {
            ensure(tu, mu);
            FileRec rec = readAllLocked(conflictsPath(tu, mu));
            LinkedHashMap<String, ConflictEntry> map = entryMap(rec.entries);
            ConflictEntry normalized = normalizeEntry(input);
            String key = entryKey(normalized);
            if (key.isBlank()) throw new IllegalArgumentException("Entity type and name are required.");

            ConflictEntry existing = map.get(key);
            ConflictEntry merged = mergeEntries(existing, normalized);
            map.put(key, merged);

            rec.entries = new ArrayList<ConflictEntry>(map.values());
            rec.updatedAt = Instant.now().toString();
            writeAllLocked(conflictsPath(tu, mu), rec);
            LinkedHashMap<String, String> details = new LinkedHashMap<String, String>();
            details.put("entry_uuid", safe(merged.uuid).trim());
            details.put("entity_type", safe(merged.entityType).trim());
            details.put("display_name", safe(merged.displayName).trim());
            details.put("source_tags", safe(merged.sourceTags).trim());
            details.put("source_refs", safe(merged.sourceRefs).trim());
            ACTIVITY_LOGS.logVerbose("conflicts.entry.upserted", tu, "", mu, "", details);
            return merged;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean deleteEntry(String tenantUuid, String matterUuid, String entryUuid) throws Exception {
        String tu = safe(tenantUuid).trim();
        String mu = safe(matterUuid).trim();
        String eu = safe(entryUuid).trim();
        if (tu.isBlank() || mu.isBlank() || eu.isBlank()) return false;

        ReentrantReadWriteLock lock = lockFor(tu, mu);
        lock.writeLock().lock();
        try {
            ensure(tu, mu);
            FileRec rec = readAllLocked(conflictsPath(tu, mu));
            ArrayList<ConflictEntry> next = new ArrayList<ConflictEntry>(rec.entries.size());
            boolean changed = false;
            for (ConflictEntry row : rec.entries) {
                if (row == null) continue;
                if (eu.equals(safe(row.uuid).trim())) {
                    changed = true;
                    continue;
                }
                next.add(normalizeEntry(row));
            }
            if (!changed) return false;
            rec.entries = next;
            rec.updatedAt = Instant.now().toString();
            writeAllLocked(conflictsPath(tu, mu), rec);
            LinkedHashMap<String, String> details = new LinkedHashMap<String, String>();
            details.put("entry_uuid", eu);
            ACTIVITY_LOGS.logVerbose("conflicts.entry.deleted", tu, "", mu, "", details);
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public static String normalizeEntityName(String name) {
        return entity_recognition_service.normalizeEntity(name);
    }

    public static String entryKey(ConflictEntry entry) {
        ConflictEntry e = normalizeEntry(entry);
        String type = safe(e.entityType).trim().toLowerCase(Locale.ROOT);
        String normalized = safe(e.normalizedName).trim();
        if (type.isBlank() || normalized.isBlank()) return "";
        return type + "|" + normalized;
    }

    public static LinkedHashMap<String, ConflictEntry> entryMap(List<ConflictEntry> rows) {
        LinkedHashMap<String, ConflictEntry> out = new LinkedHashMap<String, ConflictEntry>();
        List<ConflictEntry> xs = rows == null ? List.of() : rows;
        for (ConflictEntry row : xs) {
            ConflictEntry normalized = normalizeEntry(row);
            String key = entryKey(normalized);
            if (key.isBlank()) continue;
            ConflictEntry existing = out.get(key);
            out.put(key, mergeEntries(existing, normalized));
        }
        return out;
    }

    public static ConflictEntry mergeEntries(ConflictEntry base, ConflictEntry incoming) {
        ConflictEntry left = normalizeEntry(base);
        ConflictEntry right = normalizeEntry(incoming);
        if (safe(left.entityType).isBlank()) return right;
        if (safe(right.entityType).isBlank()) return left;

        ConflictEntry out = new ConflictEntry();
        out.uuid = chooseUuid(left.uuid, right.uuid);
        out.entityType = nonBlank(right.entityType, left.entityType).toLowerCase(Locale.ROOT);
        out.displayName = chooseDisplayName(left.displayName, right.displayName);
        out.normalizedName = nonBlank(right.normalizedName, left.normalizedName);
        out.sourceTags = mergeCsv(left.sourceTags, right.sourceTags);
        out.sourceRefs = mergeCsv(left.sourceRefs, right.sourceRefs);
        out.linkedContactUuids = mergeCsv(left.linkedContactUuids, right.linkedContactUuids);
        out.occurrenceCount = Math.max(1, Math.max(left.occurrenceCount, 0) + Math.max(right.occurrenceCount, 0));
        out.firstSeenAt = earlierIso(left.firstSeenAt, right.firstSeenAt);
        out.lastSeenAt = laterIso(left.lastSeenAt, right.lastSeenAt);
        out.notes = chooseNotes(left.notes, right.notes);
        return normalizeEntry(out);
    }

    public static ConflictEntry normalizeEntry(ConflictEntry in) {
        ConflictEntry out = new ConflictEntry();
        if (in == null) return out;
        out.uuid = safe(in.uuid).trim();
        out.entityType = normalizeEntityType(in.entityType);
        out.displayName = compactName(in.displayName);
        out.normalizedName = safe(in.normalizedName).trim();
        if (out.normalizedName.isBlank()) out.normalizedName = normalizeEntityName(out.displayName);
        out.sourceTags = normalizeCsv(in.sourceTags);
        out.sourceRefs = normalizeCsv(in.sourceRefs);
        out.linkedContactUuids = normalizeCsv(in.linkedContactUuids);
        out.occurrenceCount = Math.max(0, in.occurrenceCount);
        out.firstSeenAt = safe(in.firstSeenAt).trim();
        out.lastSeenAt = safe(in.lastSeenAt).trim();
        out.notes = safe(in.notes).trim();
        if (out.uuid.isBlank()) out.uuid = UUID.randomUUID().toString();
        if (out.occurrenceCount <= 0) out.occurrenceCount = 1;
        String now = Instant.now().toString();
        if (out.firstSeenAt.isBlank()) out.firstSeenAt = now;
        if (out.lastSeenAt.isBlank()) out.lastSeenAt = out.firstSeenAt;
        return out;
    }

    private static String normalizeEntityType(String raw) {
        String v = safe(raw).trim().toLowerCase(Locale.ROOT);
        if ("org".equals(v) || "organization".equals(v) || "organisation".equals(v)) return "organization";
        return "person".equals(v) ? "person" : "person";
    }

    private static FileRec normalizeFile(FileRec in) {
        FileRec out = in == null ? new FileRec() : in;
        out.entries = new ArrayList<ConflictEntry>(entryMap(out.entries).values());
        LinkedHashMap<String, VersionScanState> cleanState = new LinkedHashMap<String, VersionScanState>();
        if (out.versionScanState != null) {
            for (Map.Entry<String, VersionScanState> e : out.versionScanState.entrySet()) {
                if (e == null) continue;
                VersionScanState row = normalizeScanState(e.getValue());
                String key = safe(row.versionUuid).trim();
                if (key.isBlank()) continue;
                cleanState.put(key, row);
            }
        }
        out.versionScanState = cleanState;
        String now = Instant.now().toString();
        out.updatedAt = safe(out.updatedAt).trim();
        if (out.updatedAt.isBlank()) out.updatedAt = now;
        out.lastScannedAt = safe(out.lastScannedAt).trim();
        return out;
    }

    private static VersionScanState normalizeScanState(VersionScanState in) {
        VersionScanState out = new VersionScanState();
        if (in == null) return out;
        out.versionUuid = safe(in.versionUuid).trim();
        out.fingerprint = safe(in.fingerprint).trim();
        out.scannedAt = safe(in.scannedAt).trim();
        return out;
    }

    private static FileRec readAllLocked(Path p) throws Exception {
        FileRec out = new FileRec();
        if (p == null || !Files.exists(p)) return out;
        Document d = document_workflow_support.parseXml(p);
        Element root = d == null ? null : d.getDocumentElement();
        if (root == null) return out;
        if (!"conflicts".equalsIgnoreCase(safe(root.getTagName()).trim())) return out;

        out.updatedAt = safe(root.getAttribute("updated")).trim();
        out.lastScannedAt = safe(root.getAttribute("last_scanned_at")).trim();

        NodeList entryNodes = root.getElementsByTagName("entry");
        for (int i = 0; i < entryNodes.getLength(); i++) {
            Node n = entryNodes.item(i);
            if (!(n instanceof Element e)) continue;
            ConflictEntry row = new ConflictEntry();
            row.uuid = text(e, "uuid");
            row.entityType = text(e, "entity_type");
            row.displayName = text(e, "display_name");
            row.normalizedName = text(e, "normalized_name");
            row.sourceTags = text(e, "source_tags");
            row.sourceRefs = text(e, "source_refs");
            row.linkedContactUuids = text(e, "linked_contact_uuids");
            row.occurrenceCount = intOrDefault(text(e, "occurrence_count"), 0);
            row.firstSeenAt = text(e, "first_seen_at");
            row.lastSeenAt = text(e, "last_seen_at");
            row.notes = text(e, "notes");
            String key = entryKey(row);
            if (key.isBlank()) continue;
            out.entries.add(normalizeEntry(row));
        }

        NodeList versionNodes = root.getElementsByTagName("version");
        for (int i = 0; i < versionNodes.getLength(); i++) {
            Node n = versionNodes.item(i);
            if (!(n instanceof Element e)) continue;
            VersionScanState row = new VersionScanState();
            row.versionUuid = safe(e.getAttribute("uuid")).trim();
            row.fingerprint = safe(e.getAttribute("fingerprint")).trim();
            row.scannedAt = safe(e.getAttribute("scanned_at")).trim();
            row = normalizeScanState(row);
            if (safe(row.versionUuid).isBlank()) continue;
            out.versionScanState.put(row.versionUuid, row);
        }

        return normalizeFile(out);
    }

    private static void writeAllLocked(Path p, FileRec file) throws Exception {
        if (p == null) throw new IllegalArgumentException("Conflict path is required.");
        Files.createDirectories(p.getParent());
        FileRec rec = normalizeFile(file);
        String now = Instant.now().toString();
        rec.updatedAt = now;

        ArrayList<ConflictEntry> entries = new ArrayList<ConflictEntry>(rec.entries);
        entries.sort(Comparator.comparing((ConflictEntry e) -> safe(e.displayName), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(e -> safe(e.entityType), String.CASE_INSENSITIVE_ORDER));

        StringBuilder sb = new StringBuilder(8192);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<conflicts updated=\"").append(document_workflow_support.xmlText(rec.updatedAt)).append("\"");
        if (!safe(rec.lastScannedAt).isBlank()) {
            sb.append(" last_scanned_at=\"").append(document_workflow_support.xmlText(rec.lastScannedAt)).append("\"");
        }
        sb.append(">\n");

        sb.append("  <entries count=\"").append(entries.size()).append("\">\n");
        for (ConflictEntry row : entries) {
            if (row == null) continue;
            ConflictEntry e = normalizeEntry(row);
            String key = entryKey(e);
            if (key.isBlank()) continue;
            sb.append("    <entry>\n");
            sb.append("      <uuid>").append(document_workflow_support.xmlText(e.uuid)).append("</uuid>\n");
            sb.append("      <entity_type>").append(document_workflow_support.xmlText(e.entityType)).append("</entity_type>\n");
            sb.append("      <display_name>").append(document_workflow_support.xmlText(e.displayName)).append("</display_name>\n");
            sb.append("      <normalized_name>").append(document_workflow_support.xmlText(e.normalizedName)).append("</normalized_name>\n");
            if (!safe(e.sourceTags).isBlank()) {
                sb.append("      <source_tags>").append(document_workflow_support.xmlText(e.sourceTags)).append("</source_tags>\n");
            }
            if (!safe(e.sourceRefs).isBlank()) {
                sb.append("      <source_refs>").append(document_workflow_support.xmlText(e.sourceRefs)).append("</source_refs>\n");
            }
            if (!safe(e.linkedContactUuids).isBlank()) {
                sb.append("      <linked_contact_uuids>").append(document_workflow_support.xmlText(e.linkedContactUuids)).append("</linked_contact_uuids>\n");
            }
            sb.append("      <occurrence_count>").append(Math.max(1, e.occurrenceCount)).append("</occurrence_count>\n");
            if (!safe(e.firstSeenAt).isBlank()) {
                sb.append("      <first_seen_at>").append(document_workflow_support.xmlText(e.firstSeenAt)).append("</first_seen_at>\n");
            }
            if (!safe(e.lastSeenAt).isBlank()) {
                sb.append("      <last_seen_at>").append(document_workflow_support.xmlText(e.lastSeenAt)).append("</last_seen_at>\n");
            }
            if (!safe(e.notes).isBlank()) {
                sb.append("      <notes>").append(document_workflow_support.xmlText(e.notes)).append("</notes>\n");
            }
            sb.append("    </entry>\n");
        }
        sb.append("  </entries>\n");

        sb.append("  <version_scan_state count=\"").append(rec.versionScanState.size()).append("\">\n");
        for (VersionScanState state : rec.versionScanState.values()) {
            VersionScanState row = normalizeScanState(state);
            String vu = safe(row.versionUuid).trim();
            if (vu.isBlank()) continue;
            sb.append("    <version uuid=\"").append(document_workflow_support.xmlText(vu)).append("\"");
            if (!safe(row.fingerprint).isBlank()) {
                sb.append(" fingerprint=\"").append(document_workflow_support.xmlText(row.fingerprint)).append("\"");
            }
            if (!safe(row.scannedAt).isBlank()) {
                sb.append(" scanned_at=\"").append(document_workflow_support.xmlText(row.scannedAt)).append("\"");
            }
            sb.append(" />\n");
        }
        sb.append("  </version_scan_state>\n");
        sb.append("</conflicts>\n");
        document_workflow_support.writeAtomic(p, sb.toString());
    }

    private static String emptyXml() {
        String now = Instant.now().toString();
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<conflicts updated=\"" + document_workflow_support.xmlText(now) + "\" last_scanned_at=\"\">\n"
                + "  <entries count=\"0\"></entries>\n"
                + "  <version_scan_state count=\"0\"></version_scan_state>\n"
                + "</conflicts>\n";
    }

    private static ReentrantReadWriteLock lockFor(String tenantUuid, String matterUuid) {
        String key = safe(tenantUuid).trim() + "|" + safe(matterUuid).trim();
        return LOCKS.computeIfAbsent(key, k -> new ReentrantReadWriteLock());
    }

    private static Path conflictsPath(String tenantUuid, String matterUuid) throws Exception {
        String tu = safe(tenantUuid).trim();
        String mu = safe(matterUuid).trim();
        if (tu.isBlank() || mu.isBlank()) {
            throw new IllegalArgumentException("tenantUuid and matterUuid are required.");
        }
        Path matterFolder = matters.defaultStore().matterFolderPath(tu, mu);
        if (matterFolder == null) {
            matterFolder = Paths.get("data", "tenants", tu, "matters", mu).toAbsolutePath().normalize();
        }
        Files.createDirectories(matterFolder);
        pdf_redaction_service.requirePathWithinTenant(matterFolder, tu, "Matter conflicts folder");
        Path out = matterFolder.resolve("conflicts.xml").toAbsolutePath().normalize();
        pdf_redaction_service.requirePathWithinTenant(out, tu, "Matter conflicts path");
        return out;
    }

    private static String chooseUuid(String a, String b) {
        String x = safe(a).trim();
        String y = safe(b).trim();
        if (!x.isBlank()) return x;
        if (!y.isBlank()) return y;
        return UUID.randomUUID().toString();
    }

    private static String chooseDisplayName(String left, String right) {
        String a = compactName(left);
        String b = compactName(right);
        if (a.isBlank()) return b;
        if (b.isBlank()) return a;
        if (b.length() > a.length()) return b;
        return a;
    }

    private static String chooseNotes(String left, String right) {
        String a = safe(left).trim();
        String b = safe(right).trim();
        if (a.isBlank()) return b;
        if (b.isBlank()) return a;
        if (a.equalsIgnoreCase(b)) return a;
        if (a.contains(b)) return a;
        if (b.contains(a)) return b;
        return a + " | " + b;
    }

    private static String earlierIso(String a, String b) {
        String x = safe(a).trim();
        String y = safe(b).trim();
        if (x.isBlank()) return y;
        if (y.isBlank()) return x;
        return x.compareTo(y) <= 0 ? x : y;
    }

    private static String laterIso(String a, String b) {
        String x = safe(a).trim();
        String y = safe(b).trim();
        if (x.isBlank()) return y;
        if (y.isBlank()) return x;
        return x.compareTo(y) >= 0 ? x : y;
    }

    private static String nonBlank(String preferred, String fallback) {
        String p = safe(preferred).trim();
        return p.isBlank() ? safe(fallback).trim() : p;
    }

    private static String compactName(String s) {
        return safe(s).replace('\u00A0', ' ').replaceAll("\\s+", " ").trim();
    }

    private static String mergeCsv(String left, String right) {
        LinkedHashSet<String> set = new LinkedHashSet<String>();
        for (String token : csvTokens(left)) set.add(token);
        for (String token : csvTokens(right)) set.add(token);
        return String.join(",", set);
    }

    private static String normalizeCsv(String raw) {
        return String.join(",", csvTokens(raw));
    }

    private static ArrayList<String> csvTokens(String raw) {
        ArrayList<String> out = new ArrayList<String>();
        String text = safe(raw).trim();
        if (text.isBlank()) return out;
        String[] parts = text.split("[,;|\\s]+");
        LinkedHashSet<String> seen = new LinkedHashSet<String>();
        for (String part : parts) {
            String token = safe(part).trim();
            if (token.isBlank()) continue;
            if (seen.add(token)) out.add(token);
        }
        return out;
    }

    private static int intOrDefault(String raw, int def) {
        try {
            return Integer.parseInt(safe(raw).trim());
        } catch (Exception ignored) {
            return def;
        }
    }

    private static String text(Element parent, String childTag) {
        if (parent == null || safe(childTag).trim().isBlank()) return "";
        NodeList nl = parent.getElementsByTagName(childTag);
        if (nl == null || nl.getLength() <= 0) return "";
        Node n = nl.item(0);
        return safe(n == null ? "" : n.getTextContent()).trim();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
