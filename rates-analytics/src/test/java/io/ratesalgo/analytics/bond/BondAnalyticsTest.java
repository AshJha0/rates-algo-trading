package io.ratesalgo.analytics.bond;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.analytics.curve.DiscountCurve;
import io.ratesalgo.analytics.date.Dates;
import io.ratesalgo.analytics.date.DayCount;
import io.ratesalgo.analytics.funding.RepoCurve;
import io.ratesalgo.analytics.golden.Golden;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class BondAnalyticsTest {

  private static final BondPricer P = new BondPricer();

  static FixedRateBond ust10() {
    return new FixedRateBond("UST 4.25 2034", BondConvention.UST, 0.0425, Dates.of(2024, 11, 15),
        Dates.of(2034, 11, 15));
  }

  @Test
  void giltAccruedTurnsNegativeInsideTheExDividendPeriod() {
    final FixedRateBond g = new FixedRateBond("UKT 4.125 2027", BondConvention.GILT, 0.04125,
        Dates.of(2022, 7, 22), Dates.of(2027, 1, 22));
    final int coupon = Dates.of(2025, 1, 22);
    final int exDate = g.exDividendDate(4);
    assertEquals(coupon, g.couponDate(4));
    assertEquals(Dates.of(2025, 1, 13), exDate, "seven London business days before");
    assertTrue(P.accrued(g, exDate - 1) > 0);
    final double inside = P.accrued(g, exDate);
    assertEquals(-100 * 0.04125 * 0.5 * (coupon - exDate) / 184.0, inside, 1e-12);
    assertEquals(0.0, P.accrued(g, coupon));
    assertEquals(Integer.MAX_VALUE, ust10().exDividendDate(0));
    assertEquals(2, BondConvention.GILT.frequency());
    assertEquals(1, BondConvention.BUND.frequency());
  }

  /**
   * DR-R2R-01: a gilt held from before its ex-dividend date to a date inside the ex-dividend
   * period, but before the coupon is paid, still receives that coupon. Carry and implied repo must
   * count it, since the accrued at the horizon is already negative.
   */
  @Test
  void giltHeldIntoTheExDividendPeriodCarriesTheCouponPaidAfterTheHorizon() {
    final FixedRateBond g = new FixedRateBond("UKT 4.125 2027", BondConvention.GILT, 0.04125,
        Dates.of(2022, 7, 22), Dates.of(2027, 1, 22));
    final int settle = Dates.of(2025, 1, 10);
    final int horizon = Dates.of(2025, 1, 15);
    final int pay = g.couponDate(4);
    assertTrue(settle < g.exDividendDate(4) && g.exDividendDate(4) <= horizon && horizon < pay);
    assertEquals(g.exDividendDate(4), g.entitlementDate(4));
    assertEquals(ust10().couponDate(0), ust10().entitlementDate(0));
    final double c = g.couponAmount(4);
    final double aiS = P.accrued(g, settle);
    final double aiH = P.accrued(g, horizon);
    assertTrue(aiH < 0);
    final double clean = 99.5;
    final BondCarry carry = new BondCarry();
    // zero repo: carry is the accrual earned plus the coupon, a few hundredths, not minus a coupon
    assertEquals(aiH + c - aiS, carry.carry(g, clean, settle, horizon, 0.0), 1e-12);
    assertTrue(Math.abs(carry.carry(g, clean, settle, horizon, 0.0)) < 0.1);
    final double r = 0.045;
    final double dirty = clean + aiS;
    final double fwdDirty = dirty * (1 + r * (horizon - settle) / 360.0)
        - c * (1 + r * (horizon - pay) / 360.0);
    assertEquals(fwdDirty - aiH, carry.forwardCleanPrice(g, clean, settle, horizon, r), 1e-12);
    // a futures price that delivers at exactly the repo forward implies that repo rate
    final double cf = 0.95;
    final double futures = (fwdDirty - aiH) / cf;
    final io.ratesalgo.analytics.futures.BasisAnalytics.Result res =
        new io.ratesalgo.analytics.futures.BasisAnalytics()
            .analyse(g, clean, futures, cf, settle, horizon, r);
    assertEquals(r, res.impliedRepo(), 1e-12);
    assertEquals(carry.carry(g, clean, settle, horizon, r), res.carry(), 1e-12);
    assertEquals(0.0, res.netBasis(), 1e-12);
  }

  @Test
  void zSpreadAndAssetSwapSpreadAreConsistent() {
    final FixedRateBond b = ust10();
    final DiscountCurve usd = Golden.quantLibCurve("USD-OIS");
    final int settle = b.settlementDate(Golden.today());
    final double dirty = 98.0 + P.accrued(b, settle, true);
    final double z = P.zSpread(b, usd, dirty, settle);
    assertEquals(dirty, P.dirtyFromCurve(b, usd, settle, z), 1e-10);
    final double asw = P.assetSwapSpread(b, usd, dirty, settle, DayCount.ACT_360);
    // both measure cheapness to the same curve: same sign, similar size for a near-par bond
    assertTrue(z > 0 && asw > 0, "z " + z + " asw " + asw);
    assertEquals(z, asw, 0.002);
    // the asset swap spread on the bond's own curve value is zero
    assertEquals(0.0, P.assetSwapSpread(b, usd, P.dirtyFromCurve(b, usd, settle), settle,
        DayCount.ACT_360), 1e-14);
  }

  @Test
  void curveGradientMatchesBumpedPillars() {
    final FixedRateBond b = ust10();
    final DiscountCurve c = Golden.quantLibCurve("USD-OIS").copy();
    final int settle = b.settlementDate(Golden.today());
    final double[] g = new double[c.pillarCount()];
    P.dirtyFromCurveGradient(b, c, settle, 1.0, g);
    final double h = 1e-7;
    for (int i = 0; i < c.pillarCount(); i++) {
      final double y = c.pillarLogDf(i);
      c.setPillarLogDf(i, y + h);
      c.update();
      final double up = P.dirtyFromCurve(b, c, settle);
      c.setPillarLogDf(i, y - h);
      c.update();
      final double dn = P.dirtyFromCurve(b, c, settle);
      c.setPillarLogDf(i, y);
      c.update();
      assertEquals((up - dn) / (2 * h), g[i], 1e-5, "pillar " + i);
    }
  }

  @Test
  void repoForwardCarryAndRollDown() {
    final FixedRateBond b = ust10();
    final BondCarry carry = new BondCarry();
    final int settle = Dates.of(2025, 1, 16);
    final int horizon = Dates.of(2025, 7, 16);
    final double clean = 97.5;
    // coupon on 2025-05-15 lies inside the horizon and is reinvested at repo
    final double repo = 0.0430;
    final double fwd = carry.forwardCleanPrice(b, clean, settle, horizon, repo);
    final double dirty = clean + P.accrued(b, settle);
    final double c = b.couponAmount(1);
    final double expected = dirty * (1 + repo * (horizon - settle) / 360.0)
        - c * (1 + repo * (horizon - Dates.of(2025, 5, 15)) / 360.0) - P.accrued(b, horizon);
    assertEquals(expected, fwd, 1e-12);
    assertEquals(clean - fwd, carry.carry(b, clean, settle, horizon, repo), 1e-12);
    final RepoCurve gc = new RepoCurve(new int[] {1, 30, 90, 180}, new double[] {0.0431, 0.0429,
        0.0425, 0.0420});
    assertEquals(carry.forwardCleanPrice(b, clean, settle, horizon, gc.rate(181) - 0.002),
        carry.forwardCleanPrice(b, clean, settle, horizon, gc, 0.002), 1e-12);
    // special financing raises the forward price drop: more carry
    assertTrue(carry.carry(b, clean, settle, horizon, 0.03) > carry.carry(b, clean, settle,
        horizon, 0.043));
    final double roll = carry.rollDown(b, 0.0455, horizon, 0.0460, 0.0455);
    assertTrue(roll > 0, "a falling curve towards the shorter maturity rolls down: " + roll);
  }

  @Test
  void repoCurveInterpolatesAndFundsInventory() {
    final RepoCurve gc = new RepoCurve(new int[] {1, 30, 90}, new double[] {0.04, 0.042, 0.045});
    assertEquals(0.04, gc.rate(0), 0.0);
    assertEquals(0.04 + 14.0 / 29.0 * 0.002, gc.rate(15), 1e-15);
    assertEquals(0.045, gc.rate(365), 0.0);
    assertEquals(0.042 - 0.005, gc.specialRate(30, 0.005), 1e-15);
    assertEquals(1e8 * 0.04 * 7 / 360.0, RepoCurve.fundingCharge(1e8, 7, 0.04), 1e-6);
    assertEquals(0.05, RepoCurve.flat(0.05).rate(1000), 0.0);
    assertThrows(IllegalArgumentException.class, () -> new RepoCurve(new int[0], new double[0]));
    assertThrows(IllegalArgumentException.class,
        () -> new RepoCurve(new int[] {5, 5}, new double[] {0.01, 0.02}));
  }

  @Test
  void gcRepoFromNewYorkFedTgcrAndBgcrFundsInventory() throws IOException {
    // data/fixtures/repo_gc_2024.csv: published TGCR and BGCR (NY Fed), percent
    final List<String> lines = Files.readAllLines(
        Golden.repoRoot().resolve(Path.of("data", "fixtures", "repo_gc_2024.csv")),
        StandardCharsets.UTF_8);
    double tgcr = Double.NaN;
    double bgcr = Double.NaN;
    for (final String line : lines.subList(1, lines.size())) {
      final String[] f = line.split(",", -1);
      if (f[0].equals("2025-01-14")) {
        if (f[1].equals("TGCR")) {
          tgcr = Double.parseDouble(f[2]) / 100.0;
        } else if (f[1].equals("BGCR")) {
          bgcr = Double.parseDouble(f[2]) / 100.0;
        }
      }
    }
    assertTrue(tgcr > 0.03 && tgcr < 0.06 && Math.abs(tgcr - bgcr) < 0.001, tgcr + " " + bgcr);
    // overnight point from TGCR; one-month and three-month points declared (DR-P1-07)
    final RepoCurve gc = new RepoCurve(new int[] {1, 30, 90},
        new double[] {tgcr, tgcr - 0.0002, tgcr - 0.0006});
    final double charge = RepoCurve.fundingCharge(1e8, 30, gc.specialRate(30, 0.0025));
    assertEquals(1e8 * (tgcr - 0.0002 - 0.0025) * 30 / 360.0, charge, 1e-6);
  }

  @Test
  void svenssonParametersFromTheBundesbankFixtureAndRefit() throws IOException {
    final List<String> lines = Files.readAllLines(
        Golden.repoRoot().resolve(Path.of("data", "fixtures", "bund_svensson_2024.csv")),
        StandardCharsets.UTF_8);
    final String[] last = lines.get(lines.size() - 1).split(",", -1);
    final double[] params = new double[6];
    for (int i = 0; i < 6; i++) {
      params[i] = Double.parseDouble(last[i + 1]);
    }
    final NelsonSiegelSvensson nss = new NelsonSiegelSvensson(params);
    final double[] mats = {0.5, 1, 2, 3, 5, 7, 10, 15, 20, 30};
    final double[] ys = new double[mats.length];
    for (int i = 0; i < mats.length; i++) {
      ys[i] = nss.zero(mats[i]);
      assertTrue(ys[i] > 0.0 && ys[i] < 6.0, "Bund zero yield in percent at " + mats[i]);
    }
    assertEquals(params[0] + params[1], nss.zero(0.0), 1e-15);
    final double[] rms = new double[1];
    final double[] start = {2.5, -0.5, 0.5, 1.0, 2.0, 10.0};
    final NelsonSiegelSvensson fit = NelsonSiegelSvensson.fit(mats, ys, start, rms);
    System.out.println("NSS refit to Bundesbank " + last[0] + " curve rms " + rms[0]);
    assertTrue(rms[0] < 1e-6, "rms " + rms[0]);
    for (final double m : new double[] {4, 12, 25}) {
      assertEquals(nss.zero(m), fit.zero(m), 1e-5);
    }
    assertEquals(6, fit.parameters().length);
    assertThrows(IllegalArgumentException.class,
        () -> new NelsonSiegelSvensson(new double[] {1, 1, 1, 1, 0, 1}));
  }

  @Test
  void cubicSplineInterpolatesAndFlattensOutside() {
    final CubicSplineCurve s =
        new CubicSplineCurve(new double[] {1, 2, 5, 10, 30}, new double[] {4.2, 4.1, 4.2, 4.4,
            4.7});
    assertEquals(4.1, s.value(2), 0.0);
    assertEquals(4.2, s.value(0.5), 0.0);
    assertEquals(4.7, s.value(40), 0.0);
    final double mid = s.value(7);
    assertTrue(mid > 4.2 && mid < 4.4);
    final CubicSplineCurve two = new CubicSplineCurve(new double[] {1, 3}, new double[] {1, 3});
    assertEquals(2.0, two.value(2), 1e-15);
    assertThrows(IllegalArgumentException.class,
        () -> new CubicSplineCurve(new double[] {1}, new double[] {1}));
    assertThrows(IllegalArgumentException.class,
        () -> new CubicSplineCurve(new double[] {2, 1}, new double[] {1, 2}));
  }

  @Test
  void yieldRiskIsConsistentWithFiniteDifferences() {
    final FixedRateBond b = ust10();
    final int settle = Dates.of(2025, 1, 16);
    final double y = 0.0466;
    final double h = 1e-6;
    final double up = P.dirtyPrice(b, y + h, settle, false);
    final double dn = P.dirtyPrice(b, y - h, settle, false);
    final double mid = P.dirtyPrice(b, y, settle, false);
    assertEquals(-(up - dn) / (2 * h) / mid, P.modifiedDuration(b, y, settle, false), 1e-7);
    assertEquals((up - 2 * mid + dn) / (h * h) / mid, P.convexity(b, y, settle, false), 1e-2);
    assertEquals(P.cleanPrice(b, y, settle, false), P.cleanPrice(b, y, settle), 0.0);
    assertEquals(y, P.yieldFromClean(b, P.cleanPrice(b, y, settle), settle, false), 1e-14);
    assertEquals(10.0, b.maturity() > b.datedDate() ? 10.0 : 0.0, 0.0);
    assertEquals(0.0425, b.coupon(), 0.0);
    assertEquals("UST 4.25 2034", b.name());
    assertThrows(IllegalStateException.class,
        () -> P.dirtyPrice(b, y, Dates.of(2035, 1, 1), false));
  }
}
