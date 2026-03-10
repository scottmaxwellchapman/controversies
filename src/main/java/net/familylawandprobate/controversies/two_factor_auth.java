package net.familylawandprobate.controversies;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * two_factor_auth
 *
 * Challenge files:
 *   data/tenants/{tenantUuid}/auth/two_factor/challenge_{challengeId}.xml
 *
 * Engines currently supported:
 *   - email_pin
 *   - flowroute_sms
 */
public final class two_factor_auth {

    public static final String POLICY_OFF = "off";
    public static final String POLICY_OPTIONAL = "optional";
    public static final String POLICY_REQUIRED = "required";

    public static final String ENGINE_EMAIL_PIN = "email_pin";
    public static final String ENGINE_FLOWROUTE_SMS = "flowroute_sms";
    public static final String USER_ENGINE_INHERIT = "inherit";

    private static final ConcurrentHashMap<String, ReentrantReadWriteLock> LOCKS = new ConcurrentHashMap<String, ReentrantReadWriteLock>();
    private static final SecureRandom RNG = new SecureRandom();

    private static final Duration CHALLENGE_TTL = Duration.ofMinutes(10L);
    private static final Duration CHALLENGE_RETENTION = Duration.ofHours(24L);
    private static final int MAX_ATTEMPTS = 6;

    @FunctionalInterface
    interface EmailSender {
        void send(String tenantUuid, String requestedByUserUuid, String toAddress, String subject, String bodyText) throws Exception;
    }

    @FunctionalInterface
    interface FlowrouteSender {
        void send(String endpoint,
                  String accessKey,
                  String secretKey,
                  String fromNumber,
                  String toNumber,
                  String body,
                  int connectTimeoutMs,
                  int readTimeoutMs) throws Exception;
    }

    public static final class Requirement {
        public final boolean required;
        public final String policy;
        public final String engine;
        public final String issue;

        public Requirement(boolean required, String policy, String engine, String issue) {
            this.required = required;
            this.policy = normalizePolicy(policy);
            this.engine = normalizeEngine(engine);
            this.issue = safe(issue).trim();
        }
    }

    public static final class ChallengeStartResult {
        public final boolean success;
        public final boolean required;
        public final String challengeId;
        public final String engine;
        public final String maskedDestination;
        public final String issue;

        public ChallengeStartResult(boolean success,
                                    boolean required,
                                    String challengeId,
                                    String engine,
                                    String maskedDestination,
                                    String issue) {
            this.success = success;
            this.required = required;
            this.challengeId = safe(challengeId).trim();
            this.engine = normalizeEngine(engine);
            this.maskedDestination = safe(maskedDestination).trim();
            this.issue = safe(issue).trim();
        }
    }

    public static final class VerifyResult {
        public final boolean success;
        public final boolean expired;
        public final int remainingAttempts;
        public final String issue;

        public VerifyResult(boolean success, boolean expired, int remainingAttempts, String issue) {
            this.success = success;
            this.expired = expired;
            this.remainingAttempts = Math.max(0, remainingAttempts);
            this.issue = safe(issue).trim();
        }
    }

    private static final class ChallengeRec {
        final String challengeId;
        final String tenantUuid;
        final String userUuid;
        final String engine;
        final String destination;
        final String ip;
        final String salt;
        final String codeHash;
        final String createdAt;
        final String expiresAt;
        final int attempts;
        final int maxAttempts;

        ChallengeRec(String challengeId,
                     String tenantUuid,
                     String userUuid,
                     String engine,
                     String destination,
                     String ip,
                     String salt,
                     String codeHash,
                     String createdAt,
                     String expiresAt,
                     int attempts,
                     int maxAttempts) {
            this.challengeId = safeFileToken(challengeId);
            this.tenantUuid = safeFileToken(tenantUuid);
            this.userUuid = safe(userUuid).trim();
            this.engine = normalizeEngine(engine);
            this.destination = safe(destination).trim();
            this.ip = safe(ip).trim();
            this.salt = safe(salt).trim();
            this.codeHash = safe(codeHash).trim().toLowerCase(Locale.ROOT);
            this.createdAt = safe(createdAt).trim();
            this.expiresAt = safe(expiresAt).trim();
            this.attempts = Math.max(0, attempts);
            this.maxAttempts = Math.max(1, maxAttempts);
        }
    }

