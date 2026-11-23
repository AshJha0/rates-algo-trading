package io.ratesalgo.quoting;

import org.agrona.collections.Int2IntHashMap;

/**
 * What the desk assumes about a client tier before it has the client's own markouts (review
 * 2026-11-23, Q6): the expected adverse move after a trade, bp of yield at the traded tenor in the
 * client's favour, once the information has fully arrived; and the probability that the client
 * trades with us when our quote is the best one inside its reserve.
 *
 * <p>The adverse move builds up as {@code 1 - exp(-t / tau)}, the shape of the Round 3
 * simulator's information drift, so the prior at a markout horizon h is {@code adverse * (1 -
 * exp(-h / tau))}. {@link #calibrated} sets the Round 3 values: tiers A, B, C informed with
 * probability 0.25, 0.60 and 0.975, a move of 0.118 bp per informed trade, tau 10 s, and a 5 %
 * chance that the client does not trade at all (data/calibration/sim_v2_params.json; all
 * assumptions except the product of fraction and move, which is bounded by the DTCC markouts).
 * Reads are allocation-free.
 */
public final class TierPriors {

  /** Tier ids {@link #calibrated} uses. */
  public static final int TIER_A = 1;

  public static final int TIER_B = 2;
  public static final int TIER_C = 3;

  private static final int CAPACITY = 16;

  private final Int2IntHashMap slots = new Int2IntHashMap(CAPACITY * 2, 0.5f, -1);
  private final double[] adverse = new double[CAPACITY];
  private final double[] fill = new double[CAPACITY];
  private final double defaultAdverseBp;
  private final double defaultFill;
  private final double driftTauNanos;

  /** Priors with a default for any tier not set, and the information drift's time constant. */
  public TierPriors(final double defaultAdverseBp, final double defaultFillProbability, final long driftTauNanos) {
    check(defaultAdverseBp, defaultFillProbability);
    if (driftTauNanos <= 0) {
      throw new IllegalArgumentException("drift time constant " + driftTauNanos);
    }
    this.defaultAdverseBp = defaultAdverseBp;
    this.defaultFill = defaultFillProbability;
    this.driftTauNanos = driftTauNanos;
  }

  /** The Round 3 calibration: tiers A, B, C and the all-client mean as the default. */
  public static TierPriors calibrated() {
    final double move = 0.118;
    return new TierPriors(0.5 * move, 0.95, 10_000_000_000L)
        .tier(TIER_A, 0.25 * move, 0.95)
        .tier(TIER_B, 0.60 * move, 0.95)
        .tier(TIER_C, 0.975 * move, 0.95);
  }

  /** Sets one tier's full adverse move (bp) and fill probability. */
  public TierPriors tier(final int tierId, final double adverseBp, final double fillProbability) {
    check(adverseBp, fillProbability);
    int s = slots.get(tierId);
    if (s < 0) {
      s = slots.size();
      if (s == CAPACITY) {
        throw new IllegalStateException("more than " + CAPACITY + " tiers");
      }
      slots.put(tierId, s);
    }
    adverse[s] = adverseBp;
    fill[s] = fillProbability;
    return this;
  }

  /** Full adverse move of a tier, bp. */
  public double adverseBp(final int tierId) {
    final int s = slots.get(tierId);
    return s < 0 ? defaultAdverseBp : adverse[s];
  }

  /** The prior adverse move of a tier at a markout horizon, bp. */
  public double adverseBp(final int tierId, final long horizonNanos) {
    return adverseBp(tierId) * (1.0 - Math.exp(-(double) horizonNanos / driftTauNanos));
  }

  /** Probability that a client of this tier trades with our best quote. */
  public double fillProbability(final int tierId) {
    final int s = slots.get(tierId);
    return s < 0 ? defaultFill : fill[s];
  }

  private static void check(final double adverseBp, final double fillProbability) {
    if (!(adverseBp >= 0.0) || Double.isInfinite(adverseBp) || !(fillProbability > 0.0 && fillProbability <= 1.0)) {
      throw new IllegalArgumentException("adverse " + adverseBp + ", fill " + fillProbability);
    }
  }
}
