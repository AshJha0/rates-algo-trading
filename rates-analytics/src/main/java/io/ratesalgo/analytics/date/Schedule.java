package io.ratesalgo.analytics.date;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * A schedule of period boundary dates, generated the way QuantLib 1.44 {@code Schedule} does for
 * the backward and forward rules, so that the golden comparisons see identical dates.
 *
 * <p>Generation: unadjusted dates are rolled by {@code periodMonths} from the termination date
 * (front stubs) or the effective date (back stubs) on a calendar with no holidays, with the
 * end-of-month rule if requested; dates that would coincide after adjustment are skipped; the
 * first date is adjusted by the convention, the last by the termination convention, the
 * intermediate ones by the convention (moving to the last business day of the month under the
 * end-of-month rule). Long stubs merge the short stub into its neighbour.
 *
 * <p>Built once per instrument; pricing reads the arrays and does not allocate.
 */
public final class Schedule {

  private final int[] dates;
  private final boolean[] regular;
  private final int periodMonths;
  private final HolidayCalendar calendar;
  private final BusinessDayConvention convention;
  private final boolean endOfMonth;

  private Schedule(
      final int[] dates,
      final boolean[] regular,
      final int periodMonths,
      final HolidayCalendar calendar,
      final BusinessDayConvention convention,
      final boolean endOfMonth) {
    this.dates = dates;
    this.regular = regular;
    this.periodMonths = periodMonths;
    this.calendar = calendar;
    this.convention = convention;
    this.endOfMonth = endOfMonth;
  }

  /**
   * Generates a schedule.
   *
   * @param effective start date (unadjusted)
   * @param termination end date (unadjusted)
   * @param periodMonths period length in months; 12 for annual, 6 for semi-annual
   * @param calendar calendar for adjustment
   * @param convention convention for every date but the last
   * @param terminationConvention convention for the last date
   * @param stub stub placement
   * @param endOfMonth apply the end-of-month rule
   */
  public static Schedule generate(
      final int effective,
      final int termination,
      final int periodMonths,
      final HolidayCalendar calendar,
      final BusinessDayConvention convention,
      final BusinessDayConvention terminationConvention,
      final StubRule stub,
      final boolean endOfMonth) {
    if (termination <= effective) {
      throw new IllegalArgumentException(
          "termination " + Dates.format(termination) + " not after effective "
              + Dates.format(effective));
    }
    if (periodMonths <= 0) {
      throw new IllegalArgumentException("periodMonths must be positive, got " + periodMonths);
    }
    final List<Integer> d = new ArrayList<>();
    final List<Boolean> reg = new ArrayList<>();
    final BusinessDayConvention c = convention;
    final boolean backward = stub == StubRule.SHORT_FRONT || stub == StubRule.LONG_FRONT;
    final int seed;
    if (backward) {
      d.add(termination);
      seed = termination;
      int periods = 1;
      for (; ; ) {
        final int temp = Calendars.NONE.advanceMonths(seed, -periods * periodMonths, c, endOfMonth);
        if (temp < effective) {
          break;
        }
        if (calendar.adjust(d.get(d.size() - 1), c) != calendar.adjust(temp, c)) {
          d.add(temp);
          reg.add(true);
        }
        periods++;
      }
      if (calendar.adjust(d.get(d.size() - 1), c) != calendar.adjust(effective, c)) {
        d.add(effective);
        reg.add(
            Calendars.NONE.advanceMonths(d.get(d.size() - 2), -periodMonths, c, endOfMonth)
                == effective);
      }
      Collections.reverse(d);
      Collections.reverse(reg);
    } else {
      d.add(effective);
      seed = effective;
      int periods = 1;
      for (; ; ) {
        final int temp = Calendars.NONE.advanceMonths(seed, periods * periodMonths, c, endOfMonth);
        if (temp > termination) {
          break;
        }
        if (calendar.adjust(d.get(d.size() - 1), c) != calendar.adjust(temp, c)) {
          d.add(temp);
          reg.add(true);
        }
        periods++;
      }
      if (calendar.adjust(d.get(d.size() - 1), terminationConvention)
          != calendar.adjust(termination, terminationConvention)) {
        d.add(termination);
        reg.add(false);
      }
    }
    if (c != BusinessDayConvention.UNADJUSTED) {
      d.set(0, calendar.adjust(d.get(0), c));
    }
    if (terminationConvention != BusinessDayConvention.UNADJUSTED) {
      d.set(d.size() - 1, calendar.adjust(d.get(d.size() - 1), terminationConvention));
    }
    final boolean eomSeed = endOfMonth && calendar.isEndOfMonth(seed);
    for (int i = 1; i < d.size() - 1; i++) {
      d.set(i, calendar.adjust(eomSeed ? Dates.endOfMonth(d.get(i)) : d.get(i), c));
    }
    if (d.size() >= 2 && d.get(d.size() - 2) >= d.get(d.size() - 1)) {
      if (reg.size() >= 2) {
        reg.set(reg.size() - 2, d.get(d.size() - 2).equals(d.get(d.size() - 1)));
      }
      d.set(d.size() - 2, d.get(d.size() - 1));
      d.remove(d.size() - 1);
      reg.remove(reg.size() - 1);
    }
    if (d.size() >= 2 && d.get(1) <= d.get(0)) {
      reg.set(1, d.get(1).equals(d.get(0)));
      d.set(1, d.get(0));
      d.remove(0);
      reg.remove(0);
    }
    if (d.size() < 2) {
      throw new IllegalStateException("degenerate schedule from " + Dates.format(effective));
    }
    if (stub == StubRule.LONG_FRONT && d.size() > 2 && !reg.get(0)) {
      d.remove(1);
      reg.remove(0);
      reg.set(0, false);
    } else if (stub == StubRule.LONG_BACK && d.size() > 2 && !reg.get(reg.size() - 1)) {
      d.remove(d.size() - 2);
      reg.remove(reg.size() - 1);
      reg.set(reg.size() - 1, false);
    }
    final int[] out = new int[d.size()];
    final boolean[] regOut = new boolean[reg.size()];
    for (int i = 0; i < out.length; i++) {
      out[i] = d.get(i);
    }
    for (int i = 0; i < regOut.length; i++) {
      regOut[i] = reg.get(i);
    }
    return new Schedule(out, regOut, periodMonths, calendar, convention, endOfMonth);
  }

