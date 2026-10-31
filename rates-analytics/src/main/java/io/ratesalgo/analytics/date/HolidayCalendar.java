package io.ratesalgo.analytics.date;

/**
 * A business-day calendar over epoch days.
 *
 * <p>Settlement and payment calendars are kept apart from fixing calendars: a USD SOFR swap pays
 * on the joint New York Fed and SIFMA calendar but compounds over SOFR publication days, which is
 * a different set (Good Friday is the usual difference). {@link Calendars} names each.
 *
 * <p>Implementations must be allocation-free on {@link #isBusinessDay}; the default methods here
 * only loop over it.
 */
public interface HolidayCalendar {

  /** Short identifier, for example {@code US-SIFMA}. */
  String name();

  /** True if the day is a business day. */
  boolean isBusinessDay(int epochDay);

  /** True if the day is not a business day (weekend or holiday). */
  default boolean isHoliday(final int epochDay) {
    return !isBusinessDay(epochDay);
  }

  /** Moves the date by the convention. */
  default int adjust(final int date, final BusinessDayConvention convention) {
    return convention.adjust(date, this);
  }

  /**
   * Moves by {@code n} business days, skipping holidays; zero returns the date unchanged. This is
   * QuantLib's {@code advance(d, n, Days)} and ignores any convention.
   */
  default int advanceBusinessDays(final int date, final int n) {
    int d = date;
    int k = n;
    while (k > 0) {
      d++;
      while (isHoliday(d)) {
        d++;
      }
      k--;
    }
    while (k < 0) {
      d--;
      while (isHoliday(d)) {
        d--;
      }
      k++;
    }
    return d;
  }

  /**
   * Moves by {@code n} business days and, for {@code n == 0}, adjusts by the convention, as
   * QuantLib's {@code Calendar::advance(d, n, Days, c)} does.
   */
  default int advanceBusinessDays(
      final int date, final int n, final BusinessDayConvention convention) {
    return n == 0 ? adjust(date, convention) : advanceBusinessDays(date, n);
  }

  /** Number of business days in the half-open interval [from, to). Negative if to is before from. */
  default int businessDaysBetween(final int from, final int to) {
    if (to < from) {
      return -businessDaysBetween(to, from);
    }
    int count = 0;
    for (int d = from; d < to; d++) {
      if (isBusinessDay(d)) {
        count++;
      }
    }
    return count;
  }

  /** Last business day of the month containing the date. */
  default int endOfMonth(final int date) {
    return BusinessDayConvention.PRECEDING.adjust(Dates.endOfMonth(date), this);
  }

  /** True if the date is the last business day of its month. */
  default boolean isEndOfMonth(final int date) {
    return Dates.month(date) != Dates.month(advanceBusinessDays(date, 1));
  }

  /**
   * Adds whole months and adjusts, with the end-of-month rule: if {@code endOfMonth} is set and the
   * start is the last business day of its month (the last calendar day when the convention is
   * unadjusted), the result is the last business day (calendar day) of the target month. This is
   * QuantLib's {@code Calendar::advance(d, n, Months, c, eom)}.
   */
  default int advanceMonths(
      final int date,
      final int months,
      final BusinessDayConvention convention,
      final boolean endOfMonth) {
    if (months == 0) {
      return adjust(date, convention);
    }
    final int d1 = Dates.addMonths(date, months);
    if (endOfMonth) {
      if (convention == BusinessDayConvention.UNADJUSTED) {
        if (Dates.isEndOfMonth(date)) {
          return Dates.endOfMonth(d1);
        }
      } else if (isEndOfMonth(date)) {
        return endOfMonth(d1);
      }
    }
    return adjust(d1, convention);
  }
}
