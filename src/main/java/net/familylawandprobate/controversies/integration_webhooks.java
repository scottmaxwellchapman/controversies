package net.familylawandprobate.controversies;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * integration_webhooks
 *
 * Generic webhook endpoint registry for integration marketplace expansion.
 */
public final class integration_webhooks {

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final ConcurrentHashMap<String, ReentrantReadWriteLock> LOCKS = new ConcurrentHashMap<String, ReentrantReadWriteLock>();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    public static integration_webhooks defaultStore() {
        return new integration_webhooks();
    }

    public static final class EndpointInput {
        public String label = "";
        public String url = "";
        public String eventFilterCsv = "*";
        public String signingSecret = "";
        public boolean enabled = true;
    }

    public static final class EndpointRec {
        public final String webhookUuid;
        public final String label;
        public final String url;
        public final String eventFilterCsv;
        public final String signingSecretMasked;
        public final boolean enabled;
        public final String createdAt;
        public final String updatedAt;
        public final String lastAttemptAt;
        public final String lastSuccessAt;
        public final String lastError;
        public final int lastStatusCode;

        public EndpointRec(String webhookUuid,
                           String label,
                           String url,
                           String eventFilterCsv,
                           String signingSecretMasked,
                           boolean enabled,
                           String createdAt,
                           String updatedAt,
                           String lastAttemptAt,
                           String lastSuccessAt,
                           String lastError,
                           int lastStatusCode) {
            this.webhookUuid = safe(webhookUuid).trim();
            this.label = safe(label).trim();
            this.url = safe(url).trim();
            this.eventFilterCsv = normalizeCsv(eventFilterCsv);
            this.signingSecretMasked = safe(signingSecretMasked);
            this.enabled = enabled;
            this.createdAt = safe(createdAt).trim();
            this.updatedAt = safe(updatedAt).trim();
            this.lastAttemptAt = safe(lastAttemptAt).trim();
            this.lastSuccessAt = safe(lastSuccessAt).trim();
            this.lastError = safe(lastError);
            this.lastStatusCode = lastStatusCode;
        }
    }

    public static final class DeliveryRec {
        public final String deliveryUuid;
        public final String webhookUuid;
        public final String eventType;
        public final boolean success;
        public final int statusCode;
        public final String requestBodySha256;
        public final String error;
        public final String attemptedAt;

        public DeliveryRec(String deliveryUuid,
                           String webhookUuid,
                           String eventType,
                           boolean success,
                           int statusCode,
                           String requestBodySha256,
                           String error,
                           String attemptedAt) {
            this.deliveryUuid = safe(deliveryUuid).trim();
            this.webhookUuid = safe(webhookUuid).trim();
            this.eventType = safe(eventType).trim();
            this.success = success;
            this.statusCode = statusCode;
            this.requestBodySha256 = safe(requestBodySha256).trim();
            this.error = safe(error);
            this.attemptedAt = safe(attemptedAt).trim();
        }
    }

    public static final class DispatchResult {
        public final int endpointCount;
        public final int attemptedCount;
        public final int successCount;
        public final int failureCount;
        public final List<DeliveryRec> deliveries;

        public DispatchResult(int endpointCount,
                              int attemptedCount,
                              int successCount,
                              int failureCount,
                              List<DeliveryRec> deliveries) {
            this.endpointCount = endpointCount;
            this.attemptedCount = attemptedCount;
            this.successCount = successCount;
            this.failureCount = failureCount;
            this.deliveries = deliveries == null ? List.of() : List.copyOf(deliveries);
        }
    }

    private static final class FileRec {
        public String updated_at = "";
        public ArrayList<StoredEndpointRec> endpoints = new ArrayList<StoredEndpointRec>();
        public ArrayList<StoredDeliveryRec> deliveries = new ArrayList<StoredDeliveryRec>();
    }

    private static final class StoredEndpointRec {
        public String webhook_uuid = "";
        public String label = "";
        public String url = "";
        public String event_filter_csv = "*";
        public String signing_secret = "";
        public boolean enabled = true;
        public String created_at = "";
        public String updated_at = "";
        public String last_attempt_at = "";
        public String last_success_at = "";
        public String last_error = "";
        public int last_status_code = 0;
    }

    private static final class StoredDeliveryRec {
        public String delivery_uuid = "";
        public String webhook_uuid = "";
        public String event_type = "";
        public boolean success = false;
        public int status_code = 0;
        public String request_body_sha256 = "";
        public String error = "";
        public String attempted_at = "";
    }

