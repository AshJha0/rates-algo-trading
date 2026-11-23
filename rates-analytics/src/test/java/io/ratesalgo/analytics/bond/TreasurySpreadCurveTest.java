package io.ratesalgo.analytics.bond;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.analytics.curve.DiscountCurve;
import io.ratesalgo.analytics.curve.Interpolation;
import io.ratesalgo.analytics.date.Dates;
import org.junit.jupiter.api.Test;

/**
 * The fitted Treasury spread curve: recovery of a known curve and premium, the bound on one bad
 * book, a bond's influence on its own model value, and the spread ladder of a bond.
 */
class TreasurySpreadCurveTest {

  private static final double BP = 1e-4;

  /** A spread curve of the model's own form: -12 bp at the front, -30 bp at the back. */
  private static double truth(final double years) {
    final double x = years / 5.0;
    final double f1 = (1.0 - Math.exp(-x)) / x;
    return -32 * BP + 22 * BP * f1;
  }

  @Test
  void recoversACurveOfItsOwnFormAndTheOnTheRunPremium() {
    final double[] years = {1.8, 1.9, 2.0, 4.9, 5.0, 9.8, 29.8};
    final boolean[] otr = {false, false, true, false, true, true, true};
    final double[] w = {1, 1, 1, 1, 1, 1, 1};
    final double[] s = new double[7];
    for (int i = 0; i < 7; i++) {
      s[i] = truth(years[i]) - (otr[i] ? 1.5 * BP : 0.0);
    }
    final TreasurySpreadCurve c = TreasurySpreadCurve.standard(8);
    c.fit(7, years, s, w, otr);
    assertEquals(7, c.count());
    assertEquals(2, c.terms());
    assertTrue(c.premiumIdentified());
    assertEquals(0, c.rejectedCount());
    // exact data of the model's form: recovered to the conditioning of a 3 by 3 solve
    assertEquals(1.5 * BP, c.onTheRunPremium(), 1e-10);
    for (final double t : new double[] {1.0, 3.0, 7.0, 15.0, 30.0}) {
      assertEquals(truth(t), c.spread(t), 1e-10, "spread at " + t);
    }
    for (int i = 0; i < 7; i++) {
      assertEquals(0.0, c.residual(i), 1e-10);
      assertEquals(s[i], c.fitted(i), 1e-10);
      assertEquals(1.0, c.robustWeight(i), 0.0);
      assertTrue(c.leverage(i) > 0.0 && c.leverage(i) < 1.0);
    }
  }

  @Test
  void withOnlyOnTheRunBondsThePremiumIsNotIdentifiedAndTwoTermsAreFitted() {
    final double[] years = {2.0, 5.0, 10.0, 30.0};
    final boolean[] otr = {true, true, true, true};
    final double[] s = new double[4];
    for (int i = 0; i < 4; i++) {
      s[i] = truth(years[i]);
    }
    final TreasurySpreadCurve c = TreasurySpreadCurve.standard(4);
    c.fit(4, years, s, new double[] {1, 1, 1, 1}, otr);
    assertEquals(2, c.terms());
    assertFalse(c.premiumIdentified());
    assertEquals(0.0, c.onTheRunPremium(), 0.0);
    assertEquals(truth(7.0), c.spread(7.0), 1e-10);
    // one bond: a level
    c.fit(1, years, s, new double[] {1, 1, 1, 1}, otr);
    assertEquals(1, c.terms());
    assertEquals(s[0], c.spread(20.0), 1e-10);
    assertEquals(s[0], c.leaveOneOut(0), 1e-10, "nothing else to fit it from");
  }

