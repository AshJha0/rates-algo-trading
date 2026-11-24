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
import io.ratesalgo.hedge.FactorHedgePlanner;
import io.ratesalgo.hedge.FactorModel;
import io.ratesalgo.hedge.HedgeAvailability;
import io.ratesalgo.hedge.HedgeUniverse;
import io.ratesalgo.hedge.TickHedgeCostModel;
import io.ratesalgo.quoting.VenueTickTable;
import io.ratesalgo.risk.KillSwitchController;
import io.ratesalgo.risk.RiskEngine;
import io.ratesalgo.risk.RiskLimits;
import java.util.Locale;

/** COOKBOOK.md recipe 43: the factor hedge planner with tick hedge costs. */
public final class Recipe43FactorHedge {

  private static final long MILLION = 100_000_000L;

  private Recipe43FactorHedge() {}

  public static void main(final String[] args) {
    final ManualClock clock = new ManualClock();
    final InMemoryInstrumentDirectory instruments = new InMemoryInstrumentDirectory();
    final InMemoryFairValueSource fair = new InMemoryFairValueSource();
    final ParametricSensitivityProvider pv01 = new ParametricSensitivityProvider(instruments);
    // the price changes per basis point of the simulator v2 structure file, per unit of face
    final String[] names = {"UST2", "UST10", "UST30"};
    final double[] tenor = {2, 10, 30};
    final double[] perUnit = {-1.90e-4, -8.15e-4, -17.30e-4};
    for (int i = 0; i < names.length; i++) {
      instruments.add(Instrument.bond(i + 1, names[i], CurrencyCode.USD, tenor[i], 25 * MILLION));
      fair.set(i + 1, Decimal9.round(100.0), clock.epochNanos());
      pv01.defineAtTenor(i + 1, perUnit[i], 0.0);
    }
    final RiskEngine risk =
        new RiskEngine(
            instruments, pv01, fair, new RiskLimits(),
            new KillSwitchController(new RecordingOutboundSink(), clock), new RecordingOutboundSink(), clock);

    // hedge with 2-year and 10-year notes in lots of 1m face; a crossing costs half a tick.
    // Ticks: 1/256 of a point on the 2-year (3_906_250 as a Decimal9), 1/128 on the 10-year.
    final HedgeUniverse universe = new HedgeUniverse().add(1, MILLION, 5_000).add(2, MILLION, 5_000);
    final VenueTickTable ticks = VenueTickTable.simulatorDefaults().instrument(1, 3_906_250L);
    final TickHedgeCostModel costs = new TickHedgeCostModel(instruments, universe, ticks, 0.5, 4);
    for (int j = 0; j < universe.size(); j++) {
      final int id = universe.instrumentId(j);
      final double lotDv01 = Math.abs(perUnit[id - 1]) * 1e6;
      System.out.printf(
          Locale.ROOT, "%-5s tick %.4f USD a lot, half a tick %.4f USD = %.3f bp of the lot's DV01 of %.0f%n",
          names[id - 1], costs.tickValuePerLot(id), costs.linearPerLot(id), costs.linearPerLot(id) / lotDv01, lotDv01);
    }

    // long 50m of 30-year notes
    risk.onFill(3, 50 * MILLION, fair.fairMid(3));
    final Dv01Vector ladder = new Dv01Vector(CurrencyCode.USD);
    risk.ladder(CurrencyCode.USD, ladder);
    System.out.printf(Locale.ROOT, "net PV01 %.0f, all at 30 years%n", ladder.parallel());
    final FactorModel factors = FactorModel.calibrated();

    // the risk aversion decides whether the second crossing is worth its cost
    plan("small risk aversion (1e-9 per USD)", new FactorHedgePlanner(
        universe, risk, factors, costs, 1e-9, HedgeAvailability.ALL), ladder, names);
    plan("larger risk aversion (4e-7 per USD)", new FactorHedgePlanner(
        universe, risk, factors, costs, 4e-7, HedgeAvailability.ALL), ladder, names);
  }

  private static void plan(
      final String title, final FactorHedgePlanner planner, final Dv01Vector ladder, final String[] names) {
    planner.plan(ladder);
    System.out.printf(
        Locale.ROOT, "%s: level hedged %s, slope hedged %s, modelled crossing cost %.0f USD%n",
        title, planner.levelHedged(), planner.slopeHedged(), planner.plannedCost());
    for (int j = 0; j < planner.universe().size(); j++) {
      if (planner.lots(j) != 0.0) {
        System.out.printf(
            Locale.ROOT, "  %-5s %+.1f lots%n", names[planner.universe().instrumentId(j) - 1], planner.lots(j));
      }
    }
    for (int f = 0; f < FactorModel.FACTORS; f++) {
      // an exposure hedged to zero is zero up to rounding error, of either sign
      final double left = Math.abs(planner.exposureAfter(f)) < 1e-3 ? 0.0 : planner.exposureAfter(f);
      System.out.printf(
          Locale.ROOT, "  %-9s exposure %+9.0f -> %+9.0f USD per bp, daily sd left %9.0f USD%n",
          FactorModel.factorName(f), planner.exposureBefore(f), left, planner.residualSd(f));
    }
    System.out.printf(Locale.ROOT, "  idiosyncratic daily sd left %.0f USD%n", planner.residualIdioSd());
  }
}
