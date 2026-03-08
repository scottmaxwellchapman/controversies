package net.familylawandprobate.controversies;

import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * contacts
 *
 * data/tenants/{tenantUuid}/contacts.xml
 *
 * Contact fields are intentionally aligned with common Microsoft Graph
 * contact properties to simplify interoperability.
 */
public final class contacts {

    private static final ConcurrentHashMap<String, ReentrantReadWriteLock> LOCKS = new ConcurrentHashMap<String, ReentrantReadWriteLock>();

    public static contacts defaultStore() {
        return new contacts();
    }

    public static final class ContactInput {
        public String displayName = "";
        public String givenName = "";
        public String middleName = "";
        public String surname = "";
        public String companyName = "";
        public String jobTitle = "";
        public String emailPrimary = "";
        public String emailSecondary = "";
        public String emailTertiary = "";
        public String businessPhone = "";
        public String businessPhone2 = "";
        public String mobilePhone = "";
        public String homePhone = "";
        public String otherPhone = "";
        public String website = "";
        public String street = "";
        public String city = "";
        public String state = "";
        public String postalCode = "";
        public String country = "";
        public String streetSecondary = "";
        public String citySecondary = "";
        public String stateSecondary = "";
        public String postalCodeSecondary = "";
        public String countrySecondary = "";
        public String streetTertiary = "";
        public String cityTertiary = "";
        public String stateTertiary = "";
        public String postalCodeTertiary = "";
        public String countryTertiary = "";
        public String notes = "";
    }

    public static final class ContactRec {
        public final String uuid;
        public final boolean enabled;
        public final boolean trashed;
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
        public final String streetSecondary;
        public final String citySecondary;
        public final String stateSecondary;
        public final String postalCodeSecondary;
        public final String countrySecondary;
        public final String streetTertiary;
        public final String cityTertiary;
        public final String stateTertiary;
        public final String postalCodeTertiary;
        public final String countryTertiary;
        public final String notes;
        public final String source;
        public final String sourceContactId;
        public final String clioUpdatedAt;
        public final String updatedAt;

        private ContactRec(String uuid,
                           boolean enabled,
                           boolean trashed,
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
                           String streetSecondary,
                           String citySecondary,
                           String stateSecondary,
                           String postalCodeSecondary,
                           String countrySecondary,
                           String streetTertiary,
                           String cityTertiary,
                           String stateTertiary,
                           String postalCodeTertiary,
                           String countryTertiary,
                           String notes,
                           String source,
                           String sourceContactId,
                           String clioUpdatedAt,
                           String updatedAt) {
            this.uuid = safe(uuid).trim();
            this.enabled = enabled;
            this.trashed = trashed;
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
            this.streetSecondary = safe(streetSecondary);
            this.citySecondary = safe(citySecondary);
            this.stateSecondary = safe(stateSecondary);
            this.postalCodeSecondary = safe(postalCodeSecondary);
            this.countrySecondary = safe(countrySecondary);
            this.streetTertiary = safe(streetTertiary);
            this.cityTertiary = safe(cityTertiary);
            this.stateTertiary = safe(stateTertiary);
            this.postalCodeTertiary = safe(postalCodeTertiary);
            this.countryTertiary = safe(countryTertiary);
            this.notes = safe(notes);
            this.source = safe(source).trim().toLowerCase();
            this.sourceContactId = safe(sourceContactId).trim();
            this.clioUpdatedAt = safe(clioUpdatedAt).trim();
            this.updatedAt = safe(updatedAt).trim();
        }
    }

