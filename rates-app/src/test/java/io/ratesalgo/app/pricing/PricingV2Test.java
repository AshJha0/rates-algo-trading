package io.ratesalgo.app.pricing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.analytics.bond.BondCarry;
import io.ratesalgo.analytics.bond.BondConvention;
import io.ratesalgo.analytics.bond.BondPricer;
import io.ratesalgo.analytics.bond.FixedRateBond;
import io.ratesalgo.analytics.curve.SofrCurve;
import io.ratesalgo.analytics.curve.SofrCurveSpec;
import io.ratesalgo.analytics.date.Dates;
import io.ratesalgo.analytics.funding.RepoCurve;
import io.ratesalgo.analytics.swap.OisSwap;
import io.ratesalgo.analytics.swap.SwapCarryRoll;
import io.ratesalgo.analytics.swap.SwapConvention;
import io.ratesalgo.api.CarryRoll;
import io.ratesalgo.api.CurrencyCode;
import io.ratesalgo.api.Dv01Vector;
import io.ratesalgo.api.FairValueSource;
import io.ratesalgo.api.KeyRateTenors;
import io.ratesalgo.common.time.ManualClock;
import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.Test;

/**
 * The pricing v2 binding: independent fair values, the two risk ladders, carry, the business
 * date, conflation, bad input and allocation. Instrument ids: swap templates 2001 (2Y), 2002
 * (5Y), 2003 (10Y), 2004 (30Y), 2005 (12Y); on-the-run Treasuries 1003 (2Y), 1006 (5Y), 1007
 * (10Y), 1009 (30Y).
 */
class PricingV2Test {

  private static final UsdUniverse UNIVERSE = UsdUniverse.standard();
  private static final double BP = 1e-4;
  private static final long ONE_MILLION = 100_000_000L;
  private static final int SWAP_5Y = 2002;
  private static final int SWAP_10Y = 2003;
  private static final int BOND_10Y = 1007;
  private static final int BOND_30Y = 1009;

  private static V2Feed feed() {
    return feed(PricingV2.Options.defaults());
  }

  private static V2Feed feed(final PricingV2.Options options) {
    final PricingV2 p = new PricingV2(UNIVERSE, new ManualClock(V2Feed.T0), options);
    final V2Feed f = new V2Feed(p, V2Feed.T0);
    f.books(0L);
    p.book(f.book);
    return f;
  }

  /** A feed that has sent ten rounds of the declared market. */
  private static V2Feed settled() {
    final V2Feed f = feed();
    for (int k = 0; k < 10; k++) {
      f.round(0.0);
    }
    return f;
  }

  @Test
  void startsOnTheClocksBusinessDateFromTheStartingQuotes() {
    final PricingV2 p = new PricingV2(UNIVERSE, new ManualClock(V2Feed.T0), PricingV2.Options.defaults());
    assertEquals("2026-11-02", Dates.format(p.businessDate()));
    assertEquals(14, p.inputCount());
    // the four swap templates that are build inputs start fed; the other ten are seeded
    assertEquals(10, p.unfedInputs());
    assertEquals(V2InputIds.OVERNIGHT, p.inputId(0));
    assertFalse(p.inputFed(0));
    assertEquals(V2InputIds.sr1(202612), p.inputId(1));
    assertEquals(V2InputIds.sr3(202612), p.inputId(3));
    assertEquals(V2InputIds.parSwap(12), p.inputId(6));
    assertTrue(p.inputFed(7), "the 2-year template");
    for (final UsdUniverse.SwapSpec s : UNIVERSE.swaps()) {
      // the starting quote is the start-of-day mark: fair value is the quote itself
      assertEquals((double) s.initialMid(), (double) p.fairMid(s.instrumentId()), 1.0, s.tenor());
      // no time has been given yet: the marks have no reference time
      assertEquals(FairValueSource.NO_VALUE, p.referenceNanos(s.instrumentId()));
    }
    for (final UsdUniverse.BondSpec b : UNIVERSE.bonds()) {
      // the same for a bond: its starting price, to the rounding of a spread solved to 1e-15
      assertEquals((double) b.initialMid(), (double) p.fairMid(b.instrumentId()), 10.0, b.securityId());
    }
    assertEquals(FairValueSource.NO_VALUE, p.fairMid(999));
    assertEquals(FairValueSource.NO_VALUE, p.referenceNanos(999));
    assertTrue(Double.isNaN(p.fairValue(999)) && Double.isNaN(p.modelValue(999)));
    assertEquals(UNIVERSE, p.universe());
    assertEquals(9, p.directory().size());
    assertEquals(1, p.reprices());
    assertEquals(0, p.pendingInputs());
    assertEquals(1, p.leaveOneOutReferences());
    assertEquals(0, p.referenceFailures());
    assertEquals(300.0, p.options().halfLifeSeconds(), 0.0);
    // the first timed call dates the marks at the start of the business day
    p.advanceTo(V2Feed.T0);
    for (final int id : UNIVERSE.instrumentIds()) {
      assertEquals(BusinessDate.lastCut(V2Feed.T0, BusinessDate.DEFAULT_CUT_HOUR), p.referenceNanos(id));
    }
    assertEquals(1, p.reprices(), "dating the marks is not a reprice");
  }

