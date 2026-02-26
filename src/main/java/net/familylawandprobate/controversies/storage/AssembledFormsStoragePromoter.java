package net.familylawandprobate.controversies.storage;

import net.familylawandprobate.controversies.assembled_forms;

import java.util.List;
import java.util.Map;

/**
 * Incremental/no-downtime promoter for existing assembled file blobs.
 */
public final class AssembledFormsStoragePromoter {
    private final StorageBackendResolver resolver;

    public AssembledFormsStoragePromoter() {
        this(new StorageBackendResolver());
    }

    public AssembledFormsStoragePromoter(StorageBackendResolver resolver) {
        this.resolver = resolver == null ? new StorageBackendResolver() : resolver;
    }

    public int promoteMatter(String tenantUuid, String matterUuid, String targetBackendType, int limit) throws Exception {
        int max = limit <= 0 ? Integer.MAX_VALUE : limit;
        int moved = 0;

        assembled_forms store = assembled_forms.defaultStore();
        List<assembled_forms.AssemblyRec> rows = store.listByMatter(tenantUuid, matterUuid);
        for (int i = 0; i < rows.size() && moved < max; i++) {
            assembled_forms.AssemblyRec rec = rows.get(i);
            if (rec == null) continue;
            if (!"completed".equals(rec.status)) continue;
            if (targetBackendType.equalsIgnoreCase(rec.storageBackendType) && !rec.storageObjectKey.isBlank()) continue;

            byte[] bytes = store.readOutputBytes(tenantUuid, matterUuid, rec.uuid);
            if (bytes.length == 0) continue;

            DocumentStorageBackend backend = resolver.resolve(tenantUuid, matterUuid, targetBackendType);
            String ext = rec.outputFileExt == null || rec.outputFileExt.isBlank() ? "txt" : rec.outputFileExt;
            String key = "matters/" + matterUuid + "/assembled/files/" + rec.uuid + "." + ext;
            key = backend.put(key, bytes);
            Map<String, String> md = backend.metadata(key);

            store.rebindStorageMetadata(
                    tenantUuid,
                    matterUuid,
                    rec.uuid,
                    targetBackendType,
                    key,
                    md.getOrDefault("checksum_sha256", "")
            );
            moved++;
        }
        return moved;
    }
}
