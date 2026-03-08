package net.familylawandprobate.controversies;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Holiday calculator for federal and Texas holidays using tenant-level XML rules.
 *
 * XML location:
 *   data/tenants/{tenantUuid}/settings/holidays.xml
 *
 * If the tenant file does not exist, it is created from a default definition
 * equivalent to the previous hard-coded Federal + Texas holiday set.
 */
public class class_holiday_calculator {

    private static final String DEFAULT_HOLIDAY_XML =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<holidaySet id=\"default-us-tx\" label=\"Default Federal and Texas Holidays\">\n" +
            "  <holiday id=\"federal_new_years_day\" type=\"Federal\" label=\"New Year's Day\" computation=\"FIXED_DATE\" month=\"1\" day=\"1\" observe=\"FEDERAL\" court=\"NO\" />\n" +
            "  <holiday id=\"federal_mlk_day\" type=\"Federal\" label=\"Birthday of Martin Luther King, Jr.\" computation=\"NTH_WEEKDAY_OF_MONTH\" month=\"1\" dayOfWeek=\"MONDAY\" ordinal=\"3\" court=\"NO\" />\n" +
            "  <holiday id=\"federal_washington_birthday\" type=\"Federal\" label=\"Washington's Birthday\" computation=\"NTH_WEEKDAY_OF_MONTH\" month=\"2\" dayOfWeek=\"MONDAY\" ordinal=\"3\" court=\"NO\" />\n" +
            "  <holiday id=\"federal_memorial_day\" type=\"Federal\" label=\"Memorial Day\" computation=\"LAST_WEEKDAY_OF_MONTH\" month=\"5\" dayOfWeek=\"MONDAY\" court=\"NO\" />\n" +
            "  <holiday id=\"federal_juneteenth\" type=\"Federal\" label=\"Juneteenth National Independence Day\" computation=\"FIXED_DATE\" month=\"6\" day=\"19\" observe=\"FEDERAL\" court=\"NO\" />\n" +
            "  <holiday id=\"federal_independence_day\" type=\"Federal\" label=\"Independence Day\" computation=\"FIXED_DATE\" month=\"7\" day=\"4\" observe=\"FEDERAL\" court=\"NO\" />\n" +
            "  <holiday id=\"federal_labor_day\" type=\"Federal\" label=\"Labor Day\" computation=\"NTH_WEEKDAY_OF_MONTH\" month=\"9\" dayOfWeek=\"MONDAY\" ordinal=\"1\" court=\"NO\" />\n" +
            "  <holiday id=\"federal_columbus_day\" type=\"Federal\" label=\"Columbus Day\" computation=\"NTH_WEEKDAY_OF_MONTH\" month=\"10\" dayOfWeek=\"MONDAY\" ordinal=\"2\" court=\"NO\" />\n" +
            "  <holiday id=\"federal_veterans_day\" type=\"Federal\" label=\"Veterans Day\" computation=\"FIXED_DATE\" month=\"11\" day=\"11\" observe=\"FEDERAL\" court=\"NO\" />\n" +
            "  <holiday id=\"federal_thanksgiving_day\" type=\"Federal\" label=\"Thanksgiving Day\" computation=\"NTH_WEEKDAY_OF_MONTH\" month=\"11\" dayOfWeek=\"THURSDAY\" ordinal=\"4\" court=\"NO\" />\n" +
            "  <holiday id=\"federal_christmas_day\" type=\"Federal\" label=\"Christmas Day\" computation=\"FIXED_DATE\" month=\"12\" day=\"25\" observe=\"FEDERAL\" court=\"NO\" />\n" +
            "  <holiday id=\"federal_inauguration_day\" type=\"Federal\" label=\"Inauguration Day\" computation=\"FIXED_DATE\" month=\"1\" day=\"20\" observe=\"SUNDAY_TO_MONDAY\" court=\"NO\" yearMin=\"1965\" yearModulo=\"4\" yearModuloBase=\"1965\" />\n" +
            "\n" +
            "  <holiday id=\"texas_new_years_day\" type=\"Texas\" label=\"New Year's Day\" computation=\"FIXED_DATE\" month=\"1\" day=\"1\" court=\"NO\" />\n" +
            "  <holiday id=\"texas_mlk_day\" type=\"Texas\" label=\"Martin Luther King, Jr., Day\" computation=\"NTH_WEEKDAY_OF_MONTH\" month=\"1\" dayOfWeek=\"MONDAY\" ordinal=\"3\" court=\"NO\" />\n" +
            "  <holiday id=\"texas_presidents_day\" type=\"Texas\" label=\"Presidents' Day\" computation=\"NTH_WEEKDAY_OF_MONTH\" month=\"2\" dayOfWeek=\"MONDAY\" ordinal=\"3\" court=\"NO\" />\n" +
            "  <holiday id=\"texas_memorial_day\" type=\"Texas\" label=\"Memorial Day\" computation=\"LAST_WEEKDAY_OF_MONTH\" month=\"5\" dayOfWeek=\"MONDAY\" court=\"NO\" />\n" +
            "  <holiday id=\"texas_independence_day\" type=\"Texas\" label=\"Independence Day\" computation=\"FIXED_DATE\" month=\"7\" day=\"4\" court=\"NO\" />\n" +
            "  <holiday id=\"texas_labor_day\" type=\"Texas\" label=\"Labor Day\" computation=\"NTH_WEEKDAY_OF_MONTH\" month=\"9\" dayOfWeek=\"MONDAY\" ordinal=\"1\" court=\"NO\" />\n" +
            "  <holiday id=\"texas_veterans_day\" type=\"Texas\" label=\"Veterans Day\" computation=\"FIXED_DATE\" month=\"11\" day=\"11\" court=\"NO\" />\n" +
            "  <holiday id=\"texas_thanksgiving_day\" type=\"Texas\" label=\"Thanksgiving Day\" computation=\"NTH_WEEKDAY_OF_MONTH\" month=\"11\" dayOfWeek=\"THURSDAY\" ordinal=\"4\" court=\"NO\" />\n" +
            "  <holiday id=\"texas_christmas_day\" type=\"Texas\" label=\"Christmas Day\" computation=\"FIXED_DATE\" month=\"12\" day=\"25\" court=\"NO\" />\n" +
            "  <holiday id=\"texas_confederate_heroes_day\" type=\"Texas\" label=\"Confederate Heroes Day\" computation=\"FIXED_DATE\" month=\"1\" day=\"19\" court=\"NO\" />\n" +
            "  <holiday id=\"texas_independence_day_march\" type=\"Texas\" label=\"Texas Independence Day\" computation=\"FIXED_DATE\" month=\"3\" day=\"2\" court=\"NO\" />\n" +
            "  <holiday id=\"texas_san_jacinto_day\" type=\"Texas\" label=\"San Jacinto Day\" computation=\"FIXED_DATE\" month=\"4\" day=\"21\" court=\"NO\" />\n" +
            "  <holiday id=\"texas_emancipation_day\" type=\"Texas\" label=\"Emancipation Day in Texas\" computation=\"FIXED_DATE\" month=\"6\" day=\"19\" court=\"NO\" />\n" +
            "  <holiday id=\"texas_lbj_day\" type=\"Texas\" label=\"Lyndon Baines Johnson Day\" computation=\"FIXED_DATE\" month=\"8\" day=\"27\" court=\"NO\" />\n" +
            "  <holiday id=\"texas_friday_after_thanksgiving\" type=\"Texas\" label=\"Friday after Thanksgiving Day\" computation=\"RELATIVE\" baseId=\"texas_thanksgiving_day\" offsetDays=\"1\" court=\"NO\" />\n" +
            "  <holiday id=\"texas_december_24\" type=\"Texas\" label=\"December 24\" computation=\"FIXED_DATE\" month=\"12\" day=\"24\" court=\"NO\" />\n" +
            "  <holiday id=\"texas_december_26\" type=\"Texas\" label=\"December 26\" computation=\"FIXED_DATE\" month=\"12\" day=\"26\" court=\"NO\" />\n" +
            "</holidaySet>\n";

