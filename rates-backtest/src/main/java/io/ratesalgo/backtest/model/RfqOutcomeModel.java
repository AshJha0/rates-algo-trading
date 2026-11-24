package io.ratesalgo.backtest.model;

import io.ratesalgo.backtest.Instrument;
import io.ratesalgo.backtest.sim.SimDay;
import io.ratesalgo.common.random.SplitMix64;
import io.ratesalgo.messages.sbe.QuoteStatus;
import io.ratesalgo.sim.RfqDecision;

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
 *
 * <p>Simulator v2 ({@link #simV2}): the outcome is not drawn here at all. The shared market model
 * holds every competitor's quote and the client's reserve for the RFQ, fixed by the session seed,
 * and decides by best price ({@code MarketModel.decide}); the venue simulator calls the same
 * method, so the two cannot disagree (docs/research/SIM_V2_DESIGN.md section 10). The rules above
 * are version 1 and stay only so that the ledger's recorded experiments reproduce.
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
  private final SimDay simDay;

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
    this.simDay = null;
  }

  private RfqOutcomeModel(final SimDay simDay) {
    this.hitRate = null;
    this.competitors = null;
    this.seed = simDay.model().seed();
    this.choice = ClientChoice.BEST_PRICE;
    this.simDay = simDay;
  }

  /** The simulator v2 outcome model of one session: best price against the shared model's quotes. */
  public static RfqOutcomeModel simV2(final SimDay day) {
    return new RfqOutcomeModel(day);
  }

  /** The simulator v2 session this model settles against, or null for version 1. */
  public SimDay simDay() {
    return simDay;
  }

  /**
   * How a simulator v2 RFQ ended, in the back-tester's terms.
   *
   * @param status Done (the desk won), TradedAway, Passed, DoneAway or Expired
   * @param outcome the settled quote; the cover spread is the distance of the reference price from
   *     {@code midAtQuote}: the second-best price when the desk won, else the best competitor's
   */
  public record SimOutcome(QuoteStatus status, Outcome outcome) {}

  /**
   * Settles a quote against the simulator v2 session.
   *
   * @param ourPrice the desk's price on the client's side, quoted units
   * @param delayNanos time from the RFQ's arrival to the desk's quote reaching the client
   * @param midAtQuote the mid the desk's spread was measured from
   */
  public SimOutcome settleSimV2(
      final long rfqId, final Instrument instrument, final double ourPrice, final long delayNanos, final double midAtQuote) {
    final boolean buys = simDay.rfq(rfqId).clientBuys();
    final SimDay.Settlement s = simDay.outcome(rfqId, ourPrice, delayNanos);
    final RfqDecision d = s.decision();
    final boolean won = d.deskWon();
    final double reference;
    if (won) {
      reference = Double.isNaN(d.coverPrice()) ? ourPrice : d.coverPrice();
    } else {
      reference = Double.isNaN(s.bestCompetitorPrice()) ? ourPrice : s.bestCompetitorPrice();
    }
    final double sign = buys ? 1.0 : -1.0;
    final Outcome outcome =
        new Outcome(
            won,
            instrument.priceToBp(sign * (ourPrice - midAtQuote)),
            instrument.priceToBp(sign * (reference - midAtQuote)),
            won ? 1.0 : 0.0,
            0.0);
    final QuoteStatus status =
        switch (d.outcome()) {
          case DONE -> won ? QuoteStatus.Done : QuoteStatus.TradedAway;
          case PASSED -> QuoteStatus.Passed;
          case DONE_AWAY -> QuoteStatus.DoneAway;
          case EXPIRED -> QuoteStatus.Expired;
        };
    return new SimOutcome(status, outcome);
  }

  /** The same model with another client rule. */
  public RfqOutcomeModel withClientChoice(final ClientChoice c) {
    if (simDay != null) {
      throw new IllegalStateException("simulator v2 has one headline rule, best price (design section 7)");
    }
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
    if (simDay != null) {
      throw new IllegalStateException("a simulator v2 outcome model settles with settleSimV2");
    }
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
