package net.familylawandprobate.controversies;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Java NLP entity recognizer focused on conflict screening use-cases.
 * It extracts likely person and organization names from plain text.
 */
public final class entity_recognition_service {

    private static final Pattern ORG_SUFFIX_PATTERN = Pattern.compile(
            "\\b([A-Z][\\p{L}&'`.-]+(?:\\s+[A-Z][\\p{L}&'`.-]+){0,8}\\s+"
                    + "(LLC|L\\.L\\.C\\.|PLLC|P\\.L\\.L\\.C\\.|LLP|L\\.L\\.P\\.|LP|L\\.P\\.|"
                    + "INC|INC\\.|CORP|CORPORATION|CO|CO\\.|COMPANY|LTD|LTD\\.|"
                    + "PC|P\\.C\\.|PA|P\\.A\\.|PLC|GROUP|HOLDINGS?|ASSOCIATES?|"
                    + "UNIVERSITY|COLLEGE|SCHOOL|DEPARTMENT|AGENCY|BANK|TRUST|OFFICE|COURT))\\b"
    );

    private static final Pattern PERSON_PATTERN = Pattern.compile(
            "\\b([A-Z][a-z]+(?:\\s+[A-Z][a-z]+){1,3})\\b"
    );

    private static final Set<String> PERSON_STOPWORDS = Set.of(
            "The", "This", "That", "These", "Those", "Court", "County", "State",
            "United", "Plaintiff", "Defendant", "Petitioner", "Respondent",
            "Motion", "Order", "Exhibit", "Schedule", "Section", "Article",
            "January", "February", "March", "April", "May", "June", "July",
            "August", "September", "October", "November", "December",
            "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"
    );

    private static final Set<String> ORG_CUE_WORDS = Set.of(
            "Bank", "Company", "Corporation", "Department", "Agency", "Court", "Office",
            "University", "College", "School", "Trust", "Holdings", "Group", "Associates"
    );

    private static final int MAX_ENTITIES = 1500;
    private static final int MAX_TEXT_LENGTH = 3_000_000;

    private static final class Holder {
        private static final entity_recognition_service INSTANCE = new entity_recognition_service();
    }

    public static final class EntityHit {
        public String entityType = "";   // person | organization
        public String value = "";        // display text
        public String normalized = "";   // normalized key
        public String engine = "java_nlp_heuristic_v1";
    }

    private entity_recognition_service() {
    }

    public static entity_recognition_service defaultService() {
        return Holder.INSTANCE;
    }

    public ArrayList<EntityHit> extractPersonAndOrganizations(String text) {
        String raw = safe(text);
        if (raw.length() > MAX_TEXT_LENGTH) {
            raw = raw.substring(0, MAX_TEXT_LENGTH);
        }
        ArrayList<EntityHit> out = new ArrayList<EntityHit>();
        if (raw.isBlank()) return out;

        LinkedHashSet<String> seen = new LinkedHashSet<String>();
        extractOrganizations(raw, out, seen);
        extractPersons(raw, out, seen);
        return out;
    }

    private static void extractOrganizations(String text,
                                             ArrayList<EntityHit> out,
                                             LinkedHashSet<String> seen) {
        if (text == null || out == null || seen == null) return;
        Matcher m = ORG_SUFFIX_PATTERN.matcher(text);
        while (m.find() && out.size() < MAX_ENTITIES) {
            String cand = compactSpaces(m.group(1));
            if (cand.isBlank()) continue;
            if (!isReasonableEntityLength(cand)) continue;
            addHit(out, seen, "organization", cand);
        }

        // Secondary pass: sentence-level cues for organizations without legal suffixes.
        BreakIterator bi = BreakIterator.getSentenceInstance(Locale.US);
        bi.setText(text);
        int start = bi.first();
        for (int end = bi.next(); end != BreakIterator.DONE && out.size() < MAX_ENTITIES; start = end, end = bi.next()) {
            String sentence = compactSpaces(text.substring(start, end));
            if (sentence.isBlank()) continue;
            String cand = leadingTitleCasePhrase(sentence);
            if (cand.isBlank()) continue;
            if (!containsOrgCueWord(cand)) continue;
            if (!isReasonableEntityLength(cand)) continue;
            addHit(out, seen, "organization", cand);
        }
    }

