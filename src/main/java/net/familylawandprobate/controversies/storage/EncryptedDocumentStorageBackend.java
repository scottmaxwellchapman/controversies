package net.familylawandprobate.controversies.storage;

import java.util.LinkedHashMap;
import java.util.Map;

public final class EncryptedDocumentStorageBackend implements DocumentStorageBackend {
    private final DocumentStorageBackend delegate;
    private final StorageCrypto crypto;
    private final String encryptionMode;
    private final String encryptionKey;
    private final String s3SseMode;
    private final String s3SseKmsKeyId;

    public EncryptedDocumentStorageBackend(DocumentStorageBackend delegate,
                                           String encryptionMode,
                                           String encryptionKey,
                                           String s3SseMode,
                                           String s3SseKmsKeyId) {
        this.delegate = delegate;
        this.crypto = new StorageCrypto();
        this.encryptionMode = safe(encryptionMode).trim().toLowerCase();
        this.encryptionKey = safe(encryptionKey);
        this.s3SseMode = safe(s3SseMode).trim().toLowerCase();
        this.s3SseKmsKeyId = safe(s3SseKmsKeyId).trim();
    }

    @Override
    public String put(String key, byte[] bytes) throws Exception {
        byte[] src = bytes == null ? new byte[0] : bytes;
        if (isAppEncryptionEnabled()) {
            return delegate.put(key, crypto.encrypt(src, encryptionKey));
        }
        return delegate.put(key, src);
    }

    @Override
    public byte[] get(String key) throws Exception {
        byte[] stored = delegate.get(key);
        if (!isAppEncryptionEnabled()) return stored;
        return crypto.decrypt(stored, encryptionKey);
    }

    @Override
    public void delete(String key) throws Exception {
        delegate.delete(key);
    }

    @Override
    public boolean exists(String key) throws Exception {
        return delegate.exists(key);
    }

    @Override
    public Map<String, String> metadata(String key) throws Exception {
        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
        out.putAll(delegate.metadata(key));
        out.put("app_encryption", isAppEncryptionEnabled() ? "tenant_managed" : "disabled");
        if (isAppEncryptionEnabled()) {
            byte[] decrypted = get(key);
            out.put("checksum_sha256", StorageCrypto.checksumSha256Hex(decrypted));
            out.put("checksum_md5", StorageCrypto.checksumMd5Hex(decrypted));
            out.put("plaintext_size_bytes", Long.toString(decrypted.length));
        }
        if (!"none".equals(s3SseMode)) {
            out.put("s3_sse_mode", s3SseMode);
            if ("aws_kms".equals(s3SseMode) && !s3SseKmsKeyId.isBlank()) {
                out.put("s3_sse_kms_key_id", s3SseKmsKeyId);
            }
        }
        return out;
    }

    private boolean isAppEncryptionEnabled() {
        return "tenant_managed".equals(encryptionMode) && !encryptionKey.isBlank();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
