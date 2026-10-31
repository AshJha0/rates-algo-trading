package io.ratesalgo.analytics.date;

/**
 * How a date that falls on a non-business day is moved (ISDA 2021 Definitions, Section 4.12).
 * The rules are the same as QuantLib's, which the golden tests compare against.
 */
public enum BusinessDayConvention {
  /** Leave the date where it is. */
  UNADJUSTED,
  /** Next business day. */
  FOLLOWING,
  /** Next business day unless that is in the next month, then the previous business day. */
  MODIFIED_FOLLOWING,
  /** Previous business day. */
  PRECEDING,
  /** Previous business day unless that is in the previous month, then the next business day. */
  MODIFIED_PRECEDING;

  /** Applies the convention on the calendar. Allocation-free. */
  public int adjust(final int date, final HolidayCalendar calendar) {
    return switch (this) {
      case UNADJUSTED -> date;
      case FOLLOWING -> following(date, calendar);
      case PRECEDING -> preceding(date, calendar);
      case MODIFIED_FOLLOWING -> {
        final int d = following(date, calendar);
        yield Dates.month(d) != Dates.month(date) ? preceding(date, calendar) : d;
      }
      case MODIFIED_PRECEDING -> {
        final int d = preceding(date, calendar);
        yield Dates.month(d) != Dates.month(date) ? following(date, calendar) : d;
      }
    };
  }

  private static int following(final int date, final HolidayCalendar calendar) {
    int d = date;
    while (!calendar.isBusinessDay(d)) {
      d++;
    }
    return d;
  }

  private static int preceding(final int date, final HolidayCalendar calendar) {
    int d = date;
    while (!calendar.isBusinessDay(d)) {
      d--;
    }
    return d;
  }
}
