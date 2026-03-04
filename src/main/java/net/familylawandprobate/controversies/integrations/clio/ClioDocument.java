package net.familylawandprobate.controversies.integrations.clio;

import java.util.ArrayList;
import java.util.List;

public final class ClioDocument {
    public final String id;
    public final String name;
    public final String filename;
    public final String contentType;
    public final long sizeBytes;
    public final String createdAt;
    public final String updatedAt;
    public final String latestVersionId;

    public ClioDocument(String id,
                        String name,
                        String filename,
                        String contentType,
                        long sizeBytes,
                        String createdAt,
                        String updatedAt,
                        String latestVersionId) {
        this.id = safe(id);
        this.name = safe(name);
        this.filename = safe(filename);
        this.contentType = safe(contentType);
        this.sizeBytes = Math.max(0L, sizeBytes);
        this.createdAt = safe(createdAt);
        this.updatedAt = safe(updatedAt);
        this.latestVersionId = safe(latestVersionId);
    }

    public String label() {
        String n = safe(name).trim();
        if (!n.isBlank()) return n;
        String f = safe(filename).trim();
        if (!f.isBlank()) return f;
        String i = safe(id).trim();
        if (!i.isBlank()) return "Clio Document " + i;
        return "Clio Document";
    }

    public static List<ClioDocument> listFromJson(String json) {
        List<ClioDocument> out = new ArrayList<ClioDocument>();
        for (String obj : JsonHelper.dataObjects(json)) {
            String latestVersionId = "";
            String latest = JsonHelper.objectValue(obj, "latest_document_version");
            if (!latest.isBlank()) latestVersionId = JsonHelper.stringValue(latest, "id");

            out.add(new ClioDocument(
                    JsonHelper.stringValue(obj, "id"),
                    firstNonBlank(
                            JsonHelper.stringValue(obj, "name"),
                            JsonHelper.stringValue(obj, "title"),
                            JsonHelper.stringValue(obj, "description")
                    ),
                    JsonHelper.stringValue(obj, "filename"),
                    JsonHelper.stringValue(obj, "content_type"),
                    firstPositive(
                            JsonHelper.longValue(obj, "size"),
                            JsonHelper.longValue(obj, "size_in_bytes")
                    ),
                    JsonHelper.stringValue(obj, "created_at"),
                    JsonHelper.stringValue(obj, "updated_at"),
                    latestVersionId
            ));
        }
        return out;
    }

    private static String firstNonBlank(String... xs) {
        if (xs == null) return "";
        for (String x : xs) {
            String v = safe(x).trim();
            if (!v.isBlank()) return v;
        }
        return "";
    }

    private static long firstPositive(long... xs) {
        if (xs == null) return 0L;
        for (long x : xs) {
            if (x > 0L) return x;
        }
        return 0L;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
