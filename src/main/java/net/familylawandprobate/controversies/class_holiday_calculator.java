package net.familylawandprobate.controversies;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Holiday calculator for federal and Texas holidays.
 *
 * Returned holiday dates are ISO-8601 strings (yyyy-MM-dd).
 * Holiday names follow the requested format:
 *   [Holiday/Federal] Christmas Day (Court: No)
 */
public class class_holiday_calculator {

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

    public static HolidayInfo[] calculateHolidays(int year) {
        List<HolidayInfo> holidays = new ArrayList<>();
        final CourtSessionStatus closed = CourtSessionStatus.NO;

        // Federal holidays.
        holidays.add(new HolidayInfo("Federal", observeFederalFixedHoliday(LocalDate.of(year, Month.JANUARY, 1)), "New Year's Day", closed));
        holidays.add(new HolidayInfo("Federal", nthWeekdayOfMonth(year, Month.JANUARY, DayOfWeek.MONDAY, 3), "Birthday of Martin Luther King, Jr.", closed));
        holidays.add(new HolidayInfo("Federal", nthWeekdayOfMonth(year, Month.FEBRUARY, DayOfWeek.MONDAY, 3), "Washington's Birthday", closed));
        holidays.add(new HolidayInfo("Federal", lastWeekdayOfMonth(year, Month.MAY, DayOfWeek.MONDAY), "Memorial Day", closed));
        holidays.add(new HolidayInfo("Federal", observeFederalFixedHoliday(LocalDate.of(year, Month.JUNE, 19)), "Juneteenth National Independence Day", closed));
        holidays.add(new HolidayInfo("Federal", observeFederalFixedHoliday(LocalDate.of(year, Month.JULY, 4)), "Independence Day", closed));
        holidays.add(new HolidayInfo("Federal", nthWeekdayOfMonth(year, Month.SEPTEMBER, DayOfWeek.MONDAY, 1), "Labor Day", closed));
        holidays.add(new HolidayInfo("Federal", nthWeekdayOfMonth(year, Month.OCTOBER, DayOfWeek.MONDAY, 2), "Columbus Day", closed));
        holidays.add(new HolidayInfo("Federal", observeFederalFixedHoliday(LocalDate.of(year, Month.NOVEMBER, 11)), "Veterans Day", closed));
        holidays.add(new HolidayInfo("Federal", nthWeekdayOfMonth(year, Month.NOVEMBER, DayOfWeek.THURSDAY, 4), "Thanksgiving Day", closed));
        holidays.add(new HolidayInfo("Federal", observeFederalFixedHoliday(LocalDate.of(year, Month.DECEMBER, 25)), "Christmas Day", closed));

        if (isFederalInaugurationYear(year)) {
            holidays.add(new HolidayInfo("Federal",
                    observeInaugurationDay(LocalDate.of(year, Month.JANUARY, 20)),
                    "Inauguration Day",
                    closed));
        }

        // Texas holidays.
        holidays.add(new HolidayInfo("Texas", LocalDate.of(year, Month.JANUARY, 1), "New Year's Day", closed));
        holidays.add(new HolidayInfo("Texas", nthWeekdayOfMonth(year, Month.JANUARY, DayOfWeek.MONDAY, 3), "Martin Luther King, Jr., Day", closed));
        holidays.add(new HolidayInfo("Texas", nthWeekdayOfMonth(year, Month.FEBRUARY, DayOfWeek.MONDAY, 3), "Presidents' Day", closed));
        holidays.add(new HolidayInfo("Texas", lastWeekdayOfMonth(year, Month.MAY, DayOfWeek.MONDAY), "Memorial Day", closed));
        holidays.add(new HolidayInfo("Texas", LocalDate.of(year, Month.JULY, 4), "Independence Day", closed));
        holidays.add(new HolidayInfo("Texas", nthWeekdayOfMonth(year, Month.SEPTEMBER, DayOfWeek.MONDAY, 1), "Labor Day", closed));
        holidays.add(new HolidayInfo("Texas", LocalDate.of(year, Month.NOVEMBER, 11), "Veterans Day", closed));
        holidays.add(new HolidayInfo("Texas", nthWeekdayOfMonth(year, Month.NOVEMBER, DayOfWeek.THURSDAY, 4), "Thanksgiving Day", closed));
        holidays.add(new HolidayInfo("Texas", LocalDate.of(year, Month.DECEMBER, 25), "Christmas Day", closed));

        holidays.add(new HolidayInfo("Texas", LocalDate.of(year, Month.JANUARY, 19), "Confederate Heroes Day", closed));
        holidays.add(new HolidayInfo("Texas", LocalDate.of(year, Month.MARCH, 2), "Texas Independence Day", closed));
        holidays.add(new HolidayInfo("Texas", LocalDate.of(year, Month.APRIL, 21), "San Jacinto Day", closed));
        holidays.add(new HolidayInfo("Texas", LocalDate.of(year, Month.JUNE, 19), "Emancipation Day in Texas", closed));
        holidays.add(new HolidayInfo("Texas", LocalDate.of(year, Month.AUGUST, 27), "Lyndon Baines Johnson Day", closed));
        holidays.add(new HolidayInfo("Texas", fridayAfterThanksgiving(year), "Friday after Thanksgiving Day", closed));
        holidays.add(new HolidayInfo("Texas", LocalDate.of(year, Month.DECEMBER, 24), "December 24", closed));
        holidays.add(new HolidayInfo("Texas", LocalDate.of(year, Month.DECEMBER, 26), "December 26", closed));

        holidays.sort(Comparator
                .comparing(HolidayInfo::getHolidayDate)
                .thenComparing(HolidayInfo::getHolidayType)
                .thenComparing(HolidayInfo::getHolidayName));

        return holidays.toArray(new HolidayInfo[0]);
    }

    public static boolean isHoliday(LocalDate date) {
        HolidayInfo[] holidays = calculateHolidays(date.getYear());
        for (HolidayInfo holiday : holidays) {
            if (holiday.getHolidayDate().equals(date.toString())) {
                return true;
            }
        }
        return false;
    }

    public static boolean isWeekendOrHoliday(LocalDate date) {
        return date.getDayOfWeek() == DayOfWeek.SATURDAY
                || date.getDayOfWeek() == DayOfWeek.SUNDAY
                || isHoliday(date);
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

    private static boolean isFederalInaugurationYear(int year) {
        return year >= 1965 && (year - 1965) % 4 == 0;
    }

    private static LocalDate fridayAfterThanksgiving(int year) {
        return nthWeekdayOfMonth(year, Month.NOVEMBER, DayOfWeek.THURSDAY, 4).plusDays(1);
    }
}