  @Test
  void aRoundIsRepricedOnceWhenItIsComplete() {
    final V2Feed f = feed();
    final PricingV2 p = f.pricing;
    f.round(0.0);
    assertEquals(0, p.unfedInputs(), "the feed has now sent every input");
    final long before = p.reprices();
    final long solves = p.rebuilds();
    // every input but the last: held
    f.now += V2Feed.ROUND;
    final SofrCurve c = p.curve();
    for (int i = 0; i < p.inputCount(); i++) {
      assertTrue(p.onCurveInput(p.inputId(i), f.mantissa(i, V2Feed.rate(c, i) + BP), f.now));
      assertEquals(i + 1, p.pendingInputs());
      assertEquals(before, p.reprices());
    }
    final long fairBefore = p.fairMid(SWAP_10Y);
    // the last one (the 12-year mark) completes the round: one solve, one reprice
    assertTrue(p.onCurveInput(V2Feed.SWAP_12Y, Math.round((0.0380 + BP) * 1e11), f.now));
    assertEquals(0, p.pendingInputs());
    assertEquals(before + 1, p.reprices());
    assertEquals(solves + 1, p.rebuilds());
    assertNotEquals(fairBefore, p.fairMid(SWAP_10Y));
    assertEquals(f.now, p.referenceNanos(SWAP_10Y));

    // an incomplete round is applied when its oldest input is 100 ms old
    f.now += V2Feed.ROUND;
    p.onCurveInput(p.inputId(9), f.mantissa(9, V2Feed.rate(c, 9) + 2 * BP), f.now);
    p.onCurveInput(p.inputId(10), f.mantissa(10, V2Feed.rate(c, 10) + 2 * BP), f.now + 99_000_000L);
    assertEquals(2, p.pendingInputs());
    p.onCurveInput(p.inputId(11), f.mantissa(11, V2Feed.rate(c, 11) + 2 * BP), f.now + 100_000_000L);
    assertEquals(0, p.pendingInputs());
    assertEquals(before + 2, p.reprices());
    // and on request
    p.onCurveInput(p.inputId(9), f.mantissa(9, V2Feed.rate(c, 9)), f.now + 200_000_000L);
    assertEquals(1, p.pendingInputs());
    p.flush();
    assertEquals(1, p.pendingInputs(), "flush applies a round only once its time has passed");
    assertTrue(p.repriceIfDirty());
    assertEquals(0, p.pendingInputs());
    assertEquals(before + 3, p.reprices());
    assertFalse(p.repriceIfDirty());
    p.flush();
    assertEquals(before + 3, p.reprices(), "nothing held: nothing done");
    // a round that repeats the previous quotes reprices but does not solve
    final long solved = p.rebuilds();
    f.now += V2Feed.SECOND;
    f.round(0.0);
    f.round(0.0);
    assertEquals(solved + 1, p.rebuilds());
  }

  /**
   * Review finding A1. In version 1 a swap's fair value is the quote just fed in; here a quote
   * that jumps 2 bp for one round moves its own fair value by 0.12 percent of the jump (the
   * residual's gain over half a second at a 5 minute half-life) and nothing through the curve.
   */
  @Test
  void aSkewedQuoteBarelyMovesItsOwnFairValue() {
    final V2Feed f = settled();
    final PricingV2 p = f.pricing;
    final int input = p.curve().swapInput(60);
    final double fair = p.fairValue(SWAP_5Y);
    final double model = p.modelValue(SWAP_5Y);
    final double neighbour = p.fairValue(SWAP_10Y);
    f.roundWithSkew(0.0, input, 2 * BP);
    final double moved = p.fairValue(SWAP_5Y) - fair;
    // stated share: 1 - 2^(-0.5/300) = 0.115 percent of the skew, asserted with a little room
    assertTrue(moved > 0.0 && moved <= 0.0012 * 2 * BP, "own fair value moved " + moved / BP + " bp");
    assertEquals(model, p.modelValue(SWAP_5Y), 1e-15, "its own quote is not in its model value");
    // the full curve does have the quote as an input, and it reprices it
    assertEquals(V2Feed.swapRate(60) + 2 * BP, p.curve().quote(input), 1e-12);
    // the neighbours see it, as inputs of any curve do, but by less than the skew
    final double spill = Math.abs(p.fairValue(SWAP_10Y) - neighbour);
    assertTrue(spill > 0.01 * BP && spill < 1.0 * BP, "10-year moved " + spill / BP + " bp");

    // version 1 on the same quotes: the fair value is the quote
    final AnalyticsPricing v1 = new AnalyticsPricing(UNIVERSE, V2Feed.T0);
    final double v1Before = v1.parRate(SWAP_5Y);
    v1.onCurveInput(SWAP_5Y, Math.round((v1Before + 2 * BP) * 1e11), f.now);
    assertEquals(2 * BP, v1.parRate(SWAP_5Y) - v1Before, 1e-10);

    // a skew that stays is taken in with the half-life: half of it after five minutes
    for (int k = 0; k < 599; k++) {
      f.roundWithSkew(0.0, input, 2 * BP);
    }
    assertEquals(0.5 * 2 * BP, p.fairValue(SWAP_5Y) - fair, 0.02 * BP);
  }

