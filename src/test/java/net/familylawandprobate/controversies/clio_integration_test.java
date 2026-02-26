package net.familylawandprobate.controversies;

import net.familylawandprobate.controversies.integrations.clio.ClioMatterMappingStore;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class clio_integration_test {

    @Test
    void mapping_store_persists_local_to_clio_ids() throws Exception {
        String tenantUuid = "tenant-clio-map";
        Path root = Paths.get("data", "tenants", tenantUuid);
        if (Files.exists(root)) {
            Files.walk(root)
                    .sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                    });
        }

        ClioMatterMappingStore store = new ClioMatterMappingStore();
        store.upsert(tenantUuid, "local-1", "clio-900");
        assertEquals("clio-900", store.clioMatterId(tenantUuid, "local-1"));

        store.upsert(tenantUuid, "local-1", "clio-901");
        assertEquals("clio-901", store.clioMatterId(tenantUuid, "local-1"));
    }
}
