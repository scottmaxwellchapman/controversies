package net.familylawandprobate.controversies.integrations.clio;

import java.util.ArrayList;
import java.util.List;

public final class ClioMatter {
    public final String id;
    public final String number;
    public final String displayNumber;
    public final String customNumber;
    public final String description;
    public final String status;
    public final String location;
    public final String clientReference;
    public final String clientId;
    public final String billable;
    public final String billingMethod;
    public final String openDate;
    public final String closeDate;
    public final String pendingDate;
    public final String createdAt;
    public final String updatedAt;
    public final String rawJson;

    public ClioMatter(String id,
                      String number,
                      String displayNumber,
                      String customNumber,
                      String description,
                      String status,
                      String location,
                      String clientReference,
                      String clientId,
                      String billable,
                      String billingMethod,
                      String openDate,
                      String closeDate,
                      String pendingDate,
                      String createdAt,
                      String updatedAt,
                      String rawJson) {
        this.id = safe(id);
        this.number = safe(number);
        this.displayNumber = safe(displayNumber);
        this.customNumber = safe(customNumber);
        this.description = safe(description);
        this.status = safe(status);
        this.location = safe(location);
        this.clientReference = safe(clientReference);
        this.clientId = safe(clientId);
        this.billable = safe(billable);
        this.billingMethod = safe(billingMethod);
        this.openDate = safe(openDate);
        this.closeDate = safe(closeDate);
        this.pendingDate = safe(pendingDate);
        this.createdAt = safe(createdAt);
        this.updatedAt = safe(updatedAt);
        this.rawJson = safe(rawJson);
    }

    public String label() {
        String number = safe(displayNumber).trim();
        if (number.isBlank()) number = safe(customNumber).trim();
        if (number.isBlank()) number = safe(this.number).trim();
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
                    JsonHelper.stringValue(obj, "number"),
                    JsonHelper.stringValue(obj, "display_number"),
                    JsonHelper.stringValue(obj, "custom_number"),
                    JsonHelper.stringValue(obj, "description"),
                    JsonHelper.stringValue(obj, "status"),
                    JsonHelper.stringValue(obj, "location"),
                    JsonHelper.stringValue(obj, "client_reference"),
                    JsonHelper.stringValue(obj, "client_id"),
                    JsonHelper.stringValue(obj, "billable"),
                    JsonHelper.stringValue(obj, "billing_method"),
                    JsonHelper.stringValue(obj, "open_date"),
                    JsonHelper.stringValue(obj, "close_date"),
                    JsonHelper.stringValue(obj, "pending_date"),
                    JsonHelper.stringValue(obj, "created_at"),
                    JsonHelper.stringValue(obj, "updated_at"),
                    obj
            ));
        }
        return out;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
