package net.familylawandprobate.controversies.integrations.clio;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ClioDocumentVersion {
    public final String id;
    public final String uuid;
    public final String versionNumber;
    public final String name;
    public final String filename;
    public final String contentType;
    public final long sizeBytes;
    public final String createdAt;
    public final String updatedAt;
    public final boolean fullyUploaded;

    public ClioDocumentVersion(String id,
                               String uuid,
                               String versionNumber,
                               String name,
                               String filename,
                               String contentType,
                               long sizeBytes,
                               String createdAt,
                               String updatedAt,
                               boolean fullyUploaded) {
        this.id = safe(id);
        this.uuid = safe(uuid);
        this.versionNumber = safe(versionNumber);
        this.name = safe(name);
        this.filename = safe(filename);
        this.contentType = safe(contentType);
        this.sizeBytes = Math.max(0L, sizeBytes);
        this.createdAt = safe(createdAt);
        this.updatedAt = safe(updatedAt);
        this.fullyUploaded = fullyUploaded;
    }

    public String sourceKey() {
        String v = safe(id).trim();
        if (v.isBlank()) return "";
        return "clio_version:" + v;
    }

    public String versionLabel() {
        String n = safe(versionNumber).trim();
        if (!n.isBlank()) return "v" + n;
        String nameVal = safe(name).trim();
        if (!nameVal.isBlank()) return nameVal;
        String file = safe(filename).trim();
        if (!file.isBlank()) return file;
        String i = safe(id).trim();
        if (!i.isBlank()) return "v" + i;
        return "v1";
    }

    public String preferredFileName() {
        String f = safe(filename).trim();
        if (!f.isBlank()) return f;
        String n = safe(name).trim();
        if (!n.isBlank()) return n;
        String i = safe(id).trim();
        if (!i.isBlank()) return "clio-version-" + i;
        return "clio-version.bin";
    }

    public static List<ClioDocumentVersion> listFromJson(String json) {
        List<ClioDocumentVersion> out = new ArrayList<ClioDocumentVersion>();
        for (String obj : JsonHelper.dataObjects(json)) {
            out.add(fromObjectJson(obj));
        }
        return out;
    }

    public static ClioDocumentVersion fromObjectJson(String obj) {
        return new ClioDocumentVersion(
                JsonHelper.stringValue(obj, "id"),
                JsonHelper.stringValue(obj, "uuid"),
                firstNonBlank(
                        JsonHelper.stringValue(obj, "version_number"),
                        JsonHelper.stringValue(obj, "number")
                ),
                JsonHelper.stringValue(obj, "name"),
                JsonHelper.stringValue(obj, "filename"),
                JsonHelper.stringValue(obj, "content_type"),
                firstPositive(
                        JsonHelper.longValue(obj, "size"),
                        JsonHelper.longValue(obj, "size_in_bytes")
                ),
                JsonHelper.stringValue(obj, "created_at"),
                JsonHelper.stringValue(obj, "updated_at"),
                JsonHelper.boolValue(obj, "fully_uploaded")
        );
    }

    public static int compareByVersionHint(ClioDocumentVersion a, ClioDocumentVersion b) {
        int av = parseVersionHint(a == null ? "" : a.versionNumber);
        int bv = parseVersionHint(b == null ? "" : b.versionNumber);
        if (av != bv) return Integer.compare(av, bv);
        String ac = safe(a == null ? "" : a.createdAt);
        String bc = safe(b == null ? "" : b.createdAt);
        return ac.compareTo(bc);
    }

    private static int parseVersionHint(String raw) {
        String v = safe(raw).trim().toLowerCase(Locale.ROOT);
        if (v.isBlank()) return -1;
        String digits = v.replaceAll("[^0-9]", "");
        if (digits.isBlank()) return -1;
        try {
            return Integer.parseInt(digits);
        } catch (Exception ignored) {
            return -1;
        }
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
