package net.familylawandprobate.controversies;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class class_holiday_calculator {

    public static class HolidayInfo {
        private final String holidayType;      // Federal / Texas
        private final String holidayDate;      // ISO-8601 date: yyyy-MM-dd
        private final String holidayName;      // e.g. "[Holiday/Federal] Christmas Day (Court: No)"
        private final boolean courtInSession;  // true / false

        public HolidayInfo(String holidayType, LocalDate holidayDate, String baseHolidayName, boolean courtInSession) {
            this.holidayType = holidayType;
            this.holidayDate = holidayDate.toString(); // ISO format
            this.courtInSession = courtInSession;
            this.holidayName = formatHolidayName(holidayType, baseHolidayName, courtInSession);
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

        private static String formatHolidayName(String holidayType, String baseHolidayName, boolean courtInSession) {
            return "[Holiday/" + holidayType + "] " +
                    baseHolidayName +
                    " (Court: " + (courtInSession ? "Yes" : "No") + ")";
        }

        @Override
        public String toString() {
            return "HolidayInfo{" +
                    "holidayType='" + holidayType + '\'' +
                    ", holidayDate='" + holidayDate + '\'' +
                    ", holidayName='" + holidayName + '\'' +
                    ", courtInSession=" + courtInSession +
                    '}';
        }
    }

    public static HolidayInfo[] calculateHolidays(int year) {
        List<HolidayInfo> holidays = new ArrayList<>();

        final boolean courtInSession = false;

        // Federal holidays
        holidays.add(new HolidayInfo("Federal", observeFederalFixedHoliday(LocalDate.of(year, Month.JANUARY, 1)), "New Year's Day", courtInSession));
        holidays.add(new HolidayInfo("Federal", nthWeekdayOfMonth(year, Month.JANUARY, DayOfWeek.MONDAY, 3), "Birthday of Martin Luther King, Jr.", courtInSession));
        holidays.add(new HolidayInfo("Federal", nthWeekdayOfMonth(year, Month.FEBRUARY, DayOfWeek.MONDAY, 3), "Washington's Birthday", courtInSession));
        holidays.add(new HolidayInfo("Federal", lastWeekdayOfMonth(year, Month.MAY, DayOfWeek.MONDAY), "Memorial Day", courtInSession));
        holidays.add(new HolidayInfo("Federal", observeFederalFixedHoliday(LocalDate.of(year, Month.JUNE, 19)), "Juneteenth National Independence Day", courtInSession));
        holidays.add(new HolidayInfo("Federal", observeFederalFixedHoliday(LocalDate.of(year, Month.JULY, 4)), "Independence Day", courtInSession));
        holidays.add(new HolidayInfo("Federal", nthWeekdayOfMonth(year, Month.SEPTEMBER, DayOfWeek.MONDAY, 1), "Labor Day", courtInSession));
        holidays.add(new HolidayInfo("Federal", nthWeekdayOfMonth(year, Month.OCTOBER, DayOfWeek.MONDAY, 2), "Columbus Day", courtInSession));
        holidays.add(new HolidayInfo("Federal", observeFederalFixedHoliday(LocalDate.of(year, Month.NOVEMBER, 11)), "Veterans Day", courtInSession));
        holidays.add(new HolidayInfo("Federal", nthWeekdayOfMonth(year, Month.NOVEMBER, DayOfWeek.THURSDAY, 4), "Thanksgiving Day", courtInSession));
        holidays.add(new HolidayInfo("Federal", observeFederalFixedHoliday(LocalDate.of(year, Month.DECEMBER, 25)), "Christmas Day", courtInSession));

        if (isFederalInaugurationYear(year)) {
            holidays.add(new HolidayInfo(
                    "Federal",
                    observeInaugurationDay(LocalDate.of(year, Month.JANUARY, 20)),
                    "Inauguration Day",
                    courtInSession
            ));
        }

        // Texas holidays
        holidays.add(new HolidayInfo("Texas", LocalDate.of(year, Month.JANUARY, 1), "New Year's Day", courtInSession));
        holidays.add(new HolidayInfo("Texas", nthWeekdayOfMonth(year, Month.JANUARY, DayOfWeek.MONDAY, 3), "Martin Luther King, Jr., Day", courtInSession));
        holidays.add(new HolidayInfo("Texas", nthWeekdayOfMonth(year, Month.FEBRUARY, DayOfWeek.MONDAY, 3), "Presidents' Day", courtInSession));
        holidays.add(new HolidayInfo("Texas", lastWeekdayOfMonth(year, Month.MAY, DayOfWeek.MONDAY), "Memorial Day", courtInSession));
        holidays.add(new HolidayInfo("Texas", LocalDate.of(year, Month.JULY, 4), "Independence Day", courtInSession));
        holidays.add(new HolidayInfo("Texas", nthWeekdayOfMonth(year, Month.SEPTEMBER, DayOfWeek.MONDAY, 1), "Labor Day", courtInSession));
        holidays.add(new HolidayInfo("Texas", LocalDate.of(year, Month.NOVEMBER, 11), "Veterans Day", courtInSession));
        holidays.add(new HolidayInfo("Texas", nthWeekdayOfMonth(year, Month.NOVEMBER, DayOfWeek.THURSDAY, 4), "Thanksgiving Day", courtInSession));
        holidays.add(new HolidayInfo("Texas", LocalDate.of(year, Month.DECEMBER, 25), "Christmas Day", courtInSession));

        holidays.add(new HolidayInfo("Texas", LocalDate.of(year, Month.JANUARY, 19), "Confederate Heroes Day", courtInSession));
        holidays.add(new HolidayInfo("Texas", LocalDate.of(year, Month.MARCH, 2), "Texas Independence Day", courtInSession));
        holidays.add(new HolidayInfo("Texas", LocalDate.of(year, Month.APRIL, 21), "San Jacinto Day", courtInSession));
        holidays.add(new HolidayInfo("Texas", LocalDate.of(year, Month.JUNE, 19), "Emancipation Day in Texas", courtInSession));
        holidays.add(new HolidayInfo("Texas", LocalDate.of(year, Month.AUGUST, 27), "Lyndon Baines Johnson Day", courtInSession));
        holidays.add(new HolidayInfo("Texas", fridayAfterThanksgiving(year), "Friday after Thanksgiving Day", courtInSession));
        holidays.add(new HolidayInfo("Texas", LocalDate.of(year, Month.DECEMBER, 24), "December 24", courtInSession));
        holidays.add(new HolidayInfo("Texas", LocalDate.of(year, Month.DECEMBER, 26), "December 26", courtInSession));

        holidays.sort(Comparator
                .comparing(HolidayInfo::getHolidayDate)
                .thenComparing(HolidayInfo::getHolidayType)
                .thenComparing(HolidayInfo::getHolidayName));

        return holidays.toArray(new HolidayInfo[0]);
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