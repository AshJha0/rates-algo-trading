package io.ratesalgo.analytics.curve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.analytics.date.Calendars;
import io.ratesalgo.analytics.date.Dates;
import io.ratesalgo.analytics.swap.FixingHistory;
import io.ratesalgo.analytics.swap.OisSwap;
import io.ratesalgo.analytics.swap.OisSwapPricer;
import io.ratesalgo.analytics.swap.SwapConvention;
import java.util.Random;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;

/**
 * Invariants of the SOFR curve for random markets and valuation dates: levels from 0.5 to 7
 * percent, slopes of up to 2.5 percent either way between the front and thirty years, a hump, and
 * up to 3 bp of independent noise on every quote; any SOFR business day from January 2026 to
 * June 2027.
 */
class SofrCurvePropertiesTest {

  private static final double BP = 1e-4;
  private static final int FIRST = Dates.of(2026, 1, 5);

  private static SofrCurve market(
      final int dayOffset, final double level, final double slope, final double hump,
      final long seed, final boolean futuresToTwoYears) {
    int today = FIRST + dayOffset;
    while (!Calendars.US_SOFR.isBusinessDay(today)) {
      today++;
    }
    final SofrCurveSpec spec =
        futuresToTwoYears
            ? SofrCurveSpec.usd().withFutures(2, 7).withSwapTenors(36, 60, 84, 120, 240, 360)
            : SofrCurveSpec.usd();
    final SofrCurve c = new SofrCurve(today, spec, SofrMarket.fixings(today, level), level);
    final Random rnd = new Random(seed);
    final double[] q = new double[c.inputCount()];
    for (int i = 0; i < q.length; i++) {
      final double t = (c.pillarDate(i) - today) / 365.25;
      final double rate =
          level + slope * (1.0 - Math.exp(-t / 6.0)) + hump * t / 5.0 * Math.exp(1.0 - t / 5.0)
              + 3 * BP * (2.0 * rnd.nextDouble() - 1.0);
      q[i] = c.quoteForRate(i, rate);
    }
    assertTrue(c.update(q), "market did not solve on " + Dates.format(today));
    return c;
  }

  @Property(tries = 60)
  void inputsRoundTripAndDiscountFactorsArePositive(
      @ForAll @IntRange(min = 0, max = 540) final int day,
      @ForAll @IntRange(min = 50, max = 700) final int levelBp,
      @ForAll @IntRange(min = -250, max = 250) final int slopeBp,
      @ForAll @IntRange(min = -40, max = 40) final int humpBp,
      @ForAll @LongRange(min = 0, max = 1_000_000) final long seed,
      @ForAll final boolean futuresToTwoYears) {
    final double level = levelBp * BP;
    // keep the long end above 20 bp: the bootstrap brackets rates from -5 to 15 percent, and
    // deeply negative forwards are not a market this desk prices
    final double slope = Math.max(slopeBp * BP, 0.002 - level);
    final SofrCurve c = market(day, level, slope, humpBp * BP, seed, futuresToTwoYears);
    // every input reprices within the round-trip tolerance of the module
    assertTrue(c.builder().maxRepricingError() <= 1e-10, "" + c.builder().maxRepricingError());
    for (int i = 0; i < c.inputCount(); i++) {
      final CurveInstrument inst = c.builder().instruments().get(i);
      assertEquals(inst.quote(), inst.impliedQuote(c.builder().context()), 1e-10, inst.label());
    }
    final int today = c.valuationDate();
    for (int d = today + 1; d < today + 40 * 365; d += 29) {
      final double df = c.curve().df(d);
      assertTrue(df > 0.0 && Double.isFinite(df), "df " + df + " at " + Dates.format(d));
    }
  }

  @Property(tries = 40)
  void laddersSumToParallelAndTheJacobianAgreesWithABump(
      @ForAll @IntRange(min = 0, max = 540) final int day,
      @ForAll @IntRange(min = 100, max = 600) final int levelBp,
      @ForAll @IntRange(min = -80, max = 200) final int slopeBp,
      @ForAll @LongRange(min = 0, max = 1_000_000) final long seed,
      @ForAll @IntRange(min = 13, max = 340) final int tenorMonths,
      @ForAll @IntRange(min = 0, max = 7) final int bucket,
      @ForAll final boolean futuresToTwoYears) {
    final double level = levelBp * BP;
    final SofrCurve c = market(day, level, slopeBp * BP, 0.0, seed, futuresToTwoYears);
    final int today = c.valuationDate();
    final int n = c.inputCount();
    final OisSwapPricer pricer = new OisSwapPricer();
    final OisSwap swap =
        OisSwap.spot(SwapConvention.USD_SOFR, today, tenorMonths, 1e8, level + 0.003, true);
    final double[] g = new double[n];
    pricer.pvGradient(swap, c.curve(), c.curve(), FixingHistory.EMPTY, today, g, g);
    final double[] ladder = new double[c.riskTenorCount()];
    c.riskLadder(g.clone(), ladder);
    final double parallel = c.riskParallel(g.clone());
    double sum = 0.0;
    for (final double v : ladder) {
      sum += v;
    }
    assertEquals(parallel, sum, Math.abs(parallel) * 1e-9);
    assertTrue(parallel > 0.0, "a payer gains when rates rise");

    // one bucket by bumping: the move that raises that risk par rate alone by a basis point
    final double[] base = new double[n];
    final double[] shift = new double[n];
    final double[] bumped = new double[n];
    for (int i = 0; i < n; i++) {
      base[i] = c.quote(i);
    }
    c.riskShift(bucket, BP, shift);
    final double riskBefore = c.riskParRate(bucket);
    for (int i = 0; i < n; i++) {
      bumped[i] = base[i] + shift[i];
    }
    assertTrue(c.update(bumped));
    final double up = pricer.pv(swap, c.curve(), FixingHistory.EMPTY, today);
    assertEquals(BP, c.riskParRate(bucket) - riskBefore, 1e-4 * BP);
    for (int i = 0; i < n; i++) {
      bumped[i] = base[i] - shift[i];
    }
    assertTrue(c.update(bumped));
    final double down = pricer.pv(swap, c.curve(), FixingHistory.EMPTY, today);
    // AD against a 1 bp central bump: 1e-4 of the parallel DV01 (API_SPEC.md Section 7.2)
    assertEquals(ladder[bucket], (up - down) / 2.0, 1e-4 * Math.abs(parallel));
  }
}