  @Test
  void aSwapThatIsNotACurveInputIsPricedOffTheCurve() {
    final V2Feed f = settled();
    final PricingV2 p = f.pricing;
    assertEquals(-1, p.curve().swapInput(144));
    final OisSwap twelve = OisSwap.spot(SwapConvention.USD_SOFR, p.businessDate(), 144, 1.0, 0.0, true);
    assertEquals(p.curve().parRate(twelve), p.modelValue(V2Feed.SWAP_12Y), 1e-15);
    // its quote of 3.80 is the start-of-day mark, about 9 bp above a curve through 3.70 and 3.74
    final double gap = p.fairValue(V2Feed.SWAP_12Y) - p.modelValue(V2Feed.SWAP_12Y);
    assertEquals(0.0380, p.fairValue(V2Feed.SWAP_12Y), 0.01 * BP);
    assertTrue(gap > 7 * BP && gap < 11 * BP, "gap " + gap / BP);
    // the curve moves 3 bp and its own quote does not: the fair value follows the curve
    final SofrCurve c = p.curve();
    f.now += V2Feed.ROUND;
    for (int i = 0; i < p.inputCount(); i++) {
      p.onCurveInput(p.inputId(i), f.mantissa(i, V2Feed.rate(c, i) + 3 * BP), f.now);
    }
    p.repriceIfDirty();
    assertEquals(0.0380 + 3 * BP, p.fairValue(V2Feed.SWAP_12Y), 0.05 * BP);
  }

  /**
   * Review finding A1, bonds. Version 1 returns the book mid. Here a bond is priced off the
   * Treasury curve fitted to the other bonds: it moves when they move and not when its own book
   * does. A move of the SOFR curve alone, with no Treasury book moving, is a move of the spread
   * and leaves Treasury fair values where the Treasury market has them.
   */
  @Test
  void bondFairValueFollowsTheOtherTreasuriesAndNotItsOwnBook() {
    final V2Feed f = settled();
    final PricingV2 p = f.pricing;
    final long start = UNIVERSE.bond(BOND_10Y).initialMid();
    final long tick = UNIVERSE.bondTick();
    assertEquals((double) start, (double) p.fairMid(BOND_10Y), 20_000.0, "its mark, after settling");
    final double spread = p.bondSpread(BOND_10Y);
    assertEquals(spread, p.fairSpread(BOND_10Y), 0.01 * BP);
    assertTrue(Double.isNaN(p.bondSpread(SWAP_10Y)) && Double.isNaN(p.fairSpread(SWAP_10Y)));
    assertTrue(Double.isNaN(p.richCheapBp(SWAP_10Y)));
    final Dv01Vector dv = new Dv01Vector(CurrencyCode.USD);
    p.spreadPv01(BOND_10Y, 100L, dv);
    final double pricePerBp = -dv.parallel() * 100.0;

    // swaps up 5 bp, no Treasury book moves: every spread is 5 bp tighter, fair values stay
    final double before = p.fairValue(BOND_10Y);
    f.round(5 * BP);
    assertEquals(spread - 5 * BP, p.bondSpread(BOND_10Y), 0.2 * BP);
    assertEquals(before, p.fairValue(BOND_10Y), 0.1 * pricePerBp);
    assertEquals(0, p.rejectedBondBooks(), "a move common to every bond is not a bad book");

    // the version 1 binding on the same book: the book mid, whatever else happens
    final AnalyticsPricing v1 = new AnalyticsPricing(UNIVERSE, V2Feed.T0);
    v1.book(f.book);
    assertEquals(start, v1.fairMid(BOND_10Y));

    // the other three notes cheapen by about a basis point of yield (3, 6 and 22 ticks at 2, 5
    // and 30 years); the 10-year book does not move. Its fair value falls with them
    f.now += V2Feed.ROUND;
    f.book(1003, UNIVERSE.bond(1003).initialMid() - 3 * tick);
    f.book(1006, UNIVERSE.bond(1006).initialMid() - 6 * tick);
    f.book(BOND_30Y, UNIVERSE.bond(BOND_30Y).initialMid() - 22 * tick);
    f.now -= V2Feed.ROUND;
    f.round(5 * BP);
    final double fell = before - p.fairValue(BOND_10Y);
    System.out.printf("V2 10-year fair value fell %.3f bp of yield with its own book unmoved%n",
        fell / pricePerBp);
    assertTrue(fell > 0.4 * pricePerBp && fell < 1.6 * pricePerBp, "fell " + fell / pricePerBp);
    assertEquals(start, f.book.bestBid(BOND_10Y) + tick, "its own book did not move");
    assertEquals(0, p.rejectedBondBooks());

    // its own book a full point lower: held out, its last spread stands in, nothing moves
    final double fair = p.fairValue(BOND_10Y);
    final double other = p.fairValue(BOND_30Y);
    final double cheap = p.richCheapBp(BOND_10Y);
    f.book(BOND_10Y, start - 1_000_000_000L);
    f.round(5 * BP);
    assertEquals(1, p.rejectedBondBooks());
    assertEquals(fair, p.fairValue(BOND_10Y), 1e-9, "a point in the book, nothing in fair value");
    assertEquals(other, p.fairValue(BOND_30Y), 1e-9);
    // a book that stays there for twenty reprices is the market: taken as the new level, and
    // then absorbed at the half-life like any other persistent gap
    for (int k = 0; k < 25; k++) {
      f.book(BOND_10Y, start - 1_000_000_000L);
      f.round(5 * BP);
    }
    // held on nineteen reprices, taken on the twentieth
    assertEquals(19, p.rejectedBondBooks());
    assertTrue(p.bondSpread(BOND_10Y) - (spread - 5 * BP) > 9 * BP, "re-anchored a point lower");
    // ten seconds held out and three more since: a few percent of the point so far
    assertTrue(fair - p.fairValue(BOND_10Y) < 0.10, "and absorbed slowly");
    assertTrue(p.richCheapBp(BOND_10Y) - cheap > 9.0, "cheap to the fit: " + p.richCheapBp(BOND_10Y));
  }

