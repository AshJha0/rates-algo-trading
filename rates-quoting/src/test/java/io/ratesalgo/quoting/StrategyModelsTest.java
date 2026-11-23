package io.ratesalgo.quoting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ratesalgo.api.CurrencyCode;
import io.ratesalgo.api.Instrument;
import io.ratesalgo.api.ProductType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.SplittableRandom;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The Round 5 pricing parts on their own: normal tails, ticks, the cover model, markouts, priors. */
class StrategyModelsTest {

  private static final Instrument SWAP10 =
      Instrument.swap(303, "SOFR10", CurrencyCode.USD, 10.0, 8.2, 203, 0.9, 5_000_000_000L);
  private static final Instrument BOND2 = Instrument.bond(101, "UST2", CurrencyCode.USD, 2.0, 2_500_000_000L);

  // ---------------------------------------------------------------- normal distribution

  @Test
  void theNormalDistributionIsAccurateInBothTails() {
    assertEquals(0.5, NormalDist.cdf(0.0), 1e-15);
    assertEquals(0.8413447460685429, NormalDist.cdf(1.0), 1e-14);
    assertEquals(0.0013498980316301, NormalDist.cdf(-3.0), 1e-15);
    assertEquals(6.220960574271785e-16, NormalDist.cdf(-8.0), 1e-7 * 6.2e-16, "relative accuracy far out");
    assertEquals(1.0, NormalDist.cdf(40.0), 0.0);
    assertEquals(0.0, NormalDist.cdf(-40.0), 0.0);
    assertEquals(NormalDist.cdf(-2.5), NormalDist.upper(2.5), 0.0);
    assertEquals(0.3989422804014327, NormalDist.pdf(0.0), 1e-16);
    // the inverse Mills ratio: 0.7979 at 0, about z + 1/z far out
    assertEquals(Math.sqrt(2.0 / Math.PI), NormalDist.hazard(0.0), 1e-12);
    assertEquals(10.0 + 0.1, NormalDist.hazard(10.0), 0.01);
    assertEquals(40.0 + 1.0 / 40.0, NormalDist.hazard(40.0), 1e-12);
  }

  // ---------------------------------------------------------------- ticks

  @Test
  void theSimulatorTicksAreAHundredTwentyEighthAndAHundredthOfABasisPoint() {
    final VenueTickTable t = VenueTickTable.simulatorDefaults();
    assertEquals(7_812_500L, t.tick(BOND2));
    assertEquals(100_000L, t.tick(SWAP10));
    t.instrument(BOND2.id(), 3_906_250L);
    assertEquals(3_906_250L, t.tick(BOND2), "a per-instrument tick overrides the type's");
    t.instrument(BOND2.id(), 0L);
    assertEquals(7_812_500L, t.tick(BOND2));
    t.type(ProductType.SWAP, 0L);
    assertEquals(0L, t.tick(SWAP10));
    assertThrows(IllegalArgumentException.class, () -> t.type(ProductType.BOND, -1L));
    assertEquals(123L, VenueTickTable.roundForDealer(123L, 0L, true), "no tick: unchanged");
  }

  @Property(tries = 500)
  void roundingIsOnTheGridInTheDealersFavourAndWithinOneTick(
      @ForAll @LongRange(min = -200_000_000_000L, max = 200_000_000_000L) final long price,
      @ForAll @LongRange(min = 1L, max = 100_000_000L) final long tick) {
    final long bid = VenueTickTable.roundForDealer(price, tick, true);
    final long ask = VenueTickTable.roundForDealer(price, tick, false);
    assertEquals(0L, Math.floorMod(bid, tick));
    assertEquals(0L, Math.floorMod(ask, tick));
    assertTrue(bid <= price && price - bid < tick, "we buy no higher than asked");
    assertTrue(ask >= price && ask - price < tick, "we sell no lower than asked");
  }

  // ---------------------------------------------------------------- cover model

  @Test
  void thePriorIsTheBestOfTheCalibratedCompetitors() {
    final double[] mv = new double[2];
    CoverModel.minOfAnswering(1, 1.0, mv);
    assertEquals(0.0, mv[0], 1e-6);
    assertEquals(1.0, mv[1], 1e-5);
    // the minimum of two standard normals: mean -1/sqrt(pi), variance 1 - 1/pi
    CoverModel.minOfAnswering(2, 1.0, mv);
    assertEquals(-1.0 / Math.sqrt(Math.PI), mv[0], 1e-6);
    assertEquals(1.0 - 1.0 / Math.PI, mv[1], 1e-5);
    final CoverModel m = CoverModel.calibrated();
    final int three = CoverModel.cell(SWAP10, 1.0, 3);
    final int five = CoverModel.cell(SWAP10, 1.0, 5);
    assertTrue(m.predictedCoverBp(three) < 0.1827, "best of two is inside the median");
    assertTrue(m.predictedCoverBp(five) < m.predictedCoverBp(three), "more dealers, tighter cover");
    final int bond = CoverModel.cell(BOND2, 1.0, 3);
    assertEquals(0.5 * m.predictedCoverBp(three), m.predictedCoverBp(bond), 1e-12, "bonds at half the swap median");
    assertEquals(0L, m.observations(three));
  }

