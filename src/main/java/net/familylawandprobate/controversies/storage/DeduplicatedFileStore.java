package net.familylawandprobate.controversies.storage;

import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;

/**
 * Writes immutable content-addressed blobs and materializes link references at caller paths.
 * The object store is append-only to reduce accidental data loss risk.
 */
public final class DeduplicatedFileStore {
    private static final String OBJECT_EXT = ".bin";
    private static final String TMP_EXT = ".tmp";

    private DeduplicatedFileStore() {
    }

    public static void write(Path destinationFile, byte[] bytes, Path objectRoot) throws Exception {
        if (destinationFile == null) throw new IllegalArgumentException("destination file is required");
        if (objectRoot == null) throw new IllegalArgumentException("object root is required");

        byte[] src = bytes == null ? new byte[0] : bytes;
        Files.createDirectories(objectRoot);
        Path parent = destinationFile.getParent();
        if (parent == null) throw new IllegalArgumentException("destination parent is required");
        Files.createDirectories(parent);

        String sha256 = StorageCrypto.checksumSha256Hex(src);
        Path objectPath = objectPath(objectRoot, sha256);
        ensureObjectFile(objectPath, src, sha256);
        materializeReference(destinationFile, objectPath, src);
    }

    private static Path objectPath(Path objectRoot, String sha256Hex) {
        String sha = safe(sha256Hex).trim().toLowerCase(Locale.ROOT);
        if (sha.length() != 64 || !sha.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid sha256");
        }
        return objectRoot
                .resolve("sha256")
                .resolve(sha.substring(0, 2))
                .resolve(sha.substring(2, 4))
                .resolve(sha + OBJECT_EXT)
                .toAbsolutePath()
                .normalize();
    }

    private static void ensureObjectFile(Path objectPath, byte[] bytes, String expectedSha256) throws Exception {
        Files.createDirectories(objectPath.getParent());
        if (Files.exists(objectPath)) {
            verifyObjectIntegrity(objectPath, expectedSha256);
            return;
        }

        Path tmp = tempSibling(objectPath, TMP_EXT);
        try {
            Files.write(tmp, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            verifyWriteIntegrity(tmp, bytes);
            moveAtomicOrReplace(tmp, objectPath, false);
        } catch (FileAlreadyExistsException ignored) {
            // Concurrent writer already populated this object.
        } finally {
            Files.deleteIfExists(tmp);
        }
        verifyObjectIntegrity(objectPath, expectedSha256);
    }

    private static void materializeReference(Path destinationFile, Path objectPath, byte[] expected) throws Exception {
        if (!Files.exists(objectPath)) {
            throw new IllegalStateException("dedup object missing for reference materialization");
        }

        Path symlinkTemp = tempSibling(destinationFile, ".symlink");
        try {
            Path relativeTarget = symlinkTemp.getParent().relativize(objectPath);
            Files.createSymbolicLink(symlinkTemp, relativeTarget);
            moveAtomicOrReplace(symlinkTemp, destinationFile, true);
            verifyWriteIntegrity(destinationFile, expected);
            return;
        } catch (Exception ignored) {
            Files.deleteIfExists(symlinkTemp);
        }

        Path hardLinkTemp = tempSibling(destinationFile, ".hardlink");
        try {
            Files.createLink(hardLinkTemp, objectPath);
            moveAtomicOrReplace(hardLinkTemp, destinationFile, true);
            verifyWriteIntegrity(destinationFile, expected);
            return;
        } catch (Exception ignored) {
            Files.deleteIfExists(hardLinkTemp);
        }

        Files.copy(objectPath, destinationFile, StandardCopyOption.REPLACE_EXISTING);
        verifyWriteIntegrity(destinationFile, expected);
    }

    private static void verifyObjectIntegrity(Path objectPath, String expectedSha256) throws Exception {
        if (!Files.exists(objectPath) || !Files.isRegularFile(objectPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("dedup object is missing or not a regular file");
        }
        String actualSha = checksumFileSha256(objectPath);
        if (!actualSha.equalsIgnoreCase(safe(expectedSha256).trim())) {
            throw new IllegalStateException("dedup object integrity check failed");
        }
    }

    private static Path tempSibling(Path path, String tag) {
        String file = safe(path.getFileName() == null ? "" : path.getFileName().toString());
        if (file.isBlank()) file = "file";
        String nonce = UUID.randomUUID().toString().replace("-", "");
        return path.getParent().resolve("." + file + tag + "." + nonce);
    }

    private static void moveAtomicOrReplace(Path source, Path target, boolean replaceExisting) throws Exception {
        if (replaceExisting) {
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
                return;
            }
        }
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target);
        }
    }

    private static void verifyWriteIntegrity(Path p, byte[] expected) throws Exception {
        byte[] actual = Files.readAllBytes(p);
        String expectedMd5 = StorageCrypto.checksumMd5Hex(expected);
        String actualMd5 = StorageCrypto.checksumMd5Hex(actual);
        if (!expectedMd5.equals(actualMd5)) {
            throw new IllegalStateException("file write integrity check failed: md5 mismatch");
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String checksumFileSha256(Path path) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(path, StandardOpenOption.READ)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        return hex(md.digest());
    }

    private static String hex(byte[] raw) {
        byte[] src = raw == null ? new byte[0] : raw;
        StringBuilder sb = new StringBuilder(src.length * 2);
        for (int i = 0; i < src.length; i++) {
            sb.append(String.format(Locale.ROOT, "%02x", src[i]));
        }
        return sb.toString();
    }
}