  /**
   * Review findings A3 and A4: every risk tenor is a pillar, bonds are at their market spread,
   * and the spread is a factor of its own.
   */
  @Test
  void laddersFillTheirOwnTenorsAndBondsCarrySpreadRisk() {
    final V2Feed f = settled();
    final PricingV2 p = f.pricing;
    final Dv01Vector v = new Dv01Vector(CurrencyCode.USD);
    final Dv01Vector s = new Dv01Vector(CurrencyCode.USD);
    final int[] own = {1, 3, 5, 7};
    final int[] ids = {2001, 2002, 2003, 2004};
    for (int j = 0; j < ids.length; j++) {
      assertTrue(p.pv01(ids[j], ONE_MILLION, v));
      assertTrue(v.parallel() > 0.0, "payer convention");
      // a swap struck within a basis point of par: its whole ladder in its own tenor
      assertEquals(v.parallel(), v.get(own[j]), 1e-3 * v.parallel(), KeyRateTenors.label(own[j]));
      assertTrue(p.spreadPv01(ids[j], ONE_MILLION, s));
      assertEquals(0.0, s.gross(), 0.0, "a swap has no spread risk");
    }
    // the 12-year sits between the 10 and 20-year pillars and is not one: most of it is in the
    // 10-year bucket and the rest in the 20-year. A spline is not local, so the neighbouring
    // buckets carry small amounts of the opposite sign; their sum is bounded here and printed
    p.pv01(V2Feed.SWAP_12Y, ONE_MILLION, v);
    System.out.println("V2 12Y ladder per 1mm: " + v);
    assertTrue(v.get(5) > 0.5 * v.parallel() && v.get(6) > 0.1 * v.parallel(), v.toString());
    assertTrue(v.gross() - v.get(5) - v.get(6) < 0.45 * v.parallel(), v.toString());
    assertTrue(v.parallel() > 0.0);

    final BondPricer pricer = new BondPricer();
    for (final UsdUniverse.BondSpec b : UNIVERSE.bonds()) {
      final int id = b.instrumentId();
      assertTrue(p.pv01(id, ONE_MILLION, v));
      assertTrue(p.spreadPv01(id, ONE_MILLION, s));
      assertEquals(CurrencyCode.USD, s.currency());
      assertTrue(v.parallel() < 0.0 && s.parallel() < 0.0, "a long bond loses on either rise");
      // the two ladders describe the same bond: their totals agree to the difference between a
      // par-rate bump and a zero-rate bump, some percent at thirty years
      assertEquals(v.parallel(), s.parallel(), 0.10 * Math.abs(v.parallel()), b.securityId());
      // and both are the bond's yield DV01 at its market price, not at the curve's price
      final FixedRateBond bond = bond(b);
      final int settle = p.settlementDate(id);
      final double clean = b.initialMid() * 1e-9;
      final double yield = pricer.yieldFromClean(bond, clean, settle, false);
      // a continuous spread against a semi-annual yield: the DV01s differ by 1 + y/2
      final double dv01 = pricer.dv01(bond, yield, settle, false) * 1e6 / 100.0 * (1.0 + yield / 2);
      assertEquals(-dv01, s.parallel(), 0.005 * dv01, b.securityId());
      for (int k = 0; k < KeyRateTenors.COUNT; k++) {
        // negative where the bond has cash flows; the spline leaves small amounts of the other
        // sign in neighbouring buckets
        assertTrue(v.get(k) < 0.05 * dv01, v.toString());
        assertTrue(s.get(k) <= 0.0, s.toString());
      }
    }
    assertFalse(p.pv01(999, ONE_MILLION, v));
    assertFalse(p.spreadPv01(999, ONE_MILLION, s));
    assertEquals(0.0, v.gross() + s.gross(), 0.0);

    // a 10-year payer swap hedged with the 10-year note: flat in swap-rate DV01, and the whole
    // of the bond's spread ladder left over
    p.pv01(SWAP_10Y, ONE_MILLION, v);
    final double swapDv01 = v.parallel();
    p.pv01(BOND_10Y, ONE_MILLION, v);
    final long hedge = Math.round(ONE_MILLION * swapDv01 / -v.parallel());
    final SpreadRiskBook spreads = new SpreadRiskBook(p, UNIVERSE.instrumentIds());
    assertTrue(spreads.onFill(SWAP_10Y, ONE_MILLION));
    assertEquals(0.0, spreads.gross(), 0.0);
    assertTrue(spreads.onFill(BOND_10Y, hedge));
    assertFalse(spreads.onFill(999, 1L));
    assertEquals(hedge, spreads.position(BOND_10Y));
    assertEquals(0L, spreads.position(999));
    p.pv01(SWAP_10Y, ONE_MILLION, v);
    final Dv01Vector net = new Dv01Vector(CurrencyCode.USD);
    net.copyFrom(v);
    p.pv01(BOND_10Y, hedge, v);
    net.addScaled(v, 1.0);
    assertEquals(0.0, net.parallel(), 1e-6 * swapDv01);
    assertEquals(-swapDv01, spreads.parallel(), 0.10 * swapDv01);
    assertTrue(spreads.spreadPv01(5) < -0.7 * swapDv01, "mostly at 10 years");
    System.out.printf(
        "V2 hedged swap per 1mm: swap-rate DV01 %.4f, spread DV01 %.2f, of which 10Y %.2f%n",
        net.parallel(), spreads.parallel(), spreads.spreadPv01(5));
    assertEquals(-spreads.parallel(), spreads.gross(), 1e-9 * swapDv01);
    f.round(BP);
    spreads.recompute();
    assertEquals(-swapDv01, spreads.parallel(), 0.10 * swapDv01);
  }

