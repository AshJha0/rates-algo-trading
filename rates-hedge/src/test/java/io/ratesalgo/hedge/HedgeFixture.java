package io.ratesalgo.hedge;

import io.ratesalgo.api.CurrencyCode;
import io.ratesalgo.api.Decimal9;
import io.ratesalgo.api.InMemoryFairValueSource;
import io.ratesalgo.api.InMemoryInstrumentDirectory;
import io.ratesalgo.api.Instrument;
import io.ratesalgo.api.ParametricSensitivityProvider;
import io.ratesalgo.api.RecordingHedgeOrderSink;
import io.ratesalgo.api.RecordingOutboundSink;
import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.risk.KillSwitchController;
import io.ratesalgo.risk.RiskEngine;
import io.ratesalgo.risk.RiskLimits;

/** USD bonds, futures and swaps at 2Y, 10Y and 30Y, a EUR future, a risk engine and a hedger. */
final class HedgeFixture {

  static final int B2 = 1;
  static final int B10 = 2;
  static final int B30 = 3;
  static final int TU = 11;
  static final int TY = 12;
  static final int US = 13;
  static final int S2 = 21;
  static final int S10 = 22;
  static final int S30 = 23;
  static final int RX = 31;
  static final long M = 100_000_000L;
  static final long CONTRACT = 10_000_000L;

  final ManualClock clock = new ManualClock(1_767_268_800_000_000_000L);
  final InMemoryInstrumentDirectory directory = new InMemoryInstrumentDirectory();
  final ParametricSensitivityProvider sensitivities;
  final InMemoryFairValueSource fair = new InMemoryFairValueSource();
  final RecordingOutboundSink control = new RecordingOutboundSink();
  final RecordingHedgeOrderSink sink = new RecordingHedgeOrderSink();
  final KillSwitchController kill = new KillSwitchController(control, clock);
  final RiskEngine risk;
  final HedgeUniverse universe = new HedgeUniverse();
  final TableHedgeCostModel costs = new TableHedgeCostModel(16);

  HedgeFixture() {
    this(new RiskLimits());
  }

  HedgeFixture(final RiskLimits limits) {
    add(Instrument.bond(B2, "B2", CurrencyCode.USD, 2.0, 25 * M), 99.75);
    add(Instrument.bond(B10, "B10", CurrencyCode.USD, 10.0, 25 * M), 98.25);
    add(Instrument.bond(B30, "B30", CurrencyCode.USD, 30.0, 10 * M), 96.5);
    add(Instrument.future(TU, "TU", CurrencyCode.USD, 2.0, 50 * M), 103.2);
    add(Instrument.future(TY, "TY", CurrencyCode.USD, 10.0, 50 * M), 111.1);
    add(Instrument.future(US, "US", CurrencyCode.USD, 30.0, 20 * M), 117.3);
    add(Instrument.swap(S2, "S2", CurrencyCode.USD, 2.0, 1.9, TU, 0.9, 50 * M), 3.8);
    add(Instrument.swap(S10, "S10", CurrencyCode.USD, 10.0, 8.2, TY, 0.9, 50 * M), 3.85);
    add(Instrument.swap(S30, "S30", CurrencyCode.USD, 30.0, 17.0, US, 0.9, 25 * M), 3.95);
    add(Instrument.future(RX, "RX", CurrencyCode.EUR, 10.0, 50 * M), 129.5);
    sensitivities = new ParametricSensitivityProvider(directory);
    final int[] ids = {B2, B10, B30, TU, TY, US, S2, S10, S30, RX};
    final double[] pv01 = {-1.9e-4, -8.2e-4, -17e-4, -1.9e-4, -8.0e-4, -16e-4, 1.9e-4, 8.2e-4, 17e-4, -8.6e-4};
    for (int i = 0; i < ids.length; i++) {
      sensitivities.defineAtTenor(ids[i], pv01[i], 0.0);
    }
    risk = new RiskEngine(directory, sensitivities, fair, limits, kill, control, clock);
    universe.add(TU, CONTRACT, 5_000).add(TY, CONTRACT, 5_000).add(US, CONTRACT, 3_000);
    universe.add(S2, M, 2_000).add(S10, M, 2_000).add(S30, M, 1_000).add(RX, CONTRACT, 5_000);
    // half spread per lot; futures carry a basis cost against the cash risk; impact per lot squared
    costs.set(TU, 4.0, 0.5, 0.01).set(TY, 8.0, 0.5, 0.02).set(US, 16.0, 1.0, 0.04);
    costs.set(S2, 28.5, 0.0, 0.05).set(S10, 123.0, 0.0, 0.2).set(S30, 255.0, 0.0, 0.4);
    costs.set(RX, 8.0, 0.5, 0.02);
  }

  private void add(final Instrument inst, final double level) {
    directory.add(inst);
    fair.set(inst.id(), Decimal9.round(level), clock.epochNanos());
  }

  HedgePlanner planner(final double kappa) {
    return new HedgePlanner(universe, risk, KeyRateCovariance.assumedDefault(), costs, kappa, 1e-10, 50_000);
  }

  Hedger hedger(final Hedger.Config config) {
    final Hedger h =
        new Hedger(
            CurrencyCode.USD,
            planner(1e-5),
            risk,
            sink,
            fair,
            new HedgeAnalytics(64, BasisAttribution.NONE),
            clock,
            config);
    kill.register(h);
    return h;
  }
}
