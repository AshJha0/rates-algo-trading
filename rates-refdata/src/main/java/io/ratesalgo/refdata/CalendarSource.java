package io.ratesalgo.refdata;

import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * Holiday calendars by name. This is the hook {@code rates-analytics} calendars plug into once
 * merged; until then {@link FixtureCalendarSource} reads {@code holidays.csv}.
 */
public interface CalendarSource {

  /** True if the named calendar has a holiday on the date (weekends are not listed as holidays). */
  boolean isHoliday(String calendar, LocalDate date);

  /** True if the calendar knows the name. */
  boolean knows(String calendar);

  /** Weekday and not a holiday. */
  default boolean isBusinessDay(final String calendar, final LocalDate date) {
    final DayOfWeek dow = date.getDayOfWeek();
    return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY && !isHoliday(calendar, date);
  }

  /** Moves forward {@code days} business days; zero returns the date rolled forward if needed. */
  default LocalDate addBusinessDays(final String calendar, final LocalDate date, final int days) {
    if (days < 0) {
      throw new IllegalArgumentException("days must be non-negative: " + days);
    }
    LocalDate d = date;
    while (!isBusinessDay(calendar, d)) {
      d = d.plusDays(1);
    }
    int left = days;
    while (left > 0) {
      d = d.plusDays(1);
      if (isBusinessDay(calendar, d)) {
        left--;
      }
    }
    return d;
  }
}
