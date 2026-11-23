package io.ratesalgo.analytics.curve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.analytics.date.Dates;
import io.ratesalgo.analytics.swap.FixingHistory;
import io.ratesalgo.analytics.swap.OisSwap;
import io.ratesalgo.analytics.swap.OisSwapPricer;
import io.ratesalgo.analytics.swap.SwapConvention;
import org.junit.jupiter.api.Test;

/**
 * The SOFR curve of pricing v2 on its reference date, 2 November 2026, at the declared market of
 * {@link SofrMarket}. Each test names the tolerance it asserts and why.
 */
class SofrCurveTest {

  private static final double BP = 1e-4;
  private static final int TODAY = SofrMarket.TODAY;

  private final OisSwapPricer pricer = new OisSwapPricer();

  @Test
  void theBuildSetOnTheReferenceDate() {
    final SofrCurve c = SofrMarket.standard();
    final String[] labels = new String[c.inputCount()];
    final String[] pillars = new String[c.inputCount()];
    for (int i = 0; i < labels.length; i++) {
      labels[i] = c.label(i);
      pillars[i] = Dates.format(c.pillarDate(i));
    }
    // overnight to the December step, the December and February one-month contracts for the
    // steps after the December and January meetings, three quarterly contracts, eight swaps
    assertEquals(
        "[SOFR ON, SR1 2026-12, SR1 2027-02, SR3 2026-12, SR3 2027-03, SR3 2027-06,"
            + " USD-SOFR-OIS 1Y, USD-SOFR-OIS 2Y, USD-SOFR-OIS 3Y, USD-SOFR-OIS 5Y,"
            + " USD-SOFR-OIS 7Y, USD-SOFR-OIS 10Y, USD-SOFR-OIS 20Y, USD-SOFR-OIS 30Y]",
        java.util.Arrays.toString(labels));
    assertEquals("2026-12-10", pillars[0], "effective date of the December meeting");
    assertEquals("2027-01-28", pillars[1], "effective date of the January meeting");
    assertEquals("2027-03-01", pillars[2], "end of the February contract");
    assertEquals("2027-03-17", pillars[3]);
    assertEquals("2027-09-15", pillars[5]);
    assertEquals(3, c.stepPillars());
    assertEquals(SofrCurve.Kind.OVERNIGHT, c.kind(0));
    assertEquals(SofrCurve.Kind.SR1, c.kind(1));
    assertEquals(SofrCurve.Kind.SR3, c.kind(3));
    assertEquals(SofrCurve.Kind.SWAP, c.kind(6));
    assertEquals(202612, c.contractMonth(1));
    assertEquals(202706, c.contractMonth(5));
    assertEquals(0, c.contractMonth(6));
    assertEquals(84, c.tenorMonths(c.swapInput(84)));
    assertEquals(-1, c.swapInput(144), "the 12-year swap is quoted, not a build input");
    assertEquals(8, c.riskTenorCount());
    assertEquals(TODAY, c.valuationDate());
  }

  @Test
  void everyInputRepricesAndDiscountFactorsArePositiveAndFalling() {
    final SofrCurve c = SofrMarket.standard();
    // the solver stops at 1e-12 in quote units; 1e-10 is the round-trip tolerance of the module
    assertTrue(c.builder().maxRepricingError() <= 1e-10, "" + c.builder().maxRepricingError());
    double previous = 1.0;
    for (int d = TODAY + 1; d < TODAY + 31 * 365; d += 13) {
      final double df = c.curve().df(d);
      assertTrue(df > 0.0 && df < previous, "df at " + Dates.format(d) + " = " + df);
      previous = df;
    }
  }

