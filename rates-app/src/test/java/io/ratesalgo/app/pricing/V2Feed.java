package io.ratesalgo.app.pricing;

import io.ratesalgo.analytics.curve.SofrCurve;
import io.ratesalgo.api.InMemoryBookSource;
import io.ratesalgo.venues.Venues;

/**
 * A declared market for the pricing v2 tests and the feed that sends it: every curve input of the
 * binding under its {@link V2InputIds} id, the 12-year swap under its template id, and a top of
 * book for every Treasury.
 *
 * <p>Levels: overnight 3.70 percent, futures easing to 3.60, swaps at the simulators' starting
 * rates where the universe has a template (2, 5, 10 and 30 years at 3.60, 3.65, 3.70 and 3.75; the
 * 12-year at 3.80, which is 10 bp off any smooth curve through the others and is kept that way on
 * purpose) and at interpolated levels elsewhere.
 */
final class V2Feed {

  static final long SECOND = 1_000_000_000L;
  static final long ROUND = SECOND / 2;
  static final int SWAP_12Y = 2005;

  /** The simulators' session start: 2026-11-02 09:30 New York. */
  static final long T0 = Venues.T0;

  final PricingV2 pricing;
  final InMemoryBookSource book = new InMemoryBookSource(8);
  long now;

  V2Feed(final PricingV2 pricing, final long start) {
    this.pricing = pricing;
    this.now = start;
  }

  /** The declared rate of an input, decimal. */
  static double rate(final SofrCurve c, final int i) {
    if (c.kind(i) == SofrCurve.Kind.OVERNIGHT) {
      return 0.0370;
    }
    if (c.kind(i) == SofrCurve.Kind.SWAP) {
      return swapRate(c.tenorMonths(i));
    }
    final double years = (c.pillarDate(i) - c.valuationDate()) / 365.25;
    return 0.0360 + 0.0008 * Math.exp(-years / 0.3);
  }

  static double swapRate(final int months) {
    return switch (months) {
      case 12 -> 0.0358;
      case 24 -> 0.0360;
      case 36 -> 0.0362;
      case 60 -> 0.0365;
      case 84 -> 0.0368;
      case 120 -> 0.0370;
      case 144 -> 0.0380;
      case 240 -> 0.0374;
      case 360 -> 0.0375;
      default -> throw new IllegalArgumentException("no declared rate for " + months + " months");
    };
  }

  /** The mantissa the feed sends for input i at a rate. */
  long mantissa(final int i, final double rate) {
    final SofrCurve c = pricing.curve();
    return c.quotePerRate(i) == 1.0
        ? Math.round(rate * 1e11)
        : Math.round(c.quoteForRate(i, rate) * 1e9);
  }

  /** Advances the feed clock by half a second and sends a full round, every rate shifted. */
  void round(final double shift) {
    now += ROUND;
    send(shift, -1, 0.0);
  }

  /** As {@link #round}, with one input moved by a further amount. */
  void roundWithSkew(final double shift, final int input, final double skew) {
    now += ROUND;
    send(shift, input, skew);
  }

  private void send(final double shift, final int skewed, final double skew) {
    // the inputs are counted before the first is sent: a date change inside the round would
    // otherwise change the list under the loop
    pricing.advanceTo(now);
    final SofrCurve c = pricing.curve();
    final int n = pricing.inputCount();
    for (int i = 0; i < n; i++) {
      final double r = rate(c, i) + shift + (i == skewed ? skew : 0.0);
      pricing.onCurveInput(pricing.inputId(i), mantissa(i, r), now);
    }
    pricing.onCurveInput(SWAP_12Y, Math.round((swapRate(144) + shift) * 1e11), now);
  }

  /** Sets every Treasury's book one tick either side of a mid given as an offset to its start. */
  void books(final long offsetMantissa) {
    for (final UsdUniverse.BondSpec b : pricing.universe().bonds()) {
      book(b.instrumentId(), b.initialMid() + offsetMantissa);
    }
  }

  /** Sets one Treasury's book one tick either side of a mid. */
  void book(final int instrumentId, final long mid) {
    final long tick = pricing.universe().bondTick();
    book.set(instrumentId, mid - tick, 1_000_000_00L, mid + tick, 1_000_000_00L, now);
  }
}
