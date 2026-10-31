package io.ratesalgo.analytics.curve;

import io.ratesalgo.analytics.swap.OisSwap;
import io.ratesalgo.analytics.swap.OisSwapPricer;
import io.ratesalgo.analytics.swap.SwapConvention;

/**
 * A par OIS swap quote: the fixed rate at which a spot-starting swap of the tenor has zero value.
 * The pillar is the last payment date on either leg (QuantLib's {@code LastRelevantDate} pillar
 * for {@code OISRateHelper}). Single-curve: the OIS curve both projects and discounts.
 */
public final class OisSwapInstrument implements CurveInstrument {

  private final String label;
  private final String tenor;
  private final OisSwap swap;
  private final OisSwapPricer pricer = new OisSwapPricer();
  private double[] work = new double[0];
  private double quote;

  /**
   * Creates the instance from its parts.
   *
   * @param convention swap conventions
   * @param tradeDate trade (valuation) date
   * @param tenorMonths swap tenor in months
   * @param tenor ladder label, for example {@code 10Y}
   * @param quote par rate, decimal
   */
  public OisSwapInstrument(
      final SwapConvention convention,
      final int tradeDate,
      final int tenorMonths,
      final String tenor,
      final double quote) {
    this.swap = OisSwap.spot(convention, tradeDate, tenorMonths, 1.0, 0.0, true);
    this.tenor = tenor;
    this.label = convention.name() + " " + tenor;
    this.quote = quote;
  }

  /** The underlying swap (notional 1, fixed rate 0). */
  public OisSwap swap() {
    return swap;
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
    return swap.lastPaymentDate();
  }

  @Override
  public int latestRelevantDate() {
    return swap.lastPaymentDate();
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

  @Override
  public double impliedQuote(final CurveContext ctx) {
    return pricer.parRate(swap, ctx.target(), ctx.discount(), ctx.fixings(), ctx.today());
  }

  @Override
  public void impliedQuoteGradient(final CurveContext ctx, final double[] out) {
    if (!ctx.singleCurve()) {
      throw new UnsupportedOperationException(
          "OIS swap instruments bootstrap the discounting curve itself");
    }
    if (work.length < ctx.target().pillarCount()) {
      work = new double[ctx.target().pillarCount()];
    }
    pricer.parRateGradient(swap, ctx.target(), ctx.fixings(), ctx.today(), out, work);
  }
}
