package io.ratesalgo.hedge;

import static io.ratesalgo.hedge.HedgeFixture.B10;
import static io.ratesalgo.hedge.HedgeFixture.B2;
import static io.ratesalgo.hedge.HedgeFixture.B30;
import static io.ratesalgo.hedge.HedgeFixture.M;
import static io.ratesalgo.hedge.HedgeFixture.RX;
import static io.ratesalgo.hedge.HedgeFixture.TY;
import static io.ratesalgo.hedge.HedgeFixture.US;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.api.CurrencyCode;
import io.ratesalgo.api.Dv01Vector;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

/** Review 2026-11-23, Q2: hedge level fully, then slope when it pays. */
class FactorHedgePlannerTest {

  private final HedgeFixture f = new HedgeFixture();
  private final FactorModel model = FactorModel.calibrated();
  private final TableHedgeCostModel costs = new TableHedgeCostModel(16);

  FactorHedgePlannerTest() {
    // one 1mm lot of cash costs half a 1/128 tick: 39.06 USD; a 100k TY contract 4 USD
    costs.set(B2, 39.0625, 0.0, 0.0).set(B10, 39.0625, 0.0, 0.0).set(B30, 39.0625, 0.0, 0.0);
    costs.set(TY, 3.81, 0.0, 0.0).set(US, 7.62, 0.0, 0.0).set(RX, 4.0, 0.0, 0.0);
  }

  private Dv01Vector position(final int id, final long minor) {
    f.risk.onFill(id, minor, f.fair.fairMid(id));
    final Dv01Vector r = new Dv01Vector(CurrencyCode.USD);
    f.risk.ladder(CurrencyCode.USD, r);
    return r;
  }

  private FactorHedgePlanner planner(final HedgeUniverse u, final double kappa, final HedgeAvailability a) {
    return new FactorHedgePlanner(u, f.risk, model, costs, kappa, a);
  }

  @Test
  void levelIsAlwaysHedgedFullyAndSlopeOnlyWhenItPays() {
    final Dv01Vector r = position(B30, 50 * M);
    final HedgeUniverse u = new HedgeUniverse().add(B2, M, 10_000).add(B10, M, 10_000);
    // nearly indifferent to risk: one crossing is cheaper than two, so level only
    final FactorHedgePlanner cheap = planner(u, 1e-12, HedgeAvailability.ALL);
    assertTrue(cheap.plan(r));
    assertTrue(cheap.levelHedged());
    assertFalse(cheap.slopeHedged());
    assertEquals(0.0, cheap.exposureAfter(FactorModel.LEVEL), 1e-6 * Math.abs(cheap.exposureBefore(FactorModel.LEVEL)));
    assertEquals(1, nonZero(cheap, u));
    // risk-averse: the second instrument removes slope as well
    final FactorHedgePlanner averse = planner(u, 1e-3, HedgeAvailability.ALL);
    assertTrue(averse.plan(r));
    assertTrue(averse.slopeHedged());
    assertEquals(2, nonZero(averse, u));
    assertEquals(0.0, averse.exposureAfter(FactorModel.LEVEL), 1e-6 * Math.abs(averse.exposureBefore(FactorModel.LEVEL)));
    assertEquals(0.0, averse.exposureAfter(FactorModel.SLOPE), 1e-6 * Math.abs(averse.exposureBefore(FactorModel.SLOPE)));
    assertEquals(0.0, averse.residualSd(FactorModel.SLOPE), 1e-3);
    assertTrue(averse.residualSd(FactorModel.CURVATURE) > 0.0, "curvature is reported, not hedged");
    assertTrue(averse.residualIdioSd() > 0.0);
    assertTrue(averse.objective() > 0.0);
    // the plan reduces the variance it was asked to reduce
    final double[] lots = new double[u.size()];
    final double[] a = new double[8];
    final double[] b = new double[8];
    r.copyTo(b);
    r.copyTo(a);
    for (int j = 0; j < u.size(); j++) {
      lots[j] = averse.lots(j);
      final int id = u.instrumentId(j);
      for (int k = 0; k < 8; k++) {
        a[k] += lots[j] * f.risk.unitPv01(id, k) * 1e6;
      }
    }
    // what is left is curvature, which a 2s10s pair against 30-year risk makes worse (about 8 % here), and idiosyncratic
    assertTrue(model.covariance().quadratic(a, a) < 0.12 * model.covariance().quadratic(b, b), "curvature and idiosyncratic terms only");
    assertEquals(costs.cost(B2, lots[0]) + costs.cost(B10, lots[1]), averse.plannedCost(), 1e-9);
  }

