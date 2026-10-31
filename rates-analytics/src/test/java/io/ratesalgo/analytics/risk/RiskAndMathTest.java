package io.ratesalgo.analytics.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.analytics.curve.CurveBuilder;
import io.ratesalgo.analytics.curve.DiscountCurve;
import io.ratesalgo.analytics.curve.HullWhite;
import io.ratesalgo.analytics.curve.OisFutureInstrument;
import io.ratesalgo.analytics.date.Calendars;
import io.ratesalgo.analytics.date.Dates;
import io.ratesalgo.analytics.date.DayCount;
import io.ratesalgo.analytics.golden.Golden;
import io.ratesalgo.analytics.math.Brent;
import io.ratesalgo.analytics.math.LevenbergMarquardt;
import io.ratesalgo.analytics.math.LuSolver;
import io.ratesalgo.analytics.swap.FixingHistory;
import io.ratesalgo.analytics.swap.OisSwap;
import io.ratesalgo.analytics.swap.OisSwapPricer;
import io.ratesalgo.analytics.swap.SwapConvention;
import java.util.List;
import org.junit.jupiter.api.Test;

class RiskAndMathTest {

  @Test
  void ladderIsAnImmutableValue() {
    final Dv01Ladder a = new Dv01Ladder("USD", List.of("2Y", "10Y"), new double[] {10, 90},
        100);
    final Dv01Ladder b = new Dv01Ladder("USD", List.of("2Y", "10Y"), new double[] {10, 90},
        100);
    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
    assertEquals(a, a);
    assertNotEquals(a, null);
    assertNotEquals(a, a.scaled(2));
    assertEquals(200, a.scaled(2).parallel(), 0.0);
    assertEquals(20, a.plus(b).dv01(0), 0.0);
    assertEquals(100, a.keyRateSum(), 0.0);
    assertEquals("USD", a.currency());
    assertEquals(2, a.size());
    assertTrue(a.toString().contains("10Y=90.0"));
    final double[] kr = a.keyRates();
    kr[0] = 1e9;
    assertEquals(10, a.dv01(0), 0.0, "defensive copy");
    assertThrows(IllegalArgumentException.class,
        () -> new Dv01Ladder("USD", List.of("2Y"), new double[] {1, 2}, 3));
    assertThrows(IllegalArgumentException.class,
        () -> new Dv01Ladder("USD", List.of("2Y"), new double[] {Double.NaN}, 3));
    assertThrows(IllegalArgumentException.class,
        () -> new Dv01Ladder("USD", List.of("2Y"), new double[] {1}, Double.NaN));
    assertThrows(IllegalArgumentException.class,
        () -> a.plus(new Dv01Ladder("EUR", List.of("2Y", "10Y"), new double[] {1, 2}, 3)));
  }

  @Test
  void bumpedLadderOnAFuturesCurveUsesPriceUnits() {
    final CurveBuilder b = new CurveBuilder(Golden.today(), Golden.instruments("USD-OIS"),
        io.ratesalgo.analytics.curve.Interpolation.LINEAR_LOG_DF, 0, null, Golden.fixings("USD"));
    b.build(CurveBuilder.Method.AUTO);
    final OisSwapPricer pricer = new OisSwapPricer();
    final int today = Golden.today();
    final OisSwap s = OisSwap.imm(SwapConvention.USD_SOFR, today, 24, 1e8, 0.04, true);
    final FixingHistory fx = Golden.fixings("USD");
    final double[] g = new double[b.curve().pillarCount()];
    pricer.pvGradient(s, b.curve(), b.curve(), fx, today, g, g);
    final Dv01Ladder ad = KeyRateRisk.fromGradient(b, "USD", g);
    final Dv01Ladder bump =
        KeyRateRisk.byBumping(b, "USD", () -> pricer.pv(s, b.curve(), fx, today), KeyRateRisk.BP);
    for (int i = 0; i < ad.size(); i++) {
      assertEquals(ad.dv01(i), bump.dv01(i), 1e-4 * Math.abs(ad.parallel()),
          ad.tenors().get(i));
    }
    assertTrue(ad.parallel() > 0, "payer gains when rates rise");
  }

  @Test
  void parRateGradientMatchesFiniteDifferences() {
    final DiscountCurve c = Golden.quantLibCurve("EUR-OIS").copy();
    final OisSwap s = OisSwap.forwardStart(SwapConvention.EUR_ESTR, Golden.today(), 12, 60, 1e8,
        0.02, false);
    final OisSwapPricer pricer = new OisSwapPricer();
    final FixingHistory fx = Golden.fixings("EUR");
    final double[] g = new double[c.pillarCount()];
    pricer.parRateGradient(s, c, fx, Golden.today(), g, new double[c.pillarCount()]);
    final double h = 1e-7;
    for (int i = 0; i < c.pillarCount(); i++) {
      final double y = c.pillarLogDf(i);
      c.setPillarLogDf(i, y + h);
      c.update();
      final double up = pricer.parRate(s, c, fx, Golden.today());
      c.setPillarLogDf(i, y - h);
      c.update();
      final double dn = pricer.parRate(s, c, fx, Golden.today());
      c.setPillarLogDf(i, y);
      c.update();
      assertEquals((up - dn) / (2 * h), g[i], 1e-6, "pillar " + i);
    }
    assertTrue(pricer.pv(s, c, fx, Golden.today()) != 0.0);
    assertEquals(5, s.fixedPeriodCount());
    assertEquals(3, s.fixedPeriod(0).length);
    assertEquals(1e8, s.notional(), 0.0);
    assertTrue(s.withFixedRate(0.03).fixedRate() == 0.03 && !s.isPayer());
  }

