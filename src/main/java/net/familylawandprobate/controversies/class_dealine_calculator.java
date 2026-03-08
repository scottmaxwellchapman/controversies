package net.familylawandprobate.controversies;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * First-class deadline calculator.
 *
 * Supports:
 * - XML-defined triggers and computed action dates
 * - ISO-8601 returned dates
 * - calendar-day, business-day, court-day, and Texas short-period counting
 * - earlier-of / later-of computations
 * - special Texas "Monday next after expiration" computation
 * - optional federal 3-day service extension
 * - preceding/following business-day adjustment policies
 * - imaginary/internal action dates used only as dependencies
 * - detailed explanation strings with dates in sentence form
 *
 * Note: the class name intentionally matches the requested name: class_dealine_calculator.
 */
public class class_dealine_calculator {

    public enum Jurisdiction {
        TEXAS_CIVIL,
        TEXAS_FAMILY,
        TEXAS_CRIMINAL,
        FEDERAL_CIVIL,
        FEDERAL_CRIMINAL,
        GENERIC
    }

    public enum CountMethod {
        CALENDAR_DAYS,
        BUSINESS_DAYS,
        COURT_DAYS,
        TEXAS_SHORT_PERIOD,
        HOURS
    }

    public enum AdjustMode {
        NONE,
        PRECEDING,
        FOLLOWING,
        PREVIOUS_BUSINESS_DAY,
        NEXT_BUSINESS_DAY
    }

    public enum OperatorType {
        OFFSET,
        EARLIER_OF,
        LATER_OF,
        TEXAS_MONDAY_NEXT_AFTER_EXPIRATION,
        ADD_SERVICE_DAYS,
        START_OF_DAY,
        END_OF_DAY
    }

    public enum ServiceMethod {
        NONE,
        MAIL,
        CLERK,
        CONSENTED_OTHER,
        ELECTRONIC,
        HAND
    }

    public static class EngineOptions {
        private final Jurisdiction jurisdiction;
        private final ZoneId zoneId;
        private final LocalTime electronicFilingCutoff;
        private final Set<LocalDate> courtClosureDates;
        private final boolean includeImaginaryDates;

        public EngineOptions(Jurisdiction jurisdiction, ZoneId zoneId, LocalTime electronicFilingCutoff,
                             Set<LocalDate> courtClosureDates, boolean includeImaginaryDates) {
            this.jurisdiction = jurisdiction == null ? Jurisdiction.GENERIC : jurisdiction;
            this.zoneId = zoneId == null ? ZoneId.of("America/Chicago") : zoneId;
            this.electronicFilingCutoff = electronicFilingCutoff == null ? LocalTime.of(23, 59, 59) : electronicFilingCutoff;
            this.courtClosureDates = courtClosureDates == null ? new HashSet<>() : new HashSet<>(courtClosureDates);
            this.includeImaginaryDates = includeImaginaryDates;
        }

        public static EngineOptions defaults() {
            return new EngineOptions(Jurisdiction.GENERIC, ZoneId.of("America/Chicago"),
                    LocalTime.of(23, 59, 59), new HashSet<LocalDate>(), false);
        }

        public Jurisdiction getJurisdiction() {
            return jurisdiction;
        }

        public ZoneId getZoneId() {
            return zoneId;
        }

        public LocalTime getElectronicFilingCutoff() {
            return electronicFilingCutoff;
        }

        public Set<LocalDate> getCourtClosureDates() {
            return new HashSet<>(courtClosureDates);
        }

        public boolean isIncludeImaginaryDates() {
            return includeImaginaryDates;
        }
    }

    public static class TriggerEvent {
        private final String id;
        private final String label;
        private final LocalDate date;

        public TriggerEvent(String id, String label, LocalDate date) {
            this.id = id;
            this.label = label;
            this.date = date;
        }

        public String getId() {
            return id;
        }

        public String getLabel() {
            return label;
        }

        public LocalDate getDate() {
            return date;
        }
    }

    public static class ActionDateInfo {
        private final String uuid;
        private final String date;          // ISO-8601 yyyy-MM-dd
        private final String label;
        private final String explanation;
        private final boolean imaginary;
        private final String dateTimeIso;   // optional ISO-8601 date-time with offset

