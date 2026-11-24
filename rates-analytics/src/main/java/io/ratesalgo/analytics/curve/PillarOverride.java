package io.ratesalgo.analytics.curve;

/**
 * A curve instrument that determines a pillar other than its own last date.
 *
 * <p>A one-month SOFR future prices the average overnight rate of a calendar month, but the curve
 * node it informs is the next policy date: the future of a month in which the central bank meets
 * fixes the level of the step after the meeting, and that step ends at the following meeting, not
 * at the month end. The wrapper keeps the instrument's pricing and moves only the pillar. The
 * builder then solves all pillars together, since the instrument may depend on dates on either
 * side of its pillar.
 */
public final class PillarOverride implements CurveInstrument {

  private final CurveInstrument delegate;
  private final int pillar;

  /**
   * Wraps an instrument.
   *
   * @param delegate the instrument that is priced
   * @param pillar the pillar date it determines
   */
  public PillarOverride(final CurveInstrument delegate, final int pillar) {
    this.delegate = delegate;
    this.pillar = pillar;
  }

  @Override
  public String label() {
    return delegate.label();
  }

  @Override
  public String tenor() {
    return delegate.tenor();
  }

  @Override
  public int pillarDate() {
    return pillar;
  }

  @Override
  public int latestRelevantDate() {
    return Math.max(pillar, delegate.latestRelevantDate());
  }

  @Override
  public double quote() {
    return delegate.quote();
  }

  @Override
  public void setQuote(final double quote) {
    delegate.setQuote(quote);
  }

  @Override
  public double quotePerRate() {
    return delegate.quotePerRate();
  }

  @Override
  public double impliedQuote(final CurveContext context) {
    return delegate.impliedQuote(context);
  }

  @Override
  public void impliedQuoteGradient(final CurveContext context, final double[] out) {
    delegate.impliedQuoteGradient(context, out);
  }
}
