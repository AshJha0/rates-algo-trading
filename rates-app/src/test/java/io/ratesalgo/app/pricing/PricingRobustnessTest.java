package io.ratesalgo.app.pricing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.analytics.bond.BondConvention;
import io.ratesalgo.analytics.bond.BondPricer;
import io.ratesalgo.analytics.bond.FixedRateBond;
import io.ratesalgo.analytics.curve.CurveBuilder;
import io.ratesalgo.analytics.curve.CurveInstrument;
import io.ratesalgo.analytics.curve.Interpolation;
import io.ratesalgo.analytics.curve.OisSwapInstrument;
import io.ratesalgo.analytics.date.Dates;
import io.ratesalgo.analytics.swap.FixingHistory;
import io.ratesalgo.analytics.swap.SwapConvention;
import io.ratesalgo.api.BookSource;
import io.ratesalgo.api.CurrencyCode;
import io.ratesalgo.api.Dv01Vector;
import io.ratesalgo.api.KeyRateTenors;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The pricing binding under bad input and its options (review 2026-11-23, A3, A5, A8, A12): a bad
 * curve quote is counted and dropped without disturbing the curve, a repeated quote does not
 * reprice, bonds are built from their dated date, and bond risk at the market price is available
 * behind an option that is off by default.
 */
class PricingRobustnessTest {

  private static final UsdUniverse UNIVERSE = UsdUniverse.standard();
  private static final long T0 = 1_793_626_000_000_000_000L;
  /** One million face in minor units. */
  private static final long ONE_MILLION = 100_000_000L;

  private static long[] snapshot(final AnalyticsPricing p) {
    final List<Long> bits = new ArrayList<>();
    final Dv01Vector v = new Dv01Vector(CurrencyCode.USD);
    for (final int id : UNIVERSE.instrumentIds()) {
      bits.add(Double.doubleToLongBits(p.parRate(id)));
      bits.add(p.fairMid(id));
      p.pv01(id, ONE_MILLION, v);
      for (int k = 0; k < KeyRateTenors.COUNT; k++) {
        bits.add(Double.doubleToLongBits(v.get(k)));
      }
    }
    for (final CurveInstrument i : p.builder().instruments()) {
      bits.add(Double.doubleToLongBits(i.quote()));
    }
    for (int i = 0; i < p.builder().curve().pillarCount(); i++) {
      bits.add(Double.doubleToLongBits(p.builder().curve().pillarLogDf(i)));
    }
    return bits.stream().mapToLong(Long::longValue).toArray();
  }

  private static void assertSame(final long[] before, final AnalyticsPricing p, final String what) {
    final long[] after = snapshot(p);
    for (int i = 0; i < before.length; i++) {
      assertEquals(before[i], after[i], what + ": value " + i + " changed");
    }
  }

  @Test
  void aQuoteOutsideTheBandIsCountedAndChangesNothing() {
    final AnalyticsPricing p = new AnalyticsPricing(UNIVERSE, T0);
    final UsdUniverse.SwapSpec ten = UNIVERSE.swaps().get(2);
    final long[] before = snapshot(p);
    final long good = ten.initialMid();
    // ten times and a hundredth of the rate (a decimal-place and a unit error), a sign error, and
    // the extremes of the wire type
    final long[] bad = {good * 10, good / 100, -good, good + 1_010_000_000L, Long.MAX_VALUE, Long.MIN_VALUE};
    for (int k = 0; k < bad.length; k++) {
      assertTrue(p.onCurveInput(ten.instrumentId(), bad[k], T0 + 1_000L * (k + 1)), "still a curve pillar");
      assertEquals(k + 1, p.rejectedInputs());
      assertSame(before, p, "after bad quote " + bad[k]);
      assertEquals(T0, p.referenceNanos(ten.instrumentId()), "a bad quote must not make the curve look fresh");
    }
    assertEquals(0, p.rebuilds());
    // just inside the default band of 100 bp, and the binding still works
    assertTrue(p.onCurveInput(ten.instrumentId(), good + 990_000_000L, T0 + 10_000));
    assertEquals(bad.length, p.rejectedInputs());
    assertEquals(1, p.rebuilds());
    assertEquals((good + 990_000_000L) * 1e-11, p.parRate(ten.instrumentId()), 1e-12);
    assertEquals(T0 + 10_000, p.referenceNanos(ten.instrumentId()));
  }