        public ActionDateInfo(String uuid, LocalDate date, String label, String explanation, boolean imaginary, String dateTimeIso) {
            this.uuid = uuid;
            this.date = date.toString();
            this.label = label;
            this.explanation = explanation;
            this.imaginary = imaginary;
            this.dateTimeIso = dateTimeIso;
        }

        public String getUuid() {
            return uuid;
        }

        public String getDate() {
            return date;
        }

        public String getLabel() {
            return label;
        }

        public String getExplanation() {
            return explanation;
        }

        public boolean isImaginary() {
            return imaginary;
        }

        public String getDateTimeIso() {
            return dateTimeIso;
        }

        @Override
        public String toString() {
            return "ActionDateInfo{" +
                    "uuid='" + uuid + '\'' +
                    ", date='" + date + '\'' +
                    ", label='" + label + '\'' +
                    ", explanation='" + explanation + '\'' +
                    ", imaginary=" + imaginary +
                    ", dateTimeIso='" + dateTimeIso + '\'' +
                    '}';
        }
    }

    private static class RuleAction {
        String uuid;
        String id;
        String label;
        boolean imaginary;
        OperatorType operator;
        String baseRef;
        String leftRef;
        String rightRef;
        long offsetValue;
        CountMethod countMethod;
        AdjustMode adjustMode;
        boolean excludeStartDay;
        LocalTime timeOfDay;
        String explanationTemplate;
        ServiceMethod serviceMethod;
        int serviceDaysToAdd;
    }

    private static class ComputedAction {
        final String uuid;
        final String label;
        final boolean imaginary;
        final LocalDate date;
        final OffsetDateTime dateTime;
        final String explanation;

        ComputedAction(String uuid, String label, boolean imaginary, LocalDate date, OffsetDateTime dateTime, String explanation) {
            this.uuid = uuid;
            this.label = label;
            this.imaginary = imaginary;
            this.date = date;
            this.dateTime = dateTime;
            this.explanation = explanation;
        }
    }

    public static class DeadlineDefinition {
        private final String id;
        private final String label;
        private final Map<String, TriggerEvent> triggers;
        private final List<RuleAction> actions;

        public DeadlineDefinition(String id, String label, Map<String, TriggerEvent> triggers, List<RuleAction> actions) {
            this.id = id;
            this.label = label;
            this.triggers = triggers;
            this.actions = actions;
        }

        public String getId() {
            return id;
        }

        public String getLabel() {
            return label;
        }

        public Map<String, TriggerEvent> getTriggers() {
            return triggers;
        }

        public List<String> getActionUuids() {
            List<String> ids = new ArrayList<>();
            for (RuleAction action : actions) {
                ids.add(action.uuid);
            }
            return ids;
        }
    }

    public static ActionDateInfo[] calculateDeadlines(InputStream xmlInputStream) throws Exception {
        return calculateDeadlines(xmlInputStream, EngineOptions.defaults());
    }

    public static ActionDateInfo[] calculateDeadlines(InputStream xmlInputStream, EngineOptions options) throws Exception {
        DeadlineDefinition definition = parseDefinition(xmlInputStream);
        return calculateDeadlines(definition, options);
    }

    public static ActionDateInfo[] calculateDeadlines(DeadlineDefinition definition, EngineOptions options) {
        EngineOptions effectiveOptions = options == null ? EngineOptions.defaults() : options;
        Map<String, ComputedAction> computed = new LinkedHashMap<>();

        for (TriggerEvent trigger : definition.triggers.values()) {
            computed.put(trigger.id, new ComputedAction(
                    trigger.id,
                    trigger.label,
                    true,
                    trigger.date,
                    null,
                    "Triggering event \"" + trigger.label + "\" occurs on " + trigger.date + "."
            ));
        }

        topologicalValidate(definition.actions, definition.triggers.keySet());

        List<RuleAction> remaining = new ArrayList<>(definition.actions);
        int guard = 0;
        while (!remaining.isEmpty()) {
            if (++guard > 10000) {
                throw new IllegalStateException("Unable to resolve action dependencies; possible circular reference.");
            }
            boolean progressed = false;
            for (int i = 0; i < remaining.size(); i++) {
                RuleAction action = remaining.get(i);
                if (canCompute(action, computed)) {
                    ComputedAction result = computeAction(action, computed, effectiveOptions);
                    computed.put(action.uuid, result);
                    remaining.remove(i);
                    i--;
                    progressed = true;
                }
            }
            if (!progressed) {
                throw new IllegalStateException("Unable to resolve one or more actions. Check XML references.");
            }
        }

        List<ActionDateInfo> out = new ArrayList<>();
        for (RuleAction action : definition.actions) {
            ComputedAction computedAction = computed.get(action.uuid);
            if (!action.imaginary || effectiveOptions.isIncludeImaginaryDates()) {
                out.add(new ActionDateInfo(
                        computedAction.uuid,
                        computedAction.date,
                        computedAction.label,
                        computedAction.explanation,
                        action.imaginary,
                        computedAction.dateTime == null ? null : computedAction.dateTime.toString()
                ));
            }
        }

        out.sort(Comparator
                .comparing(ActionDateInfo::getDate)
                .thenComparing(ActionDateInfo::getLabel)
                .thenComparing(ActionDateInfo::getUuid));

        return out.toArray(new ActionDateInfo[0]);
    }