  private static FixedRateBond bond(final UsdUniverse.BondSpec b) {
    return new FixedRateBond(b.securityId(), BondConvention.UST, b.bond().couponMantissa() * 1e-11,
        Dates.fromLocalDate(b.bond().datedDate()), Dates.fromLocalDate(b.bond().firstCouponDate()),
        Dates.fromLocalDate(b.bond().maturity()));
  }

  @Test
  void carryAndRollDownComeFromTheLibraryAndTheRepoCurve() {
    final RepoCurve repo = new RepoCurve(new int[] {1, 30}, new double[] {0.0368, 0.0364});
    final V2Feed f = feed(PricingV2.Options.defaults().withFunding(repo, 0.0020, 30));
    for (int k = 0; k < 4; k++) {
      f.round(0.0);
    }
    final PricingV2 p = f.pricing;
    final CarryRoll out = new CarryRoll();
    // a swap: the three par rates of SwapCarryRoll on the binding's curve
    assertTrue(p.carry(SWAP_10Y, out));
    final SwapCarryRoll direct = new SwapCarryRoll(SwapConvention.USD_SOFR, p.businessDate(), 120, 30);
    direct.compute(p.curve().curve(), p.curve().fixings(), p.businessDate());
    assertEquals(30, out.horizonDays());
    assertEquals(direct.carry() / BP, out.carryBp(), 1e-12);
    assertEquals(direct.rollDown() / BP, out.rollDownBp(), 1e-12);
    assertEquals(out.carryBp() + out.rollDownBp(), out.breakEvenBp(), 0.0);
    // fixed 3.70 against an overnight rate of 3.70 compounded daily: a receiver's carry is a
    // little negative; the swap curve rises towards ten years, so the roll-down is positive
    assertTrue(out.carryBp() < 0.0 && out.carryBp() > -0.5, "carry " + out.carryBp());
    assertTrue(out.rollDownBp() > 0.0 && out.rollDownBp() < 0.5, "roll " + out.rollDownBp());

    // a bond: BondCarry at the 30-day general collateral rate less 20 bp of specialness
    assertTrue(p.carry(BOND_10Y, out));
    final UsdUniverse.BondSpec spec = UNIVERSE.bond(BOND_10Y);
    final FixedRateBond bond = bond(spec);
    final int settle = p.settlementDate(BOND_10Y);
    final double clean = spec.initialMid() * 1e-9;
    final int horizon = settle + 30;
    final double price = new BondCarry().carry(bond, clean, settle, horizon, repo.rate(30) - 0.0020);
    final Dv01Vector s = new Dv01Vector(CurrencyCode.USD);
    p.spreadPv01(BOND_10Y, 100L, s);
    assertEquals(price / (-s.parallel() * 100.0), out.carryBp(), 1e-9);
    // 4.125 percent coupon financed near 3.44 percent: about 5 cents a month, 0.6 bp of yield
    assertTrue(out.carryBp() > 0.3 && out.carryBp() < 1.0, "carry " + out.carryBp());
    assertTrue(Math.abs(out.rollDownBp()) < 2.0, "roll " + out.rollDownBp());

    // without specialness the same bond carries less
    final V2Feed general = feed(PricingV2.Options.defaults().withFunding(repo, 0.0, 30));
    for (int k = 0; k < 4; k++) {
      general.round(0.0);
    }
    final CarryRoll gc = new CarryRoll();
    general.pricing.carry(BOND_10Y, gc);
    final double expected = (clean + p.accrued(BOND_10Y)) * 0.0020 * 30 / 360.0;
    assertEquals(expected / (-s.parallel() * 100.0), out.carryBp() - gc.carryBp(), 1e-6);

    assertFalse(p.carry(999, out));
    assertEquals(0, out.horizonDays());
    assertEquals(0.0, out.breakEvenBp(), 0.0);
  }

