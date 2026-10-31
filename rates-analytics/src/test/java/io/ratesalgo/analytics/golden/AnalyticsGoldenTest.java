package io.ratesalgo.analytics.golden;

import static io.ratesalgo.analytics.golden.Golden.date;
import static io.ratesalgo.analytics.golden.Golden.integer;
import static io.ratesalgo.analytics.golden.Golden.list;
import static io.ratesalgo.analytics.golden.Golden.num;
import static io.ratesalgo.analytics.golden.Golden.raw;
import static io.ratesalgo.analytics.golden.Golden.str;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.analytics.bond.BondConvention;
import io.ratesalgo.analytics.bond.BondPricer;
import io.ratesalgo.analytics.bond.FixedRateBond;
import io.ratesalgo.analytics.curve.CurveBuilder;
import io.ratesalgo.analytics.curve.DiscountCurve;
import io.ratesalgo.analytics.curve.HullWhite;
import io.ratesalgo.analytics.curve.OisFutureInstrument;
import io.ratesalgo.analytics.date.Calendars;
import io.ratesalgo.analytics.date.Dates;
import io.ratesalgo.analytics.date.DayCount;
import io.ratesalgo.analytics.date.HolidayCalendar;
import io.ratesalgo.analytics.futures.BasisAnalytics;
import io.ratesalgo.analytics.futures.ConversionFactors;
import io.ratesalgo.analytics.futures.TreasuryFutureContract;
import io.ratesalgo.analytics.risk.Dv01Ladder;
import io.ratesalgo.analytics.risk.KeyRateRisk;
import io.ratesalgo.analytics.swap.FixingHistory;
import io.ratesalgo.analytics.swap.OisSwap;
import io.ratesalgo.analytics.swap.OisSwapPricer;
import io.ratesalgo.analytics.swap.SwapConvention;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The P1 gate: every priced instrument in data/golden/analytics_golden.json (QuantLib 1.44)
 * against this module, with the tolerances of API_SPEC.md Section 7. Each test prints its largest
 * deviation as a line starting with {@code GOLDEN} so the figures can be quoted with their source.
 */
class AnalyticsGoldenTest {

  /**
   * PV tolerance as a fraction of notional (API_SPEC.md Section 7). Tightened from 1e-8 by
   * DR-R2R-11: 54 of the 83 swaps are spot par swaps with PV near zero, and a payment-lag or
   * day-count slip moves them by less than 1e-8 of notional; the worst measured is 8.5e-15.
   */
  static final double PV_TOL = 1e-12;

  /** Par rate tolerance, decimal (1e-10 is 1e-6 bp; tightened from 1e-6 by DR-R2R-11). */
  static final double PAR_TOL = 1e-10;

  /** Bond price tolerance per 100 face. */
  static final double PRICE_TOL = 1e-9;

  /** Yield tolerance, decimal. */
  static final double YIELD_TOL = 1e-10;

  /** Curve discount factors: Java bootstrap against QuantLib-priced bootstrap. */
  static final double DF_TOL = 1e-10;

  private static void report(final String what, final double value) {
    System.out.println("GOLDEN " + what + " = " + value);
  }

  @Test
  void instrumentCountMeetsGate() {
    final int count = integer(Golden.load(), "instrument_count");
    report("instrument_count", count);
    assertTrue(count >= 200, "need at least 200 priced instruments, have " + count);
    assertEquals("1.44", str(Golden.load(), "quantlib_version"));
  }

  @Test
  void calendarsMatchQuantLibEveryWeekday2000To2060() {
    final Map<String, Object> cals = Golden.map(Golden.load(), "calendars");
    int checked = 0;
    for (final String name : cals.keySet()) {
      final HolidayCalendar cal = Calendars.byName(name);
      final Set<Integer> hol = new HashSet<>();
      for (final Object o : raw(cals, name)) {
        hol.add(Dates.parse((String) o));
      }
      for (int d = Dates.of(2000, 1, 1); d <= Dates.of(2060, 12, 31); d++) {
        if (Dates.isWeekend(d)) {
          continue;
        }
        assertEquals(!hol.contains(d), cal.isBusinessDay(d), name + " on " + Dates.format(d));
        checked++;
      }
    }
    report("calendar weekdays checked", checked);
  }