  @Test
  void theFrontEndIsAStepFunctionOfTheMeetingDates() {
    final SofrCurve c = SofrMarket.standard();
    final int december = Dates.of(2026, 12, 10);
    final int january = Dates.of(2027, 1, 28);
    final double first = c.overnightForward(TODAY);
    final double second = c.overnightForward(december);
    // flat between policy dates: linear log discount factors give the same daily forward
    for (int d = TODAY; d < december; d++) {
      assertEquals(first, c.overnightForward(d), 1e-12, Dates.format(d));
    }
    for (int d = december; d < january; d++) {
      assertEquals(second, c.overnightForward(d), 1e-12, Dates.format(d));
    }
    // the overnight input is 3.90 percent and the December contract prices an easing
    assertEquals(0.0390, first, 1e-9);
    assertTrue(first - second > 20 * BP, "step at the December meeting: " + (first - second));
  }

  /**
   * The evidence for the interpolation choice (docs/research/PRICING_V2.md Section 3): the same
   * build set and quotes under three schemes for the section after the meeting steps. Four
   * measures, each asserted, and the lines printed here are the table of the document.
   *
   * <ol>
   *   <li>Forward behaviour: the jump of the overnight forward across the swap pillars.
   *   <li>Off-curve pricing: the error on swaps that are not inputs (4, 6, 8, 12, 15, 25 years)
   *       against the declared market, a smooth function of tenor.
   *   <li>Risk stability: the ladder by algorithmic differentiation against a 1 bp central bump.
   *   <li>Leave-one-out linearity: first-order value against a rebuild after a 10 bp move.
   * </ol>
   *
   * Linear log discount factors fail the first two (step forwards, 1.3 bp off the curve).
   * Monotone convex has continuous forwards but fails the last two: its four regions switch
   * within a basis point of quote, so a 1 bp bump does not reproduce the derivative and a
   * linearised value drifts. The tension spline is linear in its node values and passes all
   * four, so it is the default; monotone convex stays selectable.
   */
  @Test
  void interpolationEvidence() {
    final Interpolation[] schemes = {
      Interpolation.LINEAR_LOG_DF, Interpolation.STEP_THEN_MONOTONE_CONVEX,
      Interpolation.STEP_THEN_TENSION_SPLINE
    };
    final double[] jump = new double[3];
    final double[] offCurve = new double[3];
    final double[] adVsBump = new double[3];
    final double[] linearisation = new double[3];
    final SofrCurve[] curves = new SofrCurve[3];
    for (int s = 0; s < 3; s++) {
      final SofrCurve c = SofrMarket.build(TODAY, SofrCurveSpec.usd().withInterpolation(schemes[s]));
      curves[s] = c;
      assertTrue(c.builder().maxRepricingError() <= 1e-10, schemes[s].toString());
      for (int i = c.swapInput(24); i < c.inputCount() - 1; i++) {
        final int p = c.pillarDate(i);
        jump[s] = Math.max(jump[s], Math.abs(c.overnightForward(p + 1) - c.overnightForward(p - 2)));
      }
      for (final int years : new int[] {4, 6, 8, 12, 15, 25}) {
        final OisSwap swap = OisSwap.spot(SwapConvention.USD_SOFR, TODAY, 12 * years, 1.0, 0.0, true);
        offCurve[s] = Math.max(offCurve[s], Math.abs(c.parRate(swap) - SofrMarket.swapRate(years)));
      }
      adVsBump[s] = ladderAgainstBump(c);
      linearisation[s] = leaveOneOutError(c);
      System.out.printf(
          "INTERPOLATION %s: forward jump %.3f bp, off-curve %.3f bp, AD against 1 bp bump %.2e of"
              + " parallel, leave-one-out linearisation %.4f bp%n",
          schemes[s], jump[s] / BP, offCurve[s] / BP, adVsBump[s], linearisation[s] / BP);
    }
    System.out.println("FORWARDS date, log-linear, monotone convex, tension spline (percent)");
    for (int y = 0; y <= 30; y += y < 3 ? 1 : y < 10 ? 2 : 5) {
      final int d = TODAY + 14 + 365 * y;
      System.out.printf(
          "FORWARDS %s %.4f %.4f %.4f%n", Dates.format(d), curves[0].overnightForward(d) * 100,
          curves[1].overnightForward(d) * 100, curves[2].overnightForward(d) * 100);
    }
    // 1. log-linear steps by tens of basis points at a pillar; the other two are continuous, and
    // three days apart their forwards differ by the slope only
    assertTrue(jump[0] > 10 * BP, "log-linear jump " + jump[0]);
    assertTrue(jump[1] < 1 * BP && jump[2] < 1 * BP, jump[1] + " " + jump[2]);
    // 2. off the curve the spline is nearest the declared market, log-linear furthest
    assertTrue(offCurve[0] > 1 * BP, "log-linear off-curve " + offCurve[0]);
    assertTrue(offCurve[2] < 0.25 * BP, "spline off-curve " + offCurve[2]);
    assertTrue(offCurve[2] < offCurve[1] && offCurve[1] < offCurve[0]);
    // 3. the module's tolerance for AD against a bump is 1e-4 of parallel; monotone convex
    // misses it by two orders of magnitude on this position, the other two meet it with room
    assertTrue(adVsBump[1] > 1e-3, "monotone convex AD against bump " + adVsBump[1]);
    assertTrue(adVsBump[0] < 1e-6 && adVsBump[2] < 1e-6, adVsBump[0] + " " + adVsBump[2]);
    // 4. a tenth of the desk's half-spread is 0.015 bp
    assertTrue(linearisation[1] > 0.1 * BP, "monotone convex " + linearisation[1]);
    assertTrue(linearisation[0] < 0.01 * BP && linearisation[2] < 0.01 * BP);
    // and the forwards of the smooth scheme stay inside the range the log-linear steps span,
    // give or take 10 bp: no ringing on this market
    double low = 1.0;
    double high = 0.0;
    double stepLow = 1.0;
    double stepHigh = 0.0;
    for (int d = TODAY + 400; d < TODAY + 30 * 365; d += 30) {
      low = Math.min(low, curves[2].overnightForward(d));
      high = Math.max(high, curves[2].overnightForward(d));
      stepLow = Math.min(stepLow, curves[0].overnightForward(d));
      stepHigh = Math.max(stepHigh, curves[0].overnightForward(d));
    }
    System.out.printf(
        "FORWARDS range beyond one year: log-linear %.2f to %.2f, tension spline %.2f to %.2f%n",
        stepLow * 100, stepHigh * 100, low * 100, high * 100);
    assertTrue(low > stepLow - 15 * BP && high < stepHigh + 15 * BP);
  }