    private final tenant_settings settingsStore;
    private final notification_emails notificationEmails;
    private final activity_log logs;
    private final EmailSender emailSender;
    private final FlowrouteSender flowrouteSender;

    private static final class Holder {
        private static final two_factor_auth INSTANCE = new two_factor_auth();
    }

    public static two_factor_auth defaultStore() {
        return Holder.INSTANCE;
    }

    public two_factor_auth() {
        this(tenant_settings.defaultStore(), notification_emails.defaultStore(), activity_log.defaultStore(), null, null);
    }

    two_factor_auth(tenant_settings settingsStore,
                    notification_emails notificationEmails,
                    activity_log logs,
                    EmailSender emailSender,
                    FlowrouteSender flowrouteSender) {
        this.settingsStore = settingsStore == null ? tenant_settings.defaultStore() : settingsStore;
        this.notificationEmails = notificationEmails == null ? notification_emails.defaultStore() : notificationEmails;
        this.logs = logs == null ? activity_log.defaultStore() : logs;
        this.emailSender = emailSender == null ? this::sendEmailCode : emailSender;
        this.flowrouteSender = flowrouteSender == null ? this::sendFlowrouteCode : flowrouteSender;
    }

    public Requirement resolveRequirement(String tenantUuid, users_roles.UserRec user) {
        String tu = safeFileToken(tenantUuid);
        if (tu.isBlank() || user == null) return new Requirement(false, POLICY_OFF, ENGINE_EMAIL_PIN, "Tenant or user context is missing.");
        LinkedHashMap<String, String> cfg = settingsStore.read(tu);
        String policy = normalizePolicy(cfg.get("two_factor_policy"));
        boolean userEnabled = user.twoFactorEnabled;
        boolean required = POLICY_REQUIRED.equals(policy) || userEnabled;
        if (!required) {
            return new Requirement(false, policy, ENGINE_EMAIL_PIN, "");
        }

        String engine = normalizeUserEngine(user.twoFactorEngine);
        if (USER_ENGINE_INHERIT.equals(engine)) {
            engine = normalizeEngine(cfg.get("two_factor_default_engine"));
        }
        if (engine.isBlank()) engine = ENGINE_EMAIL_PIN;

        String issue = readinessIssue(cfg, user, engine);
        return new Requirement(true, policy, engine, issue);
    }

    public ChallengeStartResult startChallenge(String tenantUuid,
                                               users_roles.UserRec user,
                                               String requestedByUserUuid,
                                               String clientIp) throws Exception {
        String tu = safeFileToken(tenantUuid);
        if (tu.isBlank() || user == null) {
            return new ChallengeStartResult(false, false, "", "", "", "Tenant or user context is missing.");
        }

        Requirement req = resolveRequirement(tu, user);
        if (!req.required) {
            return new ChallengeStartResult(true, false, "", "", "", "");
        }
        if (!req.issue.isBlank()) {
            return new ChallengeStartResult(false, true, "", req.engine, "", req.issue);
        }

        LinkedHashMap<String, String> cfg = settingsStore.read(tu);
        String destination = resolveDestination(cfg, user, req.engine);
        if (destination.isBlank()) {
            return new ChallengeStartResult(false, true, "", req.engine, "", "No delivery destination is configured for two-factor authentication.");
        }

        String challengeId = UUID.randomUUID().toString();
        String code = randomSixDigitCode();
        String salt = randomSalt();
        String codeHash = sha256Hex(salt + ":" + code);
        Instant now = Instant.now();

        ChallengeRec rec = new ChallengeRec(
                challengeId,
                tu,
                user.uuid,
                req.engine,
                destination,
                safe(clientIp).trim(),
                salt,
                codeHash,
                now.toString(),
                now.plus(CHALLENGE_TTL).toString(),
                0,
                MAX_ATTEMPTS
        );

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            cleanupStaleChallengesLocked(tu);
            writeChallengeLocked(rec);
        } finally {
            lock.writeLock().unlock();
        }

