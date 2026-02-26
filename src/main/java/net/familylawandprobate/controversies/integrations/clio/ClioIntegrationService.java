package net.familylawandprobate.controversies.integrations.clio;

import net.familylawandprobate.controversies.activity_log;
import net.familylawandprobate.controversies.matters;
import net.familylawandprobate.controversies.tenant_settings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;

public final class ClioIntegrationService {

    private final tenant_settings settingsStore;
    private final matters matterStore;
    private final ClioMatterMappingStore mappingStore;

    public ClioIntegrationService() {
        this.settingsStore = tenant_settings.defaultStore();
        this.matterStore = matters.defaultStore();
        this.mappingStore = new ClioMatterMappingStore();
    }

    public boolean isEnabled(String tenantUuid) {
        LinkedHashMap<String, String> cfg = settingsStore.read(tenantUuid);
        return "true".equalsIgnoreCase(cfg.getOrDefault("clio_enabled", "false"));
    }

    public int syncEnabledTenantsScheduled() {
        int total = 0;
        try {
            Path tenantsRoot = Paths.get("data", "tenants").toAbsolutePath();
            if (!Files.exists(tenantsRoot)) return 0;
            for (Path p : Files.list(tenantsRoot).toList()) {
                if (p == null || !Files.isDirectory(p)) continue;
                String tenantUuid = p.getFileName().toString();
                total += syncMatters(tenantUuid, false);
            }
        } catch (Exception ignored) {
            return total;
        }
        return total;
    }

    public int syncMatters(String tenantUuid, boolean manualRun) throws Exception {
        if (!isEnabled(tenantUuid)) return 0;
        LinkedHashMap<String, String> cfg = settingsStore.read(tenantUuid);
        String accessToken = safe(cfg.get("clio_access_token"));
        if (accessToken.isBlank()) return 0;

        ClioClient client = new ClioClient(cfg.get("clio_base_url"));
        String updatedSince = safe(cfg.get("clio_matters_last_sync_at"));
        int page = 1;
        int imported = 0;

        while (true) {
            List<ClioMatter> pageRows = client.listMatters(accessToken, updatedSince, page, 100);
            if (pageRows.isEmpty()) break;
            for (ClioMatter cm : pageRows) {
                if (upsertMatterFromClio(tenantUuid, cm)) imported++;
            }
            if (pageRows.size() < 100) break;
            page++;
        }

        LinkedHashMap<String, String> nextCfg = new LinkedHashMap<String, String>(cfg);
        nextCfg.put("clio_matters_last_sync_at", Instant.now().toString());
        settingsStore.write(tenantUuid, nextCfg);

        if (manualRun) {
            activity_log.defaultStore().logVerbose("clio.matters.sync.manual", tenantUuid, "system", "", "", java.util.Map.of("imported", String.valueOf(imported)));
        } else {
            activity_log.defaultStore().logVerbose("clio.matters.sync.scheduled", tenantUuid, "system", "", "", java.util.Map.of("imported", String.valueOf(imported)));
        }
        return imported;
    }

    public void enqueueUploadTaskIfEligible(String tenantUuid, String localMatterUuid, String assemblyUuid) {
        try {
            if (!isEnabled(tenantUuid)) return;
            LinkedHashMap<String, String> cfg = settingsStore.read(tenantUuid);
            String mode = safe(cfg.get("clio_storage_mode")).trim().toLowerCase();
            if (!"enabled".equals(mode)) return;
            String clioMatterId = mappingStore.clioMatterId(tenantUuid, localMatterUuid);
            if (clioMatterId.isBlank()) return;

            activity_log.defaultStore().logVerbose(
                    "clio.upload.enqueued",
                    tenantUuid,
                    "system",
                    localMatterUuid,
                    assemblyUuid,
                    java.util.Map.of("clioMatterId", clioMatterId)
            );
        } catch (Exception ignored) {
        }
    }

    private boolean upsertMatterFromClio(String tenantUuid, ClioMatter clioMatter) throws Exception {
        String clioId = safe(clioMatter.id).trim();
        if (clioId.isBlank()) return false;

        List<matters.MatterRec> all = matterStore.listAll(tenantUuid);
        matters.MatterRec existing = null;
        for (matters.MatterRec m : all) {
            if (m == null) continue;
            if ("clio".equalsIgnoreCase(safe(m.source)) && clioId.equals(safe(m.sourceMatterId))) {
                existing = m;
                break;
            }
        }

        String canonical = clioMatter.label();
        if (canonical.isBlank()) canonical = "Clio Matter " + clioId;

        if (existing == null) {
            matters.MatterRec created = matterStore.create(
                    tenantUuid,
                    canonical,
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    ""
            );
            matterStore.update(
                    tenantUuid,
                    created.uuid,
                    canonical,
                    created.jurisdictionUuid,
                    created.matterCategoryUuid,
                    created.matterSubcategoryUuid,
                    created.matterStatusUuid,
                    created.matterSubstatusUuid,
                    created.causeDocketNumber,
                    created.county
            );
            matterStore.updateSourceMetadata(tenantUuid, created.uuid, "clio", clioId, canonical, clioMatter.updatedAt);
            mappingStore.upsert(tenantUuid, created.uuid, clioId);
            return true;
        }

        boolean localChangedLabel = !safe(existing.label).trim().equals(safe(existing.clioCanonicalLabel).trim())
                && !safe(existing.label).trim().isBlank();

        String nextLabel = localChangedLabel ? existing.label : canonical;
        matterStore.update(
                tenantUuid,
                existing.uuid,
                nextLabel,
                existing.jurisdictionUuid,
                existing.matterCategoryUuid,
                existing.matterSubcategoryUuid,
                existing.matterStatusUuid,
                existing.matterSubstatusUuid,
                existing.causeDocketNumber,
                existing.county
        );

        matterStore.updateSourceMetadata(tenantUuid, existing.uuid, "clio", clioId, canonical, clioMatter.updatedAt);
        mappingStore.upsert(tenantUuid, existing.uuid, clioId);
        return true;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
