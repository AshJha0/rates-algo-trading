package io.ratesalgo.strategy;

/**
 * Feature 3, quote sizing: how much of a request to quote and what size costs, from the risk
 * envelope and the size distribution (API_SPEC.md Section 19.4).
 *
 * <p>A D2C RFQ is all-or-none in the back-tester (the runner settles the requested quantity), so
 * a quantity below the request means decline. The premium is a cost in bp of the trade's DV01
 * that the spread optimiser adds to adverse selection and hedge cost; the hit-rate model then
 * decides how much of it the quote passes on. The default, {@link #UNLIMITED}, is today's
 * engine: quote every size at no premium. Implementations are allocation-free.
 */
public interface QuoteSizer {

  /** Today's rule: quote the full request, no size premium. */
  QuoteSizer UNLIMITED =
      new QuoteSizer() {
        @Override
        public long maxQuantity(final RiskLadder ladder, final int index, final double tradeDv01, final long requested) {
          return requested;
        }

        @Override
        public double premiumBp(final RiskLadder ladder, final int index, final double tradeDv01, final double sizeMillions) {
          return 0.0;
        }
      };

  /** The largest quantity we quote, minor units, given the trade would change net DV01 by {@code tradeDv01}. */
  long maxQuantity(RiskLadder ladder, int index, double tradeDv01, long requested);

  /** Size and risk premium in bp for this trade; never negative. */
  double premiumBp(RiskLadder ladder, int index, double tradeDv01, double sizeMillions);
}