  @Test
  void theBandIsConfigurableAndValidated() {
    final AnalyticsPricing p = new AnalyticsPricing(UNIVERSE, T0, new AnalyticsPricing.Options(false, 0.0005));
    final UsdUniverse.SwapSpec five = UNIVERSE.swaps().get(1);
    // percent mantissa: 0.06 percent is 6 bp, outside a 5 bp band; 4 bp is inside
    p.onCurveInput(five.instrumentId(), five.initialMid() + 60_000_000L, T0 + 1);
    assertEquals(1, p.rejectedInputs());
    assertEquals(0, p.rebuilds());
    p.onCurveInput(five.instrumentId(), five.initialMid() + 40_000_000L, T0 + 2);
    assertEquals(1, p.rejectedInputs());
    assertEquals(1, p.rebuilds());
    assertEquals(0.0005, p.options().maxQuoteMove(), 0.0);
    assertEquals(0.01, AnalyticsPricing.Options.defaults().maxQuoteMove(), 0.0);
    for (final double band : new double[] {0.0, -0.01, Double.NaN}) {
      assertThrows(IllegalArgumentException.class, () -> new AnalyticsPricing.Options(false, band));
    }
  }

  @Test
  void aQuoteTheBootstrapCannotSolveIsCountedAndTheLastGoodCurveStays() {
    // a band wide enough to let through a quote with no solution: a par rate of -100 percent on
    // the first pillar would need a negative discount factor
    final AnalyticsPricing p = new AnalyticsPricing(UNIVERSE, T0, new AnalyticsPricing.Options(false, 5.0));
    final UsdUniverse.SwapSpec two = UNIVERSE.swaps().get(0);
    final UsdUniverse.SwapSpec thirty = UNIVERSE.swaps().get(3);
    final long[] before = snapshot(p);
    assertTrue(p.onCurveInput(two.instrumentId(), -100_000_000_000L, T0 + 1));
    assertEquals(1, p.rejectedInputs());
    assertEquals(0, p.rebuilds());
    assertSame(before, p, "after the unsolvable quote");
    assertEquals(T0, p.referenceNanos(two.instrumentId()));
    // the next good quote prices exactly as it does on a binding that never saw the bad one
    final AnalyticsPricing clean = new AnalyticsPricing(UNIVERSE, T0);
    final long moved = thirty.initialMid() + 25_000_000L;
    p.onCurveInput(thirty.instrumentId(), moved, T0 + 2);
    clean.onCurveInput(thirty.instrumentId(), moved, T0 + 2);
    assertSame(snapshot(clean), p, "after recovery");
  }

  @Test
  void aRepeatedQuoteRefreshesTheReferenceTimeWithoutRepricing() {
    final AnalyticsPricing p = new AnalyticsPricing(UNIVERSE, T0);
    final UsdUniverse.SwapSpec ten = UNIVERSE.swaps().get(2);
    p.onCurveInput(ten.instrumentId(), ten.initialMid() + 15_000_000L, T0 + 5);
    final long[] before = snapshot(p);
    assertEquals(1, p.rebuilds());
    // replay determinism: the state after a repeat must be bit for bit the state a full reprice
    // of the same curve gives, which is the state before it
    assertTrue(p.onCurveInput(ten.instrumentId(), ten.initialMid() + 15_000_000L, T0 + 9));
    assertEquals(1, p.rebuilds());
    assertEquals(0, p.rejectedInputs());
    assertSame(before, p, "after a repeated quote");
    for (final UsdUniverse.SwapSpec s : UNIVERSE.swaps()) {
      assertEquals(T0 + 9, p.referenceNanos(s.instrumentId()), s.tenor());
    }
    // an older timestamp never moves the reference time back
    p.onCurveInput(ten.instrumentId(), ten.initialMid() + 15_000_000L, T0 + 7);
    assertEquals(T0 + 9, p.referenceNanos(ten.instrumentId()));
  }

  private static FixedRateBond bond(final UsdUniverse.BondSpec b, final java.time.LocalDate dated) {
    return new FixedRateBond(b.securityId(), BondConvention.UST, b.bond().couponMantissa() * 1e-11,
        Dates.fromLocalDate(dated), Dates.fromLocalDate(b.bond().maturity()));
  }