  /** Worst |AD ladder - 1 bp central bump| over the buckets, as a share of the parallel DV01. */
  private double ladderAgainstBump(final SofrCurve c) {
    final int n = c.inputCount();
    final OisSwap a = OisSwap.spot(SwapConvention.USD_SOFR, TODAY, 72, 1e8, 0.030, true);
    final OisSwap b = OisSwap.spot(SwapConvention.USD_SOFR, TODAY, 18, 6e7, 0.041, false);
    final double[] g = new double[n];
    pricer.pvGradient(a, c.curve(), c.curve(), FixingHistory.EMPTY, TODAY, g, g);
    pricer.pvGradient(b, c.curve(), c.curve(), FixingHistory.EMPTY, TODAY, g, g);
    final double[] ladder = new double[c.riskTenorCount()];
    c.riskLadder(g.clone(), ladder);
    final double parallel = c.riskParallel(g);
    final double[] base = new double[n];
    for (int i = 0; i < n; i++) {
      base[i] = c.quote(i);
    }
    final double[] shift = new double[n];
    final double[] bumped = new double[n];
    double worst = 0.0;
    for (int k = 0; k < ladder.length; k++) {
      c.riskShift(k, BP, shift);
      final double[] pv = new double[2];
      for (int s = 0; s < 2; s++) {
        for (int i = 0; i < n; i++) {
          bumped[i] = base[i] + (s == 0 ? shift[i] : -shift[i]);
        }
        assertTrue(c.update(bumped));
        pv[s] = pricer.pv(a, c.curve(), FixingHistory.EMPTY, TODAY)
            + pricer.pv(b, c.curve(), FixingHistory.EMPTY, TODAY);
      }
      worst = Math.max(worst, Math.abs((pv[0] - pv[1]) / 2.0 - ladder[k]));
    }
    assertTrue(c.update(base));
    return worst / Math.abs(parallel);
  }

