package net.familylawandprobate.controversies;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.UUID;

/**
 * Handles chunked upload staging and commit for document part versions.
 */
public final class version_upload_service {

    public static final int MAX_UPLOAD_CHUNKS = 12000;

    private static final document_parts PARTS = document_parts.defaultStore();
    private static final part_versions VERSIONS = part_versions.defaultStore();
    private static final activity_log LOGS = activity_log.defaultStore();

    public static version_upload_service defaultService() {
        return new version_upload_service();
    }

    public static final class ChunkResult {
        public int chunkIndex;
        public int totalChunks;
        public int bytesWritten;
        public String chunkSha256;
    }

    public static final class CommitRequest {
        public String uploadId;
        public int totalChunks;
        public String expectedFileSha256;
        public long expectedBytes;
        public String uploadFileName;
        public String versionLabel;
        public String source;
        public String mimeType;
        public String createdBy;
        public String notes;
        public boolean makeCurrent;
    }

    public static final class CommitResult {
        public part_versions.VersionRec version;
        public String checksum;
        public long assembledBytes;
        public Path storagePath;
    }

    public ChunkResult storeChunk(String tenantUuid,
                                  String caseUuid,
                                  String docUuid,
                                  String partUuid,
                                  String uploadId,
                                  int chunkIndex,
                                  int totalChunks,
                                  String expectedChunkSha256,
                                  byte[] chunkBytes) throws Exception {
        documents.defaultStore().requireEditable(tenantUuid, caseUuid, docUuid);
        Path partFolder = requirePartFolder(tenantUuid, caseUuid, docUuid, partUuid);
        Path uploadDir = uploadDir(partFolder, sanitizeUploadId(uploadId));
        if (chunkIndex < 0 || totalChunks <= 0 || totalChunks > MAX_UPLOAD_CHUNKS || chunkIndex >= totalChunks) {
            throw new IllegalArgumentException("Invalid chunk index.");
        }
        if (chunkBytes == null || chunkBytes.length == 0) {
            throw new IllegalArgumentException("Chunk payload missing.");
        }
        String actualSha = sha256(chunkBytes);
        String expectedSha = safe(expectedChunkSha256).trim().toLowerCase(Locale.ROOT);
        if (!expectedSha.isBlank() && !expectedSha.equals(actualSha)) {
            LinkedHashMap<String, String> details = new LinkedHashMap<String, String>();
            details.put("part_uuid", safe(partUuid).trim());
            details.put("upload_id", safe(uploadId).trim());
            details.put("chunk_index", String.valueOf(chunkIndex));
            details.put("total_chunks", String.valueOf(totalChunks));
            LOGS.logWarning(
                    "document.version_upload.chunk_integrity_failed",
                    safe(tenantUuid).trim(),
                    "",
                    safe(caseUuid).trim(),
                    safe(docUuid).trim(),
                    details
            );
            throw new IllegalArgumentException("Chunk integrity check failed at chunk " + chunkIndex + ".");
        }

        Files.createDirectories(uploadDir);
        Path chunkPath = uploadDir.resolve("chunk-" + chunkIndex + ".bin");
        Path backupPath = uploadDir.resolve("chunk-" + chunkIndex + ".bak");
        Files.write(chunkPath, chunkBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        Files.write(backupPath, chunkBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);

        ChunkResult out = new ChunkResult();
        out.chunkIndex = chunkIndex;
        out.totalChunks = totalChunks;
        out.bytesWritten = chunkBytes.length;
        out.chunkSha256 = actualSha;
        return out;
    }

    public CommitResult commitUpload(String tenantUuid,
                                     String caseUuid,
                                     String docUuid,
                                     String partUuid,
                                     CommitRequest req) throws Exception {
        if (req == null) throw new IllegalArgumentException("Missing commit request.");
        documents.defaultStore().requireEditable(tenantUuid, caseUuid, docUuid);
        Path partFolder = requirePartFolder(tenantUuid, caseUuid, docUuid, partUuid);

        String uploadId = sanitizeUploadId(req.uploadId);
        int totalChunks = req.totalChunks;
        if (totalChunks <= 0 || totalChunks > MAX_UPLOAD_CHUNKS) {
            throw new IllegalArgumentException("Invalid upload metadata.");
        }

        Path uploadDir = uploadDir(partFolder, uploadId);
        if (!Files.exists(uploadDir)) throw new IllegalArgumentException("Upload session not found.");

        Path outputDir = partFolder.resolve("version_files");
        Files.createDirectories(outputDir);
        String outputName = UUID.randomUUID().toString().replace("-", "_") + "__" + safeFileName(req.uploadFileName);
        Path outputPath = outputDir.resolve(outputName);

        long assembledSize = 0L;
        try (OutputStream os = Files.newOutputStream(outputPath, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            for (int i = 0; i < totalChunks; i++) {
                Path chunkPath = uploadDir.resolve("chunk-" + i + ".bin");
                if (!Files.exists(chunkPath)) {
                    Path backupPath = uploadDir.resolve("chunk-" + i + ".bak");
                    if (Files.exists(backupPath)) {
                        Files.copy(backupPath, chunkPath);
                    } else {
                        throw new IllegalArgumentException("Missing chunk " + i + ".");
                    }
                }
                byte[] b = Files.readAllBytes(chunkPath);
                os.write(b);
                assembledSize += b.length;
            }
        }

        String finalSha = sha256(outputPath);
        String expectedSha = safe(req.expectedFileSha256).trim().toLowerCase(Locale.ROOT);
        if (!expectedSha.isBlank() && !expectedSha.equals(finalSha)) {
            Files.deleteIfExists(outputPath);
            LinkedHashMap<String, String> details = new LinkedHashMap<String, String>();
            details.put("part_uuid", safe(partUuid).trim());
            details.put("upload_id", uploadId);
            details.put("total_chunks", String.valueOf(totalChunks));
            LOGS.logWarning(
                    "document.version_upload.file_integrity_failed",
                    safe(tenantUuid).trim(),
                    safe(req.createdBy).trim(),
                    safe(caseUuid).trim(),
                    safe(docUuid).trim(),
                    details
            );
            throw new IllegalArgumentException("File integrity check failed after assembly.");
        }
        if (req.expectedBytes > 0L && req.expectedBytes != assembledSize) {
            Files.deleteIfExists(outputPath);
            LinkedHashMap<String, String> details = new LinkedHashMap<String, String>();
            details.put("part_uuid", safe(partUuid).trim());
            details.put("upload_id", uploadId);
            details.put("expected_bytes", String.valueOf(req.expectedBytes));
            details.put("assembled_bytes", String.valueOf(assembledSize));
            LOGS.logWarning(
                    "document.version_upload.file_size_mismatch",
                    safe(tenantUuid).trim(),
                    safe(req.createdBy).trim(),
                    safe(caseUuid).trim(),
                    safe(docUuid).trim(),
                    details
            );
            throw new IllegalArgumentException("File size check failed after assembly.");
        }

        deleteRecursively(uploadDir);
        part_versions.VersionRec created = VERSIONS.create(
                safe(tenantUuid).trim(),
                safe(caseUuid).trim(),
                safe(docUuid).trim(),
                safe(partUuid).trim(),
                safe(req.versionLabel),
                safe(req.source),
                safe(req.mimeType),
                finalSha,
                String.valueOf(assembledSize),
                outputPath.toUri().toString(),
                safe(req.createdBy),
                safe(req.notes),
                req.makeCurrent
        );

        CommitResult out = new CommitResult();
        out.version = created;
        out.checksum = finalSha;
        out.assembledBytes = assembledSize;
        out.storagePath = outputPath;
        LinkedHashMap<String, String> details = new LinkedHashMap<String, String>();
        details.put("part_uuid", safe(partUuid).trim());
        details.put("upload_id", uploadId);
        details.put("total_chunks", String.valueOf(totalChunks));
        details.put("assembled_bytes", String.valueOf(assembledSize));
        details.put("checksum", safe(finalSha));
        details.put("version_uuid", safe(created == null ? "" : created.uuid).trim());
        details.put("version_label", safe(created == null ? req.versionLabel : created.versionLabel));
        details.put("mime_type", safe(created == null ? req.mimeType : created.mimeType));
        LOGS.logVerbose(
                "document.version_upload.committed",
                safe(tenantUuid).trim(),
                safe(req.createdBy).trim(),
                safe(caseUuid).trim(),
                safe(docUuid).trim(),
                details
        );
        return out;
    }

    private static Path requirePartFolder(String tenantUuid, String caseUuid, String docUuid, String partUuid) {
        Path partFolder = PARTS.partFolder(
                safe(tenantUuid).trim(),
                safe(caseUuid).trim(),
                safe(docUuid).trim(),
                safe(partUuid).trim()
        );
        if (partFolder == null) throw new IllegalArgumentException("Part folder unavailable.");
        return partFolder;
    }

    private static Path uploadDir(Path partFolder, String uploadId) throws Exception {
        Path tempRoot = partFolder.resolve(".upload_staging");
        Files.createDirectories(tempRoot);
        return tempRoot.resolve(uploadId);
    }

    private static String sanitizeUploadId(String raw) {
        String clean = safe(raw).replaceAll("[^A-Za-z0-9_-]", "");
        if (clean.isBlank()) throw new IllegalArgumentException("Missing upload id.");
        return clean;
    }

    private static String safeFileName(String s) {
        String v = safe(s).trim().replaceAll("[^A-Za-z0-9.]", "_");
        if (v.isBlank()) return "version.bin";
        if (v.length() > 140) return v.substring(v.length() - 140);
        return v;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String hex(byte[] raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder(raw.length * 2);
        for (byte b : raw) sb.append(String.format(Locale.ROOT, "%02x", b));
        return sb.toString();
    }

    private static String sha256(byte[] raw) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return hex(md.digest(raw == null ? new byte[0] : raw));
    }

    private static String sha256(Path p) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(p, StandardOpenOption.READ)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        return hex(md.digest());
    }

    private static void deleteRecursively(Path root) {
        try {
            if (root == null || !Files.exists(root)) return;
            Files.walk(root)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (Exception ignored) {
                        }
                    });
        } catch (Exception ignored) {
        }
    }
}
