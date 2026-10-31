package io.ratesalgo.hedge;

import static io.ratesalgo.hedge.HedgeFixture.B10;
import static io.ratesalgo.hedge.HedgeFixture.B2;
import static io.ratesalgo.hedge.HedgeFixture.B30;
import static io.ratesalgo.hedge.HedgeFixture.M;
import static io.ratesalgo.hedge.HedgeFixture.TY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.api.CurrencyCode;
import io.ratesalgo.api.Dv01Vector;
import io.ratesalgo.api.KeyRateTenors;
import org.junit.jupiter.api.Test;

class HedgePlannerTest {

  private static final int J_TY = 1;
  private static final int J_S10 = 4;

  @Test
  void smallRiskGoesToFuturesUnlessTheBasisMakesSwapsCheaper() {
    final HedgeFixture f = new HedgeFixture();
    f.risk.onFill(B10, 10 * M, f.fair.fairMid(B10)); // -8,200 at 10Y
    final HedgePlanner p = f.planner(1e-5);
    final Dv01Vector r = ladder(f);
    assertTrue(p.plan(r));
    assertTrue(p.lots(J_TY) < -50, "sell TY: " + p.lots(J_TY));
    assertTrue(Math.abs(p.lots(J_S10)) * 820.0 < 0.2 * Math.abs(p.lots(J_TY)) * 80.0, "mostly futures");
    f.costs.basis(TY, 6.0);
    p.plan(r);
    assertTrue(p.lots(J_S10) > 5, "pay fixed on 10Y swaps: " + p.lots(J_S10));
    assertTrue(Math.abs(p.lots(J_TY)) * 80.0 < 0.2 * p.lots(J_S10) * 820.0, "mostly swaps: " + p.lots(J_TY));
    assertTrue(p.iterations() > 0);
  }

  /**
   * P3 gate: against immediately hedging the whole net DV01 in the 10-year future, the cost-aware
   * plan leaves less residual key-rate variance at lower modelled cost.
   */
  @Test
  void costAwarePlanBeatsImmediateFullHedgeOnVarianceAndCost() {
    final HedgeFixture f = new HedgeFixture();
    f.risk.onFill(B10, 200 * M, f.fair.fairMid(B10));
    f.risk.onFill(B2, -400 * M, f.fair.fairMid(B2));
    f.risk.onFill(B30, 50 * M, f.fair.fairMid(B30));
    final Dv01Vector r = ladder(f);
    final HedgePlanner p = f.planner(1e-5);
    assertTrue(p.plan(r), "converged");
    final int m = f.universe.size();
    final double[] plan = new double[m];
    p.copyLots(plan);
    final double[] naive = new double[m];
    final double tyPerLot = f.risk.unitParallelPv01(TY) * f.directory.byId(TY).major(HedgeFixture.CONTRACT);
    naive[J_TY] = Math.round(-r.parallel() / tyPerLot);
    final Dv01Vector afterPlan = new Dv01Vector(CurrencyCode.USD);
    final Dv01Vector afterNaive = new Dv01Vector(CurrencyCode.USD);
    p.residualAfter(r, plan, afterPlan);
    p.residualAfter(r, naive, afterNaive);
    final double unhedged = p.variance(r);
    final double vPlan = p.variance(afterPlan);
    final double vNaive = p.variance(afterNaive);
    final double cPlan = p.cost(plan);
    final double cNaive = p.cost(naive);
    System.out.printf(
        "HedgePlannerTest: unhedged sd %.0f, naive sd %.0f cost %.0f, plan sd %.0f cost %.0f%n",
        Math.sqrt(unhedged), Math.sqrt(vNaive), cNaive, Math.sqrt(vPlan), cPlan);
    assertTrue(Math.abs(afterNaive.parallel()) < 100.0, "naive hedge flattens net DV01");
    assertTrue(vPlan < vNaive, "plan leaves less key-rate variance");
    assertTrue(cPlan < cNaive, "plan costs less");
    assertTrue(vPlan < 0.05 * unhedged, "plan removes at least 95% of the variance");
    final double objective = 0.5 * p.kappa() * vPlan + cPlan;
    assertTrue(objective < 0.5 * p.kappa() * vNaive + cNaive);
    for (int k = 0; k < KeyRateTenors.COUNT; k++) {
      assertTrue(Math.abs(afterPlan.get(k)) < Math.abs(r.get(k)) + 1.0, "no pillar made worse: " + k);
    }
  }