  @Test
  void javaBootstrapMatchesQuantLibCurvesAndRepricesInstruments() {
    double maxDf = 0.0;
    double maxReprice = 0.0;
    for (final Map<String, Object> c : list(Golden.load(), "curves")) {
      final String id = str(c, "id");
      final CurveBuilder b = Golden.javaBuilder(id);
      final DiscountCurve ql = Golden.quantLibCurve(id);
      final DiscountCurve java = b.curve();
      assertEquals(ql.pillarCount(), java.pillarCount(), id);
      for (int i = 0; i < ql.pillarCount(); i++) {
        assertEquals(Dates.format(ql.pillarDate(i)), Dates.format(java.pillarDate(i)),
            id + " pillar " + i + " " + b.instruments().get(i).label());
        final double diff = Math.abs(ql.df(ql.pillarDate(i)) - java.df(java.pillarDate(i)));
        maxDf = Math.max(maxDf, diff);
        assertTrue(diff <= DF_TOL, id + " pillar " + i + " DF diff " + diff);
      }
      final double err = b.maxRepricingError();
      maxReprice = Math.max(maxReprice, err);
      assertTrue(err <= 1e-10, id + " repricing error " + err);
    }
    report("max |DF java - DF quantlib| over pillars", maxDf);
    report("max curve instrument repricing error", maxReprice);
  }

  private static OisSwap swap(final Map<String, Object> r) {
    SwapConvention conv = SwapConvention.forCurrency(str(r, "currency"));
    final int lb = integer(r, "lookback");
    final int lo = integer(r, "lockout");
    final boolean os = (Boolean) r.get("observation_shift");
    if (lb != 0 || lo != 0 || os) {
      conv = conv.withObservation(lb, lo, os);
    }
    return OisSwap.of(
        conv, date(r, "start"), date(r, "end"), num(r, "notional"), num(r, "fixed_rate"),
        (Boolean) r.get("payer"));
  }

  @Test
  void swapsMatchQuantLibOnBothCurves() {
    final OisSwapPricer pricer = new OisSwapPricer();
    final int today = Golden.today();
    double maxPv = 0.0;
    double maxPar = 0.0;
    double maxPvJavaCurve = 0.0;
    int n = 0;
    for (final Map<String, Object> r : list(Golden.load(), "swaps")) {
      final String ccy = str(r, "currency");
      final OisSwap s = swap(r);
      final FixingHistory fx = Golden.fixings(ccy);
      final DiscountCurve ql = Golden.quantLibCurve(ccy + "-OIS");
      final double notional = num(r, "notional");
      final double pv = pricer.pv(s, ql, fx, today);
      final double par = pricer.parRate(s, ql, fx, today);
      final double dPv = Math.abs(pv - num(r, "pv")) / notional;
      final double dPar = Math.abs(par - num(r, "par_rate"));
      assertTrue(dPv <= PV_TOL, str(r, "id") + " pv " + pv + " vs " + num(r, "pv"));
      assertTrue(dPar <= PAR_TOL, str(r, "id") + " par " + par + " vs " + num(r, "par_rate"));
      final double pvJava = pricer.pv(s, Golden.javaBuilder(ccy + "-OIS").curve(), fx, today);
      final double dJava = Math.abs(pvJava - num(r, "pv")) / notional;
      assertTrue(dJava <= PV_TOL, str(r, "id") + " pv on Java curve " + pvJava);
      maxPv = Math.max(maxPv, dPv);
      maxPar = Math.max(maxPar, dPar);
      maxPvJavaCurve = Math.max(maxPvJavaCurve, dJava);
      n++;
    }
    report("swaps", n);
    report("max |PV java - PV quantlib| / notional (QuantLib curve)", maxPv);
    report("max |PV java - PV quantlib| / notional (Java bootstrap)", maxPvJavaCurve);
    report("max |par java - par quantlib|", maxPar);
  }

