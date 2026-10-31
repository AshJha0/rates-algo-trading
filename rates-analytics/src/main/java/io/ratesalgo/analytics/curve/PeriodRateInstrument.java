package io.ratesalgo.analytics.curve;

import io.ratesalgo.analytics.date.DayCount;
import java.util.Arrays;

/**
 * A rate over one period with simple compounding on its day count: R = (P(start)/P(end) - 1) /
 * tau(start, end). Covers three bootstrap inputs:
 *
 * <ul>
 *   <li>the overnight fixing, from today to the next fixing day, which anchors the curve to
 *       SOFR, EUR STR or SONIA (today's value is not published until tomorrow, so the quote is the
 *       estimate: the last published fixing plus any expected change);
 *   <li>a deposit;
 *   <li>a meeting-dated OIS, the compounded overnight rate between two consecutive central bank
 *       policy effective dates. With linear log discount factors and pillars at the meeting
 *       dates, the overnight forward is a step function that changes only at meetings, which is
 *       how the front of an OIS curve is quoted. Daily compounding over the period telescopes to
 *       P(start)/P(end), so the formula is exact.
 * </ul>
 */
public final class PeriodRateInstrument implements CurveInstrument {

  private final String label;
  private final String tenor;
  private final int start;
  private final int end;
  private final double tau;
  private double quote;

  /**
   * @param label description
   * @param tenor ladder label
   * @param start period start (may be today)
   * @param end period end, the pillar
   * @param dayCount accrual convention of the quote
   * @param quote decimal rate
   */
  public PeriodRateInstrument(
      final String label,
      final String tenor,
      final int start,
      final int end,
      final DayCount dayCount,
      final double quote) {
    if (end <= start) {
      throw new IllegalArgumentException("period end not after start for " + label);
    }
    this.label = label;
    this.tenor = tenor;
    this.start = start;
    this.end = end;
    this.tau = dayCount.yearFraction(start, end);
    this.quote = quote;
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
    return end;
  }

  @Override
  public int latestRelevantDate() {
    return end;
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

  public int start() {
    return start;
  }

  @Override
  public double impliedQuote(final CurveContext ctx) {
    return (ctx.target().df(start) / ctx.target().df(end) - 1.0) / tau;
  }

  @Override
  public void impliedQuoteGradient(final CurveContext ctx, final double[] out) {
    Arrays.fill(out, 0, ctx.target().pillarCount(), 0.0);
    final double ratio = ctx.target().df(start) / ctx.target().df(end);
    ctx.target().accumulateLogDfGradient(start, ratio / tau, out);
    ctx.target().accumulateLogDfGradient(end, -ratio / tau, out);
  }
}