    private static void extractPersons(String text,
                                       ArrayList<EntityHit> out,
                                       LinkedHashSet<String> seen) {
        if (text == null || out == null || seen == null) return;
        HashSet<String> orgNorms = new HashSet<String>();
        for (EntityHit hit : out) {
            if (hit == null) continue;
            if ("organization".equals(hit.entityType)) orgNorms.add(safe(hit.normalized));
        }

        Matcher m = PERSON_PATTERN.matcher(text);
        while (m.find() && out.size() < MAX_ENTITIES) {
            String cand = compactSpaces(m.group(1));
            if (cand.isBlank()) continue;
            if (!isReasonableEntityLength(cand)) continue;
            if (isLikelyOrganizationPhrase(cand)) continue;
            if (startsOrEndsWithStopword(cand)) continue;
            String norm = normalizeEntity(cand);
            if (norm.isBlank()) continue;
            if (orgNorms.contains(norm)) continue;
            addHit(out, seen, "person", cand);
        }
    }

    private static boolean containsOrgCueWord(String phrase) {
        if (safe(phrase).isBlank()) return false;
        String[] tokens = compactSpaces(phrase).split(" ");
        for (String token : tokens) {
            if (ORG_CUE_WORDS.contains(token)) return true;
        }
        return false;
    }

    private static String leadingTitleCasePhrase(String sentence) {
        String s = compactSpaces(sentence);
        if (s.isBlank()) return "";
        String[] tokens = s.split(" ");
        StringBuilder out = new StringBuilder(64);
        int count = 0;
        for (String token : tokens) {
            if (token.isBlank()) continue;
            String clean = token.replaceAll("[^\\p{L}0-9&'`.-]", "");
            if (clean.isBlank()) break;
            if (!startsWithUpper(clean)) break;
            if (count > 0) out.append(' ');
            out.append(clean);
            count++;
            if (count >= 8) break;
        }
        return compactSpaces(out.toString());
    }

    private static void addHit(ArrayList<EntityHit> out,
                               LinkedHashSet<String> seen,
                               String type,
                               String value) {
        if (out == null || seen == null) return;
        if (out.size() >= MAX_ENTITIES) return;
        String entityType = safe(type).trim().toLowerCase(Locale.ROOT);
        if (!"person".equals(entityType) && !"organization".equals(entityType)) return;
        String display = compactSpaces(value);
        if (display.isBlank()) return;
        String normalized = normalizeEntity(display);
        if (normalized.isBlank()) return;
        String key = entityType + "|" + normalized;
        if (!seen.add(key)) return;

        EntityHit hit = new EntityHit();
        hit.entityType = entityType;
        hit.value = display;
        hit.normalized = normalized;
        hit.engine = "java_nlp_heuristic_v1";
        out.add(hit);
    }

    public static String normalizeEntity(String value) {
        String v = safe(value).toLowerCase(Locale.ROOT);
        v = v.replace('\u00A0', ' ');
        v = v.replaceAll("[^\\p{L}0-9\\s&'`.-]", " ");
        v = v.replaceAll("\\s+", " ").trim();
        return v;
    }

    private static boolean startsOrEndsWithStopword(String value) {
        String[] parts = compactSpaces(value).split(" ");
        if (parts.length == 0) return true;
        if (PERSON_STOPWORDS.contains(parts[0])) return true;
        return PERSON_STOPWORDS.contains(parts[parts.length - 1]);
    }

    private static boolean isLikelyOrganizationPhrase(String value) {
        String v = compactSpaces(value);
        if (v.isBlank()) return false;
        for (String token : v.split(" ")) {
            if (ORG_CUE_WORDS.contains(token)) return true;
        }
        return false;
    }

    private static boolean startsWithUpper(String token) {
        if (safe(token).isBlank()) return false;
        int cp = token.codePointAt(0);
        return Character.isUpperCase(cp);
    }

    private static boolean isReasonableEntityLength(String cand) {
        String v = compactSpaces(cand);
        if (v.isBlank()) return false;
        if (v.length() < 3 || v.length() > 160) return false;
        List<String> parts = List.of(v.split(" "));
        if (parts.isEmpty()) return false;
        if (parts.size() > 12) return false;
        return true;
    }

    private static String compactSpaces(String s) {
        return safe(s).replace('\u00A0', ' ').replaceAll("\\s+", " ").trim();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