  @Test
  void parallelDv01ByAlgorithmicDifferentiationMatchesQuantLibBumps() {
    final OisSwapPricer pricer = new OisSwapPricer();
    final int today = Golden.today();
    double maxRel = 0.0;
    double maxSum = 0.0;
    for (final Map<String, Object> r : list(Golden.load(), "swaps")) {
      final String ccy = str(r, "currency");
      final CurveBuilder b = Golden.javaBuilder(ccy + "-OIS");
      final OisSwap s = swap(r);
      final FixingHistory fx = Golden.fixings(ccy);
      final double[] g = new double[b.curve().pillarCount()];
      pricer.pvGradient(s, b.curve(), b.curve(), fx, today, g, g);
      final Dv01Ladder ladder = KeyRateRisk.fromGradient(b, ccy, g);
      final double ql = num(r, "dv01_parallel_bump");
      final double rel = Math.abs(ladder.parallel() - ql) / Math.abs(ql);
      maxRel = Math.max(maxRel, rel);
      // QuantLib side is a 1bp central difference: its truncation error is third order, about
      // 1e-6 relative on a 30Y swap; the gate (brief) is 1e-4.
      assertTrue(rel <= 1e-5, str(r, "id") + " dv01 " + ladder.parallel() + " vs " + ql);
      final double sumRel =
          Math.abs(ladder.keyRateSum() - ladder.parallel()) / Math.abs(ladder.parallel());
      maxSum = Math.max(maxSum, sumRel);
      assertTrue(sumRel <= 1e-9, "key-rate sum");
    }
    report("max relative |DV01 AD - DV01 QuantLib central bump|", maxRel);
    report("max relative |key-rate sum - parallel|", maxSum);
  }

  @Test
  void keyRateLaddersMatchQuantLibBumpsPillarByPillar() {
    final OisSwapPricer pricer = new OisSwapPricer();
    final int today = Golden.today();
    final CurveBuilder b = Golden.javaBuilder("USD-OIS");
    double maxAbs = 0.0;
    for (final Map<String, Object> l : list(Golden.load(), "key_rate_ladders")) {
      final String id = str(l, "swap");
      final Map<String, Object> r =
          list(Golden.load(), "swaps").stream()
              .filter(x -> id.equals(x.get("id")))
              .findFirst()
              .orElseThrow();
      final OisSwap s = swap(r);
      final double[] g = new double[b.curve().pillarCount()];
      pricer.pvGradient(s, b.curve(), b.curve(), Golden.fixings("USD"), today, g, g);
      final Dv01Ladder ladder = KeyRateRisk.fromGradient(b, "USD", g);
      final List<Object> ql = raw(l, "key_rate_dv01");
      final List<Object> tenors = raw(l, "tenors");
      assertEquals(tenors, new ArrayList<Object>(ladder.tenors()));
      double scale = 0.0;
      for (final Object v : ql) {
        scale += Math.abs((Double) v);
      }
      for (int i = 0; i < ql.size(); i++) {
        final double d = Math.abs(ladder.dv01(i) - (Double) ql.get(i));
        maxAbs = Math.max(maxAbs, d / scale);
        assertTrue(d <= 1e-6 * scale, id + " pillar " + tenors.get(i) + " " + ladder.dv01(i)
            + " vs " + ql.get(i));
      }
    }
    report("max |key-rate AD - key-rate QuantLib bump| / sum |key-rates|", maxAbs);
  }

  @Test
  void sofrFuturesMatchQuantLib() {
    final DiscountCurve ql = Golden.quantLibCurve("USD-OIS");
    final FixingHistory fx = Golden.fixings("USD");
    final int today = Golden.today();
    double maxPrice = 0.0;
    for (final Map<String, Object> r : list(Golden.load(), "sofr_futures")) {
      final boolean three = "future3m".equals(str(r, "kind"));
      final int start = date(r, "start");
      final int end = date(r, "end");
      final double ca =
          HullWhite.futuresConvexity(
              HullWhite.DEFAULT_A, HullWhite.DEFAULT_SIGMA, (start - today) / 365.0,
              (end - today) / 365.0);
      assertEquals(num(r, "convexity"), ca, 1e-15, "convexity " + str(r, "code"));
      final OisFutureInstrument f =
          three
              ? OisFutureInstrument.threeMonth(str(r, "code"), integer(r, "year"),
                  integer(r, "month"), Calendars.US_SOFR, DayCount.ACT_360, ca, 0.0)
              : OisFutureInstrument.oneMonth(str(r, "code"), integer(r, "year"),
                  integer(r, "month"), Calendars.US_SOFR, DayCount.ACT_360, ca, 0.0);
      final double price = 100.0 * (1.0 - (ca + f.rate(ql, fx, today)));
      final double d = Math.abs(price - num(r, "price"));
      maxPrice = Math.max(maxPrice, d);
      assertTrue(d <= 1e-9, str(r, "code") + " " + price + " vs " + num(r, "price"));
    }
    report("max |SOFR futures price java - quantlib|", maxPrice);
  }