    private enum ComputationType {
        FIXED_DATE,
        NTH_WEEKDAY_OF_MONTH,
        LAST_WEEKDAY_OF_MONTH,
        RELATIVE
    }

    private enum ObserveMode {
        NONE,
        FEDERAL,
        SUNDAY_TO_MONDAY
    }

    private static class HolidayRule {
        String id;
        String holidayType;
        String label;
        CourtSessionStatus courtStatus;
        ComputationType computationType;
        int month;
        int day;
        DayOfWeek dayOfWeek;
        int ordinal;
        String baseId;
        int offsetDays;
        ObserveMode observeMode;
        int yearMin;
        int yearMax;
        int yearModulo;
        int yearModuloBase;
        boolean enabled;
    }

    public enum CourtSessionStatus {
        YES,
        NO,
        UNKNOWN
    }

    public static class HolidayInfo {
        private final String holidayType;      // Federal / Texas
        private final String holidayDate;      // ISO-8601 date: yyyy-MM-dd
        private final String holidayName;      // e.g. [Holiday/Federal] Christmas Day (Court: No)
        private final boolean courtInSession;
        private final CourtSessionStatus courtSessionStatus;

        public HolidayInfo(String holidayType, LocalDate holidayDate, String baseHolidayName, boolean courtInSession) {
            this(holidayType, holidayDate, baseHolidayName,
                    courtInSession ? CourtSessionStatus.YES : CourtSessionStatus.NO);
        }

