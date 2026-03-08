package net.familylawandprobate.controversies;

import com.sun.mail.smtp.SMTPTransport;
import jakarta.activation.DataHandler;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.internet.MimeUtility;
import jakarta.mail.util.ByteArrayDataSource;
import net.familylawandprobate.controversies.storage.DocumentStorageBackend;
import net.familylawandprobate.controversies.storage.StorageBackendResolver;
import net.familylawandprobate.controversies.storage.StorageCrypto;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Stream;

/**
 * notification_emails
 *
 * Tenant-scoped notification email queue:
 *   data/tenants/{tenantUuid}/sync/notification_email_queue.xml
 * Attachment snapshots for queued messages:
 *   data/tenants/{tenantUuid}/sync/email_queue_attachments/{emailUuid}/...
 */
public final class notification_emails {

    private static final ConcurrentHashMap<String, ReentrantReadWriteLock> QUEUE_LOCKS = new ConcurrentHashMap<String, ReentrantReadWriteLock>();
    private static final ConcurrentHashMap<String, Long> LAST_POLL_AT_MS = new ConcurrentHashMap<String, Long>();
    private static final AtomicBoolean WORKER_STARTED = new AtomicBoolean(false);
    private static final int MAX_STORED_BODY_CHARS = 250_000;
    private static final int MAX_SERVER_RESPONSE_CHARS = 8_000;

    private final tenant_settings settingsStore;
    private final StorageBackendResolver storageBackendResolver;
    private final activity_log logs;
    private final Sender smtpSender;
    private final Sender graphSender;

    @FunctionalInterface
    interface Sender {
        SendResult send(EmailJob job, LinkedHashMap<String, String> cfg, List<QueuedAttachment> attachments) throws Exception;
    }

    public static final class NotificationEmailRequest {
        public final String matterUuid;
        public final String fromAddress;
        public final String fromName;
        public final String replyTo;
        public final String subject;
        public final String bodyText;
        public final String bodyHtml;
        public final List<String> to;
        public final List<String> cc;
        public final List<String> bcc;
        public final List<AttachmentSpec> attachments;

        public NotificationEmailRequest(String matterUuid,
                                        String fromAddress,
                                        String fromName,
                                        String replyTo,
                                        String subject,
                                        String bodyText,
                                        String bodyHtml,
                                        List<String> to,
                                        List<String> cc,
                                        List<String> bcc,
                                        List<AttachmentSpec> attachments) {
            this.matterUuid = safeFileToken(matterUuid);
            this.fromAddress = safe(fromAddress).trim();
            this.fromName = safe(fromName).trim();
            this.replyTo = safe(replyTo).trim();
            this.subject = safe(subject).trim();
            this.bodyText = safe(bodyText);
            this.bodyHtml = safe(bodyHtml);
            this.to = normalizeAddressList(to);
            this.cc = normalizeAddressList(cc);
            this.bcc = normalizeAddressList(bcc);
            this.attachments = attachments == null ? List.of() : attachments;
        }
    }

    public static final class AttachmentSpec {
        public final String sourceType; // local_file | external_storage
        public final String localPath;
        public final String storageBackend;
        public final String storageObjectKey;
        public final String storageMatterUuid;
        public final String fileName;
        public final String contentType;

        public AttachmentSpec(String sourceType,
                              String localPath,
                              String storageBackend,
                              String storageObjectKey,
                              String storageMatterUuid,
                              String fileName,
                              String contentType) {
            this.sourceType = safe(sourceType).trim();
            this.localPath = safe(localPath).trim();
            this.storageBackend = safe(storageBackend).trim();
            this.storageObjectKey = safe(storageObjectKey).trim();
            this.storageMatterUuid = safeFileToken(storageMatterUuid);
            this.fileName = safe(fileName).trim();
            this.contentType = safe(contentType).trim();
        }

        public static AttachmentSpec localFile(String localPath, String fileName, String contentType) {
            return new AttachmentSpec("local_file", localPath, "", "", "", fileName, contentType);
        }

        public static AttachmentSpec externalStorage(String backendType,
                                                     String objectKey,
                                                     String matterUuid,
                                                     String fileName,
                                                     String contentType) {
            return new AttachmentSpec("external_storage", "", backendType, objectKey, matterUuid, fileName, contentType);
        }
    }

    public static final class ValidationResult {
        public final boolean ok;
        public final String provider;
        public final List<String> issues;

        public ValidationResult(boolean ok, String provider, List<String> issues) {
            this.ok = ok;
            this.provider = safe(provider);
            this.issues = issues == null ? List.of() : issues;
        }
    }

    static final class QueuedAttachment {
        public final String fileName;
        public final String contentType;
        public final String storedPath;
        public final String sourceType;
        public final String sourceRef;
        public final long sizeBytes;
        public final String checksumSha256;

        QueuedAttachment(String fileName,
                         String contentType,
                         String storedPath,
                         String sourceType,
                         String sourceRef,
                         long sizeBytes,
                         String checksumSha256) {
            this.fileName = safe(fileName).trim();
            this.contentType = safe(contentType).trim();
            this.storedPath = safe(storedPath).trim();
            this.sourceType = safe(sourceType).trim();
            this.sourceRef = safe(sourceRef).trim();
            this.sizeBytes = Math.max(0L, sizeBytes);
            this.checksumSha256 = safe(checksumSha256).trim().toLowerCase(Locale.ROOT);
        }
    }

    static final class EmailJob {
        public final String uuid;
        public final String tenantUuid;
        public final String matterUuid;
        public final String requestedByUserUuid;
        public final String provider;
        public final String state; // pending | retry | sent | dead_letter
        public final int attempts;
        public final int maxAttempts;
        public final String createdAt;
        public final String updatedAt;
        public final String lastAttemptAt;
        public final String nextAttemptAt;
        public final String sentAt;
        public final String fromAddress;
        public final String fromName;
        public final String replyTo;
        public final String subject;
        public final String bodyText;
        public final String bodyHtml;
        public final List<String> to;
        public final List<String> cc;
        public final List<String> bcc;
        public final List<QueuedAttachment> attachments;
        public final String result;
        public final int providerStatusCode;
        public final String providerMessageId;
        public final String serverResponse;
        public final String lastError;

        EmailJob(String uuid,
                 String tenantUuid,
                 String matterUuid,
                 String requestedByUserUuid,
                 String provider,
                 String state,
                 int attempts,
                 int maxAttempts,
                 String createdAt,
                 String updatedAt,
                 String lastAttemptAt,
                 String nextAttemptAt,
                 String sentAt,
                 String fromAddress,
                 String fromName,
                 String replyTo,
                 String subject,
                 String bodyText,
                 String bodyHtml,
                 List<String> to,
                 List<String> cc,
                 List<String> bcc,
                 List<QueuedAttachment> attachments,
                 String result,
                 int providerStatusCode,
                 String providerMessageId,
                 String serverResponse,
                 String lastError) {
            this.uuid = safeFileToken(uuid).isBlank() ? UUID.randomUUID().toString() : safeFileToken(uuid);
            this.tenantUuid = safeFileToken(tenantUuid);
            this.matterUuid = safeFileToken(matterUuid);
            this.requestedByUserUuid = safe(requestedByUserUuid).trim();
            this.provider = normalizeProvider(provider);
            this.state = normalizeState(state);
            this.attempts = Math.max(0, attempts);
            this.maxAttempts = Math.max(1, maxAttempts);
            this.createdAt = safe(createdAt).isBlank() ? Instant.now().toString() : safe(createdAt).trim();
            this.updatedAt = safe(updatedAt).isBlank() ? this.createdAt : safe(updatedAt).trim();
            this.lastAttemptAt = safe(lastAttemptAt).trim();
            this.nextAttemptAt = safe(nextAttemptAt).trim();
            this.sentAt = safe(sentAt).trim();
            this.fromAddress = safe(fromAddress).trim();
            this.fromName = safe(fromName).trim();
            this.replyTo = safe(replyTo).trim();
            this.subject = truncate(safe(subject), 998);
            this.bodyText = truncate(safe(bodyText), MAX_STORED_BODY_CHARS);
            this.bodyHtml = truncate(safe(bodyHtml), MAX_STORED_BODY_CHARS);
            this.to = normalizeAddressList(to);
            this.cc = normalizeAddressList(cc);
            this.bcc = normalizeAddressList(bcc);
            this.attachments = attachments == null ? List.of() : attachments;
            this.result = safe(result).trim().toLowerCase(Locale.ROOT);
            this.providerStatusCode = Math.max(0, providerStatusCode);
            this.providerMessageId = safe(providerMessageId).trim();
            this.serverResponse = truncate(safe(serverResponse), MAX_SERVER_RESPONSE_CHARS);
            this.lastError = truncate(safe(lastError), MAX_SERVER_RESPONSE_CHARS);
        }
    }

