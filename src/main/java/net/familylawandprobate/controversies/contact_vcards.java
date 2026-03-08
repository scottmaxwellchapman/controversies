package net.familylawandprobate.controversies;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Minimal vCard 3.0 import/export helpers for contacts.
 */
public final class contact_vcards {

    private contact_vcards() {}

    public static String exportOne(contacts.ContactRec rec) {
        if (rec == null) return "";
        StringBuilder sb = new StringBuilder(512);
        appendLine(sb, "BEGIN:VCARD");
        appendLine(sb, "VERSION:3.0");

        appendIfValue(sb, "FN", rec.displayName);
        appendName(sb, rec.surname, rec.givenName, rec.middleName);
        appendIfValue(sb, "ORG", rec.companyName);
        appendIfValue(sb, "TITLE", rec.jobTitle);

        appendTypedIfValue(sb, "EMAIL", "TYPE=INTERNET;TYPE=WORK;TYPE=PREF", rec.emailPrimary);
        appendTypedIfValue(sb, "EMAIL", "TYPE=INTERNET;TYPE=HOME", rec.emailSecondary);
        appendTypedIfValue(sb, "EMAIL", "TYPE=INTERNET", rec.emailTertiary);

        appendTypedIfValue(sb, "TEL", "TYPE=WORK", rec.businessPhone);
        appendTypedIfValue(sb, "TEL", "TYPE=WORK", rec.businessPhone2);
        appendTypedIfValue(sb, "TEL", "TYPE=CELL", rec.mobilePhone);
        appendTypedIfValue(sb, "TEL", "TYPE=HOME", rec.homePhone);
        appendTypedIfValue(sb, "TEL", "TYPE=VOICE", rec.otherPhone);

        appendIfValue(sb, "URL", rec.website);

        appendAddress(sb, "TYPE=WORK", rec.street, rec.city, rec.state, rec.postalCode, rec.country);
        appendAddress(sb, "TYPE=HOME", rec.streetSecondary, rec.citySecondary, rec.stateSecondary, rec.postalCodeSecondary, rec.countrySecondary);
        appendAddress(sb, "TYPE=OTHER", rec.streetTertiary, rec.cityTertiary, rec.stateTertiary, rec.postalCodeTertiary, rec.countryTertiary);

        appendIfValue(sb, "NOTE", rec.notes);
        appendLine(sb, "END:VCARD");
        return sb.toString();
    }

