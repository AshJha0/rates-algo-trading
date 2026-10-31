package io.ratesalgo.analytics.curve;

import io.ratesalgo.analytics.date.BusinessDayConvention;
import io.ratesalgo.analytics.date.Dates;
import io.ratesalgo.analytics.date.DayCount;
import io.ratesalgo.analytics.date.HolidayCalendar;
import io.ratesalgo.analytics.date.Schedule;
import io.ratesalgo.analytics.date.StubRule;
import java.util.Arrays;

/**
 * A par swap of fixed against a term rate (for example EURIBOR 6M), used to bootstrap a
 * projection curve while discounting on a separate OIS curve: the multi-curve setup in which the
 * OIS curve is built first and held fixed.
 *
 * <p>Floating coupon j pays the forward over its accrual period, F_j tau_j = P_proj(s_j) /
 * P_proj(e_j) - 1 (QuantLib's par-coupon approximation, where the index period equals the accrual
 * period). Par rate = sum_j (P_proj(s_j)/P_proj(e_j) - 1) P_disc(e_j) / sum_i tau_i P_disc(e_i).
 * No payment lag.
 */
public final class TermSwapInstrument implements CurveInstrument {

  private final String label;
  private final String tenor;
  private final int[] fixedPay;
  private final double[] fixedTau;
  private final int[] floatStart;
  private final int[] floatEnd;
  private double quote;

  /**
   * Creates the instance from its parts.
   *
   * @param label description
   * @param tenor ladder label
   * @param tradeDate trade date
   * @param spotLag business days to spot on the calendar
   * @param tenorMonths swap tenor
   * @param calendar schedule calendar
   * @param fixedPeriodMonths fixed leg period (12 for annual)
   * @param fixedDayCount fixed leg day count
   * @param floatPeriodMonths floating period (6 for a 6M index)
   * @param quote par rate, decimal
   */
  public TermSwapInstrument(
      final String label,
      final String tenor,
      final int tradeDate,
      final int spotLag,
      final int tenorMonths,
      final HolidayCalendar calendar,
      final int fixedPeriodMonths,
      final DayCount fixedDayCount,
      final int floatPeriodMonths,
      final double quote) {
    this.label = label;
    this.tenor = tenor;
    this.quote = quote;
    final int start = calendar.advanceBusinessDays(tradeDate, spotLag);
    final int end = Dates.addMonths(start, tenorMonths);
    final BusinessDayConvention mf = BusinessDayConvention.MODIFIED_FOLLOWING;
    final Schedule fixed =
        Schedule.generate(start, end, fixedPeriodMonths, calendar, mf, mf, StubRule.SHORT_FRONT,
            false);
    final Schedule flt =
        Schedule.generate(start, end, floatPeriodMonths, calendar, mf, mf, StubRule.SHORT_FRONT,
            false);
    fixedPay = new int[fixed.periodCount()];
    fixedTau = new double[fixed.periodCount()];
    for (int i = 0; i < fixedPay.length; i++) {
      fixedPay[i] = fixed.end(i);
      fixedTau[i] = fixedDayCount.yearFraction(fixed.start(i), fixed.end(i));
    }
    floatStart = new int[flt.periodCount()];
    floatEnd = new int[flt.periodCount()];
    for (int j = 0; j < floatStart.length; j++) {
      floatStart[j] = flt.start(j);
      floatEnd[j] = flt.end(j);
    }
  }

  @Override
  public String label() {
    return label;
  }

  @Override
  public String tenor() {
    return tenor;
  }

  @Override
  public int pillarDate() {
    return Math.max(fixedPay[fixedPay.length - 1], floatEnd[floatEnd.length - 1]);
  }

  @Override
  public int latestRelevantDate() {
    return pillarDate();
  }

  @Override
  public double quote() {
    return quote;
  }

  @Override
  public void setQuote(final double q) {
    this.quote = q;
  }

  @Override
  public double quotePerRate() {
    return 1.0;
  }

  private double annuity(final DiscountCurve disc) {
    double a = 0.0;
    for (int i = 0; i < fixedPay.length; i++) {
      a += fixedTau[i] * disc.df(fixedPay[i]);
    }
    return a;
  }

  @Override
  public double impliedQuote(final CurveContext ctx) {
    double flt = 0.0;
    for (int j = 0; j < floatStart.length; j++) {
      flt += (ctx.target().df(floatStart[j]) / ctx.target().df(floatEnd[j]) - 1.0)
          * ctx.discount().df(floatEnd[j]);
    }
    return flt / annuity(ctx.discount());
  }

  @Override
  public void impliedQuoteGradient(final CurveContext ctx, final double[] out) {
    if (ctx.singleCurve()) {
      throw new UnsupportedOperationException("term swaps bootstrap a projection curve");
    }
    Arrays.fill(out, 0, ctx.target().pillarCount(), 0.0);
    final double a = annuity(ctx.discount());
    for (int j = 0; j < floatStart.length; j++) {
      final double w =
          ctx.target().df(floatStart[j]) / ctx.target().df(floatEnd[j])
              * ctx.discount().df(floatEnd[j]) / a;
      ctx.target().accumulateLogDfGradient(floatStart[j], w, out);
      ctx.target().accumulateLogDfGradient(floatEnd[j], -w, out);
    }
  }
}
