package io.ratesalgo.venues.sim;

import io.ratesalgo.sim.MarketSpec;
import io.ratesalgo.sim.SimInstrument;

/**
 * The curve quotes a simulator v2 session publishes besides its tradable instruments: the
 * overnight rate, SOFR futures and par swaps at tenors the model has no instrument for. All of
 * them come from the session's factor model, so a curve built from them and the venues' RFQ
 * competitors are on one market.
 *
 * <p>The model's shortest node is two years, and the design applies that node to every shorter
 * maturity (docs/research/SIM_V2_DESIGN.md section 3). The front of the curve here follows the
 * same rule, and what it leaves open is declared, not fitted:
 *
 * <ul>
 *   <li>The front starts flat at the two-year swap's opening rate.
 *   <li>A par swap shorter than two years, and the rate a SOFR future implies, is that opening
 *       rate plus the change of the two-year swap's latent mid since the open (the two-year
 *       node's yield change plus the swap-spread factor, as for every swap).
 *   <li>The overnight rate does not move within a session: policy does not change intraday.
 *   <li>A par swap between two model tenors is interpolated linearly in maturity between its
 *       neighbours, as the design interpolates loadings.
 * </ul>
 *
 * <p>Values leave as published prices: swaps rounded to the swap tick, futures to a quarter of a
 * basis point of price (the SR1 and SR3 front tick), half away from zero as {@code
 * referencePrice} rounds.
 */
public final class SimV2CurveRound {

  /** Price increment of a published SOFR futures price: 0.0025. */
  public static final double FUTURES_TICK = 0.0025;

  private final SimV2Session session;
  private final int[] swapIndex;
  private final int[] swapMonths;
  private final double swapTick;
  private final double frontOpen;

  /** The round of a session. */
  public SimV2CurveRound(final SimV2Session session) {
    this.session = session;
    final MarketSpec spec = session.model().spec();
    int n = 0;
    for (int i = 0; i < spec.instruments(); i++) {
      if (spec.instrument(i).kind() == SimInstrument.Kind.SWAP) {
        n++;
      }
    }
    if (n == 0) {
      throw new IllegalArgumentException("the model has no swap to take the curve from");
    }
    swapIndex = new int[n];
    swapMonths = new int[n];
    int k = 0;
    for (int i = 0; i < spec.instruments(); i++) {
      final SimInstrument s = spec.instrument(i);
      if (s.kind() == SimInstrument.Kind.SWAP) {
        swapIndex[k] = i;
        swapMonths[k] = (int) Math.round(12.0 * s.nodeYears());
        if (k > 0 && swapMonths[k] <= swapMonths[k - 1]) {
          throw new IllegalArgumentException("model swaps must be listed by rising tenor");
        }
        k++;
      }
    }
    swapTick = spec.instrument(swapIndex[0]).tick();
    frontOpen = spec.instrument(swapIndex[0]).initialMid();
  }

  /** Tenor in months of the shortest model swap. */
  public int shortestSwapMonths() {
    return swapMonths[0];
  }

  private double latent(final int k) {
    return session.model().latentMid(swapIndex[k], session.sessionNanos());
  }

  /** The front rate now, percent, unrounded. */
  public double frontRatePercent() {
    return frontOpen + (latent(0) - session.model().spec().instrument(swapIndex[0]).initialMid());
  }

  /** Published par rate of a spot-starting swap of a tenor in months: percent, Decimal9 mantissa. */
  public long parSwap(final int tenorMonths) {
    final int last = swapMonths.length - 1;
    final double rate;
    if (tenorMonths <= swapMonths[0]) {
      rate = tenorMonths == swapMonths[0] ? latent(0) : frontRatePercent();
    } else if (tenorMonths >= swapMonths[last]) {
      rate = latent(last);
    } else {
      int hi = 1;
      while (swapMonths[hi] < tenorMonths) {
        hi++;
      }
      if (swapMonths[hi] == tenorMonths) {
        rate = latent(hi);
      } else {
        final double w = (double) (tenorMonths - swapMonths[hi - 1]) / (swapMonths[hi] - swapMonths[hi - 1]);
        rate = (1.0 - w) * latent(hi - 1) + w * latent(hi);
      }
    }
    return SimV2Session.mantissa(round(rate, swapTick));
  }

  /** The overnight rate of the session: percent, Decimal9 mantissa. Constant through the day. */
  public long overnight() {
    return SimV2Session.mantissa(frontOpen);
  }

  /** Published price of a SOFR future, one- or three-month: 100 less the front rate. */
  public long futurePrice() {
    return SimV2Session.mantissa(round(100.0 - frontRatePercent(), FUTURES_TICK));
  }

  private static double round(final double value, final double tick) {
    final double n = value / tick;
    return (n < 0 ? -Math.floor(-n + 0.5) : Math.floor(n + 0.5)) * tick;
  }
}