    public List<EndpointRec> listEndpoints(String tenantUuid) throws Exception {
        String tu = safeToken(tenantUuid);
        if (tu.isBlank()) return List.of();
        ReentrantReadWriteLock lock = lockFor(tu);
        lock.readLock().lock();
        try {
            ensureLocked(tu);
            FileRec file = readLocked(tu);
            ArrayList<EndpointRec> out = new ArrayList<EndpointRec>();
            for (StoredEndpointRec row : file.endpoints) {
                if (row == null) continue;
                out.add(toEndpoint(row));
            }
            out.sort((a, b) -> compareByIsoThenUuid(a == null ? "" : a.updatedAt, b == null ? "" : b.updatedAt,
                    a == null ? "" : a.webhookUuid, b == null ? "" : b.webhookUuid));
            return out;
        } finally {
            lock.readLock().unlock();
        }
    }

    public EndpointRec getEndpoint(String tenantUuid, String webhookUuid) throws Exception {
        String tu = safeToken(tenantUuid);
        String id = safe(webhookUuid).trim();
        if (tu.isBlank() || id.isBlank()) return null;
        ReentrantReadWriteLock lock = lockFor(tu);
        lock.readLock().lock();
        try {
            ensureLocked(tu);
            FileRec file = readLocked(tu);
            StoredEndpointRec row = findEndpoint(file, id);
            return row == null ? null : toEndpoint(row);
        } finally {
            lock.readLock().unlock();
        }
    }

