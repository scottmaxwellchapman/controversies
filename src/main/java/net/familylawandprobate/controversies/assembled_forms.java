package net.familylawandprobate.controversies;

import net.familylawandprobate.controversies.storage.DocumentStorageBackend;
import net.familylawandprobate.controversies.storage.StorageBackendResolver;

import java.io.InputStream;
import java.io.StringReader;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * assembled_forms
 *
 * Per-case assembly history and saved outputs:
 *   data/tenants/{tenantUuid}/matters/{matterUuid}/assembled/assemblies.xml
 *   data/tenants/{tenantUuid}/matters/{matterUuid}/assembled/files/{assemblyUuid}.{ext}
 */
public final class assembled_forms {

    private static final ConcurrentHashMap<String, ReentrantReadWriteLock> LOCKS = new ConcurrentHashMap<String, ReentrantReadWriteLock>();
    private static final ConcurrentHashMap<String, ReentrantReadWriteLock> QUEUE_LOCKS = new ConcurrentHashMap<String, ReentrantReadWriteLock>();
    private static final AtomicBoolean WORKER_STARTED = new AtomicBoolean(false);
    private final StorageBackendResolver backendResolver;

    private static final long SYNC_BASE_BACKOFF_MS = 2_000L;
    private static final long SYNC_MAX_BACKOFF_MS = 5 * 60_000L;
    private static final int SYNC_MAX_ATTEMPTS = 8;

    public static assembled_forms defaultStore() {
        return new assembled_forms(new StorageBackendResolver());
    }

    public assembled_forms() {
        this(new StorageBackendResolver());
    }

    public assembled_forms(StorageBackendResolver backendResolver) {
        this.backendResolver = backendResolver == null ? new StorageBackendResolver() : backendResolver;
        startWorkerIfNeeded();
    }

    public static final class AssemblyRec {
        public final String uuid;
        public final String matterUuid;
        public final String templateUuid;
        public final String templateLabel;
        public final String templateExt;
        public final String status; // in_progress | completed
        public final String createdAt;
        public final String updatedAt;
        public final String userUuid;
        public final String userEmail;
        public final int overrideCount;
        public final String outputFileName;
        public final String outputFileExt;
        public final long outputSizeBytes;
        public final String storageBackendType;
        public final String storageObjectKey;
        public final String storageChecksumSha256;
        public final LinkedHashMap<String, String> overrides;

        public AssemblyRec(String uuid,
                           String matterUuid,
                           String templateUuid,
                           String templateLabel,
                           String templateExt,
                           String status,
                           String createdAt,
                           String updatedAt,
                           String userUuid,
                           String userEmail,
                           int overrideCount,
                           String outputFileName,
                           String outputFileExt,
                           long outputSizeBytes,
                           String storageBackendType,
                           String storageObjectKey,
                           String storageChecksumSha256,
                           Map<String, String> overrides) {
            this.uuid = safe(uuid);
            this.matterUuid = safe(matterUuid);
            this.templateUuid = safe(templateUuid);
            this.templateLabel = safe(templateLabel);
            this.templateExt = normalizeExtension(safe(templateExt));
            this.status = normalizeStatus(status);
            this.createdAt = safe(createdAt);
            this.updatedAt = safe(updatedAt);
            this.userUuid = safe(userUuid);
            this.userEmail = safe(userEmail);
            this.overrideCount = Math.max(0, overrideCount);
            this.outputFileName = safe(outputFileName);
            this.outputFileExt = normalizeExtension(safe(outputFileExt));
            this.outputSizeBytes = Math.max(0L, outputSizeBytes);
            this.storageBackendType = normalizeBackendType(storageBackendType);
            this.storageObjectKey = safe(storageObjectKey);
            this.storageChecksumSha256 = safe(storageChecksumSha256).toLowerCase(Locale.ROOT);
            this.overrides = sanitizeOverrides(overrides);
        }
    }


    public static final class SyncRec {
        public final String tenantUuid;
        public final String matterUuid;
        public final String assemblyUuid;
        public final String targetBackendType;
        public final String targetObjectKey;
        public final String state; // pending | retry | synced | dead_letter
        public final int attempts;
        public final String lastAttemptAt;
        public final String nextAttemptAt;
        public final String lastError;
        public final String updatedAt;

        public SyncRec(String tenantUuid, String matterUuid, String assemblyUuid, String targetBackendType, String targetObjectKey,
                       String state, int attempts, String lastAttemptAt, String nextAttemptAt, String lastError, String updatedAt) {
            this.tenantUuid = safeFileToken(tenantUuid);
            this.matterUuid = safeFileToken(matterUuid);
            this.assemblyUuid = safe(assemblyUuid).trim();
            this.targetBackendType = normalizeBackendType(targetBackendType);
            this.targetObjectKey = safe(targetObjectKey).trim();
            this.state = normalizeSyncState(state);
            this.attempts = Math.max(0, attempts);
            this.lastAttemptAt = safe(lastAttemptAt).trim();
            this.nextAttemptAt = safe(nextAttemptAt).trim();
            this.lastError = safe(lastError);
            this.updatedAt = safe(updatedAt).trim();
        }
    }

    public void ensure(String tenantUuid, String matterUuid) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String mu = safeFileToken(matterUuid);
        if (tu.isBlank() || mu.isBlank()) throw new IllegalArgumentException("tenantUuid and matterUuid required");