    public static String exportMany(List<contacts.ContactRec> rows) {
        if (rows == null || rows.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(rows.size() * 512);
        for (int i = 0; i < rows.size(); i++) {
            contacts.ContactRec rec = rows.get(i);
            if (rec == null) continue;
            sb.append(exportOne(rec));
        }
        return sb.toString();
    }

    public static List<contacts.ContactInput> parseMany(String raw) {
        ArrayList<contacts.ContactInput> out = new ArrayList<contacts.ContactInput>();
        if (raw == null || raw.trim().isEmpty()) return out;

        List<String> lines = unfoldLines(raw);
        contacts.ContactInput current = null;
        boolean inCard = false;

        for (int i = 0; i < lines.size(); i++) {
            String line = safe(lines.get(i)).trim();
            if (line.isEmpty()) continue;

            if ("BEGIN:VCARD".equalsIgnoreCase(line)) {
                inCard = true;
                current = new contacts.ContactInput();
                continue;
            }
            if ("END:VCARD".equalsIgnoreCase(line)) {
                if (inCard && current != null && hasAnyContactField(current)) {
                    normalizeDisplayName(current);
                    out.add(current);
                }
                inCard = false;
                current = null;
                continue;
            }
            if (!inCard || current == null) continue;

            int colon = line.indexOf(':');
            if (colon <= 0) continue;

            String left = line.substring(0, colon);
            String value = unescapeVCard(line.substring(colon + 1));
            String prop = left;
            String params = "";
            int semi = left.indexOf(';');
            if (semi >= 0) {
                prop = left.substring(0, semi);
                params = left.substring(semi + 1);
            }
            String p = safe(prop).trim().toUpperCase(Locale.ROOT);
            if (p.isEmpty()) continue;

            if ("FN".equals(p)) {
                if (current.displayName.isBlank()) current.displayName = value.trim();
                continue;
            }
            if ("N".equals(p)) {
                String[] parts = value.split(";", -1);
                if (parts.length > 0 && current.surname.isBlank()) current.surname = safe(parts[0]).trim();
                if (parts.length > 1 && current.givenName.isBlank()) current.givenName = safe(parts[1]).trim();
                if (parts.length > 2 && current.middleName.isBlank()) current.middleName = safe(parts[2]).trim();
                continue;
            }
            if ("ORG".equals(p)) {
                if (current.companyName.isBlank()) current.companyName = value.trim();
                continue;
            }
            if ("TITLE".equals(p)) {
                if (current.jobTitle.isBlank()) current.jobTitle = value.trim();
                continue;
            }
            if ("EMAIL".equals(p)) {
                assignEmail(current, value, params);
                continue;
            }
            if ("TEL".equals(p)) {
                assignPhone(current, value, params);
                continue;
            }
            if ("ADR".equals(p)) {
                assignAddress(current, value, params);
                continue;
            }
            if ("URL".equals(p)) {
                if (current.website.isBlank()) current.website = value.trim();
                continue;
            }
            if ("NOTE".equals(p)) {
                if (current.notes.isBlank()) current.notes = value;
                else current.notes = current.notes + "\n" + value;
            }
        }

        return out;
    }

    private static void normalizeDisplayName(contacts.ContactInput in) {
        if (in == null) return;
        if (!safe(in.displayName).trim().isBlank()) return;
        String full = (safe(in.givenName).trim() + " " + safe(in.surname).trim()).trim();
        if (!full.isBlank()) {
            in.displayName = full;
            return;
        }
        if (!safe(in.companyName).trim().isBlank()) {
            in.displayName = safe(in.companyName).trim();
            return;
        }
        in.displayName = "Contact";
    }

    private static boolean hasAnyContactField(contacts.ContactInput in) {
        if (in == null) return false;
        return !safe(in.displayName).isBlank()
                || !safe(in.givenName).isBlank()
                || !safe(in.surname).isBlank()
                || !safe(in.companyName).isBlank()
                || !safe(in.emailPrimary).isBlank()
                || !safe(in.emailSecondary).isBlank()
                || !safe(in.emailTertiary).isBlank()
                || !safe(in.businessPhone).isBlank()
                || !safe(in.businessPhone2).isBlank()
                || !safe(in.mobilePhone).isBlank()
                || !safe(in.homePhone).isBlank()
                || !safe(in.otherPhone).isBlank()
                || !safe(in.street).isBlank()
                || !safe(in.city).isBlank()
                || !safe(in.state).isBlank()
                || !safe(in.postalCode).isBlank()
                || !safe(in.country).isBlank()
                || !safe(in.streetSecondary).isBlank()
                || !safe(in.citySecondary).isBlank()
                || !safe(in.stateSecondary).isBlank()
                || !safe(in.postalCodeSecondary).isBlank()
                || !safe(in.countrySecondary).isBlank()
                || !safe(in.streetTertiary).isBlank()
                || !safe(in.cityTertiary).isBlank()
                || !safe(in.stateTertiary).isBlank()
                || !safe(in.postalCodeTertiary).isBlank()
                || !safe(in.countryTertiary).isBlank()
                || !safe(in.website).isBlank()
                || !safe(in.notes).isBlank();
    }

    private static void assignEmail(contacts.ContactInput in, String value, String params) {
        String v = safe(value).trim();
        if (v.isBlank()) return;
        String p = safe(params).toUpperCase(Locale.ROOT);
        boolean prefersPrimary = p.contains("PREF") || p.contains("WORK");
        boolean isHome = p.contains("HOME");

        if (prefersPrimary && in.emailPrimary.isBlank()) {
            in.emailPrimary = v;
            return;
        }
        if (isHome && in.emailSecondary.isBlank()) {
            in.emailSecondary = v;
            return;
        }
        if (in.emailPrimary.isBlank()) {
            in.emailPrimary = v;
            return;
        }
        if (in.emailSecondary.isBlank()) {
            in.emailSecondary = v;
            return;
        }
        if (in.emailTertiary.isBlank()) {
            in.emailTertiary = v;
        }
    }

    private static void assignPhone(contacts.ContactInput in, String value, String params) {
        String v = safe(value).trim();
        if (v.isBlank()) return;
        String p = safe(params).toUpperCase(Locale.ROOT);
        if (p.contains("CELL")) {
            if (in.mobilePhone.isBlank()) in.mobilePhone = v;
            else if (in.otherPhone.isBlank()) in.otherPhone = v;
            return;
        }
        if (p.contains("HOME")) {
            if (in.homePhone.isBlank()) in.homePhone = v;
            else if (in.otherPhone.isBlank()) in.otherPhone = v;
            return;
        }
        if (p.contains("WORK") || p.contains("OFFICE")) {
            if (in.businessPhone.isBlank()) in.businessPhone = v;
            else if (in.businessPhone2.isBlank()) in.businessPhone2 = v;
            else if (in.otherPhone.isBlank()) in.otherPhone = v;
            return;
        }
        if (in.businessPhone.isBlank()) {
            in.businessPhone = v;
            return;
        }
        if (in.businessPhone2.isBlank()) {
            in.businessPhone2 = v;
            return;
        }
        if (in.mobilePhone.isBlank()) {
            in.mobilePhone = v;
            return;
        }
        if (in.homePhone.isBlank()) {
            in.homePhone = v;
            return;
        }
        if (in.otherPhone.isBlank()) {
            in.otherPhone = v;
        }
    }

    private static void assignAddress(contacts.ContactInput in, String value, String params) {
        String[] parts = value.split(";", -1);
        String street = parts.length > 2 ? safe(parts[2]).trim() : "";
        String city = parts.length > 3 ? safe(parts[3]).trim() : "";
        String state = parts.length > 4 ? safe(parts[4]).trim() : "";
        String postal = parts.length > 5 ? safe(parts[5]).trim() : "";
        String country = parts.length > 6 ? safe(parts[6]).trim() : "";
        if (street.isBlank() && city.isBlank() && state.isBlank() && postal.isBlank() && country.isBlank()) return;

        String p = safe(params).toUpperCase(Locale.ROOT);
        if (p.contains("HOME")) {
            assignAddressSlot(in, 2, street, city, state, postal, country);
            return;
        }
        if (p.contains("OTHER")) {
            assignAddressSlot(in, 3, street, city, state, postal, country);
            return;
        }
        // Prefer primary slot for WORK or unspecified types.
        if (p.contains("WORK") || !p.contains("HOME")) {
            if (assignAddressSlot(in, 1, street, city, state, postal, country)) return;
            if (assignAddressSlot(in, 2, street, city, state, postal, country)) return;
            assignAddressSlot(in, 3, street, city, state, postal, country);
            return;
        }
        if (assignAddressSlot(in, 1, street, city, state, postal, country)) return;
        if (assignAddressSlot(in, 2, street, city, state, postal, country)) return;
        assignAddressSlot(in, 3, street, city, state, postal, country);
    }

    private static boolean assignAddressSlot(contacts.ContactInput in,
                                             int slot,
                                             String street,
                                             String city,
                                             String state,
                                             String postal,
                                             String country) {
        if (in == null) return false;
        if (slot == 1) {
            if (!in.street.isBlank() || !in.city.isBlank() || !in.state.isBlank() || !in.postalCode.isBlank() || !in.country.isBlank()) {
                return false;
            }
            in.street = street;
            in.city = city;
            in.state = state;
            in.postalCode = postal;
            in.country = country;
            return true;
        }
        if (slot == 2) {
            if (!in.streetSecondary.isBlank() || !in.citySecondary.isBlank() || !in.stateSecondary.isBlank() || !in.postalCodeSecondary.isBlank() || !in.countrySecondary.isBlank()) {
                return false;
            }
            in.streetSecondary = street;
            in.citySecondary = city;
            in.stateSecondary = state;
            in.postalCodeSecondary = postal;
            in.countrySecondary = country;
            return true;
        }
        if (!in.streetTertiary.isBlank() || !in.cityTertiary.isBlank() || !in.stateTertiary.isBlank() || !in.postalCodeTertiary.isBlank() || !in.countryTertiary.isBlank()) {
            return false;
        }
        in.streetTertiary = street;
        in.cityTertiary = city;
        in.stateTertiary = state;
        in.postalCodeTertiary = postal;
        in.countryTertiary = country;
        return true;
    }

    private static void appendAddress(StringBuilder sb,
                                      String type,
                                      String street,
                                      String city,
                                      String state,
                                      String postal,
                                      String country) {
        String st = safe(street).trim();
        String ci = safe(city).trim();
        String region = safe(state).trim();
        String zip = safe(postal).trim();
        String co = safe(country).trim();
        if (st.isBlank() && ci.isBlank() && region.isBlank() && zip.isBlank() && co.isBlank()) return;
        String params = safe(type).trim();
        String head = params.isBlank() ? "ADR:" : ("ADR;" + params + ":");
        String value = ";;"
                + escapeVCardComponent(st) + ";"
                + escapeVCardComponent(ci) + ";"
                + escapeVCardComponent(region) + ";"
                + escapeVCardComponent(zip) + ";"
                + escapeVCardComponent(co);
        appendLine(sb, head + value);
    }

    private static void appendName(StringBuilder sb, String surname, String given, String middle) {
        String value = escapeVCardComponent(safe(surname).trim()) + ";"
                + escapeVCardComponent(safe(given).trim()) + ";"
                + escapeVCardComponent(safe(middle).trim()) + ";;";
        appendLine(sb, "N:" + value);
    }

    private static void appendIfValue(StringBuilder sb, String key, String value) {
        String v = safe(value);
        if (v.trim().isBlank()) return;
        appendLine(sb, key + ":" + escapeVCard(v));
    }

    private static void appendTypedIfValue(StringBuilder sb, String key, String typedParams, String value) {
        String v = safe(value);
        if (v.trim().isBlank()) return;
        String params = safe(typedParams).trim();
        if (params.isBlank()) appendLine(sb, key + ":" + escapeVCard(v));
        else appendLine(sb, key + ";" + params + ":" + escapeVCard(v));
    }

    private static void appendLine(StringBuilder sb, String line) {
        if (sb == null || line == null) return;
        sb.append(line).append("\r\n");
    }

    private static List<String> unfoldLines(String raw) {
        String normalized = safe(raw).replace("\r\n", "\n").replace('\r', '\n');
        String[] split = normalized.split("\n", -1);
        ArrayList<String> out = new ArrayList<String>();
        for (int i = 0; i < split.length; i++) {
            String line = split[i];
            if (!out.isEmpty() && (line.startsWith(" ") || line.startsWith("\t"))) {
                int last = out.size() - 1;
                out.set(last, out.get(last) + line.substring(1));
            } else {
                out.add(line);
            }
        }
        return out;
    }

    private static String escapeVCard(String v) {
        String s = safe(v);
        return s.replace("\\", "\\\\")
                .replace(";", "\\;")
                .replace(",", "\\,")
                .replace("\r\n", "\\n")
                .replace("\n", "\\n")
                .replace("\r", "\\n");
    }

    private static String escapeVCardComponent(String v) {
        String s = safe(v);
        return s.replace("\\", "\\\\")
                .replace(";", "\\;")
                .replace(",", "\\,")
                .replace("\r\n", "\\n")
                .replace("\n", "\\n")
                .replace("\r", "\\n");
    }

    private static String unescapeVCard(String v) {
        String s = safe(v);
        // Order matters: backslash escape should be resolved last.
        s = s.replace("\\n", "\n").replace("\\N", "\n");
        s = s.replace("\\;", ";").replace("\\,", ",");
        s = s.replace("\\\\", "\\");
        return s;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
