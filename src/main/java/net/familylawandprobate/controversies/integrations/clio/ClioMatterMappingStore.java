package net.familylawandprobate.controversies.integrations.clio;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class ClioMatterMappingStore {

    public static final class MappingRec {
        public final String localMatterUuid;
        public final String clioMatterId;
        public final String updatedAt;

        public MappingRec(String localMatterUuid, String clioMatterId, String updatedAt) {
            this.localMatterUuid = safe(localMatterUuid).trim();
            this.clioMatterId = safe(clioMatterId).trim();
            this.updatedAt = safe(updatedAt).trim();
        }
    }

    public synchronized LinkedHashMap<String, String> all(String tenantUuid) {
        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
        try {
            Path p = path(tenantUuid);
            if (!Files.exists(p)) return out;
            String xml = Files.readString(p, StandardCharsets.UTF_8);
            String[] rows = xml.split("<mapping>");
            for (int i = 1; i < rows.length; i++) {
                String row = rows[i];
                String local = text(row, "local_matter_uuid");
                String clio = text(row, "clio_matter_id");
                if (!local.isBlank() && !clio.isBlank()) out.put(local, clio);
            }
        } catch (Exception ignored) {
            return out;
        }
        return out;
    }

    public synchronized String clioMatterId(String tenantUuid, String localMatterUuid) {
        return all(tenantUuid).getOrDefault(safe(localMatterUuid).trim(), "");
    }

    public synchronized void upsert(String tenantUuid, String localMatterUuid, String clioMatterId) throws Exception {
        LinkedHashMap<String, String> all = all(tenantUuid);
        String local = safe(localMatterUuid).trim();
        String clio = safe(clioMatterId).trim();
        if (local.isBlank() || clio.isBlank()) return;
        all.put(local, clio);
        write(tenantUuid, all);
    }

    private void write(String tenantUuid, Map<String, String> mappings) throws Exception {
        Path p = path(tenantUuid);
        Files.createDirectories(p.getParent());
        String now = Instant.now().toString();
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<clio_matter_mappings updated=\"").append(xml(now)).append("\">\n");
        for (Map.Entry<String, String> e : mappings.entrySet()) {
            if (e == null) continue;
            if (safe(e.getKey()).trim().isBlank() || safe(e.getValue()).trim().isBlank()) continue;
            sb.append("  <mapping>\n");
            sb.append("    <local_matter_uuid>").append(xml(e.getKey())).append("</local_matter_uuid>\n");
            sb.append("    <clio_matter_id>").append(xml(e.getValue())).append("</clio_matter_id>\n");
            sb.append("  </mapping>\n");
        }
        sb.append("</clio_matter_mappings>\n");

        Path tmp = p.resolveSibling(p.getFileName().toString() + ".tmp." + UUID.randomUUID());
        Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        try {
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception ignored) {
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Path path(String tenantUuid) {
        return Paths.get("data", "tenants", safe(tenantUuid).replaceAll("[^A-Za-z0-9._-]", "_"), "integrations", "clio_matter_mappings.xml").toAbsolutePath();
    }

    private static String text(String xml, String tag) {
        String s = safe(xml);
        String start = "<" + tag + ">";
        String end = "</" + tag + ">";
        int i = s.indexOf(start);
        if (i < 0) return "";
        int j = s.indexOf(end, i + start.length());
        if (j < 0) return "";
        return s.substring(i + start.length(), j).trim();
    }

    private static String xml(String s) {
        return safe(s).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
