package net.familylawandprobate.controversies.integrations.clio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class ClioClient {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final String baseUrl;
    private static final int MAX_RATE_LIMIT_RETRIES = 3;

    public ClioClient(String baseUrl) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
    }

    public OAuthToken exchangeToken(String clientId,
                                    String clientSecret,
                                    String code,
                                    String redirectUri) throws Exception {
        LinkedHashMap<String, String> body = new LinkedHashMap<String, String>();
        body.put("grant_type", "authorization_code");
        body.put("code", safe(code).trim());
        body.put("redirect_uri", safe(redirectUri).trim());
        body.put("client_id", safe(clientId).trim());
        body.put("client_secret", safe(clientSecret).trim());
        String raw = postForm("/oauth/token", body);
        return OAuthToken.fromJson(raw);
    }

    public OAuthToken refreshToken(String clientId,
                                   String clientSecret,
                                   String refreshToken) throws Exception {
        LinkedHashMap<String, String> body = new LinkedHashMap<String, String>();
        body.put("grant_type", "refresh_token");
        body.put("refresh_token", safe(refreshToken).trim());
        body.put("client_id", safe(clientId).trim());
        body.put("client_secret", safe(clientSecret).trim());
        String raw = postForm("/oauth/token", body);
        return OAuthToken.fromJson(raw);
    }

    public List<ClioMatter> listMatters(String accessToken,
                                        String updatedSinceIso,
                                        int page,
                                        int perPage) throws Exception {
        MatterPage out = listMattersPage(accessToken, "", "", updatedSinceIso, perPage);
        return out.items;
    }

    public MatterPage listMattersPage(String accessToken,
                                      String nextPageUrl,
                                      String order,
                                      String updatedSinceIso,
                                      int limit) throws Exception {
        return listMattersPage(accessToken, nextPageUrl, order, updatedSinceIso, limit, "");
    }

    public MatterPage listMattersPage(String accessToken,
                                      String nextPageUrl,
                                      String order,
                                      String updatedSinceIso,
                                      int limit,
                                      String fieldsCsv) throws Exception {
        String json;
        String cursor = safe(nextPageUrl).trim();
        if (!cursor.isBlank()) {
            json = getJsonAbsolute(cursor, accessToken);
        } else {
            LinkedHashMap<String, String> query = new LinkedHashMap<String, String>();
            int boundedLimit = Math.max(1, Math.min(200, limit));
            query.put("limit", String.valueOf(boundedLimit));
            String ord = safe(order).trim();
            if (!ord.isBlank()) query.put("order", ord);
            if (!safe(updatedSinceIso).trim().isBlank()) {
                query.put("updated_since", safe(updatedSinceIso).trim());
            }
            if (!safe(fieldsCsv).trim().isBlank()) {
                query.put("fields", safe(fieldsCsv).trim());
            }
            json = getJson("/api/v4/matters", accessToken, query);
        }
        return new MatterPage(
                ClioMatter.listFromJson(json),
                JsonHelper.pagingNextUrl(json)
        );
    }

    public List<ClioMatter> searchMatters(String accessToken,
                                          String queryText,
                                          String updatedSinceIso,
                                          int page,
                                          int perPage) throws Exception {
        LinkedHashMap<String, String> query = new LinkedHashMap<String, String>();
        query.put("query", safe(queryText).trim());
        query.put("page", String.valueOf(Math.max(1, page)));
        query.put("per_page", String.valueOf(Math.max(1, perPage)));
        if (!safe(updatedSinceIso).trim().isBlank()) {
            query.put("updated_since", safe(updatedSinceIso).trim());
        }
        String json = getJson("/api/v4/matters", accessToken, query);
        return ClioMatter.listFromJson(json);
    }

    public List<ClioContact> listContacts(String accessToken,
                                          int page,
                                          int perPage) throws Exception {
        LinkedHashMap<String, String> query = new LinkedHashMap<String, String>();
        query.put("page", String.valueOf(Math.max(1, page)));
        query.put("per_page", String.valueOf(Math.max(1, perPage)));
        String json = getJson("/api/v4/contacts", accessToken, query);
        return ClioContact.listFromJson(json);
    }

    public List<String> listMatterContactIds(String accessToken,
                                             String matterId,
                                             int page,
                                             int perPage) throws Exception {
        String mid = safe(matterId).trim();
        if (mid.isBlank()) return List.of();

        LinkedHashMap<String, String> query = new LinkedHashMap<String, String>();
        query.put("page", String.valueOf(Math.max(1, page)));
        query.put("per_page", String.valueOf(Math.max(1, perPage)));

        Exception firstFailure = null;
        String[] candidates = new String[] {
                "/api/v4/matters/" + encodePath(mid) + "/contacts",
                "/api/v4/matters/" + encodePath(mid) + "/clients"
        };

        for (String candidate : candidates) {
            try {
                String json = getJson(candidate, accessToken, query);
                return parseContactIdsFromData(json);
            } catch (Exception ex) {
                if (firstFailure == null) firstFailure = ex;
            }
        }

        if (firstFailure != null) throw firstFailure;
        return List.of();
    }

    public String resolveUploadTarget(String accessToken, String matterId) throws Exception {
        String id = safe(matterId).trim();
        if (id.isBlank()) return "";
        String json = getJson("/api/v4/matters/" + encodePath(id), accessToken, Map.of());
        String directId = JsonHelper.stringValue(json, "id");
        if (!directId.isBlank()) return directId;
        return JsonHelper.stringValue(JsonHelper.firstObjectInDataArray(json), "id");
    }

    public List<ClioDocument> listDocumentsForMatter(String accessToken,
                                                     String matterId,
                                                     int page,
                                                     int perPage) throws Exception {
        String mid = safe(matterId).trim();
        if (mid.isBlank()) return List.of();

        LinkedHashMap<String, String> query = new LinkedHashMap<String, String>();
        query.put("page", String.valueOf(Math.max(1, page)));
        query.put("per_page", String.valueOf(Math.max(1, perPage)));
        query.put("matter_id", mid);

        try {
            String json = getJson("/api/v4/documents", accessToken, query);
            return ClioDocument.listFromJson(json);
        } catch (ClioApiException first) {
            if (first.status != 404) throw first;
            try {
                String json = getJson("/api/v4/documents.json", accessToken, query);
                return ClioDocument.listFromJson(json);
            } catch (ClioApiException second) {
                if (second.status != 404) throw second;
            }
        }

        query.remove("matter_id");
        try {
            String fallback = getJson("/api/v4/matters/" + encodePath(mid) + "/documents", accessToken, query);
            return ClioDocument.listFromJson(fallback);
        } catch (ClioApiException ex) {
            if (ex.status != 404) throw ex;
            String fallback = getJson("/api/v4/matters/" + encodePath(mid) + "/documents.json", accessToken, query);
            return ClioDocument.listFromJson(fallback);
        }
    }

    public List<ClioDocumentVersion> listDocumentVersions(String accessToken,
                                                          String documentId,
                                                          int page,
                                                          int perPage) throws Exception {
        String did = safe(documentId).trim();
        if (did.isBlank()) return List.of();

        LinkedHashMap<String, String> query = new LinkedHashMap<String, String>();
        query.put("page", String.valueOf(Math.max(1, page)));
        query.put("per_page", String.valueOf(Math.max(1, perPage)));

        try {
            String json = getJson("/api/v4/documents/" + encodePath(did) + "/versions", accessToken, query);
            return ClioDocumentVersion.listFromJson(json);
        } catch (ClioApiException first) {
            if (first.status != 404) throw first;
            try {
                String json = getJson("/api/v4/documents/" + encodePath(did) + "/versions.json", accessToken, query);
                return ClioDocumentVersion.listFromJson(json);
            } catch (ClioApiException second) {
                if (second.status != 404) throw second;
            }
        }

        query.put("document_id", did);
        try {
            String fallback = getJson("/api/v4/document_versions", accessToken, query);
            return ClioDocumentVersion.listFromJson(fallback);
        } catch (ClioApiException ex) {
            if (ex.status != 404) throw ex;
            String fallback = getJson("/api/v4/document_versions.json", accessToken, query);
            return ClioDocumentVersion.listFromJson(fallback);
        }
    }

    public ClioDocumentVersion getDocumentVersion(String accessToken, String versionId) throws Exception {
        String vid = safe(versionId).trim();
        if (vid.isBlank()) return null;
        String json;
        try {
            json = getJson("/api/v4/document_versions/" + encodePath(vid), accessToken, Map.of());
        } catch (ClioApiException ex) {
            if (ex.status != 404) throw ex;
            json = getJson("/api/v4/document_versions/" + encodePath(vid) + ".json", accessToken, Map.of());
        }
        String obj = JsonHelper.firstObjectInDataArray(json);
        if (obj.isBlank()) obj = json;
        ClioDocumentVersion out = ClioDocumentVersion.fromObjectJson(obj);
        if (safe(out.id).trim().isBlank()) return null;
        return out;
    }

    public DownloadedFile downloadDocumentVersion(String accessToken, String versionId) throws Exception {
        String vid = safe(versionId).trim();
        if (vid.isBlank()) throw new IllegalArgumentException("version id is required");
        String endpoint = buildUrl("/api/v4/document_versions/" + encodePath(vid) + "/download", Map.of());
        HttpURLConnection conn;
        try {
            conn = openDownloadConnection(endpoint, safe(accessToken).trim());
            if (conn.getResponseCode() == 404) {
                String fallbackEndpoint = buildUrl("/api/v4/document_versions/" + encodePath(vid) + "/download.json", Map.of());
                conn = openDownloadConnection(fallbackEndpoint, safe(accessToken).trim());
            }
        } catch (ClioApiException ex) {
            if (ex.status != 404) throw ex;
            String fallbackEndpoint = buildUrl("/api/v4/document_versions/" + encodePath(vid) + "/download.json", Map.of());
            conn = openDownloadConnection(fallbackEndpoint, safe(accessToken).trim());
        }
        DownloadedFile out = readDownloadedFile(conn);
        if (looksLikeJsonDownloadPointer(out)) {
            String payload = new String(out.bytes, StandardCharsets.UTF_8);
            String directUrl = firstNonBlank(
                    JsonHelper.stringValue(payload, "url"),
                    JsonHelper.stringValue(payload, "download_url"),
                    JsonHelper.stringValue(payload, "href")
            );
            if (!directUrl.isBlank()) {
                HttpURLConnection directConn = openDownloadConnection(directUrl, "");
                out = readDownloadedFile(directConn);
            }
        }
        if (out.bytes.length == 0) {
            throw new IllegalStateException("Downloaded Clio document version is empty.");
        }
        return out;
    }

    private static HttpURLConnection openDownloadConnection(String url, String bearerToken) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create(safe(url).trim()).toURL().openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(20_000);
        conn.setReadTimeout(60_000);
        conn.setRequestProperty("Accept", "*/*");
        if (!safe(bearerToken).isBlank()) {
            conn.setRequestProperty("Authorization", "Bearer " + safe(bearerToken));
        }
        conn.setInstanceFollowRedirects(true);
        return conn;
    }

    private static DownloadedFile readDownloadedFile(HttpURLConnection conn) throws Exception {
        int status = conn.getResponseCode();
        if (status < 200 || status >= 300) {
            String body = readBody(conn, false);
            throw new ClioApiException(status, "Clio download failed status=" + status + " body=" + safeErrorBody(body));
        }

        byte[] bytes = new byte[0];
        InputStream stream = conn.getInputStream();
        if (stream != null) {
            try (InputStream in = stream) {
                bytes = in.readAllBytes();
            }
        }
        return new DownloadedFile(
                bytes,
                safe(conn.getContentType()).trim(),
                fileNameFromContentDisposition(conn.getHeaderField("Content-Disposition"))
        );
    }

    private static boolean looksLikeJsonDownloadPointer(DownloadedFile file) {
        if (file == null || file.bytes.length == 0) return false;
        String contentType = safe(file.contentType).toLowerCase(Locale.ROOT);
        String body = new String(file.bytes, StandardCharsets.UTF_8).trim();
        if (body.isBlank()) return false;
        boolean jsonType = contentType.contains("json") || body.startsWith("{");
        if (!jsonType) return false;
        return body.contains("\"url\"") || body.contains("\"download_url\"") || body.contains("\"href\"");
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) {
            String v = safe(value).trim();
            if (!v.isBlank()) return v;
        }
        return "";
    }

    private String getJson(String path, String accessToken, Map<String, String> query) throws Exception {
        String endpoint = buildUrl(path, query);
        return getJsonAbsolute(endpoint, accessToken);
    }

    private String getJsonAbsolute(String endpoint, String accessToken) throws Exception {
        String url = safe(endpoint).trim();
        int attempt = 0;
        while (true) {
            attempt++;
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(20_000);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + safe(accessToken).trim());

            int status = conn.getResponseCode();
            if (status == 429 && attempt <= MAX_RATE_LIMIT_RETRIES) {
                long waitMs = parseRetryAfterMillis(conn.getHeaderField("Retry-After"));
                try {
                    Thread.sleep(waitMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Clio API rate-limit wait interrupted");
                }
                continue;
            }
            return readResponse(conn, status);
        }
    }

    private static long parseRetryAfterMillis(String raw) {
        String v = safe(raw).trim();
        if (v.isBlank()) return 1000L;
        try {
            long sec = Long.parseLong(v);
            if (sec < 1L) sec = 1L;
            if (sec > 60L) sec = 60L;
            return sec * 1000L;
        } catch (Exception ignored) {
            return 1000L;
        }
    }

    private String postForm(String path, Map<String, String> fields) throws Exception {
        String endpoint = buildUrl(path, Map.of());
        HttpURLConnection conn = (HttpURLConnection) URI.create(endpoint).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(20_000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");

        String body = formBody(fields);
        try (OutputStream out = conn.getOutputStream()) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int status = conn.getResponseCode();
        return readResponse(conn, status);
    }

    private static String formBody(Map<String, String> fields) {
        if (fields == null || fields.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Map.Entry<String, String> e : fields.entrySet()) {
            if (e == null) continue;
            if (n++ > 0) sb.append('&');
            sb.append(urlEncode(safe(e.getKey()))).append('=').append(urlEncode(safe(e.getValue())));
        }
        return sb.toString();
    }

    private static String readResponse(HttpURLConnection conn, int status) throws Exception {
        String body = readBody(conn, status >= 200 && status < 300);
        if (status < 200 || status >= 300) {
            throw new ClioApiException(status, "Clio API error status=" + status + " body=" + safeErrorBody(body));
        }
        return body;
    }

    private static String readBody(HttpURLConnection conn, boolean successStream) throws Exception {
        String body = "";
        InputStream stream = successStream ? conn.getInputStream() : conn.getErrorStream();
        if (stream != null) {
            try (InputStream in = stream) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        return body;
    }

    private String buildUrl(String path, Map<String, String> query) {
        StringBuilder sb = new StringBuilder();
        sb.append(baseUrl);
        if (!path.startsWith("/")) sb.append('/');
        sb.append(path);

        List<String> pairs = new ArrayList<String>();
        if (query != null) {
            for (Map.Entry<String, String> e : query.entrySet()) {
                if (e == null) continue;
                String k = safe(e.getKey()).trim();
                String v = safe(e.getValue()).trim();
                if (k.isBlank() || v.isBlank()) continue;
                pairs.add(urlEncode(k) + "=" + urlEncode(v));
            }
        }
        if (!pairs.isEmpty()) {
            sb.append('?');
            for (int i = 0; i < pairs.size(); i++) {
                if (i > 0) sb.append('&');
                sb.append(pairs.get(i));
            }
        }
        return sb.toString();
    }

    private static String normalizeBaseUrl(String raw) {
        String v = safe(raw).trim();
        if (v.isBlank()) return "https://app.clio.com";
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);

        URI uri;
        try {
            uri = URI.create(v);
        } catch (Exception ex) {
            throw new IllegalArgumentException("invalid clio base url", ex);
        }

        String scheme = safe(uri.getScheme()).toLowerCase();
        if (!"https".equals(scheme)) {
            throw new IllegalArgumentException("clio base url must use https");
        }

        String host = safe(uri.getHost()).trim().toLowerCase();
        if (host.isBlank()) {
            throw new IllegalArgumentException("clio base url host is required");
        }

        if (isLocalAddressHost(host)) {
            throw new IllegalArgumentException("clio base url host is not allowed");
        }

        return uri.toString();
    }

    private static boolean isLocalAddressHost(String host) {
        if (host.isBlank()) return true;
        if ("localhost".equals(host) || host.endsWith(".localhost")) return true;

        try {
            InetAddress[] addrs = InetAddress.getAllByName(host);
            for (InetAddress a : addrs) {
                if (a == null) continue;
                if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isSiteLocalAddress()) return true;
            }
        } catch (UnknownHostException ex) {
            // If resolution fails, defer to network stack later; do not block valid hosts due to DNS noise.
            return false;
        }

        return false;
    }

    private static String safeErrorBody(String body) {
        String v = safe(body).replaceAll("[\r\n\t]+", " ").trim();
        if (v.length() > 300) return v.substring(0, 300) + "...";
        return v;
    }

    private static String fileNameFromContentDisposition(String header) {
        String h = safe(header).trim();
        if (h.isBlank()) return "";
        String lower = h.toLowerCase(Locale.ROOT);
        int idx = lower.indexOf("filename=");
        if (idx < 0) return "";
        String tail = h.substring(idx + "filename=".length()).trim();
        if (tail.startsWith("\"")) {
            int end = tail.indexOf('"', 1);
            if (end > 1) return tail.substring(1, end);
        }
        int semi = tail.indexOf(';');
        if (semi >= 0) tail = tail.substring(0, semi);
        return tail.replace("\"", "").trim();
    }

    private static List<String> parseContactIdsFromData(String json) {
        LinkedHashSet<String> ids = new LinkedHashSet<String>();
        try {
            JsonNode root = JSON.readTree(safe(json));
            JsonNode data = root == null ? null : root.path("data");
            if (data == null || !data.isArray()) return List.of();
            for (int i = 0; i < data.size(); i++) {
                JsonNode row = data.get(i);
                if (row == null || row.isMissingNode()) continue;
                collectContactIds(row, ids, 0);
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return new ArrayList<String>(ids);
    }

    private static void collectContactIds(JsonNode node, Set<String> out, int depth) {
        if (node == null || out == null) return;
        if (depth > 5) return;

        if (node.isObject()) {
            String id = text(node, "id");
            if (!id.isBlank() && looksLikeContact(node)) out.add(id);

            JsonNode contact = node.path("contact");
            if (contact != null && contact.isObject()) {
                String nestedId = text(contact, "id");
                if (!nestedId.isBlank()) out.add(nestedId);
            }
            JsonNode client = node.path("client");
            if (client != null && client.isObject()) {
                String nestedId = text(client, "id");
                if (!nestedId.isBlank()) out.add(nestedId);
            }

            node.fields().forEachRemaining(e -> collectContactIds(e.getValue(), out, depth + 1));
            return;
        }

        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                collectContactIds(node.get(i), out, depth + 1);
            }
        }
    }

    private static boolean looksLikeContact(JsonNode node) {
        if (node == null || !node.isObject()) return false;
        return hasText(node, "name")
                || hasText(node, "display_name")
                || hasText(node, "first_name")
                || node.has("email_addresses")
                || node.has("phone_numbers");
    }

    private static boolean hasText(JsonNode node, String key) {
        String v = text(node, key);
        return !v.isBlank();
    }

    private static String text(JsonNode node, String key) {
        if (node == null || key == null || key.isBlank()) return "";
        JsonNode value = node.path(key);
        if (value == null || value.isMissingNode() || value.isNull()) return "";
        if (value.isTextual()) return safe(value.asText()).trim();
        if (value.isNumber() || value.isBoolean()) return safe(value.asText()).trim();
        return "";
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(safe(s), StandardCharsets.UTF_8);
    }

    private static String encodePath(String s) {
        return safe(s).replaceAll("[^A-Za-z0-9._~-]", "_");
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    public static final class ClioApiException extends IllegalStateException {
        public final int status;

        public ClioApiException(int status, String message) {
            super(safe(message));
            this.status = status;
        }
    }

    public static final class DownloadedFile {
        public final byte[] bytes;
        public final String contentType;
        public final String fileName;

        public DownloadedFile(byte[] bytes, String contentType, String fileName) {
            this.bytes = bytes == null ? new byte[0] : bytes;
            this.contentType = safe(contentType);
            this.fileName = safe(fileName);
        }
    }

    public static final class MatterPage {
        public final List<ClioMatter> items;
        public final String nextPageUrl;

        public MatterPage(List<ClioMatter> items, String nextPageUrl) {
            this.items = items == null ? List.of() : items;
            this.nextPageUrl = safe(nextPageUrl).trim();
        }
    }

    public static final class OAuthToken {
        public final String accessToken;
        public final String refreshToken;
        public final String tokenType;
        public final String scope;
        public final String createdAt;
        public final long expiresInSeconds;

        public OAuthToken(String accessToken,
                          String refreshToken,
                          String tokenType,
                          String scope,
                          String createdAt,
                          long expiresInSeconds) {
            this.accessToken = safe(accessToken);
            this.refreshToken = safe(refreshToken);
            this.tokenType = safe(tokenType);
            this.scope = safe(scope);
            this.createdAt = safe(createdAt).isBlank() ? Instant.now().toString() : safe(createdAt);
            this.expiresInSeconds = Math.max(0L, expiresInSeconds);
        }

        public static OAuthToken fromJson(String json) {
            String accessToken = JsonHelper.stringValue(json, "access_token");
            String refreshToken = JsonHelper.stringValue(json, "refresh_token");
            String tokenType = JsonHelper.stringValue(json, "token_type");
            String scope = JsonHelper.stringValue(json, "scope");
            String createdAt = JsonHelper.stringValue(json, "created_at");
            long expiresIn = JsonHelper.longValue(json, "expires_in");
            return new OAuthToken(accessToken, refreshToken, tokenType, scope, createdAt, expiresIn);
        }
    }
}
