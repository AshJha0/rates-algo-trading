package io.ratesalgo.backtest.model;

import io.ratesalgo.backtest.Instrument;
import io.ratesalgo.backtest.util.SplitMix64;

/**
 * Settles one quoted RFQ: draws the cover from the {@link CompetitorModel}, decides the client's
 * choice and draws the outcome.
 *
 * <p>Two client rules, chosen by {@link ClientChoice}:
 *
 * <ul>
 *   <li>{@link ClientChoice#HIT_RATE} (the default): the win probability comes from the {@link
 *       HitRateModel}, a soft logistic in our distance to the cover, so a client may accept a quote
 *       behind the best competitor.
 *   <li>{@link ClientChoice#BEST_PRICE}: the client trades with the best price. We win when our
 *       spread is inside the cover, lose when it is outside, and win a tie on a fair coin. This is
 *       the price-driven client of PNL_AUDIT.md item 3.
 * </ul>
 *
 * <p>Randomness comes from a stream keyed by the run seed and the RFQ id
 * ({@link SplitMix64#forStream}), so two strategies replayed over the same journal with the same
 * seed face the same competitors and the same uniform draw on every RFQ. The comparison between
 * strategies is then paired (common random numbers), which removes most of the outcome noise from
 * the difference. Both client rules draw the same numbers in the same order, so the cover on every
 * RFQ is the same under either.
 */
public final class RfqOutcomeModel {

  /** How the simulated client picks a dealer. */
  public enum ClientChoice {
    /** Soft logistic in our distance to the cover ({@link HitRateModel}). */
    HIT_RATE,
    /** Best price wins; a tie is a fair coin. */
    BEST_PRICE
  }

  /** The settled outcome of one quote. */
  public record Outcome(
      boolean won, double ourSpreadBp, double coverSpreadBp, double winProbability, double uniform) {}

  private final HitRateModel hitRate;
  private final CompetitorModel competitors;
  private final long seed;
  private final ClientChoice choice;

  public RfqOutcomeModel(final HitRateModel hitRate, final CompetitorModel competitors, final long seed) {
    this(hitRate, competitors, seed, ClientChoice.HIT_RATE);
  }

  public RfqOutcomeModel(
      final HitRateModel hitRate, final CompetitorModel competitors, final long seed, final ClientChoice choice) {
    if (choice == null) {
      throw new IllegalArgumentException("client choice is required");
    }
    this.hitRate = hitRate;
    this.competitors = competitors;
    this.seed = seed;
    this.choice = choice;
  }

  /** The same model with another client rule. */
  public RfqOutcomeModel withClientChoice(final ClientChoice c) {
    return new RfqOutcomeModel(hitRate, competitors, seed, c);
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
        switch (choice) {
          case HIT_RATE ->
              hitRate.winProbability(
                  ourSpreadBp - cover, sizeMillions, instrument.tenorYears(), inFixingWindow, dealers);
          case BEST_PRICE -> ourSpreadBp < cover ? 1.0 : ourSpreadBp > cover ? 0.0 : 0.5;
        };
    return new Outcome(uniform < p, ourSpreadBp, cover, p, uniform);
  }

  public HitRateModel hitRate() {
    return hitRate;
  }

  public CompetitorModel competitors() {
    return competitors;
  }

  public ClientChoice clientChoice() {
    return choice;
  }
}
