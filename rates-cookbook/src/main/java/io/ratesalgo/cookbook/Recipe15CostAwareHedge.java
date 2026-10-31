package io.ratesalgo.cookbook;

import io.ratesalgo.api.CurrencyCode;
import io.ratesalgo.api.Decimal9;
import io.ratesalgo.api.Dv01Vector;
import io.ratesalgo.api.InMemoryFairValueSource;
import io.ratesalgo.api.InMemoryInstrumentDirectory;
import io.ratesalgo.api.Instrument;
import io.ratesalgo.api.ParametricSensitivityProvider;
import io.ratesalgo.api.RecordingOutboundSink;
import io.ratesalgo.common.time.ManualClock;
import io.ratesalgo.hedge.HedgePlanner;
import io.ratesalgo.hedge.HedgeUniverse;
import io.ratesalgo.hedge.KeyRateCovariance;
import io.ratesalgo.hedge.TableHedgeCostModel;
import io.ratesalgo.risk.KillSwitchController;
import io.ratesalgo.risk.RiskEngine;
import io.ratesalgo.risk.RiskLimits;
import java.util.Locale;

/** COOKBOOK.md recipe 15: read the DV01 ladder and compute a cost-aware hedge. */
public final class Recipe15CostAwareHedge {

  private Recipe15CostAwareHedge() {}

  public static void main(final String[] args) {
    final ManualClock clock = new ManualClock();
    final InMemoryInstrumentDirectory instruments = new InMemoryInstrumentDirectory();
    final InMemoryFairValueSource fair = new InMemoryFairValueSource();
    final String[] names = {"UST2", "UST10", "UST30", "TU", "TY", "US", "SOFR2", "SOFR10", "SOFR30"};
    final double[] tenor = {2, 10, 30, 2, 10, 30, 2, 10, 30};
    final double[] pv01PerUnit = {-1.9e-4, -8.2e-4, -17e-4, -1.9e-4, -8.0e-4, -16e-4, 1.9e-4, 8.2e-4, 17e-4};
    for (int i = 0; i < names.length; i++) {
      final int id = i + 1;
      instruments.add(
          i < 3
              ? Instrument.bond(id, names[i], CurrencyCode.USD, tenor[i], 2_500_000_000L)
              : i < 6
                  ? Instrument.future(id, names[i], CurrencyCode.USD, tenor[i], 5_000_000_000L)
                  : Instrument.swap(id, names[i], CurrencyCode.USD, tenor[i], tenor[i] * 0.85, 0, 0.0, 5_000_000_000L));
      fair.set(id, Decimal9.round(i < 6 ? 100.0 : 3.9), clock.epochNanos());
    }
    final ParametricSensitivityProvider pv01 = new ParametricSensitivityProvider(instruments);
    for (int i = 0; i < names.length; i++) {
      pv01.defineAtTenor(i + 1, pv01PerUnit[i], 0.0);
    }
    final RiskEngine risk =
        new RiskEngine(
            instruments, pv01, fair, new RiskLimits(),
            new KillSwitchController(new RecordingOutboundSink(), clock), new RecordingOutboundSink(), clock);

    // a curve position: long 200m 10s, short 400m 2s, long 50m 30s
    risk.onFill(2, 20_000_000_000L, fair.fairMid(2));
    risk.onFill(1, -40_000_000_000L, fair.fairMid(1));
    risk.onFill(3, 5_000_000_000L, fair.fairMid(3));
    final Dv01Vector ladder = new Dv01Vector(CurrencyCode.USD);
    risk.ladder(CurrencyCode.USD, ladder);
    System.out.println("ladder " + ladder);
    System.out.printf(Locale.ROOT, "net %.0f, curve 2s10s %.0f%n", risk.netPv01(CurrencyCode.USD), risk.curveSpreadExposure(CurrencyCode.USD));

    // hedge instruments: futures in lots of 100k face, swaps in lots of 1m notional
    final HedgeUniverse universe = new HedgeUniverse();
    final TableHedgeCostModel costs = new TableHedgeCostModel(8);
    for (int id = 4; id <= 9; id++) {
      final boolean future = id <= 6;
      universe.add(id, future ? 10_000_000L : 100_000_000L, 5_000);
      final double lotPv01 = Math.abs(pv01PerUnit[id - 1]) * (future ? 100_000 : 1_000_000);
      // half spread: futures 0.1 USD per PV01 plus 0.006 basis; swaps 0.15 USD per PV01
      costs.set(id, (future ? 0.1 : 0.15) * lotPv01, future ? 0.006 * lotPv01 : 0.0, future ? 3e-6 * lotPv01 * lotPv01 : 3e-7 * lotPv01 * lotPv01);
    }
    final HedgePlanner planner = new HedgePlanner(universe, risk, KeyRateCovariance.assumedDefault(), costs, 1e-5, 1e-10, 50_000);
    planner.plan(ladder);
    final double[] plan = new double[universe.size()];
    planner.copyLots(plan);
    for (int j = 0; j < universe.size(); j++) {
      System.out.printf(Locale.ROOT, "  %-6s %+8.1f lots%n", names[universe.instrumentId(j) - 1], plan[j]);
    }

    // compare with selling the whole net DV01 in TY at once
    final double[] naive = new double[universe.size()];
    naive[1] = Math.round(-ladder.parallel() / (-8.0e-4 * 100_000));
    final Dv01Vector after = new Dv01Vector(CurrencyCode.USD);
    planner.residualAfter(ladder, plan, after);
    final double planSd = Math.sqrt(planner.variance(after));
    planner.residualAfter(ladder, naive, after);
    final double naiveSd = Math.sqrt(planner.variance(after));
    System.out.printf(Locale.ROOT, "unhedged daily sd %.0f%n", Math.sqrt(planner.variance(ladder)));
    System.out.printf(Locale.ROOT, "all in TY: %+.0f lots, sd %.0f, cost %.0f%n", naive[1], naiveSd, planner.cost(naive));
    System.out.printf(Locale.ROOT, "cost-aware: sd %.0f, cost %.0f, converged %s%n", planSd, planner.cost(plan), planner.converged());
  }
}
