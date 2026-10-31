package io.ratesalgo.backtest.model;

import io.ratesalgo.backtest.Instrument;
import io.ratesalgo.backtest.util.SplitMix64;

/**
 * Simulated competing dealers. Each of the {@code dealers - 1} competitors quotes a half-spread in
 * basis points drawn from a lognormal whose median depends on tenor, size and product kind; the
 * cover is the tightest of them.
 *
 * <pre>
 *   median = base * (tenor / 10)^tenorSlope * (size / 50mm)^sizeSlope * (bond ? bondMultiplier : 1)
 *   spread_j = median * exp(sigma * Z_j),  Z_j standard normal
 *   cover = min_j spread_j
 * </pre>
 *
 * <p>Every parameter is a declared assumption in preset {@value #PRESET}; the Python label
 * generator ({@code ratesresearch.hitrate.simulate_labels}) uses the same formula.
 */
public final class CompetitorModel {

  public static final String PRESET = "usd_rfq_competition";

  private final double baseBp;
  private final double tenorSlope;
  private final double sizeSlope;
  private final double bondMultiplier;
  private final double sigma;

  public CompetitorModel(
      final double baseBp,
      final double tenorSlope,
      final double sizeSlope,
      final double bondMultiplier,
      final double sigma) {
    if (!(baseBp > 0) || !(sigma >= 0) || !(bondMultiplier > 0)) {
      throw new IllegalArgumentException("invalid competitor parameters");
    }
    this.baseBp = baseBp;
    this.tenorSlope = tenorSlope;
    this.sizeSlope = sizeSlope;
    this.bondMultiplier = bondMultiplier;
    this.sigma = sigma;
  }

  public static CompetitorModel fromPreset(final Preset p) {
    return new CompetitorModel(
        p.value("median_half_spread_bp"),
        p.value("tenor_slope"),
        p.value("size_slope"),
        p.value("bond_multiplier"),
        p.value("lognormal_sigma"));
  }

  public double medianBp(final Instrument instrument, final double sizeMillions) {
    final double kind = instrument.kind() == Instrument.Kind.SWAP ? 1.0 : bondMultiplier;
    return baseBp
        * Math.pow(instrument.tenorYears() / 10.0, tenorSlope)
        * Math.pow(sizeMillions / 50.0, sizeSlope)
        * kind;
  }

  public double sigma() {
    return sigma;
  }

  /** The cover half-spread in bp for one RFQ, drawn from {@code rng}. */
  public double coverBp(
      final Instrument instrument, final double sizeMillions, final int dealers, final SplitMix64 rng) {
    if (dealers < 2) {
      throw new IllegalArgumentException("an RFQ in competition needs at least 2 dealers: " + dealers);
    }
    final double median = medianBp(instrument, sizeMillions);
    double best = Double.MAX_VALUE;
    for (int j = 1; j < dealers; j++) {
      best = Math.min(best, median * Math.exp(sigma * rng.nextGaussian()));
    }
    return best;
  }
}
