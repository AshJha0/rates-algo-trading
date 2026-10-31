package io.ratesalgo.analytics.date;

/**
 * Day count conventions (ISDA 2006 Definitions Section 4.16; ICMA Rule 251 for Act/Act ICMA).
 *
 * <p>Year fractions are exact double arithmetic on integer day counts, so two implementations of
 * the same rule agree to the last bit. Each rule matches QuantLib 1.44:
 *
 * <ul>
 *   <li>{@link #ACT_360}: days / 360.
 *   <li>{@link #ACT_365F}: days / 365.
 *   <li>{@link #ACT_ACT_ISDA}: days in each calendar year divided by that year's length.
 *   <li>{@link #ACT_ACT_ICMA}: days divided by the days in the reference (coupon) period, times
 *       the period length in years; QuantLib {@code ActualActual(ISMA)} without a schedule, which
 *       also handles long and short stubs by splitting at notional coupon dates.
 *   <li>{@link #THIRTY_360_US}: 30/360 US (bond basis with the end-of-February rules).
 *   <li>{@link #THIRTY_E_360}: 30E/360 (Eurobond basis).
 * </ul>
 */
public enum DayCount {
  ACT_360,
  ACT_365F,
  ACT_ACT_ISDA,
  ACT_ACT_ICMA,
  THIRTY_360_US,
  THIRTY_E_360;

  /** Day count between two dates under this convention. */
  public int dayCount(final int d1, final int d2) {
    return switch (this) {
      case THIRTY_360_US -> thirty360Us(d1, d2);
      case THIRTY_E_360 -> thirtyE360(d1, d2);
      default -> d2 - d1;
    };
  }

  /**
   * Year fraction between two dates with the period itself as reference period. Exact for every
   * convention except {@link #ACT_ACT_ICMA}, which needs the coupon period; see {@link
   * #yearFraction(int, int, int, int)}.
   */
  public double yearFraction(final int d1, final int d2) {
    return yearFraction(d1, d2, d1, d2);
  }

  /**
   * Year fraction with a reference period, used only by {@link #ACT_ACT_ICMA}.
   *
   * @param d1 start
   * @param d2 end
   * @param refStart reference period start (the coupon period, or a notional one for a stub)
   * @param refEnd reference period end
   */
  public double yearFraction(final int d1, final int d2, final int refStart, final int refEnd) {
    return switch (this) {
      case ACT_360 -> (d2 - d1) / 360.0;
      case ACT_365F -> (d2 - d1) / 365.0;
      case ACT_ACT_ISDA -> actActIsda(d1, d2);
      case ACT_ACT_ICMA -> actActIcma(d1, d2, refStart, refEnd);
      case THIRTY_360_US -> thirty360Us(d1, d2) / 360.0;
      case THIRTY_E_360 -> thirtyE360(d1, d2) / 360.0;
    };
  }

  private static double actActIsda(final int d1, final int d2) {
    if (d1 == d2) {
      return 0.0;
    }
    if (d1 > d2) {
      return -actActIsda(d2, d1);
    }
    final int y1 = Dates.year(d1);
    final int y2 = Dates.year(d2);
    final double dib1 = Dates.lengthOfYear(y1);
    final double dib2 = Dates.lengthOfYear(y2);
    double sum = y2 - y1 - 1;
    sum += (Dates.of(y1 + 1, 1, 1) - d1) / dib1;
    sum += (d2 - Dates.of(y2, 1, 1)) / dib2;
    return sum;
  }

  /** QuantLib {@code ActualActual::Old_ISMA_Impl::yearFraction}, line for line. */
  private static double actActIcma(final int d1, final int d2, final int d3, final int d4) {
    if (d1 == d2) {
      return 0.0;
    }
    if (d1 > d2) {
      return -actActIcma(d2, d1, d3, d4);
    }
    int refStart = d3;
    int refEnd = d4;
    if (!(refEnd > refStart && refEnd > d1)) {
      throw new IllegalArgumentException(
          "invalid reference period " + Dates.format(refStart) + " to " + Dates.format(refEnd)
              + " for " + Dates.format(d1) + " to " + Dates.format(d2));
    }
    int months = (int) Math.round(12.0 * (refEnd - refStart) / 365.0);
    if (months == 0) {
      refStart = d1;
      refEnd = Dates.addYears(d1, 1);
      months = 12;
    }
    final double period = months / 12.0;
    if (d2 <= refEnd) {
      if (d1 >= refStart) {
        return period * (d2 - d1) / (refEnd - refStart);
      }
      // long first coupon: split at the notional coupon date refStart
      final int previousRef = Dates.addMonths(refStart, -months);
      if (d2 > refStart) {
        return actActIcma(d1, refStart, previousRef, refStart)
            + actActIcma(refStart, d2, refStart, refEnd);
      }
      return actActIcma(d1, d2, previousRef, refStart);
    }
    if (refStart > d1) {
      throw new IllegalArgumentException(
          "invalid dates: " + Dates.format(d1) + " before reference start "
              + Dates.format(refStart) + " and end " + Dates.format(d2) + " after reference end");
    }
    double sum = actActIcma(d1, refEnd, refStart, refEnd);
    int i = 0;
    int newRefStart;
    int newRefEnd;
    for (; ; ) {
      newRefStart = Dates.addMonths(refEnd, months * i);
      newRefEnd = Dates.addMonths(refEnd, months * (i + 1));
      if (d2 < newRefEnd) {
        break;
      }
      sum += period;
      i++;
    }
    sum += actActIcma(newRefStart, d2, newRefStart, newRefEnd);
    return sum;
  }

  private static boolean isLastOfFebruary(final int d) {
    return Dates.month(d) == 2 && Dates.isEndOfMonth(d);
  }

  private static int thirty360Us(final int d1, final int d2) {
    int dd1 = Dates.dayOfMonth(d1);
    int dd2 = Dates.dayOfMonth(d2);
    if (isLastOfFebruary(d1)) {
      if (isLastOfFebruary(d2)) {
        dd2 = 30;
      }
      dd1 = 30;
    }
    if (dd2 == 31 && dd1 >= 30) {
      dd2 = 30;
    }
    if (dd1 == 31) {
      dd1 = 30;
    }
    return 360 * (Dates.year(d2) - Dates.year(d1))
        + 30 * (Dates.month(d2) - Dates.month(d1))
        + (dd2 - dd1);
  }

  private static int thirtyE360(final int d1, final int d2) {
    final int dd1 = Math.min(Dates.dayOfMonth(d1), 30);
    final int dd2 = Math.min(Dates.dayOfMonth(d2), 30);
    return 360 * (Dates.year(d2) - Dates.year(d1))
        + 30 * (Dates.month(d2) - Dates.month(d1))
        + (dd2 - dd1);
  }
}
