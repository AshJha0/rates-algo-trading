package io.ratesalgo.analytics.curve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.analytics.date.Dates;
import io.ratesalgo.analytics.date.DayCount;
import io.ratesalgo.analytics.golden.Golden;
import io.ratesalgo.analytics.swap.FixingHistory;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Bootstrap round trips and the incremental rebuild check. Solver tolerance: {@link
 * CurveBuilder#SOLVER_TOLERANCE} = 1e-12 on |implied - quote|. Asserted: every instrument
 * reprices within 1e-10; an incremental rebuild after one quote change equals a full rebuild from
 * scratch to 1e-10 on discount factors.
 */
class CurveBuilderTest {

  private static final double SOLVER_TOLERANCE_STATED = 1e-12;

  static CurveBuilder usd(final Interpolation interpolation) {
    return new CurveBuilder(Golden.today(), Golden.instruments("USD-OIS"), interpolation, 2.0,
        null, Golden.fixings("USD"));
  }

  @ParameterizedTest
  @EnumSource(Interpolation.class)
  void everyInterpolationRepricesEveryInstrument(final Interpolation interpolation) {
    final CurveBuilder b = usd(interpolation);
    final CurveBuilder.Method m = b.build(CurveBuilder.Method.AUTO);
    assertEquals(interpolation.isLocal() ? CurveBuilder.Method.SEQUENTIAL
        : CurveBuilder.Method.GLOBAL, m);
    final double err = b.maxRepricingError();
    System.out.println("ROUNDTRIP " + interpolation + " max error " + err);
    assertTrue(err <= 1e-10, interpolation + " repricing error " + err);
    assertEquals(SOLVER_TOLERANCE_STATED, CurveBuilder.SOLVER_TOLERANCE);
  }

  @ParameterizedTest
  @EnumSource(Interpolation.class)
  void incrementalRebuildEqualsFullRebuild(final Interpolation interpolation) {
    final CurveBuilder incremental = usd(interpolation);
    incremental.build(CurveBuilder.Method.AUTO);
    final List<CurveInstrument> inst = incremental.instruments();
    int changed = -1;
    for (int i = 0; i < inst.size(); i++) {
      if (inst.get(i).tenor().equals("10Y")) {
        changed = i;
      }
    }
    final double newQuote = inst.get(changed).quote() + 0.0007;
    incremental.rebuild(changed, newQuote);

    final CurveBuilder full = usd(interpolation);
    full.instruments().get(changed).setQuote(newQuote);
    full.build(CurveBuilder.Method.AUTO);
    double max = 0.0;
    final DiscountCurve a = incremental.curve();
    final DiscountCurve b = full.curve();
    for (int d = Golden.today(); d <= a.pillarDate(a.pillarCount() - 1); d += 7) {
      max = Math.max(max, Math.abs(a.df(d) - b.df(d)));
    }
    System.out.println("INCREMENTAL " + interpolation + " max |DF incremental - full| " + max);
    assertTrue(max <= 1e-10, "incremental differs by " + max);
  }

  /**
   * A failed solve must leave the builder as it was (review 2026-11-23, A8). Before the fix the bad
   * quote stayed on the instrument and the pillars after it were left wherever Brent stopped.
   */
  @ParameterizedTest
  @EnumSource(Interpolation.class)
  void aFailedRebuildLeavesThePreviousCurveAndQuotes(final Interpolation interpolation) {
    final CurveBuilder b = usd(interpolation);
    b.build(CurveBuilder.Method.AUTO);
    final int n = b.curve().pillarCount();
    final int changed = n / 2;
    final double[] before = new double[n];
    final double[] quotes = new double[n];
    for (int i = 0; i < n; i++) {
      before[i] = b.curve().pillarLogDf(i);
      quotes[i] = b.instruments().get(i).quote();
    }
    final int probe = b.curve().pillarDate(n - 1) - 400;
    final double dfBefore = b.curve().df(probe);
    // not a number: no bracket has a root and the residual check cannot pass
    for (final double bad : new double[] {Double.NaN, Double.POSITIVE_INFINITY}) {
      assertThrows(IllegalStateException.class, () -> b.rebuild(changed, bad), "quote " + bad);
      for (int i = 0; i < n; i++) {
        // restored from a saved copy, so equal to the bit
        assertEquals(before[i], b.curve().pillarLogDf(i), 0.0, "pillar " + i);
        assertEquals(quotes[i], b.instruments().get(i).quote(), 0.0, "quote " + i);
      }
      assertEquals(dfBefore, b.curve().df(probe), 0.0, "interpolated values are refreshed too");
      assertTrue(b.maxRepricingError() <= CurveBuilder.SOLVER_TOLERANCE);
    }
    // and the builder still works: the next good quote gives the same curve as a fresh build
    final double good = quotes[changed] + 0.0003;
    b.rebuild(changed, good);
    final CurveBuilder fresh = usd(interpolation);
    fresh.instruments().get(changed).setQuote(good);
    fresh.build(CurveBuilder.Method.AUTO);
    for (int i = 0; i < n; i++) {
      // same bound as incrementalRebuildEqualsFullRebuild: two solves to 1e-12 in the quotes
      assertEquals(fresh.curve().pillarLogDf(i), b.curve().pillarLogDf(i), 1e-10, "pillar " + i);
    }
  }

  @Test
  void globalAndSequentialAgreeOnLocalInterpolation() {
    final CurveBuilder seq = usd(Interpolation.LINEAR_LOG_DF);
    seq.build(CurveBuilder.Method.SEQUENTIAL);
    final CurveBuilder glob = usd(Interpolation.LINEAR_LOG_DF);
    glob.build(CurveBuilder.Method.GLOBAL);
    for (int i = 0; i < seq.curve().pillarCount(); i++) {
      assertEquals(seq.curve().pillarLogDf(i), glob.curve().pillarLogDf(i), 1e-12);
    }
    assertFalse(seq.instrumentsOverlap());
    assertEquals(seq.curve().pillarCount() * seq.curve().pillarCount(), seq.jacobian().length);
  }

  @ParameterizedTest
  @EnumSource(Interpolation.class)
  void logDiscountGradientMatchesCentralDifferences(final Interpolation interpolation) {
    final Random rnd = new Random(42);
    final int today = Dates.of(2025, 1, 15);
    final int[] pillars = {today + 1, today + 40, today + 200, today + 700, today + 1900,
        today + 3700, today + 7400, today + 11_000};
    final double[] dfs = new double[pillars.length];
    for (int i = 0; i < pillars.length; i++) {
      final double t = (pillars[i] - today) / 365.0;
      dfs[i] = Math.exp(-(0.03 + 0.01 * rnd.nextGaussian() * 0.5) * t);
    }
    final DiscountCurve c =
        DiscountCurve.fromDiscountFactors(today, pillars, dfs, interpolation, 1.5);
    final double[] g = new double[pillars.length];
    final double h = 1e-9;
    double max = 0.0;
    for (int d = today + 1; d <= today + 12_000; d += 37) {
      c.logDfGradient(d, g);
      for (int i = 0; i < pillars.length; i++) {
        final double y = c.pillarLogDf(i);
        c.setPillarLogDf(i, y + h);
        c.update();
        final double up = Math.log(c.df(d));
        c.setPillarLogDf(i, y - h);
        c.update();
        final double dn = Math.log(c.df(d));
        c.setPillarLogDf(i, y);
        c.update();
        final double fd = (up - dn) / (2 * h);
        max = Math.max(max, Math.abs(fd - g[i]));
        assertEquals(fd, g[i], 1e-6, interpolation + " pillar " + i + " date " + d);
      }
    }
    System.out.println("GRADIENT " + interpolation + " max |AD - FD| " + max);
  }

  @Test
  void turnAddsItsSpreadOverItsWindowOnly() {
    final int today = Dates.of(2025, 1, 15);
    final int[] pillars = {Dates.of(2026, 1, 15)};
    final DiscountCurve c = DiscountCurve.fromDiscountFactors(today, pillars, new double[] {0.96},
        Interpolation.LINEAR_LOG_DF, 0.0);
    final DiscountCurve t = c.copy();
    t.addTurn(Dates.of(2025, 12, 31), Dates.of(2026, 1, 2), 0.0050);
    assertEquals(c.df(Dates.of(2025, 12, 31)), t.df(Dates.of(2025, 12, 31)), 1e-16);
    final double ratio = t.df(Dates.of(2026, 1, 2)) / c.df(Dates.of(2026, 1, 2));
    assertEquals(Math.exp(-0.0050 * 2 / 360.0), ratio, 1e-15);
    assertEquals(ratio, t.df(Dates.of(2026, 1, 10)) / c.df(Dates.of(2026, 1, 10)), 1e-15);
    assertThrows(IllegalArgumentException.class, () -> t.addTurn(today + 5, today + 5, 0.01));
    assertTrue(t.zeroRate(Dates.of(2026, 1, 15)) > c.zeroRate(Dates.of(2026, 1, 15)));
    assertThrows(IllegalArgumentException.class, () -> c.zeroRate(today));
    assertEquals(0.96, c.dfAtTime(1.0 * (pillars[0] - today) / 365.0), 1e-15);
    assertEquals((1 / 0.96 - 1) / 1.0, c.simpleForward(today, pillars[0], 1.0), 1e-14);
  }

  @Test
  void projectionCurveOnSeparateDiscountingAndErrors() {
    final CurveBuilder eur6m = Golden.javaBuilder("EUR-6M");
    assertTrue(eur6m.maxRepricingError() <= 1e-10);
    assertFalse(eur6m.context().singleCurve());
    final CurveInstrument term = eur6m.instruments().get(0);
    final CurveContext single =
        new CurveContext(eur6m.curve(), eur6m.curve(), FixingHistory.EMPTY, Golden.today());
    assertThrows(UnsupportedOperationException.class,
        () -> term.impliedQuoteGradient(single, new double[20]));
    final CurveInstrument ois = Golden.javaBuilder("EUR-OIS").instruments().get(5);
    final CurveContext dual = new CurveContext(eur6m.curve(),
        Golden.javaBuilder("EUR-OIS").curve(), FixingHistory.EMPTY, Golden.today());
    assertThrows(UnsupportedOperationException.class,
        () -> ois.impliedQuoteGradient(dual, new double[40]));
    final int today = Golden.today();
    final PeriodRateInstrument a =
        new PeriodRateInstrument("a", "A", today, today + 10, DayCount.ACT_360, 0.01);
    final PeriodRateInstrument b2 =
        new PeriodRateInstrument("b", "B", today + 2, today + 10, DayCount.ACT_360, 0.01);
    assertThrows(IllegalArgumentException.class,
        () -> new CurveBuilder(today, List.of(a, b2), Interpolation.LINEAR_LOG_DF, 0, null,
            FixingHistory.EMPTY));
    assertThrows(IllegalArgumentException.class,
        () -> new PeriodRateInstrument("c", "C", today + 3, today + 3, DayCount.ACT_360, 0.01));
    assertEquals(today, a.start());
    assertEquals(1.0, a.quotePerRate());
    assertThrows(IllegalArgumentException.class,
        () -> new DiscountCurve(today, new int[0], Interpolation.LINEAR_LOG_DF, 0));
    assertThrows(IllegalArgumentException.class,
        () -> new DiscountCurve(today, new int[] {today}, Interpolation.LINEAR_LOG_DF, 0));
    assertThrows(IllegalArgumentException.class,
        () -> DiscountCurve.fromDiscountFactors(today, new int[] {today + 5}, new double[] {-1},
            Interpolation.LINEAR_LOG_DF, 0));
    assertThrows(IllegalArgumentException.class,
        () -> new TensionSplineInterpolator(4, 0.0));
    assertThrows(IllegalArgumentException.class, () -> HullWhite.futuresConvexity(0.03, 0.01, 1,
        1));
    assertEquals(0.5 * 0.01 * 0.01 * 1.0 * 1.25, HullWhite.futuresConvexity(0.0, 0.01, 1.0,
        1.25), 1e-18);
  }
}