  /**
   * One bad book: 8 bp of spread, which is a point and a half on a 30-year bond or two thirds of a
   * point on a 10-year. The other three bonds lie on the curve with half a basis point of noise. The bad bond is dropped,
   * the fit at the others is the fit without it, and its residual shows the whole error.
   */
  @Test
  void oneBadBookIsDroppedAndTheOthersAreFittedAsIfItWereNotThere() {
    final double[] years = {2.0, 5.0, 10.0, 30.0};
    final boolean[] otr = {true, true, true, true};
    final double[] w = {1, 1, 1, 1};
    final double[] noise = {0.3 * BP, -0.5 * BP, 0.4 * BP, -0.2 * BP};
    final TreasurySpreadCurve clean = TreasurySpreadCurve.standard(4);
    final TreasurySpreadCurve hit = TreasurySpreadCurve.standard(4);
    for (int bad = 0; bad < 4; bad++) {
      final double[] s = new double[4];
      for (int i = 0; i < 4; i++) {
        s[i] = truth(years[i]) + noise[i];
      }
      clean.fit(4, years, s, w, otr);
      final double[] base = new double[4];
      for (int i = 0; i < 4; i++) {
        base[i] = clean.leaveOneOut(i);
      }
      s[bad] += 8 * BP;
      hit.fit(4, years, s, w, otr);
      assertEquals(1, hit.rejectedCount(), "bad " + bad);
      assertEquals(0.0, hit.robustWeight(bad), 0.0);
      assertEquals(0.0, hit.leverage(bad), 0.0);
      assertEquals(2, hit.terms());
      assertTrue(Math.abs(hit.residual(bad)) > 5 * BP, "residual " + hit.residual(bad) / BP);
      assertEquals(hit.fitted(bad), hit.leaveOneOut(bad), 0.0);
      for (int i = 0; i < 4; i++) {
        if (i != bad) {
          // two terms through three good bonds with half a basis point of noise: the fitted
          // values stay within a basis point of the truth, as they were before the bad book
          assertEquals(truth(years[i]), hit.fitted(i), 1.0 * BP, "bad " + bad + " bond " + i);
          assertEquals(truth(years[i]), base[i], 2.5 * BP, "clean fit, bond " + i);
          assertEquals(1.0, hit.robustWeight(i), 0.0);
        }
      }
      // the same market twice gives the same fit to the bit
      final double first = hit.fitted(0);
      hit.fit(4, years, s, w, otr);
      assertEquals(first, hit.fitted(0), 0.0);
    }
  }

  @Test
  void aSmallErrorIsBoundedByTheHuberLimit() {
    // 3 bp is inside the rejection limit; its pull is that of 1.5 bp
    final double[] years = {1.8, 2.0, 4.9, 5.0, 9.8, 10.0, 29.8};
    final boolean[] otr = new boolean[7];
    final double[] w = {1, 1, 1, 1, 1, 1, 1};
    final double[] s = new double[7];
    for (int i = 0; i < 7; i++) {
      s[i] = truth(years[i]);
    }
    final TreasurySpreadCurve c = TreasurySpreadCurve.standard(7);
    s[4] += 3 * BP;
    c.fit(7, years, s, w, otr);
    assertEquals(0, c.rejectedCount());
    assertTrue(c.robustWeight(4) < 0.7 && c.robustWeight(4) > 0.0, "" + c.robustWeight(4));
    final double moved = Math.abs(c.spread(9.9) - truth(9.9));
    assertTrue(moved < 1.0 * BP, "fit moved " + moved / BP + " bp for a 3 bp error");
    assertTrue(c.residual(4) > 2 * BP);
  }

