package io.ratesalgo.analytics.curve;

import io.ratesalgo.analytics.date.BusinessDayConvention;
import io.ratesalgo.analytics.date.Dates;
import io.ratesalgo.analytics.date.DayCount;
import io.ratesalgo.analytics.date.HolidayCalendar;
import io.ratesalgo.analytics.date.ImmDates;
import io.ratesalgo.analytics.swap.FixingHistory;
import java.util.Arrays;

/**
 * An overnight index future: price = 100 (1 - (R + CA)), where R is the overnight rate over the
 * reference period and CA the futures convexity adjustment.
 *
 * <ul>
 *   <li>Three-month (CME SR3, ICE SONIA): reference period from the third Wednesday of the
 *       contract month to the third Wednesday three months later; R is the daily compounded rate
 *       (CME SOFR futures contract specifications, Rulebook Chapter 460).
 *   <li>One-month (CME SR1): the calendar month; R is the arithmetic average of daily rates, each
 *       weighted by its calendar days (Rulebook Chapter 461).
 * </ul>
 *
 * <p>Both are computed exactly as QuantLib 1.44 {@code OvernightIndexFuture} computes them,
 * including fixings already published when the reference period has started.
 */
public final class OisFutureInstrument implements CurveInstrument {

  private final String label;
  private final boolean threeMonth;
  private final int valueDate;
  private final int maturityDate;
  private final HolidayCalendar fixingCalendar;
  private final DayCount dayCount;
  private final double convexity;
  private double price;

  private OisFutureInstrument(
      final String label,
      final boolean threeMonth,
      final int valueDate,
      final int maturityDate,
      final HolidayCalendar fixingCalendar,
      final DayCount dayCount,
      final double convexity,
      final double price) {
    this.label = label;
    this.threeMonth = threeMonth;
    this.valueDate = valueDate;
    this.maturityDate = maturityDate;
    this.fixingCalendar = fixingCalendar;
    this.dayCount = dayCount;
    this.convexity = convexity;
    this.price = price;
  }

  /** A three-month future on the IMM month (3, 6, 9 or 12) of the given year. */
  public static OisFutureInstrument threeMonth(
      final String label,
      final int year,
      final int month,
      final HolidayCalendar fixingCalendar,
      final DayCount dayCount,
      final double convexity,
      final double price) {
    final int start = ImmDates.thirdWednesday(year, month);
    final int endMonth = Dates.addMonths(Dates.of(year, month, 1), 3);
    final int end = ImmDates.thirdWednesday(Dates.year(endMonth), Dates.month(endMonth));
    return new OisFutureInstrument(
        label, true, start, end, fixingCalendar, dayCount, convexity, price);
  }

  /** A one-month future on the calendar month. */
  public static OisFutureInstrument oneMonth(
      final String label,
      final int year,
      final int month,
      final HolidayCalendar fixingCalendar,
      final DayCount dayCount,
      final double convexity,
      final double price) {
    final int start = Dates.of(year, month, 1);
    final int end = Dates.endOfMonth(start) + 1;
    return new OisFutureInstrument(
        label, false, start, end, fixingCalendar, dayCount, convexity, price);
  }

  /** Start of the reference period. */
  public int valueDate() {
    return valueDate;
  }

  /** End of the reference period (exclusive), the pillar. */
  public int maturityDate() {
    return maturityDate;
  }

  /** The convexity adjustment used, decimal. */
  public double convexity() {
    return convexity;
  }

  @Override
  public String label() {
    return label;
  }

  @Override
  public String tenor() {
    return label;
  }

  @Override
  public int pillarDate() {
    return maturityDate;
  }

  @Override
  public int latestRelevantDate() {
    return maturityDate;
  }

  @Override
  public double quote() {
    return price;
  }

  @Override
  public void setQuote(final double q) {
    this.price = q;
  }

  @Override
  public double quotePerRate() {
    return -100.0;
  }

  /** The overnight rate over the reference period implied by the curve. */
  public double rate(final DiscountCurve curve, final FixingHistory fixings, final int today) {
    return threeMonth ? compounded(curve, fixings, today, null) : averaged(curve, fixings, today,
        null);
  }

  @Override
  public double impliedQuote(final CurveContext ctx) {
    return 100.0 * (1.0 - (convexity + rate(ctx.target(), ctx.fixings(), ctx.today())));
  }

  @Override
  public void impliedQuoteGradient(final CurveContext ctx, final double[] out) {
    Arrays.fill(out, 0, ctx.target().pillarCount(), 0.0);
    if (threeMonth) {
      compounded(ctx.target(), ctx.fixings(), ctx.today(), out);
    } else {
      averaged(ctx.target(), ctx.fixings(), ctx.today(), out);
    }
    for (int i = 0; i < ctx.target().pillarCount(); i++) {
      out[i] *= -100.0;
    }
  }

  private double compounded(
      final DiscountCurve curve, final FixingHistory fixings, final int todayIn, final double[] g) {
    int today = todayIn;
    double prod = 1.0;
    int forwardStart = valueDate;
    if (today > valueDate) {
      today = fixingCalendar.adjust(today, BusinessDayConvention.FOLLOWING);
      forwardStart = today;
      int d1 = valueDate;
      int fixingDate = fixingCalendar.adjust(d1, BusinessDayConvention.PRECEDING);
      while (d1 < today) {
        final double r = fixings.rate(fixingDate);
        final int d2 = fixingCalendar.advanceBusinessDays(d1, 1);
        prod *= 1.0 + r * dayCount.yearFraction(d1, d2);
        d1 = d2;
        fixingDate = d2;
      }
      if (today < maturityDate && fixings.has(today)) {
        final int tomorrow = fixingCalendar.advanceBusinessDays(today, 1);
        final int accrualEnd = Math.min(tomorrow, maturityDate);
        prod *= 1.0 + fixings.rate(today) * dayCount.yearFraction(today, accrualEnd);
        forwardStart = accrualEnd;
      }
    }
    final double ratio = curve.df(forwardStart) / curve.df(maturityDate);
    prod *= ratio;
    final double tau = dayCount.yearFraction(valueDate, maturityDate);
    if (g != null) {
      curve.accumulateLogDfGradient(forwardStart, prod / tau, g);
      curve.accumulateLogDfGradient(maturityDate, -prod / tau, g);
    }
    return (prod - 1.0) / tau;
  }

  private double averaged(
      final DiscountCurve curve, final FixingHistory fixings, final int today, final double[] g) {
    double avg = 0.0;
    int d1 = valueDate;
    int fixingDate = fixingCalendar.adjust(d1, BusinessDayConvention.PRECEDING);
    final double tau = dayCount.yearFraction(valueDate, maturityDate);
    while (d1 < maturityDate) {
      final int d2 = fixingCalendar.advanceBusinessDays(d1, 1);
      final double weight = dayCount.yearFraction(d1, Math.min(d2, maturityDate));
      if (fixingDate < today || (fixingDate == today && fixings.has(fixingDate))) {
        avg += fixings.rate(fixingDate) * weight;
      } else {
        final double tf = dayCount.yearFraction(fixingDate, d2);
        final double ratio = curve.df(fixingDate) / curve.df(d2);
        avg += (ratio - 1.0) / tf * weight;
        if (g != null) {
          final double w = ratio / tf * weight / tau;
          curve.accumulateLogDfGradient(fixingDate, w, g);
          curve.accumulateLogDfGradient(d2, -w, g);
        }
      }
      d1 = d2;
      fixingDate = d2;
    }
    return avg / tau;
  }
}
