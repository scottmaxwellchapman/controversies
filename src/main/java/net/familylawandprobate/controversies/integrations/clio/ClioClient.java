package net.familylawandprobate.controversies.integrations.clio;

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
import java.util.List;
import java.util.Map;

public final class ClioClient {

    private final String baseUrl;

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
        LinkedHashMap<String, String> query = new LinkedHashMap<String, String>();
        query.put("page", String.valueOf(Math.max(1, page)));
        query.put("per_page", String.valueOf(Math.max(1, perPage)));
        if (!safe(updatedSinceIso).trim().isBlank()) {
            query.put("updated_since", safe(updatedSinceIso).trim());
        }
        String json = getJson("/api/v4/matters", accessToken, query);
        return ClioMatter.listFromJson(json);
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

    public String resolveUploadTarget(String accessToken, String matterId) throws Exception {
        String id = safe(matterId).trim();
        if (id.isBlank()) return "";
        String json = getJson("/api/v4/matters/" + encodePath(id), accessToken, Map.of());
        String directId = JsonHelper.stringValue(json, "id");
        if (!directId.isBlank()) return directId;
        return JsonHelper.stringValue(JsonHelper.firstObjectInDataArray(json), "id");
    }

    private String getJson(String path, String accessToken, Map<String, String> query) throws Exception {
        String endpoint = buildUrl(path, query);
        HttpURLConnection conn = (HttpURLConnection) URI.create(endpoint).toURL().openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(20_000);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + safe(accessToken).trim());
        return readResponse(conn);
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
        return readResponse(conn);
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

    private static String readResponse(HttpURLConnection conn) throws Exception {
        int status = conn.getResponseCode();
        String body = "";
        InputStream stream = status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream();
        if (stream != null) {
            try (InputStream in = stream) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("Clio API error status=" + status + " body=" + safeErrorBody(body));
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

    private static String urlEncode(String s) {
        return URLEncoder.encode(safe(s), StandardCharsets.UTF_8);
    }

    private static String encodePath(String s) {
        return safe(s).replaceAll("[^A-Za-z0-9._~-]", "_");
    }

    private static String safe(String s) {
        return s == null ? "" : s;
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