  @Test
  void cellsSeparateTenorSizeDealersAndProduct() {
    final int base = CoverModel.cell(SWAP10, 1.0, 3);
    assertTrue(base >= 0 && base < CoverModel.CELLS);
    assertTrue(CoverModel.cell(SWAP10, 0.1, 3) != base);
    assertTrue(CoverModel.cell(SWAP10, 10.0, 3) != base);
    assertTrue(CoverModel.cell(SWAP10, 1.0, 4) != CoverModel.cell(SWAP10, 1.0, 5));
    assertEquals(CoverModel.cell(SWAP10, 1.0, 5), CoverModel.cell(SWAP10, 1.0, 9));
    assertTrue(CoverModel.cell(BOND2, 1.0, 3) != CoverModel.cell(SWAP10, 1.0, 3));
    final Instrument swap30 = Instrument.swap(304, "S30", CurrencyCode.USD, 30.0, 17.0, 204, 0.9, 1L);
    assertEquals(CoverModel.CELLS - 2, CoverModel.cell(swap30, 100.0, 7), "last tenor, size and dealer bucket; swap");
  }

  @Test
  void winProbabilityFallsWithDistanceAndThroughFairIsTheFillProbability() {
    final CoverModel m = CoverModel.calibrated();
    final int c = CoverModel.cell(SWAP10, 1.0, 4);
    assertEquals(0.95, m.winProbability(c, 0.95, 0.0), 0.0);
    assertEquals(0.95, m.winProbability(c, 0.95, -0.3), 0.0);
    double last = 1.0;
    for (double d = 0.01; d < 2.0; d += 0.01) {
      final double p = m.winProbability(c, 0.95, d);
      assertTrue(p <= last + 1e-15, "monotone at " + d);
      last = p;
    }
    assertTrue(m.winProbability(c, 0.95, 2.0) < 1e-3, "past the client's reserve nobody trades");
  }

  @Property(tries = 200)
  void theOptimalQuoteIsNeverBelowBreakEvenAndRisesWithIt(
      @ForAll @DoubleRange(min = -0.5, max = 1.5) final double breakEven, @ForAll @DoubleRange(min = 0.0, max = 0.3) final double more) {
    final CoverModel m = CoverModel.calibrated();
    final int c = CoverModel.cell(SWAP10, 1.0, 4);
    final double d = m.optimal(c, 0.95, breakEven);
    final double d2 = m.optimal(c, 0.95, breakEven + more);
    assertTrue(d >= breakEven, "never below break-even");
    assertTrue(d2 >= d - 1e-6, "a higher cost never tightens the quote: " + d + " then " + d2);
    // it is a maximum: nearby distances above break-even do no better
    final double e = m.expected(c, 0.95, breakEven, d);
    assertTrue(e >= m.expected(c, 0.95, breakEven, d + 0.01) - 1e-9);
    assertTrue(d - 0.01 < breakEven || e >= m.expected(c, 0.95, breakEven, d - 0.01) - 1e-9);
  }

  @Test
  void aCostAboveEveryPlausibleCoverWidensInsteadOfPassing() {
    final CoverModel m = CoverModel.calibrated();
    final int c = CoverModel.cell(SWAP10, 1.0, 3);
    final double d = m.optimal(c, 0.95, 3.0);
    assertTrue(Double.isFinite(d) && d >= 3.0);
  }

  @Test
  void exactCoversTeachTheTrueDistribution() {
    final CoverModel m = CoverModel.calibrated();
    final int c = CoverModel.cell(SWAP10, 1.0, 3);
    final SplittableRandom rng = new SplittableRandom(5);
    final double trueMu = Math.log(0.30);
    final double trueSigma = 0.25;
    for (int i = 0; i < 20_000; i++) {
      m.observeExact(c, Math.exp(trueMu + trueSigma * gaussian(rng)));
    }
    assertEquals(trueMu, m.mu(c), 0.05);
    assertEquals(trueSigma, m.sigma(c), 0.05);
    assertEquals(20_000L, m.observations(c));
    m.observeExact(c, -1.0);
    m.observeExact(c, Double.POSITIVE_INFINITY);
    assertEquals(20_000L, m.observations());
  }

