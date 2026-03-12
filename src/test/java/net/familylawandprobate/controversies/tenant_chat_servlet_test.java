package net.familylawandprobate.controversies;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.Part;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class tenant_chat_servlet_test {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ArrayList<Path> cleanupRoots = new ArrayList<Path>();

    @AfterEach
    void cleanup() throws Exception {
        for (Path root : cleanupRoots) {
            deleteRecursively(root);
        }
    }

    @Test
    void chat_send_messages_upload_and_download_work() throws Exception {
        String tenantUuid = "tenant-chat-test-" + UUID.randomUUID();
        String userUuid = "user-" + UUID.randomUUID();
        String userEmail = "chat-user+" + UUID.randomUUID() + "@example.test";
        cleanupRoots.add(Paths.get("data", "tenants", tenantUuid).toAbsolutePath());

        HttpSession session = mockSession(
                Map.of(
                        "tenant.uuid", tenantUuid,
                        "user.uuid", userUuid,
                        "user.email", userEmail
                )
        );

        tenant_chat_servlet servlet = new tenant_chat_servlet();

        MockHttpServletResponse sendResp = new MockHttpServletResponse();
        servlet.doPost(
                mockRequest(
                        session,
                        "POST",
                        "/tenant_login.jsp",
                        "application/x-www-form-urlencoded; charset=UTF-8",
                        Map.of("action", "send", "body", "Hello **team** 😀"),
                        List.of(),
                        List.of()
                ),
                sendResp.response
        );
        assertEquals(201, sendResp.status);
        LinkedHashMap<String, Object> sendJson = parseJson(sendResp.bodyBytes());
        assertTrue(Boolean.parseBoolean(String.valueOf(sendJson.get("ok"))));
        Map<?, ?> sentMessage = asMap(sendJson.get("message"));
        assertEquals("Hello **team** 😀", safe(String.valueOf(sentMessage.get("body"))));

        byte[] uploadedBytes = "evidence-payload-123".getBytes(StandardCharsets.UTF_8);
        MockHttpServletResponse uploadResp = new MockHttpServletResponse();
        servlet.doPost(
                mockRequest(
                        session,
                        "POST",
                        "/tenant_login.jsp",
                        "multipart/form-data; boundary=----x",
                        Map.of("action", "upload"),
                        List.of(
                                mockPart("body", "", "text/plain", "Attached file".getBytes(StandardCharsets.UTF_8)),
                                mockPart("files", "evidence.txt", "text/plain", uploadedBytes)
                        ),
                        List.of()
                ),
                uploadResp.response
        );
        assertEquals(201, uploadResp.status);
        LinkedHashMap<String, Object> uploadJson = parseJson(uploadResp.bodyBytes());
        assertTrue(Boolean.parseBoolean(String.valueOf(uploadJson.get("ok"))));
        assertEquals(1, asInt(uploadJson.get("uploaded_count")));
        Map<?, ?> uploadMessage = asMap(uploadJson.get("message"));
        List<?> uploadAttachments = asList(uploadMessage.get("attachments"));
        assertEquals(1, uploadAttachments.size());
        Map<?, ?> attachment = asMap(uploadAttachments.get(0));
        String attachmentUuid = safe(String.valueOf(attachment.get("attachment_uuid")));
        assertFalse(attachmentUuid.isBlank());

        MockHttpServletResponse listResp = new MockHttpServletResponse();
        servlet.doGet(
                mockRequest(
                        session,
                        "GET",
                        "/tenant_login.jsp",
                        "",
                        Map.of("action", "messages", "limit", "50"),
                        List.of(),
                        List.of()
                ),
                listResp.response
        );
        assertEquals(200, listResp.status);
        LinkedHashMap<String, Object> listJson = parseJson(listResp.bodyBytes());
        assertTrue(Boolean.parseBoolean(String.valueOf(listJson.get("ok"))));
        List<?> messages = asList(listJson.get("messages"));
        assertTrue(messages.size() >= 2);

        boolean foundTextMessage = false;
        boolean foundUploadMessage = false;
        for (Object raw : messages) {
            Map<?, ?> row = asMap(raw);
            if ("Hello **team** 😀".equals(safe(String.valueOf(row.get("body"))))) {
                foundTextMessage = true;
            }
            if ("Attached file".equals(safe(String.valueOf(row.get("body"))))) {
                List<?> rows = asList(row.get("attachments"));
                if (!rows.isEmpty()) {
                    Map<?, ?> f = asMap(rows.get(0));
                    if ("evidence.txt".equals(safe(String.valueOf(f.get("file_name"))))) {
                        foundUploadMessage = true;
                    }
                }
            }
        }
        assertTrue(foundTextMessage);
        assertTrue(foundUploadMessage);

        MockHttpServletResponse downloadResp = new MockHttpServletResponse();
        servlet.doGet(
                mockRequest(
                        session,
                        "GET",
                        "/tenant_login.jsp",
                        "",
                        Map.of("action", "download", "attachment_uuid", attachmentUuid),
                        List.of(),
                        List.of()
                ),
                downloadResp.response
        );
        assertEquals(200, downloadResp.status);
        assertEquals("text/plain", safe(downloadResp.contentType));
        assertTrue(safe(downloadResp.headers.get("Content-Disposition")).contains("evidence.txt"));
        assertArrayEquals(uploadedBytes, downloadResp.bodyBytes());

        List<activity_log.LogEntry> logs = activity_log.defaultStore().recent(tenantUuid, 100);
        int messageSentCount = 0;
        boolean attachmentUploaded = false;
        boolean attachmentDownloaded = false;
        for (activity_log.LogEntry row : logs) {
            if (row == null) continue;
            String action = safe(row.action);
            if ("tenant_chat.message.sent".equals(action)) messageSentCount++;
            if ("tenant_chat.attachment.uploaded".equals(action)) attachmentUploaded = true;
            if ("tenant_chat.attachment.downloaded".equals(action)) attachmentDownloaded = true;
        }
        assertTrue(messageSentCount >= 2);
        assertTrue(attachmentUploaded);
        assertTrue(attachmentDownloaded);
    }

    @SuppressWarnings("unchecked")
    private static LinkedHashMap<String, Object> parseJson(byte[] bytes) throws Exception {
        return JSON.readValue(bytes, LinkedHashMap.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<?, ?> asMap(Object raw) {
        if (raw instanceof Map<?, ?> m) return m;
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<?> asList(Object raw) {
        if (raw instanceof List<?> xs) return xs;
        return List.of();
    }

    private static int asInt(Object raw) {
        if (raw instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(raw));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static HttpSession mockSession(Map<String, String> initialAttrs) {
        LinkedHashMap<String, Object> attrs = new LinkedHashMap<String, Object>();
        if (initialAttrs != null) attrs.putAll(initialAttrs);
        String sessionId = "sess-" + UUID.randomUUID();

        return (HttpSession) Proxy.newProxyInstance(
                tenant_chat_servlet_test.class.getClassLoader(),
                new Class<?>[]{HttpSession.class},
                (proxy, method, args) -> {
                    String name = method == null ? "" : safe(method.getName());
                    if ("getAttribute".equals(name)) {
                        String key = (args == null || args.length == 0) ? "" : safe(String.valueOf(args[0]));
                        return attrs.get(key);
                    }
                    if ("setAttribute".equals(name)) {
                        if (args != null && args.length >= 2) attrs.put(safe(String.valueOf(args[0])), args[1]);
                        return null;
                    }
                    if ("removeAttribute".equals(name)) {
                        if (args != null && args.length >= 1) attrs.remove(safe(String.valueOf(args[0])));
                        return null;
                    }
                    if ("getId".equals(name)) return sessionId;
                    if ("invalidate".equals(name)) {
                        attrs.clear();
                        return null;
                    }
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    if (rt == long.class) return 0L;
                    if (rt == short.class) return (short) 0;
                    if (rt == byte.class) return (byte) 0;
                    if (rt == float.class) return 0f;
                    if (rt == double.class) return 0d;
                    if (rt == char.class) return (char) 0;
                    return null;
                }
        );
    }

    private static HttpServletRequest mockRequest(HttpSession session,
                                                  String method,
                                                  String requestUri,
                                                  String contentType,
                                                  Map<String, String> params,
                                                  Collection<Part> parts,
                                                  List<Cookie> cookies) {
        LinkedHashMap<String, String> safeParams = new LinkedHashMap<String, String>();
        if (params != null) safeParams.putAll(params);
        ArrayList<Part> safeParts = new ArrayList<Part>();
        if (parts != null) safeParts.addAll(parts);
        Cookie[] cookieArray = cookies == null ? new Cookie[0] : cookies.toArray(new Cookie[0]);

        return (HttpServletRequest) Proxy.newProxyInstance(
                tenant_chat_servlet_test.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class},
                (proxy, m, args) -> {
                    String name = m == null ? "" : safe(m.getName());
                    if ("getSession".equals(name)) {
                        if (args != null && args.length == 1 && args[0] instanceof Boolean b && !b) return session;
                        return session;
                    }
                    if ("getParameter".equals(name)) {
                        String key = (args == null || args.length == 0) ? "" : safe(String.valueOf(args[0]));
                        return safeParams.get(key);
                    }
                    if ("getParameterMap".equals(name)) {
                        LinkedHashMap<String, String[]> out = new LinkedHashMap<String, String[]>();
                        for (Map.Entry<String, String> e : safeParams.entrySet()) {
                            if (e == null) continue;
                            String key = safe(e.getKey());
                            if (key.isBlank()) continue;
                            out.put(key, new String[]{safe(e.getValue())});
                        }
                        return out;
                    }
                    if ("getParts".equals(name)) return safeParts;
                    if ("getPart".equals(name)) {
                        String wanted = (args == null || args.length == 0) ? "" : safe(String.valueOf(args[0]));
                        for (Part p : safeParts) {
                            if (p == null) continue;
                            if (wanted.equals(safe(p.getName()))) return p;
                        }
                        return null;
                    }
                    if ("getMethod".equals(name)) return safe(method);
                    if ("getRequestURI".equals(name)) return safe(requestUri);
                    if ("getContextPath".equals(name)) return "";
                    if ("getContentType".equals(name)) return safe(contentType);
                    if ("getQueryString".equals(name)) return "";
                    if ("getRemoteAddr".equals(name)) return "127.0.0.1";
                    if ("getCookies".equals(name)) return cookieArray;
                    if ("getHeader".equals(name)) return null;
                    if ("getCharacterEncoding".equals(name)) return StandardCharsets.UTF_8.name();

                    Class<?> rt = m.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    if (rt == long.class) return 0L;
                    if (rt == short.class) return (short) 0;
                    if (rt == byte.class) return (byte) 0;
                    if (rt == float.class) return 0f;
                    if (rt == double.class) return 0d;
                    if (rt == char.class) return (char) 0;
                    return null;
                }
        );
    }

    private static Part mockPart(String partName, String submittedFileName, String mimeType, byte[] bytes) {
        byte[] content = bytes == null ? new byte[0] : bytes;

        return (Part) Proxy.newProxyInstance(
                tenant_chat_servlet_test.class.getClassLoader(),
                new Class<?>[]{Part.class},
                (proxy, m, args) -> {
                    String name = m == null ? "" : safe(m.getName());
                    if ("getName".equals(name)) return safe(partName);
                    if ("getSubmittedFileName".equals(name)) return safe(submittedFileName);
                    if ("getContentType".equals(name)) return safe(mimeType);
                    if ("getInputStream".equals(name)) return new ByteArrayInputStream(content);
                    if ("getSize".equals(name)) return (long) content.length;
                    if ("delete".equals(name)) return null;
                    if ("write".equals(name)) return null;
                    if ("getHeader".equals(name)) return null;
                    if ("getHeaders".equals(name)) return List.of();
                    if ("getHeaderNames".equals(name)) return List.of();

                    Class<?> rt = m.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class) return 0;
                    if (rt == long.class) return 0L;
                    if (rt == short.class) return (short) 0;
                    if (rt == byte.class) return (byte) 0;
                    if (rt == float.class) return 0f;
                    if (rt == double.class) return 0d;
                    if (rt == char.class) return (char) 0;
                    return null;
                }
        );
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (root == null || !Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                }
            });
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static final class MockHttpServletResponse {
        int status = 200;
        String contentType = "";
        String characterEncoding = "";
        int contentLength = -1;
        final LinkedHashMap<String, String> headers = new LinkedHashMap<String, String>();
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        final ServletOutputStream outputStream = new MockServletOutputStream(body);
        final HttpServletResponse response;

        MockHttpServletResponse() {
            response = (HttpServletResponse) Proxy.newProxyInstance(
                    tenant_chat_servlet_test.class.getClassLoader(),
                    new Class<?>[]{HttpServletResponse.class},
                    (proxy, m, args) -> {
                        String name = m == null ? "" : safe(m.getName());
                        if ("setStatus".equals(name)) {
                            if (args != null && args.length > 0 && args[0] instanceof Integer i) status = i;
                            return null;
                        }
                        if ("getStatus".equals(name)) return status;
                        if ("setContentType".equals(name)) {
                            contentType = (args != null && args.length > 0) ? safe(String.valueOf(args[0])) : "";
                            return null;
                        }
                        if ("getContentType".equals(name)) return contentType;
                        if ("setCharacterEncoding".equals(name)) {
                            characterEncoding = (args != null && args.length > 0) ? safe(String.valueOf(args[0])) : "";
                            return null;
                        }
                        if ("getCharacterEncoding".equals(name)) return characterEncoding;
                        if ("setContentLength".equals(name)) {
                            if (args != null && args.length > 0 && args[0] instanceof Integer i) contentLength = i;
                            return null;
                        }
                        if ("setHeader".equals(name) || "addHeader".equals(name)) {
                            String key = (args == null || args.length == 0) ? "" : safe(String.valueOf(args[0]));
                            String value = (args == null || args.length < 2) ? "" : safe(String.valueOf(args[1]));
                            headers.put(key, value);
                            return null;
                        }
                        if ("getHeader".equals(name)) {
                            String key = (args == null || args.length == 0) ? "" : safe(String.valueOf(args[0]));
                            return headers.get(key);
                        }
                        if ("sendError".equals(name)) {
                            if (args != null && args.length > 0 && args[0] instanceof Integer i) status = i;
                            return null;
                        }
                        if ("sendRedirect".equals(name)) {
                            status = 302;
                            if (args != null && args.length > 0) headers.put("Location", safe(String.valueOf(args[0])));
                            return null;
                        }
                        if ("getOutputStream".equals(name)) return outputStream;
                        if ("flushBuffer".equals(name)) {
                            outputStream.flush();
                            return null;
                        }

                        Class<?> rt = m.getReturnType();
                        if (rt == boolean.class) return false;
                        if (rt == int.class) return 0;
                        if (rt == long.class) return 0L;
                        if (rt == short.class) return (short) 0;
                        if (rt == byte.class) return (byte) 0;
                        if (rt == float.class) return 0f;
                        if (rt == double.class) return 0d;
                        if (rt == char.class) return (char) 0;
                        return null;
                    }
            );
        }

        byte[] bodyBytes() {
            return body.toByteArray();
        }
    }

    private static final class MockServletOutputStream extends ServletOutputStream {
        private final ByteArrayOutputStream delegate;

        MockServletOutputStream(ByteArrayOutputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setWriteListener(WriteListener writeListener) {
            // No async I/O in tests.
        }

        @Override
        public void write(int b) throws IOException {
            delegate.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            delegate.write(b, off, len);
        }
    }
}