  @Test
  void aFutureWithNoBookIsNotUsed() {
    final Dv01Vector r = position(B10, 20 * M);
    final HedgeUniverse u = new HedgeUniverse().add(TY, HedgeFixture.CONTRACT, 50_000).add(B30, M, 10_000);
    final FactorHedgePlanner withFutures = planner(u, 1e-5, HedgeAvailability.ALL);
    withFutures.plan(r);
    assertTrue(withFutures.lots(0) != 0.0, "TY is the cheaper level hedge of 10-year risk");
    final FactorHedgePlanner cashOnly = planner(u, 1e-5, id -> id != TY);
    cashOnly.plan(r);
    assertEquals(0.0, cashOnly.lots(0));
    assertTrue(cashOnly.lots(1) < 0.0, "sells the 30-year against a long 10-year");
    assertTrue(cashOnly.levelHedged());
  }

  @Test
  void theFuturesBasisCountsAgainstAFuturesHedge() {
    final Dv01Vector r = position(B10, 100 * M);
    // TY is a little cheaper per DV01 than the 10-year note (3.9 against 4.1 USD per 100 DV01);
    // the basis variance, valued at kappa, outweighs that
    final TableHedgeCostModel same = new TableHedgeCostModel(4).set(TY, 3.9, 0.0, 0.0).set(B10, 41.0, 0.0, 0.0);
    final HedgeUniverse u = new HedgeUniverse().add(TY, HedgeFixture.CONTRACT, 50_000).add(B10, M, 10_000);
    final FactorHedgePlanner p = new FactorHedgePlanner(u, f.risk, model, same, 1e-5, HedgeAvailability.ALL);
    p.plan(r);
    assertEquals(0.0, p.lots(0));
    assertEquals(-100.0, p.lots(1), 1e-9);
    final FactorModel noBasis =
        FactorModel.of(nodes(), new double[] {13.27695, 4.46964, 1.51051}, new double[7], 0.7, 0.0);
    final FactorHedgePlanner q = new FactorHedgePlanner(u, f.risk, noBasis, same, 1e-5, HedgeAvailability.ALL);
    q.plan(r);
    assertTrue(q.lots(0) < 0.0, "without a basis the cheaper future wins");
    assertEquals(0.0, q.lots(1));
  }

  @Test
  void otherCurrenciesAndUncostedInstrumentsAreSkipped() {
    final Dv01Vector r = position(B10, 10 * M);
    final HedgeUniverse u = new HedgeUniverse().add(RX, HedgeFixture.CONTRACT, 5_000).add(US, HedgeFixture.CONTRACT, 5_000)
        .add(B2, M, 1_000).add(HedgeFixture.S10, M, 1_000);
    final FactorHedgePlanner p = planner(u, 1e-9, HedgeAvailability.ALL);
    assertTrue(p.plan(r));
    assertEquals(0.0, p.lots(0), "EUR future");
    assertEquals(0.0, p.lots(3), "no cost entry: never traded");
    assertTrue(p.lots(1) != 0.0 || p.lots(2) != 0.0);
    assertEquals(model.covariance(), p.covariance());
    assertEquals(model, p.model());
    assertEquals(u, p.universe());
  }

