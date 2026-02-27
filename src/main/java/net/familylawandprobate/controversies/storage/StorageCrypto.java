package net.familylawandprobate.controversies.storage;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

public final class StorageCrypto {
    private static final byte[] MAGIC = new byte[] {'C', 'V', 'E', '1'};

    public byte[] encrypt(byte[] plaintext, String keyMaterial) throws Exception {
        byte[] src = plaintext == null ? new byte[0] : plaintext;
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(deriveKey(keyMaterial), "AES"), new GCMParameterSpec(128, iv));
        byte[] encrypted = cipher.doFinal(src);

        byte[] out = new byte[MAGIC.length + iv.length + encrypted.length];
        System.arraycopy(MAGIC, 0, out, 0, MAGIC.length);
        System.arraycopy(iv, 0, out, MAGIC.length, iv.length);
        System.arraycopy(encrypted, 0, out, MAGIC.length + iv.length, encrypted.length);
        return out;
    }

    public byte[] decrypt(byte[] encryptedBlob, String keyMaterial) throws Exception {
        byte[] src = encryptedBlob == null ? new byte[0] : encryptedBlob;
        if (src.length < MAGIC.length + 12 + 16) throw new IllegalArgumentException("ciphertext too short");
        if (!Arrays.equals(MAGIC, Arrays.copyOfRange(src, 0, MAGIC.length))) throw new IllegalArgumentException("ciphertext header invalid");

        byte[] iv = Arrays.copyOfRange(src, MAGIC.length, MAGIC.length + 12);
        byte[] ciphertext = Arrays.copyOfRange(src, MAGIC.length + 12, src.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(deriveKey(keyMaterial), "AES"), new GCMParameterSpec(128, iv));
        return cipher.doFinal(ciphertext);
    }

    public static String checksumSha256Hex(byte[] bytes) throws Exception {
        return checksumHex("SHA-256", bytes);
    }

    public static String checksumMd5Hex(byte[] bytes) throws Exception {
        return checksumHex("MD5", bytes);
    }

    private static String checksumHex(String algorithm, byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance(algorithm);
        byte[] out = digest.digest(bytes == null ? new byte[0] : bytes);
        StringBuilder sb = new StringBuilder(out.length * 2);
        for (int i = 0; i < out.length; i++) sb.append(String.format("%02x", out[i]));
        return sb.toString();
    }

    private byte[] deriveKey(String keyMaterial) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return digest.digest((keyMaterial == null ? "" : keyMaterial).getBytes(StandardCharsets.UTF_8));
    }
}