        try {
            dispatchCode(tu, safe(requestedByUserUuid).trim(), cfg, user, req.engine, destination, code);
        } catch (Exception ex) {
            try { deleteChallenge(tu, challengeId); } catch (Exception ignored) {}
            return new ChallengeStartResult(false, true, "", req.engine, "", "Unable to deliver a verification code. " + safe(ex.getMessage()));
        }

        LinkedHashMap<String, String> details = new LinkedHashMap<String, String>();
        details.put("engine", req.engine);
        details.put("destination", maskDestination(req.engine, destination));
        details.put("challenge_id", challengeId);
        details.put("expires_at", rec.expiresAt);
        logs.logVerbose("two_factor.challenge_sent", tu, safe(requestedByUserUuid).trim(), "", "", details);

        return new ChallengeStartResult(true, true, challengeId, req.engine, maskDestination(req.engine, destination), "");
    }

    public VerifyResult verifyChallenge(String tenantUuid,
                                        String userUuid,
                                        String challengeId,
                                        String submittedCode,
                                        String clientIp) throws Exception {
        String tu = safeFileToken(tenantUuid);
        String uu = safe(userUuid).trim();
        String cid = safeFileToken(challengeId);
        String code = digitsOnly(submittedCode);
        if (tu.isBlank() || uu.isBlank() || cid.isBlank() || code.length() != 6) {
            logs.logWarning(
                    "two_factor.verify_failed",
                    tu,
                    uu,
                    "",
                    "",
                    Map.of(
                            "challenge_id", cid,
                            "reason", "invalid_input",
                            "client_ip", safe(clientIp).trim()
                    )
            );
            return new VerifyResult(false, false, 0, "Verification code is invalid.");
        }

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ChallengeRec rec = readChallengeLocked(tu, cid);
            if (rec == null) {
                logs.logWarning(
                        "two_factor.verify_failed",
                        tu,
                        uu,
                        "",
                        "",
                        Map.of(
                                "challenge_id", cid,
                                "reason", "challenge_not_found",
                                "client_ip", safe(clientIp).trim()
                        )
                );
                return new VerifyResult(false, false, 0, "Verification challenge was not found.");
            }
            if (!uu.equals(rec.userUuid)) {
                logs.logWarning(
                        "two_factor.verify_failed",
                        tu,
                        uu,
                        "",
                        "",
                        Map.of(
                                "challenge_id", cid,
                                "reason", "challenge_user_mismatch",
                                "client_ip", safe(clientIp).trim()
                        )
                );
                return new VerifyResult(false, false, 0, "Verification challenge does not match the authenticated user.");
            }
            if (!rec.ip.isBlank() && !safe(clientIp).trim().isBlank() && !rec.ip.equals(safe(clientIp).trim())) {
                logs.logWarning(
                        "two_factor.verify_failed",
                        tu,
                        uu,
                        "",
                        "",
                        Map.of(
                                "challenge_id", cid,
                                "reason", "client_context_changed",
                                "client_ip", safe(clientIp).trim()
                        )
                );
                return new VerifyResult(false, false, 0, "Client verification context changed. Start over and try again.");
            }

            Instant now = Instant.now();
            Instant expiry = parseInstant(rec.expiresAt);
            if (expiry == null || now.isAfter(expiry)) {
                deleteChallengeLocked(tu, cid);
                logs.logWarning(
                        "two_factor.verify_failed",
                        tu,
                        uu,
                        "",
                        "",
                        Map.of(
                                "challenge_id", cid,
                                "reason", "expired",
                                "client_ip", safe(clientIp).trim()
                        )
                );
                return new VerifyResult(false, true, 0, "Verification code expired. Request a new code.");
            }

            if (rec.attempts >= rec.maxAttempts) {
                deleteChallengeLocked(tu, cid);
                logs.logWarning(
                        "two_factor.verify_failed",
                        tu,
                        uu,
                        "",
                        "",
                        Map.of(
                                "challenge_id", cid,
                                "reason", "max_attempts_reached",
                                "client_ip", safe(clientIp).trim()
                        )
                );
                return new VerifyResult(false, false, 0, "Too many verification attempts. Request a new code.");
            }

            String expectedHash = rec.codeHash;
            String providedHash = sha256Hex(rec.salt + ":" + code);
            boolean ok = MessageDigest.isEqual(
                    expectedHash.getBytes(StandardCharsets.UTF_8),
                    providedHash.getBytes(StandardCharsets.UTF_8)
            );

            if (ok) {
                deleteChallengeLocked(tu, cid);
                logs.logVerbose(
                        "two_factor.verify_success",
                        tu,
                        uu,
                        "",
                        "",
                        Map.of(
                                "challenge_id", cid,
                                "engine", safe(rec.engine),
                                "client_ip", safe(clientIp).trim()
                        )
                );
                return new VerifyResult(true, false, rec.maxAttempts - rec.attempts, "");
            }

            int attempts = rec.attempts + 1;
            int remaining = Math.max(0, rec.maxAttempts - attempts);
            if (remaining <= 0) {
                deleteChallengeLocked(tu, cid);
                logs.logWarning(
                        "two_factor.verify_failed",
                        tu,
                        uu,
                        "",
                        "",
                        Map.of(
                                "challenge_id", cid,
                                "reason", "max_attempts_reached",
                                "client_ip", safe(clientIp).trim()
                        )
                );
                return new VerifyResult(false, false, 0, "Too many verification attempts. Request a new code.");
            }

            writeChallengeLocked(new ChallengeRec(
                    rec.challengeId,
                    rec.tenantUuid,
                    rec.userUuid,
                    rec.engine,
                    rec.destination,
                    rec.ip,
                    rec.salt,
                    rec.codeHash,
                    rec.createdAt,
                    rec.expiresAt,
                    attempts,
                    rec.maxAttempts
            ));
            LinkedHashMap<String, String> details = new LinkedHashMap<String, String>();
            details.put("challenge_id", cid);
            details.put("reason", "incorrect_code");
            details.put("remaining_attempts", String.valueOf(remaining));
            details.put("client_ip", safe(clientIp).trim());
            logs.logWarning("two_factor.verify_failed", tu, uu, "", "", details);
            return new VerifyResult(false, false, remaining, "Verification code is incorrect.");
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void invalidateChallenge(String tenantUuid, String challengeId) {
        String tu = safeFileToken(tenantUuid);
        String cid = safeFileToken(challengeId);
        if (tu.isBlank() || cid.isBlank()) return;
        try {
            deleteChallenge(tu, cid);
        } catch (Exception ignored) {
        }
    }

    private String readinessIssue(Map<String, String> cfg, users_roles.UserRec user, String engine) {
        if (ENGINE_FLOWROUTE_SMS.equals(engine)) {
            String access = safe(cfg.get("flowroute_sms_access_key")).trim();
            String secret = safe(cfg.get("flowroute_sms_secret_key")).trim();
            String from = normalizePhone(cfg.get("flowroute_sms_from_number"));
            String to = normalizePhone(user.twoFactorPhone);
            if (access.isBlank() || secret.isBlank() || from.isBlank()) {
                return "Flowroute SMS credentials are incomplete in tenant settings.";
            }
            if (to.isBlank()) {
                return "User phone number is required for Flowroute SMS verification.";
            }
            return "";
        }

        notification_emails.ValidationResult vr = notificationEmails.validateConfiguration(cfg);
        String provider = safe(cfg.get("email_provider")).trim().toLowerCase(Locale.ROOT);
        if ("disabled".equals(provider) || !vr.ok) {
            return "Notification email settings must be configured before email PIN authentication can be used.";
        }
        String email = safe(user.emailAddress).trim();
        if (email.isBlank()) return "User email address is required for email PIN authentication.";
        return "";
    }

    private String resolveDestination(Map<String, String> cfg, users_roles.UserRec user, String engine) {
        if (ENGINE_FLOWROUTE_SMS.equals(engine)) {
            return normalizePhone(user.twoFactorPhone);
        }
        return safe(user.emailAddress).trim();
    }

    private void dispatchCode(String tenantUuid,
                              String requestedByUserUuid,
                              Map<String, String> cfg,
                              users_roles.UserRec user,
                              String engine,
                              String destination,
                              String code) throws Exception {
        if (ENGINE_FLOWROUTE_SMS.equals(engine)) {
            String access = safe(cfg.get("flowroute_sms_access_key")).trim();
            String secret = safe(cfg.get("flowroute_sms_secret_key")).trim();
            String from = normalizePhone(cfg.get("flowroute_sms_from_number"));
            String endpoint = safe(cfg.get("flowroute_sms_api_base_url")).trim();
            if (endpoint.isBlank()) endpoint = "https://api.flowroute.com/v2.2/messages";
            int connectTimeoutMs = parseInt(cfg.get("email_connect_timeout_ms"), 15000);
            int readTimeoutMs = parseInt(cfg.get("email_read_timeout_ms"), 20000);
            String body = "Your Controversies verification code is " + code + ". It expires in 10 minutes.";
            flowrouteSender.send(endpoint, access, secret, from, destination, body, connectTimeoutMs, readTimeoutMs);
            return;
        }

        String subject = "Your verification code";
        String body = "Your Controversies verification code is " + code + ". It expires in 10 minutes.";
        emailSender.send(tenantUuid, requestedByUserUuid, destination, subject, body);
    }

    private void sendEmailCode(String tenantUuid,
                               String requestedByUserUuid,
                               String toAddress,
                               String subject,
                               String bodyText) throws Exception {
        notificationEmails.sendSimpleMessageNow(
                tenantUuid,
                requestedByUserUuid,
                new notification_emails.NotificationEmailRequest(
                        "",
                        "",
                        "",
                        "",
                        subject,
                        bodyText,
                        "",
                        java.util.List.of(toAddress),
                        java.util.List.of(),
                        java.util.List.of(),
                        java.util.List.of()
                )
        );
    }

    private void sendFlowrouteCode(String endpoint,
                                   String accessKey,
                                   String secretKey,
                                   String fromNumber,
                                   String toNumber,
                                   String body,
                                   int connectTimeoutMs,
                                   int readTimeoutMs) throws Exception {
        URI uri = URI.create(endpoint);
        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        conn.setConnectTimeout(Math.max(1000, connectTimeoutMs));
        conn.setReadTimeout(Math.max(1000, readTimeoutMs));
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString((safe(accessKey) + ":" + safe(secretKey)).getBytes(StandardCharsets.UTF_8)));
        conn.setRequestProperty("Content-Type", "application/vnd.api+json");
        conn.setRequestProperty("Accept", "application/vnd.api+json");

        String payload =
                "{\"data\":{\"type\":\"message\",\"attributes\":{" +
                        "\"to\":\"" + jsonEscape(toNumber) + "\"," +
                        "\"from\":\"" + jsonEscape(fromNumber) + "\"," +
                        "\"body\":\"" + jsonEscape(body) + "\"" +
                        "}}}";
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream out = conn.getOutputStream()) {
            out.write(bytes);
        }

        int status = conn.getResponseCode();
        String responseBody = readBody(conn, status);
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("Flowroute SMS delivery failed (HTTP " + status + "). " + safe(responseBody));
        }
    }

    private static String readBody(HttpURLConnection conn, int statusCode) {
        try (InputStream in = statusCode >= 200 && statusCode < 400 ? conn.getInputStream() : conn.getErrorStream()) {
            if (in == null) return "";
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return "";
        }
    }

    public static String normalizePolicy(String raw) {
        String v = safe(raw).trim().toLowerCase(Locale.ROOT);
        if (POLICY_REQUIRED.equals(v)) return POLICY_REQUIRED;
        if (POLICY_OPTIONAL.equals(v)) return POLICY_OPTIONAL;
        return POLICY_OFF;
    }

    public static String normalizeEngine(String raw) {
        String v = safe(raw).trim().toLowerCase(Locale.ROOT);
        if (ENGINE_FLOWROUTE_SMS.equals(v)) return ENGINE_FLOWROUTE_SMS;
        return ENGINE_EMAIL_PIN;
    }

    public static String normalizeUserEngine(String raw) {
        String v = safe(raw).trim().toLowerCase(Locale.ROOT);
        if (ENGINE_FLOWROUTE_SMS.equals(v)) return ENGINE_FLOWROUTE_SMS;
        if (ENGINE_EMAIL_PIN.equals(v)) return ENGINE_EMAIL_PIN;
        return USER_ENGINE_INHERIT;
    }

    public static String normalizePhone(String raw) {
        String in = safe(raw).trim();
        if (in.isBlank()) return "";
        boolean plus = in.startsWith("+");
        String digits = in.replaceAll("[^0-9]", "");
        if (digits.length() < 10) return "";
        if (digits.length() > 15) digits = digits.substring(digits.length() - 15);
        return plus ? ("+" + digits) : digits;
    }

    private static String randomSixDigitCode() {
        int n = RNG.nextInt(1_000_000);
        return String.format(Locale.ROOT, "%06d", n);
    }

    private static String randomSalt() {
        byte[] b = new byte[16];
        RNG.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String digitsOnly(String raw) {
        return safe(raw).replaceAll("[^0-9]", "");
    }

    private static String maskDestination(String engine, String destination) {
        String dst = safe(destination).trim();
        if (dst.isBlank()) return "";
        if (ENGINE_FLOWROUTE_SMS.equals(engine)) return maskPhone(dst);
        return maskEmail(dst);
    }

    private static String maskPhone(String raw) {
        String p = normalizePhone(raw);
        if (p.isBlank()) return "";
        String digits = p.startsWith("+") ? p.substring(1) : p;
        String last4 = digits.length() <= 4 ? digits : digits.substring(digits.length() - 4);
        return "***-***-" + last4;
    }

    private static String maskEmail(String raw) {
        String e = safe(raw).trim();
        int at = e.indexOf('@');
        if (at <= 0) return "***";
        String local = e.substring(0, at);
        String domain = e.substring(at + 1);
        if (local.length() <= 2) local = local.substring(0, 1) + "*";
        else local = local.substring(0, 2) + "***";
        return local + "@" + domain;
    }

    private static String sha256Hex(String value) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] out = md.digest(safe(value).getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(out.length * 2);
        for (int i = 0; i < out.length; i++) {
            String h = Integer.toHexString(out[i] & 0xFF);
            if (h.length() < 2) sb.append('0');
            sb.append(h);
        }
        return sb.toString();
    }

    private static Path challengePath(String tenantUuid, String challengeId) {
        return Paths.get("data", "tenants", safeFileToken(tenantUuid), "auth", "two_factor", "challenge_" + safeFileToken(challengeId) + ".xml").toAbsolutePath();
    }

    private static Path challengeDir(String tenantUuid) {
        return Paths.get("data", "tenants", safeFileToken(tenantUuid), "auth", "two_factor").toAbsolutePath();
    }

    private void deleteChallenge(String tenantUuid, String challengeId) throws Exception {
        ReentrantReadWriteLock lock = lockFor(tenantUuid);
        lock.writeLock().lock();
        try {
            deleteChallengeLocked(tenantUuid, challengeId);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private static void deleteChallengeLocked(String tenantUuid, String challengeId) {
        Path p = challengePath(tenantUuid, challengeId);
        try { Files.deleteIfExists(p); } catch (Exception ignored) {}
    }

    private static ChallengeRec readChallengeLocked(String tenantUuid, String challengeId) throws Exception {
        Path p = challengePath(tenantUuid, challengeId);
        if (!Files.exists(p)) return null;
        Document d = parseXml(p);
        Element root = d == null ? null : d.getDocumentElement();
        if (root == null) return null;
        return new ChallengeRec(
                text(root, "challenge_id"),
                tenantUuid,
                text(root, "user_uuid"),
                text(root, "engine"),
                text(root, "destination"),
                text(root, "ip"),
                text(root, "salt"),
                text(root, "code_hash"),
                text(root, "created_at"),
                text(root, "expires_at"),
                parseInt(text(root, "attempts"), 0),
                parseInt(text(root, "max_attempts"), MAX_ATTEMPTS)
        );
    }

    private static void writeChallengeLocked(ChallengeRec rec) throws Exception {
        Path p = challengePath(rec.tenantUuid, rec.challengeId);
        Files.createDirectories(p.getParent());
        StringBuilder sb = new StringBuilder(512);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<two_factor_challenge updated=\"").append(xmlAttr(Instant.now().toString())).append("\">\n");
        sb.append("  <challenge_id>").append(xmlText(rec.challengeId)).append("</challenge_id>\n");
        sb.append("  <user_uuid>").append(xmlText(rec.userUuid)).append("</user_uuid>\n");
        sb.append("  <engine>").append(xmlText(rec.engine)).append("</engine>\n");
        sb.append("  <destination>").append(xmlText(rec.destination)).append("</destination>\n");
        sb.append("  <ip>").append(xmlText(rec.ip)).append("</ip>\n");
        sb.append("  <salt>").append(xmlText(rec.salt)).append("</salt>\n");
        sb.append("  <code_hash>").append(xmlText(rec.codeHash)).append("</code_hash>\n");
        sb.append("  <created_at>").append(xmlText(rec.createdAt)).append("</created_at>\n");
        sb.append("  <expires_at>").append(xmlText(rec.expiresAt)).append("</expires_at>\n");
        sb.append("  <attempts>").append(rec.attempts).append("</attempts>\n");
        sb.append("  <max_attempts>").append(rec.maxAttempts).append("</max_attempts>\n");
        sb.append("</two_factor_challenge>\n");
        writeAtomic(p, sb.toString());
    }

    private static void cleanupStaleChallengesLocked(String tenantUuid) {
        Path dir = challengeDir(tenantUuid);
        if (!Files.isDirectory(dir)) return;
        Instant cutoff = Instant.now().minus(CHALLENGE_RETENTION);
        try (java.util.stream.Stream<Path> files = Files.list(dir)) {
            for (Path p : files.toList()) {
                if (p == null || !Files.isRegularFile(p)) continue;
                try {
                    ChallengeRec rec = readChallengeFromPath(tenantUuid, p);
                    if (rec == null) {
                        Files.deleteIfExists(p);
                        continue;
                    }
                    Instant expiry = parseInstant(rec.expiresAt);
                    Instant created = parseInstant(rec.createdAt);
                    if ((expiry != null && expiry.isBefore(Instant.now())) || (created != null && created.isBefore(cutoff))) {
                        Files.deleteIfExists(p);
                    }
                } catch (Exception ignored) {
                    try { Files.deleteIfExists(p); } catch (Exception ignoredDelete) {}
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static ChallengeRec readChallengeFromPath(String tenantUuid, Path p) throws Exception {
        if (p == null || !Files.exists(p)) return null;
        Document d = parseXml(p);
        Element root = d == null ? null : d.getDocumentElement();
        if (root == null) return null;
        return new ChallengeRec(
                text(root, "challenge_id"),
                tenantUuid,
                text(root, "user_uuid"),
                text(root, "engine"),
                text(root, "destination"),
                text(root, "ip"),
                text(root, "salt"),
                text(root, "code_hash"),
                text(root, "created_at"),
                text(root, "expires_at"),
                parseInt(text(root, "attempts"), 0),
                parseInt(text(root, "max_attempts"), MAX_ATTEMPTS)
        );
    }

    private static ReentrantReadWriteLock lockFor(String tenantUuid) {
        return LOCKS.computeIfAbsent(safeFileToken(tenantUuid), k -> new ReentrantReadWriteLock());
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

    private static String text(Element parent, String childTag) {
        if (parent == null || childTag == null) return "";
        NodeList nl = parent.getElementsByTagName(childTag);
        if (nl.getLength() == 0) return "";
        Node n = nl.item(0);
        return n == null ? "" : safe(n.getTextContent()).trim();
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

    private static Instant parseInstant(String raw) {
        String s = safe(raw).trim();
        if (s.isBlank()) return null;
        try {
            return Instant.parse(s);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static int parseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(safe(raw).trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String jsonEscape(String raw) {
        StringBuilder sb = new StringBuilder();
        String s = safe(raw);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 32) sb.append(String.format(Locale.ROOT, "\\u%04x", (int) ch));
                    else sb.append(ch);
                }
            }
        }
        return sb.toString();
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
                .replace(">", "&gt;");
    }

    private static String safeFileToken(String s) {
        String t = safe(s).trim();
        if (t.isBlank()) return "";
        return t.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