  @Test
  void censoredOutcomesAloneMoveTheCoverTowardTheTruth() {
    // wins without a cover (B above d) and losses without one (B below d) at random distances
    final CoverModel m = CoverModel.calibrated();
    final int c = CoverModel.cell(SWAP10, 1.0, 3);
    final double prior = m.mu(c);
    final SplittableRandom rng = new SplittableRandom(11);
    final double trueMu = Math.log(0.30);
    for (int i = 0; i < 40_000; i++) {
      final double b = Math.exp(trueMu + 0.3 * gaussian(rng));
      final double d = 0.05 + 0.6 * rng.nextDouble();
      if (b > d) {
        m.observeAbove(c, d);
      } else {
        m.observeBelow(c, d);
      }
    }
    assertTrue(prior < trueMu - 0.2, "the prior starts well inside the truth");
    assertEquals(trueMu, m.mu(c), 0.12, "censored learning lands near the truth from " + prior);
    m.observeAbove(c, 0.0);
    m.observeBelow(c, Double.NaN);
    assertEquals(40_000L, m.observations(c));
  }

  @Test
  void theConstructorChecksThePrior() {
    assertThrows(IllegalArgumentException.class, () -> new CoverModel(0.0, 0.5, 0.5, 0.9, 2.5, 0.4, 10));
    assertThrows(IllegalArgumentException.class, () -> new CoverModel(0.2, 0.5, 0.5, 1.1, 2.5, 0.4, 10));
    assertThrows(IllegalArgumentException.class, () -> new CoverModel(0.2, 0.5, 0.5, 0.9, 2.5, 0.4, 0.5));
    assertThrows(IllegalArgumentException.class, () -> new CoverModel(0.2, 0.0, 0.5, 0.9, 2.5, 0.4, 10));
    assertThrows(IllegalArgumentException.class, () -> new CoverModel(0.2, 0.5, 0.0, 0.9, 2.5, 0.4, 10));
    assertThrows(IllegalArgumentException.class, () -> new CoverModel(0.2, 0.5, 0.5, 0.9, 0.0, 0.4, 10));
    assertThrows(IllegalArgumentException.class, () -> new CoverModel(0.2, 0.5, 0.5, 0.9, 2.5, 0.0, 10));
  }

  // ---------------------------------------------------------------- priors and markouts

  @Test
  void tierPriorsFollowTheCalibrationAndTheDriftShape() {
    final TierPriors p = TierPriors.calibrated();
    assertEquals(0.25 * 0.118, p.adverseBp(TierPriors.TIER_A), 1e-15);
    assertEquals(0.975 * 0.118, p.adverseBp(TierPriors.TIER_C), 1e-15);
    assertEquals(0.059, p.adverseBp(99), 1e-15, "unknown tier: the all-client mean");
    assertEquals(0.95, p.fillProbability(TierPriors.TIER_B), 0.0);
    assertEquals(0.95, p.fillProbability(99), 0.0);
    final double atTau = p.adverseBp(TierPriors.TIER_B, 10_000_000_000L);
    assertEquals(0.60 * 0.118 * (1.0 - Math.exp(-1.0)), atTau, 1e-12);
    assertTrue(p.adverseBp(TierPriors.TIER_B, 300_000_000_000L) > 0.999 * p.adverseBp(TierPriors.TIER_B));
    assertThrows(IllegalArgumentException.class, () -> p.tier(5, -0.1, 0.9));
    assertThrows(IllegalArgumentException.class, () -> p.tier(5, 0.1, 0.0));
    assertThrows(IllegalArgumentException.class, () -> new TierPriors(0.1, 0.9, 0L));
    final TierPriors full = new TierPriors(0.0, 1.0, 1L);
    for (int t = 0; t < 16; t++) {
      full.tier(t, 0.0, 1.0);
    }
    assertThrows(IllegalStateException.class, () -> full.tier(16, 0.0, 1.0));
  }