  /**
   * Parallel DV01 per unit face by moving every curve quote a tenth of a basis point up and down
   * and repricing. Central difference: the third-order term is about T^2 h^2 / 6, 1.5e-8 relative
   * for the 30-year, and the 1e-12 solver tolerance on quotes adds about 1e-7 relative, so the
   * callers compare to 1e-6.
   */
  private static double bumpedParallel(final FixedRateBond b, final int settle, final double spread) {
    final BondPricer pricer = new BondPricer();
    double sum = 0.0;
    for (int k = 0; k < UNIVERSE.swaps().size(); k++) {
      final double[] dirty = new double[2];
      for (int side = 0; side < 2; side++) {
        final List<CurveInstrument> inst = new ArrayList<>();
        for (int j = 0; j < UNIVERSE.swaps().size(); j++) {
          final UsdUniverse.SwapSpec s = UNIVERSE.swaps().get(j);
          final double bump = j == k ? (side == 0 ? 1e-5 : -1e-5) : 0.0;
          inst.add(new OisSwapInstrument(SwapConvention.USD_SOFR, UNIVERSE.valuationDate(), s.tenorMonths(), s.tenor(),
              s.initialMid() * 1e-11 + bump));
        }
        final CurveBuilder cb = new CurveBuilder(UNIVERSE.valuationDate(), inst, Interpolation.LINEAR_LOG_DF, 0.0, null,
            FixingHistory.EMPTY);
        cb.build(CurveBuilder.Method.AUTO);
        dirty[side] = pricer.dirtyFromCurve(b, cb.curve(), settle, spread);
      }
      // per 1 bp, per unit face (prices are per 100)
      sum += (dirty[0] - dirty[1]) / 2e-5 * 1e-4 * 0.01;
    }
    return sum;
  }

  @Test
  void bondsAreBuiltFromTheDatedDateAndDefaultRiskIsStillAtZeroSpread() {
    final AnalyticsPricing p = new AnalyticsPricing(UNIVERSE, T0);
    final Dv01Vector v = new Dv01Vector(CurrencyCode.USD);
    int weekendDated = 0;
    for (final UsdUniverse.BondSpec b : UNIVERSE.bonds()) {
      final FixedRateBond dated = bond(b, b.bond().datedDate());
      final int settle = Math.max(dated.settlementDate(UNIVERSE.valuationDate()), Dates.fromLocalDate(b.bond().issueDate()));
      assertTrue(p.pv01(b.instrumentId(), 100L, v));
      assertEquals(bumpedParallel(dated, settle, 0.0), v.parallel(), 1e-6 * Math.abs(v.parallel()), b.securityId());
      assertEquals(0.0, p.bondSpread(b.instrumentId()), 0.0);
      if (!b.bond().datedDate().equals(b.bond().issueDate())) {
        weekendDated++;
        // the old construction, from the issue date, has a short first coupon and so less risk;
        // the two must differ by more than the comparison tolerance or this test proves nothing
        final double old = bumpedParallel(bond(b, b.bond().issueDate()), settle, 0.0);
        assertTrue(Math.abs(old - v.parallel()) > 1e-5 * Math.abs(v.parallel()), b.securityId());
        assertTrue(Math.abs(v.parallel()) > Math.abs(old), "a full first coupon adds a little risk");
      }
    }
    assertEquals(2, weekendDated, "the on-the-run 2-year and 5-year are dated Saturday 2026-10-31");
    assertTrue(Double.isNaN(p.bondSpread(UNIVERSE.swaps().get(0).instrumentId())));
  }

