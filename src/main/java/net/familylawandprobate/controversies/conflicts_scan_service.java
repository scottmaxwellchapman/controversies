package net.familylawandprobate.controversies;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Builds and refreshes per-matter conflicts.xml entries from version text/OCR and linked contacts.
 */
public final class conflicts_scan_service {

    private static final Logger LOG = Logger.getLogger(conflicts_scan_service.class.getName());
    private static final activity_log ACTIVITY_LOGS = activity_log.defaultStore();

    private static final class Holder {
        private static final conflicts_scan_service INSTANCE = new conflicts_scan_service();
    }

    public static final class ScanSummary {
        public String tenantUuid = "";
        public String matterUuid = "";
        public String startedAt = "";
        public String completedAt = "";
        public int versionsTotal = 0;
        public int versionsScanned = 0;
        public int versionsSkipped = 0;
        public int nlpEntities = 0;
        public int linkedContactEntities = 0;
        public int entriesChanged = 0;
        public int ocrWarnings = 0;
        public String message = "";
    }

    private static final class VersionTarget {
        String matterUuid = "";
        String matterLabel = "";
        String documentUuid = "";
        String documentTitle = "";
        String partUuid = "";
        String partLabel = "";
        part_versions.VersionRec version;
    }

    private final matter_conflicts conflictsStore = matter_conflicts.defaultStore();
    private final entity_recognition_service nlp = entity_recognition_service.defaultService();
    private final version_ocr_companion_service ocrService = version_ocr_companion_service.defaultService();

    private conflicts_scan_service() {
    }

    public static conflicts_scan_service defaultService() {
        return Holder.INSTANCE;
    }

    public ScanSummary scanMatter(String tenantUuid, String matterUuid) throws Exception {
        return scanMatter(tenantUuid, matterUuid, true);
    }

