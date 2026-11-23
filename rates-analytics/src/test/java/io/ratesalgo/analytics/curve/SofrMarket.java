package io.ratesalgo.analytics.curve;

import io.ratesalgo.analytics.date.Calendars;
import io.ratesalgo.analytics.date.Dates;
import io.ratesalgo.analytics.swap.FixingHistory;

/**
 * Declared market levels for the SOFR curve tests: an overnight rate, a policy path that eases
 * through 2027, and a par swap curve that troughs near two years and rises to twenty. The same
 * numbers are declared in {@code research/ratesresearch/goldens/pricing_v2.py}; the golden test
 * reads them from the golden file rather than from here.
 */
final class SofrMarket {

  /** The reference valuation date of pricing v2: Monday 2 November 2026. */
  static final int TODAY = Dates.of(2026, 11, 2);

  private SofrMarket() {}

  /** Rate of an input of the given kind, by maturity in years from today. */
  static double rate(final SofrCurve.Kind kind, final double years, final int tenorMonths) {
    if (kind == SofrCurve.Kind.OVERNIGHT) {
      return 0.0390;
    }
    if (kind == SofrCurve.Kind.SWAP) {
      return swapRate(tenorMonths / 12.0);
    }
    // futures: easing from 3.90 towards 3.30 over the first year and a half
    return 0.0330 + 0.0060 * Math.exp(-years / 0.45);
  }

  /** Par swap rate by tenor in years. */
  static double swapRate(final double t) {
    return 0.0415 - 0.0085 * Math.exp(-t / 9.0) + 0.0045 * Math.exp(-t / 0.9)
        - 0.0012 * Math.exp(-(t - 30.0) * (t - 30.0) / 60.0);
  }

  /** Market quotes of every input of the curve, in the inputs' own units. */
  static double[] quotes(final SofrCurve c) {
    final double[] q = new double[c.inputCount()];
    for (int i = 0; i < q.length; i++) {
      final double years = (c.pillarDate(i) - c.valuationDate()) / 365.25;
      q[i] = c.quoteForRate(i, rate(c.kind(i), years, c.tenorMonths(i)));
    }
    return q;
  }

  /** A curve of the specification at the declared market on the given date. */
  static SofrCurve build(final int today, final SofrCurveSpec spec) {
    final SofrCurve c = new SofrCurve(today, spec, fixings(today, 0.0390), 0.0390);
    if (!c.update(quotes(c))) {
      throw new IllegalStateException("declared market did not solve");
    }
    return c;
  }

  /** The standard curve on the reference date. */
  static SofrCurve standard() {
    return build(TODAY, SofrCurveSpec.usd());
  }

  /** Flat fixings on the 120 calendar days before {@code today}. */
  static FixingHistory fixings(final int today, final double rate) {
    int n = 0;
    final int[] dates = new int[120];
    for (int d = today - 120; d < today; d++) {
      if (Calendars.US_SOFR.isBusinessDay(d)) {
        dates[n++] = d;
      }
    }
    final double[] rates = new double[n];
    java.util.Arrays.fill(rates, rate);
    return new FixingHistory(java.util.Arrays.copyOf(dates, n), rates);
  }
}
