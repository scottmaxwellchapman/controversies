package net.familylawandprobate.controversies.integrations.clio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

public final class ClioContact {
    private static final ObjectMapper JSON = new ObjectMapper();

    public final String id;
    public final String displayName;
    public final String givenName;
    public final String middleName;
    public final String surname;
    public final String companyName;
    public final String jobTitle;
    public final String emailPrimary;
    public final String emailSecondary;
    public final String emailTertiary;
    public final String businessPhone;
    public final String businessPhone2;
    public final String mobilePhone;
    public final String homePhone;
    public final String otherPhone;
    public final String website;
    public final String street;
    public final String city;
    public final String state;
    public final String postalCode;
    public final String country;
    public final String notes;
    public final String updatedAt;

    public ClioContact(String id,
                       String displayName,
                       String givenName,
                       String middleName,
                       String surname,
                       String companyName,
                       String jobTitle,
                       String emailPrimary,
                       String emailSecondary,
                       String emailTertiary,
                       String businessPhone,
                       String businessPhone2,
                       String mobilePhone,
                       String homePhone,
                       String otherPhone,
                       String website,
                       String street,
                       String city,
                       String state,
                       String postalCode,
                       String country,
                       String notes,
                       String updatedAt) {
        this.id = safe(id);
        this.displayName = safe(displayName);
        this.givenName = safe(givenName);
        this.middleName = safe(middleName);
        this.surname = safe(surname);
        this.companyName = safe(companyName);
        this.jobTitle = safe(jobTitle);
        this.emailPrimary = safe(emailPrimary);
        this.emailSecondary = safe(emailSecondary);
        this.emailTertiary = safe(emailTertiary);
        this.businessPhone = safe(businessPhone);
        this.businessPhone2 = safe(businessPhone2);
        this.mobilePhone = safe(mobilePhone);
        this.homePhone = safe(homePhone);
        this.otherPhone = safe(otherPhone);
        this.website = safe(website);
        this.street = safe(street);
        this.city = safe(city);
        this.state = safe(state);
        this.postalCode = safe(postalCode);
        this.country = safe(country);
        this.notes = safe(notes);
        this.updatedAt = safe(updatedAt);
    }

    public static List<ClioContact> listFromJson(String json) {
        ArrayList<ClioContact> out = new ArrayList<ClioContact>();
        try {
            JsonNode root = JSON.readTree(safe(json));
            JsonNode data = root == null ? null : root.path("data");
            if (data == null || !data.isArray()) return out;
            for (int i = 0; i < data.size(); i++) {
                JsonNode node = data.get(i);
                if (node == null || node.isMissingNode()) continue;
                ClioContact parsed = fromNode(node);
                if (safe(parsed.id).isBlank()) continue;
                out.add(parsed);
            }
        } catch (Exception ignored) {
            return out;
        }
        return out;
    }

    private static ClioContact fromNode(JsonNode node) {
        String id = text(node, "id");
        String displayName = firstNonBlank(
                text(node, "name"),
                text(node, "display_name"),
                (text(node, "first_name") + " " + text(node, "last_name")).trim(),
                text(node, "company_name")
        );
        String givenName = firstNonBlank(text(node, "first_name"), text(node, "given_name"));
        String middleName = firstNonBlank(text(node, "middle_name"), text(node, "middle"));
        String surname = firstNonBlank(text(node, "last_name"), text(node, "surname"));
        String companyName = firstNonBlank(text(node, "company_name"), text(node, "company", "name"));
        String jobTitle = firstNonBlank(text(node, "title"), text(node, "job_title"));

        List<String> emails = collectEmailAddresses(node);
        String emailPrimary = emails.size() > 0 ? emails.get(0) : "";
        String emailSecondary = emails.size() > 1 ? emails.get(1) : "";
        String emailTertiary = emails.size() > 2 ? emails.get(2) : "";

        PhonePack phones = collectPhones(node);
        AddressPack addr = collectAddress(node);

        return new ClioContact(
                id,
                displayName,
                givenName,
                middleName,
                surname,
                companyName,
                jobTitle,
                emailPrimary,
                emailSecondary,
                emailTertiary,
                phones.business1,
                phones.business2,
                phones.mobile,
                phones.home,
                phones.other,
                text(node, "web_site"),
                addr.street,
                addr.city,
                addr.state,
                addr.postalCode,
                addr.country,
                firstNonBlank(text(node, "note"), text(node, "notes")),
                text(node, "updated_at")
        );
    }