  /**
   * Bond risk at the market price (review A3). Off by default; with the option on, each bond's
   * ladder is taken at the z-spread that reprices its market dirty price. The printed table is the
   * size of the change on the fixture universe, per million face.
   */
  @Test
  void bondRiskAtMarketSolvesTheSpreadAndMatchesBumpAndReprice() {
    final AnalyticsPricing zero = new AnalyticsPricing(UNIVERSE, T0);
    final AnalyticsPricing market = new AnalyticsPricing(UNIVERSE, T0, new AnalyticsPricing.Options(true, 0.01));
    assertTrue(market.options().bondRiskAtMarket());
    final BondPricer pricer = new BondPricer();
    final Dv01Vector a = new Dv01Vector(CurrencyCode.USD);
    final Dv01Vector b = new Dv01Vector(CurrencyCode.USD);
    System.out.println("A3 bond DV01 per 1mm face: tenor, clean, z-spread bp, zero-spread, at market, change %");
    for (final UsdUniverse.BondSpec spec : UNIVERSE.bonds()) {
      final FixedRateBond bond = bond(spec, spec.bond().datedDate());
      final int settle = Math.max(bond.settlementDate(UNIVERSE.valuationDate()), Dates.fromLocalDate(spec.bond().issueDate()));
      final double clean = spec.initialMid() * 1e-9;
      final double spread = market.bondSpread(spec.instrumentId());
      // the spread reprices the starting price plus accrued; Brent to 1e-15 in the spread
      assertEquals(clean + pricer.accrued(bond, settle),
          pricer.dirtyFromCurve(bond, market.builder().curve(), settle, spread), 1e-9, spec.securityId());
      zero.pv01(spec.instrumentId(), ONE_MILLION, a);
      market.pv01(spec.instrumentId(), ONE_MILLION, b);
      assertEquals(bumpedParallel(bond, settle, spread) * 1e6, b.parallel(), 1e-6 * Math.abs(b.parallel()),
          spec.securityId());
      assertTrue(b.parallel() < 0, "a long bond loses as rates rise");
      // a bond below its curve value (positive spread) has less risk than at zero spread
      assertEquals(Math.signum(spread), Math.signum(Math.abs(a.parallel()) - Math.abs(b.parallel())), spec.securityId());
      System.out.printf("A3 %s %.4f %.2f %.2f %.2f %+.2f%n", spec.bond().tenor(), clean, spread * 1e4, a.parallel(),
          b.parallel(), 100.0 * (b.parallel() / a.parallel() - 1.0));
    }
    assertEquals(0, market.failedSpreadSolves());
    // swaps are untouched by the option
    for (final UsdUniverse.SwapSpec s : UNIVERSE.swaps()) {
      zero.pv01(s.instrumentId(), ONE_MILLION, a);
      market.pv01(s.instrumentId(), ONE_MILLION, b);
      for (int k = 0; k < KeyRateTenors.COUNT; k++) {
        assertEquals(a.get(k), b.get(k), 0.0);
      }
    }
  }

  @Test
  void bondRiskAtMarketFollowsTheBookAndSurvivesABrokenOne() {
    final AnalyticsPricing p = new AnalyticsPricing(UNIVERSE, T0, new AnalyticsPricing.Options(true, 0.01));
    final UsdUniverse.BondSpec thirty = UNIVERSE.bonds().get(UNIVERSE.bonds().size() - 1);
    final UsdUniverse.SwapSpec ten = UNIVERSE.swaps().get(2);
    final Dv01Vector v = new Dv01Vector(CurrencyCode.USD);
    p.pv01(thirty.instrumentId(), ONE_MILLION, v);
    final double atStart = v.parallel();
    final double spreadAtStart = p.bondSpread(thirty.instrumentId());
    final long[] mid = {thirty.initialMid() - 2_000_000_000L};
    p.book(new BookSource() {
      @Override
      public long bestBid(final int id) {
        return id == thirty.instrumentId() ? mid[0] - 15_625_000L : io.ratesalgo.api.FairValueSource.NO_VALUE;
      }

      @Override
      public long bestAsk(final int id) {
        return id == thirty.instrumentId() ? mid[0] + 15_625_000L : io.ratesalgo.api.FairValueSource.NO_VALUE;
      }

      @Override
      public long bidSize(final int id) {
        return 0;
      }

      @Override
      public long askSize(final int id) {
        return 0;
      }

      @Override
      public long updateNanos(final int id) {
        return T0;
      }
    });
    // a repeated curve quote: no rebuild, but the bond spreads follow the book
    p.onCurveInput(ten.instrumentId(), ten.initialMid(), T0 + 1);
    assertEquals(0, p.rebuilds());
    p.pv01(thirty.instrumentId(), ONE_MILLION, v);
    assertTrue(p.bondSpread(thirty.instrumentId()) > spreadAtStart, "two points cheaper: a wider spread");
    assertTrue(Math.abs(v.parallel()) < Math.abs(atStart), "and less risk");
    // a book no spread within 500 bp can reprice: counted, and the ladder falls back to the
    // curve's own risk
    mid[0] = 1L;
    p.onCurveInput(ten.instrumentId(), ten.initialMid(), T0 + 2);
    assertEquals(1, p.failedSpreadSolves());
    assertEquals(0.0, p.bondSpread(thirty.instrumentId()), 0.0);
    final AnalyticsPricing zero = new AnalyticsPricing(UNIVERSE, T0);
    final Dv01Vector z = new Dv01Vector(CurrencyCode.USD);
    zero.pv01(thirty.instrumentId(), ONE_MILLION, z);
    p.pv01(thirty.instrumentId(), ONE_MILLION, v);
    assertEquals(z.parallel(), v.parallel(), 0.0);
    assertNotEquals(atStart, v.parallel());
  }
}