    static final class SendResult {
        public final int statusCode;
        public final String providerMessageId;
        public final String serverResponse;

        SendResult(int statusCode, String providerMessageId, String serverResponse) {
            this.statusCode = Math.max(0, statusCode);
            this.providerMessageId = safe(providerMessageId).trim();
            this.serverResponse = truncate(safe(serverResponse), MAX_SERVER_RESPONSE_CHARS);
        }
    }

    private static final class DeliveryException extends Exception {
        public final int statusCode;
        public final String serverResponse;

        DeliveryException(String message, int statusCode, String serverResponse) {
            super(safe(message));
            this.statusCode = Math.max(0, statusCode);
            this.serverResponse = truncate(safe(serverResponse), MAX_SERVER_RESPONSE_CHARS);
        }
    }

    private static final class Holder {
        private static final notification_emails INSTANCE = new notification_emails();
    }

    public static notification_emails defaultStore() {
        return Holder.INSTANCE;
    }

    public notification_emails() {
        this(tenant_settings.defaultStore(), new StorageBackendResolver(), activity_log.defaultStore(), null, null);
    }

    notification_emails(tenant_settings settingsStore,
                        StorageBackendResolver storageBackendResolver,
                        activity_log logs,
                        Sender smtpSender,
                        Sender graphSender) {
        this(settingsStore, storageBackendResolver, logs, smtpSender, graphSender, true);
    }

    notification_emails(tenant_settings settingsStore,
                        StorageBackendResolver storageBackendResolver,
                        activity_log logs,
                        Sender smtpSender,
                        Sender graphSender,
                        boolean autoStartWorker) {
        this.settingsStore = settingsStore == null ? tenant_settings.defaultStore() : settingsStore;
        this.storageBackendResolver = storageBackendResolver == null ? new StorageBackendResolver() : storageBackendResolver;
        this.logs = logs == null ? activity_log.defaultStore() : logs;
        this.smtpSender = smtpSender == null ? this::sendViaSmtp : smtpSender;
        this.graphSender = graphSender == null ? this::sendViaMicrosoftGraph : graphSender;
        if (autoStartWorker) startWorkerIfNeeded();
    }

    public ValidationResult validateConfiguration(Map<String, String> cfg) {
        LinkedHashMap<String, String> safeCfg = cfg == null ? new LinkedHashMap<String, String>() : new LinkedHashMap<String, String>(cfg);
        String provider = normalizeProvider(safeCfg.get("email_provider"));
        ArrayList<String> issues = new ArrayList<String>();
        if ("disabled".equals(provider)) return new ValidationResult(true, provider, issues);

        if ("smtp".equals(provider)) {
            String host = safe(safeCfg.get("email_smtp_host")).trim();
            int port = parseInt(safeCfg.get("email_smtp_port"), 587);
            boolean auth = truthy(safeCfg.get("email_smtp_auth"));
            String user = safe(safeCfg.get("email_smtp_username")).trim();
            String pass = safe(safeCfg.get("email_smtp_password")).trim();
            String fromAddress = safe(safeCfg.get("email_from_address")).trim();

            if (host.isBlank()) issues.add("SMTP host is required.");
            if (port < 1 || port > 65535) issues.add("SMTP port must be between 1 and 65535.");
            if (auth && user.isBlank()) issues.add("SMTP username is required when SMTP auth is enabled.");
            if (auth && pass.isBlank()) issues.add("SMTP password is required when SMTP auth is enabled.");
            if (fromAddress.isBlank() && user.isBlank()) {
                issues.add("Email from address is required when SMTP username is not set.");
            }
        } else if ("microsoft_graph".equals(provider)) {
            if (safe(safeCfg.get("email_graph_tenant_id")).trim().isBlank()) issues.add("Microsoft Graph tenant ID is required.");
            if (safe(safeCfg.get("email_graph_client_id")).trim().isBlank()) issues.add("Microsoft Graph client ID is required.");
            if (safe(safeCfg.get("email_graph_client_secret")).trim().isBlank()) issues.add("Microsoft Graph client secret is required.");
            if (safe(safeCfg.get("email_graph_sender_user")).trim().isBlank()) issues.add("Microsoft Graph sender user/mailbox is required.");
        } else {
            issues.add("Unsupported email provider: " + provider);
        }
        return new ValidationResult(issues.isEmpty(), provider, issues);
    }

    /**
     * Sends a notification email immediately without queue persistence.
     * Used for security-sensitive real-time flows (e.g., two-factor codes).
     */
    public SendResult sendSimpleMessageNow(String tenantUuid,
                                           String requestedByUserUuid,
                                           NotificationEmailRequest request) throws Exception {
        String tu = safeFileToken(tenantUuid);
        if (tu.isBlank()) throw new IllegalArgumentException("tenantUuid required");
        if (request == null) throw new IllegalArgumentException("request required");
        if (request.to.isEmpty() && request.cc.isEmpty() && request.bcc.isEmpty()) {
            throw new IllegalArgumentException("at least one recipient is required");
        }
        if (request.subject.isBlank()) throw new IllegalArgumentException("subject required");
        if (request.bodyText.isBlank() && request.bodyHtml.isBlank()) {
            throw new IllegalArgumentException("email body is required");
        }

        LinkedHashMap<String, String> cfg = settingsStore.read(tu);
        ValidationResult cfgValidation = validateConfiguration(cfg);
        if (!cfgValidation.ok) {
            throw new IllegalStateException("email provider configuration invalid: " + String.join("; ", cfgValidation.issues));
        }

        String fromAddress = safe(request.fromAddress).trim();
        if (fromAddress.isBlank()) fromAddress = safe(cfg.get("email_from_address")).trim();
        if (fromAddress.isBlank() && "smtp".equals(cfgValidation.provider)) {
            fromAddress = safe(cfg.get("email_smtp_username")).trim();
        }
        String fromName = safe(request.fromName).trim();
        if (fromName.isBlank()) fromName = safe(cfg.get("email_from_name")).trim();
        String replyTo = safe(request.replyTo).trim();
        if (replyTo.isBlank()) replyTo = safe(cfg.get("email_reply_to")).trim();

        String now = Instant.now().toString();
        EmailJob transientJob = new EmailJob(
                UUID.randomUUID().toString(),
                tu,
                request.matterUuid,
                requestedByUserUuid,
                cfgValidation.provider,
                "pending",
                0,
                1,
                now,
                now,
                "",
                now,
                "",
                fromAddress,
                fromName,
                replyTo,
                request.subject,
                request.bodyText,
                request.bodyHtml,
                request.to,
                request.cc,
                request.bcc,
                List.of(),
                "queued",
                0,
                "",
                "",
                ""
        );

        SendResult sent = send(transientJob, cfg);
        EmailJob logged = new EmailJob(
                transientJob.uuid,
                transientJob.tenantUuid,
                transientJob.matterUuid,
                transientJob.requestedByUserUuid,
                transientJob.provider,
                "sent",
                1,
                1,
                transientJob.createdAt,
                Instant.now().toString(),
                Instant.now().toString(),
                "",
                Instant.now().toString(),
                transientJob.fromAddress,
                transientJob.fromName,
                transientJob.replyTo,
                transientJob.subject,
                transientJob.bodyText,
                transientJob.bodyHtml,
                transientJob.to,
                transientJob.cc,
                transientJob.bcc,
                transientJob.attachments,
                "ok",
                sent.statusCode,
                sent.providerMessageId,
                sent.serverResponse,
                ""
        );
        logSendResult(logged, true);
        return sent;
    }