  @Test
  void withinNoLotLimitTheCheapestLevelHedgeIsTradedToItsLimit() {
    final Dv01Vector r = position(B10, 100 * M);
    final HedgeUniverse u = new HedgeUniverse().add(B2, M, 10).add(B30, M, 5);
    final FactorHedgePlanner p = planner(u, 1e-6, HedgeAvailability.ALL);
    assertFalse(p.plan(r));
    assertFalse(p.converged());
    assertFalse(p.levelHedged());
    assertEquals(1, nonZero(p, u));
    final int j = p.lots(0) != 0.0 ? 0 : 1;
    assertEquals(u.maxLots(j), Math.abs(p.lots(j)), 1e-12);
  }

  @Test
  void nothingToHedgeWithLeavesThePlanEmpty() {
    final Dv01Vector r = position(B10, 10 * M);
    final HedgeUniverse u = new HedgeUniverse().add(RX, HedgeFixture.CONTRACT, 5_000);
    final FactorHedgePlanner p = planner(u, 1e-6, null);
    assertFalse(p.plan(r));
    assertEquals(0.0, p.lots(0));
    assertEquals(0.0, p.plannedCost());
    assertEquals(0.5 * 1e-6 * model.covariance().quadratic(ladder(r), ladder(r)), p.objective(), 1e-9);
    assertThrows(IllegalArgumentException.class, () -> planner(u, 0.0, null));
  }

  @Test
  void theHedgerTradesTheFactorPlan() {
    final HedgeUniverse u = new HedgeUniverse().add(B2, M, 10_000).add(B10, M, 10_000);
    final FactorHedgePlanner p = planner(u, 1e-9, HedgeAvailability.ALL);
    final Hedger h =
        new Hedger(CurrencyCode.USD, p, f.risk, f.sink, f.fair, new HedgeAnalytics(16, BasisAttribution.NONE), f.clock,
            Hedger.config().triggerStdDev(1_000.0).firstTranche(1.0));
    f.risk.onFill(B30, 20 * M, f.fair.fairMid(B30));
    assertEquals(1, h.onTimer(f.clock.epochNanos()));
    assertEquals(B10, f.sink.parents().get(0).instrumentId());
    assertEquals(model.covariance(), p.covariance());
  }

  @Property(tries = 60)
  void everyPlanZeroesLevelWhenTheLimitsAllowIt(
      @ForAll @IntRange(min = -400, max = 400) final int b2, @ForAll @IntRange(min = -400, max = 400) final int b30) {
    final HedgeFixture g = new HedgeFixture();
    g.risk.onFill(B2, b2 * M, g.fair.fairMid(B2));
    g.risk.onFill(B30, b30 * M, g.fair.fairMid(B30));
    final Dv01Vector r = new Dv01Vector(CurrencyCode.USD);
    g.risk.ladder(CurrencyCode.USD, r);
    final HedgeUniverse u = new HedgeUniverse().add(B2, M, 100_000).add(B10, M, 100_000);
    final FactorHedgePlanner p = new FactorHedgePlanner(u, g.risk, model, costs, 1e-6, HedgeAvailability.ALL);
    assertTrue(p.plan(r));
    assertEquals(0.0, p.exposureAfter(FactorModel.LEVEL), 1e-6 * (1.0 + Math.abs(p.exposureBefore(FactorModel.LEVEL))));
  }

  private static double[] ladder(final Dv01Vector v) {
    final double[] a = new double[8];
    v.copyTo(a);
    return a;
  }

  private static int nonZero(final FactorHedgePlanner p, final HedgeUniverse u) {
    int n = 0;
    for (int j = 0; j < u.size(); j++) {
      if (p.lots(j) != 0.0) {
        n++;
      }
    }
    return n;
  }

  private static double[][] nodes() {
    return new double[][] {
      {0.3383, -0.5378, 0.6533},
      {0.3785, -0.4176, -0.0498},
      {0.4097, -0.1897, -0.4024},
      {0.4177, 0.0127, -0.4127},
      {0.3945, 0.1986, -0.1959},
      {0.3583, 0.445, 0.2255},
      {0.3406, 0.5125, 0.3864}
    };
  }
}
