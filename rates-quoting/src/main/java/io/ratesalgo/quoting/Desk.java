package io.ratesalgo.quoting;

import io.ratesalgo.api.BookSource;
import io.ratesalgo.api.CurrencyCode;
import io.ratesalgo.api.FairValueSource;
import io.ratesalgo.api.HedgeOrderSink;
import io.ratesalgo.api.Instrument;
import io.ratesalgo.api.InstrumentDirectory;
import io.ratesalgo.api.OutboundSink;
import io.ratesalgo.api.SensitivityProvider;
import io.ratesalgo.common.time.Clock;
import io.ratesalgo.hedge.AxeSource;
import io.ratesalgo.hedge.ExposureBasisAttribution;
import io.ratesalgo.hedge.HedgeAnalytics;
import io.ratesalgo.hedge.HedgeCostModel;
import io.ratesalgo.hedge.HedgePlanner;
import io.ratesalgo.hedge.HedgeUniverse;
import io.ratesalgo.hedge.Hedger;
import io.ratesalgo.hedge.KeyRateCovariance;
import io.ratesalgo.messages.sbe.Side;
import io.ratesalgo.risk.KillSwitchController;
import io.ratesalgo.risk.RiskEngine;
import io.ratesalgo.risk.RiskLimits;

/**
 * Wires the P3 components for one instrument group: kill switch, risk engine, a hedger per
 * currency, fair value, quoting engine, package quoter, CLOB quoter and the event handler. The
 * coordinator builds the same graph over the real P1, P2 and P4 implementations of the {@code
 * rates-api} interfaces; tests and the cookbook build it over the stubs.
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
    final KeyRateCovariance covariance = KeyRateCovariance.assumedDefault();
    for (int c = 0; c < CurrencyCode.COUNT; c++) {
      final HedgePlanner planner =
          new HedgePlanner(in.hedgeUniverse(), risk, covariance, in.hedgeCosts(), DEFAULT_HEDGE_KAPPA, 1e-9, 20_000);
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
    tiers = new TierTableControl(in.tierTable());
    final HitRateModel hitRate = new HitRateModel(in.preset(), in.quotingConfig().learningRate);
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
            in.hedgeCosts(),
            covariance,
            health,
            in.gateway(),
            in.clock(),
            in.quotingConfig());
    packages = new PackageQuoter(in.directory(), risk, fairValue, quoting, covariance);
    clob =
        new ClobQuoter(
            in.directory(), risk, fairValue, in.book(), in.gateway(), in.clock(), in.clobConfig(),
            in.quotingConfig().maxInputAgeNanos());
    handler = new QuotingEventHandler(quoting, clob, health);
    killSwitch.register(quoting);
    killSwitch.register(clob);
    for (final Hedger h : hedgers) {
      killSwitch.register(h);
    }
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