  /**
   * Review finding A7: the business date comes from the clock with a 17:00 New York cut, and at
   * a date change settlement dates, accrued interest and the curve's schedules move with it.
   * Monday 2 November to Thursday 12 November 2026, across a weekend and Veterans Day.
   */
  @Test
  void theBusinessDateRollsAtTheNewYorkCutAcrossSeveralDays() {
    final V2Feed f = settled();
    final PricingV2 p = f.pricing;
    final BondPricer pricer = new BondPricer();
    final FixedRateBond bond = bond(UNIVERSE.bond(BOND_10Y));
    assertEquals("2026-11-02", Dates.format(p.businessDate()));
    assertEquals("2026-11-03", Dates.format(p.settlementDate(BOND_10Y)));
    assertEquals(-1, p.settlementDate(SWAP_10Y));
    assertTrue(Double.isNaN(p.accrued(SWAP_10Y)));
    final double accruedMonday = p.accrued(BOND_10Y);
    assertEquals(pricer.accrued(bond, Dates.of(2026, 11, 3)), accruedMonday, 0.0);

    // 16:59:59 New York (09:30 plus 7 h 29 min 59 s): still Monday
    f.now = V2Feed.T0 + (7 * 3600 + 29 * 60 + 59) * V2Feed.SECOND - V2Feed.ROUND;
    f.round(0.0);
    assertEquals("2026-11-02", Dates.format(p.businessDate()));
    assertEquals(0, p.dateChanges());
    final double swapBefore = p.fairValue(SWAP_10Y);
    final double bondBefore = p.fairValue(BOND_10Y);
    final int unfed = p.unfedInputs();

    // the first input after 17:00:00: Tuesday
    f.now = V2Feed.T0 + (7 * 3600 + 30 * 60) * V2Feed.SECOND - V2Feed.ROUND;
    f.round(0.0);
    assertEquals("2026-11-03", Dates.format(p.businessDate()));
    assertEquals(1, p.dateChanges());
    assertEquals(Dates.of(2026, 11, 3), p.curve().valuationDate());
    assertEquals("2026-11-04", Dates.format(p.settlementDate(BOND_10Y)));
    // one more day of a 4.125 percent coupon in a 184-day period
    assertEquals(pricer.accrued(bond, Dates.of(2026, 11, 4)), p.accrued(BOND_10Y), 0.0);
    assertEquals(4.125 / 2.0 / 184.0, p.accrued(BOND_10Y) - accruedMonday, 1e-12);
    // the quotes carry over: fair values are continuous across the change, to the day's roll
    assertEquals(swapBefore, p.fairValue(SWAP_10Y), 0.02 * BP);
    assertEquals(bondBefore, p.fairValue(BOND_10Y), 0.02);
    assertEquals(unfed, p.unfedInputs());
    assertEquals(1, p.leaveOneOutReferences(), "a new date starts from a new reference");
    // Monday's overnight rate is now a fixing
    assertTrue(p.curve().fixings().has(Dates.of(2026, 11, 2)));
    assertEquals(0.0370, p.curve().fixings().rate(Dates.of(2026, 11, 2)), 1e-12);

    // a timer with no market data moves the date too: Friday 6 November 17:00 is Monday's business
    final long day = 24 * 3600 * V2Feed.SECOND;
    p.advanceTo(V2Feed.T0 + 4 * day + (7 * 3600 + 30 * 60) * V2Feed.SECOND);
    assertEquals("2026-11-09", Dates.format(p.businessDate()));
    assertEquals("2026-11-10", Dates.format(p.settlementDate(BOND_10Y)));
    assertEquals(pricer.accrued(bond, Dates.of(2026, 11, 10)), p.accrued(BOND_10Y), 0.0);
    // every business day in between has its fixing; the weekend has none
    for (int d = Dates.of(2026, 11, 3); d <= Dates.of(2026, 11, 6); d++) {
      assertTrue(p.curve().fixings().has(d), Dates.format(d));
    }
    assertFalse(p.curve().fixings().has(Dates.of(2026, 11, 7)));

    // Tuesday 10 November 17:00: Wednesday is Veterans Day, so the next business date is Thursday
    f.now = V2Feed.T0 + 8 * day + (7 * 3600 + 30 * 60) * V2Feed.SECOND;
    f.round(0.0);
    assertEquals("2026-11-12", Dates.format(p.businessDate()));
    assertEquals("2026-11-13", Dates.format(p.settlementDate(BOND_10Y)));
    assertEquals(pricer.accrued(bond, Dates.of(2026, 11, 13)), p.accrued(BOND_10Y), 0.0);
    assertTrue(p.curve().builder().maxRepricingError() <= 1e-10);
    // the same instant again changes nothing
    final long changes = p.dateChanges();
    p.advanceTo(f.now);
    assertEquals(changes, p.dateChanges());
  }