  @Test
  void otherCurrencyInstrumentsAreNotUsedAndBoundsHold() {
    final HedgeFixture f = new HedgeFixture();
    f.risk.onFill(B10, 3_000 * M, f.fair.fairMid(B10)); // -2.46m PV01: beyond every bound
    final HedgePlanner p = f.planner(1e-3);
    p.plan(ladder(f));
    for (int j = 0; j < f.universe.size(); j++) {
      assertTrue(Math.abs(p.lots(j)) <= f.universe.maxLots(j) + 1e-6);
    }
    assertEquals(0.0, p.lots(6), 0.0, "RX is EUR");
    assertThrows(IllegalArgumentException.class, () -> f.planner(0.0));
    assertThrows(IllegalArgumentException.class, () -> f.universe.add(1, 0L, 1.0));
    assertEquals(-1, f.universe.indexOf(999));
    assertEquals(TY, f.universe.instrumentId(f.universe.indexOf(TY)));
  }

  /**
   * DR-R2R-10: one universe instrument without a cost entry used to set the solver's stop
   * tolerance to about 1e293, so the plan stopped after one iteration, reported converged and
   * left the risk almost unhedged. It is now left out and the rest of the plan is unchanged.
   */
  @Test
  void anInstrumentWithoutACostEntryIsLeftOutAndThePlanStillConverges() {
    final HedgeFixture base = new HedgeFixture();
    base.risk.onFill(B10, 100 * M, base.fair.fairMid(B10));
    final HedgePlanner pb = base.planner(1e-5);
    final Dv01Vector rb = ladder(base);
    assertTrue(pb.plan(rb));
    final HedgeFixture f = new HedgeFixture();
    f.universe.add(B30, M, 100);
    f.risk.onFill(B10, 100 * M, f.fair.fairMid(B10));
    final HedgePlanner p = f.planner(1e-5);
    final Dv01Vector r = ladder(f);
    assertTrue(p.plan(r));
    assertTrue(p.iterations() > 10, "iterations " + p.iterations());
    assertEquals(0.0, p.lots(f.universe.size() - 1), 0.0, "the unpriced instrument is not traded");
    final double[] l = new double[f.universe.size()];
    p.copyLots(l);
    final Dv01Vector out = new Dv01Vector(CurrencyCode.USD);
    p.residualAfter(r, l, out);
    final double[] lb = new double[base.universe.size()];
    pb.copyLots(lb);
    final Dv01Vector outB = new Dv01Vector(CurrencyCode.USD);
    pb.residualAfter(rb, lb, outB);
    assertTrue(Math.sqrt(p.variance(out)) < 0.05 * Math.sqrt(p.variance(r)));
    assertEquals(Math.sqrt(pb.variance(outB)), Math.sqrt(p.variance(out)), 1e-6 * Math.sqrt(pb.variance(rb)));
  }

  @Test
  void costModelAndCovariance() {
    final TableHedgeCostModel c = new TableHedgeCostModel(1).set(7, 2.0, 1.0, 0.5);
    assertEquals(3.0 * 4 + 0.5 * 16, c.cost(7, -4.0), 1e-12);
    assertEquals(Double.POSITIVE_INFINITY, c.linearPerLot(8), "unknown instruments are never traded");
    assertEquals(0.0, c.impactPerLot2(8), 0.0);
    assertThrows(IllegalStateException.class, () -> c.set(8, 1.0, 0.0, 0.0));
    assertThrows(IllegalArgumentException.class, () -> c.set(7, -1.0, 0.0, 0.0));
    assertThrows(IllegalArgumentException.class, () -> c.basis(7, -1.0));
    final KeyRateCovariance cov = KeyRateCovariance.assumedDefault();
    assertEquals(36.0, cov.get(0, 0), 1e-12);
    assertEquals(6.0 * 5.8 * Math.exp(-0.06 * 29.0), cov.get(0, 7), 1e-12);
    assertEquals(cov.get(3, 6), cov.get(6, 3), 0.0);
    assertThrows(IllegalArgumentException.class, () -> KeyRateCovariance.of(new double[3], 0.1));
  }

  static Dv01Vector ladder(final HedgeFixture f) {
    final Dv01Vector r = new Dv01Vector(CurrencyCode.USD);
    f.risk.ladder(CurrencyCode.USD, r);
    return r;
  }
}
