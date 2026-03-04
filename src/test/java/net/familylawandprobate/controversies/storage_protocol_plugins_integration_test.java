package net.familylawandprobate.controversies;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import net.familylawandprobate.controversies.storage.CachedDocumentStorageBackend;
import net.familylawandprobate.controversies.storage.DocumentStorageBackend;
import net.familylawandprobate.controversies.storage.EncryptedDocumentStorageBackend;
import net.familylawandprobate.controversies.storage.StorageCrypto;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;
import org.apache.commons.net.ftp.FTPSClient;
import org.apache.commons.net.util.TrustManagerUtils;
import org.apache.ftpserver.FtpServer;
import org.apache.ftpserver.FtpServerFactory;
import org.apache.ftpserver.listener.ListenerFactory;
import org.apache.ftpserver.ssl.SslConfigurationFactory;
import org.apache.ftpserver.usermanager.ClearTextPasswordEncryptor;
import org.apache.ftpserver.usermanager.PropertiesUserManagerFactory;
import org.apache.ftpserver.usermanager.impl.BaseUser;
import org.apache.ftpserver.usermanager.impl.WritePermission;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class storage_protocol_plugins_integration_test {

    private static final String TEST_USER = "plugin_user";
    private static final String TEST_PASSWORD = "plugin_password";
    private static final String FTPS_KEYSTORE_PASSWORD = "changeit";

    @TempDir
    Path tempDir;

    @Test
    void ftp_plugin_round_trip_and_encryption() throws Exception {
        try (FtpServerHandle server = FtpServerHandle.start(tempDir, false)) {
            DocumentStorageBackend plugin = new FtpPluginBackend("127.0.0.1", server.port, TEST_USER, TEST_PASSWORD);
            assertPluginRoundTripAndEncryption("ftp", plugin);
        }
    }

    @Test
    void ftps_plugin_round_trip_and_encryption() throws Exception {
        try (FtpServerHandle server = FtpServerHandle.start(tempDir, true)) {
            DocumentStorageBackend plugin = new FtpsPluginBackend("127.0.0.1", server.port, TEST_USER, TEST_PASSWORD);
            assertPluginRoundTripAndEncryption("ftps", plugin);
        }
    }

    @Test
    void sftp_plugin_round_trip_and_encryption() throws Exception {
        try (SftpServerHandle server = SftpServerHandle.start(tempDir)) {
            DocumentStorageBackend plugin = new SftpPluginBackend("127.0.0.1", server.port, TEST_USER, TEST_PASSWORD);
            assertPluginRoundTripAndEncryption("sftp", plugin);
        }
    }

    @Test
    void s3_compatible_plugin_round_trip_and_encryption() throws Exception {
        try (MiniS3Server server = MiniS3Server.start()) {
            DocumentStorageBackend plugin = new S3CompatibleHttpPluginBackend(
                    "http://127.0.0.1:" + server.port,
                    "plugin-bucket",
                    "access-key",
                    "secret-key",
                    server
            );
            assertPluginRoundTripAndEncryption("s3_compatible", plugin);
        }
    }

    @Test
    void ftp_plugin_redundancy_survives_interruption_failures_and_unavailability() throws Exception {
        try (FtpServerHandle server = FtpServerHandle.start(tempDir, false)) {
            DocumentStorageBackend plugin = new FtpPluginBackend("127.0.0.1", server.port, TEST_USER, TEST_PASSWORD);
            assertPluginRedundancyDuringOutage("ftp", plugin, server::close);
        }
    }

    @Test
    void ftps_plugin_redundancy_survives_interruption_failures_and_unavailability() throws Exception {
        try (FtpServerHandle server = FtpServerHandle.start(tempDir, true)) {
            DocumentStorageBackend plugin = new FtpsPluginBackend("127.0.0.1", server.port, TEST_USER, TEST_PASSWORD);
            assertPluginRedundancyDuringOutage("ftps", plugin, server::close);
        }
    }

    @Test
    void sftp_plugin_redundancy_survives_interruption_failures_and_unavailability() throws Exception {
        try (SftpServerHandle server = SftpServerHandle.start(tempDir)) {
            DocumentStorageBackend plugin = new SftpPluginBackend("127.0.0.1", server.port, TEST_USER, TEST_PASSWORD);
            assertPluginRedundancyDuringOutage("sftp", plugin, server::close);
        }
    }

    @Test
    void s3_compatible_plugin_redundancy_survives_interruption_failures_and_unavailability() throws Exception {
        try (MiniS3Server server = MiniS3Server.start()) {
            DocumentStorageBackend plugin = new S3CompatibleHttpPluginBackend(
                    "http://127.0.0.1:" + server.port,
                    "plugin-bucket",
                    "access-key",
                    "secret-key",
                    server
            );
            assertPluginRedundancyDuringOutage("s3_compatible", plugin, server::close);
        }
    }

    private static void assertPluginRoundTripAndEncryption(String pluginName, DocumentStorageBackend plugin) throws Exception {
        byte[] plainPayload = ("plain-" + pluginName + "-" + Instant.now().toString()).getBytes(StandardCharsets.UTF_8);
        String plainKey = plugin.put("plugins/" + pluginName + "/roundtrip-" + UUID.randomUUID() + ".txt", plainPayload);
        assertTrue(plugin.exists(plainKey));
        assertArrayEquals(plainPayload, plugin.get(plainKey));
        Map<String, String> plainMeta = plugin.metadata(plainKey);
        assertEquals(String.valueOf(plainPayload.length), plainMeta.get("size_bytes"));
        assertEquals(StorageCrypto.checksumSha256Hex(plainPayload), plainMeta.get("checksum_sha256"));

        DocumentStorageBackend encrypted = new EncryptedDocumentStorageBackend(plugin, "tenant_managed", "encryption-key-" + pluginName, "none", "");
        byte[] secretPayload = ("secret-" + pluginName + "-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
        String encryptedKey = encrypted.put("plugins/" + pluginName + "/encrypted-" + UUID.randomUUID() + ".bin", secretPayload);

        byte[] rawStored = plugin.get(encryptedKey);
        assertFalse(Arrays.equals(secretPayload, rawStored));
        assertArrayEquals(secretPayload, encrypted.get(encryptedKey));
        Map<String, String> encryptedMeta = encrypted.metadata(encryptedKey);
        assertEquals("tenant_managed", encryptedMeta.get("app_encryption"));
        assertEquals(String.valueOf(secretPayload.length), encryptedMeta.get("plaintext_size_bytes"));

        encrypted.delete(encryptedKey);
        assertFalse(plugin.exists(encryptedKey));
        plugin.delete(plainKey);
        assertFalse(plugin.exists(plainKey));
    }

    private static void assertPluginRedundancyDuringOutage(String pluginName,
                                                           DocumentStorageBackend plugin,
                                                           ThrowingRunnable outageAction) throws Exception {
        String tenantUuid = "tenant-" + UUID.randomUUID();
        String sourceToken = pluginName + "_redundancy_" + UUID.randomUUID();
        DocumentStorageBackend cached = new CachedDocumentStorageBackend(
                plugin,
                tenantUuid,
                sourceToken,
                16L * 1024L * 1024L
        );
        DocumentStorageBackend encrypted = new EncryptedDocumentStorageBackend(
                cached,
                "tenant_managed",
                "redundancy-key-" + pluginName,
                "none",
                ""
        );

        byte[] payload = ("redundancy-" + pluginName + "-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
        String key = encrypted.put("plugins/" + pluginName + "/redundancy-" + UUID.randomUUID() + ".bin", payload);

        byte[] rawStored = plugin.get(key);
        assertFalse(Arrays.equals(payload, rawStored));
        assertArrayEquals(payload, encrypted.get(key));

        outageAction.run();

        assertTrue(cached.exists(key));
        Thread.currentThread().interrupt();
        try {
            assertArrayEquals(payload, encrypted.get(key));
        } finally {
            Thread.interrupted();
        }

        String missKey = "plugins/" + pluginName + "/miss-" + UUID.randomUUID() + ".bin";
        assertEventuallyFails("cache miss read during outage", () -> cached.get(missKey));

        String writeKey = "plugins/" + pluginName + "/write-during-outage-" + UUID.randomUUID() + ".bin";
        assertEventuallyFails("write during outage", () -> encrypted.put(writeKey, "write".getBytes(StandardCharsets.UTF_8)));
    }

    private static void assertEventuallyFails(String description, ThrowingRunnable operation) throws Exception {
        long deadline = System.currentTimeMillis() + 5000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                operation.run();
            } catch (Exception expected) {
                return;
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for outage assertion: " + description, ex);
            }
        }
        throw new IllegalStateException("Expected failure was not observed: " + description);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static final class FtpServerHandle implements AutoCloseable {
        private final FtpServer server;
        private final int port;

        private FtpServerHandle(FtpServer server, int port) {
            this.server = server;
            this.port = port;
        }

        static FtpServerHandle start(Path root, boolean tls) throws Exception {
            Path home = root.resolve(tls ? "ftps-home" : "ftp-home");
            Files.createDirectories(home);

            FtpServerFactory factory = new FtpServerFactory();
            ListenerFactory listenerFactory = new ListenerFactory();
            int port = freePort();
            listenerFactory.setPort(port);

            if (tls) {
                Path keystore = createTempPkcs12Keystore(root.resolve("ftps-keystore.p12"));
                SslConfigurationFactory sslFactory = new SslConfigurationFactory();
                sslFactory.setKeystoreFile(keystore.toFile());
                sslFactory.setKeystorePassword(FTPS_KEYSTORE_PASSWORD);
                listenerFactory.setSslConfiguration(sslFactory.createSslConfiguration());
                listenerFactory.setImplicitSsl(false);
            }

            factory.addListener("default", listenerFactory.createListener());

            PropertiesUserManagerFactory users = new PropertiesUserManagerFactory();
            Path usersFile = root.resolve(tls ? "ftps-users.properties" : "ftp-users.properties");
            if (!Files.exists(usersFile)) Files.createFile(usersFile);
            users.setFile(usersFile.toFile());
            users.setPasswordEncryptor(new ClearTextPasswordEncryptor());
            org.apache.ftpserver.ftplet.UserManager userManager = users.createUserManager();

            BaseUser user = new BaseUser();
            user.setName(TEST_USER);
            user.setPassword(TEST_PASSWORD);
            user.setHomeDirectory(home.toAbsolutePath().toString());
            user.setAuthorities(List.of(new WritePermission()));
            userManager.save(user);
            factory.setUserManager(userManager);

            FtpServer server = factory.createServer();
            server.start();
            return new FtpServerHandle(server, port);
        }

        @Override
        public void close() {
            if (server == null) return;
            try {
                server.stop();
            } catch (Exception ignored) {
            }
        }
    }

    private static final class SftpServerHandle implements AutoCloseable {
        private final SshServer server;
        private final int port;

        private SftpServerHandle(SshServer server, int port) {
            this.server = server;
            this.port = port;
        }

        static SftpServerHandle start(Path root) throws Exception {
            Path home = root.resolve("sftp-home");
            Files.createDirectories(home);
            Path hostKey = root.resolve("sftp-host-key.ser");

            SshServer sshd = SshServer.setUpDefaultServer();
            int port = freePort();
            sshd.setHost("127.0.0.1");
            sshd.setPort(port);
            sshd.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(hostKey));
            sshd.setPasswordAuthenticator((username, password, session) ->
                    TEST_USER.equals(username) && TEST_PASSWORD.equals(password)
            );
            sshd.setSubsystemFactories(List.of(new SftpSubsystemFactory.Builder().build()));
            sshd.setFileSystemFactory(new VirtualFileSystemFactory(home.toAbsolutePath()));
            sshd.start();
            return new SftpServerHandle(sshd, port);
        }

        @Override
        public void close() {
            if (server == null) return;
            try {
                server.stop();
            } catch (Exception ignored) {
            }
        }
    }

    private static final class MiniS3Server implements AutoCloseable, HttpHandler {
        private final HttpServer server;
        private final int port;
        private final ConcurrentHashMap<String, byte[]> objects = new ConcurrentHashMap<String, byte[]>();

        private MiniS3Server(HttpServer server, int port) {
            this.server = server;
            this.port = port;
        }

        static MiniS3Server start() throws Exception {
            HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", freePort()), 0);
            MiniS3Server handle = new MiniS3Server(http, http.getAddress().getPort());
            http.createContext("/", handle);
            http.setExecutor(Executors.newCachedThreadPool());
            http.start();
            return handle;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                String method = safe(exchange.getRequestMethod()).toUpperCase();
                String path = safe(exchange.getRequestURI() == null ? "" : exchange.getRequestURI().getPath());
                String trimmed = path.startsWith("/") ? path.substring(1) : path;
                int slash = trimmed.indexOf('/');
                if (slash <= 0 || slash >= trimmed.length() - 1) {
                    send(exchange, 400, new byte[0], "text/plain");
                    return;
                }

                String bucket = decode(trimmed.substring(0, slash));
                String key = decode(trimmed.substring(slash + 1));
                if (bucket.isBlank() || key.isBlank()) {
                    send(exchange, 400, new byte[0], "text/plain");
                    return;
                }

                Headers headers = exchange.getRequestHeaders();
                String access = safe(headers.getFirst("x-access-key")).trim();
                String secret = safe(headers.getFirst("x-secret-key")).trim();
                if (!"access-key".equals(access) || !"secret-key".equals(secret)) {
                    send(exchange, 403, new byte[0], "text/plain");
                    return;
                }

                String objectRef = bucket + "/" + key;
                if ("PUT".equals(method)) {
                    byte[] body = exchange.getRequestBody().readAllBytes();
                    objects.put(objectRef, body == null ? new byte[0] : body);
                    send(exchange, 200, new byte[0], "application/octet-stream");
                    return;
                }
                if ("GET".equals(method)) {
                    byte[] body = objects.get(objectRef);
                    if (body == null) {
                        send(exchange, 404, new byte[0], "text/plain");
                        return;
                    }
                    send(exchange, 200, body, "application/octet-stream");
                    return;
                }
                if ("HEAD".equals(method)) {
                    byte[] body = objects.get(objectRef);
                    if (body == null) {
                        send(exchange, 404, new byte[0], "text/plain");
                        return;
                    }
                    send(exchange, 200, new byte[0], "application/octet-stream");
                    return;
                }
                if ("DELETE".equals(method)) {
                    objects.remove(objectRef);
                    send(exchange, 204, new byte[0], "text/plain");
                    return;
                }
                send(exchange, 405, new byte[0], "text/plain");
            } catch (Exception ex) {
                send(exchange, 500, safe(ex.getMessage()).getBytes(StandardCharsets.UTF_8), "text/plain");
            }
        }

        private static void send(HttpExchange exchange, int status, byte[] body, String contentType) throws IOException {
            byte[] payload = body == null ? new byte[0] : body;
            if (!safe(contentType).isBlank()) {
                exchange.getResponseHeaders().set("Content-Type", contentType);
            }
            boolean headRequest = "HEAD".equalsIgnoreCase(safe(exchange.getRequestMethod()));
            boolean noBodyStatus = status == 204 || status == 304;
            long responseLength = (headRequest || noBodyStatus) ? -1L : payload.length;
            exchange.sendResponseHeaders(status, responseLength);
            try (OutputStream out = exchange.getResponseBody()) {
                if (responseLength >= 0) out.write(payload);
            }
        }

        @Override
        public void close() {
            if (server == null) return;
            try {
                server.stop(0);
            } catch (Exception ignored) {
            }
        }
    }

    private static final class FtpPluginBackend implements DocumentStorageBackend {
        private final String host;
        private final int port;
        private final String username;
        private final String password;

        private FtpPluginBackend(String host, int port, String username, String password) {
            this.host = safe(host).trim();
            this.port = port;
            this.username = safe(username).trim();
            this.password = safe(password);
        }

        @Override
        public String put(String key, byte[] bytes) throws Exception {
            String normalized = normalizeKey(key);
            try (FtpSession s = connect()) {
                ensureFtpDirectories(s.client, normalized);
                byte[] payload = bytes == null ? new byte[0] : bytes;
                boolean ok = s.client.storeFile(normalized, new ByteArrayInputStream(payload));
                if (!ok) throw new IllegalStateException("FTP put failed for key: " + normalized);
            }
            return normalized;
        }

        @Override
        public byte[] get(String key) throws Exception {
            String normalized = normalizeKey(key);
            try (FtpSession s = connect()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                boolean ok = s.client.retrieveFile(normalized, out);
                if (!ok && s.client.getReplyCode() == 550) return new byte[0];
                if (!ok) throw new IllegalStateException("FTP get failed for key: " + normalized);
                return out.toByteArray();
            }
        }

        @Override
        public void delete(String key) throws Exception {
            String normalized = normalizeKey(key);
            try (FtpSession s = connect()) {
                boolean ok = s.client.deleteFile(normalized);
                if (!ok && s.client.getReplyCode() != 550) {
                    throw new IllegalStateException("FTP delete failed for key: " + normalized);
                }
            }
        }

        @Override
        public boolean exists(String key) throws Exception {
            String normalized = normalizeKey(key);
            try (FtpSession s = connect()) {
                FTPFile[] files = s.client.listFiles(normalized);
                return files != null && files.length > 0 && files[0] != null && files[0].isFile();
            }
        }

        @Override
        public Map<String, String> metadata(String key) throws Exception {
            LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
            String normalized = normalizeKey(key);
            out.put("backend", "ftp");
            out.put("key", normalized);
            byte[] bytes = get(normalized);
            if (bytes.length == 0 && !exists(normalized)) return out;
            out.put("size_bytes", String.valueOf(bytes.length));
            out.put("checksum_sha256", StorageCrypto.checksumSha256Hex(bytes));
            out.put("checksum_md5", StorageCrypto.checksumMd5Hex(bytes));
            return out;
        }

        private FtpSession connect() throws Exception {
            FTPClient client = new FTPClient();
            client.setConnectTimeout(10_000);
            client.setDefaultTimeout(10_000);
            client.connect(host, port);
            if (!FTPReply.isPositiveCompletion(client.getReplyCode())) {
                throw new IllegalStateException("FTP connect failed code=" + client.getReplyCode());
            }
            if (!client.login(username, password)) {
                throw new IllegalStateException("FTP login failed");
            }
            client.enterLocalPassiveMode();
            client.setFileType(FTP.BINARY_FILE_TYPE);
            return new FtpSession(client);
        }
    }

    private static final class FtpsPluginBackend implements DocumentStorageBackend {
        private final String host;
        private final int port;
        private final String username;
        private final String password;

        private FtpsPluginBackend(String host, int port, String username, String password) {
            this.host = safe(host).trim();
            this.port = port;
            this.username = safe(username).trim();
            this.password = safe(password);
        }

        @Override
        public String put(String key, byte[] bytes) throws Exception {
            String normalized = normalizeKey(key);
            try (FtpsSession s = connect()) {
                ensureFtpDirectories(s.client, normalized);
                byte[] payload = bytes == null ? new byte[0] : bytes;
                boolean ok = s.client.storeFile(normalized, new ByteArrayInputStream(payload));
                if (!ok) throw new IllegalStateException("FTPS put failed for key: " + normalized);
            }
            return normalized;
        }

        @Override
        public byte[] get(String key) throws Exception {
            String normalized = normalizeKey(key);
            try (FtpsSession s = connect()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                boolean ok = s.client.retrieveFile(normalized, out);
                if (!ok && s.client.getReplyCode() == 550) return new byte[0];
                if (!ok) throw new IllegalStateException("FTPS get failed for key: " + normalized);
                return out.toByteArray();
            }
        }

        @Override
        public void delete(String key) throws Exception {
            String normalized = normalizeKey(key);
            try (FtpsSession s = connect()) {
                boolean ok = s.client.deleteFile(normalized);
                if (!ok && s.client.getReplyCode() != 550) {
                    throw new IllegalStateException("FTPS delete failed for key: " + normalized);
                }
            }
        }

        @Override
        public boolean exists(String key) throws Exception {
            String normalized = normalizeKey(key);
            try (FtpsSession s = connect()) {
                FTPFile[] files = s.client.listFiles(normalized);
                return files != null && files.length > 0 && files[0] != null && files[0].isFile();
            }
        }

        @Override
        public Map<String, String> metadata(String key) throws Exception {
            LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
            String normalized = normalizeKey(key);
            out.put("backend", "ftps");
            out.put("key", normalized);
            byte[] bytes = get(normalized);
            if (bytes.length == 0 && !exists(normalized)) return out;
            out.put("size_bytes", String.valueOf(bytes.length));
            out.put("checksum_sha256", StorageCrypto.checksumSha256Hex(bytes));
            out.put("checksum_md5", StorageCrypto.checksumMd5Hex(bytes));
            return out;
        }

        private FtpsSession connect() throws Exception {
            FTPSClient client = new FTPSClient(false);
            client.setTrustManager(TrustManagerUtils.getAcceptAllTrustManager());
            client.setConnectTimeout(10_000);
            client.setDefaultTimeout(10_000);
            client.connect(host, port);
            if (!FTPReply.isPositiveCompletion(client.getReplyCode())) {
                throw new IllegalStateException("FTPS connect failed code=" + client.getReplyCode());
            }
            if (!client.login(username, password)) {
                throw new IllegalStateException("FTPS login failed");
            }
            client.execPBSZ(0);
            client.execPROT("P");
            client.enterLocalPassiveMode();
            client.setFileType(FTP.BINARY_FILE_TYPE);
            return new FtpsSession(client);
        }
    }

    private static final class SftpPluginBackend implements DocumentStorageBackend {
        private final String host;
        private final int port;
        private final String username;
        private final String password;

        private SftpPluginBackend(String host, int port, String username, String password) {
            this.host = safe(host).trim();
            this.port = port;
            this.username = safe(username).trim();
            this.password = safe(password);
        }

        @Override
        public String put(String key, byte[] bytes) throws Exception {
            String normalized = normalizeKey(key);
            try (SftpSession s = connect()) {
                ensureSftpDirectories(s.channel, normalized);
                byte[] payload = bytes == null ? new byte[0] : bytes;
                s.channel.put(new ByteArrayInputStream(payload), normalized, ChannelSftp.OVERWRITE);
            }
            return normalized;
        }

        @Override
        public byte[] get(String key) throws Exception {
            String normalized = normalizeKey(key);
            try (SftpSession s = connect()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                try {
                    s.channel.get(normalized, out);
                } catch (SftpException ex) {
                    if (ex.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) return new byte[0];
                    throw ex;
                }
                return out.toByteArray();
            }
        }

        @Override
        public void delete(String key) throws Exception {
            String normalized = normalizeKey(key);
            try (SftpSession s = connect()) {
                try {
                    s.channel.rm(normalized);
                } catch (SftpException ex) {
                    if (ex.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) throw ex;
                }
            }
        }

        @Override
        public boolean exists(String key) throws Exception {
            String normalized = normalizeKey(key);
            try (SftpSession s = connect()) {
                try {
                    return s.channel.stat(normalized) != null;
                } catch (SftpException ex) {
                    if (ex.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) return false;
                    throw ex;
                }
            }
        }

        @Override
        public Map<String, String> metadata(String key) throws Exception {
            LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
            String normalized = normalizeKey(key);
            out.put("backend", "sftp");
            out.put("key", normalized);
            byte[] bytes = get(normalized);
            if (bytes.length == 0 && !exists(normalized)) return out;
            out.put("size_bytes", String.valueOf(bytes.length));
            out.put("checksum_sha256", StorageCrypto.checksumSha256Hex(bytes));
            out.put("checksum_md5", StorageCrypto.checksumMd5Hex(bytes));
            return out;
        }

        private SftpSession connect() throws Exception {
            JSch jsch = new JSch();
            Session session = jsch.getSession(username, host, port);
            session.setPassword(password);
            Properties conf = new Properties();
            conf.put("StrictHostKeyChecking", "no");
            session.setConfig(conf);
            session.connect(10_000);

            ChannelSftp channel = (ChannelSftp) session.openChannel("sftp");
            channel.connect(10_000);
            return new SftpSession(session, channel);
        }
    }

    private static final class S3CompatibleHttpPluginBackend implements DocumentStorageBackend {
        private final String endpoint;
        private final String bucket;
        private final String accessKey;
        private final String secretKey;
        private final MiniS3Server server;

        private S3CompatibleHttpPluginBackend(String endpoint,
                                              String bucket,
                                              String accessKey,
                                              String secretKey,
                                              MiniS3Server server) {
            this.endpoint = safe(endpoint).trim();
            this.bucket = safe(bucket).trim();
            this.accessKey = safe(accessKey).trim();
            this.secretKey = safe(secretKey).trim();
            this.server = server;
        }

        @Override
        public String put(String key, byte[] bytes) throws Exception {
            String normalized = normalizeKey(key);
            byte[] payload = bytes == null ? new byte[0] : bytes;
            HttpURLConnection conn = openConnection(normalized, "PUT");
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(payload.length);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(payload);
            }
            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) throw new IllegalStateException("S3-compatible put failed status=" + status);
            return normalized;
        }

        @Override
        public byte[] get(String key) throws Exception {
            String normalized = normalizeKey(key);
            HttpURLConnection conn = openConnection(normalized, "GET");
            int status = conn.getResponseCode();
            if (status == 404) return new byte[0];
            if (status < 200 || status >= 300) throw new IllegalStateException("S3-compatible get failed status=" + status);
            return conn.getInputStream().readAllBytes();
        }

        @Override
        public void delete(String key) throws Exception {
            String normalized = normalizeKey(key);
            HttpURLConnection conn = openConnection(normalized, "DELETE");
            int status = conn.getResponseCode();
            if (status != 204 && (status < 200 || status >= 300)) {
                throw new IllegalStateException("S3-compatible delete failed status=" + status);
            }
        }

        @Override
        public boolean exists(String key) throws Exception {
            String normalized = normalizeKey(key);
            HttpURLConnection conn = openConnection(normalized, "HEAD");
            int status = conn.getResponseCode();
            if (status == 404) return false;
            return status >= 200 && status < 300;
        }

        @Override
        public Map<String, String> metadata(String key) throws Exception {
            LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
            String normalized = normalizeKey(key);
            out.put("backend", "s3_compatible");
            out.put("key", normalized);
            byte[] bytes = get(normalized);
            if (bytes.length == 0 && !exists(normalized)) return out;
            out.put("size_bytes", String.valueOf(bytes.length));
            out.put("checksum_sha256", StorageCrypto.checksumSha256Hex(bytes));
            out.put("checksum_md5", StorageCrypto.checksumMd5Hex(bytes));
            out.put("server_port", String.valueOf(server == null ? 0 : server.port));
            return out;
        }

        private HttpURLConnection openConnection(String key, String method) throws Exception {
            String url = endpoint + "/" + urlEncode(bucket) + "/" + encodePath(key);
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);
            conn.setRequestProperty("x-access-key", accessKey);
            conn.setRequestProperty("x-secret-key", secretKey);
            return conn;
        }
    }

    private static final class FtpSession implements AutoCloseable {
        private final FTPClient client;

        private FtpSession(FTPClient client) {
            this.client = client;
        }

        @Override
        public void close() {
            if (client == null) return;
            try {
                if (client.isConnected()) {
                    try {
                        client.logout();
                    } finally {
                        client.disconnect();
                    }
                }
            } catch (Exception ignored) {
            }
        }
    }

    private static final class FtpsSession implements AutoCloseable {
        private final FTPSClient client;

        private FtpsSession(FTPSClient client) {
            this.client = client;
        }

        @Override
        public void close() {
            if (client == null) return;
            try {
                if (client.isConnected()) {
                    try {
                        client.logout();
                    } finally {
                        client.disconnect();
                    }
                }
            } catch (Exception ignored) {
            }
        }
    }

    private static final class SftpSession implements AutoCloseable {
        private final Session session;
        private final ChannelSftp channel;

        private SftpSession(Session session, ChannelSftp channel) {
            this.session = session;
            this.channel = channel;
        }

        @Override
        public void close() {
            try {
                if (channel != null && channel.isConnected()) channel.disconnect();
            } catch (Exception ignored) {
            }
            try {
                if (session != null && session.isConnected()) session.disconnect();
            } catch (Exception ignored) {
            }
        }
    }

    private static void ensureFtpDirectories(FTPClient client, String key) throws Exception {
        String dir = parentPath(key);
        if (dir.isBlank()) return;
        String[] parts = dir.split("/");
        String current = "";
        for (String part : parts) {
            String p = safe(part).trim();
            if (p.isBlank()) continue;
            current = current.isBlank() ? p : current + "/" + p;
            client.makeDirectory(current);
        }
    }

    private static void ensureSftpDirectories(ChannelSftp channel, String key) throws Exception {
        String dir = parentPath(key);
        if (dir.isBlank()) return;
        String[] parts = dir.split("/");
        String current = "";
        for (String part : parts) {
            String p = safe(part).trim();
            if (p.isBlank()) continue;
            current = current.isBlank() ? p : current + "/" + p;
            try {
                channel.stat(current);
            } catch (SftpException ex) {
                if (ex.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) channel.mkdir(current);
                else throw ex;
            }
        }
    }

    private static Path createTempPkcs12Keystore(Path out) throws Exception {
        if (Files.exists(out)) return out;
        Files.createDirectories(Objects.requireNonNull(out.getParent()));

        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path javaHomeKeytool = Path.of(System.getProperty("java.home"), "bin", windows ? "keytool.exe" : "keytool");
        String keytool = Files.exists(javaHomeKeytool) ? javaHomeKeytool.toString() : (windows ? "keytool.exe" : "keytool");

        ArrayList<String> cmd = new ArrayList<String>();
        cmd.add(keytool);
        cmd.add("-genkeypair");
        cmd.add("-alias");
        cmd.add("ftps-test");
        cmd.add("-keyalg");
        cmd.add("RSA");
        cmd.add("-keysize");
        cmd.add("2048");
        cmd.add("-storetype");
        cmd.add("PKCS12");
        cmd.add("-keystore");
        cmd.add(out.toString());
        cmd.add("-storepass");
        cmd.add(FTPS_KEYSTORE_PASSWORD);
        cmd.add("-keypass");
        cmd.add(FTPS_KEYSTORE_PASSWORD);
        cmd.add("-dname");
        cmd.add("CN=localhost, OU=Testing, O=Controversies, L=Local, ST=Local, C=US");
        cmd.add("-validity");
        cmd.add("3650");
        cmd.add("-noprompt");

        Process proc = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        byte[] output = proc.getInputStream().readAllBytes();
        int code = proc.waitFor();
        if (code != 0) {
            throw new IllegalStateException("keytool failed: " + new String(output, StandardCharsets.UTF_8));
        }
        return out;
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static String encodePath(String key) {
        String normalized = normalizeKey(key);
        String[] parts = normalized.split("/");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append('/');
            sb.append(urlEncode(parts[i]));
        }
        return sb.toString();
    }

    private static String parentPath(String key) {
        String normalized = normalizeKey(key);
        int idx = normalized.lastIndexOf('/');
        if (idx <= 0) return "";
        return normalized.substring(0, idx);
    }

    private static String normalizeKey(String key) {
        String cleaned = safe(key).replace("\\", "/").trim();
        cleaned = cleaned.replaceAll("^/+", "");
        if (cleaned.isBlank() || cleaned.contains("..")) throw new IllegalArgumentException("invalid key");
        return cleaned;
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(safe(value), StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String decode(String value) {
        return URLDecoder.decode(safe(value), StandardCharsets.UTF_8);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