  @Test
  void badInputsAreCountedAndDropped() {
    final V2Feed f = settled();
    final PricingV2 p = f.pricing;
    final long fair = p.fairMid(SWAP_10Y);
    final long reprices = p.reprices();
    assertFalse(p.onCurveInput(999, 3_700_000_000L, f.now + 1));
    assertFalse(p.onCurveInput(BOND_10Y, 98_000_000_000L, f.now + 1), "bond prices come from the book");
    // 3.70 sent as 37.0 and as 0.037, and not a number at all
    assertTrue(p.onCurveInput(SWAP_10Y, 37_000_000_000L, f.now + 1));
    assertTrue(p.onCurveInput(SWAP_10Y, 37_000_000L, f.now + 1));
    assertTrue(p.onCurveInput(V2InputIds.sr3(202612), Long.MIN_VALUE, f.now + 1));
    assertEquals(3, p.rejectedInputs());
    assertEquals(0, p.pendingInputs());
    assertEquals(reprices, p.reprices());
    assertEquals(fair, p.fairMid(SWAP_10Y));

    // a first quote is checked for plausibility, since there is no previous value to band it to
    final PricingV2 fresh =
        new PricingV2(UNIVERSE, new ManualClock(V2Feed.T0), PricingV2.Options.defaults().withOvernightRate(0.0370));
    assertTrue(fresh.onCurveInput(V2InputIds.OVERNIGHT, 37_000_000_000L, V2Feed.T0 + 1));
    assertEquals(1, fresh.rejectedInputs());
    assertFalse(fresh.inputFed(0));
    assertTrue(fresh.onCurveInput(V2InputIds.OVERNIGHT, 3_690_000_000L, V2Feed.T0 + 2));
    assertTrue(fresh.inputFed(0));
    assertEquals(1, fresh.pendingInputs(), "one of six fed slots: held");
    assertEquals(0.0370, fresh.curve().quote(0), 1e-12);
    fresh.repriceIfDirty();
    assertEquals(0.0369, fresh.curve().quote(0), 1e-12);

    assertThrows(IllegalArgumentException.class,
        () -> PricingV2.Options.defaults().withFunding(null, 0.0, 0));
    // the ladders are reported on the key-rate tenors: a curve with other risk tenors is refused
    assertThrows(IllegalArgumentException.class,
        () -> PricingV2.Options.defaults().withSpec(SofrCurveSpec.usd().withRiskTenors(24, 120)));
    assertThrows(IllegalArgumentException.class,
        () -> PricingV2.Options.defaults()
            .withSpec(SofrCurveSpec.usd().withRiskTenors(12, 24, 36, 60, 84, 120, 180, 360)));
    assertThrows(IllegalArgumentException.class,
        () -> new PricingV2.Options(SofrCurveSpec.usd(), -1L, 300.0, 0.01, 5e-4, Double.NaN, null,
            0.0, 1, 17, 3e-4));
  }

  /** The same inputs in the same order give the same state to the bit: the replay property. */
  @Test
  void theStateIsAFunctionOfTheInputs() {
    final long[] a = run();
    final long[] b = run();
    assertEquals(a.length, b.length);
    for (int i = 0; i < a.length; i++) {
      assertEquals(a[i], b[i], "value " + i);
    }
  }

  private static long[] run() {
    final V2Feed f = feed();
    final PricingV2 p = f.pricing;
    final java.util.Random rnd = new java.util.Random(11);
    double level = 0.0;
    for (int k = 0; k < 400; k++) {
      level += 0.4 * BP * rnd.nextGaussian();
      f.books(Math.round(-level / BP * 60_000_000.0 / 7_812_500.0) * 7_812_500L);
      f.roundWithSkew(level, rnd.nextInt(p.inputCount()), 0.3 * BP * rnd.nextGaussian());
    }
    final java.util.List<Long> bits = new java.util.ArrayList<>();
    final Dv01Vector v = new Dv01Vector(CurrencyCode.USD);
    final CarryRoll c = new CarryRoll();
    for (final int id : UNIVERSE.instrumentIds()) {
      bits.add(p.fairMid(id));
      bits.add(Double.doubleToLongBits(p.fairValue(id)));
      bits.add(Double.doubleToLongBits(p.modelValue(id)));
      p.pv01(id, ONE_MILLION, v);
      for (int k = 0; k < KeyRateTenors.COUNT; k++) {
        bits.add(Double.doubleToLongBits(v.get(k)));
      }
      p.spreadPv01(id, ONE_MILLION, v);
      bits.add(Double.doubleToLongBits(v.parallel()));
      p.carry(id, c);
      bits.add(Double.doubleToLongBits(c.breakEvenBp()));
    }
    bits.add(p.rebuilds());
    bits.add(p.leaveOneOutReferences());
    bits.add(p.rejectedBondBooks());
    return bits.stream().mapToLong(Long::longValue).toArray();
  }