    public ScanSummary scanMatter(String tenantUuid, String matterUuid, boolean includeLinkedContacts) throws Exception {
        String tu = safe(tenantUuid).trim();
        String mu = safe(matterUuid).trim();
        if (tu.isBlank() || mu.isBlank()) {
            throw new IllegalArgumentException("tenantUuid and matterUuid are required.");
        }

        ScanSummary summary = new ScanSummary();
        summary.tenantUuid = tu;
        summary.matterUuid = mu;
        summary.startedAt = Instant.now().toString();

        matters.MatterRec matter = matters.defaultStore().getByUuid(tu, mu);
        if (matter == null) {
            throw new IllegalArgumentException("Matter not found.");
        }

        conflictsStore.ensure(tu, mu);
        matter_conflicts.FileRec file = conflictsStore.read(tu, mu);
        LinkedHashMap<String, matter_conflicts.ConflictEntry> entriesByKey = matter_conflicts.entryMap(file.entries);
        LinkedHashMap<String, matter_conflicts.VersionScanState> scanState = new LinkedHashMap<String, matter_conflicts.VersionScanState>();
        if (file.versionScanState != null) scanState.putAll(file.versionScanState);

        ArrayList<VersionTarget> targets = collectVersionTargets(tu, mu, matter);
        summary.versionsTotal = targets.size();
        LinkedHashSet<String> seenVersionUuids = new LinkedHashSet<String>();
        String nowIso = Instant.now().toString();

        for (VersionTarget target : targets) {
            if (target == null || target.version == null) continue;
            String vu = safe(target.version.uuid).trim();
            if (vu.isBlank()) continue;
            seenVersionUuids.add(vu);

            String fingerprint = buildVersionFingerprint(tu, target);
            matter_conflicts.VersionScanState prior = scanState.get(vu);
            if (prior != null && fingerprint.equals(safe(prior.fingerprint).trim())) {
                summary.versionsSkipped++;
                continue;
            }

            summary.versionsScanned++;
            String text = buildVersionScanText(tu, target, summary);
            ArrayList<entity_recognition_service.EntityHit> hits = nlp.extractPersonAndOrganizations(text);
            summary.nlpEntities += hits.size();
            for (entity_recognition_service.EntityHit hit : hits) {
                if (hit == null) continue;
                matter_conflicts.ConflictEntry candidate = new matter_conflicts.ConflictEntry();
                candidate.entityType = safe(hit.entityType);
                candidate.displayName = safe(hit.value);
                candidate.normalizedName = safe(hit.normalized);
                candidate.sourceTags = "nlp_" + safe(hit.entityType);
                candidate.sourceRefs = safe(target.version.uuid);
                candidate.occurrenceCount = 1;
                candidate.firstSeenAt = nowIso;
                candidate.lastSeenAt = nowIso;
                candidate.notes = "engine=" + safe(hit.engine);
                boolean changed = mergeConflictEntry(entriesByKey, candidate);
                if (changed) summary.entriesChanged++;
            }

            matter_conflicts.VersionScanState state = new matter_conflicts.VersionScanState();
            state.versionUuid = vu;
            state.fingerprint = fingerprint;
            state.scannedAt = nowIso;
            scanState.put(vu, state);
        }

        if (includeLinkedContacts) {
            summary.linkedContactEntities += ingestLinkedContacts(tu, mu, entriesByKey, nowIso, summary);
        }

        // Remove stale scan states for deleted versions.
        scanState.entrySet().removeIf(e -> e == null || !seenVersionUuids.contains(safe(e.getKey()).trim()));

        matter_conflicts.FileRec out = new matter_conflicts.FileRec();
        out.entries = new ArrayList<matter_conflicts.ConflictEntry>(entriesByKey.values());
        out.versionScanState = scanState;
        out.lastScannedAt = Instant.now().toString();
        out.updatedAt = out.lastScannedAt;
        conflictsStore.write(tu, mu, out);

        summary.completedAt = Instant.now().toString();
        summary.message = "Scanned " + summary.versionsScanned + "/" + summary.versionsTotal
                + " version(s), skipped " + summary.versionsSkipped
                + ", entities=" + (summary.nlpEntities + summary.linkedContactEntities)
                + ", changes=" + summary.entriesChanged + ".";
        LOG.info("conflicts_scan tenant=" + tu + " matter=" + mu + " " + summary.message);
        LinkedHashMap<String, String> details = new LinkedHashMap<String, String>();
        details.put("versions_total", String.valueOf(summary.versionsTotal));
        details.put("versions_scanned", String.valueOf(summary.versionsScanned));
        details.put("versions_skipped", String.valueOf(summary.versionsSkipped));
        details.put("nlp_entities", String.valueOf(summary.nlpEntities));
        details.put("linked_contact_entities", String.valueOf(summary.linkedContactEntities));
        details.put("entries_changed", String.valueOf(summary.entriesChanged));
        details.put("ocr_warnings", String.valueOf(summary.ocrWarnings));
        details.put("include_linked_contacts", includeLinkedContacts ? "true" : "false");
        details.put("message", safe(summary.message));
        if (summary.ocrWarnings > 0) {
            ACTIVITY_LOGS.logWarning("conflicts.scan.completed_with_warnings", tu, "", mu, "", details);
        } else {
            ACTIVITY_LOGS.logVerbose("conflicts.scan.completed", tu, "", mu, "", details);
        }
        return summary;
    }

    public ArrayList<ScanSummary> scanAllMatters(String tenantUuid) throws Exception {
        String tu = safe(tenantUuid).trim();
        if (tu.isBlank()) throw new IllegalArgumentException("tenantUuid is required.");
        ArrayList<ScanSummary> out = new ArrayList<ScanSummary>();
        List<matters.MatterRec> rows = matters.defaultStore().listAll(tu);
        for (matters.MatterRec row : rows) {
            if (row == null) continue;
            String mu = safe(row.uuid).trim();
            if (mu.isBlank()) continue;
            try {
                out.add(scanMatter(tu, mu, true));
            } catch (Exception ex) {
                ScanSummary failed = new ScanSummary();
                failed.tenantUuid = tu;
                failed.matterUuid = mu;
                failed.startedAt = Instant.now().toString();
                failed.completedAt = failed.startedAt;
                failed.message = "Scan failed: " + safe(ex.getMessage());
                out.add(failed);
                LOG.log(Level.WARNING, "Conflict scan failed tenant=" + tu + " matter=" + mu + ": " + safe(ex.getMessage()), ex);
                LinkedHashMap<String, String> details = new LinkedHashMap<String, String>();
                details.put("reason", safe(ex.getMessage()));
                ACTIVITY_LOGS.logError("conflicts.scan.failed", tu, "", mu, "", details);
            }
        }
        return out;
    }

