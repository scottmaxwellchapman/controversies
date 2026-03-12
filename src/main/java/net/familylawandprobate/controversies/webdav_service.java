package net.familylawandprobate.controversies;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLConnection;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class webdav_service {

    public static final String REALM = "Controversies WebDAV";

    private static final String AUTO_LEAD_PART_LABEL = "Lead";
    private static final String ORPHAN_PART_LABEL = "Orphan Uploads";

    private final tenants tenantStore;
    private final users_roles usersStore;
    private final matters mattersStore;
    private final documents documentsStore;
    private final document_parts partsStore;
    private final part_versions versionsStore;

    public static webdav_service defaultService() {
        return new webdav_service(
                tenants.defaultStore(),
                users_roles.defaultStore(),
                matters.defaultStore(),
                documents.defaultStore(),
                document_parts.defaultStore(),
                part_versions.defaultStore()
        );
    }

    webdav_service(tenants tenantStore,
                   users_roles usersStore,
                   matters mattersStore,
                   documents documentsStore,
                   document_parts partsStore,
                   part_versions versionsStore) {
        this.tenantStore = tenantStore;
        this.usersStore = usersStore;
        this.mattersStore = mattersStore;
        this.documentsStore = documentsStore;
        this.partsStore = partsStore;
        this.versionsStore = versionsStore;
    }

    public enum Kind {
        ROOT,
        CASE,
        DOCUMENT,
        PART,
        FILE
    }

    public static final class WebDavException extends Exception {
        public final int status;
        public final boolean challenge;

        WebDavException(int status, String message, boolean challenge) {
            super(safe(message));
            this.status = status;
            this.challenge = challenge;
        }
    }

    public static final class Principal {
        public final String tenantUuid;
        public final String tenantSlug;
        public final String tenantLabel;
        public final String userUuid;
        public final String email;

        Principal(String tenantUuid,
                  String tenantSlug,
                  String tenantLabel,
                  String userUuid,
                  String email) {
            this.tenantUuid = safe(tenantUuid).trim();
            this.tenantSlug = safe(tenantSlug).trim();
            this.tenantLabel = safe(tenantLabel).trim();
            this.userUuid = safe(userUuid).trim();
            this.email = safe(email).trim().toLowerCase(Locale.ROOT);
        }

        public String actor() {
            if (!email.isBlank()) return email;
            return userUuid;
        }
    }

    public static final class Resource {
        public final Kind kind;
        public final boolean collection;
        public final List<String> canonicalSegments;
        public final String displayName;

        public final matters.MatterRec matter;
        public final documents.DocumentRec document;
        public final document_parts.PartRec part;
        public final part_versions.VersionRec version;

        public final boolean currentAlias;
        public final String fileName;
        public final Path storagePath;
        public final long contentLength;
        public final String contentType;
        public final String etag;
        public final Instant modifiedAt;

        Resource(Kind kind,
                 boolean collection,
                 List<String> canonicalSegments,
                 String displayName,
                 matters.MatterRec matter,
                 documents.DocumentRec document,
                 document_parts.PartRec part,
                 part_versions.VersionRec version,
                 boolean currentAlias,
                 String fileName,
                 Path storagePath,
                 long contentLength,
                 String contentType,
                 String etag,
                 Instant modifiedAt) {
            this.kind = kind;
            this.collection = collection;
            this.canonicalSegments = canonicalSegments == null ? List.of() : List.copyOf(canonicalSegments);
            this.displayName = safe(displayName);
            this.matter = matter;
            this.document = document;
            this.part = part;
            this.version = version;
            this.currentAlias = currentAlias;
            this.fileName = safe(fileName);
            this.storagePath = storagePath;
            this.contentLength = contentLength;
            this.contentType = safe(contentType);
            this.etag = safe(etag);
            this.modifiedAt = modifiedAt;
        }
    }

    private static final class RealmUser {
        final String tenantToken;
        final String email;

        RealmUser(String tenantToken, String email) {
            this.tenantToken = safe(tenantToken).trim();
            this.email = safe(email).trim().toLowerCase(Locale.ROOT);
        }
    }

    public Principal authenticate(String authorizationHeader) throws Exception {
        String pair = decodeBasicPair(authorizationHeader);
        int idx = pair.indexOf(':');
        if (idx <= 0) {
            throw unauthorized("Basic authentication credentials are required.");
        }
        String username = pair.substring(0, idx);
        String passwordRaw = pair.substring(idx + 1);

        RealmUser realmUser = parseRealmUser(username);
        tenants.Tenant tenant = resolveTenantByToken(realmUser.tenantToken);
        if (tenant == null) {
            throw unauthorized("Unknown tenant slug in username.");
        }

        users_roles.AuthResult auth = usersStore.authenticate(
                safe(tenant.uuid).trim(),
                realmUser.email,
                passwordRaw.toCharArray()
        );
        if (auth == null || auth.user == null) {
            throw unauthorized("Invalid WebDAV username or password.");
        }
        if (!hasDocumentsAccess(auth.permissions)) {
            throw forbidden("This user does not have documents.access permission.");
        }

        return new Principal(
                safe(tenant.uuid).trim(),
                tenantSlug(tenant.label),
                tenant.label,
                safe(auth.user.uuid).trim(),
                safe(auth.user.emailAddress).trim().toLowerCase(Locale.ROOT)
        );
    }

    public List<Resource> propfind(Principal principal, String rawPath, int depth) throws Exception {
        Resource target = resolveExisting(principal, rawPath);
        if (target == null) {
            throw notFound("WebDAV path not found.");
        }

        ArrayList<Resource> out = new ArrayList<Resource>();
        out.add(target);
        if (depth > 0 && target.collection) {
            out.addAll(children(principal, target));
        }
        return out;
    }

    public Resource fileForGet(Principal principal, String rawPath) throws Exception {
        Resource target = resolveExisting(principal, rawPath);
        if (target == null) throw notFound("WebDAV file not found.");
        if (target.collection || target.kind != Kind.FILE) {
            throw status(405, "GET is only available for file resources.", false);
        }
        if (target.storagePath == null || !Files.isRegularFile(target.storagePath)) {
            throw notFound("Version file not found on disk.");
        }
        return target;
    }

    public Resource mkcol(Principal principal, String rawPath) throws Exception {
        List<String> segments = parseSegments(rawPath);
        if (segments.isEmpty()) {
            throw status(405, "Cannot create collection at WebDAV root.", false);
        }

        Resource existing = resolveExisting(principal, rawPath);
        if (existing != null) {
            throw status(405, "Collection already exists.", false);
        }

        if (segments.size() == 1) {
            String label = labelForCreateSegment(segments.get(0), "New Case");
            matters.MatterRec created = mattersStore.create(
                    principal.tenantUuid,
                    label,
                    "",
                    "",
                    "",
                    "",
                    ""
            );
            return caseResource(List.of(caseSegment(created)), created);
        }

        if (segments.size() == 2) {
            matters.MatterRec matter = resolveMatter(principal.tenantUuid, segments.get(0));
            if (matter == null) throw status(409, "Case folder does not exist.", false);
            String label = labelForCreateSegment(segments.get(1), "New Document");
            documents.DocumentRec created = withActor(principal.actor(), () -> documentsStore.create(
                    principal.tenantUuid,
                    matter.uuid,
                    label,
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    ""
            ));
            ArrayList<String> path = new ArrayList<String>();
            path.add(caseSegment(matter));
            path.add(documentSegment(created));
            return documentResource(path, matter, created);
        }

        if (segments.size() == 3) {
            matters.MatterRec matter = resolveMatter(principal.tenantUuid, segments.get(0));
            if (matter == null) throw status(409, "Case folder does not exist.", false);
            documents.DocumentRec doc = resolveDocument(principal.tenantUuid, matter.uuid, segments.get(1));
            if (doc == null) throw status(409, "Document folder does not exist.", false);

            String label = labelForCreateSegment(segments.get(2), "New Part");
            String sequence = nextPartSequence(activeParts(principal.tenantUuid, matter.uuid, doc.uuid));
            document_parts.PartRec part = withActor(principal.actor(), () -> partsStore.create(
                    principal.tenantUuid,
                    matter.uuid,
                    doc.uuid,
                    label,
                    "attachment",
                    sequence,
                    "",
                    principal.actor(),
                    "Created via WebDAV MKCOL"
            ));
            ArrayList<String> path = new ArrayList<String>();
            path.add(caseSegment(matter));
            path.add(documentSegment(doc));
            path.add(partSegment(part));
            return partResource(path, matter, doc, part);
        }

        throw status(409, "Collection depth is not supported.", false);
    }

    public Resource put(Principal principal,
                        String rawPath,
                        String requestContentType,
                        InputStream body) throws Exception {
        if (body == null) throw badRequest("Missing upload body.");

        List<String> segments = parseSegments(rawPath);
        if (segments.size() < 3 || segments.size() > 4) {
            throw status(409, "Upload target must be inside a document or part folder.", false);
        }

        matters.MatterRec matter = resolveMatter(principal.tenantUuid, segments.get(0));
        if (matter == null) throw notFound("Case folder not found.");

        documents.DocumentRec doc = resolveDocument(principal.tenantUuid, matter.uuid, segments.get(1));
        if (doc == null) throw notFound("Document folder not found.");

        String fileName = safe(segments.get(segments.size() - 1)).trim();
        if (fileName.isBlank()) throw badRequest("Upload file name is required.");

        document_parts.PartRec part;
        if (segments.size() == 3) {
            part = withActor(principal.actor(), () -> resolvePartForDocumentUpload(principal, matter, doc));
        } else {
            part = resolvePart(principal.tenantUuid, matter.uuid, doc.uuid, segments.get(2));
            if (part == null) throw notFound("Part folder not found.");
        }

        part_versions.VersionRec created = withActor(principal.actor(), () -> createVersionFromStream(
                principal,
                matter,
                doc,
                part,
                fileName,
                requestContentType,
                body
        ));

        ArrayList<String> path = new ArrayList<String>();
        path.add(caseSegment(matter));
        path.add(documentSegment(doc));
        path.add(partSegment(part));
        path.add(versionFileName(created));
        return fileResource(path, principal.tenantUuid, matter, doc, part, created, false);
    }

    public void delete(Principal principal, String rawPath) throws Exception {
        Resource target = resolveExisting(principal, rawPath);
        if (target == null) throw notFound("WebDAV path not found.");

        if (target.kind == Kind.ROOT) {
            throw status(405, "Cannot delete WebDAV root.", false);
        }
        if (target.kind == Kind.FILE) {
            throw status(405, "Deleting individual versions is not supported over WebDAV.", false);
        }

        if (target.kind == Kind.CASE && target.matter != null) {
            mattersStore.trash(principal.tenantUuid, target.matter.uuid);
            return;
        }

        if (target.kind == Kind.DOCUMENT && target.matter != null && target.document != null) {
            withActor(principal.actor(), () -> {
                documentsStore.setTrashed(principal.tenantUuid, target.matter.uuid, target.document.uuid, true);
                return Boolean.TRUE;
            });
            return;
        }

        if (target.kind == Kind.PART && target.matter != null && target.document != null && target.part != null) {
            withActor(principal.actor(), () -> {
                partsStore.setTrashed(principal.tenantUuid, target.matter.uuid, target.document.uuid, target.part.uuid, true);
                return Boolean.TRUE;
            });
            return;
        }

        throw status(409, "Delete target could not be resolved.", false);
    }

    Resource resolveExisting(Principal principal, String rawPath) throws Exception {
        List<String> segments = parseSegments(rawPath);

        if (segments.isEmpty()) {
            return new Resource(
                    Kind.ROOT,
                    true,
                    List.of(),
                    "webdav",
                    null,
                    null,
                    null,
                    null,
                    false,
                    "",
                    null,
                    0L,
                    "",
                    "",
                    app_clock.now()
            );
        }

        matters.MatterRec matter = resolveMatter(principal.tenantUuid, segments.get(0));
        if (matter == null) return null;

        ArrayList<String> path = new ArrayList<String>();
        path.add(caseSegment(matter));
        if (segments.size() == 1) {
            return caseResource(path, matter);
        }

        documents.DocumentRec doc = resolveDocument(principal.tenantUuid, matter.uuid, segments.get(1));
        if (doc == null) return null;
        path.add(documentSegment(doc));
        if (segments.size() == 2) {
            return documentResource(path, matter, doc);
        }

        document_parts.PartRec part = resolvePart(principal.tenantUuid, matter.uuid, doc.uuid, segments.get(2));
        if (part == null) return null;
        path.add(partSegment(part));
        if (segments.size() == 3) {
            return partResource(path, matter, doc, part);
        }

        if (segments.size() == 4) {
            List<Resource> files = partFiles(path, principal.tenantUuid, matter, doc, part);
            String lookup = safe(segments.get(3)).trim();
            String lookupLower = lookup.toLowerCase(Locale.ROOT);
            for (Resource r : files) {
                if (lookupLower.equals(safe(r.fileName).trim().toLowerCase(Locale.ROOT))) return r;
            }
            String uuid = extractUuidSuffix(lookup);
            if (!uuid.isBlank()) {
                for (Resource r : files) {
                    if (r.version != null && uuid.equalsIgnoreCase(safe(r.version.uuid).trim())) return r;
                }
            }
            return null;
        }

        return null;
    }

    List<Resource> children(Principal principal, Resource parent) throws Exception {
        if (parent == null || !parent.collection) return List.of();

        if (parent.kind == Kind.ROOT) {
            List<matters.MatterRec> cases = activeMatters(principal.tenantUuid);
            ArrayList<Resource> out = new ArrayList<Resource>(cases.size());
            for (matters.MatterRec m : cases) {
                out.add(caseResource(List.of(caseSegment(m)), m));
            }
            return out;
        }

        if (parent.kind == Kind.CASE && parent.matter != null) {
            List<documents.DocumentRec> docs = activeDocuments(principal.tenantUuid, parent.matter.uuid);
            ArrayList<Resource> out = new ArrayList<Resource>(docs.size());
            for (documents.DocumentRec d : docs) {
                ArrayList<String> path = new ArrayList<String>();
                path.add(caseSegment(parent.matter));
                path.add(documentSegment(d));
                out.add(documentResource(path, parent.matter, d));
            }
            return out;
        }

        if (parent.kind == Kind.DOCUMENT && parent.matter != null && parent.document != null) {
            List<document_parts.PartRec> parts = activeParts(principal.tenantUuid, parent.matter.uuid, parent.document.uuid);
            ArrayList<Resource> out = new ArrayList<Resource>(parts.size());
            for (document_parts.PartRec p : parts) {
                ArrayList<String> path = new ArrayList<String>();
                path.add(caseSegment(parent.matter));
                path.add(documentSegment(parent.document));
                path.add(partSegment(p));
                out.add(partResource(path, parent.matter, parent.document, p));
            }
            return out;
        }

        if (parent.kind == Kind.PART && parent.matter != null && parent.document != null && parent.part != null) {
            return partFiles(parent.canonicalSegments, principal.tenantUuid, parent.matter, parent.document, parent.part);
        }

        return List.of();
    }

    private List<Resource> partFiles(List<String> parentSegments,
                                     String tenantUuid,
                                     matters.MatterRec matter,
                                     documents.DocumentRec doc,
                                     document_parts.PartRec part) throws Exception {
        List<part_versions.VersionRec> versions = versionsStore.listAll(tenantUuid, matter.uuid, doc.uuid, part.uuid);
        if (versions.isEmpty()) return List.of();

        part_versions.VersionRec current = null;
        for (part_versions.VersionRec rec : versions) {
            if (rec != null && rec.current) {
                current = rec;
                break;
            }
        }
        if (current == null) current = versions.get(0);

        ArrayList<Resource> out = new ArrayList<Resource>();
        Resource currentFile = fileResource(parentSegments, tenantUuid, matter, doc, part, current, true);
        if (currentFile.storagePath != null && Files.isRegularFile(currentFile.storagePath)) {
            out.add(currentFile);
        }

        for (part_versions.VersionRec rec : versions) {
            Resource file = fileResource(parentSegments, tenantUuid, matter, doc, part, rec, false);
            if (file.storagePath != null && Files.isRegularFile(file.storagePath)) {
                out.add(file);
            }
        }

        out.sort(Comparator.comparing(a -> safe(a.fileName).toLowerCase(Locale.ROOT)));
        return out;
    }

    private part_versions.VersionRec createVersionFromStream(Principal principal,
                                                             matters.MatterRec matter,
                                                             documents.DocumentRec doc,
                                                             document_parts.PartRec part,
                                                             String uploadFileName,
                                                             String contentType,
                                                             InputStream body) throws Exception {
        if (principal == null || matter == null || doc == null || part == null) {
            throw status(409, "Upload target is incomplete.", false);
        }

        Path partFolder = partsStore.partFolder(principal.tenantUuid, matter.uuid, doc.uuid, part.uuid);
        if (partFolder == null) throw status(409, "Part folder is unavailable.", false);

        Path outputDir = partFolder.resolve("version_files");
        Files.createDirectories(outputDir);

        String safeName = safeUploadFileName(uploadFileName);
        String generated = UUID.randomUUID().toString().replace("-", "_") + "__" + safeName;
        Path outputPath = outputDir.resolve(generated);

        MessageDigest md = MessageDigest.getInstance("SHA-256");
        long size = 0L;
        try (InputStream in = body;
             OutputStream os = Files.newOutputStream(outputPath, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
                md.update(buf, 0, n);
                size += n;
            }
        } catch (Exception ex) {
            try {
                Files.deleteIfExists(outputPath);
            } catch (Exception ignored) {
            }
            throw ex;
        }

        String checksum = hex(md.digest());
        String mime = normalizeMimeType(contentType, uploadFileName);
        String label = versionLabelForUpload(uploadFileName);

        return versionsStore.create(
                principal.tenantUuid,
                matter.uuid,
                doc.uuid,
                part.uuid,
                label,
                "webdav",
                mime,
                checksum,
                String.valueOf(size),
                outputPath.toUri().toString(),
                principal.actor(),
                "Uploaded via WebDAV",
                true
        );
    }

    private document_parts.PartRec resolvePartForDocumentUpload(Principal principal,
                                                                matters.MatterRec matter,
                                                                documents.DocumentRec doc) throws Exception {
        List<document_parts.PartRec> active = activeParts(principal.tenantUuid, matter.uuid, doc.uuid);
        if (active.isEmpty()) {
            return partsStore.create(
                    principal.tenantUuid,
                    matter.uuid,
                    doc.uuid,
                    AUTO_LEAD_PART_LABEL,
                    "lead",
                    "1",
                    "",
                    principal.actor(),
                    "Auto-created from WebDAV upload"
            );
        }

        if (active.size() == 1) {
            return active.get(0);
        }

        String orphanNorm = normalizeCompare(ORPHAN_PART_LABEL);
        for (document_parts.PartRec rec : active) {
            if (rec == null) continue;
            if (orphanNorm.equals(normalizeCompare(rec.label))) return rec;
        }

        return partsStore.create(
                principal.tenantUuid,
                matter.uuid,
                doc.uuid,
                ORPHAN_PART_LABEL,
                "attachment",
                nextPartSequence(active),
                "",
                principal.actor(),
                "Auto-created to hold document-level WebDAV uploads"
        );
    }

    private static String nextPartSequence(List<document_parts.PartRec> parts) {
        int max = 0;
        for (document_parts.PartRec rec : parts) {
            int value = parseInt(safe(rec == null ? "" : rec.sequence).trim(), 0);
            if (value > max) max = value;
        }
        int next = max + 1;
        if (next <= 0) next = 1;
        return String.valueOf(next);
    }

    private Resource caseResource(List<String> segments, matters.MatterRec matter) {
        return new Resource(
                Kind.CASE,
                true,
                segments,
                safe(matter == null ? "" : matter.label),
                matter,
                null,
                null,
                null,
                false,
                "",
                null,
                0L,
                "",
                "",
                parseInstant(safe(matter == null ? "" : matter.clioUpdatedAt))
        );
    }

    private Resource documentResource(List<String> segments,
                                      matters.MatterRec matter,
                                      documents.DocumentRec doc) {
        return new Resource(
                Kind.DOCUMENT,
                true,
                segments,
                safe(doc == null ? "" : doc.title),
                matter,
                doc,
                null,
                null,
                false,
                "",
                null,
                0L,
                "",
                "",
                parseInstant(safe(doc == null ? "" : doc.updatedAt))
        );
    }

    private Resource partResource(List<String> segments,
                                  matters.MatterRec matter,
                                  documents.DocumentRec doc,
                                  document_parts.PartRec part) {
        return new Resource(
                Kind.PART,
                true,
                segments,
                safe(part == null ? "" : part.label),
                matter,
                doc,
                part,
                null,
                false,
                "",
                null,
                0L,
                "",
                "",
                parseInstant(safe(part == null ? "" : part.updatedAt))
        );
    }

    private Resource fileResource(List<String> parentSegments,
                                  String tenantUuid,
                                  matters.MatterRec matter,
                                  documents.DocumentRec doc,
                                  document_parts.PartRec part,
                                  part_versions.VersionRec version,
                                  boolean currentAlias) {
        Path storage = storagePathSafe(tenantUuid, version);
        String fileName = currentAlias ? currentAliasFileName(part, version) : versionFileName(version);
        long size = parseLong(safe(version == null ? "" : version.fileSizeBytes), -1L);
        if (size < 0L && storage != null) {
            try {
                size = Files.size(storage);
            } catch (Exception ignored) {
                size = -1L;
            }
        }

        String mime = normalizeMimeType(safe(version == null ? "" : version.mimeType), safe(fileName));
        String etag = safe(version == null ? "" : version.checksum).trim();
        if (etag.isBlank()) etag = safe(version == null ? "" : version.uuid).trim();
        if (!etag.isBlank()) etag = "\"" + etag + "\"";

        ArrayList<String> segments = new ArrayList<String>(parentSegments == null ? List.of() : parentSegments);
        segments.add(fileName);

        return new Resource(
                Kind.FILE,
                false,
                segments,
                fileName,
                matter,
                doc,
                part,
                version,
                currentAlias,
                fileName,
                storage,
                size,
                mime,
                etag,
                parseInstant(safe(version == null ? "" : version.createdAt))
        );
    }

    private Path storagePathSafe(String tenantUuid, part_versions.VersionRec version) {
        Path p = pdf_redaction_service.resolveStoragePath(safe(version == null ? "" : version.storagePath));
        if (p == null) return null;
        if (!pdf_redaction_service.isPathWithinTenant(p, tenantUuid)) return null;
        return p;
    }

    private matters.MatterRec resolveMatter(String tenantUuid, String segment) throws Exception {
        List<matters.MatterRec> all = activeMatters(tenantUuid);
        String uuid = extractUuidSuffix(segment);
        if (!uuid.isBlank()) {
            for (matters.MatterRec rec : all) {
                if (uuid.equalsIgnoreCase(safe(rec == null ? "" : rec.uuid).trim())) return rec;
            }
        }

        String cmp = normalizeCompare(stripUuidSuffix(segment));
        for (matters.MatterRec rec : all) {
            if (cmp.equals(normalizeCompare(caseSegment(rec)))) return rec;
        }
        for (matters.MatterRec rec : all) {
            if (cmp.equals(normalizeCompare(safe(rec == null ? "" : rec.label)))) return rec;
        }
        return null;
    }

    private documents.DocumentRec resolveDocument(String tenantUuid, String matterUuid, String segment) throws Exception {
        List<documents.DocumentRec> all = activeDocuments(tenantUuid, matterUuid);
        String uuid = extractUuidSuffix(segment);
        if (!uuid.isBlank()) {
            for (documents.DocumentRec rec : all) {
                if (uuid.equalsIgnoreCase(safe(rec == null ? "" : rec.uuid).trim())) return rec;
            }
        }

        String cmp = normalizeCompare(stripUuidSuffix(segment));
        for (documents.DocumentRec rec : all) {
            if (cmp.equals(normalizeCompare(documentSegment(rec)))) return rec;
        }
        for (documents.DocumentRec rec : all) {
            if (cmp.equals(normalizeCompare(safe(rec == null ? "" : rec.title)))) return rec;
        }
        return null;
    }

    private document_parts.PartRec resolvePart(String tenantUuid,
                                               String matterUuid,
                                               String docUuid,
                                               String segment) throws Exception {
        List<document_parts.PartRec> all = activeParts(tenantUuid, matterUuid, docUuid);
        String uuid = extractUuidSuffix(segment);
        if (!uuid.isBlank()) {
            for (document_parts.PartRec rec : all) {
                if (uuid.equalsIgnoreCase(safe(rec == null ? "" : rec.uuid).trim())) return rec;
            }
        }

        String cmp = normalizeCompare(stripUuidSuffix(segment));
        for (document_parts.PartRec rec : all) {
            if (cmp.equals(normalizeCompare(partSegment(rec)))) return rec;
        }
        for (document_parts.PartRec rec : all) {
            if (cmp.equals(normalizeCompare(safe(rec == null ? "" : rec.label)))) return rec;
        }
        return null;
    }

    private List<matters.MatterRec> activeMatters(String tenantUuid) throws Exception {
        List<matters.MatterRec> all = mattersStore.listAll(tenantUuid);
        ArrayList<matters.MatterRec> out = new ArrayList<matters.MatterRec>();
        for (matters.MatterRec rec : all) {
            if (rec == null) continue;
            if (!rec.enabled) continue;
            if (rec.trashed) continue;
            out.add(rec);
        }
        out.sort(Comparator.comparing(a -> normalizeCompare(safe(a == null ? "" : a.label))));
        return out;
    }

    private List<documents.DocumentRec> activeDocuments(String tenantUuid, String matterUuid) throws Exception {
        List<documents.DocumentRec> all = documentsStore.listAll(tenantUuid, matterUuid);
        ArrayList<documents.DocumentRec> out = new ArrayList<documents.DocumentRec>();
        for (documents.DocumentRec rec : all) {
            if (rec == null) continue;
            if (rec.trashed) continue;
            out.add(rec);
        }
        out.sort(Comparator.comparing(a -> normalizeCompare(safe(a == null ? "" : a.title))));
        return out;
    }

    private List<document_parts.PartRec> activeParts(String tenantUuid, String matterUuid, String docUuid) throws Exception {
        List<document_parts.PartRec> all = partsStore.listAll(tenantUuid, matterUuid, docUuid);
        ArrayList<document_parts.PartRec> out = new ArrayList<document_parts.PartRec>();
        for (document_parts.PartRec rec : all) {
            if (rec == null) continue;
            if (rec.trashed) continue;
            out.add(rec);
        }
        out.sort(Comparator.comparing(a -> normalizeCompare(safe(a == null ? "" : a.sequence) + " " + safe(a == null ? "" : a.label))));
        return out;
    }

    private static String decodeBasicPair(String authHeader) throws WebDavException {
        String raw = safe(authHeader).trim();
        if (raw.isBlank()) {
            throw unauthorized("Basic authentication is required.");
        }
        if (!raw.regionMatches(true, 0, "basic ", 0, 6)) {
            throw unauthorized("Basic authentication is required.");
        }
        String token = raw.substring(6).trim();
        if (token.isBlank()) throw unauthorized("Basic authentication is required.");

        try {
            byte[] decoded = Base64.getDecoder().decode(token);
            return new String(decoded, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw unauthorized("Invalid basic authentication header.");
        }
    }

    private RealmUser parseRealmUser(String username) throws WebDavException {
        String raw = safe(username).trim();
        int idx = raw.indexOf('\\');
        if (idx <= 0 || idx + 1 >= raw.length()) {
            throw unauthorized("Username must use tenant_slug\\email_address format.");
        }
        String tenantToken = raw.substring(0, idx).trim();
        String email = raw.substring(idx + 1).trim();
        if (tenantToken.isBlank() || email.isBlank()) {
            throw unauthorized("Username must use tenant_slug\\email_address format.");
        }
        return new RealmUser(tenantToken, email);
    }

    private tenants.Tenant resolveTenantByToken(String tenantToken) throws Exception {
        String token = safe(tenantToken).trim().toLowerCase(Locale.ROOT);
        if (token.isBlank()) return null;

        List<tenants.Tenant> all = tenantStore.list();
        tenants.Tenant uuidMatch = null;
        ArrayList<tenants.Tenant> slugMatches = new ArrayList<tenants.Tenant>();

        for (tenants.Tenant t : all) {
            if (t == null || !t.enabled) continue;
            String uuid = safe(t.uuid).trim().toLowerCase(Locale.ROOT);
            if (token.equals(uuid)) {
                uuidMatch = t;
                break;
            }
            String slug = tenantSlug(t.label);
            if (token.equals(slug)) slugMatches.add(t);
        }

        if (uuidMatch != null) return uuidMatch;
        if (slugMatches.isEmpty()) return null;
        if (slugMatches.size() == 1) return slugMatches.get(0);
        throw unauthorized("Tenant slug is ambiguous. Use a unique slug.");
    }

    static String tenantSlug(String label) {
        String lower = safe(label).trim().toLowerCase(Locale.ROOT);
        if (lower.isBlank()) return "tenant";
        String normalized = lower.replaceAll("[^a-z0-9]+", "-");
        normalized = normalized.replaceAll("^-+", "").replaceAll("-+$", "");
        if (normalized.isBlank()) return "tenant";
        if (normalized.length() > 80) normalized = normalized.substring(0, 80);
        return normalized;
    }

    static String caseSegment(matters.MatterRec rec) {
        String label = safe(rec == null ? "" : rec.label).trim();
        String id = safe(rec == null ? "" : rec.uuid).trim();
        return safeSegmentLabel(label, "Case") + "__" + id;
    }

    static String documentSegment(documents.DocumentRec rec) {
        String label = safe(rec == null ? "" : rec.title).trim();
        String id = safe(rec == null ? "" : rec.uuid).trim();
        return safeSegmentLabel(label, "Document") + "__" + id;
    }

    static String partSegment(document_parts.PartRec rec) {
        String label = safe(rec == null ? "" : rec.label).trim();
        String seq = safe(rec == null ? "" : rec.sequence).trim();
        String id = safe(rec == null ? "" : rec.uuid).trim();
        String composite = seq.isBlank() ? label : (seq + " " + label).trim();
        return safeSegmentLabel(composite, "Part") + "__" + id;
    }

    static String versionFileName(part_versions.VersionRec rec) {
        String id = safe(rec == null ? "" : rec.uuid).trim();
        String label = safeSegmentLabel(safe(rec == null ? "" : rec.versionLabel).trim(), "Version");
        return label + "__" + id + extensionForVersion(rec);
    }

    static String currentAliasFileName(document_parts.PartRec part, part_versions.VersionRec currentVersion) {
        String label = safeSegmentLabel(safe(part == null ? "" : part.label).trim(), "Part");
        return "Current - " + label + extensionForVersion(currentVersion);
    }

    private static String extensionForVersion(part_versions.VersionRec rec) {
        String ext = extensionFromFileName(pathFileName(safe(rec == null ? "" : rec.storagePath)));
        if (!ext.isBlank()) return ext;

        String mime = safe(rec == null ? "" : rec.mimeType).trim().toLowerCase(Locale.ROOT);
        if (mime.contains("pdf")) return ".pdf";
        if (mime.contains("wordprocessingml")) return ".docx";
        if (mime.contains("msword")) return ".doc";
        if (mime.contains("text/plain")) return ".txt";
        if (mime.contains("json")) return ".json";
        if (mime.contains("xml")) return ".xml";
        if (mime.contains("jpeg")) return ".jpg";
        if (mime.contains("png")) return ".png";
        if (mime.contains("csv")) return ".csv";
        return ".bin";
    }

    private static String pathFileName(String storagePath) {
        Path p = pdf_redaction_service.resolveStoragePath(storagePath);
        if (p == null || p.getFileName() == null) return "";
        return safe(p.getFileName().toString());
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

    private static String versionLabelForUpload(String fileName) {
        String base = stripExtension(safe(fileName).trim());
        base = base.replace('_', ' ').replaceAll("\\s+", " ").trim();
        if (base.isBlank()) base = "WebDAV Upload";
        if (base.length() > 120) base = base.substring(0, 120).trim();
        return base;
    }

    private static String safeUploadFileName(String raw) {
        String v = safe(raw).trim();
        if (v.isBlank()) v = "upload.bin";
        v = v.replaceAll("[\\u0000-\\u001F\\u007F]", "_");
        v = v.replaceAll("[\\\\/:*?\"<>|]", "_");
        while (v.startsWith(".")) v = v.substring(1);
        while (v.endsWith(".")) v = v.substring(0, v.length() - 1);
        if (v.isBlank()) v = "upload.bin";
        if (v.length() > 140) v = v.substring(v.length() - 140);
        return v;
    }

    private static String normalizeMimeType(String raw, String fileName) {
        String mime = safe(raw).trim().toLowerCase(Locale.ROOT);
        int semicolon = mime.indexOf(';');
        if (semicolon > 0) mime = mime.substring(0, semicolon).trim();
        if (!mime.isBlank() && !"application/octet-stream".equals(mime)) return mime;

        String guessed = safe(URLConnection.guessContentTypeFromName(safe(fileName))).trim().toLowerCase(Locale.ROOT);
        if (!guessed.isBlank()) return guessed;

        String ext = extensionFromFileName(fileName);
        if (".pdf".equals(ext)) return "application/pdf";
        if (".docx".equals(ext)) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (".doc".equals(ext)) return "application/msword";
        if (".txt".equals(ext)) return "text/plain";
        if (".json".equals(ext)) return "application/json";
        if (".xml".equals(ext)) return "application/xml";
        if (".csv".equals(ext)) return "text/csv";
        if (".jpg".equals(ext) || ".jpeg".equals(ext)) return "image/jpeg";
        if (".png".equals(ext)) return "image/png";
        return "application/octet-stream";
    }

    private static String extensionFromFileName(String name) {
        String n = safe(name).trim();
        int slash = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
        if (slash >= 0 && slash + 1 < n.length()) n = n.substring(slash + 1);
        int dot = n.lastIndexOf('.');
        if (dot <= 0 || dot + 1 >= n.length()) return "";
        String ext = n.substring(dot).toLowerCase(Locale.ROOT);
        if (!ext.matches("\\.[a-z0-9]{1,10}")) return "";
        return ext;
    }

    private static String stripExtension(String name) {
        String n = safe(name).trim();
        int slash = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
        if (slash >= 0 && slash + 1 < n.length()) n = n.substring(slash + 1);
        int dot = n.lastIndexOf('.');
        if (dot <= 0) return n;
        return n.substring(0, dot);
    }

    private static String labelForCreateSegment(String segment, String fallback) {
        String s = stripUuidSuffix(safe(segment).trim());
        s = s.replace('_', ' ').replaceAll("\\s+", " ").trim();
        if (s.isBlank()) s = fallback;
        if (s.length() > 140) s = s.substring(0, 140).trim();
        if (s.isBlank()) s = fallback;
        return s;
    }

    private static List<String> parseSegments(String rawPath) throws WebDavException {
        String p = safe(rawPath).trim();
        if (p.isBlank() || "/".equals(p)) return List.of();

        p = p.replace('\\', '/');
        while (p.startsWith("/")) p = p.substring(1);
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        if (p.isBlank()) return List.of();

        String[] parts = p.split("/");
        ArrayList<String> segments = new ArrayList<String>();
        for (String raw : parts) {
            String seg = decodeSegment(raw);
            if (seg.isBlank()) continue;
            if (".".equals(seg) || "..".equals(seg)) {
                throw badRequest("Invalid WebDAV path segment.");
            }
            if (seg.contains("/") || seg.contains("\\")) {
                throw badRequest("Invalid WebDAV path segment.");
            }
            segments.add(seg);
        }
        return segments;
    }

    private static String decodeSegment(String raw) throws WebDavException {
        try {
            return URLDecoder.decode(safe(raw).replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw badRequest("Invalid URL encoding in path.");
        }
    }

    private static String stripUuidSuffix(String segment) {
        String s = safe(segment).trim();
        int idx = s.lastIndexOf("__");
        if (idx <= 0 || idx + 2 >= s.length()) return s;
        String maybe = s.substring(idx + 2).trim();
        if (!looksUuid(maybe)) return s;
        return s.substring(0, idx).trim();
    }

    private static String extractUuidSuffix(String segment) {
        String s = safe(segment).trim();
        int idx = s.lastIndexOf("__");
        if (idx <= 0 || idx + 2 >= s.length()) return "";
        String maybe = s.substring(idx + 2).trim();
        if (!looksUuid(maybe)) return "";
        return maybe;
    }

    private static boolean looksUuid(String raw) {
        String s = safe(raw).trim();
        if (s.length() != 36) return false;
        return s.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    }

    private static String normalizeCompare(String raw) {
        String s = safe(raw).trim().toLowerCase(Locale.ROOT);
        if (s.isBlank()) return "";
        s = s.replaceAll("__", " ");
        s = s.replaceAll("[_-]", " ");
        s = s.replaceAll("\\s+", " ").trim();
        return s;
    }

    private static String hex(byte[] raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder(raw.length * 2);
        for (byte b : raw) sb.append(String.format(Locale.ROOT, "%02x", b));
        return sb.toString();
    }

    private static int parseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(safe(raw).trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static long parseLong(String raw, long fallback) {
        try {
            return Long.parseLong(safe(raw).trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static Instant parseInstant(String raw) {
        String v = safe(raw).trim();
        if (v.isBlank()) return null;
        try {
            return Instant.parse(v);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean hasDocumentsAccess(Map<String, String> permissions) {
        if (permissions == null || permissions.isEmpty()) return false;
        if (isTrue(permissions.get("tenant_admin"))) return true;
        return isTrue(permissions.get("documents.access"));
    }

    private static boolean isTrue(String raw) {
        return "true".equalsIgnoreCase(safe(raw).trim());
    }

    private <T> T withActor(String actor, ThrowingSupplier<T> supplier) throws Exception {
        documents.bindActorContext(actor);
        try {
            return supplier.get();
        } catch (WebDavException ex) {
            throw ex;
        } catch (IllegalStateException ex) {
            throw status(409, ex.getMessage(), false);
        } catch (IllegalArgumentException ex) {
            throw badRequest(ex.getMessage());
        } finally {
            documents.clearActorContext();
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private static WebDavException unauthorized(String message) {
        return new WebDavException(401, message, true);
    }

    private static WebDavException forbidden(String message) {
        return new WebDavException(403, message, false);
    }

    private static WebDavException badRequest(String message) {
        return new WebDavException(400, message, false);
    }

    private static WebDavException notFound(String message) {
        return new WebDavException(404, message, false);
    }

    private static WebDavException status(int code, String message, boolean challenge) {
        return new WebDavException(code, message, challenge);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