  @Test
  void aBondsOwnSpreadBarelyMovesItsOwnLeaveOneOutValue() {
    final double[] years = {1.8, 2.0, 4.9, 5.0, 9.8, 10.0, 29.8};
    final boolean[] otr = {false, true, false, true, false, true, true};
    final double[] w = {1, 1, 1, 1, 1, 1, 1};
    final double[] s = new double[7];
    for (int i = 0; i < 7; i++) {
      s[i] = truth(years[i]) + (i % 2 == 0 ? 0.2 : -0.2) * BP;
    }
    final TreasurySpreadCurve c = TreasurySpreadCurve.standard(7);
    for (int i = 0; i < 7; i++) {
      c.fit(7, years, s, w, otr);
      final double before = c.leaveOneOut(i);
      final double plain = c.fitted(i);
      final double leverage = c.leverage(i);
      assertTrue(leverage < 0.95, "bond " + i + " leverage " + leverage);
      s[i] += 0.5 * BP;
      c.fit(7, years, s, w, otr);
      // inside the Huber limit the weights do not change, so the leave-one-out value is exactly
      // independent of the bond's own spread; the plain fitted value follows it by its leverage
      assertEquals(before, c.leaveOneOut(i), 1e-12, "bond " + i);
      assertEquals(leverage * 0.5 * BP, c.fitted(i) - plain, 1e-12, "bond " + i);
      s[i] -= 0.5 * BP;
    }
  }

  @Test
  void badInputsAreLeftOutAndArgumentsChecked() {
    final TreasurySpreadCurve c = TreasurySpreadCurve.standard(4);
    final double[] years = {2.0, 5.0, 10.0, 30.0};
    final double[] s = {truth(2), Double.NaN, truth(10), truth(30)};
    c.fit(4, years, s, new double[] {1, 1, 1, 1}, new boolean[4]);
    assertEquals(1, c.rejectedCount());
    assertEquals(1, c.terms(), "three usable bonds: a level");
    assertEquals(0.0, c.robustWeight(1), 0.0);
    assertTrue(Double.isFinite(c.spread(5.0)));
    // no usable bond at all: the previous fit stands and the bonds are valued off it
    final double[] good = {truth(2), truth(5), truth(10), truth(30)};
    c.fit(4, years, good, new double[] {1, 1, 1, 1}, new boolean[4]);
    final double kept = c.spread(7.0);
    c.fit(4, years, s, new double[4], new boolean[4]);
    assertEquals(4, c.rejectedCount());
    assertEquals(kept, c.spread(7.0), 0.0);
    assertEquals(c.spread(10.0), c.fitted(2), 1e-15);
    assertEquals(c.fitted(2), c.leaveOneOut(2), 0.0);
    assertEquals(0.0, c.leverage(2), 0.0);
    // and before any fit there is nothing to stand: zeros, not an exception
    final TreasurySpreadCurve empty = TreasurySpreadCurve.standard(4);
    empty.fit(2, years, s, new double[4], new boolean[4]);
    assertEquals(0.0, empty.fitted(0), 0.0);
    assertEquals(0.0, empty.spread(5.0), 0.0);
    assertThrows(IllegalArgumentException.class, () -> c.fit(5, years, s, s, new boolean[5]));
    assertThrows(IllegalArgumentException.class, () -> c.fit(0, years, s, s, new boolean[4]));
    assertThrows(IllegalArgumentException.class, () -> new TreasurySpreadCurve(0, 5, 1e-4, 5e-4));
    assertThrows(IllegalArgumentException.class, () -> new TreasurySpreadCurve(4, 0, 1e-4, 5e-4));
    assertThrows(IllegalArgumentException.class, () -> new TreasurySpreadCurve(4, 5, 5e-4, 5e-4));
    assertThrows(IllegalArgumentException.class, () -> new TreasurySpreadCurve(4, 5, 0, 5e-4));
  }