    private static ArrayList<VersionTarget> collectVersionTargets(String tenantUuid,
                                                                  String matterUuid,
                                                                  matters.MatterRec matter) throws Exception {
        String tu = safe(tenantUuid).trim();
        String mu = safe(matterUuid).trim();
        ArrayList<VersionTarget> out = new ArrayList<VersionTarget>();
        if (tu.isBlank() || mu.isBlank()) return out;

        documents docs = documents.defaultStore();
        document_parts parts = document_parts.defaultStore();
        part_versions versions = part_versions.defaultStore();

        List<documents.DocumentRec> docRows = docs.listAll(tu, mu);
        for (documents.DocumentRec doc : docRows) {
            if (doc == null || doc.trashed) continue;
            String du = safe(doc.uuid).trim();
            if (du.isBlank()) continue;

            List<document_parts.PartRec> partRows = parts.listAll(tu, mu, du);
            for (document_parts.PartRec part : partRows) {
                if (part == null || part.trashed) continue;
                String pu = safe(part.uuid).trim();
                if (pu.isBlank()) continue;

                List<part_versions.VersionRec> versionRows = versions.listAll(tu, mu, du, pu);
                for (part_versions.VersionRec version : versionRows) {
                    if (version == null) continue;
                    VersionTarget target = new VersionTarget();
                    target.matterUuid = mu;
                    target.matterLabel = safe(matter == null ? "" : matter.label);
                    target.documentUuid = du;
                    target.documentTitle = safe(doc.title);
                    target.partUuid = pu;
                    target.partLabel = safe(part.label);
                    target.version = version;
                    out.add(target);
                }
            }
        }
        return out;
    }

    private String buildVersionScanText(String tenantUuid,
                                        VersionTarget target,
                                        ScanSummary summary) {
        if (target == null || target.version == null) return "";
        StringBuilder sb = new StringBuilder(4096);
        appendLine(sb, target.matterLabel);
        appendLine(sb, target.documentTitle);
        appendLine(sb, target.partLabel);
        appendLine(sb, safe(target.version.versionLabel));
        appendLine(sb, safe(target.version.notes));
        appendLine(sb, safe(target.version.source));
        appendLine(sb, safe(target.version.mimeType));
        appendLine(sb, safe(target.version.storagePath));

        try {
            version_ocr_companion_service.CompanionRec companion = ocrService.ensureCompanion(
                    tenantUuid,
                    target.matterUuid,
                    target.documentUuid,
                    target.partUuid,
                    target.version
            );
            appendLine(sb, safe(companion == null ? "" : companion.fullText));
        } catch (Exception ex) {
            if (summary != null) summary.ocrWarnings++;
            LOG.log(Level.WARNING,
                    "Conflict scan OCR companion warning tenant=" + tenantUuid
                            + " matter=" + safe(target.matterUuid)
                            + " doc=" + safe(target.documentUuid)
                            + " part=" + safe(target.partUuid)
                            + " version=" + safe(target.version.uuid)
                            + ": " + safe(ex.getMessage()),
                    ex);
        }
        return sb.toString();
    }

    private static String buildVersionFingerprint(String tenantUuid, VersionTarget target) {
        if (target == null || target.version == null) return "";
        part_versions.VersionRec v = target.version;
        long size = -1L;
        long mtime = -1L;
        try {
            Path source = pdf_redaction_service.resolveStoragePath(v.storagePath);
            pdf_redaction_service.requirePathWithinTenant(source, tenantUuid, "Version source path");
            if (source != null && Files.isRegularFile(source)) {
                size = Files.size(source);
                mtime = Files.getLastModifiedTime(source).toMillis();
            }
        } catch (Exception ignored) {
        }
        return safe(v.uuid).trim() + "|"
                + safe(v.checksum).trim() + "|"
                + safe(v.storagePath).trim() + "|"
                + safe(v.mimeType).trim().toLowerCase(Locale.ROOT) + "|"
                + size + "|" + mtime;
    }