  @Test
  void markoutsShrinkFromTheTierPriorToTheClientsOwnMean() {
    final TierPriors priors = TierPriors.calibrated();
    final MarkoutStore s = new MarkoutStore(4, 500.0, 20.0, priors);
    final int h = MarkoutStore.horizonIndex(30_000_000_000L);
    final double prior = priors.adverseBp(TierPriors.TIER_A, MarkoutStore.horizonNanos(h));
    assertEquals(prior, s.adverseBp(7, true, TierPriors.TIER_A, h), 0.0, "no history: the prior");
    for (int i = 0; i < 20; i++) {
      s.record(7, true, h, 0.5);
    }
    assertEquals(0.5, s.mean(7, true, h), 1e-12);
    assertEquals(20L, s.count(7, true, h));
    assertEquals(0.5 * (0.5 + prior), s.adverseBp(7, true, TierPriors.TIER_A, h), 1e-12, "n = n0: half and half");
    assertEquals(prior, s.adverseBp(7, false, TierPriors.TIER_A, h), 0.0, "the other side has no history");
    s.record(7, true, h, Double.NaN);
    assertEquals(20L, s.count(7, true, h));
    assertEquals(1, s.clients());
    assertEquals(priors, s.priors());
    final MarkoutStore noShrink = new MarkoutStore(1, 1.0, 0.0, priors);
    assertEquals(prior, noShrink.adverseBp(1, true, TierPriors.TIER_A, h), 0.0);
    noShrink.record(1, true, h, 0.2);
    noShrink.record(1, true, h, 0.4);
    assertEquals(0.4, noShrink.adverseBp(1, true, TierPriors.TIER_A, h), 1e-12, "window 1: the last markout");
    noShrink.record(2, true, h, 1.0);
    assertEquals(1L, noShrink.clientsDropped());
    assertEquals(0L, noShrink.count(2, true, h));
    assertEquals(0.0, noShrink.mean(2, true, h));
  }

  @Test
  void theStoreSurvivesASaveAndLoadAndItsFileIsDeterministic(@TempDir final Path dir) throws IOException {
    final MarkoutStore s = new MarkoutStore(16, 500.0, 20.0, TierPriors.calibrated());
    for (int h = 0; h < MarkoutStore.H; h++) {
      s.record(2003, true, h, 0.01 * (h + 1));
      s.record(2001, false, h, -0.003 * h);
    }
    s.record(2002, true, 0, 1.0 / 3.0);
    final Path file = dir.resolve("sub/markouts.txt");
    s.save(file);
    final byte[] first = Files.readAllBytes(file);
    s.save(file);
    assertEquals(new String(first, StandardCharsets.UTF_8), Files.readString(file), "same store, same bytes");
    final String text = Files.readString(file);
    assertTrue(text.indexOf("2001 S") < text.indexOf("2002 B") && text.indexOf("2002 B") < text.indexOf("2003 B"), text);
    final MarkoutStore back = new MarkoutStore(16, 500.0, 20.0, TierPriors.calibrated()).load(file);
    for (int h = 0; h < MarkoutStore.H; h++) {
      assertEquals(s.mean(2003, true, h), back.mean(2003, true, h), 0.0);
      assertEquals(s.count(2001, false, h), back.count(2001, false, h));
    }
    assertEquals(1.0 / 3.0, back.mean(2002, true, 0), 0.0);
    assertEquals(0, new MarkoutStore(4, 500.0, 20.0, TierPriors.calibrated()).load(dir.resolve("none")).clients());
    assertFalse(Files.exists(dir.resolve("sub/markouts.txt.tmp")));
  }

  @Test
  void aDamagedStoreFileIsRefused(@TempDir final Path dir) throws IOException {
    final Path file = dir.resolve("m.txt");
    for (final String bad : new String[] {"1 X 1000 1 0.1", "1 B 1000 1", "1 B 7 1 0.1", "1 B 1000 0 0.1", "1 B 1000 1 NaN", "x B 1000 1 0.1"}) {
      Files.writeString(file, "# header\n\n" + bad + "\n");
      assertThrows(IOException.class, () -> new MarkoutStore(4, 500.0, 20.0, TierPriors.calibrated()).load(file), bad);
    }
    Files.writeString(file, "1 B 1000 1 0.1\n2 B 1000 1 0.1\n");
    assertThrows(IOException.class, () -> new MarkoutStore(1, 500.0, 20.0, TierPriors.calibrated()).load(file), "capacity");
    assertThrows(IllegalArgumentException.class, () -> new MarkoutStore(0, 500.0, 20.0, TierPriors.calibrated()));
    assertThrows(IllegalArgumentException.class, () -> new MarkoutStore(1, 0.5, 20.0, TierPriors.calibrated()));
    assertThrows(IllegalArgumentException.class, () -> new MarkoutStore(1, 5.0, -1.0, TierPriors.calibrated()));
    assertThrows(IllegalArgumentException.class, () -> MarkoutStore.horizonIndex(2L));
  }

  private static double gaussian(final SplittableRandom rng) {
    // Box-Muller; one draw per call is enough here
    return Math.sqrt(-2.0 * Math.log(1.0 - rng.nextDouble())) * Math.cos(2.0 * Math.PI * rng.nextDouble());
  }
}