    private static boolean canCompute(RuleAction rule, Map<String, ComputedAction> computed) {
        switch (rule.operator) {
            case OFFSET:
            case TEXAS_MONDAY_NEXT_AFTER_EXPIRATION:
            case ADD_SERVICE_DAYS:
            case START_OF_DAY:
            case END_OF_DAY:
                return computed.containsKey(rule.baseRef);
            case EARLIER_OF:
            case LATER_OF:
                return computed.containsKey(rule.leftRef) && computed.containsKey(rule.rightRef);
            default:
                return false;
        }
    }

    private static ComputedAction computeAction(RuleAction rule, Map<String, ComputedAction> computed, EngineOptions options) {
        switch (rule.operator) {
            case OFFSET:
                return computeOffsetAction(rule, computed, options);
            case EARLIER_OF:
                return computeComparisonAction(rule, computed, options, true);
            case LATER_OF:
                return computeComparisonAction(rule, computed, options, false);
            case TEXAS_MONDAY_NEXT_AFTER_EXPIRATION:
                return computeTexasMondayNext(rule, computed, options);
            case ADD_SERVICE_DAYS:
                return computeServiceDays(rule, computed, options);
            case START_OF_DAY:
                return computeBoundary(rule, computed, options, true);
            case END_OF_DAY:
                return computeBoundary(rule, computed, options, false);
            default:
                throw new IllegalStateException("Unsupported operator: " + rule.operator);
        }
    }

    private static ComputedAction computeOffsetAction(RuleAction rule, Map<String, ComputedAction> computed, EngineOptions options) {
        ComputedAction base = computed.get(rule.baseRef);
        LocalDate rawDate = addByMethod(base.date, rule.offsetValue, rule.countMethod, rule.excludeStartDay, options);
        LocalDate adjustedDate = applyAdjustment(rawDate, rule.adjustMode, options);
        OffsetDateTime dateTime = attachOptionalTime(adjustedDate, rule.timeOfDay, options.getZoneId());
        String explanation = buildOffsetExplanation(rule, base, rawDate, adjustedDate, options);
        return new ComputedAction(rule.uuid, rule.label, rule.imaginary, adjustedDate, dateTime, explanation);
    }

    private static ComputedAction computeComparisonAction(RuleAction rule, Map<String, ComputedAction> computed,
                                                          EngineOptions options, boolean earlier) {
        ComputedAction left = computed.get(rule.leftRef);
        ComputedAction right = computed.get(rule.rightRef);
        LocalDate rawDate = earlier
                ? (left.date.isAfter(right.date) ? right.date : left.date)
                : (left.date.isAfter(right.date) ? left.date : right.date);
        LocalDate adjustedDate = applyAdjustment(rawDate, rule.adjustMode, options);
        OffsetDateTime dateTime = attachOptionalTime(adjustedDate, rule.timeOfDay, options.getZoneId());
        String explanation = buildComparisonExplanation(rule, left, right, rawDate, adjustedDate, earlier);
        return new ComputedAction(rule.uuid, rule.label, rule.imaginary, adjustedDate, dateTime, explanation);
    }

