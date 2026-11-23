package io.ratesalgo.app.loop;

import io.ratesalgo.hedge.Hedger;
import io.ratesalgo.quoting.ClientTier;
import io.ratesalgo.quoting.ClobQuoter;
import io.ratesalgo.quoting.HitRatePreset;
import io.ratesalgo.quoting.QuotingConfig;
import io.ratesalgo.quoting.TierTable;
import io.ratesalgo.risk.RiskLimits;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * The tunable parameters of one instrument group's desk. Each call builds fresh mutable objects,
 * so the live loop and its replay never share state through the settings. The defaults are the
 * values the smoke test and the process run with; they are declared, not fitted (DR-R2W-09).
 *
 * <p>Two limit presets (review 2026-11-23, Q9). {@link LimitPreset#BACKTEST} is the default and
 * what every recorded smoke and back-test run used: limits so wide that the run never meets one,
 * so that the P&amp;L being studied is the strategy's and not a limit's. {@link
 * LimitPreset#DESK_SIZED} is what a desk earning this P&amp;L would run with; see {@link
 * #deskSizedLimits}. Switching the default is a change to the back-tested configuration and
 * belongs to a pre-registered round.
 *
 * @param housekeepingNanos period of the loop timer that polls quotes, routers and the hedger
 * @param hedgeIntervalNanos how often the hedger measures its residual
 * @param tradeRatePerSecond traded quantity per second the routers assume on the CLOB (minor units)
 * @param hedgeLotMinor hedge lot on the CLOB, minor units of face (the venue's minimum size)
 * @param hedgeMaxLots most lots per hedge instrument in one plan
 * @param seed seed of the routers' anti-gaming jitter
 * @param quoteRatePerSecond the risk engine's quote rate limit; orders get the same, messages five
 *     times it
 * @param limitPreset which limit set and client tiers {@link #limits} and {@link #tiers} build
 */
public record DeskSettings(
    long housekeepingNanos,
    long hedgeIntervalNanos,
    double tradeRatePerSecond,
    long hedgeLotMinor,
    double hedgeMaxLots,
    long seed,
    double quoteRatePerSecond,
    LimitPreset limitPreset) {

  /** Which limit set a desk runs with. */
  public enum LimitPreset {
    /** Wide limits the smoke day and the back-tests never meet; the recorded configuration. */
    BACKTEST,
    /** Limits in proportion to the desk's income and to what its hedger can clear. */
    DESK_SIZED
  }

  /**
   * Session income the review measured for this desk, USD (REVIEW.md 2026-11-23, Q9). The
   * desk-sized loss limit is a multiple of it.
   */
  public static final double EXPECTED_DAILY_INCOME = 45_000.0;

  /** DV01 of the largest single RFQ the desk-sized preset quotes: about 50mm of the 10-year. */
  public static final double DESK_RFQ_DV01 = 40_000.0;

  /** Settings with the back-test limit preset. */
  public DeskSettings(
      final long housekeepingNanos,
      final long hedgeIntervalNanos,
      final double tradeRatePerSecond,
      final long hedgeLotMinor,
      final double hedgeMaxLots,
      final long seed,
      final double quoteRatePerSecond) {
    this(housekeepingNanos, hedgeIntervalNanos, tradeRatePerSecond, hedgeLotMinor, hedgeMaxLots, seed,
        quoteRatePerSecond, LimitPreset.BACKTEST);
  }

  /** The values the smoke test and the process run with. */
  public static DeskSettings defaults() {
    return new DeskSettings(10_000_000L, 250_000_000L, 2_000_000_000.0, 100_000_000L, 500.0, 2026L, 1_000.0);
  }

  /** The defaults with the desk-sized limits and client tier. Not what the recorded runs used. */
  public static DeskSettings deskSized() {
    return defaults().withLimits(LimitPreset.DESK_SIZED);
  }

  /** The same settings with another quote rate limit (load tests send far faster than a venue). */
  public DeskSettings withQuoteRate(final double perSecond) {
    return new DeskSettings(housekeepingNanos, hedgeIntervalNanos, tradeRatePerSecond, hedgeLotMinor, hedgeMaxLots, seed,
        perSecond, limitPreset);
  }

  /** The same settings with another limit preset. */
  public DeskSettings withLimits(final LimitPreset preset) {
    return new DeskSettings(housekeepingNanos, hedgeIntervalNanos, tradeRatePerSecond, hedgeLotMinor, hedgeMaxLots, seed,
        quoteRatePerSecond, preset);
  }

  /**
   * Quoting parameters: firm quotes live for the venue's response window, and the half spread is
   * held between 0.02 and 0.08 bp because the D2C simulator's dealers quote a median half spread
   * of 0.25 bp for swaps and 1/128 of a point for bonds; a desk wider than its competitors never
   * trades on the smoke day.
   */
  public QuotingConfig quoting() {
    return new QuotingConfig()
        .firmTtlNanos(5_000_000_000L)
        .maxInputAgeNanos(5_000_000_000L)
        .heartbeatTimeoutNanos(3_000_000_000L)
        .requoteThrottleNanos(0L)
        .minHalfSpreadBp(0.02)
        .maxHalfSpreadBp(0.08)
        // DR-INT-05: the engine passes an RFQ whose expected cost reaches the cap (NEGATIVE_EDGE,
        // DR-R2R); the default 0.10 bp winner's curse alone is 0.15 bp with three dealers
        .winnersCurseBp(0.02)
        .kappa(2e-6)
        .maxSkewBp(0.05);
  }

  /** The limits of this settings' preset. */
  public RiskLimits limits() {
    return limitPreset == LimitPreset.DESK_SIZED ? deskSizedLimits() : backtestLimits();
  }

  /**
   * The back-test and smoke preset: limits wide enough that the smoke day never breaches them by
   * design. They are not controls: net 5mm USD a basis point and a 50mm daily loss against a
   * session income of about 45k. No price collar, fat-finger quantity or DV01 limit is set.
   */
  public RiskLimits backtestLimits() {
    return new RiskLimits()
        .defaultMaxPosition(500_000_000_000L)
        .bucketPv01(2_000_000.0)
        .netPv01(5_000_000.0)
        .grossPv01(20_000_000.0)
        .dailyLoss(50_000_000.0)
        .orderRatePerSecond(quoteRatePerSecond)
        .quoteRatePerSecond(quoteRatePerSecond)
        .messageRatePerSecond(5.0 * quoteRatePerSecond)
        .maxOrderSize(100_000_000_000L);
  }

  /**
   * The desk-sized preset: every figure follows from the desk's income, its largest quote or its
   * hedger, and is a declared assumption until a desk head signs a limit set.
   *
   * <ul>
   *   <li>Daily loss 180k: four times {@link #EXPECTED_DAILY_INCOME} (the review asks for three to
   *       five). The breach pulls quotes and leaves the hedgers flattening.
   *   <li>Per-RFQ DV01 {@link #DESK_RFQ_DV01}, 40k: 50mm of the 10-year. Per-client DV01 200k a
   *       day: five such trades.
   *   <li>Net DV01 85k: two largest trades arriving before the hedger's first tranche, plus the
   *       band the hedger leaves open (its 20k trigger at the 6 bp baseline volatility is about
   *       3.3k of DV01, rounded up to 5k). Pillar 60k: one and a half largest trades at one
   *       tenor. Gross 340k: four times net, because a hedge at a neighbouring pillar adds to
   *       gross while it takes net down.
   *   <li>Basis and swap-spread 120k each: twice the pillar limit, since the hedger clears cash
   *       risk with futures and swaps by design.
   *   <li>Price collar 10 bp: hedge parents are limited 5 bp from mid and quotes sit inside 1 bp,
   *       so 10 bp is only ever a wrong price. Fat finger 200mm and maximum order 250mm of face.
   *   <li>Position 500mm of face per instrument.
   * </ul>
   */
  public RiskLimits deskSizedLimits() {
    return new RiskLimits()
        .defaultMaxPosition(50_000_000_000L)
        .bucketPv01(1.5 * DESK_RFQ_DV01)
        .netPv01(2.0 * DESK_RFQ_DV01 + 5_000.0)
        .grossPv01(4.0 * (2.0 * DESK_RFQ_DV01 + 5_000.0))
        .basisPv01(3.0 * DESK_RFQ_DV01)
        .swapSpreadPv01(3.0 * DESK_RFQ_DV01)
        .dailyLoss(4.0 * EXPECTED_DAILY_INCOME)
        .rfqDv01(DESK_RFQ_DV01)
        .clientDv01(5.0 * DESK_RFQ_DV01)
        .priceCollarBp(10.0)
        .fatFingerQuantity(20_000_000_000L)
        .maxOrderSize(25_000_000_000L)
        .orderRatePerSecond(quoteRatePerSecond)
        .quoteRatePerSecond(quoteRatePerSecond)
        .messageRatePerSecond(5.0 * quoteRatePerSecond);
  }

  /** The client tiers of this settings' preset. */
  public TierTable tiers() {
    return limitPreset == LimitPreset.DESK_SIZED ? deskSizedTiers() : backtestTiers();
  }

  /** The back-test preset: one tier for every client, firm quotes up to 1 billion. */
  public TierTable backtestTiers() {
    return TierTable.single(1, new ClientTier(1, 1.0, 100_000_000_000L, true, false));
  }

  /** The desk-sized preset: one tier for every client, firm quotes up to 100 million. */
  public TierTable deskSizedTiers() {
    return TierTable.single(1, new ClientTier(1, 1.0, 10_000_000_000L, true, false));
  }

  /**
   * The hedger: hedge once the residual's daily standard deviation exceeds 20,000 USD. A parent is
   * priced only off a mid no older than 5 s (the quoting input age) and stops counting as a hedge
   * 60 s after it was sent, twelve half-lives; neither is reached on the smoke day.
   */
  public Hedger.Config hedger() {
    return Hedger.config()
        .triggerStdDev(20_000.0)
        .firstTranche(0.5)
        .halfLifeNanos(5_000_000_000L)
        .limitBp(5.0)
        .maxMidAgeNanos(5_000_000_000L)
        .parentTimeoutNanos(60_000_000_000L);
  }

  /** The hit-rate preset shipped with rates-quoting. */
  public HitRatePreset hitRate() {
    try {
      return HitRatePreset.shippedDefault();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The CLOB quoter's configuration (built, not enabled on any instrument). */
  public ClobQuoter.Config clob() {
    return ClobQuoter.config().venueId(AppIds.CLOB_VENUE);
  }
}