        ReentrantReadWriteLock lock = lockFor(tu, mu);
        lock.writeLock().lock();
        try {
            Files.createDirectories(caseDir(tu, mu));
            Path idx = indexPath(tu, mu);
            if (!Files.exists(idx)) writeAtomic(idx, emptyXml());
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<AssemblyRec> listByMatter(String tenantUuid, String matterUuid) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String mu = safeFileToken(matterUuid);
        if (tu.isBlank() || mu.isBlank()) return List.of();

        ReentrantReadWriteLock lock = lockFor(tu, mu);
        lock.readLock().lock();
        try {
            return readAllLocked(tu, mu);
        } finally {
            lock.readLock().unlock();
        }
    }

    public AssemblyRec getByUuid(String tenantUuid, String matterUuid, String assemblyUuid) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String mu = safeFileToken(matterUuid);
        String au = safe(assemblyUuid).trim();
        if (tu.isBlank() || mu.isBlank() || au.isBlank()) return null;

        ReentrantReadWriteLock lock = lockFor(tu, mu);
        lock.readLock().lock();
        try {
            List<AssemblyRec> rows = readAllLocked(tu, mu);
            for (int i = 0; i < rows.size(); i++) {
                AssemblyRec r = rows.get(i);
                if (r == null) continue;
                if (au.equals(safe(r.uuid).trim())) return r;
            }
            return null;
        } finally {
            lock.readLock().unlock();
        }
    }

    public AssemblyRec upsertInProgress(String tenantUuid,
                                        String matterUuid,
                                        String preferredAssemblyUuid,
                                        String templateUuid,
                                        String templateLabel,
                                        String templateExt,
                                        String userUuid,
                                        String userEmail,
                                        Map<String, String> overrides) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String mu = safeFileToken(matterUuid);
        String tid = safe(templateUuid).trim();
        if (tu.isBlank() || mu.isBlank() || tid.isBlank()) return null;

        ReentrantReadWriteLock lock = lockFor(tu, mu);
        lock.writeLock().lock();
        try {
            ensure(tu, mu);
            List<AssemblyRec> all = readAllLocked(tu, mu);

            String now = Instant.now().toString();
            String preferred = safe(preferredAssemblyUuid).trim();
            String uid = safe(userUuid).trim();
            String eml = safe(userEmail).trim();
            LinkedHashMap<String, String> cleanedOverrides = sanitizeOverrides(overrides);

            int targetIdx = -1;
            AssemblyRec current = null;
            if (!preferred.isBlank()) {
                for (int i = 0; i < all.size(); i++) {
                    AssemblyRec r = all.get(i);
                    if (r == null) continue;
                    if (preferred.equals(safe(r.uuid).trim())) {
                        if ("in_progress".equals(r.status)) {
                            targetIdx = i;
                            current = r;
                        }
                        break;
                    }
                }
            }

            if (current == null) {
                for (int i = 0; i < all.size(); i++) {
                    AssemblyRec r = all.get(i);
                    if (r == null) continue;
                    if (!"in_progress".equals(r.status)) continue;
                    if (!tid.equals(safe(r.templateUuid).trim())) continue;
                    if (!identityMatches(r, uid, eml)) continue;
                    targetIdx = i;
                    current = r;
                    break;
                }
            }

            AssemblyRec outRec;
            if (current == null) {
                outRec = new AssemblyRec(
                        UUID.randomUUID().toString(),
                        mu,
                        tid,
                        safe(templateLabel).trim(),
                        templateExt,
                        "in_progress",
                        now,
                        now,
                        uid,
                        eml,
                        cleanedOverrides.size(),
                        "",
                        "",
                        0L,
                        "local",
                        "",
                        "",
                        cleanedOverrides
                );
                all.add(outRec);
                sortByUpdatedDesc(all);
                writeAllLocked(tu, mu, all);
                return outRec;
            }

            AssemblyRec updated = new AssemblyRec(
                    current.uuid,
                    current.matterUuid,
                    tid,
                    safe(templateLabel).trim().isBlank() ? current.templateLabel : safe(templateLabel).trim(),
                    normalizeExtension(templateExt).isBlank() ? current.templateExt : normalizeExtension(templateExt),
                    "in_progress",
                    safe(current.createdAt).isBlank() ? now : current.createdAt,
                    now,
                    uid.isBlank() ? current.userUuid : uid,
                    eml.isBlank() ? current.userEmail : eml,
                    cleanedOverrides.size(),
                    "",
                    "",
                    0L,
                    normalizeBackendType(current.storageBackendType),
                    safe(current.storageObjectKey),
                    safe(current.storageChecksumSha256),
                    cleanedOverrides
            );

            if (recordsEquivalentForDraft(current, updated)) return current;

            all.set(targetIdx, updated);
            sortByUpdatedDesc(all);
            writeAllLocked(tu, mu, all);
            return updated;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public AssemblyRec markCompleted(String tenantUuid,
                                     String matterUuid,
                                     String preferredAssemblyUuid,
                                     String templateUuid,
                                     String templateLabel,
                                     String templateExt,
                                     String userUuid,
                                     String userEmail,
                                     Map<String, String> overrides,
                                     String outputFileName,
                                     String outputExt,
                                     byte[] outputBytes) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String mu = safeFileToken(matterUuid);
        String tid = safe(templateUuid).trim();
        if (tu.isBlank() || mu.isBlank() || tid.isBlank()) return null;
        if (outputBytes == null || outputBytes.length == 0) return null;

        ReentrantReadWriteLock lock = lockFor(tu, mu);
        lock.writeLock().lock();
        try {
            ensure(tu, mu);
            List<AssemblyRec> all = readAllLocked(tu, mu);

            String now = Instant.now().toString();
            String preferred = safe(preferredAssemblyUuid).trim();
            String uid = safe(userUuid).trim();
            String eml = safe(userEmail).trim();
            LinkedHashMap<String, String> cleanedOverrides = sanitizeOverrides(overrides);
            String normalizedOutExt = normalizeExtension(outputExt);
            if (normalizedOutExt.isBlank()) normalizedOutExt = normalizeExtension(templateExt);
            if (normalizedOutExt.isBlank()) normalizedOutExt = "txt";

            int targetIdx = -1;
            AssemblyRec current = null;
            if (!preferred.isBlank()) {
                for (int i = 0; i < all.size(); i++) {
                    AssemblyRec r = all.get(i);
                    if (r == null) continue;
                    if (preferred.equals(safe(r.uuid).trim())) {
                        if ("in_progress".equals(r.status)) {
                            targetIdx = i;
                            current = r;
                        }
                        break;
                    }
                }
            }

            if (current == null) {
                for (int i = 0; i < all.size(); i++) {
                    AssemblyRec r = all.get(i);
                    if (r == null) continue;
                    if (!"in_progress".equals(r.status)) continue;
                    if (!tid.equals(safe(r.templateUuid).trim())) continue;
                    if (!identityMatches(r, uid, eml)) continue;
                    targetIdx = i;
                    current = r;
                    break;
                }
            }

            String assemblyUuid = current == null ? UUID.randomUUID().toString() : safe(current.uuid).trim();
            if (assemblyUuid.isBlank()) assemblyUuid = UUID.randomUUID().toString();

            String configuredBackendType = normalizeBackendType(backendResolver.resolveBackendType(tu));
            String objectKey = storageKey(mu, assemblyUuid, normalizedOutExt);
            DocumentStorageBackend localBackend = backendResolver.resolve(tu, mu, "local");
            objectKey = localBackend.put(objectKey, outputBytes);
            Map<String, String> metadata = localBackend.metadata(objectKey);
            String checksum = safe(metadata.get("checksum_sha256"));

            AssemblyRec completed = new AssemblyRec(
                    assemblyUuid,
                    mu,
                    tid,
                    safe(templateLabel).trim().isBlank() ? (current == null ? "" : current.templateLabel) : safe(templateLabel).trim(),
                    normalizeExtension(templateExt).isBlank() ? (current == null ? "" : current.templateExt) : normalizeExtension(templateExt),
                    "completed",
                    current == null || safe(current.createdAt).isBlank() ? now : current.createdAt,
                    now,
                    uid.isBlank() ? (current == null ? "" : current.userUuid) : uid,
                    eml.isBlank() ? (current == null ? "" : current.userEmail) : eml,
                    cleanedOverrides.size(),
                    sanitizeOutputName(outputFileName, normalizedOutExt),
                    normalizedOutExt,
                    outputBytes.length,
                    "local",
                    objectKey,
                    checksum,
                    cleanedOverrides
            );

            if (targetIdx >= 0 && targetIdx < all.size()) {
                all.set(targetIdx, completed);
            } else {
                all.add(completed);
            }

            sortByUpdatedDesc(all);
            writeAllLocked(tu, mu, all);
            if (!"local".equals(configuredBackendType)) {
                enqueueSyncJob(tu, mu, assemblyUuid, configuredBackendType, objectKey);
            } else {
                markSyncState(tu, mu, assemblyUuid, "synced", 0, "", "", "");
            }
            return completed;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public byte[] readOutputBytes(String tenantUuid, String matterUuid, String assemblyUuid) {
        String tu = safeFileToken(tenantUuid);
        String mu = safeFileToken(matterUuid);
        String au = safe(assemblyUuid).trim();
        if (tu.isBlank() || mu.isBlank() || au.isBlank()) return new byte[0];

        ReentrantReadWriteLock lock = lockFor(tu, mu);
        lock.readLock().lock();
        try {
            AssemblyRec rec = getByUuid(tu, mu, au);
            if (rec == null) return new byte[0];
            String ext = normalizeExtension(rec.outputFileExt);
            if (ext.isBlank()) ext = normalizeExtension(rec.templateExt);
            if (ext.isBlank()) ext = "txt";

            String backendType = normalizeBackendType(rec.storageBackendType);
            String objectKey = safe(rec.storageObjectKey).trim();
            if (objectKey.isBlank()) objectKey = storageKey(mu, au, ext);

            DocumentStorageBackend backend = backendResolver.resolve(tu, mu, backendType);
            if (backend.exists(objectKey)) return backend.get(objectKey);

            Path fallbackPath = outputPath(tu, mu, au, ext);
            if (!Files.exists(fallbackPath)) return new byte[0];
            return Files.readAllBytes(fallbackPath);
        } catch (Exception ignored) {
            return new byte[0];
        } finally {
            lock.readLock().unlock();
        }
    }


    public boolean rebindStorageMetadata(String tenantUuid,
                                         String matterUuid,
                                         String assemblyUuid,
                                         String storageBackendType,
                                         String storageObjectKey,
                                         String storageChecksumSha256) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String mu = safeFileToken(matterUuid);
        String au = safe(assemblyUuid).trim();
        if (tu.isBlank() || mu.isBlank() || au.isBlank()) return false;

        ReentrantReadWriteLock lock = lockFor(tu, mu);
        lock.writeLock().lock();
        try {
            List<AssemblyRec> all = readAllLocked(tu, mu);
            for (int i = 0; i < all.size(); i++) {
                AssemblyRec current = all.get(i);
                if (current == null) continue;
                if (!au.equals(safe(current.uuid).trim())) continue;

                AssemblyRec updated = new AssemblyRec(
                        current.uuid,
                        current.matterUuid,
                        current.templateUuid,
                        current.templateLabel,
                        current.templateExt,
                        current.status,
                        current.createdAt,
                        Instant.now().toString(),
                        current.userUuid,
                        current.userEmail,
                        current.overrideCount,
                        current.outputFileName,
                        current.outputFileExt,
                        current.outputSizeBytes,
                        storageBackendType,
                        storageObjectKey,
                        storageChecksumSha256,
                        current.overrides
                );
                all.set(i, updated);
                sortByUpdatedDesc(all);
                writeAllLocked(tu, mu, all);
                return true;
            }
            return false;
        } finally {
            lock.writeLock().unlock();
        }
    }


    public String syncState(String tenantUuid, String matterUuid, String assemblyUuid) {
        String tu = safeFileToken(tenantUuid);
        String mu = safeFileToken(matterUuid);
        String au = safe(assemblyUuid).trim();
        if (tu.isBlank() || mu.isBlank() || au.isBlank()) return "synced";
        try {
            String backendType = normalizeBackendType(backendResolver.resolveBackendType(tu));
            if ("local".equals(backendType)) return "synced";
            SyncRec rec = getSyncRecord(tu, mu, au);
            if (rec == null) return "synced";
            if ("dead_letter".equals(rec.state)) return "failed";
            if ("synced".equals(rec.state)) return "synced";
            return "pending";
        } catch (Exception ignored) {
            return "pending";
        }
    }

    public boolean retrySyncNow(String tenantUuid, String matterUuid, String assemblyUuid) {
        String tu = safeFileToken(tenantUuid);
        String mu = safeFileToken(matterUuid);
        String au = safe(assemblyUuid).trim();
        if (tu.isBlank() || mu.isBlank() || au.isBlank()) return false;
        ReentrantReadWriteLock lock = queueLockFor(tu);
        lock.writeLock().lock();
        try {
            List<SyncRec> all = readSyncQueueLocked(tu);
            for (int i = 0; i < all.size(); i++) {
                SyncRec rec = all.get(i);
                if (rec == null) continue;
                if (!mu.equals(rec.matterUuid) || !au.equals(rec.assemblyUuid)) continue;
                SyncRec updated = new SyncRec(tu, mu, au, rec.targetBackendType, rec.targetObjectKey, "pending", 0, "", Instant.now().toString(), "", Instant.now().toString());
                all.set(i, updated);
                writeSyncQueueLocked(tu, all);
                return true;
            }
            return false;
        } catch (Exception ignored) {
            return false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private static ReentrantReadWriteLock lockFor(String tenantUuid, String matterUuid) {
        String key = safeFileToken(tenantUuid) + "::" + safeFileToken(matterUuid);
        return LOCKS.computeIfAbsent(key, k -> new ReentrantReadWriteLock());
    }

    private static Path caseDir(String tenantUuid, String matterUuid) {
        return Paths.get("data", "tenants", tenantUuid, "matters", matterUuid, "assembled").toAbsolutePath();
    }

    private static Path indexPath(String tenantUuid, String matterUuid) {
        return caseDir(tenantUuid, matterUuid).resolve("assemblies.xml");
    }

    private static Path outputsDir(String tenantUuid, String matterUuid) {
        return caseDir(tenantUuid, matterUuid).resolve("files");
    }

    private static Path outputPath(String tenantUuid, String matterUuid, String assemblyUuid, String ext) {
        String id = safeFileToken(assemblyUuid);
        String cleanExt = normalizeExtension(ext);
        if (cleanExt.isBlank()) cleanExt = "txt";
        return outputsDir(tenantUuid, matterUuid).resolve(id + "." + cleanExt);
    }

    private static List<AssemblyRec> readAllLocked(String tenantUuid, String matterUuid) throws Exception {
        ArrayList<AssemblyRec> out = new ArrayList<AssemblyRec>();
        Path p = indexPath(tenantUuid, matterUuid);
        if (!Files.exists(p)) return out;

        Document d = parseXml(p);
        Element root = d == null ? null : d.getDocumentElement();
        if (root == null) return out;

        NodeList nl = root.getElementsByTagName("assembly");
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (!(n instanceof Element)) continue;
            Element e = (Element) n;

            String uuid = text(e, "uuid");
            if (safe(uuid).trim().isBlank()) continue;

            String mu = text(e, "matter_uuid");
            String tid = text(e, "template_uuid");
            String tLabel = text(e, "template_label");
            String tExt = text(e, "template_ext");
            String status = normalizeStatus(text(e, "status"));
            String createdAt = text(e, "created_at");
            String updatedAt = text(e, "updated_at");
            String userUuid = text(e, "user_uuid");
            String userEmail = text(e, "user_email");
            int overrideCount = parseInt(text(e, "override_count"), 0);
            String outName = text(e, "output_file_name");
            String outExt = text(e, "output_file_ext");
            long outSize = parseLong(text(e, "output_size_bytes"), 0L);
            LinkedHashMap<String, String> overrides = parseOverrides(e);

            if (overrideCount <= 0) overrideCount = overrides.size();

            AssemblyRec rec = new AssemblyRec(
                    uuid.trim(),
                    safe(mu).trim(),
                    safe(tid).trim(),
                    safe(tLabel).trim(),
                    safe(tExt).trim(),
                    status,
                    createdAt,
                    updatedAt,
                    userUuid,
                    userEmail,
                    overrideCount,
                    outName,
                    outExt,
                    outSize,
                    text(e, "storage_backend"),
                    text(e, "storage_object_key"),
                    text(e, "storage_checksum_sha256"),
                    overrides
            );
            out.add(rec);
        }

        sortByUpdatedDesc(out);
        return out;
    }

    private static LinkedHashMap<String, String> parseOverrides(Element assemblyEl) {
        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
        if (assemblyEl == null) return out;

        NodeList nl = assemblyEl.getElementsByTagName("overrides");
        if (nl == null || nl.getLength() == 0) return out;
        Node n = nl.item(0);
        if (!(n instanceof Element)) return out;
        Element overridesEl = (Element) n;

        NodeList entries = overridesEl.getElementsByTagName("entry");
        for (int i = 0; i < entries.getLength(); i++) {
            Node en = entries.item(i);
            if (!(en instanceof Element)) continue;
            Element ee = (Element) en;
            String k = safe(ee.getAttribute("key")).trim();
            if (k.isBlank()) continue;
            String v = safe(ee.getTextContent());
            out.put(k, v);
        }
        return out;
    }

    private static void writeAllLocked(String tenantUuid, String matterUuid, List<AssemblyRec> rows) throws Exception {
        Path p = indexPath(tenantUuid, matterUuid);
        Files.createDirectories(p.getParent());

        String now = Instant.now().toString();
        StringBuilder sb = new StringBuilder(8192);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<assembled_forms updated=\"").append(xmlAttr(now)).append("\">\n");

        List<AssemblyRec> all = rows == null ? List.of() : rows;
        for (int i = 0; i < all.size(); i++) {
            AssemblyRec r = all.get(i);
            if (r == null) continue;

            sb.append("  <assembly>\n");
            sb.append("    <uuid>").append(xmlText(r.uuid)).append("</uuid>\n");
            sb.append("    <matter_uuid>").append(xmlText(r.matterUuid)).append("</matter_uuid>\n");
            sb.append("    <template_uuid>").append(xmlText(r.templateUuid)).append("</template_uuid>\n");
            sb.append("    <template_label>").append(xmlText(r.templateLabel)).append("</template_label>\n");
            sb.append("    <template_ext>").append(xmlText(r.templateExt)).append("</template_ext>\n");
            sb.append("    <status>").append(xmlText(normalizeStatus(r.status))).append("</status>\n");
            sb.append("    <created_at>").append(xmlText(safe(r.createdAt).isBlank() ? now : r.createdAt)).append("</created_at>\n");
            sb.append("    <updated_at>").append(xmlText(safe(r.updatedAt).isBlank() ? now : r.updatedAt)).append("</updated_at>\n");
            sb.append("    <user_uuid>").append(xmlText(r.userUuid)).append("</user_uuid>\n");
            sb.append("    <user_email>").append(xmlText(r.userEmail)).append("</user_email>\n");
            sb.append("    <override_count>").append(r.overrideCount).append("</override_count>\n");
            sb.append("    <output_file_name>").append(xmlText(r.outputFileName)).append("</output_file_name>\n");
            sb.append("    <output_file_ext>").append(xmlText(r.outputFileExt)).append("</output_file_ext>\n");
            sb.append("    <output_size_bytes>").append(r.outputSizeBytes).append("</output_size_bytes>\n");
            sb.append("    <storage_backend>").append(xmlText(normalizeBackendType(r.storageBackendType))).append("</storage_backend>\n");
            sb.append("    <storage_object_key>").append(xmlText(r.storageObjectKey)).append("</storage_object_key>\n");
            sb.append("    <storage_checksum_sha256>").append(xmlText(r.storageChecksumSha256)).append("</storage_checksum_sha256>\n");
            sb.append("    <overrides>\n");
            for (Map.Entry<String, String> e : r.overrides.entrySet()) {
                if (e == null) continue;
                String k = safe(e.getKey()).trim();
                if (k.isBlank()) continue;
                sb.append("      <entry key=\"").append(xmlAttr(k)).append("\">")
                        .append(xmlText(safe(e.getValue())))
                        .append("</entry>\n");
            }
            sb.append("    </overrides>\n");
            sb.append("  </assembly>\n");
        }

        sb.append("</assembled_forms>\n");
        writeAtomic(p, sb.toString());
    }

    private static void sortByUpdatedDesc(List<AssemblyRec> rows) {
        if (rows == null) return;
        rows.sort(new Comparator<AssemblyRec>() {
            public int compare(AssemblyRec a, AssemblyRec b) {
                String au = safe(a == null ? "" : a.updatedAt);
                String bu = safe(b == null ? "" : b.updatedAt);
                int byDate = bu.compareTo(au);
                if (byDate != 0) return byDate;
                String as = safe(a == null ? "" : a.status);
                String bs = safe(b == null ? "" : b.status);
                if (!as.equals(bs)) {
                    if ("in_progress".equals(as)) return -1;
                    if ("in_progress".equals(bs)) return 1;
                }
                return safe(a == null ? "" : a.templateLabel).compareToIgnoreCase(safe(b == null ? "" : b.templateLabel));
            }
        });
    }

    private static boolean identityMatches(AssemblyRec r, String userUuid, String userEmail) {
        if (r == null) return false;
        String recUuid = safe(r.userUuid).trim();
        String recEmail = safe(r.userEmail).trim();
        String uid = safe(userUuid).trim();
        String eml = safe(userEmail).trim();

        if (!uid.isBlank() && !recUuid.isBlank()) return uid.equals(recUuid);
        if (!eml.isBlank() && !recEmail.isBlank()) return eml.equalsIgnoreCase(recEmail);
        return uid.isBlank() && eml.isBlank() && recUuid.isBlank() && recEmail.isBlank();
    }

    private static boolean recordsEquivalentForDraft(AssemblyRec a, AssemblyRec b) {
        if (a == null || b == null) return false;
        if (!safe(a.uuid).equals(safe(b.uuid))) return false;
        if (!safe(a.templateUuid).equals(safe(b.templateUuid))) return false;
        if (!safe(a.templateLabel).equals(safe(b.templateLabel))) return false;
        if (!safe(a.templateExt).equals(safe(b.templateExt))) return false;
        if (!safe(a.userUuid).equals(safe(b.userUuid))) return false;
        if (!safe(a.userEmail).equalsIgnoreCase(safe(b.userEmail))) return false;
        if (a.overrideCount != b.overrideCount) return false;
        if (!normalizeBackendType(a.storageBackendType).equals(normalizeBackendType(b.storageBackendType))) return false;
        if (!safe(a.storageObjectKey).equals(safe(b.storageObjectKey))) return false;
        if (!safe(a.storageChecksumSha256).equalsIgnoreCase(safe(b.storageChecksumSha256))) return false;
        if (a.overrides.size() != b.overrides.size()) return false;

        for (Map.Entry<String, String> e : a.overrides.entrySet()) {
            if (e == null) continue;
            String k = safe(e.getKey());
            if (!b.overrides.containsKey(k)) return false;
            if (!safe(e.getValue()).equals(safe(b.overrides.get(k)))) return false;
        }
        return true;
    }

    private static LinkedHashMap<String, String> sanitizeOverrides(Map<String, String> in) {
        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
        if (in == null || in.isEmpty()) return out;
        for (Map.Entry<String, String> e : in.entrySet()) {
            if (e == null) continue;
            String k = safe(e.getKey()).trim();
            if (k.isBlank()) continue;
            out.put(k, safe(e.getValue()));
        }
        return out;
    }

    private static String sanitizeOutputName(String name, String ext) {
        String in = safe(name).trim();
        if (in.isBlank()) in = "assembled-form";
        in = in.replace("\\", "/");
        int slash = in.lastIndexOf('/');
        if (slash >= 0) in = in.substring(slash + 1);
        in = in.replaceAll("[^A-Za-z0-9._ -]", "_").trim();
        if (in.isBlank()) in = "assembled-form";
        if (in.length() > 180) in = in.substring(0, 180);

        String cleanExt = normalizeExtension(ext);
        if (cleanExt.isBlank()) return in;

        String currentExt = normalizeExtension(in);
        if (!cleanExt.equals(currentExt)) return in + "." + cleanExt;
        return in;
    }

    private static String normalizeBackendType(String backendType) {
        String v = safe(backendType).trim().toLowerCase(Locale.ROOT);
        if (v.isBlank()) return "local";
        if ("local".equals(v) || "ftp".equals(v) || "ftps".equals(v) || "sftp".equals(v) || "s3_compatible".equals(v)) return v;
        return "local";
    }

    private static String storageKey(String matterUuid, String assemblyUuid, String ext) {
        String mu = safeFileToken(matterUuid);
        String au = safeFileToken(assemblyUuid);
        String outExt = normalizeExtension(ext);
        if (outExt.isBlank()) outExt = "txt";
        return "matters/" + mu + "/assembled/files/" + au + "." + outExt;
    }

    private static String normalizeStatus(String s) {
        String v = safe(s).trim().toLowerCase(Locale.ROOT);
        if ("completed".equals(v)) return "completed";
        return "in_progress";
    }


    private static String normalizeSyncState(String s) {
        String v = safe(s).trim().toLowerCase(Locale.ROOT);
        if ("synced".equals(v)) return "synced";
        if ("dead_letter".equals(v)) return "dead_letter";
        if ("retry".equals(v)) return "retry";
        return "pending";
    }

    private void enqueueSyncJob(String tenantUuid, String matterUuid, String assemblyUuid, String targetBackendType, String targetObjectKey) {
        String tu = safeFileToken(tenantUuid);
        if (tu.isBlank()) return;
        ReentrantReadWriteLock lock = queueLockFor(tu);
        lock.writeLock().lock();
        try {
            List<SyncRec> all = readSyncQueueLocked(tu);
            String now = Instant.now().toString();
            boolean replaced = false;
            for (int i = 0; i < all.size(); i++) {
                SyncRec rec = all.get(i);
                if (rec == null) continue;
                if (!safeFileToken(matterUuid).equals(rec.matterUuid) || !safe(assemblyUuid).trim().equals(rec.assemblyUuid)) continue;
                all.set(i, new SyncRec(tu, matterUuid, assemblyUuid, targetBackendType, targetObjectKey, "pending", 0, "", now, "", now));
                replaced = true;
                break;
            }
            if (!replaced) all.add(new SyncRec(tu, matterUuid, assemblyUuid, targetBackendType, targetObjectKey, "pending", 0, "", now, "", now));
            writeSyncQueueLocked(tu, all);
        } catch (Exception ex) {
            logSyncFailure(tu, matterUuid, assemblyUuid, 0, ex.getMessage());
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void markSyncState(String tenantUuid, String matterUuid, String assemblyUuid, String state, int attempts, String lastAttemptAt, String nextAttemptAt, String lastError) {
        String tu = safeFileToken(tenantUuid);
        ReentrantReadWriteLock lock = queueLockFor(tu);
        lock.writeLock().lock();
        try {
            List<SyncRec> all = readSyncQueueLocked(tu);
            String mu = safeFileToken(matterUuid);
            String au = safe(assemblyUuid).trim();
            String now = Instant.now().toString();
            boolean found = false;
            for (int i = 0; i < all.size(); i++) {
                SyncRec rec = all.get(i);
                if (rec == null) continue;
                if (!mu.equals(rec.matterUuid) || !au.equals(rec.assemblyUuid)) continue;
                all.set(i, new SyncRec(tu, mu, au, rec.targetBackendType, rec.targetObjectKey, state, attempts, lastAttemptAt, nextAttemptAt, lastError, now));
                found = true;
                break;
            }
            if (!found) all.add(new SyncRec(tu, mu, au, "local", "", state, attempts, lastAttemptAt, nextAttemptAt, lastError, now));
            writeSyncQueueLocked(tu, all);
        } catch (Exception ignored) {
        } finally {
            lock.writeLock().unlock();
        }
    }

    private SyncRec getSyncRecord(String tenantUuid, String matterUuid, String assemblyUuid) {
        String tu = safeFileToken(tenantUuid);
        ReentrantReadWriteLock lock = queueLockFor(tu);
        lock.readLock().lock();
        try {
            List<SyncRec> all = readSyncQueueLocked(tu);
            String mu = safeFileToken(matterUuid);
            String au = safe(assemblyUuid).trim();
            for (int i = 0; i < all.size(); i++) {
                SyncRec rec = all.get(i);
                if (rec == null) continue;
                if (mu.equals(rec.matterUuid) && au.equals(rec.assemblyUuid)) return rec;
            }
        } catch (Exception ignored) {
        } finally {
            lock.readLock().unlock();
        }
        return null;
    }

    private static ReentrantReadWriteLock queueLockFor(String tenantUuid) {
        String key = safeFileToken(tenantUuid);
        return QUEUE_LOCKS.computeIfAbsent(key, k -> new ReentrantReadWriteLock());
    }

    private static Path syncQueuePath(String tenantUuid) {
        return Paths.get("data", "tenants", safeFileToken(tenantUuid), "sync", "storage_queue.xml").toAbsolutePath();
    }

    private static List<SyncRec> readSyncQueueLocked(String tenantUuid) throws Exception {
        ArrayList<SyncRec> out = new ArrayList<SyncRec>();
        Path p = syncQueuePath(tenantUuid);
        if (!Files.exists(p)) return out;
        Document d = parseXml(p);
        Element root = d == null ? null : d.getDocumentElement();
        if (root == null) return out;
        NodeList nl = root.getElementsByTagName("job");
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (!(n instanceof Element)) continue;
            Element e = (Element) n;
            out.add(new SyncRec(
                    tenantUuid,
                    text(e, "matter_uuid"),
                    text(e, "assembly_uuid"),
                    text(e, "target_backend"),
                    text(e, "target_object_key"),
                    text(e, "state"),
                    parseInt(text(e, "attempts"), 0),
                    text(e, "last_attempt_at"),
                    text(e, "next_attempt_at"),
                    text(e, "last_error"),
                    text(e, "updated_at")
            ));
        }
        return out;
    }

    private static void writeSyncQueueLocked(String tenantUuid, List<SyncRec> rows) throws Exception {
        Path p = syncQueuePath(tenantUuid);
        Files.createDirectories(p.getParent());
        String now = Instant.now().toString();
        StringBuilder sb = new StringBuilder(2048);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<storage_sync_queue updated=\"").append(xmlAttr(now)).append("\">\n");
        for (int i = 0; i < (rows == null ? 0 : rows.size()); i++) {
            SyncRec r = rows.get(i);
            if (r == null) continue;
            sb.append("  <job>\n");
            sb.append("    <matter_uuid>").append(xmlText(r.matterUuid)).append("</matter_uuid>\n");
            sb.append("    <assembly_uuid>").append(xmlText(r.assemblyUuid)).append("</assembly_uuid>\n");
            sb.append("    <target_backend>").append(xmlText(r.targetBackendType)).append("</target_backend>\n");
            sb.append("    <target_object_key>").append(xmlText(r.targetObjectKey)).append("</target_object_key>\n");
            sb.append("    <state>").append(xmlText(normalizeSyncState(r.state))).append("</state>\n");
            sb.append("    <attempts>").append(r.attempts).append("</attempts>\n");
            sb.append("    <last_attempt_at>").append(xmlText(r.lastAttemptAt)).append("</last_attempt_at>\n");
            sb.append("    <next_attempt_at>").append(xmlText(r.nextAttemptAt)).append("</next_attempt_at>\n");
            sb.append("    <last_error>").append(xmlText(r.lastError)).append("</last_error>\n");
            sb.append("    <updated_at>").append(xmlText(r.updatedAt)).append("</updated_at>\n");
            sb.append("  </job>\n");
        }
        sb.append("</storage_sync_queue>\n");
        writeAtomic(p, sb.toString());
    }

    private void startWorkerIfNeeded() {
        if (!WORKER_STARTED.compareAndSet(false, true)) return;
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    processSyncQueues();
                } catch (Exception ignored) {
                }
                try {
                    TimeUnit.SECONDS.sleep(5);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "assembled-storage-sync-worker");
        t.setDaemon(true);
        t.start();
    }

    private void processSyncQueues() {
        Path tenantsRoot = Paths.get("data", "tenants").toAbsolutePath();
        if (!Files.isDirectory(tenantsRoot)) return;
        try (var stream = Files.list(tenantsRoot)) {
            stream.filter(Files::isDirectory).forEach(tenantPath -> {
                String tenantUuid = safe(tenantPath.getFileName() == null ? "" : tenantPath.getFileName().toString()).trim();
                if (tenantUuid.isBlank()) return;
                processTenantSyncQueue(tenantUuid);
            });
        } catch (Exception ignored) {
        }
    }

    private void processTenantSyncQueue(String tenantUuid) {
        ReentrantReadWriteLock lock = queueLockFor(tenantUuid);
        lock.writeLock().lock();
        try {
            List<SyncRec> all = readSyncQueueLocked(tenantUuid);
            boolean changed = false;
            String now = Instant.now().toString();
            for (int i = 0; i < all.size(); i++) {
                SyncRec rec = all.get(i);
                if (rec == null) continue;
                if ("synced".equals(rec.state) || "dead_letter".equals(rec.state)) continue;
                if (!safe(rec.nextAttemptAt).isBlank() && safe(rec.nextAttemptAt).compareTo(now) > 0) continue;

                int attempts = rec.attempts + 1;
                try {
                    byte[] bytes = readOutputBytes(tenantUuid, rec.matterUuid, rec.assemblyUuid);
                    if (bytes.length == 0) throw new IllegalStateException("local output missing");
                    DocumentStorageBackend backend = backendResolver.resolve(tenantUuid, rec.matterUuid, rec.targetBackendType);
                    String remoteKey = backend.put(rec.targetObjectKey, bytes);
                    Map<String, String> md = backend.metadata(remoteKey);
                    rebindStorageMetadata(tenantUuid, rec.matterUuid, rec.assemblyUuid, rec.targetBackendType, remoteKey, md.getOrDefault("checksum_sha256", ""));
                    all.set(i, new SyncRec(tenantUuid, rec.matterUuid, rec.assemblyUuid, rec.targetBackendType, remoteKey, "synced", attempts, now, now, "", now));
                    changed = true;
                } catch (Exception ex) {
                    String err = safe(ex.getMessage());
                    if (attempts >= SYNC_MAX_ATTEMPTS) {
                        all.set(i, new SyncRec(tenantUuid, rec.matterUuid, rec.assemblyUuid, rec.targetBackendType, rec.targetObjectKey, "dead_letter", attempts, now, "", err, now));
                    } else {
                        long delay = Math.min(SYNC_MAX_BACKOFF_MS, (long) (SYNC_BASE_BACKOFF_MS * Math.pow(2.0d, Math.max(0, attempts - 1))));
                        long jitter = ThreadLocalRandom.current().nextLong(250L, 1200L);
                        String nextAt = Instant.now().plusMillis(delay + jitter).toString();
                        all.set(i, new SyncRec(tenantUuid, rec.matterUuid, rec.assemblyUuid, rec.targetBackendType, rec.targetObjectKey, "retry", attempts, now, nextAt, err, now));
                    }
                    changed = true;
                    logSyncFailure(tenantUuid, rec.matterUuid, rec.assemblyUuid, attempts, err);
                }
            }
            if (changed) writeSyncQueueLocked(tenantUuid, all);
        } catch (Exception ignored) {
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void logSyncFailure(String tenantUuid, String matterUuid, String assemblyUuid, int attempts, String error) {
        try {
            LinkedHashMap<String, String> details = new LinkedHashMap<String, String>();
            details.put("matter_uuid", safe(matterUuid));
            details.put("assembly_uuid", safe(assemblyUuid));
            details.put("attempt", String.valueOf(attempts));
            details.put("error", safe(error));
            activity_log.defaultStore().logVerbose("assembled_forms.sync.failure", tenantUuid, "system", matterUuid, assemblyUuid, details);
        } catch (Exception ignored) {
        }
    }

    private static String normalizeExtension(String extOrFileName) {
        String v = safe(extOrFileName).trim().toLowerCase(Locale.ROOT);
        if (v.isBlank()) return "";

        int slash = Math.max(v.lastIndexOf('/'), v.lastIndexOf('\\'));
        if (slash >= 0 && slash + 1 < v.length()) v = v.substring(slash + 1);
        int dot = v.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < v.length()) v = v.substring(dot + 1);
        return v.replaceAll("[^a-z0-9]", "");
    }

    private static Document parseXml(Path p) throws Exception {
        if (p == null || !Files.exists(p)) return null;
        DocumentBuilder b = secureBuilder();
        try (InputStream in = Files.newInputStream(p)) {
            Document d = b.parse(in);
            d.getDocumentElement().normalize();
            return d;
        }
    }

    private static DocumentBuilder secureBuilder() throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false);
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);

        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

        DocumentBuilder b = f.newDocumentBuilder();
        b.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
        return b;
    }

    private static void writeAtomic(Path p, String content) throws Exception {
        Files.createDirectories(p.getParent());
        Path tmp = p.resolveSibling(p.getFileName().toString() + ".tmp." + UUID.randomUUID());
        Files.writeString(
                tmp,
                safe(content),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        );
        try {
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception ex) {
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String text(Element parent, String childTag) {
        if (parent == null || childTag == null) return "";
        NodeList nl = parent.getElementsByTagName(childTag);
        if (nl == null || nl.getLength() == 0) return "";
        Node n = nl.item(0);
        return n == null ? "" : safe(n.getTextContent()).trim();
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(safe(s).trim());
        } catch (Exception ignored) {
            return def;
        }
    }

    private static long parseLong(String s, long def) {
        try {
            return Long.parseLong(safe(s).trim());
        } catch (Exception ignored) {
            return def;
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String safeFileToken(String s) {
        String t = safe(s).trim();
        if (t.isBlank()) return "";
        return t.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String xmlAttr(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("'", "&apos;");
    }

    private static String xmlText(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private static String emptyXml() {
        String now = Instant.now().toString();
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<assembled_forms created=\"" + xmlAttr(now) + "\" updated=\"" + xmlAttr(now) + "\"></assembled_forms>\n";
    }
}
