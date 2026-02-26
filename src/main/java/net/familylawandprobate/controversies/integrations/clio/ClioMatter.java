package net.familylawandprobate.controversies.integrations.clio;

import java.util.ArrayList;
import java.util.List;

public final class ClioMatter {
    public final String id;
    public final String displayNumber;
    public final String description;
    public final String updatedAt;

    public ClioMatter(String id, String displayNumber, String description, String updatedAt) {
        this.id = safe(id);
        this.displayNumber = safe(displayNumber);
        this.description = safe(description);
        this.updatedAt = safe(updatedAt);
    }

    public String label() {
        String number = safe(displayNumber).trim();
        String text = safe(description).trim();
        if (!number.isBlank() && !text.isBlank()) return number + " - " + text;
        if (!number.isBlank()) return number;
        return text;
    }

    public static List<ClioMatter> listFromJson(String json) {
        List<ClioMatter> out = new ArrayList<ClioMatter>();
        for (String obj : JsonHelper.dataObjects(json)) {
            out.add(new ClioMatter(
                    JsonHelper.stringValue(obj, "id"),
                    JsonHelper.stringValue(obj, "display_number"),
                    JsonHelper.stringValue(obj, "description"),
                    JsonHelper.stringValue(obj, "updated_at")
            ));
        }
        return out;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