        public HolidayInfo(String holidayType, LocalDate holidayDate, String baseHolidayName, CourtSessionStatus status) {
            this.holidayType = holidayType;
            this.holidayDate = holidayDate.toString();
            this.courtSessionStatus = status == null ? CourtSessionStatus.UNKNOWN : status;
            this.courtInSession = this.courtSessionStatus == CourtSessionStatus.YES;
            this.holidayName = formatHolidayName(holidayType, baseHolidayName, this.courtSessionStatus);
        }

        public String getHolidayType() {
            return holidayType;
        }

        public String getHolidayDate() {
            return holidayDate;
        }

        public String getHolidayName() {
            return holidayName;
        }

        public boolean isCourtInSession() {
            return courtInSession;
        }

        public CourtSessionStatus getCourtSessionStatus() {
            return courtSessionStatus;
        }

        private static String formatHolidayName(String holidayType, String baseHolidayName, CourtSessionStatus status) {
            return "[Holiday/" + holidayType + "] " +
                    baseHolidayName +
                    " (Court: " + statusText(status) + ")";
        }

        private static String statusText(CourtSessionStatus status) {
            switch (status) {
                case YES:
                    return "Yes";
                case NO:
                    return "No";
                default:
                    return "Unknown";
            }
        }

        @Override
        public String toString() {
            return "HolidayInfo{" +
                    "holidayType='" + holidayType + '\'' +
                    ", holidayDate='" + holidayDate + '\'' +
                    ", holidayName='" + holidayName + '\'' +
                    ", courtInSession=" + courtInSession +
                    ", courtSessionStatus=" + courtSessionStatus +
                    '}';
        }
    }

    public static Path tenantHolidayXmlPath(String tenantUuid) {
        String tu = safeFileToken(tenantUuid);
        if (tu.isBlank()) return null;
        return Paths.get("data", "tenants", tu, "settings", "holidays.xml").toAbsolutePath();
    }

    public static Path ensureTenantHolidayXml(String tenantUuid) throws Exception {
        Path p = tenantHolidayXmlPath(tenantUuid);
        if (p == null) return null;
        Files.createDirectories(p.getParent());
        if (!Files.exists(p)) {
            Files.writeString(
                    p,
                    DEFAULT_HOLIDAY_XML,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW
            );
        }
        return p;
    }