    private int ingestLinkedContacts(String tenantUuid,
                                     String matterUuid,
                                     LinkedHashMap<String, matter_conflicts.ConflictEntry> entriesByKey,
                                     String nowIso,
                                     ScanSummary summary) throws Exception {
        int count = 0;
        List<matter_contacts.LinkRec> links = matter_contacts.defaultStore().listByMatter(tenantUuid, matterUuid);
        for (matter_contacts.LinkRec link : links) {
            if (link == null) continue;
            String contactUuid = safe(link.contactUuid).trim();
            if (contactUuid.isBlank()) continue;
            contacts.ContactRec contact = contacts.defaultStore().getByUuid(tenantUuid, contactUuid);
            if (contact == null || contact.trashed) continue;

            String personName = derivePersonName(contact);
            if (!personName.isBlank()) {
                matter_conflicts.ConflictEntry person = new matter_conflicts.ConflictEntry();
                person.entityType = "person";
                person.displayName = personName;
                person.normalizedName = matter_conflicts.normalizeEntityName(personName);
                person.sourceTags = "linked_contact";
                person.sourceRefs = contactUuid;
                person.linkedContactUuids = contactUuid;
                person.occurrenceCount = 1;
                person.firstSeenAt = nowIso;
                person.lastSeenAt = nowIso;
                person.notes = "contact_source=" + safe(contact.source);
                boolean changed = mergeConflictEntry(entriesByKey, person);
                if (changed && summary != null) summary.entriesChanged++;
                count++;
            }

            String orgName = compact(safe(contact.companyName));
            if (!orgName.isBlank()) {
                matter_conflicts.ConflictEntry org = new matter_conflicts.ConflictEntry();
                org.entityType = "organization";
                org.displayName = orgName;
                org.normalizedName = matter_conflicts.normalizeEntityName(orgName);
                org.sourceTags = "linked_contact";
                org.sourceRefs = contactUuid;
                org.linkedContactUuids = contactUuid;
                org.occurrenceCount = 1;
                org.firstSeenAt = nowIso;
                org.lastSeenAt = nowIso;
                org.notes = "contact_source=" + safe(contact.source);
                boolean changed = mergeConflictEntry(entriesByKey, org);
                if (changed && summary != null) summary.entriesChanged++;
                count++;
            }
        }
        return count;
    }

    private static boolean mergeConflictEntry(LinkedHashMap<String, matter_conflicts.ConflictEntry> map,
                                              matter_conflicts.ConflictEntry candidate) {
        if (map == null || candidate == null) return false;
        matter_conflicts.ConflictEntry normalized = matter_conflicts.normalizeEntry(candidate);
        String key = matter_conflicts.entryKey(normalized);
        if (key.isBlank()) return false;
        matter_conflicts.ConflictEntry before = map.get(key);
        matter_conflicts.ConflictEntry merged = matter_conflicts.mergeEntries(before, normalized);
        map.put(key, merged);
        return !entriesEquivalent(before, merged);
    }

    private static boolean entriesEquivalent(matter_conflicts.ConflictEntry a, matter_conflicts.ConflictEntry b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return safe(a.entityType).equals(safe(b.entityType))
                && safe(a.displayName).equals(safe(b.displayName))
                && safe(a.normalizedName).equals(safe(b.normalizedName))
                && safe(a.sourceTags).equals(safe(b.sourceTags))
                && safe(a.sourceRefs).equals(safe(b.sourceRefs))
                && safe(a.linkedContactUuids).equals(safe(b.linkedContactUuids))
                && a.occurrenceCount == b.occurrenceCount
                && safe(a.firstSeenAt).equals(safe(b.firstSeenAt))
                && safe(a.lastSeenAt).equals(safe(b.lastSeenAt))
                && safe(a.notes).equals(safe(b.notes));
    }

    private static String derivePersonName(contacts.ContactRec contact) {
        if (contact == null) return "";
        String composed = compact(
                safe(contact.givenName) + " "
                        + safe(contact.middleName) + " "
                        + safe(contact.surname)
        );
        if (!composed.isBlank()) return composed;

        String display = compact(safe(contact.displayName));
        if (display.isBlank()) return "";
        if (display.contains("@")) return "";
        if (display.equalsIgnoreCase(safe(contact.companyName).trim())) return "";
        return display;
    }

    private static void appendLine(StringBuilder sb, String value) {
        if (sb == null) return;
        String v = safe(value).trim();
        if (v.isBlank()) return;
        if (sb.length() > 0) sb.append('\n');
        sb.append(v);
    }

    private static String compact(String s) {
        return safe(s).replace('\u00A0', ' ').replaceAll("\\s+", " ").trim();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
