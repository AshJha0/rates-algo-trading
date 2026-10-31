package io.ratesalgo.backtest.model;

import io.ratesalgo.backtest.Instrument;
import io.ratesalgo.backtest.util.SplitMix64;

/**
 * Settles one quoted RFQ: draws the cover from the {@link CompetitorModel}, computes the win
 * probability from the {@link HitRateModel} and draws the outcome.
 *
 * <p>Randomness comes from a stream keyed by the run seed and the RFQ id
 * ({@link SplitMix64#forStream}), so two strategies replayed over the same journal with the same
 * seed face the same competitors and the same uniform draw on every RFQ. The comparison between
 * strategies is then paired (common random numbers), which removes most of the outcome noise from
 * the difference.
 */
public final class RfqOutcomeModel {

  /** The settled outcome of one quote. */
  public record Outcome(
      boolean won, double ourSpreadBp, double coverSpreadBp, double winProbability, double uniform) {}

  private final HitRateModel hitRate;
  private final CompetitorModel competitors;
  private final long seed;

  public RfqOutcomeModel(final HitRateModel hitRate, final CompetitorModel competitors, final long seed) {
    this.hitRate = hitRate;
    this.competitors = competitors;
    this.seed = seed;
  }

  /**
   * Settles a quote.
   *
   * @param ourSpreadBp our distance from mid on the client's side, in bp (may be negative)
   */
  public Outcome settle(
      final long rfqId,
      final Instrument instrument,
      final double ourSpreadBp,
      final double sizeMillions,
      final int dealers,
      final boolean inFixingWindow) {
    final SplitMix64 rng = SplitMix64.forStream(seed, rfqId);
    final double uniform = rng.nextDouble();
    final double cover = competitors.coverBp(instrument, sizeMillions, dealers, rng);
    final double p =
        hitRate.winProbability(
            ourSpreadBp - cover, sizeMillions, instrument.tenorYears(), inFixingWindow, dealers);
    return new Outcome(uniform < p, ourSpreadBp, cover, p, uniform);
  }

  public HitRateModel hitRate() {
    return hitRate;
  }

  public CompetitorModel competitors() {
    return competitors;
  }
}