  @Test
  void theSpreadLadderSumsToTheSpreadDerivativeAndSitsAtTheBondsMaturity() {
    final int today = Dates.of(2026, 11, 2);
    final DiscountCurve curve =
        DiscountCurve.fromDiscountFactors(
            today, new int[] {today + 365, today + 3650, today + 10_950},
            new double[] {Math.exp(-0.036), Math.exp(-0.38), Math.exp(-1.2)},
            Interpolation.LINEAR_LOG_DF, 0.0);
    final BondPricer pricer = new BondPricer();
    final FixedRateBond bond =
        new FixedRateBond("10Y", BondConvention.UST, 0.04125, Dates.of(2026, 8, 15),
            Dates.of(2036, 8, 15));
    final int settle = bond.settlementDate(today);
    final double[] tenors = {1, 2, 3, 5, 7, 10, 20, 30};
    final double[] ladder = new double[8];
    final double spread = -25 * BP;
    final double total = pricer.spreadLadder(bond, curve, settle, spread, 1.0, tenors, ladder);
    double sum = 0.0;
    for (final double v : ladder) {
      sum += v;
    }
    assertEquals(total, sum, Math.abs(total) * 1e-12);
    final double h = 1e-6;
    final double fd =
        (pricer.dirtyFromCurve(bond, curve, settle, spread + h)
            - pricer.dirtyFromCurve(bond, curve, settle, spread - h)) / (2 * h);
    // central difference of a smooth price: relative 1e-8
    assertEquals(fd, total, Math.abs(fd) * 1e-8);
    assertTrue(total < 0.0, "a wider spread lowers the price");
    // principal at 9.8 years, coupons spread over the earlier tenors: most of it on 10 years
    assertTrue(ladder[5] / total > 0.75, "10Y share " + ladder[5] / total);
    assertEquals(0.0, ladder[6], 0.0);
    assertEquals(0.0, ladder[7], 0.0);
    assertTrue(ladder[0] < 0.0 && ladder[4] < 0.0);
    // a weight scales it, and it accumulates
    final double again = pricer.spreadLadder(bond, curve, settle, spread, -2.0, tenors, ladder);
    assertEquals(-2.0 * total, again, Math.abs(total) * 1e-12);
    assertEquals(-total, sumOf(ladder), Math.abs(total) * 1e-12);
    // a 40-year flow lands on the last tenor
    final FixedRateBond longBond =
        new FixedRateBond("40Y", BondConvention.UST, 0.04, Dates.of(2026, 8, 15),
            Dates.of(2066, 8, 15));
    final double[] l2 = new double[8];
    pricer.spreadLadder(longBond, curve, settle, 0.0, 1.0, tenors, l2);
    assertTrue(l2[7] < 0.0);
  }

  private static double sumOf(final double[] v) {
    double s = 0.0;
    for (final double x : v) {
      s += x;
    }
    return s;
  }

  @Test
  void parYieldOfTheSpreadedCurveRepricesAParBond() {
    final int today = Dates.of(2026, 11, 2);
    final DiscountCurve curve =
        DiscountCurve.fromDiscountFactors(
            today, new int[] {today + 365, today + 3650, today + 10_950},
            new double[] {Math.exp(-0.036), Math.exp(-0.38), Math.exp(-1.2)},
            Interpolation.LINEAR_LOG_DF, 0.0);
    // whole number of half years: the par coupon c solves c/2 sum D + D_n = 1
    final double y = TreasurySpreadCurve.parYield(curve, today, 5.0, -20 * BP);
    double pv = 0.0;
    for (int k = 1; k <= 10; k++) {
      final double t = 0.5 * k;
      final double d = curve.df(today + (int) Math.round(t * 365.0)) * Math.exp(20 * BP * t);
      pv += y / 2.0 * d + (k == 10 ? d : 0.0);
    }
    assertEquals(1.0, pv, 1e-12);
    // a tighter (more negative) spread is a lower yield, one for one to first order
    final double tighter = TreasurySpreadCurve.parYield(curve, today, 5.0, -30 * BP);
    assertEquals(-10 * BP, tighter - y, 0.3 * BP);
    // a short first period: between the yields of the neighbouring whole maturities on a
    // rising curve
    final double stub = TreasurySpreadCurve.parYield(curve, today, 4.8, -20 * BP);
    final double shorter = TreasurySpreadCurve.parYield(curve, today, 4.5, -20 * BP);
    assertTrue(stub > shorter && stub < y, shorter + " " + stub + " " + y);
  }
}
