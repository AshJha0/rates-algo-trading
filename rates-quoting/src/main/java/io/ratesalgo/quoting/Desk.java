package io.ratesalgo.quoting;

import io.ratesalgo.api.BookSource;
import io.ratesalgo.api.CurrencyCode;
import io.ratesalgo.api.FairValueSource;
import io.ratesalgo.api.HedgeOrderSink;
import io.ratesalgo.api.Instrument;
import io.ratesalgo.api.InstrumentDirectory;
import io.ratesalgo.api.OutboundSink;
import io.ratesalgo.api.ProductType;
import io.ratesalgo.api.SensitivityProvider;
import io.ratesalgo.common.time.Clock;
import io.ratesalgo.hedge.AxeSource;
import io.ratesalgo.hedge.ExposureBasisAttribution;
import io.ratesalgo.hedge.FactorHedgePlanner;
import io.ratesalgo.hedge.FactorModel;
import io.ratesalgo.hedge.HedgeAnalytics;
import io.ratesalgo.hedge.HedgeCostModel;
import io.ratesalgo.hedge.HedgePlanner;
import io.ratesalgo.hedge.HedgePlanning;
import io.ratesalgo.hedge.HedgeUniverse;
import io.ratesalgo.hedge.Hedger;
import io.ratesalgo.hedge.KeyRateCovariance;
import io.ratesalgo.hedge.TickHedgeCostModel;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.risk.KillSwitchController;
import io.ratesalgo.risk.RiskEngine;
import io.ratesalgo.risk.RiskLimits;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Wires the P3 components for one instrument group: kill switch, risk engine, a hedger per
 * currency, fair value, quoting engine, package quoter, CLOB quoter and the event handler. The
 * coordinator builds the same graph over the real P1, P2 and P4 implementations of the {@code
 * rates-api} interfaces; tests and the cookbook build it over the stubs.
 *
 * <p>The Round 5 capabilities ({@link StrategyConfig}, carried by the quoting configuration)
 * change what is built: the fitted factor covariance for the skew and the key-rate planner, the
 * factor hedge planner, the tick hedge cost model, the cover model, the markout store and the
 * working-hedge feed of the reservation price. With every switch off the graph is the one built
 * before.
 */
public final class Desk {

  /** Default hedge risk aversion per unit of currency (API_SPEC.md Section 14.2). */
  public static final double DEFAULT_HEDGE_KAPPA = 1e-5;

  private final KillSwitchController killSwitch;
  private final RiskEngine risk;
  private final Hedger[] hedgers = new Hedger[CurrencyCode.COUNT];
  private final ExposureBasisAttribution[] basis = new ExposureBasisAttribution[CurrencyCode.COUNT];
  private final FairValueEngine fairValue;
  private final QuotingEngine quoting;
  private final PackageQuoter packages;
  private final ClobQuoter clob;
  private final InputHealth health;
  private final QuotingEventHandler handler;
  private final TierTableControl tiers;
  private final MarkoutStore markouts;
  private final StrategyConfig strategy;

  /** Everything a desk needs from outside P3. */
  public record Inputs(
      InstrumentDirectory directory,
      SensitivityProvider sensitivities,
      FairValueSource fairValues,
      BookSource book,
      RiskLimits limits,
      HedgeUniverse hedgeUniverse,
      HedgeCostModel hedgeCosts,
      OutboundSink gateway,
      OutboundSink control,
      HedgeOrderSink hedgeSink,
      Clock clock,
      QuotingConfig quotingConfig,
      TierTable tierTable,
      HitRatePreset preset,
      Hedger.Config hedgerConfig,
      ClobQuoter.Config clobConfig) {}

