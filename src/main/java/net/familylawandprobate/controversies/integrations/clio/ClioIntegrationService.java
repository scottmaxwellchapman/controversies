package net.familylawandprobate.controversies.integrations.clio;

import net.familylawandprobate.controversies.activity_log;
import net.familylawandprobate.controversies.contacts;
import net.familylawandprobate.controversies.document_parts;
import net.familylawandprobate.controversies.documents;
import net.familylawandprobate.controversies.matter_contacts;
import net.familylawandprobate.controversies.matters;
import net.familylawandprobate.controversies.part_versions;
import net.familylawandprobate.controversies.tenant_settings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public final class ClioIntegrationService {

    private static final String MATTER_FIELDS = String.join(",",
            "id",
            "etag",
            "number",
            "display_number",
            "custom_number",
            "description",
            "status",
            "location",
            "client_reference",
            "client_id",
            "billable",
            "billing_method",
            "open_date",
            "close_date",
            "pending_date",
            "created_at",
            "updated_at"
    );
    private static final String MATTER_ORDER = "id(asc)";
    private static final int CONTACTS_PAGE_SIZE = 200;
    private static final int DOCUMENTS_PAGE_SIZE = 200;
    private static final String CLIO_SYNC_INTERVAL_MINUTES_KEY = "clio_matters_sync_interval_minutes";
    private static final String CLIO_DOCUMENTS_LAST_SYNC_AT_KEY = "clio_documents_last_sync_at";
    private static final long DEFAULT_SYNC_INTERVAL_MINUTES = 15L;
    private static final ConcurrentHashMap<String, Object> SYNC_LOCKS = new ConcurrentHashMap<String, Object>();

    private final tenant_settings settingsStore;
    private final matters matterStore;
    private final contacts contactStore;
    private final matter_contacts matterContactStore;
    private final ClioMatterMappingStore mappingStore;
    private final ClioMatterSnapshotStore snapshotStore;
    private final documents documentStore;
    private final document_parts partStore;
    private final part_versions versionStore;

    public ClioIntegrationService() {
        this.settingsStore = tenant_settings.defaultStore();
        this.matterStore = matters.defaultStore();
        this.contactStore = contacts.defaultStore();
        this.matterContactStore = matter_contacts.defaultStore();
        this.mappingStore = new ClioMatterMappingStore();
        this.snapshotStore = new ClioMatterSnapshotStore();
        this.documentStore = documents.defaultStore();
        this.partStore = document_parts.defaultStore();
        this.versionStore = part_versions.defaultStore();
    }

    public static final class ContactSyncResult {
        public final int contactsUpserted;
        public final int mattersScanned;
        public final int linksApplied;
        public final int linkErrors;
        public final boolean ok;
        public final String error;

        public ContactSyncResult(int contactsUpserted,
                                 int mattersScanned,
                                 int linksApplied,
                                 int linkErrors,
                                 boolean ok,
                                 String error) {
            this.contactsUpserted = Math.max(0, contactsUpserted);
            this.mattersScanned = Math.max(0, mattersScanned);
            this.linksApplied = Math.max(0, linksApplied);
            this.linkErrors = Math.max(0, linkErrors);
            this.ok = ok;
            this.error = safe(error);
        }
    }

    public static final class DocumentSyncResult {
        public final int mattersScanned;
        public final int documentsUpserted;
        public final int versionsImported;
        public final boolean ok;
        public final String error;

        public DocumentSyncResult(int mattersScanned,
                                  int documentsUpserted,
                                  int versionsImported,
                                  boolean ok,
                                  String error) {
            this.mattersScanned = Math.max(0, mattersScanned);
            this.documentsUpserted = Math.max(0, documentsUpserted);
            this.versionsImported = Math.max(0, versionsImported);
            this.ok = ok;
            this.error = safe(error);
        }
    }

    public boolean isEnabled(String tenantUuid) {
        LinkedHashMap<String, String> cfg = settingsStore.read(tenantUuid);
        return "true".equalsIgnoreCase(cfg.getOrDefault("clio_enabled", "false"));
    }

    public int syncEnabledTenantsScheduled() {
        int total = 0;
        try {
            Path tenantsRoot = Paths.get("data", "tenants").toAbsolutePath();
            if (!Files.exists(tenantsRoot)) return 0;
            for (Path p : Files.list(tenantsRoot).toList()) {
                if (p == null || !Files.isDirectory(p)) continue;
                String tenantUuid = p.getFileName().toString();
                if (!isSyncDue(tenantUuid)) continue;
                total += syncMatters(tenantUuid, false);
                DocumentSyncResult docs = syncDocuments(tenantUuid, false);
                total += docs.documentsUpserted + docs.versionsImported;
                total += syncContactsToMatters(tenantUuid, false).contactsUpserted;
            }
        } catch (Exception ignored) {
            return total;
        }
        return total;
    }

    public int syncMatters(String tenantUuid, boolean manualRun) throws Exception {
        String tu = safe(tenantUuid).trim();
        if (tu.isBlank()) return 0;

        Object lock = SYNC_LOCKS.computeIfAbsent(tu, k -> new Object());
        synchronized (lock) {
            if (!isEnabled(tu)) return 0;
            LinkedHashMap<String, String> cfg = settingsStore.read(tu);
            String accessToken = safe(cfg.get("clio_access_token"));
            if (accessToken.isBlank()) return 0;

            ClioClient client = new ClioClient(cfg.get("clio_base_url"));
            String nextCursor = "";
            int synced = 0;
            int scanned = 0;
            int pages = 0;
            String nowIso = Instant.now().toString();
            LinkedHashSet<String> seenCursors = new LinkedHashSet<String>();

            while (true) {
                if (!nextCursor.isBlank() && !seenCursors.add(nextCursor)) {
                    break;
                }
                ClioClient.MatterPage page = client.listMattersPage(
                        accessToken,
                        nextCursor,
                        MATTER_ORDER,
                        "",
                        200,
                        MATTER_FIELDS
                );
                List<ClioMatter> rows = page == null ? List.of() : page.items;
                if (rows.isEmpty()) break;
                pages++;

                for (ClioMatter cm : rows) {
                    if (cm == null || safe(cm.id).trim().isBlank()) continue;
                    scanned++;
                    snapshotStore.upsert(tu, cm.id, cm.rawJson, cm.updatedAt, nowIso);
                    if (upsertMatterFromClio(tu, cm)) synced++;
                }

                nextCursor = page == null ? "" : safe(page.nextPageUrl).trim();
                if (nextCursor.isBlank()) break;
            }

            snapshotStore.writeManifest(tu, scanned, nowIso);

            LinkedHashMap<String, String> nextCfg = new LinkedHashMap<String, String>(cfg);
            nextCfg.put("clio_matters_last_sync_at", nowIso);
            settingsStore.write(tu, nextCfg);

            LinkedHashMap<String, String> details = new LinkedHashMap<String, String>();
            details.put("synced", String.valueOf(synced));
            details.put("scanned", String.valueOf(scanned));
            details.put("pages", String.valueOf(pages));
            details.put("fields", MATTER_FIELDS);

            if (manualRun) {
                activity_log.defaultStore().logVerbose("clio.matters.sync.manual", tu, "system", "", "", details);
            } else {
                activity_log.defaultStore().logVerbose("clio.matters.sync.scheduled", tu, "system", "", "", details);
            }
            return synced;
        }
    }

    public DocumentSyncResult syncDocuments(String tenantUuid, boolean manualRun) throws Exception {
        String tu = safe(tenantUuid).trim();
        if (tu.isBlank()) return new DocumentSyncResult(0, 0, 0, false, "tenantUuid required");

        Object lock = SYNC_LOCKS.computeIfAbsent(tu, k -> new Object());
        synchronized (lock) {
            if (!isEnabled(tu)) return new DocumentSyncResult(0, 0, 0, false, "clio disabled");

            LinkedHashMap<String, String> cfg = settingsStore.read(tu);
            String accessToken = safe(cfg.get("clio_access_token")).trim();
            if (accessToken.isBlank()) return new DocumentSyncResult(0, 0, 0, false, "clio access token missing");

            ClioClient client = new ClioClient(cfg.get("clio_base_url"));
            LinkedHashMap<String, String> mappings = mappingStore.all(tu);
            int mattersScanned = 0;
            int documentsUpserted = 0;
            int versionsImported = 0;
            String error = "";

            for (Map.Entry<String, String> entry : mappings.entrySet()) {
                if (entry == null) continue;
                String localMatterUuid = safe(entry.getKey()).trim();
                String clioMatterId = safe(entry.getValue()).trim();
                if (localMatterUuid.isBlank() || clioMatterId.isBlank()) continue;

                matters.MatterRec localMatter = matterStore.getByUuid(tu, localMatterUuid);
                if (localMatter == null || localMatter.trashed) continue;
                mattersScanned++;

                try {
                    MatterDocumentSyncCounts counts = syncMatterDocuments(tu, localMatterUuid, clioMatterId, accessToken, client);
                    documentsUpserted += counts.documentsUpserted;
                    versionsImported += counts.versionsImported;
                } catch (Exception ex) {
                    if (error.isBlank()) error = safe(ex.getMessage());
                }
            }

            String nowIso = Instant.now().toString();
            LinkedHashMap<String, String> nextCfg = new LinkedHashMap<String, String>(cfg);
            nextCfg.put(CLIO_DOCUMENTS_LAST_SYNC_AT_KEY, nowIso);
            settingsStore.write(tu, nextCfg);

            boolean ok = error.isBlank();
            LinkedHashMap<String, String> details = new LinkedHashMap<String, String>();
            details.put("matters_scanned", String.valueOf(mattersScanned));
            details.put("documents_upserted", String.valueOf(documentsUpserted));
            details.put("versions_imported", String.valueOf(versionsImported));
            details.put("status", ok ? "ok" : "failed");
            if (!error.isBlank()) details.put("error", error);

            if (manualRun) {
                activity_log.defaultStore().logVerbose("clio.documents.sync.manual", tu, "system", "", "", details);
            } else {
                activity_log.defaultStore().logVerbose("clio.documents.sync.scheduled", tu, "system", "", "", details);
            }

            return new DocumentSyncResult(mattersScanned, documentsUpserted, versionsImported, ok, error);
        }
    }

    public ContactSyncResult syncContactsToMatters(String tenantUuid, boolean manualRun) throws Exception {
        String tu = safe(tenantUuid).trim();
        if (tu.isBlank()) return new ContactSyncResult(0, 0, 0, 0, false, "tenantUuid required");

        Object lock = SYNC_LOCKS.computeIfAbsent(tu, k -> new Object());
        synchronized (lock) {
            LinkedHashMap<String, String> cfg = settingsStore.read(tu);
            if (!isEnabled(tu)) {
                return new ContactSyncResult(0, 0, 0, 0, false, "clio disabled");
            }

            String accessToken = safe(cfg.get("clio_access_token")).trim();
            if (accessToken.isBlank()) {
                String error = "Clio access token is missing.";
                writeContactSyncStatus(tu, cfg, false, error);
                return new ContactSyncResult(0, 0, 0, 0, false, error);
            }

            ClioClient client = new ClioClient(cfg.get("clio_base_url"));
            int contactsUpserted = 0;
            int mattersScanned = 0;
            int linksApplied = 0;
            int linkErrors = 0;
            String error = "";

            LinkedHashMap<String, String> localContactByClioId = new LinkedHashMap<String, String>();

            try {
                int page = 1;
                while (true) {
                    List<ClioContact> rows = client.listContacts(accessToken, page, CONTACTS_PAGE_SIZE);
                    if (rows.isEmpty()) break;
                    for (ClioContact row : rows) {
                        if (row == null || safe(row.id).trim().isBlank()) continue;
                        contacts.ContactRec rec = contactStore.upsertClio(
                                tu,
                                toContactInput(row),
                                row.id,
                                row.updatedAt
                        );
                        if (rec != null && !safe(rec.uuid).trim().isBlank()) {
                            localContactByClioId.put(safe(row.id).trim(), safe(rec.uuid).trim());
                            contactsUpserted++;
                        }
                    }
                    if (rows.size() < CONTACTS_PAGE_SIZE) break;
                    page++;
                }

                List<matters.MatterRec> allMatters = matterStore.listAll(tu);
                for (matters.MatterRec matter : allMatters) {
                    if (matter == null) continue;
                    if (!"clio".equalsIgnoreCase(safe(matter.source).trim())) continue;
                    String clioMatterId = safe(matter.sourceMatterId).trim();
                    if (clioMatterId.isBlank()) continue;
                    mattersScanned++;

                    try {
                        LinkedHashSet<String> clioContactIds = new LinkedHashSet<String>();
                        int mp = 1;
                        while (true) {
                            List<String> ids = client.listMatterContactIds(accessToken, clioMatterId, mp, CONTACTS_PAGE_SIZE);
                            if (ids == null || ids.isEmpty()) break;
                            for (String id : ids) {
                                String normalized = safe(id).trim();
                                if (!normalized.isBlank()) clioContactIds.add(normalized);
                            }
                            if (ids.size() < CONTACTS_PAGE_SIZE) break;
                            mp++;
                        }

                        ArrayList<matter_contacts.LinkRec> links = new ArrayList<matter_contacts.LinkRec>();
                        String nowIso = Instant.now().toString();
                        for (String clioContactId : clioContactIds) {
                            String localContactUuid = safe(localContactByClioId.get(clioContactId)).trim();
                            if (localContactUuid.isBlank()) continue;
                            links.add(new matter_contacts.LinkRec(
                                    matter.uuid,
                                    localContactUuid,
                                    "clio",
                                    clioMatterId,
                                    clioContactId,
                                    nowIso
                            ));
                        }
                        matterContactStore.replaceClioLinksForMatter(tu, matter.uuid, clioMatterId, links);
                        linksApplied += links.size();
                    } catch (Exception matterLinkEx) {
                        linkErrors++;
                    }
                }
            } catch (Exception ex) {
                error = safe(ex.getMessage());
            }

            boolean ok = error.isBlank() && linkErrors == 0;
            if (!ok && error.isBlank() && linkErrors > 0) {
                error = "Failed to sync one or more matter-contact link pages from Clio.";
            }
            writeContactSyncStatus(tu, cfg, ok, error);

            LinkedHashMap<String, String> details = new LinkedHashMap<String, String>();
            details.put("contacts_upserted", String.valueOf(contactsUpserted));
            details.put("matters_scanned", String.valueOf(mattersScanned));
            details.put("links_applied", String.valueOf(linksApplied));
            details.put("link_errors", String.valueOf(linkErrors));
            details.put("status", ok ? "ok" : "failed");
            if (!safe(error).isBlank()) details.put("error", safe(error));

            if (manualRun) {
                activity_log.defaultStore().logVerbose("clio.contacts.sync.manual", tu, "system", "", "", details);
            } else {
                activity_log.defaultStore().logVerbose("clio.contacts.sync.scheduled", tu, "system", "", "", details);
            }
            return new ContactSyncResult(contactsUpserted, mattersScanned, linksApplied, linkErrors, ok, error);
        }
    }

    public void enqueueUploadTaskIfEligible(String tenantUuid, String localMatterUuid, String assemblyUuid) {
        try {
            if (!isEnabled(tenantUuid)) return;
            LinkedHashMap<String, String> cfg = settingsStore.read(tenantUuid);
            String mode = safe(cfg.get("clio_storage_mode")).trim().toLowerCase();
            if (!"enabled".equals(mode)) return;
            String clioMatterId = mappingStore.clioMatterId(tenantUuid, localMatterUuid);
            if (clioMatterId.isBlank()) return;

            activity_log.defaultStore().logVerbose(
                    "clio.upload.enqueued",
                    tenantUuid,
                    "system",
                    localMatterUuid,
                    assemblyUuid,
                    java.util.Map.of("clioMatterId", clioMatterId)
            );
        } catch (Exception ignored) {
        }
    }

    private boolean upsertMatterFromClio(String tenantUuid, ClioMatter clioMatter) throws Exception {
        String clioId = safe(clioMatter.id).trim();
        if (clioId.isBlank()) return false;

        List<matters.MatterRec> all = matterStore.listAll(tenantUuid);
        matters.MatterRec existing = null;
        for (matters.MatterRec m : all) {
            if (m == null) continue;
            if ("clio".equalsIgnoreCase(safe(m.source)) && clioId.equals(safe(m.sourceMatterId))) {
                existing = m;
                break;
            }
        }

        String canonical = clioMatter.label();
        if (canonical.isBlank()) canonical = "Clio Matter " + clioId;

        if (existing == null) {
            matters.MatterRec created = matterStore.create(
                    tenantUuid,
                    canonical,
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    ""
            );
            matterStore.updateSourceMetadata(tenantUuid, created.uuid, "clio", clioId, canonical, clioMatter.updatedAt);
            mappingStore.upsert(tenantUuid, created.uuid, clioId);
            return true;
        }

        matterStore.updateForExternalSync(
                tenantUuid,
                existing.uuid,
                canonical,
                existing.jurisdictionUuid,
                existing.matterCategoryUuid,
                existing.matterSubcategoryUuid,
                existing.matterStatusUuid,
                existing.matterSubstatusUuid,
                existing.causeDocketNumber,
                existing.county
        );

        matterStore.updateSourceMetadata(tenantUuid, existing.uuid, "clio", clioId, canonical, clioMatter.updatedAt);
        mappingStore.upsert(tenantUuid, existing.uuid, clioId);
        return true;
    }

    private static final class UpsertedDocument {
        final documents.DocumentRec document;
        final boolean created;

        UpsertedDocument(documents.DocumentRec document, boolean created) {
            this.document = document;
            this.created = created;
        }
    }

    private MatterDocumentSyncCounts syncMatterDocuments(String tenantUuid,
                                                         String localMatterUuid,
                                                         String clioMatterId,
                                                         String accessToken,
                                                         ClioClient client) throws Exception {
        documentStore.ensure(tenantUuid, localMatterUuid);
        List<documents.DocumentRec> existing = documentStore.listAll(tenantUuid, localMatterUuid);
        LinkedHashMap<String, documents.DocumentRec> byClioDocumentId = new LinkedHashMap<String, documents.DocumentRec>();
        for (documents.DocumentRec rec : existing) {
            if (rec == null) continue;
            if (!"clio".equalsIgnoreCase(safe(rec.source).trim())) continue;
            String sourceDocumentId = safe(rec.sourceDocumentId).trim();
            if (sourceDocumentId.isBlank()) continue;
            byClioDocumentId.put(sourceDocumentId, rec);
        }

        List<ClioDocument> remoteDocs = listAllDocumentsForMatter(client, accessToken, clioMatterId);
        int documentsUpserted = 0;
        int versionsImported = 0;

        for (ClioDocument remote : remoteDocs) {
            if (remote == null) continue;
            String remoteId = safe(remote.id).trim();
            if (remoteId.isBlank()) continue;

            documents.DocumentRec local = byClioDocumentId.get(remoteId);
            UpsertedDocument upserted = upsertClioDocument(tenantUuid, localMatterUuid, local, remote);
            if (upserted.created) documentsUpserted++;
            documents.DocumentRec persisted = upserted.document;
            if (persisted == null) continue;
            byClioDocumentId.put(remoteId, persisted);

            String partUuid = ensureClioPart(tenantUuid, localMatterUuid, persisted.uuid);
            if (partUuid.isBlank()) continue;
            versionsImported += importDocumentVersions(
                    tenantUuid,
                    localMatterUuid,
                    persisted.uuid,
                    partUuid,
                    remote,
                    accessToken,
                    client
            );
        }

        return new MatterDocumentSyncCounts(documentsUpserted, versionsImported);
    }

    private UpsertedDocument upsertClioDocument(String tenantUuid,
                                                String matterUuid,
                                                documents.DocumentRec existing,
                                                ClioDocument remote) throws Exception {
        String remoteId = safe(remote == null ? "" : remote.id).trim();
        if (remoteId.isBlank()) return new UpsertedDocument(existing, false);

        documents.DocumentRec local = existing;
        boolean created = false;
        if (local == null) {
            local = documentStore.create(
                    tenantUuid,
                    matterUuid,
                    remote.label(),
                    "clio",
                    "",
                    "synced",
                    "Clio",
                    "",
                    isoDate(remote.createdAt),
                    remoteId,
                    "Synced from Clio. Edit in Clio."
            );
            created = true;
        }

        documents.DocumentRec in = new documents.DocumentRec();
        in.uuid = safe(local.uuid);
        in.title = remote.label();
        in.category = "clio";
        in.subcategory = "";
        in.status = "synced";
        in.owner = "Clio";
        in.privilegeLevel = safe(local.privilegeLevel);
        in.filedOn = isoDate(remote.createdAt);
        in.externalReference = remoteId;
        in.notes = "Synced from Clio. Edit in Clio.";
        documentStore.updateForExternalSync(tenantUuid, matterUuid, in);
        documentStore.updateSourceMetadata(tenantUuid, matterUuid, in.uuid, "clio", remoteId, safe(remote.updatedAt), true);
        documents.DocumentRec persisted = documentStore.get(tenantUuid, matterUuid, in.uuid);
        return new UpsertedDocument(persisted, created);
    }

    private String ensureClioPart(String tenantUuid, String matterUuid, String docUuid) throws Exception {
        List<document_parts.PartRec> parts = partStore.listAll(tenantUuid, matterUuid, docUuid);
        for (document_parts.PartRec part : parts) {
            if (part == null || part.trashed) continue;
            String id = safe(part.uuid).trim();
            if (!id.isBlank()) return id;
        }
        document_parts.PartRec created = partStore.createForExternalSync(
                tenantUuid,
                matterUuid,
                docUuid,
                "Clio Document",
                "lead",
                "1",
                "",
                "Clio",
                "Auto-created for Clio sync."
        );
        return safe(created == null ? "" : created.uuid).trim();
    }

    private int importDocumentVersions(String tenantUuid,
                                       String matterUuid,
                                       String docUuid,
                                       String partUuid,
                                       ClioDocument remoteDocument,
                                       String accessToken,
                                       ClioClient client) throws Exception {
        List<ClioDocumentVersion> remoteVersions = listAllDocumentVersions(client, accessToken, safe(remoteDocument == null ? "" : remoteDocument.id));
        if (remoteVersions.isEmpty()) return 0;
        remoteVersions.sort(new Comparator<ClioDocumentVersion>() {
            @Override
            public int compare(ClioDocumentVersion a, ClioDocumentVersion b) {
                return ClioDocumentVersion.compareByVersionHint(a, b);
            }
        });

        String latestVersionId = safe(remoteDocument == null ? "" : remoteDocument.latestVersionId).trim();
        if (latestVersionId.isBlank()) {
            ClioDocumentVersion tail = remoteVersions.get(remoteVersions.size() - 1);
            latestVersionId = safe(tail == null ? "" : tail.id).trim();
        }

        LinkedHashSet<String> existingSourceKeys = new LinkedHashSet<String>();
        List<part_versions.VersionRec> localVersions = versionStore.listAll(tenantUuid, matterUuid, docUuid, partUuid);
        for (part_versions.VersionRec v : localVersions) {
            if (v == null) continue;
            String source = safe(v.source).trim();
            if (!source.isBlank()) existingSourceKeys.add(source);
        }

        int imported = 0;
        for (ClioDocumentVersion base : remoteVersions) {
            if (base == null) continue;
            String versionId = safe(base.id).trim();
            if (versionId.isBlank()) continue;
            String sourceKey = base.sourceKey();
            if (sourceKey.isBlank() || existingSourceKeys.contains(sourceKey)) continue;

            ClioDocumentVersion detail = null;
            try {
                detail = client.getDocumentVersion(accessToken, versionId);
            } catch (Exception ignored) {
            }

            ClioDocumentVersion effective = mergeVersion(base, detail);
            if (detail != null && !detail.fullyUploaded) continue;

            ClioClient.DownloadedFile downloaded = client.downloadDocumentVersion(accessToken, versionId);
            Path stored = writeVersionFile(tenantUuid, matterUuid, docUuid, partUuid, effective, downloaded);
            long fileSize = Files.size(stored);
            String checksum = sha256(stored);
            String mimeType = firstNonBlank(
                    safe(effective.contentType).trim(),
                    safe(downloaded.contentType).trim(),
                    "application/octet-stream"
            );

            versionStore.createForExternalSync(
                    tenantUuid,
                    matterUuid,
                    docUuid,
                    partUuid,
                    effective.versionLabel(),
                    sourceKey,
                    mimeType,
                    checksum,
                    String.valueOf(fileSize),
                    stored.toUri().toString(),
                    "clio-sync",
                    "Synced from Clio. Edit in Clio.",
                    versionId.equals(latestVersionId)
            );
            existingSourceKeys.add(sourceKey);
            imported++;
        }
        return imported;
    }

    private static ClioDocumentVersion mergeVersion(ClioDocumentVersion base, ClioDocumentVersion detail) {
        if (detail == null) return base;
        if (base == null) return detail;
        return new ClioDocumentVersion(
                firstNonBlank(detail.id, base.id),
                firstNonBlank(detail.uuid, base.uuid),
                firstNonBlank(detail.versionNumber, base.versionNumber),
                firstNonBlank(detail.name, base.name),
                firstNonBlank(detail.filename, base.filename),
                firstNonBlank(detail.contentType, base.contentType),
                detail.sizeBytes > 0L ? detail.sizeBytes : base.sizeBytes,
                firstNonBlank(detail.createdAt, base.createdAt),
                firstNonBlank(detail.updatedAt, base.updatedAt),
                detail.fullyUploaded || base.fullyUploaded
        );
    }

    private List<ClioDocument> listAllDocumentsForMatter(ClioClient client, String accessToken, String clioMatterId) throws Exception {
        ArrayList<ClioDocument> out = new ArrayList<ClioDocument>();
        int page = 1;
        while (true) {
            List<ClioDocument> rows = client.listDocumentsForMatter(accessToken, clioMatterId, page, DOCUMENTS_PAGE_SIZE);
            if (rows.isEmpty()) break;
            out.addAll(rows);
            if (rows.size() < DOCUMENTS_PAGE_SIZE) break;
            page++;
        }
        return out;
    }

    private List<ClioDocumentVersion> listAllDocumentVersions(ClioClient client, String accessToken, String clioDocumentId) throws Exception {
        ArrayList<ClioDocumentVersion> out = new ArrayList<ClioDocumentVersion>();
        int page = 1;
        while (true) {
            List<ClioDocumentVersion> rows = client.listDocumentVersions(accessToken, clioDocumentId, page, DOCUMENTS_PAGE_SIZE);
            if (rows.isEmpty()) break;
            out.addAll(rows);
            if (rows.size() < DOCUMENTS_PAGE_SIZE) break;
            page++;
        }
        return out;
    }

    private Path writeVersionFile(String tenantUuid,
                                  String matterUuid,
                                  String docUuid,
                                  String partUuid,
                                  ClioDocumentVersion version,
                                  ClioClient.DownloadedFile downloaded) throws Exception {
        Path partFolder = partStore.partFolder(tenantUuid, matterUuid, docUuid, partUuid);
        if (partFolder == null) throw new IllegalStateException("Part folder unavailable.");
        Path outputDir = partFolder.resolve("version_files");
        Files.createDirectories(outputDir);

        String preferred = firstNonBlank(
                safe(version == null ? "" : version.preferredFileName()),
                safe(downloaded == null ? "" : downloaded.fileName),
                "clio-version.bin"
        );
        String name = safeFileName(preferred);
        Path output = outputDir.resolve(java.util.UUID.randomUUID().toString() + "__" + name);
        byte[] bytes = downloaded == null ? new byte[0] : downloaded.bytes;
        Files.write(output, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        return output;
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] bytes = Files.readAllBytes(path);
        byte[] digest = md.digest(bytes == null ? new byte[0] : bytes);
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) sb.append(String.format(java.util.Locale.ROOT, "%02x", b));
        return sb.toString();
    }

    private static String safeFileName(String value) {
        String v = safe(value).trim().replaceAll("[^A-Za-z0-9._-]", "_");
        if (v.isBlank()) return "clio-version.bin";
        if (v.length() > 140) return v.substring(v.length() - 140);
        return v;
    }

    private static String isoDate(String iso) {
        String v = safe(iso).trim();
        if (v.length() >= 10 && v.charAt(4) == '-' && v.charAt(7) == '-') return v.substring(0, 10);
        return "";
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) {
            String v = safe(value).trim();
            if (!v.isBlank()) return v;
        }
        return "";
    }

    private boolean isSyncDue(String tenantUuid) {
        String tu = safe(tenantUuid).trim();
        if (tu.isBlank()) return false;
        if (!isEnabled(tu)) return false;
        LinkedHashMap<String, String> cfg = settingsStore.read(tu);
        long intervalMinutes = parseLong(cfg.get(CLIO_SYNC_INTERVAL_MINUTES_KEY), DEFAULT_SYNC_INTERVAL_MINUTES);
        if (intervalMinutes < 1L) intervalMinutes = DEFAULT_SYNC_INTERVAL_MINUTES;
        String lastRunIso = safe(cfg.get(CLIO_DOCUMENTS_LAST_SYNC_AT_KEY)).trim();
        if (lastRunIso.isBlank()) return true;
        try {
            Instant last = Instant.parse(lastRunIso);
            Instant next = last.plusSeconds(intervalMinutes * 60L);
            return !Instant.now().isBefore(next);
        } catch (Exception ignored) {
            return true;
        }
    }

    private static long parseLong(String raw, long fallback) {
        try {
            return Long.parseLong(safe(raw).trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private void writeContactSyncStatus(String tenantUuid,
                                        LinkedHashMap<String, String> cfg,
                                        boolean ok,
                                        String error) {
        try {
            LinkedHashMap<String, String> nextCfg = new LinkedHashMap<String, String>(cfg == null ? Map.of() : cfg);
            nextCfg.put("clio_contacts_last_sync_at", Instant.now().toString());
            nextCfg.put("clio_contacts_last_sync_status", ok ? "ok" : "failed");
            nextCfg.put("clio_contacts_last_sync_error", safe(error));
            settingsStore.write(tenantUuid, nextCfg);
        } catch (Exception ignored) {
        }
    }

    private static contacts.ContactInput toContactInput(ClioContact c) {
        contacts.ContactInput in = new contacts.ContactInput();
        if (c == null) return in;
        in.displayName = safe(c.displayName);
        in.givenName = safe(c.givenName);
        in.middleName = safe(c.middleName);
        in.surname = safe(c.surname);
        in.companyName = safe(c.companyName);
        in.jobTitle = safe(c.jobTitle);
        in.emailPrimary = safe(c.emailPrimary);
        in.emailSecondary = safe(c.emailSecondary);
        in.emailTertiary = safe(c.emailTertiary);
        in.businessPhone = safe(c.businessPhone);
        in.businessPhone2 = safe(c.businessPhone2);
        in.mobilePhone = safe(c.mobilePhone);
        in.homePhone = safe(c.homePhone);
        in.otherPhone = safe(c.otherPhone);
        in.website = safe(c.website);
        in.street = safe(c.street);
        in.city = safe(c.city);
        in.state = safe(c.state);
        in.postalCode = safe(c.postalCode);
        in.country = safe(c.country);
        in.notes = safe(c.notes);
        return in;
    }

    private static final class MatterDocumentSyncCounts {
        final int documentsUpserted;
        final int versionsImported;

        MatterDocumentSyncCounts(int documentsUpserted, int versionsImported) {
            this.documentsUpserted = Math.max(0, documentsUpserted);
            this.versionsImported = Math.max(0, versionsImported);
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