  /** Worst |first-order - exact| leave-one-out rate after a 10 bp move with a twist. */
  private static double leaveOneOutError(final SofrCurve c) {
    final SofrLeaveOneOut loo = new SofrLeaveOneOut(c);
    final double[] q = new double[c.inputCount()];
    final double[] base = new double[q.length];
    for (int i = 0; i < q.length; i++) {
      base[i] = c.quote(i);
      final double years = (c.pillarDate(i) - TODAY) / 365.25;
      q[i] = base[i] + (10.0 - 0.2 * years + (i % 2 == 0 ? 1.0 : -1.0)) * BP * c.quotePerRate(i);
    }
    assertTrue(c.update(q));
    final double[] linear = new double[loo.swapCount()];
    for (int k = 0; k < linear.length; k++) {
      linear[k] = loo.value(k);
    }
    double worst = 0.0;
    for (int k = 0; k < linear.length; k++) {
      worst = Math.max(worst, Math.abs(linear[k] - loo.exact(k)));
    }
    assertTrue(c.update(base));
    return worst;
  }

  @Test
  void aSwapsOwnQuoteDoesNotReachItsLeaveOneOutRate() {
    final SofrCurve c = SofrMarket.standard();
    final SofrLeaveOneOut loo = new SofrLeaveOneOut(c);
    final double[] q = SofrMarket.quotes(c);
    assertEquals(8, loo.swapCount());
    for (int k = 0; k < loo.swapCount(); k++) {
      final int i = loo.inputOfSwap(k);
      assertEquals(k, loo.swapOfInput(i));
      final double before = loo.value(k);
      final double exactBefore = loo.exact(k);
      // same quotes as the reference: the first-order value is the exact one
      assertEquals(exactBefore, before, 1e-13);
      q[i] += 5 * BP;
      assertTrue(c.update(q));
      // its own quote is not an input of its reduced curve: exactly no effect
      assertEquals(before, loo.value(k), 0.0, c.label(i));
      assertEquals(exactBefore, loo.exact(k), 1e-13, c.label(i));
      assertEquals(0.0, loo.weight(k, i), 0.0);
      // while the full curve, which has it as an input, follows it one for one
      assertEquals(q[i], c.parRate(OisSwap.spot(SwapConvention.USD_SOFR, TODAY, c.tenorMonths(i),
          1.0, 0.0, true)), 1e-10);
      q[i] -= 5 * BP;
      assertTrue(c.update(q));
      double sum = 0.0;
      for (int j = 0; j < c.inputCount(); j++) {
        sum += loo.weight(k, j);
      }
      System.out.printf(
          "LOO %s market %.4f leave-one-out %.4f gap %.2f bp, weights sum %.4f%n", c.label(i),
          q[i] * 100, before * 100, (q[i] - before) / BP, sum);
      // a parallel move of the other inputs moves the leave-one-out rate about one for one
      assertEquals(1.0, sum, 0.05, c.label(i));
    }
    assertEquals(-1, loo.swapOfInput(0));
    assertEquals(1, loo.references());
  }

