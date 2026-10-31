package io.ratesalgo.analytics.curve;

/**
 * A market instrument that determines one pillar of a curve. The bootstrap finds pillar values
 * such that every instrument's {@link #impliedQuote} equals its {@link #quote}.
 */
public interface CurveInstrument {

  /** Short description, for example {@code SOFR OIS 10Y}. */
  String label();

  /** Tenor label for risk ladders, for example {@code 10Y}. */
  String tenor();

  /** The pillar date this instrument determines (its latest relevant date by default). */
  int pillarDate();

  /** The latest date whose discount factor the implied quote depends on. */
  int latestRelevantDate();

  /** The market quote: a decimal rate, or a price for futures. */
  double quote();

  /** Replaces the market quote. */
  void setQuote(double quote);

  /**
   * Change in quote units for a +1 change in rate units: 1 for rate quotes, -100 for futures
   * prices (a rate rise of one unit lowers the price by 100).
   */
  double quotePerRate();

  /** The quote implied by the curves in the context. */
  double impliedQuote(CurveContext context);

  /**
   * Writes d impliedQuote / d ln P(target pillar i) into {@code out} (length at least the
   * target's pillar count), overwriting it.
   */
  void impliedQuoteGradient(CurveContext context, double[] out);
}