  static BondConvention market(final String m) {
    return switch (m) {
      case "UST" -> BondConvention.UST;
      case "BUND" -> BondConvention.BUND;
      case "GILT" -> BondConvention.GILT;
      default -> throw new IllegalArgumentException(m);
    };
  }

  @Test
  void bondsMatchQuantLib() {
    final BondPricer p = new BondPricer();
    final DiscountCurve usd = Golden.quantLibCurve("USD-OIS");
    double maxPrice = 0.0;
    double maxYield = 0.0;
    double maxRisk = 0.0;
    int n = 0;
    for (final Map<String, Object> r : list(Golden.load(), "bonds")) {
      final String id = str(r, "id");
      final FixedRateBond b =
          new FixedRateBond(id, market(str(r, "market")), num(r, "coupon"), date(r, "dated"),
              date(r, "maturity"));
      final int settle = b.settlementDate(date(r, "trade"));
      assertEquals(str(r, "settlement"), Dates.format(settle), id);
      final double y = num(r, "yield");
      final double clean = p.cleanPrice(b, y, settle);
      final double accrued = p.accrued(b, settle);
      final double dPrice =
          Math.max(Math.abs(clean - num(r, "clean")), Math.abs(accrued - num(r, "accrued")));
      maxPrice = Math.max(maxPrice, dPrice);
      assertTrue(dPrice <= PRICE_TOL, id + " clean " + clean + " accrued " + accrued);
      final double street = p.yieldFromClean(b, num(r, "clean"), settle, false);
      final double trueY = p.yieldFromClean(b, num(r, "clean"), settle, true);
      final double dY =
          Math.max(Math.abs(street - num(r, "street_yield")), Math.abs(trueY - num(r,
              "true_yield")));
      maxYield = Math.max(maxYield, dY);
      assertTrue(dY <= YIELD_TOL, id + " yields " + street + " " + trueY);
      final double md = p.modifiedDuration(b, y, settle, false);
      final double cx = p.convexity(b, y, settle, false);
      final double dv = p.dv01(b, y, settle, false);
      final double dRisk =
          Math.max(
              Math.abs(md / num(r, "modified_duration") - 1.0),
              Math.max(Math.abs(cx / num(r, "convexity") - 1.0),
                  Math.abs(dv / num(r, "dv01") - 1.0)));
      maxRisk = Math.max(maxRisk, dRisk);
      assertTrue(dRisk <= 1e-10, id + " duration " + md + " convexity " + cx + " dv01 " + dv);
      if (r.containsKey("z_spread")) {
        final double onCurve = p.dirtyFromCurve(b, usd, settle);
        assertEquals(num(r, "dirty_on_curve"), onCurve, PRICE_TOL, id + " dirty on curve");
        final double z = p.zSpread(b, usd, num(r, "clean") + p.accrued(b, settle, true), settle);
        assertEquals(num(r, "z_spread"), z, YIELD_TOL, id + " z-spread");
      }
      n++;
    }
    report("bonds", n);
    report("max |price or accrued java - quantlib| per 100", maxPrice);
    report("max |street or true yield java - quantlib|", maxYield);
    report("max relative |duration, convexity, DV01 java - quantlib|", maxRisk);
  }

  @Test
  void conversionFactorsMatchQuantLibExactly() {
    final BondPricer p = new BondPricer();
    int n = 0;
    for (final Map<String, Object> r : list(Golden.load(), "conversion_factors")) {
      final String ex = str(r, "exchange");
      final double cf;
      switch (ex) {
        case "CME" -> {
          final TreasuryFutureContract k = TreasuryFutureContract.valueOf(str(r, "contract"));
          cf = ConversionFactors.cme(num(r, "coupon"), date(r, "maturity"), integer(r, "year"),
              integer(r, "month"), k);
        }
        case "EUREX" -> {
          final FixedRateBond b = new FixedRateBond("b", BondConvention.BUND, num(r, "coupon"),
              date(r, "dated"), date(r, "maturity"));
          cf = ConversionFactors.eurex(b, date(r, "delivery"), p);
        }
        default -> {
          final FixedRateBond b = new FixedRateBond("b", BondConvention.GILT, num(r, "coupon"),
              date(r, "dated"), date(r, "maturity"));
          cf = ConversionFactors.longGilt(b, integer(r, "year"), integer(r, "month"), p);
        }
      }
      assertEquals(num(r, "factor"), cf, 1e-12, ex + " " + str(r, "maturity"));
      n++;
    }
    report("conversion factors equal to the published rounding", n);
  }