  /**
   * The first-order leave-one-out value against a full rebuild after the market has moved. The
   * binding takes a new reference before any input has moved 10 bp; at that distance, with a
   * twist on top, the error must stay far below the desk's half-spread (about 0.15 bp).
   */
  @Test
  void leaveOneOutLinearisationError() {
    final SofrCurve c = SofrMarket.standard();
    final SofrLeaveOneOut loo = new SofrLeaveOneOut(c);
    final double[] q = SofrMarket.quotes(c);
    for (int i = 0; i < q.length; i++) {
      // 10 bp up at the front, tapering to 4 bp at the back, alternating 1 bp of noise
      final double years = (c.pillarDate(i) - TODAY) / 365.25;
      final double move = (10.0 - 0.2 * years + (i % 2 == 0 ? 1.0 : -1.0)) * BP;
      q[i] += move * c.quotePerRate(i);
    }
    assertTrue(c.update(q));
    assertTrue(loo.moveSinceReference() > 9 * BP && loo.moveSinceReference() <= 11 * BP + 1e-12);
    double worst = 0.0;
    final double[] linear = new double[loo.swapCount()];
    for (int k = 0; k < loo.swapCount(); k++) {
      linear[k] = loo.value(k);
    }
    for (int k = 0; k < loo.swapCount(); k++) {
      worst = Math.max(worst, Math.abs(linear[k] - loo.exact(k)));
    }
    System.out.printf("LOO linearisation error after a 10 bp move: %.5f bp%n", worst / BP);
    // the spline is linear in its nodes, so only the bootstrap's own curvature is left
    assertTrue(worst < 0.01 * BP, "linearisation error " + worst / BP + " bp");
    loo.reference();
    assertEquals(0.0, loo.moveSinceReference(), 0.0);
    assertEquals(2, loo.references());
  }

  @Test
  void everyRiskBucketIsPopulatedByItsOwnSwap() {
    final SofrCurve c = SofrMarket.standard();
    final int n = c.inputCount();
    final int[] months = c.spec().riskTenorMonths();
    for (int j = 0; j < months.length; j++) {
      for (int k = 0; k < months.length; k++) {
        // every risk tenor is a build swap, so K W is the identity up to the solve
        assertEquals(j == k ? 1.0 : 0.0, c.riskMatrix(j, k), 1e-9, j + "," + k);
      }
    }
    final double[] ladder = new double[months.length];
    for (int k = 0; k < months.length; k++) {
      final OisSwap payer =
          OisSwap.spot(SwapConvention.USD_SOFR, TODAY, months[k], 1.0, c.riskParRate(k), true);
      final double[] g = new double[n];
      pricer.pvGradient(payer, c.curve(), c.curve(), FixingHistory.EMPTY, TODAY, g, g);
      c.riskLadder(g, ladder);
      final double annuity = pricer.annuity(payer, c.curve(), TODAY);
      // a par payer gains its annuity per unit of its own rate and nothing from the others
      assertEquals(annuity * BP, ladder[k], annuity * BP * 1e-9, "own bucket " + months[k]);
      for (int j = 0; j < months.length; j++) {
        if (j != k) {
          assertEquals(0.0, ladder[j], annuity * BP * 1e-9, months[k] + "M in bucket " + j);
        }
      }
    }
  }

