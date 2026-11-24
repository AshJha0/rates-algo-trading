package io.ratesalgo.app.loop;

import io.ratesalgo.hedge.Hedger;
import io.ratesalgo.quoting.ClientTier;
import io.ratesalgo.quoting.ClobQuoter;
import io.ratesalgo.quoting.HitRatePreset;
import io.ratesalgo.quoting.QuotingConfig;
import io.ratesalgo.quoting.StrategyConfig;
import io.ratesalgo.quoting.TierPriors;
import io.ratesalgo.quoting.TierTable;
import io.ratesalgo.risk.RiskLimits;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

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
 * <p>Two strategy presets (Round 5, docs/research/STRATEGY_V2.md). {@link StrategyPreset#DEFAULT}
 * quotes and hedges as every recorded run did. {@link StrategyPreset#V2}, from {@link
 * #strategyV2()}, turns on the Round 5 capabilities together: cover-relative break-even pricing,
 * the capped reservation price, the fitted factor covariance and factor hedging, tick hedge costs,
 * the client markout store with three client tiers, tick rounding, the volatility time to live and
 * the pull at half the quoted distance, and an internalising hedger. It is for the
 * pre-registered family R5-FAM-001 and is not recommended for anything until it is read.
 *
 * <p>What a desk runs is the resolved {@link StrategySwitches}, not a preset's name: the presets are
 * two values of it, and each ablation arm is {@link StrategySwitches#V2} with one capability
 * switched off. {@link #quoting}, {@link #hedger} and {@link #tiers} are functions of the switches,
 * so two settings with equal switches build the same desk. The other components of a desk (market,
 * pricing binding, router) are {@link DeskProfile}'s; a profile with its settings is the whole
 * configuration ({@link DeskProfile#resolved}).
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
 * @param strategy the strategy switches {@link #quoting}, {@link #tiers} and {@link #hedger} build
 */
public record DeskSettings(
    long housekeepingNanos,
    long hedgeIntervalNanos,
    double tradeRatePerSecond,
    long hedgeLotMinor,
    double hedgeMaxLots,
    long seed,
    double quoteRatePerSecond,
    LimitPreset limitPreset,
    StrategySwitches strategy) {

  /** Which limit set a desk runs with. */
  public enum LimitPreset {
    /** Wide limits the smoke day and the back-tests never meet; the recorded configuration. */
    BACKTEST,
    /** Limits in proportion to the desk's income and to what its hedger can clear. */
    DESK_SIZED
  }

  /** A named value of the strategy switches. */
  public enum StrategyPreset {
    /** The recorded strategy: every Round 5 switch off ({@link StrategySwitches#DEFAULT}). */
    DEFAULT,
    /** The Round 5 capabilities together (STRATEGY_V2.md, {@link StrategySwitches#V2}). */
    V2,
    /** Any other value of the switches, such as an ablation arm; never a starting point. */
    CUSTOM;

    /** The switches of a named preset. */
    public StrategySwitches switches() {
      return switch (this) {
        case DEFAULT -> StrategySwitches.DEFAULT;
        case V2 -> StrategySwitches.V2;
        case CUSTOM -> throw new IllegalArgumentException("CUSTOM names no switches; pass StrategySwitches");
      };
    }
  }

  /**
   * What the desk's quoting and hedging strategy is, switch by switch (STRATEGY_V2.md Section 8).
   * The eight capability switches are {@link StrategyConfig}'s; the rest are the parts of the
   * Round 5 desk that live outside it.
   *
   * @param coverPricing break-even, cover-relative pricing (Section 2)
   * @param reservationPrice the capped inventory reservation price (Section 3)
   * @param factorCovariance the fitted factor covariance (Section 4)
   * @param factorHedging the factor hedge planner (Section 4)
   * @param tickHedgeCost hedge cost from each instrument's tick (Section 4)
   * @param markoutStore the client markout store (Section 6)
   * @param tickRounding prices rounded to the venue tick, our way (Section 7)
   * @param volatilityTtl the volatility time to live (Section 7)
   * @param volatilityReferenceTenor tenor in years whose swap volatility drives the time to live;
   *     NaN for the engine's own choice
   * @param pullFraction pull a quote once this fraction of its quoted distance is gone; 0 for no
   *     pull by fraction (Section 7)
   * @param internalisingHedger hold risk up to a budget and a time stop, resting hedges at the touch
   *     first (Section 5); false is the default hedger, which crosses at its trigger
   * @param clientTiers three client tiers with the calibration's priors ({@link #V2_CLIENT_TIERS});
   *     false is one tier for every client
   */
  public record StrategySwitches(
      boolean coverPricing,
      boolean reservationPrice,
      boolean factorCovariance,
      boolean factorHedging,
      boolean tickHedgeCost,
      boolean markoutStore,
      boolean tickRounding,
      boolean volatilityTtl,
      double volatilityReferenceTenor,
      double pullFraction,
      boolean internalisingHedger,
      boolean clientTiers) {

    /** The recorded strategy: nothing on. */
    public static final StrategySwitches DEFAULT =
        new StrategySwitches(false, false, false, false, false, false, false, false, Double.NaN, 0.0, false, false);

    /**
     * The Round 5 desk: every capability on, the volatility read at the 10-year, the pull at half
     * the quoted distance (review Q7), the internalising hedger and three client tiers.
     */
    public static final StrategySwitches V2 =
        new StrategySwitches(true, true, true, true, true, true, true, true, 10.0, 0.5, true, true);

    /** Validates the switches. */
    public StrategySwitches {
      if (!(pullFraction >= 0.0)) {
        throw new IllegalArgumentException("pullFraction must be >= 0: " + pullFraction);
      }
    }

    /** The same switches with cover pricing on or off. */
    public StrategySwitches withCoverPricing(final boolean on) {
      return new StrategySwitches(on, reservationPrice, factorCovariance, factorHedging, tickHedgeCost, markoutStore,
          tickRounding, volatilityTtl, volatilityReferenceTenor, pullFraction, internalisingHedger, clientTiers);
    }

    /** The same switches with the reservation price on or off. */
    public StrategySwitches withReservationPrice(final boolean on) {
      return new StrategySwitches(coverPricing, on, factorCovariance, factorHedging, tickHedgeCost, markoutStore,
          tickRounding, volatilityTtl, volatilityReferenceTenor, pullFraction, internalisingHedger, clientTiers);
    }

    /**
     * The same switches with the factor covariance, factor hedging and tick hedge cost on or off
     * together: one change to how risk is measured and hedged (STRATEGY_V2.md Section 8).
     */
    public StrategySwitches withFactorHedging(final boolean on) {
      return new StrategySwitches(coverPricing, reservationPrice, on, on, on, markoutStore, tickRounding,
          volatilityTtl, volatilityReferenceTenor, pullFraction, internalisingHedger, clientTiers);
    }

    /** The same switches with the markout store on or off. */
    public StrategySwitches withMarkoutStore(final boolean on) {
      return new StrategySwitches(coverPricing, reservationPrice, factorCovariance, factorHedging, tickHedgeCost, on,
          tickRounding, volatilityTtl, volatilityReferenceTenor, pullFraction, internalisingHedger, clientTiers);
    }

    /**
     * The same switches with quote protection on or off together: tick rounding, the volatility
     * time to live and the pull at {@code pull} of the quoted distance (off: both false, pull 0).
     */
    public StrategySwitches withQuoteProtection(final boolean on, final double pull) {
      return new StrategySwitches(coverPricing, reservationPrice, factorCovariance, factorHedging, tickHedgeCost,
          markoutStore, on, on, volatilityReferenceTenor, on ? pull : 0.0, internalisingHedger, clientTiers);
    }

    /** The same switches with the internalising hedger or the default one. */
    public StrategySwitches withInternalisingHedger(final boolean on) {
      return new StrategySwitches(coverPricing, reservationPrice, factorCovariance, factorHedging, tickHedgeCost,
          markoutStore, tickRounding, volatilityTtl, volatilityReferenceTenor, pullFraction, on, clientTiers);
    }

    /** True when any {@link StrategyConfig} capability is on. */
    public boolean anyCapability() {
      return coverPricing || reservationPrice || factorCovariance || factorHedging || tickHedgeCost || markoutStore
          || tickRounding || volatilityTtl;
    }

    /** The quoting engine's switches, a fresh object. */
    public StrategyConfig strategyConfig() {
      final StrategyConfig s =
          new StrategyConfig()
              .coverPricing(coverPricing)
              .reservationPrice(reservationPrice)
              .factorCovariance(factorCovariance)
              .factorHedging(factorHedging)
              .tickHedgeCost(tickHedgeCost)
              .markoutStore(markoutStore)
              .tickRounding(tickRounding)
              .volatilityTtl(volatilityTtl);
      return Double.isNaN(volatilityReferenceTenor) ? s : s.volatilityReferenceTenor(volatilityReferenceTenor);
    }
  }

  /**
   * Strategy V2's internalisation budget, USD of daily sd: about two median 10-year swap RFQs
   * (13mm, 11k of DV01, 60k of daily sd each), six times the hedger's 20k trigger. Over the 45 s
   * time stop such a book's intraday P&amp;L sd is about 2.5k USD, against about 1k USD of hedge
   * cost saved when a resting hedge fills (STRATEGY_V2.md Section 5).
   */
  public static final double V2_BUDGET_STD_DEV = 120_000.0;

  /**
   * Strategy V2's time stop: 45 s, past four of the simulator's 10 s information time constants
   * and inside the 60 s stalled-parent timeout, so a resting hedge is withdrawn and crossed before
   * the timeout would cancel it.
   */
  public static final long V2_TIME_STOP_NANOS = 45_000_000_000L;

  /**
   * The wire client id of simulator v2's tier A; tiers B and C follow it (SIM_V2_DESIGN.md Section
   * 14, D12: the D2C venue sends 2001, 2002 and 2003 for tiers A, B and C). The one place the desk
   * states the simulator's client ids: {@link #V2_CLIENT_TIERS} and the back-test's binding are
   * derived from it, and {@code ClientTierWiringTest} checks it against the venue simulator's own
   * RFQs.
   */
  public static final int V2_CLIENT_ID_BASE = 2001;

  /** The calibration's tiers in the simulator's order: tier index 0, 1, 2 is A, B, C. */
  public static final List<Integer> V2_TIERS = List.of(TierPriors.TIER_A, TierPriors.TIER_B, TierPriors.TIER_C);

  /**
   * Strategy V2's client tiers: wire client id to the index of its tier in {@link
   * #strategyV2Tiers} (A, B, C). Anyone else is tier B.
   */
  public static final Map<Integer, Integer> V2_CLIENT_TIERS =
      Map.of(v2ClientId(0), 0, v2ClientId(1), 1, v2ClientId(2), 2);

  /** The wire client id simulator v2 sends for a tier index (0 is tier A). */
  public static int v2ClientId(final int tierIndex) {
    if (tierIndex < 0 || tierIndex >= V2_TIERS.size()) {
      throw new IllegalArgumentException("no simulator v2 tier with index " + tierIndex);
    }
    return V2_CLIENT_ID_BASE + tierIndex;
  }

  /** Validates the settings. */
  public DeskSettings {
    java.util.Objects.requireNonNull(limitPreset, "limitPreset");
    java.util.Objects.requireNonNull(strategy, "strategy");
  }

  /** Settings with a named strategy preset. */
  public DeskSettings(
      final long housekeepingNanos,
      final long hedgeIntervalNanos,
      final double tradeRatePerSecond,
      final long hedgeLotMinor,
      final double hedgeMaxLots,
      final long seed,
      final double quoteRatePerSecond,
      final LimitPreset limitPreset,
      final StrategyPreset strategyPreset) {
    this(housekeepingNanos, hedgeIntervalNanos, tradeRatePerSecond, hedgeLotMinor, hedgeMaxLots, seed,
        quoteRatePerSecond, limitPreset, strategyPreset.switches());
  }

  /** Settings with the given limit preset and the default strategy. */
  public DeskSettings(
      final long housekeepingNanos,
      final long hedgeIntervalNanos,
      final double tradeRatePerSecond,
      final long hedgeLotMinor,
      final double hedgeMaxLots,
      final long seed,
      final double quoteRatePerSecond,
      final LimitPreset limitPreset) {
    this(housekeepingNanos, hedgeIntervalNanos, tradeRatePerSecond, hedgeLotMinor, hedgeMaxLots, seed,
        quoteRatePerSecond, limitPreset, StrategySwitches.DEFAULT);
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

  /**
   * The defaults with the Round 5 strategy (back-test limits, so that a paired comparison with the
   * default desk differs in the strategy only). Not what any recorded run used.
   */
  public static DeskSettings strategyV2() {
    return defaults().withStrategy(StrategyPreset.V2);
  }

  /** The same settings with another quote rate limit (load tests send far faster than a venue). */
  public DeskSettings withQuoteRate(final double perSecond) {
    return new DeskSettings(housekeepingNanos, hedgeIntervalNanos, tradeRatePerSecond, hedgeLotMinor, hedgeMaxLots, seed,
        perSecond, limitPreset, strategy);
  }

  /** The same settings with another limit preset. */
  public DeskSettings withLimits(final LimitPreset preset) {
    return new DeskSettings(housekeepingNanos, hedgeIntervalNanos, tradeRatePerSecond, hedgeLotMinor, hedgeMaxLots, seed,
        quoteRatePerSecond, preset, strategy);
  }

  /** The same settings with a named strategy preset. */
  public DeskSettings withStrategy(final StrategyPreset preset) {
    return withStrategy(preset.switches());
  }

  /** The same settings with these strategy switches. */
  public DeskSettings withStrategy(final StrategySwitches switches) {
    return new DeskSettings(housekeepingNanos, hedgeIntervalNanos, tradeRatePerSecond, hedgeLotMinor, hedgeMaxLots, seed,
        quoteRatePerSecond, limitPreset, switches);
  }

  /** The preset the switches are, or {@link StrategyPreset#CUSTOM}. */
  public StrategyPreset strategyPreset() {
    if (strategy.equals(StrategySwitches.DEFAULT)) {
      return StrategyPreset.DEFAULT;
    }
    return strategy.equals(StrategySwitches.V2) ? StrategyPreset.V2 : StrategyPreset.CUSTOM;
  }

  /**
   * Quoting parameters: firm quotes live for the venue's response window, and the half spread is
   * held between 0.02 and 0.08 bp because the D2C simulator's dealers quote a median half spread
   * of 0.25 bp for swaps and 1/128 of a point for bonds; a desk wider than its competitors never
   * trades on the smoke day.
   */
  public QuotingConfig quoting() {
    final QuotingConfig q = defaultQuoting();
    if (strategy.anyCapability()) {
      q.strategy(strategy.strategyConfig());
    }
    // the recorded desk never sets the fraction, and 0 is the engine's own "off"
    return strategy.pullFraction() > 0.0 ? q.fairMoveWithdrawFraction(strategy.pullFraction()) : q;
  }

  /**
   * Strategy V2's switches: every Round 5 pricing and hedging capability with its calibrated model,
   * the volatility estimated from the 10-year swap. The individual switches are {@link
   * StrategyConfig}'s, so each can be run alone (ablation).
   *
   * <p>The fix round's pull at a fraction of the quoted distance is on (0.5: a quote is withdrawn
   * once half its edge is gone, review Q7). Rejecting a mismatched Done stays off: booking the
   * venue's trade and disputing it keeps the risk engine's position true (DR-RV-Q03).
   */
  public static StrategyConfig strategyV2Switches() {
    return StrategySwitches.V2.strategyConfig();
  }

  private QuotingConfig defaultQuoting() {
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

  /**
   * The client tiers of this settings' presets: strategy V2's three tiers when it is on, otherwise
   * the limit preset's single tier. V2's tiers quote up to the limit preset's size.
   */
  public TierTable tiers() {
    final TierTable single = limitPreset == LimitPreset.DESK_SIZED ? deskSizedTiers() : backtestTiers();
    return strategy.clientTiers() ? strategyV2Tiers(single.tierOf(0).maxQuantity()) : single;
  }

  /**
   * Strategy V2's tiers A, B and C (ids {@link TierPriors#TIER_A} to {@link TierPriors#TIER_C}, as
   * the markout store's priors use them), all firm, multiplier 1: on the V2 path tiers differ by
   * their adverse prior and fill probability, not by a spread multiplier. Clients from {@link
   * #V2_CLIENT_TIERS}; anyone else is tier B.
   */
  public static TierTable strategyV2Tiers(final long maxQuantity) {
    final List<ClientTier> tiers = new java.util.ArrayList<>(V2_TIERS.size());
    for (final int tier : V2_TIERS) {
      tiers.add(new ClientTier(tier, 1.0, maxQuantity, true, false));
    }
    return new TierTable(1, tiers, V2_CLIENT_TIERS, 1);
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
    final Hedger.Config h =
        Hedger.config()
            .triggerStdDev(20_000.0)
            .firstTranche(0.5)
            .halfLifeNanos(5_000_000_000L)
            .limitBp(5.0)
            .maxMidAgeNanos(5_000_000_000L)
            .parentTimeoutNanos(60_000_000_000L);
    // strategy V2 internalises: holds up to the budget, rests at the touch, crosses at the time stop
    return strategy.internalisingHedger()
        ? h.internalise(V2_BUDGET_STD_DEV, V2_TIME_STOP_NANOS).passiveFirst(true)
        : h;
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