  @Test
  void startedFuturesUsePublishedFixings() {
    // 1 January is a holiday, so the month's first day takes the 31 December fixing
    final int[] dates = {Dates.of(2024, 12, 31), Dates.of(2025, 1, 2), Dates.of(2025, 1, 3),
        Dates.of(2025, 1, 6)};
    final double[] rates = {0.0433, 0.0430, 0.0431, 0.0432};
    final FixingHistory fx = new FixingHistory(dates, rates);
    final int today = Dates.of(2025, 1, 6);
    final DiscountCurve flat = DiscountCurve.fromDiscountFactors(today,
        new int[] {Dates.of(2026, 1, 6)}, new double[] {Math.exp(-0.043)},
        io.ratesalgo.analytics.curve.Interpolation.LINEAR_LOG_DF, 0);
    final OisFutureInstrument jan = OisFutureInstrument.oneMonth("SR1F5", 2025, 1,
        Calendars.US_SOFR, DayCount.ACT_360, 0.0, 95.7);
    final double r = jan.rate(flat, fx, today);
    assertTrue(r > 0.042 && r < 0.045, "rate " + r);
    final OisFutureInstrument dec = OisFutureInstrument.threeMonth("SR3Z4", 2024, 12,
        Calendars.US_SOFR, DayCount.ACT_360, 0.0, 95.7);
    final int[] d2 = new int[25];
    final double[] r2 = new double[25];
    int n = 0;
    for (int d = Dates.of(2024, 12, 18); d < today; d++) {
      if (Calendars.US_SOFR.isBusinessDay(d)) {
        d2[n] = d;
        r2[n] = 0.0435;
        n++;
      }
    }
    d2[n] = today;
    r2[n] = 0.0432;
    n++;
    final FixingHistory started = new FixingHistory(java.util.Arrays.copyOf(d2, n),
        java.util.Arrays.copyOf(r2, n));
    final double rate = dec.rate(flat, started, today);
    assertTrue(rate > 0.042 && rate < 0.0445, "rate " + rate);
    assertEquals(-100.0, dec.quotePerRate(), 0.0);
    assertEquals(dec.maturityDate(), dec.pillarDate());
    assertTrue(dec.valueDate() < dec.maturityDate());
    assertEquals(0.0, dec.convexity(), 0.0);
    assertTrue(fx.size() == 4 && fx.date(0) == dates[0] && fx.rateAt(3) == 0.0432);
    assertThrows(IllegalStateException.class, () -> fx.rate(Dates.of(2025, 1, 7)));
    assertThrows(IllegalArgumentException.class,
        () -> new FixingHistory(new int[] {2, 1}, new double[] {0, 0}));
    assertThrows(IllegalArgumentException.class,
        () -> new FixingHistory(new int[] {1}, new double[] {0, 0}));
    assertTrue(HullWhite.futuresConvexity(0.03, 0.0085, 1, 1.25) > 0);
  }

  @Test
  void solvers() {
    assertEquals(Math.sqrt(2), Brent.solve(x -> x * x - 2, 0, 1, 1e-15, 100), 1e-14);
    assertEquals(0.0, Brent.solve(x -> x, 0, 1, 1e-15, 100), 0.0);
    assertEquals(1.0, Brent.solve(x -> x - 1, 0, 1, 1e-15, 100), 0.0);
    assertThrows(IllegalStateException.class, () -> Brent.solve(x -> x * x + 1, -1, 1, 1e-15,
        100));
    assertThrows(IllegalStateException.class, () -> Brent.solve(Math::sin, 3, 3.2, 1e-300, 1));

    final LuSolver lu = new LuSolver(3);
    final double[] a = {0, 2, 1, 1, 1, 0, 3, 0, 1};
    lu.factor(a, 3);
    final double[] b = {3, 2, 4};
    lu.solve(b);
    assertEquals(1.0, b[0], 1e-14);
    assertEquals(1.0, b[1], 1e-14);
    assertEquals(1.0, b[2], 1e-14);
    final double[] bt = {4, 3, 2};
    lu.solveTransposed(bt);
    // A^T x = (4, 3, 2) has the solution x = (1, 1, 1) since the column sums are 4, 3, 2
    assertEquals(1.0, bt[0], 1e-14);
    assertEquals(1.0, bt[1], 1e-14);
    assertEquals(1.0, bt[2], 1e-14);
    assertThrows(IllegalStateException.class, () -> lu.factor(new double[9], 3));
    assertThrows(IllegalArgumentException.class, () -> lu.factor(new double[16], 4));

    final LevenbergMarquardt lm = new LevenbergMarquardt(3, 2);
    final LevenbergMarquardt.Problem line = new LevenbergMarquardt.Problem() {
      @Override
      public int residualCount() {
        return 3;
      }

      @Override
      public int parameterCount() {
        return 2;
      }

      @Override
      public void residuals(final double[] x, final double[] r) {
        for (int i = 0; i < 3; i++) {
          r[i] = x[0] + x[1] * i - (1 + 2 * i);
        }
      }

      @Override
      public void jacobian(final double[] x, final double[] j) {
        for (int i = 0; i < 3; i++) {
          j[i * 2] = 1;
          j[i * 2 + 1] = i;
        }
      }
    };
    final double[] x = {0, 0};
    assertTrue(lm.minimise(line, x, 1e-14, 0, 50) <= 1e-14);
    assertEquals(1.0, x[0], 1e-12);
    assertEquals(2.0, x[1], 1e-12);
    assertTrue(lm.iterations() >= 0);
    assertThrows(IllegalArgumentException.class,
        () -> new LevenbergMarquardt(1, 2).minimise(line, new double[2], 1e-14, 0, 5));
  }
}