  @Test
  void bucketsSumToParallelAndAgreeWithBumping() {
    for (final SofrCurveSpec spec :
        new SofrCurveSpec[] {SofrCurveSpec.usd(), futuresToTwoYears()}) {
      final SofrCurve c = SofrMarket.build(TODAY, spec);
      final int n = c.inputCount();
      final int r = c.riskTenorCount();
      // an off-market, off-pillar position: 100 mm of a 6-year payer and 60 mm of an 18-month
      // receiver, both away from par
      final OisSwap a = OisSwap.spot(SwapConvention.USD_SOFR, TODAY, 72, 1e8, 0.030, true);
      final OisSwap b = OisSwap.spot(SwapConvention.USD_SOFR, TODAY, 18, 6e7, 0.041, false);
      final double[] g = new double[n];
      pricer.pvGradient(a, c.curve(), c.curve(), FixingHistory.EMPTY, TODAY, g, g);
      pricer.pvGradient(b, c.curve(), c.curve(), FixingHistory.EMPTY, TODAY, g, g);
      final double[] ladder = new double[r];
      c.riskLadder(g.clone(), ladder);
      final double parallel = c.riskParallel(g.clone());
      double sum = 0.0;
      double gross = 0.0;
      for (final double v : ladder) {
        sum += v;
        gross += Math.abs(v);
      }
      // two routes through the same linear map (a transposed solve and a forward solve):
      // equal to rounding, asserted at the module's 1e-9 relative
      assertEquals(parallel, sum, Math.abs(parallel) * 1e-9, "sum of buckets against parallel");

      final double[] base = new double[n];
      for (int i = 0; i < n; i++) {
        base[i] = c.quote(i);
      }
      final double[] shift = new double[n];
      final double[] bumped = new double[n];
      final double[] riskBase = new double[r];
      for (int k = 0; k < r; k++) {
        riskBase[k] = c.riskParRate(k);
      }
      double worst = 0.0;
      double bumpedSum = 0.0;
      for (int k = 0; k < r; k++) {
        c.riskShift(k, BP, shift);
        final double[] pv = new double[2];
        for (int s = 0; s < 2; s++) {
          for (int i = 0; i < n; i++) {
            bumped[i] = base[i] + (s == 0 ? shift[i] : -shift[i]);
          }
          assertTrue(c.update(bumped));
          pv[s] = pricer.pv(a, c.curve(), FixingHistory.EMPTY, TODAY)
              + pricer.pv(b, c.curve(), FixingHistory.EMPTY, TODAY);
          if (s == 0) {
            for (int j = 0; j < r; j++) {
              // the Jacobian property: this move raises risk rate k by a basis point and leaves
              // the others; second order in a 1 bp move is below 1e-4 bp
              assertEquals(j == k ? BP : 0.0, c.riskParRate(j) - riskBase[j], 1e-4 * BP,
                  "risk rate " + j + " under the shift of " + k);
            }
          }
        }
        final double byBump = (pv[0] - pv[1]) / 2.0;
        bumpedSum += byBump;
        worst = Math.max(worst, Math.abs(byBump - ladder[k]));
      }
      assertTrue(c.update(base));
      System.out.printf(
          "RISK %d inputs: parallel %.2f, gross %.2f, worst |AD - bump| %.3e of parallel%n", n,
          parallel, gross, worst / Math.abs(parallel));
      // the module's tolerance for AD against a 1 bp central bump (API_SPEC.md Section 7.2)
      assertTrue(worst <= 1e-4 * Math.abs(parallel), "AD against bump: " + worst);
      assertEquals(parallel, bumpedSum, 1e-4 * Math.abs(parallel));
    }
  }

  /** Futures out to two years in place of the 1 and 2-year swaps: risk tenors off the build set. */
  private static SofrCurveSpec futuresToTwoYears() {
    return SofrCurveSpec.usd().withFutures(2, 7).withSwapTenors(36, 60, 84, 120, 240, 360);
  }

  @Test
  void riskTenorsThatAreNotBuildSwapsAreMappedByTheJacobian() {
    final SofrCurve c = SofrMarket.build(TODAY, futuresToTwoYears());
    assertEquals(SofrCurve.Kind.SR3, c.kind(9), c.label(9));
    assertEquals(-1, c.swapInput(12));
    // K W is not the identity here: the 1-year risk swap is a blend of futures
    assertTrue(Math.abs(c.riskMatrix(0, 0) - 1.0) > 1e-4 || Math.abs(c.riskMatrix(0, 1)) > 1e-4);
    final int n = c.inputCount();
    final double[] ladder = new double[c.riskTenorCount()];
    for (int k = 0; k < 2; k++) {
      final OisSwap payer =
          OisSwap.spot(SwapConvention.USD_SOFR, TODAY, 12 * (k + 1), 1.0, c.riskParRate(k), true);
      final double[] g = new double[n];
      pricer.pvGradient(payer, c.curve(), c.curve(), FixingHistory.EMPTY, TODAY, g, g);
      c.riskLadder(g, ladder);
      final double annuity = pricer.annuity(payer, c.curve(), TODAY);
      // still the whole DV01 in its own bucket: that is what the Jacobian is for
      assertEquals(annuity * BP, ladder[k], annuity * BP * 1e-9);
      assertEquals(0.0, ladder[1 - k], annuity * BP * 1e-9);
    }
  }