    public void ensureQueue(String tenantUuid) throws Exception {
        String tu = safeFileToken(tenantUuid);
        if (tu.isBlank()) throw new IllegalArgumentException("tenantUuid required");

        ReentrantReadWriteLock lock = queueLockFor(tu);
        lock.writeLock().lock();
        try {
            Path p = queuePath(tu);
            if (!Files.exists(p)) writeQueueLocked(tu, List.of());
        } finally {
            lock.writeLock().unlock();
        }
    }

    public String enqueue(String tenantUuid, String requestedByUserUuid, NotificationEmailRequest request) throws Exception {
        String tu = safeFileToken(tenantUuid);
        if (tu.isBlank()) throw new IllegalArgumentException("tenantUuid required");
        if (request == null) throw new IllegalArgumentException("request required");
        if (request.to.isEmpty() && request.cc.isEmpty() && request.bcc.isEmpty()) {
            throw new IllegalArgumentException("at least one recipient is required");
        }
        if (request.subject.isBlank()) throw new IllegalArgumentException("subject required");
        if (request.bodyText.isBlank() && request.bodyHtml.isBlank()) {
            throw new IllegalArgumentException("email body is required");
        }

        LinkedHashMap<String, String> cfg = settingsStore.read(tu);
        ValidationResult cfgValidation = validateConfiguration(cfg);
        if (!cfgValidation.ok) {
            throw new IllegalStateException("email provider configuration invalid: " + String.join("; ", cfgValidation.issues));
        }

        String now = Instant.now().toString();
        String jobUuid = UUID.randomUUID().toString();
        List<QueuedAttachment> attachments = materializeAttachments(tu, request, jobUuid);
        int maxAttempts = parseInt(cfg.get("email_queue_max_attempts"), 8);
        if (maxAttempts < 1) maxAttempts = 8;
        String fromAddress = safe(request.fromAddress).trim();
        if (fromAddress.isBlank()) fromAddress = safe(cfg.get("email_from_address")).trim();
        if (fromAddress.isBlank() && "smtp".equals(cfgValidation.provider)) {
            fromAddress = safe(cfg.get("email_smtp_username")).trim();
        }
        String fromName = safe(request.fromName).trim();
        if (fromName.isBlank()) fromName = safe(cfg.get("email_from_name")).trim();
        String replyTo = safe(request.replyTo).trim();
        if (replyTo.isBlank()) replyTo = safe(cfg.get("email_reply_to")).trim();

        EmailJob job = new EmailJob(
                jobUuid,
                tu,
                request.matterUuid,
                requestedByUserUuid,
                cfgValidation.provider,
                "pending",
                0,
                maxAttempts,
                now,
                now,
                "",
                now,
                "",
                fromAddress,
                fromName,
                replyTo,
                request.subject,
                request.bodyText,
                request.bodyHtml,
                request.to,
                request.cc,
                request.bcc,
                attachments,
                "queued",
                0,
                "",
                "",
                ""
        );

        ReentrantReadWriteLock lock = queueLockFor(tu);
        lock.writeLock().lock();
        try {
            List<EmailJob> all = readQueueLocked(tu);
            all.add(job);
            writeQueueLocked(tu, all);
        } finally {
            lock.writeLock().unlock();
        }

        LinkedHashMap<String, String> details = new LinkedHashMap<String, String>();
        details.put("email_uuid", job.uuid);
        details.put("provider", job.provider);
        details.put("to_count", String.valueOf(job.to.size()));
        details.put("cc_count", String.valueOf(job.cc.size()));
        details.put("bcc_count", String.valueOf(job.bcc.size()));
        details.put("attachment_count", String.valueOf(job.attachments.size()));
        details.put("subject", job.subject);
        logs.logVerbose("notification_email.enqueued", tu, safe(requestedByUserUuid), request.matterUuid, "", details);
        return job.uuid;
    }

    public boolean retryNow(String tenantUuid, String emailUuid) {
        String tu = safeFileToken(tenantUuid);
        String eu = safeFileToken(emailUuid);
        if (tu.isBlank() || eu.isBlank()) return false;

        ReentrantReadWriteLock lock = queueLockFor(tu);
        lock.writeLock().lock();
        boolean changed = false;
        try {
            List<EmailJob> all = readQueueLocked(tu);
            String now = Instant.now().toString();
            for (int i = 0; i < all.size(); i++) {
                EmailJob j = all.get(i);
                if (j == null || !eu.equals(j.uuid)) continue;
                all.set(i, new EmailJob(
                        j.uuid, j.tenantUuid, j.matterUuid, j.requestedByUserUuid, j.provider, "pending",
                        j.attempts, j.maxAttempts, j.createdAt, now, j.lastAttemptAt, now, j.sentAt,
                        j.fromAddress, j.fromName, j.replyTo, j.subject, j.bodyText, j.bodyHtml,
                        j.to, j.cc, j.bcc, j.attachments, j.result, j.providerStatusCode, j.providerMessageId, j.serverResponse, j.lastError
                ));
                changed = true;
                break;
            }
            if (changed) writeQueueLocked(tu, all);
        } catch (Exception ignored) {
            return false;
        } finally {
            lock.writeLock().unlock();
        }
        if (changed) processTenantQueue(tu, true);
        return changed;
    }

