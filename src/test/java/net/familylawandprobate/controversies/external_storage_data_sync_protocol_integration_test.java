package net.familylawandprobate.controversies;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
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
import org.opentest4j.TestAbortedException;

import java.net.ServerSocket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class external_storage_data_sync_protocol_integration_test {

    private static final String TEST_USER = "plugin_user";
    private static final String TEST_PASSWORD = "plugin_password";
    private static final String FTPS_KEYSTORE_PASSWORD = "changeit";
    private static final String DATA_ROOT_PROP = "controversies.data.root";

    @TempDir
    Path tempDir;

    @Test
    void ftp_server_round_trip_backup_and_restore() throws Exception {
        try (FtpServerHandle server = FtpServerHandle.start(tempDir.resolve("ftp"), false)) {
            Path sourceRoot = runRoundTrip("ftp", server.home.resolve("external-source"));
            assertFtpFileExists(server.port, "external-source/controversies_data_backup_v1/latest.properties");
            assertTrue(Files.exists(sourceRoot.resolve("controversies_data_backup_v1").resolve("latest.properties")));
        }
    }

    @Test
    void ftps_server_round_trip_backup_and_restore() throws Exception {
        try (FtpServerHandle server = FtpServerHandle.start(tempDir.resolve("ftps"), true)) {
            Path sourceRoot = runRoundTrip("ftps", server.home.resolve("external-source"));
            assertFtpsFileExists(server.port, "external-source/controversies_data_backup_v1/latest.properties");
            assertTrue(Files.exists(sourceRoot.resolve("controversies_data_backup_v1").resolve("latest.properties")));
        }
    }

    @Test
    void sftp_server_round_trip_backup_and_restore() throws Exception {
        try (SftpServerHandle server = SftpServerHandle.start(tempDir.resolve("sftp"))) {
            Path sourceRoot = runRoundTrip("sftp", server.home.resolve("external-source"));
            assertSftpFileExists(server.port, "external-source/controversies_data_backup_v1/latest.properties");
            assertTrue(Files.exists(sourceRoot.resolve("controversies_data_backup_v1").resolve("latest.properties")));
        }
    }

    @Test
    void webdav_backend_config_round_trip_backup_and_restore() throws Exception {
        Path sourceRoot = runRoundTrip("webdav", tempDir.resolve("webdav").resolve("external-source"));
        assertTrue(Files.exists(sourceRoot.resolve("controversies_data_backup_v1").resolve("latest.properties")));
    }

    @Test
    void onedrive_business_backend_config_round_trip_backup_and_restore() throws Exception {
        Path sourceRoot = runRoundTrip("onedrive_business", tempDir.resolve("onedrive").resolve("external-source"));
        assertTrue(Files.exists(sourceRoot.resolve("controversies_data_backup_v1").resolve("latest.properties")));
    }

    private Path runRoundTrip(String backend, Path sourceRoot) throws Exception {
        Path dataRoot = tempDir.resolve("data-" + backend + "-" + UUID.randomUUID());
        Files.createDirectories(dataRoot);
        Files.createDirectories(sourceRoot);

        String oldDataRoot = System.getProperty(DATA_ROOT_PROP);
        try {
            System.setProperty(DATA_ROOT_PROP, dataRoot.toString());

            Path alpha = dataRoot.resolve("alpha.txt");
            Path beta = dataRoot.resolve("nested").resolve("beta.txt");
            Files.createDirectories(beta.getParent());
            Files.writeString(alpha, "alpha-original", StandardCharsets.UTF_8);
            Files.writeString(beta, "beta-original", StandardCharsets.UTF_8);

            external_storage_data_sync service = external_storage_data_sync.defaultService();
            String tenantUuid = "tenant-" + UUID.randomUUID();
            external_storage_data_sync.BackupResult backup = service.backupNowForConfig(
                    tenantUuid,
                    backend,
                    sourceRoot.toUri().toString(),
                    "access-key",
                    "secret-key",
                    ""
            );
            assertTrue(backup.ok);
            assertFalse(backup.snapshotId.isBlank());

            Path latestPointer = sourceRoot.resolve("controversies_data_backup_v1").resolve("latest.properties");
            assertTrue(Files.exists(latestPointer));
            String latestBeforeRestore = Files.readString(latestPointer, StandardCharsets.UTF_8);

            Files.writeString(alpha, "alpha-mutated", StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
            Files.deleteIfExists(beta);
            Files.writeString(dataRoot.resolve("local-only.txt"), "remove-me", StandardCharsets.UTF_8);

            external_storage_data_sync.RestoreResult restored = service.restoreLatestForConfig(
                    tenantUuid,
                    backend,
                    sourceRoot.toUri().toString(),
                    "access-key",
                    "secret-key",
                    ""
            );
            assertTrue(restored.ok);

            assertEquals("alpha-original", Files.readString(alpha, StandardCharsets.UTF_8));
            assertEquals("beta-original", Files.readString(beta, StandardCharsets.UTF_8));
            assertFalse(Files.exists(dataRoot.resolve("local-only.txt")));
            assertEquals(latestBeforeRestore, Files.readString(latestPointer, StandardCharsets.UTF_8));

            return sourceRoot;
        } finally {
            if (oldDataRoot == null) System.clearProperty(DATA_ROOT_PROP);
            else System.setProperty(DATA_ROOT_PROP, oldDataRoot);
            deleteTree(dataRoot);
            // Source root is under @TempDir and will be cleaned by JUnit.
        }
    }

    private static void assertFtpFileExists(int port, String path) throws Exception {
        FTPClient client = new FTPClient();
        client.setConnectTimeout(10_000);
        client.setDefaultTimeout(10_000);
        client.connect("127.0.0.1", port);
        assertTrue(FTPReply.isPositiveCompletion(client.getReplyCode()));
        try {
            assertTrue(client.login(TEST_USER, TEST_PASSWORD));
            client.enterLocalPassiveMode();
            client.setFileType(FTP.BINARY_FILE_TYPE);
            FTPFile[] files = client.listFiles(path);
            assertTrue(files != null && files.length > 0);
            assertTrue(files[0].isFile());
        } finally {
            try { client.logout(); } catch (Exception ignored) {}
            try { client.disconnect(); } catch (Exception ignored) {}
        }
    }

    private static void assertFtpsFileExists(int port, String path) throws Exception {
        FTPSClient client = new FTPSClient(false);
        client.setTrustManager(TrustManagerUtils.getAcceptAllTrustManager());
        client.setConnectTimeout(10_000);
        client.setDefaultTimeout(10_000);
        client.connect("127.0.0.1", port);
        assertTrue(FTPReply.isPositiveCompletion(client.getReplyCode()));
        try {
            assertTrue(client.login(TEST_USER, TEST_PASSWORD));
            client.execPBSZ(0);
            client.execPROT("P");
            client.enterLocalPassiveMode();
            client.setFileType(FTP.BINARY_FILE_TYPE);
            FTPFile[] files = client.listFiles(path);
            assertTrue(files != null && files.length > 0);
            assertTrue(files[0].isFile());
        } finally {
            try { client.logout(); } catch (Exception ignored) {}
            try { client.disconnect(); } catch (Exception ignored) {}
        }
    }

    private static void assertSftpFileExists(int port, String path) throws Exception {
        JSch jsch = new JSch();
        Session session = jsch.getSession(TEST_USER, "127.0.0.1", port);
        session.setPassword(TEST_PASSWORD);
        Properties conf = new Properties();
        conf.put("StrictHostKeyChecking", "no");
        session.setConfig(conf);
        session.connect(10_000);
        ChannelSftp channel = null;
        try {
            channel = (ChannelSftp) session.openChannel("sftp");
            channel.connect(10_000);
            SftpATTRS attrs = channel.stat(path);
            assertNotNull(attrs);
            assertFalse(attrs.isDir());
        } finally {
            try {
                if (channel != null && channel.isConnected()) channel.disconnect();
            } catch (Exception ignored) {
            }
            try {
                if (session.isConnected()) session.disconnect();
            } catch (Exception ignored) {
            }
        }
    }

    private static final class FtpServerHandle implements AutoCloseable {
        private final FtpServer server;
        private final int port;
        private final Path home;

        private FtpServerHandle(FtpServer server, int port, Path home) {
            this.server = server;
            this.port = port;
            this.home = home;
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
            return new FtpServerHandle(server, port, home);
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
        private final Path home;

        private SftpServerHandle(SshServer server, int port, Path home) {
            this.server = server;
            this.port = port;
            this.home = home;
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
            return new SftpServerHandle(sshd, port, home);
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
        } catch (SocketException ex) {
            String msg = safe(ex.getMessage()).toLowerCase();
            if (msg.contains("operation not permitted") || msg.contains("permission denied")) {
                throw new TestAbortedException("Local socket binding is blocked in this environment.", ex);
            }
            throw ex;
        }
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