  @Test
  void basisImpliedRepoAndCtdMatch() {
    final BasisAnalytics a = new BasisAnalytics();
    double max = 0.0;
    for (final Map<String, Object> basket : list(Golden.load(), "basis")) {
      final TreasuryFutureContract k = TreasuryFutureContract.valueOf(str(basket, "contract"));
      final int first = Dates.of(integer(basket, "year"), integer(basket, "month"), 1);
      final List<BasisAnalytics.Result> results = new ArrayList<>();
      for (final Map<String, Object> r : list(basket, "bonds")) {
        final int dated = date(r, "dated");
        final int mat = date(r, "maturity");
        assertTrue(
            k.isDeliverable(ConversionFactors.wholeMonths(first, mat),
                ConversionFactors.wholeMonths(dated, mat)),
            k + " deliverable " + str(r, "maturity"));
        final FixedRateBond b = new FixedRateBond(str(r, "maturity"), BondConvention.UST,
            num(r, "coupon"), dated, mat);
        final double cf = ConversionFactors.cme(num(r, "coupon"), mat, integer(basket, "year"),
            integer(basket, "month"), k);
        assertEquals(num(r, "conversion_factor"), cf, 1e-12);
        final BasisAnalytics.Result res = a.analyse(b, num(r, "clean"),
            num(basket, "futures_price"), cf, date(basket, "settlement"),
            date(basket, "delivery"), num(basket, "repo"));
        results.add(res);
        final double[] java = {res.impliedRepo(), res.netBasis(), res.grossBasis(), res.carry()};
        final String[] keys = {"implied_repo", "net_basis", "gross_basis", "carry"};
        for (int j = 0; j < keys.length; j++) {
          final double d = Math.abs(java[j] - num(r, keys[j]));
          max = Math.max(max, d);
          assertTrue(d <= 1e-10, k + " " + str(r, "maturity") + " " + keys[j] + " " + java[j]);
        }
      }
      assertEquals(integer(basket, "ctd_index"), BasisAnalytics.cheapestToDeliver(results),
          k + " CTD");
    }
    report("max |basis, carry or implied repo java - quantlib|", max);
  }

  @Test
  void bumpedLadderAgreesWithAlgorithmicDifferentiation() {
    // the stated bump: one basis point, central; AD versus bump to relative 1e-4 of the parallel
    final CurveBuilder b = Golden.javaBuilder("GBP-OIS");
    final OisSwapPricer pricer = new OisSwapPricer();
    final int today = Golden.today();
    final OisSwap s =
        OisSwap.spot(SwapConvention.GBP_SONIA, today, 84, 1e8, 0.045, true);
    final FixingHistory fx = Golden.fixings("GBP");
    final double[] g = new double[b.curve().pillarCount()];
    pricer.pvGradient(s, b.curve(), b.curve(), fx, today, g, g);
    final Dv01Ladder ad = KeyRateRisk.fromGradient(b, "GBP", g);
    final Dv01Ladder bump =
        KeyRateRisk.byBumping(b, "GBP", () -> pricer.pv(s, b.curve(), fx, today), KeyRateRisk.BP);
    double max = 0.0;
    for (int i = 0; i < ad.size(); i++) {
      final double d = Math.abs(ad.dv01(i) - bump.dv01(i)) / Math.abs(ad.parallel());
      max = Math.max(max, d);
      assertTrue(d <= 1e-4, "pillar " + ad.tenors().get(i));
    }
    final double par = Math.abs(ad.parallel() - bump.parallel()) / Math.abs(ad.parallel());
    assertTrue(par <= 1e-4, "parallel " + ad.parallel() + " vs " + bump.parallel());
    report("max |key-rate AD - key-rate bump| / parallel (GBP 7Y, 1bp central)", max);
    report("relative |parallel AD - parallel bump| (GBP 7Y, 1bp central)", par);
  }
}