    private static ComputedAction computeTexasMondayNext(RuleAction rule, Map<String, ComputedAction> computed, EngineOptions options) {
        ComputedAction base = computed.get(rule.baseRef);
        LocalDate expiration = addByMethod(base.date, rule.offsetValue, CountMethod.CALENDAR_DAYS, true, options);
        LocalDate mondayNext = nextMondayAfter(expiration);
        LocalDate adjustedDate = applyAdjustment(mondayNext, rule.adjustMode, options);
        LocalTime timeOfDay = rule.timeOfDay == null ? LocalTime.of(10, 0) : rule.timeOfDay;
        OffsetDateTime dateTime = attachOptionalTime(adjustedDate, timeOfDay, options.getZoneId());

        String fallback = "\"" + rule.label + "\" is computed from \"" + base.label + "\" (" + base.date + ") by " +
                "calculating the expiration of " + rule.offsetValue + " days on " + expiration + ", then moving to the next Monday, " +
                adjustedDate + ", at " + timeOfDay + ".";
        if (!mondayNext.equals(adjustedDate)) {
            fallback += " Because the Monday date fell on a Saturday, Sunday, or legal holiday under the configured calendar, " +
                    "the deadline was adjusted to " + adjustedDate + ".";
        }
        String explanation = applyTemplate(rule.explanationTemplate, values(
                "label", rule.label,
                "baseLabel", base.label,
                "baseDate", base.date.toString(),
                "expirationDate", expiration.toString(),
                "finalDate", adjustedDate.toString(),
                "finalDateTime", dateTime.toString()
        ), fallback);

        return new ComputedAction(rule.uuid, rule.label, rule.imaginary, adjustedDate, dateTime, explanation);
    }

    private static ComputedAction computeServiceDays(RuleAction rule, Map<String, ComputedAction> computed, EngineOptions options) {
        ComputedAction base = computed.get(rule.baseRef);
        LocalDate rawDate = base.date;
        int addDays = qualifiesForFederalServiceDays(rule.serviceMethod) ? rule.serviceDaysToAdd : 0;
        if (addDays != 0) {
            rawDate = addByMethod(base.date, addDays, CountMethod.CALENDAR_DAYS, false, options);
        }
        LocalDate adjustedDate = applyAdjustment(rawDate, rule.adjustMode, options);
        OffsetDateTime dateTime = attachOptionalTime(adjustedDate, rule.timeOfDay, options.getZoneId());
        String fallback = "\"" + rule.label + "\" starts from \"" + base.label + "\" (" + base.date + "). " +
                (addDays == 0
                        ? "No additional service days were added under the selected service method."
                        : addDays + " service day(s) were added, yielding " + rawDate + ".") +
                (adjustedDate.equals(rawDate)
                        ? " No further weekend/holiday adjustment was required."
                        : " The resulting date was adjusted to " + adjustedDate + " because " + rawDate + " falls on a Saturday, Sunday, or legal holiday.");
        String explanation = applyTemplate(rule.explanationTemplate, values(
                "label", rule.label,
                "baseLabel", base.label,
                "baseDate", base.date.toString(),
                "rawDate", rawDate.toString(),
                "finalDate", adjustedDate.toString(),
                "serviceMethod", rule.serviceMethod.name(),
                "serviceDaysAdded", String.valueOf(addDays)
        ), fallback);
        return new ComputedAction(rule.uuid, rule.label, rule.imaginary, adjustedDate, dateTime, explanation);
    }

    private static ComputedAction computeBoundary(RuleAction rule, Map<String, ComputedAction> computed,
                                                  EngineOptions options, boolean startOfDay) {
        ComputedAction base = computed.get(rule.baseRef);
        LocalDate adjustedDate = applyAdjustment(base.date, rule.adjustMode, options);
        LocalTime time = startOfDay ? LocalTime.MIN : options.getElectronicFilingCutoff();
        OffsetDateTime dateTime = attachOptionalTime(adjustedDate, time, options.getZoneId());
        String explanation = "\"" + rule.label + "\" uses the " + (startOfDay ? "start" : "end") +
                " of day for \"" + base.label + "\" on " + adjustedDate + ".";
        return new ComputedAction(rule.uuid, rule.label, rule.imaginary, adjustedDate, dateTime, explanation);
    }

