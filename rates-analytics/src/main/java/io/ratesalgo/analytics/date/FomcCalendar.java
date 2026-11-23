package io.ratesalgo.analytics.date;

/**
 * Scheduled meetings of the Federal Open Market Committee, for the meeting-dated front end of the
 * SOFR curve.
 *
 * <p>A change of the target range announced on the second day of a meeting applies from the next
 * business day, so the overnight rate steps on that day: the effective date. Between two effective
 * dates the curve holds the overnight forward flat.
 *
 * <p>Source: Board of Governors of the Federal Reserve System, "Meeting calendars, statements, and
 * minutes", federalreserve.gov/monetarypolicy/fomccalendars.htm (page last updated November 18,
 * 2026; read 2026-11-23). 2025 and 2026 are the held schedules; the 2025 dates and the 2026 dates
 * to June also match data/fixtures/edge/calendar.csv, built from the same page. 2027 is tentative:
 * the page says each date is tentative until confirmed at the meeting before it. The August 22,
 * 2025 notation vote is not a meeting and is left out. The October 26-27, 2027 entry is marked
 * "Monetary Policy Strategy Meeting", three weeks before the November 16-17 meeting, and is left
 * out as not a scheduled policy decision; that is a reading of the page, not something it states.
 * The next listed meeting, January 25-26, 2028, is outside the list. A wrong date moves one step of
 * the front end by the error in days; it does not stop the curve from repricing its inputs.
 * Unscheduled meetings are not modelled. After the last date listed the front end has no meeting
 * steps and the futures strip starts at the overnight pillar.
 */
public final class FomcCalendar {

  /** Second (decision) day of each scheduled meeting, in date order. */
  private static final int[] DECISION_DAYS = {
    Dates.of(2025, 1, 29), Dates.of(2025, 3, 19), Dates.of(2025, 5, 7), Dates.of(2025, 6, 18),
    Dates.of(2025, 7, 30), Dates.of(2025, 9, 17), Dates.of(2025, 10, 29), Dates.of(2025, 12, 10),
    Dates.of(2026, 1, 28), Dates.of(2026, 3, 18), Dates.of(2026, 4, 29), Dates.of(2026, 6, 17),
    Dates.of(2026, 7, 29), Dates.of(2026, 9, 16), Dates.of(2026, 10, 28), Dates.of(2026, 12, 9),
    Dates.of(2027, 1, 27), Dates.of(2027, 3, 17), Dates.of(2027, 4, 28), Dates.of(2027, 6, 9),
    Dates.of(2027, 7, 28), Dates.of(2027, 9, 15), Dates.of(2027, 11, 17),
  };

  private FomcCalendar() {}

  /** Number of meetings listed. */
  public static int size() {
    return DECISION_DAYS.length;
  }

  /** Decision day of meeting i, in date order. */
  public static int decisionDay(final int i) {
    return DECISION_DAYS[i];
  }

  /**
   * Writes the effective dates strictly after {@code date} into {@code out}, earliest first, and
   * returns how many were written (at most {@code out.length}; fewer when the list runs out).
   *
   * @param date reference date
   * @param calendar business days of the overnight index
   * @param out receives the effective dates
   */
  public static int effectiveDatesAfter(
      final int date, final HolidayCalendar calendar, final int[] out) {
    int n = 0;
    for (int i = 0; i < DECISION_DAYS.length && n < out.length; i++) {
      final int effective = calendar.advanceBusinessDays(DECISION_DAYS[i], 1);
      if (effective > date) {
        out[n++] = effective;
      }
    }
    return n;
  }
}