    private static List<String> collectEmailAddresses(JsonNode node) {
        ArrayList<String> out = new ArrayList<String>();
        String primaryObjectAddress = text(node, "primary_email_address", "address");
        if (!primaryObjectAddress.isBlank()) out.add(primaryObjectAddress);
        String primaryRaw = text(node, "primary_email_address");
        if (!primaryRaw.isBlank() && !containsIgnoreCase(out, primaryRaw)) out.add(primaryRaw);

        JsonNode all = node == null ? null : node.path("email_addresses");
        if (all != null && all.isArray()) {
            for (int i = 0; i < all.size(); i++) {
                String address = firstNonBlank(
                        text(all.get(i), "address"),
                        text(all.get(i), "email"),
                        text(all.get(i), "value")
                );
                if (!address.isBlank() && !containsIgnoreCase(out, address)) out.add(address);
            }
        }

        String fallback = text(node, "email");
        if (!fallback.isBlank() && !containsIgnoreCase(out, fallback)) out.add(fallback);
        return out;
    }

    private static PhonePack collectPhones(JsonNode node) {
        String business1 = "";
        String business2 = "";
        String mobile = "";
        String home = "";
        String other = "";

        JsonNode all = node == null ? null : node.path("phone_numbers");
        if (all != null && all.isArray()) {
            for (int i = 0; i < all.size(); i++) {
                JsonNode item = all.get(i);
                if (item == null || item.isMissingNode()) continue;
                String label = safe(firstNonBlank(text(item, "name"), text(item, "label"))).toLowerCase();
                String number = firstNonBlank(text(item, "number"), text(item, "value"), text(item, "phone"));
                if (number.isBlank()) continue;

                if (mobile.isBlank() && (label.contains("mobile") || label.contains("cell"))) {
                    mobile = number;
                } else if (home.isBlank() && label.contains("home")) {
                    home = number;
                } else if (label.contains("work") || label.contains("business") || label.contains("office")) {
                    if (business1.isBlank()) business1 = number;
                    else if (business2.isBlank()) business2 = number;
                } else if (other.isBlank()) {
                    other = number;
                }
            }
        }

        if (business1.isBlank()) business1 = firstNonBlank(text(node, "phone"), text(node, "work_phone"));
        if (mobile.isBlank()) mobile = firstNonBlank(text(node, "mobile_phone"), text(node, "cell_phone"));
        if (home.isBlank()) home = text(node, "home_phone");

        return new PhonePack(business1, business2, mobile, home, other);
    }

    private static AddressPack collectAddress(JsonNode node) {
        JsonNode addresses = node == null ? null : node.path("addresses");
        JsonNode chosen = null;
        if (addresses != null && addresses.isArray() && addresses.size() > 0) {
            chosen = addresses.get(0);
            for (int i = 0; i < addresses.size(); i++) {
                JsonNode item = addresses.get(i);
                String name = safe(firstNonBlank(text(item, "name"), text(item, "label"))).toLowerCase();
                if (name.contains("work") || name.contains("business")) {
                    chosen = item;
                    break;
                }
            }
        }
        if (chosen == null || chosen.isMissingNode()) {
            chosen = node == null ? null : node.path("address");
        }
        if (chosen == null) chosen = node;
        return new AddressPack(
                firstNonBlank(text(chosen, "street"), text(chosen, "street_1"), text(chosen, "line1")),
                firstNonBlank(text(chosen, "city"), text(chosen, "town")),
                firstNonBlank(text(chosen, "province"), text(chosen, "state"), text(chosen, "region")),
                firstNonBlank(text(chosen, "postal_code"), text(chosen, "zip")),
                firstNonBlank(text(chosen, "country"), text(chosen, "country_name"))
        );
    }

    private static String text(JsonNode node, String... path) {
        if (node == null || path == null || path.length == 0) return "";
        JsonNode cur = node;
        for (String part : path) {
            if (cur == null) return "";
            cur = cur.path(safe(part));
        }
        if (cur == null || cur.isMissingNode() || cur.isNull()) return "";
        if (cur.isTextual()) return safe(cur.asText());
        if (cur.isNumber() || cur.isBoolean()) return safe(cur.asText());
        return "";
    }

    private static boolean containsIgnoreCase(List<String> xs, String value) {
        String v = safe(value).trim().toLowerCase();
        if (v.isBlank() || xs == null) return false;
        for (String x : xs) {
            if (v.equals(safe(x).trim().toLowerCase())) return true;
        }
        return false;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String v : values) {
            if (!safe(v).trim().isBlank()) return safe(v).trim();
        }
        return "";
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private record PhonePack(String business1, String business2, String mobile, String home, String other) {}
    private record AddressPack(String street, String city, String state, String postalCode, String country) {}
}