    public static String readTenantHolidayXml(String tenantUuid) throws Exception {
        Path p = ensureTenantHolidayXml(tenantUuid);
        if (p == null) return DEFAULT_HOLIDAY_XML;
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    public static void writeTenantHolidayXml(String tenantUuid, String xml) throws Exception {
        Path p = tenantHolidayXmlPath(tenantUuid);
        if (p == null) throw new IllegalArgumentException("tenantUuid required");
        String text = safe(xml);
        if (text.trim().isEmpty()) throw new IllegalArgumentException("Holiday XML cannot be empty.");
        try (InputStream in = new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))) {
            List<HolidayRule> rules = parseRules(in);
            if (rules.isEmpty()) {
                throw new IllegalArgumentException("Holiday XML must contain at least one <holiday> definition.");
            }
        }
        Files.createDirectories(p.getParent());
        Files.writeString(
                p,
                text,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        );
    }

    public static HolidayInfo[] calculateHolidays(int year) {
        return calculateHolidays("", year);
    }

    public static HolidayInfo[] calculateHolidays(String tenantUuid, int year) {
        List<HolidayInfo> holidays = new ArrayList<>();
        List<HolidayRule> rules = loadRules(tenantUuid);
        Map<String, HolidayRule> byId = new LinkedHashMap<>();
        for (int i = 0; i < rules.size(); i++) {
            HolidayRule rule = rules.get(i);
            if (rule == null || rule.id == null || rule.id.isBlank()) continue;
            byId.put(rule.id, rule);
        }

        Map<String, LocalDate> memo = new HashMap<>();
        for (int i = 0; i < rules.size(); i++) {
            HolidayRule rule = rules.get(i);
            if (rule == null || !rule.enabled) continue;
            if (!matchesYear(rule, year)) continue;
            LocalDate date = resolveDate(rule, year, byId, memo, new HashSet<String>());
            if (date == null) continue;
            holidays.add(new HolidayInfo(rule.holidayType, date, rule.label, rule.courtStatus));
        }

        holidays.sort(Comparator
                .comparing(HolidayInfo::getHolidayDate)
                .thenComparing(HolidayInfo::getHolidayType)
                .thenComparing(HolidayInfo::getHolidayName));

        return holidays.toArray(new HolidayInfo[0]);
    }

    public static boolean isHoliday(LocalDate date) {
        return isHoliday("", date);
    }

    public static boolean isHoliday(String tenantUuid, LocalDate date) {
        if (date == null) return false;
        HolidayInfo[] holidays = calculateHolidays(tenantUuid, date.getYear());
        for (HolidayInfo holiday : holidays) {
            if (holiday.getHolidayDate().equals(date.toString())) {
                return true;
            }
        }
        return false;
    }

    public static boolean isWeekendOrHoliday(LocalDate date) {
        return isWeekendOrHoliday("", date);
    }

    public static boolean isWeekendOrHoliday(String tenantUuid, LocalDate date) {
        if (date == null) return false;
        return date.getDayOfWeek() == DayOfWeek.SATURDAY
                || date.getDayOfWeek() == DayOfWeek.SUNDAY
                || isHoliday(tenantUuid, date);
    }

    private static List<HolidayRule> loadRules(String tenantUuid) {
        String tenant = safe(tenantUuid).trim();
        try {
            String xml = tenant.isBlank() ? DEFAULT_HOLIDAY_XML : readTenantHolidayXml(tenant);
            try (InputStream in = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))) {
                List<HolidayRule> rules = parseRules(in);
                if (!rules.isEmpty()) return rules;
            }
        } catch (Exception ignored) {
        }
        try (InputStream in = new ByteArrayInputStream(DEFAULT_HOLIDAY_XML.getBytes(StandardCharsets.UTF_8))) {
            return parseRules(in);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to load default holiday rules.", ex);
        }
    }

    private static List<HolidayRule> parseRules(InputStream xmlInputStream) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setIgnoringComments(true);
        factory.setIgnoringElementContentWhitespace(true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

        DocumentBuilder builder = factory.newDocumentBuilder();
        Document document = builder.parse(xmlInputStream);
        document.getDocumentElement().normalize();

        Element root = document.getDocumentElement();
        if (root == null || !"holidaySet".equals(root.getTagName())) {
            throw new IllegalArgumentException("Root element must be <holidaySet>.");
        }

        List<HolidayRule> out = new ArrayList<>();
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (!(node instanceof Element) || !"holiday".equals(((Element) node).getTagName())) continue;
            Element el = (Element) node;

            HolidayRule rule = new HolidayRule();
            rule.id = requiredAttr(el, "id");
            rule.holidayType = defaultString(el.getAttribute("type"), "General");
            rule.label = requiredAttr(el, "label");
            rule.enabled = parseBoolean(defaultString(el.getAttribute("enabled"), "true"), true);
            rule.courtStatus = parseCourtStatus(el);
            rule.computationType = parseEnum(defaultString(el.getAttribute("computation"), "FIXED_DATE"), ComputationType.class);
            rule.observeMode = parseObserveMode(defaultString(el.getAttribute("observe"), "NONE"));
            rule.yearMin = parseInt(defaultString(el.getAttribute("yearMin"), "0"), 0);
            rule.yearMax = parseInt(defaultString(el.getAttribute("yearMax"), "0"), 0);
            rule.yearModulo = parseInt(defaultString(el.getAttribute("yearModulo"), "0"), 0);
            rule.yearModuloBase = parseInt(defaultString(el.getAttribute("yearModuloBase"), "0"), 0);
            rule.month = parseInt(defaultString(el.getAttribute("month"), "1"), 1);
            rule.day = parseInt(defaultString(el.getAttribute("day"), "1"), 1);
            rule.dayOfWeek = parseDayOfWeek(defaultString(el.getAttribute("dayOfWeek"), "MONDAY"));
            rule.ordinal = parseInt(defaultString(el.getAttribute("ordinal"), "1"), 1);
            rule.baseId = defaultString(el.getAttribute("baseId"), "");
            rule.offsetDays = parseInt(defaultString(el.getAttribute("offsetDays"), "0"), 0);

            out.add(rule);
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("Holiday XML must include at least one <holiday> rule.");
        }
        return out;
    }

    private static LocalDate resolveDate(HolidayRule rule,
                                         int year,
                                         Map<String, HolidayRule> byId,
                                         Map<String, LocalDate> memo,
                                         Set<String> visiting) {
        if (rule == null || rule.id == null || rule.id.isBlank()) return null;
        if (memo.containsKey(rule.id)) return memo.get(rule.id);
        if (visiting.contains(rule.id)) {
            throw new IllegalArgumentException("Circular holiday dependency detected: " + rule.id);
        }
        visiting.add(rule.id);

        LocalDate raw;
        switch (rule.computationType) {
            case FIXED_DATE:
                raw = LocalDate.of(year, Month.of(rule.month), rule.day);
                break;
            case NTH_WEEKDAY_OF_MONTH:
                raw = nthWeekdayOfMonth(year, Month.of(rule.month), rule.dayOfWeek, rule.ordinal);
                break;
            case LAST_WEEKDAY_OF_MONTH:
                raw = lastWeekdayOfMonth(year, Month.of(rule.month), rule.dayOfWeek);
                break;
            case RELATIVE:
                if (rule.baseId == null || rule.baseId.isBlank()) {
                    throw new IllegalArgumentException("Holiday rule " + rule.id + " requires baseId for RELATIVE computation.");
                }
                HolidayRule baseRule = byId.get(rule.baseId);
                if (baseRule == null) {
                    throw new IllegalArgumentException("Holiday rule " + rule.id + " references unknown baseId: " + rule.baseId);
                }
                if (!matchesYear(baseRule, year)) {
                    return null;
                }
                LocalDate baseDate = resolveDate(baseRule, year, byId, memo, visiting);
                if (baseDate == null) return null;
                raw = baseDate.plusDays(rule.offsetDays);
                break;
            default:
                throw new IllegalArgumentException("Unsupported holiday computation: " + rule.computationType);
        }

        LocalDate observed = applyObserve(raw, rule.observeMode);
        memo.put(rule.id, observed);
        visiting.remove(rule.id);
        return observed;
    }

    private static LocalDate applyObserve(LocalDate actualDate, ObserveMode observeMode) {
        if (actualDate == null) return null;
        ObserveMode mode = observeMode == null ? ObserveMode.NONE : observeMode;
        switch (mode) {
            case FEDERAL:
                return observeFederalFixedHoliday(actualDate);
            case SUNDAY_TO_MONDAY:
                return observeInaugurationDay(actualDate);
            case NONE:
            default:
                return actualDate;
        }
    }

    private static boolean matchesYear(HolidayRule rule, int year) {
        if (rule == null) return false;
        if (rule.yearMin > 0 && year < rule.yearMin) return false;
        if (rule.yearMax > 0 && year > rule.yearMax) return false;
        if (rule.yearModulo > 0) {
            int base = rule.yearModuloBase;
            return Math.floorMod(year - base, rule.yearModulo) == 0;
        }
        return true;
    }

    private static CourtSessionStatus parseCourtStatus(Element el) {
        String court = defaultString(el.getAttribute("court"), "");
        if (!court.isBlank()) {
            return parseEnum(court, CourtSessionStatus.class);
        }
        String courtInSession = defaultString(el.getAttribute("courtInSession"), "");
        if (!courtInSession.isBlank()) {
            return parseBoolean(courtInSession, false) ? CourtSessionStatus.YES : CourtSessionStatus.NO;
        }
        return CourtSessionStatus.NO;
    }

    private static ObserveMode parseObserveMode(String raw) {
        String value = defaultString(raw, "NONE").trim().toUpperCase(Locale.ROOT);
        if ("FEDERAL".equals(value)) return ObserveMode.FEDERAL;
        if ("SUNDAY_TO_MONDAY".equals(value)) return ObserveMode.SUNDAY_TO_MONDAY;
        return ObserveMode.NONE;
    }

    private static DayOfWeek parseDayOfWeek(String raw) {
        String value = defaultString(raw, "MONDAY").trim().toUpperCase(Locale.ROOT);
        if ("MON".equals(value)) return DayOfWeek.MONDAY;
        if ("TUE".equals(value) || "TUES".equals(value)) return DayOfWeek.TUESDAY;
        if ("WED".equals(value)) return DayOfWeek.WEDNESDAY;
        if ("THU".equals(value) || "THUR".equals(value) || "THURS".equals(value)) return DayOfWeek.THURSDAY;
        if ("FRI".equals(value)) return DayOfWeek.FRIDAY;
        if ("SAT".equals(value)) return DayOfWeek.SATURDAY;
        if ("SUN".equals(value)) return DayOfWeek.SUNDAY;
        return DayOfWeek.valueOf(value);
    }

    private static String requiredAttr(Element element, String name) {
        String value = element.getAttribute(name);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing required attribute '" + name + "' on <" + element.getTagName() + ">.");
        }
        return value.trim();
    }

    private static String defaultString(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(defaultString(value, String.valueOf(fallback)));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static boolean parseBoolean(String value, boolean fallback) {
        String v = defaultString(value, fallback ? "true" : "false").toLowerCase(Locale.ROOT);
        if ("true".equals(v) || "1".equals(v) || "yes".equals(v) || "on".equals(v)) return true;
        if ("false".equals(v) || "0".equals(v) || "no".equals(v) || "off".equals(v)) return false;
        return fallback;
    }

    private static <T extends Enum<T>> T parseEnum(String value, Class<T> enumClass) {
        return Enum.valueOf(enumClass, value.trim().toUpperCase(Locale.ROOT));
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String safeFileToken(String s) {
        String t = safe(s).trim();
        if (t.isBlank()) return "";
        return t.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static LocalDate nthWeekdayOfMonth(int year, Month month, DayOfWeek dayOfWeek, int ordinal) {
        return LocalDate.of(year, month, 1)
                .with(TemporalAdjusters.dayOfWeekInMonth(ordinal, dayOfWeek));
    }

    private static LocalDate lastWeekdayOfMonth(int year, Month month, DayOfWeek dayOfWeek) {
        return LocalDate.of(year, month, 1)
                .with(TemporalAdjusters.lastInMonth(dayOfWeek));
    }

    private static LocalDate observeFederalFixedHoliday(LocalDate actualDate) {
        if (actualDate.getDayOfWeek() == DayOfWeek.SATURDAY) {
            return actualDate.minusDays(1);
        }
        if (actualDate.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return actualDate.plusDays(1);
        }
        return actualDate;
    }

    private static LocalDate observeInaugurationDay(LocalDate jan20) {
        return (jan20.getDayOfWeek() == DayOfWeek.SUNDAY) ? jan20.plusDays(1) : jan20;
    }
}