    private void startWorkerIfNeeded() {
        if (!WORKER_STARTED.compareAndSet(false, true)) return;
        Thread worker = new Thread(() -> {
            while (true) {
                try {
                    processQueues();
                } catch (Exception ignored) {
                }
                try {
                    TimeUnit.SECONDS.sleep(2L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "notification-email-worker");
        worker.setDaemon(true);
        worker.start();
    }

    private void processQueues() {
        Path tenantsRoot = Paths.get("data", "tenants").toAbsolutePath();
        if (!Files.isDirectory(tenantsRoot)) return;
        try (Stream<Path> stream = Files.list(tenantsRoot)) {
            stream.filter(Files::isDirectory).forEach(tenantPath -> {
                String tenantUuid = safe(tenantPath.getFileName() == null ? "" : tenantPath.getFileName().toString()).trim();
                if (tenantUuid.isBlank()) return;
                processTenantQueue(tenantUuid);
            });
        } catch (Exception ignored) {
        }
    }

    private void processTenantQueue(String tenantUuid) {
        processTenantQueue(tenantUuid, false);
    }

    private void processTenantQueue(String tenantUuid, boolean force) {
        String tu = safeFileToken(tenantUuid);
        if (tu.isBlank()) return;
        ReentrantReadWriteLock lock = queueLockFor(tu);
        lock.writeLock().lock();
        try {
            List<EmailJob> all = readQueueLocked(tu);
            if (all.isEmpty()) return;

            LinkedHashMap<String, String> cfg = settingsStore.read(tu);
            int pollSeconds = parseInt(cfg.get("email_queue_poll_seconds"), 5);
            if (pollSeconds < 1) pollSeconds = 5;
            long nowEpoch = System.currentTimeMillis();
            if (!force) {
                long intervalMs = Math.max(1000L, pollSeconds * 1000L);
                Long lastPoll = LAST_POLL_AT_MS.get(tu);
                if (lastPoll != null && nowEpoch - lastPoll < intervalMs) return;
            }
            LAST_POLL_AT_MS.put(tu, nowEpoch);

            int batchSize = parseInt(cfg.get("email_queue_batch_size"), 10);
            if (batchSize < 1) batchSize = 10;
            int maxAttemptsFallback = parseInt(cfg.get("email_queue_max_attempts"), 8);
            if (maxAttemptsFallback < 1) maxAttemptsFallback = 8;
            long baseBackoff = parseInt(cfg.get("email_queue_backoff_base_ms"), 2000);
            long maxBackoff = parseInt(cfg.get("email_queue_backoff_max_ms"), 300000);
            if (baseBackoff < 100L) baseBackoff = 100L;
            if (maxBackoff < baseBackoff) maxBackoff = Math.max(baseBackoff, 300000L);

            String now = Instant.now().toString();
            int processed = 0;
            boolean changed = false;
            for (int i = 0; i < all.size(); i++) {
                EmailJob job = all.get(i);
                if (job == null) continue;
                if ("sent".equals(job.state) || "dead_letter".equals(job.state)) continue;
                if (!job.nextAttemptAt.isBlank() && job.nextAttemptAt.compareTo(now) > 0) continue;
                if (processed >= batchSize) break;
                processed++;

                int attempts = job.attempts + 1;
                int maxAttempts = job.maxAttempts > 0 ? job.maxAttempts : maxAttemptsFallback;
                try {
                    SendResult sent = send(job, cfg);
                    String attemptAt = Instant.now().toString();
                    EmailJob updated = new EmailJob(
                            job.uuid, job.tenantUuid, job.matterUuid, job.requestedByUserUuid, job.provider, "sent",
                            attempts, maxAttempts, job.createdAt, attemptAt, attemptAt, "", attemptAt,
                            job.fromAddress, job.fromName, job.replyTo, job.subject, job.bodyText, job.bodyHtml,
                            job.to, job.cc, job.bcc, job.attachments, "ok", sent.statusCode, sent.providerMessageId, sent.serverResponse, ""
                    );
                    all.set(i, updated);
                    changed = true;
                    logSendResult(updated, true);
                } catch (DeliveryException de) {
                    String attemptAt = Instant.now().toString();
                    String err = safe(de.getMessage()).isBlank() ? "email send failed" : safe(de.getMessage());
                    EmailJob updated;
                    if (attempts >= maxAttempts) {
                        updated = new EmailJob(
                                job.uuid, job.tenantUuid, job.matterUuid, job.requestedByUserUuid, job.provider, "dead_letter",
                                attempts, maxAttempts, job.createdAt, attemptAt, attemptAt, "", "",
                                job.fromAddress, job.fromName, job.replyTo, job.subject, job.bodyText, job.bodyHtml,
                                job.to, job.cc, job.bcc, job.attachments, "failed", de.statusCode, job.providerMessageId, de.serverResponse, err
                        );
                    } else {
                        long nextDelay = Math.min(maxBackoff, (long) (baseBackoff * Math.pow(2.0d, Math.max(0, attempts - 1))));
                        String nextAt = Instant.now().plusMillis(nextDelay).toString();
                        updated = new EmailJob(
                                job.uuid, job.tenantUuid, job.matterUuid, job.requestedByUserUuid, job.provider, "retry",
                                attempts, maxAttempts, job.createdAt, attemptAt, attemptAt, nextAt, "",
                                job.fromAddress, job.fromName, job.replyTo, job.subject, job.bodyText, job.bodyHtml,
                                job.to, job.cc, job.bcc, job.attachments, "failed", de.statusCode, job.providerMessageId, de.serverResponse, err
                        );
                    }
                    all.set(i, updated);
                    changed = true;
                    logSendResult(updated, false);
                } catch (Exception ex) {
                    String attemptAt = Instant.now().toString();
                    String err = safe(ex.getMessage()).isBlank() ? ex.getClass().getSimpleName() : safe(ex.getMessage());
                    EmailJob updated;
                    if (attempts >= maxAttempts) {
                        updated = new EmailJob(
                                job.uuid, job.tenantUuid, job.matterUuid, job.requestedByUserUuid, job.provider, "dead_letter",
                                attempts, maxAttempts, job.createdAt, attemptAt, attemptAt, "", "",
                                job.fromAddress, job.fromName, job.replyTo, job.subject, job.bodyText, job.bodyHtml,
                                job.to, job.cc, job.bcc, job.attachments, "failed", 0, job.providerMessageId, "", err
                        );
                    } else {
                        long nextDelay = Math.min(maxBackoff, (long) (baseBackoff * Math.pow(2.0d, Math.max(0, attempts - 1))));
                        String nextAt = Instant.now().plusMillis(nextDelay).toString();
                        updated = new EmailJob(
                                job.uuid, job.tenantUuid, job.matterUuid, job.requestedByUserUuid, job.provider, "retry",
                                attempts, maxAttempts, job.createdAt, attemptAt, attemptAt, nextAt, "",
                                job.fromAddress, job.fromName, job.replyTo, job.subject, job.bodyText, job.bodyHtml,
                                job.to, job.cc, job.bcc, job.attachments, "failed", 0, job.providerMessageId, "", err
                        );
                    }
                    all.set(i, updated);
                    changed = true;
                    logSendResult(updated, false);
                }
            }

            if (changed) writeQueueLocked(tu, all);
        } catch (Exception ignored) {
        } finally {
            lock.writeLock().unlock();
        }
    }

    private SendResult send(EmailJob job, LinkedHashMap<String, String> cfg) throws Exception {
        String provider = normalizeProvider(job.provider);
        if ("disabled".equals(provider)) provider = normalizeProvider(cfg.get("email_provider"));
        if ("smtp".equals(provider)) return smtpSender.send(job, cfg, job.attachments);
        if ("microsoft_graph".equals(provider)) return graphSender.send(job, cfg, job.attachments);
        throw new DeliveryException("unsupported email provider: " + provider, 0, "");
    }

    private SendResult sendViaSmtp(EmailJob job, LinkedHashMap<String, String> cfg, List<QueuedAttachment> attachments) throws Exception {
        String host = safe(cfg.get("email_smtp_host")).trim();
        int port = parseInt(cfg.get("email_smtp_port"), 587);
        boolean auth = truthy(cfg.get("email_smtp_auth"));
        boolean startTls = truthy(cfg.get("email_smtp_starttls"));
        boolean ssl = truthy(cfg.get("email_smtp_ssl"));
        String username = safe(cfg.get("email_smtp_username")).trim();
        String password = safe(cfg.get("email_smtp_password")).trim();
        String heloDomain = safe(cfg.get("email_smtp_helo_domain")).trim();
        int connectTimeout = parseInt(cfg.get("email_connect_timeout_ms"), 15000);
        int readTimeout = parseInt(cfg.get("email_read_timeout_ms"), 20000);

        if (host.isBlank()) throw new DeliveryException("smtp host is required", 0, "");
        if (port < 1 || port > 65535) throw new DeliveryException("smtp port is invalid", 0, "");
        if (auth && username.isBlank()) throw new DeliveryException("smtp username is required", 0, "");
        if (auth && password.isBlank()) throw new DeliveryException("smtp password is required", 0, "");

        String fromAddress = safe(job.fromAddress).trim();
        if (fromAddress.isBlank()) fromAddress = safe(cfg.get("email_from_address")).trim();
        if (fromAddress.isBlank()) fromAddress = username;
        if (fromAddress.isBlank()) throw new DeliveryException("from address is required", 0, "");

        Properties props = new Properties();
        props.setProperty("mail.smtp.host", host);
        props.setProperty("mail.smtp.port", String.valueOf(port));
        props.setProperty("mail.smtp.auth", auth ? "true" : "false");
        props.setProperty("mail.smtp.starttls.enable", startTls ? "true" : "false");
        props.setProperty("mail.smtp.ssl.enable", ssl ? "true" : "false");
        props.setProperty("mail.smtp.connectiontimeout", String.valueOf(Math.max(1000, connectTimeout)));
        props.setProperty("mail.smtp.timeout", String.valueOf(Math.max(1000, readTimeout)));
        props.setProperty("mail.smtp.writetimeout", String.valueOf(Math.max(1000, readTimeout)));
        if (!heloDomain.isBlank()) props.setProperty("mail.smtp.localhost", heloDomain);

        Session session;
        if (auth) {
            session = Session.getInstance(props, new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(username, password);
                }
            });
        } else {
            session = Session.getInstance(props);
        }

        MimeMessage message = new MimeMessage(session);
        String fromName = safe(job.fromName).trim();
        if (fromName.isBlank()) fromName = safe(cfg.get("email_from_name")).trim();
        if (!fromName.isBlank()) {
            message.setFrom(new InternetAddress(fromAddress, fromName, StandardCharsets.UTF_8.name()));
        } else {
            message.setFrom(new InternetAddress(fromAddress));
        }
        if (!safe(job.replyTo).isBlank()) {
            message.setReplyTo(new InternetAddress[] { new InternetAddress(job.replyTo) });
        } else if (!safe(cfg.get("email_reply_to")).trim().isBlank()) {
            message.setReplyTo(new InternetAddress[] { new InternetAddress(safe(cfg.get("email_reply_to")).trim()) });
        }
        addRecipients(message, Message.RecipientType.TO, job.to);
        addRecipients(message, Message.RecipientType.CC, job.cc);
        addRecipients(message, Message.RecipientType.BCC, job.bcc);
        message.setSubject(job.subject, StandardCharsets.UTF_8.name());
        message.setSentDate(java.util.Date.from(Instant.now()));

        MimeMultipart mixed = new MimeMultipart("mixed");
        MimeBodyPart contentPart = new MimeBodyPart();
        if (!job.bodyText.isBlank() && !job.bodyHtml.isBlank()) {
            MimeMultipart alt = new MimeMultipart("alternative");

            MimeBodyPart textPart = new MimeBodyPart();
            textPart.setText(job.bodyText, StandardCharsets.UTF_8.name());
            alt.addBodyPart(textPart);

            MimeBodyPart htmlPart = new MimeBodyPart();
            htmlPart.setContent(job.bodyHtml, "text/html; charset=UTF-8");
            alt.addBodyPart(htmlPart);

            contentPart.setContent(alt);
        } else if (!job.bodyHtml.isBlank()) {
            contentPart.setContent(job.bodyHtml, "text/html; charset=UTF-8");
        } else {
            contentPart.setText(job.bodyText, StandardCharsets.UTF_8.name());
        }
        mixed.addBodyPart(contentPart);

        for (int i = 0; i < (attachments == null ? 0 : attachments.size()); i++) {
            QueuedAttachment a = attachments.get(i);
            if (a == null || a.storedPath.isBlank()) continue;
            Path p = Paths.get(a.storedPath).toAbsolutePath().normalize();
            if (!Files.exists(p)) throw new DeliveryException("attachment missing: " + p, 0, "");
            byte[] bytes = Files.readAllBytes(p);
            MimeBodyPart attachmentPart = new MimeBodyPart();
            ByteArrayDataSource ds = new ByteArrayDataSource(bytes, contentTypeOrDefault(a.contentType));
            attachmentPart.setDataHandler(new DataHandler(ds));
            attachmentPart.setFileName(MimeUtility.encodeText(nonBlank(a.fileName, p.getFileName() == null ? "attachment.bin" : p.getFileName().toString()), StandardCharsets.UTF_8.name(), null));
            mixed.addBodyPart(attachmentPart);
        }
        message.setContent(mixed);
        message.saveChanges();

        Transport tr = session.getTransport("smtp");
        try {
            if (auth) tr.connect(host, port, username, password);
            else tr.connect();
            tr.sendMessage(message, message.getAllRecipients());
            SMTPTransport smtp = tr instanceof SMTPTransport ? (SMTPTransport) tr : null;
            int statusCode = smtp == null ? 250 : Math.max(0, smtp.getLastReturnCode());
            String response = smtp == null ? "smtp send accepted" : safe(smtp.getLastServerResponse());
            return new SendResult(statusCode, safe(message.getMessageID()), response);
        } catch (Exception ex) {
            String detail = safe(ex.getMessage());
            throw new DeliveryException("smtp delivery failed: " + detail, 0, detail);
        } finally {
            try { tr.close(); } catch (Exception ignored) {}
        }
    }

    private SendResult sendViaMicrosoftGraph(EmailJob job, LinkedHashMap<String, String> cfg, List<QueuedAttachment> attachments) throws Exception {
        String tenantId = safe(cfg.get("email_graph_tenant_id")).trim();
        String clientId = safe(cfg.get("email_graph_client_id")).trim();
        String clientSecret = safe(cfg.get("email_graph_client_secret")).trim();
        String senderUser = safe(cfg.get("email_graph_sender_user")).trim();
        String scope = safe(cfg.get("email_graph_scope")).trim();
        if (scope.isBlank()) scope = "https://graph.microsoft.com/.default";
        int connectTimeout = parseInt(cfg.get("email_connect_timeout_ms"), 15000);
        int readTimeout = parseInt(cfg.get("email_read_timeout_ms"), 20000);

        if (tenantId.isBlank() || clientId.isBlank() || clientSecret.isBlank() || senderUser.isBlank()) {
            throw new DeliveryException("microsoft graph credentials are incomplete", 0, "");
        }

        String tokenEndpoint = "https://login.microsoftonline.com/" + urlEncodePathSegment(tenantId) + "/oauth2/v2.0/token";
        LinkedHashMap<String, String> tokenForm = new LinkedHashMap<String, String>();
        tokenForm.put("grant_type", "client_credentials");
        tokenForm.put("client_id", clientId);
        tokenForm.put("client_secret", clientSecret);
        tokenForm.put("scope", scope);
        HttpResponse tokenResponse = postForm(tokenEndpoint, tokenForm, connectTimeout, readTimeout, Map.of());
        if (tokenResponse.statusCode < 200 || tokenResponse.statusCode >= 300) {
            throw new DeliveryException("graph token request failed", tokenResponse.statusCode, tokenResponse.body);
        }
        String accessToken = jsonStringValue(tokenResponse.body, "access_token");
        if (accessToken.isBlank()) throw new DeliveryException("graph token response missing access_token", tokenResponse.statusCode, tokenResponse.body);

        String endpoint = "https://graph.microsoft.com/v1.0/users/" + urlEncodePathSegment(senderUser) + "/sendMail";
        String payload = buildGraphSendPayload(job, attachments);
        HttpResponse sendResponse = postJson(
                endpoint,
                payload,
                connectTimeout,
                readTimeout,
                Map.of("Authorization", "Bearer " + accessToken)
        );
        if (sendResponse.statusCode < 200 || sendResponse.statusCode >= 300) {
            throw new DeliveryException("graph sendMail failed", sendResponse.statusCode, sendResponse.body);
        }
        String reqId = safe(sendResponse.headers.getOrDefault("request-id", ""));
        String statusLine = "status=" + sendResponse.statusCode + (reqId.isBlank() ? "" : " request-id=" + reqId);
        if (!sendResponse.body.isBlank()) statusLine = statusLine + " body=" + truncate(sendResponse.body, MAX_SERVER_RESPONSE_CHARS);
        return new SendResult(sendResponse.statusCode, "", statusLine);
    }

    private void addRecipients(MimeMessage message, Message.RecipientType type, List<String> values) throws Exception {
        List<String> xs = values == null ? List.of() : values;
        for (int i = 0; i < xs.size(); i++) {
            String address = safe(xs.get(i)).trim();
            if (address.isBlank()) continue;
            message.addRecipient(type, new InternetAddress(address));
        }
    }

    private String buildGraphSendPayload(EmailJob job, List<QueuedAttachment> attachments) throws Exception {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("{\"message\":{");
        sb.append("\"subject\":\"").append(jsonEscape(job.subject)).append("\",");
        if (!job.bodyHtml.isBlank()) {
            sb.append("\"body\":{\"contentType\":\"HTML\",\"content\":\"").append(jsonEscape(job.bodyHtml)).append("\"},");
        } else {
            sb.append("\"body\":{\"contentType\":\"Text\",\"content\":\"").append(jsonEscape(job.bodyText)).append("\"},");
        }

        appendGraphRecipients(sb, "toRecipients", job.to);
        sb.append(",");
        appendGraphRecipients(sb, "ccRecipients", job.cc);
        sb.append(",");
        appendGraphRecipients(sb, "bccRecipients", job.bcc);

        String fromAddress = safe(job.fromAddress).trim();
        if (!fromAddress.isBlank()) {
            sb.append(",\"from\":{\"emailAddress\":{\"address\":\"").append(jsonEscape(fromAddress)).append("\"}}");
        }
        if (!safe(job.replyTo).trim().isBlank()) {
            sb.append(",\"replyTo\":[{\"emailAddress\":{\"address\":\"").append(jsonEscape(job.replyTo.trim())).append("\"}}]");
        }

        if (attachments != null && !attachments.isEmpty()) {
            sb.append(",\"attachments\":[");
            int n = 0;
            for (int i = 0; i < attachments.size(); i++) {
                QueuedAttachment a = attachments.get(i);
                if (a == null || a.storedPath.isBlank()) continue;
                Path p = Paths.get(a.storedPath).toAbsolutePath().normalize();
                if (!Files.exists(p)) throw new IllegalStateException("attachment missing: " + p);
                byte[] bytes = Files.readAllBytes(p);
                if (n++ > 0) sb.append(",");
                sb.append("{\"@odata.type\":\"#microsoft.graph.fileAttachment\",");
                sb.append("\"name\":\"").append(jsonEscape(nonBlank(a.fileName, p.getFileName() == null ? "attachment.bin" : p.getFileName().toString()))).append("\",");
                sb.append("\"contentType\":\"").append(jsonEscape(contentTypeOrDefault(a.contentType))).append("\",");
                sb.append("\"contentBytes\":\"").append(Base64.getEncoder().encodeToString(bytes)).append("\"}");
            }
            sb.append("]");
        }
        sb.append("},\"saveToSentItems\":true}");
        return sb.toString();
    }

    private void appendGraphRecipients(StringBuilder sb, String key, List<String> recipients) {
        sb.append("\"").append(key).append("\":[");
        int n = 0;
        List<String> xs = recipients == null ? List.of() : recipients;
        for (int i = 0; i < xs.size(); i++) {
            String email = safe(xs.get(i)).trim();
            if (email.isBlank()) continue;
            if (n++ > 0) sb.append(",");
            sb.append("{\"emailAddress\":{\"address\":\"").append(jsonEscape(email)).append("\"}}");
        }
        sb.append("]");
    }

    private List<QueuedAttachment> materializeAttachments(String tenantUuid,
                                                          NotificationEmailRequest request,
                                                          String jobUuid) throws Exception {
        ArrayList<QueuedAttachment> out = new ArrayList<QueuedAttachment>();
        List<AttachmentSpec> specs = request.attachments == null ? List.of() : request.attachments;
        if (specs.isEmpty()) return out;

        Path attachmentRoot = attachmentRootDir(tenantUuid, jobUuid);
        Files.createDirectories(attachmentRoot);

        for (int i = 0; i < specs.size(); i++) {
            AttachmentSpec spec = specs.get(i);
            if (spec == null) continue;
            String type = normalizeAttachmentType(spec.sourceType);

            byte[] bytes;
            String sourceRef;
            String fileName = safe(spec.fileName).trim();
            String contentType = safe(spec.contentType).trim();

            if ("external_storage".equals(type)) {
                String objectKey = safe(spec.storageObjectKey).trim();
                if (objectKey.isBlank()) throw new IllegalArgumentException("attachment storage object key is required");
                String backendType = safe(spec.storageBackend).trim();
                if (backendType.isBlank()) backendType = storageBackendResolver.resolveBackendType(tenantUuid);
                String matterUuid = safeFileToken(spec.storageMatterUuid);
                if (matterUuid.isBlank()) matterUuid = safeFileToken(request.matterUuid);
                DocumentStorageBackend backend = storageBackendResolver.resolve(tenantUuid, matterUuid, backendType);
                boolean exists = backend.exists(objectKey);
                if (!exists) throw new IllegalStateException("external storage attachment missing: " + objectKey);
                bytes = backend.get(objectKey);
                sourceRef = "external_storage:" + backendType + ":" + objectKey;
                if (fileName.isBlank()) fileName = nameFromPath(objectKey, "attachment-" + (i + 1) + ".bin");
            } else {
                String localPath = safe(spec.localPath).trim();
                if (localPath.isBlank()) throw new IllegalArgumentException("attachment local path is required");
                Path p = Paths.get(localPath);
                if (!p.isAbsolute()) p = Paths.get(".").toAbsolutePath().resolve(p).normalize();
                if (!Files.exists(p) || !Files.isRegularFile(p)) {
                    throw new IllegalStateException("local attachment file missing: " + p);
                }
                bytes = Files.readAllBytes(p);
                sourceRef = "local_file:" + p;
                if (fileName.isBlank()) fileName = nameFromPath(p.toString(), "attachment-" + (i + 1) + ".bin");
                if (contentType.isBlank()) {
                    try {
                        contentType = safe(Files.probeContentType(p));
                    } catch (Exception ignored) {
                        contentType = "";
                    }
                }
            }

            if (contentType.isBlank()) contentType = guessContentType(fileName);

            String safeName = sanitizeAttachmentFileName(fileName);
            if (safeName.isBlank()) safeName = "attachment-" + (i + 1) + ".bin";
            Path stored = attachmentRoot.resolve((i + 1) + "_" + safeName).normalize();
            Files.write(stored, bytes == null ? new byte[0] : bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            String checksum = StorageCrypto.checksumSha256Hex(bytes);
            out.add(new QueuedAttachment(fileName, contentType, stored.toString(), type, sourceRef, bytes == null ? 0 : bytes.length, checksum));
        }
        return out;
    }

    private void logSendResult(EmailJob job, boolean ok) {
        try {
            LinkedHashMap<String, String> details = new LinkedHashMap<String, String>();
            details.put("email_uuid", job.uuid);
            details.put("provider", job.provider);
            details.put("state", job.state);
            details.put("attempts", String.valueOf(job.attempts));
            details.put("result", ok ? "ok" : "failed");
            details.put("provider_status_code", String.valueOf(job.providerStatusCode));
            details.put("provider_message_id", safe(job.providerMessageId));
            details.put("server_response", safe(job.serverResponse));
            details.put("last_error", safe(job.lastError));
            details.put("subject", job.subject);
            details.put("to_count", String.valueOf(job.to.size()));
            details.put("cc_count", String.valueOf(job.cc.size()));
            details.put("bcc_count", String.valueOf(job.bcc.size()));
            details.put("attachment_count", String.valueOf(job.attachments.size()));
            logs.logVerbose(ok ? "notification_email.sent" : "notification_email.failed", job.tenantUuid, job.requestedByUserUuid, job.matterUuid, "", details);
        } catch (Exception ignored) {
        }
    }

    private static List<EmailJob> readQueueLocked(String tenantUuid) throws Exception {
        ArrayList<EmailJob> out = new ArrayList<EmailJob>();
        Path p = queuePath(tenantUuid);
        if (!Files.exists(p)) return out;
        Document d = parseXml(p);
        Element root = d == null ? null : d.getDocumentElement();
        if (root == null) return out;

        NodeList nl = root.getElementsByTagName("email");
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (!(n instanceof Element)) continue;
            Element e = (Element) n;

            ArrayList<QueuedAttachment> atts = new ArrayList<QueuedAttachment>();
            Element attachmentsEl = child(e, "attachments");
            if (attachmentsEl != null) {
                NodeList an = attachmentsEl.getElementsByTagName("attachment");
                for (int j = 0; j < an.getLength(); j++) {
                    Node anNode = an.item(j);
                    if (!(anNode instanceof Element)) continue;
                    Element ae = (Element) anNode;
                    atts.add(new QueuedAttachment(
                            text(ae, "file_name"),
                            text(ae, "content_type"),
                            text(ae, "stored_path"),
                            text(ae, "source_type"),
                            text(ae, "source_ref"),
                            parseLong(text(ae, "size_bytes"), 0L),
                            text(ae, "checksum_sha256")
                    ));
                }
            }

            out.add(new EmailJob(
                    text(e, "uuid"),
                    tenantUuid,
                    text(e, "matter_uuid"),
                    text(e, "requested_by_user_uuid"),
                    text(e, "provider"),
                    text(e, "state"),
                    parseInt(text(e, "attempts"), 0),
                    parseInt(text(e, "max_attempts"), 8),
                    text(e, "created_at"),
                    text(e, "updated_at"),
                    text(e, "last_attempt_at"),
                    text(e, "next_attempt_at"),
                    text(e, "sent_at"),
                    text(e, "from_address"),
                    text(e, "from_name"),
                    text(e, "reply_to"),
                    text(e, "subject"),
                    text(e, "body_text"),
                    text(e, "body_html"),
                    splitAddresses(text(e, "to_list")),
                    splitAddresses(text(e, "cc_list")),
                    splitAddresses(text(e, "bcc_list")),
                    atts,
                    text(e, "result"),
                    parseInt(text(e, "provider_status_code"), 0),
                    text(e, "provider_message_id"),
                    text(e, "server_response"),
                    text(e, "last_error")
            ));
        }
        return out;
    }

    private static void writeQueueLocked(String tenantUuid, List<EmailJob> jobs) throws Exception {
        Path p = queuePath(tenantUuid);
        Files.createDirectories(p.getParent());
        String now = Instant.now().toString();
        StringBuilder sb = new StringBuilder(4096);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<notification_email_queue updated=\"").append(xmlAttr(now)).append("\">\n");
        for (int i = 0; i < (jobs == null ? 0 : jobs.size()); i++) {
            EmailJob j = jobs.get(i);
            if (j == null) continue;
            sb.append("  <email>\n");
            sb.append("    <uuid>").append(xmlText(j.uuid)).append("</uuid>\n");
            sb.append("    <matter_uuid>").append(xmlText(j.matterUuid)).append("</matter_uuid>\n");
            sb.append("    <requested_by_user_uuid>").append(xmlText(j.requestedByUserUuid)).append("</requested_by_user_uuid>\n");
            sb.append("    <provider>").append(xmlText(j.provider)).append("</provider>\n");
            sb.append("    <state>").append(xmlText(j.state)).append("</state>\n");
            sb.append("    <attempts>").append(j.attempts).append("</attempts>\n");
            sb.append("    <max_attempts>").append(j.maxAttempts).append("</max_attempts>\n");
            sb.append("    <created_at>").append(xmlText(j.createdAt)).append("</created_at>\n");
            sb.append("    <updated_at>").append(xmlText(j.updatedAt)).append("</updated_at>\n");
            sb.append("    <last_attempt_at>").append(xmlText(j.lastAttemptAt)).append("</last_attempt_at>\n");
            sb.append("    <next_attempt_at>").append(xmlText(j.nextAttemptAt)).append("</next_attempt_at>\n");
            sb.append("    <sent_at>").append(xmlText(j.sentAt)).append("</sent_at>\n");
            sb.append("    <from_address>").append(xmlText(j.fromAddress)).append("</from_address>\n");
            sb.append("    <from_name>").append(xmlText(j.fromName)).append("</from_name>\n");
            sb.append("    <reply_to>").append(xmlText(j.replyTo)).append("</reply_to>\n");
            sb.append("    <subject>").append(xmlText(j.subject)).append("</subject>\n");
            sb.append("    <body_text>").append(xmlText(j.bodyText)).append("</body_text>\n");
            sb.append("    <body_html>").append(xmlText(j.bodyHtml)).append("</body_html>\n");
            sb.append("    <to_list>").append(xmlText(String.join("\n", j.to))).append("</to_list>\n");
            sb.append("    <cc_list>").append(xmlText(String.join("\n", j.cc))).append("</cc_list>\n");
            sb.append("    <bcc_list>").append(xmlText(String.join("\n", j.bcc))).append("</bcc_list>\n");
            sb.append("    <attachments>\n");
            for (int a = 0; a < (j.attachments == null ? 0 : j.attachments.size()); a++) {
                QueuedAttachment qa = j.attachments.get(a);
                if (qa == null) continue;
                sb.append("      <attachment>\n");
                sb.append("        <file_name>").append(xmlText(qa.fileName)).append("</file_name>\n");
                sb.append("        <content_type>").append(xmlText(qa.contentType)).append("</content_type>\n");
                sb.append("        <stored_path>").append(xmlText(qa.storedPath)).append("</stored_path>\n");
                sb.append("        <source_type>").append(xmlText(qa.sourceType)).append("</source_type>\n");
                sb.append("        <source_ref>").append(xmlText(qa.sourceRef)).append("</source_ref>\n");
                sb.append("        <size_bytes>").append(qa.sizeBytes).append("</size_bytes>\n");
                sb.append("        <checksum_sha256>").append(xmlText(qa.checksumSha256)).append("</checksum_sha256>\n");
                sb.append("      </attachment>\n");
            }
            sb.append("    </attachments>\n");
            sb.append("    <result>").append(xmlText(j.result)).append("</result>\n");
            sb.append("    <provider_status_code>").append(j.providerStatusCode).append("</provider_status_code>\n");
            sb.append("    <provider_message_id>").append(xmlText(j.providerMessageId)).append("</provider_message_id>\n");
            sb.append("    <server_response>").append(xmlText(j.serverResponse)).append("</server_response>\n");
            sb.append("    <last_error>").append(xmlText(j.lastError)).append("</last_error>\n");
            sb.append("  </email>\n");
        }
        sb.append("</notification_email_queue>\n");
        writeAtomic(p, sb.toString());
    }

    private static Path queuePath(String tenantUuid) {
        return Paths.get("data", "tenants", safeFileToken(tenantUuid), "sync", "notification_email_queue.xml").toAbsolutePath();
    }

    private static Path attachmentRootDir(String tenantUuid, String emailUuid) {
        return Paths.get("data", "tenants", safeFileToken(tenantUuid), "sync", "email_queue_attachments", safeFileToken(emailUuid)).toAbsolutePath();
    }

    private static ReentrantReadWriteLock queueLockFor(String tenantUuid) {
        String key = safeFileToken(tenantUuid);
        return QUEUE_LOCKS.computeIfAbsent(key, k -> new ReentrantReadWriteLock());
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
        Files.writeString(tmp, safe(content), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        try {
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception ex) {
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Element child(Element parent, String tag) {
        if (parent == null || tag == null) return null;
        NodeList nl = parent.getElementsByTagName(tag);
        if (nl.getLength() == 0) return null;
        Node n = nl.item(0);
        return n instanceof Element ? (Element) n : null;
    }

    private static String text(Element parent, String childTag) {
        if (parent == null || childTag == null) return "";
        NodeList nl = parent.getElementsByTagName(childTag);
        if (nl.getLength() == 0) return "";
        Node n = nl.item(0);
        return n == null ? "" : safe(n.getTextContent());
    }

    private static String normalizeProvider(String provider) {
        String p = safe(provider).trim().toLowerCase(Locale.ROOT);
        if ("smtp".equals(p)) return "smtp";
        if ("microsoft_graph".equals(p) || "graph".equals(p) || "ms_graph".equals(p)) return "microsoft_graph";
        return "disabled";
    }

    private static String normalizeState(String state) {
        String s = safe(state).trim().toLowerCase(Locale.ROOT);
        if ("retry".equals(s)) return "retry";
        if ("sent".equals(s)) return "sent";
        if ("dead_letter".equals(s)) return "dead_letter";
        return "pending";
    }

    private static List<String> normalizeAddressList(List<String> in) {
        ArrayList<String> out = new ArrayList<String>();
        if (in == null) return out;
        for (int i = 0; i < in.size(); i++) {
            String v = safe(in.get(i)).trim();
            if (v.isBlank()) continue;
            if (!out.contains(v)) out.add(v);
        }
        return out;
    }

    private static List<String> splitAddresses(String raw) {
        String src = safe(raw).replace(';', '\n').replace(',', '\n');
        ArrayList<String> out = new ArrayList<String>();
        String[] xs = src.split("\\n");
        for (int i = 0; i < xs.length; i++) {
            String v = safe(xs[i]).trim();
            if (v.isBlank()) continue;
            if (!out.contains(v)) out.add(v);
        }
        return out;
    }

    private static String normalizeAttachmentType(String raw) {
        String v = safe(raw).trim().toLowerCase(Locale.ROOT);
        if ("external_storage".equals(v) || "storage".equals(v) || "remote".equals(v)) return "external_storage";
        return "local_file";
    }

    private static String nameFromPath(String pathLike, String fallback) {
        String s = safe(pathLike).trim();
        if (s.isBlank()) return fallback;
        s = s.replace('\\', '/');
        int idx = s.lastIndexOf('/');
        if (idx >= 0 && idx + 1 < s.length()) s = s.substring(idx + 1);
        return s.isBlank() ? fallback : s;
    }

    private static String contentTypeOrDefault(String value) {
        String v = safe(value).trim();
        return v.isBlank() ? "application/octet-stream" : v;
    }

    private static String guessContentType(String fileName) {
        String f = safe(fileName).trim().toLowerCase(Locale.ROOT);
        if (f.endsWith(".pdf")) return "application/pdf";
        if (f.endsWith(".txt")) return "text/plain";
        if (f.endsWith(".csv")) return "text/csv";
        if (f.endsWith(".html") || f.endsWith(".htm")) return "text/html";
        if (f.endsWith(".doc")) return "application/msword";
        if (f.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (f.endsWith(".xls")) return "application/vnd.ms-excel";
        if (f.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (f.endsWith(".ppt")) return "application/vnd.ms-powerpoint";
        if (f.endsWith(".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if (f.endsWith(".jpg") || f.endsWith(".jpeg")) return "image/jpeg";
        if (f.endsWith(".png")) return "image/png";
        if (f.endsWith(".gif")) return "image/gif";
        if (f.endsWith(".zip")) return "application/zip";
        return "application/octet-stream";
    }

    private static String jsonStringValue(String json, String key) {
        String src = safe(json);
        String quotedKey = "\"" + safe(key).replace("\"", "\\\"") + "\"";
        int k = src.indexOf(quotedKey);
        if (k < 0) return "";
        int colon = src.indexOf(':', k + quotedKey.length());
        if (colon < 0) return "";
        int first = src.indexOf('"', colon + 1);
        if (first < 0) return "";
        StringBuilder out = new StringBuilder();
        boolean esc = false;
        for (int i = first + 1; i < src.length(); i++) {
            char ch = src.charAt(i);
            if (esc) {
                switch (ch) {
                    case '"': out.append('"'); break;
                    case '\\': out.append('\\'); break;
                    case '/': out.append('/'); break;
                    case 'b': out.append('\b'); break;
                    case 'f': out.append('\f'); break;
                    case 'n': out.append('\n'); break;
                    case 'r': out.append('\r'); break;
                    case 't': out.append('\t'); break;
                    default: out.append(ch); break;
                }
                esc = false;
                continue;
            }
            if (ch == '\\') {
                esc = true;
                continue;
            }
            if (ch == '"') return out.toString();
            out.append(ch);
        }
        return "";
    }

    private static HttpResponse postForm(String endpoint,
                                         Map<String, String> fields,
                                         int connectTimeoutMs,
                                         int readTimeoutMs,
                                         Map<String, String> headers) throws Exception {
        StringBuilder body = new StringBuilder();
        int n = 0;
        for (Map.Entry<String, String> e : (fields == null ? Map.<String, String>of() : fields).entrySet()) {
            if (e == null) continue;
            String k = safe(e.getKey());
            if (k.isBlank()) continue;
            if (n++ > 0) body.append('&');
            body.append(urlEncode(k)).append('=').append(urlEncode(safe(e.getValue())));
        }
        return httpPost(endpoint, "application/x-www-form-urlencoded; charset=UTF-8", body.toString(), connectTimeoutMs, readTimeoutMs, headers);
    }

    private static HttpResponse postJson(String endpoint,
                                         String jsonBody,
                                         int connectTimeoutMs,
                                         int readTimeoutMs,
                                         Map<String, String> headers) throws Exception {
        return httpPost(endpoint, "application/json; charset=UTF-8", safe(jsonBody), connectTimeoutMs, readTimeoutMs, headers);
    }

    private static HttpResponse httpPost(String endpoint,
                                         String contentType,
                                         String body,
                                         int connectTimeoutMs,
                                         int readTimeoutMs,
                                         Map<String, String> headers) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create(endpoint).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(Math.max(1000, connectTimeoutMs));
        conn.setReadTimeout(Math.max(1000, readTimeoutMs));
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", contentType);
        conn.setRequestProperty("Accept", "application/json");
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e == null) continue;
                String k = safe(e.getKey()).trim();
                if (k.isBlank()) continue;
                conn.setRequestProperty(k, safe(e.getValue()));
            }
        }
        byte[] payload = safe(body).getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = conn.getOutputStream()) {
            out.write(payload);
        }
        int status = conn.getResponseCode();
        String responseBody = "";
        InputStream stream = status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream();
        if (stream != null) {
            try (InputStream in = stream) {
                responseBody = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        LinkedHashMap<String, String> outHeaders = new LinkedHashMap<String, String>();
        for (Map.Entry<String, List<String>> h : conn.getHeaderFields().entrySet()) {
            if (h == null) continue;
            String k = safe(h.getKey()).trim().toLowerCase(Locale.ROOT);
            if (k.isBlank()) continue;
            List<String> vs = h.getValue();
            if (vs == null || vs.isEmpty()) continue;
            outHeaders.put(k, safe(vs.get(0)));
        }
        return new HttpResponse(status, responseBody, outHeaders);
    }

    private static final class HttpResponse {
        final int statusCode;
        final String body;
        final Map<String, String> headers;

        HttpResponse(int statusCode, String body, Map<String, String> headers) {
            this.statusCode = statusCode;
            this.body = safe(body);
            this.headers = headers == null ? Map.of() : headers;
        }
    }

    private static String urlEncode(String raw) {
        return URLEncoder.encode(safe(raw), StandardCharsets.UTF_8);
    }

    private static String urlEncodePathSegment(String raw) {
        return urlEncode(raw).replace("+", "%20");
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

    private static boolean truthy(String raw) {
        String v = safe(raw).trim().toLowerCase(Locale.ROOT);
        return "1".equals(v) || "true".equals(v) || "on".equals(v) || "yes".equals(v);
    }

    private static String nonBlank(String preferred, String fallback) {
        String p = safe(preferred).trim();
        if (!p.isBlank()) return p;
        return safe(fallback).trim();
    }

    private static String safeFileToken(String s) {
        String t = safe(s).trim();
        if (t.isBlank()) return "";
        return t.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String sanitizeAttachmentFileName(String s) {
        String t = safe(s).trim();
        if (t.isBlank()) return "";
        return t.replaceAll("[^A-Za-z0-9.]", "_");
    }

    private static String jsonEscape(String s) {
        String src = safe(s);
        StringBuilder out = new StringBuilder(src.length() + 16);
        for (int i = 0; i < src.length(); i++) {
            char ch = src.charAt(i);
            switch (ch) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (ch < 0x20) out.append(String.format("\\u%04x", (int) ch));
                    else out.append(ch);
                    break;
            }
        }
        return out.toString();
    }

    private static String xmlText(String s) {
        return safe(s)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("'", "&apos;");
    }

    private static String xmlAttr(String s) {
        return xmlText(s).replace("\"", "&quot;");
    }

    private static String truncate(String s, int maxChars) {
        String v = safe(s);
        if (maxChars <= 0 || v.length() <= maxChars) return v;
        return v.substring(0, maxChars);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