  @Test
  void aYearEndTurnRaisesTheForwardOverItsWindowOnly() {
    final int start = Dates.of(2026, 12, 31);
    final int end = Dates.of(2027, 1, 4);
    final SofrCurve plain = SofrMarket.standard();
    final SofrCurve turned = SofrMarket.build(TODAY, SofrCurveSpec.usd().withTurn(start, end, 20 * BP));
    assertEquals(1, turned.spec().turnCount());
    assertEquals(start, turned.spec().turnStart(0));
    assertEquals(end, turned.spec().turnEnd(0));
    assertEquals(20 * BP, turned.spec().turnSpread(0), 0.0);
    assertTrue(turned.builder().maxRepricingError() <= 1e-10);
    final double inside = turned.overnightForward(start) - turned.overnightForward(start - 1);
    // the turn is a simple Act/360 spread added to the daily forward
    assertEquals(20 * BP, inside, 0.01 * BP);
    assertEquals(turned.overnightForward(end + 1), turned.overnightForward(end + 5), 1e-12);
    // the December contract is an input of both curves, so the step level around the turn is
    // lower by the turn's share of the contract: one turn day (31 December) of 20 bp, spread over
    // the 22 days of December after the step
    final double level = plain.overnightForward(start - 1) - turned.overnightForward(start - 1);
    assertEquals(20 * BP / 22.0, level, 0.05 * BP);
  }

  /**
   * The Hull-White parameters are assumptions (DR-P1-05). This states what they are worth: the
   * adjustment of each future, and what a 20 percent error in sigma moves on a 9-month swap,
   * which is priced off the futures section.
   */
  @Test
  void convexityAssumptionSensitivity() {
    final SofrCurve base = SofrMarket.standard();
    final SofrCurve high =
        SofrMarket.build(TODAY, SofrCurveSpec.usd().withHullWhite(HullWhite.DEFAULT_A,
            1.2 * HullWhite.DEFAULT_SIGMA));
    double largest = 0.0;
    for (int i = 0; i < base.inputCount(); i++) {
      if (base.kind(i) == SofrCurve.Kind.SR1 || base.kind(i) == SofrCurve.Kind.SR3) {
        System.out.printf("CONVEXITY %s %.4f bp%n", base.label(i), base.convexity(i) / BP);
        assertTrue(base.convexity(i) > 0.0);
        // sigma squared scaling
        assertEquals(1.44 * base.convexity(i), high.convexity(i), 1e-15);
        largest = Math.max(largest, base.convexity(i));
      } else {
        assertEquals(0.0, base.convexity(i), 0.0);
      }
    }
    // the furthest contract ends ten months out: about a quarter of a basis point
    assertTrue(largest < 0.35 * BP, "largest convexity " + largest);
    // same futures rates, so prices differ by the adjustment; here the same prices are fed to
    // both, which is the error made by a wrong sigma
    final double[] q = SofrMarket.quotes(base);
    assertTrue(high.update(q));
    final OisSwap nine = OisSwap.spot(SwapConvention.USD_SOFR, TODAY, 9, 1.0, 0.0, true);
    final double move = high.parRate(nine) - base.parRate(nine);
    System.out.printf("CONVEXITY sigma +20%%: 9M par rate moves %.4f bp%n", move / BP);
    assertTrue(move < 0.0 && move > -0.1 * BP, "9M move " + move);
  }