    private static LocalDate addByMethod(LocalDate start, long amount, CountMethod countMethod,
                                         boolean excludeStartDay, EngineOptions options) {
        if (amount == 0) {
            return excludeStartDay ? start : start;
        }
        switch (countMethod) {
            case CALENDAR_DAYS:
                return excludeStartDay ? start.plusDays(amount) : start.plusDays(amount);
            case BUSINESS_DAYS:
                return addBusinessDays(start, amount, excludeStartDay, false, options);
            case COURT_DAYS:
                return addBusinessDays(start, amount, excludeStartDay, true, options);
            case TEXAS_SHORT_PERIOD:
                return addTexasShortPeriod(start, amount, excludeStartDay, options);
            case HOURS:
                long days = amount / 24;
                long remainder = amount % 24;
                LocalDate candidate = start.plusDays(days);
                return remainder == 0 ? candidate : candidate.plusDays(remainder > 0 ? 1 : -1);
            default:
                return start.plusDays(amount);
        }
    }

    private static LocalDate addBusinessDays(LocalDate start, long amount, boolean excludeStartDay,
                                             boolean includeCourtClosures, EngineOptions options) {
        if (amount == 0) {
            return start;
        }
        LocalDate date = start;
        long remaining = Math.abs(amount);
        int step = amount > 0 ? 1 : -1;

        if (!excludeStartDay && isBusinessDay(date, includeCourtClosures, options)) {
            remaining--;
            if (remaining < 0) {
                return date;
            }
        }

        while (remaining > 0) {
            date = date.plusDays(step);
            if (isBusinessDay(date, includeCourtClosures, options)) {
                remaining--;
            }
        }
        return date;
    }

    /**
     * Texas short period support:
     * - excludes the trigger day
     * - if the period is 5 days or less, skips intermediate Saturdays, Sundays, and legal holidays
     * - otherwise uses ordinary calendar day counting
     */
    private static LocalDate addTexasShortPeriod(LocalDate start, long amount, boolean excludeStartDay, EngineOptions options) {
        long abs = Math.abs(amount);
        if (abs > 5) {
            return start.plusDays(amount);
        }
        LocalDate date = start;
        long remaining = abs;
        int step = amount >= 0 ? 1 : -1;

        if (!excludeStartDay && !isNonBusinessDay(date, options)) {
            remaining--;
        }

        while (remaining > 0) {
            date = date.plusDays(step);
            if (!isNonBusinessDay(date, options)) {
                remaining--;
            }
        }
        return date;
    }

    private static LocalDate applyAdjustment(LocalDate date, AdjustMode mode, EngineOptions options) {
        if (mode == null || mode == AdjustMode.NONE) {
            return date;
        }
        LocalDate adjusted = date;
        switch (mode) {
            case PRECEDING:
            case PREVIOUS_BUSINESS_DAY:
                while (isNonBusinessDay(adjusted, options)) {
                    adjusted = adjusted.minusDays(1);
                }
                return adjusted;
            case FOLLOWING:
            case NEXT_BUSINESS_DAY:
                while (isNonBusinessDay(adjusted, options)) {
                    adjusted = adjusted.plusDays(1);
                }
                return adjusted;
            default:
                return adjusted;
        }
    }

    private static boolean isBusinessDay(LocalDate date, boolean includeCourtClosures, EngineOptions options) {
        if (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return false;
        }
        if (class_holiday_calculator.isHoliday(date)) {
            return false;
        }
        return !includeCourtClosures || !options.getCourtClosureDates().contains(date);
    }

    private static boolean isNonBusinessDay(LocalDate date, EngineOptions options) {
        return !isBusinessDay(date, true, options);
    }

    private static LocalDate nextMondayAfter(LocalDate date) {
        LocalDate candidate = date.plusDays(1);
        while (candidate.getDayOfWeek() != DayOfWeek.MONDAY) {
            candidate = candidate.plusDays(1);
        }
        return candidate;
    }

    private static boolean qualifiesForFederalServiceDays(ServiceMethod serviceMethod) {
        return serviceMethod == ServiceMethod.MAIL
                || serviceMethod == ServiceMethod.CLERK
                || serviceMethod == ServiceMethod.CONSENTED_OTHER;
    }

    private static OffsetDateTime attachOptionalTime(LocalDate date, LocalTime time, ZoneId zoneId) {
        if (time == null) {
            return null;
        }
        ZonedDateTime zonedDateTime = ZonedDateTime.of(date, time, zoneId);
        return zonedDateTime.toOffsetDateTime();
    }

