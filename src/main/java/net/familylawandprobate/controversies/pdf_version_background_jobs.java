package net.familylawandprobate.controversies;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Background OCR/flatten jobs for part PDF versions.
 */
public final class pdf_version_background_jobs {

    private static final Logger LOG = Logger.getLogger(pdf_version_background_jobs.class.getName());
    private static final long PDFSANDWICH_AVAILABILITY_CACHE_MS = 30_000L;

    private static final class Holder {
        private static final pdf_version_background_jobs INSTANCE = new pdf_version_background_jobs();
    }

    private final ExecutorService worker;
    private final Object availabilityLock = new Object();
    private volatile long pdfsandwichCheckedAtMs = 0L;
    private volatile boolean pdfsandwichAvailable = false;

    private pdf_version_background_jobs() {
        this.worker = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "pdf-version-transform-worker");
                t.setDaemon(true);
                return t;
            }
        });
    }

    public static pdf_version_background_jobs defaultService() {
        return Holder.INSTANCE;
    }

    public boolean isPdfSandwichAvailable() {
        long now = System.currentTimeMillis();
        if (now - pdfsandwichCheckedAtMs <= PDFSANDWICH_AVAILABILITY_CACHE_MS) {
            return pdfsandwichAvailable;
        }
        synchronized (availabilityLock) {
            now = System.currentTimeMillis();
            if (now - pdfsandwichCheckedAtMs <= PDFSANDWICH_AVAILABILITY_CACHE_MS) {
                return pdfsandwichAvailable;
            }
            boolean available = probePdfSandwich();
            pdfsandwichAvailable = available;
            pdfsandwichCheckedAtMs = now;
            return available;
        }
    }

    public String enqueueOcr(String tenantUuid,
                             String matterUuid,
                             String docUuid,
                             String partUuid,
                             String sourceVersionUuid,
                             String requestedBy) throws Exception {
        if (!isPdfSandwichAvailable()) {
            throw new IllegalArgumentException("pdfsandwich is not available on this server.");
        }

        final JobInput input = prepareJobInput(
                tenantUuid,
                matterUuid,
                docUuid,
                partUuid,
                sourceVersionUuid,
                requestedBy,
                "ocr"
        );

        final String jobId = UUID.randomUUID().toString();
        worker.submit(() -> runOcrJob(jobId, input));
        return jobId;
    }

    public String enqueueFlatten(String tenantUuid,
                                 String matterUuid,
                                 String docUuid,
                                 String partUuid,
                                 String sourceVersionUuid,
                                 String requestedBy) throws Exception {
        final JobInput input = prepareJobInput(
                tenantUuid,
                matterUuid,
                docUuid,
                partUuid,
                sourceVersionUuid,
                requestedBy,
                "flat"
        );

        final String jobId = UUID.randomUUID().toString();
        worker.submit(() -> runFlattenJob(jobId, input));
        return jobId;
    }

    private static final class JobInput {
        final String tenantUuid;
        final String matterUuid;
        final String docUuid;
        final String partUuid;
        final String sourceVersionUuid;
        final String sourceVersionLabel;
        final Path sourcePath;
        final Path outputPath;
        final String outputVersionLabel;
        final String outputSource;
        final String outputNotes;
        final String createdBy;

        JobInput(String tenantUuid,
                 String matterUuid,
                 String docUuid,
                 String partUuid,
                 String sourceVersionUuid,
                 String sourceVersionLabel,
                 Path sourcePath,
                 Path outputPath,
                 String outputVersionLabel,
                 String outputSource,
                 String outputNotes,
                 String createdBy) {
            this.tenantUuid = safe(tenantUuid).trim();
            this.matterUuid = safe(matterUuid).trim();
            this.docUuid = safe(docUuid).trim();
            this.partUuid = safe(partUuid).trim();
            this.sourceVersionUuid = safe(sourceVersionUuid).trim();
            this.sourceVersionLabel = safe(sourceVersionLabel).trim();
            this.sourcePath = sourcePath;
            this.outputPath = outputPath;
            this.outputVersionLabel = safe(outputVersionLabel).trim();
            this.outputSource = safe(outputSource).trim();
            this.outputNotes = safe(outputNotes).trim();
            this.createdBy = safe(createdBy).trim();
        }
    }

    private JobInput prepareJobInput(String tenantUuid,
                                     String matterUuid,
                                     String docUuid,
                                     String partUuid,
                                     String sourceVersionUuid,
                                     String requestedBy,
                                     String mode) throws Exception {
        String tu = safe(tenantUuid).trim();
        String mu = safe(matterUuid).trim();
        String du = safe(docUuid).trim();
        String pu = safe(partUuid).trim();
        String vu = safe(sourceVersionUuid).trim();
        if (tu.isBlank() || mu.isBlank() || du.isBlank() || pu.isBlank()) {
            throw new IllegalArgumentException("Missing matter/document/part identifiers.");
        }
        if (vu.isBlank()) throw new IllegalArgumentException("Source version is required.");

        List<part_versions.VersionRec> rows = part_versions.defaultStore().listAll(tu, mu, du, pu);
        part_versions.VersionRec source = findVersion(rows, vu);
        if (source == null) throw new IllegalArgumentException("Source version not found.");
        if (!pdf_redaction_service.isPdfVersion(source)) {
            throw new IllegalArgumentException("Source version is not a PDF.");
        }

        Path sourcePath = pdf_redaction_service.resolveStoragePath(source.storagePath);
        pdf_redaction_service.requirePathWithinTenant(sourcePath, tu, "Source PDF path");
        if (sourcePath == null || !Files.isRegularFile(sourcePath)) {
            throw new IllegalArgumentException("Source PDF file not found.");
        }

        Path partFolder = document_parts.defaultStore().partFolder(tu, mu, du, pu);
        if (partFolder == null) throw new IllegalArgumentException("Part folder unavailable.");
        pdf_redaction_service.requirePathWithinTenant(partFolder, tu, "Part folder path");

        Path outputDir = partFolder.resolve("version_files");
        Files.createDirectories(outputDir);
        pdf_redaction_service.requirePathWithinTenant(outputDir, tu, "Output folder path");

        String sourceFileName = sourcePath.getFileName() == null ? "document.pdf" : sourcePath.getFileName().toString();
        String suffix = "ocr".equals(mode) ? "_sandwiched" : "_flattened";
        String outputFileName = suggestOutputFileName(sourceFileName, suffix);
        Path outputPath = outputDir.resolve(UUID.randomUUID().toString() + "__" + outputFileName).toAbsolutePath().normalize();
        pdf_redaction_service.requirePathWithinTenant(outputPath, tu, "Output PDF path");

        String sourceLabel = safe(source.versionLabel).trim();
        if (sourceLabel.isBlank()) sourceLabel = "PDF";
        String versionLabel = "ocr".equals(mode)
                ? sourceLabel + " (Sandwiched OCR)"
                : sourceLabel + " (Flattened)";
        String notes = "Derived from source version " + vu + " by background " + ("ocr".equals(mode) ? "OCR" : "FLAT") + " job.";
        String createdBy = safe(requestedBy).trim();

        return new JobInput(
                tu,
                mu,
                du,
                pu,
                vu,
                sourceLabel,
                sourcePath,
                outputPath,
                versionLabel,
                "ocr".equals(mode) ? "ocr_sandwiched" : "flattened",
                notes,
                createdBy
        );
    }

    private void runOcrJob(String jobId, JobInput input) {
        if (input == null) return;
        try {
            runPdfSandwich(input.sourcePath, input.outputPath);
            pdf_redaction_service.preservePageAnnotations(input.sourcePath, input.outputPath);
            finalizeVersion(input);
            LOG.info("PDF OCR job complete: jobId=" + jobId + ", sourceVersion=" + input.sourceVersionUuid + ", output=" + input.outputPath);
        } catch (Exception ex) {
            safeDelete(input.outputPath);
            LOG.log(Level.WARNING,
                    "PDF OCR job failed: jobId=" + jobId + ", sourceVersion=" + input.sourceVersionUuid + ", error=" + safe(ex.getMessage()),
                    ex);
        }
    }

    private void runFlattenJob(String jobId, JobInput input) {
        if (input == null) return;
        try {
            pdf_redaction_service.flattenToImagePdf(input.sourcePath, input.outputPath);
            finalizeVersion(input);
            LOG.info("PDF flatten job complete: jobId=" + jobId + ", sourceVersion=" + input.sourceVersionUuid + ", output=" + input.outputPath);
        } catch (Exception ex) {
            safeDelete(input.outputPath);
            LOG.log(Level.WARNING,
                    "PDF flatten job failed: jobId=" + jobId + ", sourceVersion=" + input.sourceVersionUuid + ", error=" + safe(ex.getMessage()),
                    ex);
        }
    }

    private void finalizeVersion(JobInput input) throws Exception {
        if (input == null) throw new IllegalArgumentException("Job input missing.");
        pdf_redaction_service.requirePathWithinTenant(input.outputPath, input.tenantUuid, "Output PDF path");
        if (!Files.exists(input.outputPath) || !Files.isRegularFile(input.outputPath)) {
            throw new IllegalStateException("Transformed output file not found.");
        }
        long bytes = Files.size(input.outputPath);
        if (bytes <= 0L) throw new IllegalStateException("Transformed output file is empty.");
        String checksum = pdf_redaction_service.sha256(input.outputPath);

        part_versions.defaultStore().create(
                input.tenantUuid,
                input.matterUuid,
                input.docUuid,
                input.partUuid,
                input.outputVersionLabel,
                input.outputSource,
                "application/pdf",
                checksum,
                String.valueOf(bytes),
                input.outputPath.toUri().toString(),
                input.createdBy,
                input.outputNotes,
                true
        );
    }

    private static part_versions.VersionRec findVersion(List<part_versions.VersionRec> rows, String versionUuid) {
        if (rows == null || rows.isEmpty()) return null;
        String target = safe(versionUuid).trim();
        if (target.isBlank()) return null;
        for (part_versions.VersionRec row : rows) {
            if (row == null) continue;
            if (target.equals(safe(row.uuid).trim())) return row;
        }
        return null;
    }

    private static String suggestOutputFileName(String sourceFileName, String suffix) {
        String name = safe(sourceFileName).trim().replaceAll("[^A-Za-z0-9._-]", "_");
        if (name.isBlank()) name = "document.pdf";
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf") && name.length() > 4) {
            String base = name.substring(0, name.length() - 4);
            return base + safe(suffix) + ".pdf";
        }
        return name + safe(suffix) + ".pdf";
    }

    private boolean probePdfSandwich() {
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("pdfsandwich", "--version");
            pb.redirectErrorStream(true);
            process = pb.start();
            try (var in = process.getInputStream()) {
                in.transferTo(OutputStream.nullOutputStream());
            }
            int exit = process.waitFor();
            return exit == 0;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (process != null) process.destroyForcibly();
        }
    }

    private static void runPdfSandwich(Path inputPdf, Path outputPdf) throws Exception {
        if (inputPdf == null || outputPdf == null) throw new IllegalArgumentException("Input/output PDF path required.");
        ProcessBuilder pb = new ProcessBuilder(
                "pdfsandwich",
                inputPdf.toAbsolutePath().normalize().toString(),
                outputPdf.toAbsolutePath().normalize().toString()
        );
        pb.redirectErrorStream(true);
        Process process = pb.start();
        try (var in = process.getInputStream()) {
            in.transferTo(OutputStream.nullOutputStream());
        }
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IllegalStateException("pdfsandwich exited with code " + exit + ".");
        }
        if (!Files.exists(outputPdf) || Files.size(outputPdf) <= 0L) {
            throw new IllegalStateException("pdfsandwich did not produce an output PDF.");
        }
    }

    private static void safeDelete(Path p) {
        if (p == null) return;
        try {
            Files.deleteIfExists(p);
        } catch (Exception ignored) {
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