  /**
   * The hot path: a round of fourteen inputs and a mark, the solve, the leave-one-out values,
   * nine ladders, the Treasury fit and carry. After warm-up it must not allocate, including the
   * rounds in which the leave-one-out reference is retaken (the market drifts 12 bp here).
   */
  @Test
  void repriceDoesNotAllocate() {
    final V2Feed f = feed();
    final PricingV2 p = f.pricing;
    f.round(0.0);
    // the market path is laid out beforehand, so that the measured loop is the binding's calls
    // and nothing else: 240 rounds drifting 12 bp and back, the books two ticks either side
    final SofrCurve c = p.curve();
    final int n = p.inputCount();
    final int states = 240;
    final int[] ids = new int[n];
    final long[][] quotes = new long[states][n];
    final long[] mark = new long[states];
    for (int s = 0; s < states; s++) {
      final double shift = (s < states / 2 ? s : states - s) * 0.1 * BP;
      for (int i = 0; i < n; i++) {
        ids[i] = p.inputId(i);
        quotes[s][i] = f.mantissa(i, V2Feed.rate(c, i) + shift);
      }
      mark[s] = Math.round((V2Feed.swapRate(144) + shift) * 1e11);
    }
    final int[] bondIds = UNIVERSE.bondIds();
    final long[] mids = new long[bondIds.length];
    for (int j = 0; j < mids.length; j++) {
      mids[j] = UNIVERSE.bond(bondIds[j]).initialMid();
    }
    final long tick = UNIVERSE.bondTick();
    final com.sun.management.ThreadMXBean threads =
        (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    final long thread = Thread.currentThread().threadId();
    long now = f.now;
    long before = 0L;
    long references = 0L;
    long reprices = 0L;
    long t0 = 0L;
    final int warmUp = 6_000;
    final int measured = 4_000;
    for (int k = 0; k < warmUp + measured; k++) {
      if (k == warmUp) {
        references = p.leaveOneOutReferences();
        reprices = p.reprices();
        t0 = System.nanoTime();
        before = threads.getThreadAllocatedBytes(thread);
      }
      now += V2Feed.ROUND / 100;
      final int s = k % states;
      final long offset = (k % 5 - 2) * tick;
      for (int j = 0; j < bondIds.length; j++) {
        f.book.set(bondIds[j], mids[j] + offset - tick, ONE_MILLION, mids[j] + offset + tick,
            ONE_MILLION, now);
      }
      for (int i = 0; i < n; i++) {
        p.onCurveInput(ids[i], quotes[s][i], now);
      }
      p.onCurveInput(V2Feed.SWAP_12Y, mark[s], now);
    }
    final long bytes = threads.getThreadAllocatedBytes(thread) - before;
    final long nanos = System.nanoTime() - t0;
    System.out.printf(
        "V2 reprice in the allocation test (coverage agent on): %.1f microseconds a round, %d"
            + " references retaken, %d bytes%n",
        nanos / (double) measured / 1e3, p.leaveOneOutReferences() - references, bytes);
    assertEquals(measured, p.reprices() - reprices);
    assertTrue(p.leaveOneOutReferences() > references, "references were retaken in the window");
    assertEquals(0, p.rejectedInputs());
    assertEquals(0, p.referenceFailures());
    assertEquals(0L, bytes,
        "bytes allocated over " + measured + " reprices (bond books held out: "
            + p.rejectedBondBooks() + ")");
  }

  @Test
  void theSelectionDefaultsToVersionOne() {
    assertEquals(PricingBindings.Version.V1, PricingBindings.defaultVersion());
    final ManualClock clock = new ManualClock(V2Feed.T0);
    final PricingBinding v1 = PricingBindings.create(PricingBindings.Version.V1, UNIVERSE, clock);
    assertInstanceOf(AnalyticsPricing.class, v1);
    // constructed as the desk loop always constructed it: no reference time until a quote arrives
    assertEquals(FairValueSource.NO_VALUE, v1.referenceNanos(SWAP_10Y));
    final PricingBinding v2 = PricingBindings.create(PricingBindings.Version.V2, UNIVERSE, clock);
    assertInstanceOf(PricingV2.class, v2);
    assertEquals(v1.directory().size(), v2.directory().size());
    for (int i = 0; i < v1.directory().size(); i++) {
      assertEquals(v1.directory().byIndex(i).id(), v2.directory().byIndex(i).id());
    }
    // the clock is not read: no reference time until the first timed call, then the start of
    // the business day of that call
    assertEquals(FairValueSource.NO_VALUE, v2.referenceNanos(SWAP_10Y));
    assertTrue(v2.onCurveInput(SWAP_10Y, 3_701_000_000L, V2Feed.T0 + 1));
    assertEquals(BusinessDate.lastCut(V2Feed.T0, BusinessDate.DEFAULT_CUT_HOUR), v2.referenceNanos(SWAP_10Y));
    assertFalse(v1.repriceIfDirty(), "version 1 holds nothing");
    assertTrue(v1.setInput(SWAP_10Y, 3_701_000_000L, V2Feed.T0 + 1), "version 1 applies a set input at once");
    assertEquals(UNIVERSE, v2.universe());
  }
}