    private static String buildOffsetExplanation(RuleAction rule, ComputedAction base,
                                                 LocalDate rawDate, LocalDate adjustedDate, EngineOptions options) {
        String unitPhrase = rule.countMethod == CountMethod.HOURS ? "hour(s)" : "day(s)";
        String fallback = "\"" + rule.label + "\" is calculated from \"" + base.label + "\" (" + base.date + ") by " +
                (rule.offsetValue >= 0 ? "adding " : "subtracting ") + Math.abs(rule.offsetValue) + " " + unitPhrase +
                " using " + rule.countMethod + ", which yields " + rawDate + ".";
        if (!rawDate.equals(adjustedDate)) {
            fallback += " Because " + rawDate + " falls on a Saturday, Sunday, legal holiday, or configured closure day, " +
                    "the deadline is adjusted to " + adjustedDate + ".";
        } else if (rule.adjustMode != AdjustMode.NONE) {
            fallback += " No weekend, holiday, or closure adjustment was required.";
        }
        return applyTemplate(rule.explanationTemplate, values(
                "label", rule.label,
                "baseLabel", base.label,
                "baseDate", base.date.toString(),
                "rawDate", rawDate.toString(),
                "finalDate", adjustedDate.toString(),
                "offsetValue", String.valueOf(rule.offsetValue),
                "countMethod", rule.countMethod.name(),
                "jurisdiction", options.getJurisdiction().name()
        ), fallback);
    }

    private static String buildComparisonExplanation(RuleAction rule, ComputedAction left, ComputedAction right,
                                                     LocalDate rawDate, LocalDate adjustedDate, boolean earlier) {
        String which = earlier ? "earlier" : "later";
        String fallback = "\"" + rule.label + "\" is the " + which + " of \"" + left.label + "\" (" + left.date + ") and " +
                "\"" + right.label + "\" (" + right.date + "), producing " + rawDate + ".";
        if (!rawDate.equals(adjustedDate)) {
            fallback += " Because " + rawDate + " falls on a Saturday, Sunday, or legal holiday, the date is adjusted to " + adjustedDate + ".";
        }
        return applyTemplate(rule.explanationTemplate, values(
                "label", rule.label,
                "leftLabel", left.label,
                "leftDate", left.date.toString(),
                "rightLabel", right.label,
                "rightDate", right.date.toString(),
                "rawDate", rawDate.toString(),
                "finalDate", adjustedDate.toString()
        ), fallback);
    }

    public static DeadlineDefinition parseDefinition(InputStream xmlInputStream) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setIgnoringComments(true);
        factory.setIgnoringElementContentWhitespace(true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document document = builder.parse(xmlInputStream);
        document.getDocumentElement().normalize();

        Element root = document.getDocumentElement();
        if (!"deadlineSet".equals(root.getTagName())) {
            throw new IllegalArgumentException("Root element must be <deadlineSet>.");
        }

        String setId = root.getAttribute("id");
        String setLabel = root.getAttribute("label");

        Map<String, TriggerEvent> triggers = parseTriggers(root);
        List<RuleAction> actions = parseActions(root);
        validateDefinition(triggers, actions);

        return new DeadlineDefinition(setId, setLabel, triggers, actions);
    }