  @Test
  void aQuoteSetThatCannotBeSolvedLeavesTheCurveAsItWas() {
    final SofrCurve c = SofrMarket.standard();
    final double[] good = SofrMarket.quotes(c);
    final double df = c.curve().df(TODAY + 2000);
    final double[] bad = good.clone();
    bad[8] = Double.NaN;
    assertFalse(c.update(bad));
    assertEquals(df, c.curve().df(TODAY + 2000), 0.0);
    for (int i = 0; i < good.length; i++) {
      assertEquals(good[i], c.quote(i), 0.0);
    }
    assertTrue(c.update(good));
    assertEquals(df, c.curve().df(TODAY + 2000), 0.0, "same quotes: nothing is re-solved");
  }

  @Test
  void theLayoutFollowsTheCalendar() {
    // 3 December 2026: the December contract has started, so its average uses two fixings
    final int december = Dates.of(2026, 12, 3);
    final SofrCurve started = SofrMarket.build(december, SofrCurveSpec.usd());
    assertEquals("SR1 2026-12", started.label(1));
    // a started contract is adjusted for the four weeks that remain of it: a thousandth of a bp
    assertTrue(started.convexity(1) > 0.0 && started.convexity(1) < 0.01 * BP);
    assertTrue(started.builder().maxRepricingError() <= 1e-10);
    // without the fixings the started contract cannot be priced: no silent default
    assertThrows(IllegalStateException.class,
        () -> new SofrCurve(december, SofrCurveSpec.usd(), FixingHistory.EMPTY, 0.039));
    // a non-business day is refused
    assertThrows(IllegalArgumentException.class,
        () -> new SofrCurve(Dates.of(2026, 11, 1), SofrCurveSpec.usd(), FixingHistory.EMPTY,
            0.039));
    // after the last listed meeting the front end is the overnight pillar and the strip
    final int late = Dates.of(2027, 12, 13);
    final SofrCurve none = SofrMarket.build(late, SofrCurveSpec.usd());
    assertEquals(1, none.stepPillars());
    assertEquals(SofrCurve.Kind.SR3, none.kind(1));
    assertTrue(none.builder().maxRepricingError() <= 1e-10);
    // no futures at all, and a log-linear curve: the degenerate specifications still build
    final SofrCurve bare =
        SofrMarket.build(TODAY, SofrCurveSpec.usd().withFutures(0, 0)
            .withInterpolation(Interpolation.LINEAR_LOG_DF));
    assertEquals(9, bare.inputCount());
    assertEquals(0, bare.stepPillars());
  }

  @Test
  void specificationsAreChecked() {
    final SofrCurveSpec usd = SofrCurveSpec.usd();
    assertEquals(2, usd.meetings());
    assertEquals(3, usd.sr3Contracts());
    assertEquals(HullWhite.DEFAULT_A, usd.hwA(), 0.0);
    assertEquals(HullWhite.DEFAULT_SIGMA, usd.hwSigma(), 0.0);
    assertEquals(Interpolation.STEP_THEN_TENSION_SPLINE, usd.interpolation());
    assertEquals(SofrCurveSpec.DEFAULT_TENSION, usd.tension(), 0.0);
    assertEquals(8, usd.swapTenorMonths().length);
    assertThrows(IllegalArgumentException.class, () -> usd.withFutures(-1, 0));
    assertThrows(IllegalArgumentException.class, () -> usd.withFutures(0, -1));
    assertThrows(IllegalArgumentException.class, () -> usd.withSwapTenors());
    assertThrows(IllegalArgumentException.class, () -> usd.withSwapTenors(24, 12));
    assertThrows(IllegalArgumentException.class,
        () -> usd.withInterpolation(Interpolation.TENSION_SPLINE, 0.0));
    assertEquals(2.0, usd.withInterpolation(Interpolation.TENSION_SPLINE, 2.0).tension(), 0.0);
  }
}