    public EndpointRec createEndpoint(String tenantUuid, EndpointInput in) throws Exception {
        String tu = safeToken(tenantUuid);
        if (tu.isBlank()) throw new IllegalArgumentException("tenantUuid required");
        EndpointInput input = in == null ? new EndpointInput() : in;

        String url = safe(input.url).trim();
        if (url.isBlank()) throw new IllegalArgumentException("url is required.");
        validateUri(url);

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensureLocked(tu);
            FileRec file = readLocked(tu);
            if (file.endpoints == null) file.endpoints = new ArrayList<StoredEndpointRec>();
            String now = app_clock.now().toString();

            StoredEndpointRec row = new StoredEndpointRec();
            row.webhook_uuid = "wh_" + UUID.randomUUID().toString().replace("-", "");
            row.label = safe(input.label).trim().isBlank() ? "Webhook Endpoint" : safe(input.label).trim();
            row.url = url;
            row.event_filter_csv = normalizeCsv(input.eventFilterCsv);
            if (safe(row.event_filter_csv).trim().isBlank()) row.event_filter_csv = "*";
            row.signing_secret = safe(input.signingSecret).trim().isBlank() ? randomSecret() : safe(input.signingSecret).trim();
            row.enabled = input.enabled;
            row.created_at = now;
            row.updated_at = now;
            file.endpoints.add(row);
            file.updated_at = now;
            writeLocked(tu, file);
            return toEndpoint(row);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public EndpointRec updateEndpoint(String tenantUuid, String webhookUuid, EndpointInput in) throws Exception {
        String tu = safeToken(tenantUuid);
        String id = safe(webhookUuid).trim();
        if (tu.isBlank() || id.isBlank()) throw new IllegalArgumentException("tenantUuid and webhookUuid are required.");
        EndpointInput input = in == null ? new EndpointInput() : in;
        String url = safe(input.url).trim();
        if (url.isBlank()) throw new IllegalArgumentException("url is required.");
        validateUri(url);

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensureLocked(tu);
            FileRec file = readLocked(tu);
            StoredEndpointRec row = findEndpoint(file, id);
            if (row == null) throw new IllegalArgumentException("Webhook endpoint not found.");

            row.label = safe(input.label).trim().isBlank() ? row.label : safe(input.label).trim();
            row.url = url;
            row.event_filter_csv = normalizeCsv(input.eventFilterCsv);
            if (safe(row.event_filter_csv).trim().isBlank()) row.event_filter_csv = "*";
            if (!safe(input.signingSecret).trim().isBlank()) {
                row.signing_secret = safe(input.signingSecret).trim();
            }
            row.enabled = input.enabled;
            row.updated_at = app_clock.now().toString();
            file.updated_at = row.updated_at;
            writeLocked(tu, file);
            return toEndpoint(row);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean deleteEndpoint(String tenantUuid, String webhookUuid) throws Exception {
        String tu = safeToken(tenantUuid);
        String id = safe(webhookUuid).trim();
        if (tu.isBlank() || id.isBlank()) return false;
        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensureLocked(tu);
            FileRec file = readLocked(tu);
            if (file.endpoints == null || file.endpoints.isEmpty()) return false;
            boolean removed = file.endpoints.removeIf(r -> r != null && id.equals(safe(r.webhook_uuid).trim()));
            if (!removed) return false;
            file.updated_at = app_clock.now().toString();
            writeLocked(tu, file);
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<DeliveryRec> listDeliveries(String tenantUuid, int limit) throws Exception {
        String tu = safeToken(tenantUuid);
        if (tu.isBlank()) return List.of();
        int lim = Math.max(1, Math.min(1000, limit <= 0 ? 100 : limit));
        ReentrantReadWriteLock lock = lockFor(tu);
        lock.readLock().lock();
        try {
            ensureLocked(tu);
            FileRec file = readLocked(tu);
            ArrayList<DeliveryRec> out = new ArrayList<DeliveryRec>();
            for (StoredDeliveryRec row : file.deliveries) {
                if (row == null) continue;
                out.add(toDelivery(row));
            }
            out.sort((a, b) -> compareByIsoThenUuid(a == null ? "" : a.attemptedAt, b == null ? "" : b.attemptedAt,
                    a == null ? "" : a.deliveryUuid, b == null ? "" : b.deliveryUuid));
            if (out.size() <= lim) return out;
            return new ArrayList<DeliveryRec>(out.subList(0, lim));
        } finally {
            lock.readLock().unlock();
        }
    }

    public DispatchResult dispatchEvent(String tenantUuid, String eventType, java.util.Map<String, Object> payload) throws Exception {
        String tu = safeToken(tenantUuid);
        String evt = safe(eventType).trim().toLowerCase(Locale.ROOT);
        if (tu.isBlank() || evt.isBlank()) throw new IllegalArgumentException("tenantUuid and eventType are required.");

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensureLocked(tu);
            FileRec file = readLocked(tu);
            ArrayList<StoredEndpointRec> endpoints = file.endpoints == null ? new ArrayList<StoredEndpointRec>() : file.endpoints;
            int endpointCount = endpoints.size();

            ArrayList<DeliveryRec> deliveries = new ArrayList<DeliveryRec>();
            int attempted = 0;
            int success = 0;
            int failure = 0;
            String now = app_clock.now().toString();

            for (StoredEndpointRec endpoint : endpoints) {
                if (endpoint == null) continue;
                if (!endpoint.enabled) continue;
                if (!eventMatches(endpoint.event_filter_csv, evt)) continue;

                attempted++;
                DeliveryRec delivery;
                try {
                    delivery = dispatchOne(tu, endpoint, evt, payload == null ? new LinkedHashMap<String, Object>() : new LinkedHashMap<String, Object>(payload), now);
                } catch (Exception ex) {
                    delivery = new DeliveryRec(
                            "whd_" + UUID.randomUUID().toString().replace("-", ""),
                            safe(endpoint.webhook_uuid).trim(),
                            evt,
                            false,
                            0,
                            "",
                            safe(ex.getMessage()),
                            app_clock.now().toString()
                    );
                }

                deliveries.add(delivery);
                StoredDeliveryRec row = fromDelivery(delivery);
                if (file.deliveries == null) file.deliveries = new ArrayList<StoredDeliveryRec>();
                file.deliveries.add(row);
                if (file.deliveries.size() > 2000) {
                    file.deliveries = new ArrayList<StoredDeliveryRec>(file.deliveries.subList(file.deliveries.size() - 2000, file.deliveries.size()));
                }

                endpoint.last_attempt_at = safe(delivery.attemptedAt);
                endpoint.last_status_code = delivery.statusCode;
                endpoint.last_error = safe(delivery.error);
                if (delivery.success) {
                    success++;
                    endpoint.last_success_at = safe(delivery.attemptedAt);
                } else {
                    failure++;
                }
                endpoint.updated_at = safe(delivery.attemptedAt);
            }

            file.updated_at = app_clock.now().toString();
            writeLocked(tu, file);
            return new DispatchResult(endpointCount, attempted, success, failure, deliveries);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private static DeliveryRec dispatchOne(String tenantToken,
                                           StoredEndpointRec endpoint,
                                           String eventType,
                                           java.util.Map<String, Object> payload,
                                           String emittedAt) throws Exception {
        LinkedHashMap<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("event_type", eventType);
        body.put("tenant_uuid", tenantToken);
        body.put("emitted_at", safe(emittedAt).trim().isBlank() ? app_clock.now().toString() : safe(emittedAt).trim());
        body.put("payload", payload == null ? new LinkedHashMap<String, Object>() : payload);
        byte[] jsonBytes = JSON.writeValueAsBytes(body);
        String timestamp = app_clock.now().toString();
        String signature = signPayload(safe(endpoint.signing_secret), jsonBytes, timestamp);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(safe(endpoint.url).trim()))
                .timeout(Duration.ofSeconds(12))
                .header("Content-Type", "application/json")
                .header("X-Controversies-Event", eventType)
                .header("X-Controversies-Timestamp", timestamp)
                .header("X-Controversies-Signature", signature)
                .POST(HttpRequest.BodyPublishers.ofByteArray(jsonBytes))
                .build();

        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        int status = resp == null ? 0 : resp.statusCode();
        boolean ok = status >= 200 && status < 300;
        String error = ok ? "" : ("HTTP " + status + ": " + safe(resp == null ? "" : resp.body()));
        return new DeliveryRec(
                "whd_" + UUID.randomUUID().toString().replace("-", ""),
                safe(endpoint.webhook_uuid).trim(),
                eventType,
                ok,
                status,
                sha256Hex(jsonBytes),
                error,
                app_clock.now().toString()
        );
    }

    private static boolean eventMatches(String eventFilterCsv, String eventType) {
        String evt = safe(eventType).trim().toLowerCase(Locale.ROOT);
        if (evt.isBlank()) return false;
        String csv = normalizeCsv(eventFilterCsv);
        if (csv.isBlank() || "*".equals(csv)) return true;
        for (String token : csv.split(",")) {
            String t = safe(token).trim().toLowerCase(Locale.ROOT);
            if (t.isBlank()) continue;
            if ("*".equals(t) || evt.equals(t)) return true;
            if (t.endsWith(".*")) {
                String prefix = t.substring(0, t.length() - 1);
                if (evt.startsWith(prefix)) return true;
            }
        }
        return false;
    }

    private static String signPayload(String secret, byte[] body, String timestamp) {
        String sec = safe(secret).trim();
        if (sec.isBlank()) return "";
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(sec.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            if (!safe(timestamp).trim().isBlank()) {
                mac.update(safe(timestamp).trim().getBytes(StandardCharsets.UTF_8));
                mac.update((byte) '.');
            }
            mac.update(body == null ? new byte[0] : body);
            return "sha256=" + hex(mac.doFinal());
        } catch (Exception ex) {
            return "";
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return hex(md.digest(bytes == null ? new byte[0] : bytes));
        } catch (Exception ex) {
            return "";
        }
    }

    private static String hex(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "";
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format(Locale.ROOT, "%02x", b));
        }
        return sb.toString();
    }

    private static String randomSecret() {
        return "whsec_" + UUID.randomUUID().toString().replace("-", "");
    }

    private static void validateUri(String rawUrl) {
        String url = safe(rawUrl).trim();
        try {
            URI uri = URI.create(url);
            String scheme = safe(uri.getScheme()).toLowerCase(Locale.ROOT);
            if (!"https".equals(scheme) && !"http".equals(scheme)) {
                throw new IllegalArgumentException("Webhook URL must use http or https.");
            }
            if (safe(uri.getHost()).trim().isBlank()) {
                throw new IllegalArgumentException("Webhook URL host is required.");
            }
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid webhook URL.");
        }
    }

    private static EndpointRec toEndpoint(StoredEndpointRec row) {
        if (row == null) return null;
        String masked = safe(row.signing_secret).trim().isBlank()
                ? ""
                : "********" + safe(row.signing_secret).trim().substring(Math.max(0, safe(row.signing_secret).trim().length() - 4));
        return new EndpointRec(
                safe(row.webhook_uuid),
                safe(row.label),
                safe(row.url),
                safe(row.event_filter_csv),
                masked,
                row.enabled,
                safe(row.created_at),
                safe(row.updated_at),
                safe(row.last_attempt_at),
                safe(row.last_success_at),
                safe(row.last_error),
                row.last_status_code
        );
    }

    private static DeliveryRec toDelivery(StoredDeliveryRec row) {
        if (row == null) return null;
        return new DeliveryRec(
                safe(row.delivery_uuid),
                safe(row.webhook_uuid),
                safe(row.event_type),
                row.success,
                row.status_code,
                safe(row.request_body_sha256),
                safe(row.error),
                safe(row.attempted_at)
        );
    }

    private static StoredDeliveryRec fromDelivery(DeliveryRec rec) {
        StoredDeliveryRec row = new StoredDeliveryRec();
        if (rec == null) return row;
        row.delivery_uuid = safe(rec.deliveryUuid);
        row.webhook_uuid = safe(rec.webhookUuid);
        row.event_type = safe(rec.eventType);
        row.success = rec.success;
        row.status_code = rec.statusCode;
        row.request_body_sha256 = safe(rec.requestBodySha256);
        row.error = safe(rec.error);
        row.attempted_at = safe(rec.attemptedAt);
        return row;
    }

    private static StoredEndpointRec findEndpoint(FileRec file, String webhookUuid) {
        if (file == null || file.endpoints == null) return null;
        String id = safe(webhookUuid).trim();
        if (id.isBlank()) return null;
        for (StoredEndpointRec row : file.endpoints) {
            if (row == null) continue;
            if (id.equals(safe(row.webhook_uuid).trim())) return row;
        }
        return null;
    }

    private static String normalizeCsv(String raw) {
        String text = safe(raw).trim();
        if (text.isBlank()) return "";
        LinkedHashSet<String> out = new LinkedHashSet<String>();
        for (String part : text.split(",")) {
            String token = safe(part).trim().toLowerCase(Locale.ROOT);
            if (token.isBlank()) continue;
            out.add(token);
        }
        return String.join(",", out);
    }

    private static String safeToken(String tenantUuid) {
        String tu = safe(tenantUuid).trim();
        if (tu.isBlank()) return "";
        return tu.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static ReentrantReadWriteLock lockFor(String tenantToken) {
        return LOCKS.computeIfAbsent(safeToken(tenantToken), k -> new ReentrantReadWriteLock());
    }

    private static Path storePath(String tenantToken) {
        return Paths.get("data", "tenants", safeToken(tenantToken), "integrations", "webhooks.json").toAbsolutePath();
    }

    private static void ensureLocked(String tenantToken) throws Exception {
        Path file = storePath(tenantToken);
        Files.createDirectories(file.getParent());
        if (Files.exists(file)) return;
        FileRec empty = new FileRec();
        empty.updated_at = app_clock.now().toString();
        writeJsonAtomic(file, empty);
    }

    private static FileRec readLocked(String tenantToken) throws Exception {
        Path file = storePath(tenantToken);
        if (!Files.exists(file)) return new FileRec();
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length == 0) return new FileRec();
        try {
            FileRec rec = JSON.readValue(bytes, FileRec.class);
            if (rec == null) rec = new FileRec();
            if (rec.endpoints == null) rec.endpoints = new ArrayList<StoredEndpointRec>();
            if (rec.deliveries == null) rec.deliveries = new ArrayList<StoredDeliveryRec>();
            return rec;
        } catch (Exception ignored) {
            return new FileRec();
        }
    }

    private static void writeLocked(String tenantToken, FileRec file) throws Exception {
        if (file == null) file = new FileRec();
        if (file.endpoints == null) file.endpoints = new ArrayList<StoredEndpointRec>();
        if (file.deliveries == null) file.deliveries = new ArrayList<StoredDeliveryRec>();
        if (safe(file.updated_at).trim().isBlank()) file.updated_at = app_clock.now().toString();
        writeJsonAtomic(storePath(tenantToken), file);
    }

    private static void writeJsonAtomic(Path file, FileRec rec) throws Exception {
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
        byte[] json = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(rec == null ? new FileRec() : rec);
        Files.write(tmp, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception ex) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static int compareByIsoThenUuid(String isoA, String isoB, String uuidA, String uuidB) {
        String a = safe(isoA).trim();
        String b = safe(isoB).trim();
        int cmp = b.compareTo(a);
        if (cmp != 0) return cmp;
        return safe(uuidB).trim().compareTo(safe(uuidA).trim());
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