    private static Map<String, TriggerEvent> parseTriggers(Element root) {
        Map<String, TriggerEvent> triggers = new LinkedHashMap<>();
        Element triggersElement = firstChildElement(root, "triggers");
        if (triggersElement != null) {
            NodeList children = triggersElement.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node node = children.item(i);
                if (node instanceof Element && "trigger".equals(((Element) node).getTagName())) {
                    Element el = (Element) node;
                    String id = requiredAttr(el, "id");
                    String label = requiredAttr(el, "label");
                    LocalDate date = LocalDate.parse(requiredAttr(el, "date"));
                    triggers.put(id, new TriggerEvent(id, label, date));
                }
            }
        }
        Element legacyTrigger = firstChildElement(root, "trigger");
        if (legacyTrigger != null) {
            String id = legacyTrigger.hasAttribute("id") ? legacyTrigger.getAttribute("id") : "trigger";
            if (!triggers.containsKey(id)) {
                String label = requiredAttr(legacyTrigger, "label");
                String dateValue = legacyTrigger.getAttribute("date");
                if (dateValue == null || dateValue.trim().isEmpty()) {
                    throw new IllegalArgumentException("Legacy <trigger> must include a date attribute.");
                }
                triggers.put(id, new TriggerEvent(id, label, LocalDate.parse(dateValue)));
            }
        }
        if (triggers.isEmpty()) {
            throw new IllegalArgumentException("At least one trigger is required.");
        }
        return triggers;
    }

    private static List<RuleAction> parseActions(Element root) {
        Element actionsElement = firstChildElement(root, "actions");
        if (actionsElement == null) {
            throw new IllegalArgumentException("Missing <actions> element.");
        }
        List<RuleAction> actions = new ArrayList<>();
        NodeList children = actionsElement.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (!(node instanceof Element) || !"action".equals(((Element) node).getTagName())) {
                continue;
            }
            Element el = (Element) node;
            RuleAction action = new RuleAction();
            action.uuid = requiredAttr(el, "uuid");
            action.id = el.hasAttribute("id") ? el.getAttribute("id") : action.uuid;
            action.label = requiredAttr(el, "label");
            action.imaginary = Boolean.parseBoolean(defaultString(el.getAttribute("imaginary"), defaultString(el.getAttribute("internal"), "false")));
            action.operator = parseEnum(defaultString(el.getAttribute("operator"), defaultString(el.getAttribute("type"), "OFFSET")), OperatorType.class);
            action.adjustMode = parseEnum(defaultString(el.getAttribute("adjust"), "NONE"), AdjustMode.class);
            action.countMethod = parseEnum(defaultString(el.getAttribute("countMethod"), "CALENDAR_DAYS"), CountMethod.class);
            action.excludeStartDay = Boolean.parseBoolean(defaultString(el.getAttribute("excludeStartDay"), "true"));
            action.explanationTemplate = el.getAttribute("explanationTemplate");
            action.serviceMethod = parseEnum(defaultString(el.getAttribute("serviceMethod"), "NONE"), ServiceMethod.class);
            action.serviceDaysToAdd = Integer.parseInt(defaultString(el.getAttribute("serviceDaysToAdd"), "3"));
            action.timeOfDay = parseOptionalLocalTime(el.getAttribute("timeOfDay"));

            switch (action.operator) {
                case OFFSET:
                case TEXAS_MONDAY_NEXT_AFTER_EXPIRATION:
                    action.baseRef = requiredAttr(el, "baseRef");
                    action.offsetValue = Long.parseLong(defaultString(el.getAttribute("offsetDays"), defaultString(el.getAttribute("offsetValue"), "0")));
                    break;
                case ADD_SERVICE_DAYS:
                case START_OF_DAY:
                case END_OF_DAY:
                    action.baseRef = requiredAttr(el, "baseRef");
                    break;
                case EARLIER_OF:
                case LATER_OF:
                    action.leftRef = requiredAttr(el, "leftRef");
                    action.rightRef = requiredAttr(el, "rightRef");
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported operator in XML: " + action.operator);
            }
            actions.add(action);
        }
        return actions;
    }

    private static void validateDefinition(Map<String, TriggerEvent> triggers, List<RuleAction> actions) {
        Map<String, RuleAction> ids = new HashMap<>();
        for (RuleAction action : actions) {
            if (ids.containsKey(action.uuid)) {
                throw new IllegalArgumentException("Duplicate action uuid: " + action.uuid);
            }
            ids.put(action.uuid, action);
        }
        for (RuleAction action : actions) {
            switch (action.operator) {
                case OFFSET:
                case TEXAS_MONDAY_NEXT_AFTER_EXPIRATION:
                case ADD_SERVICE_DAYS:
                case START_OF_DAY:
                case END_OF_DAY:
                    validateReference(action.baseRef, triggers, ids, action.uuid);
                    break;
                case EARLIER_OF:
                case LATER_OF:
                    validateReference(action.leftRef, triggers, ids, action.uuid);
                    validateReference(action.rightRef, triggers, ids, action.uuid);
                    break;
                default:
                    break;
            }
        }
    }

    private static void topologicalValidate(List<RuleAction> actions, Set<String> triggerIds) {
        Map<String, Set<String>> deps = new HashMap<>();
        for (RuleAction action : actions) {
            Set<String> depSet = new HashSet<>();
            if (action.baseRef != null && !triggerIds.contains(action.baseRef)) {
                depSet.add(action.baseRef);
            }
            if (action.leftRef != null && !triggerIds.contains(action.leftRef)) {
                depSet.add(action.leftRef);
            }
            if (action.rightRef != null && !triggerIds.contains(action.rightRef)) {
                depSet.add(action.rightRef);
            }
            deps.put(action.uuid, depSet);
        }
        Deque<String> ready = new ArrayDeque<>();
        Map<String, Integer> indegree = new HashMap<>();
        for (Map.Entry<String, Set<String>> e : deps.entrySet()) {
            indegree.put(e.getKey(), e.getValue().size());
            if (e.getValue().isEmpty()) {
                ready.add(e.getKey());
            }
        }
        int seen = 0;
        while (!ready.isEmpty()) {
            String id = ready.removeFirst();
            seen++;
            for (Map.Entry<String, Set<String>> e : deps.entrySet()) {
                if (e.getValue().remove(id)) {
                    int next = indegree.get(e.getKey()) - 1;
                    indegree.put(e.getKey(), next);
                    if (next == 0) {
                        ready.add(e.getKey());
                    }
                }
            }
        }
        if (seen != actions.size()) {
            throw new IllegalArgumentException("Circular dependency detected in action graph.");
        }
    }

    private static void validateReference(String ref, Map<String, TriggerEvent> triggers,
                                          Map<String, RuleAction> actionIds, String owner) {
        if (ref == null || ref.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing reference on action " + owner + ".");
        }
        if (triggers.containsKey(ref) || actionIds.containsKey(ref)) {
            return;
        }
        throw new IllegalArgumentException("Action " + owner + " references unknown id: " + ref);
    }

    private static Element firstChildElement(Element parent, String tagName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element && tagName.equals(((Element) node).getTagName())) {
                return (Element) node;
            }
        }
        return null;
    }

    private static String requiredAttr(Element element, String name) {
        String value = element.getAttribute(name);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing required attribute '" + name + "' on <" + element.getTagName() + ">.");
        }
        return value.trim();
    }

    private static String defaultString(String value, String defaultValue) {
        return value == null || value.trim().isEmpty() ? defaultValue : value.trim();
    }

    private static <T extends Enum<T>> T parseEnum(String value, Class<T> enumClass) {
        return Enum.valueOf(enumClass, value.trim().toUpperCase());
    }

    private static LocalTime parseOptionalLocalTime(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return LocalTime.parse(value.trim());
    }

    private static Map<String, String> values(String... kv) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(kv[i], kv[i + 1]);
        }
        return map;
    }

    private static String applyTemplate(String template, Map<String, String> values, String fallback) {
        if (template == null || template.trim().isEmpty()) {
            return fallback;
        }
        String out = template;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            out = out.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return out;
    }

    public static void main(String[] args) throws Exception {
        String xml =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<deadlineSet id=\"demo\" label=\"Demonstration\">" +
                "  <triggers>" +
                "    <trigger id=\"serviceDate\" label=\"Service Date\" date=\"2026-03-10\"/>" +
                "    <trigger id=\"complaintFiled\" label=\"Complaint Filed\" date=\"2026-11-30\"/>" +
                "  </triggers>" +
                "  <actions>" +
                "    <action uuid=\"a1\" label=\"Texas Answer Due\" operator=\"TEXAS_MONDAY_NEXT_AFTER_EXPIRATION\" baseRef=\"serviceDate\" offsetDays=\"20\" adjust=\"NEXT_BUSINESS_DAY\" timeOfDay=\"10:00\"/>" +
                "    <action uuid=\"a2\" label=\"Federal Service Deadline\" operator=\"OFFSET\" baseRef=\"complaintFiled\" offsetDays=\"90\" countMethod=\"CALENDAR_DAYS\" adjust=\"NEXT_BUSINESS_DAY\"/>" +
                "    <action uuid=\"a3\" label=\"Internal Reminder\" imaginary=\"true\" operator=\"OFFSET\" baseRef=\"a1\" offsetDays=\"-5\" countMethod=\"BUSINESS_DAYS\" adjust=\"PREVIOUS_BUSINESS_DAY\"/>" +
                "    <action uuid=\"a4\" label=\"Controlling Date\" operator=\"EARLIER_OF\" leftRef=\"a1\" rightRef=\"a2\" adjust=\"NONE\"/>" +
                "  </actions>" +
                "</deadlineSet>";

        ActionDateInfo[] results = calculateDeadlines(
                new java.io.ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)),
                EngineOptions.defaults());
        System.out.println(Arrays.toString(results));
    }
}
