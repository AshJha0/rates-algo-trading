package io.ratesalgo.analytics.date;

/**
 * IMM dates: the third Wednesday of March, June, September and December. They are the reference
 * period boundaries of 3-month SOFR, SONIA and EUR STR futures (CME and ICE contract
 * specifications) and the usual start dates of IMM swaps.
 */
public final class ImmDates {

  private ImmDates() {}

  /** The third Wednesday of the month. */
  public static int thirdWednesday(final int year, final int month) {
    return Dates.nthWeekday(3, Dates.WEDNESDAY, year, month);
  }

  /** True if the date is an IMM date (quarterly months only). */
  public static boolean isImm(final int date) {
    final int m = Dates.month(date);
    return m % 3 == 0 && date == thirdWednesday(Dates.year(date), m);
  }

  /** The first IMM date strictly after the given date. */
  public static int next(final int date) {
    int y = Dates.year(date);
    int m = Dates.month(date);
    m = ((m + 2) / 3) * 3;
    int candidate = thirdWednesday(y, m);
    while (candidate <= date) {
      m += 3;
      if (m > 12) {
        m -= 12;
        y++;
      }
      candidate = thirdWednesday(y, m);
    }
    return candidate;
  }

  /** The first IMM date on or after the given date. */
  public static int nextOrSame(final int date) {
    return isImm(date) ? date : next(date);
  }
}
