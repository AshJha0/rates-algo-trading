package io.ratesalgo.analytics.golden;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.analytics.bond.BondConvention;
import io.ratesalgo.analytics.bond.BondPricer;
import io.ratesalgo.analytics.bond.FixedRateBond;
import io.ratesalgo.analytics.curve.HullWhite;
import io.ratesalgo.analytics.curve.Interpolation;
import io.ratesalgo.analytics.curve.OisFutureInstrument;
import io.ratesalgo.analytics.curve.SofrCurve;
import io.ratesalgo.analytics.curve.SofrCurveSpec;
import io.ratesalgo.analytics.date.Calendars;
import io.ratesalgo.analytics.date.Dates;
import io.ratesalgo.analytics.date.DayCount;
import io.ratesalgo.analytics.swap.FixingHistory;
import io.ratesalgo.analytics.swap.OisSwap;
import io.ratesalgo.analytics.swap.SwapConvention;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pricing v2 against {@code data/golden/pricing_v2_golden.json}, written by {@code
 * research/ratesresearch/goldens/pricing_v2.py} with QuantLib 1.44.
 *
 * <p>What each test is evidence of, in the words of the generator's own account:
 *
 * <ul>
 *   <li>Independent of the Java code: instrument prices on the curves (QuantLib objects on a
 *       daily-node curve), the curve solves (SciPy), monotone-convex forwards (QuantLib's
 *       interpolation), the in-arrears Hull-White adjustments (numerical variances, and
 *       QuantLib's Kirikos-Novak bias for the forward-looking part), bond z-spreads and bumped
 *       risk (QuantLib).
 *   <li>Independent in derivation: the tension spline (solved in Python from its differential
 *       equation, and checked there against the minimiser of its energy).
 *   <li>A cross-language retyping only, which catches a slip in one language and not a formula
 *       wrong in both: the closed-form adjustments inside the futures quotes, the Hagan-West
 *       formula that builds the monotone-convex curve, and the hat weights of the risk map.
 * </ul>
 */
class PricingV2GoldenTest {

  private static final double BP = 1e-4;
  private static final Map<String, Object> GOLDEN = load();
  private static final int TODAY = Dates.parse(Golden.str(GOLDEN, "valuation_date"));

  @SuppressWarnings("unchecked")
  private static Map<String, Object> load() {
    try {
      return (Map<String, Object>)
          Json.parse(
              Files.readString(
                  Golden.repoRoot().resolve("data/golden/pricing_v2_golden.json"),
                  StandardCharsets.UTF_8));
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static SofrCurve curve(final Map<String, Object> golden) {
    final SofrCurveSpec spec =
        SofrCurveSpec.usd()
            .withInterpolation(
                Interpolation.valueOf(Golden.str(golden, "interpolation")),
                Golden.num(golden, "tension"));
    final SofrCurve c = new SofrCurve(TODAY, spec, FixingHistory.EMPTY, 0.039);
    final List<Map<String, Object>> inputs = Golden.list(GOLDEN, "inputs");
    final double[] quotes = new double[c.inputCount()];
    for (int i = 0; i < quotes.length; i++) {
      quotes[i] = Golden.num(inputs.get(i), "quote");
    }
    assertTrue(c.update(quotes), "golden quotes did not solve");
    return c;
  }

  private static Map<String, Object> curveGolden(final String interpolation) {
    for (final Map<String, Object> c : Golden.list(GOLDEN, "curves")) {
      if (interpolation.equals(Golden.str(c, "interpolation"))) {
        return c;
      }
    }
    throw new IllegalArgumentException(interpolation);
  }

  /** The build set as the generator declares it by hand: kinds, contracts, pillars. */
  @Test
  void theBuildSetIsTheOneDeclaredInTheGenerator() {
    final SofrCurve c = curve(curveGolden("STEP_THEN_TENSION_SPLINE"));
    final List<Map<String, Object>> inputs = Golden.list(GOLDEN, "inputs");
    assertEquals(inputs.size(), c.inputCount());
    for (int i = 0; i < inputs.size(); i++) {
      final Map<String, Object> in = inputs.get(i);
      final String kind = Golden.str(in, "kind");
      assertEquals(Golden.date(in, "pillar"), c.pillarDate(i), c.label(i) + " pillar");
      switch (c.kind(i)) {
        case OVERNIGHT -> assertEquals("on", kind);
        case SR1 -> assertEquals("future1m", kind);
        case SR3 -> assertEquals("future3m", kind);
        case SWAP -> assertEquals("ois", kind);
      }
      if (c.kind(i) == SofrCurve.Kind.SWAP) {
        assertEquals(Golden.integer(in, "tenor_months"), c.tenorMonths(i));
      } else if (c.kind(i) != SofrCurve.Kind.OVERNIGHT) {
        assertEquals(Golden.str(in, "label"), c.label(i));
        assertEquals(
            Golden.integer(in, "year") * 100 + Golden.integer(in, "month"), c.contractMonth(i));
        // the closed form typed in two languages: equal to rounding
        assertEquals(Golden.num(in, "convexity"), c.convexity(i), 1e-18, c.label(i));
      }
    }
    assertEquals(Golden.integer(curveGolden("STEP_THEN_TENSION_SPLINE"), "step_pillars"),
        c.stepPillars());
  }

  /**
   * The three curves against a solve by SciPy with every instrument priced by QuantLib. Discount
   * factors at the pillars agree to 1e-10: both sides reprice fourteen quotes to 1e-12 or better
   * and the map from quotes to discount factors has a condition number of order ten.
   */
  @Test
  void curvesMatchTheQuantLibPricedSolve() {
    for (final Map<String, Object> g : Golden.list(GOLDEN, "curves")) {
      final String scheme = Golden.str(g, "interpolation");
      final SofrCurve c = curve(g);
      assertTrue(Golden.num(g, "max_reprice_error") <= 1e-12, scheme + " Python reprice error");
      assertTrue(c.builder().maxRepricingError() <= 1e-10, scheme);
      final List<Object> dates = Golden.raw(g, "pillar_dates");
      final List<Object> dfs = Golden.raw(g, "discount_factors");
      double worst = 0.0;
      for (int i = 0; i < dates.size(); i++) {
        final int pillar = Dates.parse((String) dates.get(i));
        assertEquals(pillar, c.curve().pillarDate(i), scheme + " pillar " + i);
        worst = Math.max(worst, Math.abs((Double) dfs.get(i) - c.curve().df(pillar)));
      }
      System.out.println("V2 GOLDEN " + scheme + " max |DF - QuantLib-priced solve| " + worst);
      assertTrue(worst <= 1e-10, scheme + " discount factors differ by " + worst);
    }
  }

  /**
   * Instruments that are not inputs, priced off each curve by QuantLib on daily nodes: par swaps
   * between the pillars, futures between and beyond the contracts of the build set (the January
   * one-month contract straddles the January step), and daily forwards. This is where the
   * interpolation shows: 1e-9 in rate and 1e-7 in futures price (100 times a rate).
   */
  @Test
  void instrumentsOffTheBuildSetMatchQuantLib() {
    for (final Map<String, Object> g : Golden.list(GOLDEN, "curves")) {
      final String scheme = Golden.str(g, "interpolation");
      final SofrCurve c = curve(g);
      double worstRate = 0.0;
      for (final Map<String, Object> p : Golden.list(g, "par_rates")) {
        final OisSwap swap =
            OisSwap.spot(
                SwapConvention.USD_SOFR, TODAY, Golden.integer(p, "tenor_months"), 1.0, 0.0, true);
        worstRate = Math.max(worstRate, Math.abs(Golden.num(p, "par_rate") - c.parRate(swap)));
      }
      double worstPrice = 0.0;
      for (final Map<String, Object> f : Golden.list(g, "futures")) {
        final boolean three = Golden.str(f, "code").startsWith("SR3");
        final int year = Golden.integer(f, "year");
        final int month = Golden.integer(f, "month");
        final double ca = Golden.num(f, "convexity");
        final OisFutureInstrument future =
            three
                ? OisFutureInstrument.threeMonth("f", year, month, Calendars.US_SOFR,
                    DayCount.ACT_360, ca, 0.0)
                : OisFutureInstrument.oneMonth("f", year, month, Calendars.US_SOFR,
                    DayCount.ACT_360, ca, 0.0);
        worstPrice =
            Math.max(worstPrice,
                Math.abs(Golden.num(f, "price") - future.impliedQuote(c.builder().context())));
      }
      double worstForward = 0.0;
      for (final Map<String, Object> f : Golden.list(g, "overnight_forwards")) {
        worstForward =
            Math.max(worstForward,
                Math.abs(Golden.num(f, "forward") - c.overnightForward(Golden.date(f, "date"))));
      }
      System.out.println(
          "V2 GOLDEN " + scheme + " off-curve par " + worstRate + ", futures price " + worstPrice
              + ", overnight forward " + worstForward);
      assertTrue(worstRate <= 1e-9, scheme + " par rate " + worstRate);
      assertTrue(worstPrice <= 1e-7, scheme + " futures price " + worstPrice);
      // a one-day forward is a difference of two discount factors divided by a day: 1e-10 on
      // the discount factors is 4e-8 on the forward
      assertTrue(worstForward <= 5e-8, scheme + " forward " + worstForward);
    }
  }

  /**
   * Monotone-convex instantaneous forwards against QuantLib's ConvexMonotone interpolation
   * (quadraticity 0, monotonicity 1: the basic Hagan-West method), fed the discrete forwards of
   * the golden curve's nodes past the join. The Java forward is a central difference of the log
   * discount factor over 1e-5 years: 1e-8 covers its truncation and the 1e-10 on the nodes.
   */
  @Test
  void monotoneConvexForwardsMatchQuantLibsInterpolation() {
    final SofrCurve c = curve(curveGolden("STEP_THEN_MONOTONE_CONVEX"));
    final Map<String, Object> g = Golden.map(GOLDEN, "monotone_convex_forwards");
    assertEquals(c.stepPillars(), Golden.integer(g, "first_node"));
    double worst = 0.0;
    for (final Map<String, Object> p : Golden.list(g, "points")) {
      final double t = Golden.num(p, "time");
      final double h = 1e-5;
      final double forward =
          -(Math.log(c.curve().dfAtTime(t + h)) - Math.log(c.curve().dfAtTime(t - h))) / (2 * h);
      worst = Math.max(worst, Math.abs(Golden.num(p, "instantaneous_forward") - forward));
    }
    System.out.println("V2 GOLDEN monotone convex forward against QuantLib " + worst);
    assertTrue(worst <= 1e-8, "forward differs by " + worst);
  }

  /**
   * The tension spline's Python statement was checked there against the minimiser of the spline
   * energy on a grid of 1/128 year; the gap is recorded in the file and must be small against
   * the log discount factors it interpolates (second order in the grid step).
   */
  @Test
  void theTensionSplineIsTheMinimiserOfItsEnergy() {
    final Map<String, Object> v = Golden.map(GOLDEN, "tension_spline_variational_check");
    final double gap = Golden.num(v, "max_abs_log_df_gap");
    final double scale = Golden.num(v, "largest_abs_log_df");
    System.out.println("V2 GOLDEN tension spline variational gap " + gap + " on " + scale);
    assertTrue(gap <= 1e-6 * scale, "variational gap " + gap);
  }

  /**
   * The in-arrears Hull-White adjustments three ways. Closed form against numerical variances
   * (independent): 1e-9 relative, the quadrature's accuracy. For the compounded contracts, tau
   * times the adjustment less the variance of the integrated rate over a period of length tau is
   * QuantLib's Kirikos-Novak exponent (independent): 1e-9 relative. And against Hull's
   * forward-looking formula, to state the size of the difference.
   */
  @Test
  void inArrearsConvexityMatchesNumericalVariancesAndQuantLib() {
    final Map<String, Object> hw = Golden.map(GOLDEN, "hull_white");
    final double a = Golden.num(hw, "a");
    final double sigma = Golden.num(hw, "sigma");
    assertEquals(HullWhite.DEFAULT_A, a, 0.0);
    assertEquals(HullWhite.DEFAULT_SIGMA, sigma, 0.0);
    int compounded = 0;
    for (final Map<String, Object> r : Golden.list(GOLDEN, "hull_white_convexity")) {
      final double t1 = Golden.num(r, "t1");
      final double t2 = Golden.num(r, "t2");
      final boolean three = (Boolean) r.get("compounded");
      final double java =
          three
              ? HullWhite.compoundedFuturesConvexity(a, sigma, t1, t2)
              : HullWhite.averageFuturesConvexity(a, sigma, t1, t2);
      final String code = Golden.str(r, "code");
      assertEquals(Golden.num(r, "closed_form"), java, 1e-18, code + " retyping");
      assertEquals(Golden.num(r, "numerical"), java, 1e-9 * java, code + " numerical");
      final double tau = t2 - t1;
      assertEquals(Golden.num(r, "period_variance_numerical"),
          HullWhite.integratedVariance(a, sigma, tau), 1e-9 * sigma * sigma * tau * tau * tau);
      final double hull = Golden.num(r, "hull_forward_looking");
      assertEquals(hull, HullWhite.futuresConvexity(a, sigma, t1, t2), 1e-18);
      System.out.printf(
          "V2 GOLDEN convexity %s in arrears %.4f bp, Hull forward-looking %.4f bp%n", code,
          java / BP, hull / BP);
      assertTrue(java > hull, code + ": in arrears is the larger");
      if (three) {
        compounded++;
        final double z = Golden.num(r, "quantlib_kirikos_novak_z");
        assertEquals(z, tau * java - HullWhite.integratedVariance(a, sigma, tau), 1e-9 * z, code);
      }
    }
    assertEquals(3, compounded);
  }

  /**
   * Bonds on the default curve: accrued, z-spread and price by QuantLib; risk to each build
   * quote by QuantLib central bumps with a full re-solve, against algorithmic differentiation
   * through the bootstrap Jacobian; the same mapped to risk tenors; and the spread DV01.
   */
  @Test
  void bondSpreadAndRiskMatchQuantLibBumps() {
    assertEquals("STEP_THEN_TENSION_SPLINE", Golden.str(GOLDEN, "bond_risk_curve"));
    assertTrue(Golden.num(GOLDEN, "bond_risk_reprice_error") <= 1e-12);
    final SofrCurve c = curve(curveGolden("STEP_THEN_TENSION_SPLINE"));
    final BondPricer pricer = new BondPricer();
    final int n = c.inputCount();
    final List<Object> tenors = Golden.raw(GOLDEN, "risk_tenor_years");
    final double[] tenorYears = new double[tenors.size()];
    for (int k = 0; k < tenorYears.length; k++) {
      tenorYears[k] = (Double) tenors.get(k);
    }
    for (final Map<String, Object> g : Golden.list(GOLDEN, "bonds")) {
      final String id = Golden.str(g, "id");
      final FixedRateBond bond =
          new FixedRateBond(id, BondConvention.UST, Golden.num(g, "coupon"),
              Golden.date(g, "dated"), Golden.date(g, "maturity"));
      final int settle = bond.settlementDate(TODAY);
      assertEquals(Golden.date(g, "settlement"), settle, id);
      final double accrued = pricer.accrued(bond, settle);
      assertEquals(Golden.num(g, "accrued"), accrued, 1e-10, id + " accrued");
      final double dirty = Golden.num(g, "clean_price") + accrued;
      final double z = pricer.zSpread(bond, c.curve(), dirty, settle);
      // two root searches to 1e-15 on curves that agree to 1e-10
      assertEquals(Golden.num(g, "z_spread"), z, 1e-9, id + " z-spread");
      assertEquals(Golden.num(g, "dirty_at_zero_spread"),
          pricer.dirtyFromCurve(bond, c.curve(), settle), 1e-7, id + " curve price");

      final double[] gradient = new double[n];
      pricer.dirtyFromCurveGradient(bond, c.curve(), settle, z, 1.0, gradient);
      final double[] byQuote = gradient.clone();
      c.builder().quoteSensitivities(byQuote);
      final List<Object> bumped = Golden.raw(g, "quote_dv01");
      double total = 0.0;
      for (final Object v : bumped) {
        total += Math.abs((Double) v);
      }
      double worst = 0.0;
      for (int i = 0; i < n; i++) {
        final double ad = byQuote[i] * c.quotePerRate(i) * BP;
        worst = Math.max(worst, Math.abs(ad - (Double) bumped.get(i)));
      }
      // the module's tolerance for a ladder against QuantLib bumps: 1e-6 of its absolute sum
      assertTrue(worst <= 1e-6 * total, id + " quote DV01 differs by " + worst / total);

      final double[] ladder = new double[c.riskTenorCount()];
      c.riskLadder(gradient, ladder);
      final List<Object> mapped = Golden.raw(g, "risk_ladder");
      double worstBucket = 0.0;
      for (int k = 0; k < ladder.length; k++) {
        worstBucket = Math.max(worstBucket, Math.abs(ladder[k] - (Double) mapped.get(k)));
      }
      assertTrue(worstBucket <= 1e-6 * total, id + " risk ladder differs by " + worstBucket / total);

      final double[] spreadLadder = new double[tenorYears.length];
      final double spreadDv01 =
          pricer.spreadLadder(bond, c.curve(), settle, z, BP, tenorYears, spreadLadder);
      // a 1 bp central bump leaves the third-order term: (1 bp)^2 times the cube of the flow
      // times over six, 1e-6 relative for the thirty-year; asserted at 5e-6
      final double quantLib = Golden.num(g, "spread_dv01");
      assertEquals(quantLib, spreadDv01, 5e-6 * Math.abs(quantLib), id + " spread DV01");
      System.out.printf(
          "V2 GOLDEN %s z-spread %.2f bp, spread DV01 %.5f, quote DV01 worst %.2e of ladder%n", id,
          z / BP, spreadDv01, worst / total);
    }
  }
}