    public void ensure(String tenantUuid) throws Exception {
        String tu = safe(tenantUuid).trim();
        if (tu.isBlank()) throw new IllegalArgumentException("tenantUuid required");

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            Path file = contactsPath(tu);
            Files.createDirectories(file.getParent());
            if (!Files.exists(file)) writeAtomic(file, emptyContactsXml());
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<ContactRec> listAll(String tenantUuid) throws Exception {
        String tu = safe(tenantUuid).trim();
        if (tu.isBlank()) return List.of();

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.readLock().lock();
        try {
            return readAllLocked(tu);
        } finally {
            lock.readLock().unlock();
        }
    }

    public ContactRec getByUuid(String tenantUuid, String contactUuid) throws Exception {
        String tu = safe(tenantUuid).trim();
        String cu = safe(contactUuid).trim();
        if (tu.isBlank() || cu.isBlank()) return null;

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.readLock().lock();
        try {
            List<ContactRec> all = readAllLocked(tu);
            for (ContactRec c : all) {
                if (c == null) continue;
                if (cu.equals(safe(c.uuid).trim())) return c;
            }
            return null;
        } finally {
            lock.readLock().unlock();
        }
    }

    public ContactRec getBySourceContactId(String tenantUuid, String source, String sourceContactId) throws Exception {
        String tu = safe(tenantUuid).trim();
        String src = safe(source).trim().toLowerCase();
        String sid = safe(sourceContactId).trim();
        if (tu.isBlank() || src.isBlank() || sid.isBlank()) return null;

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.readLock().lock();
        try {
            List<ContactRec> all = readAllLocked(tu);
            for (ContactRec c : all) {
                if (c == null) continue;
                if (src.equals(safe(c.source).trim().toLowerCase()) && sid.equals(safe(c.sourceContactId).trim())) {
                    return c;
                }
            }
            return null;
        } finally {
            lock.readLock().unlock();
        }
    }

    public ContactRec createNative(String tenantUuid, ContactInput input) throws Exception {
        return createWithSource(tenantUuid, input, "native", "", "");
    }

    public ContactRec upsertClio(String tenantUuid, ContactInput input, String clioContactId, String clioUpdatedAt) throws Exception {
        return upsertExternal(tenantUuid, input, "clio", clioContactId, clioUpdatedAt);
    }

    public ContactRec upsertExternal(String tenantUuid,
                                     ContactInput input,
                                     String source,
                                     String sourceContactId,
                                     String sourceUpdatedAt) throws Exception {
        String tu = safe(tenantUuid).trim();
        String src = normalizeSourceKey(source);
        String sourceId = safe(sourceContactId).trim();
        if (tu.isBlank() || src.isBlank() || sourceId.isBlank()) {
            throw new IllegalArgumentException("tenantUuid, source, and sourceContactId required");
        }
        if ("native".equals(src)) throw new IllegalArgumentException("source must not be native for external upsert");

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensure(tu);
            List<ContactRec> all = readAllLocked(tu);
            String now = Instant.now().toString();

            ContactRec existing = null;
            for (ContactRec c : all) {
                if (c == null) continue;
                if (src.equals(normalizeSourceKey(c.source)) && sourceId.equals(safe(c.sourceContactId))) {
                    existing = c;
                    break;
                }
            }

            if (existing == null) {
                ContactRec created = normalizeRecord(
                        new ContactRec(
                                UUID.randomUUID().toString(),
                                true,
                                false,
                                safe(input == null ? "" : input.displayName),
                                safe(input == null ? "" : input.givenName),
                                safe(input == null ? "" : input.middleName),
                                safe(input == null ? "" : input.surname),
                                safe(input == null ? "" : input.companyName),
                                safe(input == null ? "" : input.jobTitle),
                                safe(input == null ? "" : input.emailPrimary),
                                safe(input == null ? "" : input.emailSecondary),
                                safe(input == null ? "" : input.emailTertiary),
                                safe(input == null ? "" : input.businessPhone),
                                safe(input == null ? "" : input.businessPhone2),
                                safe(input == null ? "" : input.mobilePhone),
                                safe(input == null ? "" : input.homePhone),
                                safe(input == null ? "" : input.otherPhone),
                                safe(input == null ? "" : input.website),
                                safe(input == null ? "" : input.street),
                                safe(input == null ? "" : input.city),
                                safe(input == null ? "" : input.state),
                                safe(input == null ? "" : input.postalCode),
                                safe(input == null ? "" : input.country),
                                safe(input == null ? "" : input.streetSecondary),
                                safe(input == null ? "" : input.citySecondary),
                                safe(input == null ? "" : input.stateSecondary),
                                safe(input == null ? "" : input.postalCodeSecondary),
                                safe(input == null ? "" : input.countrySecondary),
                                safe(input == null ? "" : input.streetTertiary),
                                safe(input == null ? "" : input.cityTertiary),
                                safe(input == null ? "" : input.stateTertiary),
                                safe(input == null ? "" : input.postalCodeTertiary),
                                safe(input == null ? "" : input.countryTertiary),
                                safe(input == null ? "" : input.notes),
                                src,
                                sourceId,
                                safe(sourceUpdatedAt),
                                now
                        )
                );
                all.add(created);
                sortByDisplayName(all);
                writeAllLocked(tu, all);
                publishContactEvent(tu, "contact.created", created, Map.of("action", "created"), "contacts.store.upsert_external");
                publishContactEvent(tu, "contact.changed", created, Map.of("action", "created"), "contacts.store.upsert_external");
                publishContactEvent(tu, "contact.source_upserted", created, Map.of("action", "upserted"), "contacts.store.upsert_external");
                return created;
            }

            List<ContactRec> out = new ArrayList<ContactRec>(all.size());
            ContactRec updated = existing;
            for (ContactRec c : all) {
                if (c == null) continue;
                if (!safe(existing.uuid).equals(safe(c.uuid))) {
                    out.add(c);
                    continue;
                }

                ContactRec next = normalizeRecord(
                        new ContactRec(
                                existing.uuid,
                                true,
                                false,
                                safe(input == null ? "" : input.displayName),
                                safe(input == null ? "" : input.givenName),
                                safe(input == null ? "" : input.middleName),
                                safe(input == null ? "" : input.surname),
                                safe(input == null ? "" : input.companyName),
                                safe(input == null ? "" : input.jobTitle),
                                safe(input == null ? "" : input.emailPrimary),
                                safe(input == null ? "" : input.emailSecondary),
                                safe(input == null ? "" : input.emailTertiary),
                                safe(input == null ? "" : input.businessPhone),
                                safe(input == null ? "" : input.businessPhone2),
                                safe(input == null ? "" : input.mobilePhone),
                                safe(input == null ? "" : input.homePhone),
                                safe(input == null ? "" : input.otherPhone),
                                safe(input == null ? "" : input.website),
                                safe(input == null ? "" : input.street),
                                safe(input == null ? "" : input.city),
                                safe(input == null ? "" : input.state),
                                safe(input == null ? "" : input.postalCode),
                                safe(input == null ? "" : input.country),
                                safe(input == null ? "" : input.streetSecondary),
                                safe(input == null ? "" : input.citySecondary),
                                safe(input == null ? "" : input.stateSecondary),
                                safe(input == null ? "" : input.postalCodeSecondary),
                                safe(input == null ? "" : input.countrySecondary),
                                safe(input == null ? "" : input.streetTertiary),
                                safe(input == null ? "" : input.cityTertiary),
                                safe(input == null ? "" : input.stateTertiary),
                                safe(input == null ? "" : input.postalCodeTertiary),
                                safe(input == null ? "" : input.countryTertiary),
                                safe(input == null ? "" : input.notes),
                                src,
                                sourceId,
                                safe(sourceUpdatedAt),
                                now
                        )
                );
                out.add(next);
                updated = next;
            }
            sortByDisplayName(out);
            writeAllLocked(tu, out);
            publishContactEvent(tu, "contact.updated", updated, Map.of("action", "updated"), "contacts.store.upsert_external");
            publishContactEvent(tu, "contact.changed", updated, Map.of("action", "updated"), "contacts.store.upsert_external");
            publishContactEvent(tu, "contact.source_upserted", updated, Map.of("action", "upserted"), "contacts.store.upsert_external");
            return updated;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean updateNative(String tenantUuid, String contactUuid, ContactInput input) throws Exception {
        String tu = safe(tenantUuid).trim();
        String cu = safe(contactUuid).trim();
        if (tu.isBlank() || cu.isBlank()) throw new IllegalArgumentException("tenantUuid/contactUuid required");

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensure(tu);
            List<ContactRec> all = readAllLocked(tu);
            boolean changed = false;
            ContactRec changedRec = null;
            List<ContactRec> out = new ArrayList<ContactRec>(all.size());
            for (ContactRec c : all) {
                if (c == null) continue;
                if (!cu.equals(safe(c.uuid).trim())) {
                    out.add(c);
                    continue;
                }
                if (isExternalReadOnly(c)) {
                    throw new IllegalStateException(readOnlyMessage(c));
                }
                ContactRec next = normalizeRecord(
                        new ContactRec(
                                c.uuid,
                                c.enabled,
                                c.trashed,
                                safe(input == null ? "" : input.displayName),
                                safe(input == null ? "" : input.givenName),
                                safe(input == null ? "" : input.middleName),
                                safe(input == null ? "" : input.surname),
                                safe(input == null ? "" : input.companyName),
                                safe(input == null ? "" : input.jobTitle),
                                safe(input == null ? "" : input.emailPrimary),
                                safe(input == null ? "" : input.emailSecondary),
                                safe(input == null ? "" : input.emailTertiary),
                                safe(input == null ? "" : input.businessPhone),
                                safe(input == null ? "" : input.businessPhone2),
                                safe(input == null ? "" : input.mobilePhone),
                                safe(input == null ? "" : input.homePhone),
                                safe(input == null ? "" : input.otherPhone),
                                safe(input == null ? "" : input.website),
                                safe(input == null ? "" : input.street),
                                safe(input == null ? "" : input.city),
                                safe(input == null ? "" : input.state),
                                safe(input == null ? "" : input.postalCode),
                                safe(input == null ? "" : input.country),
                                safe(input == null ? "" : input.streetSecondary),
                                safe(input == null ? "" : input.citySecondary),
                                safe(input == null ? "" : input.stateSecondary),
                                safe(input == null ? "" : input.postalCodeSecondary),
                                safe(input == null ? "" : input.countrySecondary),
                                safe(input == null ? "" : input.streetTertiary),
                                safe(input == null ? "" : input.cityTertiary),
                                safe(input == null ? "" : input.stateTertiary),
                                safe(input == null ? "" : input.postalCodeTertiary),
                                safe(input == null ? "" : input.countryTertiary),
                                safe(input == null ? "" : input.notes),
                                c.source,
                                c.sourceContactId,
                                c.clioUpdatedAt,
                                Instant.now().toString()
                        )
                );
                out.add(next);
                changed = true;
                changedRec = next;
            }
            if (!changed) return false;
            sortByDisplayName(out);
            writeAllLocked(tu, out);
            publishContactEvent(tu, "contact.updated", changedRec, Map.of("action", "updated"), "contacts.store.update");
            publishContactEvent(tu, "contact.changed", changedRec, Map.of("action", "updated"), "contacts.store.update");
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean trash(String tenantUuid, String contactUuid) throws Exception {
        return setTrash(tenantUuid, contactUuid, true);
    }

    public boolean restore(String tenantUuid, String contactUuid) throws Exception {
        return setTrash(tenantUuid, contactUuid, false);
    }

    public static boolean isClioLocked(ContactRec rec) {
        return rec != null
                && "clio".equalsIgnoreCase(sourceType(rec))
                && !safe(rec.sourceContactId).trim().isBlank();
    }

    public static boolean isExternalReadOnly(ContactRec rec) {
        if (rec == null) return false;
        String src = sourceType(rec);
        if ("native".equals(src)) return false;
        return !safe(rec.sourceContactId).trim().isBlank();
    }

    public static String sourceType(ContactRec rec) {
        return sourceType(rec == null ? "" : rec.source);
    }

    public static String sourceType(String raw) {
        String src = normalizeSourceKey(raw);
        return src.isBlank() ? "native" : src;
    }

    public static String sourceDisplayName(ContactRec rec) {
        return sourceDisplayName(sourceType(rec));
    }

    public static String sourceDisplayName(String sourceType) {
        String src = normalizeSourceKey(sourceType);
        if (src.isBlank() || "native".equals(src)) return "Native";
        if ("clio".equals(src)) return "Clio";
        if (src.startsWith("office365")) {
            int idx = src.indexOf(':');
            if (idx <= 0 || idx >= src.length() - 1) return "Office 365";
            return "Office 365 (" + src.substring(idx + 1) + ")";
        }
        int idx = src.indexOf(':');
        if (idx > 0 && idx < src.length() - 1) {
            return humanizeToken(src.substring(0, idx)) + " (" + src.substring(idx + 1) + ")";
        }
        return humanizeToken(src);
    }

    public static String readOnlyMessage(ContactRec rec) {
        String src = sourceType(rec);
        if ("clio".equals(src)) {
            return "This contact is synced from Clio and is read-only here. Edit it in Clio.";
        }
        if (src.startsWith("office365")) {
            return "This contact is synced from Office 365 and is read-only here. Edit it in Office 365.";
        }
        return "This contact is synced from " + sourceDisplayName(src) + " and is read-only here. Edit it in the source system.";
    }

    private ContactRec createWithSource(String tenantUuid,
                                        ContactInput input,
                                        String source,
                                        String sourceContactId,
                                        String clioUpdatedAt) throws Exception {
        String tu = safe(tenantUuid).trim();
        if (tu.isBlank()) throw new IllegalArgumentException("tenantUuid required");

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensure(tu);
            List<ContactRec> all = readAllLocked(tu);
            ContactRec rec = normalizeRecord(
                    new ContactRec(
                            UUID.randomUUID().toString(),
                            true,
                            false,
                            safe(input == null ? "" : input.displayName),
                            safe(input == null ? "" : input.givenName),
                            safe(input == null ? "" : input.middleName),
                            safe(input == null ? "" : input.surname),
                            safe(input == null ? "" : input.companyName),
                            safe(input == null ? "" : input.jobTitle),
                            safe(input == null ? "" : input.emailPrimary),
                            safe(input == null ? "" : input.emailSecondary),
                            safe(input == null ? "" : input.emailTertiary),
                            safe(input == null ? "" : input.businessPhone),
                            safe(input == null ? "" : input.businessPhone2),
                            safe(input == null ? "" : input.mobilePhone),
                            safe(input == null ? "" : input.homePhone),
                            safe(input == null ? "" : input.otherPhone),
                            safe(input == null ? "" : input.website),
                            safe(input == null ? "" : input.street),
                            safe(input == null ? "" : input.city),
                            safe(input == null ? "" : input.state),
                            safe(input == null ? "" : input.postalCode),
                            safe(input == null ? "" : input.country),
                            safe(input == null ? "" : input.streetSecondary),
                            safe(input == null ? "" : input.citySecondary),
                            safe(input == null ? "" : input.stateSecondary),
                            safe(input == null ? "" : input.postalCodeSecondary),
                            safe(input == null ? "" : input.countrySecondary),
                            safe(input == null ? "" : input.streetTertiary),
                            safe(input == null ? "" : input.cityTertiary),
                            safe(input == null ? "" : input.stateTertiary),
                            safe(input == null ? "" : input.postalCodeTertiary),
                            safe(input == null ? "" : input.countryTertiary),
                            safe(input == null ? "" : input.notes),
                            safe(source),
                            safe(sourceContactId),
                            safe(clioUpdatedAt),
                            Instant.now().toString()
                    )
            );
            all.add(rec);
            sortByDisplayName(all);
            writeAllLocked(tu, all);
            publishContactEvent(tu, "contact.created", rec, Map.of("action", "created"), "contacts.store.create");
            publishContactEvent(tu, "contact.changed", rec, Map.of("action", "created"), "contacts.store.create");
            return rec;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private boolean setTrash(String tenantUuid, String contactUuid, boolean trashed) throws Exception {
        String tu = safe(tenantUuid).trim();
        String cu = safe(contactUuid).trim();
        if (tu.isBlank() || cu.isBlank()) return false;

        ReentrantReadWriteLock lock = lockFor(tu);
        lock.writeLock().lock();
        try {
            ensure(tu);
            List<ContactRec> all = readAllLocked(tu);
            boolean changed = false;
            ContactRec changedRec = null;
            List<ContactRec> out = new ArrayList<ContactRec>(all.size());
            for (ContactRec c : all) {
                if (c == null) continue;
                if (!cu.equals(safe(c.uuid).trim())) {
                    out.add(c);
                    continue;
                }
                if (isExternalReadOnly(c)) {
                    throw new IllegalStateException(readOnlyMessage(c));
                }
                ContactRec next = new ContactRec(
                        c.uuid,
                        trashed ? false : true,
                        trashed,
                        c.displayName,
                        c.givenName,
                        c.middleName,
                        c.surname,
                        c.companyName,
                        c.jobTitle,
                        c.emailPrimary,
                        c.emailSecondary,
                        c.emailTertiary,
                        c.businessPhone,
                        c.businessPhone2,
                        c.mobilePhone,
                        c.homePhone,
                        c.otherPhone,
                        c.website,
                        c.street,
                        c.city,
                        c.state,
                        c.postalCode,
                        c.country,
                        c.streetSecondary,
                        c.citySecondary,
                        c.stateSecondary,
                        c.postalCodeSecondary,
                        c.countrySecondary,
                        c.streetTertiary,
                        c.cityTertiary,
                        c.stateTertiary,
                        c.postalCodeTertiary,
                        c.countryTertiary,
                        c.notes,
                        c.source,
                        c.sourceContactId,
                        c.clioUpdatedAt,
                        Instant.now().toString()
                );
                out.add(next);
                changed = true;
                changedRec = next;
            }
            if (!changed) return false;
            sortByDisplayName(out);
            writeAllLocked(tu, out);
            if (trashed) {
                publishContactEvent(tu, "contact.trashed", changedRec, Map.of("action", "trashed"), "contacts.store.trash");
                publishContactEvent(tu, "contact.changed", changedRec, Map.of("action", "trashed"), "contacts.store.trash");
            } else {
                publishContactEvent(tu, "contact.restored", changedRec, Map.of("action", "restored"), "contacts.store.restore");
                publishContactEvent(tu, "contact.changed", changedRec, Map.of("action", "restored"), "contacts.store.restore");
            }
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private static ContactRec normalizeRecord(ContactRec rec) {
        if (rec == null) return null;
        String display = safe(rec.displayName).trim();
        if (display.isBlank()) {
            String full = (safe(rec.givenName) + " " + safe(rec.surname)).trim();
            if (!full.isBlank()) display = full;
        }
        if (display.isBlank() && !safe(rec.companyName).trim().isBlank()) {
            display = safe(rec.companyName).trim();
        }
        if (display.isBlank()) display = "Contact";

        return new ContactRec(
                rec.uuid,
                rec.enabled,
                rec.trashed,
                display,
                safe(rec.givenName).trim(),
                safe(rec.middleName).trim(),
                safe(rec.surname).trim(),
                safe(rec.companyName).trim(),
                safe(rec.jobTitle).trim(),
                safe(rec.emailPrimary).trim(),
                safe(rec.emailSecondary).trim(),
                safe(rec.emailTertiary).trim(),
                safe(rec.businessPhone).trim(),
                safe(rec.businessPhone2).trim(),
                safe(rec.mobilePhone).trim(),
                safe(rec.homePhone).trim(),
                safe(rec.otherPhone).trim(),
                safe(rec.website).trim(),
                safe(rec.street).trim(),
                safe(rec.city).trim(),
                safe(rec.state).trim(),
                safe(rec.postalCode).trim(),
                safe(rec.country).trim(),
                safe(rec.streetSecondary).trim(),
                safe(rec.citySecondary).trim(),
                safe(rec.stateSecondary).trim(),
                safe(rec.postalCodeSecondary).trim(),
                safe(rec.countrySecondary).trim(),
                safe(rec.streetTertiary).trim(),
                safe(rec.cityTertiary).trim(),
                safe(rec.stateTertiary).trim(),
                safe(rec.postalCodeTertiary).trim(),
                safe(rec.countryTertiary).trim(),
                safe(rec.notes),
                sourceType(rec.source),
                safe(rec.sourceContactId).trim(),
                safe(rec.clioUpdatedAt).trim(),
                safe(rec.updatedAt).trim().isBlank() ? Instant.now().toString() : safe(rec.updatedAt).trim()
        );
    }

    private static void sortByDisplayName(List<ContactRec> rows) {
        if (rows == null) return;
        rows.sort(new Comparator<ContactRec>() {
            @Override
            public int compare(ContactRec a, ContactRec b) {
                return safe(a == null ? "" : a.displayName).compareToIgnoreCase(safe(b == null ? "" : b.displayName));
            }
        });
    }

    private static List<ContactRec> readAllLocked(String tenantUuid) throws Exception {
        Path p = contactsPath(tenantUuid);
        if (!Files.exists(p)) return new ArrayList<ContactRec>();

        Document d = parseXml(p);
        Element root = d == null ? null : d.getDocumentElement();
        if (root == null) return new ArrayList<ContactRec>();

        List<ContactRec> out = new ArrayList<ContactRec>();
        NodeList nl = root.getElementsByTagName("contact");
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (!(n instanceof Element)) continue;
            Element e = (Element) n;
            String uuid = text(e, "uuid");
            if (safe(uuid).trim().isBlank()) continue;
            ContactRec rec = normalizeRecord(
                    new ContactRec(
                            uuid,
                            parseBool(text(e, "enabled"), true),
                            parseBool(text(e, "trashed"), false),
                            text(e, "display_name"),
                            text(e, "given_name"),
                            text(e, "middle_name"),
                            text(e, "surname"),
                            text(e, "company_name"),
                            text(e, "job_title"),
                            text(e, "email_primary"),
                            text(e, "email_secondary"),
                            text(e, "email_tertiary"),
                            text(e, "business_phone"),
                            text(e, "business_phone_2"),
                            text(e, "mobile_phone"),
                            text(e, "home_phone"),
                            text(e, "other_phone"),
                            text(e, "website"),
                            text(e, "street"),
                            text(e, "city"),
                            text(e, "state"),
                            text(e, "postal_code"),
                            text(e, "country"),
                            text(e, "street_secondary"),
                            text(e, "city_secondary"),
                            text(e, "state_secondary"),
                            text(e, "postal_code_secondary"),
                            text(e, "country_secondary"),
                            text(e, "street_tertiary"),
                            text(e, "city_tertiary"),
                            text(e, "state_tertiary"),
                            text(e, "postal_code_tertiary"),
                            text(e, "country_tertiary"),
                            text(e, "notes"),
                            text(e, "source"),
                            text(e, "source_contact_id"),
                            text(e, "clio_updated_at"),
                            text(e, "updated_at")
                    )
            );
            out.add(rec);
        }
        return out;
    }

    private static void writeAllLocked(String tenantUuid, List<ContactRec> rows) throws Exception {
        Path p = contactsPath(tenantUuid);
        Files.createDirectories(p.getParent());
        String now = Instant.now().toString();

        StringBuilder sb = new StringBuilder(16384);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<contacts updated=\"").append(xmlAttr(now)).append("\">\n");
        List<ContactRec> xs = rows == null ? List.of() : rows;
        for (ContactRec c : xs) {
            if (c == null) continue;
            sb.append("  <contact>\n");
            sb.append("    <uuid>").append(xmlText(c.uuid)).append("</uuid>\n");
            sb.append("    <enabled>").append(c.enabled ? "true" : "false").append("</enabled>\n");
            sb.append("    <trashed>").append(c.trashed ? "true" : "false").append("</trashed>\n");
            sb.append("    <display_name>").append(xmlText(c.displayName)).append("</display_name>\n");
            if (!safe(c.givenName).isBlank()) sb.append("    <given_name>").append(xmlText(c.givenName)).append("</given_name>\n");
            if (!safe(c.middleName).isBlank()) sb.append("    <middle_name>").append(xmlText(c.middleName)).append("</middle_name>\n");
            if (!safe(c.surname).isBlank()) sb.append("    <surname>").append(xmlText(c.surname)).append("</surname>\n");
            if (!safe(c.companyName).isBlank()) sb.append("    <company_name>").append(xmlText(c.companyName)).append("</company_name>\n");
            if (!safe(c.jobTitle).isBlank()) sb.append("    <job_title>").append(xmlText(c.jobTitle)).append("</job_title>\n");
            if (!safe(c.emailPrimary).isBlank()) sb.append("    <email_primary>").append(xmlText(c.emailPrimary)).append("</email_primary>\n");
            if (!safe(c.emailSecondary).isBlank()) sb.append("    <email_secondary>").append(xmlText(c.emailSecondary)).append("</email_secondary>\n");
            if (!safe(c.emailTertiary).isBlank()) sb.append("    <email_tertiary>").append(xmlText(c.emailTertiary)).append("</email_tertiary>\n");
            if (!safe(c.businessPhone).isBlank()) sb.append("    <business_phone>").append(xmlText(c.businessPhone)).append("</business_phone>\n");
            if (!safe(c.businessPhone2).isBlank()) sb.append("    <business_phone_2>").append(xmlText(c.businessPhone2)).append("</business_phone_2>\n");
            if (!safe(c.mobilePhone).isBlank()) sb.append("    <mobile_phone>").append(xmlText(c.mobilePhone)).append("</mobile_phone>\n");
            if (!safe(c.homePhone).isBlank()) sb.append("    <home_phone>").append(xmlText(c.homePhone)).append("</home_phone>\n");
            if (!safe(c.otherPhone).isBlank()) sb.append("    <other_phone>").append(xmlText(c.otherPhone)).append("</other_phone>\n");
            if (!safe(c.website).isBlank()) sb.append("    <website>").append(xmlText(c.website)).append("</website>\n");
            if (!safe(c.street).isBlank()) sb.append("    <street>").append(xmlText(c.street)).append("</street>\n");
            if (!safe(c.city).isBlank()) sb.append("    <city>").append(xmlText(c.city)).append("</city>\n");
            if (!safe(c.state).isBlank()) sb.append("    <state>").append(xmlText(c.state)).append("</state>\n");
            if (!safe(c.postalCode).isBlank()) sb.append("    <postal_code>").append(xmlText(c.postalCode)).append("</postal_code>\n");
            if (!safe(c.country).isBlank()) sb.append("    <country>").append(xmlText(c.country)).append("</country>\n");
            if (!safe(c.streetSecondary).isBlank()) sb.append("    <street_secondary>").append(xmlText(c.streetSecondary)).append("</street_secondary>\n");
            if (!safe(c.citySecondary).isBlank()) sb.append("    <city_secondary>").append(xmlText(c.citySecondary)).append("</city_secondary>\n");
            if (!safe(c.stateSecondary).isBlank()) sb.append("    <state_secondary>").append(xmlText(c.stateSecondary)).append("</state_secondary>\n");
            if (!safe(c.postalCodeSecondary).isBlank()) sb.append("    <postal_code_secondary>").append(xmlText(c.postalCodeSecondary)).append("</postal_code_secondary>\n");
            if (!safe(c.countrySecondary).isBlank()) sb.append("    <country_secondary>").append(xmlText(c.countrySecondary)).append("</country_secondary>\n");
            if (!safe(c.streetTertiary).isBlank()) sb.append("    <street_tertiary>").append(xmlText(c.streetTertiary)).append("</street_tertiary>\n");
            if (!safe(c.cityTertiary).isBlank()) sb.append("    <city_tertiary>").append(xmlText(c.cityTertiary)).append("</city_tertiary>\n");
            if (!safe(c.stateTertiary).isBlank()) sb.append("    <state_tertiary>").append(xmlText(c.stateTertiary)).append("</state_tertiary>\n");
            if (!safe(c.postalCodeTertiary).isBlank()) sb.append("    <postal_code_tertiary>").append(xmlText(c.postalCodeTertiary)).append("</postal_code_tertiary>\n");
            if (!safe(c.countryTertiary).isBlank()) sb.append("    <country_tertiary>").append(xmlText(c.countryTertiary)).append("</country_tertiary>\n");
            if (!safe(c.notes).isBlank()) sb.append("    <notes>").append(xmlText(c.notes)).append("</notes>\n");
            if (!safe(c.source).isBlank()) sb.append("    <source>").append(xmlText(c.source)).append("</source>\n");
            if (!safe(c.sourceContactId).isBlank()) sb.append("    <source_contact_id>").append(xmlText(c.sourceContactId)).append("</source_contact_id>\n");
            if (!safe(c.clioUpdatedAt).isBlank()) sb.append("    <clio_updated_at>").append(xmlText(c.clioUpdatedAt)).append("</clio_updated_at>\n");
            if (!safe(c.updatedAt).isBlank()) sb.append("    <updated_at>").append(xmlText(c.updatedAt)).append("</updated_at>\n");
            sb.append("  </contact>\n");
        }
        sb.append("</contacts>\n");
        writeAtomic(p, sb.toString());
    }

    private static ReentrantReadWriteLock lockFor(String tenantUuid) {
        return LOCKS.computeIfAbsent(safe(tenantUuid), k -> new ReentrantReadWriteLock());
    }

    private static Path contactsPath(String tenantUuid) {
        return Paths.get("data", "tenants", safe(tenantUuid).trim(), "contacts.xml").toAbsolutePath();
    }

    private static Document parseXml(Path p) throws Exception {
        if (p == null || !Files.exists(p)) return null;
        DocumentBuilder b = secureBuilder();
        try (InputStream in = Files.newInputStream(p)) {
            Document d = b.parse(in);
            d.getDocumentElement().normalize();
            return d;
        }
    }

    private static DocumentBuilder secureBuilder() throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false);
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        DocumentBuilder b = f.newDocumentBuilder();
        b.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
        return b;
    }

    private static String text(Element parent, String childTag) {
        if (parent == null || childTag == null) return "";
        NodeList nl = parent.getElementsByTagName(childTag);
        if (nl == null || nl.getLength() == 0) return "";
        Node n = nl.item(0);
        return n == null ? "" : safe(n.getTextContent()).trim();
    }

    private static boolean parseBool(String raw, boolean fallback) {
        String v = safe(raw).trim().toLowerCase();
        if (v.isBlank()) return fallback;
        return "true".equals(v) || "1".equals(v) || "yes".equals(v) || "y".equals(v) || "on".equals(v);
    }

    private static void writeAtomic(Path p, String content) throws Exception {
        if (p == null) return;
        Files.createDirectories(p.getParent());
        Path tmp = p.resolveSibling(p.getFileName().toString() + ".tmp." + UUID.randomUUID());
        Files.writeString(
                tmp,
                content == null ? "" : content,
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

    private static String emptyContactsXml() {
        String now = Instant.now().toString();
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<contacts created=\"" + xmlAttr(now) + "\" updated=\"" + xmlAttr(now) + "\"></contacts>\n";
    }

    private static String xmlAttr(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("'", "&apos;");
    }

    private static String xmlText(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private static String normalizeSourceKey(String source) {
        return safe(source).trim().toLowerCase(Locale.ROOT);
    }

    private static String humanizeToken(String raw) {
        String v = safe(raw).trim().replace('_', ' ').replace('-', ' ');
        if (v.isBlank()) return "External";
        String[] parts = v.split("\\s+");
        StringBuilder sb = new StringBuilder(v.length());
        for (int i = 0; i < parts.length; i++) {
            String p = safe(parts[i]).trim();
            if (p.isBlank()) continue;
            if (i > 0) sb.append(' ');
            if (p.length() == 1) sb.append(p.toUpperCase(Locale.ROOT));
            else sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        String out = sb.toString().trim();
        return out.isBlank() ? "External" : out;
    }

    private static void publishContactEvent(String tenantUuid,
                                            String eventType,
                                            ContactRec rec,
                                            Map<String, String> extras,
                                            String source) {
        if (rec == null) return;
        try {
            LinkedHashMap<String, String> payload = new LinkedHashMap<String, String>();
            payload.put("tenant_uuid", safe(tenantUuid));
            payload.put("contact_uuid", safe(rec.uuid));
            payload.put("display_name", safe(rec.displayName));
            payload.put("given_name", safe(rec.givenName));
            payload.put("middle_name", safe(rec.middleName));
            payload.put("surname", safe(rec.surname));
            payload.put("company_name", safe(rec.companyName));
            payload.put("job_title", safe(rec.jobTitle));
            payload.put("email_primary", safe(rec.emailPrimary));
            payload.put("email_secondary", safe(rec.emailSecondary));
            payload.put("email_tertiary", safe(rec.emailTertiary));
            payload.put("business_phone", safe(rec.businessPhone));
            payload.put("business_phone_2", safe(rec.businessPhone2));
            payload.put("mobile_phone", safe(rec.mobilePhone));
            payload.put("home_phone", safe(rec.homePhone));
            payload.put("other_phone", safe(rec.otherPhone));
            payload.put("website", safe(rec.website));
            payload.put("street", safe(rec.street));
            payload.put("city", safe(rec.city));
            payload.put("state", safe(rec.state));
            payload.put("postal_code", safe(rec.postalCode));
            payload.put("country", safe(rec.country));
            payload.put("street_secondary", safe(rec.streetSecondary));
            payload.put("city_secondary", safe(rec.citySecondary));
            payload.put("state_secondary", safe(rec.stateSecondary));
            payload.put("postal_code_secondary", safe(rec.postalCodeSecondary));
            payload.put("country_secondary", safe(rec.countrySecondary));
            payload.put("street_tertiary", safe(rec.streetTertiary));
            payload.put("city_tertiary", safe(rec.cityTertiary));
            payload.put("state_tertiary", safe(rec.stateTertiary));
            payload.put("postal_code_tertiary", safe(rec.postalCodeTertiary));
            payload.put("country_tertiary", safe(rec.countryTertiary));
            payload.put("notes", safe(rec.notes));
            payload.put("source", sourceType(rec));
            payload.put("source_display", sourceDisplayName(rec));
            payload.put("source_contact_id", safe(rec.sourceContactId));
            payload.put("source_updated_at", safe(rec.clioUpdatedAt));
            payload.put("updated_at", safe(rec.updatedAt));
            payload.put("enabled", rec.enabled ? "true" : "false");
            payload.put("trashed", rec.trashed ? "true" : "false");
            if (extras != null) {
                for (Map.Entry<String, String> e : extras.entrySet()) {
                    if (e == null) continue;
                    String key = safe(e.getKey()).trim();
                    if (key.isBlank()) continue;
                    payload.put(key, safe(e.getValue()));
                }
            }
            business_process_manager.defaultService().triggerEvent(
                    safe(tenantUuid),
                    safe(eventType).trim().toLowerCase(Locale.ROOT),
                    payload,
                    "",
                    safe(source).trim().isBlank() ? "contacts.store" : safe(source).trim()
            );
        } catch (Exception ignored) {
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