  /** Builds and wires a desk. */
  public Desk(final Inputs in) {
    killSwitch = new KillSwitchController(in.control(), in.clock());
    risk =
        new RiskEngine(
            in.directory(), in.sensitivities(), in.fairValues(), in.limits(), killSwitch, in.control(), in.clock());
    strategy = in.quotingConfig().strategy();
    final FactorModel model =
        strategy.factorCovariance() || strategy.factorHedging() ? strategy.factorModelOrCalibrated() : null;
    final KeyRateCovariance covariance =
        strategy.factorCovariance() ? model.covariance() : KeyRateCovariance.assumedDefault();
    final HedgeCostModel hedgeCosts =
        strategy.tickHedgeCost()
            ? new TickHedgeCostModel(
                in.directory(), in.hedgeUniverse(), strategy.ticks(), strategy.hedgeHalfSpreadTicks(),
                Math.max(1, in.hedgeUniverse().size()))
            : in.hedgeCosts();
    for (int c = 0; c < CurrencyCode.COUNT; c++) {
      final HedgePlanning planner =
          strategy.factorHedging()
              ? new FactorHedgePlanner(
                  in.hedgeUniverse(), risk, model, hedgeCosts, strategy.hedgeKappaPerUsd(), strategy.hedgeAvailability())
              : new HedgePlanner(in.hedgeUniverse(), risk, covariance, hedgeCosts, DEFAULT_HEDGE_KAPPA, 1e-9, 20_000);
      basis[c] = new ExposureBasisAttribution(risk, CurrencyCode.ofIndex(c));
      hedgers[c] =
          new Hedger(
              CurrencyCode.ofIndex(c),
              planner,
              risk,
              in.hedgeSink(),
              in.fairValues(),
              new HedgeAnalytics(1024, basis[c]),
              in.clock(),
              in.hedgerConfig().parentIdBase((1L << 40) + ((long) c << 36)));
    }
    final AxeSource axes =
        (int instrumentId, Side ourSide) -> {
          final Instrument inst = in.directory().byId(instrumentId);
          return inst == null ? 0.0 : hedgers[inst.currency().index()].axeBp(instrumentId, ourSide);
        };
    fairValue = new FairValueEngine(in.fairValues(), in.book(), risk);
    health = new InputHealth(in.quotingConfig().heartbeatTimeoutNanos);
    // a hedge is priced from the same inputs as a quote, so the hedgers stop on the same signal
    for (final Hedger h : hedgers) {
      h.inputGate(health::healthy);
      // passive hedges (internalisation) rest at the touch of the same composite book
      h.touch(in.book());
    }
    tiers = new TierTableControl(in.tierTable());
    final HitRateModel hitRate = new HitRateModel(in.preset(), in.quotingConfig().learningRate);
    resolveVolatilityReference(in.directory(), in.quotingConfig());
    markouts = strategy.markoutStore() ? loadMarkouts(strategy) : null;
    final QuotingEngine.StrategyParts parts =
        new QuotingEngine.StrategyParts(
            strategy.coverPricing() ? strategy.coverModelOrCalibrated() : null,
            markouts,
            strategy.factorHedging()
                ? new FactorHedgePlanner(
                    in.hedgeUniverse(), risk, model, hedgeCosts, strategy.hedgeKappaPerUsd(), strategy.hedgeAvailability())
                : null,
            (currency, ladder) -> hedgers[currency.index()].addWorking(ladder));
    quoting =
        new QuotingEngine(
            in.directory(),
            risk,
            fairValue,
            new SpreadModel(hitRate),
            tiers,
            new ToxicityTable(4096, 0.1, 5.0),
            axes,
            in.hedgeUniverse(),
            hedgeCosts,
            covariance,
            health,
            in.gateway(),
            in.clock(),
            in.quotingConfig(),
            parts);
    packages = new PackageQuoter(in.directory(), risk, fairValue, quoting, covariance);
    clob =
        new ClobQuoter(
            in.directory(), risk, fairValue, in.book(), in.gateway(), in.clock(), in.clobConfig(),
            in.quotingConfig().maxInputAgeNanos());
    handler = new QuotingEventHandler(quoting, clob, health);
    // both hold reservations against a clock: the engine's poll expires packages and resends
    // unanswered CLOB cancels, so neither waits for its own next message
    quoting.attach(packages::poll);
    quoting.attach(clob::poll);
    killSwitch.register(quoting);
    killSwitch.register(clob);
    killSwitch.register(packages);
    for (final Hedger h : hedgers) {
      killSwitch.register(h);
    }
  }

  /**
   * Sets each currency's volatility reference to the instrument nearest the strategy's reference
   * tenor, swaps first, where the configuration names none.
   */
  private static void resolveVolatilityReference(final InstrumentDirectory directory, final QuotingConfig config) {
    final double years = config.strategy().volatilityReferenceTenor();
    if (Double.isNaN(years)) {
      return;
    }
    for (int c = 0; c < CurrencyCode.COUNT; c++) {
      if (config.volReference[c] != 0) {
        continue;
      }
      int best = 0;
      double bestScore = Double.MAX_VALUE;
      for (int i = 0; i < directory.size(); i++) {
        final Instrument inst = directory.byIndex(i);
        if (inst.currency().index() != c || inst.type() == ProductType.FUTURE) {
          continue;
        }
        // a swap is preferred: it is what the desk quotes most and it carries the spread factor
        final double score = Math.abs(inst.tenorYears() - years) + (inst.type() == ProductType.SWAP ? 0.0 : 1_000.0);
        if (score < bestScore) {
          bestScore = score;
          best = inst.id();
        }
      }
      if (best != 0) {
        config.volatilityReference(CurrencyCode.ofIndex(c), best);
      }
    }
  }

  private static MarkoutStore loadMarkouts(final StrategyConfig strategy) {
    final MarkoutStore store =
        new MarkoutStore(
            strategy.markoutClients, strategy.markoutWindow, strategy.markoutShrinkage, strategy.tierPriorsOrCalibrated());
    if (strategy.markoutFile() == null) {
      return store;
    }
    try {
      return store.load(strategy.markoutFile());
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Saves the client markouts to the configured file (Round 5, review Q6). Call at the end of a
   * session on the loop thread; it writes a file and must not be called per RFQ. Returns false
   * when the store is off or no file is configured.
   */
  public boolean saveMarkouts() throws IOException {
    if (markouts == null || strategy.markoutFile() == null) {
      return false;
    }
    markouts.save(strategy.markoutFile());
    return true;
  }

  public KillSwitchController killSwitch() {
    return killSwitch;
  }

  public RiskEngine risk() {
    return risk;
  }

  /** The hedger for a currency. */
  public Hedger hedger(final CurrencyCode currency) {
    return hedgers[currency.index()];
  }

  /** Basis and swap-spread attribution of a currency's hedge fills. */
  public ExposureBasisAttribution basisAttribution(final CurrencyCode currency) {
    return basis[currency.index()];
  }

  public FairValueEngine fairValue() {
    return fairValue;
  }

  public QuotingEngine quoting() {
    return quoting;
  }

  public PackageQuoter packages() {
    return packages;
  }

  public ClobQuoter clob() {
    return clob;
  }

  public InputHealth health() {
    return health;
  }

  public QuotingEventHandler handler() {
    return handler;
  }

  public TierTableControl tiers() {
    return tiers;
  }
}
