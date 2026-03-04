package net.familylawandprobate.controversies.integrations.clio;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.UUID;

/**
 * Stores full Clio matter payload snapshots per tenant.
 * Files live under:
 *   data/tenants/{tenantUuid}/integrations/clio/matters/{clioMatterId}.json
 *   data/tenants/{tenantUuid}/integrations/clio/matters/_sync_manifest.json
 */
public final class ClioMatterSnapshotStore {

    public synchronized void upsert(String tenantUuid,
                                    String clioMatterId,
                                    String rawMatterJson,
                                    String clioUpdatedAt,
                                    String syncedAt) throws Exception {
        String tu = safeToken(tenantUuid);
        String clioId = safe(clioMatterId).trim();
        if (tu.isBlank() || clioId.isBlank()) return;

        Path dir = matterDir(tu);
        Files.createDirectories(dir);

        Path p = dir.resolve(safeToken(clioId) + ".json");
        StringBuilder sb = new StringBuilder(Math.max(256, safe(rawMatterJson).length() + 128));
        sb.append("{\n");
        sb.append("  \"clio_matter_id\": \"").append(json(clioId)).append("\",\n");
        sb.append("  \"clio_updated_at\": \"").append(json(safe(clioUpdatedAt).trim())).append("\",\n");
        sb.append("  \"synced_at\": \"").append(json(safe(syncedAt).trim())).append("\",\n");
        sb.append("  \"matter\": ").append(safe(rawMatterJson).isBlank() ? "{}" : safe(rawMatterJson)).append("\n");
        sb.append("}\n");
        writeAtomic(p, sb.toString());
    }

    public synchronized void writeManifest(String tenantUuid,
                                           int syncedCount,
                                           String syncedAt) throws Exception {
        String tu = safeToken(tenantUuid);
        if (tu.isBlank()) return;
        Path p = matterDir(tu).resolve("_sync_manifest.json");
        Files.createDirectories(p.getParent());
        String body = "{\n"
                + "  \"last_synced_at\": \"" + json(safe(syncedAt).trim()) + "\",\n"
                + "  \"synced_count\": " + Math.max(0, syncedCount) + "\n"
                + "}\n";
        writeAtomic(p, body);
    }

    private static Path matterDir(String tenantUuid) {
        return Paths.get("data", "tenants", safeToken(tenantUuid), "integrations", "clio", "matters").toAbsolutePath();
    }

    private static void writeAtomic(Path p, String content) throws Exception {
        Path tmp = p.resolveSibling(p.getFileName().toString() + ".tmp." + UUID.randomUUID());
        Files.writeString(
                tmp,
                safe(content),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        );
        try {
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception ignored) {
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String json(String s) {
        return safe(s)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
    }

    private static String safeToken(String s) {
        return safe(s).trim().replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