  /** Number of dates; one more than the number of periods. */
  public int size() {
    return dates.length;
  }

  /** Number of periods. */
  public int periodCount() {
    return dates.length - 1;
  }

  /** The i-th date, 0-based. */
  public int date(final int i) {
    return dates[i];
  }

  /** Start of period i (0-based). */
  public int start(final int period) {
    return dates[period];
  }

  /** End of period i (0-based). */
  public int end(final int period) {
    return dates[period + 1];
  }

  /** True if period i (0-based) is a full regular period. */
  public boolean isRegular(final int period) {
    return regular[period];
  }

  /** Period length in months. */
  public int periodMonths() {
    return periodMonths;
  }

  /** Copy of the dates. */
  public int[] dates() {
    return Arrays.copyOf(dates, dates.length);
  }

  /**
   * Reference period start of a period for Act/Act ICMA: the period start, except for an
   * irregular first period, where it is the notional coupon date one period before the end.
   * Matches QuantLib {@code FixedRateLeg}.
   */
  public int referenceStart(final int period) {
    if (period == 0 && !regular[0]) {
      return calendar.advanceMonths(dates[1], -periodMonths, convention, endOfMonth);
    }
    return dates[period];
  }

  /**
   * Reference period end of a period: the period end, except for an irregular last period (when
   * there is more than one period), where it is the notional coupon date one period after the
   * start.
   */
  public int referenceEnd(final int period) {
    final int last = dates.length - 2;
    if (period == last && last > 0 && !regular[last]) {
      return calendar.advanceMonths(dates[last], periodMonths, convention, endOfMonth);
    }
    return dates[period + 1];
  }
}
